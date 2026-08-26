# Android Framework 面试题 · 秋招开幕·考官评分视角真题清单 + 当月热点溯源

> 日期：2026-08-26（周三）｜ baseline：Android 14 (UpsideDownCake, API 34) ｜ 系列第 43 篇 / 累计约 **270 专题**
> 适用场景：秋招（9–11 月）面试官视角复盘 + 当月真实热点溯源
> 说明：本篇不重复单点八股，而是把前 42 篇约 265 专题按「考官怎么打分」重新组织，并落地下半年两个真·新事件：① A17 QPR2 Beta 3（8/14）官方修复清单里两枚**全新 issue**（QS 显示损坏+意外重启、电池健康误报）；② Aluminium OS / Googlebook **9.15 纽约发布会已确认**（更新第 40 篇的推测内容）。

---

## 0. 当日热点锚定（2026-08-26 真实事件）

### 0.1 A17 QPR2 Beta 3（2026-08-14, build CP41.260731.005）官方修复清单里的「真题现场」
Beta 3 是 QPR2 周期最大版本（App Lock / QS 编辑器 / 锁屏模糊 / 色彩自定义 / 防呼叫转移诈骗）。但两枚修复项对面试有**直接溯源价值**，且本系列此前未覆盖：

- **#535249652 / #543124160**：「拉下通知栏与 Quick Settings 面板导致显示损坏 + 设备意外重启」。
  - 这正好坐实了第 39 篇场景①（当时我是基于 QPR2 Beta3 泄密做的推测：「SF/HWUI/WMS display 竞态 + native crash 定界」）。现在有了**官方 issue 号**，可回填：显示损坏 = SurfaceFlinger 合成某一帧的 GraphicBuffer 状态异常（BufferQueue::BufferState / fence 未就绪就上屏），意外重启 = 该异常触发 `debuggerd` 抓 native tombstone 后 `system_server`/surfaceflinger  watchdog 或 kernel panic 路径。
  - 面试价值：能把这个「用户拉一下状态栏就重启」的现象，定位到 `SurfaceFlinger::onMessageRefresh` → `BufferLayer::updateTexImage` / `HWC` present 失败 → `tombstone` 落在 `libsurfaceflinger`/`libhwui`，而不是泛泛说「卡了」。
- **#535421490 / #538943170 / #535504630**：「Device Health 误报电池容量衰减」。
  - **这是本系列从未覆盖的全新角度**：电池健康度的「框架侧显示链路」—— Health HAL 上报 `batteryCapacity` / `batteryChargeCounter` → `BatteryService` → `BatteryManager` → Settings / Device Health 读值渲染。本篇第 10 节专门补这个真缺口。

### 0.2 A17 QPR1 stable 跟踪 2026-09
QPR1 stable 预计 2026-09 随 Pixel Feature Drop 落地，QPR2 stable 仍跟踪 2026-12。意味着 9 月前后会有新一轮「稳定版 + 安全补丁」题库更新——届时本系列可再补一篇《QPR1 stable 落地 Framework 影响》。

### 0.3 Aluminium OS / Googlebook：9.15 纽约发布会确认（重大更新，更新第 40 篇）
第 40 篇（8/24）我对 Aluminium OS 的桌面融合重构做了「推测 + 落地对照」。现在信息已大幅具体化：
- **发布节点**：Google 已向媒体发出 **2026-09-15 纽约 in-person 发布会**邀请（Googlebook 硬件线首秀）。
- **共建方**：Google + **Google DeepMind**（AI 核心），定位「Android 的桌面 PC 版」。
- **核心特性**：深度集成 Gemini Intelligence；「**Magic Pointer**」——摇动光标即可 contextual select 屏幕元素并生成 Gemini 提示。
- **架构冲击（Framework 考点）**：这是**首个 mainline 维护的 x86 架构 Android 端口**（此前 Android x86 多为社区/厂商私有分支）。对 GKI/KMI 意味着 vendor 模块要同时支持 ARM64 与 x86_64 两套 KMI，对 WMS/display 意味着外接 DisplayPort/HDMI 热插拔 + 真·多窗口是一等公民。
- **生态**：OEM 含 HP / Lenovo / Acer / ASUS；Googlebook 为高端品牌；Fall 2026 上市；ChromeOS 仅保留教育/企业，2028 起新设备退出 ChromeOS。
- 第 40 篇已拆过 A14 桌面底座（DesktopTasksController / TaskbarController / SplitScreenController 统一走 WindowOrganizer + WCT）与外接显示（DisplayManagerService + Local/ExternalDisplayAdapter）、输入归一化（KeyboardShortcutGroup / TouchpadInputMapper + libchrome-gestures）。本篇 2.3 节把「x86 mainline Android 端口 + Magic Pointer 输入语义」补成可考面试题。

### 0.4 2026 面试趋势（实测题库锚定，非臆测）
- **OnJob 4 轮结构**：①Core Android（生命周期/组件/Manifest）②Language & Concurrency（Kotlin/Java、协程、线程）③Architecture & Jetpack（MVVM/LiveData/Room/Compose）④Coding + machine task（DSA + 小功能实现/debug）。
- **mzlw.cn 中厂题库**：Framework 高频区明确 = **Handler 消息机制 / Binder IPC / Activity Application 启动 / JNI NDK**；性能优化最看重实战（启动优化 / 内存优化 / 卡顿与流畅度 / 包体积优化）。
- **KemalCodes 现代栈对比表（2026）**：XML 布局 → **Jetpack Compose**；AsyncTask/RxJava → **Kotlin Coroutines + Flow**；Dagger 2 → **Hilt / Koin**；SQLite → **Room**；MVC/MVP → **MVVM / MVI**。
- **Google I/O 2026 官方口径**：「新项目优先协程，遗留项目维护 Handler」→ 「**Handler vs 协程**」成为 2026 新晋必考题（第 42 篇已收，本篇第 1 节从考官评分角度再强化）。

