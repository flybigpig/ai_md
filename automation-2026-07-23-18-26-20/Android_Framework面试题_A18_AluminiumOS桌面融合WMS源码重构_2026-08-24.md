# Android Framework 面试题 · A18 Aluminium OS 桌面融合对 WMS / display / Input / CDM 的源码级重构走读（第 40 篇）

> 日期：2026-08-24 ｜ 系列第 40 篇 ｜ 累计约 255 专题（本篇为 A18 桌面融合重构专项）
> 主线 baseline：Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX，内核 GKI android14-6.1)
> 热点锚定：A17 QPR2 Beta 3（build CP41.260731.005，2026-08-14）为本周期最大增量版本，stable 跟踪 2026-12；**「Aluminium OS」桌面融合泄漏确认**（build ZL1A.260119.001.A1，HP Elite Dragonfly 13.5 Intel x86 上 dogfooding）——这是 2026 年对 Framework 层（WMS / display / Input / CDM / 内核驱动）冲击最大的前瞻热点，今天一次做源码级重构走读。
>
> 约定：文中 `frameworks/` `system/` `drivers/` 路径默认是 **Android 14 AOSP (android-14.0.0_rXX)**；内核路径为 **GKI common-android14-6.1**；Google 公开泄漏/路线图信息显式标注 `[Aluminium OS]` 或 `[A17]`。凡属对 A18 落地形态的"前瞻推测"，一律标注为【推测】，不与 A14 已落地代码混淆。

---

## 0. 为什么今天做"Aluminium OS 桌面融合的 WMS 源码重构"

前 39 篇（约 249 专题）已把主线（Binder/AMS/WMS/SF/ART/HAL/内核）+ 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧 AI + 源码 code walk + Perfetto SQL + 基础八股 + 三版真题大乱斗 + Native 稳定性 + Compose 编译器 + 输入系统 + Jetpack 架构 全部闭环。复盘时发现一个**反复挂起、至今未独立成篇的真缺口**：

```
缺口（真缺口）：A18 桌面融合对 Framework 的重构级冲击
  第 8 篇(8/8) 只讲了 A18 "Desktop Mode 2.0" 的 freeform 三维辨析;
  第 21 篇(8/23 vol.3) 把 Aluminium OS 当成一个混合场景考了一次, 但没拆底座。
  而 2026-08 的 Aluminium OS 泄漏(ChromeOS 技术栈重建于 Android 内核)是
  对 WMS / display / Input / CDM / 内核驱动 的重构级事件——
  今天把它彻底焊成一条源码级链路, 作为系列第 40 篇。
```

### 0.1 当日热点锚定（2026-08-24 已联网核实）

- **A17 QPR2 Beta 3 (CP41.260731.005, 2026-08-14)**：本周期最大版本，含原生 App Lock、可重排 Quick Settings、锁屏模糊、色彩主题扩展、防呼叫转移诈骗、折叠屏多任务手柄。Stable 跟踪 **2026-12**（配合 Pixel Feature Drop）。QPR1 预计 2026-09 stable，QPR3 预计 2027 初。`mainline` 模块与 AOSP 二次开源节奏不变。
- **Aluminium OS 泄漏（2026-08 公开）**：Google Android 生态负责人 Sameer Samat 明确表态「把 ChromeOS 的底层技术重建在 Android 之上」。一次 Chromium issue tracker 的意外屏幕录制泄露了运行在 **HP Elite Dragonfly 13.5（Intel x86，非 ARM）** 上的 "Aluminium OS" 界面，build string `ALOS: ZL1A.260119.001.A1`。关键 UI 信号：
  - 浮动任务栏（floating taskbar，居中，类 macOS / Windows 11）；
  - 标准桌面窗口控件：**minimize / maximize / close** 按钮 + 下拉菜单「desktop windowing / truly full screen」；
  - Windows snap（拖到侧边平铺、手动调尺寸）；
  - 状态栏出现 **Gemini 专属按钮**（AI 进 OS 级）；
  - **Chrome 扩展（extensions）图标**出现在浏览器里（"浏览器优先"计算时代落幕）；
  - 设备分级战略：`AL Entry / AL Mass Premium / AL Premium`，2026 与 ChromeOS 并行过渡，Q4 2026 设备落地。
