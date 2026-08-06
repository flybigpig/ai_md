# SystemUI 架构总览与模块详解（AOSP 14 / android-14.0.0_r1）

> 本文是 SystemUI 系列的总纲文档，承接：
>
> - `SystemUI修改指南_AOSP14.md`（改什么、怎么改）
> - `SystemUI_AndroidManifest_分析.md`（身份/权限/多进程）
> - `SystemUI启动流程详解_AOSP14.md`（从 SystemServer 到 CoreStartable.start()）
> - `KeyguardService详解_AOSP14.md`（锁屏 Binder 桩）
>
> 说明：本文为**架构级**梳理，类路径/方法名均对照 AOSP 14 仓库结构。需要某模块的逐行注释，告诉我文件名，我可以用 cnb 的 `/-/git/raw/main/` 路由拉真实源码逐行批注（已验证该路由可用）。

---

## 一、SystemUI 在系统中的位置

SystemUI 不是 Framework 的一部分，而是跑在 **`com.android.systemui` 进程**里的独立 APK（`SystemUI.apk`），但它是“特权系统应用”，和 Framework 深度耦合。

关键事实（已在 Manifest 分析中确认）：

- `package="com.android.systemui"`，`sharedUserId="android.uid.systemui"` → **专用 UID，不是 system(1000)**
- `coreApp="true"` + `persistent="true"` → 永不被 LMK 回收，崩溃由 AMS 自动拉起
- 与 `system_server` **是两个进程、两个 UID**，跨进程通信一律走 Binder

### 1.1 双向 Binder 关系

```
┌─────────────────────────┐         ┌──────────────────────────┐
│      system_server       │         │   SystemUI 进程           │
│  (uid 1000 / system)     │         │  (uid: android.uid.systemui)│
│                          │         │                          │
│ StatusBarManagerService  │◀──IStatusBar (registerStatusBar)──│ StatusBar (CommandQueue)
│        │ 回调             │         │  经 CommandQueue 收命令    │
│ NotificationManagerService│◀─NotificationListener (NS side)──│ NotificationListener
│ WallpaperManagerService  │◀───────getWallpaper──────────────│ ImageWallpaper / WMS
│ KeyguardViewMediator     │──bind──▶ KeyguardService (WMS 拉起)│ KeyguardService
│ ActivityManagerService   │◀───START_ANY_ACTIVITY 等─────────│ 各组件
└─────────────────────────┘         └──────────────────────────┘
        │                                      ▲
        └──── IStatusBarService (SystemUI 调 system) ──┘
```

| 方向              | Binder 接口              | 落点（AOSP 14）                                                                                                                                                                   |
| --------------- | ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| system→SystemUI | `IStatusBar`           | `frameworks/base/core/java/com/android/internal/statusbar/IStatusBar.aidl`；SystemUI 侧实现在 `CommandQueue`（`statusbar/CommandQueue.java`），由 `StatusBar`/`CentralSurfacesImpl` 接收 |
| SystemUI→system | `IStatusBarService`    | `StatusBarManagerService`（`services/core/java/com/android/server/statusbar/StatusBarManagerService.java`）                                                                     |
| SystemUI→system | `INotificationManager` | `NotificationManagerService`                                                                                                                                                  |
| SystemUI→system | `IWallpaperManager`    | `WallpaperManagerService`                                                                                                                                                     |
| WMS→SystemUI    | `IKeyguardService`     | `KeyguardService`（见 KeyguardService 详解）                                                                                                                                       |

`CommandQueue` 是 SystemUI 侧对 `IStatusBar` 回调的**统一收口**：Framework 通过 `StatusBarManagerService` 调 `IStatusBar` 的方法，最终都进 `CommandQueue` 的回调，再派发给 `CentralSurfacesImpl` 等。绝大多数字符串/图标/展开通知栏的命令都走这条线。

---

## 二、进程内架构：Dagger + CoreStartable 组件模型

SystemUI 内部用 **Dagger 2** 管理所有单例依赖，用 **`CoreStartable`** 统一组件生命周期。

### 2.1 三级 Dagger 组件树（已在启动流程详解中展开）

```
ReferenceGlobalRootComponent   (全局单例根：context、主线程、Looper)
        │
        ▼  + WMComponent (WindowManager 相关)
SysUIComponent                 (绝大多数 CoreStartable / Controller 的注入源)
        │
        ├─ @Provides / @Binds 大量 @Singleton 控制器
        └─ getSystemUIs() 返回 Map<Class<?>, CoreStartable>  (multibinding)
```

