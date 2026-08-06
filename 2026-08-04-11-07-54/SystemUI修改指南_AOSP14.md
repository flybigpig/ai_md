# SystemUI 修改指南 — AOSP 14 (UpsideDownCake, API 34)

> **核心结论前置：** SystemUI 是独立 APK（`SystemUI.apk`），运行在 `com.android.systemui` 进程中，签名与 platform 同钥。源码主目录 `frameworks/base/packages/SystemUI/`。AOSP 14 中用 **Dagger 2** 做依赖注入，核心控制器从旧版 `PhoneStatusBar` 演进为 `CentralSurfacesImpl`。修改 SystemUI 的关键在于理解 Dagger 绑定链 + `CoreStartable` 生命周期。

---

## 一、方案速查表

| # | 修改需求 | 改动层级 | 涉及核心文件 | 难度 |
|---|---------|---------|-------------|------|
| 1 | 隐藏/精简状态栏 | Framework / SystemUI | `CentralSurfacesImpl`, `config.xml` | ★★☆ |
| 2 | 自定义导航栏（返回/Home/Recent） | SystemUI | `NavigationBarController`, `NavigationBarView` | ★★☆ |
| 3 | 添加自定义 QS Tile | SystemUI | `qs/tiles/`, `QSFactoryImpl`, `R.string` | ★★★ |
| 4 | 修改锁屏行为/跳过锁屏 | SystemUI | `KeyguardViewMediator`, `KeyguardSecurityContainerController` | ★★★ |
| 5 | 自定义通知样式/过滤通知 | SystemUI | `NotificationStackScrollLayoutController`, `NotificationListener` | ★★★ |
| 6 | 修改状态栏图标/信号/电量 | SystemUI | `StatusBarIconController`, `SignalClusterView` | ★★☆ |
| 7 | 修改截屏行为 | SystemUI | `ScreenshotController`, `ScreenshotPolicy` | ★★☆ |
| 8 | 自定义音量面板 | SystemUI | `VolumeDialogImpl`, `VolumeUI` | ★★☆ |
| 9 | 开机全屏/沉浸式 | SystemUI + config | `CentralSurfacesImpl`, `config.xml` 资源覆盖 | ★☆☆ |
| 10 | 添加自定义 SystemUI 组件 | SystemUI / Dagger | `CoreStartable`, `SystemUICoreStartableModule` | ★★★ |

---

## 二、SystemUI 架构总览

### 2.1 进程与加载

```
system_server 启动
  └─ ActivityTaskManagerService.startActivityAsUser()
       └─ 启动 SystemUI 进程 (com.android.systemui)
            └─ SystemUIApplication.onCreate()
                 ├─ 初始化 Dagger 组件 (SystemUIRootComponent)
                 ├─ 遍历 CoreStartable 列表，依次调用 start()
                 └─ 各 Controller/View 按需初始化
```

**关键点：** SystemUI 不是由 `system_server` 直接加载的代码，而是一个**独立 APK**，通过 `Activity`/`Service` 机制启动。`system_server` 通过 `ActivityTaskManagerInternal` 触发 SystemUI 的启动。

### 2.2 Dagger 2 依赖注入

AOSP 14 的 SystemUI 完全基于 Dagger 2。理解绑定链是修改的前提：

```
SystemUIRootComponent (Dagger 根组件)
  ├─ @Singleton → SystemUICoreStartableModule (注册所有 CoreStartable)
  ├─ ReferenceSingletonModule (单例绑定)
  ├─ CentralSurfacesModule (状态栏相关绑定)
  ├─ QSModule (快捷设置绑定)
  ├─ KeyguardModule (锁屏绑定)
  ├─ NotificationModule (通知绑定)
  ├─ NavigationBarModule (导航栏绑定)
  └─ ... 其他 Module
```

**源码路径：**
- 根组件：`src/com/android/systemui/dagger/SystemUIRootComponent.java`
- CoreStartable 注册：`src/com/android/systemui/dagger/SystemUICoreStartableModule.java`
- Dagger 初始化入口：`src/com/android/systemui/SystemUIApplication.java`

### 2.3 CoreStartable 生命周期

```java
// frameworks/base/packages/SystemUI/src/com/android/systemui/CoreStartable.java
public interface CoreStartable {
    void start();        // SystemUIApplication.onCreate() 时遍历调用
    // 以下为可选回调（通过 DefaultLifecycleObserver 实现）
    default void onBootCompleted() {}
}
```

