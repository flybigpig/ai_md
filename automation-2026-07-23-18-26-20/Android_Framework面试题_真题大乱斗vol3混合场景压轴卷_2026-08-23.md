# Android Framework 面试题 · 真题大乱斗 vol.3 混合场景压轴卷（2026-08-23）

> 系列第 **39 篇** / 累计约 **249 专题**（前 38 篇 241 专题 + 本篇 8 新场景）。
> 本篇角度：把前 38 篇分散单点焊成 **「多子系统叠加」压轴真题**，并把 8/23 当日两大热点（A17 QPR2 Beta 3 稳定性 bug、A18 Aluminium OS 桌面融合）反向翻译成面试题。
> 每个场景按五段式展开：**现象 -> 定界 -> 原理 + AOSP 路径 -> 易错红榜 -> 追问链 / 延伸阅读**。
> Baseline：Android 14（UpsideDownCake, android-14.0.0_rXX）/ Kernel 6.1 GKI。

---

## 0. 当日热点锚定（2026-08-23）

| 热点 | 关键事实 | 反推成的面试题落点 |
|------|----------|--------------------|
| **A17 QPR2 Beta 3**（build CP41.260731.005，2026-08-14） | 新增 App Lock、QS 布局编辑器、扩展模糊、防呼叫转移诈骗、折叠屏多任务 handle；**修复两类稳定性 bug：拉下通知栏/QS 导致显示损坏 + 意外重启；Device Health 误报电池衰减** | 显示损坏 + 意外重启 -> SF/HWUI/WMS display 竞态 + native crash 定界（场景 1）；误报电池 -> batterystats / battery HAL 归因（场景 1 延伸） |
| **A18 Aluminium OS**（MWC 2026 确认 2026 年末首发） | ChromeOS 技术栈重建于 Android 内核；AVF pVM 跑 Linux 应用；Pixel 笔记本；WindowManager 1.5 新增 Large/Extra-Large 尺寸类；Navigation 3；KeyboardShortcutGroup API；Desktop 模拟器 profile | 桌面融合对 WMS/display/CDM/AVF 的重构（场景 2、6） |
| **Android 17 QPR1**（Beta 8）预计 9 月 Pixel Drop；QPR2 stable 预计 2026-12 | 无新增 Framework 破坏性变更 -> 经典八股 + 跨子系统综合题仍为最高频 | 本卷场景均可用「经典八股 × 叠加」思路拆解 |

> 联网佐证：Android Authority / Heise / IBTimes AU 报道 QPR2 Beta 3 修复「notification shade & QS 视觉损坏 + 设备意外重启」「Device Health 误报电池容量衰减」；Android Authority / Androidsis 报道 Aluminium OS 路线图（ChromeOS 重建于 Android 内核 + AVF pVM + Pixel 笔记本）。

---

## 1. 使用说明：为什么是「混合场景」而不是「单点八股」

考官的真实提问早已不是「说说 Handler 原理」，而是 **「冷启动里 ANR 了，栈显示主线程在 Binder 同步调用上，你怎么定界？」** —— 这要求你：

1. 能从**现象**反推**涉及哪些子系统**（App / WMS / SF / Input / AMS / kernel）；
2. 知道**该抓哪类 trace**（logcat / tombstone / Perfetto / bugreport）；
3. 能用 **AOSP 路径 + 方法名** 佐证根因；
4. 能答出**易错点**和**考官高频追问**。

本卷 8 个场景刻意做成「前两卷没组合过」的新叠加，并优先纳入 8/23 当日热点。

---

## 2. 真题大乱斗 vol.3 · 八大混合场景

### 场景 1 · QPR2 Beta 3「拉下通知栏/QS 导致显示损坏 + 设备意外重启」真实现场
**现象**：用户拉下通知栏或编辑 QS 时，屏幕出现花屏/撕裂，少数机型直接重启。Google 官方将其归为稳定性 bug 并修复。

**定界**：
- 显示损坏 + 重启，第一怀疑对象不是 App，而是 **SurfaceFlinger / HWUI / display 驱动** 在配置变更（状态栏展开触发 window 尺寸/层级变化）时的竞态。
- 关键信号：`logcat` 搜 `DEBUG` / `tombstoned` / `FATAL EXCEPTION`；`/data/tombstones/` 是否有新 tombstone；`dmesg` 看 display/GPU 驱动报错；`bugreport` 看 `system_server` 与 `surfaceflinger` 是否 crash。

