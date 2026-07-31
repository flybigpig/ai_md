# Android Framework 热点面试题（第十篇）
## 兼容性框架主线 × Android 17 跨设备 / 窗口 / 输入 / 隐私新雷区

> 整理日期：**2026-07-31**
> 目标版本基线：**AOSP Android 14（UpsideDownCake, API 34）** 源码路径 + **Android 17（API 37, CinnamonBun, 2026-06-16 stable）** 行为变更
> 本篇定位：前九篇累计 85 个专题，本篇补 **10 个真缺口**，其中第 1 章「应用兼容性框架 platform_compat」是贯穿**所有 targetSdk 行为变更**的底层机制主线——前九篇一直在用它的"结果"，却从没拆过它的"引擎"。

---

## 目录

| # | 专题 | 层级 | 热度 |
|---|------|------|------|
| 1 | 应用兼容性框架 `CompatChanges` / `platform_compat` 全链路 | Framework 核心机制 | ★★★★★ |
| 2 | A17 大屏强制 resizable：WMS letterbox 与尺寸兼容全链路 | WMS | ★★★★★ |
| 3 | A17 BAL 后台 Activity 启动加固与 `IntentSender` 收口 | AMS/ATMS | ★★★★★ |
| 4 | A17 Bubbles 浮窗：新 windowing mode 与 SystemUI 协同 | WMS/SystemUI | ★★★★ |
| 5 | Handoff API / Continue On 跨设备接力全链路 | CDM/跨设备 | ★★★★ |
| 6 | Input 深水区：Pointer Capture 相对/绝对模式与触控板归一化 | Input | ★★★★ |
| 7 | Telephony：SMS OTP 三小时延迟拦截机制 | Telephony/Provider | ★★★★ |
| 8 | 网络栈：ECH 加密 ClientHello 与 `ACCESS_LOCAL_NETWORK` | Conscrypt/netd | ★★★★ |
| 9 | ContentProvider 深水区：CP2 PII 列裁剪与 Strict SQL 校验 | Provider/SQLite | ★★★ |
| 10 | ART hiddenapi 名单生成流水线（补前篇欠账） | ART/构建系统 | ★★★★ |
| + | 查缺补漏 / 易错点速记 / 十篇交叉索引 | — | — |

---

# 1. 应用兼容性框架：`CompatChanges` / `platform_compat` 全链路

> **这是本篇的主轴。** 面试里凡是问到"Android 某版本行为变更为什么只对 targetSdk ≥ N 的应用生效？系统怎么做到同一份代码对不同应用表现不同？"——考的就是这套框架。前九篇讲的 16KB 页面、BAL、大屏 resizable、DCL 加固、static final 不可变，**全部**跑在这条链路上。

### Q1.1 系统如何实现"同一份 Framework 代码，对 targetSdk 不同的应用表现不同"？

**答案分层解析：**

**（1）声明层：`@ChangeId` 注解**

每一个行为变更都是一个 **long 型常量 ID**（通常是 Google 内部 bug 号），用注解声明生效条件：

```java
// 典型写法（frameworks/base 各处）
@ChangeId
@EnabledSince(targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
static final long ENFORCE_SOMETHING = 254631730L;
```

关键注解（`frameworks/base/core/java/android/compat/annotation/`）：

| 注解 | 语义 |
|------|------|
| `@ChangeId` | 标记这是一个兼容性变更 ID |
| `@EnabledSince(targetSdkVersion=X)` | targetSdk ≥ X 时**默认开启**（可被 override 关闭） |
| `@Disabled` | 默认关闭，仅供调试/灰度开启 |
| `@LoggingOnly` | 只打点不改行为，用于变更前的影响面评估 |
| `@Overridable` | 允许通过 `am compat` 或 device config 覆盖 |

**（2）判定层：`CompatChanges.isChangeEnabled()`**

```java
// frameworks/base/core/java/android/app/compat/CompatChanges.java
public static boolean isChangeEnabled(long changeId) {
    return QUERY_CACHE.query(changeId);      // 进程内查询，走缓存
}
public static boolean isChangeEnabled(long changeId, String packageName, UserHandle user)
public static boolean isChangeEnabled(long changeId, int uid)
```

进程内版本（无 packageName）由 **Zygote fork 后注入的 `AppCompatCallbacks`** 处理：

```java
// frameworks/base/core/java/android/app/AppCompatCallbacks.java
// 由 ActivityThread.handleBindApplication() 调用 install(disabledChanges, loggableChanges)
// 底层 native: libcore/luni/src/main/java/dalvik/system/VMRuntime.java
//              art/runtime/native/dalvik_system_VMRuntime.cc
//              → Runtime::SetDisabledCompatChanges()
```

**这里是最容易被忽略的关键点：** 应用进程启动时，`system_server` 会把该应用**被禁用的 changeId 集合**一次性打包传给进程，存进 ART Runtime 的 `std::set<uint64_t> disabled_compat_changes_`。之后进程内每次 `isChangeEnabled` 只是查这个集合，**不走 Binder**，所以性能开销接近 0。

**（3）服务层：`PlatformCompat` 系统服务**

```
frameworks/base/services/core/java/com/android/server/compat/
├── PlatformCompat.java            // IPlatformCompat AIDL 实现，Binder 服务
├── CompatConfig.java              // 加载 compat_config XML，维护所有 ChangeId 元信息
├── CompatChange.java              // 单个变更：enableSinceTargetSdk / disabled / overrides
├── OverrideValidatorImpl.java     // 校验 override 是否允许（debuggable? 是否 @Overridable?）
└── CompatChangeReporter.java      // 打点上报（statsd）
```

核心判定逻辑在 `CompatConfig.isChangeEnabled(long changeId, ApplicationInfo app)`：

```
1. 查 mChanges 里是否有该 changeId
   → 没有：默认 enabled（未知变更按开启处理，防止漏配导致老行为泄漏）
2. 有 packageName override → 直接返回 override 值
3. change.getDisabled() == true → false
4. change.getEnableSinceTargetSdk() > 0
   → return app.targetSdkVersion >= enableSinceTargetSdk
5. 否则 → true
```

**（4）数据层：编译期生成的 compat_config**

`@ChangeId` 注解由 **annotation processor** 在编译期扫描，生成 XML 落到设备：