**注册方式（AOSP 14）：** 在 `SystemUICoreStartableModule.java` 中通过 `@IntoSet` 绑定：

```java
// SystemUICoreStartableModule.java
@Provides
@IntoSet
CoreStartable provideChargingControl(ChargingControl impl) {
    return impl;
}
```

---

## 三、核心源码路径速查

> **根目录：** `frameworks/base/packages/SystemUI/`

### 3.1 入口与核心

| 类名 | 路径 | 职责 |
|------|------|------|
| `SystemUIApplication` | `src/com/android/systemui/SystemUIApplication.java` | Application 入口，初始化 Dagger + 启动 CoreStartable |
| `SystemUIRootComponent` | `src/com/android/systemui/dagger/SystemUIRootComponent.java` | Dagger 根组件 |
| `SystemUICoreStartableModule` | `src/com/android/systemui/dagger/SystemUICoreStartableModule.java` | 注册所有 CoreStartable |
| `BootCompleteImpl` | `src/com/android/systemui/BootCompleteImpl.java` | 开机完成广播处理 |

### 3.2 状态栏 (Status Bar)

| 类名 | 路径 | 职责 |
|------|------|------|
| `CentralSurfacesImpl` | `src/com/android/systemui/statusbar/phone/CentralSurfacesImpl.java` | **核心控制器**，管理状态栏整体生命周期（替代旧版 PhoneStatusBar） |
| `CentralSurfaces` | `src/com/android/systemui/statusbar/phone/CentralSurfaces.java` | 接口定义 |
| `PhoneStatusBarViewController` | `src/com/android/systemui/statusbar/phone/PhoneStatusBarViewController.java` | 状态栏 View 控制器 |
| `StatusBarIconController` | `src/com/android/systemui/statusbar/phone/StatusBarIconController.java` | 状态栏图标管理（信号/电量/通知图标等） |
| `StatusBarIconList` | `src/com/android/systemui/statusbar/phone/StatusBarIconList.java` | 图标列表配置 |
| `NotificationPanelViewController` | `src/com/android/systemui/statusbar/phone/NotificationPanelViewController.java` | 下拉通知面板控制器 |
| `KeyguardBypassController` | `src/com/android/systemui/statusbar/phone/KeyguardBypassController.java` | 锁屏绕过逻辑 |

### 3.3 导航栏 (Navigation Bar)

| 类名 | 路径 | 职责 |
|------|------|------|
| `NavigationBarController` | `src/com/android/systemui/navigationbar/NavigationBarController.java` | 导航栏控制器（多 Display 管理） |
| `NavigationBarView` | `src/com/android/systemui/navigationbar/NavigationBarView.java` | 导航栏 View |
| `NavigationBar` | `src/com/android/systemui/navigationbar/NavigationBar.java` | Fragment，管理导航栏生命周期 |
| `NavigationBarTransitions` | `src/com/android/systemui/navigationbar/NavigationBarTransitions.java` | 导航栏过渡动画 |
| `NavigationModeController` | `src/com/android/systemui/navigationbar/NavigationModeController.java` | 手势/三键模式切换 |

### 3.4 快捷设置 (Quick Settings)

| 类名 | 路径 | 职责 |
|------|------|------|
| `QSFragment` | `src/com/android/systemui/qs/QSFragment.java` | QS Fragment 容器 |
| `QSPanel` | `src/com/android/systemui/qs/QSPanel.java` | QS 面板布局 |
| `QSTileImpl` | `src/com/android/systemui/qs/QSTileImpl.java` | Tile 基类 |
| `QSFactoryImpl` | `src/com/android/systemui/qs/QSFactoryImpl.java` | Tile 工厂，根据 key 创建 Tile |
| `QSTileFactories` | `src/com/android/systemui/qs/tiles/QSTileFactories.java` | **Tile 注册表**（AOSP 14 新增，集中管理 Tile 创建） |
| `qs/tiles/` | `src/com/android/systemui/qs/tiles/` | 所有 Tile 实现类目录 |

### 3.5 锁屏 (Keyguard)

