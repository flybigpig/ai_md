# SystemUI AndroidManifest.xml 分析（AOSP 14 / android-14.0.0_r1）

> 源文件：`frameworks/base/packages/SystemUI/AndroidManifest.xml`
> 仓库：`https://cnb.cool/flybigpig/aosp` @ `main`
> 分析日期：2026-08-04

---

## 核心结论

这份 manifest 是**标准 AOSP 14 原版**，未被修改（仓库 commit `ccea5b58` "ci: Initial AOSP commit"）。它定义了一个**专用 UID 的特权核心应用**，通过自定义 `Application` + `AppComponentFactory` 做 Dagger 依赖注入，并用**多进程架构**隔离截屏/Tuner 等模块。关键信号：清单内已内置 `android.car.*` 车载权限，说明该 build 天然包含 AAOS 能力，可直接做空调/车载音量控制。

---

## 一、身份与进程模型（manifest 根标签）

```xml
<manifest package="com.android.systemui"
         android:sharedUserId="android.uid.systemui"
         coreApp="true"
         ...>
```

| 属性 | 值 | 含义 / 对你的意义 |
|------|----|------------------|
| `package` | `com.android.systemui` | APK 包名，也是 Dagger 注入、组件类名解析的命名空间 |
| `sharedUserId` | `android.uid.systemui` | **专用 UID（`android.uid.systemui`）**，不是 `android.uid.system`(platform)。SystemUI 与 `system_server` 不同 UID，跨进程走 Binder 而非同进程调用 |
| `coreApp` | `true` | 标记为系统核心应用，参与早期启动，禁止被普通卸载/停用 |

> **注意点：** `sharedUserId="android.uid.systemui"` 意味着 SystemUI 拥有独立系统 UID，但仍用 **platform 签名**（`privileged + signature` 级权限才能授予）。它和 `system_server`（uid 1000）是**两个进程、两个 UID**，相互通信必须走 Binder（例如 `KeyguardService`、`StatusBarManagerService`）。

---

## 二、Application 关键属性（注入与启动入口）

```xml
<application
    android:name=".SystemUIApplication"
    android:persistent="true"
    android:defaultToDeviceProtectedStorage="true"
    android:directBootAware="true"
    android:process="com.android.systemui"
    android:appComponentFactory=".SystemUIAppComponentFactory"
    tools:replace="android:appComponentFactory"
    ... />
```

| 属性 | 含义 / 对你的意义 |
|------|------------------|
| `android:name=".SystemUIApplication"` | 自定义 `Application`。**Dagger 根组件初始化、CoreStartable 遍历启动都在这里**（`onCreate()`）。改 SystemUI 初始化逻辑优先看这里 |
| `android:persistent="true"` | 永不被 LowMemoryKiller 回收，AMS 保证其常驻。崩溃后由 AMS 自动拉起 |
| `defaultToDeviceProtectedStorage` + `directBootAware` | **Direct Boot 模式可用**——用户未解锁前就必须跑（锁屏、状态栏、紧急呼叫）。任何需要在解锁前工作的 Service/Receiver 必须标 `directBootAware` |
| `android:process="com.android.systemui"` | 主进程名 |
| `appComponentFactory=".SystemUIAppComponentFactory"` + `tools:replace` | **自定义组件工厂**，是 SystemUI 在 `Application`/`Activity`/`Service` 实例化前做早期注入的钩子（配合 Dagger）。`tools:replace` 强制覆盖 build 系统默认的工厂 |

> **修改启示：** 想在组件创建最早期插手（先于 `onCreate`），可扩展 `SystemUIAppComponentFactory`；想在启动时注册全局组件，加到 `SystemUIApplication` 的 CoreStartable 列表。

---

## 三、多进程架构（进程拆分）

SystemUI 主进程之外，多个独立进程用于**隔离崩溃/内存压力**：

| 进程 | 组件 | 作用 |
|------|------|------|
| `com.android.systemui`（主） | SystemUIService、KeyguardService、各种 Dialog Activity | 核心 UI |
| `:screenshot` | `TakeScreenshotService`、`ScreenshotServiceErrorReceiver`、`LongScreenshotActivity` | 截屏（独立进程，截屏崩溃不影响主进程） |
| `:tuner` | `TunerActivity`、`DemoMode`(activity-alias) | 系统 UI 调谐器 / Demo 模式 |
| `:sweetsweetdesserts` | `DessertCase`、`DessertCaseDream` | Android 版本彩蛋 |
| `:fgservices` | `ForegroundServicesDialog` | 前台服务管理对话框 |
| `:appclips.screenshot` | `AppClipsActivity` | 截图标注 |

