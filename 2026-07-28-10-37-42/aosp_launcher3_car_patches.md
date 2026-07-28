# Launcher3 车载定制 Patch 合集（AOSP 14 / packages/apps/Launcher3）

> 适用版本：Android 14 (UpsideDownCake, API 34)，`packages/apps/Launcher3`
> 对应源码已对照 AOSP 14 `InvariantDeviceProfile.java` / `LauncherProvider.java` 核实；`BaseQuickstepLauncher` / `DragController` 按 AOSP 14 稳定结构编写，应用前请 `git apply --reject` 后手动对齐方法。
> 所有开关统一走 `SystemProperties`（反射读取），避免改动 `FeatureFlags`（AOSP 14 中该文件由 `featureflags` 工具链生成，手动改会被覆盖）。

---

## 0. 共用开关：CarConfig（新增文件）

所有四组 patch 都依赖这个开关类。它用反射读 `android.os.SystemProperties`，**不引入 hidden API 编译依赖**，可在 `device.mk` 里按机型动态开合，无需重编 Launcher3。

**新增文件：`src/com/android/launcher3/car/CarConfig.java`**

```java
/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.car;

/**
 * 车载 / Kiosk 模式 Launcher3 行为总开关。
 * 全部通过系统属性读取，便于在 product makefile 中按机型配置：
 *
 *   PRODUCT_PROPERTY_OVERRIDES += \
 *       ro.car.launcher.disable_overview=true \
 *       ro.car.launcher.lock_desktop=true \
 *       ro.car.launcher.secondary_grid=car_6x5
 */
public final class CarConfig {
    private CarConfig() {}

    /** 禁用多任务 / 最近任务（Overview）。 */
    public static boolean disableOverview() {
        return getBoolean("ro.car.launcher.disable_overview", false);
    }

    /** 锁定桌面：禁止任何拖拽（图标 / 文件夹 / 小部件均不可移动、不可拖拽卸载）。 */
    public static boolean lockDesktop() {
        return getBoolean("ro.car.launcher.lock_desktop", false);
    }

    /** 副屏 / 后排屏强制使用的 grid-option 名称（见 device_profiles.xml）。留空表示不强制。 */
    public static String secondaryGridName() {
        return getString("ro.car.launcher.secondary_grid", "");
    }

    private static boolean getBoolean(String prop, boolean def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (boolean) sp.getMethod("getBoolean", String.class, boolean.class)
                    .invoke(null, prop, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static String getString(String prop, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, prop, def);
        } catch (Throwable t) {
            return def;
        }
    }
}
```

**设备侧开启方式**（`device/<vendor>/<product>/device.mk`）：

```makefile
PRODUCT_PROPERTY_OVERRIDES += \
    ro.car.launcher.disable_overview=true \
    ro.car.launcher.lock_desktop=true \
    ro.car.launcher.secondary_grid=car_6x5
```

---

## 1. Patch：禁用 Overview / 最近任务

### 方案 A（推荐，最干净）：构建期排除 quickstep

AOSP 14 的「最近任务」UI 来自 `quickstep` 模块（`com.android.quickstep`，独立 APK/组件），`Launcher3` 核心 `src/` 的 `Launcher` 本身不含 recents。车载/工控最稳妥的做法是**不编入 quickstep**，直接用核心 `Launcher`：

- 在 product 的 `device.mk` 中不要引用 `quickstep` / `QuickstepLauncher` 的 overlay；
- 或在 `packages/apps/Launcher3/Android.bp` 中确认 lunch 目标未包含 `quickstep` 源码集（`srcs` 仅 `src`、`src_shortcuts`、`src_unbundled` 等）。

排除后，系统将回退到不带手势概览的 Launcher，且无 recents 进程，彻底规避 NPE 风险。

### 方案 B（运行时兜底，quickstep 必须保留时）

