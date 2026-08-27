# Android Framework 面试题 · 秋招白热期·高频八股源码级深挖与跨版本演进查缺补漏（第 44 篇）

> 日期：2026-08-27（周四）｜ baseline：Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX，内核 GKI android14-6.1) ｜ 系列第 44 篇 / 累计约 **275 专题**
> 适用场景：秋招白热期（9–11 月）高频八股精练 + 跨版本差异对抗 + 考官连环追问模拟
> 说明：承 8/26 考官评分视角，本篇把用户显式列出的全部领域（Handler/Looper、Binder、AMS/ATMS、WMS/View、App 启动、内存/卡顿/ANR、Jetpack/Compose、HAL/内核/drivers、MTK）做「源码级深挖 + 跨版本演进（A14->A17->A18）查缺补漏」。每题统一结构：**问题 -> 答案解析(源码路径/方法名) -> 跨版本演进 -> 易错点 -> 考官高频连环追问(标准答案) -> 延伸阅读**。本篇刻意关闭全系列长期隐含但未独立成篇的视角——「跨版本差异对照」。

---

## 0. 当日热点锚定（2026-08-27）

- **A17 QPR1 stable 跟踪 2026-09**：随 Pixel Feature Drop 落地 + 新一轮安全补丁，题库即将进入「稳定版 + 补丁」更新窗口。QPR2 stable 仍跟踪 2026-12。
- **Aluminium OS / Googlebook 9.15 纽约发布会倒计时约 3 周**：首个 mainline 维护的 x86 Android 端口，vendor 模块须同时支持 ARM64 + x86_64 两套 KMI；WMS/display 把外接 DisplayPort/HDMI 热插拔 + 真·多窗口做成一等公民；Magic Pointer 输入语义成为 Input 新考点。
- **2026 秋招趋势（白热期）**：跨版本演进题（"A14 和 A17/A18 这块有什么变化？"）权重明显上升——考官用版本差异区分候选人深度。Handler/Binder/Compose/ANR/Native crash/量化数字仍是最高频深考题。
- **本篇主线**：把前 43 篇约 270 专题按「版本演进 + 连环追问」重新组织，重点补「跨版本差异对照」这一真缺口，并强化每题的「考官会怎么追问」。

---

## 专题一：Handler / Looper —— 主线程死循环为什么不 ANR？

### 问题
"主线程 `Looper.loop()` 是死循环，为什么不会 ANR？消息队列空了线程在干什么？同步屏障、IdleHandler、消息池分别解决什么？A14->A17 这块有变化吗？"

### 答案解析 + 底层原理
- **死循环不 ANR 的真相**：ANR 是「主线程处理某条消息时超时」（输入派发 5s / 广播 10s / 服务 20s），不是「在跑循环」。`Looper.loop()` 在 `MessageQueue.next()` 里**阻塞休眠**，不占 CPU。
  - `frameworks/base/core/java/android/os/Looper.java`：`loop()` -> `queue.next()` -> 无消息时 `nativePollOnce(ptr, -1)` 永久休眠（timeout=-1）。
  - `frameworks/base/core/java/android/os/MessageQueue.java`：`nativePollOnce` -> `frameworks/base/core/jni/android_os_MessageQueue.cpp` -> `nativeMessageQueue->pollOnce()` -> `frameworks/native/libs/utils/Looper.cpp` 的 `pollOnce()` -> **`epoll_wait()`** 在 `mWakeEventFd` + 其他 fd 上休眠。
- **唤醒**：`Looper.cpp` 持有 `mWakeEventFd`（eventfd），`wake()` 时 `write(mWakeEventFd, 1)` 触发 `epoll_wait` 返回。
- **同步屏障**：`MessageQueue.postSyncBarrier()` 插入 `target == null` 的屏障，`next()` 遇屏障**跳过所有同步消息、只取异步消息**（`Choreographer` 的 `MSG_DO_FRAME` 是异步消息），保证渲染优先。`removeSyncBarrier()` 必须配对移除，否则队列卡死。
- **IdleHandler**：队列空且无可运行异步消息时触发 `mIdleHandlers`（`ActivityThread.GcIdler`、`ActivityThread.Idler` 上报 `ActivityIdle`）。`queueIdle()` 返回 false 表示一次性。
- **消息池**：`Message.sPool` 单链表复用池，`MAX_POOL_SIZE = 50`，`obtain()`/`recycleUnchecked()` 复用，避免高频消息 GC 抖动。

### 跨版本演进（A14 -> A17 -> A18）
- 核心机制稳定跨版本，`epoll` 休眠模型自 Android 2.3 起未变。
- A17/A18 仅在 JankStats / 帧调度侧增强（Choreographer 与 `FrameTimeline` 联动更紧），Looper/MessageQueue 公共契约不变。面试被问"有没有变化"要答：**接口与休眠模型稳定，演进在 Choreographer/帧定责侧，不在 Looper 本身**。

