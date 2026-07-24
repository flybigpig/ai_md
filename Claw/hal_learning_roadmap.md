# HAL 学习路线图（Android 14 / AOSP）

> 目标：用最少的时间建立「HAL 全栈」心智模型，并能动手写、编、调、读真实 HAL。
> 适用版本：AOSP `android-14.0.0_r*`（API 34），内核 GKI `android14-6.1`。
> 配合工作区已有材料：`hal_version_history.md`、`hal_android14.md`、`binder_aidl.md`、`hal_example_android14.md`。

---

## 0. 先记住这一句话（心智模型）

**Project Treble 把 framework（/system）和厂商实现（/vendor）解耦。** HAL 因此变成独立进程，经 binder IPC 通信，接口用 IDL 描述成"版本化契约"，由 VINTF 校验。所有 HAL 代码都要挂回这条主线理解。

**三个 binder 域（Android 14 铁律）：**
| 域 | 节点 | 服务管理器 | 管什么 |
|----|------|-----------|--------|
| framework↔framework / 新 AIDL HAL | `/dev/binder` | `servicemanager`(`system/bin/servicemanager`) | 系统服务 + AIDL HAL |
| framework↔遗留 HIDL HAL | `/dev/hwbinder` | `hwservicemanager`(`vendor/bin/hwservicemanager`) | 遗留 HIDL HAL |
| vendor↔vendor | `/dev/vndbinder` | `vndservicemanager`(同二进制) | vendor 进程间服务 |

⚠️ 纠偏：`hwservicemanager` 在 Android 14 **仍存在**，只服务遗留 HIDL；新 AIDL HAL 走 `servicemanager`。

---

## 1. 阶段划分与对应材料

### 阶段 1 · 演进史（读 `hal_version_history.md`）
- 看第 8、9 节 +「三个 binder 域对照表」。
- 重点理解：HIDL 为何被 AIDL 取代（Android 10 功能并入、11 Stable AIDL、13 冻结、14 标准）。
- 真实参考：`hardware/interfaces/*`（老 `.hal` 与 新 `aidl/` 并存）。

### 阶段 2 · 架构解剖（读 `hal_android14.md`）
- 四层：Framework ↔ Treble 边界(AIDL+VINTF) ↔ HAL 实现 ↔ 内核(GKI)。
- 读完后能在白纸画出：Framework 进程 → servicemanager → HAL 进程 → 内核驱动 调用链。

### 阶段 3 · IPC 机制（读 `binder_aidl.md`）
- 搞懂 AIDL 如何编译成 `BnXxx`(服务端桩)/`BpXxx`(客户端桩)、`Parcel` 序列化、`transact`/`onTransact` 与方法对应。
- 真实路径：
  - `frameworks/native/libs/binder/`（libbinder，`BnInterface`/`BpInterface`/`Parcel`）
  - `frameworks/native/libs/binder/ndk/`（NDK 后端，`AIBinder`、`AServiceManager`）
  - `system/libhwbinder/`（遗留 HIDL 的 hwbinder 后端）

### 阶段 4 · 动手写一个 HAL（照 `hal_example_android14.md` 做）
- 实现 `android.hardware.led`：`.aidl`(`@VintfStability`) → `aidl_interface`(`stability:"vintf"`) → `BnLed` 实现 + `AServiceManager_addService` → `init.rc` → `manifest.xml`(VINTF) → SELinux(`hal_attribute(led)`) → 客户端 `waitForDeclaredService`。
- 真实落地路径（AOSP 树内）：`hardware/interfaces/led/aidl/`。

