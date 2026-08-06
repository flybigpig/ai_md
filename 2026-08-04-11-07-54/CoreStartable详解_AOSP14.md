# CoreStartable 详解（AOSP 14 / android-14.0.0_r1）

> 本文基于仓库 `main` 分支**真实源码**逐行注释，三个文件：
> - `frameworks/base/packages/SystemUI/src/com/android/systemui/CoreStartable.java`（63 行）
> - `frameworks/base/packages/SystemUI/src/com/android/systemui/dagger/SystemUICoreStartableModule.kt`（298 行）
> - `frameworks/base/packages/SystemUI/src/com/android/systemui/SystemUIApplication.java`（384 行，启动遍历逻辑）
>
> 这是 SystemUI 所有“开机自启组件”的统一基类/接口，是整个 SystemUI 依赖注入与启动模型的核心。

---

## 一、为什么需要 CoreStartable（历史背景）

早期 SystemUI 里，每个组件都继承一个抽象基类 `SystemUI`，重写 `start()`，再由一个手写列表 `config_systemUIServiceComponents` 挨个 `new` + `start()`。问题：

1. **启动列表要手动维护**——加一个组件必须同时改 XML 和 Java，极易漏改。
2. **无法用 Dagger 注入依赖**——基类靠反射 `newInstance()`，构造参数拿不到 Dagger 注入的实例。
3. **全局单例 vs 每用户实例混在一起**，难以区分。

AOSP 12 起重构为 **`CoreStartable` 接口 + Dagger multibinding**：
- 组件只需实现接口，依赖通过 `@Inject` 构造自动注入。
- 注册用 `@Binds @IntoMap @ClassKey(...)`，Dagger 自动收集成一个 `Map<Class<?>, CoreStartable>`。
- 启动流程遍历这个 Map，**新增组件零列表维护**。

> 注意：旧的 `SystemUI` 抽象基类仍存在于 `com.android.systemui.SystemUI`（仅供少数历史类过渡），**新代码一律实现 `CoreStartable`**。

---

## 二、接口定义逐行注释

文件：`CoreStartable.java`

```java
// ===== 文件头注释（节选关键信息）=====
// Copyright (C) 2010 The Android Open Source Project
package com.android.systemui;

import android.content.res.Configuration;
import androidx.annotation.NonNull;
import java.io.PrintWriter;

/**
 * 需要在 SystemUI 启动时运行的代码。
 *
 * 哪些 Module 被加载，由 Dagger 图控制。用如下代码把实现绑进 CoreStartable map：
 *   @Binds @IntoMap @ClassKey(FoobarStartable::class)
 *   abstract fun bind(impl: FoobarStartable): CoreStartable
 *
 * @see SystemUIApplication#startServicesIfNeeded()
 */
public interface CoreStartable extends Dumpable {   // ① 继承 Dumpable，自动获得 dump() 能力（dumpsys 可查）

    /** 实现类的主入口，在 SysUI 启动后不久被调用。 */
    void start();                                    // ② 唯一抽象方法：组件初始化入口

    /**
     * 设备配置变化时调用。不会在 start() 之前调用，
     * 但可能在 onBootCompleted() 之前调用。
     * @see android.app.Application#onConfigurationChanged(Configuration)
     */
    default void onConfigurationChanged(Configuration newConfig) {   // ③ 默认空实现，可选重写（横竖屏/语言/深色模式切换）
    }

    @Override
    default void dump(@NonNull PrintWriter pw, @NonNull String[] args) {  // ④ 来自 Dumpable，默认空；重写后 dumpsys activity service SystemUI 可见
    }

    /**
     * 在系统广播 ACTION_LOCKED_BOOT_COMPLETED 后立即调用；
     * 如果 sys.boot_completed 已为 1（例如为二级用户启动新 SysUI 实例），
     * 则在 SysUI 启动时直接调用。onBootCompleted() 绝不会在 start() 之前调用。
     */
    default void onBootCompleted() {                 // ⑤ 默认空实现；需要“开机完成后才干活”的逻辑放这里
    }
}
```

