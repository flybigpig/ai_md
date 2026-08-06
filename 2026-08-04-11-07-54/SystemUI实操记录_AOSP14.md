# SystemUI 实操记录（AOSP 14 / android-14.0.0_r1）

> 定位：把前 5 篇（修改指南 / Manifest / 启动流程 / KeyguardService / 架构总览 / CoreStartable）的理论落到**真动手**——模块编译、刷入、可 apply 的 diff、调试命令、踩坑。
> 所有路径基于仓库 `frameworks/base/packages/SystemUI/`。车载定制视角。

---

## 0. 本文与系列文档的关系

| 文档 | 侧重 | 何时看 |
|------|------|--------|
| SystemUI修改指南_AOSP14.md | 场景速查（10 类） | 不知道改哪个文件时 |
| SystemUI_AndroidManifest_分析.md | 清单 / 权限 / 多进程 | 加组件、声明权限时 |
| SystemUI启动流程详解_AOSP14.md | 5 阶段启动链路 | 理解组件何时起来 |
| KeyguardService详解_AOSP14.md | 锁屏 Binder 桩 | 改锁屏行为时 |
| SystemUI架构总览与模块详解_AOSP14.md | 子系统地图 | 找模块归属时 |
| **SystemUI实操记录_AOSP14.md（本文）** | **命令 / diff / 调试验证** | **动手改 + 验证时** |

---

## 1. 源码定位速查（5 秒找到文件）

```
frameworks/base/packages/SystemUI/
├── AndroidManifest.xml                          # 清单（上篇已分析）
├── Android.bp                                  # 模块定义，模块名 "SystemUI"
├── src/com/android/systemui/
│   ├── SystemUIApplication.java                # Application 入口，Dagger 根
│   ├── SystemUIService.java                    # AMS 拉起的第一个 Service
│   ├── CoreStartable.java                      # 组件接口（详见 CoreStartable详解）
│   ├── dagger/
│   │   ├── SystemUIRootComponent.java
│   │   └── SystemUICoreStartableModule.kt      # CoreStartable 注册表（加组件改这）
│   ├── statusbar/phone/CentralSurfacesImpl.java# 状态栏核心控制器（旧 PhoneStatusBar）
│   ├── navigationbar/NavigationBarView.java
│   ├── qs/QSFactoryImpl.java                   # QS Tile 创建工厂
│   └── keyguard/KeyguardViewMediator.java      # 锁屏真正状态机
├── res/values/config.xml                       # 组件开关 / 参数（高频改）
└── res/values/strings.xml
```

**资源分区优先级**（overlay 不生效先查这里）：`vendor` > `product` > `system_ext` > `system` > 默认。车载定制放 `vendor/overlay` 或 `product` 覆盖层。

---

## 2. 模块编译与刷入（最高频实操）

### 2.1 只编 SystemUI，不整编

```bash
source build/envsetup.sh
lunch <你的产品>-eng          # 例: sdk_phone_x86_64-eng / <device>-userdebug
m SystemUI                    # 等价 make SystemUI，只编 SystemUI 模块
```

- 模块名就是 `SystemUI`（见 `Android.bp` 的 `android_app { name: "SystemUI" }`）。
- AOSP 14 用 **KSP/KAPT 生成 Dagger 代码**，改 `dagger/` 下 Module 后会触发增量重生成，首编较慢，后续快。

### 2.2 产物位置

```
out/target/product/<device>/system_ext/priv-app/SystemUI/SystemUI.apk
```

⚠️ AOSP 14 SystemUI 在 **`system_ext` 分区**（priv-app），不是 `/system`。推送路径要对应。

### 2.3 推到设备 / 模拟器

```bash
adb root
adb remount                 # 模拟器需 -writable-system 启动: emulator -writable-system
adb push out/target/product/<device>/system_ext/priv-app/SystemUI/SystemUI.apk \
        /system_ext/priv-app/SystemUI/
adb shell stop com.android.systemui     # 停掉当前 SystemUI 进程
adb shell start com.android.systemui    # 重新拉起（AMS 会因 persistent 自动拉，也可 reboot）
# 或直接: adb reboot
```