| 类名 | 路径 | 职责 |
|------|------|------|
| `KeyguardViewMediator` | `src/com/android/systemui/keyguard/KeyguardViewMediator.java` | **锁屏核心**，管理锁屏状态机 |
| `KeyguardSecurityContainerController` | `src/com/android/keyguard/KeyguardSecurityContainerController.java` | 锁屏安全验证容器（PIN/密码/图案） |
| `KeyguardUpdateMonitor` | `src/com/android/keyguard/KeyguardUpdateMonitor.java` | 锁屏状态监听（SIM/时间/生物识别等） |
| `KeyguardViewMediator` | `src/com/android/systemui/keyguard/KeyguardViewMediator.java` | 控制锁屏显示/隐藏/解锁 |
| `KeyguardClockSwitch` | `src/com/android/keyguard/KeyguardClockSwitch.java` | 锁屏时钟 |

### 3.6 通知 (Notifications)

| 类名 | 路径 | 职责 |
|------|------|------|
| `NotificationListener` | `src/com/android/systemui/statusbar/NotificationListener.java` | 监听系统通知（连接 NotificationManagerService） |
| `NotificationStackScrollLayoutController` | `src/com/android/systemui/statusbar/notification/stack/NotificationStackScrollLayoutController.java` | 通知列表布局控制 |
| `ExpandableNotificationRow` | `src/com/android/systemui/statusbar/notification/row/ExpandableNotificationRow.java` | 单条通知 View |
| `NotificationGroupManager` | `src/com/android/systemui/statusbar/notification/NotificationGroupManager.java` | 通知分组管理 |
| `NotificationEntryManager` | `src/com/android/systemui/statusbar/notification/NotificationEntryManager.java` | 通知条目生命周期管理 |

### 3.7 资源文件

| 路径 | 说明 |
|------|------|
| `res/layout/` | 布局 XML（`status_bar.xml`, `navigation_bar.xml`, `quick_settings.xml` 等） |
| `res/drawable/` | 图标/图形资源 |
| `res/values/config.xml` | **SystemUI 核心配置**（控制组件开关、行为参数） |
| `res/values/strings.xml` | 字符串资源 |
| `res/values/dimens.xml` | 尺寸定义 |

---

## 四、常见修改场景详解

### 场景 1：隐藏/精简状态栏

**适用场景：** 车载/工控设备不需要顶部状态栏，或需要自定义状态栏内容。

**方法 A：配置开关（推荐，低侵入）**

```xml
<!-- frameworks/base/packages/SystemUI/res/values/config.xml -->
<!-- 控制状态栏头部高度，设为 0 可隐藏 -->
<dimen name="status_bar_height_portrait">0dp</dimen>
<dimen name="status_bar_height_landscape">0dp</dimen>

<!-- 控制哪些图标显示在状态栏 -->
<!-- 格式：slot1,slot2,... -->
<string name="config_statusBarIcons" translatable="false">
    <!-- 清空或保留需要的图标 -->
</string>
```

**方法 B：代码层禁用（更彻底）**

```java
// CentralSurfacesImpl.java
// 找到 makeStatusBarView() 方法中的状态栏初始化逻辑
// 方案：不添加 status_bar View 到窗口

// 关键修改点（伪代码示意）：
// 1. 在 start() 方法中跳过状态栏 View 的创建
// 2. 或在 PhoneStatusBarViewController 中将 visibility 设为 GONE

// 注意：直接隐藏状态栏可能导致下拉面板无法触发，
// 需要同步处理 NotificationPanelViewController 的触发逻辑
```

**方法 C：overlay 覆盖（最推荐，符合 Treble 规范）**

创建 vendor overlay 目录，仅覆盖 `config.xml` 中的值：

```
vendor/xxx/overlay/SystemUI/res/values/config.xml
```

```xml
<resources>
    <dimen name="status_bar_height_portrait">0dp</dimen>
    <dimen name="status_bar_height_landscape">0dp</dimen>
</resources>
```

在 `overlay/Android.bp` 中声明：
```bp
runtime_overlay {
    name: "SystemUIOverlay",
    theme: "SystemUIOverlay",
    product_specific: true,
}
```

---

### 场景 2：自定义导航栏

**适用场景：** 车载设备使用物理按键替代导航栏，或需要自定义导航栏按钮。

**方法 A：完全隐藏导航栏**

```java
// NavigationBarController.java
// 找到 createNavigationBar() 方法
// 方案：在创建前检查配置，直接 return

@Override
public void createNavigationBar(Display display) {
    // 车载场景：直接跳过导航栏创建
    if (isAutomotiveMode()) {
        return;  // ← 新增
    }
    // ... 原有逻辑
}
```

