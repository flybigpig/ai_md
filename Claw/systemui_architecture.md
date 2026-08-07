# SystemUI 架构详解（Android 14 / AOSP）

> 本文聚焦**架构原理与调用链路**；"怎么改 SystemUI" 见同目录 `systemui_customization.md`，窗口层级细节见 `wms_deep_dive.md`。
> 注：AOSP 12 起 `StatusBar` 已重命名为 `CentralSurfaces` / `CentralSurfacesImpl`，下文统一用新名，旧名会标注。

## 0. 一句话定位
SystemUI 是 Android 的**系统界面进程**，包名 `com.android.systemui`，运行在独立进程（**非 system_server**），是带系统权限（priv-app、platform 签名）的普通 app。它承载状态栏、导航栏、通知、快捷设置、锁屏、最近任务、音量、截图、画中画等所有"系统级 UI"，通过 binder 与各系统服务（AMS/ATMS/WMS/NMS/AudioService/PowerManager）通信，可崩溃后被 zygote/system_server 重新拉起。

代码根目录：`frameworks/base/packages/SystemUI/`，编译产物 `SystemUI.apk`，安装到 `/system/priv-app/SystemUI/`。

---

## 1. 进程与启动链路

### 1.1 启动时序（SystemServer → SystemUI）
```
SystemServer.startOtherServices()
  └─ startSystemUi(context, wm)
       ├─ context.startServiceAsUser(                       // 经 AMS 启动
       │     new Intent().setComponent(mSystemUiComponent), // com.android.systemui/.SystemUIService
       │     UserHandle.SYSTEM)
       └─ windowManager.onSystemUiStarted()                 // 通知 WMS:SystemUI 已就绪
```
`mSystemUiComponent` 来自资源 `config_systemUiComponent`（`frameworks/base/core/res/res/values/config.xml`）。

SystemUI 的入口是一个 `Service`（非 Activity）：
```java
// SystemUIService.java
public void onCreate() {
    super.onCreate();
    ((SystemUIApplication) getApplication()).startServicesIfNeeded();
}
```

### 1.2 组件启动（插件式架构）
`SystemUIApplication.startServicesIfNeeded()` 做三件事：
1. 读取 `res/values/config.xml` 的 **`config_systemUIServiceComponents`**（String[]，所有 SystemUI 组件类名）；
2. 通过 `SystemUIFactory`（Dagger 图）反射实例化每个 `SystemUI` 子类；
3. 调用 `systemUI.start()` → 各组件 `onStart()` 完成初始化。

基类契约：
```java
// SystemUI.java（抽象类）
public abstract class SystemUI {
    public final void start() {        // 模板方法
        onStart();                     // 子类实现
        mStarted = true;
    }
    protected abstract void onStart();
    public void onBootCompleted() {}   // 收到系统启动完成广播后回调
}
```

### 1.3 组件注册表（config_systemUIServiceComponents，AOSP 14 典型清单）
| 组件 | 职责 |
|---|---|
| `com.android.systemui.keyguard.KeyguardViewMediator` | 锁屏核心，桥接 Power/AMS/WMS |
| `com.android.systemui.statusbar.phone.CentralSurfacesImpl` | 状态栏/通知 shade（原 StatusBar） |
| `com.android.systemui.recents.Recents` | 最近任务（旧架构入口） |
| `com.android.systemui.volume.VolumeUI` | 音量面板 |
| `com.android.systemui.pip.PipUI` | 画中画 |
| `com.android.systemui.shortcut.ShortcutKeyDispatcher` | 系统快捷键 |
| `com.android.systemui.ScreenDecorations` | 圆角/挖孔/刘海遮罩 |
| `com.android.systemui.biometrics.AuthController` | 生物识别提示 |
| `com.android.systemui.globalactions.GlobalActionsComponent` | 长按电源菜单 |
| `com.android.systemui.accessibility.SystemActions` | 无障碍系统动作 |