```
tools/platform-compat/                        # 注解处理器与工具
/system/etc/compatconfig/*.xml                # 生成产物
  例：services-core-platform-compat-config.xml
```

XML 形如：

```xml
<compat-config>
  <compat-change id="254631730" name="ENFORCE_SOMETHING"
                 enableSinceTargetSdk="34" overridable="true">
    <meta-data definedIn="com.android.server.Foo" sourcePosition="..."/>
  </compat-change>
</compat-config>
```

`CompatConfig.create()` 在开机时读取 `/system/etc/compatconfig/`、`/product/etc/compatconfig/` 等目录合并。

### Q1.2 调试与灰度：怎么在不改 targetSdk 的情况下验证行为变更？

```bash
# 列出某应用所有变更状态
adb shell dumpsys platform_compat

# 强制开启 / 关闭（应用需 debuggable，或 userdebug/eng 版本）
adb shell am compat enable  ENFORCE_SOMETHING com.example.app
adb shell am compat disable 254631730          com.example.app
adb shell am compat reset-all com.example.app

# 通过 targetSdkPreview 提前适配
android { defaultConfig { targetSdkPreview = "CinnamonBun" } }
```

**A17 强相关：** 大屏 resizable 那条变更（`UNIVERSAL_RESIZABLE_BY_DEFAULT`）在 A16 上是 `@Overridable`，应用可以通过 manifest 的 `PROPERTY_COMPAT_ALLOW_*` 退出；**A17 把 overridable 拿掉了**——这正是"退出选项消失"在框架层的真实含义：不是 API 删了，而是 `OverrideValidatorImpl` 不再放行该 changeId 的 override。

### 🔥 面试高频追问

1. **"未知 changeId 默认开还是关？为什么？"**
   默认 **开**。因为 changeId 未在 config 中注册通常意味着编译配置缺失，此时应保证走"新行为"，避免旧兼容分支被静默保留成永久技术债。

2. **"isChangeEnabled 会不会每次都 Binder 调 system_server？"**
   进程内单参数版本 **不会**——禁用集合在 `handleBindApplication` 阶段已注入 ART Runtime。但带 `packageName`/`uid` 的重载**会**走 Binder（查别的应用），所以不要在热路径上用带包名的重载。

3. **"应用能不能自己伪造 targetSdk 绕过？"**
   不能。`ApplicationInfo.targetSdkVersion` 来自 `PackageManagerService` 解析 APK 时读的 `AndroidManifest.xml`（`frameworks/base/services/core/java/com/android/server/pm/parsing/`），由系统持有，应用进程内修改无效——判定发生在 `system_server` 侧或用 system_server 下发的集合。

4. **"@LoggingOnly 有什么用？"**
   Google 的标准做法：新变更先以 LoggingOnly 上线一两个版本，通过 statsd 收集有多少应用会被打断，再决定是否转 `@EnabledSince`。这是"行为变更"从提案到强制的完整生命周期。

### ⚠️ 易错点

- 把 `compileSdk` / `targetSdk` / `minSdk` 混为一谈：**只有 targetSdk 影响 compat 判定**。
- 以为 override 在 user 版本也能用：`OverrideValidatorImpl` 在 user build 上只允许 debuggable 应用被 override。
- 忘记 **Mainline 模块**（如 `art`、`media`）有自己的 compat config，OTA 时可能与 platform 版本不同步。

### 📖 延伸阅读
- `frameworks/base/services/core/java/com/android/server/compat/CompatConfig.java`
- `frameworks/base/core/java/android/compat/annotation/*.java`
- `adb shell dumpsys platform_compat` 输出结构

---

# 2. A17 大屏强制 resizable：WMS letterbox 与尺寸兼容全链路

### Q2.1 A17 上 `screenOrientation="portrait"` 在平板上为什么"失效"了？走的是哪条代码路径？

**答案：** 不是 manifest 解析被跳过，而是 **ATMS 在计算 activity 期望方向时，用 compat 框架把应用请求的 orientation "吞掉"了**。

链路（Android 14 路径，A17 在此基础上强化）：

```
Activity.setRequestedOrientation(int)
  → ActivityTaskManagerService.setRequestedOrientation()
  → ActivityRecord.setRequestedOrientation(int requestedOrientation)
      frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java
  → AppCompatOrientationPolicy / ActivityRecord#getOverrideOrientation()
      ↓ 判定：shouldIgnoreOrientationRequest()
  → DisplayContent.getIgnoreOrientationRequest()
      frameworks/base/services/core/java/com/android/server/wm/DisplayContent.java
      // 大屏（sw >= 600dp）设备上 ignoreOrientationRequest = true
  → 若忽略 → 返回 SCREEN_ORIENTATION_UNSPECIFIED
  → WindowOrientationListener / DisplayRotation 不再因该 activity 改变旋转
```

**尺寸兼容（Size Compat Mode / letterbox）侧：**

```
frameworks/base/services/core/java/com/android/server/wm/
├── ActivityRecord.java                 // inSizeCompatMode(), mSizeCompatBounds
├── AppCompatController.java            // A14 引入的 app compat 聚合入口
├── LetterboxUiController.java          // letterbox 背景、圆角、reachability
├── AppCompatAspectRatioOverrides.java  // min/maxAspectRatio 覆盖策略
└── SizeCompatTests / AppCompatConfiguration
```

关键判定：`ActivityRecord#shouldCreateAppCompatDisplayInsets()` 与 `resolveSizeCompatModeBounds()` —— 当 activity 不可 resize 且窗口尺寸变化超过阈值，系统冻结其 bounds 并做缩放（Size Compat Mode），加黑边（letterbox）。

**A17 的变化本质：** `UNIVERSAL_RESIZABLE_BY_DEFAULT` 这个 changeId 在 targetSdk 37 下 **不可 override**，因此：
- `resizeableActivity=false` 被忽略 → `ActivityInfo.resizeMode` 被强制视为 `RESIZE_MODE_RESIZEABLE`
- `minAspectRatio` / `maxAspectRatio` 被忽略 → 不进 size compat
- 结果：应用**必然**收到 `onConfigurationChanged` 或被重建

**例外**：`android:appCategory="game"` 的应用、`sw < 600dp` 的小屏设备。

### Q2.2 为什么"不能锁方向"最常炸的是相机预览？

