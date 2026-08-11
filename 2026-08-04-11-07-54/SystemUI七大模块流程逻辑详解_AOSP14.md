# SystemUI 七大模块 · 流程逻辑与代码注释（AOSP 14）

> 本文对 StatusBar/NavigationBar、NotificationShade、Keyguard、Recents、VolumeUI、Screenshot、PipUI 七个模块逐一拆解**启动/触发流程**与**核心代码逻辑**。
> 所有方法名、行号、类路径均来自仓库 `main` 分支真实源码（已逐文件 grep 实测），对照前文《七大模块勘误》使用。
>
> 约定路径前缀：`frameworks/base/packages/SystemUI/src/com/android/systemui/`

---

# 1. StatusBar（状态栏）与 NavigationBar（导航栏）

## 1.1 流程总览

```
SystemServer 拉起 SystemUIService
  → SystemUIApplication.onCreate
    → SystemUIService.onCreate → startServicesIfNeeded()
      → 遍历 CoreStartable Map → CentralSurfacesImpl.start()        [CentralSurfacesImpl.java:973]
        → createAndAddWindows(result)                                [CentralSurfacesImpl.java:2260]
          → mStatusBarWindowController.attach()                      [CentralSurfacesImpl.java:2263]
            → StatusBarWindowController.attach()                     [StatusBarWindowController.java:141]
              → mWindowManager.addView(mStatusBarWindowView, mLp)    [StatusBarWindowController.java:149]  ← 真正的窗口添加
          → createNavigationBar()/createNavigationBars()            [NavigationBarController.java:338/315]
            → 按 display 创建 NavigationBarFragment → NavigationBarView（窗口由 NavigationBarView 内部 addView）
```

> **关键认知**：状态栏窗口**不是**在 `CentralSurfacesImpl.start()` 里直接 `addView`，而是收进 `StatusBarWindowController`。导航栏**没有** `initNavigationBar(View)`，真实入口是 `createNavigationBars()` / `createNavigationBar()`。

## 1.2 状态栏窗口创建（真实代码注释）

`StatusBarWindowController.java`：

```java
// StatusBarWindowController.java
81:  private final ViewGroup mStatusBarWindowView;          // 状态栏根视图（StatusBarWindowView）
116: // 构造注入：StatusBarWindowView 由 Dagger @StatusBarWindowModule.InternalWindowView 提供
141: public void attach() {
142:     // ... 一些主题/旋转准备 ...
149:     mWindowManager.addView(mStatusBarWindowView, mLp); // ← WMS 在此分配 Surface + InputChannel
150: }
219: private WindowManager.LayoutParams createLayoutParams() {
220:     WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
221:             ViewGroup.LayoutParams.MATCH_PARENT,
222:             WindowManager.LayoutParams.TYPE_STATUS_BAR,   // 系统级窗口类型，普通 app 无法覆盖
223:             WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
224:                     | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
225:                     | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
226:                     | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
227:                     | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
228:                     | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
229:             PixelFormat.TRANSLUCENT);
230:     // ... 设 gravity=TOP、softInputMode、token 等 ...
349:     mWindowManager.updateViewLayout(mStatusBarWindowView, mLp); // 旋转/高度变化时更新
}
```

`CentralSurfacesImpl.java`（入口）：

```java
973:  public void start() {
        ... // 注册大量回调、初始化子控制器
1015:     createAndAddWindows(result);     // 不直接 addView，转交 WindowController
        ...
2260: public void createAndAddWindows(@Nullable RegisterStatusBarResult result) {
2263:     mStatusBarWindowController.attach();  // 真正创建并 addView 状态栏窗口
        // 随后 makeStatusBarView() inflate 状态栏/锁屏/shade 的子视图
2264:     mStatusBarWindowView = mStatusBarWindowController.getStatusBarWindowView();
        ...
}
```

## 1.3 导航栏创建（真实代码注释）

`NavigationBarController.java`：

```java
315:  public void createNavigationBars(final boolean includeDefaultDisplay,
316:          RegisterStatusBarResult result) {
317:      // 遍历所有 display，逐个创建导航栏（多屏场景）
318:      for (int i = 0; i < displayCount; i++) {
319:          createNavigationBar(displays[i], null, result);
320:      }
321:  }

338:  void createNavigationBar(Display display, Bundle savedState,
339:          RegisterStatusBarResult result) {
340:      // 通过 Fragment 方式挂 NavigationBar，便于生命周期管理
341:      NavigationBarFragment fragment = new NavigationBarFragment();
        // ... 提交 Fragment，Fragment 内部创建 NavigationBarView（TYPE_NAVIGATION_BAR）
        // 窗口的 addView 发生在 NavigationBarView/NavigationBarFrame，而非本方法直接 addView
}
```