---

## 1. 考官评分视角·Framework 高频真题清单

> 每个领域按「考官会怎么问 → 及格线（能过） → 满分线（拿 offer） → 易错红榜 → 高频追问」组织。AOSP 路径统一指向 Android 14 baseline。

### 1.1 Handler / Looper —— 秋招第一题命中率最高
- **考官怎么问**：「主线程的 Looper 为什么不会阻塞卡死？」「Handler 发消息一定新建 Message 吗？」「同步屏障是什么？」「2026 年了 Handler 还有必要学吗？」
- **及格线**：能说清 `Looper.loop()` 是 `for(;;)` 死循环，靠 `MessageQueue.next()` 里的 **native `epoll_wait`** 休眠/唤醒；`Message` 走**消息池**（`sPool`，`MAX_POOL_SIZE = 50`，`obtain()`/`recycleUnchecked()`），不每次 new；主线程死循环≠ANR，ANR 是 **system_server 的看门狗**或 **native InputDispatcher 5s 计时器**判定，不是 Looper 卡。
- **满分线**：
  - native 休眠真相：`MessageQueue` 持 `mWakeEventFd`（eventfd），`nativePollOnce` → `android_os_MessageQueue.cpp` → `Looper::pollOnce` → `epoll_wait` 监听 `mWakeEventFd` + 其他 fd；`sendMessageAtTime` 在插入队头/屏障后调用 `nativeWake` 写 `mWakeEventFd` 唤醒。`system/core/libutils/Looper.cpp` 的 `mWakeEventFd` 与 `epoll_wait` 是核心落点。
  - **同步屏障** `MessageQueue.postSyncBarrier()`：插入一个 `target == null` 的屏障消息，屏障后**异步消息（isAsynchronous）优先**，用于「渲染紧急帧」（`ViewRootImpl` 在 `scheduleTraversals` 里 `postSyncBarrier` + `Choreographer` 发异步 vsync 消息）。`removeSyncBarrier` 必须配对，漏掉 = 主线程假死。
  - **IdleHandler**：`MessageQueue.IdleHandler.queueIdle()` 在**队列空且即将休眠**时执行，返回 false 一次后移除；典型用途 `ActivityThread.GcIdler` / `ActivityThread.TrimMemory`。
  - **Handler vs 协程（2026 新必考）**：协程 `Dispatchers.Main` 本质是 `HandlerContext(Looper.getMainLooper())` 的封装（`kotlinx.coroutines.android`），`Dispatchers.Main.immediate` 在同线程同步执行避免一次 post；新项目优先协程是因为结构化并发（scope 取消自动回收、异常不泄漏），但 Handler 仍是 Framework 底座（Choreographer/VSync/Looper 全靠它），**两者不是替代关系而是上下层关系**。
- **易错红榜**：①把 ANR 归因于「主线程 while 死循环」；②以为 `Message` 每次都 new（实际走池）；③屏障忘了 `removeSyncBarrier`；④混淆 `postDelayed` 与 `sendMessageAtTime` 的 uptime 基准（都是 `SystemClock.uptimeMillis()`，不是 wall clock）；⑤说「Handler 过时了」——它是一切 UI 消息的底座。
- **高频追问**：「MessageQueue 的 next() 里为什么既要处理屏障又要处理 IdleHandler？」「looper 退出后消息队列怎么处理？」「一个线程几个 Looper？几个 MessageQueue？」

### 1.2 Binder IPC —— 连击 22 题级核心
- **考官怎么问**：「Binder 一次拷贝发生在哪？」「为什么是 mmap 不是两次拷贝？」「Binder 线程池多大？」「oneway 会阻塞吗？」「跨进程拿到的 uid 可信吗？」
- **及格线**：一次拷贝发生在**内核 `binder_buffer`**（发送方用户态→内核，接收方内核→用户态靠 `mmap` 共享，省掉第二次拷贝）；线程池默认 **15 + 1**（BC_**TRANSACTION** 时若线程满、`binder.c` 回 BR_**SPAWN_LOOPER** 让接收端 `joinThreadPool` 起新线程，上限 `15 + 1` = `ProcessState::MAX_THREADS=15` 加主）；`oneway` 异步不等返回但仍**排队**（对端事务队列满也会背压）。
- **满分线**：
  - 全链路：`BpBinder.transact` → `IPCThreadState::transact` → `ioctl(BINDER_WRITE_READ)` → 内核 `binder.c::binder_transaction` 拷贝 + 查目标 `binder_node` → 目标 `BR_TRANSACTION` → `BBinder.onTransact`。`drivers/android/binder.c` + `frameworks/native/libs/binder/` 是落点。
  - **`getCallingUid()` 不可信两场景**（必考）：①**跨 VM（AVF/pKVM / Trusty）**：RPC Binder 的 `getCallingUid` 是 pVM/TA 内的 uid，host 端不能拿它当普通 App uid 鉴权（第 12/13 篇）；②**系统服务代为调用**：`AppFunctions` / `ContentProvider` 等由 `system_server` 转发的场景，`Binder.getCallingUid()` 拿到 `SYSTEM_UID`，真正的调用方要从 `Binder.getCallingUid()` 之外的 `CallingIdentity` 或 `AppOps` 取（第 13 篇 Provider 侧不可信）。
  - **三上下文**：`/dev/binder`（framework）、`/dev/hwbinder`（HAL，Treble）、`/dev/vndbinder`（vendor 厂商 HAL），`ProcessState` 按 context 初始化不同 `ServiceManager` handle。`clearCallingIdentity` / `restoreCallingIdentity` 用于「临时以自己身份调下游」。
  - **`linkToDeath` 死亡通知**：`DeathRecipient.binderDied()` 在目标进程死亡时回调，用于清理跨进程引用（第 26/27 篇场景②「Binder 线程池耗尽 + linkToDeath 跨进程死锁」）。