**原理 + AOSP 路径**：
- **SurfaceFlinger** 合成在主线程 `SurfaceFlinger::onMessageInvalidate()`（搜集无效区）与 `onMessageRefresh()`（真正合成并提交 HWC）两阶段，位于 `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp`；状态栏展开会触发大量 Layer 增删 + 尺寸变化，若某 Layer 的 `BufferStateLayer` / `BufferQueue` 在 `acquire` 与 `onFrameAvailable` 间出现时序竞态，会出现「撕裂/花屏」。
- **HWUI** 的 `RenderThread`（`frameworks/base/libs/hwui/renderthread/`）在 SystemUI 这类 UI 进程内异步渲染，若 `CanvasContext::prepareTree()` 与 `View` 树 `requestLayout` 因状态栏 insets 变化出现「先提交旧树、后测新树」，也可能产生错帧。
- **意外重启**的根因通常是 `surfaceflinger` 进程 native crash 后被 `init` 重启；若短时间内连续 crash，`init` 会触发整机 reboot（看门狗逻辑）。这正是 QPR2 Beta 3 修复的「visual corruption + unexpected device restart」—— 属于 **SF/HWUI 在 window 配置变更下的合成竞态**，不是 App 问题。
- **Perfetto 定界**：用 `android_frame_timeline` / `actual_frame_timeline_slice` 看 `jank_type` 是否落在 `SurfaceFlinger`/`HWC`；用 `gpu_counter` 看 GPU 是否掉速（呼应 8/11 第 25 篇）。

**易错红榜**：
- 误把「显示损坏」当成 App 卡顿（实际根因在合成侧，不是主线程 Looper 卡）。
- 误以为「重启 = kernel panic」（很多是 sf native crash 触发 init 重启，tombstone 在 `/data/tombstones/`）。
- 忽视 **HWC（HWComposer / drm_hwcomposer）** 在 overlay 合成时的尺寸/格式校验失败。

**追问链**：
- Q：你怎么证明是 SF 不是 display 驱动？A：看 tombstone 栈落在 `surfaceflinger` 还是 `drm` 驱动；overlay 失败通常 `HWC2::Error` 在 logcat。
- Q：为什么状态栏展开才触发？A：配置变更（insets/rotation/window 尺寸）才触发 Layer 批量增删，暴露出 acquire/release 竞态。
- **延伸**：Device Health 误报电池衰减 -> 根因在 `frameworks/base/services/core/java/com/android/server/am/batterystats/` + `BatteryStatsImpl` 与 battery HAL 读数校准，属 batterystats 归因问题（呼应 8/11 battery 耗电细分）。

---

### 场景 2 · Aluminium OS 桌面融合：外接显示器 + freeform 窗口 + WM 1.5 新尺寸类 + AVF pVM 跑 Linux 应用
**现象（前瞻题）**：Android 接显示器进入桌面模式，App 以 freeform 浮窗运行，系统同时用 AVF pVM 跑一个 Linux 应用（如 Chromium），两者通过跨 VM Binder 通信。考官问：「这对 WMS / display / Binder 有什么新挑战？」

**定界**：这是 **WMS（多显示 + WM Shell freeform）+ AVF（pVM 隔离）+ RPC Binder（跨 VM IPC）** 的三件套叠加。

**原理 + AOSP 路径**：
- **多显示**：`frameworks/base/services/core/java/com/android/server/wm/DisplayContent.java` 管理每块物理屏的 Window 容器；桌面模式下外接显示器是独立 `DisplayContent`，App 的 `WindowState` 落在对应 display 的 `DisplayChildWindowContainer`。
- **WM Shell freeform**：`frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/` 下 `DesktopTasksController`（管理 freeform 任务）、`TaskbarController`（桌面任务栏）；所有窗口变更统一走 `WindowOrganizer` 的 `WindowContainerTransaction`（WCT），**不再用老的 ActivityManager 直接改窗口**——这是 A17/A18 桌面融合的核心约束（呼应 8/08 第 22 篇、8/21 第 37 篇折叠屏 handle）。
- **WindowManager 1.5 尺寸类**：`androidx.window.core.WindowSizeClass` 新增 `Large` / `Extra-Large` 两级，App 用 `WindowSizeClass.compute()` 适配笔记本大屏；`Navigation 3`（`androidx.navigation`）接管多 pane 导航。
- **AVF pVM 跑 Linux**：`packages/modules/Virtualization/` 下 `VirtualMachine` / `crosvm` 拉起 protected VM，Linux 应用跑在隔离 VM 内，内存由 stage-2 页表拥有（呼应 8/02 第 12 篇 pKVM）。
- **跨 VM Binder**：`frameworks/native/libs/binder/RpcServer.cpp` + `RpcSession`，用 vsock 承载 Binder 协议；**关键雷区：跨 VM `getCallingUid()` 不可信**（VM 内 UID 与 host 不互通），host 侧必须自己做身份校验（与 8/13 第 13 篇 AppFunctions provider 侧 `getCallingUid()==SYSTEM_UID` 不可信、8/12 第 12 篇跨 pVM 安全边界同源）。