构建入口：`SystemUIInitializerImpl.init()` → `ReferenceSystemUIInitializer`。

### 2.2 CoreStartable：所有“开机自启组件”的统一接口

```java
// frameworks/base/packages/SystemUI/src/com/android/systemui/CoreStartable.java
public interface CoreStartable {
    void start();                       // 进程起来后、系统就绪前后被调用
    default void onBootCompleted() {}   // ACTION_BOOT_COMPLETED 后回调
}
```

新增一个随开机启动的组件，只需两步（**不用改任何启动列表**）：

1. 写 `class FooStartable implements CoreStartable { void start(){...} }`
2. 在 `frameworks/base/packages/SystemUI/src/com/android/systemui/dagger/SystemUICoreStartableModule.kt` 注册：
   ```kotlin
   @Binds @IntoMap @ClassKey(FooStartable::class)
   abstract fun bindFoo(s: FooStartable): CoreStartable
   ```
   Dagger 把实现收集进 `Map<Class<?>, CoreStartable>`，启动流程遍历这个 Map 逐个 `start()`。

> 这是 AOSP 12 把旧 `SystemUI` 基类（每个组件继承 `SystemUI` 并重写 `start()`）重构后的结果。现在几乎不再继承旧 `SystemUI` 基类，统一走 `CoreStartable`。

车载集成 CAN 数据展示 / 自定义车控面板，标准做法就是写一个 `CoreStartable`（见第七节）。

---

## 三、主要子系统模块详解

下面按“职责 → 核心类 → 真实路径 → 关键方法”列出 SystemUI 全部主要模块。

### 3.1 状态栏 + 通知面板（最核心）

目录：`frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/`

| 类                                         | 路径                                                                             | 职责                                                                            |
| ----------------------------------------- | ------------------------------------------------------------------------------ | ----------------------------------------------------------------------------- |
| `CentralSurfacesImpl`                     | `statusbar/phone/CentralSurfacesImpl.java`                                     | **状态栏总控制器**（旧 `PhoneStatusBar`）。持有 status bar window、收 CommandQueue 命令、管展开/收起 |
| `StatusBarWindowController`               | `statusbar/phone/StatusBarWindowController.java`                               | 管理状态栏 Window（`TYPE_STATUS_BAR`）的添加/显隐/flag                                    |
| `PhoneStatusBarViewController`            | `statusbar/phone/PhoneStatusBarViewController.java`                            | 状态栏 View 的控制器                                                                 |
| `NotificationPanelViewController`         | `statusbar/phone/NotificationPanelViewController.java`                         | 通知面板（下拉后的 shade）手势/展开逻辑                                                       |
| `StatusBarIconController` / `IconManager` | `statusbar/phone/StatusBarIconController.java` + `StatusBarIconControllerImpl` | 状态栏图标（信号/电量/蓝牙等），经 `IIconManager` Binder 与 system_server 通信                   |
| `StatusBarKeyguardViewManager`            | `statusbar/phone/StatusBarKeyguardViewManager.java`                            | 状态栏与锁屏 View 的协调（何时显示 bouncer）                                                 |
| `StatusBarTouchableRegionManager`         | `statusbar/phone/StatusBarTouchableRegionManager.java`                         | 状态栏可触摸区域（权限 `ALLOW_SLIPPERY_TOUCHES`）                                         |

状态栏图标数据来源（不在这目录，是各 `Controller`）：

- `NetworkController`（`statusbar/policy/NetworkController.java`）→ 信号/运营商
- `BatteryController`（`statusbar/policy/BatteryController.java`）→ 电量
- `BluetoothController`（`statusbar/policy/BluetoothController.java`）

### 3.2 通知子系统

目录：`frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/notification/`

| 类                                         | 路径                                                                          | 职责                                                                                         |
| ----------------------------------------- | --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| `NotificationEntryManager`                | `statusbar/notification/NotificationEntryManager.java`                      | 通知条目管理（增删改、排序）                                                                             |
| `NotificationListener`                    | `statusbar/NotificationListener.java`                                       | **继承 `NotificationListenerService`**，是 SystemUI 与 `NotificationManagerService` 的桥梁（监听通知增删） |
| `NotificationStackScrollLayoutController` | `statusbar/notification/stack/NotificationStackScrollLayoutController.java` | 通知列表滚动/布局                                                                                  |
| `ExpandableNotificationRow`               | `statusbar/notification/row/ExpandableNotificationRow.java`                 | 单条通知 View                                                                                  |
| `NotificationGutsManager`                 | `statusbar/notification/NotificationGutsManager.java`                       | 通知长按菜单（阻塞/静音）                                                                              |

