# 《Android 系统定制开发 — 七大工作场景实战指南》分析与审查（Android 14）

> 被分析文档：`C:\Users\YTO-02231406\Downloads\Android_System_Development_Cookbook (1).md`（2492 行，v1.0，标称 Android 14 / API 34 / android-14.0.0_rXX / GKI android14-6.1）
> 审查目标：**核对 Android 14 技术准确性 + 找出与前一份 HAL 文档及本会话前几轮结论的矛盾 + 给出使用建议**。

## 一、结论前置（TL;DR）

- **定位准确、结构好**：6 大场景（新增系统服务 / 改系统行为 / 适配外设 / 裁剪系统 / 性能稳定 / 安全策略）正好覆盖车载定制全谱，每场景「需求→架构→源码路径→验证命令」闭环，速查表/路径表符合工程习惯。**作为「路线图」值得保留**。
- **但代码级细节有硬伤**：至少 **4 处 Android 10 时代残留 / AIDL-HIDL 自相矛盾 / 编造的 API**，直接照抄会编不过或行为不对。最严重的两条：
  1. **§6.1 用 HIDL 写法（`hwservice_manager_type` + `::` 双冒号）给一个 AIDL HAL 写策略** —— 与 §1「新增 HAL 一律 AIDL」自相矛盾，且对 AIDL HAL 是错的。
  2. **§2.2 `AppErrors.handleShowAnrUi(Message)`** —— Android 14 的 ANR 早已迁到 `AnrHelper` + `ProcessErrorStateRecord`，这个旧签名是 Android 10 残留（本会话第一轮我就纠正过同类问题）。
- **两份文档对「HAL 代理获取」给了对立建议**：前一份 HAL 文档在构造里用 `waitForService`（我判为 🔴 会阻塞 boot）；本 cookbook 用 `getService`+sleep 异步重试（更安全）。**本 cookbook 这点反而更对**，但两份文档没对齐，容易让人困惑。
- **使用建议**：当结构清单用，每个代码片段落地前必须对照 14 真树核对（尤其 sepolicy 宏名、ANR 入口、ANR traces 路径、overlay 资源 key）。

---

## 二、结构总览

| 场景 | 内容 | 准确度（整体） |
|---|---|---|
| 1 新增系统服务 | VehicleBodyInfoService：App→Framework→AIDL HAL→SocketCAN 全链路 | 中（HAL 部分好，sepolicy/注册有缺） |
| 2 修改系统行为 | Launcher 动画、禁用 ANR/USB 对话框 | 中偏低（ANR 入口是 10 残留） |
| 3 适配硬件外设 | CAN(DTS/defconfig/SocketCAN)、串口 JNI、输入 .kl/.idc | 高（最实用的一章） |
| 4 裁剪/定制系统 | remove-package、Settings Dashboard、Treble 分区 | 中（部分资源 key 存疑） |
| 5 性能/稳定性 | 开机优化(5 阶段)、ANR 阈值/分析、GKI 调优 | 中高（阈值表准，traces 路径错） |
| 6 安全策略 | sepolicy 流程、hiddenapi 放开 | 中（HIDL/AIDL 混用、宏名错） |
| 附录 | 日志/编译命令速查 | 高 |

---

## 三、必须修正的硬伤（按严重度）

### 🔴 1. §6.1 与 §1 的 AIDL/HIDL 自相矛盾（最严重）
- §1.6 通篇：`android.hardware.vehiclebody.IVehicleBody/default`（**单点**分隔 = AIDL），并明确说「Android 14 新增 HAL 一律 AIDL」「stability: vintf」。
- §6.1 却写：
  ```
  type hal_vehicle_hwservice, hwservice_manager_type;     // ← hwbinder 专属类型
  android.hardware.vehicle::IVehicle/default u:object_r:hal_vehicle_hwservice:s0  // ← “::” 双冒号 = HIDL 语法
  allow hal_vehicle hal_vehicle_client:hwbinder { transfer call };   // ← hwbinder
  ```
- **问题**：AIDL HAL 走 `/dev/binder` + `service_manager_type`，**不是** `hwservice_manager_type`/`hwbinder`。给 AIDL HAL 写 `hwservice_manager_type` + `::` 是错的：servicemanager 注册名对不上、`check-vintf-all`/VINTF 扫描按 AIDL 契约校验，HIDL 那套 `hwservice_manager` 根本管不到它。
- **正解**：AIDL HAL 用 `type hal_vehicle_service, service_manager_type;` + `service_contexts` 里 `android.hardware.vehicle.IVehicle/default u:object_r:hal_vehicle_service:s0`，binder 通信走 `binder_call(system_server, hal_vehicle_default)`。请作者统一成 AIDL 写法，别混。