**易错红榜**：
- 误以为桌面模式是「投屏」（是独立 `DisplayContent` + freeform 窗口，不是 mirror）。
- 误以为 freeform 走老 `ActivityManager` API（已统一收敛到 `WindowOrganizer`/`WCT`）。
- 忽视 **跨 VM Binder 身份不可信**，直接信 `getCallingUid()` 做鉴权会越权。

**追问链**：
- Q：freeform 窗口的最小/最大尺寸由谁约束？A：WM Shell `DesktopTasksController` + `TaskbarController` 策略 + App 侧 `WindowConfiguration.WINDOWING_MODE_FREE_FORM`。
- Q：pVM 里 Linux 应用崩溃会影响 host 吗？A：不会，stage-2 页表 + SMMU DMA 隔离（8/02 第 12 篇四短板之一）。
- **延伸**：Aluminium OS 把 ChromeOS 重建于 Android 内核，意味着 **GKI + AVF 成为笔记本底座**，kernel 驱动（显示/输入）要走 GKI KMI（呼应 8/17 第 32 篇）。

---

### 场景 3 · App Lock 安全链：AI agent 仍能读被锁 App 数据 + getCallingUid 不可信 + NMS 强制 redaction
**现象（联动 8/21 第 37 篇）**：QPR2 Beta 3 的 App Lock 锁住某 App 后，通知内容隐藏、widget 移除、打开需指纹/PIN；但 Google 明说「你授权的 AI agent 仍能访问其数据」。考官问：「App Lock 到底防住了什么、没防住什么？」

**定界**：App Lock 是 **启动拦截 + 通知脱敏**，不是 **进程沙箱隔离**；真正的安全边界要回到 Binder IPC 身份校验。

**原理 + AOSP 路径**：
- **启动拦截**：`frameworks/base/core/java/android/app/ActivityStartInterceptor.java` 在 `ActivityStarter` 调度路径上拦截目标 Activity，若目标包被锁定且未通过凭证，跳到解锁确认（`Keyguard` / `LockSettingsService`）。它只挡「启动」，不挡「已运行进程的数据」。
- **凭证**：`frameworks/base/services/core/java/com/android/server/locksettings/LockSettingsService.java` 校验 PIN/指纹；与 `LockPatternUtils` 衔接。
- **通知脱敏**：`frameworks/base/services/core/java/com/android/server/notification/NotificationManagerService.java` 经 `NotificationManagerService.mLockScreenUserManager` 对锁屏通知做 `redaction`（隐藏敏感文本）。
- **为什么 agent 仍能读**：agent 通常通过 `BIND_APP_FUNCTION_SERVICE`（AppFunctions，8/03 第 13 篇）或 Accessibility 跨进程调用目标 App 的 provider/服务；此时 **`Binder.getCallingUid()` 拿到的是 `SYSTEM_UID`（系统 binder 转发），不是用户 UID**——所以「锁」挡不住系统级 agent，因为 agent 调用经系统服务中转，身份已被「洗」成 SYSTEM_UID。这才是「App Lock 不防 agent」的底层真相。

**易错红榜**：
- 误以为 App Lock = 进程级沙箱（它只拦启动 + 脱敏通知）。
- 误以为 `getCallingUid()` 能区分「真实用户」与「agent」（跨系统服务中转后不可信，8/13 第 13 篇、8/12 第 12 篇同源考点）。
- 忽视 **redaction 是 NMS 策略层**，App 自己 `setVisibility` 也要配合。

**追问链**：
- Q：如果不想让 agent 读，怎么办？A：在 provider/服务侧做「端到端」鉴权（如要求 caller 持特定 permission + 校验 package），而非依赖 App Lock。
- Q：App Lock 和 8/21 的 App Lock 与 ActivityStartInterceptor 关系？A：同一机制，本场景强调「它不防 agent」的安全边界。
- **延伸**：这题是「系统托管 UI + 一次性凭证」隐私范式（8/03 第 13 篇）在 A17 的落地实例。