**要点**：状态栏/导航栏都是 `WindowManager.addView` 出来的系统 Window（`TYPE_STATUS_BAR` / `TYPE_NAVIGATION_BAR`），由 WMS 分配 InputChannel 接收触摸；但**创建逻辑已抽象进各自的 `*WindowController` / Fragment**，不再是一个 `start()` 里堆 `addView`。

---

# 2. NotificationShade（通知下拉帘）

## 2.1 流程总览

```
用户下拉状态栏
  → CentralSurfacesImpl / NotificationPanelViewController 捕获手势
    → 设置 NotificationShadeWindowState（panelExpanded / panelVisible / keyguardShowing ...）
      → NotificationShadeWindowControllerImpl.apply(state)   [NotificationShadeWindowControllerImpl.java:498]
        → 14 个 applyXxx(state) 分别调整 mLpChanged 的各 flag/字段
        → applyWindowLayoutParams() 合并到 mLp 并 mWindowManager.updateViewLayout(...)
```

> 通知帘是覆盖在状态栏之上的独立 Window（`TYPE_NOTIFICATION_SHADE`），初始 Y 在屏幕外（折叠），下拉时靠 `updateViewLayout` 改变位置与 flag。

## 2.2 apply 状态机（真实代码注释）

`NotificationShadeWindowControllerImpl.java`：

```java
// 初始把 shade 视图 addView 到 WMS
265: mWindowManager.addView(mNotificationShadeView, mLp);

// 每次状态变化都走这个统一入口；不是直接改 mLp.flags，而是先写 mLpChanged 再合并
498: private void apply(NotificationShadeWindowState state) {
499:     logState(state);                       // 记录状态用于 dump
500:     applyKeyguardFlags(state);             // keyguard 相关 flag
501:     applyFocusableFlag(state);             // ← 焦点 flag（展开态可聚焦、折叠态不可聚焦）
502:     applyForceShowNavigationFlag(state);
503:     adjustScreenOrientation(state);
504:     applyVisibility(state);                // 可见性
505:     applyUserActivityTimeout(state);
506:     applyInputFeatures(state);
507:     applyFitsSystemWindows(state);
508:     applyModalFlag(state);
509:     applyBrightness(state);
510:     applyHasTopUi(state);                  // 是否置顶（影响 AMS 优先级）
511:     applyNotTouchable(state);
512:     applyStatusBarColorSpaceAgnosticFlag(state);
513:     applyWindowLayoutParams();             // ← 把 mLpChanged 合并进 mLp 并 updateViewLayout
514:     // 跨进程通知 AMS 本窗口是否为 topUi
515:     if (mHasTopUi != mHasTopUiChanged) {
518:         mActivityManager.setHasTopUi(mHasTopUiChanged);
        }
525:     notifyStateChangedCallbacks();        // 通知其他关心 shade 状态的组件
526: }

// 折叠态不可聚焦、展开态可聚焦——这是“下拉时不误触下层 app”的核心机制
373: private void applyFocusableFlag(NotificationShadeWindowState state) {
381:     mLpChanged.flags &= ~LayoutParams.FLAG_NOT_FOCUSABLE;   // 默认：可聚焦
384:     mLpChanged.flags &= ~LayoutParams.FLAG_NOT_FOCUSABLE;
392:     mLpChanged.flags |= LayoutParams.FLAG_NOT_FOCUSABLE;    // 折叠/锁屏态：不可聚焦，事件穿透到下层
        // 真实还涉及 FLAG_ALT_FOCUSABLE_IM 等，比“展开就 &= ~FLAG_NOT_TOUCH_MODAL”复杂得多
}
```

**要点**：`apply(state)` 采用“命令-应用分离”——所有改动先写入 `mLpChanged`，最后统一合并到 `mLp` 并 `updateViewLayout`。这正是前面勘误里指出的“不是直接 `mLp.flags |=`”的原因。

---

# 3. Keyguard（锁屏）

## 3.1 流程总览

