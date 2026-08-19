# Android Framework 面试题 · 输入系统全链路源码走读（2026-08-16）

> 系列第 **31 篇** / 累计约 **201 专题**。
> 落点：把被反复点到、却从未独立成篇的 **native Input 子系统** 焊成一条端到端链路 —— 内核 `evdev` → `EventHub` → `InputReader` → `InputDispatcher` → `InputChannel` → `ViewRootImpl` → `View` 树 → Input ANR。它正好把用户显式列出的「WMS/View 事件分发 + linux kernel/drivers 驱动」三块串起来，并补齐第 8/12 篇（Java 侧事件分发三方法）与第 8/11 篇（Perfetto input 延迟测量）之间的「native 管道」真空。
> 横向衔接：第 8/12 篇讲过 `dispatchTouchEvent/onInterceptTouchEvent/onTouchEvent` + `requestDisallowIntercept` + CANCEL（Java 责任链）；第 8/11 篇讲过用 `android_input_events` 表测 input 四段延迟；第 8/10 篇讲过 Pointer Capture；第 8/22 篇讲过跨设备 CDM/Handoff。本篇是它们的「管道层」底座，不是重复。

---

## 0. 当日热点锚定（为什么今天深挖 Input 全链路）

| 信号 | 内容 | 对面试的影响 |
| --- | --- | --- |
| A17 QPR2 Beta 2 | 2026-08-03/06 推送，build `CP41.260717.006`（开发者站 `CP41.260701.006`），内部代号由 `CinnamonBun` 切到 `DEV`；stable 预计 2026-12；Pixel 6/6Pro 退出（EOL）。无行为变更，纯打磨。 | 经典八股仍是最高频考点；但 Input 子系统的**真实 bug** 最能检验「是否真读懂过 `InputDispatcher`」。 |
| **#516836306 多指拖拽丢触摸**（真题现场溯源） | QPR2 Beta 1 修复清单：「Starting a multi-finger drag-and-drop could stop the source app from receiving further touch events」。 | 这正是本篇 §4/§9 讲的「多指 split + 窗口 touch 归属转移」竞态 —— 考官用这种真实 bug 考你 `TouchState::split()` 与 `finishDispatchCycle` 时序。 |
| #527376569 窗口模糊渲染 | 窗口级 blur 失效 + 重启后开关被重置。 | 与 WMS/SF 合成相关，本篇 §6 提一句窗口 `touchableRegion`/`focusedWindow` 的关联即可。 |
| 面试死亡陷阱题 | 第三方题库（2026）仍将 Binder/Handler/AMS/WMS 列为 90%/80%/70% 出现概率；Input 系统常作「区分业务 CRUD 与底层高级工程师」的分水岭题。 | 光背 `onInterceptTouchEvent` 不够，考官会追到「InputChannel 为什么用 socketpair 不用 Binder」「Input ANR 的 5 秒计时器在哪个线程」。 |

**结论**：当用户问「WMS / View 事件分发 / drivers 驱动」时，真正能拉开差距的是把 native 输入管道讲透。本篇即为此而生。

---

## 1. Input 子系统全景架构与线程模型

### 1.1 分层全景（从硬件中断到 App `onTouchEvent`）

```
[Kernel]                                    [system_server]                         [App 进程]
---------                                   ----------------                        ------------
input 硬件(TP)                              InputManagerService(Java)               ActivityThread
  | 中断                                     |  mNative(NativeInputManager)           |
  v                                         v                                        v
input.c / evdev.c                           InputManager(native)                    ViewRootImpl
  -> /dev/input/eventN (input_event)          |  new InputReader + InputReaderThread  |  WindowInputEventReceiver
        ^                                     |  new InputDispatcher + DispatcherThread|    (InputEventReceiver)
        |  read()                             v                                        |
  EventHub.getEvents()                  InputReader --notifyMotion--> InputDispatcher --> InputChannel(socketpair)
  (inotify+/dev/input, epoll)            (InputMapper)            (焦点/触摸窗口查找)        |  consume
        |                                     ^                       | publishMotionEvent    v
        +-------------------------------------+                       v                InputStage 责任链
   RawEvent 上抛                               (QueuedInputListener)   Connection.inputPublisher   |
                                                                                              v
                                                                                   DecorView.dispatchTouchEvent
                                                                                              |
                                                                                              v
                                                                                   ViewGroup/View 树 (8/12 Java 侧)
```

三条关键线程（都在 `system_server`，由 `InputManager::initialize()` 拉起）：
- **`InputReader` 线程**（`"InputReader"`）：死循环 `loopOnce()`，从 `EventHub` 读 raw event，经 `InputMapper` 加工成 `NotifyMotionArgs`，通过 `QueuedInputListener` 上抛给 `InputDispatcher`。
- **`InputDispatcher` 线程**（`"InputDispatcher"`）：死循环 `dispatchOnce()`，从 `mInboundQueue` 取事件，找目标窗口，经 `InputChannel` 下发给 App，并等待 App 回送 `FINISHED` 信号。
- **`InputManagerService` 主线程**（Java）：只负责策略回调（`interceptKeyBeforeQueueing` 等）、窗口列表下发（`setInputWindows`）。

> AOSP 14 关键路径：`frameworks/native/services/inputflinger/InputManager.cpp`、`reader/InputReader.cpp`、`reader/EventHub.cpp`、`dispatcher/InputDispatcher.cpp`、`dispatcher/InputDispatcher.h`。