### 🔴 2. §2.2 `AppErrors.handleShowAnrUi(Message)` 是 Android 10 残留
- 文档代码片段（行 ~1126）：
  ```java
  @Override public boolean handleShowAnrUi(Message msg) {
      AppNotRespondingDialog dialog = (AppNotRespondingDialog) msg.obj;
      // dialog.show();  // 改成静默
      return true;
  }
  ```
- **事实**：Android 11(R) 起 ANR 逻辑已从 `AppErrors` 重构到 `AnrHelper`（`frameworks/base/services/core/java/com/android/server/am/AnrHelper.java`）+ `ProcessErrorStateRecord.appNotResponding()`。`AppErrors` 在 14 上**没有** `handleShowAnrUi(Message)` 这个返回 boolean 的旧签名。照抄编译不过。
- **正解（14）**：要静默 ANR，改 `AnrHelper` 的对话框触发路径，或更直接——在 `ProcessErrorStateRecord.appNotResponding()` 里对指定包跳过 `mService.mUiHandler` 弹窗；或用 `ActivityManager` 的 `isBackgroundAnr`/crash 策略。禁用系统对话框在车载更常见的合法做法是 **`DevicePolicyManager`/`CarService` 的 headless 配置**或 `SystemUI` 侧不显示，而不是动 `AppErrors`。
- 注：本会话**第一轮**我已纠正过「AppErrors 内 ANR 逻辑已迁出」这一点，此处再次印证「cookbook 常带 10 时代片段」。

### 🟠 3. §5.2 ANR traces 路径编造
- 文档（行 ~1974）：`/data/anr/traces.txt`（最新）、`/data/anr/traces.db`（历史）。
- **事实**：Android 11+ 的 ANR 栈改由 `SIGQUIT` → `art` 经 `tombstoned`/perfetto 落盘到 **`/data/anr/anr_<时间戳>_<pid>`** 带时间戳文件；`/data/anr/traces.txt` 仅 legacy/部分路径兼容，而 **`traces.db` 根本不存在**（编造）。
- **正解**：`ls /data/anr/`，抓最新 `anr_*`；或用 `adb shell am anr <pid>` / `adb bugreport` 取。不要依赖 `traces.txt` 单一入口。

### 🟠 4. §2.2 overlay 资源 key 虚构
- `config_showAnrDialog`、`anr_delay`（行 ~1089）在 AOSP `framework-res` 里**不存在**。
- RRO 思路对，但具体 key 是编的。禁用 ANR 对话框在 14 没有现成 config 开关，需改 `AnrHelper`/SystemUI，或走 device 策略（如 `ro.debuggable`/headless 模式），不是加个 overlay bool 就行。

---

## 四、其他技术瑕疵（照抄会踩坑）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 5 | §1.4 Step4 | `java_libs_zip: { srcs: [...] }` **不是合法 Soong 模块类型** | 把 `.java` 加进 `services` 模块（或对应 `filegroup`，如 `framework-base-sources`）的 `srcs`；AIDL 由 `framework` 模块 glob 自动收 |
| 6 | §1.6.9 | framework 系统服务 `vehicle_body_info` **缺 sepolicy**（service_contexts + `system_server_service`/`app_api_service`） | 参照前一份 HAL 文档的 `hello_service`：补 `system/sepolicy/public/service.te` + `private/service_contexts`，并同步 `prebuilts/api/34.0`（否则 `sepolicy_freeze_test` 挂） |
| 7 | §6.1 | 用了 `hal_server_domain` 但**没 `hal_attribute(hal_vehiclebody)` 声明** | 先 `hal_attribute(hal_vehiclebody);` 再 `hal_server_domain(...)`；否则宏展开失败 |
| 8 | §6.1 | `halserver_domain(...)` **宏名错误**，应为 `hal_server_domain` | 改名 |
| 9 | §2.1 | `TaskFragment.shouldIgnoreForRecents(ActivityRecord)` 大概率**不存在**该方法 | 真实入口在 `RecentTasks`（`com.android.server.wm.RecentTasks`，有 `isExcludedFromRecents`/包过滤逻辑）或 `ActivityRecord` 可见性；改那里 |
| 10 | §1.6.8 | rc `class hal` + `oneshot`，注释「稳定后改为不写」逻辑含糊 | 车载 HAL 通常**不要** `oneshot`（崩溃要重启）；省电用 lazy HAL（`interface aidl`+`disabled`+`LazyServiceRegistrar`），见前一份 HAL 文档 |
| 11 | §3.1 | defconfig 直接改 `CONFIG_CAN_*` | GKI 下不能改 GKI defconfig；CAN 必须编成 **module（.ko）** 放 `vendor/lib/modules/`，或用 vendor kernel。§5.3 提了 GKI 却未在 §3 呼应 |
| 12 | §5.1 | `CONFIG_DRM=n` 建议禁用 DRM | 车载有屏设备**不能禁 DRM**（SurfaceFlinger 依赖 DRM/graphics 合成），否则黑屏。仅无显示节点才可考虑 |
| 13 | §1.6.10 | `lshal list -i \| grep vehiclebody` 验证 AIDL HAL | `lshal` 主要面向 HIDL/hwbinder；AIDL HAL 用 `service list \| grep vehiclebody` 更权威 |
| 14 | §1.3 | `VehicleBodyInfoService` 注释说「publishBinderService 注册」但类不继承 `SystemService`，代码用 `ServiceManager.addService` | 注释误导；要么继承 `SystemService` 用 `publishBinderService`，要么老实写 `addService`（当前代码 OK，注释改掉） |
| 15 | §1.4 | APP 示例 `ServiceManager.getService` 直连但未声明 `ACCESS_VEHICLE_BODY_INFO` 权限 | APP 的 `AndroidManifest.xml` 需 `<uses-permission android:name="android.permission.ACCESS_VEHICLE_BODY_INFO"/>`，否则 `enforceCallingOrSelfPermission` 抛 `SecurityException` |