因为相机预览的正确性依赖三个角度的叠加：

```
最终旋转 = (sensorOrientation - displayRotation * 90 + 360) % 360      // 后摄
         = (sensorOrientation + displayRotation * 90 + 360) % 360      // 前摄（镜像）
```

- `sensorOrientation`：`CameraCharacteristics.SENSOR_ORIENTATION`（HAL 上报，固定值）
- `displayRotation`：`Display.getRotation()`（会随窗口变化）

旧代码常把 `displayRotation` 当成"屏幕当前方向"缓存一次；A17 强制 resizable 后**窗口方向与设备方向解耦**（分屏、自由窗口、桌面模式下二者不等），缓存立刻失效 → 预览被拉伸/旋转 90°。

**正确做法优先级：** CameraX `PreviewView` > `CameraViewfinder` > 手动 Camera2 计算 + **用 `WindowMetricsCalculator` 而非 `Display.getRealSize()` 取尺寸** > Intent 调系统相机。

### 🔥 高频追问
1. **"Size Compat Mode 和 letterbox 是一回事吗？"**
   不是。Letterbox 是**视觉**上的黑边填充；Size Compat 是**逻辑**上冻结应用 bounds + 像素缩放。可以只 letterbox（应用能 resize 但宽高比受限）而不进 size compat。
2. **"`android:configChanges="orientation|screenSize"` 能规避重建，能规避强制 resizable 吗？"**
   能规避**重建**，不能规避**尺寸变化**。你仍会收到 `onConfigurationChanged`，布局必须自适应。
3. **"Compose 为什么被官方推为大屏首选？"**
   因为 Compose 的重组模型天然支持窗口尺寸变化只触发**重组**而非 Activity 重建，配合 `rememberSaveable` + `WindowSizeClass` 可无缝自适应。

### ⚠️ 易错点
- 用 `Resources.getConfiguration().orientation` 判断"横竖屏"做业务分支 —— 在自由窗口下毫无意义，应改用 `WindowSizeClass`。
- `fillMaxWidth()` / `match_parent` 在超宽窗口拉成畸形；应加 `widthIn(max = ...)`。
- 底部操作按钮在横屏平板被挤出屏幕 → 外层缺 `verticalScroll`。

### 📖 延伸阅读
`frameworks/base/services/core/java/com/android/server/wm/LetterboxUiController.java`、`AppCompatController.java`、`DisplayContent#setIgnoreOrientationRequest`

---

# 3. A17 BAL 后台 Activity 启动加固与 `IntentSender` 收口

### Q3.1 完整讲讲后台 Activity 启动（BAL）的判定链路

**核心类（Android 14 起独立出来）：**

```
frameworks/base/services/core/java/com/android/server/wm/
├── BackgroundActivityStartController.java     // A14 新增，BAL 判定总入口
├── ActivityStarter.java                        // execute() → executeRequest()
├── BackgroundLaunchProcessController.java      // 进程维度的可见性/豁免状态
└── ActivityTaskManagerService.java
```

判定入口：

```java
// ActivityStarter#executeRequest()
BalVerdict balVerdict = mController.getBackgroundActivityLaunchController()
        .checkBackgroundActivityStart(callingUid, callingPid, callingPackage,
                realCallingUid, realCallingPid, callerApp,
                originatingPendingIntent, backgroundStartPrivileges,
                intent, checkedOptions);
if (balVerdict.blocks()) { abort = true; }
```

**豁免条件（部分，`BackgroundActivityStartController` 内逐条 check）：**

| 类别 | 条件 |
|------|------|
| 可见性 | 调用方有可见窗口 / 是前台任务栈 top |
| 进程状态 | `ActivityManager.PROCESS_STATE_TOP` 或有 FGS 且满足 WIU |
| 权限 | `START_ACTIVITIES_FROM_BACKGROUND`、`SYSTEM_ALERT_WINDOW` |
| 身份 | 系统 UID、persistent 进程、device owner、companion app |
| 授权传递 | 最近收到过 `PendingIntent` 且 sender 授权了 BAL |
| 时间窗 | 调用方最近 `ACTIVITY_START_GRACE_PERIOD`（约 10s）内在前台 |

**（2）`IntentSender`/`PendingIntent` 侧的双方授权模型：**

```java
// ActivityOptions
setPendingIntentBackgroundActivityStartMode(int mode)        // sender 侧
setPendingIntentCreatorBackgroundActivityStartMode(int mode) // creator 侧
```

模式常量（`ActivityOptions`）：

```java
MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED   // 交给系统判
MODE_BACKGROUND_ACTIVITY_START_ALLOWED          // A17 起被弃用/收紧
MODE_BACKGROUND_ACTIVITY_START_DENIED
MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE // A15 引入，A17 力推
MODE_BACKGROUND_ACTIVITY_START_COMPAT
```

**A17 的核心变化：**
1. BAL 保护范围从 `PendingIntent` **扩展到 `IntentSender`**（此前 IntentSender 路径有绕过空间）。
2. 弃用无条件的 `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`，推荐迁到 `ALLOW_IF_VISIBLE`——把"允许后台启动"缩窄为"调用方当前可见时才允许"，大幅收窄钓鱼与"混淆代理"（confused deputy）攻击面。
3. 配套 StrictMode 检测项 + lint 检查，帮助提前发现旧模式。

### Q3.2 为什么 BAL 要区分 `callingUid` 和 `realCallingUid`？

这是 **confused deputy 攻击**的防御核心。
- `callingUid`：Binder 调用直接来源。
- `realCallingUid`：`PendingIntent` **创建者**的 uid。

恶意应用 A 构造一个 PendingIntent 交给高权限应用 B（如系统 UI）去 send，此时 `callingUid` = B（高权限），`realCallingUid` = A。若只看 `callingUid`，A 就借 B 的手完成了后台启动。所以 `checkBackgroundActivityStart` **两个 uid 都要过检查**，且都需各自的授权模式放行。

### 🔥 高频追问
1. **"通知点击算后台启动吗？"** 不算 —— 通知的 PendingIntent 由 SystemUI 发送，且属于明确用户交互，走豁免。
2. **"FGS 一定能后台启活动吗？"** 不一定。A17 要求 FGS 具备 **while-in-use（WIU）权限** 或精确闹钟豁免，纯 `dataSync` 类 FGS 不足以豁免。
3. **"BAL 被拦截时怎么排查？"** `adb logcat | grep -i "Background activity launch blocked"`，日志会打印 `BalVerdict` 的具体 reason code。