在 `quickstep` 模块的 recents 命令入口直接拦截。当 SystemUI 通过 `OverviewProxyService` 下发「toggle overview」时，让 `OverviewCommandHelper` 直接返回，recents 不会被拉起。

**修改文件：`quickstep/src/com/android/launcher3/quickstep/OverviewCommandHelper.java`**

在 `onOverviewToggle()` 方法开头插入 guard（定位方法：搜索 `public void onOverviewToggle()`）：

```java
    public void onOverviewToggle() {
        // >>> CAR: 车载模式禁用最近任务
        if (com.android.launcher3.car.CarConfig.disableOverview()) {
            return;
        }
        // >>> CAR END
        ...  // 原有逻辑保持不变
    }
```

> 注：若你的分支 Overview 由 `OverviewCommandHelper.addCommand(...)` + `recentsView` 触发，同样在该命令派发入口加同样的 guard 即可。
> 若需进一步让 `OverviewState` 不再被设为可见状态，可在 `quickstep/.../uioverrides/states/OverviewState.java` 的 `getVisibleElements(...)` 开头同样 `if (CarConfig.disableOverview()) return NONE;`（可选，方案 A 下无需）。

**验证**：
```bash
adb shell setprop ro.car.launcher.disable_overview true
# 从底部上滑 / 按概览键，应无任何 recents 界面；logcat 无 RecentsView 崩溃
```

---

## 2. Patch：车载开机默认布局

默认布局由 `LauncherProvider` 在首次建库时写入，布局 XML 由 `InvariantDeviceProfile` 中 `GridOption.defaultLayoutId` 指向。链路已核实：

```
LauncherProvider.DatabaseHelper.loadDefaultFavoritesIfNecessary()
   → getDefaultWorkspaceResourceId()
   → LauncherAppState.getIDP(context).defaultLayoutId   // 来自 device_profiles.xml 的 grid-option
   → 解析 res/xml/<car_default_workspace>.xml
```

### 2.1 新增默认布局资源：`res/xml/car_default_workspace_5x5.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 车载开机默认桌面：固定应用 + 一排 Hotseat -->
<favorites xmlns:launcher="http://schemas.android.com/apk/res-auto/com.android.launcher3">

    <!--  Workspace 第一屏：按屏幕行列定位 -->
    <appwidget
        launcher:packageName="com.android.car.carlauncher"
        launcher:className="com.android.car.carlauncher.CarLauncher"
        launcher:screen="0"
        launcher:x="0" launcher:y="0"
        launcher:spanX="4" launcher:spanY="3" />

    <favorite
        launcher:packageName="com.android.car.dialer"
        launcher:className="com.android.car.dialer.ui.TelecomActivity"
        launcher:screen="0" launcher:x="0" launcher:y="3" />

    <favorite
        launcher:packageName="com.android.car.media"
        launcher:className="com.android.car.media.MediaActivity"
        launcher:screen="0" launcher:x="1" launcher:y="3" />

    <favorite
        launcher:packageName="com.android.car.settings"
        launcher:className="com.android.car.settings.CarSettingsActivity"
        launcher:screen="0" launcher:x="2" launcher:y="3" />

    <favorite
        launcher:packageName="com.android.car.radio"
        launcher:className="com.android.car.radio.RadioActivity"
        launcher:screen="0" launcher:x="3" launcher:y="3" />

    <!--  Hotseat（dock 栏）：container=-101，cellX 从 0 开始 -->
    <favorite
        launcher:packageName="com.android.car.carlauncher"
        launcher:className="com.android.car.carlauncher.CarLauncher"
        launcher:container="-101" launcher:screen="0" launcher:x="0" />

    <favorite
        launcher:packageName="com.android.car.dialer"
        launcher:className="com.android.car.dialer.ui.TelecomActivity"
        launcher:container="-101" launcher:screen="0" launcher:x="1" />

    <favorite
        launcher:packageName="com.android.car.media"
        launcher:className="com.android.car.media.MediaActivity"
        launcher:container="-101" launcher:screen="0" launcher:x="2" />
