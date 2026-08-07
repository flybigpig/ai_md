# SystemUI 面试深挖（Android 14 / AOSP）

> 本文是 `systemui_architecture.md` §7「面试视角小结」六点的逐题展开。每条给：完整调用链（真实类名/方法名）+ 关键设计点 + 面试官可能追问。
> 代码根目录：`frameworks/base/packages/SystemUI/`；进程：`com.android.systemui`(priv-app, platform 签名, 非 system_server)。
> 命名约定：AOSP 12 起 `StatusBar`→`CentralSurfaces`/`CentralSurfacesImpl`；`Recents`→`Overview`/`RecentsImplementation`。下文统一用新名,旧名标注。

---

## 1. 启动链路：SystemServer → SystemUIService → 组件注册表 → SystemUI.start()

### 1.1 完整时序（都带方法名）

```
SystemServer.main()
 └─ run()                              // frameworks/base/services/java/com/android/server/SystemServer.java
     └─ startOtherServices()           // 启动 ATMS/WMS/PowerManager/NMS 之后
         └─ startSystemUi(context, wm) // 私有静态方法
              ├─ Intent intent = new Intent();
              │    intent.setComponent(mSystemUiComponent);   // R.string.config_systemUiComponent
              │    intent.addFlags(Intent.FLAG_DEBUG_TRIAGNOSTICS);
              │    context.startServiceAsUser(intent, UserHandle.SYSTEM);  // 经 AMS 拉起 Service
              └─ wm.onSystemUiStarted();   // 通知 WMS：SystemUI 已就绪（用于后续窗口类型路由）
```

`SystemServer` 构造函数里读 `mSystemUiComponent`：
```java
mSystemUiComponent = res.getString(com.android.internal.R.string.config_systemUiComponent);
// 默认 "com.android.systemui/.SystemUIService"（frameworks/base/core/res/res/values/config.xml）
```

### 1.2 SystemUIService（入口是 Service，不是 Activity）

```java
// SystemUIService.java
@Override
public void onCreate() {
    super.onCreate();
    // 应用是 SystemUIApplication，转交它去启动所有组件
    ((SystemUIApplication) getApplication()).startServicesIfNeeded();
}
```

### 1.3 SystemUIApplication：组件注册表 + 实例化 + 启动

```java
// SystemUIApplication.java
public void startServicesIfNeeded() {
    // 1) 组件清单来自 config 数组(R.array.config_systemUIServiceComponents)
    String[] names = SystemUIFactory.getInstance().getSystemUIServiceComponents(this);
    startServicesIfNeeded(names);   // 无参版
}

private void startServicesIfNeeded(String[] services) {
    final int N = services.length;
    mServices = new SystemUI[N];
    for (int i = 0; i < N; i++) {
        String clsName = services[i];
        try {
            Class<?> cls = Class.forName(clsName);
            // 2) 由 Dagger 根组件创建实例（注入依赖），不是裸 newInstance
            Object o = SystemUIFactory.getInstance()
                        .getSystemUIFactoryComponent()
                        .createSystemUI(cls, this);
            mServices[i] = (SystemUI) o;
            // 3) 模板方法 start() → onStart()
            mServices[i].start();
            if (mBootCompleted) mServices[i].onBootCompleted();   // 若开机已完成,补发
        } catch (...) { ... }
    }
}
```

### 1.4 基类契约（模板方法模式）

```java
// SystemUI.java（抽象类）
public abstract class SystemUI {
    private final Context mContext;
    private boolean mStarted;
    public final void start() {           // final，子类不可改骨架
        onStart();                        // 子类实现真正的初始化
        mStarted = true;
    }
    protected abstract void onStart();
    public void onBootCompleted() {}      // BOOT_COMPLETED 后逐组件回调
    public void onConfigurationChanged(Configuration newConfig) {}  // 多语言/分屏/旋转
}
```

### 1.5 组件注册表（config_systemUIServiceComponents，AOSP 14 典型条目）

