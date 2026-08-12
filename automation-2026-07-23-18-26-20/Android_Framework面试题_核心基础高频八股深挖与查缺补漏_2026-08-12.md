# Android Framework 面试题 · 核心基础高频八股深挖与查缺补漏（第 26 篇）

> 日期：2026-08-12 ｜ 系列第 26 篇 ｜ 累计约 175 专题
> 主线 baseline：Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX，内核 GKI android14-6.1)
> 热点锚定：Android 17 stable 已于 2026-06-16 发布（代号 CinnamonBun）；**A17 QPR2 Beta 2 于 2026-08-03 推送**（build CP41.260701.006，代号 DEV，仅图标微调 + 稳定性修复 + Pixel 6/6 Pro EOL 退出，无行为变更，stable 预计 2026-12）；A18 桌面融合 / 跨设备 Handoff / EU DMA 开放 11 项 AI 能力仍处路线图。结论：**2026 面试最高频的仍是经典八股（Handler/Looper、Binder、View、AMS、启动、ANR）——本篇回归基础，并把前 25 篇只点到名的底层机制一次挖透。**

---

## 0. 为什么今天要"回归基础 + 查缺补漏"

前 25 篇（约 170 专题）把主线 + 盲区 + 深水区 + 智能层 + 安全世界(TEE/pKVM) + 座舱 + 端侧 AI + 源码 code walk + Perfetto SQL 全做完了。但复盘发现：**经典八股里有一批"人人都会背一句、没人能讲到底层"的题，前 25 篇要么只在总论里一笔带过，要么只讲了 Java 层没讲 native 层**。今天专门补这些"看似会、实则虚"的点：

```
前 25 篇已覆盖（一笔带过/只讲上层）        本篇新挖深度（native + 边界 + 易错）
--------------------------------------      --------------------------------------
Handler/Looper（仅 7/23 主篇 + 7/29       native MessageQueue / Looper.cpp epoll 唤醒
  A17 lock-free 提了一句）                   IdleHandler / 同步屏障 / 消息池 /
                                            "主线程死循环为什么不卡死/不 ANR"
Binder 线程池 15（仅 19/21 篇一句）          线程池 spawn 链路 + 死亡通知 linkToDeath
View measure/layout/draw（仅 20 篇 code      事件分发三方法责任链 + requestDisallow
  walk）                                      + onTouch/onClick 优先级 + CANCEL 语义
MeasureSpec（几乎没单独展开）               三模式 + getChildMeasureSpec 推导 + AT_MOST 坑
HAL/Treble/GKI（4 篇）/ binder.c（1 篇）     epoll/futex/cgroup/EAS/uclamp + GKI 字符驱动骨架
MTK（仅 1 篇提了 DuraSpeed/AEE 名词）         AEE/mtklog/PerfService/thermal/vendor HAL/内核驱动
```

> 约定：文中 `.java/.cpp` 路径默认是 **Android 14 AOSP (android-14.0.0_rXX)**；内核路径为 **GKI common-android14-6.1**；MTK 为 vendor 实现，路径随平台/Android 版本变化，下列为常见形态，引用时以真机为准。涉及 A17 新增项显式标注 `[A17]`。

---

## 专题一：Handler / Looper / MessageQueue —— 不止 Java 层，native 才是真相

**现象 / 考官提问**
> 1) 主线程 `Looper.loop()` 是个死循环，为什么不会把 CPU 跑满、也不会卡死？2) 为什么主线程这个死循环不会引发 ANR，反而是"处理消息太久"才 ANR？3) `IdleHandler` 和"同步屏障"是干嘛的？4) `Message` 为什么能反复 `obtain()` 不 OOM？

**底层原理 + 源码落点（从 Java 一路挖到 epoll）**

整条链路：`Handler.sendMessage` -> `MessageQueue.enqueueMessage` -> `Looper.loop()` -> `MessageQueue.next()` -> `nativePollOnce` -> **native `Looper` 用 `epoll` 阻塞** -> 被 `wake()` 唤醒 -> 回到 Java 分发 `msg.target.dispatchMessage`。

1. **Java 层循环（`frameworks/base/core/java/android/os/Looper.java`）**
   `loop()` 里是个 `for(;;)`：`Message msg = queue.next();` 然后 `msg.target.dispatchMessage(msg);`。关键点：`queue.next()` 在没有消息时会**阻塞**，而不是自旋空转。

2. **native 阻塞真相（`frameworks/base/core/jni/android_os_MessageQueue.cpp` + `system/core/libutils/Looper.cpp`）**
   `MessageQueue.next()` 调 `nativePollOnce(ptr, nextPollTimeoutMillis)`。native 侧 `NativeMessageQueue` 持有 `Looper`（native，`system/core/libutils/Looper.cpp`）。`Looper::pollOnce` -> `pollInner` 最终调 **`epoll_wait`** 监听一个 `eventfd`（`mWakeEventFd`，Android 2.3+ 取代旧 pipeline）。
   - 当 `nextPollTimeoutMillis == -1`（无消息、无定时消息）时，`epoll_wait` 无限期阻塞，主线程进入 **interruptible sleep（S 状态）**，CPU 完全释放 —— 这就是"死循环不卡死"的根因：**它其实在休眠，不在跑**。
   - 当有消息入队，`enqueueMessage` 调 `nativeWake` -> `Looper::wake()` -> `write(mWakeEventFd, 1)` 往 eventfd 写一个字节 -> `epoll_wait` 立即返回 -> 线程被唤醒处理消息。
   - 这是经典 **"one thread, one looper, epoll event loop"** 模式，和 libevent / Node.js libuv / Netty 的 reactor 本质相同。

3. **为什么"死循环"不 ANR，反而是"消息处理太久" ANR？**
   ANR **不是** loop 自己抛的，而是 **system_server 看门狗**（AMS 的 `AppErrors` / `BroadcastQueue` / `InputDispatcher` 超时监测）发现的：
   - 输入事件超时 `5s`（`InputDispatcher` 派发触摸后等应用 `finishInputEvent`，见第 20/25 篇）；`BroadcastReceiver` 前台 `10s` / 后台 `60s`；`Service` 生命周期 `20s`；`ContentProvider` `10s`。
   - 你的 `onCreate`/`onTouchEvent` 本质都是在某个 `Message` 的 `dispatchMessage` 里跑。**只要这个 handler 跑太久没返回，loop 就回不到 `next()` 去取下一个消息**，于是 `InputDispatcher` 派下来的触摸消息一直排不上 —— system_server 等不到响应 -> 判定主线程无响应 -> 弹 ANR。
   - 所以准确说法是：**Looper 死循环是"等消息"，ANR 是"处理某条消息时阻塞导致后续消息（含系统输入）饿死"**。loop 本身永远无罪。

