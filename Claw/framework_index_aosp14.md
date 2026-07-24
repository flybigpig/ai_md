# AOSP 14 Framework「需求 → 改动点」速查表（补全版）

> 基于《APP 开发者转 Framework 开发：破局指南》（已留存校订版 `app_to_framework_guide.md` v1.1），按 **Android 14 (API 34, UpsideDownCake)** 校订，并补全指南中缺失/薄弱的模块：WMS / Input / SystemUI / SELinux / Perfetto 排障 / 纯系统服务。
> 路径以 `android-14.0.0_rXX` 为准；个别类名随版本微调，以本地 AOSP checkout 为准。
> 用法：拿到需求先查「二、通用索引」，再按「一、缺口深挖」或「五、深读笔记」找真实文件 + 函数 + 验证命令。

---

## 〇、全景对照（路线图 vs 覆盖状态）

| 指南路线图 | 本工作区已有笔记 | 状态 | AOSP 14 入口（补缺用） | 深读笔记 |
|---|---|---|---|---|
| 编译烧录（阶段一） | `android14_build.md` / `aosp-build-guide.md` | ✅ | — | — |
| Binder IPC（阶段二） | `binder_aidl.md` | ✅ | — | — |
| AMS / 四大组件（阶段二/三） | `ams_deep_dive.md` / `ams_modify_practice.md` + `ams_patches/` | ✅ 深覆盖 | — | — |
| HAL / 外设适配 | `hal_android14.md` / `hal_example_android14.md` / `hal_led_example/` | ✅ | — | — |
| Settings / 系统裁剪 | `framework_settings_analysis.md` / `settings_modify_practice.md` | ✅ | — | — |
| WMS 窗口管理 | — | ✅ 已补 | `frameworks/base/services/core/java/com/android/server/wm/` | `wms_deep_dive.md` |
| Input 事件分发 | — | ✅ 已补 | `frameworks/base/services/core/java/com/android/server/input/` + native `inputflinger` | `input_deep_dive.md` |
| SystemUI 定制 | — | ✅ 已补 | `frameworks/base/packages/SystemUI/` | `systemui_customization.md` |
| SELinux 策略 | 仅 HAL 示例零星涉及 | ✅ 已补 | `system/sepolicy/` | `selinux_policy.md` |
| 性能/排障（Perfetto/ANR） | — | ✅ 已补 | `perfetto` / `/data/anr/` | `perfetto_anr_troubleshooting.md` |
| 新增纯系统服务（含 AIDL） | 仅 HAL-AIDL 示例 | ✅ 已补 | `services/core/java/com/android/server/` + `SystemServer` | `system_service_aidl.md` |

---

## 一、缺口模块深挖（真实路径 + 函数 + 验证）

### 1. WMS 窗口管理
- **核心类**
  - `frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java`：`addWindow()` / `relayoutWindow()` / `removeWindow()` / `performLayoutAndPlaceSurfacesLocked()`
  - `frameworks/base/services/core/java/com/android/server/wm/WindowState.java`：单个窗口状态
  - `frameworks/base/services/core/java/com/android/server/wm/Task.java`：**原 `ActivityStack`**（Android 11 重命名），生命周期/暂停逻辑在此协调
  - `frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java`：`resumeHomeActivity()` / `getTopDisplayFocusedRootTask()`
  - `frameworks/base/core/java/android/view/SurfaceControl.java`：native surface 句柄
  - native：`frameworks/native/services/surfaceflinger/`（SurfaceFlinger）
- **常见需求**
  - 禁止某类系统对话框：在 `WMS.addWindow()` 按 `WindowManager.LayoutParams.type`（`TYPE_SYSTEM_ALERT` / `TYPE_SYSTEM_DIALOG` / `TYPE_APPLICATION_OVERLAY`）拦截，或改 `PhoneWindowManager`
  - 修改窗口/转场动画：`WMS` 动画 + `RemoteAnimationRunner` / `ShellTransition`
  - 改默认分辨率/密度：`WMS` + `DisplayManager` + `ro.sf.lcd_density`（build.prop）
- **验证**：`adb shell dumpsys window windows` / `dumpsys window displays` / `dumpsys SurfaceFlinger`

### 2. Input 事件分发
- **核心类**
  - `frameworks/base/services/core/java/com/android/server/input/InputManagerService.java`
  - native：`frameworks/native/services/inputflinger/` → `InputDispatcher.cpp`（分发策略/焦点）、`InputReader.cpp`（读设备/映射）、`EventHub.cpp`（设备枚举/事件读取）
  - `frameworks/base/core/java/android/view/InputChannel.java` / `InputEventReceiver.java`
- **按键拦截**：`PhoneWindowManager.interceptKeyBeforeQueueing(KeyEvent, int)`（入队前）/ `interceptKeyBeforeDispatching()`（分发前）
- **外设（自定义按键板）**：`frameworks/base/data/keyboards/*.kl`（scancode→keycode 映射）、`*.kcm`（key char map）；必要时加 `InputReader` 子 reader
- **验证**：`adb shell dumpsys input` / `adb shell getevent -l`（看原始事件）/ `adb shell input keyevent KEYCODE_XXX`

