# SystemUI 锁屏详解

## 一、整体架构

锁屏（Keyguard）运行在 **SystemUI 进程**（`com.android.systemui`）中，而非 system_server。整个体系分为三层：

```
┌─────────────────────────────────────────────────────────────────────┐
│                    system_server 进程                                │
│  ┌──────────┐   ┌──────────────────────────────────────┐           │
│  │   AMS    │   │  WMS                                 │           │
│  │ system   │   │  ┌──────────────────────────────────┐ │           │
│  │ Ready()  │───>│  │ IKeyguardService.aidl (Binder) │ │           │
│  └──────────┘   │  │ setKeyguardService() 持有引用   │ │           │
│                 │  └──────────────────────────────────┘ │           │
│                 └──────────────────────────────────────┘           │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ Binder 跨进程调用
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    SystemUI 进程                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  KeyguardService (Binder 入口, 实现 IKeyguardService.aidl)   │  │
│  │  职责: WMS/systemReady → start() → 委托 KVM                 │  │
│  └────────────────────┬─────────────────────────────────────────┘  │
│                       ▼                                            │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  KeyguardViewMediator (KVM) —— 核心状态机                   │  │
│  │  * Handler 串行 (KEYGUARD_SHOW/KEYGUARD_HIDE 消息)          │  │
│  │  * 持有6个核心状态位                                        │  │
│  │  * 睡眠唤醒钩子: GoingToSleep / WakingUp 各 2 阶段          │  │
│  └────────────────────┬─────────────────────────────────────────┘  │
│                       ▼                                            │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  KeyguardViewControllerImpl (AOSP 13+) — 窗口/视图控制      │  │
│  │  替代旧 KeyguardViewManager                                  │  │
│  └────────────────────┬─────────────────────────────────────────┘  │
│                       ▼                                            │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  KeyguardSecurityContainer — 安全界面容器                    │  │
│  │  ┌──────────┐ ┌─────────┐ ┌──────────┐ ┌─────────────┐     │  │
│  │  │滑动解锁屏│ │Pattern  │ │PIN 键盘  │ │Password输入 │     │  │
│  │  │(无凭证)  │ │输入界面 │ │界面      │ │界面        │     │  │
│  │  └──────────┘ └─────────┘ └──────────┘ └─────────────┘     │  │
│  └────────────────────┬─────────────────────────────────────────┘  │
│                       ▼                                            │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  KeyguardSecurityModel — 安全等级决策                       │  │
│  │  None(滑动) < Pattern < PIN < Password < SimPin/SimPuk      │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  辅助组件:                                                          │
│  * KeyguardUpdateMonitor — 事件汇聚中心（充电/着屏/指纹/人脸）    │
│  * KeyguardStateController — 状态查询接口（isShowing/isOccluded）│
│  * KeyguardBouncer — PIN/Pattern/Password 浮层                    │
└─────────────────────────────────────────────────────────────────────┘
```

**源码路径：** `packages/apps/SystemUI/src/com/android/systemui/keyguard/`

---

## 二、核心类详解

### 2.1 KeyguardService —— Binder 入口

| 方法 | 调用方 | 作用 |
|------|--------|------|
| `start()` | SystemUI 启动时 | 初始化 KVM |
| `onSystemReady()` | AMS systemReady 回调 | 通知 KVM 系统就绪 |
| `doKeyguardTimeout()` | WMS lockNow | 立即锁屏 |
| `doKeyguardLater()` | Settings 延时锁 | N 秒后锁 |
| `setOccluded(boolean)` | 全屏Activity | 遮挡/恢复锁屏 |
| `keyguardDone()` | 解锁成功回调 | 下发 KVM dismiss |
| `dismiss()` | 生物识别/Bouncer 解锁 | 信任代理解锁 |

KeyguardService 的核心代码很短：它实现 `IKeyguardService.aidl`，所有 Binder 方法内部委托给 `KeyguardViewMediator`。它的注册时机在 WMS 启动后：

```java
// WMS.systemReady() 中
mKeyguardController.setKeyguardService(
    mContext.getSystemService(KeyguardService.class));
```

### 2.2 KeyguardViewMediator —— 状态机

KVM 使用 **内部 Handler 串行化** 所有锁屏操作，避免竞态：

```
Handler 消息类型:
  MSG_KEYGUARD_SHOW       -> handleShow()
  MSG_KEYGUARD_HIDE       -> handleHide()
  MSG_KEYGUARD_DONE       -> handleDone()
  MSG_KEYGUARD_GOING_AWAY -> handleGoingAway()
  MSG_SET_OCCLUDED        -> handleSetOccluded()
  MSG_KEYGUARD_TIMEOUT    -> doKeyguardTimeout()
  MSG_DOZE_CHANGED        -> handleDozeChanged()
```

