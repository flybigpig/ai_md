# Android Framework 面试题 · 秋招决战期·高频八股源码级深扒（第三轮：native 定责 + 易错雷区 + 追问压测）（第 49 篇）

> 日期：2026-09-01（周二）｜ baseline：Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX，内核 GKI android14-6.1) ｜ 系列第 49 篇 / 累计约 **300 专题**
> 适用场景：秋招密集面试期（9–11 月）高频八股精练 + native 定责视角 + 考官连环追问压测
> 说明：承 8/27 跨版本演进查缺补漏、8/31 ART 内存与 GC 实战，本篇做**第三轮**——把用户显式点名的全部领域（Handler/Looper、Binder IPC、AMS/ATMS、WMS、View 绘制与测量、App 启动、内存/卡顿/ANR、Jetpack/Compose、HAL/Linux kernel/drivers、MTK）按「**源码级 native 定责 + 易错雷区清单 + 高频追问压测**」重新组织。本篇刻意强化两件事：①前序文章只点到名的 native 底层细节一次凿穿（如 `binder_transaction` 的 buffer 回收、`Looper.cpp` 的 epoll/futex 双机制、`ViewRootImpl` 输入分发到 `Choreographer` 的帧定责）；②把「考官会怎么追问」做成可直接口述的速答。

---

## 0. 当日热点锚定（2026-09-01）

- **A17 QPR1 stable 落地窗口（跟踪 2026-09）**：此前 8/27 预测的「A17 QPR1 随 Pixel Feature Drop 落地」已进入 9 月窗口；面试中「A14 vs A17 这块有变化吗」权重持续走高——务必能答出"接口稳定、演进在 Choreographer/帧定责/安全边界侧"。
- **A18 Aluminium OS / Googlebook 9.15 纽约发布会倒计时约 2 周**：首个 mainline 维护的 x86 Android 端口，vendor 模块须同时过 ARM64 + x86_64 两套 KMI；WMS/display 把外接 DP/HDMI 热插拔 + 真·多窗口做成一等公民；Magic Pointer 输入语义成为 Input 新考点。
- **秋招趋势**：跨版本演进 + 连环追问是区分候选人深度的两把刀。Handler/Binder/Compose/ANR/Native crash/量化数字仍是最高频深考题；**"能讲清 native 层"** 是 senior 档的硬门槛。
- **本篇主线**：经典八股不做概念复述，直接下钻 native 源码；每题给「易错雷区」+「追问速答」，结尾给 20 条压测速答。

> 约定：文中 `.java/.cpp/.kt` 路径默认是 **Android 14 AOSP (android-14.0.0_rXX)**；内核路径为 **GKI common-android14-6.1**；MTK 为 vendor 实现，路径随平台/Android 版本变化，下列为常见形态。A17+ 增量标注 `[A17]`。

---

## 专题一：Handler / Looper / MessageQueue —— 主线程死循环为什么不 ANR？

### 问题
"主线程 `Looper.loop()` 是死循环，为什么不会把 CPU 跑满、也不会 ANR？消息队列空了线程在干什么？同步屏障、IdleHandler、消息池分别解决什么？A14->A17 这块有变化吗？"

### 答案解析 + 底层原理（从 Java 一路挖到 epoll）
整条链路：`Handler.sendMessage` -> `MessageQueue.enqueueMessage` -> `Looper.loop()` -> `MessageQueue.next()` -> `nativePollOnce` -> **native `Looper` 用 `epoll` 阻塞** -> 被 `wake()` 唤醒 -> 回 Java 分发 `msg.target.dispatchMessage`。

1. **Java 层循环（`frameworks/base/core/java/android/os/Looper.java`）**
   `loop()` 里 `for(;;)`：`Message msg = queue.next();` 然后 `msg.target.dispatchMessage(msg);`。关键：`queue.next()` 无消息时**阻塞**，不自旋空转。

2. **native 阻塞真相（`frameworks/base/core/jni/android_os_MMessageQueue.cpp` + `system/core/libutils/Looper.cpp`）**
   `MessageQueue.next()` 调 `nativePollOnce(ptr, nextPollTimeoutMillis)`。native 侧 `NativeMessageQueue` 持有 native `Looper`（`system/core/libutils/Looper.cpp`）。`Looper::pollOnce` -> `pollInner` 最终调 **`epoll_wait`** 监听 `mWakeEventFd`（Android 2.3+ 取代旧 pipe）。
   - `nextPollTimeoutMillis == -1`（无消息、无定时消息）时，`epoll_wait` 无限阻塞，主线程进入 **interruptible sleep（S 状态）**，CPU 完全释放——"死循环不卡死"的根因：**它其实在休眠，不在跑**。
   - 有消息入队时 `enqueueMessage` 调 `nativeWake` -> `Looper::wake()` -> `write(mWakeEventFd, 1)` 往 eventfd 写一个字节 -> `epoll_wait` 立即返回 -> 线程唤醒。
   - 这是经典 **"one thread, one looper, epoll event loop"** 模式，与 libevent / libuv / Netty 的 reactor 本质相同。

3. **为什么"死循环"不 ANR，反而是"消息处理太久" ANR？**
   ANR **不是** loop 自己抛的，而是 **system_server 看门狗**（AMS `AppErrors` / `BroadcastQueue` / `InputDispatcher` 超时监测）发现的：
   - 输入事件超时 `5s`（`InputDispatcher` 派发触摸后等应用 `finishInputEvent`）；`BroadcastReceiver` 前台 `10s`/后台 `60s`；`Service` 生命周期 `20s`；`ContentProvider` `10s`。
   - `onCreate`/`onTouchEvent` 本质都在某条 `Message` 的 `dispatchMessage` 里跑。**只要这个 handler 跑太久没返回，loop 就回不到 `next()` 取下一个消息**，于是 `InputDispatcher` 派下来的触摸消息一直排不上 -> system_server 等不到响应 -> 弹 ANR。
   - 准确说法：**Looper 死循环是"等消息"，ANR 是"处理某条消息时阻塞导致后续消息（含系统输入）饿死"。loop 本身永远无罪。**

4. **同步屏障（`MessageQueue.postSyncBarrier()`）**：插入 `target == null` 的屏障，`next()` 遇屏障**跳过所有同步消息、只取异步消息**（`Choreographer` 的 `MSG_DO_FRAME` 是异步消息），保证渲染优先。`removeSyncBarrier()` 必须配对移除，否则队列卡死。

5. **IdleHandler（`MessageQueue.java`）**：队列空且无可运行异步消息时触发 `mIdleHandlers`（`ActivityThread.GcIdler`、`ActivityThread.Idler` 上报 `ActivityIdle`）。`queueIdle()` 返回 false 表示一次性。

6. **消息池（`Message.sPool`）**：单链表复用池，`MAX_POOL_SIZE = 50`，`obtain()`/`recycleUnchecked()` 复用，避免高频消息 GC 抖动。