### 1.2 面试高频追问

- **Q：InputReader 和 InputDispatcher 为什么要拆成两个线程？**
  **A**：职责不同、阻塞点不同。`InputReader` 阻塞在 `EventHub.getEvents()` 的 `epoll_wait`（等内核 evdev 数据），`InputDispatcher` 阻塞在「等 App 回 `FINISHED`」。如果合一个线程，App 主线程卡死会直接让整个输入读取停摆（连物理按键都进不来）。拆开后用 `QueuedInputListener` + `mInboundQueue` 解耦，Reader 永远能读、Dispatcher 永远能派。
- **Q：事件在 Reader 和 Dispatcher 之间是怎么传的？是 Binder 吗？**
  **A**：不是 Binder。`InputReader` 调 `mQueuedListener->flush()`（`QueuedInputListener`），把 `NotifyArgs` 通过 `InputDispatcher::notifyMotion/notifyKey` 入队到 Dispatcher 的 `mInboundQueue`——这是 **同一进程内（都在 system_server）的函数调用 + 队列**，零 IPC。真正的跨进程发生在 Dispatcher → App 这一段，而那一截用的也不是 Binder，是 **`InputChannel`（socketpair）**（见 §5）。

---

## 2. 事件源头：内核 evdev → EventHub

### 2.1 内核侧（linux kernel / drivers 驱动）

触摸 IC 产生硬件中断 → `drivers/input/touchscreen/*` 的驱动调用 `input_event()`（`drivers/input/input.c`）→ 经 `input_handle_event()` → `evdev.c` 的 `evdev_event()` 把数据填入 `evdev_client` 环形缓冲，并唤醒等待 `read()` 的用户态。用户态看到的是 `/dev/input/eventN` 字符设备，每次 `read()` 拿到一个或多个 `struct input_event`：

```c
// include/uapi/linux/input.h
struct input_event {
    struct timeval time;   // 时间戳
    __u16 type;            // EV_KEY / EV_ABS / EV_SYN ...
    __u16 code;            // ABS_MT_POSITION_X / BTN_TOUCH / KEY_HOME ...
    __u32 value;           // 坐标 / 状态
};
```

> A14 baseline 内核分支 `android14-6.1`（GKI 2.0）。`evdev` 是 vendor-independent 内核模块，EventHub 打开的 `/dev/input/eventN` 由 GKI 统一提供，厂商驱动只负责喂 `input_event`。

### 2.2 EventHub：用户态的「设备发现 + 读事件」

`frameworks/native/services/inputflinger/reader/EventHub.cpp` 是 native 侧第一个接触硬件事件的类：

- **设备发现**：`scanDirLocked("/dev/input")` 遍历节点；并用 `inotify_init()` 监控 `/dev/input` 目录的增删（`mINotifyFd`），热插拔（键盘、鼠标、TP）即时生效。
- **打开设备**：`openDeviceLocked()` 对每个 `eventN` 调 `open()`，再 `ioctl(EVIOCGPROP)` / `EVIOCGBIT` 读设备能力，据此归类（`INPUT_DEVICE_CLASS_TOUCH` / `KEYBOARD` / `CURSOR` / `TOUCH_PAD` …）并构造 `InputDeviceIdentifier`。
- **读事件**：`getEvents(int timeoutMillis, RawEvent* buffer, size_t bufferSize)` 是核心循环。它把 `mINotifyFd` 与所有 `device->fd` 一起 `epoll_wait`（**注意：是 epoll，不是 Binder、不是 Looper 的 epoll**，EventHub 自己维护 `mEpollFd`）。有数据时 `read(device->fd, &event, sizeof(input_event))` 转成 `RawEvent` 填入 buffer，返回给 `InputReader::loopOnce()`。

```cpp
// EventHub.cpp (节选逻辑)
size_t EventHub::getEvents(int timeoutMillis, RawEvent* buffer, size_t bufferSize) {
    // 1. 先处理 inotify 设备增删、扫描到的设备
    // 2. 否则 epoll_wait(mEpollFd, mPendingEventItems, ..., timeoutMillis)
    // 3. 对可读的 device->fd 调用 read()，逐条转成 RawEvent
}
```

### 2.3 面试高频追问

- **Q：EventHub 怎么知道有新设备插拔？**
  **A**：`inotify` 监听 `/dev/input` 目录 + `epoll` 复用所有 `eventN` fd。`getEvents()` 在 `epoll_wait` 返回后，对 inotify 事件调 `readNotifyLocked()` 做 `openDeviceLocked/closeDeviceLocked`，对设备 fd 调 `read()` 取 `input_event` 转 `RawEvent`。
- **Q：`RawEvent` 里的坐标是什么坐标系？**
  **A**：是驱动原始坐标系（TP 上报的 raw 值，可能带偏移/翻转/缩放）。真正映射成屏幕坐标是在 `TouchInputMapper::cookAndDispatch()` 里按 `Viewport`（由 `InputManagerService` 下发的显示区域 + 旋转）换算，最后才是 App 收到的 `MotionEvent` 的 `getX()/getY()`。

---

## 3. InputReader 线程：raw event → InputMapper → notifyMotion

### 3.1 流水线