4. **IdleHandler（`frameworks/base/core/java/android/os/MessageQueue.java`）**
   `next()` 在没有可立即处理的消息、且当前无同步屏障时，会执行 `PendingIdleHandler`：
   ```java
   // MessageQueue.next() 末尾
   if (pendingIdleHandlerCount < 0) pendingIdleHandlerCount = mIdleHandlers.size();
   if (pendingIdleHandlerCount <= 0) { mBlocked = true; continue; } // 真正阻塞在 epoll
   // 否则先跑 IdleHandler
   for (int i=0; i<mPendingIdleHandlers.length; i++) {
       final IdleHandler idler = mPendingIdleHandlers[i];
       boolean keep = idler.queueIdle();   // 返回 false 自动移除
   }
   ```
   典型用途：`ActivityThread.GcIdler`（空闲时 GC）、`ActivityThread` 的 `finalizer` 清理、Binder 代理 flush、`ViewRootImpl` 某些遍历。`queueIdle()` 返回 true 常驻、false 一次性。

5. **同步屏障（Sync Barrier，`postSyncBarrier` / `removeSyncBarrier`）**
   往队列插一条 `target == null` 的 Message。在 `next()` 里：遇到 `msg.target == null` 就**跳过所有同步消息，只放行 `isAsynchronous()` 的异步消息**，直到屏障被移除。
   - 谁在用？`Choreographer` 在收到 VSYNC 后要确保"绘制消息"能插队立刻执行，于是先 `postSyncBarrier()` 挡住普通消息，发完异步的 `doFrame` 再 `removeSyncBarrier()`。
   - 面试坑：屏障**必须配对移除**，漏移除会导致普通消息永远排不上（界面冻住但没 ANR，因为 loop 在跑、只是你的消息被挡）。

6. **消息池（避免 GC 抖动）**
   `Message` 用静态 `sPool` 单向链表缓存，`obtain()` 取、`recycleUnchecked()` 还，`MAX_POOL_SIZE = 50`。所以频繁 `Message.obtain()` 不会每次 new 对象，减少 GC。对应 `MessageQueue` 里消息也是链表（`next` 指针），`enqueueMessage` 按 `when` 时间顺序插入。

**易错点（红榜）**
- "死循环会耗 CPU"。错：无消息时在 `epoll_wait` 休眠，CPU 占用近 0。
- "ANR 是 Looper 的锅"。错：loop 永远在等消息；是某条 `dispatchMessage` 处理太久导致后续消息（含系统输入）饿死，system_server 看门狗判定。
- "同步屏障能随便加"。错：漏 `removeSyncBarrier` 会让普通 UI 消息被永久挡住，界面假死。
- "消息 new 出来就行"。错：`obtain()` 复用对象池，避免高频发消息制造 GC。

**高频追问链**
1. `next()` 里 `epoll` 监听的除了 eventfd 还有什么？-> 还有 `mWakeEventFd` + 通过 `addFd` 注册的 `LooperCallback`（native 侧监听 fd，如 InputChannel、Surface 的 fd），所以 Looper 既能处理 Java 消息也能处理 native fd 事件。
2. 子线程怎么用 Looper？-> `new HandlerThread("x").start()`（内部 `run()` 调 `Looper.prepare()` + `loop()`），或手动 `Looper.prepare()`/`loop()`；`Handler(handlerThread.looper)` 把消息抛到子线程。
3. `[A17]` MessageQueue 变成 lock-free 影响上面哪条？-> 第 7/29 篇讲的 A17 `MessageQueue` 内部用无锁结构替换了 `synchronized` 锁，但 `nativePollOnce`/epoll 唤醒模型不变，上面 1~6 的语义完全一致。

**延伸阅读**：第 7 篇（主篇 Handler 概述）、第 7/29 篇（A17 lock-free MessageQueue）、第 20 篇（code walk·主线程消息）、第 25 篇（Input 派发与主线程饥饿的定责）。

---

## 专题二：Binder 线程模型 + 死亡通知 —— 为什么一个进程能并发扛 15 个跨进程调用

**现象 / 考官提问**
> 1) Binder 服务端是怎么并发处理多个客户端请求的？线程池多大？2) 为什么 oneway 调用"满了"也会排队？3) 客户端怎么知道对端进程挂了（避免调用僵尸 binder）？4) `getCallingUid()` 在什么场景下不可信（呼应第 12/13 篇）？

**底层原理 + 源码落点**

1. **Binder 线程池（默认上限 15）**
   - 服务端进程在 `ProcessState`（native，`frameworks/native/libs/binder/ProcessState.cpp`）初始化时，`startThreadPool()` 先 spawn **1 个**主 binder 线程调用 `IPCThreadState::joinThreadPool()`。
   - 每个 binder 线程在 `joinThreadPool` 里循环 `getAndExecuteCommand()`，从 `/dev/binder` 读 `BR_*` 命令。当内核 `binder.c` 发现当前线程数不够、需要更多线程处理并发事务时，回一个 `BR_SPAWN_LOOPER`，用户态据此再 `spawnPooledThread(true)`。
   - 上限由 `mMaxThreads`（默认 **15**）控制，对应内核 `binder.c` 的 `max_threads`（经 `BC_SET_MAX_THREADS` 下发）。所以**一个进程默认最多 15 个 binder 工作线程 + 1 个主线程**，可并发处理 16 路 incoming binder 调用。
   - 源码：`ProcessState.cpp` `setThreadPoolMaxThreadCount` / `spawnPooledThread`；`IPCThreadState.cpp` `joinThreadPool` / `getAndExecuteCommand`。

2. **oneway 满也排队（呼应 19/21 篇）**
   `oneway`（异步、`FLAG_ONEWAY`）不等人回，但**它只占 binder 线程 + 事务槽位**。当对端 15 个线程全被占、或 binder 缓冲（`binder_buffer`）耗尽，`binder_transaction` 在驱动层会**阻塞等待空闲**（不是立即失败）。这就是为什么"oneway 调用多了也会卡"——它也得排队等线程/缓冲，第 19/21 篇 Perfetto 里 `binder_transaction` 的"kernel 拷贝 vs 对端执行"两段延迟就是这么来的。