- **结论**：桌面融合是 A18（代号 Aluminium OS）最大增量，它对 Framework 的冲击集中在四块——**WMS 窗口模型、多显示/外接显示、键鼠输入范式、跨设备协同边界**——而这四块恰好是你列出的核心考点（WMS / AMS / Binder / 内核 / drivers）。今天按"已落地 A14 代码 + 对 A18 重构的前瞻走读"双线推进。

---

## 专题一：A14 桌面模式的底座（复习 + 升级，所有后续章节的根）

A18 的 "true desktop windowing" 不是凭空出现，它建立在 A14 已经存在、且本身就在快速演进的桌面模式之上。面试先要证明你**知道底座**，才能谈重构。

### 1.1 三驾马车统一走 WindowOrganizer + WindowContainerTransaction

A14 中，桌面模式、任务栏、分屏**不再各自直接调 AMS**，而是统一通过 `WindowOrganizer` 下发 `WindowContainerTransaction`（WCT）：

```
frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/
  +-- desktopmode/DesktopTasksController.java   // 桌面窗口的创建/移动/缩放/关闭
  +-- taskbar/TaskbarController.java            // 任务栏(桌面态的 app drawer + 最近任务)
  +-- splitscreen/SplitScreenController.java    // 分屏(拖到侧边平铺)
        |
        +-- 三者都通过 WindowOrganizer.applyTransaction(WCT) 下发到 system_server
                    |
                    v
frameworks/base/services/core/java/com/android/server/wm/
  WindowOrganizerController.java  ->  applyTransaction() -> 在 WMS 锁内重排 WindowContainer 树
```

- **关键点**：`DesktopTasksController` 不直接操作 `Task`，而是构造 `WindowContainerTransaction`（一堆 `WindowContainerTransaction.Change` 的列表），经 Binder 调到 `IWindowOrganizerController`（实现在 `WindowOrganizerController`）。这样**WM Shell 这一独立进程（com.android.systemui 的 shell 部分）和 system_server 解耦**，事务原子性由 WMS 锁保证。
- **面试坑**：桌面模式不是「Activity 的某种 flag」，**它是 WindowContainer 的一种摆放策略**。`WINDOWING_MODE_FREE_FORM` 是窗口的摆放模式（windowing mode），和 `ACTIVITY_TYPE_STANDARD` 等活动类型是**正交的两维**（windowing mode vs activity type）。`WindowConfiguration.java` 里定义 `WINDOWING_MODE_FULLSCREEN / SPLIT_SCREEN_PRIMARY / MULTI_WINDOW / FREE_FORM / UNDEFINED`。

### 1.2 桌面窗口的"外壳"：Caption + WindowDecor

自由窗口的标题栏/控件在 A14 里由 `WindowDecor` + `CaptionWindowDecorViewModel` 自绘，不是系统统一控件：

```
frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/windowdecor/
  CaptionWindowDecorViewModel.java   // 监听桌面 Task 的增删, 挂载自绘 caption
  CaptionTouchEventListener.java     // caption 上的拖拽/缩放/关闭按钮
```

- **易错点**：A14 的 freeform 关闭/最小化按钮是 **shell 进程自绘 + 本地逻辑**，不是 `DecorView` 的原生三键。这也是为什么 Aluminium OS 泄漏里出现"标准桌面控件"会被视为**重构信号**——它暗示 caption 自绘可能升级为系统级统一窗口装饰。

### 1.3 桌面启动参数修饰器

```
frameworks/base/services/core/java/com/android/server/wm/DesktopModeLaunchParamsModifier.java
  // LaunchParamsController 的一个 modifier, 决定新启动的 Activity 在桌面模式下
  // 落哪个 display、什么尺寸、什么位置(默认桌面窗口大小由这里算)
```

---

## 专题二：Aluminium OS 新窗口模型 —— "true desktop windowing" 的源码重构走读【推测 + 已落地对照】

### 2.1 从 freeform 到 "real desktop controls"

Aluminium OS 泄漏里最刺眼的是**标准 minimize / maximize / close 按钮**，而 A14 freeform 里这些是 caption 自绘。对 A18 的重构，合理的前瞻推断（标注【推测】）是：