### 3. SystemUI 定制
- **目录**：`frameworks/base/packages/SystemUI/`
- 状态栏：`src/com/android/systemui/statusbar/phone/CentralSurfaces.java`（**Android 12+ 由 `StatusBar` 重命名**，实现类 `CentralSurfacesImpl`）
- 导航栏：`src/com/android/systemui/navigationbar/NavigationBarController.java` / `NavigationBar.java`
- 通知：`src/com/android/systemui/statusbar/notification/`
- 快速设置：`src/com/android/systemui/qs/QSPanel.java` / `QuickQSPanel.java` / `QSTileHost.java`
- 锁屏：`src/com/android/systemui/keyguard/`
- **验证**：改写后 `make SystemUI` + `adb install -r`，或整编；`adb shell kill <systemui_pid>` 让其重启；`dumpsys activity services SystemUI` 看状态

### 4. SELinux 策略
- **目录**：`system/sepolicy/` → `public/`（跨版本稳定类型/属性）、`private/`（平台私有）、`vendor/`（厂商）、`prebuilts/api/<ver>/`（版本快照）
- **关键文件**：`file_contexts`（文件→type）、`service_contexts`（binder 服务名→domain）、`hwservice_contexts`（hwbinder）、`property_contexts`（系统属性）、`seapp_contexts`（app 进程 domain）
- **新增 native 服务/节点的典型步骤**：
  1. `file_contexts` 打 label：`/system/bin/myservice u:object_r:myservice_exec:s0`
  2. 新建 `myservice.te`：`type myservice, domain; type myservice_exec, exec_type, file_type;` + `init_daemon_domain(myservice)`
  3. binder 服务：`service_contexts` 加 `myservice u:object_r:myservice_service:s0`，`.te` 里 `binder_service(myservice)` + allow 规则
  4. `make sepolicy` 或整编；`adb shell dmesg | grep avc` / `logcat | grep avc` 查拒绝，用 `audit2allow` 仅作调试参考
- **注意**：`neverallow` 很严；userdebug/eng 可 `setenforce 0` 临时验证是否 SELinux 拦截

### 5. Perfetto / ANR 排障
- **Perfetto**：`adb shell perfetto -o /data/misc/perfetto-traces/trace.pftrace -t 10s sched freq idle am wm gfx view binder`（按需选 datasource）→ `adb pull` → 用 `https://ui.perfetto.dev` 打开
- **systrace**：`frameworks/native/cmds/atrace/`；`python systrace.py` 已 deprecated，优先 perfetto
- **ANR**：`adb shell ls /data/anr/` → `anr_<pid>_<时间戳>`；或 `adb bugreport` 收集；`adb shell kill -3 <pid>` 触发 Java 线程栈 dump 到 logcat
- **主线程阻塞**：perfetto 里看 `am`/`wm` 与 Binder 事务耗时；`adb shell am hang` 可制造等待
- **内存**：`adb shell dumpsys meminfo system_server`（或 `<pkg>`）；泄漏看趋势 + `binder` 代理数

### 6. 新增纯系统服务（含 AIDL）
1. **定义 AIDL**：`frameworks/base/core/java/android/os/IMyService.aidl`，接口方法如 `void doSomething();`；公开 SDK 则去掉 `@hide` 走 API 审核，否则 `@hide`
2. **实现**：`frameworks/base/services/core/java/com/android/server/MyService.java` `extends IMyService.Stub`（可同时 `extends SystemService` 接入生命周期）
3. **注册**：`SystemServer.startOtherServices()`（或 `startBootstrapServices`，看重要性）里 `ServiceManager.addService(Context.MY_SERVICE, mMyService);`；`Context` 加常量；若走 `SystemService` 用 `publishBinderService()`
4. **SELinux**：`service_contexts` + `system_server.te` allow（见上）
5. **客户端**：`ServiceManager.getService("myservice")` → `IMyService.Stub.asInterface()`；或封装进 `Context.getSystemService()`
6. **验证**：`adb shell service list | grep myservice`；实现 `dump()` 后 `dumpsys myservice`

---

## 二、通用「需求 → 文件」速查索引（AOSP 14 精确版）