---

### 场景 4 · Jetpack ViewModel 跨进程死亡重建 + SavedState + AMS 进程模型 + 三路杀
**现象**：后台 App 被回收后用户返回，ViewModel 数据丢失引发白屏/崩溃；或低内存时 Activity 被 kill 后重建出现异常。考官问：「ViewModel 为什么能抗旋转不能抗进程死？进程死是谁杀的？」

**定界**：这是 **Jetpack（Lifecycle/ViewModel/SavedState）+ AMS 进程模型 + 三路杀** 的叠加。

**原理 + AOSP 路径**：
- **ViewModel 抗旋转**：`androidx.lifecycle.ViewModelStore` 存在 `Activity` 级（非 `Fragment`），旋转时 `ActivityThread` 走 `handleRelaunchActivity()`，**不销毁进程**，ViewModelStore 经 `onRetainNonConfigurationInstance()` 保留（呼应 8/22 第 38 篇）。
- **ViewModel 不抗进程死**：进程被杀后 `ViewModelStore` 随进程消失；唯有 `SavedStateRegistry` / `SavedStateHandle` 经 `Activity.onSaveInstanceState()` 落到 `Parcel` 持久化（且只活到进程重启，跨进程死亡仍可能丢非持久字段）。
- **谁杀的（三路杀，呼应 8/06 第 19 篇 / 8/19 第 34 篇）**：
  1. `lmkd`（`system/core/lmkd/`）基于 PSI 杀 `cached/empty` 进程；
  2. **A17 Memory Limiter** 个体超标静默杀（不抛异常）；
  3. 内核 OOM（`out_of_memory()`）最后兜底；
  此外 `ActivityManagerService` 按 `oom_adj` 分级（foreground/visible/perceptible/service/cached）。
- **AMS 进程模型**：`frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` 维护 `ProcessRecord` 与 `oom_adj`；`updateOomAdjLocked()` 算级别。

**易错红榜**：
- 误以为 ViewModel 能跨进程死保留（只有 SavedState 的 Parcel 字段能，且非全部）。
- 混淆「旋转重建」与「进程死亡重建」（前者不杀进程，后者杀）。
- 三路杀分不清：lmkd(PSI)/MemoryLimiter(个体)/内核OOM（谁先动手取决于内存压力路径）。

**追问链**：
- Q：onSaveInstanceState 存的 Parcel 会被谁清理？A：进程死即丢，且 `SavedStateHandle` 只覆盖可序列化字段。
- Q：如何区分前后台被杀？A：`ActivityManager.getImportance()` / `RunningAppProcessInfo.importance`（foreground 不被 lmkd 杀）。
- **延伸**：`onTrimMemory()` 级别（A14 起只剩 `TRIM_MEMORY_UI_HIDDEN` / `TRIM_MEMORY_COMPLETE` 等，不是救命机制，是「请自觉释放」）。

---

### 场景 5 · Compose 强跳过 + KMP/Native 并发 + CMP iOS 无障碍语义树
**现象（跨语言综合题）**：同一份 Compose 业务代码，Android 端强跳过后不重组，iOS 端（CMP）无障碍树却「整屏 opaque」读不到子控件；Kotlin/Native 并发里共享对象崩了。考官问：「这三件事底层有什么关系？」

**定界**：**Compose 编译器/运行时（强跳过 + SlotTable）+ Kotlin/Native 新内存模型 + CMP iOS 无障碍语义树** 的跨语言叠加。

**原理 + AOSP 路径 / 参考**：
- **强跳过（Strong Skipping）**：自 Kotlin 2.0.20（Compose Compiler 1.5.4 实验、K2 默认开），`ComposerImpl` 用 `===`（引用相等）而非 `equals` 判断跳过；`$changed` 位掩码解码在 `frameworks/.../Compose` 运行时（`androidx.compose.runtime.ComposerImpl.changed()` / `BitsPerParam`）。**核心澄清（8/15 第 30 篇 / 8/20 第 36 篇）**：强跳过 **不改 stability、`$stable` 位域不变**，仅改「跳过策略」；unstable 参数仍可能每帧重组（呼应第 30/36 篇两误解）。
- **Kotlin/Native 并发**：新内存模型（默认自 1.7.20+）**取消默认 freeze**，对象可在 `Worker` 间通过 **所有权转移（Transfer）** 传递，`kotlin-native` runtime 不靠 ART GC（呼应 8/19 第 35 篇）；与 ART 分代/CMC GC 完全不同。
- **CMP iOS 无障碍**：Compose 语义树 `SemanticsNode` 映射到 iOS `accessibilityIdentifier`；**「整屏 opaque」是误区**——`testTag` 可用、lazy 列表语义树异步 sync、未标 tag 的节点会塌缩（呼应 8/19 第 35 篇、8/03 第 13 篇 ANI 语义树）。