### ⚠️ 易错点
- 以为 `SYSTEM_ALERT_WINDOW` 是万能钥匙 —— A14 起需实际**持有可见的悬浮窗**，仅授权不够。
- 在 `BroadcastReceiver.onReceive()` 里直接 `startActivity` —— 静态广播接收时进程通常在后台，必然被拦。
- 用 `setPendingIntentBackgroundActivityStartMode(ALLOWED)` 一把梭 —— A17 后应改用 `ALLOW_IF_VISIBLE`。

---

# 4. A17 Bubbles 浮窗：新 windowing mode 与 SystemUI 协同

### Q4.1 A17 的 Bubbles 浮窗模式和旧的"气泡通知"是一回事吗？

**不是。** 这是面试最容易踩的概念坑。

| 维度 | 旧：Bubble 通知 API（A11） | 新：A17 Bubbles 窗口模式 |
|------|---------------------------|-------------------------|
| 触发 | 应用调 `Notification.BubbleMetadata` | **用户**长按桌面图标 |
| 主体 | 应用主动声明 | 系统行为，应用无需适配 |
| 承载 | `ActivityView`/`TaskView` 承载 activity | 系统级浮窗窗口模式 + Taskbar Bubble 栏 |
| 控制权 | 应用 | 用户 / 系统 |

**框架侧关键组件（A14 已有基础，A17 扩展）：**

```
frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/
├── bubbles/BubbleController.java        // Bubble 生命周期总控
├── bubbles/BubbleStackView.java         // 气泡堆叠 UI
├── bubbles/BubbleTaskViewHelper.java
├── taskview/TaskView.java               // 在任意 View 层级中嵌入一个 Task
├── taskview/TaskViewTaskController.java
└── ShellTaskOrganizer.java              // 与 WMS 的 TaskOrganizer 通道
```

**底层机制：** Bubble 里跑的是**真实 Task**，通过 `TaskOrganizer` 机制把 Task 的 `SurfaceControl` 重新 reparent 到 SystemUI（Shell）自己的 `SurfaceControlViewHost` 里：

```
WindowContainerTransaction wct = new WindowContainerTransaction();
wct.setWindowingMode(taskToken, WINDOWING_MODE_MULTI_WINDOW);
wct.reparent(taskToken, mShellTaskOrganizer.getRootTask(...), true);
mSyncQueue.queue(wct);
```

对应 WMS 侧：`frameworks/base/services/core/java/com/android/server/wm/TaskOrganizerController.java`、`WindowOrganizerController.java`。

### 🔥 高频追问
1. **"TaskView / TaskOrganizer 的意义是什么？"**
   把窗口层级管理从 `system_server` 的 WMS **下放到 SystemUI（Shell）进程**，避免每加一种窗口形态就改 WMS。分屏、PiP、Bubble、桌面窗口模式全部基于此。这是 A10 以后 WM 架构最重要的一次解耦。
2. **"Bubble 里的 Activity 生命周期正常吗？"**
   正常，它是完整 Task，有 resumed/paused。但要注意它可能与主界面 Task **同时 resumed**（multi-resume），单例假设会失效。
3. **"为什么不用 `ActivityView`？"** `ActivityView` 已废弃（安全模型有缺陷、无法正确处理输入路由），`TaskView` 是官方替代。

---

# 5. Handoff API / Continue On 跨设备接力全链路

### Q5.1 Continue On（Handoff）在框架层是怎么实现"手机→平板接力"的？

**用户侧品牌名：Continue On；开发者 API：Handoff API（A17 Beta 2 引入，I/O 2026 正式发布）。**
其平台前身是 A16 QPR1 的内部特性 **Task Continuity**。

**链路拆解：**

```
[发送端]
Activity.setHandoffEnabled(true)  +  提供可序列化的状态载荷
   ↓
系统采集 Task 状态快照（component + intent + 应用自定义 state）
   ↓
CompanionDeviceManager 建立的可信设备通道
   frameworks/base/core/java/android/companion/CompanionDeviceManager.java
   frameworks/base/services/companion/java/com/android/server/companion/
     ├── CompanionDeviceManagerService.java
     ├── datatransfer/           // 跨设备数据传输
     └── presence/               // 设备在场检测（BLE/Wi-Fi）
   ↓
[接收端]
Launcher / Taskbar 展示 "继续" 建议卡片
   ↓
两种落地路径：
   (a) 原生 App → 本地重建 Activity 并注入 state
   (b) App-to-Web 兜底 → 未安装 App 时跳 Web 版
```

**为什么必须挂在 CDM 上？** 因为跨设备状态传输涉及：
- **设备可信关系**：CDM 的 association 是用户显式授权过的配对关系。
- **在场检测**：`CompanionDeviceService.onDeviceAppeared/onDeviceDisappeared`，只在设备实际靠近时才提示。
- **权限收口**：不需要发明新的跨设备权限模型，复用 CDM 已有的 `REQUEST_COMPANION_*` 体系。

### Q5.2 这跟"应用自己用云同步做接力"有什么本质区别？

| 维度 | 云同步方案 | Handoff API |
|------|-----------|-------------|
| 时延 | 依赖网络往返 | 本地链路（BLE/Wi-Fi Direct） |
| 隐私 | 状态上云 | 端到端本地传输 |
| 发现 | 应用自己做 | 系统 Launcher 统一入口 |
| 离线 | 不可用 | 可用 |
| 兜底 | 无 | app-to-web |

### 🔥 高频追问
1. **"接力的状态载荷有大小限制吗？"** 有。走 CDM 数据传输通道，设计上是**轻量状态指针**（如文档 ID + 光标位置），不是全量数据同步。大数据仍应走应用自己的同步通道。
2. **"和 Nearby Share / Cast 的关系？"** 三者共享底层的设备发现与传输基建（`packages/modules/Connectivity`、Nearby Mainline 模块），但语义不同：Share 传文件、Cast 投屏像素、Handoff 传**任务状态**。
3. **"多设备同时在场怎么选？"** 由 CDM 的 presence + 用户交互决定，应用不参与选择。

---