**核心状态位：**

| 状态位 | 含义 | 影响 |
|--------|------|------|
| `mShowing` | 锁屏是否当前显示 | 控制 KEYGUARD_DIALOG 窗口可见性 |
| `mOccluded` | 被全屏界面遮挡 | 如来电/导航时隐藏锁屏 |
| `mInputRestricted` | 输入是否受限 | true 时系统按键/触摸被拦截 |
| `mSwitchingUser` | 用户切换中 | 切换完成才恢复锁屏 |
| `mDeviceLocked` | 设备是否锁定 | 区分屏幕超时 vs 设备锁定 |
| `mScreenOn` | 屏幕是否亮着 | 暗屏时锁屏不显示 |

**睡眠唤醒四阶段钩子：**

```
onStartedGoingToSleep()     -> 开始灭屏（保存状态）
onFinishedGoingToSleep()    -> 灭屏完成（显示锁屏准备）
onStartedWakingUp()         -> 开始亮屏（准备解锁）
onFinishedWakingUp()        -> 亮屏完成（显示锁屏并等待输入）
```

这是一个关键的时序点：`onFinishedGoingToSleep` 时锁屏尚未显示，`onStartedWakingUp` 时才真正把锁屏窗口挂载到 WMS。

---

## 三、启动方法完整解析

### 第 1 步：`AMS.systemReady()`

```java
// SystemServer.java, startOtherServices() 末尾
mActivityManagerService.systemReady(() -> {
    // 启动所有 persistent 应用（SystemUI 即其中之一）
    startPersistentApps(PackageManager.MATCH_DIRECT_BOOT_AWARE);

    // 同时向已注册的 SystemServiceManager 回调
    mSystemServiceManager.startBootPhase(
        SystemService.PHASE_BOOT_COMPLETED);
});
```

SystemUI 在 AndroidManifest.xml 中声明 `android:persistent="true"`，AMS 在 systemReady 时会无条件启动它（无论是否在后台限制列表）。

### 第 2 步：`SystemUIService.onCreate()`

```java
// SystemUIService.java
public void onCreate() {
    super.onCreate();
    ((SystemUIApplication) getApplication()).startServicesIfNeeded();
}
```

`SystemUIApplication.startServicesIfNeeded()` 反射加载 `config_systemUIServiceComponents` 数组中定义的组件，其中就包括：

| 组件类 | config 键名 |
|--------|-------------|
| `KeyguardService` | `config_systemUIServiceComponents` |
| `KeyguardViewMediator` | `config_systemUIServiceComponents` |
| `KeyguardUpdateMonitor` | `config_systemUIServiceComponents` |
| `KeyguardViewControllerImpl` | `config_systemUIServiceComponents` |

### 第 3 步：`KeyguardService.start()`

```java
// KeyguardService.java
public void start() {
    mKeyguardViewMediator.start();   // 核心初始化
}
```

KVM.start() 内部做：
1. 注册广播接收器：ACTION_BOOT_COMPLETED、Intent.ACTION_USER_PRESENT 等
2. 初始化 KeyguardUpdateMonitor
3. 初始化 KeyguardViewControllerImpl（AOSP 13+）
4. 读取 Settings 配置（锁屏超时、锁屏类型）

### 第 4-11 步：窗口挂载链

`onSystemReady()` 触发后，KVM 开始准备显示锁屏：

```java
onSystemReady() {
    // 4. 注册 KeyguardUpdateMonitor 监听
    mUpdateMonitor.registerCallback(mUpdateMonitorCallback);

    // 5. 尝试显示锁屏
    if (shouldShow()) {
        handleShow();  // post 到 Handler
    }
}
```

`handleShow()` -> `showLocked()` 内部：

```java
showLocked() {
    // 6. 设置锁屏视图
    mKeyguardViewControllerImpl.setupLocked();  // 7

    // 8. 调用 showKeyguard()
    mKeyguardViewControllerImpl.showKeyguard();  // 9

    // 10. 窗口过渡动画
    // 内部调用 WindowManager.addView() -> KEYGUARD_DIALOG -> 11
}
```

### 完整时序图