```
【推测】A18 可能新增一类窗口语义, 例如 WINDOWING_MODE_DESKTOP 或
       把 FREE_FORM 的 caption 收编为系统级 WindowDecor, 由 TaskbarController 统一托管三键:
  - close   -> WCT 里 removeTask / finishActivity (现有能力, 无新意)
  - minimize-> 新语义: Task 进入 "minimized" 状态(非 destroy), 由任务栏 instance 持有,
               对应 A14 已有的 ActivityStackSupervisor 暂停逻辑 + 新增 visible=false 标记
  - maximize-> toggle WINDOWING_MODE_FULLSCREEN <-> FREE_FORM, 复用 WindowConfiguration 切换
```

- **落地佐证（A14 已能做的）**：`DesktopTasksController` 里 `moveToFullscreen()` / `moveToDesktop()` 已经能在 fullscreen 与 freeform 间切换；"maximize" 本质就是 `moveToFullscreen()`，"minimize" 在 A14 里靠 `setMinimized()`（DesktopMode 私有状态）模拟。所以 A18 的"标准控件"更多是**交互统一 + 任务栏深度接管**，而非全新的 IPC 机制。
- **面试追问**：「minimize 的 Activity 还在不在内存里？」—— 在（除非 LMKD 回收）。minimize ≠ finish，它只把 Task 的 `visible` 置 false 并交给 Taskbar 持有；AMS 侧的 `mVisibleActivities` 计数减少，**但 `ProcessRecord` 不一定死**，这正好接第 34 篇的 oom_adj 三段杀与第 19 篇的内存三路杀。

### 2.2 Taskbar 接管窗口生命周期

```
frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/taskbar/TaskbarController.java
  // 桌面态下: app drawer 按钮 + 已固定 app + 运行中 app 视觉分隔; 泄漏里它是"浮动任务栏"
```

- **重构信号**：Aluminium OS 泄漏的"居中浮动任务栏 + 固定 app / 运行中 app 分隔线"，与 A14 `TaskbarController` 的桌面态结构高度吻合，**说明 A18 是把 A14 的 Taskbar 从 Pixel Tablet 专属推广为笔记本默认**，而非重写。落地代码路径不变，产品形态升级。

### 2.3 窗口层级树的新问题

当 minimize/maximize/多窗口叠加时，`RootWindowContainer` 下每个 `DisplayContent` 的 `mChildren`（一串 `TaskDisplayArea` -> `Task` -> `ActivityRecord` -> `WindowState`）树会频繁重排。`WindowContainerTransaction` 的**批量变更 + WMS 锁内原子应用**是这套模型能扛住高频窗口操作的根本。

---

## 专题三：多显示 + 外接显示热插拔（桌面融合的物理前提）

桌面笔记本必然有**外接显示器热插拔**，这是对 display 子系统（DisplayManagerService）最直接的冲击点，也是 AAOS 多显示（第 18 篇）的同源技术。

### 3.1 DisplayManagerService 与 Adapter 模型

```
frameworks/base/services/core/java/com/android/server/display/DisplayManagerService.java
  // 系统服务, 管理所有 LogicalDisplay; 通过 DisplayAdapter 抽象不同来源
frameworks/base/services/core/java/com/android/server/display/LocalDisplayAdapter.java
  // 内置显示(HWC 通过 SurfaceFlinger 上报的 built-in display)
frameworks/base/services/core/java/com/android/server/display/ExternalDisplayAdapter.java  // 【A14 已存在, 外接 HDMI/DP/USB-C 显示】
```

- **热插拔链路**：内核 `drivers/gpu/drm/**` 的 DRM/KMS 检测到 connector 状态变化 -> `hotplug` uevent -> `SurfaceFlinger` 通过 `HWComposer` 拿到显示增删 -> 通知 `DisplayManagerService` -> 创建/销毁 `LogicalDisplay` -> 更新 `DisplayContent`（WMS 侧）。
- **面试坑**：外接显示增删是**异步跨进程事件**，App 通过 `DisplayManager.DisplayListener` 感知；WMS 侧每个 `DisplayContent` 是独立窗口树根。把桌面窗口从内置屏拖到外接屏 = 把 `Task` 的 `mDisplayId` 改掉并迁移整个 `WindowContainer` 子树——这正是 `WindowContainerTransaction` 的 `setToDisplay()` 能力。

### 3.2 桌面窗口跨 display 迁移