# 6. Input 深水区：Pointer Capture 相对/绝对模式与触控板归一化

### Q6.1 `requestPointerCapture()` 在 A17 前后行为有何差异？为什么要改？

**问题背景：** 游戏/远程桌面类应用需要"捕获指针"——让鼠标移动不再驱动系统光标，而是把**原始位移**送给应用（第一人称视角必需）。

**A17 之前的痛点：**
- 鼠标捕获后上报 **相对位移**（delta）；
- 触控板捕获后上报的却是**手指在触控板上的绝对坐标**；
- 两者语义完全不同，开发者要写大量分支判断设备类型。

**A17 的修正：** 系统**默认把触控板手势归一化为鼠标事件**上报（相对位移），保留显式回退：

```java
// 默认 = 相对模式（等同鼠标）
view.requestPointerCapture();
// 显式请求旧的绝对坐标（原始触点数据，做多指手势才需要）
view.requestPointerCapture(View.POINTER_CAPTURE_MODE_ABSOLUTE);
```

**框架链路（Android 14 基线）：**

```
View.requestPointerCapture()
  → ViewRootImpl.requestPointerCapture(boolean enabled)
      frameworks/base/core/java/android/view/ViewRootImpl.java
  → IWindowSession.updatePointerIcon / WindowManagerService#requestPointerCapture
      frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java
  → InputManagerService.setPointerCapture()
      frameworks/base/services/core/java/com/android/server/input/InputManagerService.java
  → native: frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp
             InputDispatcher::setPointerCaptureLocked()
  → 捕获期间事件绕过 pointer 坐标映射，以 AMOTION_EVENT_SOURCE_MOUSE_RELATIVE 派发
```

应用侧接收：

```java
@Override
public boolean onCapturedPointerEvent(MotionEvent event) {
    float dx = event.getX();  // 捕获模式下 = 相对位移，不是绝对坐标
    float dy = event.getY();
    return true;
}
// 或 View.setOnCapturedPointerListener(...)
```

**关键源码点：**
- `frameworks/native/services/inputflinger/reader/mapper/CursorInputMapper.cpp` —— 鼠标/触控板原始事件到 MotionEvent 的转换，`mSource = AINPUT_SOURCE_MOUSE_RELATIVE` 的设置处。
- `frameworks/native/services/inputflinger/reader/mapper/TouchpadInputMapper.cpp` —— A13 引入的触控板专用 mapper，内部集成 **Google gestures 库**（`external/libchrome-gestures`）做手势识别（双指滚动、三指切换等）。A17 的归一化正是在这一层完成。

### 🔥 高频追问
1. **"Pointer Capture 和 `SYSTEM_UI_FLAG_*` 沉浸模式有关系吗？"** 无关。前者管**输入路由**，后者管**窗口装饰可见性**。
2. **"为什么捕获必须窗口有焦点？"** 安全考虑——`InputDispatcher` 只把捕获通道给 focused window，防止后台窗口窃取指针输入。焦点丢失会自动 `onPointerCaptureChange(false)`。
3. **"触控板的手势识别在哪一层？"** 在 **native 的 `TouchpadInputMapper` + libchrome-gestures**，不是 Java 层，也不是内核驱动。内核只上报原始 multitouch 事件（`evdev` ABS_MT_*）。

### ⚠️ 易错点
- 在捕获模式下继续用 `event.getRawX()` —— 相对模式下 raw 坐标无意义。
- 忘记处理 `onPointerCaptureChange()` 回调，窗口失焦后状态不同步。

---

# 7. Telephony：SMS OTP 三小时延迟拦截机制

### Q7.1 A17 "含 OTP 短信延迟 3 小时投递"是怎么实现的？

这是 A17 隐私侧最"硬"的一条变更，考的是 **Telephony 收信全链路 + Provider 查询过滤**双层拦截。

**（1）短信接收主链路（Android 14）：**

```
Modem → RIL → RadioService (AIDL HAL)
  hardware/interfaces/radio/aidl/android/hardware/radio/messaging/IRadioMessaging.aidl
  ↓
frameworks/opt/telephony/src/java/com/android/internal/telephony/
├── RIL.java                       // acknowledgeLastIncomingGsmSms 等
├── SmsDispatchersController.java
├── InboundSmsHandler.java         // 状态机：IdleState / DeliveringState / WaitingState
│     ├── dispatchIntent()         // 发 SMS_RECEIVED_ACTION 广播
│     └── addTrackerToRawTable()   // 分段短信重组，落 raw 表
├── GsmInboundSmsHandler.java / CdmaInboundSmsHandler.java
└── SmsBroadcastUndelivered.java
  ↓
广播 Telephony.Sms.Intents.SMS_RECEIVED_ACTION → 具备 RECEIVE_SMS 的应用
  ↓
默认短信应用写库 → SmsProvider (packages/providers/TelephonyProvider/)
```

**（2）A17 的双层拦截点：**

| 层 | 拦截动作 |
|----|---------|
| **广播层** | 在 `InboundSmsHandler.dispatchIntent()` 之前做 OTP 内容检测；命中则对**非豁免应用**扣留 `SMS_RECEIVED_ACTION` 三小时 |
| **Provider 层** | `SmsProvider.query()` 对非豁免调用方**过滤掉**处于延迟窗口内的 OTP 记录 |

**双层的必要性：** 只拦广播不够——应用可以主动 `query(Telephony.Sms.CONTENT_URI)` 轮询数据库把验证码捞出来。必须两条路一起堵。

**（3）OTP 内容检测怎么做？**
Android 自 A14 起在 SystemUI/通知层已有 OTP 检测能力（用于通知内容隐藏），基于**正则 + 端侧 ML 分类器**（`TextClassifier` 体系），A17 把它下沉复用到 Telephony 拦截判定。

```
frameworks/base/core/java/android/view/textclassifier/TextClassifier.java
packages/modules/ExtServices/  // TextClassifierService 默认实现，Mainline 模块
```

**（4）豁免名单：**
- 默认短信应用（`RoleManager.ROLE_SMS`）
- 语音助手 / 数字助理
- CDM 关联的伴侣设备应用
- 短信的**目标域名归属方**（WebOTP 场景，通过 `SMS Retriever` 的 app hash 匹配）

**（5）正确姿势：**