```
InputReader.loopOnce()
  -> mEventHub->getEvents(...)            // 拿到一批 RawEvent
  -> processEventsLocked(rawEvents, count)
       -> for device: device->process(rawEvents, count)
            -> InputDevice::process()     // 按 mapper 分发
                 -> TouchInputMapper::process() / KeyboardInputMapper::process() / ...
  -> mQueuedListener->flush()             // 把加工结果上抛给 Dispatcher
```

`InputDevice` 内部挂多个 `InputMapper`（一个设备可有键盘+触摸+按键），每个 mapper 把自己的 raw 事件转成语义事件：
- `TouchInputMapper::process()` → `cookAndDispatch()` → `dispatchTouch()` → `dispatchMotion()`，产出 `NotifyMotionArgs`（含 pointerCount、各 pointer 的 id/x/y/压力、action）。
- `KeyboardInputMapper::processKey()` 产出 `NotifyKeyArgs`。
- `TouchpadInputMapper`（触控板）、`CursorInputMapper`（鼠标）、`SwitchInputMapper`（合盖/耳机）、`SensorInputMapper`（传感器）等同理。

> 关键文件：`reader/mapper/TouchInputMapper.cpp`、`KeyboardInputMapper.cpp`、`TouchpadInputMapper.cpp`。

### 3.2 多指与 split（呼应 §9 与 #516836306）

`TouchInputMapper` 维护 `TouchState mCurrentTouch`（当前所有按下的手指）。当窗口允许 `splitTouch` 时，`dispatchTouch()` 会调 `TouchState::split()`，把不同 pointer 分配到不同窗口（例如状态栏一个手指、App 一个手指，两个窗口各自收到一份只含自己 pointer 的 `MotionEvent`）。这正是多指交互的底层支撑，也是 #516836306「多指拖拽后源 App 收不到后续触摸」这类 bug 的现场。

### 3.3 面试高频追问

- **Q：`notifyMotion` 是怎么到 Dispatcher 的？**
  **A**：`InputReader` 持有 `InputListenerInterface* mQueuedListener`（`QueuedInputListener`）。mapper 调 `getListener()->notifyMotion(&args)`，参数先缓存进 `mQueuedListener` 的队列；`loopOnce()` 末尾 `mQueuedListener->flush()` 逐个调 `InputDispatcher::notifyMotion()`，入 `mInboundQueue`。全程同进程内调用，无 IPC。

---

## 4. InputDispatcher 线程：找窗口 → 下发 → 等回执

### 4.1 派发主循环

```
InputDispatcher.dispatchOnce()
  -> dispatchOnceInnerLocked(&nextWakeupTime)
       -> if (!mPendingEvent) mPendingEvent = mInboundQueue.dequeue();
       -> dispatchMotionLocked()                    // 区分 key / motion
            -> findTouchedWindowTargetsLocked()      // 多点：按 touchableRegion 找命中的窗口
            -> findFocusedWindowTargetsLocked()      // 单点/key：找焦点窗口
            -> 若目标未就绪 -> handleTargetsNotReadyLocked() -> (可能) onANRLocked()  // §8
            -> dispatchEventLocked() -> prepareDispatchCycleLocked()
                 -> startDispatchCycleLocked()
                      -> connection->inputPublisher.publishMotionEvent(...)  // 经 InputChannel 写 socket
```

### 4.2 目标窗口从哪来

`InputDispatcher` 不直接问 WMS「谁在前台」，而是 `InputManagerService` 在每次窗口变化时通过 `setInputWindows()` 把「窗口列表（含 focus 标记、touchableRegion、token、displayId）」推给 native。Dispatcher 缓存成 `mWindowHandles`，`findFocusedWindowTargetsLocked()` 取 `mFocusedWindowHandle`，`findTouchedWindowTargetsLocked()` 按 `MotionEvent` 坐标命中哪个窗口的 `touchableRegion`。

> 易错点：焦点窗口（`focusedWindowHandle`）是 **Input 子系统自己维护的副本**，由 WMS 经 IMS 推送；不是每次派发都跨进程查 WMS。这也是为什么「窗口刚 add 但 Input 还没收到 `setInputWindows`」时事件会短暂丢失。

### 4.3 Connection 与回执

每个注册到 Dispatcher 的窗口对应一个 `InputDispatcher::Connection`（持有 `InputPublisher` + `InputChannel`）。`publishMotionEvent()` 把事件写进 socket 后，Dispatcher 并不立即丢弃，而是把 `DispatchEntry` 挂到 `connection->outboundQueue` 等待 App 回执；App 消费完调 `finishInputEvent()` → native `sendFinishedSignal()` → Dispatcher `handleReceiveCallback()` → `finishDispatchCycleLocked()` 才把 entry 从队列摘除、释放窗口「正在处理」状态。

> 关键：`finishDispatchCycleLocked` 是 Input ANR 计时器的「停表点」——见 §8。

### 4.4 面试高频追问

- **Q：`mInboundQueue` 和 `connection->outboundQueue` 有什么区别？**
  **A**：`mInboundQueue` 是 Dispatcher 的「待派发」全局队列（所有窗口/所有 display 的事件都先入这里）；`outboundQueue` 是**每个 Connection 私有**的「已发出、等 App 回执」队列。前者满=输入堆积（通常意味着下游处理慢），后者满且迟迟不回 `FINISHED`=触发 Input ANR。
- **Q：一个事件会同时发给多个窗口吗？**
  **A**：普通触摸只发命中窗口（单指）或 split 后的若干窗口（多指 + splitTouch）；`isSplit()` 决定。`ACTION_OUTSIDE` 用于窗口外点击（不进入窗口）。