> 若改了 `AndroidManifest.xml`（新增组件/权限），**必须 reboot**，因为 PMS 只在扫描时解析 manifest；仅 push apk + stop/start 不会重新解析 manifest。

### 2.4 模拟器验证（无实机时）

```bash
emulator -avd <avd_name> -writable-system -qemu -enable-kvm
# 等待开机后进 adb remount
```

---

## 3. 常见修改的可 apply diff

> 以下为基于 AOSP 14 结构的**最小改动示例**。实际类名/方法名请以你仓库 `main` 分支为准（用 `m systemui` 编译报错时按提示微调）。

### 3.1 隐藏状态栏（overlay 法，推荐，不动核心代码）

新增 overlay 资源（放 `vendor/<oem>/overlay/SystemUIRes/`）：

```xml
<!-- res/values/config.xml -->
<?xml merge -->
<resources>
    <!-- 0 = 显示, 1 = 隐藏。改默认值即全局隐藏 -->
    <integer name="config_statusBarHeight">0</integer>
</resources>
```

Manifest 中声明 overlay（Android.bp 用 `runtime_resource_overlay`）。此法**无需重编 SystemUI 核心**，只编 overlay，风险最低。

### 3.2 新增 QS Tile（内置，5 步）

假设新增 `CarClimateTile`：

**Step1** 新建 `src/com/android/systemui/qs/tiles/CarClimateTile.java`：
```java
package com.android.systemui.qs.tiles;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.QSHost;
import android.service.quicksettings.Tile;

public class CarClimateTile extends QSTileImpl<QSTile.State> {
    public CarClimateTile(QSHost host) { super(host); }
    @Override public State newTileState() { return new State(); }
    @Override protected void handleClick() {
        // TODO: 调 CarClimateManager 控制空调
    }
    @Override public int getMetricsCategory() { return 0; }
    @Override public Intent getLongClickIntent() { return null; }
    @Override public CharSequence getTileLabel() {
        return mContext.getString(R.string.car_climate_tile_label);
    }
    @Override protected void handleUpdateState(State state, Object arg) {
        state.label = mContext.getString(R.string.car_climate_tile_label);
        state.icon = ResourceIcon.get(R.drawable.ic_car_climate);
    }
}
```

**Step2** 注册到工厂 `src/com/android/systemui/qs/QSFactoryImpl.java`：
```java
// createTileInternal() 的 switch 或 Map 中加:
case "carclimate":
    return new CarClimateTile(mHost);   // 实际 AOSP14 走 createTile 反射/Map，按仓库写法套
```

**Step3** `res/values/config.xml` 加入默认 Tile 列表：
```xml
<string name="quick_settings_tiles_default" translatable="false">
    wifi,bt,rotation,carclimate,...  <!-- 把 carclimate 加进序列 -->
</string>
```

**Step4** `res/values/strings.xml`：
```xml
<string name="car_climate_tile_label">车载空调</string>
```

**Step5** 加图标 `res/drawable/ic_car_climate.xml`（vector）。

### 3.3 新增 CoreStartable（车载 CAN 监听，标准自启组件）

**新建** `src/com/android/systemui/car/CanMonitorStartable.java`：
```java
package com.android.systemui.car;
import com.android.systemui.CoreStartable;
import javax.inject.Inject;

public class CanMonitorStartable implements CoreStartable {
    @Inject
    public CanMonitorStartable() {}   // 依赖由 Dagger 注入

    @Override
    public void start() {
        // TODO: 注册 CAN 接收、启动监听线程
    }
    @Override
    public void onBootCompleted() {
        // 解锁后初始化需要用户数据的部分
    }
}
```

**注册** `SystemUICoreStartableModule.kt` 加一行：
```kotlin
@Binds @IntoMap @ClassKey(CanMonitorStartable::class)
abstract fun bindCanMonitor(s: CanMonitorStartable): CoreStartable
```

→ 编译后该组件随开机**自动 start()**，零启动列表维护。详见 `CoreStartable详解_AOSP14.md`。

### 3.4 禁用锁屏（车载常见）

推荐改法（**不要**在 `KeyguardService` 的 Binder 方法里加 hack）：