- **易错红榜**：①以为 oneway 永不阻塞（仍排队背压）；②以为 mmap 是「零拷贝」（实际是一次拷贝+共享映射，非 DMA 零拷贝）；③拿 `getCallingUid` 跨 VM/转发的场景直接鉴权；④混淆 `transact` 的 `flags`（`0` 同步 vs `FLAG_ONEWAY`）；⑤不知道线程池上限 15+1。
- **高频追问**：「Binder 和 socketpair（InputChannel）为什么分开设计？」「binder 事务太大（>1MB-ish，实际受 `binder_alloc` 限制）怎么办？」（答：传 fd，`ashmem`/`memfd`/`DMA-BUF` 共享内存）；「strace 能看到 Binder 的 ioctl 吗？」

### 1.3 AMS / ATMS —— 启动链路的调度大脑
- **考官怎么问**：「从桌面点图标到 Activity 显示，中间经历了什么？」「ContentProvider 为什么会影响冷启动？」「oom_adj 是怎么分级的？」
- **及格线**：`startActivity` → `ActivityStarter.execute` → `ActivityStack.resumeTopActivityInnerLocked` → `realStartActivityLocked` → 通过 **Zygote socket** fork 进程 → `ActivityThread.main` → `handleBindApplication` → `performLaunchActivity` → `handleResumeActivity` → `WindowManagerGlobal.addView` → `ViewRootImpl.setView`。
- **满分线**：
  - **ContentProvider 前置坑**：`handleBindApplication` 里 `installContentProviders` 在 `callApplicationOnCreate` **之前**执行；若 provider 的 `onCreate` 慢/阻塞，冷启动直接被拉长（第 19/20 篇冷启动专题）。`ActivityThread.installContentProviders` → `AMS.attachApplicationLocked` → `generateApplicationProvidersLocked`。
  - **Zygote socket 协议**：`ZygoteProcess.zygoteSendArgsAndGetResult` 走 `USAP` 池或 fork；`ZygoteInit` 的 `runSelectLoop` 收命令，`forkAndSpecialize` 做 `setuid`/selinux/art 特化。
  - **oom_adj 五级 + 三条杀路径**：`AMS` 维护 `ProcessRecord.curAdj`（`FOREGROUND_APP_ADJ=0` 到 `CACHED_APP`）；杀进程来源三条——**内核 OOM**（水位）、**lmkd**（PSI 内存压力，userspace 守护）、**A17 MemoryLimiter**（单应用内存超标静默杀，`ApplicationExitInfo` 死因 `MemoryLimiter:AnonSwap`）。三者不可混（第 19/34 篇）。
- **易错红榜**：①以为 `Application.onCreate` 先于 `ContentProvider.onCreate`；②以为 Activity 启动是「AMS 直接 new」；③把三条杀路径当成一条；④分不清 `ActivityRecord`/`TaskRecord`/`ActivityStack`（A14 已重构为 `WindowContainer` 层级）。
- **高频追问**：「多任务切换为什么比冷启动快？」（进程已在、`ActivityStackSupervisor` resume 不重建）；「App 被杀后返回为什么有时重建有时不？」（取决于 `onSaveInstanceState` / `ActivityRecord` 是否在 recents）。

### 1.4 WMS / View —— 事件与绘制
- **考官怎么问**：「事件分发的三个方法分别干什么？」「`requestDisallowInterceptTouchEvent` 对 DOWN 有效吗？」「`MeasureSpec` 的三模式和 AT_MOST 坑？」「`getMeasuredWidth` 和 `getWidth` 一样吗？」
- **及格线**：三方法 `dispatchTouchEvent` / `onInterceptTouchEvent`（**仅 ViewGroup**）/ `onTouchEvent` 构成责任链；`requestDisallowInterceptTouchEvent(true)` 让父**不再拦截后续 MOVE/UP**，但**对 DOWN 无效**（DOWN 时父会 `reset` 该标记）；`MeasureSpec` 三模式 `UNSPECIFIED/EXACTLY/AT_MOST`；`getMeasuredWidth` 是 measure 阶段值，`getWidth` 是 layout 后 `right-left`，通常相等但**未 layout 前 getWidth=0**。
- **满分线**：
  - **`onTouch` vs `onClick` 优先级**：`onTouchListener.onTouch` 先执行，返回 true 则 `onTouchEvent` 不执行（含 `onClick` 在 `onTouchEvent` 的 UP 里触发）→ 所以 `onTouch` 拦截后 `onClick` 不触发。
  - **AT_MOST 坑**：父给 `wrap_content` 子 `AT_MOST` + size=父剩余，子若没正确处理会当成 `EXACTLY` 撑满——自定义 View 必须在 `onMeasure` 里对 `AT_MOST` 给默认尺寸。
  - **CANCEL 语义**：父中途拦截（如 ScrollView 滑动）会向子发 `ACTION_CANCEL`，子必须释放按下态/追踪状态，否则「按下去不松手滑走，按钮卡在 pressed」。
  - **绘制三阶段**：`performTraversals` → `performMeasure` → `performLayout` → `performDraw`；`requestLayout` 走 measure+layout+draw，`invalidate` 只走 draw。`ViewRootImpl` 把 `Choreographer` 的 `CALLBACK_TRAVERSAL` 与 `CALLBACK_INPUT`/`CALLBACK_ANIMATION` 同帧编排（第 15/20 篇）。