**五个要点：**
1. **`start()` 是唯一必须实现的方法**——所有初始化（创建 View、注册监听、bindService）都在这里。
2. **`onBootCompleted()` 不在 `start()` 之前**——适合“等系统完全就绪”的动作（如某些需要 `PackageManager` 全量查询的逻辑）。顺序：`start()` → (可能) `onConfigurationChanged()` → `onBootCompleted()`。
3. **`onConfigurationChanged()` 可能早于 `onBootCompleted()`**——注释明确警示这一点。
4. 继承自 **`Dumpable`**——重写 `dump()` 后，可通过 `adb shell dumpsys activity service com.android.systemui` 查看组件内部状态，排障利器。
5. 广播用的是 **`ACTION_LOCKED_BOOT_COMPLETED`**（解锁前），不是普通的 `BOOT_COMPLETED`——因为 SystemUI 是 `directBootAware`，解锁前就要启动。

---

## 三、注册机制：Dagger multibinding

文件：`SystemUICoreStartableModule.kt`（节选）

```kotlin
/**
 * 应该在 AOSP 上运行的 CoreStartable 集合。
 */
@Module(includes = [
    MultiUserUtilsModule::class,      // 二级用户相关 Startable
    StartControlsStartableModule::class,  // 设备控制（Device Controls）
    StartBinderLoggerModule::class,   // Binder 调用日志
])
abstract class SystemUICoreStartableModule {

    /** 注入 AuthController */
    @Binds
    @IntoMap
    @ClassKey(AuthController::class)
    abstract fun bindAuthController(service: AuthController): CoreStartable
    // ...
}
```

### 3.1 三个注解的含义

| 注解 | 作用 |
|------|------|
| `@Binds` | Dagger 抽象绑定：`AuthController` 实例当作 `CoreStartable` 看待（接口实现关系） |
| `@IntoMap` | 把这条绑定**塞进一个 Map**，而不是替换 |
| `@ClassKey(AuthController::class)` | 以 `AuthController.class` 作为 Map 的 **key** |

最终 Dagger 生成一个 `Map<Class<?>, Provider<CoreStartable>>`，key 是各个实现类，value 是延迟 Provider（**按需实例化，不提前 new**）。

### 3.2 全局 vs 每用户：`@PerUser`

注意第 136 行的 `NotificationChannels`：

```kotlin
    @Binds
    @IntoMap
    @ClassKey(NotificationChannels::class)
    @PerUser                                    // ← 关键：这个 Startable 是“每用户”的
    abstract fun bindNotificationChannels(sysui: NotificationChannels): CoreStartable
```

- **没有 `@PerUser`**：进 `getStartables()`，只在**系统用户（主进程）**启动一次，是全局单例。
- **加了 `@PerUser`**：进 `getPerUserStartables()`，对**每个用户**各启动一份实例（多用户/车载副驾屏场景）。

### 3.3 注册表全量（AOSP 14 默认加载的 CoreStartable）

从 `SystemUICoreStartableModule.kt` 统计，AOSP 14 默认注册了 **41 个** CoreStartable（含 `@PerUser`）；`@Module(includes=...)` 还额外引入了 3 个子 Module。完整列表（按文件顺序）：

| # | 类 | 职责 | 备注 |
|---|----|------|------|
| 1 | `AuthController` | 生物识别授权 UI | |
| 2 | `BiometricNotificationService` | 生物识别通知 | |
| 3 | `ClipboardListener` | 剪贴板监听/Overlay | |
| 4 | `GlobalActionsComponent` | 长按电源键全局操作菜单 | |
| 5 | `InstantAppNotifier` | 免安装应用通知 | |
| 6 | `KeyboardUI` | 键盘 UI | |
| 7 | `KeyguardBiometricLockoutLogger` | 锁屏生物识别锁定日志 | |
| 8 | `KeyguardViewMediator` | **锁屏状态机**（见 KeyguardService 详解） | |
| 9 | `LatencyTester` | 延迟测试（eng 用） | |
| 10 | `NotificationChannels` | 通知渠道 | **@PerUser** |
| 11 | `PowerUI` | 电量/高温警告 UI | |
| 12 | `Recents` | 概览/多任务（桥接 wm.shell） | |
| 13 | `RingtonePlayer` | 铃声播放 | |
| 14 | `ScreenDecorations` | 屏幕圆角/挖孔/刘海装饰 | |
| 15 | `SessionTracker` | 会话追踪 | |
| 16 | `ShortcutKeyDispatcher` | 快捷按键分发 | |
| 17 | `SliceBroadcastRelayHandler` | Slice 广播中继 | |
| 18 | `StorageNotification` | 存储卸载警告 | |
| 19 | `SystemActions` | 无障碍系统动作 | |
| 20 | `ThemeOverlayController` | **Monet 主题叠加**（深色/取色） | |
| 21 | `ToastUI` | Toast 显示 | |
| 22 | `MediaOutputSwitcherDialogUI` | 媒体输出切换弹窗 | |
| 23 | `VolumeUI` | **音量面板**（见架构总览） | |
| 24 | `WindowMagnification` | 窗口放大（无障碍） | |
| 25 | `WMShell` | **wm.shell 桥接**（Recents/Pip/分屏） | |
| 26 | `KeyguardLiftController` | 抬手亮屏检测 | |
| 27 | `MediaTttSenderCoordinator` | 媒体就近传输（发送端） | |
| 28 | `MediaTttChipControllerReceiver` | 媒体就近传输（接收 chip） | |
| 29 | `MediaTttCommandLineHelper` | 媒体就近传输（CLI 调试） | |
| 30 | `ChipbarCoordinator` | chipbar 临时显示 | |
| 31 | `RearDisplayDialogController` | 后显示屏对话框 | |
| 32 | `StylusUsiPowerStartable` | 触控笔电源 | |
| 33 | `PhysicalKeyboardCoreStartable` | 物理键盘 | |
| 34 | `MuteQuickAffordanceCoreStartable` | 锁屏静音快捷方式 | |
| 35 | `DreamMonitor` | 屏保(dream)监控 | |
| 36 | `AssistantAttentionMonitor` | 助手注意力监控 | |
| + | （来自 `MultiUserUtilsModule` 等） | 二级用户工具类 | includes |