```xml
<!-- 方案A: overlay config.xml -->
<bool name="config_disableLockscreen">true</bool>
```
或改 `KeyguardViewMediator` 的 `isSecure()` / 直接信任 `TrustAgent`（车载无锁屏常用 TrustAgent 自动解锁）。

### 3.5 自定义导航栏

改 `res/values/config.xml` 的 `config_navBarLayout` / `config_navBarMode`，或改 `NavigationBarView.java` 的按钮构造。隐藏导航栏用 overlay：
```xml
<bool name="config_showNavigationBar">false</bool>
```

---

## 4. 调试排障命令速查

```bash
# 重启 SystemUI（比 reboot 快，验证改动首选）
adb shell am crash com.android.systemui
# 或: adb shell stop com.android.systemui && adb shell start com.android.systemui

# 过滤 SystemUI 日志
adb logcat -b all -s "SystemUI:*"
adb logcat -b system | grep -i systemui

# 状态栏 / 通知面板状态
adb shell dumpsys statusbar
# 看 SystemUI 全部 Service 状态
adb shell dumpsys activity services com.android.systemui

# 模拟下拉通知栏 / 收起
adb shell cmd statusbar expand-notifications
adb shell cmd statusbar collapse

# 查看 CoreStartable 是否都起来了（Dumpable 实现会打印）
adb shell dumpsys activity service com.android.systemui

# 卡在锁屏 / 锁屏异常
adb shell wm dismiss-keyguard     # 调试用，跳锁屏
```

---

## 5. 踩坑清单（真实高频）

| 现象 | 原因 | 修复 |
|------|------|------|
| push 后 SystemUI 起不来 / 循环崩溃 | manifest 改了但没 reboot，PMS 未重解析 | `adb reboot` |
| 资源 overlay 不生效 | overlay 分区优先级低于被覆盖层 / 未声明 `isRRO` | 确认 overlay 在 `vendor`/`product` 且 `android:priority` 正确 |
| 改 `dagger/` Module 后编译报 Dagger 错误 | 注入图不满足（某依赖没 `@Provides`/`@Binds`） | 看 kapt/ksp 报错定位缺哪个 binding；新类必须 `@Inject` 构造或被 `@Binds` |
| `start()` 里耗时 >1000ms 系统告警 | 组件初始化阻塞主线程 | 异步化；`SystemUIApplication.startServicesIfNeeded` 会 `Log.w` 打印超时的类名 |
| 新增权限后调用报 SecurityException | 自定义权限需 `signature`/`privileged`，且 SELinux 要加 | 加 `sepolicy` + 确认 APK 用 platform 签名 |
| 子进程（`:screenshot`）崩溃不影响主界面但功能失效 | 多进程隔离，需单独编译对应代码 | 改截屏相关只动 `:screenshot` 进程代码，push 后 `am crash` 对应进程 |
| `m SystemUI` 内存溢出（OOM） | 编译器（javac/kotlinc）堆不足 | `export JAVA_OPTS="-Xmx4g"`，或 `export ANDROID_JACK_VM_ARGS` 类同；不要在 8G 以下机器编 |
| 锁屏改了不生效 | 改错层（动了 Binder 桩而非 Mediator） | 改 `KeyguardViewMediator` / overlay `config_disableLockscreen`，详见 KeyguardService 篇 |

---

## 6. 典型工作流（一次完整改动）

```
1. 定位文件（第1节速查表）
2. 改代码 / 资源（第3节 diff 参考）
3. m SystemUI                          # 编译
4. adb push .../SystemUI.apk /system_ext/priv-app/SystemUI/
5. (改了 manifest) adb reboot / (只改代码资源) adb shell am crash com.android.systemui
6. adb logcat -s SystemUI:*            # 看是否起来、有无异常
7. adb shell dumpsys statusbar         # 验证 UI 状态
8. 出问题查第5节踩坑表
```

---

## 7. 一句话总结

SystemUI 改动的**正确姿势**：优先 overlay（资源/配置）→ 不行再改核心代码；加自启组件走 `CoreStartable` + Dagger `@IntoMap`（零启动列表）；锁屏逻辑下钻 `KeyguardViewMediator` 而非 Binder 桩；改完 `m SystemUI` 只编模块，`am crash` 快速验证；动 manifest 必 reboot。
