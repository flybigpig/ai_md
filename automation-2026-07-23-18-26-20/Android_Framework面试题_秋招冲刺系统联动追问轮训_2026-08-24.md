# Android Framework 面试题 · 2026 秋招冲刺 · 高频八股"系统联动追问"轮训（第 41 篇）

> 日期：2026-08-24 ｜ 系列第 41 篇 ｜ 累计约 260 专题（本篇为"系统联动追问"轮训，与当日主运行第 40 篇互补）
> 主线 baseline：Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX，内核 GKI android14-6.1)
>
> 说明：今日 08:35 主运行（第 40 篇）已专攻 **A18 Aluminium OS 桌面融合对 WMS / display / Input / CDM 的源码级重构**。本篇为当日二次触发，按"系统联动追问"形态，把你列出的全部领域——Handler/Looper、Binder、AMS/ATMS、WMS、View 绘制与测量、App 启动流程、内存/卡顿/ANR 优化、Jetpack/Compose 底层机制、HAL/Linux kernel/drivers 驱动、MTK——焊成"一次操作跨多子系统"的面试能力。每题附详细答案解析 + AOSP 源码路径/方法名 + 易错点 + 高频追问 + 延伸阅读。

---

## 0. 当日热点锚定（2026-08-24 已联网核实）

- **A17 QPR2 Beta 3（build CP41.260731.005，2026-08-14）** 本周期最大增量版本，stable 跟踪 **2026-12**（QPR1 约 2026-09，QPR3 约 2027 初）。新特性（原生 App Lock / 重排 QS / 锁屏模糊 / 防呼叫转移诈骗 / 折叠屏多任务）的底层落点见第 37 篇。
- **Aluminium OS（A18）桌面融合** 已泄漏确认，对 WMS/display/Input/CDM/内核驱动的冲击见第 40 篇。
- **EU DMA 裁定** 强制 Google 在 A18 前开放 11 项 AI 能力给第三方助手，Framework 侧由 CDM + AppFunctions + role 体系承接（第 8/22 篇）。
- **2026 秋招趋势**（来自近期面经聚合）：Handler/Looper（epoll/泄漏）、Binder（一次拷贝/安全/线程池）、Compose 重组/稳定性、ANR、Native crash、量化数字，是最高频深考题；且考官偏好"一个现象串起多个子系统"的连环追问。

---

## 专题一：Handler / Looper —— 主线程死循环为什么不 ANR？

### 问题
"主线程的 `Looper.loop()` 是一个死循环，为什么不会 ANR？消息队列空了之后线程在干什么？同步屏障、IdleHandler、消息池分别解决什么问题？"

### 答案解析 + 底层原理
- **死循环不 ANR 的真相**：ANR 不是"主线程在跑循环"触发的，而是"主线程在**处理某条消息时超时**"（输入派发 5s / 广播 10s / 服务 20s）。`Looper.loop()` 在 `MessageQueue.next()` 里**阻塞休眠**，不占 CPU，所以空闲时根本不会触发看门狗。
  - `frameworks/base/core/java/android/os/Looper.java`：`loop()` -> `queue.next()` -> 若无消息则 `nativePollOnce(ptr, -1)` 永久休眠（timeout=-1）。
  - `frameworks/base/core/java/android/os/MessageQueue.java`：`nativePollOnce` 对应 `frameworks/base/core/jni/android_os_MessageQueue.cpp` -> `nativeMessageQueue->pollOnce()` -> `frameworks/native/libs/utils/Looper.cpp` 的 `pollOnce()` -> **`epoll_wait()`** 在 `mWakeEventFd` + 其他 fd 上休眠。
- **唤醒机制**：`Looper.cpp` 持有 `mWakeEventFd`（eventfd），`wake()` 时 `write(mWakeEventFd, 1)` 触发 `epoll_wait` 返回；`nativePollOnce` 返回后 `next()` 继续取消息。`system_server` 的"看门狗超时"是另一套 `Watchdog`（第 34 篇），与 App 主线程 `Looper` 休眠无关。
- **同步屏障（Sync Barrier）**：`MessageQueue.postSyncBarrier()` 插入一个 `target == null` 的屏障消息，`next()` 在遇到屏障时会**跳过所有同步消息、只取异步消息**（如 `Choreographer` 的 vsync 回调 `MSG_DO_FRAME` 是异步消息），保证渲染优先。`removeSyncBarrier()` 移除。易错：屏障必须配对移除，否则队列永久卡死。
- **IdleHandler**：`next()` 在队列空且无可运行异步消息时，触发 `mIdleHandlers`（如 `ActivityThread` 的 `GcIdler`、首帧后 `ActivityThread.Idler` 把 `onResume` 之后的 `ActivityIdle` 上报 AMS）。`queueIdle()` 返回 false 表示一次性。
- **消息池**：`Message.sPool` 是 singly-linked 复用池，`MAX_POOL_SIZE = 50`（`MessageQueue` 注释/Message 内常量），`obtain()`/`recycleUnchecked()` 复用，避免高频消息 GC 抖动。