```
frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/desktopmode/DesktopTasksController.java
  // 拖拽 Task 到另一 display 时 -> WCT.setToDisplay(task, newDisplayId)
frameworks/base/services/core/java/com/android/server/wm/DisplayContent.java
  // moveTaskToDisplay() / 窗口树重父化
```

- **易错点**：跨 display 迁移**不改变 ActivityRecord 身份**（不重建 Activity），只是换 `DisplayContent` 父节点 + 重新布局。这点和"旋转屏幕不重建 Activity（除非声明 configChanges）"是同一类"配置变更 vs 实例销毁"的判断题。

### 3.3 与 AAOS 多显示的同源对照（第 18 篇联动）

AAOS 的 `CarOccupantZoneManager`（occupant -> display -> user 强分区）与桌面多显示是**同一套 `DisplayManagerService` 基础设施**的两个产品形态：一个按"乘员分区"，一个按"物理显示器"。面试能指出"多显示的根是 `DisplayContent` 多实例"即满分。

---

## 专题四：输入范式归一化 —— 键鼠为主、触控为辅

桌面融合把输入范式从"触控优先"翻转为"键鼠优先 + 触控并存"，直接命中你列的 `drivers 驱动 / Input 系统`（第 16 篇）。

### 4.1 键盘快捷键体系

```
frameworks/base/core/java/android/view/KeyboardShortcutGroup.java   // 一组快捷键(按窗口/系统分类)
frameworks/base/core/java/android/view/KeyboardShortcutInfo.java    // 单个快捷键((label, keycode, modifiers))
frameworks/base/services/core/java/com/android/server/policy/PhoneWindowManager.java
  interceptKeyBeforeDispatching()  // 系统级快捷键拦截(如截图/最近任务/桌面切换)
```

- **Aluminium OS 信号**：泄漏提到"移除传统导航栏、偏向窗口管理"——意味着系统导航从 3-button / gesture 转为**键盘 + 任务栏**驱动，`PhoneWindowManager` 的 `interceptKeyBeforeDispatching` 会新增大量桌面快捷键（如 Win/Cmd+数字切换应用、Alt+Tab 等价物）。
- **面试坑**：`interceptKeyBeforeDispatching` 在 `InputDispatcher` 把 KeyEvent 派发给 App **之前**由 system_server 的 WMS 策略线程处理；它返回的 `long` 是"延迟派发的窗口 token（0 表示不拦截）"。这和 touch 的 `onInterceptTouchEvent` 是**不同层**的拦截（native InputDispatcher vs View 树），第 16 篇已细讲 touch，这里补 keyboard 这一半。

### 4.2 鼠标 / 触控板归一化

```
frameworks/base/services/core/java/com/android/server/input/InputReader.cpp (native)
  TouchpadInputMapper.cpp   // 触控板 -> 合成鼠标事件 + 手势(双指滚动/三指切换)
frameworks/base/services/core/java/com/android/server/input/InputDispatcher.cpp
  setPointerCaptureLocked() // Pointer Capture(第 10/16 篇讲过: 把指针事件排他给某窗口)
```

- **联动**：触控板手势（双指滚动、三指上滑切应用）在 Aluminium OS 里会是核心交互，`TouchpadInputMapper` 通过 `libchrome-gestures`（Chromium 的 gestures 库，因 ChromeOS 技术栈复用而自然引入）做手势识别，再翻译成 Android 的 `MotionEvent` / `KeyEvent`。这正是"ChromeOS 技术栈重建于 Android"在输入层的落点佐证。

### 4.3 指针事件与 View 树的接缝

桌面窗口的 hover / 右键 / 拖拽缩放，最终都进 `ViewRootImpl` 的 `InputStage` 责任链（第 16 篇 §6），经 `DecorView` 派发到 `View` 树。区别是：触控的 `MotionEvent` 是 `ACTION_DOWN/MOVE/UP`，鼠标是 `ACTION_HOVER_*` + `BUTTON_*` + `ACTION_POINTER_DOWN` 带 button state——**同一个 `onTouchEvent` 要同时处理两套语义**，是自定义 View 的经典坑。

---

## 专题五：应用兼容层 —— WM 1.5 尺寸类 + ActivityEmbedding + 折叠屏

桌面大屏下，手机 App 直接全屏会丑，"应用兼容层"决定 App 怎么适应。