```java
// 推荐：SMS Retriever API —— 无需任何 SMS 权限
SmsRetrieverClient client = SmsRetriever.getClient(context);
client.startSmsRetriever();
// 短信需含 11 位 app hash：<#> Your code is 123456  FA+9qCX9VSu

// 或：SMS User Consent API —— 系统弹窗让用户一键授权单条短信
```

### 🔥 高频追问
1. **"分段长短信（concatenated SMS）怎么处理？"** `InboundSmsHandler` 用 raw 表按 `reference_number + sequence` 重组，全部分段到齐才 dispatch。OTP 检测在**重组后**的完整文本上做。
2. **"应用用 `ContentObserver` 监听 SMS 表能绕过吗？"** 不能。Observer 通知后仍需 `query()`，Provider 层已过滤。
3. **"为什么是 3 小时而不是永久？"** 平衡点：绝大多数 OTP 有效期 5~10 分钟，3 小时后验证码已失效，攻击价值归零，同时保留应用的历史短信读取能力（备份类应用不受实质影响）。

### ⚠️ 易错点
- 以为 `RECEIVE_SMS` 权限已授权就万事大吉 —— 权限只管"能不能收"，compat 变更管"什么时候给"。
- 用 `READ_SMS` 轮询做验证码自动填充 —— A17 后必坏，且 Play 政策早已限制。

---

# 8. 网络栈：ECH 加密 ClientHello 与 `ACCESS_LOCAL_NETWORK`

### Q8.1 ECH（Encrypted Client Hello）解决什么问题？Android 在哪一层实现？

**问题：** TLS 1.3 加密了握手中的证书，但 **SNI（Server Name Indication）明文**——中间人（运营商、Wi-Fi 网关）能看到你连的是哪个域名，即使内容加密。

**ECH 方案：**
1. 客户端通过 **DNS 的 HTTPS/SVCB 记录**拿到目标的 **ECH 公钥配置**（`ech_config`）。
2. 用该公钥加密真实的 ClientHello（含真 SNI），构造成 **inner ClientHello**。
3. 外层发一个 **outer ClientHello**，SNI 填公共的 "cover" 域名。
4. 服务端解密内层，恢复真实握手。
5. 若协商失败 → 发送 **ECH GREASE**（随机内容的 ECH 扩展，RFC 9849），让"有 ECH"和"无 ECH"流量在网络上不可区分——这点是隐私设计的精髓。

**Android 实现层级：**

```
external/conscrypt/                   // Android 的 JSSE Provider，封装 BoringSSL
external/boringssl/                   // ECH 的实际密码学实现
packages/modules/Conscrypt/           // Mainline 模块，可独立更新
  ↑
HttpEngine (Cronet, packages/modules/NetworkStack 相关) / WebView / OkHttp
  ↑
应用
```

**A17 行为：** targetSdk ≥ 37 的应用 TLS 连接**默认启用 ECH**（前提：所用网络库集成了 ECH 支持，且服务端支持）。新增网络安全配置元素：

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
  <base-config>
    <domainEncryption mode="enabled"/>
  </base-config>
  <domain-config>
    <domain includeSubdomains="true">internal.corp.example</domain>
    <domainEncryption mode="disabled"/>   <!-- 内网域名可关闭 -->
  </domain-config>
</network-security-config>
```

解析入口：`frameworks/base/core/java/android/security/net/config/XmlConfigSource.java`、`NetworkSecurityConfig.java`。

### Q8.2 `ACCESS_LOCAL_NETWORK` 权限的框架实现在哪？

**背景：** 此前应用访问局域网（192.168.x.x、mDNS 发现）**完全无需权限**——恶意应用可扫描家庭网络做设备指纹、追踪用户。

**A16 可选启用 → A17 targetSdk 37 强制。**

- 权限组：归入既有的 **`NEARBY_DEVICES`** 权限组（已授权蓝牙等的用户不会二次弹窗）。
- 两条合规路径：
  1. **系统设备选择器**（隐私友好，无需权限）—— 类似 CDM 的 association 流程，用户选哪个设备就给哪个设备的访问权。
  2. **声明 + 运行时请求 `ACCESS_LOCAL_NETWORK`**。

**执行层：** 局域网访问的拦截落在 **netd / eBPF 流量策略**上：

```
system/netd/                                  // netd 守护进程
packages/modules/Connectivity/                // Tethering/Connectivity Mainline
  service/native/  → TrafficController (eBPF)
  bpf_progs/       → netd.c / offload.c，uid 维度的流量准入 map
frameworks/base/services/core/java/com/android/server/net/NetworkPolicyManagerService.java
```

原理与 `INTERNET` 权限的执行一致：应用 uid 是否在允许 map 中，由 eBPF 程序在 socket/cgroup hook 点判定。这也解释了为什么它能拦住 raw socket 层的扫描，而不只是拦 Java API。

### 🔥 高频追问
1. **"ECH 会不会被防火墙直接掐掉？"** 会有对抗。所以 GREASE 很关键——让不支持 ECH 的连接也带随机 ECH 扩展，防火墙无法靠"是否有 ECH 扩展"做区分性封锁。
2. **"`INTERNET` 权限是 normal 权限，为什么 `ACCESS_LOCAL_NETWORK` 是 runtime？"** 因为局域网访问的隐私敏感度更高（能推断家庭设备构成、物理位置），需要用户显式知情。
3. **"Conscrypt 是 Mainline 模块意味着什么？"** 意味着 ECH 这类 TLS 能力可以**不随大版本 OTA**，通过 Google Play 系统更新下发到老版本 Android。

---

# 9. ContentProvider 深水区：CP2 PII 列裁剪与 Strict SQL 校验

### Q9.1 A17 对 ContactsProvider2（CP2）做了什么限制？为什么要做 SQL 严格校验？

**（1）PII 列裁剪**

targetSdk ≥ 37 时，`ContactsContract.Data` 视图移除以下列：

```
ACCOUNT_NAME            // 常是用户邮箱 → 强 PII
ACCOUNT_TYPE            // 泄漏用户使用了哪些账号服务
ACCOUNT_TYPE_AND_DATA_SET
```

实现位置：`packages/providers/ContactsProvider/src/com/android/providers/contacts/ContactsProvider2.java`，在构建 projection map 时按 compat changeId 分支裁列。

**（2）Strict SQL 校验（重点）**

无 `READ_CONTACTS` 权限查询 `ContactsContract.Data` 时，强制设置：

```java
SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
qb.setStrictColumns(true);   // 校验 projection / selection 里的列名合法性
qb.setStrictGrammar(true);   // 校验 SQL 语法结构，禁止子查询/函数注入
```

对应源码：`frameworks/base/core/java/android/database/sqlite/SQLiteQueryBuilder.java`
关键方法：`enforceStrictColumns(String[] projection)`、`enforceStrictGrammar(String selection, ...)`，内部用 `SQLiteTokenizer.tokenize()` 逐 token 校验。

**为什么必须做？** 这是防 **ContentProvider SQL 注入 / 侧信道推断**的经典问题：

```java
// 攻击者即使没有 READ_CONTACTS，也可能通过 selection 做布尔盲注：
resolver.query(Data.CONTENT_URI, new String[]{"_id"},
    "(SELECT COUNT(*) FROM raw_contacts WHERE display_name LIKE 'A%') > 0", null, null);