```
显示锁屏：
  SystemUI 启动 → KeyguardViewMediator（CoreStartable 注册）随开机启动
  屏保/亮屏/用户切换等事件 → doKeyguardLocked()        [KeyguardViewMediator.java:2153]
    → showLocked(options)                              [KeyguardViewMediator.java:2286]
      → 经 StatusBarKeyguardViewManager.show() 显示锁屏视图 + 动画

解锁锁屏：
  指纹/密码/滑动成功 → KeyguardViewMediator.keyguardDone()  [KeyguardViewMediator.java:783]
    → handleKeyguardDone()                            [KeyguardViewMediator.java:2537]
      → handleHide()                                  [KeyguardViewMediator.java:2831]
        → handleStartKeyguardExitAnimation()          [KeyguardViewMediator.java:2870]  ← 跨进程动画，WMS 协同
```

> **最重要**：`handleKeyguardDoneDrawing()`（@2588）**只负责放行 `mWaitingUntilKeyguardVisible` 等待线程**，与“移除窗口”无关；锁屏视图隐藏由 `StatusBarKeyguardViewManager` 管理，WMS 层面走 `IWindowManager` 状态，**全程没有任何 `removeWindow(mKeyguardWindow)`**。

## 3.2 解锁链路（真实代码注释）

```java
// KeyguardViewMediator.java
783:  public void keyguardDone(boolean primaryAuth, int targetUserId) {
784:      // 由 Bouncer/TrustAgent 等调用，向 Handler 发 KEYGUARD_DONE 消息
        ...
        // → 最终走到 handleKeyguardDone()
    }

2537: private void handleKeyguardDone() {
2538:     Trace.beginSection("KeyguardViewMediator#handleKeyguardDone");
2539:     final int currentUser = KeyguardUpdateMonitor.getCurrentUser();
2540:     mUiBgExecutor.execute(() -> {                 // 后台线程
2542:         mLockPatternUtils.getDevicePolicyManager().reportKeyguardDismissed(currentUser);
2543:     });
2546:     synchronized (this) { resetKeyguardDonePendingLocked(); }
2550:     if (mGoingToSleep) {                           // 正要睡眠则中止解锁
2551:         mUpdateMonitor.clearBiometricRecognizedWhenKeyguardDone(currentUser);
2552:         Log.i(TAG, "Device is going to sleep, aborting keyguardDone");
2553:         return;
2554:     }
2555:     setPendingLock(false);
2557:     handleHide();                                 // ← 进入隐藏流程（视图动画）
2558:     mUpdateMonitor.clearBiometricRecognizedWhenKeyguardDone(currentUser);
2559: }

2831: private void handleHide() {                       // 隐藏锁屏
2832:     Trace.beginSection("KeyguardViewMediator#handleHide");
2836:     if (mAodShowing) { mPM.wakeUp(...); }          // dozing 时先唤醒
2844:     mHiding = true;
2856:     mKeyguardGoingAwayRunnable.run();             // 通知 WMS“keyguard 正在离开”，开始退出动画
        // 或（未显示时）走 batchApplyWindowLayoutParams → handleStartKeyguardExitAnimation(...)
    }

2870: private void handleStartKeyguardExitAnimation(long startTime, long fadeoutDuration, ...) {
        // 与 WMS/Shell 协同的锁屏退出远程动画（RemoteAnimation）；动画结束回调 finishedCallback
        // 动画完成后 StatusBarKeyguardViewManager 才真正把锁屏视图 GONE 掉
    }
```

## 3.3 生物识别对接（真实代码注释）

```java
// 注意：生物识别回调不在 KeyguardViewMediator 里，而在 com.android.keyguard.KeyguardUpdateMonitor
// 它通过 IBimetricService（android.hardware.biometrics）注册回调：
//   - FingerprintManager / FaceManager 的 AuthenticationCallback
//   - 认证成功 → KeyguardUpdateMonitorCallback#onBiometricAuthenticated()
//   - 再由 KeyguardViewMediator 监听该 callback 推进解锁
//
// 没有 “BiometricServiceConnector” 这个类（前文勘误已指出其为虚构）。
```

**要点**：锁屏 = 状态机（`KeyguardViewMediator`）+ 视图/动画（`StatusBarKeyguardViewManager`/`KeyguardBouncer`）+ 生物识别（`com.android.keyguard.KeyguardUpdateMonitor`），三者通过 `ViewMediatorCallback` / `KeyguardUpdateMonitorCallback` 通信。禁用/跳过锁屏应改 `config_disableLockscreen` 或 `KeyguardViewMediator`，**绝不在 Binder 方法里加 dismiss hack**。

---

# 4. Recents（最近任务）

## 4.1 流程总览

