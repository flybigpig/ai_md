# AOSP 核心代码分析 · AMS / WMS 系统服务

> 关联文档：[aosp-repo-analysis.md](./aosp-repo-analysis.md)（仓库结构与树状图）、[aosp-harness-blog-summary.md](./aosp-harness-blog-summary.md)（整机源码 Harness 工程）
>
> 分析方法：对 cnb.cool 上的 AOSP `android-14.0.0_r1` monorepo 做 **git partial clone（`--filter=tree:0 --depth=1`，不下载任何文件内容）**，再用 `git ls-tree` / `git show` 按需拉取真实源码片段。本文所有文件名、方法签名、代码片段均来自仓库真实 HEAD，非凭记忆杜撰。

---

## 1. 概述

**AMS（ActivityManagerService）** 与 **WMS（WindowManagerService）** 是 Android 框架层两大系统服务，分别掌管「应用进程与组件生命周期」和「窗口与画面合成」。它们都运行在 `system_server` 进程里，通过 Binder 向 App 进程提供能力，彼此也用 Binder/IPC 互调。

| 项 | AMS | WMS |
|---|---|---|
| 源码包 | `frameworks/base/services/core/java/com/android/server/am/` | `frameworks/base/services/core/java/com/android/server/wm/` |
| 核心类 | `ActivityManagerService.java` | `WindowManagerService.java` |
| 文件数（真实统计） | **122** | **219** |
| 顶层调度 | `ActivityTaskManagerService`（AMS 内委托给 wm 包） | `WindowManagerService` |
| 启动入口 | `SystemServer.java` 中 `startBootstrapServices()` / `startOtherServices()` | 同左 |

> 注意一个架构细节：AMS 早已把「Activity 任务栈管理」拆到了 **wm 包**（`ActivityTaskManagerService` / `ActivityTaskSupervisor` / `RootWindowContainer` / `Task` / `ActivityRecord`），AMS 本身只保留进程、Service、Broadcast、Provider、OOM、错误等职责。所以「四大组件里的 Activity」实际代码在 wm 包，本文一并纳入分析。

---

## 2. AMS（am/ 包）核心代码功能区块

基于 122 个真实文件归类：