### 跨版本演进（A14 -> A17 -> A18）
核心机制稳定跨版本，`epoll` 休眠模型自 Android 2.3 起未变。A17/A18 仅在 JankStats / 帧调度侧增强（Choreographer 与 `FrameTimeline` 联动更紧），Looper/MessageQueue 公共契约不变。被问"有没有变化"答：**接口与休眠模型稳定，演进在 Choreographer/帧定责侧，不在 Looper 本身**。

### 易错雷区
1. 主线程空闲是 `epoll` 休眠非空转——`top` 看到主线程 0% CPU 正常。
2. 同步屏障 `target==null`，普通 `Handler` 发的消息 `target` 非 null，不过屏障。
3. `Handler` 内存泄漏：非静态内部类持外部 `Activity`，用静态类 + `WeakReference` 或 `onDestroy` 调 `removeCallbacksAndMessages(null)` 清理。
4. `Looper.quit()` 调 `removeAllMessagesLocked` 后 `nativeWake`；`quitSafely()` 只清未来消息、等当前跑完——主线程 `Looper` 不能 quit（会杀 APP）。

### 考官高频追问速答
- "postDelayed 延时怎么保证？" -> `enqueueMessage()` 按 `when` 插入有序队列；`nativePollOnce` 的 timeout = `nextMessage.when - now`，到点唤醒。
- "为什么 `Choreographer` 用异步消息？" -> 保证 vsync 帧回调不被普通消息饿死（接专题六卡顿定责）。
- "子线程能建 Handler 吗？" -> 可以，但必须先 `Looper.prepare()` + `Looper.loop()`，否则 `new Handler()` 抛 `RuntimeException: No Looper; Looper.prepare() wasn't called`。

### 延伸阅读
`frameworks/base/core/java/android/os/Looper.java` + `MessageQueue.java` 对照 `system/core/libutils/Looper.cpp` 的 epoll 实现；`frameworks/base/core/jni/android_os_MessageQueue.cpp`。

---

## 专题二：Binder IPC —— 一次拷贝、线程池 15、oneway 排队、getCallingUid 不可信

### 问题
"Binder 一次事务到底拷贝几次？为什么是 mmap 不是多次 copy？Binder 线程池默认多大？oneway 会阻塞吗？跨进程 `getCallingUid()` 为什么有时不可信？A17 在 Binder 安全上做了什么？"

### 答案解析 + 底层原理
1. **一次拷贝**：`BpBinder.transact()` -> `IPCThreadState::transact()` -> `writeTransactionData()` 写 `Parcel` 进 `mOut` -> `talkWithDriver()` 经 `ioctl(BINDER_WRITE_READ)` 入 `drivers/android/binder.c` 的 `binder_ioctl` -> `binder_thread_write` -> `binder_transaction()`。
   - `binder_transaction()` 在内核态用 `copy_from_user()` **把发送方用户态 Parcel 拷到内核 binder_buffer 一次**；接收方通过 **mmap 共享同一内核 buffer**（`binder_mmap`），接收方 `BR_TRANSACTION` 直接读映射区，**无需第二次拷贝**。

2. **线程池默认 15**：`frameworks/native/libs/binder/ProcessState.cpp` `mMaxThreads = 15`（实际并发上限受 `binder_proc` 限制）；`BR_SPAWN_LOOPER` 可动态扩容。`startThreadPool()` + `joinThreadPool()` 让 `IPCThreadState` 循环 `getAndExecuteCommand()` -> `executeCommand(BR_TRANSACTION)` -> `onTransact()`。

3. **oneway 也排队**：`oneway` 不阻塞发送方返回，但接收方 `binder_transaction` 仍串行入 `binder_proc.todo` / `binder_thread.todo`。对端 15 线程全忙时 oneway 在队列等待——oneway 满也是导致对端 LMK/ANR 的源头之一。

4. **binder_buffer 回收**：事务完成（接收方 `BR_TRANSACTION` 处理完调 `BR_REPLY` 或 `put_user` 回 `BC_FREE_BUFFER`）后，`binder_transaction_buffer_release` 释放 buffer 节点；跨进程 fd 用 `binder_fd 数组` 经 `fget`/`fput` 引用计数管理，fd 泄漏会吃满 `/dev/binder` 的 fd 上限（默认 1MB-8KB 缓冲区 + fd 数限制）。

5. **getCallingUid 不可信两场景**：①跨 VM（AVF/pKVM）RPC Binder——`getCallingUid()` 返回 VM 内 UID，host/guest 内核命名空间不同；②Provider/系统服务侧——`Binder.getCallingUid()` 拿到 `SYSTEM_UID` 不可信，必须 attestation/签名校验。`clearCallingIdentity()` / `restoreCallingIdentity()` 临时切换身份。

6. **linkToDeath**：`IBinder.linkToDeath()` 注册 `DeathRecipient`，对端进程死时收 `binderDied()`，用于重连（如 `ActivityManager` 死连 system_server）。

### 跨版本演进（A14 -> A17 -> A18）
三大上下文（`/dev/binder`、`/dev/hwbinder`、`/dev/vndbinder`）稳定。**A17 安全演进**：防 confused-deputy 在 Telecom/Phone 侧前置校验（如 `PhoneInterfaceManager` 校验 MMI 前缀），并强化跨 VM Binder 身份——呼应"getCallingUid 不可信"在 AVF/pKVM 场景更突出（A17 端侧 AI 跑 Microdroid 变多）。**AIDL for HAL 取代 HIDL** 进程持续（A10+），A17 新 HAL 几乎全 AIDL。

### 易错雷区
1. "一次拷贝"非零拷贝——相对 socket/管道（两次拷贝）更优，但发送方到内核仍有一份 `copy_from_user`。
2. `mmap` 只解决接收方读，发送方到内核仍拷贝。
3. oneway 的"异步"指**不阻塞调用方**，不是"不排队"——对端线程池满照样堵。
4. `TransactionTooLargeException`：单事务 Binder 缓冲区上限约 1MB-8KB（含 `binder_buffer` 头），`Bundle` 传大图/大列表必崩。

### 考官高频追问速答
- "Binder 和 socket 比优在哪？" -> 一次拷贝 + 内存映射 + 内核对象引用（fd/object）随事务传递；socket 两次拷贝且需自己序列化句柄。
- "为什么 Binder 比共享内存安全？" -> 内核做身份校验（`binder_get_thread`/`binder_transaction` 写 `sender_euid`/`sender_pid`），共享内存无内建身份。
- "AIDL oneway 方法能返回吗？" -> 不能，oneway 方法返回类型必须 `void` 且不能有 out/inout 参数。

### 延伸阅读
`frameworks/native/libs/binder/{IPCThreadState,ProcessState}.cpp`；`drivers/android/binder.c` 的 `binder_transaction` / `binder_thread_write`；`drivers/android/binder_alloc.c`。

---

## 专题三：AMS / ATMS 与 App 启动流程