---

## 5. InputChannel 深剖：为什么是 socketpair 而非 Binder（核心易错）

### 5.1 源码事实

`frameworks/native/libs/input/InputTransport.cpp`：

```cpp
status_t InputChannel::openInputChannelPair(const String8& name,
        sp<InputChannel>& outServerChannel, sp<InputChannel>& outClientChannel) {
    int fds[2];
    // ★ 关键：Unix 域套接字，SOCK_SEQPACKET 保序、保消息边界
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, fds)) { ... }
    // 调大收发缓冲
    setsockopt(fds[0], SOL_SOCKET, SO_SNDBUF, &size, sizeof(size));
    ...
    outServerChannel = new InputChannel(name, fds[0]);   // server 端 → Dispatcher
    outClientChannel = new InputChannel(name, fds[1]);   // client 端 → App
}
```

- **下发**：`InputPublisher::publishMotionEvent()` → `InputChannel::sendMessage()` → `send(mFd, &msg, sizeof(InputMessage), MSG_DONTWAIT)`。`InputMessage` 内含 `MotionEvent` 序列化后的字段（action、pointerCount、各 pointer 坐标/压力等）。
- **回执**：App 消费后 `InputConsumer::sendFinishedSignal()` → 同一 socket 写回一个 `FINISHED` 消息。
- **fd 转移**：窗口创建时（`WMS.addWindow`），`InputManager` 调 `openInputChannelPair`，**server 端注册给 Dispatcher，client 端通过 Binder（WindowSession.addToDisplay 的 Parcel）把 socket fd 传给 App**。`InputChannel.writeToParcel` 序列化的是 fd（`Parcel.writeFileDescriptor`）——但事件本身从不走 Binder。

### 5.2 为什么不用 Binder（必背对比）

| 维度 | InputChannel (socketpair) | Binder |
| --- | --- | --- |
| 传输载体 | `AF_UNIX` SOCK_SEQPACKET 套接字 | `/dev/binder` 字符设备 + mmap |
| 消息边界 | 天然保边界（每个 event 一个 message） | 一次 transaction 一个 parcel |
| 顺序 | 严格 FIFO（同一连接内） | 每事务独立，受线程池调度 |
| 线程模型 | 每连接独立，App 端由 `Looper` 监听 fd 触发 | 线程池（默认 15 + 1，见 8/12） |
| 背压/回执 | `FINISHED` 信号直接回同一 socket，Dispatcher 精确停表 | oneway 不回执；sync 回执走另一事务 |
| 抗风暴 | socket 缓冲满→Dispatcher 感知→排队，不占 Binder 线程 | 输入风暴会挤占 Binder 线程池，可能饿死其它 IPC |
| 低延迟 | 无 Binder 驱动锁竞争、无线程池排队 | 有锁、有排队，延迟更高且抖动大 |

**一句话**：输入事件是高频、强顺序、需「发→收→停表」闭环的实时流，Binder 的线程池 + 锁竞争 + 无内建回执会让输入延迟抖动甚至被 Binder 风暴饿死。socketpair 用独立 fd + 双向 message + 内建 `FINISHED` 回执，把输入流和 Binder IPC 完全解耦。

> 易错点：**「InputChannel 用共享内存传事件」是过时说法**。当前 AOSP 事件直接序列化进 `InputMessage` 写入 socket 缓冲，没有独立 ashmem；fd 仅在窗口创建时由 Binder 顺手传递一次。

### 5.3 面试高频追问

- **Q：那 Binder 在整个输入链路里一点没用？**
  **A**：用在两处非事件面：① 窗口创建时 client 端 socket fd 由 `WindowSession.addToDisplay` 经 Binder Parcel 传给 App；② `InputManagerService.setInputWindows` / `interceptKeyBeforeQueueing` 等策略回调是 Java↔native 的 Binder/JNI 调用（IMS 在 system_server，Dispatcher 也在 system_server，其实多为同进程 JNI，不走跨进程 Binder；但 App→WMS 注册窗口确实走 Binder）。**事件流本身全程不走 Binder。**
- **Q：socketpair 断了（App 崩溃）会怎样？**
  **A**：Dispatcher 在 `handleReceiveCallback` 检测到 `errno == EPIPE`/对端关闭，调 `unregisterInputChannel` + `onChannelDestroyedLocked`，把该 Connection 从派发目标移除，避免往死连接写。

---

## 6. 事件进入 App：InputEventReceiver → InputStage → View 树

### 6.1 App 侧接收

- `ViewRootImpl.setView()` 时 `new InputChannel()` 并 `mWindowSession.addToDisplay(... mInputChannel)` 拿回 client 端 socket，构造 `WindowInputEventReceiver(mInputChannel, Looper.myLooper())`（`InputEventReceiver` 子类）。
- native 侧 `InputConsumer.consume()` 从 socket 读出 `InputMessage` 重建 `MotionEvent`，JNI 回调 `InputEventReceiver.dispatchInputEvent()` → `onInputEvent()`。

### 6.2 InputStage 责任链（输入处理的「七道关」）

`ViewRootImpl` 把输入处理拆成一条 `InputStage` 链，每 stage 可拦截或放行：