> 设计点：**新增一个系统 UI 功能 = 写一个 `SystemUI` 子类 + 注册进 config 数组**，无需改启动主流程。

### 1.4 依赖注入（Dagger2）
AOSP 12+ SystemUI 全面引入 Dagger。核心：
- `SystemUIFactory`：构建根 `SysUIComponent` 图；
- `Dependency`：`get(Class<T>)` 提供单例（`Dependency.get(T::class.java)`）；
- `SystemUIDependencyProvider`：声明全局单例绑定。

组件间不直接 new，而是从图里取（`StatusBarIconController`、`NotificationEntryManager`、`CommandQueue` 等）。

---

## 2. 核心子系统详解

### 2.1 状态栏 CentralSurfaces（原 StatusBar）
路径：`statusbar/phone/CentralSurfacesImpl.java`

关键协作类：
- `StatusBarWindowController`：管理状态栏窗口的 `WindowManager.LayoutParams`（`TYPE_STATUS_BAR`），控制 show/hide、expanded、keyguard 状态、z-order。
- `StatusBarWindowView` / `CollapsedStatusBarFragment`：折叠态视图（时钟、图标、电池）。
- `StatusBarIconControllerImpl` + `StatusBarIconList`：状态栏图标（信号/电池/WIFI）的增删改。
- `StatusBarSignalPolicy` / `BatteryController` / `Clock`：各图标数据源。
- `NotificationIconAreaController`：通知图标区（收起时展示的 N 个通知小图标）。
- `StatusBarStateController`：状态机 `KEYGUARD` / `SHADE` / `KEYGUARD_OCCLUDED`，驱动状态栏/锁屏切换。

### 2.2 通知（Notification）
路径：`statusbar/notification/`

链路（**SystemUI 是展示层，真相在 NMS**）：
```
NotificationManagerService(NMS, 系统服务)
  └─ 通过 NotificationListener 接口回调
       └─ SystemUI: NotificationListener(extends NotificationListenerService)
            onNotificationPosted/Removed(StatusBarNotification)
            └─ NotificationEntryManagerImpl: 维护 Map<String,NotificationEntry>
                 └─ NotificationEntry → NotificationRow (视图)
                      └─ NotificationStackScrollLayout (通知 shade 里的滚动列表)
```
- `NotificationListener`：继承 `NotificationListenerService`，注册为通知监听者（`NotificationManager.registerListener`）。
- `NotificationEntryManager`：内部维护通知集合（`NotificationData` / `NotificationEntry`），不直接持有真相，仅缓存用于渲染。
- 通知 shade 视图：`NotificationPanelViewController` + `NotificationShadeWindowView`（`TYPE_NOTIFICATION_SHADE`）。

> 关键点：NMS 才是通知的唯一真相源；SystemUI 崩溃不影响通知数据，重启后重新同步。

### 2.3 快捷设置 QS
路径：`qs/`

- `QSFragment`：下拉后的完整 QS 面板（嵌入通知 shade 上方）。
- `QSPanel` / `QuickQSPanel`：磁贴容器（完整 QS / 下拉即见的快捷 QS）。
- `QSTileHost`（`QSHost`）：磁贴宿主，持有所有 tile 实例，处理点击/长按/状态变化。
- `QSTileImpl`：单个磁贴基类（`TileState` 描述开/关/不可用）。
- `TileServices`：管理 tile 生命周期，向外部 app 的 `TileService` 暴露接口。
- 自定义磁贴：实现 `QSTileImpl` 子类并注册到 `TileMapper`，或 app 侧继承 `TileService`（`android.service.quicksettings`）。