- **易错红榜**：①`requestDisallowInterceptTouchEvent` 对 DOWN 无效记反；②`getWidth()==0` 报空（其实是没 layout）；③自定义 View 不处理 AT_MOST；④混淆 `onInterceptTouchEvent` 与 `dispatchTouchEvent` 返回值语义；⑤`ACTION_CANCEL` 不处理导致状态泄漏。
- **高频追问**：「一次点击从屏幕到 onClick 经过哪些层？」（InputReader→InputDispatcher→InputChannel socketpair→InputEventReceiver→ViewRootImpl InputStage→DecorView→View 树，第 16 篇）；「滑动冲突怎么解决？」（`onInterceptTouchEvent` + `requestDisallowInterceptTouchEvent` + 方向判定）。

### 1.5 App 启动 —— 冷启动全链路
- **考官怎么问**：「怎么优化冷启动？」「为什么 bindApplication 占这么久？」「基线 Profile 和云 Profile 是什么？」
- **及格线**：冷启动三段——**进程创建（Zygote fork）** → **绑定应用（handleBindApplication：ART 加载、ContentProvider 安装、Application.onCreate）** → **首帧（Activity 启动 + View 绘制）**；优化手段：避免 CP 前置重活、懒加载、预加载（基线 Profile）。
- **满分线**：
  - **基线 & 云 Profile（art apex Mainline）**：`PMS`/`InstalledApp` 安装期由 `dexopt` 触发 `dex2oat`，用 `profman` 合并「**安装期基线 Profile**」（随 APK 的 `assets/dexopt` 或 Play 下发）与「**运行时云 Profile**」生成 `odex/vdex/art`（第 24 篇 dex2oat 专题）。
  - **PinnerService**：`system_server` 的 `PinnerService` 把常用 dex/art/oat 段 `mlock` 进内存，减少冷启动 page fault（与 `lmkd` 抢内存，需权衡）。
  - **ContentProvider 前置坑**再次强调（见 1.3）。
- **易错红榜**：①以为冷启动只卡在 Application.onCreate（实际 bindApplication 30%+ 在 ART/PMS）；②混淆 speed / speed-profile / verify / quicken compiler filter；③不知道基线 Profile 是 AAB/Play 下发的。
- **高频追问**：「怎么用 Perfetto 抓冷启动？」（第 7/21 篇：`android_startup` 表 + `slice` 拆 `bindApplication`/`installContentProviders`）；「启动优化和内存优化冲突吗？」（PinnerService mlock 抢内存）。

### 1.6 内存 / 卡顿 / ANR —— 性能三连
- **考官怎么问**：「怎么定位卡顿？」「ANR 有哪几类超时？」「Input ANR 的 5s 计时器在哪？」「内存压力谁先死？」
- **及格线**：卡顿用 `Choreographer` + `FrameTimeline`（`jank_type` 定责到 App/RenderThread/SF/HWC）；ANR 四类——**Input（5s，native InputDispatcher）/ Broadcast（前台 10s/后台 60s）/ Service（前台 20s/后台 200s）/ ContentProvider（10s）**；同帧时序 `CALLBACK_INPUT → CALLBACK_ANIMATION → CALLBACK_TRAVERSAL`。
- **满分线**：
  - **Input ANR 计时器在 native**：`InputDispatcher::DEFAULT_INPUT_DISPATCHING_TIMEOUT = 5s`，**不在 App Looper**；主线程卡在某个 `message` 里（同步 Binder 阻塞/死锁）导致 `finishDispatchCycleLocked` 无法停表才 ANR。`/data/anr/` 栈 + `event log` 的 `am_anr` 是定界入口（第 16/19 篇）。
  - **三路杀辨析**（第 19/34 篇）：内核 OOM（水位触发 SIGKILL）/ lmkd（PSI 内存压力，userspace 守护）/ A17 MemoryLimiter（单应用内存超标静默杀）。`onTrimMemory` 是「请求你释放」**不是**「救命」（救不了已被 LMK 盯上的）。
  - **FrameTimeline `jank_type`**：`actual_frame_timeline_slice` vs `expected_frame_timeline_slice` JOIN `upid`/`frame_number`，`present_type`(on-time/late) + `jank_type`(AppDeadlineMissed/RenderThreadDeadlineMissed/SurfaceFlingerDeadlineMissed/GpuDeadlineMissed) 精确定责（第 7/21 篇）。
- **易错红榜**：①把 Input ANR 计时器说成在 App 主线程；②三路杀混为一谈；③以为 `onTrimMemory` 能阻止被杀；④混淆四类 ANR 超时；⑤卡顿只查主线程不查 RenderThread/SF。
- **高频追问**：「主线程没做重活却 ANR 了？」（Binder 阻塞链/对端 LMK 杀/跨进程死锁，第 27 篇场景②）；「怎么证明是 SF 还是 App 掉帧？」（FrameTimeline JOIN 定责）。