`frameworks/base/packages/SystemUI/res/values/config.xml`：
```xml
<string-array name="config_systemUIServiceComponents" translatable="false">
    <item>com.android.systemui.keyguard.KeyguardViewMediator</item>
    <item>com.android.systemui.statusbar.phone.CentralSurfacesImpl</item>  <!-- 原 StatusBar -->
    <item>com.android.systemui.recents.Recents</item>
    <item>com.android.systemui.volume.VolumeUI</item>
    <item>com.android.systemui.pip.PipUI</item>
    <item>com.android.systemui.shortcut.ShortcutKeyDispatcher</item>
    <item>com.android.systemui.ScreenDecorations</item>
    <item>com.android.systemui.biometrics.AuthController</item>
    <item>com.android.systemui.globalactions.GlobalActionsComponent</item>
    <item>com.android.systemui.accessibility.SystemActions</item>
    <item>com.android.systemui.toast.ToastUI</item>
    ...
</string-array>
```

**设计点**：新增一个系统 UI 功能 = 写一个 `SystemUI` 子类 + 把它塞进这个数组。启动主流程、AMS/system_server 一行都不用改。这就是「插件式」。

### 🔥 追问
- **为什么入口是 Service 不是 Activity？** SystemUI 要在没有前台 Activity、甚至锁屏/开机动画阶段就能显示系统 UI；Service 常驻、可后台启动、不依赖窗口焦点，更适配「系统界面常驻进程」语义。
- **SystemUI 进程挂了会怎样？** 不影响系统服务；AMS 的 `Service` 死亡后由 `system_server`/init 重新拉起（和 Dalton/zygote 无关，纯 AMS 重启 Service）。重启后 `startServicesIfNeeded()` 重建全部组件并从各系统服务重新同步（如通知重新 `onNotificationPosted`）。
- **getSystemUIServiceComponents 和直接读 R.array 的区别?** 经过 `SystemUIFactory` 是为了允许 OEM/厂商 override（Android 14 里它最终还是读同一个 `R.array`，但留了扩展点）。

---

## 2. 架构思想：插件式 + Dagger 注入 + 「展示层 / 真相在服务」

### 2.1 插件式（config 数组 + 模板方法）
见 §1.3–1.5。三大支柱：① 组件清单外置到 config；② 统一抽象基类 `SystemUI` + `start()/onStart()` 模板；③ `SystemUIApplication` 负责反射 + 生命周期。

### 2.2 Dagger2 依赖注入
AOSP 12+ SystemUI 全面 Dagger 化，目标是消除组件间 `new` 和全局静态单例。

- **根组件**：`SystemUIFactory`（`SystemUIFactory.java`）构建 `SysUIComponent`（Dagger 根 `@Component`）。
  - `SystemUIFactory.getInstance()` 是进程级单例入口。
  - `SysUIComponent` 声明全局 `@SysUISingleton` 绑定：`Context`、`Handler`、`BroadcastDispatcher`、`ActivityStarter`、`CommandQueue`、`NotificationLockscreenUserManager` 等。
- **子组件（Subcomponent）**：每个大子系统有独立 Dagger 子图，按需注入：
  - `StatusBarComponent`（含 `CentralSurfacesImpl`、`StatusBarWindowController`、`StatusBarIconControllerImpl`…）
  - `NotificationComponent`（含 `NotificationEntryManagerImpl`、`NotificationListener`…）
  - `KeyguardComponent`、`QsComponent` 等。
- **新写法的标志**：构造函数 `@Inject` + 作用域注解（`@SysUISingleton` / `@StatusBarScope`）。例：
  ```java
  @StatusBarScope
  public class StatusBarWindowController {
      @Inject
      public StatusBarWindowController(Context context, @Nullable CommandQueue commandQueue,
              WindowManager windowManager, StatusBarStateController stateController, ...) { ... }
  }
  ```
- **遗留的 `Dependency` 类**：Dagger 前的老单例容器，仍大量被引用（`Dependency.get(Class<T>)`、`Dependency.inject()`）。面试时可直接说「老的 `Dependency` 是过渡态，新版往 Dagger 子图迁；两者并存」。

**为什么用 Dagger 而不是 Service Locator？** 编译期生成注入代码、无反射开销、依赖图可视化、作用域清晰（避免 StatusBar 实例被错误共享）。

### 2.3 「真相在系统服务，SystemUI 只是展示层」
这是贯穿全文的灵魂原则。SystemUI 几乎不持有权威数据：