### 问题
"从桌面点图标到 `Activity.onCreate`，完整链路是什么？进程是怎么创建的（Zygote fork）？`Application`/`ContentProvider`/`Activity` 初始化顺序？冷启动慢怎么从 ART 视角优化？"

### 答案解析 + 底层原理
1. **startActivity 全链路（应用侧 -> system_server -> zygote -> 应用进程）**
   - 应用侧：`Activity.startActivity` -> `Instrumentation.execStartActivity` -> `ActivityTaskManager.getService().startActivity`（跨进程到 **ATMS**）。
   - system_server 侧：`ActivityTaskManagerService.startActivity` -> `ActivityStartController.execute` -> `ActivityStarter.execute` -> `startActivityUnchecked` -> 解析 `Intent`/`Flag`/`stack` -> `ResumeActivityItem` 决策。
   - 若目标进程未起：`ActivityStackSupervisor.startSpecificActivity` -> `ActivityManagerInternal.startProcessLocked` -> `Process.start`（经 `ZygoteProcess` 用 **`LocalSocket` 给 Zygote 发 fork 参数**）。
   - Zygote 侧：`ZygoteServer.runSelectLoop` 收到 fork 请求 -> `Zygote.forkAndSpecialize` -> 子进程 `ActivityThread.main()`。

2. **Zygote fork 的本质**：Zygote 进程预加载 `framework.jar` / `boot.oat` / `preloaded-classes` / `preloaded-resources`，fork 出子进程**共享已映射的只读内存（COW）**，子进程再 `specialize`（设 UID/GID、挂载 namespace、`bindApplication`）。这是 Android 启动快的根本——避免每个 APP 重 load 框架。

3. **`ActivityThread.main()` 之后**：`Looper.prepareMainLooper()` -> `attach(false)` -> `AMS.attachApplication` -> `bindApplication`（创建 `LoadedApk`、`Instrumentation`、`Application`）-> `mH` 收到 `BIND_APPLICATION` -> `handleBindApplication` -> `Application.onCreate` -> 再 `ActivityThread.handleLaunchActivity` -> `performLaunchActivity` -> `Instrumentation.callActivityOnCreate` -> `Activity.onCreate`。

4. **初始化顺序（关键易错）**：
   `Application.attachBaseContext` -> `ContentProvider.onCreate`（**在 Application.onCreate 之前！**）-> `Application.onCreate` -> `Activity.onCreate`。
   - 坑：`ContentProvider` 在 `Application.onCreate` 之前初始化，所以在 `ContentProvider.onCreate` 里访问还没准备好的单例会 NPE；早期 SDK 初始化（如 `MultiDex.install`、`Tinker`）必须放 `attachBaseContext`。

5. **冷启动慢的 ART 视角优化**：dex 未编译（`compile-filter=quicken/verify`）时首次运行大量走**解释执行 + JIT**，触发 `dex2oat` 后台编译；可用 `adb shell dumpsys package <pkg> | grep filter` 看 filter；推 `speed-profile`（装后跑一遍热路径生成 profile，`bg-dexopt` 编译）或 `speed` 全量编译；`dexpreopt` 在系统编译期预生成 `odex` 减首次开机负担。

### 易错雷区
1. `ContentProvider.onCreate` 早于 `Application.onCreate`——这是高频连环追问陷阱。
2. `Activity.onCreate` 里做 IO/主线程阻塞 -> 直接拉长冷启动 + 可能 ANR（Application 阶段超 20s 也 ANR）。
3. `MultiDex.install` 必须最早（`attachBaseContext`），放 `onCreate` 已晚。

### 考官高频追问速答
- "Zygote 为什么用 fork 不用新建进程？" -> 共享预加载的 framework 映射内存（COW），避免每 APP 重 load 上千类与资源，启动快、内存省。
- "为什么 Zygote 不用线程？" -> 进程隔离，APP 崩溃不影响 Zygote 与其他 APP；fork 后 COW 快照干净。
- "启动优化的核心指标？" -> 冷启动（进程创建->首帧 `reportFullyDrawn`）、温启动、热启动；用 Perfetto `android_startup` 表 + `activityStart`/`activityResume`/`Choreographer#doFrame` 定界。

### 延伸阅读
`frameworks/base/services/core/java/com/android/server/am/{ActivityManagerService,ActivityStartController,ActivityStarter,ProcessList}.java`；`frameworks/base/core/java/android/app/{ActivityThread,LoadedApk,Instrumentation}.java`；`frameworks/base/core/java/com/android/internal/os/Zygote.java`。

---

## 专题四：WMS 与 Window 体系

### 问题
"`Window`/`WindowManager`/`ViewRootImpl` 三者关系？`addView` 流程？窗口层级与 `token` 怎么管理？`windowSoftInputMode` 怎么影响布局？"

### 答案解析 + 底层原理
1. **三者关系**：`Window` 是抽象（具体 `PhoneWindow`），持有 `DecorView`；`WindowManager`（`WindowManagerImpl`）是 `View` 与 WMS 之间的客户端代理；`ViewRootImpl` 是**每个 `DecorView` 一对一的控制中心**——连接 `View` 树与 WMS，负责测量/布局/绘制调度、输入事件接收、VSync 注册。

2. **addView 流程（`WindowManagerImpl.addView` -> `WindowManagerGlobal.addView`）**
   - 创建 `ViewRootImpl(root)` -> `root.setView(view, params, panelParentView)`。
   - `setView` 内：`requestLayout()`（先 `scheduleTraversals` 排一次遍历）-> `mWindowSession.addToDisplayAsUser`（**跨进程到 WMS 的 `Session.addToDisplay`**）-> WMS 创建 `WindowState` 并挂到对应 `DisplayContent`/`WindowToken` 下 -> 返回 `mInputChannel` 给 `ViewRootImpl` 用于输入。
   - 之后 `ViewRootImpl` 通过 `mWindow`（一个 `IWindow.Stub` Binder）接收 WMS 的 `relayout`/`insets` 回调。

3. **窗口层级 (`z-order`) 与 token**：WMS 用 `WindowToken`（按 `IBinder` key，如 `ActivityRecord.token`）聚合同属一个逻辑的窗口；`WindowState` 按 `type`（`TYPE_APPLICATION` / `TYPE_STATUS_BAR` / `TYPE_INPUT_METHOD` 等）决定 base layer，`mBaseLayer + multiwindow` 计算最终 z。输入事件按 z 从上往下命中。

4. **软键盘 (`windowSoftInputMode`)**：`adjustResize` 让 WMS 给应用发 `insets`（`WindowInsetsCompat`），`ViewRootImpl` 重算 `DecorView` 尺寸触发 relayout；`adjustPan` 不 resize 只平移；`stateVisible`/`stateHidden` 控制初始显隐。`InputMethodManager` 与 WMS `WindowState` 的 `ImeInputTarget` 联动。