```
AMS/SystemServer       SystemUI               KeyguardService        KeyguardViewMediator     WMS
    |                       |                       |                       |                  |
①   |--systemReady()------->|                       |                       |                  |
    |  (启动持久应用)         |                       |                       |                  |
②   |                       |--onCreate()---------->|                       |                  |
    |                       |  startServicesIf()   |                       |                  |
③   |                       |                       |--start()------------->| (CoreStartable)  |
    |                       |                       |                       |                  |
④   |                       |                       |                       |--onSystemReady() |
⑤   |                       |                       |                       |--registerKUM()   |
⑥   |                       |                       |                       |--handleShow()    |
⑦   |                       |                       |                       |--setupLocked()   |
⑧   |                       |                       |                       |--showKeyguard()  |
⑨   |                       |                       |                       |--doTransition()  |
⑩   |                       |                       |                       |--addView()------>|
    |                       |                       |                       | KEYGUARD_DIALOG  |
⑪   |                       |                       |                       |<-- 窗口挂载成功 --|
    |                       |                       |                       |                  |
    |                       |                       |                       | 锁屏界面已显示    |
```

---

## 四、锁屏窗口机制

### 4.1 KEYGUARD_DIALOG 窗口

锁屏窗口使用特殊的窗口类型：

```java
// WindowManager.LayoutParams
public static final int TYPE_KEYGUARD_DIALOG = FIRST_SYSTEM_WINDOW + 24;
// 值 = 2024
```

**窗口层级位置**（由高到低）：

```
TYPE_APPLICATION_OVERLAY       (最后一名)
TYPE_STATUS_BAR                (状态栏)
TYPE_KEYGUARD_DIALOG           (锁屏)  <- 在这里
TYPE_NAVIGATION_BAR            (导航栏)
TYPE_SYSTEM_ALERT              (系统弹窗)
TYPE_BOOT_PROGRESS             (开机画面)
TYPE_WALLPAPER                 (壁纸 - 最底层)
```

**关键特性：**
- 仅主屏（Display 0）创建
- SystemUI 进程通过 `WindowManager.addView()` 挂载
- 窗口标题为 "Keyguard"
- 窗口动画通过 `doTransition()` 触发（缩放+淡入）

### 4.2 showWhenLocked 与锁屏共存

应用侧控制窗口在锁屏之上的显示规则：

| 方式 | 说明 | 示例 |
|------|------|------|
| `android:showWhenLocked="true"` | Activity 属性，窗口显示在锁屏之上 | 音乐播放器、导航 |
| `setShowWhenLocked(true)` | 代码动态设置 | 来电界面 |
| `FLAG_SHOW_WHEN_LOCKED` | 旧方式（已废弃） | 兼容旧代码 |
| `setTurnScreenOn(true)` | 解锁时自动亮屏 | 闹钟提醒 |

**实现原理：** WMS 的 `KeyguardController` 检查 Activity 窗口是否带有 `showWhenLocked` 标记，如有则将锁屏窗口 `mOccluded` 置为 true，锁屏隐藏在后，Activity 可见。

---

## 五、解锁流程

### 5.1 完整解锁时序

```
用户触发解锁（滑动/指纹/人脸/密码）
        |
        v
KeyguardSecurityContainer.onUserInput()
  | 验证通过 |                   验证失败
  |         |
  v         v
  KeyguardSecurityModel.updateSecurityMethod()
        |
        v
KeyguardSecurityContainer.callback.keyguardDone()
  | (SystemUI 内部)
  v
onKeyguardDismissed()   <- KeyguardStateController 状态更新
        |
        v
keyguardGoingAway()     <- 通知 WMS 播放解锁动画
  | 窗口动画期间 KEYGUARD_DIALOG 渐出
  v
WMS 完成动画 -> 移除锁屏窗口
        |
        v
发送 ACTION_USER_PRESENT 广播  <- 各 App 监听此广播恢复 UI
        |
        v
KeyguardUpdateMonitor 重置认证状态
```

### 5.2 三种解锁触发源

**方式 A：滑动解锁（无凭证）**

```
触摸 KeyguardSecurityContainer 滑动区域
-> MotionEvent.ACTION_UP 在滑动范围
-> onUserInput() -> 直接 keyguardDone()
-> 无 Bouncer，无验证
```

**方式 B：凭证解锁（PIN/Pattern/Password）**

```
输入完整凭证 -> 验证 -> gatekeeperd 校验
-> 通过 -> 解锁 CE 密钥 -> keyguardDone()
-> 失败 -> 计数 +1 -> 超限 lockout 30s
```

**方式 C：生物识别解锁（指纹/人脸）**

```
BiometricService 回调 onBiometricAuthenticated()
-> KeyguardUpdateMonitor 分发到 KVM
-> 无安全凭证时直接 dismiss（无 Bouncer）
-> 有安全凭证时：指纹->Bouncer 自动填充，人脸->直接解锁（可信代理）
```

### 5.3 FBE 解锁时序关系