### 易错点
1. 主线程空闲是 `epoll` 休眠非空转——`top` 看到主线程 0% CPU 正常。
2. 同步屏障 `target==null`，普通 `Handler` 发的消息 `target` 非 null，不过屏障。
3. `Handler` 内存泄漏：非静态内部类持外部 `Activity`，用静态类 + `WeakReference` 或 `onDestroy` 调 `removeCallbacksAndMessages(null)` 清理。

### 考官高频连环追问（标准答案）
- "postDelayed 延时怎么保证？" -> `enqueueMessage()` 按 `when` 插入有序队列；`nativePollOnce` 的 timeout = `nextMessage.when - now`，到点唤醒。
- "为什么 `Choreographer` 用异步消息？" -> 保证 vsync 帧回调不被普通消息饿死（接专题六卡顿定责）。

### 延伸阅读
`frameworks/base/core/java/android/os/Looper.java` + `MessageQueue.java` 对照 `frameworks/native/libs/utils/Looper.cpp` 的 epoll 实现。

---

## 专题二：Binder IPC —— 一次拷贝、线程池 15、oneway 排队、getCallingUid 不可信

### 问题
"Binder 一次事务到底拷贝几次？为什么是 mmap 不是多次 copy？Binder 线程池默认多大？oneway 会阻塞吗？跨进程 `getCallingUid()` 为什么有时不可信？A17 在 Binder 安全上做了什么？"

### 答案解析 + 底层原理
- **一次拷贝**：`BpBinder.transact()` -> `IPCThreadState::transact()` -> `writeTransactionData()` 写 `Parcel` 进 `mOut` -> `talkWithDriver()` 经 `ioctl(BINDER_WRITE_READ)` 入 `drivers/android/binder.c` 的 `binder_ioctl` -> `binder_thread_write` -> `binder_transaction()`。
  - `binder_transaction()` 在内核态用 `copy_from_user()` **把发送方用户态 Parcel 拷到内核 binder_buffer 一次**；接收方通过 **mmap 共享同一内核 buffer**（`binder_mmap`），接收方 `BR_TRANSACTION` 直接读映射区，**无需第二次拷贝**。
- **线程池默认 15**：`frameworks/native/libs/binder/ProcessState.cpp` `mMaxThreads = 15`；`BR_SPAWN_LOOPER` 可动态扩容。`startThreadPool()` + `joinThreadPool()` 让 `IPCThreadState` 循环 `getAndExecuteCommand()` -> `executeCommand(BR_TRANSACTION)` -> `onTransact()`。
- **oneway 也排队**：`oneway` 不阻塞发送方返回，但接收方 `binder_transaction` 仍串行入队；对端 15 线程全忙时 oneway 在 `binder_proc.todo` 等待——oneway 满也排队导致对端 LMK/ANR 的源头。
- **getCallingUid 不可信两场景**：①跨 VM（AVF/pKVM）RPC Binder——`getCallingUid()` 返回 VM 内 UID，host/guest 内核命名空间不同；②Provider/系统服务侧——`Binder.getCallingUid()` 拿到 `SYSTEM_UID` 不可信，必须 attestation/签名校验。`clearCallingIdentity()` / `restoreCallingIdentity()` 临时切换身份。
- **linkToDeath**：`IBinder.linkToDeath()` 注册 `DeathRecipient`，对端进程死时收 `binderDied()`，用于重连（如 `ActivityManager` 死连 system_server）。

### 跨版本演进（A14 -> A17 -> A18）
- 三大上下文（`/dev/binder`、`/dev/hwbinder`、`/dev/vndbinder`）稳定。
- **A17 安全演进**：防 confused-deputy 在 Telecom/Phone 侧前置校验（`PhoneInterfaceManager.sendUssdRequest` 校验 MMI 前缀），并强化跨 VM Binder 身份——呼应"getCallingUid 不可信"在 AVF/pKVM 场景更突出（A17 端侧 AI 跑 Microdroid 变多）。
- **AIDL for HAL 取代 HIDL** 进程持续（A10+），A17 新 HAL 几乎全 AIDL。A18 Aluminium OS 的 x86 端口不新增 binder 上下文，但 vendor binder 须过 x86_64 KMI。

### 易错点
1. "一次拷贝"非零拷贝——相对 socket/管道（两次拷贝）更优，但发送方到内核仍有一份 `copy_from_user`。
2. `mmap` 只解决接收方读，发送方到内核仍拷贝。
3. 三上下文：`/dev/binder`(framework)、`/dev/hwbinder`(HAL)、`/dev/vndbinder`(vendor)。

### 考官高频连环追问（标准答案）
- "Binder 比 socket 快在哪？" -> 一次拷贝 + 内核对象引用（binder_node/binder_ref 句柄映射）而非全量序列化。
- "binder 死锁怎么排查？" -> `dumpsys activity binder` + `binderinfo` + `atrace` 的 `binder` 类别，看 `binder_transaction` 阻塞在哪个对端 `to_pid`。