### 5.1 WindowSizeClass（WM 1.5 尺寸类）

```
androidx/compose/material3/window/WindowSizeClass.kt   // Compact / Medium / Expanded
androidx/window/WindowManager / Jetpack WindowManager  // 1.5 尺寸类, 基于 dp 宽度断点
```

- **语义**：`WindowSizeClass` 是**纯计算**（按当前窗口 dp 宽度落入断点），不依赖具体设备。`EXPANDED` 大屏触发双栏布局（如邮件列表+详情）。桌面窗口自由缩放时，`WindowSizeClass` 会随窗口尺寸**实时跳变**，驱动 Jetpack Compose / 传统 View 的响应式重排。
- **面试坑**：`WindowSizeClass` 是**窗口尺寸**不是**屏幕尺寸**——外接显示器上把 App 窗口拉到很小，它就是 `Compact`；这是和 `smallestScreenWidthDp`（资源限定符）的关键区别。

### 5.2 ActivityEmbedding（应用侧分栏）

```
android/window/TaskFragment.java          // 一个 Task 内嵌多个 TaskFragment(分栏容器)
android/window/TaskFragmentOrganizer.java // App 声明分栏规则, 经 WindowOrganizer 下发
frameworks/base/libs/WindowManager/Java/src/android/window/WindowContainerTransaction.java
  setAdjacentTaskFragments() / setLaunchAdjacentFlags()
```

- **定位**：ActivityEmbedding 让**单个 App 自己决定**主从分栏（无需系统分屏），桌面大屏下邮件/笔记/文档类 App 用它做双栏。它和第 8 篇的 `ActivityEmbedding` 一致，是桌面融合的"应用侧适配主力"。

### 5.3 折叠屏旋转 + 多窗口竞态（第 12 篇 vol.2 场景①联动）

桌面融合设备上折叠/展开 + 外接显示 + 自由窗口同时发生，`onConfigurationChanged` 与 `Surface` 销毁时序竞态是经典黑屏坑（呼应 QPR2 Beta1 修复 #516836306 多指拖拽丢触摸的同源"时序竞态"类问题）。

---

## 专题六：跨设备协同 —— CDM + Handoff + 通用剪贴板 + 跨 VM 边界

桌面笔记本天然是"跨设备枢纽"，Aluminium OS 的 Gemini 按钮 + 通用剪贴板 + Handoff 是协同重点（第 8 篇已讲，这里把"桌面融合"作为新场景缝合）。

### 6.1 CompanionDeviceManager（CDM）

```
android/companion/CompanionDeviceManager.java
packages/modules/CompanionDevice/...   // Mainline 模块, A17 QPR2 把 companion 从无障碍分流(第 8 篇)
```

- **桌面场景**：手机 <-> 笔记本的持久 Association 由 CDM 建立，系统级角色权限（如锁屏屏幕自动化）在 A17 QPR2 已重写。Aluminium OS 下"在笔记本上接手手机任务"走的就是这条已落地通道。

### 6.2 通用剪贴板 + Handoff

```
frameworks/base/services/core/java/com/android/server/clipboard/ClipboardService.java
android/content/ClipData.java   // 跨 App/跨设备共享的"信封", 传输中 AES-GCM 加密(第 8 篇)
```

- **重构信号**：Aluminium OS 路线图明确列"通用剪贴板"，即手机复制 -> 笔记本粘贴。机制 = `ClipboardService` 跨设备同步 `ClipData`（不是新 IPC，复用现有），但**跨设备传输必须加密**，且"跨设备 getCallingUid 不可信"（第 12/13 篇结论：跨 VM / 跨设备调用方 UID 不能当信任锚）。

### 6.3 跨 VM 安全边界（机密计算联动第 12 篇）

若 Gemini 本地推理跑在 pKVM 的 Microdroid（第 12 篇 AVF），则笔记本 App -> 推理 VM 的 Binder RPC 同样面临"getCallingUid 不可信"。桌面融合把"端侧 AI + 跨 VM"从手机奢侈品变成笔记本默认能力，这条安全边界题的权重会显著上升。

---

## 专题七：内核 / 驱动支撑 —— 外接显示 DRM-KMS + HID + GKI

桌面融合对内核驱动的压力集中在三处（命中你列的 `linux kernel / drivers 驱动`）。

### 7.1 外接显示：DRM / KMS