```
NativePreImeInputStage   // 原生前置（如 IME 之前的特殊键）
  -> ImeInputStage       // 先问输入法（软键盘）
  -> EarlyPostImeInputStage
  -> NativePostImeInputStage
  -> ViewPostImeInputStage   // ★ 真正派发到 View 树
  -> SyntheticInputStage
```

`ViewPostImeInputStage.processPointerEvent()` → `mView.dispatchInputEvent()`（`mView` 即 `DecorView`）。

> 关键文件：`frameworks/base/core/java/android/view/ViewRootImpl.java`（内部类 `WindowInputEventReceiver`、`InputStage`、`ViewPostImeInputStage`）、`frameworks/base/core/java/android/view/InputEventReceiver.java`。

### 6.3 DecorView → View 树（接 8/12 Java 侧）

```
DecorView.dispatchTouchEvent()
  -> mWindow.getCallback().dispatchTouchEvent()   // Activity.dispatchTouchEvent
       -> 若 Activity 未消费 -> PhoneWindow.superDispatchTouchEvent()
            -> ViewGroup.dispatchTouchEvent()      // 责任链开始（8/12 已深挖）
                 -> onInterceptTouchEvent() / 子 View.dispatchTouchEvent() / onTouchEvent()
```

此段与第 8/12 篇的「事件分发三方法 + `requestDisallowIntercept` + CANCEL」完全衔接：**§5 之前的全部链路，都是为了把这一刻的 `MotionEvent` 准确、及时地送到 `ViewGroup.dispatchTouchEvent`**。

### 6.4 面试高频追问

- **Q：软键盘弹出时触摸去哪了？**
  **A**：`ImeInputStage` 优先拦截。触摸落在输入框时，事件先经 `ImeInputStage` 转给 IME 处理；非输入区才继续往下走到 `ViewPostIme`。这也是为什么「点输入框外关闭软键盘」由 `EarlyPostImeInputStage` 处理。
- **Q：为什么有时 `onTouchEvent` 收不到 DOWN 但能收到 MOVE？**
  **A**：DOWN 决定后续事件归属（`mFirstTouchTarget`）。若某 ViewGroup 在 DOWN 时 `onInterceptTouchEvent` 返回 true，后续 MOVE/UP 直接发给它，子 View 收不到；反之若子 View 在 DOWN 时 `requestDisallowInterceptTouchEvent(true)`，父容器对 MOVE/UP 不拦截（但下一个 DOWN 会重置，见 8/12）——这常与 §9 的 split/CANCEL 叠加出复杂竞态。

---

## 7. Input 与 Choreographer / Vsync：输入为什么「刚好卡在 vsync 前」

### 7.1 CALLBACK_INPUT 批处理

`ViewRootImpl` 不会每来一个事件就立刻遍历 View 树，而是把输入**批量**到下一帧的 Choreographer 输入回调消费：

```java
// ViewRootImpl.java
void scheduleConsumeBatchedInput() {
    if (mConsumeBatchedInputScheduled) return;
    mConsumeBatchedInputScheduled = true;
    mChoreographer.postCallback(Choreographer.CALLBACK_INPUT,
            mConsumeBatchedInputRunnable, null);   // ★ 关键：INPUT 类型
}
void doConsumeBatchedInput(long frameTimeNanos) {
    mConsumeBatchedInputScheduled = false;
    mInputEventReceiver.consumeBatchedInputEvents(frameTimeNanos);  // 真正消费
    ...
}
```

`Choreographer` 回调类型执行顺序（每帧）：

```
CALLBACK_INPUT(0)  -> 消费批处理输入（最先）
CALLBACK_ANIMATION(1) -> 动画（Recomposer 挂这里，见 8/15）
CALLBACK_INSETS_ANIMATION(2)
CALLBACK_TRAVERSAL(3) -> measure/layout/draw（见 8/20）
CALLBACK_COMMIT(4)
```

**含义**：每帧先处理完输入，再跑动画、再遍历绘制。单条非批处理事件（`deliverInputEvent` 直接走）会经 `Looper` 在 socket 可读时立即处理；批量事件则在 `CALLBACK_INPUT` 这一帧起点统一消费，保证「输入 → 动画 → 绘制」同帧有序、避免抖动。

> 联动：第 8/15 篇指出 Compose `Recomposer` 挂在 `CALLBACK_ANIMATION`；第 8/20 篇指出绘制在 `CALLBACK_TRAVERSAL`。本篇补上最前面的 `CALLBACK_INPUT` —— 三者同帧、顺序固定，是「输入不丢、动画不抖、绘制不错位」的帧内时序基石。

### 7.2 面试高频追问

- **Q：触摸事件是在 vsync 信号来之前还是之后产生？**
  **A**：事件由硬件在任意时刻产生（不受 vsync 约束）。但 App **消费**被对齐到 vsync 帧的 `CALLBACK_INPUT`，因此「用户看到的处理结果」与渲染帧同步，避免一帧内多次消费导致绘制抖动。`Choreographer` 同时给输入提供稳定的时间基准（事件可带 `frameTimeNanos` 做运动预测/resample）。

---

## 8. Input ANR 全解析（5 秒计时器在 native Dispatcher）

### 8.1 计时器在哪、怎么算

`frameworks/native/services/inputflinger/dispatcher/InputDispatcher.h`：

```cpp
static constexpr nsecs_t DEFAULT_INPUT_DISPATCHING_TIMEOUT = 5000 * 1000;  // 5 秒
```