### 易错点
1. 主线程空闲是 `epoll` 休眠，不是空转——拿 `top` 看到主线程 0% CPU 是正常的。
2. 同步屏障的 `target` 为 null，普通 `Handler` 发的消息 `target` 非 null，不会越过屏障。
3. `Handler` 内存泄漏：非静态内部类 `Handler` 持有外部 `Activity`，消息未处理完前 `Activity` 无法回收；用静态类 + `WeakReference` 或 `removeCallbacksAndMessages(null)` 在 `onDestroy` 清理。

### 高频追问
- "postDelayed 的延时是怎么保证的？" -> `MessageQueue.enqueueMessage()` 按 `when` 插入有序队列；`nativePollOnce` 的 timeout 计算为 `nextMessage.when - now`，到点唤醒。
- "为什么 `Choreographer` 用异步消息？" -> 见专题六（卡顿定界），保证 vsync 帧回调不被普通消息饿死。

---

## 专题二：Binder IPC —— 一次拷贝、线程池 15、oneway 排队、getCallingUid 不可信

### 问题
"Binder 一次事务到底拷贝几次？为什么是 mmap 而不是多次 copy？Binder 线程池默认多大？oneway 会阻塞吗？跨进程 `getCallingUid()` 为什么有时不可信？"

### 答案解析 + 底层原理
- **一次拷贝**：用户态 `BpBinder.transact()` -> `IPCThreadState::transact()` -> `writeTransactionData()` 把 `Parcel` 写进 `mOut` 缓冲区 -> `talkWithDriver()` 通过 `ioctl(BINDER_WRITE_READ)` 进入内核 `drivers/android/binder.c` 的 `binder_ioctl` -> `binder_thread_write` -> `binder_transaction()`。
  - **关键**：`binder_transaction()` 在内核态用 `copy_from_user()` **把发送方用户态 Parcel 拷到内核 binder_buffer 一次**；接收方通过 **mmap 共享同一块内核 buffer**（`binder_mmap` 把内核 `binder_buffer` 映射到接收进程用户态），接收方 `BR_TRANSACTION` 直接读该映射区，**无需第二次拷贝**。所以"一次拷贝"= 内核从发送方用户态拷到内核 buffer，接收方靠 mmap 零拷贝读取。
- **线程池默认 15**：`frameworks/native/libs/binder/ProcessState.cpp` `openDriver()` 后 `mMaxThreads = 15`（实际 `maxThreads - 1` 可由 `BR_SPAWN_LOOPER` 动态扩容）；`startThreadPool()` + `joinThreadPool()` 让 `IPCThreadState` 循环 `getAndExecuteCommand()` -> `executeCommand(BR_TRANSACTION)` -> `BBinder::transact()` -> `onTransact()`。
- **oneway 也排队**：`oneway`（异步）事务不阻塞**发送方**返回，但**接收方 `binder_transaction` 仍串行入队**；若对端 `binder_thread` 全忙（默认 15），oneway 也会在 `binder_proc` 的 `todo` 队列等待——这就是"oneway 满也排队"导致对端 LMK/ANR 的源头（第 19/27 篇）。
- **getCallingUid 不可信的两场景**：①跨 VM（AVF/pKVM）RPC Binder——`getCallingUid()` 返回的是 VM 内 UID，host 与 guest 内核命名空间不同，不能当信任锚（第 12/13 篇）；②Provider/系统服务侧——`Binder.getCallingUid()` 拿到 `SYSTEM_UID` 不可信，必须靠 attestation/签名（第 13 篇 AppFunctions、第 37 篇 App Lock）。`clearCallingIdentity()` / `restoreCallingIdentity()` 用于临时切换身份。

### 易错点
1. "一次拷贝"不是零拷贝——是相对 socket/管道（两次拷贝）更优，但仍有一份内核拷贝。
2. `mmap` 只解决"接收方读"，发送方到内核仍有一份 `copy_from_user`。
3. `BR_SPAWN_LOOPER`：接收方线程不够时由驱动通知 spawn 新 looper 线程，上限受 `maxThreads` 约束。
4. 三上下文：`/dev/binder`（framework）、`/dev/hwbinder`（HAL，HIDL/AIDL-HAL）、`/dev/vndbinder`（vendor 自定义，ProcessState `initWithDriver`）。