**易错红榜**：
- 误以为强跳过 = 「stable 对象才跳过」（实际策略变 `===`，但仍受 stability 约束）。
- 误以为 CMP iOS 无障碍「整屏 opaque 读不到」（testTag 可用，塌缩只发生在未标 tag 节点）。
- 混淆 **Kotlin/Native 内存模型** 与 **ART GC**（前者无分代、靠所有权转移；后者 CMC + A17 分代）。

**追问链**：
- Q：强跳过下 `remember` 还会跳过吗？A：`remember` 仍按 key + 跳过策略；强跳过只放宽参数比较。
- Q：KMP 共享对象崩的根因？A：Worker 间未正确 Transfer 所有权，跨线程访问非冻结对象。
- **延伸**：Compose 编译器 IR lowering 注入 `$composer`/`$changed`/`skipToGroupEnd`（8/15 第 30 篇逐行走读）。

---

### 场景 6 · Aluminium desktop 外接键鼠 + 折叠屏旋转 + 多指 + Pointer Capture 归一化
**现象（输入综合题）**：笔记本模式（Aluminium OS）下外接鼠标键盘，同时折叠屏旋转 + 多指触控，某个手势被「吞掉」或 Pointer Capture 失效。考官问：「Input 系统怎么把鼠标、键盘、多指、外接显示揉到一起？」

**定界**：**InputReader（多 mapper）+ InputDispatcher（多 display 分发）+ Pointer Capture** 的叠加（呼应 8/16 第 31 篇输入全链路 / 8/10 第 10 篇 Pointer Capture）。

**原理 + AOSP 路径**：
- **InputReader**：`frameworks/native/services/inputflinger/reader/` 下 `TouchInputMapper` / `TouchpadInputMapper` / `KeyboardInputMapper` 把 `/dev/input/eventN`（evdev，`EventHub` inotify+epoll 读）转成 `RawEvent` -> `NotifyArgs`，经 `QueuedInputListener` 入队。
- **InputDispatcher**：`frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp` 的 `dispatchOnceInnerLocked()` -> `findFocusedWindowTargetsLocked()` 找当前 **focused display** 的目标窗口；多显示下鼠标/触控要路由到正确 `DisplayContent`（呼应场景 2 桌面多显示）。
- **外接鼠标**：`MotionEvent` 带 `SOURCE_MOUSE`，`TouchpadInputMapper` 走 `libchrome-gestures` 做双指滚动/三指切换（8/10 第 10 篇）。
- **多指 + 旋转 + split**：`TouchState::split` 在旋转导致窗口尺寸变化时，旧的 pointer 归属可能错位（呼应 QPR2 Beta1 修复 #516836306「多指拖拽后源 App 收不到后续触摸」，8/16 第 31 篇）。
- **Pointer Capture**：`InputDispatcher::setPointerCaptureLocked()` 把某窗口设为捕获目标，绕过正常命中测试（8/10 第 10 篇 / 8/16 第 31 篇）。

**易错红榜**：
- 误以为鼠标和触摸走同一套命中测试（鼠标有 `SOURCE_MOUSE` 独立路径）。
- 误以为 Pointer Capture 跨 display 有效（捕获绑定到特定窗口/display）。
- 忽视 **旋转时的 split 时序竞态**（#516836306 真实现场）。

**追问链**：
- Q：Input ANR 计时器在哪？A：native `InputDispatcher`，`DEFAULT_INPUT_DISPATCHING_TIMEOUT=5s`，不是 App Looper（8/16 第 31 篇）。
- Q：外接键鼠延迟高怎么定界？A：Perfetto `android_input_events` 四段延迟（dispatch/handling/ack/end_to_end，8/11 第 25 篇）。
- **延伸**：`KeyboardShortcutGroup` API（Aluminium OS 笔记本快捷键，场景 2）在 Input 层如何注入。

---

### 场景 7 · 16KB 页面 + HAL AIDL + GKI KMI + vendor module + linker namespace + MTK vendor 差异
**现象（内核/驱动综合题）**：升级到 16KB 页内核后，某 vendor HAL .so 加载失败；或 GKI 升级后 MTK 平台 vendor 驱动符号找不到。考官问：「16KB、GKI KMI、linker namespace、vendor HAL 是怎么互相卡住的？」