</favorites>
```

> `container="-101"` 即 `CONTAINER_HOTSEAT`；`spanX/spanY` 用于小部件占位；`screen` 为工作区页码（0 起）。

### 2.2 让某个 grid-option 指向该布局：`res/xml/device_profiles.xml`

在对应的 `<grid-option ...>` 上增加/修改 `defaultLayoutId`（已核实该属性由 `GridOption` 解析）：

```xml
    <grid-option
        name="car_5x5"
        ...
        defaultLayoutId="@xml/car_default_workspace_5x5"
        ... />
```

或直接给现有 grid-option 改 `defaultLayoutId`。若希望新装/恢复出厂后强制重建，删除 `/data/data/com.android.launcher3/databases/` 下的 `launcher.db` 再重启。

**验证**：
```bash
adb shell pm clear com.android.launcher3
adb shell am force-stop com.android.launcher3
adb shell settings put global ...  # 无需
# 重启 Launcher / 设备，桌面应呈现 car_default_workspace_5x5 内容
```

---

## 3. Patch：固定桌面 / 禁止拖拽

在拖拽的两个核心入口加 guard，任一命中即返回，杜绝图标、文件夹、小部件的移动与拖拽卸载。

### 3.1 中央漏斗：`src/com/android/launcher3/DragController.java`

`DragController.startDrag(Bitmap ...)` 是所有拖拽最终汇聚的公共入口（AOSP 14 结构稳定）。在其方法体**第一行**插入：

```java
    public void startDrag(Bitmap b, int dragLayerX, int dragLayerY,
            DragSource source, ItemInfo dragInfo, float initialDragViewScale,
            int registrationPointX, int registrationPointY, boolean willDelete,
            Point dragOffset, Rect dragRegion, float initialDragViewScaleAtDragStart,
            DragOptions options) {
        // >>> CAR: 锁定桌面，禁止任何拖拽
        if (com.android.launcher3.car.CarConfig.lockDesktop()) {
            return;
        }
        // >>> CAR END
        ... // 原有逻辑保持不变
    }