### 延伸阅读
`drivers/android/binder.c` 的 `binder_transaction()` 逐行看一次拷贝 + mmap 共享 buffer；`frameworks/native/libs/binder/ProcessState.cpp` 看线程池上限。

---

## 专题三：AMS / ATMS —— startActivity 链路与 ContentProvider 前置坑

### 问题
"从 `startActivity` 到应用进程收到 `onCreate`，经过哪些关键类？为什么冷启动有时被 ContentProvider 拖慢？oom_adj 怎么分级？A17/A18 窗口组织有什么变化？"

### 答案解析 + 底层原理
- **调度链路**：`Context.startActivity()` -> `Instrumentation.execStartActivity()` -> `ActivityTaskManagerService.startActivity()`（`frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java`）-> `ActivityStarter.execute()` -> `startActivityUnchecked()`（解析 flag/launch mode/task affinity）-> `RootWindowContainer.resumeFocusedTasksTopActivities()` -> `ActivityStackSupervisor.realStartActivityLocked()` -> `ClientTransaction`(`LaunchActivityItem`) 经 Binder 交 `ActivityThread`。
- **ContentProvider 前置坑**：`ActivityThread.handleBindApplication()` 先 `installContentProviders()`，**ContentProvider 的 `onCreate()` 跑在 `Application.onCreate()` 之前**，三方 SDK 借 Provider 自启做耗时 I/O 会拖慢冷启动首帧。
- **oom_adj 分级**：`frameworks/base/services/core/java/com/android/server/am/ProcessList.java` `computeOomAdjLocked()` 算 `oomScoreAdj`；前台 `FOREGROUND_APP_ADJ=0`，可见/可感知/后台/缓存逐级递增；`lmkd` 据此杀进程。

### 跨版本演进（A14 -> A17 -> A18）
- **A14 起窗口组织统一走 `WindowOrganizer` + `WindowContainerTransaction`(WCT)**：`DesktopTasksController` / `TaskbarController` / `SplitScreenController` 均不再用老的 `ActivityManager` 直接窗口 API。A17 折叠屏窗口 handle、桌面模式 taskbar 在此之上强化。
- **A18 Aluminium OS 桌面融合**：WM1.5 尺寸类（`WindowManager1.5` sizing classes）、`Navigation3`、`KeyboardShortcutGroup` 成为一等公民；`startActivity` 调度骨架不变，但 task/display 组织在外接显示器场景大幅扩展。

### 易错点
1. `launchMode` 判定在 `ActivityStarter.startActivityUnchecked`，不是 AMS 入口。
2. `singleTask`/`singleInstance` 的 task 亲和在 `TaskRecord` 层。
3. ContentProvider `onCreate` 早于 `Application.onCreate`——冷启动优化隐藏雷区。

### 考官高频连环追问（标准答案）
- "冷启动有时不走 Zygote fork？" -> USAP（Unspecialized App Process）池预 fork 特化，省 fork 抖动。
- "后台进程被杀顺序？" -> 见专题五三路杀与 oom_adj。

### 延伸阅读
`frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java` + `ActivityStackSupervisor.realStartActivityLocked()`。

---

## 专题四：WMS / View —— 事件三方法、requestDisallowIntercept 坑、MeasureSpec

### 问题
"`View` 事件分发三个方法各自职责？`requestDisallowInterceptTouchEvent` 对 DOWN 为什么无效？`MeasureSpec.AT_MOST` 经典坑？`getMeasuredWidth()` 和 `getWidth()` 何时不等？A17/A18 输入侧有什么新考点？"

### 答案解析 + 底层原理
- **三方法责任链**（`frameworks/base/core/java/android/view/`）：
  - `ViewGroup.dispatchTouchEvent()`：总调度，先问自己 `onInterceptTouchEvent()`（仅 ViewGroup 有），再下发给子 `dispatchTouchEvent()`。
  - `ViewGroup.onInterceptTouchEvent()`：返回 true 拦截，事件转自己 `onTouchEvent()`，子 View 收 `ACTION_CANCEL`。
  - `View.onTouchEvent()`：真正消费（true 表示消费）。
- **requestDisallowIntercept 对 DOWN 无效**：`ViewGroup.dispatchTouchEvent()` 在 `ACTION_DOWN` 时 **reset `FLAG_DISALLOW_INTERCEPT`**（`mGroupFlags &= ~FLAG_DISALLOW_INTERCEPT`），子 View 在 DOWN 调 `requestDisallowInterceptTouchEvent(true)` 被忽略；标志只对 MOVE/UP 生效。滑动嵌套经典坑。
- **ACTION_CANCEL 语义**：父在 MOVE 突然拦截，子收 `ACTION_CANCEL`，必须当 UP 处理（清理按下态）。
- **MeasureSpec 三模式**：`ViewRootImpl.performTraversals()` -> `performMeasure()` -> `View.measure()` -> `onMeasure()`。`MeasureSpec` = `specSize + specMode`：`UNSPECIFIED` / `EXACTLY`(match_parent/精确值) / `AT_MOST`(wrap_content, 父给上限)。
  - **AT_MOST 坑**：自定义 `onMeasure` 不处理 `AT_MOST` 而直接用 spec 里的 size，wrap_content 表现像 match_parent。