`InputDispatcher::handleTargetsNotReadyLocked()` 在「找不到就绪目标窗口」或「目标窗口已派发但迟迟不回 `FINISHED`」时：

```cpp
if (mInputTargetWaitCause == WAIT_CAUSE_APPLICATION_NOT_READY) {
    // 首次进入：设置超时点
    mInputTargetWaitTimeoutTime = currentTime + timeout;   // timeout = 5s
    ...
}
// 每次 dispatchOnce 检查：超时了且还没解除 -> 触发 ANR
if (currentTime >= mInputTargetWaitTimeoutTime) {
    onANRLocked(currentTime, ...);   // 上报
}
```

`onANRLocked` → `mPolicy->notifyANR()`（`InputDispatcherPolicyInterface`，即 `NativeInputManager`）→ JNI → `InputManagerService.notifyANR()` → 最终 `ActivityManagerService.inputDispatchingTimedOut()` 走 AMS 的 ANR 流水线（写 `/data/anr/`，弹 ANR 对话框）。

### 8.2 与「主线程死循环」「Binder 阻塞」的关系（高危易错）

- **计时器在 `InputDispatcher` 线程（system_server），不在 App 主线程 Looper。** 所以「主线程死循环为什么 ANR」这类题，答案要落到「主线程被阻塞（如 Binder 同步调用卡死、死锁、`onCreate` 里做重 IO）→ 无法在 `CALLBACK_INPUT` 消费并回 `FINISHED` → Dispatcher 的 5s 计时器到点 → AMS ANR」。
- **典型根因链**：App 主线程在 `onTouchEvent`/`onCreate` 里发起**同步 Binder 调用**且对端（另一个 App 或 system_server）也卡住 → 主线程阻塞 → 输入消费停滞 → Dispatcher 5s 超时。**本质不是 Looper 没消息，是 Looper 卡在某个消息里。**
- **区别于其它 ANR**：Service/Broadcast 的 10s/60s 超时在 AMS 侧；Input ANR 的 5s 计时器在 native Dispatcher，只是结果经 AMS 呈现。三者是不同计时器、不同触发点（呼应 8/6 全链路排查「ANR 四类超时」）。

### 8.3 真题现场溯源：#516836306

「多指拖拽后源 App 收不到后续触摸」= 多指 split 场景下，某 pointer 的窗口归属（touch 转移）在拖拽中被错误释放/未正确 `finishDispatchCycle`，导致 Dispatcher 认为该连接「仍在处理上一个事件」而停止派发，直到 5s Input ANR 计时器或状态机自愈。考官用这类真实 bug 考察你对 `TouchState::split()`、`mFirstTouchTarget`、窗口 touch 归属转移与 `finishDispatchCycleLocked` 时序的理解——背 API 没用，必须懂状态机。

### 8.4 面试高频追问

- **Q：主线程明明在跑 `Looper.loop()`，为什么还会 Input ANR？**
  **A**：`loop()` 在跑 ≠ 空闲。`loop()` 取出一个 message 后，会**同步执行到底**才取下一个。若这个 message（如某次 `onTouchEvent` 触发的同步 Binder）耗时 >5s 或死锁，期间 `CALLBACK_INPUT` 回调排不上，输入消费停滞 → Dispatcher 计时器到点。所以「主线程死循环不 ANR」只适用于**无阻塞的 epoll 空闲等待**（见 8/12 native Looper/epoll），不适用于「卡在某个 message 里」。
- **Q：怎么用 trace 定位 Input ANR 是 App 卡还是 Dispatcher 卡？**
  **A**：Perfetto 看 `android_input_events` 的 `dispatch`/`handling`/`ack`/`end_to_end` 四段延迟（第 8/11 篇）：若 `dispatch`（系统侧派发）很短但 `handling`（App 主线程处理）很长 → App 主线程卡；若 `dispatch` 本身就长 → Dispatcher/焦点窗口未就绪。再配合 `thread_state` 看主线程是不是卡在 `binder_thread_read`（Binder 阻塞）。

---

## 9. CANCEL / 多指 split / Pointer Capture（联动 8/10/8/12）

### 9.1 ACTION_CANCEL 的触发条件

`MotionEvent.ACTION_CANCEL` 由 **系统/父容器** 在「当前手势不应再给这个 View」时下发：
- 父 `ViewGroup.onInterceptTouchEvent` 在 MOVE 时返回 true（原本给子 View 的手势被父夺走）→ 子 View 收到 CANCEL。
- 窗口焦点转移 / 窗口被移除 / 发生 `setWindowType` 变化 → Dispatcher 给旧窗口发 CANCEL（对应 §4 `findFocusedWindowTargetsLocked` 结果变化）。
- 多指 split 时某 pointer 的归属窗口改变，旧窗口对失去的 pointer 收 CANCEL。

> 呼应 8/12：CANCEL 是「手势被劫持」的信号，收到 CANCEL 必须**立即终止一切手势态**（如停止拖拽、还原按压态），否则状态错乱。CANCEL 的 action 与 UP 不同——UP 表示正常结束，CANCEL 表示被系统打断。

### 9.2 多指 split 与 touch 归属

`TouchInputMapper::dispatchTouch()` + `TouchState::split()`：当窗口声明 `splitTouch=true`（如 `FLAG_SPLIT_TOUCH`，状态栏/导航栏常用），多个 pointer 可分别命中不同窗口，各收独立 `MotionEvent`。归属在 DOWN/POINTER_DOWN 时确定，MOVE 中随焦点/窗口变化可转移（#516836306 的现场）。