3. **死亡通知 `linkToDeath`（`frameworks/base/core/java/android/os/Binder.java` + `BinderProxy.java`）**
   - 客户端：`binder.linkToDeath(DeathRecipient, flags)` -> native `BinderProxy.linkToDeath` -> 在 `binder.c` 的 `binder_ref` 上挂一个 `death` 结构，并注册到目标 `binder_node` 的死亡列表。
   - 当目标进程死，`binder.c` 遍历死亡列表发 `BR_DEAD_BINDER`；客户端 `IPCThreadState` 收到后，在 `joinThreadPool` 里回调 `DeathRecipient.binderDied()`（注意：**在 binder 线程**回调，不要在里面做重活或再次跨进程）。
   - 典型用途：AMS 监听 App 进程死亡（`appDied`）、客户端监听系统服务（如 `PowerManager`/`LocationManager` 的 binder 死亡重连）。`unlinkToDeath` 解注册。
   - 面试坑：死亡通知是**异步**的，收到时对方可能已经死了片刻；且 `binderDied` 在 binder 线程跑，不要在里面 `Toast`/弹 UI（要 post 回主线程）。

4. **`getCallingUid()` 不可信的场景（呼应 12/13 篇）**
   - 正常同进程内 `transact`：内核 `binder.c` 在事务里填 `sender_euid`，`Binder.getCallingUid()` 拿到对端真实 UID（这是权限校验基石，如 `checkCallingPermission`）。
   - **不可信一**：跨 VM（AVF/pKVM，第 12 篇）—— RPC Binder 经 vsock 跨 VM 时，对端 UID 是**映射值**，不能直接当真实 UID 授权。
   - **不可信二**：Provider 侧（第 13 篇）—— AppFunctions Provider 收到的 `getCallingUid()` 是 **SYSTEM_UID**（因为请求经 system_server 的 AppSearch 中转），必须用 `AttributionSource` / `getCallingAttributionSource()` 拿原始调用方。

**易错点（红榜）**
- "Binder 单线程串行"。错：线程池默认 15，可并发 16 路；但同一个 binder 对象的事务**默认不保证顺序之外的并发安全**，service 自己要线程安全。
- "oneway 永不阻塞"。错：线程/缓冲满同样在驱动层排队等待。
- "进程死了客户端立刻知道"。错：靠 `linkToDeath` 异步回调，且回调在 binder 线程。
- "getCallingUid 永远可信"。错：跨 VM 和经 system_server 中转两处都不可信。

**高频追问链**
1. 15 个线程全忙会怎样？-> 新事务在 `binder.c` 排队等 `BR_SPAWN_LOOPER` 已到上限就阻塞，客户端表现为调用卡顿（第 19 篇卡顿/Binder 实战坑）。
2. `clearCallingIdentity` 解决什么？-> 临时把 calling UID 清成自己（0），用于"以自己身份去调别的服务"（如 system_server 代发广播），用完 `restoreCallingIdentity`——Binder 安全经典题（第 1 篇拓展已提）。
3. AIDL 生成的 stub/proxy 是什么？-> `xxx.Stub extends Binder` 收 `onTransact` 按 code 分发；`xxx.Proxy` 封装 `transact(code, data, reply, flags)`，oneway 体现在 flags 里。

**延伸阅读**：第 1 篇（Binder 驱动层一次拷贝/mmap）、第 12 篇（跨 VM RPC Binder）、第 13 篇（Provider 侧 UID 不可信）、第 19/21 篇（oneway 排队 + Perfetto 定责）。

---

## 专题三：View 事件分发三方法责任链 —— 90% 的人讲不清 CANCEL 和 disallow

**现象 / 考官提问**
> 1) 一次点击 `DOWN` 到 `onClick` 走了哪些方法？`dispatchTouchEvent`/`onInterceptTouchEvent`/`onTouchEvent` 谁先谁后？2) `onInterceptTouchEvent` 只有谁有？3) 子 View 怎么"阻止父 View 抢事件"？4) 为什么有的时候收不到 `UP` 只收到 `CANCEL`？5) `onTouchListener`、`onTouchEvent`、`onClick` 优先级？

**底层原理 + 源码落点（`frameworks/base/core/java/android/view/`）**

1. **分发起点**（Activity -> Window -> DecorView -> ViewGroup）
   `Activity.dispatchTouchEvent` -> `getWindow().superDispatchTouchEvent` -> `PhoneWindow` -> `DecorView.superDispatchTouchEvent` -> `ViewGroup.dispatchTouchEvent`。

2. **责任链核心（ViewGroup.dispatchTouchEvent）**
   ```
   ViewGroup.dispatchTouchEvent(ev):
     // 1) 是否拦截
     intercepted = onInterceptTouchEvent(ev)   // 只有 ViewGroup 有此方法
     // 2) 不拦截则向下分发给子 View（递归 child.dispatchTouchEvent）
     if (!intercepted && 有子 View 消费) -> 子 View 处理
     // 3) 没有任何子 View 消费 -> 自己当普通 View 处理
     if (无消费) -> super.dispatchTouchEvent -> View.dispatchTouchEvent -> onTouchEvent
   ```
   - `onInterceptTouchEvent` **只有 `ViewGroup` 有**；普通 `View` 没有这个方法（`View.dispatchTouchEvent` 直接走 `onTouchEvent`）。

3. **消费判定与事件流向**
   - 某 View 的 `dispatchTouchEvent` 返回 **true = 消费**（事件到此为止，父不再处理）；返回 false = 不消费（向上抛给父的 `onTouchEvent`）。
   - 一次完整手势：只要 `DOWN` 被某 View 消费，后续 `MOVE`/`UP` 都会**优先发给它**；若 `DOWN` 没人消费，后续 `MOVE`/`UP` 直接走 Activity 的 `onTouchEvent`（不会再向 View 树派发）。

4. **`requestDisallowInterceptTouchEvent`（嵌套滑动关键）**
   - 子 View 调 `parent.requestDisallowInterceptTouchEvent(true)` 设 `FLAG_DISALLOW_INTERCEPT`，父 ViewGroup 在非 `DOWN` 时**跳过 `onInterceptTouchEvent`**，不再抢事件。
   - 经典用法：`RecyclerView`/`ViewPager` 内部滑动时，子列表告诉外层的 ViewPager"这段手势你别拦"。
   - **坑**：该标志在每次 `DOWN` 时会被重置（`resetTouchState` 清 `FLAG_DISALLOW_INTERCEPT`），所以只对**非 DOWN** 事件生效；想在 DOWN 上就阻止父拦截，要靠在子 View 的 `onInterceptTouchEvent`/更早时机处理。