| # | 功能区块 | 代表文件 | 职责 |
|---|---|---|---|
| 1 | **进程与进程记录** | `ProcessRecord` / `ProcessList` / `ProcessStateRecord` / `ProcessProfileRecord` / `ProcessServiceRecord` / `ProcessProviderRecord` / `ProcessReceiverRecord` / `ProcessCachedOptimizerRecord` / `ProcessErrorStateRecord` / `UidRecord` / `ActiveUids` | 进程对象的完整状态切片（把 `ProcessRecord` 按维度拆成多个 `*Record` 避免锁竞争） |
| 2 | **Activity 调度（委托层）** | `ActivityManagerService`（attachApplication / 生命周期回调） | AMS 侧对 Activity 的入口与进程绑定，真正的栈逻辑在 wm 包 |
| 3 | **Service 管理** | `ActiveServices` / `ServiceRecord` / `ConnectionRecord` / `IntentBindRecord` / `AppBindRecord` | `bindService` / `startService` / 绑定关系图 |
| 4 | **Broadcast 管理（最庞大）** | `BroadcastQueue` / `BroadcastQueueImpl` / `BroadcastQueueModernImpl` / `BroadcastDispatcher` / `BroadcastRecord` / `BroadcastFilter` / `BroadcastProcessQueue` / `BroadcastSkipPolicy` / `BroadcastHistory` | 广播排队、按进程派发、现代队列改造（`BroadcastQueue.md` 有设计说明） |
| 5 | **ContentProvider 管理** | `ContentProviderHelper` / `ContentProviderRecord` / `ContentProviderConnection` / `ProviderMap` | Provider 发布、跨进程获取、`provider` 映射表 |
| 6 | **应用错误 / ANR / 崩溃** | `AppErrors` / `AnrHelper` / `NativeCrashListener` / `AppErrorDialog` / `AppNotRespondingDialog` / `ProcessErrorStateRecord` / `StackTracesDumpHelper` | ANR 检测、native crash 监听、错误弹窗、trace dump |
| 7 | **前台服务（FGS）** | `FgsTempAllowList` / `ForegroundServiceDelegation` / `ForegroundServiceTypeLoggerModule` / `AppFGSTracker` / `AppMediaSessionTracker` | 前台服务类型约束、临时白名单、媒体会话追踪 |
| 8 | **OOM / 内存 / LMK** | `OomAdjuster` / `CacheOomRanker` / `CachedAppOptimizer` / `LowMemDetector` / `LmkdConnection` / `PhantomProcessList` / `PhantomProcessRecord` | oom_adj 计算、缓存回收、低内存侦测、与 `lmkd` 通信、幽灵进程管控 |
| 9 | **App 状态追踪器** | `BaseAppStateTracker` 及 `AppBatteryTracker` / `AppPermissionTracker` / `AppTimeTracker` / `AppBroadcastEventsTracker` / `AppRestrictionController` 等一族 `BaseAppState*` | 可插拔的 App 行为监控框架（电量/权限/时长/广播事件） |
| 10 | **电池 / 性能统计** | `BatteryStatsService` / `HealthStatsBatteryStatsWriter` / `AppProfiler` / `ProcessStatsService` | BatteryStats 写入、性能采样、进程统计 |
| 11 | **多用户** | `UserController` / `UserState` / `UserSwitchingDialog` | 用户切换、多用户状态机 |
| 12 | **PendingIntent / Intent 管控** | `PendingIntentController` / `PendingIntentRecord` / `PendingStartActivityUids` / `PendingTempAllowlists` | PendingIntent 注册表、启动白名单 |
| 13 | **Instrumentation / 测试** | `ActiveInstrumentation` / `InstrumentationReporter` / `AssistDataRequester` | 测试桩、assist 数据请求 |
| 14 | **兼容性与配置** | `PlatformCompatCache` / `CoreSettingsObserver` / `SettingsToPropertiesMapper` / `ComponentAliasResolver` / `ActivityManagerConstants` | 平台兼容标志缓存、系统设置→属性映射、组件别名解析 |
| 15 | **Debug / 诊断** | `ActivityManagerShellCommand` / `ActivityManagerDebugConfig` / `ActivityManagerUtils` / `DropboxRateLimiter` / `BugReportHandlerUtil` | `am` 命令、debug 开关、dropbox 限流 |
| 16 | **冻结 / 待机优化** | `CachedAppOptimizer` / `ProcessCachedOptimizerRecord` / `AppFreezer`（相关） | 缓存 App 的冻结与资源回收 |

---

## 3. WMS（wm/ 包）核心代码功能区块

基于 219 个真实文件归类：