// 通过返回行数 / 是否抛异常，逐字符推断出通讯录内容
```

`setStrictGrammar` 会在 tokenize 阶段发现 `SELECT` 子查询关键字并直接拒绝抛异常。

### 🔥 高频追问
1. **"为什么不直接禁止无权限查询？"** 因为有合法场景（如查询自己写入的数据、查询 caller 自身账号），一刀切会破坏兼容性。严格校验是"允许但收紧"的折中。
2. **"Contacts Picker（`ACTION_PICK_CONTACTS`）比 `READ_CONTACTS` 好在哪？"**
   - 会话级临时授权，不持久化
   - **字段级**授权（只给请求的 Email/Phone，不给全部）
   - 支持从工作资料选择
   - 不触发 Play 敏感权限审核
   - 对应 extras：`EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS`、`EXTRA_PICK_CONTACTS_SELECTION_LIMIT`
3. **"自研 Provider 该怎么防注入？"** 一律用 `SQLiteQueryBuilder` + `setProjectionMap()` 白名单 + `setStrict*`，**永远不要**字符串拼接 selection。

---

# 10. ART hiddenapi 名单生成流水线（补前篇欠账）

> 第八篇讲了 hiddenapi 的**运行时管制**（light/dark/black greylist），但没讲名单**怎么来的**。这是构建系统与 ART 交界处的高阶问题。

### Q10.1 hiddenapi 的四类名单是编译期生成的还是运行时算的？完整流水线是什么？

**编译期生成，运行时查表。** 流水线：

```
(1) 源码标注
    frameworks/base 中给需要保留的隐藏 API 加注解：
      @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.S, ...)
      定义在 frameworks/base/core/java/android/compat/annotation/UnsupportedAppUsage.java
      （旧名 @hide + 灰名单 txt）
        ↓
(2) 编译期扫描
    tools/platform-compat/java/android/processor/compat/unsupportedappusage/
      UnsupportedAppUsageProcessor  → 生成 CSV 索引
        ↓
(3) hiddenapi 工具注入 flag
    art/tools/hiddenapi/hiddenapi.cc
      - 读取 boot classpath 的 dex
      - 把 flag 编码进 dex 的 hiddenapi_class_data section
      - 产出 out/soong/hiddenapi/hiddenapi-flags.csv
        ↓
(4) 打包
    hiddenapi-flags.csv → boot jar 内嵌 + /system/etc/ 校验文件
    build/soong/java/hiddenapi_singleton.go  // Soong 侧编排
        ↓
(5) 运行时查表
    art/runtime/hidden_api.h / hidden_api.cc
      ShouldDenyAccessToMember(ArtMethod*, AccessContext, AccessMethod)
      art/runtime/hidden_api_flags.h   // ApiList 定义