- **getMeasuredWidth vs getWidth**：`getMeasuredWidth()` = `mMeasuredWidth`（measure 阶段）；`getWidth()` = `mRight - mLeft`（layout 阶段）。measure 之后、layout 之前两者不等；动画/自定义 layout 常不等。

### 跨版本演进（A14 -> A17 -> A18）
- 事件分发三方法、`MeasureSpec` 公共契约稳定跨版本。
- **A18 新考点**：Aluminium OS 外接 DisplayPort/HDMI 热插拔 + 真·多窗口，输入归一化新增 `KeyboardShortcutGroup` / `TouchpadInputMapper` + libchrome-gestures；Magic Pointer（摇动光标 contextual select）是 Input 语义新维度。A17 折叠屏多任务强化 `MotionEvent` 的多指/分屏派发。

### 易错点
1. `onInterceptTouchEvent` 仅 ViewGroup 有，`View` 没有。
2. `requestDisallowInterceptTouchEvent` 在 DOWN 被 reset，故意设计（父须在 DOWN 决定拦截链）。
3. `onTouchListener.onTouch` 返回 true 短路 `onTouchEvent`，优先级高于 `onClick`。
4. `wrap_content` 必须手动处理 AT_MOST，否则退化为 match_parent。

### 考官高频连环追问（标准答案）
- "事件分发和输入系统怎么衔接？" -> `InputReader` -> `InputDispatcher` -> `InputChannel`(socketpair) -> `ViewRootImpl.InputStage` -> `DecorView.dispatchTouchEvent`。
- "onTouchEvent 返回 false 会怎样？" -> 事件向上回溯父 `onTouchEvent`，最终无人消费则下次 DOWN 不再派发该序列。

### 延伸阅读
`frameworks/base/core/java/android/view/ViewGroup.java` 的 `dispatchTouchEvent()`，重点看 DOWN 时 `FLAG_DISALLOW_INTERCEPT` 的 reset 位置。

---

## 专题五：App 启动流程 + 内存 / 卡顿 / ANR 三路杀

### 问题
"冷启动三段是什么？基线 Profile 与云 Profile 怎么加速？内存压力有哪几条杀路径？卡顿掉帧怎么定责？ANR 有几类超时？**A17 在内存治理上有什么标志性新增？**"

### 答案解析 + 底层原理
- **冷启动三段**：①`Zygote.forkAndSpecialize()`（或 USAP 特化）建应用进程 -> `ActivityThread.main()` 建主线程 Looper；②`handleBindApplication()`：`installContentProviders`（前置坑）+ `makeApplication()` + `Application.onCreate()`；③`performLaunchActivity()` -> `onCreate/onStart` -> `handleResumeActivity()` -> `ViewRootImpl.setView()` -> `performTraversals()` 首帧。
- **启动加速**：基线 Profile（`/data/misc/profiles/ref/` 元数据，PMS 安装期触发 `dexopt`）+ 云 Profile（Play 下发聚合 Profile 做云编译）；`PinnerService`（`frameworks/base/services/core/java/com/android/server/PinnerService.java`）把关键 dex/oat 锁进 RAM 防 page-out。
- **三路杀（A14）**：①`lmkd`（`system/core/lmkd/lmkd.cpp`，基于 PSI + 内存水位，杀低 oom_adj 后台）；②内核 OOM killer（按 `oom_score` 杀，连前台都可能）；③`onTrimMemory()` 是提示非救命。
- **卡顿定责**：`Choreographer.doFrame()` 分 `CALLBACK_INPUT -> CALLBACK_ANIMATION -> CALLBACK_TRAVERSAL`；`FrameTimeline`（`frameworks/native/services/surfaceflinger/FrameTimeline`）的 `jank_type` 把责任定到 App/RenderThread/SF/HWC。
- **ANR 四类超时**：输入派发 `DEFAULT_INPUT_DISPATCHING_TIMEOUT=5s`（native `InputDispatcher` 计时，非 App Looper）、广播 `BROADCAST_FG_TIMEOUT=10s`、服务 `ACTIVE_SERVICES_TIMEOUT=20s`、ContentProvider `CONTENT_PROVIDER_PUBLISH_TIMEOUT=10s`。