```
drivers/gpu/drm/                  // Direct Rendering Manager + Kernel Mode Setting
  drm_connector.c / drm_crtc.c / drm_plane.c   // connector(物理口) / CRTC(管线) / plane(图层)
drivers/gpu/drm/panel/  drivers/gpu/drm/bridge/  // 面板 / 桥接芯片(DP->eDP 等)
```

- **链路**：外接显示器插入 -> DRM 子系统 `drm_connector` 状态变 `connected` -> 产生 hotplug -> `drm_kms_helper_hpd_irq_event()` -> 用户态（SurfaceFlinger/HWC）重新枚举 display -> `DisplayManagerService` 见 §3.1。
- **面试坑**：EDID（显示器能力描述）由内核 `drivers/gpu/drm` 读取并暴露给用户态；分辨率/刷新率协商是 KMS 的 `drm_mode` 匹配，不是 Android Framework 层决定——Framework 只能"选"不能"造"模式。

### 7.2 输入：HID 子系统

```
drivers/hid/                      // Human Interface Device: 键盘/鼠标/触控板
  hid-input.c / hid-core.c
drivers/hid/usbhid/  drivers/hid/i2c-hid/   // USB HID / I2C HID(笔记本内置触控板)
```

- **链路**：HID 设备 -> `hid-core` 解析 report descriptor -> `input_event`（第 16 篇的 `struct input_event`）-> `evdev` 节点 `/dev/input/eventN` -> `EventHub`（InputReader 侧）-> 见 §4。桌面融合让 HID 设备种类暴增（外接机械键盘、多键鼠、触控板），`EventHub` 的 `getEvents()` 吞吐与 `InputReader` 的多 `InputMapper` 并行是稳定性重点。

### 7.3 GKI 2.0 / KMI 解耦（第 17 篇联动）

外接显示/键鼠的 vendor 驱动必须过 **GKI KMI** 边界（`drivers/` 符号稳定 ABI）。Aluminium OS 跑在 Intel x86 上（泄漏证实），意味着 x86 平台的 GKI 对齐与 ARM64 同等重要——这是 MTK/高通之外新的 vendor 差异面（第 17 篇 MTK 真缺口的"x86 版"延伸）。

---

## 专题八：易错红榜 TOP18（桌面融合专项 + 全系列交叉）

1. **桌面模式不是 Activity flag，是 WindowContainer 的 windowing mode**（`WINDOWING_MODE_FREE_FORM` 与 activity type 正交）。
2. **minimize ≠ finish**：Task 仍在 WMS 树，仅 `visible=false`，ProcessRecord 未必死，接 oom_adj 三路杀。
3. **caption 三键在 A14 是 shell 自绘**，非 `DecorView` 原生；Aluminium OS 的"标准控件"疑似系统级收编【推测】。
4. **WindowContainerTransaction 是 WM Shell 与 system_server 解耦的原子事务**，经 `IWindowOrganizerController` Binder 调用，WMS 锁内重排。
5. **跨 display 迁移不重建 Activity**，只改 `DisplayContent` 父节点 + 重布局（同"旋转不重建"一类）。
6. **WindowSizeClass 看窗口不看屏幕**：拉小窗口即 `Compact`；区别于 `smallestScreenWidthDp`。
7. **KeyboardShortcutGroup 是系统级快捷键分组**，`PhoneWindowManager.interceptKeyBeforeDispatching` 的返回值是"延迟窗口 token"，0=不拦截。
8. **键盘拦截在 native InputDispatcher 之前、View 树之外**，与 touch 的 `onInterceptTouchEvent` 不同层。
9. **触控板手势经 TouchpadInputMapper + libchrome-gestures 翻译成 MotionEvent/KeyEvent**——ChromeOS 技术栈复用的直接证据。
10. **外接显示热插拔是异步跨进程事件**，App 经 `DisplayManager.DisplayListener` 感知，根在 `DisplayContent` 多实例。
11. **DRM/KMS 负责模式协商（EDID/分辨率/刷新率），Framework 只能选不能造**。
12. **HID 设备 -> input_event -> /dev/input/eventN -> EventHub**，桌面融合下 HID 种类暴增考验 InputReader 吞吐。
13. **ActivityEmbedding 是应用侧分栏**，走 `TaskFragmentOrganizer` + WCT，不依赖系统分屏。
14. **跨设备剪贴板复用 ClipData + 传输加密**，且"跨设备 getCallingUid 不可信"。
15. **跨 VM 推理 Binder RPC 同样 getCallingUid 不可信**（接第 12/13 篇机密计算边界）。
16. **GKI KMI 是 x86 与 ARM64 同等强约束**，Aluminium OS 上 Intel 平台也要过 KMI。
17. **DesktopModeLaunchParamsModifier 决定新桌面窗口的 display/尺寸/位置**，不是随机摆放。
18. **Aluminium OS 形态多为"产品升级 + 技术栈统一"，不是全新 IPC**——面试要分清"新交互"与"新机制"。