### 易错雷区
1. 一个 `Activity` 一个 `PhoneWindow` 一个 `DecorView` 一个 `ViewRootImpl`——但 `Dialog`/`PopupWindow`/`Toast` 都有各自 `ViewRootImpl`（PopupWindow 用 `TYPE_APPLICATION_PANEL` 挂到宿主 `WindowToken`）。
2. `Dialog` 必须依附 `Activity` 的 `WindowToken`，所以**不能在 `Application` 上下文 show Dialog**（无 token -> `BadTokenException`）。
3. `Toast` 在 Android 11+ 改由 `NotificationManager` 内部 `TN` Binder 显示，不再走应用 `ViewRootImpl` 的 token 约束，可在子线程 show（但需先 `Looper.prepare`）。

### 考官高频追问速答
- "Dialog 为什么不能用 Application Context？" -> `WindowManager` 需要 `WindowToken`（来自 Activity 的 `ActivityRecord`），Application 无 token。
- "PopupWindow 和 Dialog 区别？" -> PopupWindow 是 `TYPE_APPLICATION_PANEL` 浮在宿主 window 上、可任意定位、不强制模态；Dialog 是独立 `TYPE_APPLICATION` 窗口、默认模态。
- "为什么 `requestFeature` 必须在 `setContentView` 前？" -> `PhoneWindow` 在 `setContentView` 时才构建 `DecorView` 并应用 feature（如 `FEATURE_NO_TITLE`），之后改无效。

### 延伸阅读
`frameworks/base/core/java/android/view/{WindowManagerGlobal,ViewRootImpl,WindowManager}.java`；`frameworks/base/services/core/java/com/android/server/wm/{WindowManagerService,WindowState,WindowToken,Session}.java`。

---

## 专题五：View 绘制与事件分发

### 问题
"`MeasureSpec` 三种模式与 `AT_MOST` 坑？`requestLayout` / `invalidate` / `postInvalidate` 区别？事件分发三方法 + `CANCEL` + `requestDisallowIntercept`？自定义 View 注意什么？"

### 答案解析 + 底层原理
1. **MeasureSpec（32 位：高 2 位 mode + 低 30 位 size）**
   - `UNSPECIFIED`：父不对子施加约束（如 `ScrollView` 量子、`ListView` 量 header），子可任意大。
   - `EXACTLY`：父已定死尺寸（`match_parent` 或具体 dp），子必须接受。
   - `AT_MOST`：父给上限（`wrap_content`），子不能超过但可更小。
   - **`getChildMeasureSpec` 推导规则**（高频追问）：子 `wrap_content` 在父 `EXACTLY` 下得 `AT_MOST+父size`；子 `wrap_content` 在父 `AT_MOST` 下得 `AT_MOST+父size`；子 `match_parent` 继承父 mode/size。**坑**：自定义 `View` 的 `onMeasure` 不处理 `AT_MOST` 时，`wrap_content` 会等效 `match_parent`（直接撑满父），必须手写 `wrap_content` 默认尺寸。

2. **requestLayout / invalidate / postInvalidate**
   - `requestLayout()`：标记 `PFLAG_FORCE_LAYOUT` + `PFLAG_INVALIDATED`，从当前 View 向上到 `ViewRootImpl` 触发**完整 measure + layout + draw**（重测布局）。
   - `invalidate()`：只标脏区，**重 draw 不重 measure/layout**；必须在**主线程**调。
   - `postInvalidate()`：跨线程 post 回主线程再 `invalidate`。
   - `postOnAnimation` / `invalidate` 走 `Choreographer` 下一帧 vsync，避免一帧多次 draw。

3. **事件分发三方法（责任链）**
   - `dispatchTouchEvent`（分发）、`onInterceptTouchEvent`（仅 `ViewGroup`，拦截）、`onTouchEvent`（消费）。
   - 传递顺序：`Activity.dispatchTouchEvent` -> `PhoneWindow/DecorView.dispatchTouchEvent` -> `ViewGroup.dispatchTouchEvent` -> 递归子 `View` -> 叶子 `View.onTouchEvent`。
   - **`onInterceptTouchEvent` 返回 true**：事件流被该 `ViewGroup` 截走，后续 `MOVE/UP` 不再下发给子，直接进 `ViewGroup.onTouchEvent`；已被子消费的 `DOWN` 不可再被拦截（一旦子 `onTouchEvent` 在 DOWN 返回 true，`mFirstTouchTarget` 设上）。
   - **`CANCEL` 语义**：父在后续 `MOVE` 决定自己处理（如 `ScrollView` 滚动）时，会给子发 `ACTION_CANCEL`，子必须**把 pressed 状态/动画复位**，否则视觉卡在 pressed。`onInterceptTouchEvent` 在 `MOVE` 第一次返回 true 前，已向子发过 DOWN，此时要补一个 CANCEL。
   - **`requestDisallowInterceptTouchEvent(true)`**：子通知父**本次手势不要拦截**（设 `FLAG_DISALLOW_INTERCEPT`），父的 `onInterceptTouchEvent` 在 `DOWN` 之后被跳过；但父可在 `DOWN` 阶段先拦（子还没机会 disallow），且父可长按后强制清该 flag。典型用：内层 `RecyclerView` 横向滑动 vs 外层 `ViewPager2` 纵向——`ViewPager2` 内部已处理；手写嵌套滑动要配合 `NestedScrolling`。

4. **绘制三部曲 + 自定义 View 注意**
   - `measure`（确定尺寸）-> `layout`（确定位置）-> `draw`（渲染到 `Surface`，经 `Hwc`/`SurfaceFlinger` 合成）。
   - 自定义 `View` 注意：`onMeasure` 必须调 `setMeasuredDimension`；`onDraw` 里**不要 new 对象**（避免 GC 抖动，尽量用 `Rect`/`Paint` 成员变量）；`wrap_content` 处理 `AT_MOST`；支持 `padding`（自己算）；`onSaveInstanceState` 存恢复状态。

### 易错雷区
1. `wrap_content` 不处理 `AT_MOST` -> 等效 `match_parent`（自定义 View 头号坑）。
2. `onInterceptTouchEvent` 在 `DOWN` 时返回 false、在 `MOVE` 返回 true 是合法且常见的（如滚动判定），但要记得给子补 `CANCEL`。
3. 收到 `CANCEL` 不复位 pressed -> 按钮卡在按下态。
4. `invalidate` 在子线程调会抛 `CalledFromWrongThreadException`（只有 `postInvalidate` 跨线程）。

### 考官高频追问速答
- "`onTouchEvent` 和 `OnClickListener` 谁先执行？" -> `onTouchEvent` 先（`dispatchTouchEvent` -> `onTouchListener` -> `onTouchEvent` -> 若 UP 且没消费则 `performClick` 触发 `OnClickListener`）。返回 true 会吞掉 click。
- "事件一定从 `Activity` 开始吗？" -> 是的，`Activity.dispatchTouchEvent` 最先收到，`Window` 再往下。
- "`LinearLayout` 权重布局为啥 `wrap_content` 子拿不到预期尺寸？" -> `weight` 在 `EXACTLY` 下先按 `0dp` 量再按剩余分配；用 `0dp+weight` 而非 `wrap_content+weight`。