**方法 B：修改导航栏按钮**

```java
// NavigationBarView.java
// 找到 getButton_views() 或 similar 布局构建逻辑
// 关键：通过 res/layout/navigation_bar.xml 修改布局

// res/layout/navigation_bar.xml 中定义了 Back / Home / Recent 按钮
// 可添加自定义按钮：

<Button
    android:id="@+id/custom_button"
    style="@style/NavigationBarButton"
    android:src="@drawable/ic_custom"
    android:onClick="onCustomButtonClick" />
```

然后在 `NavigationBarView.java` 中处理点击：
```java
// NavigationBarView.java
private View mCustomButton;

@Override
public void onFinishInflate() {
    super.onFinishInflate();
    mCustomButton = findViewById(R.id.custom_button);
    if (mCustomButton != null) {
        mCustomButton.setOnClickListener(v -> {
            // 自定义行为：启动特定 Activity / 发送广播等
            Intent intent = new Intent("com.example.CUSTOM_ACTION");
            mContext.sendBroadcast(intent);
        });
    }
}
```

**方法 C：通过配置控制导航栏模式**

```xml
<!-- config.xml -->
<!-- 0 = 三键导航, 1 = 双键(Back+Home), 2 = 手势导航 -->
<integer name="config_navBarInteractionMode">0</integer>

<!-- 控制导航栏是否可隐藏 -->
<bool name="config_hideNavigationBars">false</bool>
```

---

### 场景 3：添加自定义 QS Tile

**适用场景：** 添加快捷开关（如车载空调、Mode 切换等）。

**步骤 1：创建 Tile 类**

```java
// src/com/android/systemui/qs/tiles/CustomModeTile.java
package com.android.systemui.qs.tiles;

import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.res.R;

import javax.inject.Inject;

public class CustomModeTile extends QSTileImpl<BooleanState> {

    @Inject
    public CustomModeTile(QSHost host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        // 点击切换状态
        boolean newState = !mState.value;
        // 执行自定义逻辑（发送广播 / 调用系统服务等）
        Intent intent = new Intent("com.example.MODE_TOGGLE");
        intent.putExtra("enabled", newState);
        mContext.sendBroadcast(intent);

        refreshState(newState);
    }

    @Override
    public Intent getLongClickIntent() {
        // 长按跳转设置页
        return new Intent("android.settings.CUSTOM_MODE_SETTINGS");
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_custom_mode_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = arg instanceof Boolean ? (Boolean) arg : state.value;
        state.icon = ResourceIcon.get(
            state.value ? R.drawable.ic_custom_on : R.drawable.ic_custom_off);
        state.label = mContext.getString(R.string.quick_settings_custom_mode_label);
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }
}
```

**步骤 2：注册 Tile（AOSP 14 方式）**

在 `QSTileFactories.java` 中添加：

```java
// src/com/android/systemui/qs/tiles/QSTileFactories.java
// 找到已有的 @Inject 构造的 Tile 列表

@Inject
CustomModeTile.Provider mCustomModeTileProvider;

// 在 createTile() 方法中添加：
case "custom_mode":
    return mCustomModeTileProvider.get();
```

**步骤 3：在 config.xml 中声明 Tile**

```xml
<!-- res/values/config.xml -->
<!-- QS Tile 列表，逗号分隔 -->
<string-array name="config_quickSettingsTilesStock" translatable="false">
    <item>wifi</item>
    <item>bt</item>
    <item>dnd</item>
    <!-- 新增 -->
    <item>custom_mode</item>
</string-array>
```

**步骤 4：添加字符串资源**

```xml
<!-- res/values/strings.xml -->
<string name="quick_settings_custom_mode_label">自定义模式</string>
```

**步骤 5：添加图标资源**

```
res/drawable/ic_custom_on.xml   ← VectorDrawable，开启状态
res/drawable/ic_custom_off.xml  ← VectorDrawable，关闭状态
```

---

### 场景 4：修改锁屏行为

**适用场景：** 车载设备通常不需要锁屏，或需要自定义锁屏解锁逻辑。

**方法 A：全局禁用锁屏（系统级，推荐）**