```
gatekeeperd 验证通过
  |
  v
USER_UNLOCKED        <- 此时 CE 密钥可用，但锁屏可能还在动画
  | (更早)
  v
锁屏窗口动画完成
  |
  v
ACTION_USER_PRESENT  <- 锁屏完全消失，各 App 恢复 UI
  | (更晚)
```

**重要：** 车载/工控定制如果要恢复后台任务，应监听 `ACTION_USER_PRESENT` 而不是 `onUnlock` 回调，因为 `USER_UNLOCKED` 发生时界面可能尚未准备就绪。

---

## 六、生物识别认证体系

### 6.1 集成链路

```
BiometricService (system_server)
  | Binder
  v
BiometricManager (SystemUI 客户端)
  |
  +-- 指纹: FingerprintManager -> FingerprintService (fingerprintd HAL)
  |
  +-- 人脸: FaceManager -> FaceService (face HAL)
        |
        v
KeyguardUpdateMonitor 注册 BiometricCallback
  | onBiometricAuthenticated -> KVM.dismiss()
  | onBiometricError -> 显示错误提示
  | onBiometricAcquired -> 更新 UI 提示
  v
LockIconView 显示状态: 待识别 -> 识别中 -> 通过/失败
```

### 6.2 失败锁定策略

| 认证方式 | 连续失败次数 | 惩罚 | 恢复方式 |
|---------|-------------|------|---------|
| 指纹 | 5 次 | lockout 30 秒 | 等待或 PIN/密码解锁 |
| 人脸 | 5 次 | lockout 30 秒 | 等待或 PIN/密码解锁 |
| 密码 | 无限制 | 无 | - |
| PIN | 取决于 DevicePolicy | 可配置擦除 | - |

### 6.3 密钥认证集成

```java
// 创建需要用户认证才能使用的密钥
KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder("key_name")
    .setUserAuthenticationRequired(true)  // 必须解锁才能用
    .setUserAuthenticationValidityDurationSeconds(30)  // 解锁后 30s 内有效
    .build();
```

此密钥在锁屏解锁后的窗口期内可用，超时后需要重新认证。

---

## 七、AOD / Doze 联动

### 7.1 状态切换

```
屏幕亮着 (锁屏显示)         屏幕亮着 (锁屏显示)
      |                          ^
      | onStartedGoingToSleep    | onStartedWakingUp
      v                          |
屏幕灭 (Doze 模式) ---------------+
      |
      +- AOD 启用 -> 显示 AOD 界面 (锁屏窗口隐藏)
      |             双击/抬手亮屏 -> wakeAndUnlock()
      |
      +- AOD 禁用 -> 屏幕全黑，直接深度休眠
```

**KeyguardUpdateMonitor 监听 dozing 状态：**

```java
// handleDozeChanged()
public void handleDozeChanged(boolean dozing) {
    if (dozing) {
        // 进入 Doze -> 移除 KEYGUARD_DIALOG 窗口
        mViewMediator.onDozingChanged(true);
        // 显示 AOD 内容（如已配置）
    } else {
        // 退出 Doze -> 恢复 KEYGUARD_DIALOG 窗口
        mViewMediator.onDozingChanged(false);
    }
}
```

### 7.2 车载 AOD 建议

| 场景 | 建议 | 原因 |
|------|------|------|
| 车载主驾 | 禁用 AOD | 耗电、夜间干扰驾驶 |
| 工控面板 | 禁用 AOD | 无需常显信息 |
| 手机 | 启用 AOD | 用户习惯 |

禁用方法：修改 `config_dozeComponent` 为空或替换为自定义组件。

---

## 八、车载定制要点

### 8.1 常见定制项

| 需求 | 方案 | 侵入性 |
|------|------|--------|
| **完全去锁屏** | RRO 覆盖 `config_disableKeyguard=true` | 低 |
| **保留滑动锁屏，禁 PIN** | 不设置 LockSettings 凭证 + 锁屏界面隐藏安全提示 | 中 |
| **延时锁屏** | `doKeyguardLater()` + Settings 超时可配 | 低 |
| **定制锁屏 UI** | RRO overlay 覆盖 keyguard_* 布局和资源 | 低 |
| **禁用 Bouncer** | 无安全凭证时 Bouncer 自然消失 | 无 |
| **定制 PIN 键盘** | 修改 KeyguardSecurityContainer 布局 | 中 |
| **副驾/仪表屏去锁** | DisplayPolicy 控制，仅主屏锁屏 | 低 |
| **安全警示文案** | overlay 覆盖 strings.xml | 低 |

### 8.2 config_disableKeyguard 深入

配置位置：

```xml
<!-- frameworks/base/core/res/res/values/config.xml -->
<bool name="config_disableKeyguard">false</bool>
```