### 3.3 快捷设置 QS（Quick Settings）

目录：`frameworks/base/packages/SystemUI/src/com/android/systemui/qs/`

| 类                          | 路径                            | 职责                                                                |
| -------------------------- | ----------------------------- | ----------------------------------------------------------------- |
| `QSFragment`               | `qs/QSFragment.java`          | QS 面板 Fragment                                                    |
| `QSPanel` / `QuickQSPanel` | `qs/QSPanel.java`             | 完整 QS 面板 / 下拉首屏快捷区                                                |
| `QSFactoryImpl`            | `qs/QSFactoryImpl.java`       | Tile 工厂，按类名创建 Tile 实例                                             |
| `QSTileImpl`               | `qs/tileimpl/QSTileImpl.java` | 所有 Tile 的基类                                                       |
| `tiles/*`                  | `qs/tiles/`                   | 各具体 Tile（WifiTile、BluetoothTile、FlashlightTile、NightDisplayTile…） |
| `QSTileFactories`          | `qs/QSTileFactories.java`     | Tile 注册表（类名→Tile 类）                                               |

**新增 Tile 三处必改**（详见修改指南）：

1. `qs/tiles/MyTile.java` 新建实现
2. `qs/QSTileFactories.java` 或 `QSFactoryImpl` 注册类名映射
3. `res/values/config.xml` 的 `config_quickSettingsTiles` / `quick_settings_tiles_default` 声明

### 3.4 导航栏

目录：`frameworks/base/packages/SystemUI/src/com/android/systemui/navigationbar/`

| 类                          | 路径                                           | 职责                                        |
| -------------------------- | -------------------------------------------- | ----------------------------------------- |
| `NavigationBarController`  | `navigationbar/NavigationBarController.java` | 管理各 display 的导航栏                          |
| `NavigationBarView`        | `navigationbar/NavigationBarView.java`       | 导航栏 View                                  |
| `NavigationBar` (Fragment) | `navigationbar/NavigationBar.java`           | 导航栏 Fragment                              |
| 按钮/模式                      | `navigationbar/buttons/`                     | 返回/Home/最近任务按钮；3-button / 2-button / 手势模式 |

模式切换：`NavigationModeController`（`navigationbar/NavigationModeController.java`），由 `WindowManager` 的 `NavigationMode` 决定。

### 3.5 锁屏（已单篇详解）

- Binder 桩：`keyguard/KeyguardService.java`（详见 `KeyguardService详解_AOSP14.md`）
- 真正状态机：`keyguard/KeyguardViewMediator.java`
- 状态监听：`keyguard/KeyguardUpdateMonitor.java`
- UI 层（独立包名 `com.android.keyguard`）：`src/com/android/keyguard/KeyguardSecurityContainerController.java`、`KeyguardClockSwitch.java`、`KeyguardPasswordView` 等
- 协调：`statusbar/phone/StatusBarKeyguardViewManager.java`

### 3.6 概览 / 多任务（Recents）

⚠️ **AOSP 12+ 多任务已迁出 SystemUI**，移到 `com.android.wm.shell`（Shell 进程）：

- 源码：`frameworks/base/libs/WindowManager/Shell/`
- 通过 `ShellTaskOrganizer` 由 WMS 驱动
- SystemUI 侧只做“概览按钮触发”和结果展示（`statusbar/phone` 里调起 `IOverviewProxy`）

### 3.7 音量面板

- `volume/VolumeDialogImpl.java`：音量弹窗
- `volume/VolumePanelFactory.java`：工厂
- 广播入口：`VolumePanelDialogReceiver`（Manifest 已注册）

### 3.8 截屏

- `screenshot/ScreenshotController.java`：核心控制
- `screenshot/TakeScreenshotService.java`：独立进程 `:screenshot`（Manifest 已注册，permission SELF）
- 长截屏：`screenshot/LongScreenshotActivity`

### 3.9 亮度