### 跨版本演进（A14 -> A17 -> A18）—— 本专题重点
- **A17 标志性新增：Memory Limiter**（应用个体内存配额超标，**静默杀** `ApplicationExitInfo.REASON_MEMORY_LIMITER`，无 ANR）。三路杀变「lmkd(PSI) / A17 Memory Limiter(静默) / 内核 OOM」独立三路径。这是 A14->A17 在内存治理上最值得说的演进点。
- **A15+ 16KB 页面**：驱动 `alloc` 须对齐 16KB，`p_align` 校验（A14 默认 4KB，A15 起支持 16KB，影响 native 内存/驱动）。
- A18 桌面融合可能引入更大图形内存预算（外接显示器多屏合成），`FrameTimeline` 定责模型不变。

### 易错点
1. Input ANR 的 5s 计时器在 **native InputDispatcher**，不是主线程消息循环。
2. 三路杀是**独立路径**，Memory Limiter 不报 ANR（A17 新增，易漏答）。
3. 基线 Profile 只覆盖安装期已知热方法，云 Profile 才覆盖用户真实路径。
4. `FrameTimeline` 的 `jank_type` 取值决定优化方向。

### 考官高频连环追问（标准答案）
- "怎么用 Perfetto 抓一次冷启动？" -> `android_startup` + slice 拆解 bindApplication/installContentProviders（见 Perfetto SQL 范例库）。
- "后台进程被杀后用户数据会丢吗？" -> ViewModel + SavedStateHandle 跨进程重建，但非持久化数据仍丢。

### 延伸阅读
`frameworks/base/services/core/java/com/android/server/am/ProcessList.java`(oom_adj) + `system/core/lmkd/lmkd.cpp`(PSI) + `frameworks/native/services/surfaceflinger/FrameTimeline.cpp`(jank_type)。

---

## 专题六：Jetpack / Compose 底层机制

### 问题
"Compose 的 SlotTable 是什么结构？Snapshot 怎么实现『读取即订阅』？Recomposer 挂在 Choreographer 哪个回调？强跳过（Strong Skipping）改了什么？Kotlin 版本演进对 Compose 编译器有什么影响？"

### 答案解析 + 底层原理
- **SlotTable gap buffer**：`androidx.compose.runtime.ComposerImpl` 用平坦 `IntArray`(slots)+`IntArray`(keys)+Anchor 表，逻辑树、物理数组；插入/删除用 gap buffer 移动，避免整段拷贝。
- **Snapshot MVCC**：`Snapshot` 维护全局版本号；`State.read()` 经 `readObserver` 把当前 `Snapshot` 记为"依赖"（读取即订阅）；`Snapshot.apply()` 冲突检测（写写冲突抛 `SnapshotApplyConflictException`）。`derivedStateOf` 惰性重算。
- **Recomposer 挂 CALLBACK_ANIMATION**：`Recomposer.runRecomposeAndApplyChanges()` 经 `Choreographer` 的 **`CALLBACK_ANIMATION`**（非 `TRAVERSAL`）调度重组；同帧 INPUT->ANIMATION->TRAVERSAL，重组在遍历前完成，保证本帧生效。
- **强跳过（Strong Skipping）**：Kotlin 2.0.20 默认开。`$stable` 位域判定稳定性；强跳过把"是否跳过重组"从"推断稳定则跳过"升级为"**引用相等 `===` 即可跳过**"，但**不改 `$changed` 位域**、非 stable 类型仍可能每帧重组。

### 跨版本演进（A14 -> A17 -> A18）
- **Kotlin 2.0（K2）把 Compose 编译器并入主编译器**（IR 统一中间表示），不再用独立注解处理器——这是 A14 后期到 A17 的重大演进。
- **强跳过自 Kotlin 2.0.20 默认开**（约 A17 生态），减少不必要重组，但引用相等陷阱仍在（每次 new 对象仍重组）。
- A18 持续，Compose 在 Android 仍走 HWUI（RenderNode），未绕过 `frameworks/base` 图形栈。

### 易错点
1. Recomposer 挂 `CALLBACK_ANIMATION`，不是 `CALLBACK_TRAVERSAL`。
2. 带返回值的 `@Composable` 不是重组边界。
3. 强跳过 != 改 stability；引用相等陷阱仍要 `@Stable`。

### 考官高频连环追问（标准答案）
- "`$changed` 位掩码怎么工作？" -> 每参数 2 bit（real + static），父方编译期预填，运行时 `ComposerImpl.changed()` 解码决定是否跳过 group。
- "Compose 语义树为什么对 AI Agent 更友好？" -> a11y 语义树映射（呼应 ANI）。

### 延伸阅读
androidx `compose/runtime` 的 `ComposerImpl` + `Snapshot`，对照 Compose 编译器 `$changed` 位掩码走读。

---

## 专题七：HAL / Linux kernel / drivers 驱动

### 问题
"Treble 怎么解耦 framework 与 vendor？Binder 三个上下文分别给谁？HIDL 和 AIDL for HAL 怎么选？GKI / KMI 是什么？外接显示和键鼠内核驱动在哪？A18 在 GKI 上有什么新约束？"