### 高频追问
- "Binder 比 socket 快在哪？" -> 一次拷贝 + 内核对象引用（binder_node/binder_ref 句柄映射）而非全量序列化。
- "binder 死锁怎么排查？" -> `dumpsys activity binder` + `binderinfo` + `atrace` 的 `binder` 类别，看 `binder_transaction` 阻塞在哪个对端 `to_pid`。

---

## 专题三：AMS / ATMS —— startActivity 链路与 ContentProvider 前置坑

### 问题
"从 `startActivity` 到应用进程收到 `onCreate`，中间经过哪些关键类？为什么冷启动有时被 ContentProvider 拖慢？oom_adj 是怎么分级的？"

### 答案解析 + 底层原理
- **调度链路**（boot 侧见第 34 篇，app 侧见第 20 篇）：`Context.startActivity()` -> `Instrumentation.execStartActivity()` -> `ActivityTaskManagerService.startActivity()`（`frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java`）-> `ActivityStarter.execute()` -> `startActivityUnchecked()`（解析 flag/launch mode/task affinity）-> `RootWindowContainer.resumeFocusedTasksTopActivities()` -> `ActivityStackSupervisor.realStartActivityLocked()` -> 通过 `ClientTransaction`（`LaunchActivityItem`）经 Binder 交给应用进程 `ActivityThread`。
- **ContentProvider 前置坑**：`ActivityManagerService.attachApplication()` -> `bindApplication()` 之前会先 `installContentProviders()`（冷启动第一步 bindApplication 里 `ActivityThread.handleBindApplication()` 调 `installContentProviders()`）。**ContentProvider 的 `onCreate()` 跑在 `Application.onCreate()` 之前**，若某个 Provider（尤其三方 SDK 借 Provider 自启）做了耗时 I/O，会直接拖慢冷启动首帧（第 19/20 篇）。
- **oom_adj 分级**：`frameworks/base/services/core/java/com/android/server/am/ProcessList.java` `computeOomAdjLocked()` 给每个进程算 `oomScoreAdj`；前台 `FOREGROUND_APP_ADJ=0`，可见/可感知/后台/缓存逐级递增；`lmkd` 据此杀进程（第 34 篇三路杀）。

### 易错点
1. `launchMode` 判定在 `ActivityStarter.startActivityUnchecked`，不是 AMS 入口。
2. `singleTask`/`singleInstance` 的 task 亲和在 `TaskRecord` 层，`realStartActivityLocked` 之后才真正创建 `ActivityRecord`。
3. ContentProvider 的 `onCreate` 早于 `Application.onCreate`——这是冷启动优化的隐藏雷区。

### 高频追问
- "冷启动为什么有时候不走 Zygote fork？" -> USAP（Unspecialized App Process）池预 fork 的进程特化，省去 fork 抖动（第 34 篇）。
- "后台进程被杀的顺序？" -> 见专题五（三路杀）与第 34 篇 oom_adj。

---

## 专题四：WMS / View —— 事件分发三方法、requestDisallowIntercept 坑、MeasureSpec

### 问题
"View 事件分发三个方法各自的职责是什么？`requestDisallowInterceptTouchEvent` 对 DOWN 事件为什么无效？`MeasureSpec.AT_MOST` 的经典坑是什么？`getMeasuredWidth()` 和 `getWidth()` 何时不等？"

### 答案解析 + 底层原理
- **三方法责任链**（`frameworks/base/core/java/android/view/`）：
  - `ViewGroup.dispatchTouchEvent()`：总调度，先问自己 `onInterceptTouchEvent()`（**仅 ViewGroup 有**），再下发给子 View 的 `dispatchTouchEvent()`。
  - `ViewGroup.onInterceptTouchEvent()`：返回 true 则拦截，事件转给自己 `onTouchEvent()`，子 View 收 `ACTION_CANCEL`。
  - `View.onTouchEvent()`：真正消费事件（返回 true 表示消费）。
  - 顺序：`dispatchTouchEvent` -> (若 ViewGroup) `onInterceptTouchEvent` -> 子 `dispatchTouchEvent` / 自身 `onTouchEvent`。