```

**四类 flag 的语义（`hidden_api_flags.h` 的 `ApiList`）：**

| ApiList | 行为 |
|---------|------|
| `sdk` (whitelist) | 公开 API，完全放行 |
| `unsupported` (light greylist) | 可用，打 log + strict mode 警告 |
| `max-target-x` (dark greylist) | targetSdk ≤ x 可用，超过则按 blocked 处理 |
| `blocked` (blacklist) | 一律 `NoSuchMethodError` / `NoSuchFieldError` |

**A17 的收紧：** `max-target-*` 名单进一步下沉为 `blocked`；配合 **static final 字段真不可变**（反射写抛 `IllegalAccessException`，JNI `SetStaticLongField` 直接 crash），过去插件化/热修复框架赖以生存的两大后门同时关闭。

### Q10.2 `AccessContext` 是什么？为什么系统应用不受限？

```cpp
// art/runtime/hidden_api.h
class AccessContext {
  // 由调用者的 ClassLoader + DexFile 域推断
  // Domain: kCorePlatform > kPlatform > kApplication
};
```

判定规则：**调用方 domain ≥ 被调用方 domain 时放行**。
- boot classpath 里的类 → `kCorePlatform`
- `/system/framework`、`/system/app` 里的类 → `kPlatform`
- 普通应用 → `kApplication`

所以系统应用调隐藏 API 天然放行，而普通应用不行。**这也是"反射调隐藏 API 被拦"的绕过原理来源**——早期通过 `setHiddenApiExemptions`（VMRuntime 的隐藏方法，自身用双重反射/`unsafe` 绕过）或伪造 `AccessContext` 达成，A12+ 起这些路径陆续被封。

### 🔥 高频追问
1. **"为什么 Google 要保留 `@UnsupportedAppUsage`，不直接全封？"** 兼容性务实：先统计真实使用量（statsd 打点），高使用量的 API 提供公开替代后再逐步下沉，避免生态大面积崩塌。整个流程与第 1 章的 compat 框架是**同一套治理哲学**。
2. **"怎么查一个 API 属于哪个名单？"**
   ```bash
   # 设备上
   adb shell settings put global hidden_api_policy 1   # 调试用，仅 userdebug
   # 源码侧
   grep "Landroid/app/ActivityThread;->currentActivityThread" out/soong/hiddenapi/hiddenapi-flags.csv
   ```
3. **"Mainline 模块的 hiddenapi 怎么算？"** 每个 apex 有自己的 hiddenapi flag 分片，在 `hiddenapi_singleton.go` 中合并校验，OTA/apex 更新时会做一致性检查（`--check-flags`）。

---

# 📌 查缺补漏清单（本篇新增覆盖）

| 缺口 | 本篇章节 | 与前九篇的关系 |
|------|---------|---------------|
| compat 框架引擎本身 | §1 | 前九篇所有"targetSdk 行为变更"的**共同底座**，首次拆解 |
| WMS letterbox / SizeCompat 内部类 | §2 | 补第一篇 WMS、第九篇渲染的空白 |
| BAL 全链路 + confused deputy | §3 | 补第一篇 AMS/ATMS 的安全侧 |
| TaskOrganizer / TaskView | §4 | 补第二篇"折叠屏/多窗口 WM"的实现层 |
| CDM 跨设备 / Handoff | §5 | 全新领域 |
| Pointer Capture / TouchpadInputMapper | §6 | 补第二、四篇 Input 的最后一块 |
| SMS OTP 拦截 / InboundSmsHandler | §7 | 补第五篇 Telephony/RIL 的上层 |
| ECH / Conscrypt / eBPF 流量准入 | §8 | 全新领域（网络栈此前只提过 Wi-Fi/BT） |
| SQLiteQueryBuilder 严格模式 | §9 | 全新领域 |
| hiddenapi 生成流水线 | §10 | 补第八篇 hiddenapi 运行时的**构建期**欠账 |

**仍未覆盖、可作后续轮换：**
LiteRT NPU delegate 源码走读 · CarService 电源状态机完整状态图 · Codec2 vendor 组件调试实战 · 端侧 LLM 量化工程化 · `packages/modules/Connectivity` eBPF 程序细读 · Ravenwood/host 侧单测框架 · Trusty TEE / Widevine DRM

---

# ⚡ 易错点速记（本篇 15 条）

1. `compileSdk` ≠ `targetSdk`，**只有 targetSdk 决定 compat 判定**。
2. 未注册的 changeId **默认开启**，不是关闭。
3. 进程内 `isChangeEnabled(long)` 不走 Binder；带包名的重载**会**走。
4. Size Compat Mode ≠ letterbox，前者逻辑冻结 + 缩放，后者视觉黑边。
5. `configChanges` 能防重建，防不了尺寸变化。
6. 相机预览别用 `Display.getRealSize()`，用 `WindowMetricsCalculator`。
7. BAL 检查 `callingUid` **和** `realCallingUid` 两个 uid。
8. `SYSTEM_ALERT_WINDOW` 需**实际显示悬浮窗**才豁免 BAL。
9. A17 起改用 `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`。
10. A17 Bubbles 窗口模式 ≠ A11 气泡通知 API。
11. `ActivityView` 已废弃，用 `TaskView`。
12. Pointer Capture 下 `getX()` 是**位移**不是坐标。
13. OTP 拦截是**广播 + Provider 双层**，只堵一层无效。
14. ECH GREASE 的意义是让"有无 ECH"不可区分，不是性能优化。
15. hiddenapi 名单是**编译期注入 dex**，运行时只查表。

---

# 🗺️ 十篇交叉索引

| 篇 | 日期 | 主题 | 专题数 |
|----|------|------|--------|
| 1 主篇 | 07-23 | Handler/Looper · Binder · 启动 · AMS/ATMS · WMS · View · ANR · LMKD · Compose · HAL · GKI · MTK | 16 |
| 2 拓展篇 | 07-23 | Input 全链路 · PMS · ART/JIT · SystemUI · 折叠屏 WM · SELinux · OTA/AB · JNI hook · Binder 安全 · Perfetto | 10 |
| 3 深挖篇 | 07-23 | ART 对象头 · CMC GC · deopt · binderfs 调试 · Rust Binder · split touch · VSync 时序 · Camera HAL · Audio · GKI KMI · Perfetto SQL | 11 |
| 4 图形多媒体通信篇 | 07-24 | HWUI · Choreographer · SurfaceFlinger · Gralloc/DMA-BUF · 多刷新率 · MediaCodec · Codec2 · Thermal · Power/ADPF · RIL · Wi-Fi · BT | 12 |
| 5 系统基建篇 | 07-27 | 16KB 页 · ClassLoader 插件化 · 权限链路 · Keystore2 · AVB/dm-verity · Vold/FUSE · logd · 可观测性 · RRO · Doze/JobScheduler · A15/16 变更 | 11 |
| 6 端侧 AI 与 A17 演进篇 | 07-28 | NNAPI/NPU · LiteRT · CarService · Vulkan/ANGLE/Skia · ART 镜像 · virtual A/B snapuserd | 10 |
| 7 A17 新雷区篇 | 07-29 | Lock-free MessageQueue · ART 分代 GC · hiddenapi 运行时 · ProfilingManager · 后台音频 · NFC/SE · Media3 · 端侧 LLM | 8 |
| 8 渲染合成与安全内存篇 | 07-30 | SF RenderEngine · Codec2 vendor plugin · Memory Limiter · 原生 DCL 加固 · Keystore 限额 · CarService 多用户 · ART oat/odex 布局 | 7 |
| **9 本篇** | **07-31** | **compat 框架 · 大屏强制 resizable · BAL · Bubbles/TaskView · Handoff · Pointer Capture · SMS OTP · ECH/局域网权限 · CP2 Strict SQL · hiddenapi 流水线** | **10** |

> 说明：07-23 当日产出三篇，故按文件计为九份、篇序第十次整理。**累计 95 个专题**。

---

# 🔑 一句话串讲（面试收尾用）

> Android 从 P 开始把"版本行为变更"工程化成了一套**可声明、可灰度、可打点、可回滚**的治理系统——`@ChangeId` 声明、`platform_compat` 判定、Zygote 注入禁用集合、statsd 收集影响面、`@LoggingOnly → @EnabledSince → 不可 override` 三级推进。
> hiddenapi 是同一套哲学在 **API 表面**的投影，BAL / 大屏 resizable / OTP 延迟 / 本地网络权限则是它在 **AMS / WMS / Telephony / 网络栈**四个子系统的具体落点。
> **看懂第 1 章，后面九章都是它的实例。**

---

*文档生成：2026-07-31 · 源码基线 AOSP android-14.0.0_rXX · 行为变更基线 Android 17 (API 37)*