5. **CANCEL 语义（最易错）**
   - 当父 ViewGroup 在手势中途（已 `DOWN` 给子 View 后）`onInterceptTouchEvent` 返回 true 抢事件，子 View 会立刻收到 **`ACTION_CANCEL`**（而不是后续 `MOVE`/`UP`），代表"这个手势被父抢走了，你之前的按下状态作废"。
   - 所以：**`onInterceptTouchEvent` 返回 true 的那一刻，之前已收到 DOWN 的子 View 会收到 CANCEL**——这是处理滑动冲突时最容易漏掉的分支（要在 `onTouchEvent` 里妥善处理 CANCEL，清理按下高亮/拖拽状态）。

6. **onTouchListener / onTouchEvent / onClick 优先级**
   `View.dispatchTouchEvent` 内：
   ```
   if (mOnTouchListener != null && mOnTouchListener.onTouch(this, event)) return true; // 消费
   if (onTouchEvent(event)) return true;
   ```
   - `OnTouchListener.onTouch` **优先于** `onTouchEvent`；若 `onTouch` 返回 true 则 `onTouchEvent` 不执行，`onClick` 也不会触发（因为 onClick 是在 `onTouchEvent` 的 `ACTION_UP` 里 `performClick()` 触发的）。
   - 顺序：`onTouchListener.onTouch` ->（若未消费）`onTouchEvent` ->（UP 且 clickable）`performClick` -> `OnClickListener.onClick`。

**易错点（红榜）**
- "onInterceptTouchEvent 普通 View 也有"。错：仅 ViewGroup。
- "DOWN 没人消费后续还会派发"。错：DOWN 不消费则后续事件直接走 Activity。
- "requestDisallowIntercept 对 DOWN 也生效"。错：DOWN 会清标志。
- "拦截就收到 UP"。错：中途拦截子 View 收的是 CANCEL。
- "onTouch 里返回 true 还能 onClick"。错：onTouch 消费后 onTouchEvent 不跑，onClick 不触发。

**高频追问链**
1. 滑动冲突怎么解？-> 外部拦截法（父 `onInterceptTouchEvent` 按方向决定）/ 内部拦截法（子 `requestDisallowIntercept` + 父在 DOWN 放行）；第 22/23 篇 freeform resize handle 也是系统层抢事件的实例。
2. 事件最终谁消费？-> 用 `getDecorView().dispatchTouchEvent` 调试；`MotionEvent` 的 `getActionMasked` 看多指。
3. Compose 怎么处理？-> Compose 用 `pointerInput` + 三段 `PointerEventPass`（Initial/Main/Final）替代这套，语义等价但声明式（第 13 篇）。

**延伸阅读**：第 20 篇（ViewRootImpl 收事件入口 `deliverInputEvent` / InputStage）、第 22/23 篇（系统手势抢事件 / freeform）、第 25 篇（input 延迟定界）。

---

## 专题四：MeasureSpec 三模式 + 测量布局绘制边界（自定义 View 必考点）

**现象 / 考官提问**
> 1) `MeasureSpec` 三种模式什么含义？2) 父 View 怎么决定子 View 的 MeasureSpec？3) 为什么自定义 View 不处理 `AT_MOST`，`wrap_content` 会撑满父？4) `getMeasuredWidth()` 和 `getWidth()` 为什么有时候不等？

**底层原理 + 源码落点（`frameworks/base/core/java/android/view/View.java` + `ViewGroup.java`）**

1. **三模式（`View.MeasureSpec`）**
   - `UNSPECIFIED (0)`：父不对子施加约束（如 `ScrollView` 的 child、系统测量用）——子想多大就多大。
   - `EXACTLY (1)`：父已定死子的大小（精确 dp / `match_parent`）——子必须接受这个值。
   - `AT_MOST (2)`：子最大不能超过某值（`wrap_content`）——子自行决定但不得超 `specSize`。

2. **父如何生成子的 MeasureSpec（`ViewGroup.getChildMeasureSpec`）**
   经典推导表（spec 来自父、childDimension 来自子的 `LayoutParams`）：
   ```
   父 EXACTLY  + 子 精确值  -> 子 EXACTLY(=精确值)
   父 EXACTLY  + 子 MATCH   -> 子 EXACTLY(=父size)
   父 EXACTLY  + 子 WRAP    -> 子 AT_MOST(=父size)   <-- 关键
   父 AT_MOST  + 子 精确值  -> 子 EXACTLY(=精确值)
   父 AT_MOST  + 子 MATCH   -> 子 AT_MOST(=父size)
   父 AT_MOST  + 子 WRAP    -> 子 AT_MOST(=父size)
   父 UNSPEC   + 子 任意    -> 子 UNSPECIFIED
   ```

3. **`wrap_content` 撑满父的坑**
   `wrap_content` 落到的就是 `AT_MOST` + `specSize=父剩余空间`。如果你自定义 `onMeasure` 里不判断 mode、无条件 `setMeasuredDimension(widthMeasureSpec 的 size, ...)`，那就把 `AT_MOST` 当成了精确值，**WRAP_CONTENT 会变成 MATCH_PARENT**。正确写法：
   ```java
   int specMode = MeasureSpec.getMode(widthMeasureSpec);
   int specSize = MeasureSpec.getSize(widthMeasureSpec);
   int w = (specMode == MeasureSpec.EXACTLY) ? specSize
           : Math.min(desiredWidth, specSize); // AT_MOST 时不得超 specSize
   setMeasuredDimension(w, h);
   ```

4. **`getMeasuredWidth()` vs `getWidth()`**
   - `getMeasuredWidth()` = `measuredWidth`，在 `setMeasuredDimension` 时定，**measure 阶段后有效**。
   - `getWidth()` = `right - left`，在 `layout`/`setFrame` 时定，**layout 阶段后有效**。
   - 两者可短暂不等：①measure 完了还没 layout（requestLayout 进行中）；②动画/自定义 layout 故意把子放得比 measure 结果大；③`measure` 可能被调多次（"measure 两遍"），`getMeasuredWidth` 反映最后一次。第 20 篇 code walk 已强调这一对。

**易错点（红榜）**
- "wrap_content 一定自适应"。错：不处理 AT_MOST 会撑满父。
- "getMeasuredWidth == getWidth 永远成立"。错：layout 前/动画中可不等。
- "measure 只跑一次"。错：父约束变化会 measure 多遍。
- "UNSPECIFIED 只系统用"。错：ScrollView/NestedScrollView 子元素就是 UNSPECIFIED，自定义嵌套要注意。

**高频追问链**
1. `requestLayout()` vs `invalidate()`？-> `requestLayout` 触发 measure+layout（可能 draw）；`invalidate` 只重绘（draw），不重测（第 20 篇）。
2. 硬件加速下 draw 走了谁？-> View.draw 经 HWUI 录成 DisplayList，交给 RenderThread（第 4/19/25 篇 SF 一帧）。
3. 自定义 View 性能？-> 避免 onMeasure/onDraw 里分配对象；用 `View.postOnAnimation` 配合 Choreographer。

