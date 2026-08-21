# Android SystemUI 对话记录

> 导出时间：2026-08-21

---

## 目录

- [一、Android SystemUI 概述](#一android-systemui-概述)
- [二、Status Bar 添加过程](#二status-bar-添加过程)
- [三、信号图标的添加过程](#三信号图标的添加过程)
- [四、通知图标的排列逻辑](#四通知图标的排列逻辑)
- [五、时钟更新机制](#五时钟更新机制)
- [六、源码版本说明](#六源码版本说明)

---

## 一、Android SystemUI 概述

### 核心模块

| 模块 | 说明 |
|------|------|
| **Status Bar（状态栏）** | 显示时间、信号、电量、通知图标等 |
| **Navigation Bar（导航栏）** | 返回、主页、最近任务按钮 |
| **Notification Panel（通知面板）** | 下拉通知中心和快速设置面板 |
| **Lock Screen（锁屏）** | 锁屏界面及通知展示 |
| **Volume UI** | 音量调节面板 |
| **Screenshot** | 截图功能 |
| **Recents/Overview** | 最近任务视图（与 Launcher 交互） |
| **Power Menu** | 电源菜单 |

### 关键架构组件

- **`SystemUIApplication`** — SystemUI 的入口 Application 类
- **`SystemUIService`** — 作为系统服务启动
- **`SystemUIFactory`** — 工厂模式创建各组件
- **`Dependency`** — 轻量级依赖注入系统
- **`PluginManager`** — 插件机制，支持扩展功能

### 源码关键路径

```
frameworks/base/packages/SystemUI/
├── src/com/android/systemui/
│   ├── statusbar/          # 状态栏相关
│   ├── navigationbar/      # 导航栏相关
│   ├── notification/       # 通知相关
│   ├── volume/             # 音量相关
│   ├── keyguard/           # 锁屏相关
│   ├── screenshot/         # 截图相关
│   ├── recents/            # 最近任务
│   └── power/              # 电源相关
├── res/                    # 资源文件
├── plugin/                 # 插件接口
└── Android.mk / Android.bp # 构建配置
```

---

## 二、Status Bar 添加过程

### 1. 启动入口

```
SystemServer.startBootstrapServices()
  → WindowManagerService 启动
  → 启动 SystemUI 服务

SystemServer.startOtherServices()
  → startSystemUi(context)
    → context.startServiceAsUser(
        new Intent().setComponent(new ComponentName("com.android.systemui",
            "com.android.systemui.SystemUIService")),
        UserHandle.SYSTEM)
```

### 2. SystemUIService → SystemUIApplication

```java
// SystemUIService.onCreate()
((SystemUIApplication) getApplication()).startServicesIfNeeded();

// SystemUIApplication.startServicesIfNeeded()
// 遍历 mServices 数组，依次创建各组件：
for (Class<?> cl : mServices) {
    // 反射创建实例
    Object newService = SystemUIFactory.getInstance().createInstance(cl);
    newService.start();  // 调用每个组件的 start()
}
```

**mServices 数组**（定义在 `config.xml` 中）：

```xml
<!-- config.xml -->
<string-array name="config_systemUIServiceComponents" translate="false">
    <item>com.android.systemui.statusbar.SystemBars</item>
    <item>com.android.systemui.power.PowerUI</item>
    <item>com.android.systemui.volume.VolumeUI</item>
    <!-- ... 其他组件 -->
</string-array>
```

### 3. SystemBars — 状态栏的核心管理者

`SystemBars` 是状态栏创建的关键类：

```java
// SystemBars.start()
public void start() {
    createStatusBarFromConfig();  // 从配置创建状态栏
}

private void createStatusBarFromConfig() {
    // 读取配置，决定使用哪个 StatusBar 实现
    String clsName = mContext.getString(R.string.config_statusBarComponent);
    Class<?> cls = Class.forName(clsName);
    mStatusBar = (SystemBars) cls.getConstructor(Context.class)
                                  .newInstance(mContext);
    mStatusBar.start();  // 启动 StatusBar
}
```

### 4. StatusBar (CollapsedStatusBarFragment) 创建过程

在 Android 12+ 中，状态栏主要由 `CollapsedStatusBarFragment` 管理：

```
StatusBar.start()
  → 加载布局 → StatusBar.super_notification_shade
  → 添加 StatusBarWindowView
  → 初始化各 Controller
  → 创建 Fragment: StatusBarWindowFragment
      → 子 Fragment: CollapsedStatusBarFragment  ← 状态栏核心
```

**CollapsedStatusBarFragment** 的关键流程：

```java
// CollapsedStatusBarFragment.onCreateView()
public View onCreateView(LayoutInflater inflater, ...) {
    View view = inflater.inflate(R.layout.status_bar, container, false);
    // status_bar_container 包含：
    //   - status_bar_start_side_content (左侧：通知图标)
    //   - status_bar_end_side_content  (右侧：电池、时钟、信号)
    return view;
}

// onViewCreated() → 初始化各 Controller
public void onViewCreated(View view, ...) {
    mStatusBarClockController = ...;    // 时钟
    mStatusBarSignalIconController = ...; // 信号图标
    mStatusBarWifiIconController = ...;   // WiFi图标
    mStatusBarBatteryIconController = ...;// 电池图标
    mNotificationIconController = ...;    // 通知图标
}
```

### 5. 状态栏布局结构

```xml
<!-- status_bar.xml -->
<StatusBarWindowView>
    <FrameLayout id="status_bar_container">
        
        <!-- 左侧 -->
        <LinearLayout id="status_bar_start_side_content">
            <NotificationIcons id="notification_icon_area"/>  <!-- 通知图标 -->
            <AlarmImageView id="alarm"/>                       <!-- 闹钟 -->
        </LinearLayout>
        
        <!-- 中间（可选） -->
        <TextClock id="clock"/>
        
        <!-- 右侧 -->
        <LinearLayout id="status_bar_end_side_content">
            <BatteryMeterView id="battery"/>     <!-- 电池 -->
            <SignalClusterView id="signal"/>     <!-- 信号 -->
            <ClockView id="clock"/>              <!-- 时钟 -->
        </LinearLayout>
    </FrameLayout>
</StatusBarWindowView>
```

### 6. 图标添加流程（以通知图标为例）

```java
// NotificationIconController
// 当新通知到来时：
NotificationEntryListener.onEntryAdded(entry)
  → updateNotificationIcons()
    → NotificationIconAreaController.updateNotificationIcons()
      → 遍历活跃通知，创建 StatusBarIconView
      → 添加到 notification_icon_area (LinearLayout)
        → 如果图标过多，显示溢出图标 "..."
```

### 7. 完整时序图

```
SystemServer
  │
  ├─ startSystemUi()
  │    │
  │    └─ SystemUIService.onCreate()
  │         │
  │         └─ SystemUIApplication.startServicesIfNeeded()
  │              │
  │              ├─ SystemBars.start()
  │              │    │
  │              │    └─ createStatusBarFromConfig()
  │              │         │
  │              │         └─ StatusBar.start()
  │              │              │
  │              │              ├─ 加载 StatusBarWindowView
  │              │              ├─ 创建 CollapsedStatusBarFragment
  │              │              │    ├─ 加载 status_bar.xml
  │              │              │    ├─ 初始化 ClockController
  │              │              │    ├─ 初始化 SignalIconController
  │              │              │    ├─ 初始化 BatteryIconController
  │              │              │    └─ 初始化 NotificationIconController
  │              │              │
  │              │              └─ 状态栏显示完成 ✓
  │              │
  │              ├─ PowerUI.start()
  │              ├─ VolumeUI.start()
  │              └─ ... 其他组件
```

### 关键类总结

| 类名 | 职责 |
|------|------|
| `SystemUIService` | 系统服务入口 |
| `SystemUIApplication` | 管理并启动所有组件 |
| `SystemBars` | 管理状态栏和导航栏的创建 |
| `CollapsedStatusBarFragment` | 状态栏 UI 核心 Fragment |
| `StatusBarIconController` | 管理状态栏图标的添加/移除 |
| `NotificationIconAreaController` | 管理通知图标区域 |
| `PhoneStatusBarView` | 状态栏的 View 层实现 |

---

## 三、信号图标的添加过程

### 1. 整体架构

```
TelephonyManager / WifiManager
        │  (回调/广播)
        ▼
NetworkControllerImpl          ← 网络状态核心管理
        │
        ├─→ MobileSignalController    ← 移动数据信号
        ├─→ WifiSignalController      ← WiFi 信号
        └─→ EthernetSignalController  ← 以太网信号
        │
        ▼
SignalClusterView (View层)     ← 状态栏中的图标容器
```

### 2. NetworkControllerImpl 初始化

```java
// NetworkControllerImpl.start()
public void start() {
    // 1. 注册 TelephonyManager 监听
    mTelephonyManager.listen(mPhoneStateListener,
        PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
        | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
        | PhoneStateListener.LISTEN_SERVICE_STATE);

    // 2. 注册 WiFi 监听
    mContext.registerReceiver(mWifiReceiver,
        new IntentFilter(WifiManager.RSSI_CHANGED_ACTION));
    // ... 更多 WiFi 相关广播

    // 3. 为每个 SIM 卡创建 MobileSignalController
    for (int i = 0; i < mTelephonyManager.getActiveModemCount(); i++) {
        MobileSignalController controller = new MobileSignalController(
            mContext, mCallbackHandler, mNetworkController,
            mSubscriptionController, mSubId, i);
        mMobileSignalControllers.put(i, controller);
    }
}
```

### 3. 信号强度更新链路

```java
// PhoneStateListener 回调 → 信号强度变化
PhoneStateListener.onSignalStrengthsChanged(SignalStrength signalStrength) {
    // 更新原始信号数据
    mCurrentSignalStrength = signalStrength;
    
    // 计算信号等级 (0~4)
    int level = signalStrength.getLevel();  // 0=无信号, 4=满格
    
    // 通知 Controller 更新
    MobileSignalController.notifySignalStrengthChanged();
}

// MobileSignalController 处理
void notifySignalStrengthChanged() {
    // 1. 更新数据模型
    mState.iconGroup = getSignalIconGroup(mState.level);
    mState.activityIn = hasActivityIn();
    mState.activityOut = hasActivityOut();
    
    // 2. 通过 CallbackHandler 通知 View 层
    mCallbackHandler.post(() -> {
        mNetworkController.addCallback(mSignalCallback);
        // 最终调用到 SignalClusterView
    });
}
```

### 4. SignalClusterView 图标更新

```java
// SignalClusterView.apply()
public void apply(NetworkController.IconState iconState, ...) {
    // 移动数据信号
    if (mMobileVisible) {
        // 根据信号等级选择对应 drawable
        // ic_signal_bar_0 ~ ic_signal_bar_4
        mMobileSignal.setImageIcon(getSignalIcon(iconState.icon));
        mMobileSignal.setContentDescription(iconState.contentDescription);
        
        // 数据活动指示器 (上下箭头)
        if (iconState.activityIn) {
            mMobileType.setImageResource(R.drawable.ic_activity_down);
        }
        if (iconState.activityOut) {
            mMobileType.setImageResource(R.drawable.ic_activity_up);
        }
    }
    
    // WiFi 信号
    if (mWifiVisible) {
        mWifiSignal.setImageResource(getWifiIcon(iconState.icon));
    }
    
    // 无 SIM 卡 / 飞行模式等特殊状态
    if (mAirplaneMode) {
        mMobileSignal.setImageResource(R.drawable.ic_airplane_mode);
    } else if (!mHasService) {
        mMobileSignal.setImageResource(R.drawable.ic_signal_no_service);
    }
}
```

### 5. 信号图标资源映射

```
信号等级 0 → ic_signal_bar_0  (无信号/叉号)
信号等级 1 → ic_signal_bar_1  (1格)
信号等级 2 → ic_signal_bar_2  (2格)
信号等级 3 → ic_signal_bar_3  (3格)
信号等级 4 → ic_signal_bar_4  (满格)

特殊状态：
  飞行模式  → ic_airplane_mode
  无SIM卡   → ic_signal_sim_absent
  无服务    → ic_signal_no_service
  漫游      → ic_signal_roaming + "R"标记
```

### 6. 信号图标添加时序

```
TelephonyManager
  │ onSignalStrengthsChanged()
  ▼
PhoneStateListener
  │ 计算 signalLevel (0-4)
  ▼
MobileSignalController
  │ 更新 mState.iconGroup
  │ 确定 IconState (icon, description)
  ▼
CallbackHandler (主线程)
  │ post callback
  ▼
SignalClusterView.apply()
  │ 选择对应 drawable
  │ 设置 ImageView
  ▼
状态栏信号图标刷新完成 ✓
```

---

## 四、通知图标的排列逻辑

### 1. 核心架构

```
NotificationEntryManager     ← 管理所有活跃通知
        │
        ▼
NotificationIconAreaController ← 管理图标区域
        │
        ├─ NotificationIconContainer  ← 图标容器 ViewGroup
        │     ├─ StatusBarIconView (通知1)
        │     ├─ StatusBarIconView (通知2)
        │     ├─ StatusBarIconView (通知3)
        │     └─ OverflowImageView ("...")  ← 溢出图标
        │
        └─ NotificationIconController  ← 控制逻辑
```

### 2. 通知添加流程

```java
// NotificationEntryManager.addEntry()
public void addEntry(Entry entry) {
    // 1. 注册到活跃通知集合
    mNotificationMap.put(entry.key, entry);
    
    // 2. 通知各监听器
    for (NotificationEntryListener listener : mEntryListeners) {
        listener.onEntryAdded(entry);
    }
}

// NotificationIconController.onEntryAdded()
public void onEntryAdded(Entry entry) {
    // 只处理非静默通知
    if (!entry.getSbn().isGroup() || !isGroupSilenced(entry)) {
        updateNotificationIcons();
    }
}
```

### 3. 图标排列核心逻辑

```java
// NotificationIconAreaController.updateNotificationIcons()
private void updateNotificationIcons() {
    // 1. 收集所有需要显示的通知图标
    List<StatusBarNotification> activeNotifications = 
        getActiveNotifications();
    
    // 2. 过滤：只显示有 icon 的通知
    List<StatusBarIconView> iconViews = new ArrayList<>();
    for (StatusBarNotification sbn : activeNotifications) {
        Notification notification = sbn.getNotification();
        if (notification.smallIcon != null) {
            // 获取或创建 StatusBarIconView
            StatusBarIconView view = getOrCreateIconView(sbn);
            view.set(new StatusBarIcon(
                sbn.getPackageName(),
                UserHandle.of(sbn.getUserId()),
                notification.smallIcon,
                notification.number,
                notification.tickerText
            ));
            iconViews.add(view);
        }
    }
    
    // 3. 计算可用宽度
    int maxWidth = mNotificationIconContainer.getMaxWidth();
    
    // 4. 核心排列算法
    layoutIcons(iconViews, maxWidth);
}
```

### 4. 排列算法详解

```java
// NotificationIconContainer 的排列策略
private void layoutIcons(List<StatusBarIconView> icons, int maxWidth) {
    removeAllViews();
    
    int iconSize = mContext.getResources()
        .getDimensionPixelSize(R.dimen.notification_icon_size);
    // 默认 iconSize ≈ 18dp
    int iconSpacing = mContext.getResources()
        .getDimensionPixelSize(R.dimen.notification_icon_spacing);
    // 默认间距 ≈ 4dp
    
    int availableWidth = maxWidth;
    int iconsThatFit = 0;
    
    // 计算能放下多少个图标
    for (int i = 0; i < icons.size(); i++) {
        int requiredWidth = (iconsThatFit + 1) * iconSize 
                          + iconsThatFit * iconSpacing;
        if (requiredWidth > availableWidth) {
            break;
        }
        iconsThatFit++;
    }
    
    // 情况1：所有图标都能放下
    if (iconsThatFit >= icons.size()) {
        for (StatusBarIconView view : icons) {
            addView(view);
        }
        mOverflowImageView.setVisibility(GONE);
    }
    // 情况2：放不下，显示溢出图标
    else {
        // 预留溢出图标的空间
        int overflowWidth = iconSize + iconSpacing;
        availableWidth -= overflowWidth;
        
        // 重新计算能放多少
        iconsThatFit = 0;
        for (int i = 0; i < icons.size(); i++) {
            int requiredWidth = (iconsThatFit + 1) * iconSize 
                              + iconsThatFit * iconSpacing;
            if (requiredWidth > availableWidth) break;
            iconsThatFit++;
        }
        
        // 添加能放下的图标
        for (int i = 0; i < iconsThatFit; i++) {
            addView(icons.get(i));
        }
        
        // 显示溢出图标 "...+N"
        int overflowCount = icons.size() - iconsThatFit;
        mOverflowImageView.setVisibility(VISIBLE);
        mOverflowImageView.setContentDescription(
            mContext.getString(R.string.notification_overflow_desc, 
                overflowCount));
    }
}
```

### 5. 通知优先级排序

```java
// 通知图标的显示顺序由优先级决定
// 排序规则 (高→低)：
// 1. IMPORTANCE_HIGH     (紧急通知， heads-up)
// 2. IMPORTANCE_DEFAULT  (普通通知，有声音)
// 3. IMPORTANCE_LOW      (低优先级，无声音)
// 4. IMPORTANCE_MIN      (最低，不显示在状态栏)

// 同优先级内按时间排序：新的在前
Collections.sort(notifications, (a, b) -> {
    // 先按 importance 降序
    int diff = b.getImportance() - a.getImportance();
    if (diff != 0) return diff;
    // 再按时间降序 (新的优先)
    return Long.compare(
        b.getNotification().when, 
        a.getNotification().when);
});
```

### 6. 通知图标排列示意

```
状态栏可用宽度: [████████████████████████]

情况1 - 图标少:
[📧][💬][📅][🔔]                    [时钟][📶][🔋]

情况2 - 图标多，显示溢出:
[📧][💬][📅][🔔][📷] [+3]          [时钟][📶][🔋]
  ↑ 显示5个         ↑ 溢出图标

情况3 - 分组通知:
[📧×5]                              [时钟][📶][🔋]
  ↑ 同组只显示一个聚合图标
```

### 7. 通知移除时图标更新

```java
// NotificationEntryManager.removeEntry()
public void removeEntry(String key) {
    Entry entry = mNotificationMap.get(key);
    
    // 通知监听器
    for (NotificationEntryListener listener : mEntryListeners) {
        listener.onEntryRemoved(entry, false);
    }
    
    // NotificationIconController 重新排列
    updateNotificationIcons();
    
    // 如果移除后空间足够，溢出图标中隐藏的通知会自动补上
}
```

---

## 五、时钟更新机制

### 1. 架构概览

```
AlarmManager / Handler
      │  (定时触发)
      ▼
ClockController (接口)
      │
      ▼
StatusBarClockController
      │
      ├─ ClockModel (数据层)
      │    ├─ 当前时间
      │    ├─ 时区
      │    └─ 12/24小时格式
      │
      └─ ClockView (View层)
           └─ TextClock / Clock
```

### 2. 时钟初始化

```java
// CollapsedStatusBarFragment.onViewCreated()
public void onViewCreated(View view, Bundle savedInstanceState) {
    // 获取时钟 Controller
    mStatusBarClockController = 
        mDependency.get(StatusBarClockController.class);
    
    // 绑定 View
    TextClock clockView = view.findViewById(R.id.clock);
    mStatusBarClockController.addClockView(clockView);
    
    // 启动时钟更新
    mStatusBarClockController.onTimeTick();
}
```

### 3. 时钟更新双机制

SystemUI 使用**两种机制**确保时钟准确更新：

```java
// StatusBarClockController 实现

// ═══ 机制1：精确到分钟的 Handler 定时 ═══
private void scheduleNextTick() {
    // 计算到下一分钟的时间差
    long now = System.currentTimeMillis();
    long nextMinute = getNextMinute();
    long delay = nextMinute - now;
    
    // 在下一分钟整点触发
    mHandler.postDelayed(() -> {
        onTimeTick();
        scheduleNextTick();  // 递归调度下一次
    }, delay);
}

private long getNextMinute() {
    Calendar cal = Calendar.getInstance();
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    cal.add(Calendar.MINUTE, 1);  // 下一分钟
    return cal.getTimeInMillis();
}

// ═══ 机制2：系统广播监听 ═══
// 监听时区变化、时间设置变化等
private final BroadcastReceiver mIntentReceiver = 
    new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
        switch (intent.getAction()) {
            case Intent.ACTION_TIMEZONE_CHANGED:
                // 时区变化 → 更新时区并刷新
                TimeZone newZone = TimeZone.getTimeZone(
                    intent.getStringExtra("time-zone"));
                mClockModel.setTimeZone(newZone);
                onTimeTick();
                break;
                
            case Intent.ACTION_TIME_CHANGED:
                // 用户手动改时间 → 立即刷新
                onTimeTick();
                break;
                
            case Intent.ACTION_CONFIGURATION_CHANGED:
                // 配置变化 (如12/24小时制切换)
                updateFormat();
                onTimeTick();
                break;
        }
    }
};
```

### 4. 时钟显示格式化

```java
// ClockModel 格式化逻辑
public CharSequence getFormattedTime(long timeMillis) {
    // 根据系统设置选择格式
    String formatPattern;
    
    if (DateFormat.is24HourFormat(mContext, mCurrentUserId)) {
        // 24小时制: "HH:mm"
        formatPattern = "HH:mm";
    } else {
        // 12小时制: "h:mm a"
        formatPattern = "h:mm a";
    }
    
    // 格式化
    SimpleDateFormat sdf = new SimpleDateFormat(formatPattern, 
        Locale.getDefault());
    sdf.setTimeZone(mTimeZone);
    return sdf.format(new Date(timeMillis));
}

// 最终输出示例：
// 24小时制 → "14:30"
// 12小时制 → "2:30 PM"
```

### 5. ClockView (TextClock) 更新

```xml
<!-- status_bar.xml 中的时钟 View -->
<TextClock
    android:id="@+id/clock"
    android:format12Hour="h:mm a"
    android:format24Hour="HH:mm"
    android:layout_width="wrap_content"
    android:layout_height="match_parent"
    android:singleLine="true"
    android:gravity="center"
    android:textAppearance="@style/TextAppearance.StatusBar.Clock"
/>
```

```java
// TextClock 内部也有自己的更新机制 (作为双保险)
public TextClock(Context context, AttributeSet attrs) {
    // 注册 ContentObserver 监听系统时间格式
    mContext.getContentResolver().registerContentObserver(
        Settings.System.getUriFor(Settings.System.TIME_12_24),
        false, mFormatObserver);
}

// TextClock.onTimeChanged()
// 由系统框架在每分钟广播 ACTION_TIME_TICK 时触发
private void onTimeChanged() {
    // 获取当前时间
    long now = System.currentTimeMillis();
    // 格式化并 setText
    CharSequence text = DateFormat.format(mFormat, now);
    setText(text);
}
```

### 6. 锁屏时钟的特殊处理

```java
// KeyguardStatusBarView 中的锁屏时钟
// 锁屏时钟更大，且支持动画
public class KeyguardClockSwitch {
    
    // 大时钟 (锁屏时显示)
    private TextClock mLargeClock;
    // 小时钟 (解锁后状态栏)
    private TextClock mSmallClock;
    
    // 锁屏时钟支持 Doze 动画
    // 使用 AOD (Always On Display) 时
    // 时钟会变为低亮度渲染
    public void setDozing(boolean dozing) {
        if (dozing) {
            mLargeClock.setAlpha(0.7f);  // AOD 模式
        } else {
            mLargeClock.setAlpha(1.0f);  // 正常模式
        }
    }
}
```

### 7. 时钟更新时序图

```
┌─────────────────────────────────────────────────────┐
│                   正常每分钟更新                       │
├─────────────────────────────────────────────────────┤
│                                                     │
│  Handler.postDelayed(下一分钟)                       │
│       │                                             │
│       ▼ (到达整分钟)                                  │
│  onTimeTick()                                       │
│       │                                             │
│       ├─→ ClockModel.getFormattedTime(now)          │
│       │      └─→ SimpleDateFormat.format()          │
│       │                                             │
│       └─→ ClockView.setText(formattedTime)          │
│              └─→ invalidate() → 重绘                 │
│                                                     │
│  scheduleNextTick()  ← 递归调度下一分钟               │
│                                                     │
├─────────────────────────────────────────────────────┤
│                   事件驱动更新                        │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ACTION_TIMEZONE_CHANGED → 更新时区 → 刷新           │
│  ACTION_TIME_CHANGED     → 立即刷新                  │
│  ACTION_CONFIGURATION_CHANGED → 切换格式 → 刷新      │
│  Screen On                   → 立即刷新              │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

## 六、源码版本说明

前面描述的内容基于 **AOSP (Android Open Source Project)** 的通用架构，主要参考的版本范围是：

| 版本 | API Level | 关键特征 |
|------|-----------|---------|
| **Android 12** | API 31 | 引入 `CollapsedStatusBarFragment`、`SystemUIFactory`、`Dependency` 注入体系 |
| **Android 13** | API 33 | 增强插件化架构、通知图标排列优化 |
| **Android 14** | API 34 | 进一步模块化，`StatusBarIconController` 重构 |

### 版本差异要点

```
Android 10 (Q) / 11 (R)
  → StatusBar 直接在 StatusBarManagerService 中创建
  → 没有 Fragment 化

Android 12 (S) / 13 (T)
  → CollapsedStatusBarFragment 化
  → SystemUIFactory + Dependency 依赖注入
  → 图标 Controller 体系重构

Android 14 (U) / 15 (V)
  → 进一步拆分为独立模块
  → 引入更多 Kotlin 代码
  → Edge-to-edge 显示适配
```

---

## 总结对比

| 机制 | 触发方式 | 核心类 | 更新频率 |
|------|---------|--------|---------|
| **信号图标** | 系统回调 (信号变化) | `NetworkControllerImpl` → `SignalClusterView` | 事件驱动 |
| **通知图标** | 通知增删事件 | `NotificationIconAreaController` → `NotificationIconContainer` | 事件驱动 |
| **时钟** | Handler 定时 + 系统广播 | `StatusBarClockController` → `TextClock` | 每分钟 + 事件 |