**定界**：**16KB 页对齐 + GKI KMI 符号版本 + linker namespace 隔离 + HAL AIDL 上下文 + 厂商驱动差异** 的叠加（呼应 8/17 第 32 篇 HAL/内核 / 8/14 第 29 篇 16KB + linker）。

**原理 + AOSP 路径**：
- **16KB 页**：`max-page-size=16384` + linker `p_align` 校验；`boot.art` 页感知；未对齐的 .so 在 16KB 内核上 `mmap` 失败（8/14 第 29 篇、8/22 第 22 篇）。
- **GKI KMI**：`kernel/` GKI 2.0 冻结内核符号 ABI（KMI），vendor 模块只能调 KMI 符号；非 KMI 符号升级即断（8/17 第 32 篇）。
- **linker namespace**：`system/linkerconfig/` 生成 `ld.config.txt`，`public.libraries.txt` 决定哪些 .so 对 app 可见；A17 安全原生 DCL 加固要求 `.so` 只读（8/14 第 29 篇、8/29 篇）。
- **HAL AIDL 三上下文**：`/dev/binder`（framework）、`/hwbinder`（HAL，HIDL/AIDL-HAL）、`/vndbinder`（vendor，8/17 第 32 篇 / 8/19 第 34 篇）；vendor HAL 走 `vndbinder`。
- **MTK vendor 差异**：MTK 平台除标准 GKI 外，有自研 vendor 驱动 + `PerfService`（CPU/GPU 提频接口）+ `thermal` 定制 HAL + `AEE`/`mtklog` 崩溃采集（8/17 第 32 篇）；其 vendor .so 若未随 GKI KMI 重新编译，16KB + KMI 双约束下最易加载失败。

**易错红榜**：
- 误以为 16KB 只影响 app（boot.art + vendor .so 全要页对齐）。
- 误以为 vendor 驱动能用任意内核符号（只能 KMI 符号）。
- 混淆 `hwbinder` 与 `vndbinder`（前者 HAL、后者 vendor 内部）。

**追问链**：
- Q：怎么查 .so 是否 16KB 对齐？A：`readelf -l` 看 `p_align`，或 `llvm-readelf`。
- Q：MTK 平台 GKI 升级怎么不崩？A：vendor 模块随 KMI 重编 + 走 `vndbinder` + 自研驱动过 KMI 白名单。
- **延伸**：`debuggerd -b` 抓 vendor 进程 native 栈（8/14 第 29 篇工具链）。

---

### 场景 8 · AAOS 座舱多显示 + 整车 hibernation 电源 + 端侧 LLM 进 pKVM（AISeal）
**现象（车机综合题）**：车机进入 hibernation（整车休眠）后，仪表盘/中控多显示掉电时序错乱；同时座舱 AI 助手把端侧 LLM 推理搬进 pKVM 保护 VM。考官问：「座舱电源状态机怎么和多显示、机密计算协同？」

**定界**：**AAOS CarPowerManagementService（CPMS）电源状态机 + 多显示掉电/上电时序 + pKVM/AISeal 机密计算** 的叠加（呼应 8/18 第 33 篇 AAOS / 8/02 第 12 篇 pKVM / 8/04 第 14 篇 AISeal）。

**原理 + AOSP 路径**：
- **CPMS 状态机**：`packages/services/Car/service/src/com/android/car/power/` 下 `CarPowerManagementService`，状态 `ON` -> `SHUTDOWN_PREPARE` -> `SUSPEND` -> `HIBERNATION` -> `OFF` -> `POST_SHUTDOWN`；VHAL `AP_POWER_STATE_*` 属性驱动（8/18 第 33 篇、8/05 第 16 篇电源状态图）。
- **多显示掉电时序**：`CarOccupantZoneManager` 把 occupant->display->user 强分区；hibernation 时各 `DisplayContent` 按策略掉电，仪表盘（cluster）受 `CarClusterManager` 受控通道，时序错乱会导致唤醒后黑屏。
- **端侧 LLM 进 pKVM**：`packages/modules/Virtualization/` 拉起 protected VM（Microdroid），AISeal 把 AppSearch 个人库 + 端侧 LLM 推理搬进隔离 VM（8/04 第 14 篇）；**跨 VM `getCallingUid()` 不可信**（同场景 2/3）。
- **电源与机密计算协同**：hibernation 前必须先把 pVM 内推理状态落盘/暂停，否则唤醒后 VM 内存状态丢失；`CarPowerPolicy` 组件级供电白名单决定哪些硬件（含 NPU）在 hibernation 仍保电。