**延伸阅读**：第 20 篇（performTraversals / measure/layout/draw 全链路）、第 4 篇（HWUI/RenderThread）、第 25 篇（SF 一帧）。

---

## 专题五：App 冷启动根因 + Compose 底层机制（紧凑呼应 + 各补一点新）

> 这两块前 20/24/13 篇已深讲，本篇只做"串联 + 补刀"，便于单点复习时回扣。

**冷启动根因（回扣 20/24 篇）**
完整链路：`zygote fork` -> `ActivityThread.main` -> `attach` -> `handleBindApplication`（建 App 对象、装 BaseContext、走 `onCreate` 前还有 `installContentProviders` **前置坑**：ContentProvider 在 Application.onCreate 之前初始化，是冷启动隐性大头）-> `ATMS` 通知 `realStartActivityLocked` -> `performLaunchActivity` -> `onCreate/onStart/onResume` -> `ViewRootImpl.setView` -> 首帧上屏。
- **新增补刀**：`installContentProviders` 这一步常被忽略——三方库爱在 `ContentProvider.onCreate` 里做初始化（Firebase 等），它**早于 Application.onCreate**，是冷启动"莫名慢"的高频元凶；优化手段 = 延迟初始化 / 用 `App Startup` 库集中管控 / 把重活挪到首帧之后（第 24 篇 ART profile 配合）。

**Compose 底层机制（回扣 13 篇）**
- **编译器注入**：Compose 编译器插件在 IR 阶段给每个 `@Composable` 注入 `$composer` 参数与 `$changed` 位掩码，用 **restart/replace/movable 三种 group** 标记重组边界；带返回值的 Composable 不是重组边界（这是常见误解）。
- **运行时**：`SlotTable` 用**平坦 IntArray + gap buffer** 存组合树；`Snapshot` 是 **MVCC 版本链**，"读取即订阅"（`readObserver`）实现状态驱动重组；`Recomposer` 挂在 `Choreographer` 的 **ANIMATION** 回调，View 遍历在 **TRAVERSAL** 回调，同帧先后执行。
- **新增补刀**：`LayoutNode` -> `RenderNode` 的绘制**未绕过 HWUI**（第 13 篇六接缝之一）；Compose 语义树 `SemanticsNode` 对无障碍/AI Agent 更友好，因为结构信息显式（第 13 篇 ANI 自动化）。

**易错点（红榜）**
- "冷启动慢一定是 Application.onCreate 重"。错：ContentProvider 前置初始化更隐蔽。
- "带返回值的 Composable 能重组"。错：它不是重组边界。
- "Compose 绕过了 View 体系"。错：AndroidComposeView 只是 View 树里一个 View，底层仍 HWUI/RenderNode。

**延伸阅读**：第 20 篇（code walk 启动到首帧）、第 24 篇（ART dex2oat 冷启动优化）、第 13 篇（Compose 编译器 + 运行时 + 六接缝）。

---

## 专题六：内存 / 卡顿 / ANR 三杀 —— 三条"杀进程/掉帧"路径辨析

> 前 19/21/23 篇已详述，本篇把"三条杀路径"和"ANR 四超时"凝成一张速记表（面试最爱连击）。

**三条杀进程路径辨析（核心）**
```
路径 A  内核 OOM Killer        : 系统整体内存压力下, 按 oom_score_adj 杀(无进程自身感知)
路径 B  LMKD (用户态)         : 监听 PSI(mem.pressure)/低内存事件, 按 oom_adj 档位杀,
                                 Android 14 默认 userspace lmk + PSI
路径 C  [A17] Memory Limiter   : 单个应用超过"内存配额"被静默杀, 死因
                                 ApplicationExitInfo REASON_MEMORY_LIMITER / "MemoryLimiter:AnonSwap"
```
三者的区别（考官连击点）：A/B 是**系统级**内存不足时的"挤牙膏式"回收，按 `oom_adj`（前台 -1000 ~ 后台 900+）挑最该杀的；C 是 **A17 新增的"个体超标"**，即使系统内存够，单 App 超配额也会被定向静默杀，对应 `getDescription()` 出现 `MemoryLimiter:AnonSwap`（第 8/12 篇已落地）。

**ANR 四类超时（速记）**
```
输入事件   : InputDispatcher 派发后 5s 未 finishInputEvent
Broadcast  : 前台 10s / 后台 60s 未 onReceive
Service    : 生命周期 20s 未返回 (onCreate/onStartCommand)
ContentProvider : 10s 未 publish
```
定界：`/data/anr/` 栈 + `event log` 里 `am_anr` + 主线程 `thread_state`（是不是 `Running`/`Runnable` 被某条消息占住，或 `monitor_contention` 锁竞争，第 21 篇 Perfetto SQL 可量化）。

**卡顿掉帧定责**（呼应 19/21/25 篇）
主线程 TRAVERSAL(draw) vs RenderThread(HWUI 录制) vs GPU(执行) vs SF/HWC(合成) 四段，用 `actual_frame_timeline_slice.jank_type` 定责到谁；GPU bound 要 `gpu_counter`(busy) 佐证（第 25 篇）。

**易错点（红榜）**
- "ANR 是 CPU 不够"。错：本质是有消息（常是主线程自己）长时间占住 loop 不返回。
- "OOM 和 LMKD 一回事"。错：一个是内核、一个是用户态监听 PSI；A17 又加 Memory Limiter 第三路。
- "掉帧一定是 GPU 慢"。错：主线程/RenderThread 喂不及更常见（第 25 篇 GPU bound 判定）。

**延伸阅读**：第 19 篇（全链路排查）、第 21 篇（主线程阻塞/内存/Binder SQL）、第 23 篇（场景四 卡顿发热）、第 25 篇（GPU/battery SQL）、第 8/12 篇（Memory Limiter）。

---

## 专题七：HAL / Linux Kernel / drivers 驱动 —— epoll/futex/cgroup/EAS + GKI 字符驱动骨架

**现象 / 考官提问**
> 1) Android 为什么大量用 epoll？2) `synchronized`/`wait-notify` 底层是 futex 吗？3) LMKD/cgroup 怎么限内存？4) Android 调度器有什么特别的（EAS/uclamp）？5) GKI 下怎么写一个能被加载的字符驱动？

**底层原理 + 源码落点（GKI common-android14-6.1）**