| # | 功能区块 | 代表文件 | 职责 |
|---|---|---|---|
| 1 | **窗口容器层级（骨架）** | `WindowContainer` / `WindowToken` / `WindowState` / `WindowStateAnimator` / `WindowFrames` / `RootWindowContainer` / `DisplayContent` / `DisplayArea` / `TaskDisplayArea` / `Task` / `TaskFragment` / `ActivityRecord` | 所有「窗口/任务/显示」的公共父类与树形组织 |
| 2 | **Activity 任务栈管理** | `ActivityTaskManagerService` / `ActivityTaskSupervisor` / `ActivityStarter` / `ActivityStartController` / `ActivityStartInterceptor` / `RootWindowContainer` / `RecentTasks` / `RunningTasks` / `EnsureActivitiesVisibleHelper` | Activity 启动、栈/任务复用、可见性推进 |
| 3 | **窗口策略与布局** | `DisplayPolicy` / `DisplayFrames` / `DisplayRotation`（及一堆变体） / `DisplayWindowSettings` / `InsetsStateController` / `InsetsSourceProvider` / `InsetsPolicy` | 状态栏/导航栏/挖孔/旋转/Insets 计算 |
| 4 | **输入与焦点** | `InputManagerCallback` / `InputMonitor` / `InputConsumerImpl` / `DragDropController` / `DragState` / `PointerEventDispatcher` / `EmbeddedWindowController` | 输入事件分发、拖拽、焦点窗口 |
| 5 | **动画与转场** | `SurfaceAnimator` / `SurfaceAnimationRunner` / `WindowAnimator` / `WindowSurfacePlacer` / `AppTransition` / `AppTransitionController` / `RemoteAnimationController` / `ScreenRotationAnimation` / `Transition` / `TransitionController` / `BLASTSyncEngine` | Surface 动画、App 转场、旋转动画、BLAST 同步 |
| 6 | **壁纸** | `WallpaperController` / `WallpaperWindowToken` / `WallpaperVisibilityListeners` / `WallpaperAnimationAdapter` | 壁纸窗口生命周期与动画 |
| 7 | **截图 / 快照 / 启动画面** | `AbsAppSnapshotController` / `TaskSnapshotController` / `TaskSnapshotPersister` / `SnapshotController` / `AppSnapshotLoader` / `StartingSurfaceController` / `SplashScreenStartingData` / `SplashScreenExceptionList` | 任务快照、冷启动占位、SplashScreen |
| 8 | **显示管理** | `DisplayContent` / `DisplayAreaOrganizerController` / `DisplayWindowSettings` / `DisplayHashController` / `ContentRecordingController` / `RefreshRatePolicy` / `HighRefreshRateDenylist` | 多显示、刷新率、内容录制、显示哈希 |
| 9 | **锁屏 / 系统 UI** | `KeyguardController` / `KeyguardDisableHandler` / `ImmersiveModeConfirmation` / `SystemGesturesPointerEventListener` | 锁屏可见性、沉浸模式 |
| 10 | **Letterbox / 应用兼容** | `Letterbox` / `LetterboxConfiguration`(+DeviceConfig/Persister) / `LetterboxUiController` / `CompatModePackages` / `DisplayRotationImmersiveAppCompatPolicy` / `DesktopModeLaunchParamsModifier` | 非适配应用的黑边/兼容策略 |
| 11 | **Task/Window Organizer** | `WindowOrganizerController` / `TaskOrganizerController` / `TaskFragmentOrganizerController` / `DisplayAreaOrganizerController` / `LaunchParamsController`(Persister/Util) | Shell/ launcher 通过 Organizer 协议操控窗口 |
| 12 | **后台启动控制** | `BackgroundActivityStartController` / `BackgroundActivityStartCallback` / `BackgroundLaunchProcessController` | 限制后台擅自弹 Activity |
| 13 | **锁定任务 / 多窗口** | `LockTaskController` / `PinnedTaskController` / `TaskPositioner` / `TaskPositioningController` | 锁定任务（kiosk）、画中画拖拽 |
| 14 | **无障碍** | `AccessibilityController` / `AccessibilityWindowsPopulator` | 无障碍窗口枚举 |
| 15 | **持久化 / 回收** | `PersisterQueue` / `TaskPersister` / `PackageConfigPersister` | 任务/配置异步落盘 |
| 16 | **App 生命周期客户端回调** | `ActivityClientController` / `ClientLifecycleManager` / `WindowProcessController` / `WindowProcessControllerMap` / `VisibleActivityProcessTracker` | 与服务端进程状态双向同步 |
| 17 | **旋转控制** | `AsyncRotationController` / `RotationWatcherController` / `DisplayRotationReversionController` / `DisplayRotationCoordinator` | 无缝旋转、旋转监听 |
| 18 | **回溯导航** | `BackNavigationController` / `RecentsAnimation` / `RecentsAnimationController` | 系统返回手势、概览动画 |
| 19 | **Debug / Shell / 水印** | `WindowManagerShellCommand` / `WindowTracing` / `WindowTraceLogLevel` / `ViewServer` / `Watermark` / `StrictModeFlash` / `BlackFrame` | `wm` 命令、窗轨迹、调试叠层 |