> 注意：`CentralSurfacesImpl`（状态栏）、`QSFragment`、`NavigationBarController` 等**不在**这个列表里——它们是 UI 组件，由 `CentralSurfacesImpl` 自身或其他 Startable 间接创建/启动，而非作为顶层 CoreStartable 注册。

---

## 四、启动遍历：startServicesIfNeeded 逐行注释

文件：`SystemUIApplication.java`

### 4.1 两个入口

```java
public void startServicesIfNeeded() {
    final String vendorComponent = mInitializer.getVendorComponent(getResources());  // ① 厂商自定义组件（overlay 指定类名），可为 null

    // 排序保证确定性启动顺序（避免每次启动依赖随机 HashMap 顺序）
    Map<Class<?>, Provider<CoreStartable>> sortedStartables = new TreeMap<>(
            Comparator.comparing(Class::getName));                                    // ② 按类名排序
    sortedStartables.putAll(mSysUIComponent.getStartables());        // ③ 全局单例 Startable
    sortedStartables.putAll(mSysUIComponent.getPerUserStartables()); // ④ 每用户 Startable，合并进同一 Map
    startServicesIfNeeded(sortedStartables, "StartServices", vendorComponent);
}

void startSecondaryUserServicesIfNeeded() {
    // 二级用户只启动 PerUser 的那批（getPerUserStartables），不重复启动全局单例
    Map<Class<?>, Provider<CoreStartable>> sortedStartables = new TreeMap<>(
            Comparator.comparing(Class::getName));
    sortedStartables.putAll(mSysUIComponent.getPerUserStartables());                  // ⑤ 注意：只有 PerUser
    startServicesIfNeeded(sortedStartables, "StartSecondaryServices", null);          // ⑥ 厂商组件不重复
}
```

### 4.2 遍历核心

```java
private void startServicesIfNeeded(
        Map<Class<?>, Provider<CoreStartable>> startables,
        String metricsPrefix,
        String vendorComponent) {
    if (mServicesStarted) return;                              // ① 幂等保护：已启动直接返回
    mServices = new CoreStartable[startables.size() + (vendorComponent == null ? 0 : 1)];  // ② 数组预留厂商组件位

    if (!mBootCompleteCache.isBootComplete()) {                // ③ 检查 boot 是否早已完成（避免漏掉回调）
        if ("1".equals(SystemProperties.get("sys.boot_completed"))) {
            mBootCompleteCache.setBootComplete();
        }
    }

    DumpManager dumpManager = mSysUIComponent.createDumpManager();  // ④ 用于注册 dump 输出

    int i = 0;
    for (Map.Entry<Class<?>, Provider<CoreStartable>> entry : startables.entrySet()) {
        String clsName = entry.getKey().getName();
        int j = i;
        timeInitialization(clsName,
                () -> mServices[j] = startStartable(clsName, entry.getValue()),  // ⑤ 真正实例化并 start()
                log, metricsPrefix);
        i++;
    }

    if (vendorComponent != null) {                             // ⑥ 厂商组件：反射 newInstance（不走 Dagger）
        timeInitialization(vendorComponent,
                () -> mServices[mServices.length - 1] = startAdditionalStartable(vendorComponent),
                log, metricsPrefix);
    }

    for (i = 0; i < mServices.length; i++) {
        if (mBootCompleteCache.isBootComplete()) {
            notifyBootCompleted(mServices[i]);                 // ⑦ boot 已完成则立刻补调 onBootCompleted()
        }
        dumpManager.registerDumpable(mServices[i].getClass().getName(), mServices[i]);  // ⑧ 注册 dumpsys 输出
    }
    mSysUIComponent.getInitController().executePostInitTasks(); // ⑨ 执行延迟初始化任务（@Init 标注）
    log.traceEnd();
    mServicesStarted = true;                                   // ⑩ 标记完成
}
```