### 答案解析 + 底层原理
- **Treble / VINTF**：`/system`(framework) 与 `/vendor`(HAL) 通过 **HAL 接口**解耦，`compatibility_matrix.xml` + `manifest.xml` 双向校验版本。
- **Binder 三上下文**：`/dev/binder`(framework)、`/dev/hwbinder`(HAL)、`/dev/vndbinder`(vendor 自定义，`ProcessState.initWithDriver`)。
- **HIDL vs AIDL for HAL**：旧 HAL 用 HIDL（`hardware/interfaces/*`，`Ixxx.hal`）；Android 10+ 新 HAL 统一 **AIDL for HAL**（`aidl_interface`），共享 Binder 模型、更易版本演进。
- **GKI 2.0 / KMI**：Generic Kernel Image 让 `/system` 与内核解耦，vendor 驱动须过 **KMI（Kernel Module Interface）稳定 ABI**——`drivers/` 暴露符号 ABI 锁定，`include/` 头稳定。
- **外接显示**：`drivers/gpu/drm/`（DRM/KMS）——`drm_connector`/`drm_crtc`/`drm_plane`，hotplug 经 `drm_kms_helper_hpd_irq_event` -> SF/HWC 重枚举 -> `DisplayManagerService`。
- **键鼠**：`drivers/hid/`——`hid-core` 解析 report descriptor -> `input_event` -> `evdev` 节点 `/dev/input/eventN` -> `EventHub`。

### 跨版本演进（A14 -> A17 -> A18）—— 本专题重点
- **GKI/KMI 自 A14 已是主线**；A18 Aluminium OS 把 **x86_64 也拉进 KMI 约束**（首个 mainline x86 Android 端口），vendor 模块须同时支持 ARM64 + x86_64 两套 KMI。
- **16KB 页面对内核驱动的要求**（A15+）：驱动 `alloc`/`p_align` 须对齐 16KB。
- AIDL for HAL 在 A17 基本完成存量迁移。

### 易错点
1. GKI 锁的是 **KMI 符号 ABI**，不是内核版本号；vendor 模块须基于同一 KMI 分支编译。
2. `hwbinder` 与 `vndbinder` 都基于 Binder 驱动，只是独立上下文、独立 handle 空间。
3. KMS 负责模式协商（EDID/分辨率），Framework 只能"选"不能"造"。

### 考官高频连环追问（标准答案）
- "vendor 驱动怎么过 GKI？" -> vendor hook / DDK 模式（KMI + vendor hook）。
- "16KB 页面要求什么？" -> 驱动分配对齐 16KB，`p_align` 校验，否则加载失败。

### 延伸阅读
`drivers/android/binder.c`(三上下文内核侧) + `drivers/gpu/drm/`（外接显示）+ `drivers/hid/`（键鼠）。

---

## 专题八：MTK 平台真缺口（AEE / mtklog / PerfService / thermal / vendor HAL）

### 问题
"MTK 平台有哪些独有排查与性能机制？AEE / mtklog 怎么用？PerfService 和 thermal 是什么？A14 之后 MTK 怎么过 GKI？"

### 答案解析 + 底层原理
- **AEE（Android Exception Engine）**：MTK 异常收集框架，`/system/bin/aee`（`exp_main` 等），崩溃/重启生成 `db.*` 异常报告（比 tombstone 更全，含 kernel/driver 上下文），存 `/data/aee_exp/`。
- **mtklog**：三件套——`mobilelog`(logcat/kernel)、`mdlog`(modem)、`netlog`(网络)。抓取用 `mktlog`/`aee_extract`，定位 modem/射频/功耗。
- **PerfService**：MTK 性能调度 API（`vendor/mediatek/proprietary/hardware/perfservice/`），App 可提频/绑核（`perfBoost`/`perfLockAcquire`）防卡顿；长期 perfLock 拉高频率引入发热。
- **thermal**：MTK thermal HAL + 内核 thermal zone，温度超阈降频；与 Android `Thermal HAL` -> `Power HAL`/`ADPF` 联动。
- **vendor HAL / 内核驱动**：MTK 大量 vendor 模块须过 GKI KMI（A14+）。

### 跨版本演进（A14 -> A17 -> A18）
- AEE/mtklog/PerfService/thermal 机制跨版本稳定，是 MTK 平台长期积累。
- A14 起 MTK vendor 驱动同样须对齐 GKI KMI 分支；A18 若出 x86 变体，MTK 须额外支持 x86_64 KMI（目前 MTK 主战场仍是 ARM64 手机/座舱）。

### 易错点
1. AEE 报告在 `/data/aee_exp/`，不是 `/data/tombstones/`（后者 AOSP debuggerd）。
2. `mtklog` 三件套分清 mobilelog/mdlog/netlog 用途。
3. PerfService 是双刃剑——提频换流畅但牺牲功耗/发热。