车载覆盖方式（device 目录下创建 RRO）：

```xml
<!-- device/mycompany/mydevice/overlay/frameworks/base/core/res/res/values/config.xml -->
<bool name="config_disableKeyguard">true</bool>
```

**注意：**
- 仅禁用 UI 锁屏，**不影响 FBE 的 DE/CE 密钥**
- `ACTION_USER_PRESENT` 广播仍会发送（晚于 onUnlock）
- 如果同时无安全凭证，DE 和 CE 密钥等价（无密码加密）

### 8.3 锁屏 UI 定制组件

| 组件 | 类名 | 定制内容 |
|------|------|---------|
| 大时钟 | `KeyguardStatusView` | 字体/大小/颜色/日期格式 |
| 时钟组件 | `KeyguardClockView` (AOSP 12+) | 时钟样式、秒针显隐 |
| 锁定图标 | `LockIconView` | 解锁提示图标（指纹/人脸） |
| 底部快捷区 | `KeyguardBottomAreaView` | 相机、拨号、解锁提示入口显隐 |
| 安全容器 | `KeyguardSecurityContainer` | 凭证输入界面整体替换 |

---

## 九、调试命令

```bash
# 锁屏状态查询
dumpsys activity activities | grep -i keyguard

# 窗口层级（确认 KEYGUARD_DIALOG 存在）
dumpsys window windows | grep -i keyguard

# 模拟锁屏/解锁操作
adb shell input keyevent KEYCODE_POWER    # 灭屏
adb shell input keyevent KEYCODE_WAKEUP   # 亮屏
adb shell locksettings set-pin 1234        # 设置 PIN
adb shell locksettings clear               # 清除凭证

# 锁屏相关日志
logcat -b all | grep -iE "KeyguardViewMediator|KeyguardService|KeyguardUpdateMonitor"

# 生物识别测试
adb shell cmd biometric_manager authenticate
adb shell cmd fingerprint acquire  # 模拟指纹识别
```

---

## 十、源码速查

| 类 | 路径 |
|----|------|
| KeyguardService | `packages/apps/SystemUI/src/com/android/systemui/keyguard/KeyguardService.java` |
| KeyguardViewMediator | `packages/apps/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java` |
| KeyguardViewControllerImpl | `packages/apps/SystemUI/src/com/android/systemui/keyguard/KeyguardViewControllerImpl.java` |
| KeyguardSecurityContainer | `packages/apps/SystemUI/src/com/android/systemui/keyguard/KeyguardSecurityContainer.java` |
| KeyguardSecurityModel | `packages/apps/SystemUI/src/com/android/systemui/keyguard/KeyguardSecurityModel.java` |
| KeyguardUpdateMonitor | `packages/apps/SystemUI/src/com/android/systemui/keyguard/KeyguardUpdateMonitor.java` |
| KeyguardStateController | `packages/apps/SystemUI/src/com/android/systemui/statusbar/policy/KeyguardStateController.java` |
| KeyguardBouncer | `packages/apps/SystemUI/src/com/android/systemui/keyguard/KeyguardBouncer.java` |
| LockIconView | `packages/apps/SystemUI/src/com/android/systemui/statusbar/phone/LockIconView.java` |
| KeyguardStatusView | `packages/apps/SystemUI/src/com/android/systemui/keyguard/KeyguardStatusView.java` |
| IKeyguardService.aidl | `frameworks/base/core/java/android/app/IKeyguardService.aidl` |
| KeyguardController (WMS) | `frameworks/base/services/core/java/com/android/server/wm/KeyguardController.java` |
| LockSettingsService | `frameworks/base/services/core/java/com/android/server/locksettings/LockSettingsService.java` |
| BiometricService | `frameworks/base/services/core/java/com/android/server/biometrics/BiometricService.java` |
| KeyguardService (AOSP 10 Manifest) | `frameworks/base/packages/SystemUI/AndroidManifest.xml` (line 546-549) |
| StatusBarKeyguardViewManager | `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/StatusBarKeyguardViewManager.java` |
| KeyguardLifecyclesDispatcher | `frameworks/base/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardLifecyclesDispatcher.java` |
| PhoneWindowManager (WMP) | `frameworks/base/services/core/java/com/android/server/policy/PhoneWindowManager.java` |
| BiometricUnlockController | `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/BiometricUnlockController.java` |

---

## 十一、AOSP 10 启动流程源码级分析

以下分析基于 AOSP 10 实际源码，与 AOSP 13+ 的启动链路存在显著差异。

### 11.1 关键差异一览