### 9.3 Pointer Capture（联动 8/10）

`InputDispatcher::setPointerCaptureLocked()` + `TouchpadInputMapper`（第 8/10 篇已讲 Pointer Capture 归一化：鼠标/触控板捕获后，所有相对位移直接给捕获窗口，绕过命中测试）。本篇补一句：Pointer Capture 状态下的事件**不走 `findTouchedWindowTargetsLocked` 命中**，而是直接派给 `capturedWindowHandle`，是 Dispatcher 派发路径的一个特例分支。

---

## 10. 跨设备输入（联动 8/8 / 8/22 CDM / Handoff）

Android 的「输入」不止本地 TP：
- **CDM（CompanionDeviceManager）**：配对手表/车机后，其输入设备可作为**虚拟输入源**接入 `EventHub`（经 `InputManager.injectInputEvent` 或厂商 HAL 注册虚拟 `InputDevice`），走同一套 `InputReader → Dispatcher → App` 管道。第 8/22 篇讲过 CDM 持久 Association + 系统级角色权限。
- **跨设备 Handoff / Universal Clipboard**：剪贴板走 `ClipboardService`（第 8/22 篇），输入事件本身不跨设备；但「在 A 设备选中文字 → B 设备粘贴」的体验依赖输入/剪贴板协同。
- **安全边界**：跨设备注入的输入同样过 `injectInputEvent`，需 `android.permission.INJECT_EVENTS`；且跨设备来源在 `InputDevice` 上标记 `SOURCE_*` + 可信度，Dispatcher 策略层（`NativeInputManager.filterInputEvent`）会据此判定是否放行——呼应第 12/13 篇「跨设备 `getCallingUid` 不可信」的同一安全主题。

---

## 11. 易错红榜 TOP20（输入系统专版）

1. **InputChannel 用 socketpair，不是 Binder、也不是独立 ashmem**——事件直接序列化进 socket 缓冲。
2. **Input ANR 的 5s 计时器在 `InputDispatcher`（system_server），不在 App Looper。**
3. `InputReader` 阻塞在 `EventHub.getEvents()` 的 `epoll_wait`；`InputDispatcher` 阻塞在「等 App 回 `FINISHED`」——两者拆线程是有意为之。
4. Reader→Dispatcher 是**同进程函数调用 + 队列**，零 IPC；跨进程发生在 Dispatcher→App 这一段。
5. 焦点窗口是 **Input 子系统缓存的副本**（由 `setInputWindows` 推送），不是每次派发查 WMS。
6. `mInboundQueue`（全局待派发）≠ `connection->outboundQueue`（每连接等回执）。
7. `finishDispatchCycleLocked` 是 Input ANR 的「停表点」——App 不回 `FINISHED` 才会超时。
8. 主线程「跑着 `Looper.loop()`」≠ 不 ANR；卡在某个 message（如同步 Binder）里照样 Input ANR。
9. 输入消费对齐到 `Choreographer.CALLBACK_INPUT`，每帧最先跑；顺序 INPUT→ANIMATION(8/15)→TRAVERSAL(8/20)。
10. `onInterceptTouchEvent` **只有 `ViewGroup` 有**；`View` 没有（8/12）。
11. `requestDisallowInterceptTouchEvent(true)` **只对 MOVE/UP 生效，下一个 DOWN 自动重置**（8/12）。
12. CANCEL = 手势被系统/父容器劫持，必须立即终止手势态；≠ UP。
13. 多指 split 依赖窗口 `splitTouch` 标志，`TouchState::split()` 决定 pointer 归属（#516836306 现场）。
14. 单指触摸只发命中窗口；`ACTION_OUTSIDE` 用于窗口外点击。
15. 窗口刚 `add` 但 Input 还没收到 `setInputWindows` 时，事件会短暂丢失（时序窗口）。
16. `injectInputEvent`（adb/Instrumentation）走独立注入路径，需 `INJECT_EVENTS` 权限，不是普通派发。
17. Pointer Capture 下事件绕过命中测试直接派给 `capturedWindowHandle`。
18. 内核 `evdev` 是 GKI vendor-independent；坐标 raw 值在 `TouchInputMapper.cookAndDispatch()` 按 `Viewport` 才映射成屏幕坐标。
19. `InputEventReceiver` 的 native 端由 `Looper` 监听 socket fd 触发，不是 Binder 唤醒。
20. App 崩溃 → socket `EPIPE` → Dispatcher `unregisterInputChannel` 摘除连接，不会卡死派发。

---

## 12. 三条高频追问链（输入系统专版）

### 链 A：多指拖拽后 App 收不到触摸（真题现场 #516836306）
现象（Beta1 真实 bug）：多指拖拽后源 App 不再收到任何触摸。
追问：事件从哪来？→ `TouchInputMapper.dispatchTouch()` 的 `TouchState::split()` 怎么分配 pointer？→ 拖拽中窗口 touch 归属转移时，`finishDispatchCycleLocked` 有没有被正确调用？→ 若旧连接停在 `outboundQueue` 未回 `FINISHED`，Dispatcher 会怎样？→ 会不会触发 5s Input ANR？→ 怎么用 `android_input_events` 表的 `handling` 段定位是 App 卡还是 Dispatcher 卡？