### 1.7 Compose —— 重组与编译器
- **考官怎么问**：「Compose 为什么能声明式更新？」「SlotTable 是什么？」「强跳过模式改了什么？」「`$changed` 位掩码干嘛的？」
- **及格线**：Compose 用 `Composer` 记录「组合位置」(`group key = 源码位置哈希`)；`SlotTable` 用 **gap buffer** 扁平 `IntArray` 存状态；`Snapshot` 用 **MVCC「读取即订阅」**（readObserver）实现状态变更触发重组；`Recomposer` 挂在 `Choreographer.CALLBACK_ANIMATION`（非 TRAVERSAL），与 View traversal 同帧先后执行（第 15/30 篇）。
- **满分线**：
  - **`$changed` 2bit 位掩码**（第 30/36/42 篇末位缺口）：每个参数 2 bit（`real` + `static`）；父 Composable 在编译期预填子参数是否变化，运行时 `ComposerImpl.changed()` 比对，`skipToGroupEnd` 跳过未变子树；`BitsPerParameter=2`，`ChangedMask`/`StaticMask` 解码。
  - **强跳过（Strong Skipping，Kotlin 2.0.20 默认）核心澄清**：**不改 stability、`$stable` 位域不变、仅改跳过策略为 `===`（引用相等）**；引用相等陷阱仍要 `@Stable`（第 30 篇红榜）。
  - **Compose 与 Framework 接缝**：`AndroidComposeView` 在 View 树里只是一个普通 View；`LayoutNode → RenderNode` **未绕过 HWUI**（仍走 RenderThread）；`Modifier.Node`（第 13/15 篇）。
- **易错红榜**：①以为 unstable 参数必然每帧重组（强跳过下不一定）；②以为强跳过改了位域（只改策略）；③混淆 `remember` 与 `mutableStateOf`；④带返回值的 Composable 不是重组边界；⑤说 Compose 绕过 HWUI（没绕过）。
- **高频追问**：「Compose 卡顿怎么用 Perfetto 看？」（Recomposer/Choreographer 回调 + `compose` 相关 slice）；「Compose 语义树为什么对 AI Agent 更友好？」（ANI 直接读 SemanticsNode，第 13 篇）。

### 1.8 HAL / Kernel / 驱动 —— Treble + GKI + 设备驱动
- **考官怎么问**：「Treble 解决了什么？」「HIDL 和 AIDL for HAL 区别？」「GKI 是什么？」「16KB 页面有什么影响？」
- **及格线**：Treble 用 **VINTF + Binder 三上下文** 解耦 framework 与 vendor；`/dev/hwbinder` 服务 HAL，`/dev/vndbinder` 服务 vendor 私有 HAL；GKI 2.0 用 **KMI 稳定内核 ABI**，vendor 模块可加载不重编内核；16KB 页面强制（A15/A16 起，Android 14 已兼容模式）要求 `.so`/`boot.art` 页对齐（`max-page-size=16384`）。
- **满分线**：
  - **HIDL vs AIDL for HAL**：早期 HAL 用 HIDL（`.hal` + `hidl-gen`），Android 10+ 新 HAL 统一用 **AIDL for HAL**（同一套 `aidl` 工具，支持稳定接口 `@VintfStability`，走 `/dev/vndbinder`/`/dev/hwbinder`）；`android.hardware.*` AIDL 接口是落点。
  - **GKI KMI**：`drivers/android/` + `common` 内核维护稳定导出符号集；vendor 通过 KMI 钩子（vendor hook）或模块化 driver 接入；MTK/高通差异主要在 vendor 模块与 `BoardConfig`。
  - **字符设备骨架**：`miscdevice` + `file_operations`（`read`/`write`/`ioctl`/`mmap`）+ `platform_driver`/`platform_device` 匹配；`ioctl` 传参需 `copy_from_user`/`copy_to_user`（第 26/32 篇）。
  - **外接显示 / 输入（Aluminium OS 关联）**：`DRM-KMS`（外接显示驱动模型）、`HID`（usbhid + i2c-hid）对 GKI KMI x86 与 ARM64 同等（第 40 篇）。
- **易错红榜**：①以为 GKI 让 vendor 不用适配（仍要 KMI 合规 + 设备树）；②混淆 hwbinder/vndbinder；③以为 16KB 只是「内存对齐小事」（影响 linker p_align、boot.art、DMA）；④HIDL 与 AIDL for HAL 混为一谈。
- **高频追问**：「vendor 模块怎么在不重编内核的情况下升级？」（KMI 符号稳定 + 模块化 ko）；「binder 驱动和 hwbinder 是同一份代码吗？」（`drivers/android/binder.c` 是同一驱动，按 context 区分）。

### 1.9 MTK 平台 —— 真缺口补全
- **考官怎么问**：「MTK 平台排查崩溃用什么？」「PerfService 干嘛的？」「MTK 的 thermal 怎么联动？」
- **及格线**：MTK 平台用 **AEE（Android Exception Engine）** + **mtklog**（`exp_main`/`mdlog`/`mobilelog` 三件套）抓 tombstone/kernel log；`PerfService` 用于**提频锁核**保性能（游戏场景）；thermal 走 vendor HAL 联动 `Power HAL`/`Thermal HAL`。
- **满分线**：AEE 把 native crash / JE / KE 统一收口到 `/data/aee_exp/`；mtklog 三件套分别抓 AP / modem / 移动日志；`PerfService` 的 `userReg`/`userEnable` 易泄漏（忘 `userDisable` = 一直锁频 → 发热）；vendor HAL 与 Google 标准 HAL 的差异主要在 `vendor/mediatek` 私有实现（第 26/32 篇）。
- **易错红榜**：①以为 AEE 是 Google 标准（是 MTK 私有）；②PerfService 忘了 disable 导致发热；③把 mtklog 和 logcat 混为一谈。
- **高频追问**：「MTK 机器 ANR 怎么抓全链路？」（mtklog + /data/anr + 同时抓 modem 侧）。