---

## 4. 按需深入分析（三大核心区块 + 真实代码）

### 4.1 窗口容器树模型 —— WMS 的骨架

WMS 把「屏幕→显示→区域→任务→Activity→窗口」统一建模成一棵 **`WindowContainer` 树**。根抽象类只有两个核心字段：

```java
// frameworks/base/services/core/java/com/android/server/wm/WindowContainer.java:139
class WindowContainer<E extends WindowContainer> extends ConfigurationContainer<E>
        implements Comparable<WindowContainer>, Animatable, SurfaceFreezer.Freezable,
        InsetsControlTarget {

    /** The parent of this window container. */
    private WindowContainer<WindowContainer> mParent = null;          // :153

    // List of children for this window container. List is in z-order as the children
    // appear on screen with the top-most window container at the tail of the list.
    protected final WindowList<E> mChildren = new WindowList<E>();     // :179
    ...
}
```

**要点**：
- `mParent` + `mChildren(WindowList)` 构成树；`WindowList` 按 **z-order** 排序（尾部在最上层）。
- 整棵树的典型层级：`RootWindowContainer` → `DisplayContent` → `DisplayArea`/`TaskDisplayArea` → `Task`/`TaskFragment` → `ActivityRecord` → `WindowState`。
- 因为 `WindowContainer extends ConfigurationContainer`，配置（Configuration）、方向（orientation）、Insets 都会沿树自上而下合并/覆盖——这就是多窗口、旋转、黑边策略能统一生效的原因。
- 切换 parent 必须用 `setParent()`（而非直接赋值），它会触发 `onParentChanged()` 做配置重算。

### 4.2 Activity 启动全链路 —— AMS ↔ WMS 协同

一次 `startActivity()` 的调用路径（方法签名均来自真实源码）：

**① AMS 入口：把 Activity 启动直接委托给 ATMS（wm 包）**

```java
// frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java:3228
public final int startActivityAsUserWithFeature(IApplicationThread caller,
        String callingPackage, String callingFeatureId, Intent intent, String resolvedType,
        IBinder resultTo, String resultWho, int requestCode, int startFlags,
        ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
    return mActivityTaskManager.startActivityAsUser(caller, callingPackage,
            callingFeatureId, intent, resolvedType, resultTo, resultWho, requestCode,
            startFlags, profilerInfo, bOptions, userId);   // ← 交给 wm 包的 ATMS
}
```

**② ActivityStarter：计算 flags、复用 Task、决定去哪**

```java
// frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java:1628
int startActivityInner(final ActivityRecord r, ActivityRecord sourceRecord,
        IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
        int startFlags, ActivityOptions options, Task inTask,
        TaskFragment inTaskFragment, @BalCode int balCode,
        NeededUriGrants intentGrants, int realCallingUid) {
    setInitialState(r, options, inTask, inTaskFragment, startFlags, sourceRecord,
            voiceSession, voiceInteractor, balCode, realCallingUid);
    computeLaunchingTaskFlags();           // 算 launchMode / flags
    mIntent.setFlags(mLaunchFlags);
    ...
    final Task reusedTask = getReusableTask();   // 是否复用已有 Task
    ...
}
```

**③ ActivityTaskSupervisor：真正让 App 进程启动/恢复 Activity**

```java
// frameworks/base/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java:784
boolean realStartActivityLocked(ActivityRecord r, WindowProcessController proc,
        boolean andResume, boolean checkConfig) throws RemoteException {
    if (!mRootWindowContainer.allPausedActivitiesComplete()) {
        return false;   // 还有 Activity 在 pausing，先等等
    }
    final Task task = r.getTask();
    final Task rootTask = task.getRootTask();
    ...
    r.setProcess(proc);                       // 绑定进程
    ...
    // 后续通过 client (ApplicationThread) 跨进程通知 App 真正创建 Activity
}
```