- `settings/brightness/BrightnessDialog.java`（Manifest 已注册，action `SHOW_BRIGHTNESS_DIALOG`）
- 受 `CONTROL_DISPLAY_BRIGHTNESS` 权限保护

### 3.10 Doze（息屏显示/AOD）

- `doze/DozeService.java`：继承 `DreamService`（Manifest `BIND_DREAM_SERVICE`）
- `doze/DozeMachine.java`：状态机
- `doze/DozeTriggers.java`：唤醒触发（抬手/通知）

### 3.11 其他重要模块

| 模块            | 目录                                      | 说明                                 |
| ------------- | --------------------------------------- | ---------------------------------- |
| Pip（画中画）      | 实际在 `com.android.wm.shell`，SystemUI 桥接  | 同 Recents，迁到 Shell                 |
| Bubbles（对话气泡） | `statusbar/bubbles/`                    | 通知气泡                               |
| Monet 主题引擎    | `monet/`                                | Material You 取色/配色                 |
| 无障碍           | `accessibility/`                        | 触控/放大                              |
| 折叠屏           | `unfold/`                               | 展开/合拢适配                            |
| 车机特化          | `car/`（`src/com/android/systemui/car/`） | 仅 AAOS 构建；标准 SystemUI 不包含          |
| 插件 API        | `plugin/`                               | `Plugin` 接口，第三方可注入（SELF/PLUGIN 权限） |

---

## 四、视图层级与 Window 类型

SystemUI 添加的窗口都带 `INTERNAL_SYSTEM_WINDOW` 权限（Manifest 已声明），类型如下：

| Window Type                 | 用途         | 添加方                               |
| --------------------------- | ---------- | --------------------------------- |
| `TYPE_STATUS_BAR`           | 状态栏        | `StatusBarWindowController`       |
| `TYPE_NAVIGATION_BAR`       | 导航栏        | `NavigationBarController`         |
| `TYPE_STATUS_BAR_PANEL`     | 下拉通知面板     | `NotificationPanelViewController` |
| `TYPE_STATUS_BAR_SUB_PANEL` | QS 详情/弹窗   | QS                                |
| `TYPE_NAVIGATION_BAR_PANEL` | 导航栏相关弹层    | NavigationBar                     |
| `TYPE_VOLUME_OVERLAY`       | 音量条        | `VolumeDialogImpl`                |
| `TYPE_KEYGUARD_DIALOG`      | 锁屏弹窗       | Keyguard                          |
| `TYPE_STATUS_BAR_ADDITION`  | 状态栏附加（如搜索） | —                                 |

> 这些 Window 的层级由 `WindowManagerPolicy`（PhoneWindowManager）控制，确保始终在应用之上、但在输入法/壁纸之下（依类型而定）。

---

## 五、控制器（*Controller）模式

SystemUI 大量使用 `XXXController` 类，经 Dagger 注入，监听系统状态变化并向 UI 推送：

- `BatteryController`、`NetworkController`、`BluetoothController`（状态栏图标源）
- `UserInfoController`、`ManagedProfileController`
- `AccessibilityController`、`MagnificationController`
- `RotationLockController`、`CastController`

它们通常：`@Inject` 构造 → 注册到对应系统服务监听（Broadcast/Callback）→ 维护状态 → 通过 `@Callback` 接口通知订阅者。想加一个“状态栏显示某车载数据”的组件，标准做法就是写一个 `CarXxxController` + 对应 UI。

---

## 六、关键 Binder 接口速查（Framework 侧）

| AIDL                   | 路径                                                                                | 作用                                                    |
| ---------------------- | --------------------------------------------------------------------------------- | ----------------------------------------------------- |
| `IStatusBar`           | `frameworks/base/core/java/com/android/internal/statusbar/IStatusBar.aidl`        | system→SystemUI 命令通道（经 CommandQueue）                  |
| `IStatusBarService`    | `frameworks/base/core/java/com/android/internal/statusbar/IStatusBarService.aidl` | SystemUI→system（collapsePanels/expandNotifications 等） |
| `IIconManager`         | `frameworks/base/core/java/com/android/internal/statusbar/IIconManager.aidl`      | 状态栏图标管理                                               |
| `IKeyguardService`     | `frameworks/base/core/java/com/android/internal/policy/IKeyguardService.aidl`     | WMS→KeyguardService                                   |
| `INotificationManager` | `frameworks/base/core/java/android/app/INotificationManager.aidl`                 | 通知                                                    |
| `IWallpaperManager`    | `frameworks/base/core/java/android/app/IWallpaperManager.aidl`                    | 壁纸                                                    |