- **requestDisallowIntercept 对 DOWN 无效**：`ViewGroup.dispatchTouchEvent()` 在 `ACTION_DOWN` 时会 **reset `FLAG_DISALLOW_INTERCEPT`**（即 `mGroupFlags &= ~FLAG_DISALLOW_INTERCEPT`），所以子 View 在 DOWN 时调 `requestDisallowInterceptTouchEvent(true)` 被忽略；该标志只对 **MOVE/UP** 生效。这是滑动嵌套（RecyclerView 内嵌 ViewPager 等）的经典坑（第 26 篇）。
- **ACTION_CANCEL 语义**：父 View 在 MOVE 中突然拦截，子 View 会收到 `ACTION_CANCEL`，必须把它当 UP 处理（清理按下态），否则状态错乱。
- **MeasureSpec 三模式**：`ViewRootImpl.performTraversals()` -> `performMeasure()` -> `View.measure()` -> `onMeasure()`。`MeasureSpec` 由 `specSize + specMode` 打包：`UNSPECIFIED`（随意）/ `EXACTLY`（match_parent/精确值）/ `AT_MOST`（wrap_content，父给上限）。`getChildMeasureSpec()` 按父 spec + 子 layout param 推导子 spec。
  - **AT_MOST 坑**：自定义 View 的 `onMeasure` 若不处理 `AT_MOST` 而直接 `setMeasuredDimension(width, height)` 用 measureSpec 里的 size，wrap_content 会表现得像 match_parent。
- **getMeasuredWidth vs getWidth**：`getMeasuredWidth()` = `mMeasuredWidth`（measure 阶段 `setMeasuredDimension` 设置）；`getWidth()` = `mRight - mLeft`（layout 阶段确定）。**measure 之后、layout 之前两者不等**；正常布局完成后相等。动画/自定义 layout 中常出现不等。

### 易错点
1. `onInterceptTouchEvent` 只有 `ViewGroup` 有，`View` 没有。
2. `requestDisallowInterceptTouchEvent` 在 DOWN 被 reset，这是故意设计（父必须在 DOWN 决定拦截链）。
3. `onTouchListener.onTouch` 返回 true 会**短路** `onTouchEvent`，且优先级高于 `onClick`（onClick 在 `onTouchEvent` 的 UP 里触发）。
4. `wrap_content` 必须手动处理 AT_MOST，否则退化为 match_parent。

### 高频追问
- "事件分发和输入系统怎么衔接？" -> `InputReader` -> `InputDispatcher` -> `InputChannel`（socketpair）-> `ViewRootImpl.InputStage` -> `DecorView.dispatchTouchEvent`（第 16 篇）。
- "onTouchEvent 返回 false 会怎样？" -> 事件向上回溯给父 View 的 onTouchEvent，最终无人消费则下一次 DOWN 不再派发该序列。

---

## 专题五：App 启动流程 + 内存 / 卡顿 / ANR 三路杀

### 问题
"冷启动三段是什么？基线 Profile 与云 Profile 怎么加速？内存压力有哪三条杀路径？卡顿掉帧怎么定责？ANR 有哪几类超时？"

### 答案解析 + 底层原理
- **冷启动三段**（第 20/34 篇）：①`Zygote.forkAndSpecialize()`（或 USAP 特化）创建应用进程 -> `ActivityThread.main()` 建主线程 Looper；②`handleBindApplication()`：installContentProviders（前置坑）+ `makeApplication()` + `Application.onCreate()`；③`performLaunchActivity()` -> `onCreate/onStart` -> `handleResumeActivity()` -> `ViewRootImpl.setView()` -> `performTraversals()` 首帧。
- **启动加速**：基线 Profile（`/data/misc/profiles/ref/` dex 元数据，PMS 安装期触发 `dexopt`）让常用方法走 AOT；云 Profile（Play 下发聚合 Profile）在 OTA/安装后做云编译；`PinnerService`（`frameworks/base/services/core/java/com/android/server/PinnerService.java`）把关键 dex/oat 锁进 RAM 防 page-out。ART 三态（解释/JIT/AOT）见第 24 篇。
- **三路杀**（第 19/34 篇）：①`lmkd`（用户态，`system/core/lmkd/lmkd.cpp`，基于 PSI `/proc/pressure/memory` + 内存水位，杀低 oom_adj 后台进程）；②**A17 Memory Limiter**（应用个体内存配额超标，**静默杀** `REASON_MEMORY_LIMITER`，无 ANR）；③内核 OOM killer（极端内存压力下按 `oom_score` 杀，连前台都可能）。`onTrimMemory()` 是"提示"不是"救命"，无法阻止被杀。
- **卡顿定责**：`Choreographer.doFrame()` 分 `CALLBACK_INPUT -> CALLBACK_ANIMATION -> CALLBACK_TRAVERSAL`；掉帧看 `FrameTimeline`（`frameworks/native/services/surfaceflinger/FrameTimeline`）的 `actual_frame_timeline` vs `expected`，`jank_type` 把责任定到 App（主线程长）/ RenderThread / SF / HWC（第 21/25 篇）。
- **ANR 四类超时**：输入派发 `DEFAULT_INPUT_DISPATCHING_TIMEOUT=5s`（native `InputDispatcher` 计时，非 App Looper，第 16 篇）、广播 `BROADCAST_FG_TIMEOUT=10s`、服务 `ACTIVE_SERVICES_TIMEOUT=20s`、ContentProvider `CONTENT_PROVIDER_PUBLISH_TIMEOUT=10s`。`/data/anr/` 栈 + `event log` 的 `am_anr`。