**易错红榜**：
- 误以为 hibernation = 普通 suspend（座舱有整车电源 + 多显示 + VHAL 协同，比手机复杂）。
- 忽视 **pVM 在 hibernation 的状态保存**（否则唤醒后 AI 会话断）。
- 混淆 `SHUTDOWN_PREPARE` 与 `POST_SHUTDOWN` 时序（前者通知应用保存、后者已断电）。

**追问链**：
- Q：Garage Mode 和 hibernation 关系？A：Garage Mode 是停车后后台任务窗口，hibernation 是最终整车休眠（8/05 第 16 篇）。
- Q：NPU 在 hibernation 保电谁决定？A：`CarPowerPolicy` 白名单 + VHAL 属性。
- **延伸**：端侧 LLM INT4/KV cache 量化（8/04 第 14 篇）+ LiteRT NPU delegate 算子回退（8/04 第 4 篇）。

---

## 3. 跨场景易错红榜 TOP18

1. 「显示损坏/重启」先查 SF/HWUI，不先怪 App（场景 1）。
2. surfaceflinger native crash -> init 重启 -> 连续 crash 触发整机 reboot（场景 1）。
3. 桌面模式是独立 `DisplayContent` + freeform，不是投屏（场景 2）。
4. freeform 统一走 `WindowOrganizer`/`WCT`，不走老 ActivityManager（场景 2）。
5. 跨 VM / 跨系统服务中转后 `getCallingUid()` 不可信（场景 2、3、8）。
6. App Lock 只拦启动 + 脱敏通知，不是进程沙箱（场景 3）。
7. ViewModel 抗旋转不抗进程死；只有 SavedState Parcel 字段能跨进程死（场景 4）。
8. 三路杀：lmkd(PSI) / A17 MemoryLimiter(个体) / 内核 OOM（场景 4）。
9. 强跳过不改 stability/`$stable` 位域，只改比较策略为 `===`（场景 5）。
10. CMP iOS 无障碍「整屏 opaque」是误区，塌缩只发生在未标 tag 节点（场景 5）。
11. Kotlin/Native 新内存模型不 freeze、靠 Worker 所有权转移，≠ ART GC（场景 5）。
12. 鼠标 `SOURCE_MOUSE` 与触摸命中测试路径不同（场景 6）。
13. Pointer Capture 绑定特定窗口/display，不跨显示（场景 6）。
14. Input ANR 计时器在 native InputDispatcher(5s)，不在 App Looper（场景 6）。
15. 16KB 页影响 boot.art + vendor .so，不只 app（场景 7）。
16. vendor 驱动只能用 GKI KMI 符号（场景 7）。
17. `hwbinder`(HAL) 与 `vndbinder`(vendor) 区分（场景 7）。
18. AAOS hibernation ≠ 普通 suspend，要协同多显示 + VHAL + pVM 状态（场景 8）。

---

## 4. 三条跨子系统高频追问链

**链 A · 显示/重启类（SF/HWUI/WMS/kernel）**
现象(花屏/重启) -> 抓 tombstone + dmesg + Perfetto frame_timeline -> 定界 SF 合成竞态 vs display 驱动 vs App -> 修复 Layer acquire/release 竞态 / HWC 格式校验。

**链 B · 跨 VM/跨服务身份安全（Binder/AVF/AppFunctions/App Lock）**
系统服务中转 -> `getCallingUid()==SYSTEM_UID` 不可信 -> 必须在 provider/服务侧做端到端鉴权 -> App Lock 挡不住 agent 的本质。

**链 C · 进程生死与恢复（AMS/Lifecycle/三路杀/启动）**
低内存 -> lmkd/PSI 或 MemoryLimiter 或内核 OOM 杀进程 -> ViewModel 丢（SavedState 救部分）-> 重建经 Zygote fork + AMS oom_adj 重新分级 -> onTrimMemory 只是「请释放」非救命。

---

## 5. AOSP 14 源码路径清单（本篇引用）