---

## 专题九：三条高频追问链（桌面融合场景）

### 链 A：冷启动一个桌面 App，到它在外接显示器上自由窗口上屏（跨 8 篇）
`startActivity`(ATMS, 第 34 篇 boot 侧) -> `DesktopModeLaunchParamsModifier` 决定落外接 display -> `WindowContainerTransaction.setToDisplay()` 跨 `DisplayContent` -> `ViewRootImpl.setView`(第 20 篇 code walk) -> `SurfaceFlinger` 合成到外接 `DisplayContent` 的 HWC(第 20 篇 §5) -> 帧上屏。`热点加试题`：若此时外接显示器热插拔拔掉，Window 去哪？(答：`DisplayManagerService` 销毁 `LogicalDisplay` -> WMS 把该 display 的 Task 迁移回内置 display 或最小化，不崩溃)。

### 链 B：键盘快捷键呼出任务栏，再切到另一应用（WMS + Input 联动）
`KeyEvent` -> `InputDispatcher` -> `PhoneWindowManager.interceptKeyBeforeDispatching` 拦截系统快捷键 -> `TaskbarController` 弹出 -> 用户选择 -> `DesktopTasksController` 经 WCT `reorder`/`moveToFullscreen` -> WMS 锁内重排窗口树。`加试题`：为什么键盘拦截能"抢在 App 之前"？(答：system_server WMS 策略线程在 InputDispatcher 派发前处理，返回非 0 token 即吞掉事件)。

### 链 C：手机复制 -> 笔记本粘贴（跨设备协同 + 安全边界）
`ClipboardService` 跨设备同步 `ClipData`(传输 AES-GCM) -> 笔记本 App 读取 -> 若内容触发 Gemini 本地推理且推理在 pKVM VM 内 -> App -> 推理 VM 的 Binder RPC **getCallingUid 不可信**(第 12/13 篇) -> 必须靠 attestation/签名校验而非 UID。`加试题`：跨设备 UID 与跨 VM UID 为什么都不能当信任锚？(答：UID 是"同内核命名空间"概念，跨设备/跨 VM 没有共享内核，UID 各自独立编号，伪造成本极低)。

---

## 专题十：AOSP 14 源码路径清单（桌面融合重构专项）

| 子系统 | 关键类 / 文件 | 路径 |
|---|---|---|
| 桌面窗口控制 | DesktopTasksController | `frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/desktopmode/` |
| 任务栏 | TaskbarController | `frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/taskbar/` |
| 分屏 | SplitScreenController | `frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/splitscreen/` |
| 窗口装饰 | CaptionWindowDecorViewModel / CaptionTouchEventListener | `frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/windowdecor/` |
| WCT 应用 | WindowOrganizerController | `frameworks/base/services/core/java/com/android/server/wm/` |
| 启动参数 | DesktopModeLaunchParamsModifier | `frameworks/base/services/core/java/com/android/server/wm/` |
| 窗口配置 | WindowConfiguration (WINDOWING_MODE_*) | `frameworks/base/core/java/android/app/WindowConfiguration.java` |
| 显示管理 | DisplayManagerService / LocalDisplayAdapter / ExternalDisplayAdapter | `frameworks/base/services/core/java/com/android/server/display/` |
| 显示内容 | DisplayContent (moveTaskToDisplay) | `frameworks/base/services/core/java/com/android/server/wm/` |
| 键盘快捷键 | KeyboardShortcutGroup / KeyboardShortcutInfo | `frameworks/base/core/java/android/view/` |
| 策略拦截 | PhoneWindowManager.interceptKeyBeforeDispatching | `frameworks/base/services/core/java/com/android/server/policy/` |
| 输入读取 | InputReader / TouchpadInputMapper / InputDispatcher | `frameworks/base/services/core/java/com/android/server/input/`(native 在 `frameworks/native/services/inputflinger/`) |
| 尺寸类 | WindowSizeClass | `androidx/compose/material3/window/` + `androidx/window/` |
| 应用分栏 | TaskFragment / TaskFragmentOrganizer | `frameworks/base/libs/WindowManager/Java/src/android/window/` |
| 跨设备 | CompanionDeviceManager / ClipboardService / ClipData | `android/companion/` + `frameworks/base/services/core/java/com/android/server/clipboard/` |
| 外接显示驱动 | DRM / KMS (connector/crtc/plane) | `drivers/gpu/drm/` (GKI common-android14-6.1) |
| HID 输入驱动 | hid-core / hid-input / usbhid / i2c-hid | `drivers/hid/` |
| GKI/KMI | KMI 符号稳定 ABI | `drivers/` + `include/` (GKI 2.0) |