1. **epoll —— Android 事件循环的基石**
   `epoll_create1` / `epoll_ctl` / `epoll_wait`（`fs/eventpoll.c`）。Android 几乎所有 native 事件循环都建在 epoll 上：native `Looper`（专题一）、`SurfaceFlinger`、`InputReader/Dispatcher`、`vold`、`netd`、`lmkd`……原因：单线程就能高效监听成千上万个 fd，避免每个 fd 一个线程 + 忙等，CPU 友好、延迟低。

2. **futex —— 用户态锁的基石**
   `futex(2)`（`kernel/futex/`）。ART 的 `Monitor`、Java `synchronized`、`Object.wait/notify`、`atomic` 都最终落到 futex 的 `FUTEX_WAIT`/`FUTEX_WAKE`。**锁竞争 → 线程在 futex 上睡眠**，Perfetto 的 `monitor_contention` 就是测 futex 等待时长（第 21 篇主线程阻塞 SQL 的底层）。结论：主线程卡住的常见真凶不是 CPU，是 futex 上等一把被别人持有的锁。

3. **cgroup + LMKD（内存隔离）**
   Android 用 cgroup 做 CPU/IO/内存分组：`/sys/fs/cgroup`（v2 逐步推进，legacy 有 `cpuctl`/`cpuset`）。LMKD 监听 `PSI`（`/proc/pressure/memory`，mem.pressure）与低内存事件，按 `oom_score_adj`（来自 `ActivityManager` 的 `oom_adj`）决定杀谁（专题六路径 B）。`dev/cgroup` 或 `cgroupfs` 挂载随版本。

4. **EAS（Energy Aware Scheduling）+ uclamp**
   Android 调度器在 CFS 基础上启用 **EAS**（`kernel/sched/`，`sched/fair.c` + energy model），结合 big.LITTLE/三簇架构选"能效最优"的 CPU；配 `schedutil` 调频器。关键 API：**`uclamp`**（utilization clamp，`UCLAMP_MIN`/`UCLAMP_MAX`）——应用/框架可以"暗示"调度器自己的算力下限/上限。`PerformanceHintManager`（ADPF，第 4/19 篇）和游戏帧率提升就是靠 `sched_setattr` 设 uclamp 把关键线程钉到高性能簇。

5. **GKI 下的字符驱动骨架（真·可写）**
   GKI 要求 vendor 驱动只用 **KMI 稳定导出符号**（`EXPORT_SYMBOL_GPL`），否则 OOT 模块在 GKI 内核上加载失败。最小字符驱动（用 `miscdevice` 最简）：
   ```c
   #include <linux/module.h>
   #include <linux/miscdevice.h>
   #include <linux/fs.h>
   #include <linux/uaccess.h>

   static ssize_t my_read(struct file *f, char __user *buf, size_t n, loff_t *off) {
       const char *msg = "hello from gki driver\n";
       return simple_read_from_buffer(buf, n, off, msg, strlen(msg));
   }
   static ssize_t my_write(struct file *f, const char __user *buf, size_t n, loff_t *off) {
       char kbuf[32];
       if (copy_from_user(kbuf, buf, min(n, sizeof(kbuf)-1))) // 必须走 copy_from/to_user
           return -EFAULT;
       return n;
   }
   static const struct file_operations my_fops = {
       .owner = THIS_MODULE, .read = my_read, .write = my_write,
   };
   static struct miscdevice my_dev = { .minor = MISC_DYNAMIC_MINOR, .name = "mygki", .fops = &my_fops };
   static int __init my_init(void) { return misc_register(&my_dev); }
   static void __exit my_exit(void) { misc_deregister(&my_dev); }
   module_init(my_init); module_exit(my_exit);
   MODULE_LICENSE("GPL");   // GKI 必须 GPL, 且只用 KMI 导出符号
   ```
   - 真实 GKI 开发两条路：① 只用 KMI 导出符号写常规 `tristate` 模块；② 用 **vendor hook**（`CONFIG_VENDOR_HOOK`，GKI 预留的可注册扩展点，如 `register_vendor_hook`）在不改 common 内核的前提下插桩。binder 本身就在 GKI common 内核（`drivers/android/binder.c`）。

**易错点（红榜）**
- "Android 用 select/poll"。错：统一 epoll。
- "synchronized 是 JVM 自旋"。错：竞争时落 futex 睡眠。
- "GKI 随便写驱动"。错：必须 KMI 稳定符号 / vendor hook，否则加载失败。
- "LMKD 就是内核 OOM"。错：用户态 + PSI，且 A17 又加了 Memory Limiter 第三路。

**高频追问链**
1. epoll 和 Looper 怎么连起来？-> 专题一 native `Looper` 的 `mWakeEventFd` 就是注册进 epoll 的一个 fd，外加各类 native fd（InputChannel 等）。
2. uclamp 怎么影响游戏帧率？-> ADPF `PerformanceHintManager` 给游戏渲染线程设高 `UCLAMP_MIN`，调度器优先放高性能核 + 拉频（第 4 篇 Power HAL/ADPF）。
3. 为什么 GKI 要 KMI？-> 让 vendor 内核模块与 Google 维护的 common 内核 ABI 稳定解耦，OEM 能独立收安全更新（第 4/7 篇 GKI/KMI）。

**延伸阅读**：第 1 篇（binder.c 一次拷贝/mmap）、第 4 篇（GKI/KMI/DDK/vendor hook）、第 7 篇（GKI 2.0 / Kernel 6.1）、第 21 篇（monitor_contention 锁竞争）、第 8/12 篇（Memory Limiter）。

---

## 专题八：MTK 平台特有 —— AEE / mtklog / PerfService / thermal / vendor HAL（真缺口补全）

> 前 25 篇只在第 1 篇提了 MTK 的 DuraSpeed/AEE 名词。MTK 平台（of 国产机型大半）面试常考"你懂不懂 MTK 的崩溃/日志/调频体系"。以下为 vendor 实现，**路径随平台/Android 版本变化**，以真机为准。

**1. AEE（Application Exception Engine）—— MTK 崩溃捕获**
- 守护进程 `exp_main`（`vendor/mediatek/proprietary/external/aee/exp_main`），监听 native crash / kernel panic / watchdog / 系统异常。
- 触发后写 **db 文件**（如 `/data/aee_exp/` 或 `/sdcard/mtklog/aee_exp/db.XX.XXXX.dbg`）含 backtrace、寄存器、logcat、process map；并提供 `aee`-coredump。
- 内核态用 **`mrdump`**（MTK ramdump）在 kernel panic 时抓全内存。面试常问："怎么用 AEE db 定位 native crash"——解开 db 包看 `exp_main` 生成的 `SYS_MINI_RDUMP`/`ZZ_INTERNAL` 里的 tombstone/backtrace。