### 考官高频连环追问（标准答案）
- "MTK 机器卡顿发热怎么排查？" -> mtklog + perfetto（thermal 计数器）+ PerfService 占用三方交叉。

### 延伸阅读
`vendor/mediatek/proprietary/hardware/perfservice/` + `/data/aee_exp/` 报告结构。

---

## 专题九：三条跨子系统高频追问链（版本演进 + 连环追问核心能力）

### 链 A：一次冷启动 ANR —— Binder x AMS x 主线程 x A17 Memory Limiter
`startActivity`(ATMS) -> `realStartActivityLocked` 经 Binder 到应用 -> `handleBindApplication` 先 `installContentProviders` -> 某三方 Provider `onCreate` 做**同步 Binder 调用**卡对端（线程池满）-> 主线程阻塞 > 5s -> `InputDispatcher` 5s 计时触发 ANR。
**加试题**：为什么 InputDispatcher 计时而非主线程 Looper？（答：输入派发超时在 native 侧独立计时，主线程死循环不 ANR，但卡在某条消息里会）。**版本追问**：A17 新增 Memory Limiter 会不会让这个 ANR 变静默？（答：不会——Memory Limiter 是内存配额超标静默杀，与超时 ANR 是两条独立路径，本场景仍是标准输入 ANR）。

### 链 B：一次屏幕点击 —— Input x WMS x View x Choreographer
`Touchscreen` -> `evdev`(/dev/input) -> `EventHub` -> `InputReader`(TouchInputMapper) -> `InputDispatcher`(findFocusedWindowTargetsLocked) -> `InputChannel`(socketpair) -> `ViewRootImpl.InputStage` -> `DecorView.dispatchTouchEvent` -> `onInterceptTouchEvent` -> `onTouchEvent`。View `onTouchEvent` 耗时长 -> 主线程阻塞 -> 下一帧 `Choreographer.doFrame` 迟到 -> `FrameTimeline` jank_type=App Deadline Missed。
**加试题**：`requestDisallowInterceptTouchEvent` 在 DOWN 为何无效？（答：dispatchTouchEvent 在 DOWN reset FLAG_DISALLOW_INTERCEPT）。**版本追问**：A18 外接显示器下这条链在哪变了？（答：输入归一化新增 KeyboardShortcutGroup / TouchpadInputMapper，多屏焦点窗口查找 `findFocusedWindowTargetsLocked` 须按 displayId 路由，派发骨架不变）。

### 链 C：跨设备 AI 助手 —— CDM x RPC Binder x 机密计算 x 版本演进
手机 -> 笔记本 `CompanionDeviceManager` 持久 Association -> 通用剪贴板 `ClipboardService` 同步 `ClipData`（AES-GCM）-> 若 Gemini 本地推理跑在 pKVM Microdroid -> 笔记本 App -> 推理 VM 的 Binder RPC **getCallingUid 不可信** -> 必须 attestation/签名校验。
**加试题**：跨设备 UID 与跨 VM UID 为什么都不能当信任锚？（答：UID 是同一内核命名空间概念，跨设备/跨 VM 无共享内核，编号各自独立，伪造成本极低）。**版本追问**：A17 端侧 AI 普及后这条链更突出在哪？（答：Microdroid/pKVM 跑本地 LLM 变多，跨 VM Binder 身份校验成为 AppFunctions/AI agent 读数据的硬门槛，呼应 App Lock 的 NMS redaction）。

---

## 专题十：跨版本演进对照总表（A14 -> A17 -> A18）—— 本篇新视角

| 子系统 | Android 14 (baseline) | Android 17 | Android 18 (Aluminium OS) |
|---|---|---|---|
| 内存治理 | lmkd(PSI) + 内核 OOM 两路杀 | **新增 Memory Limiter 静默杀（REASON_MEMORY_LIMITER）** 成三路独立 | 三路杀不变，外接多屏图形内存预算扩大 |
| 页面大小 | 默认 4KB | 支持 16KB（驱动须对齐） | 16KB 常态化，驱动 p_align 强制 |
| 窗口组织 | WindowOrganizer + WCT 统一（Desktop/Taskbar/Split） | 折叠屏窗口 handle、桌面 taskbar 强化 | 桌面融合 WM1.5 sizing + Navigation3 + 外接 DP/HDMI 一等公民 |
| Binder | 三上下文稳定 | 跨 VM getCallingUid 不可信更突出（AVF/pKVM） | x86_64 KMI 约束，vendor binder 须双架构 |
| HAL | AIDL for HAL 取代 HIDL 进程 | 基本完成存量迁移 | 持续，x86 mainline 端口 |
| Compose | Kotlin 2.0(K2) 并入主编译器 | **强跳过 Kotlin 2.0.20 默认开** | 持续 |
| GKI/KMI | GKI 2.0 主线，ARM64 KMI | 稳定 | **x86_64 纳入 KMI 约束** |
| 安全特性 | — | App Lock / 防呼叫转移诈骗（confused-deputy 前置校验） | Magic Pointer 输入语义 + 跨设备 AI 安全边界 |
| 输入 | InputChannel socketpair 稳定 | 折叠屏多指/分屏派发强化 | KeyboardShortcutGroup / TouchpadInputMapper + libchrome-gestures |