| 能力 | 真相源（系统服务） | SystemUI 角色 |
|---|---|---|
| 通知 | `NotificationManagerService`(NMS) | `NotificationListener` 回调 + 缓存渲染 |
| 最近任务/缩略图 | `ActivityTaskManagerService` + `RecentTasks` | 仅展示（手势态甚至委托 Launcher） |
| 是否锁屏/是否安全 | `PowerManager` + `KeyguardManager` + AMS | `KeyguardViewMediator` 监听并绘制 |
| 音量 | `AudioService` | `VolumeUI` 监听 `AudioManager` 变化 |
| 状态栏图标(信号/电池) | `TelephonyRegistry` / `BatteryStats` | `StatusBarIconController` 订阅更新 |
| 窗口层级/insets | `WindowManagerService` | 上报 insets、接收 relayout |

**推论**：SystemUI 可崩溃重启而不丢数据；数据一致性由各系统服务保证，SystemUI 只是「视图投影」。

### 🔥 追问
- **Dependency 和 Dagger 现在谁主谁次？** 共存。`@SysUISingleton` 的 Dagger bean 很多仍被 `Dependency` 包一层暴露给旧代码。新模块优先用 Dagger 子图。
- **StatusBarComponent 为什么是子组件而不是根组件的一部分？** 作用域隔离：StatusBar 相关对象（如 `StatusBarWindowController`）只需在 StatusBar 生命周期内单例，拆子组件让 Dagger 在 StatusBar 销毁时能释放图，避免根图无限膨胀。

---

## 3. 通知：NMS 是真相 → NotificationListener 回调 → EntryManager 缓存渲染

### 3.1 数据归属
`NotificationManagerService`(NMS, `com.android.server.notification`) 是通知唯一真相源，存于 `mNotificationList`（按 key 索引的 `NotificationRecord` 列表）。App 调 `NotificationManager.notify()` 最终落到 `NMS.enqueueNotificationInternal()`。

### 3.2 监听通道（binder 回调）
SystemUI 侧 `NotificationListener`(`statusbar/notification/NotificationListener.java`，继承 `NotificationListenerService`) 注册为监听者：
- 注册：`NotificationManager.registerListener()` → `NMS.registerListener()`，拿到 `INotificationListener` binder 桩。
- 回调方法（NMS 跨进程主动调）：
  ```java
  onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap)
  onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason)
  onNotificationRankingUpdate(RankingMap rankingMap)
  ```
- 这些回调跑在 NMS 的 binder 线程，SystemUI 内部 post 到主线程处理。

### 3.3 AOSP 12+ 新管线（NotificationPipeline）
旧版 `NotificationEntryManager`/`NotificationData` 在 12 起被重构成**管线流**，面试讲新版更加分：

```
NMS (binder)
 └─ NotificationListenerWithPlugins (extends NotificationListenerService)
      └─ NotificationHandler 回调
           └─ NotifCollection          // 收集/去重,维护 NotificationEntry 集合
                └─ NotifPipeline       // 跑过滤(NotifFilter)与协调(NotifCoordinator)
                     └─ NotifPresenter // 接口,由 CentralSurfacesImpl 实现
                          └─ NotificationStackScrollLayout
                               └─ ExpandableNotificationRow (单条视图)
```

- **`NotifCollection`**：维护 `Map<String, NotificationEntry>`，是 SystemUI 内的「缓存副本」（非真相）。
- **`NotifPipeline`**：依次执行注册的 `NotifFilter`(如隐藏敏感通知)、`NotifCoordinator`(分组/排序)。
- **`NotifPresenter`**：决定怎么上屏（`NotificationPresenterImpl` 持有 `NotificationEntryManager` 视图层）。
- 旧名 `NotificationEntryManagerImpl` 仍作为兼容壳存在，内部委托给管线。

### 3.4 渲染层
- `NotificationEntry`：包装 `StatusBarNotification` + `Ranking`（重要度、是否静默、渠道）。
- `NotificationRowBinderImpl` / `NotificationInflater`：把 entry inflate 成 `ExpandableNotificationRow`（含 big/small view）。
- `NotificationStackScrollLayout`：通知 shade 里的滚动列表（位于 `NotificationShadeWindowView`，窗口类型 `TYPE_NOTIFICATION_SHADE`）。
- 收起态图标区：`NotificationIconAreaController` 把前 N 条通知缩成状态栏小图标（`StatusBarIconView`）。