**2. mtklog 三件套（现场抓日志）**
- `mobilelog`（main/logcat + kernel）、`netlog`（网络）、`metaLog`（modem/RIL）。常由 `mtklogger` App 或 `logkit`/`mobilelog` 守护开启，落 `/sdcard/mtklog/`。
- 用法：`adb shell mtklog` 或拨号盘 `*#*#3646633#*#*`（EngineerMode）开 logger；`aee -c` 清历史。对应排查：native crash 看 `aee_exp`，ANR/性能看 `mobilelog` 里的 `events/logcat`。

**3. PerfService（游戏/性能调频）**
- `libperfservice.so` + `PerfServiceWrapper`（`vendor/mediatek/proprietary/frameworks/.../perfservice/`）。作用：把关键线程/集群**钉到高频、暂时忽略 thermal 限制**，给游戏/相机降卡顿。
- API：`PerfServiceNative_userReg` / `userEnable` / `userDisable`，按场景 boost 指定 CPU 簇频率、核数、GPU/DDR 频率。与 Google 的 ADPF（`PerformanceHintManager`）是两套并存机制（OEM 适配层常把 ADPF 翻译成 PerfService 调用）。

**4. MTK Thermal / 电源（vendor HAL + 内核驱动）**
- Thermal：`vendor.mediatek.hardware.thermal` HAL（`thermal_engine`/`thermalhal`）+ 内核 `drivers/thermal/mediatek/`（读 TSENSOR、限频策略）。
- 电源/充电：`drivers/power/supply/mediatek/`、`mtk-battery`、`mtk-charger`；`mtk_wdt`（`drivers/watchdog/mtk_wdt.c`，看门狗，超时被 AEE 抓）。

**5. 内核驱动常见 MTK 路径（GKI 之外的 vendor 部分）**
```
drivers/soc/mediatek/        SOC 拓扑 / 电源域
drivers/memory/mtk-smi.c      SMI(内存子系统接口, IOMMU 桥)
drivers/mmc/host/mtk-sd.c     MSDC(MMC/SD 控制器)
drivers/clk/mediatek/         时钟
drivers/gpu/drm/mediatek/     DRM 显示
vendor/mediatek/hardware/     HIDL/AIDL vendor HAL (MtkSystemService 等)
```

**6. MTK 与 GKI 的冲突（面试深水）**
MTK 大量 out-of-tree 驱动要随内核 ABI 走。GKI 要求 common 内核 KMI 稳定，MTK 要么把这些驱动放进 **KMI 兼容模块**，要么用 **vendor hook / vendor module** 机制。这是"为什么 MTK 机型收 Android 安全更新慢"的底层原因之一（第 4/7 篇 GKI 主线）。

**MTK 面试定位法速记**
```
native crash  -> /data/aee_exp/ 的 db 文件, 解包看 backtrace
系统 ANR/卡顿 -> /sdcard/mtklog/mobilelog 的 events + logcat
游戏掉帧调频  -> PerfService 是否 enable, 对比 ADPF
发热降频      -> thermal HAL + mtk-thermal 驱动 + /sys 温控节点
kernel panic  -> mrdump + aee_exp 的 kernel db
```

**易错点（红榜）**
- "AEE 是 Google 的"。错：MTK proprietary，`exp_main` 守护。
- "mtklog 和 logcat 一样"。错：mtklog 含 modem/meta/kernel，比纯 logcat 全。
- "PerfService 就是 ADPF"。错：并存，OEM 常把 ADPF 翻译为 PerfService。
- "GKI 之后 MTK 驱动全进 mainline"。错：仍大量 vendor 模块 + vendor hook。

**延伸阅读**：第 4 篇（GKI/KMI/vendor hook）、第 7 篇（GKI 2.0）、第 1 篇（MTK DuraSpeed/AEE 名词）、第 19 篇（发热降频链路）。

---

## 9. 跨专题易错红榜 TOP20（压轴速记）

1. 主线程 Looper 死循环在 epoll 休眠，CPU 占用近 0（专题一）。
2. ANR 是"某消息处理太久导致后续消息饿死"，不是 loop 的锅（专题一）。
3. 同步屏障漏 `removeSyncBarrier` 会永久挡普通 UI 消息（专题一）。
4. 消息用 `obtain()` 复用对象池，别每次 new（专题一）。
5. Binder 线程池默认 15 + 1 主线程，可并发 16 路（专题二）。
6. oneway 满也排队（线程/缓冲耗尽在驱动层等待）（专题二，呼应 19/21）。
7. `linkToDeath` 异步回调且在 binder 线程，别在里面做 UI（专题二）。
8. 跨 VM / 经 system_server 中转时 `getCallingUid()` 不可信（专题二，呼应 12/13）。
9. `onInterceptTouchEvent` 仅 ViewGroup 有（专题三）。
10. DOWN 不消费则后续事件直接走 Activity（专题三）。
11. `requestDisallowIntercept` 对 DOWN 无效（DOWN 清标志）（专题三）。
12. 中途拦截子 View 收的是 CANCEL 不是 UP（专题三）。
13. `onTouch` 消费后 `onTouchEvent`/`onClick` 不触发（专题三）。
14. `wrap_content` 不处理 AT_MOST 会撑满父（专题四）。
15. `getMeasuredWidth`(measure 后) ≠ `getWidth`(layout 后) 可短暂不等（专题四，呼应 20）。
16. 冷启动隐性大头是 ContentProvider 前置初始化（专题五，呼应 20/24）。
17. 带返回值的 Composable 不是重组边界（专题五，呼应 13）。
18. 三杀路径辨析：内核 OOM / LMKD+PSI / A17 Memory Limiter（专题六，呼应 8/12）。
19. Android 锁竞争真凶是 futex 睡眠，不是 CPU（专题七，呼应 21）。
20. GKI 驱动必须 KMI 稳定符号 / vendor hook；MTK 大量 vendor 模块（专题七/八）。

---

## 10. 三条高频追问链（跨专题综合）

**链 A：一次"主线程卡死"能从哪几个角度讲？**
Looper 在 epoll 休眠等消息 -> 某 `dispatchMessage`（常是 onCreate / onTouchEvent / 第三方 SDK 同步 IO）长时间占住 -> 后续消息（含 InputDispatcher 派来的触摸）饿死 -> system_server 看门狗超时 5s 弹 ANR。定界：Perfetto `thread_state` + `monitor_contention`（futex 锁竞争）定位谁占了主线程（专题一/六/七/二十一）。