### 延伸阅读
`frameworks/base/core/java/android/view/{View,ViewGroup,MotionEvent}.java`；`frameworks/base/core/java/com/android/internal/policy/DecorView.java`；`frameworks/base/core/java/android/widget/ScrollView.java`（拦截示例）。

---

## 专题六：内存 / 卡顿 / ANR 优化

### 问题
"ANR 有哪些类型、各自超时？`Choreographer` 怎么定掉帧的责任？LMK 怎么杀进程？内存三路杀（Leak/Frame/OOM）怎么排查？"

### 答案解析 + 底层原理
1. **ANR 类型与超时**
   - Input dispatching：`5s`（`InputDispatcher` 派发触摸后等应用 `finishInputEvent`，见 `frameworks/native/services/inputflinger/InputDispatcher.cpp` 的 `mAnrTimeouts`）。
   - `BroadcastReceiver` 前台 `10s`/后台 `60s`（`BroadcastQueue` `mTimeoutPeriod`）。
   - `Service` 生命周期 `20s`（`ActiveServices.SERVICE_TIMEOUT`）；`ContentProvider` `10s`。
   - 触发后 `AppErrors.appNotResponding` 写 `/data/anr/traces.txt`（Android 11+ 改 `am anr` 经 `perfetto`/`tombstoned` 收集）并弹对话框。

2. **Choreographer 与掉帧定责**
   - `Choreographer` 注册 `DisplayEventReceiver` 收 VSync，`FrameDisplayEventReceiver` 在 VSync 到时回调 `doFrame`。`doFrame` 内按 `Choreographer.CALLBACK_*` 顺序跑：输入 -> 动画 -> 布局绘制（`TraversalRunnable`，即 `ViewRootImpl.doTraversal` -> `performTraversals`）-> COMMIT。
   - **掉帧定责**：若某帧 `doFrame` 内 `performTraversals` 耗时 > 16.6ms（60Hz），该帧被跳过（jank）；用 `FrameInfo` / `FrameTimeline`（[A17] 与 `JankStats` 联动更紧）区分是"应用 UI 线程慢"（measure/layout/draw 慢）还是"渲染/合成慢"（GPU/HWC/`SurfaceFlinger`）。
   - `Choreographer.FrameCallback` 的 `doFrame` 时间是**应用主线程掉帧**的直接信号。

3. **LMK（Low Memory Killer）**
   - 旧 `lowmemorykiller` 驱动按 `oom_adj`（现 `oom_score_adj`，`/proc/<pid>/oom_score_adj`，范围 -1000~1000）杀进程；Android 12+ 转用户态 **`lmkd`**（`system/core/lmkd`），策略由 `frameworks/base/services/core/java/com/android/server/am/ProcessList.java` 的 `computeOomAdj` 计算，前台 0、可见 100、服务 200、缓存 900+。
   - 内存紧张时 lmkd 按 `oom_score_adj` 从大到小杀，释放 `ionicache`/匿名页。

4. **内存三路杀排查**
   - **Leak（Java 堆泄漏）**：`LeakCanary` / `MAT` / `Android Studio Profiler` 抓 `Activity` 泄漏（非静态内部类 Handler、未反注册的 `Listener`、`static` 单例持 `Context`）；用 `hprof` + `hprof-conv` 分析 `Dominator Tree`。
   - **Frame（图形内存）**：`Bitmap` 像素自 Android 8 起默认走 **`GraphicBuffer`（Native/Graphics 堆，不占 Java 堆）**；`glTexture`/ `HardwareBitmap` 用 `Gralloc` 显存；用 `dumpsys gfxinfo` / `Meminfo` 的 `Gfx dev`/`EGL mtrack` 看。
   - **OOM**：Java 堆 `OutOfMemoryError`（`java` 堆上限约 192~512MB 取决于设备）或 Native 堆吃满；用 `dumpsys meminfo <pkg>` 看 `Native`/`Dalvik`/`Graphics` 三块，定位哪块涨。

### 易错雷区
1. ANR 不是"主线程在跑"触发，是"某条消息处理超时导致系统输入/广播饿死"——同专题一。
2. `Choreographer` 异步消息只保证渲染优先，**不能消除**应用主线程自身慢导致的掉帧。
3. `Bitmap` 在 Android 8+ 像素在 Native/Graphics 堆，Java 堆 OOM 不一定是 Bitmap 背锅——要看 `dumpsys meminfo` 的 `Graphics` 项。
4. LMK 杀的是**整进程**，不是回收对象；后台保活的本质是降低 `oom_score_adj`。

### 考官高频追问速答
- "怎么定位 ANR 是主线程卡还是 Binder 堵？" -> 看 `traces.txt`：主线程栈停在 `Message.dispatchMessage` 内自己代码 = 主线程慢；停在 `BinderProxy.transact` = 等远端（可能是 system_server 或另一个 APP 慢）。
- "systrace/Perfetto 怎么看掉帧？" -> 找 `Choreographer#doFrame` 间隔 > 16.6ms，点开看 `performTraversals` 哪段（measure/layout/draw）长，再下钻 `binder`/`inflate`。
- "为什么后台服务不回收还占内存？" -> 服务进程 `oom_score_adj` 比缓存低，lmkd 后杀；用 `startForeground` 更稳但会显示通知。

### 延伸阅读
`frameworks/base/core/java/android/view/Choreographer.java`；`frameworks/native/services/inputflinger/InputDispatcher.cpp`；`frameworks/base/services/core/java/com/android/server/am/{AppErrors,ProcessList,ActiveServices}.java`；`system/core/lmkd/`。

---

## 专题七：Jetpack / Compose 底层机制

### 问题
"Compose 重组（recomposition）原理？`@Composable` 编译器到底做了什么？`remember` / 稳定性（stability）/ 跳过重组（skip）怎么判定？`LazyColumn` 的 `key` 干嘛用？"

### 答案解析 + 底层原理
1. **`@Composable` 编译器的实质（`compiler-host` / `compose-compiler`）**
   - 编译器给每个 `@Composable` 函数**插入一个 `Composer` 参数**（并加 `$composer` + `$changed` 位掩码）。`Composer` 维护一棵 **`SlotTable`**（以"槽位 slot"数组存组合（composition）的节点、数据、源码位置）。
   - 首次组合（initial composition）：`Composer` 顺序写 slot，构建 `SlotTable` 表示的 UI 树（含每个 `@Composable` 的 key = 函数名+调用点）。
   - 重组（recomposition）：`Composer` 沿 `SlotTable` 重放（replay）同一序列，用 `$changed` 位判定**哪些参数变了**；未变的可跳过其子树（skip）。