> 面试金句：**"A14 是 stable baseline，A17 在内存治理(Memory Limiter)与安全(App Lock/防诈骗/跨VM身份)上标志性强，A18 在架构边界(x86 mainline + 桌面融合 + 外接显示)上突破。"**

---

## 专题十一：易错红榜 TOP20（全系列交叉 + 版本演进补充）

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
14. **A17 新增 Memory Limiter 静默杀，与 lmkd/内核 OOM 是独立三路（易漏答版本演进点）**（专题五）。
15. Input ANR 5s 计时在 native InputDispatcher，非 App Looper（专题五/链 A）。
16. `FrameTimeline` jank_type 定责 App/RenderThread/SF/HWC（专题五）。
17. Recomposer 挂 `CALLBACK_ANIMATION`，非 TRAVERSAL（专题六）。
18. 强跳过不改 `$stable` 位域，引用相等陷阱仍在（专题六）。
19. GKI 锁 KMI 符号 ABI，非内核版本号；**A18 把 x86_64 纳入 KMI**（专题七/专题十）。
20. AEE 报告在 `/data/aee_exp/`，非 tombstones（专题八）。

---

## 专题十二：AOSP 14 源码路径清单（本轮训专项）

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

## 专题十三：43 -> 44 篇交叉索引（跨版本演进查缺补漏专项）

- **第 43 篇（8/26 秋招开幕考官评分视角）**：本篇是其「逐题深挖 + 版本演进」执行版——第 43 篇给考官打分框架，本篇给每题的源码级答案 + A14/A17/A18 差异。
- **第 42 篇（8/25 月末冲刺全领域源码级真题压轴轮训）**：本篇专题一~九复用其真题场景，本篇补「跨版本演进」维度。
- **第 40/41 篇（8/24 A18 Aluminium OS WMS 重构 / 系统联动追问）**：本篇专题十对照表、链 B 的 A18 输入追问，复用其桌面融合/外接显示素材。
- **第 37 篇（8/21 A17 QPR2 Beta3 溯源）**：本篇专题十的 A17 行（App Lock / 防呼叫转移诈骗 / Memory Limiter 背景）复用。
- **第 34 篇（8/19 启动链路/system_server）**：本篇专题三/五复用 boot 侧与 oom_adj 三路杀。
- **第 16 篇（8/16 输入系统）**：本篇专题四/链 B 复用 InputChannel 与 Input ANR 计时器。
- **第 17 篇（8/17 HAL/内核驱动）**：本篇专题七复用 Treble/GKI/MTK。
- **第 15/30 篇（Compose 编译器/运行时）**：本篇专题六复用 SlotTable/Snapshot/强跳过。
- **第 12/13 篇（机密计算边界）**：本篇链 C 复用跨 VM getCallingUid 不可信。

> 全系列至此 **44 篇 / 约 275 专题** 完整闭环（含本篇「跨版本演进查缺补漏」真缺口）。剩余可选增量：真题大乱斗 vol.4（更刁钻混合场景，可基于本篇链 A/B/C 扩展）、Compose 编译器插件 IR 改写（`ComposableFunctionBodyTransformer` 内部）逐行、Aluminium OS 落地后对照 A14 真实 diff 复盘（待 A18 源码/AOSP 二次开源）。

---

## 延伸阅读（冲刺自检用）

1. AOSP：`frameworks/base/core/java/android/os/` 的 `Looper`/`MessageQueue` 对照 `frameworks/native/libs/utils/Looper.cpp` 的 epoll 实现。
2. AOSP：`drivers/android/binder.c` 的 `binder_transaction()` 逐行看一次拷贝 + mmap 共享 buffer。
3. AOSP：`frameworks/base/core/java/android/view/ViewGroup.java` 的 `dispatchTouchEvent()`，看 DOWN 时 `FLAG_DISALLOW_INTERCEPT` 的 reset 位置。
4. AOSP：`frameworks/native/services/surfaceflinger/FrameTimeline.cpp` 理解 jank_type 定责。
5. androidx：`compose/runtime` 的 `ComposerImpl` + `Snapshot`，对照强跳过实现。
6. AOSP：`system/core/lmkd/lmkd.cpp` + `frameworks/base/services/core/java/com/android/server/am/ProcessList.java` 理解 A17 Memory Limiter 之外的两套杀路径。
7. 第 12/13/16/17/30/34/37/40/41/42/43 篇：本篇所有交叉引用点，冲刺时按链 A/B/C + 专题十对照表串讲。