```java
// frameworks/base/services/core/java/com/android/server/locksettings/LockSettingsService.java
// 或通过 DevicePolicyManager API 禁用

// 最简单的方式：在系统属性中设置
adb shell settings put secure lockscreen.disabled 1

// 代码层面：在 KeyguardViewMediator.java 中拦截
// KeyguardViewMediator.java
private boolean mExternallyEnabled = true;

public void setKeyguardEnabled(boolean enabled) {
    mExternallyEnabled = enabled;
    if (!enabled) {
        // 直接隐藏锁屏
        hideLocked();
    }
}
```

**方法 B：跳过锁屏安全验证**

```java
// KeyguardSecurityContainerController.java
// 找到 showNextSecurityScreenOrFinish() 方法

private boolean showNextSecurityScreenOrFinish(boolean wasAuthenticated) {
    // 车载场景：直接返回 true 跳过验证
    if (isAutomotiveMode()) {
        return true;  // ← 新增，跳过所有安全验证
    }
    // ... 原有逻辑
}
```

**方法 C：通过 overlay 配置禁用**

```xml
<!-- vendor/xxx/overlay/SystemUI/res/values/config.xml -->
<bool name="config_enableKeyguardService">false</bool>
```

> **⚠️ 注意：** 禁用锁屏需要同步修改 `KeyguardUpdateMonitor` 的状态判断，否则可能导致 `system_server` 状态不一致。

---

### 场景 5：添加自定义 SystemUI 组件

**适用场景：** 需要在 SystemUI 启动时初始化一个全局组件（如车载信号监听器、CAN 数据接收器等）。

**步骤 1：创建 CoreStartable 实现**

```java
// src/com/android/systemui/automotive/AutomotiveCanReceiver.java
package com.android.systemui.automotive;

import com.android.systemui.CoreStartable;
import android.content.Context;
import android.util.Log;

import javax.inject.Inject;

public class AutomotiveCanReceiver extends CoreStartable {

    private static final String TAG = "AutomotiveCanReceiver";

    @Inject
    public AutomotiveCanReceiver(Context context) {
        super(context);
    }

    @Override
    public void start() {
        Log.d(TAG, "AutomotiveCanReceiver started");
        // 初始化 CAN 信号监听
        // 注册广播接收器
        // 启动后台服务连接等
    }

    @Override
    public void onBootCompleted() {
        Log.d(TAG, "Boot completed, starting CAN data polling");
        // 开机完成后开始 CAN 数据轮询
    }
}
```

**步骤 2：在 Dagger Module 中注册**

```java
// src/com/android/systemui/dagger/SystemUICoreStartableModule.java
// 添加绑定

@Provides
@IntoSet
CoreStartable provideAutomotiveCanReceiver(AutomotiveCanReceiver impl) {
    return impl;
}
```

**步骤 3：确保构造函数可注入**

```java
// AutomotiveCanReceiver 构造函数标记 @Inject（已在步骤 1 中完成）
// Dagger 会自动解析 Context 依赖
```

---

## 五、编译与验证

### 5.1 单独编译 SystemUI

```bash
source build/envsetup.sh
lunch sdk_phone_x86_64-eng   # 或你的车载 target

# 单独编译 SystemUI 模块
make SystemUI -j32

# 产物路径
# out/target/product/xxx/system/priv-app/SystemUI/SystemUI.apk
```

### 5.2 推送到设备验证

```bash
# 模拟器 / userdebug 版本
adb root
adb remount
adb push out/target/product/xxx/system/priv-app/SystemUI/SystemUI.apk \
        /system/priv-app/SystemUI/
adb shell am force-stop com.android.systemui

# 或用 killall 重启 SystemUI 进程
adb shell killall com.android.systemui

# 查看 SystemUI 日志
adb logcat -s SystemUI:* SystemUICore:* | grep -i "your_tag"
```

### 5.3 验证清单

| 验证项 | 命令 / 方法 |
|--------|------------|
| SystemUI 是否正常启动 | `adb shell dumpsys activity service SystemUIService` |
| QS Tile 是否加载 | `adb shell cmd statusbar list-tiles`（如有） |
| 导航栏状态 | `adb shell dumpsys SurfaceFlinger \| grep -i nav` |
| 锁屏状态 | `adb shell dumpsys Keyguard` 或 `adb shell dumpsys window \| grep -i keyguard` |
| Dagger 组件绑定 | `adb logcat -s Dagger:*` |
| 布局层次 | `adb shell dumpsys activity top \| grep -A 20 systemui` |