**调用时序（ASCII）**

```
App 进程                 AMS (am/)                  WMS/wm 包
   │                       │                            │
   │ startActivity()       │                            │
   ├──────────────────────►│                            │
   │                       │ startActivityAsUser()      │
   │                       ├───────────────────────────►│  ATMS
   │                       │                            │ ActivityStarter
   │                       │                            │   .startActivityInner()
   │                       │                            │   (算 flags / 复用 Task)
   │                       │                            │ ActivityTaskSupervisor
   │                       │                            │   .realStartActivityLocked()
   │                       │                            │   (绑定进程 / 校验 pause)
   │◄────── scheduleTransaction (LaunchActivityItem) ───┤  Binder 回 App
   │ 创建 Activity 实例     │                            │
```

### 4.3 进程孵化 —— 从 AMS 到 Zygote fork

当目标 App 进程尚不存在时，AMS 一侧的 `ProcessList` 负责构造参数并请求 Zygote fork：

```java
// frameworks/base/services/core/java/com/android/server/am/ProcessList.java:2287
private Process.ProcessStartResult startProcess(HostingRecord hostingRecord, String entryPoint,
        ProcessRecord app, int uid, int[] gids, int runtimeFlags, int zygotePolicyFlags,
        int mountExternal, String seInfo, String requiredAbi, String instructionSet,
        String invokeWith, long startTime) {
    try {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "Start proc: " + app.processName);
        final boolean isTopApp = hostingRecord.isTopApp();
        if (isTopApp) {
            app.mState.setHasForegroundActivities(true);   // 顶层 App 调度组提示
        }
        ...
        // 构造 appDataInfoMap / bindMount / abi / seInfo 等参数
        // → 最终通过 ZygoteProcess 向 zygote socket 发送 fork 请求
    }
}
```

**要点**：
- `startProcess` 只是 AMS 侧封装；真正的 fork 走 `ZygoteProcess` → zygote socket → `fork()` + `exec()`，新进程从 `ActivityThread.main()` 起步。
- 参数里 `requiredAbi` / `instructionSet` / `seInfo` / `runtimeFlags` 决定了进程的 ABI、SELinux 上下文、ART 运行时标志。
- 配合 4.1 的容器树：进程起来后，`ActivityTaskSupervisor.realStartActivityLocked` 才会把 Activity `setProcess` 挂到对应 `WindowProcessController`。

---

## 5. 一句话总结

> **AMS 管「谁能活、活成什么样」（进程/OOM/组件/错误），WMS 管「活的东西怎么出现在屏幕上」（窗口树/布局/动画/输入）**；两者通过 `ActivityTaskManager` 这一个桥梁把「启动 Activity」串成一条从 App→AMS→wm 包→Zygote→回 App 的闭环。

---

## 6. 与你的关联 & 下一步

- 你是 Android/Kotlin 开发者（erp-pda），日常 `startActivity` / `bindService` / 前台服务 / 多窗口兼容，底层全在这两套服务里。读懂 `ActivityStarter` 和 `WindowContainer` 树，能直接解释「为什么启动慢」「为什么黑边」「为什么后台弹不出 Activity」。
- 前文 [aosp-harness-blog-summary.md](./aosp-harness-blog-summary.md) 提到的「让 agent 在 AOSP 树上干活」，正是要导航到 `am/` `wm/` 这些包——本文的区块清单可直接用作 harness 的 path-scoped 索引。

**可继续深入的方向（按需指定）**：
1. `BroadcastQueueModernImpl` —— Android 14 广播派发新队列的改造细节
2. `OomAdjuster` —— oom_adj 计算过程与 `lmkd` 交互
3. `SurfaceAnimator` / `BLASTSyncEngine` —— 转场动画与 Surface 同步机制
4. `Letterbox*` —— 非适配应用在大屏/折叠屏上的兼容策略
5. `RootWindowContainer` / `DisplayContent` —— 多显示与容器树遍历细节