### 🔥 追问
- **SystemUI 重启后通知为什么还在？** 因为真相在 NMS。SystemUI 重启后 `NotificationListener` 重新注册，`NMS` 会把当前全部通知重发一遍 `onNotificationPosted`，SystemUI 重建缓存。（注意有 `RankingMap` 和 `isRebuilt()` 标记区分首刷与增量。）
- **为什么需要 RankingMap？** 通知的展示顺序、是否遮蔽、渠道重要性、气泡/静默状态由 NMS 算好后随回调下发，SystemUI 不能自己重算（否则各客户端不一致）。
- **NotificationEntryManager 和 NotifCollection 什么关系？** 历史演进：EntryManager 是 11 及以前的名字；12 拆成「收集(NotifCollection) + 管线(NotifPipeline) + 展示(NotifPresenter)」三段式，EntryManager 退化为兼容接口。

---

## 4. 锁屏 / 状态栏：同进程协同 + 状态机驱动 + 锁屏 UI 内嵌

### 4.1 同进程
锁屏（`Keyguard*`）和状态栏（`CentralSurfaces*`）**都在 `com.android.systemui` 进程内**，没有独立 `Keyguard.apk`（Lollipop 起废弃）。两者通过共享的 `StatusBarWindowView` 层级 + 状态机协同。

### 4.2 状态机：`StatusBarStateController`
`StatusBarStateControllerImpl` 维护一个三态机（常量在 `StatusBarState`）：
```java
STATUS_BAR_STATE_SHADE            = 0;   // 下拉通知/QS
STATUS_BAR_STATE_KEYGUARD         = 1;   // 锁屏
STATUS_BAR_STATE_KEYGUARD_OCCLUDED = 2;  // 锁屏被 Activity 遮挡(如相机/来电全屏)
```
- 切换入口：`setState(int)` → 通知所有 `StatusBarStateListener`（StatusBar、Keyguard、QS 各自响应）。
- 驱动方：`StatusBarKeyguardViewManager` / `KeyguardStateController` 在锁屏显示/隐藏时改状态。

### 4.3 锁屏大脑：`KeyguardViewMediator`
`KeyguardViewMediator` 是「锁屏逻辑中枢」，但它**不画 UI**，只决策：
- 监听 `PowerManager` 睡眠/唤醒（`ScreenLifecycle` / `KeyguardStateMonitor` 转发 `onStartedGoingToSleep` / `onScreenTurnedOn`）；
- 监听 AMS 的 activity 切换（`KeyguardStateMonitor` + `ActivityManager` 回调）判断是否需要锁屏；
- 监听 `mUpdateMonitor`（指纹/人脸结果）决定解锁；
- 决定：显示/隐藏锁屏、是否安全锁（`isSecure()`）、是否走 bouncer。

### 4.4 视图层级与协同
```
StatusBarWindowController (type=TYPE_STATUS_BAR 的窗口)
 └─ StatusBarWindowView
      ├─ CollapsedStatusBarFragment   // 折叠态:时钟/图标/电池
      ├─ NotificationShadeWindowView  // 下拉后的 shade + QS
      └─ KeyguardBouncer / KeyguardStatusBarView  // 锁屏主体 + 密码页
```
- `StatusBarKeyguardViewManager`：实际创建/移除锁屏视图（`showSurfaceBehindKeyguard`、`hide`）。
- `KeyguardBouncer`：`KeyguardPasswordView`/`KeyguardPatternView` 等密码页。
- `KeyguardStatusBarViewController`：锁屏上的状态栏（和主状态栏共享 `StatusBarIconController`）。
- z 关系由 `StatusBarWindowController` + `StatusBarStateController` 协同：`KEYGUARD` 态抬高锁屏层级、压低 shade。

### 🔥 追问
- **为什么锁屏 UI 不放独立 apk？** 早期（Android 4 及以前）有 `Keyguard.apk`，但和 SystemUI 强耦合、升级困难；合并进 SystemUI 后共享状态栏/图标/窗口栈，状态切换零 IPC、瞬时完成。
- **KEYGUARD_OCCLUDED 是什么场景？** 锁屏上弹出一个全屏 Activity（来电、相机快捷、全屏广告），锁屏被「遮挡」但仍处于后台——此时不销毁锁屏，activity 退场后立刻恢复锁屏。状态机区分它和 KEYGUARD 才能正确处理返回键/壁纸。
- **指纹解锁后谁负责收起锁屏？** `KeyguardViewMediator` 收到 `KeyguardUpdateMonitor` 的 `onKeyguardDismissSucceeded` → 调 `StatusBarKeyguardViewManager.reset()` 收起；`StatusBarStateController` 切回 SHADE。