### 易错点
1. Input ANR 的 5s 计时器在 **native InputDispatcher**，不是主线程消息循环——主线程卡在某条消息里（同步 Binder/死锁）才会触发。
2. 三路杀是**独立路径**，Memory Limiter 不报 ANR，直接 `ApplicationExitInfo.REASON_MEMORY_LIMITER`。
3. 基线 Profile 只覆盖"安装期已知热方法"，云 Profile 才覆盖用户真实路径。
4. `FrameTimeline` 的 `jank_type` 取值（App Deadline Missed / SurfaceFlinger / HWC 等）决定优化方向。

### 高频追问
- "怎么用 Perfetto 抓一次冷启动？" -> 见第 21 篇 SQL 范例（android_startup + slice 拆解 bindApplication/installContentProviders）。
- "后台进程被杀后用户数据会丢吗？" -> ViewModel + SavedStateHandle 跨进程重建（第 38 篇），但非持久化数据仍丢。

---

## 专题六：Jetpack / Compose 底层机制

### 问题
"Compose 的 SlotTable 是什么结构？Snapshot 怎么实现'读取即订阅'？Recomposer 挂在 Choreographer 哪个回调？强跳过（Strong Skipping）改了什么？"

### 答案解析 + 底层原理
- **SlotTable gap buffer**：`androidx.compose.runtime.ComposerImpl` 用一块平坦的 `IntArray`（slots）+ `IntArray`（keys）+ Anchor 表，逻辑上是树、物理上是数组；插入/删除用 gap buffer 移动，避免整段拷贝（第 15/30 篇）。
- **Snapshot MVCC**：`androidx.compose.runtime.snapshots.Snapshot` 维护全局版本号；`State.read()` 经 `readObserver` 把当前 `Snapshot` 记录为"依赖"（读取即订阅）；`Snapshot.apply()` 时冲突检测（写写冲突抛 `SnapshotApplyConflictException`）。`derivedStateOf` 惰性重算依赖。
- **Recomposer 挂 CALLBACK_ANIMATION**：`Recomposer.runRecomposeAndApplyChanges()` 通过 `Choreographer` 的 **`CALLBACK_ANIMATION`**（不是 `TRAVERSAL`）调度重组；同帧内 `INPUT -> ANIMATION -> TRAVERSAL` 先后，重组在遍历之前完成，保证本帧生效（第 15/30 篇）。
- **强跳过（Strong Skipping）**：Kotlin 2.0.20 默认开。`$stable` 位域判定参数稳定性；强跳过把"是否跳过重组"策略从"推断稳定则跳过"升级为"**引用相等 `===` 即可跳过**"，但**不改 `$changed` 位域**、非 stable 类型仍可能每帧重组（第 15/30/36 篇两处误解澄清）。

### 易错点
1. Recomposer 挂在 `CALLBACK_ANIMATION`，不是 `CALLBACK_TRAVERSAL`——这是 Compose 与 View 同帧时序的关键。
2. 带返回值的 `@Composable` 不是重组边界（它不独立成 group）。
3. 强跳过 ≠ 改 stability；引用相等陷阱仍在（如每次 new 对象导致仍重组）。
4. Compose on Android 走 HWUI（RenderNode），未绕过 `frameworks/base` 的图形栈（第 3/13 篇接缝）。

### 高频追问
- "`$changed` 位掩码怎么工作？" -> 见第 15/30/36 篇：每参数 2 bit（real + static），父方编译期预填，运行时 `ComposerImpl.changed()` 解码决定是否跳过 group。
- "Compose 语义树为什么对 AI Agent 更友好？" -> 见第 13 篇 ANI / a11y 语义树映射。

---

## 专题七：HAL / Linux kernel / drivers 驱动