2. **重组的作用域（Scope）**：重组以**可重启的 `@Composable` 函数**为最小单元（非整棵树）。状态（`State<T>`）被 `snapshot` 系统观察，`State.setValue` 触发 `snapshot` 失效 -> 标记依赖该 `State` 的 scope 为"待重组" -> 下一帧由 `Recomposer` 调度重跑该 scope。

3. **`remember` / 稳定性 / 跳过**
   - `remember { ... }` 把计算结果缓存进 `SlotTable` 的对应 slot，重组时若该 slot 对应的 key/输入未变则复用，避免重算。
   - **稳定性（Stability）**：编译器用**强/弱/不稳定**判定类型。不可变且所有字段稳定 = stable（如 `String`、基本类型、带 `@Stable` 注解的类）。stable 类型做"结构相等"比较；**unstable 类型（如含 `var`、含 `ArrayList` 的 data class 且未注解）每次都判为"可能变"，导致无法跳过重组**——这是 Compose 性能坑的头号来源。
   - **跳过重组（skip）条件**：函数参数全部 stable 且前后相等，或参数被 `remember`/常量 -> scope 跳过。否则每次父重组都重跑。

4. **`LazyColumn` 的 `key`**：列表项用 `key` 唯一标识，便于 `ItemAnimator` 在项重排/增删时**复用/移动** `Composable` 实例而不是整体重建（类似 `RecyclerView` 的 `stableId`）；不写 key 时位置变化会丢状态、动画错乱、重组浪费。

### 易错雷区
1. 在 `@Composable` 里**有条件地调用**不同数量的 `@Composable`（如 `if` 分支里调数目不等的 composable）会破坏 `SlotTable` 序列一致性 -> 崩溃或状态错位；用 `key`/稳定结构化解。
2. 把 **unstable 类型** 当参数传来传去（如未注解的 `data class` 含 `var`）-> 重组无法跳过 -> 列表卡。
3. `remember` 不解决"状态跨重组存活"以外的性能问题；真正卡是 scope 粒度 + 稳定性。
4. `LaunchedEffect`/`DisposableEffect` 的 key 变化会重启副作用——key 选错会反复执行。

### 考官高频追问速答
- "Compose 比 XML/View 慢吗？" -> 首帧因组合开销略慢，但跳过重组 + 无 `inflate` 反射，长列表/高频更新场景常更快；卡多是稳定性没处理好。
- "`derivedStateOf` 干嘛用？" -> 把"由其他 State 派生、但变化频率低"的计算包一层，避免每次源 State 变都重组，只在派生值真正变时才失效。
- "Compose 和 View 互操作？" -> `AndroidView` 在 Compose 里嵌 View；`ComposeView`/`setContent` 在 View 体系里嵌 Compose。

### 延伸阅读
`frameworks/compose`（AOSP）`runtime/runtime/src/main/kotlin/androidx/compose/runtime/{Composer,SlotTable,Recomposer}.kt`；`compiler` 模块的 `$Composer` 注入与 `$changed` 位掩码；`androidx.compose.foundation.lazy.LazyColumnKt`。

---

## 专题八：HAL / Linux 内核 / drivers

### 问题
"`binder.c` 里一次事务 `binder_transaction` 关键路径？oneway 在驱动层怎么处理？GKI 2.0 的 loadable vs built-in 怎么选？字符设备驱动骨架？`ashmem` 演进？SELinux 怎么约束 Binder？"

### 答案解析 + 底层原理
1. **`binder_transaction`（GKI `drivers/android/binder.c`）关键路径**
   - `binder_ioctl(BINDER_WRITE_READ)` -> `binder_thread_write` 解析 `BC_TRANSACTION` -> `binder_transaction`。
   - 该函数：① 校验 `target_node`（从 `binder_ref` 解析目标 `binder_node`）；② **`copy_from_user` 把发送方 Parcel 拷进内核 `binder_buffer`**（一次拷贝）；③ 处理 `binder_object`（fd/ptr 引用计数，跨进程传 fd 用 `fget`）；④ 把事务挂到目标 `binder_thread.todo` 或 `binder_proc.todo`，若目标线程阻塞在 `binder_thread_read` 则唤醒（`wake_up_interruptible`）；⑤ 发 `BR_TRANSACTION` 给目标用户态。
   - 回复 `BR_REPLY` 走对称的 `binder_transaction`（反向），完成后 `binder_transaction_buffer_release` 释放 buffer + `fput` fd。

2. **oneway 在驱动层**：`binder_transaction` 看 `tr.flags & TF_ONE_WAY`：oneway 不建 `binder_transaction` 的"等待回复"结构、不阻塞发送方，但仍入 `todo` 队列串行处理；oneway 无 `BR_REPLY`。

3. **GKI 2.0（Kernel 6.1）loadable vs built-in**
   - GKI 把内核分为 **Generic 内核（Google 维护，所有设备共用，过 VTS/KMI）** + **vendor 模块（`.ko` 可加载，须过 KMI 符号白名单）**。
   - **字符设备驱动（如 sensor/codec 自建节点）**：优先做成 **loadable `.ko`**（放在 `vendor` 分区，`modules_load` 列表加载），这样内核可独立 OTA（GKI 核心价值）。只有极早期/必须在 boot 阶段就用的才 built-in（编译进 `Image`，但会进 generic 内核，受限 KMI）。
   - KMI 稳定性靠 `__kabi` 符号保留 + `abi_gki_modules` 白名单；vendor `.ko` 只能引用白名单符号。

4. **字符设备驱动骨架（`struct file_operations` + `misc_register` / `cdev_add`）**
   ```c
   static const struct file_operations my_fops = {
       .owner = THIS_MODULE, .open = my_open, .read = my_read,
       .unlocked_ioctl = my_ioctl, .compat_ioctl = my_compat_ioctl,
       .release = my_release,
   };
   // miscdevice 最简：static struct miscdevice my_dev = { .minor=MISC_DYNAMIC_MINOR, .name="mydev", .fops=&my_fops }; misc_register(&my_dev);
   ```
   用户态经 `/dev/mydev` + `open/ioctl/read` 与内核交互；AOSP 侧用 `android::base::ReadFileDescriptor` 或 `libbinder` 的 `IBinder` 之上的 HAL 通信（AIDL）。

5. **`ashmem` 演进**：早期跨进程共享内存用 `ashmem`（`drivers/staging/android/ashmem.c`，`ashmem_create_region` + `mmap`）；**AOSP 14 主路径已迁到 `memfd_create`（ASharedMemory = `ashmem` 封装但新设备走 `memfd`）**，更安全（无全局 `/dev/ashmem` 节点、配合 SELinux）。面试答：**新代码用 `ASHMEM`/`ASharedMemory`(NDK) 但底层是 memfd；老设备仍 ashmem 驱动**。