> **修改启示：** 新增高崩溃风险或内存重的模块，建议放独立进程（仿 `:screenshot` 写法：`android:process=":xxx"`）。

---

## 四、权限体系（按职责分类）

### 4.1 系统核心 / 状态栏 / 窗口（最关键）
- `STATUS_BAR_SERVICE` / `STATUS_BAR` / `EXPAND_STATUS_BAR` —— 状态栏控制
- `INTERNAL_SYSTEM_WINDOW` / `SYSTEM_ALERT_WINDOW` —— 添加系统级窗口（状态栏/导航栏/锁屏本质都是系统窗口）
- `READ_FRAME_BUFFER` / `MONITOR_INPUT` / `INPUT_CONSUMER` —— 截屏、输入监听
- `INJECT_EVENTS` / `MODIFY_TOUCH_MODE_STATE` —— 注入输入事件

### 4.2 ActivityManager（跨用户/任务栈）
- `REAL_GET_TASKS` / `GET_DETAILED_TASKS` / `REORDER_TASKS` / `REMOVE_TASKS`
- `INTERACT_ACROSS_USERS` / `INTERACT_ACROSS_USERS_FULL` —— 多用户交互（SystemUI 需操作其他用户进程）
- `START_ANY_ACTIVITY` / `START_ACTIVITIES_FROM_BACKGROUND`

### 4.3 Keyguard / 生物识别
- `CONTROL_KEYGUARD` / `DISABLE_KEYGUARD` —— 锁屏控制
- `USE_BIOMETRIC_INTERNAL` / `MANAGE_BIOMETRIC` / `TRUST_LISTENER` —— 生物识别/信任代理

### 4.4 ⭐ 车载权限（与你的场景直接相关）
```xml
<uses-permission android:name="android.car.permission.CONTROL_CAR_CLIMATE" />
<uses-permission android:name="android.car.permission.CAR_CONTROL_AUDIO_VOLUME" />
```
- 这两项是 **AAOS（Android Automotive）** 权限。标准手机 SystemUI 清单里没有，它们出现在这里说明该 build 包含车载能力。
- 含义：SystemUI 可直接控制**车载空调（HVAC）**和**车载音频音量**，无需再走额外桥接。车载定制（如状态栏加空调快捷入口）可直接调用，无需新增权限。

### 4.5 媒体 / 截屏 / 录屏
- `MANAGE_MEDIA_PROJECTION` / `RECORD_AUDIO` / `CAPTURE_AUDIO_OUTPUT`
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` —— 录屏前台服务豁免

### 4.6 网络 / 电话 / 蓝牙（略，标准 SystemUI 所需）

---

## 五、自定义权限（签名级，官方扩展点）

| 权限 | protectionLevel | 作用 |
|------|-----------------|------|
| `com.android.systemui.permission.SELF` | `signature` | 内部组件互相调用的保护（如 `SystemUIAuxiliaryDumpService`、`TakeScreenshotService` 都要求此权限） |
| `com.android.systemui.permission.PLUGIN` | `signature` | **插件机制**入口，允许同签名插件扩展 SystemUI |
| `com.android.systemui.permission.FLAGS` | `signature` | Flag 管理（配合 AConfig 特性开关） |
| `android.permission.CUSTOMIZE_SYSTEM_UI` | `signature\|privileged` | **厂商定制官方入口**，配合 `CustomizationProvider` |

> **修改启示：** 你做车载定制时，若要从外部 App/服务调用 SystemUI 内部能力，优先用 `CUSTOMIZE_SYSTEM_UI` 或自定义 `signature` 权限，而不是放宽 `exported`。

---

## 六、关键组件

### 6.1 启动入口
```xml
<service android:name="SystemUIService" android:exported="true" />
```
- **开机入口 Service**。由 `ActivityManagerService.startSystemUi()` 拉起，进而触发 `SystemUIApplication` 启动所有 `CoreStartable`。这是整个 SystemUI 的"点火点"。

```xml
<service android:name="SystemUISecondaryUserService" android:exported="false"
         android:permission="com.android.systemui.permission.SELF" />