---

## 五、做得好的地方（直接采纳）

- **§3 适配外设**是全文最扎实的：i.MX8 FlexCAN 的 DTS（`assigned-clocks`/`pinctrl`）、defconfig 宏集（`CONFIG_CAN_RAW/BCM/J1939/ISOTP`）、`ip link set can0 up type can bitrate 500000 sample-point 0.875`、`cansend`/`candump`/`candump 过滤` 全部准确可用。
- **§5.2 ANR 阈值表准确**：Input 5s(fg)/Broadcast fg10s·bg60s/Service fg20s·bg200s/ContentProvider publish 10s —— 与 14 源码一致。
- **§6.2 hiddenapi 三法**（`hiddenapi-greylist-max-o.txt` / `VMRuntime.setHiddenApiExemptions(prefix[])` / `@UnsupportedAppUsage(maxTargetSdk=MAX)`）技术正确，`settings get/put global hidden_api_policy` 调试手段可用。
- **§1 的「异步获取 HAL 代理 + 退避重试」思路**比前一份 HAL 文档（构造里 `waitForService` 阻塞）更稳健——这点本 cookbook 更对，但两份文档没对齐，建议统一成此方案。
- 整体「速查表 + 源码路径表 + 验证命令」格式，符合你的工程习惯，直接当 checklist 用。

---

## 六、与前几轮分析的衔接

1. **servicemanager C++ 版（前轮）**：本 cookbook §1.6.10 的 `service list \| grep` 验证正是 `checkService`；`AServiceManager_addService`（NDK）对应我讲的 servicemanager C++ 版注册路径。一致。
2. **前一份 HAL 文档（两轮前）**：本 cookbook 的 HAL 链路（AIDL + VINTF fragment + `vintf_fragments` 自动打包 + NDK `AServiceManager_addService`）与之高度一致；**差异**在于 HAL 代理获取方式（见 §三/§五）和本 cookbook 漏了 `prebuilts/api/34.0` 同步与 lazy HAL —— 这两点前一份文档讲对了，本 cookbook 没覆盖。
3. **dumpsys 两轮**：附录 `dumpsys activity/window/package/binder_stats` 与我前两轮讲的命令面、超时机制一致；`binder_stats --all` 是查 IPC 瓶颈的好补充。
4. **反复出现的模式（重要）**：你手上的「cookbook / 教程类」文档普遍带 **Android 10 时代片段**（`AppErrors.handleShowAnrUi`、HIDL `hwservice` 给 AIDL HAL、`/data/anr/traces.txt` 单文件）。**任何这类文档落地前，都必须对照 14 真树做一次版本校验**——这正是我这几轮一直在做的事。

---

## 七、使用建议（一句话）

> 把本文当**车载定制的结构清单/checklist**用，价值很高；但**每个代码块落地前对照 android-14 真树核对一遍**，尤其修正 §三的 4 个硬伤（AIDL/HIDL 矛盾、ANR 入口、traces 路径、overlay key）和 §四的 15 处瑕疵（sepolicy 宏名、HAL 服务缺策略、`prebuilts` 同步、GKI/CAN 模块约束）。修正后即可作为团队 onboarding 手册。

### 优先修正清单（落地前必做）
1. §6.1 全节改成 AIDL sepolicy 写法（删 `hwservice_manager_type`/`::`/`hwbinder`）。
2. §2.2 ANR 静默改成 `AnrHelper`/`ProcessErrorStateRecord` 入口，删 `handleShowAnrUi`。
3. §5.2 traces 路径改成 `/data/anr/anr_*`。
4. §1 补 framework 系统服务 `vehicle_body_info` 的 sepolicy（含 `prebuilts/api/34.0` 同步）。
5. §6.1 `halserver_domain`→`hal_server_domain` 并补 `hal_attribute`。