### 1.10 电池健康显示框架（★本篇新专题·系列从未覆盖）
- **背景**：A17 QPR2 Beta 3 修复 #535421490/#538943170/#535504630「Device Health 误报电池容量衰减」——直接暴露了**电池健康度在框架侧的显示链路**，本系列此前零覆盖。
- **全链路源码落点（Android 14）**：
  1. **Health HAL（AIDL）**：`hardware/interfaces/health/aidl/android/hardware/health/IHealth.aidl` + `HealthInfo`（`batteryCapacity`[%] / `batteryChargeCounter`[uAh] / `batteryFullCharge` / `batteryStatus`）。设备厂商（MTK/QCOM）实现 `IHealth`。
  2. **BatteryService（系统服务）**：`frameworks/base/services/core/java/com/android/server/BatteryService.java` 通过 `HealthServiceWrapper` 监听 Health HAL 回调，更新 `mHealthInfo` 并广播 `Intent.ACTION_BATTERY_CHANGED`（sticky）。
  3. **BatteryManager（公开 API）**：`frameworks/base/core/java/android/os/BatteryManager.java` 暴露 `BATTERY_PROPERTY_CAPACITY` / `BATTERY_PROPERTY_CHARGE_COUNTER` / `BATTERY_PROPERTY_CURRENT_NOW` 等，经 `IBatteryStats`/`BatteryProperty` 读取。
  4. **显示端**：Settings / Device Health（Pixel 的「Device Health」或各 OEM 电池健康页）读 `BatteryManager` 的 `batteryCapacity`（设计容量百分比）渲染「电池健康度」。
- **#535421490 误报根因溯源（面试可答方向）**：
  - 误报「容量衰减」通常来自 `batteryChargeCounter`（当前电荷计数 uAh）相对 `batteryFullCharge`（满充参考）的**比值**被错误折算**，或 Health HAL 在**低温/新电池学习期**上报的 `batteryCapacity` 未做「首充校准」就直接展示。
  - 另一类根因：`BatteryService` 在 HAL 回调**乱序/重复**时未做滑动窗口滤波，导致 UI 读到瞬时异常值。修复一般是在 Health HAL 侧加「充电计数学习门限」+ 框架侧加显示下限钳制。
- **易错红榜**：①以为电池健康度是 OS 算的（其实是 Health HAL 上报 + 厂商校准逻辑）；②混淆 `batteryCapacity`[%] 与 `batteryLevel`[%]（健康度 vs 当前电量）；③以为 `ACTION_BATTERY_CHANGED` 能静态注册（它是 sticky broadcast，动态注册即时拿到）；④忽略低温对容量读数的影响。
- **高频追问**：「电池健康度能靠应用读取吗？」（受 `BATTERY_STATS` 权限限制，普通 App 读不到原始 `batteryChargeCounter`）；「Health HAL 从 HIDL 迁到 AIDL 后有什么变化？」（与 1.8 的 AIDL for HAL 主线一致，统一 `aidl` 工具链）。

---

## 2. 当月热点溯源（真题现场）

### 2.1 A17 QPR2 Beta 3 #535249652 / #543124160：QS/通知栏显示损坏 + 意外重启
- **现象**：用户下拉通知栏 / Quick Settings 面板 → 屏幕显示损坏（撕裂、残影、错误帧）→ 设备**意外重启**。
- **定界（Framework 视角）**：下拉面板是 `SystemUI` 的 `NotificationShadeWindowView` / `QS` 容器，渲染走 `SurfaceFlinger` 合成；「显示损坏」说明某一帧的 `GraphicBuffer` 在 `HWC` present 时**状态/ fence 异常**（BufferQueue 的 `BufferState` 未到 `ACQUIRED`/`FREE` 就上屏，或 `fence` 未 signal 就读）；「意外重启」说明该异常升级为 `surfaceflinger` 或 `system_server` 崩溃 → `debuggerd` 抓 `tombstone`（`/data/tombstones`）→ watchdog 或 kernel 路径触发重启。
- **溯源落点**：`frameworks/native/services/surfaceflinger/`（`SurfaceFlinger::onMessageRefresh` → `BufferLayer::updateTexImage` / `onFrameAvailable`）、`libui`/`libgui`（`BufferQueueCore` / `BufferState` / `Fence`）、`frameworks/base/packages/SystemUI/`（`NotificationShadeWindowController` / `QS` 容器）。
- **面试答法**：不要只说「卡了」，要能画出「下拉 QS → SystemUI 请求新 surface → BufferQueue 出帧 → SF/HWC present → fence 异常 → tombstone → 重启」的链路，并指出调试入口（`logcat -b system` 看 SF/WTF + `/data/tombstones`）。这正好呼应第 39 篇场景①，现在有了**官方 issue 号坐实**。
- **关联交叉索引**：→ 第 39 篇（真题大乱斗 vol.3 场景①）、第 21 篇（Perfetto 抓 SF 掉帧）、第 14 篇（Native 稳定性/debuggerd）。

### 2.2 #535421490 电池健康误报 → 第 1.10 节新专题
- 见 1.10。这是本系列**第一个电池健康显示框架**专题，补上「Settings/Device Health 读值」这一长期盲区。