```
- 多用户场景下，每个用户一个 SystemUI 辅助进程。

### 6.2 锁屏
```xml
<service android:name=".keyguard.KeyguardService" android:exported="true" />
```
- `WindowManagerService` 通过它与 SystemUI 通信控制锁屏显示/隐藏。锁屏修改（场景 4）最终都绕不开这个 Service 的状态机。

### 6.3 截屏（独立进程）
```xml
<service android:name=".screenshot.TakeScreenshotService"
         android:permission="com.android.systemui.permission.SELF"
         android:process=":screenshot" android:exported="false" />
```
- 由 `PhoneWindowManager` 调用，独立进程保护。

### 6.4 调谐器 / Demo 模式
```xml
<activity android:name=".tuner.TunerActivity" android:enabled="false" ... />
<activity-alias android:name=".DemoMode" android:targetActivity=".tuner.TunerActivity" ... />
```
- 默认 `enabled=false`，需 adb 手动开启。车载设备通常应**保持关闭**（避免被误改系统 UI）。

### 6.5 系统对话框 Activity（从各 Manager 拉起）
清单内大量 `android:permission="android.permission.MANAGE_XXX"` 的 Activity，本质都是**系统级确认/错误对话框**，由对应服务拉起：
- USB 相关：`UsbConfirmActivity` / `UsbPermissionActivity` / `UsbDebuggingActivity` / `WifiDebuggingActivity`
- HDMI CEC：`HdmiCecSetMenuLanguageActivity`
- 传感器隐私：`SensorUseStartedActivity`
- 媒体投影：`MediaProjectionPermissionActivity`
- 网络超额：`NetworkOverLimitActivity`

> **修改启示：** 车载设备若不需要这些交互（如 USB 调试弹窗、网络超额提示），可在定制的 overlay/产品配置里 `android:enabled="false"` 对应组件，或接管其逻辑。

---

## 七、ContentProvider

| Provider | authorities | 作用 |
|----------|-------------|------|
| `androidx.core.content.FileProvider` | `com.android.systemui.fileprovider` | 文件共享（截屏分享等） |
| `.keyguard.KeyguardSliceProvider` | `com.android.systemui.keyguard` | 锁屏切片（时钟/日期） |
| `.keyguard.CustomizationProvider` | `com.android.systemui.customization` | **厂商定制数据**，要求 `CUSTOMIZE_SYSTEM_UI` 权限 |
| `com.android.keyguard.clock.ClockOptionsProvider` | `com.android.keyguard.clock` | 锁屏时钟选项（`enabled=false`） |

---

## 八、protected-broadcast 与 queries

```xml
<protected-broadcast android:name="com.android.systemui.STARTED" />
<protected-broadcast android:name="com.android.settingslib.action.REGISTER_SLICE_RECEIVER" />
...
<queries>
    <intent><action android:name="android.intent.action.CREATE_NOTE" /></intent>
</queries>
```

- **5 个 protected-broadcast**：声明这些广播只有系统（签名级）能发送，防止第三方伪造（如 `com.android.systemui.STARTED` 启动完成广播）。
- **queries**：显式声明 SystemUI 能感知 `CREATE_NOTE` intent（Android 11+ 包可见性限制要求）。

---

## 九、对你的车载定制修改的启示（速查）

1. **加新组件**：仿照现有 `<service>/<activity>` 写法，注意 `exported`、`permission`（内部用 `SELF`）、必要时的 `process` 隔离。
2. **车载能力已就绪**：`CONTROL_CAR_CLIMATE` / `CAR_CONTROL_AUDIO_VOLUME` 已声明，可直接做空调/音量快捷控制，无需补权限。
3. **官方定制入口**：`CUSTOMIZE_SYSTEM_UI` 权限 + `CustomizationProvider` 是厂商定制的标准扩展点，优先用它而非改核心代码。
4. **启动链路**：改初始化逻辑看 `SystemUIApplication` + `SystemUIAppComponentFactory`；新增常驻组件走 `CoreStartable` + Dagger `@IntoSet`。
5. **关闭不必要的系统弹窗**：USB/HDMI/网络超额等 Activity 在车载场景多数无意义，可在产品 overlay 中 `enabled=false`。
6. **Direct Boot 注意**：任何解锁前需工作的组件必须 `directBootAware="true"`，否则用户未解锁时功能失效。
7. **图标**：`android:icon="@drawable/android14_patch_adaptive"` 是标准 AOSP 14 应用图标（Android 14 雕塑/补丁 logo），非定制点；如需改 SystemUI 自身图标在此替换。

---

> 文档基于仓库 `main` 分支当前 manifest 内容生成。若后续在 `src-release` 等目录有修改，需重新核对。