```

> 定位方法：搜索 `public void startDrag(Bitmap`。若存在只接受 `View` 的 overload 且它不走 Bitmap 版本，请在那个 overload 开头同样插入相同 guard。

### 3.2 双保险：`src/com/android/launcher3/Workspace.java`

`Workspace.beginDragShared(...)` 是 Workspace 内发起拖拽的常用入口，补一道 guard：

```java
    public DragView beginDragShared(View child, DragSource source,
            Supplier<DragController.DragParams> dragParamsSupplier, DragOptions options) {
        // >>> CAR: 锁定桌面，禁止从 Workspace 发起拖拽
        if (com.android.launcher3.car.CarConfig.lockDesktop()) {
            return null;
        }
        // >>> CAR END
        ... // 原有逻辑保持不变
    }
```

**验证**：
```bash
adb shell setprop ro.car.launcher.lock_desktop true
# 长按桌面图标：不应出现拖拽浮层；文件夹内图标不可拖出；无卸载拖拽
```

---

## 4. Patch：多屏独立网格（副屏/后排屏独立布局）

AOSP 14 的 `InvariantDeviceProfile` 已原生支持多显示：`TYPE_MULTI_DISPLAY` + 每个 `Display` 单独构造 `InvariantDeviceProfile(context, display)`（源码已核实该构造函数存在）。本 patch 让副屏强制使用指定 grid-option，从而拥有独立的行列数 / 图标尺寸（即「独立布局外观」）。

> 范围说明：以下实现的是**网格/尺寸层面**的独立布局（每行图标数、图标大小、hotseat 数随屏不同）。若要**桌面内容**（哪些 app）也按屏独立，需要改 `LauncherProvider` 的 favorites 表加 `displayId` 列并区分插入——属于 DB schema 变更，超出本 patch 范围，需要可单独再做。

**修改文件：`src/com/android/launcher3/InvariantDeviceProfile.java`**

在 `public InvariantDeviceProfile(Context context, Display display)` 构造函数中，计算 `gridName` 之后、使用它之前插入副屏覆盖逻辑（源码已核实该构造函数以 `String gridName = getCurrentGridName(context);` 开头）：

```java
    public InvariantDeviceProfile(Context context, Display display) {
        // Ensure that the main device profile is initialized
        INSTANCE.get(context);
        String gridName = getCurrentGridName(context);

        // >>> CAR: 副屏 / 后排屏强制使用独立 grid-option
        String carGrid = com.android.launcher3.car.CarConfig.secondaryGridName();
        if (!carGrid.isEmpty() && display.getDisplayId() != Display.DEFAULT_DISPLAY) {
            gridName = carGrid;
        }
        // >>> CAR END

        Info defaultInfo = DisplayController.INSTANCE.get(context).getInfo();
        ... // 原有逻辑保持不变
    }
```

配套：在 `res/xml/device_profiles.xml` 中确保存在名为 `car_6x5`（或你设置的 `secondary_grid` 值）的 `<grid-option ...>`，并建议其 `defaultLayoutId` 指向副屏专用布局 XML。

**验证**：
```bash
adb shell setprop ro.car.launcher.secondary_grid car_6x5
# 将设备连到副屏（或用车机后排屏）：该屏桌面应使用 car_6x5 的行列与图标尺寸
# 主屏仍为默认 grid（如 car_5x5）
```

---

## 5. 应用与编译验证（AOSP 14 标准流程）

```bash
source build/envsetup.sh
lunch <target>-eng          # 例如 sdk_phone_x86_64-eng / 你的车机 target
# 仅编译 Launcher3 模块（quickstep 改动需同时编 quickstep）
m Launcher3
# 若改了 quickstep：m quickstep

# 推到设备 / 模拟器
adb root
adb remount
adb sync
adb shell am force-stop com.android.launcher3
adb shell am start com.android.launcher3/.uioverrides.FlaggableQuickstepLauncher \
   || adb shell am start com.android.launcher3/.Launcher
# 或整机重刷：fastboot flash system && fastboot reboot
```

### 冲突处理
- 若 `git apply` 报 context 不匹配：`git apply --reject patch.txt`，再手工把 `.rej` 里的内容按方法名定位合入。
- `OverviewCommandHelper.onOverviewToggle()` / `Workspace.beginDragShared()` / `DragController.startDrag(Bitmap)` 仅需定位方法签名后，在方法体**首行**插入 guard 即可，不依赖前后若干行。

### 开关生效（无需重编）
所有开关为运行时属性，改 `device.mk` 可固化；调试时：
```bash
adb shell setprop ro.car.launcher.disable_overview true
adb shell setprop ro.car.launcher.lock_desktop true
adb shell setprop ro.car.launcher.secondary_grid car_6x5
adb shell am force-stop com.android.launcher3
```

---

## 6. 速查表

| 需求 | 关键文件 | 开关属性 |
|---|---|---|
| 禁用 Overview / 最近任务 | `quickstep/.../OverviewCommandHelper.java`（或构建期排除 quickstep） | `ro.car.launcher.disable_overview` |
| 车载开机默认布局 | `res/xml/car_default_workspace_5x5.xml` + `device_profiles.xml` 的 `defaultLayoutId` | 无（静态资源） |
| 固定桌面禁止拖拽 | `DragController.startDrag` + `Workspace.beginDragShared` | `ro.car.launcher.lock_desktop` |
| 多屏独立网格 | `InvariantDeviceProfile(Context, Display)` + `device_profiles.xml` | `ro.car.launcher.secondary_grid` |
| 全部开关入口 | `src/com/android/launcher3/car/CarConfig.java` | — |