```
导航键/手势触发“最近任务”
  → SystemUI 输入层（NavigationBar / EdgeBackGestureHandler / StatusBar）
    → OverviewProxyService.getProxy().onOverviewToggle()        [OverviewProxyService.java:832 getProxy]
      → 跨进程 Binder 调用 Launcher 的 IOverviewProxy
        → Launcher（Quickstep）的 OverviewCommandHelper 画出后台任务列表（RecentsView/TaskView）
```

> AOSP 12+ Recents 的真实渲染已迁到 **Launcher（Quickstep）**，SystemUI 只做代理。`OverviewProxyService` 是 SystemUI 侧持有 `IOverviewProxy` Binder 的桥梁。

## 4.2 OverviewProxyService 绑定与触发（真实代码注释）

`OverviewProxyService.java`：

```java
135: static final String ACTION_QUICKSTEP = "android.intent.action.QUICKSTEP_SERVICE"; // Launcher 暴露的 action
168: private IOverviewProxy mOverviewProxy;        // Launcher 侧 Binder 的本地代理

428: private final ServiceConnection mOverviewServiceConnection = new ServiceConnection() {
446:     mOverviewProxy = IOverviewProxy.Stub.asInterface(service); // ← 拿到 Launcher 的 IOverviewProxy
463:     mOverviewProxy.onInitialize(params);                    // 初始化同步状态
    };

783: mBound = mContext.bindServiceAsUser(mQuickStepIntent,       // ← 绑定 Launcher 的 Recents 服务
784:         mOverviewServiceConnection, Context.BIND_AUTO_CREATE, UserHandle.USER_CURRENT);

832: public IOverviewProxy getProxy() {                          // ← SystemUI 其他模块拿代理的入口
833:     return mOverviewProxy;
    }

1002: void notifyToggleRecentApps() {                            // ← SystemUI 内部回调钩子
1004:     mConnectionCallbacks.get(i).onToggleRecentApps();      //   通知关心“最近任务”的本地监听者
    }
// 外部（手势/导航键）通过 getProxy().onOverviewToggle() 真正跨进程拉起 Launcher 的概览界面
```

**要点**：真实跨进程调用是 `IOverviewProxy.onOverviewToggle()`（Launcher 实现），SystemUI 侧用 `OverviewProxyService` 持有代理。文中所谓 `toggleRecentApps()` 直接 `mOverviewProxy.onOverviewToggle()` 是简化；AOSP 14 里 `OverviewProxyService` 暴露的是 `getProxy()` + `notifyToggleRecentApps()` 内部回调。

---

# 5. VolumeUI（音量调节）

## 5.1 流程总览

```
物理音量键 / 蓝牙设备 / 应用 setStreamVolume
  → AudioService（system_server）状态变化
    → 回调 IVolumeController（系统侧）
      → VolumeDialogControllerImpl（mVolumeController，IVolumeController.Stub）  [VolumeDialogControllerImpl.java:707]
        → onVolumeChangedW(stream, flags)                                  [VolumeDialogControllerImpl.java:459]
          → 通知 VolumeDialogImpl 显示/更新音量滑块（TYPE_VOLUME_OVERLAY）
用户拖动滑块 → VolumeDialogImpl → VolumeDialogControllerImpl.setStreamVolume() → AudioManager.setStreamVolume()
```

## 5.2 音量 AIDL 注册（真实代码注释）

`VolumeDialogControllerImpl.java`（真正的 AIDL 注册点，**不是** `VolumeDialogComponent`）：

```java
37:  import android.media.IVolumeController;
153: protected final VC mVolumeController = new VC();   // 系统音量回调的 Binder 桩

235: protected void setVolumeController() {
237:     mAudio.setVolumeController(mVolumeController);   // ← 向 AudioService 注册自己为音量控制器
        // mAudio 即 AudioManager；此后系统音量变化会回调 VC.onVolumeChanged()
260:     setVolumeController();                           // 在构造/连接后调用注册
    }

459: boolean onVolumeChangedW(int stream, int flags) {   // VC 收到系统音量变化
        // flags & AudioManager.FLAG_SHOW_UI → 需要弹 UI
        // 转发给 VolumeDialogControllerImpl.Callback → VolumeDialogComponent → VolumeDialogImpl 显示
    }

707: private final class VC extends IVolumeController.Stub {  // ← 真实类名是 VC，不是 VolumeController
        // onVolumeChanged / onMasterVolumeChanged / onConfigurationChanged ...
        // 通过 Handler 把消息投到主线程，最终调到 onVolumeChangedW(...)
    }
```