6. **SELinux 与 Binder**：`service_manager` 的 `binder` 访问、各域的 `binder_call(...)`、`service_manager_type` 查询都受 `.te` 约束；AOSP 14 大量 `neverallow` 防域越权调 Binder；vendor 域调 system 服务须显式 `allow`。

### 易错雷区
1. oneway "不阻塞"指调用方，驱动层仍排队——和专题二呼应。
2. GKI 下**不能直接改 generic 内核加 built-in 驱动**做 OTA 兼容；必须 `.ko` + KMI。
3. `ashmem` 在新设备实际是 `memfd`，说"ashmem 已废弃"对、说"完全没了"错（旧设备驱动仍在）。
4. `ioctl` 必须配 `compat_ioctl`（32 位用户态进程在 64 位内核上调 64 位驱动的 ioctl 时走 compat 分支，否则 `ENOTTY`）。

### 考官高频追问速答
- "GKI 为什么重要？" -> 让 OEM 内核与 Google generic 内核解耦，Google 能直接给内核打安全补丁 OTA，不用等 OEM 整包升级（解决 Android 内核碎片化）。
- "字符设备 vs 块设备？" -> 字符设备按字节流随机访问（`/dev/xxx`，如 input、binder）；块设备按扇区（磁盘），有缓存层。
- "怎么用 `devtmpfs`/`uevent` 自动建 `/dev` 节点？" -> 驱动 `misc_register`/`device_create` 触发 `uevent`，用户态 `ueventd` 据 `uevent` + `*.rc`/`*.rules` 建节点并设 SELinux context。

### 延伸阅读
GKI common-android14-6.1：`drivers/android/binder.c` / `binder_alloc.c` / `binder_internal.h`；`drivers/staging/android/ashmem.c`（legacy）；`include/linux/miscdevice.h`；`system/sepolicy` 下的 `service_manager.te` / `binder.te`。

---

## 专题九：MTK 平台差异（AEE / mtklog / PerfService / thermal）

### 问题
"MTK 平台相比原生 AOSP 在 Framework 上有哪些必须知道的坑？AEE / mtklog 怎么抓系统级崩溃？PerfService 限频 / thermal 限频对 Framework 的影响？"

### 答案解析 + 底层原理
1. **AEE（Android Exception Engine）**：MTK 的系统级异常收集框架，比原生 `tombstoned` 更全——NE（Native Crash）、KE（Kernel Exception，即 kernel panic/oops）、JE（Java Exception）、EE（Exception Engine 汇总）。崩溃时生成 `/data/aee_exp/` 下的 `db.*`（`_exp_detail.txt` + `ZZ_INTERNAL` 等），含 kernel log、native tombstone、Java stack、唤醒源。
   - 用法：`adb pull /data/aee_exp`；用 `aee_extract`/`GAT`（MTK 工具）解析；`exp_filter.cfg` 控制采集项。**区别于原生**：原生只留 `tombstone_<pid>` + `anr/traces`，MTK 把 KE/NE/JE 统一进 `aee_exp`。

2. **mtklog**：MTK 的日志聚合（`/data/mtklog/`），含 `mobilelog`（logcat + kernel）、`netlog`、`meta` 等；抓取 `adb shell mtklogd` 或 ` EngineerMode` 里开。**关键**：出现"无原生 traces 但系统卡死/重启"时，先看 `aee_exp` 再开 `mtklog`，比纯 logcat 更准。

3. **PerfService（`perfservative` / `libperfservice`）**：MTK 的**性能调度守护**，App/系统可通过它**锁频/锁核**（避免关键路径被限频）。Framework 侧 `PowerHal`/`Thermal` 与之联动：王者/相机等场景锁大核高频。面试考点：上层 `ActivityManager` 的 `setProcessState`、PerformanceHint API（`ADPF`，Android 12+）是更标准的路径，MTK PerfService 是 vendor 增强。

4. **thermal（温控）限频**：MTK `thermal` 守护（`/vendor/bin/thermal*` + `thermal_config.xml`）按温度触发 `throttling` -> 降 CPU/GPU 频 -> **直接拉长帧时间 + 触发掉帧/卡顿 + 极端时限频导致 ANR 风险**。Framework 侧 `PowerManager`/`ThermalManagerService`（`frameworks/base/services/core/java/com/android/server/power/ThermalManagerService.java`）监听 `thermal HAL` 的 `Temperature` 回调，对 `THERMAL_STATUS_SEVERE` 等做降亮度/限后台。
   - 排查：卡顿+掉帧先 `cat /sys/devices/virtual/thermal/thermal_zone*/temp` 或 MTK `thermal_log` 看是否 throttling，排除"应用代码慢"误判。

### 易错雷区
1. 把 MTK `aee_exp` 当原生 tombstone——两者格式/路径不同，解析工具也不同（GAT vs `ndk-stack`/`tombstone` 解析）。
2. 卡顿根因误判：thermal 限频发烫时，优化应用代码无效，得从散热/降负载/锁频入手。
3. PerfService 锁频是"请求"非"保证"，thermal 严重时会覆盖；不要依赖它做硬实时。

### 考官高频追问速答
- "MTK 上 ANR 没 traces 怎么查？" -> 查 `/data/aee_exp` 的 `db.*` + `mtklog` 的 `mobilelog/kernel_log`；KE 场景下 system_server 可能直接重启无标准 traces。
- "thermal 和 LMK 谁先触发？" -> 不同维度：thermal 按温度限频（不杀进程），LMK 按内存杀进程；高温+低内存会叠加恶化。
- "PerfService 和 `PowerHal`/`ADPF` 关系？" -> ADPF（Android Dynamic Performance Framework）是 Google 标准 API（Perfetto 可见 hint），PerfService 是 MTK vendor 实现底座之一。

### 延伸阅读
MTK vendor：`vendor/mediatek/proprietary/` 下 `aee/` `mtklog/` `perfservice/` `thermal/`；AOSP：`frameworks/base/services/core/java/com/android/server/power/ThermalManagerService.java`；`hardware/interfaces/thermal/`。

---

## 专题十：高频连环追问压测（五段式口述法 + 20 条速答）

> 考官不考背诵，考"现象 -> 抓 trace -> 定界 -> 根因 -> 修复"的闭环。下面 20 条用一句话压测，能口述即达标。