| 维度 | AOSP 10 | AOSP 13+ |
|------|---------|----------|
| KeyguardService 启动方式 | 作为 exported bound Service，被 system_server 的 WindowManagerPolicy 绑定 | 作为 CoreStartable 由 SystemUIApplication 统一启动 |
| 视图管理器 | `StatusBarKeyguardViewManager` | `KeyguardViewControllerImpl`（替代旧 ViewManager） |
| 状态栏类名 | `StatusBar` | `CentralSurfacesImpl`（Android 12+ 重命名） |
| `setupLocked()` 创建者 | `KeyguardViewMediator` 的 `start()` 中直接调用 | `KeyguardService.start()` 委托 KVM 间接调用 |
| 生命周期派发 | `KeyguardLifecyclesDispatcher` | 集成到 `KeyguardViewControllerImpl` |

### 11.2 四段启动链路

#### 第一阶段：KeyguardService 被 system_server 绑定

`KeyguardService` 在 `AndroidManifest.xml` 中声明为 exported 的 Service：

```xml
<!-- frameworks/base/packages/SystemUI/AndroidManifest.xml, line 546-549 -->
<service
    android:name=".keyguard.KeyguardService"
    android:exported="true"
    android:enabled="@bool/config_enableKeyguardService" />
```

开机过程中，`system_server` 里的 `WindowManagerPolicy`（PhoneWindowManager）通过 `IKeyguardService` 接口绑定这个 Service。绑定触发 `KeyguardService.onCreate()`，这是 SystemUI 锁屏体系的**首个入口**（而非 AMS.systemReady 启动 persistent 应用——那是 AOSP 13+ 的行为）：

```java
// KeyguardService.java, line 46-55
@Override
public void onCreate() {
    ((SystemUIApplication) getApplication()).startServicesIfNeeded();
    mKeyguardViewMediator =
            ((SystemUIApplication) getApplication()).getComponent(KeyguardViewMediator.class);
    mKeyguardLifecyclesDispatcher = new KeyguardLifecyclesDispatcher(
            Dependency.get(ScreenLifecycle.class),
            Dependency.get(WakefulnessLifecycle.class));
}
```

这里首次触发 `startServicesIfNeeded()`，反射初始化所有 SystemUI 默认组件（包括 `KeyguardViewMediator`）。同时创建 `KeyguardLifecyclesDispatcher`，用于将屏幕/唤醒生命周期事件派发给 SystemUI 内部的 `ScreenLifecycle` 和 `WakefulnessLifecycle` 观察者。

`KeyguardService` 本身仅是一个 Binder 转发层。其 `mBinder`（实现 `IKeyguardService.Stub`）收到的所有调用——`onSystemReady()`、`onStartedGoingToSleep()`、`onScreenTurningOn()`、`setKeyguardEnabled()`、`doKeyguardTimeout()` 等——均转交给 `KeyguardViewMediator` 处理。系统框架（PhoneWindowManager、PowerManagerService）正是通过这个 Binder 通道指挥锁屏何时显示、隐藏和退出动画。

#### 第二阶段：KeyguardViewMediator 初始化

`KeyguardViewMediator` 是 SystemUI 默认服务列表中的一项，其 `start()` 在 `startServicesIfNeeded()` 阶段被调用，内部执行 `setupLocked()` 完成核心对象创建：

```java
// KeyguardViewMediator.java, line 726-728
mStatusBarKeyguardViewManager =
        SystemUIFactory.getInstance().createStatusBarKeyguardViewManager(mContext,
                mViewMediatorCallback, mLockPatternUtils);
```

此步骤通过 `SystemUIFactory` 工厂创建出 `StatusBarKeyguardViewManager`（锁屏视图管理器），并传入 `mViewMediatorCallback`（Mediator 对 StatusBar 的回调接口）。同时 `setupLocked()` 还初始化了：

- `KeyguardUpdateMonitor`（锁屏状态监听，单例）
- `LockPatternUtils`（锁屏密码工具）
- `KeyguardDisplayManager`（多屏/第二屏显示）
- 通过 `setShowingLocked(...)` 根据 `config_enableKeyguardService` 与"是否禁用锁屏"预设初始 showing 状态

**与 AOSP 13+ 的关键差异：** AOSP 10 的 `setupLocked()` 直接创建 `StatusBarKeyguardViewManager`，而 AOSP 13+ 使用 `KeyguardViewControllerImpl` 替代了该类。

#### 第三阶段：StatusBar 挂接锁屏体系

`KeyguardViewMediator` 初始化时只建好了管理器对象，尚未与 StatusBar 窗口、Bouncer 容器等视图实体接通。真正的"接线"发生在 `StatusBar.start()` 中调用的 `startKeyguard()`：