### 阶段 5 · VINTF 深潜（当前材料缺口，重点补）
- 读 `system/libvintf/`：`HalManifest`、`CompatibilityMatrix`、`VintfObject::CheckCompatibility`。
- 理解 `vendor/etc/vintf/manifest.xml` vs `system/etc/vintf/compatibility_matrix.xml` 匹配逻辑、major/minor 版本语义。
- 真实校验点：`frameworks/native/cmds/servicemanager/ServiceManager.cpp` 的 `do_add_service()`——内部用 `libvintf` 做 VINTF 校验 + SELinux 检查，失败直接拒绝注册 → HAL server 进程退出。
- 命令：`adb shell lshal --matrix` 看不匹配项。

### 阶段 6 · framework 怎么调真实 HAL（当前材料缺口，样板：vibrator）
- AIDL 接口：`hardware/interfaces/vibrator/aidl/android/hardware/vibrator/IVibrator.aidl`
- framework 客户端（Java）：`frameworks/base/services/core/java/com/android/server/VibratorService.java`
- 上层入口：`frameworks/base/core/java/android/os/VibratorManager.java` / `Vibrator.java`
- 跟一遍：`VibratorService` 从 `ServiceManager.waitForDeclaredService("android.hardware.vibrator.IVibrator/default")` 拿 `IVibrator` → 调 `vibrate()` 全链路。
- 套路通一个，audio/camera/sensors 同理。

### 阶段 7 · 调试工具箱
- `adb shell lshal | grep <name>`（AIDL 用 `lshal --aidl` 或 `service list`）
- `adb shell service check android.hardware.x.IXxx/default`
- `adb shell logcat | grep avc`（SELinux 拒绝，最常见的起不来原因）
- `adb shell ps -A | grep <hal>`（看进程是否起来）

### 阶段 8 · 进阶（按需）
- Stable AIDL 特性：`@VintfStability`、`@Backing`、`@JavaPassthrough`、`union`/`enum`/`Parcelable`、`m <iface>-update-api` 冻结。
- 遗留 HIDL passthrough 模式 + `hidl2aidl` 迁移工具。
- 内核侧：`drivers/android/binder.c`（GKI `android14-6.1`）同一驱动实例服务三个 binder 域。

---

## 2. 动手练习（按优先级）

**E1（必做）— 跑通自己的 HAL**
- 按 `hal_example_android14.md` 编出 `android.hardware.led-service.example`，刷机/推文件后：
  - `adb shell service check android.hardware.led.ILed/default` 返回 `service is running`。
- 验收：服务端进程 `ps -A | grep led-service` 可见，无 `avc` 拒绝。

**E2（理解 VINTF）— 故意改错**
- 把 `manifest.xml` 的 `<instance>default</instance>` 改成 `wrong`，重新部署重启。
- 观察：服务进程起不来 + `logcat` 出现 VINTF/servicemanager 拒绝日志。
- 验收：能复述"为什么 manifest 写错服务就崩"。

**E3（看真实设备）— 列出现有 AIDL HAL**
- `adb shell lshal --aidl` 列出设备已注册 AIDL HAL，挑一个（如 vibrator）读其 `.aidl` 源码。
- 验收：能说出该 HAL 的接口方法。

**E4（接回 framework）— 跟 vibrator 调用链**
- 从 `VibratorService.java` 一路跟到 `IVibrator` HAL 调用，画调用栈。
- 验收：能指出 framework 拿 HAL 用的是 `waitForDeclaredService` 而非老 `getService`。

---

## 3. 当前材料缺口（后续补）

1. VINTF 源码级解析（`libvintf` + `servicemanager` 校验点）。
2. framework 调用真实 HAL 的代码走读（vibrator 样板）。
3. Stable AIDL 高级类型（`Parcelable`/`union`/版本管理）。
4. 遗留 HIDL passthrough + `hidl2aidl` 迁移实战。
5. `servicemanager` 注册/校验全流程源码跟踪。

---

*本路线图复用了工作区既有 4 份 HAL 材料，并补齐了"VINTF 校验点"与"framework 调真实 HAL"两块最大缺口的 AOSP 路径。按阶段 1→8 推进，配合 E1~E4 练习即可建立完整 HAL 认知。*