### 4.3 实例化的三种路径

```java
// 路径 A：Dagger 注入（正常注册项）
private static CoreStartable startStartable(String clsName, Provider<CoreStartable> provider) {
    CoreStartable startable = provider.get();   // ← Dagger 实例化并注入所有 @Inject 依赖
    return startStartable(startable);
}

// 路径 B：厂商组件（overlay 指定类名，不走 Dagger）
private static CoreStartable startAdditionalStartable(String clsName) {
    startable = (CoreStartable) Class.forName(clsName).newInstance();  // ← 反射，无 DI
    return startStartable(startable);
}

// 路径 C：统一调用 start()
private static CoreStartable startStartable(CoreStartable startable) {
    startable.start();   // ← 最终的初始化入口
    return startable;
}
```

**关键差异**：正常注册的组件走 **Dagger**（依赖全注入）；厂商组件（`vendorComponent`）走 **反射 `newInstance()`**，拿不到 Dagger 注入的依赖——所以厂商自定义组件通常只能做轻量逻辑，或自己用 `Context` 取系统服务。

### 4.4 耗时告警

```java
private static void timeInitialization(String clsName, Runnable init, TimingsTraceLog log, ...) {
    long ti = System.currentTimeMillis();
    init.run();
    ti = System.currentTimeMillis() - ti;
    if (ti > 1000) {                                       // ← 单个组件启动超过 1 秒，打印警告
        Log.w(TAG, "Initialization of " + clsName + " took " + ti + " ms");
    }
}
```

**排障要点**：若 `logcat` 出现 `Initialization of xxx took N ms`，说明该 `start()` 卡主线程，应把耗时操作（IO/网络/大量计算）挪到子线程或延迟任务。

---

## 五、生命周期总览

```
SystemServer.startSystemUi()
   └─ startServiceAsUser(SystemUIService) → SystemUI 进程创建
        └─ SystemUIAppComponentFactory.instantiateApplicationCompat()   (早于 onCreate)
             └─ Application 实例创建
SystemUIApplication.onCreate()
   ├─ mInitializer = onContextAvailable(this)   ← 建 Dagger 树
   ├─ 注册 ACTION_LOCKED_BOOT_COMPLETED 接收器（仅 system 用户）
   └─ (二级用户分支) startSecondaryUserServicesIfNeeded()
SystemUIService.onCreate()  → 回调 startServicesIfNeeded()
   └─ 遍历 sortedStartables：
        for each: provider.get()  →  start()            ← ① 每个组件 start()
   └─ boot 已完成? → onBootCompleted()                  ← ② 补调
   └─ registerDumpable() 每个组件                       ← ③ dumpsys 可见
   └─ executePostInitTasks()                            ← ④ 延迟初始化
─── 之后（收到 LOCKED_BOOT_COMPLETED 广播）───
   └─ for each: onBootCompleted()                      ← ⑤ 未提前完成则此时调
─── 配置变化（旋转/语言/深色）───
   └─ onConfigurationChanged() → 每个组件              ← ⑥
```

---

## 六、如何新增一个 CoreStartable（含车载示例）

### 6.1 标准三步

**Step 1** — 新建实现类（放在合适的功能包下）：

```java
// frameworks/base/packages/SystemUI/src/com/android/systemui/car/CarCanMonitorStartable.java
package com.android.systemui.car;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;  // 可选：标记为系统级单例
import javax.inject.Inject;

public class CarCanMonitorStartable implements CoreStartable {
    // ⭐ 构造参数全部由 Dagger 注入——这是 CoreStartable 相比旧基类最大的优势
    @Inject
    public CarCanMonitorStartable(/* 注入你需要的依赖，如 Context、某 Controller */) {}

    @Override
    public void start() {
        // 初始化：创建监听、注册广播、bindService 等
    }

    @Override
    public void onBootCompleted() {
        // 开机完成后的动作
    }

    // 可选：重写 dump() 便于排障
}
```