### 2.4 锁屏 Keyguard
- `KeyguardViewMediator`：锁屏的"大脑"，监听 `PowerManager` 睡眠/唤醒、`AMS` 活动切换、`WMS` 窗口变化，决定锁屏显示/隐藏、是否需密码（`isSecure`）。
- `StatusBarKeyguardViewManager`：管理锁屏视图层级（状态栏、锁屏主体、bouncer 密码页）。
- `KeyguardBouncer` / `KeyguardPasswordView`：密码/图案输入页。
- `KeyguardStatusBarViewController`：锁屏上的状态栏（时钟、图标）。
- 渲染位置：**锁屏 UI 在 SystemUI 进程内绘制**（Android 5+ 已废弃独立 Keyguard.apk）。窗口类型 `TYPE_KEYGUARD` / 由 `StatusBarWindowController` 统一调度。

### 2.5 导航栏 NavigationBar
路径：`navigationbar/`

- `NavigationBarController`：多屏下每屏一个 `NavigationBar`。
- `NavigationBarView`：导航栏视图容器。
- `NavigationBarInflaterView`：从 `navigation_bar.xml` **反射式 inflate** 出三个按钮（back/home/recents），支持自定义布局。
- 三键 vs 手势：**手势导航由 Launcher3（Quickstep）处理**，SystemUI 仅负责三键模式；两者通过 §2.6 的 `OverviewProxyService` 桥接。

### 2.6 最近任务 Recents / Overview（重点：跨进程桥接）
- 旧架构：`Recents` + `RecentsImplementation`，`TaskView` / `TaskThumbnailView` 在 SystemUI 内绘制。
- 新架构（手势导航，AOSP 12+）：最近任务实际由 **Launcher3（Quickstep）** 承载。桥接者：
  ```
  SystemUI: OverviewProxyService
    └─ 持有 IOverviewProxy binder（Launcher 实现并注册）
         ├─ SystemUI → Launcher：onOverviewShown()、onSystemUiStateChanged()
         └─ Launcher → SystemUI：registerCallback / 手势事件上报
  ```
- 任务缩略图：`TaskSnapshot` 由 `ActivityTaskManagerService` 的 `TaskSnapshotController` 抓取，跨 binder 传给 SystemUI/Launcher。

> 设计点：**SystemUI 不直接画最近任务卡片**，而是把"概览"能力委托给 Launcher，自己只做手势捕获与状态同步。这是 Treble/模块化思想的体现。

### 2.7 其他常驻子系统
- `VolumeUI`：音量面板（`TYPE_VOLUME_OVERLAY`），与 `AudioService` 通信。
- `PowerUI`：低电量/高温警告（`PowerManager` 电池意图）。
- `PipUI` + `PipTaskOrganizer` + `PipTouchHandler`：画中画窗口与拖拽（关联 `wms_deep_dive.md` 的 PIP 窗口）。
- `ScreenDecorations`：屏幕圆角、挖孔、刘海的物理遮罩层（`TYPE_NAVIGATION_BAR_PANEL`/overlay）。
- `AuthController`：指纹/人脸等生物识别提示气泡。

---

## 3. 窗口层级与 WMS / SurfaceFlinger 关系

### 3.1 SystemUI 添加的窗口类型一览
| 窗口 | WindowManager.LayoutParams.type |
|---|---|
| 状态栏 | `TYPE_STATUS_BAR` |
| 导航栏 | `TYPE_NAVIGATION_BAR` |
| 通知 shade | `TYPE_NOTIFICATION_SHADE` |
| 锁屏 | `TYPE_KEYGUARD` |
| 音量面板 | `TYPE_VOLUME_OVERLAY` |
| 截图 | `TYPE_SCREENSHOT` |
| 屏幕装饰 | `TYPE_NAVIGATION_BAR_PANEL`(overlay) |

这些窗口的 `addView` 都由 `StatusBarWindowController` / 各子系统的 window controller 封装，统一走 `IWindowManager.add(...)`.