### 链 B：为什么主线程「死循环」有时 ANR、有时不
追问：`Looper.loop()` 空闲时靠什么休眠？→（答：native `epoll`，见 8/12）→ 那为什么还会 Input ANR？→ 卡在某个 message 里（同步 Binder/死锁/重 IO）和「空闲 epoll 等待」本质区别？→ 5s 计时器在谁手里？→ 用 trace 怎么区分 App 卡 vs Dispatcher 卡？

### 链 C：InputChannel 设计权衡
追问：为什么不用 Binder 传触摸？→ Binder 线程池风暴会怎样？→ socketpair 为什么保序、有消息边界、能内建 `FINISHED` 回执？→ fd 是怎么从 system_server 到 App 的（还是走了 Binder！）？→ 那 Binder 在整个输入链路里到底用在哪两处？→ 「InputChannel 用共享内存」为什么是过时说法？

---

## 13. AOSP 14 源码路径清单（输入系统）

```
# Native 输入服务（system_server 进程内）
frameworks/native/services/inputflinger/
  InputManager.cpp                         # initialize() 拉起 Reader/Dispatcher 线程
  reader/EventHub.cpp                      # /dev/input + inotify + epoll，getEvents()
  reader/InputReader.cpp                   # loopOnce()/processEventsLocked()/mQueuedListener
  reader/mapper/TouchInputMapper.cpp       # cookAndDispatch()/dispatchTouch()/split()
  reader/mapper/KeyboardInputMapper.cpp    # processKey()
  reader/mapper/TouchpadInputMapper.cpp    # 触控板
  dispatcher/InputDispatcher.cpp           # dispatchOnce()/findFocusedWindowTargetsLocked()
  dispatcher/InputDispatcher.h             # DEFAULT_INPUT_DISPATCHING_TIMEOUT=5s
  dispatcher/InputDispatcher.cpp           # handleTargetsNotReadyLocked()/onANRLocked()
  libs/input/InputTransport.cpp            # openInputChannelPair() socketpair + publish/consume

# Java 侧（system_server）
frameworks/base/services/core/java/com/android/server/input/InputManagerService.java
  # start()/setInputWindows()/interceptKeyBeforeQueueing()/notifyANR()
frameworks/base/services/core/jni/com_android_server_input_InputManagerService.cpp
  # NativeInputManager（InputReaderPolicyInterface/InputDispatcherPolicyInterface）

# App 侧（android.jar）
frameworks/base/core/java/android/view/InputEventReceiver.java
frameworks/base/core/java/android/view/ViewRootImpl.java
  # WindowInputEventReceiver / InputStage / ViewPostImeInputStage / scheduleConsumeBatchedInput
frameworks/base/core/java/com/android/internal/policy/DecorView.java  # dispatchTouchEvent
frameworks/base/core/java/android/view/ViewGroup.java  # dispatchTouchEvent/onInterceptTouchEvent
frameworks/base/core/java/android/view/View.java       # dispatchTouchEvent/onTouchEvent
frameworks/base/core/java/android/view/Choreographer.java  # CALLBACK_INPUT=0

# Kernel / drivers（android14-6.1, GKI）
drivers/input/input.c                      # input_event()/input_handle_event()
drivers/input/evdev.c                      # evdev_event()/evdev_read() -> /dev/input/eventN
include/uapi/linux/input.h                 # struct input_event
```

---

## 14. 30 → 31 篇交叉索引（输入系统视角）

| 主题 | 本篇衔接点 | 关联篇 |
| --- | --- | --- |
| View 事件分发三方法 / `requestDisallowIntercept` / CANCEL | §6.3、§9.1 | 第 8/12 篇（核心基础八股） |
| input 延迟四段定界（dispatch/handling/ack/end_to_end） | §8.4、链 A | 第 8/11 篇（Perfetto SQL 扩充） |
| Pointer Capture 归一化 | §9.3 | 第 8/10 篇（兼容性框架 × A17 跨设备/输入） |
| Recomposer 挂 `CALLBACK_ANIMATION` | §7.1 | 第 8/15 篇（Compose 编译器/运行时） |
| 绘制在 `CALLBACK_TRAVERSAL` | §7.1 | 第 8/20 篇（源码级 code walk 启动到首帧） |
| ANR 四类超时辨析 | §8.2 | 第 8/6 篇（全链路排查实战） |
| Binder 线程池 15 / oneway 队列 | §5.2 对比 | 第 8/12 篇（核心基础八股） |
| 跨设备输入安全边界 / `getCallingUid` 不可信 | §10 | 第 8/22 篇（A18 桌面融合与跨设备协同） |
| native Looper/epoll 主线程不 ANR 真相 | §8.4、链 B | 第 8/12 篇（核心基础八股） |
| GKI / drivers 驱动骨架 | §2.1、§13 | 第 8/12 篇（核心基础 / GKI 字符驱动） |

---

> 本篇把「驱动 evdev → EventHub → InputReader → InputDispatcher → InputChannel → ViewRootImpl → View 树 → Input ANR」焊成一条端到端链路，补齐了此前 Java 侧（8/12）与测量侧（8/11）之间的 native 管道真空。系列至此 **31 篇 / 约 201 专题**：主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧 AI + 源码 walk + Perfetto SQL + 基础八股 + 两版真题大乱斗 + Native 稳定性 + Compose 编译/运行时 + 输入系统全链路，完整闭环。