```java
// StatusBar.java, line 1285-1304
protected void startKeyguard() {
    Trace.beginSection("StatusBar#startKeyguard");
    KeyguardViewMediator keyguardViewMediator = getComponent(KeyguardViewMediator.class);
    mBiometricUnlockController = new BiometricUnlockController(mContext,
            mDozeScrimController, keyguardViewMediator,
            mScrimController, this, UnlockMethodCache.getInstance(mContext),
            new Handler(), mKeyguardUpdateMonitor, mKeyguardBypassController);
    putComponent(BiometricUnlockController.class, mBiometricUnlockController);
    mStatusBarKeyguardViewManager = keyguardViewMediator.registerStatusBar(this,
            getBouncerContainer(), mNotificationPanel, mBiometricUnlockController,
            mStatusBarWindow.findViewById(R.id.lock_icon_container), mStackScroller,
            mKeyguardBypassController, mFalsingManager);
    // ...
    mKeyguardViewMediatorCallback = keyguardViewMediator.getViewMediatorCallback();
}
```

`registerStatusBar()` 将 StatusBar 实例、Bouncer 容器（`getBouncerContainer()`）、通知面板、`BiometricUnlockController`、锁图标容器、`KeyguardBypassController` 等一一注入 `StatusBarKeyguardViewManager`：

```java
// KeyguardViewMediator.java, line 2071-2080
public StatusBarKeyguardViewManager registerStatusBar(StatusBar statusBar,
        ViewGroup container, NotificationPanelView panelView,
        BiometricUnlockController biometricUnlockController, ViewGroup lockIconContainer,
        View notificationContainer, KeyguardBypassController bypassController,
        FalsingManager falsingManager) {
    mStatusBarKeyguardViewManager.registerStatusBar(statusBar, container, panelView,
            biometricUnlockController, mDismissCallbackRegistry, lockIconContainer,
            notificationContainer, bypassController, falsingManager);
    return mStatusBarKeyguardViewManager;
}
```

在 `StatusBarKeyguardViewManager.registerStatusBar()` 内部，进一步通过 `SystemUIFactory.createKeyguardBouncer(...)` 创建 `KeyguardBouncer`（密码/图案/PIN 输入面板），并建立锁屏视图与通知面板的展开监听关系。至此，Mediator、StatusBar、Bouncer、生物识别解锁控制器全部串成一条协作链。

#### 第四阶段：系统就绪后首次展示锁屏

当 `system_server` 完成启动，回调 `KeyguardService.onSystemReady()` 时，最终走到 `KeyguardViewMediator.handleSystemReady()`：

```java
// KeyguardViewMediator.java, line 788-794
private void handleSystemReady() {
    synchronized (this) {
        if (DEBUG) Log.d(TAG, "onSystemReady");
        mSystemReady = true;
        doKeyguardLocked(null);
        mUpdateMonitor.registerCallback(mUpdateCallback);
    }
}
```

`doKeyguardLocked()` 判断是否需要显示锁屏（是否正在 provisioning、锁屏是否被禁用、SIM 状态等），若需要且尚未显示，调用 `mStatusBarKeyguardViewManager.show(options)`，进而触发 `StatusBar.showKeyguard()` 将锁屏视图挂到状态栏窗口上。当 `needsFullscreenBouncer()` 为真时直接通过 `mBouncer.show()` 显示全屏密码界面：

```java
// StatusBarKeyguardViewManager.java, line 301-328
public void show(Bundle options) {
    mShowing = true;
    mStatusBarWindowController.setKeyguardShowing(true);
    mKeyguardMonitor.notifyKeyguardState(
            mShowing, mKeyguardMonitor.isSecure(), mKeyguardMonitor.isOccluded());
    // ...
}

protected void showBouncerOrKeyguard(boolean hideBouncerWhenShowing) {
    if (mBouncer.needsFullscreenBouncer() && !mDozing) {
        mStatusBar.hideKeyguard();
        mBouncer.show(true /* resetSecuritySelection */);
    } else {
        mStatusBar.showKeyguard();
        // ...
    }
    updateStates();
}
```

### 11.3 AOSP 10 启动时序一览