---

## 六、踩坑避坑清单

### 6.1 Dagger 相关

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| 编译报 `Cannot resolve symbol` | 新类未在 Module 中注册 | 在对应 `*Module.java` 添加 `@Provides` / `@Binds` |
| 运行时 `NullPointerException` on injected field | 构造函数未标记 `@Inject` | 确保唯一的 `@Inject` 构造函数 |
| Tile 不显示 | `config_quickSettingsTilesStock` 未声明 | 在 `config.xml` 的 `string-array` 中添加 tile key |
| CoreStartable 不执行 `start()` | 未在 `SystemUICoreStartableModule` 注册 `@IntoSet` | 添加 `@Provides @IntoSet` 绑定 |

### 6.2 布局/资源相关

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| 修改 `config.xml` 不生效 | 被其他 overlay 覆盖 | `adb shell cmd overlay list` 检查 overlay 优先级 |
| 状态栏隐藏后下拉面板无法触发 | `NotificationPanelViewController` 依赖状态栏高度 | 同步修改 `config.xml` 中的 `status_bar_height` 或代码中面板触发逻辑 |
| 图标显示为黑色方块 | VectorDrawable 未正确定义 | 检查 `res/drawable/` 下的 XML，确保 `<vector>` 标签正确 |

### 6.3 进程/权限相关

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| SystemUI crash on boot | 新增代码有未捕获异常 | `adb logcat -s AndroidRuntime:*` 查看 crash 栈 |
| 权限不足导致功能失效 | SystemUI 需要系统级权限 | 在 `AndroidManifest.xml` 添加对应权限（`signature\|privileged` 级别） |
| `adb remount` 失败 | `vbmeta` 验证未关闭 | `adb shell setprop sys.boot_completed 0` 后 `adb disable-verity` 并重启 |

### 6.4 车载场景特有

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| 车载横屏下状态栏位置异常 | `CentralSurfacesImpl` 默认竖屏布局 | 检查 `res/layout-land/` 下是否有对应横屏布局覆盖 |
| 导航栏在多 Display 下重复创建 | `NavigationBarController` 按 Display 管理 | 在 `createNavigationBar()` 中过滤目标 Display ID |
| 锁屏禁用后 `system_server` 状态不更新 | `KeyguardViewMediator` 与 `WindowManagerService` 状态不同步 | 同步调用 `mUpdateMonitor.reportKeyguardDismissed()` |

---

## 七、关键类 / 函数速查表

| 类名 | 核心方法 | 说明 |
|------|---------|------|
| `SystemUIApplication` | `onCreate()` / `startServicesIfNeeded()` | 入口，启动所有 CoreStartable |
| `CentralSurfacesImpl` | `start()` / `makeStatusBarView()` / `animateCollapsePanels()` | 状态栏核心控制器 |
| `NavigationBarController` | `createNavigationBar()` / `setNavigationMode()` | 导航栏管理 |
| `KeyguardViewMediator` | `showLocked()` / `hideLocked()` / `onWakeAndUnlock()` | 锁屏状态机 |
| `QSPanel` | `addTile()` / `removeTile()` | QS 面板 Tile 管理 |
| `QSFactoryImpl` | `createTile(String spec)` | 根据 spec 创建 Tile |
| `NotificationListener` | `onNotificationPosted()` / `onNotificationRemoved()` | 通知监听回调 |
| `StatusBarIconController` | `setIcon()` / `removeIcon()` | 状态栏图标管理 |
| `CoreStartable` | `start()` / `onBootCompleted()` | 组件生命周期 |

---

## 八、修改流程建议

```
需求分析 → 确定改动层级（资源/代码/Dagger）
    ↓
配置优先 → 能用 config.xml / overlay 解决的不改代码
    ↓
代码修改 → 遵循 Dagger 注入规范，新组件走 CoreStartable
    ↓
单独编译 → make SystemUI -j32
    ↓
推送验证 → adb push + force-stop + logcat
    ↓
全量编译 → make -j32（确保无依赖遗漏）
    ↓
刷机回归 → 在目标设备上完整验证所有交互路径
```

---

> **文档版本：** v1.0 | **AOSP 版本：** android-14.0.0_rXX | **内核分支：** android14-6.1
>
> **维护说明：** 实际修改时请以本地 AOSP 源码为准，类名/方法名可能因具体 release 版本有细微差异。