| # | 追问 | 一句话速答 |
|---|------|----------|
| 1 | 主线程死循环为什么 ANR 不了？ | 它在 epoll 休眠等消息；ANR 是某条消息处理超时饿死系统输入。 |
| 2 | Binder 一次拷贝在哪？ | 发送方用户态->内核 `copy_from_user` 一次，接收方 mmap 共享无需二次拷贝。 |
| 3 | Binder 线程池多大？满会怎样？ | 默认 15（`ProcessState.mMaxThreads`），满则事务排队，可能拖垮对端 ANR。 |
| 4 | oneway 真的异步吗？ | 调用方不阻塞，但驱动仍入 todo 队列，对端忙照样排。 |
| 5 | ContentProvider 和 Application 谁先 onCreated？ | ContentProvider.onCreate 先，Application.onCreate 后——早期 SDK 初始化要放 attachBaseContext。 |
| 6 | Zygote 为何 fork 不用新建进程？ | 共享预加载 framework 映射（COW），启动快、内存省。 |
| 7 | Dialog 能用 Application Context 吗？ | 不能，缺 WindowToken -> BadTokenException。 |
| 8 | wrap_content 自定义 View 撑满父？ | onMeasure 没处理 AT_MOST -> 等效 match_parent。 |
| 9 | 收到 ACTION_CANCEL 要做什么？ | 复位 pressed/动画，否则视觉卡按下态。 |
| 10 | requestDisallowIntercept 何时失效？ | 父在 DOWN 阶段先拦则子没机会；父可强制清 FLAG_DISALLOW_INTERCEPT。 |
| 11 | invalidate 能在子线程调吗？ | 不能，跨线程用 postInvalidate。 |
| 12 | requestLayout vs invalidate？ | 前者重 measure+layout+draw，后者只重 draw。 |
| 13 | ANR 输入超时多少？ | 5s（InputDispatcher 派发触摸等 finishInputEvent）。 |
| 14 | 掉帧责任怎么定？ | Choreographer.doFrame 内 performTraversals 耗时 vs GPU/HWC/SF 合成耗时（FrameTimeline）。 |
| 15 | LMK 杀谁？ | 按 oom_score_adj 从高（缓存 900+）到低，用户态 lmkd。 |
| 16 | Bitmap OOM 在 Java 堆吗？ | Android 8+ 像素在 Native/Graphics 堆，看 dumpsys meminfo 的 Graphics 项。 |
| 17 | Compose 重组最小单元？ | 可重启的 @Composable 函数（scope），非整棵树。 |
| 18 | Compose 为什么跳不过重组？ | 参数是 unstable 类型（未注解 var/data class），无法判相等。 |
| 19 | LazyColumn 的 key 干嘛？ | 复用/移动项 Composable，避免重排丢状态、动画错乱。 |
| 20 | GKI 驱动必须 built-in 吗？ | 优先 loadable .ko（vendor，过 KMI），方便内核独立 OTA。 |

### 五段式口述法示例（冷启动慢）
- **现象**：点图标到首帧 > 2s，用户感知白屏。
- **抓 trace**：Perfetto 抓 `android_startbar`/`android_startup` 表 + `activityStart`->`activityResume`->`Choreographer#doFrame(reportFullyDrawn)` 区间。
- **定界**：`bindApplication` 到 `Activity.onCreate` 耗时长 -> 看 `dex2oat`/`verify` 占用；或 `onCreate` 内主线程 IO/初始化重。
- **根因**：dex 未编译（compile-filter=verify）首次解释执行 + `onCreate` 同步拉取配置。
- **修复**：推 `speed-profile`（装后跑热路径生成 profile 触发 bg-dexopt）；`attachBaseContext` 早初始化 + `onCreate` 懒加载/异步；用 `App Startup` 库管初始化顺序；`setTheme` 防白屏。

---

## 11. 查缺补漏与跨篇导航（本篇在系列里的位置）

本篇是**第三轮**高频精炼，重点补 native 定责 + 易错雷区。与既有文章的衔接：

- **第一轮基础**：7/23 主篇 / 7/29 A17 分代 GC / 8/12 核心基础高频八股深挖（native Looper / 同步屏障 / IdleHandler / Binder 线程池 15 / linkToDeath / View 事件三方法+CANCEL / MeasureSpec AT_MOST 坑 / GKI 字符驱动 / MTK AEE）。
- **跨版本演进**：8/27 秋招白热期高频八股源码级深挖与跨版本演进查缺补漏（A14->A17->A18 对照，本篇沿用其版本结论）。
- **源码 code walk**：8/06 源码级 code walk（startActivity->首帧 / SF 一帧 / binder.c 一次事务）——本篇专题二/三/八与之互补。
- **排查实战**：8/06 全链路排查实战（冷启动/卡顿/ANR/内存三路杀）——本篇专题六/十复用其五段式。
- **Perfetto SQL**：8/11 第二十五篇（input 延迟 / gpu_counter / battery 细分 / 混合 SQL）——本篇掉帧定责、冷启动抓取数据源。
- **ART 底座**：8/10 ART 运行时与 dex2oat、8/31 ART 内存管理与 GC 实战——本篇专题三冷启动优化、专题六内存三路杀引用。
- **Compose 底层**：8/15 Compose 编译器与运行时底层机制源码走读——本篇专题七压缩成面试速答。
- **HAL/内核/MTK**：8/17 HAL 与 Linux 内核驱动全链路（Treble/GKI/MTK 差异）——本篇专题八/九深化 GKI `.ko` 与 ashmem/memfd、MTK thermal。
- **系统层 Rust 化 / 内存安全**：8/28 系统层 Rust 化与内存安全边界——本篇专题八 SELinux/安全边界呼应。

> **剩余可轮换角度（若继续日更）**：① "真题大乱斗 vol.4" 更刁钻混合场景卷；② KMP/skijo 非 Android target 运行时深水区（androidMain 之外 target 的差异）；③ A18 Aluminium OS x86 端口下 WMS/display DP 热插拔 + Magic Pointer 输入语义专项（待 9.15 发布会后补真增量）。

---

## 12. 当日复习清单（速记卡）

- [ ] Looper 死循环 = epoll 休眠；ANR = 消息处理超时饿死系统输入。
- [ ] Binder 一次拷贝 = copy_from_user 一次 + mmap 共享；线程池 15；oneway 仍排队。
- [ ] 启动顺序：attachBaseContext -> ContentProvider.onCreate -> Application.onCreate -> Activity.onCreate。
- [ ] Zygote fork 共享预加载 framework（COW）= 启动快根因。
- [ ] Dialog 需 Activity token；Toast 11+ 走 NotificationManager。
- [ ] wrap_content 必处理 AT_MOST，否则撑满父。
- [ ] CANCEL 必须复位 pressed；requestDisallowIntercept 父可强制清。
- [ ] ANR 输入 5s / 广播前台 10s / 服务 20s / CP 10s。
- [ ] 掉帧定责：Choreographer.doFrame 内 vs GPU/HWC/SF（FrameTimeline）。
- [ ] LMK 按 oom_score_adj 杀；Bitmap 8+ 在 Native/Graphics 堆。
- [ ] Compose 重组最小单元 = @Composable scope；跳过靠 stable 类型 + remember。
- [ ] GKI 驱动优先 .ko（过 KMI），不 built-in；ashmem 新设备实为 memfd。
- [ ] MTK 查 aee_exp + mtklog；thermal 限频会伪装成"应用卡"。

---

> 生成于 2026-09-01 自动化任务。下一日更建议方向：A18 Aluminium OS 发布会后 x86 端口 WMS/display + Magic Pointer 输入专项，或真题大乱斗 vol.4。