---

## 七、车载（本项目）定制切入点速查

结合项目背景（车载/工控 Android、Treble 隔离），推荐改法：

| 需求          | 推荐做法                                                                       | 改哪里                                                  |
| ----------- | -------------------------------------------------------------------------- | ---------------------------------------------------- |
| 隐藏/精简状态栏    | 资源 overlay 设 `config_statusBarDisable` / 隐藏 `StatusBarWindowController`    | `device/<vendor>/overlay/` 或 `res/values/config.xml` |
| 自定义导航栏      | overlay 改 `config_navBarLayout` / 改模式                                      | `navigationbar/` + overlay                           |
| 禁用/跳过锁屏     | `config_disableLockscreen` + `KeyguardViewMediator` + 自定义 `TrustAgent`     | **不要**在 KeyguardService 里加 dismiss hack              |
| 加车控 QS Tile | 写 `CarXxxTile` + 注册 + config 声明                                            | `qs/tiles/` + `config.xml`                           |
| 显示 CAN/车机数据 | 写 `CarXxxStartable implements CoreStartable` + Dagger `@IntoMap` 注册        | `SystemUICoreStartableModule.kt`                     |
| 厂商扩展点       | 用官方 `CUSTOMIZE_SYSTEM_UI` 权限 + `CustomizationProvider`                     | 优先于改核心代码                                             |
| AAOS 整车 UI  | 用 `packages/services/Car` 的 `CarSystemUI`（`src/com/android/systemui/car/`） | 独立构建                                                 |

**Treble 隔离提醒**：Framework 改动进 `system` 分区，驱动/DTS 进 `vendor` 分区。SystemUI 属于 `system`，随 `system.img` 编。

**`directBootAware` 铁律**：锁屏/状态栏在用户解锁前就要工作，相关组件必须保留 `directBootAware=true`（Application 已全局设 `defaultToDeviceProtectedStorage`）。

---

## 八、核心类/方法速查表（一页纸）

| 你想改的东西       | 先读的类                                                | 路径                                                             |
| ------------ | --------------------------------------------------- | -------------------------------------------------------------- |
| 状态栏整体行为      | `CentralSurfacesImpl`                               | `statusbar/phone/CentralSurfacesImpl.java`                     |
| 下拉通知面板       | `NotificationPanelViewController`                   | `statusbar/phone/NotificationPanelViewController.java`         |
| 状态栏图标        | `StatusBarIconControllerImpl` / `IconManager`       | `statusbar/phone/`                                             |
| 通知增删         | `NotificationListener` / `NotificationEntryManager` | `statusbar/`                                                   |
| 加 QS 开关      | `QSTileImpl` + `QSFactoryImpl`                      | `qs/`                                                          |
| 导航栏按钮        | `NavigationBarView`                                 | `navigationbar/NavigationBarView.java`                         |
| 锁屏状态机        | `KeyguardViewMediator`                              | `keyguard/KeyguardViewMediator.java`                           |
| 锁屏 Binder 入口 | `KeyguardService`                                   | `keyguard/KeyguardService.java`                                |
| 音量弹窗         | `VolumeDialogImpl`                                  | `volume/VolumeDialogImpl.java`                                 |
| 截屏           | `ScreenshotController`                              | `screenshot/ScreenshotController.java`                         |
| 新增自启组件       | `CoreStartable` + `SystemUICoreStartableModule`     | `CoreStartable.java` + `dagger/SystemUICoreStartableModule.kt` |
| 启动点火         | `SystemUIApplication` / `SystemUIService`           | 根目录                                                            |
| Dagger 根     | `SystemUIInitializerImpl`                           | `SystemUIInitializerImpl.java`                                 |

---

## 九、下一步可深挖的方向

1. `StatusBarKeyguardViewManager` —— 状态栏与锁屏 View 的实时切换（解锁动画衔接）
2. `NotificationListener` —— 通知如何从 `NotificationManagerService` 流到 SystemUI
3. `CentralSurfacesImpl.start()` —— 状态栏 Window 创建全过程
4. `CommandQueue` —— Framework→SystemUI 命令收口细节
5. 某个具体 `CoreStartable`（如 `StatusBarStartable`）的 `start()` 内部

告诉我哪个模块要逐行注释，我直接拉该文件真实源码批注。