### 2.3 Aluminium OS / Googlebook（9.15 发布确认，更新第 40 篇）
- **从推测到确认**：第 40 篇（8/24）基于泄漏做了桌面融合重构的「推测 + 落地对照」。现在 9.15 纽约发布已官宣，且细节具体化：
  - **首个 mainline x86 Android 端口**：此前 Android x86 多为社区/厂商私有分支，Aluminium OS 把 x86_64 拉进 GKI KMI 一等公民——意味着 **vendor 模块 + KMI 钩子要同时过 ARM64 与 x86_64 两套 ABI 验证**（第 32 篇 GKI/KMI 专题的直接延伸）。
  - **Magic Pointer 的输入语义**：摇光标 contextual select → 生成 Gemini 提示，本质是**系统级 Pointer hover/捕获 + AI 服务调用**，复用 `InputDispatcher` 的 pointer 事件 + 一个新系统服务（推测 `GeminiiIntelligence` 类），对 WMS 输入归一化（第 16/40 篇 KeyboardShortcutGroup / TouchpadInputMapper）提出新要求。
  - **DeepMind 共建**：端侧 Gemini（Gemini Nano v3，≥12GB RAM）作为底座，呼应第 4/13 篇端侧 LLM + AISeal/pKVM 机密计算——AI 推理可下沉到 AVF pVM（第 2/12 篇）。
- **面试可考**：「Android 上桌面融合对 WMS 的冲击？」「x86 进入 GKI KMI 对驱动开发意味着什么？」「跨设备 Handoff（手机→Googlebook）的 Binder 安全边界？」（→ 第 12/13/22 篇跨 VM getCallingUid 不可信）。
- **关联交叉索引**：→ 第 40 篇（Aluminium OS WMS 源码重构）、第 22 篇（A18 桌面融合与跨设备协同）、第 16 篇（输入系统全链路）、第 32 篇（GKI/KMI）。

---

## 3. 易错红榜 TOP20（跨领域高频踩坑）

1. 主线程 `Looper.loop()` 死循环 ≠ ANR（ANR 看门狗在 system_server / native InputDispatcher 5s）。
2. `Message` 走池（`MAX_POOL_SIZE=50`），不每次 new。
3. 同步屏障 `postSyncBarrier` 必须配对 `removeSyncBarrier`，漏掉 = 主线程假死。
4. Binder `oneway` 异步但仍**排队背压**，不是「永不阻塞」。
5. Binder 一次拷贝 + `mmap` 共享，是「一次拷贝」非「零拷贝」。
6. `getCallingUid()` 在**跨 VM** 与**系统服务转发**两场景不可信。
7. Binder 线程池默认 **15 + 1**。
8. `ContentProvider.onCreate` 在 `Application.onCreate` **之前**，会拖冷启动。
9. `requestDisallowInterceptTouchEvent(true)` 对 **DOWN 无效**。
10. `getMeasuredWidth` ≠ `getWidth`（后者未 layout 前为 0）。
11. 自定义 View 不处理 `AT_MOST` 会撑满父容器。
12. `ACTION_CANCEL` 不处理 → 按下态泄漏。
13. `onTouch` 返回 true 则 `onClick` 不触发。
14. Input ANR 5s 计时器在 **native InputDispatcher**，不在 App Looper。
15. 三条杀路径（内核 OOM / lmkd PSI / A17 MemoryLimiter）不可混。
16. `onTrimMemory` 是「请求释放」不是「救命」。
17. Compose 强跳过**不改 stability / 不改位域**，只改策略为 `===`。
18. Compose `LayoutNode → RenderNode` **未绕过 HWUI**。
19. GKI 让 vendor 仍要 KMI 合规 + 设备树，不是「免适配」。
20. 电池健康度 `batteryCapacity`[%] ≠ 当前电量 `batteryLevel`[%]，且来自 Health HAL 上报非 OS 计算。

---

## 4. 三条高频追问链（考官连击模拟）

### 链 A：冷启动 ANR × Binder × AMS（最经典连击）
- Q1：冷启动慢怎么定界？→ Perfetto `android_startup` + `slice` 拆 `bindApplication`/`installContentProviders`（第 7/21 篇）。
- Q2：为什么 `bindApplication` 占 30%+？→ ART 加载 + PMS `dexopt`/odex 校验 + CP 前置（第 24/19 篇）。
- Q3：启动后偶发 ANR 但主线程没重活？→ Binder 阻塞链 / 对端 LMK 杀 / 跨进程死锁（第 27 篇场景②）。
- Q4：被杀的是谁先死？→ 三条杀路径辨析（第 19/34 篇）。

### 链 B：一次点击 × Input × WMS × View × Choreographer（端到端必考）
- Q1：点击到 `onClick` 经过哪些层？→ InputReader→InputDispatcher→InputChannel(socketpair)→InputEventReceiver→ViewRootImpl InputStage→DecorView→View 树（第 16 篇）。
- Q2：滑动冲突怎么解？→ `onInterceptTouchEvent` + `requestDisallowInterceptTouchEvent`（注意 DOWN 无效）+ 方向判定（第 26 篇）。
- Q3：为什么有时掉帧但不卡输入？→ `CALLBACK_INPUT → ANIMATION → TRAVERSAL` 同帧时序，输入优先（第 15/20 篇）。
- Q4：怎么用 FrameTimeline 证明是 App 还是 SF 掉帧？→ `actual_frame_timeline_slice` JOIN `expected`，`jank_type` 定责（第 7/21 篇）。

### 链 C：跨设备 AI × CDM × RPC Binder（前沿压轴）
- Q1：手机→Googlebook Handoff 怎么保证安全？→ CDM 持久 Association + 系统级角色（第 22 篇）。
- Q2：跨 VM 调 Binder 拿到的 uid 可信吗？→ 不可信，pVM/TA 内 uid ≠ 普通 App uid（第 12/13 篇）。
- Q3：端侧推理进 pKVM 的边界？→ AISeal / Protected VM，采集链路在 host 不受保护（第 2/4 篇）。
- Q4：Aluminium OS 把 Android 搬上 x86 对驱动意味着什么？→ GKI KMI 双架构验证（第 32/40 篇）。