**Step 2** — 在 `SystemUICoreStartableModule.kt` 注册（一行）：

```kotlin
@Binds
@IntoMap
@ClassKey(CarCanMonitorStartable::class)
abstract fun bindCarCanMonitor(sysui: CarCanMonitorStartable): CoreStartable
```

**Step 3** — 编译（模块编译即可）：

```bash
source build/envsetup.sh && lunch xxx-eng && m SystemUI -j32
# 或 adb install -r out/target/product/xxx/system/priv-app/SystemUI/SystemUI.apk
```

**完成**：无需改任何启动列表，`start()` 会在下次开机自动被调用。

### 6.2 如果是“每用户”组件

加 `@PerUser`：

```kotlin
@Binds @IntoMap @ClassKey(CarPerUserThing::class) @PerUser
abstract fun bindCarPerUser(sysui: CarPerUserThing): CoreStartable
```

### 6.3 厂商隔离（不改 AOSP 主干）—— `vendorComponent`

若不想在主干 `SystemUICoreStartableModule` 注册，可在厂商 overlay 里指定 `config_systemUIVendorComponent`：

```xml
<!-- device/<vendor>/<device>/overlay/frameworks/base/packages/SystemUI/res/values/config.xml -->
<string name="config_systemUIVendorComponent" translatable="false">
    com.android.systemui.car.CarVendorStartable
</string>
```

该组件由 `startAdditionalStartable()` **反射**创建（见 4.3 路径 B），无 Dagger 注入——适合纯厂商私有、不依赖 Framework 内部类的轻量逻辑。

---

## 七、调试与排障

| 需求 | 命令 / 方法 |
|------|------------|
| 看所有 CoreStartable 启动耗时 | `adb logcat -s SystemUIService` 搜 `Initialization of` |
| 查看某组件内部状态 | `adb shell dumpsys activity service com.android.systemui` → 找组件类名（需重写 `dump()`） |
| 启动失败/崩溃 | `adb logcat -b all | grep -i systemui`；`start()` 抛异常会致 SystemUI 崩溃被 AMS 拉起 |
| 确认组件是否注册 | 搜 `SystemUICoreStartableModule` 是否有对应 `@ClassKey` |
| 启动顺序 | `TreeMap` 按类名排序，`A` 开头先于 `Z`；厂商组件永远最后 |

**常见坑：**
1. **`start()` 里做 IO/网络** → 触发 >1000ms 警告甚至 ANR，务必异步。
2. **忘记 `@Inject` 构造** → Dagger 编译报错“cannot be provided without an @Provides”。
3. **组件需要解锁前工作（如锁屏相关）却没标 `directBootAware`** → 解锁前不会启动。
4. **厂商组件想用 Dagger 注入的依赖** → 不行，它走反射；需改成正常 `@ClassKey` 注册。

---

## 八、与系列文档的衔接

- 启动总链路：`SystemUI启动流程详解_AOSP14.md`（本文是其中 `startServicesIfNeeded()` 这一步的展开）
- 模块全景：`SystemUI架构总览与模块详解_AOSP14.md`（第三节“Dagger + CoreStartable 组件模型”的落地）
- 锁屏状态机：`KeyguardService详解_AOSP14.md`（`KeyguardViewMediator` 正是通过本 Module 注册为 CoreStartable，见本文 3.3 第 8 项）

---

## 九、速查表

| 问题 | 答案 |
|------|------|
| 新增自启组件要改几处？ | 2 处：实现类 + Module 一行 `@ClassKey` 注册 |
| `start()` 什么时候调？ | `SystemUIService.onCreate()` → `startServicesIfNeeded()` 遍历 Map |
| `onBootCompleted()` 何时调？ | `LOCKED_BOOT_COMPLETED` 广播后；若已 boot 完成则 start 后立即补调 |
| 全局单例 vs 每用户？ | 有无 `@PerUser` 注解，分别对应 `getStartables()` / `getPerUserStartables()` |
| 厂商私有组件怎么做？ | overlay 配 `config_systemUIVendorComponent`，反射加载（无 DI） |
| 组件能否 dumpsys 看到？ | 重写 `dump()` 即可，启动时被 `registerDumpable()` |
| 启动超时阈值？ | 单组件 `start()` > 1000ms 打印 `Log.w` |
| 排序规则？ | 按类名 `TreeMap` 确定性排序，厂商组件最后 |