| 需求 | 改哪层 | 关键文件（AOSP 14） | 关键函数/类 | 验证 |
|---|---|---|---|---|
| 修改开机动画 | framework/cmds | `frameworks/base/cmds/bootanimation/` | `BootAnimation.cpp` | 重启看动画 |
| 修改默认亮度 | SettingsProvider | `frameworks/base/packages/SettingsProvider/res/values/defaults.xml` | `def_screen_brightness` | `settings get system screen_brightness` |
| 禁止某系统对话框 | WMS/PWM | `services/core/java/com/android/server/wm/WindowManagerService.java`（addWindow） | `addWindow()` | `dumpsys window` + 复现 |
| 修改音量步长 | AudioService | `services/core/java/com/android/server/audio/AudioService.java` | `adjustStreamVolume()` | `input keyevent KEYCODE_VOLUME_UP` |
| 修改输入法/窗口动画 | WMS | `services/core/java/com/android/server/wm/WindowManagerService.java` | `relayoutWindow()` | `dumpsys window` |
| 改状态栏图标 | SystemUI | `packages/SystemUI/src/com/android/systemui/statusbar/phone/CentralSurfaces.java` | — | `kill <systemui_pid>` 看 |
| 改导航栏 | SystemUI | `packages/SystemUI/src/com/android/systemui/navigationbar/NavigationBar.java` | — | 同上 |
| 多任务键改回桌面 | PWM | `services/core/java/com/android/server/policy/PhoneWindowManager.java` | `interceptKeyBeforeQueueing()` | `input keyevent KEYCODE_APP_SWITCH` |
| 新增系统属性 | build | `build/make/target/product/*.mk` 或 `system.prop` | `PRODUCT_PROPERTY_OVERRIDES` | `getprop xxx` |
| 适配自定义按键板 | Input | `frameworks/base/data/keyboards/*.kl` + `InputReader` | `KeyLayoutMap` | `getevent -l` |
| 新增系统服务 | SystemServer | `services/core/java/com/android/server/MyService.java` + `SystemServer` | `addService()` | `service list` |
| SELinux 放行新服务 | sepolicy | `system/sepolicy/{private,vendor}/*.te` + `service_contexts` | `allow ...` | `dmesg \| grep avc` |

---

## 三、排障命令速查（AOSP 14 修正）

```bash
# ===== 窗口/显示 =====
adb shell dumpsys window | grep mCurrentFocus
adb shell dumpsys window windows
adb shell dumpsys window displays

# ===== Activity =====
adb shell dumpsys activity activities
adb shell dumpsys activity top
adb shell dumpsys activity processes

# ===== 输入 =====
adb shell dumpsys input
adb shell getevent -l            # 原始输入事件
adb shell input keyevent KEYCODE_APP_SWITCH

# ===== 性能 / 排障 =====
adb shell ls /data/anr/          # ANR trace
adb shell kill -3 <pid>          # Java 线程栈 dump 到 logcat
adb shell perfetto -o /data/misc/perfetto-traces/t.pftrace -t 10s sched freq idle am wm gfx binder
adb shell dumpsys meminfo system_server
adb shell top -n 1 | head -20

# ===== 服务 =====
adb shell service list
adb shell service call activity 1599295570   # ⚠️ magic number 随 API level 变,勿硬编码

# ===== Binder =====
adb shell cat /sys/kernel/debug/binder/stats
adb shell cat /sys/kernel/debug/binder/transaction_log

# ===== 日志 =====
adb logcat -b main -b system -v threadtime | grep -E "system_server|WindowManager|ActivityManager"
adb shell dmesg | grep avc     # SELinux 拒绝
adb logcat -c && adb logcat -v threadtime > all_log.txt
```

---

## 四、给本工作区的学习清单（按缺口优先级）

1. **WMS**（最高优先）：覆盖最空白却最常被改——先 `dumpsys window` 看懂 WindowState/Task，再试「禁止某系统对话框」
2. **Input**：外设适配基础，`getevent -l` + `.kl/.kcm` 改一遍自定义按键
3. **SystemUI**：状态栏/导航栏/QS 三块各改一处，练 `kill systemui` 验证
4. **SELinux**：给现有 `hal_led_example` 补一份完整 `service_contexts` + `.te`，做到不看 `avc` 也能过
5. **Perfetto/ANR**：抓一次开机/卡顿 trace，定位一个主线程阻塞点
6. **纯系统服务**：把 HAL-AIDL 经验升级为「Java 系统服务 + AIDL + SystemServer 注册」

---

## 五、缺口模块深读笔记（本次补齐）

以下 6 篇为本次补齐的缺口深读（AOSP 14，真实路径 + 方法名 + 验证 + 实战项目），与上方速查表配合使用：

| 模块 | 深读笔记 | 一句话 |
|---|---|---|
| **来源指南（已校订留存）** | `app_to_framework_guide.md` | 路线图 + 学习方法 + 排障思维；含 AOSP 14 校订与「附录 A：与现有笔记对照」 |
| WMS 窗口管理 | `wms_deep_dive.md` | addWindow/relayoutWindow、Task(原 ActivityStack)、焦点/动画 hook |
| Input 事件分发 | `input_deep_dive.md` | EventHub→InputReader→InputDispatcher、`PhoneWindowManager` 拦截、`.kl/.kcm` |
| SystemUI 定制 | `systemui_customization.md` | `CentralSurfaces`/`NavigationBar`/QS、`kill pid` 验证 |
| SELinux 策略 | `selinux_policy.md` | public/private/vendor、`file_contexts`/`service_contexts`、`audit2allow` |
| Perfetto/ANR 排障 | `perfetto_anr_troubleshooting.md` | perfetto 抓 trace、`/data/anr/`、`kill -3`、`meminfo` |
| 新增纯系统服务 | `system_service_aidl.md` | AIDL→Stub→`SystemServer.addService`→SELinux→客户端 |