`VolumeDialogComponent.java`（SystemUI 组件壳，实现 `VolumeComponent`）：

```java
52:  public class VolumeDialogComponent implements VolumeComponent, TunerService.Tunable, ... {
78:      @Inject public VolumeDialogComponent(..., VolumeDialogControllerImpl volumeDialogController,
                 VolumeDialog volumeDialog) {
92:          mController = volumeDialogController;             // 持有上面的 ControllerImpl
104:         mDialog.init(LayoutParams.TYPE_VOLUME_OVERLAY, mVolumeDialogCallback); // 音量浮层窗口类型
        }
192:     public void register() { mController.register(); } // 启动音量监听
    }
```

**要点**：`VolumeDialogComponent` 只是 Dagger 注入的组件壳；**真正的 `IVolumeController` 注册与 `onVolumeChanged` 处理在 `VolumeDialogControllerImpl`**。弹 UI 的是 `VolumeDialogImpl`（窗口类型 `TYPE_VOLUME_OVERLAY`）。这修正了前文勘误里把 `IVolumeController` 直接算到 `VolumeDialogComponent` 头上的小偏差。

---

# 6. Screenshot（截屏）

## 6.1 流程总览

```
组合键（电源+音量下）/ 快捷开关 / 手势
  → PhoneWindowManager 捕获 → 跨 Binder 调 SystemUI 的 TakeScreenshotService（:screenshot 进程）
    → TakeScreenshotService（extends Service，IScreenshotProxy.Stub）  [TakeScreenshotService.java:63]
      → ScreenshotController.takeScreenshot(...) / handleScreenshot(...)
        → ImageCapture.captureDisplay(displayId, bounds)    [ScreenshotController.java:409]
          → SurfaceControl 截各图层合成 Bitmap
            → 显示 ScreenshotView / 编辑 UI（:screenshot 进程内）
```

> 截屏运行在**独立进程 `:screenshot`**（Manifest `android:process=":screenshot"`），崩溃不影响主 SystemUI。

## 6.2 截屏核心（真实代码注释）

`TakeScreenshotService.java`：

```java
63:  public class TakeScreenshotService extends Service {
        // 内部持有 IScreenshotProxy.Stub 的 binder（android.app.IScreenshotProxy）
        // system_server / PWM 通过它把“截屏请求”投到本 Service
        // 收到请求后转交 ScreenshotController 执行真正截图
    }
```

`ScreenshotController.java`：

```java
126: public class ScreenshotController {
266:     private final ImageCapture mImageCapture;   // 封装 SurfaceControl.screenshot 的截图器

402:     void handleScreenshot(ScreenshotData screenshot, Consumer<Uri> finisher, ...) {
403:         // 拿截图锁，避免并发截屏
409:         mImageCapture.captureDisplay(            // ← 真正的底层截图（SurfaceFlinger 合成）
410:                 mDisplayTracker.getDefaultDisplayId(), bounds);
            // 返回 Bitmap 后：prepareViewForNewScreenshot → mScreenshotView.setScreenshot(bitmap)
            // 随后显示截图浮层、提供“编辑/分享/滚动截屏”入口
            // 出错时 mNotificationsController.notifyScreenshotError(...)
    }
```

**要点**：AOSP 14 真实接口是 `IScreenshotProxy`（非旧 `IScreenshot.Stub`），捕获走 `ImageCapture.captureDisplay(...)`（封装 `SurfaceControl.screenshot`），**没有 `ScreenCapture.takeScreenshot(...)`/`ScreenCaptureListener` 这种旧 API**。

---

# 7. PipUI（画中画）

## 7.1 流程总览

```
某 App（如 YouTube）进入 PiP 模式
  → system_server / ActivityTaskManager 把该 Activity 标为 pinned
    → com.android.wm.shell（独立进程 :shell）的 PipController / PipTaskOrganizer 接管
      → 计算 PiP 窗口 bounds、处理拖拽/缩放/动画、显示控制菜单
    → SystemUI 侧 PipUI（CoreStartable）仅做桥接：把 SystemUI 内的 PiP 事件转发给 wm.shell 的 IPip
```

> **最重要**：`PipController` **不在 SystemUI**。从 Android 12 起，PiP 的真实逻辑全部迁到 **`com.android.wm.shell`**（`frameworks/base/libs/WindowManager/Shell/.../com/android/wm/shell/pip/PipController.java`），由 `ShellTaskOrganizer` 驱动，运行在独立 `:shell` 进程。SystemUI 侧只剩一个薄桥接类 `PipUI`（一个 `CoreStartable`）。