### 问题
"Treble 怎么解耦 framework 与 vendor？Binder 三个上下文分别给谁？HIDL 和 AIDL for HAL 怎么选？GKI / KMI 是什么？外接显示和键鼠的内核驱动分别在哪？"

### 答案解析 + 底层原理
- **Treble / VINTF**：`/system`（framework）与 `/vendor`（HAL 实现）通过 **HAL 接口**解耦，`compatibility_matrix.xml` + `manifest.xml`（VINTF）双向校验版本。`hwservicemanager` / `servicemanager` 分别管理服务。
- **Binder 三上下文**（第 17 篇）：`/dev/binder`（framework 间）、`/dev/hwbinder`（HAL，HIDL + 部分 AIDL-HAL）、`/dev/vndbinder`（vendor 自定义 Binder，`ProcessState.initWithDriver("/dev/vndbinder")`）。
- **HIDL vs AIDL for HAL**：旧 HAL 用 HIDL（`hardware/interfaces/*`，`Ixxx.hal` -> C++/Java 生成）；Android 10+ 新 HAL 统一用 **AIDL for HAL**（`aidl_interface`，`frameworks/hardware/interfaces` 逐步迁移），共享 Binder 模型、更易版本演进。
- **GKI 2.0 / KMI**：Generic Kernel Image 让 `/system` 与内核解耦，vendor 驱动须过 **KMI（Kernel Module Interface）稳定 ABI**——`drivers/` 暴露的符号 ABI 锁定，`include/` 头文件稳定。MTK/高通/Intel 都须对齐（第 40 篇 Aluminium OS 把 x86 也拉进 KMI 约束）。
- **外接显示驱动**：`drivers/gpu/drm/`（DRM/KMS）——`drm_connector`（物理口）/ `drm_crtc`（管线）/ `drm_plane`（图层），hotplug 经 `drm_kms_helper_hpd_irq_event` -> 用户态 SF/HWC 重枚举 -> `DisplayManagerService`（第 40 篇 §3/§7）。
- **键鼠驱动**：`drivers/hid/`（HID 子系统）——`hid-core` 解析 report descriptor -> `input_event` -> `evdev` 节点 `/dev/input/eventN` -> `EventHub`（第 16/40 篇）。

### 易错点
1. GKI 锁的是 **KMI 符号 ABI**，不是内核版本号；vendor 模块须基于同一 KMI 分支编译。
2. `hwbinder` 与 `vndbinder` 都基于 Binder 驱动，只是独立上下文、独立 handle 空间。
3. KMS 负责模式协商（EDID/分辨率），Framework 只能"选"不能"造"。
4. AIDL for HAL 是趋势，面试被问 HIDL 要能说出"存量多、新接口用 AIDL"。

### 高频追问
- "vendor 驱动怎么过 GKI？" -> vendor hook / DDK 模式（第 17 篇 KMI + vendor hook）。
- "16KB 页面对内核驱动有什么要求？" -> 见第 14 篇：驱动 `alloc` 须对齐 16KB，`p_align` 校验。

---

## 专题八：MTK 平台真缺口（AEE / mtklog / PerfService / thermal / vendor HAL）

### 问题
"MTK 平台有哪些独有的排查与性能机制？AEE / mtklog 怎么用？PerfService 和 thermal 是什么？"

### 答案解析 + 底层原理
- **AEE（Android Exception Engine）**：MTK 的异常收集框架，`/system/bin/aee`（`exp_main` 等），崩溃/重启时生成 `db.*` 异常报告（类似 tombstone 但更全，含 kernel/driver 上下文）。`/data/aee_exp/` 存报告。
- **mtklog**：MTK 三件套日志——`mobilelog`（logcat/kernel）、`mdlog`（modem）、`netlog`（网络）。抓取用 `mktlog` / `aee_extract`，定位 modem/射频/功耗问题。
- **PerfService**：MTK 性能调度 API（`/vendor/mediatek/proprietary/hardware/perfservice/`），App 可提频/绑核（`perfBoost` / `perfLockAcquire`）防卡顿；面试常考"为什么会引入发热"（长期 perfLock 拉高频率）。
- **thermal**：MTK thermal HAL（`vendor/mediatek/proprietary/hardware/thermal/`）+ 内核 thermal zone，温度超阈降频；与 Android `Thermal HAL` -> `Power HAL`/`ADPF` 联动（第 7/19 篇）。
- **vendor HAL / 内核驱动**：MTK 大量 vendor 模块须过 GKI KMI（第 17 篇），显示/相机/音频有 MTK 私有 HAL 扩展。