**链 B：一次滑动掉帧怎么从 CPU 挖到 GPU 再挖到 MTK 调频？**
主线程 TRAVERSAL(draw) 慢 / RenderThread 录制慢 / GPU 执行慢 / SF-HWC 合成慢 —— `actual_frame_timeline_slice.jank_type` 定责（专题六，呼应 19/25）；若 GPU busy 高用 `gpu_counter` 佐证 GPU bound（专题 25）；发热时 MTK `thermal` HAL + PerfService/ADPF(`uclamp`) 拉频或降频决定帧率（专题七/八）。

**链 C：一个 App 莫名被杀/耗电，怎么用 MTK + 三条杀路径定位？**
先看死因：`ApplicationExitInfo` 的 REASON（LMK / OOM / A17 `MemoryLimiter:AnonSwap` 三路辨析，专题六）；耗电看 `dumpsys batterystats` + Perfetto `android.power`（专题 25）；native crash 用 AEE `db` 解 backtrace（专题八）；锁竞争 futex 用 Perfetto `monitor_contention`（专题七/二十一）。

---

## 11. 数据源 / AOSP 路径清单（本篇引用）

| 子系统 | 路径（android-14.0.0_rXX / GKI android14-6.1） |
| --- | --- |
| Handler/Looper | `frameworks/base/core/java/android/os/Handler.java` / `Looper.java` / `MessageQueue.java` / `Message.java` |
| native MessageQueue | `frameworks/base/core/jni/android_os_MessageQueue.cpp` |
| native Looper(epoll) | `system/core/libutils/Looper.cpp` |
| View 事件分发 | `frameworks/base/core/java/android/view/View.java`(dispatchTouchEvent/onTouchEvent) / `ViewGroup.java`(dispatchTouchEvent/onInterceptTouchEvent/requestDisallowInterceptTouchEvent) / `MotionEvent.java` |
| 分发起点 | `frameworks/base/core/java/android/app/Activity.java` / `com/android/internal/policy/DecorView.java` / `PhoneWindow.java` |
| MeasureSpec | `frameworks/base/core/java/android/view/View.java`(MeasureSpec/getMeasuredWidth/getWidth) / `ViewGroup.java`(getChildMeasureSpec) |
| Binder 服务端 | `frameworks/native/libs/binder/ProcessState.cpp`(startThreadPool/spawnPooledThread) / `IPCThreadState.cpp`(joinThreadPool/getAndExecuteCommand) / `Binder.cpp` |
| Binder Java | `frameworks/base/core/java/android/os/Binder.java`(linkToDeath/clearCallingIdentity) / `BinderProxy.java` |
| 内核 Binder | `drivers/android/binder.c`(max_threads/binder_transaction/BR_SPAWN_LOOPER/BR_DEAD_BINDER) |
| 冷启动 | `frameworks/base/core/java/android/app/ActivityThread.java`(handleBindApplication/installContentProviders/main) / `ActivityTaskManagerService.java` |
| Compose | `frameworks/base/libs/` + Compose compiler plugin(IR $composer/$changed) + runtime SlotTable/Snapshot |
| 调度器/EAS/uclamp | `kernel/sched/fair.c` / `kernel/sched/core.c`(sched_setattr, uclamp) |
| epoll/futex | `fs/eventpoll.c` / `kernel/futex/` |
| cgroup/PSI/LMKD | `/sys/fs/cgroup` / `kernel/sched/psi.c` / `system/core/lmkd/` |
| GKI 驱动 | `drivers/android/binder.c`(common) / `include/linux/miscdevice.h` / vendor hook(`CONFIG_VENDOR_HOOK`) |
| MTK AEE | `vendor/mediatek/proprietary/external/aee/exp_main` + `/data/aee_exp/` |
| MTK mtklog | `mobilelog`/`netlog`/`metaLog` + `mtklogger` + `/sdcard/mtklog/` |
| MTK PerfService | `vendor/mediatek/proprietary/frameworks/.../perfservice/`(`libperfservice.so`) |
| MTK 内核驱动 | `drivers/soc/mediatek/` / `drivers/memory/mtk-smi.c` / `drivers/mmc/host/mtk-sd.c` / `drivers/watchdog/mtk_wdt.c` / `drivers/thermal/mediatek/` |
| MTK vendor HAL | `vendor/mediatek/hardware/`(MtkSystemService 等) |

---

## 12. 26 篇交叉索引（前 25 篇 -> 本篇）

| 篇 | 主题 | 本篇衔接 |
| --- | --- | --- |
| 01~14 主/拓展/深挖/图形/基建/A17/渲染/TEE/pKVM/智能/座舱/收官 | 单点专题（约 132） | 本篇把其中"只提名词"的底层机制一次挖透 |
| 15 速查卡 | 15 篇知识地图 | 全局 |
| 16 连击考 | 考官连击形态 | 本篇 §10 三条链即"连击"的底层版 |
| 17 全链路排查 | 冷启动/卡顿/ANR/内存/发热/Binder | 本篇 §五/六/七为其底层基石 |
| 18 code walk | startActivity->首帧 / Binder 事务 | 本篇 §四/五/二为其 native 补刀 |
| 19 Perfetto SQL | 启动/掉帧/主线程/Binder/电源 SQL | 本篇 §七 futex/§六 三杀为其原理层 |
| 20 A18 桌面融合 | freeform/WM Shell/CDM | 本篇 §三 系统抢事件与之呼应 |
| 21 系列累计 | 153 专题闭环 | — |
| 22~25 Perfetto/SQL 扩充/ART/pKVM 等 | 专项深水 | 本篇 §二/六/七跨 VM UID/三杀/MemoryLimiter 呼应 |

> 至此系列 26 篇 / 约 175 专题：单点(1~14) + 复习(15~16) + 实战(17~19) + 源码(20) + 综合(21~25) + **核心基础查缺补漏(26)**。本篇专门补全"经典八股只背不深"的虚点（native Looper/epoll、事件分发三方法+CANCEL、MeasureSpec、Binder 线程池+死亡通知、GKI 字符驱动、MTK AEE/mtklog/PerfService）。**真·未覆盖角度所剩**：真题大乱斗 vol.2（更刁钻多子系统叠加）、KMP/skiko 非 Android target 运行时深水区（第十五篇已部分覆盖 Android 侧差异）。

---

*本篇为每日自动化产出，落盘工作区根目录（文件名带日期），并推送飞书云文档 AOSP 文件夹 + bot 私聊链接。复习建议：先背 §9 红榜 20 条建立"经典八股到底层"的肌肉记忆，再把 §1/§3/§4 的源码方法名在 AOSP 里点开对照一遍；面试按"Java 层语义 -> native 层机制(epoll/futex) -> 易错红榜 -> 追问链"四段口述，比背八股得分高得多。*