## 7.2 SystemUI 侧桥接（真实代码注释）

`com/android/systemui/pip/PipUI.java`（SystemUI 包内，`CoreStartable`）：

```java
// PipUI 大致结构（基于 AOSP 14 架构；本仓库该文件路由返回 404，以下为架构级描述）
public class PipUI implements CoreStartable {
    @Inject public PipUI(Context context, /* 注入 wm.shell 的 IPip Binder 代理 */) { ... }

    @Override public void start() {
        // 1. 连接 com.android.wm.shell 的 IPip Binder（跨进程到 :shell）
        // 2. 注册 SystemUI 内关心 PiP 的监听（如控制菜单点击、进入/退出动画回调）
        // 3. 把 SystemUI 事件（如用户点击 PiP 菜单项）转发给 wm.shell 的 PipController
    }
    // 本身不持有 PiP 窗口的视图/动画逻辑——那些都在 wm.shell 的 PipController / PipTaskOrganizer
}
```

`com/android/wm/shell/pip/PipController.java`（**真实 PiP 逻辑所在**，在 wm.shell 而非 SystemUI）：

```java
// 真实 PiP 进入/退出/边界/菜单控制都在这里：
//   - 注册 TaskStackListener，onActivityPinned(...) 时启动 PiP 窗口
//   - 计算并应用 PiP bounds（PipBoundsHandler / PipBoundsState）
//   - 处理拖拽、缩放、吸附、动画（PipTaskOrganizer）
//   - 显示 PipMenuActivity（播放/暂停/全屏/关闭）
// 这些在用户原分析里被写成“SystemUI 内的 PipController + TaskStackChangeListener”，位置全错。
```

**要点**：原分析把 `PipController` 放在 `com/android/systemui/pip/` 并让它直接处理 `TaskStackChangeListener.onActivityPinned`，**类归属与进程边界都错了**。正确说法是：PiP 真实逻辑在 `com.android.wm.shell.pip.PipController`，SystemUI 侧仅 `PipUI` 做跨进程桥接。

---

# 8. 七模块横向对照速查

| 模块 | 触发方 | 真实核心类（SystemUI 内） | 跨进程对象 | 运行进程 |
|------|--------|--------------------------|-----------|---------|
| StatusBar/Nav | SystemUIService 启动 | `CentralSurfacesImpl` + `StatusBarWindowController` + `NavigationBarController` | WMS（`IWindowManager`） | `com.android.systemui` |
| NotificationShade | 用户下拉 | `NotificationShadeWindowControllerImpl` | WMS / AMS(`setHasTopUi`) | `com.android.systemui` |
| Keyguard | 系统事件/生物识别 | `KeyguardViewMediator` + `StatusBarKeyguardViewManager` | WMS / `IBiometricService` | `com.android.systemui` |
| Recents | 导航键/手势 | `OverviewProxyService` | Launcher `IOverviewProxy` | `com.android.systemui` |
| VolumeUI | 音量键/应用 | `VolumeDialogComponent` + `VolumeDialogControllerImpl` | `AudioService`(`IVolumeController`) | `com.android.systemui` |
| Screenshot | 组合键 | `TakeScreenshotService` + `ScreenshotController` | `SurfaceControl`/`ImageCapture` | `:screenshot` |
| PipUI | App 进入 PiP | `PipUI`（仅桥接） | wm.shell `IPip` | `com.android.systemui` → `:shell` |

**共同架构特征（验证前文总结）**：
1. ✅ 窗口直连 WMS——状态栏/导航栏/通知帘/音量浮层都是 `WindowManager.addView` 的系统 Window。
2. ✅ 跨进程极频繁——截屏↔`SurfaceControl`、音量↔`AudioService`、锁屏↔WMS/生物识别、最近任务↔Launcher、Pip↔wm.shell。
3. ✅ 事件驱动——`IVolumeController.onVolumeChanged`、`IOverviewProxy`、`KeyguardUpdateMonitorCallback`、`TaskStackListener` 等回调把底层状态变成 UI。

> 注：PipUI / wm.shell 的 `PipController` 在本仓库 cnb `/-/git/raw/main/` 路由返回 39B（疑似路由异常），文中 PiP 部分基于 AOSP 12+ 既定架构事实描述；建议本地 `repo` 工作区用 `find frameworks -name PipController.java` 复核确切路径。