---

## 5. AOSP 14 源码路径清单（本篇引用）

| 子系统 | 关键路径（Android 14） |
|---|---|
| Handler/Looper native | `system/core/libutils/Looper.cpp`（`mWakeEventFd`/`epoll_wait`）；`frameworks/base/core/jni/android_os_MessageQueue.cpp` |
| MessageQueue | `frameworks/base/core/java/android/os/MessageQueue.java`；`Looper.java`/`Handler.java` |
| Binder 驱动 | `drivers/android/binder.c`（`binder_transaction`/`BR_SPAWN_LOOPER`） |
| Binder native | `frameworks/native/libs/binder/IPCThreadState.cpp`/`Binder.cpp`/`ProcessState.cpp`；`frameworks/base/core/java/android/os/Binder.java` |
| AMS/ATMS | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java`；`com/android/server/wm/ActivityTaskManagerService.java`；`ActivityStarter.java` |
| App 启动 | `frameworks/base/core/java/android/app/ActivityThread.java`（`handleBindApplication`/`performLaunchActivity`/`handleResumeActivity`）；`android/app/ZygoteInit.java` |
| WMS/View | `frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java`；`frameworks/base/core/java/android/view/ViewRootImpl.java`；`View.java`(`measure`/`layout`/`draw`) |
| 卡顿/ANR | `frameworks/base/core/java/android/view/Choreographer.java`；`com/android/server/am/AppErrors.java`；`ProcessRecord.java` |
| Input ANR | `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp`（`DEFAULT_INPUT_DISPATCHING_TIMEOUT`） |
| Compose | `androidx/compose/runtime/Composer.kt`/`ComposerImpl.kt`(`changed`/`BitsPerParameter`)/`SlotTable.kt`/`Snapshot.kt`/`Recomposer.kt` |
| HAL 三上下文 | `frameworks/native/libs/binder/`（`/dev/binder`/`hwbinder`/`vndbinder`）；`hardware/interfaces/`；`hardware/libhardware/` |
| GKI/KMI | `drivers/android/`；`common` 内核 KMI 导出；`kernel/power`/`kernel/sched`(EAS/uclamp) |
| 电池健康 | `hardware/interfaces/health/aidl/android/hardware/health/IHealth.aidl`；`frameworks/base/services/core/java/com/android/server/BatteryService.java`；`frameworks/base/core/java/android/os/BatteryManager.java` |
| Aluminium OS 关联 | `frameworks/base/services/core/java/com/android/server/display/DisplayManagerService.java`；`frameworks/base/services/core/java/com/android/server/wm/`（DesktopTasksController/TaskbarController）；`frameworks/native/services/inputflinger/`（TouchpadInputMapper） |

---

## 6. 42 → 43 篇交叉索引

- **本篇「考官评分视角」** 整合：第 26 篇（核心基础八股深挖）、第 36 篇（热点高频八股深度溯源）、第 41 篇（秋招系统联动追问轮训）、第 42 篇（月末全领域压轴轮训）—— 把单点八股按「打分标准」重新组织，不新增单点。
- **本篇「电池健康显示框架」（1.10 / 2.2）** 是系列**首个**电池健康专题，补第 32 篇（HAL/内核）未覆盖的 Health HAL → 显示链路，并回填 A17 QPR2 Beta3 #535421490 现场。
- **本篇 2.1（#535249652/#543124160）** 回填第 39 篇场景①（QS 显示损坏 + 意外重启）的官方 issue 号。
- **本篇 2.3（Aluminium OS/Googlebook 9.15）** 更新第 40 篇推测内容，落地「x86 mainline Android 端口 + Magic Pointer + DeepMind」。
- **未覆盖角度仅剩（可选增量）**：Compose 编译器插件 `ComposableFunctionBodyTransformer` IR 内部逐行（第 30/36/42 篇已到生成代码级 + `$changed` 解码，可下钻 IR 改写实现）；Aluminium OS 落地后对照 A14 真实 diff 复盘（待 A18 源码/AOSP 二次开源）。

---

## 7. 延伸阅读

- AOSP 源码（android-14.0.0_rXX）：`frameworks/base`、`frameworks/native`、`hardware/interfaces`、`drivers/android`、`kernel/common`。
- Android 17 QPR2 release notes（developer.android.com/about/versions/17/qpr2/release-notes）—— 官方 issue 号溯源。
- Google I/O 2026：「新项目优先协程、遗留维护 Handler」官方口径。
- OnJob 4-round Android interview structure；mzlw.cn 2026 中厂 Framework 高频区；KemalCodes 2026 modern Android stack 对比表。
- Aluminium OS / Googlebook：2026-09-15 纽约发布会前瞻（Android Authority / 9to5Google / HandWiki）。
- 调试入口：`logcat -b system`（SF/WTF）、`/data/tombstones`（native crash）、`/data/anr/`（ANR）、`dumpsys batterystats`（电池归因）、Perfetto（`android_startup` / `actual_frame_timeline_slice` / `binder_transaction`）。

---

> 系列状态：43 篇 / 约 270 专题完整闭环（含本篇「考官评分视角 + 电池健康显示框架新专题 + Aluminium OS 9.15 具体化 + A17 QPR2 Beta3 新 bug 官方溯源」）。后续可选增量仅剩：①Compose 编译器 IR 内部逐行；②Aluminium OS 落地对照 A14 真实 diff（待 A18 源码开放）。秋招（9–11 月）建议按本篇「考官评分视角」逐域自测，并持续跟踪 9 月 QPR1 stable 落地。