---

## 5. 最近任务：手势导航委托 Launcher3，靠 OverviewProxyService 的 IOverviewProxy binder

### 5.1 两种架构
- **三键导航（旧）**：`SystemUI` 内 `Recents`（实现 `RecentsImplementation`）自己画任务卡片（`TaskView`/`TaskThumbnailView`），通过 `CommandQueue.toggleRecentApps()` 触发。
- **手势导航（默认，AOSP 12+）**：最近任务**实际由 Launcher3/Quickstep 承载**。SystemUI 只做手势捕获 + 状态同步，**不画卡片**。

### 5.2 跨进程桥：OverviewProxyService + IOverviewProxy
`OverviewProxyService`(`statusbar/phone/OverviewProxyService.java`) 持有 Launcher 注册上来的 `IOverviewProxy` binder：

```
SystemUI (OverviewProxyService)
  └─ mOverviewProxy: IOverviewProxy   // Launcher(Quickstep) 实现并注册
       SystemUI → Launcher:
         onOverviewShown(boolean fromHome)     // 请求显示概览
         onSystemUiStateChanged(long state)    // SysUiState 变化(如 shade 开/关)
         onStatusBarMotionEvent(MotionEvent)   // 状态栏下拉手势透传
       Launcher → SystemUI:
         registerCallback(IOverviewProxyCallback)  // 手势/动画进度回调
         onInitialize(...) / onOverviewToggle()
```
- 连接建立：Launcher 启动后调 `OverviewProxyService` 的 `IOverviewProxy` 注册 stub（`setOverviewProxy`），Service 端 `onConnectionChanged()` 通知各监听者（如 `NavigationBar`、手势区）。
- 反向桥 `SysUiProxy`（在 WM Shell / Launcher 侧）：Launcher 用它回调 SystemUI/WM，例如 `startRecentsActivity()`、`onTaskbarShown()`。

### 5.3 任务数据来源（仍是系统服务真相）
- **任务列表**：`ActivityTaskManagerService`、`RecentTasks`（存 `TaskRecord`/`RecentTaskInfo`）。
- **缩略图**：`TaskSnapshotController` 抓 `TaskSnapshot`（通过 `TaskSnapshotCache`），跨 binder 传给 Launcher 渲染。SystemUI 不直接拿缩略图。
- 手势上滑 → Launcher 调 `ATMS.startRecentsActivity()` → `RecentTasks` 取列表 → Launcher 用 `TaskSnapshot` 画卡片。

### 🔥 追问
- **为什么手势导航要把最近任务塞给 Launcher？** 手势动画要和 Launcher 的 workspace/壁纸联动（上滑回桌面、横滑切应用），动画连续帧必须同源；SystemUI 跨进程画再同步会撕裂。把「概览」交给 Launcher，SystemUI 退居手势捕获与状态广播，符合模块化/Treble 思想。
- **三键模式下 Recents 还在 SystemUI 吗？** 在。`Recents` 组件仍是 config 注册表一员，但手势模式基本是空壳（由 Quickstep 接管）。
- **SysUiState 是什么？** `SysUiState`（`statusbar/SysUiState.java`）是一个 bitmask，SystemUI 用它告诉 Launcher 当前系统 UI 状态（状态栏可见、shade 展开、navbar 模式等），Launcher 据此调整动画与布局——是 SystemUI↔Launcher 的状态同步通道。

---

## 6. 窗口：SystemUI 窗口是 WMS 的特殊窗口 → 最终落 SF 的 Layer

### 6.1 SystemUI 添加的窗口类型（WindowManager.LayoutParams.type）
| 窗口 | type |
|---|---|
| 状态栏 | `TYPE_STATUS_BAR` |
| 导航栏 | `TYPE_NAVIGATION_BAR` |
| 通知 shade | `TYPE_NOTIFICATION_SHADE` |
| 锁屏 | `TYPE_KEYGUARD` |
| 音量面板 | `TYPE_VOLUME_OVERLAY` |
| 截图 | `TYPE_SCREENSHOT` |
| 屏幕装饰 | `TYPE_NAVIGATION_BAR_PANEL`(overlay) |