### 易错点
1. AEE 报告在 `/data/aee_exp/`，不是 `/data/tombstones/`（后者是 AOSP debuggerd）。
2. `mtklog` 三件套要分清 mobilelog/mdlog/netlog 的用途。
3. PerfService 是性能双刃剑——提频换流畅但牺牲功耗/发热。

### 高频追问
- "MTK 机器卡顿发热怎么排查？" -> mtklog + perfetto（thermal 计数器）+ PerfService 占用情况三方交叉。

---

## 专题九：三条跨子系统高频追问链（系统联动核心能力）

### 链 A：一次冷启动 ANR —— Binder × AMS × 主线程
`startActivity`(ATMS) -> `realStartActivityLocked` 经 Binder 到应用 -> `handleBindApplication` 先 `installContentProviders` -> 某三方 Provider `onCreate` 做了**同步 Binder 调用**卡在对端（对端线程池满，第 27 篇场景②）-> 主线程阻塞 > 5s -> `InputDispatcher` 5s 计时触发 ANR。**加试题**：为什么是 InputDispatcher 计时而不是主线程 Looper？（答：输入派发超时在 native 侧独立计时，主线程死循环不 ANR，但卡在某条消息里会）。

### 链 B：一次屏幕点击 —— Input × WMS × View × Choreographer
`Touchscreen` -> `evdev`(/dev/input) -> `EventHub` -> `InputReader`(TouchInputMapper) -> `InputDispatcher`(findFocusedWindowTargetsLocked) -> `InputChannel`(socketpair) -> `ViewRootImpl.InputStage` -> `DecorView.dispatchTouchEvent` -> `ViewGroup.dispatchTouchEvent` -> `onInterceptTouchEvent` -> `onTouchEvent`。若 View `onTouchEvent` 耗时长 -> 主线程阻塞 -> 下一帧 `Choreographer.doFrame` 迟到 -> `FrameTimeline` jank_type=App Deadline Missed。**加试题**：`requestDisallowInterceptTouchEvent` 在 DOWN 为何无效？（答：dispatchTouchEvent 在 DOWN reset FLAG_DISALLOW_INTERCEPT）。

### 链 C：跨设备 AI 助手 —— CDM × RPC Binder × 机密计算
手机 -> 笔记本 `CompanionDeviceManager` 持久 Association -> 通用剪贴板 `ClipboardService` 同步 `ClipData`（传输 AES-GCM）-> 若 Gemini 本地推理跑在 pKVM Microdroid（第 12 篇 AVF）-> 笔记本 App -> 推理 VM 的 Binder RPC **getCallingUid 不可信** -> 必须 attestation/签名校验。**加试题**：跨设备 UID 与跨 VM UID 为什么都不能当信任锚？（答：UID 是同一内核命名空间概念，跨设备/跨 VM 无共享内核，编号各自独立，伪造成本极低）。

---

## 专题十：易错红榜 TOP20（全系列交叉）

1. 主线程死循环靠 `epoll` 休眠，不占 CPU，空闲不 ANR（专题一）。
2. 同步屏障 `target==null`，普通消息不过屏障（专题一）。
3. `Message` 池 `MAX_POOL_SIZE=50`（专题一）。
4. Binder"一次拷贝"= 内核从发送方拷贝一次 + 接收方 mmap 零拷贝（专题二）。
5. Binder 线程池默认 15，oneway 满也排队（专题二）。
6. `getCallingUid` 跨 VM/系统服务侧不可信（专题二/链 C）。
7. ContentProvider `onCreate` 早于 `Application.onCreate`（专题三）。
8. `launchMode` 判定在 `ActivityStarter.startActivityUnchecked`（专题三）。
9. `onInterceptTouchEvent` 仅 ViewGroup 有（专题四）。
10. `requestDisallowInterceptTouchEvent` 对 DOWN 被 reset（专题四）。
11. `AT_MOST` 下 wrap_content 必须手动处理，否则退化为 match_parent（专题四）。
12. `getMeasuredWidth`(measure) vs `getWidth`(layout) 在 layout 前不等（专题四）。
13. 冷启动三段：fork -> bindApplication(含 CP 前置) -> launch+首帧（专题五）。
14. 三路杀：lmkd(PSI) / A17 Memory Limiter(静默) / 内核 OOM，独立路径（专题五）。
15. Input ANR 5s 计时在 native InputDispatcher，非 App Looper（专题五/链 A）。
16. `FrameTimeline` jank_type 定责 App/RenderThread/SF/HWC（专题五）。
17. Recomposer 挂 `CALLBACK_ANIMATION`，非 TRAVERSAL（专题六）。
18. 强跳过不改 `$stable` 位域，引用相等陷阱仍在（专题六）。
19. GKI 锁 KMI 符号 ABI，非内核版本号（专题七）。
20. AEE 报告在 `/data/aee_exp/`，非 tombstones（专题八）。