---

## 专题十一：39 -> 40 篇交叉索引（桌面融合重构专项）

- **第 8 篇（8/8 A18 桌面融合初次）**：本篇是其"源码级深化"，把 freeform 三维辨析落成 `DesktopTasksController`/`TaskbarController`/`WindowOrganizer` 真实代码。
- **第 16 篇（8/16 输入系统全链路）**：本篇 §4 补 keyboard/Touchpad 这一半，与 §6 touch 责任链拼成完整输入范式。
- **第 17 篇（8/17 HAL/内核驱动）**：本篇 §7 的 DRM-KMS / HID / GKI 是其"桌面外设驱动"延伸（含 x86 平台新维度）。
- **第 18 篇（8/18 AAOS 多显示）**：本篇 §3.3 指出车载多显示与桌面多显示同源于 `DisplayContent` 多实例。
- **第 20 篇（8/20 源码 code walk）**：本篇链 A 复用其 `startActivity -> 首帧` 链路，新增"落外接 display"分支。
- **第 22 篇（8/8 跨设备协同）**：本篇 §6 的 CDM/剪贴板/Handoff 是其桌面场景缝合。
- **第 12/13 篇（机密计算边界）**：本篇 §6.3 跨 VM Binder `getCallingUid` 不可信是其桌面融合复用。
- **第 34 篇（8/19 启动链路/system_server）**：本篇 §2.2 minimize 与 oom_adj 三路杀、§3 跨 display 迁移与进程模型联动。
- **第 23/27/39 篇（真题大乱斗 vol.1/2/3）**：本篇链 A/B/C 可作为 vol.4 的候选混合场景素材。

> 全系列至此 **40 篇 / 约 255 专题** 完整闭环（含 A18 Aluminium OS 桌面融合 WMS 源码重构专项）。剩余可选增量：KMP/Swift Export 实战坑下钻、Compose 编译器插件 IR lowering（`ComposableFunctionBodyTransformer`）内部逐行走读、Aluminium OS 落地后对照 A14 的真实 diff 复盘（待 A18 源码/AOSP 二次开源后补）。

---

## 延伸阅读（冲刺自检用）

1. AOSP：`frameworks/base/libs/WindowManager/Shell/` 下的 `desktopmode` / `taskbar` / `splitscreen` 三目录，对照 `WindowOrganizerController` 读事务落地。
2. AOSP：`frameworks/base/services/core/java/com/android/server/display/` 的 Adapter 模型，配合 `drivers/gpu/drm/` 的 KMS 理解外接显示。
3. AOSP：`frameworks/native/services/inputflinger/` 的 `InputReader` / `InputDispatcher`，对照第 16 篇看 keyboard/touchpad 与 touch 的对称结构。
4. Jetpack：`androidx.window` + `androidx.compose.material3.window` 的 `WindowSizeClass` 与 `ActivityEmbedding` 官方 Sample。
5. Google I/O 2026 / Android 开发者博客：Aluminium OS 路线图与「ChromeOS 技术栈重建于 Android」公开表态（待官方文档细化后回看本篇【推测】项）。
6. 第 8/12/13/16/17/18/20/22/34 篇：本篇所有交叉引用点，冲刺时按链 A/B/C 串讲。