- 统一走 `IWindowManager.add()`（WMS 的 `addWindow()`）。
- 这些属于「系统内部窗口」，SystemUI 持有 `android.permission.INTERNAL_SYSTEM_WINDOW` 才能 add（普通 app 拿不到）。
- 封装者：`StatusBarWindowController`(状态栏/shade/锁屏)、`VolumeDialogController`(音量)、各子系统的 `*-WindowController`。

### 6.2 LayoutParams 关键设定
`StatusBarWindowController` 设置：
```java
LayoutParams.flags = FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
                   | FLAG_ALT_FOCUSABLE_IM | FLAG_LAYOUT_IN_SCREEN
                   | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
LayoutParams.gravity = Gravity.TOP;          // 状态栏在顶部
LayoutParams.softInputMode = SOFT_INPUT_ADJUST_RESIZE;
```
- `FLAG_NOT_FOCUSABLE`：状态栏不抢焦点，触摸透传到下层 app（除非点中图标）。
- `fitsSystemWindows` / `gravity`：决定它占屏幕哪条边。

### 6.3 WMS → DisplayArea → z-order
- AOSP 12+ WMS 用 `DisplayArea` 树组织窗口。`TYPE_STATUS_BAR` / `TYPE_NAVIGATION_BAR` / `TYPE_NOTIFICATION_SHADE` 等被路由到**专门的 `DisplayArea.Tokens`**（如 `StatusBarDisplayArea`、`NavigationBarDisplayArea`），而非普通 app 的 `TaskDisplayArea`。
- z-order 由 WMS 按 type 优先级 + `DisplayArea` 层级算好，再经 `SurfaceControl.Transaction.apply()` 把每个窗口的 `Surface` 变成 `Layer`，提交给 SurfaceFlinger。
- **SystemUI 不直接碰 SurfaceFlinger**——它只是「又一组应用窗口」，WMS 翻译成 Layer，SF 合成。壁纸闪黑、双壁纸 token、PIP 层级问题，本质都是这些窗口在 WMS→SF 链路的 z-order。

### 6.4 Insets（SystemUI 反向影响 app 布局）
SystemUI 通过 `InsetsSource` 把自身窗口的「占据区域」上报给 WMS：
- `StatusBar` 上报顶部 insets（状态栏高度）、`NavigationBar` 上报底部 insets（导航栏高度）。
- WMS 据此生成 `InsetsState`，app 的 `View` 经 `InsetsController`/`WindowInsets` 收到，实现 `fitsSystemWindows` 自动避让。
- 折叠/展开状态栏时 `InsetsSource` 高度变化 → app 重新布局（这就是下拉通知时 app 内容上推的原理）。

### 🔥 追问
- **SystemUI 窗口为什么用特殊 type 而不是 TYPE_APPLICATION？** type 决定 WMS 把它放进哪个 DisplayArea、算 z-order 的基准、以及谁能 add。系统窗口 type 有更高基线 z，且绕开 app 任务栈——否则状态栏会随某个 app 一起最小化。
- **状态栏收起时 app 为什么能顶到顶部？** 因为 `InsetsSource` 高度变 0（或沉浸模式 `SYSTEM_UI_FLAG_FULLSCREEN` 让 SystemUI 主动把 insets 置 0），`InsetsState` 更新后 app 重布局。
- **为什么 SystemUI 崩溃不会导致整屏黑？** 窗口本身是 WMS 管理的 Layer；SystemUI 进程死只丢「内容绘制」，WMS 会保留窗口框架/重新 relayout，且 AMS 立刻重启 Service 补回。

---

## 附：调试与背题命令
```bash
adb shell dumpsys statusbar                     # 状态栏/通知/QS 全量状态
adb shell dumpsys notification                   # NMS 侧通知真相
adb shell dumpsys window | grep -i systemui      # SystemUI 窗口与层级
adb shell service list | grep -i ui              # 看 SystemUI 相关 binder 服务
adb shell ps -A | grep com.android.systemui      # 拿 pid
adb shell kill <pid>                             # 杀掉后自动重启,验证改动
adb shell wm size / adb shell wm density         # 验证 insets 变化
```
> 同目录参考：`systemui_architecture.md`(架构总览)、`systemui_customization.md`(怎么改)、`wms_deep_dive.md`(DisplayArea/Layer 原理)、`binder_aidl.md`(binder 一次拷贝/oneway 基础)。