---

## 专题十一：AOSP 14 源码路径清单（轮训专项）

| 子系统 | 关键类 / 文件 | 路径 |
|---|---|---|
| Handler/Looper | Looper / MessageQueue / Message | `frameworks/base/core/java/android/os/` + native `frameworks/native/libs/utils/Looper.cpp` |
| Binder | IPCThreadState / ProcessState / BpBinder / binder.c | `frameworks/native/libs/binder/` + `drivers/android/binder.c` |
| AMS/ATMS | ActivityTaskManagerService / ActivityStarter / ProcessList | `frameworks/base/services/core/java/com/android/server/wm/` + `am/` |
| WMS/View | View / ViewGroup / ViewRootImpl / MeasureSpec | `frameworks/base/core/java/android/view/` |
| 启动 | ActivityThread / ZygoteInit / PinnerService | `frameworks/base/core/java/android/app/` + `com/android/internal/os/` + `services/core/java/com/android/server/` |
| 卡顿/ANR | Choreographer / FrameTimeline / InputDispatcher | `frameworks/base/core/java/android/view/` + `frameworks/native/services/surfaceflinger/` + `inputflinger/` |
| Compose | ComposerImpl / Snapshot / Recomposer | `frameworks/support/compose/runtime/`(androidx) |
| HAL/内核 | ProcessState(hw/vnd binder) / GKI KMI / DRM / HID | `frameworks/native/libs/binder/` + `drivers/`(GKI common-android14-6.1) |
| MTK | AEE / mtklog / PerfService / thermal | `vendor/mediatek/proprietary/` |

---

## 专题十二：40 -> 41 篇交叉索引（系统联动轮训专项）

- **第 40 篇（8/24 A18 Aluminium OS WMS 重构）**：本篇是其"广度补充"——第 40 篇深挖桌面融合单一主题，本篇把用户列的全部领域做系统联动轮训。
- **第 34 篇（8/19 启动链路/system_server）**：本篇专题三/五复用其 boot 侧与 oom_adj 三路杀。
- **第 20 篇（8/6 code walk）**：本篇专题三/五复用 startActivity->首帧链路。
- **第 16 篇（8/16 输入系统）**：本篇专题四/链 B 复用 InputChannel 与 Input ANR 计时器。
- **第 26/36 篇（8/12/8/20 基础八股轮训）**：本篇是其"系统联动追问"升级版，弱化单点、强化跨子系统链。
- **第 12/13 篇（机密计算边界）**：本篇链 C 复用跨 VM getCallingUid 不可信。
- **第 14 篇（8/14 Native 稳定性）**：本篇专题七/八的驱动与崩溃排查延伸。
- **第 17 篇（8/17 HAL/内核驱动）**：本篇专题七复用 Treble/GKI/MTK。
- **第 19/27/39 篇（真题大乱斗 vol.1/2/3）**：本篇链 A/B/C 可作为 vol.4 候选混合场景素材。

> 全系列至此 **41 篇 / 约 260 专题** 完整闭环（含本篇系统联动追问轮训）。剩余可选增量：KMP/Swift Export 实战坑下钻、Compose 编译器插件 IR lowering（`ComposableFunctionBodyTransformer`）内部逐行走读、Aluminium OS 落地后对照 A14 真实 diff 复盘（待 A18 源码/AOSP 二次开源）。

---

## 延伸阅读（冲刺自检用）

1. AOSP：`frameworks/base/core/java/android/os/` 的 `Looper`/`MessageQueue` 对照 `frameworks/native/libs/utils/Looper.cpp` 的 epoll 实现，理解"休眠-唤醒"。
2. AOSP：`drivers/android/binder.c` 的 `binder_transaction()` 一行行看一次拷贝 + mmap 共享 buffer。
3. AOSP：`frameworks/base/core/java/android/view/ViewGroup.java` 的 `dispatchTouchEvent()`，重点看 DOWN 时 `FLAG_DISALLOW_INTERCEPT` 的 reset 位置。
4. AOSP：`frameworks/native/services/surfaceflinger/FrameTimeline.cpp` 理解 jank_type 定责。
5. androidx：`compose/runtime` 的 `ComposerImpl` + `Snapshot`，对照第 15/30 篇读强跳过实现。
6. 第 12/13/16/17/19/20/26/34/36/40 篇：本篇所有交叉引用点，冲刺时按链 A/B/C 串讲。