### 3.2 与 WMS 的交互
- `StatusBarWindowController` 设置 `LayoutParams` 的 `flags`（如 `FLAG_NOT_FOCUSABLE`、`FLAG_ALT_FOCUSABLE_IM`）、`gravity`、`fitsSystemWindows`，控制是否能获焦、是否下沉到状态栏区域。
- 与 `DisplayArea` 层级：SystemUI 窗口挂在 WMS 的 `DisplayContent` 下对应 `DisplayArea`，最终由 WMS 算 z-order，再经 `SurfaceControl.Transaction` 落到 SurfaceFlinger 成为 `Layer`（详见 `wms_deep_dive.md`、`framework_interview_deep_dive.md` 中的层级树/Feature 讨论）。
- 锁屏与状态栏的层级互斥/叠加由 `StatusBarStateController` + `StatusBarWindowController` 协同控制。

### 3.3 与 SurfaceFlinger
SystemUI 本身不直接碰 SF，它只是"又一组应用窗口"。WMS 把这些窗口翻译成 `Layer`，SF 合成。理解壁纸闪黑、双壁纸 token、PIP 层级，本质都是这些窗口在 WMS→SF 链路的 z-order 问题（见 `framework_interview_deep_dive.md` 第 12–14 题）。

---

## 4. 关键 binder / 广播接口

- **对外暴露**：SystemUI 通过 `CommandQueue`（内部 binder `ICommandQueue`）接收 system_server 下发的指令（如 `toggleRecentApps`、`setIcon`），也通过 `SysUiProxy` 暴露给 WM Shell。
- **OverviewProxyService**：与 Launcher3 的 `IOverviewProxy` binder 通道（见 §2.6）。
- **监听的关键广播**：`Intent.ACTION_USER_SWITCHED`（多用户）、`ACTION_SCREEN_ON/OFF`、`ACTION_CONFIGURATION_CHANGED`、`BOOT_COMPLETED`（触发 `onBootCompleted()`）。
- **与 NMS**：`NotificationListenerService` 的 `INotificationListener` binder 回调。

---

## 5. 调试与排障

```bash
# 状态栏/通知/QS 状态
adb shell dumpsys statusbar
# 通知真相（NMS 侧）
adb shell dumpsys notification
# 窗口层级
adb shell dumpsys window | grep -i systemui
# SystemUI 系统日志
adb logcat -b all | grep -i systemui
# 快速验证改动（杀掉后 Zygote 自动重启 SystemUI）
adb shell ps -A | grep com.android.systemui
adb shell kill <pid>
# 查看当前 SystemUI 组件清单
adb shell dumpsys activity services com.android.systemui
```
注意：priv-app 需平台签名；调试可 `adb install -r` 覆盖安装（详见 `systemui_customization.md` §5）。

---

## 6. 与同目录文档的关系
- `systemui_customization.md` — 怎么改（定制点 + 编译验证）
- `wms_deep_dive.md` — 窗口层级树 / DisplayArea / Layer 原理
- `framework_interview_deep_dive.md` — 面试题：WMS/SF 层级树、壁纸双 token、ShellTransition
- `binder_aidl.md` — binder 一次拷贝 / oneway（本文 §4 的 binder 通道基础）

## 7. 面试视角小结
SystemUI 面试官常考：
1. **启动链路**：SystemServer.startSystemUi → SystemUIService → SystemUIApplication 组件注册表 → 各 SystemUI.start()。
2. **架构思想**：插件式（config 数组）+ Dagger 依赖注入 + 真相在系统服务、SystemUI 只是展示层。
3. **通知**：NMS 是真相，NotificationListener 回调，NotificationEntryManager 缓存渲染。
4. **锁屏/状态栏**：同一进程内协同，状态机驱动；锁屏 UI 在 SystemUI 内（无独立 apk）。
5. **最近任务**：手势导航下委托 Launcher3，靠 OverviewProxyService 的 IOverviewProxy binder。
6. **窗口**：SystemUI 窗口是 WMS 的一类特殊窗口，最终落 SF 的 Layer。