```
system_server                    SystemUI 进程
    |                                |
    |  (1) 通过 IKeyguardService     |
    | 绑定 KeyguardService           |
    |------------------------------->| KeyguardService.onCreate()
    |                                |  -> startServicesIfNeeded()
    |                                |  -> KeyguardViewMediator.start()
    |                                |  -> setupLocked()
    |                                |    创建 StatusBarKeyguardViewManager
    |                                |    创建 KeyguardUpdateMonitor
    |                                |    创建 LockPatternUtils
    |                                |
    |                                | (2) StatusBar.start()
    |                                |  -> startKeyguard()
    |                                |  -> registerStatusBar()
    |                                |    注入 StatusBar/Bouncer 容器/
    |                                |    通知面板/BiometricUnlockController
    |                                |  -> 创建 KeyguardBouncer
    |                                |
    |  (3) onSystemReady()           |
    |------------------------------->| KeyguardService.onSystemReady()
    |                                |  -> handleSystemReady()
    |                                |  -> doKeyguardLocked()
    |                                |  -> StatusBarKeyguardViewManager.show()
    |                                |  -> showBouncerOrKeyguard()
    |                                |  -> StatusBar.showKeyguard()
    |                                |    锁屏视图首次呈现
    |                                |
    |  (4) 后续事件链路               |
    |  灭屏/亮屏/解锁 -> Binder       |
    |------------------------------->| KeyguardService.mBinder
    |                                |  -> KeyguardViewMediator
    |                                |  -> StatusBarKeyguardViewManager
```

### 11.4 AOSP 10 独有设计要点

1. **KeyguardService 作为 bound Service：** 这是 AOSP 10 的最大设计差异。system_server 通过标准 Android Service 绑定机制拉起 KeyguardService，而非像 AOSP 13+ 那样由 persistent 应用的 SystemUIService 统一管理。

2. **`config_enableKeyguardService` 控制开关：** 此资源布尔值可直接禁用整个 KeyguardService 绑定，是车载去锁的底层开关（但并非唯一开关——`config_disableKeyguard` 控制 UI 层面）。

3. **`KeyguardLifecyclesDispatcher` 独立存在：** AOSP 10 中生命周期的屏幕/唤醒事件由这个独立派发器处理；AOSP 13+ 此逻辑被合并到 `KeyguardViewControllerImpl`。

4. **`StatusBarKeyguardViewManager` 双角色：** 在 AOSP 10 中同时承担视图管理和 Bouncer 协调职责，职责较重。AOSP 13+ 将其拆分为 `KeyguardViewControllerImpl`（窗口/视图控制）+ 精简后的 `StatusBarKeyguardViewManager`（仅 Bouncer 协调）。

5. **`BiometricUnlockController` 在 `startKeyguard()` 中创建：** 生物识别控制器在 StatusBar 阶段创建并与 KVM 关联，而非预先在 `setupLocked()` 中准备——这意味着生物识别模块的初始化晚于锁屏框架的初始化。

6. **`PhoneWindowManager` 作为绑定发起方：** 系统框架侧的 WindowManagerPolicy 负责发起 Service 绑定，而非 AMS。这也意味着锁屏服务的绑定时机与 WMS 初始化时序紧密耦合。

### 11.5 AOSP 10 关键源码文件索引

| 类/文件 | AOSP 10 路径 | 作用 |
|---------|-------------|------|
| `KeyguardService` | `packages/apps/SystemUI/src/com/android/systemui/keyguard/KeyguardService.java` | Binder 入口，system_server 绑定目标 |
| `AndroidManifest.xml` | `frameworks/base/packages/SystemUI/AndroidManifest.xml` (L546-549) | 声明 KeyguardService 为 exported Service |
| `KeyguardViewMediator` | `packages/apps/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java` | 核心状态机，串行化所有锁屏操作 |
| `StatusBar` | `packages/apps/SystemUI/src/com/android/systemui/statusbar/phone/StatusBar.java` | 状态栏实现，`startKeyguard()` 挂接锁屏 |
| `StatusBarKeyguardViewManager` | `packages/apps/SystemUI/src/com/android/systemui/statusbar/phone/StatusBarKeyguardViewManager.java` | 视图管理器，控制 show/hide/Bouncer |
| `KeyguardLifecyclesDispatcher` | `packages/apps/SystemUI/src/com/android/systemui/keyguard/KeyguardLifecyclesDispatcher.java` | 屏幕/唤醒生命周期派发 |
| `BiometricUnlockController` | `packages/apps/SystemUI/src/com/android/systemui/statusbar/phone/BiometricUnlockController.java` | 生物识别解锁控制 |
| `PhoneWindowManager` | `frameworks/base/services/core/java/com/android/server/policy/PhoneWindowManager.java` | system_server 侧发起 Service 绑定 |
| `IKeyguardService.aidl` | `frameworks/base/core/java/android/app/IKeyguardService.aidl` | Binder 接口定义 |
| `SystemUIFactory` | `packages/apps/SystemUI/src/com/android/systemui/SystemUIFactory.java` | 创建 StatusBarKeyguardViewManager 等 |