| 子系统 | 路径 |
|--------|------|
| SurfaceFlinger | `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp` |
| HWUI RenderThread | `frameworks/base/libs/hwui/renderthread/` |
| WMS / DisplayContent | `frameworks/base/services/core/java/com/android/server/wm/DisplayContent.java` |
| WM Shell freeform | `frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/` |
| WindowOrganizer / WCT | `frameworks/base/core/java/android/window/` |
| AVF / pVM | `packages/modules/Virtualization/` |
| RPC Binder | `frameworks/native/libs/binder/RpcServer.cpp` / `RpcSession.cpp` |
| App Lock 启动拦截 | `frameworks/base/core/java/android/app/ActivityStartInterceptor.java` |
| LockSettingsService | `frameworks/base/services/core/java/com/android/server/locksettings/LockSettingsService.java` |
| NMS 通知脱敏 | `frameworks/base/services/core/java/com/android/server/notification/NotificationManagerService.java` |
| ViewModelStore | `androidx/lifecycle/ViewModelStore.java` |
| AMS 进程模型 | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` |
| lmkd | `system/core/lmkd/` |
| InputDispatcher | `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp` |
| InputReader mapper | `frameworks/native/services/inputflinger/reader/` |
| EventHub | `frameworks/native/services/inputflinger/reader/EventHub.cpp` |
| 16KB / linker config | `system/linkerconfig/` + `ld.config.txt` + `public.libraries.txt` |
| GKI / vendor 驱动 | `kernel/` (GKI 2.0) + `drivers/` |
| AAOS CPMS | `packages/services/Car/service/src/com/android/car/power/` |
| VHAL | `hardware/interfaces/automotive/vehicle/` |
| CarOccupantZoneManager | `packages/services/Car/` |
| KeyboardShortcutGroup | `frameworks/base/core/java/android/view/KeyboardShortcutGroup.java` |
| WindowSizeClass (WM 1.5) | `androidx/window/core/WindowSizeClass.kt` |

---

## 6. 38 -> 39 篇交叉索引

| 前置篇 | 主题 | 本篇复用点 |
|--------|------|-----------|
| 第 12 篇 (8/02) | pKVM/AVF | 场景 2、8 跨 VM Binder 不可信 + pVM 隔离 |
| 第 13 篇 (8/03) | AppFunctions/Compose-First | 场景 3、5 getCallingUid 不可信 + 语义树 |
| 第 16 篇 (8/05) | AAOS 电源状态机 | 场景 8 hibernation/Garage Mode |
| 第 19 篇 (8/06) | 全链路排查 | 场景 1、4 三路杀/定界法 |
| 第 20 篇 (8/06) | code walk | 场景 1 SF 一帧 |
| 第 22 篇 (8/08) | A18 桌面融合 | 场景 2、6 桌面模式/Handoff |
| 第 25 篇 (8/11) | Perfetto SQL | 场景 1、6 input/frame/battery SQL |
| 第 29 篇 (8/14) | Native 稳定性 | 场景 1、7 tombstone/16KB/linker |
| 第 30/36 篇 (8/15/8/20) | Compose 编译器 | 场景 5 强跳过/`$changed` |
| 第 31 篇 (8/16) | 输入全链路 | 场景 6 InputDispatcher/multi-display |
| 第 32 篇 (8/17) | HAL/内核/MTK | 场景 7 GKI/HAL/MTK |
| 第 33 篇 (8/18) | AAOS 座舱 | 场景 8 CPMS/多显示 |
| 第 34 篇 (8/19) | 启动/system_server | 场景 4 AMS 进程模型 |
| 第 35 篇 (8/19) | KMP/Swift Export | 场景 5 Native 内存模型/CMP iOS |
| 第 37 篇 (8/21) | A17 QPR2 Beta 3 | 场景 1、3 App Lock/稳定性 bug |
| 第 38 篇 (8/22) | Jetpack 架构组件 | 场景 4 ViewModel/SavedState |

---

## 7. 延伸阅读

- AOSP 源码：`frameworks/native/services/surfaceflinger/`、`frameworks/native/services/inputflinger/`、`packages/services/Car/`、`packages/modules/Virtualization/`（Android 14 分支）。
- Android Authority / Heise / IBTimes AU：A17 QPR2 Beta 3 发布报道（2026-08-14）。
- Android Authority / Androidsis：Aluminium OS 路线图（ChromeOS 重建于 Android 内核 + AVF pVM + Pixel 笔记本）。
- 本系列前 38 篇（工作区根目录 `Android_Framework面试题_*.md`）作为单点深挖底稿，本卷为「混合场景压轴」形态。

> 全系列至此 39 篇 / 约 249 专题：主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧 AI + 源码 walk + Perfetto SQL + 基础八股 + 两版真题大乱斗 + Native 稳定性 + Compose 编译器 + Jetpack + 第三版真题大乱斗 完整闭环。
