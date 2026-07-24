# 高通 SA8295 车机开发深度指南

> 面向 Android Framework / 系统开发视角。基于 AAOS（Android Automotive OS）常见方案：
> QNX Hypervisor（Type-1）托管 QNX 安全 VM（仪表/Cluster）+ Android Guest VM（IVI）。
> 8295 上常见 Android 版本为 12 / 13 / 14。

---

## 0. 心智模型（先看这个）

```
            [ 安全启动 ]  PBL(ROM) -> XBL -> ABL/UEFI -> QNX Hypervisor
                                     │
                       ┌─────────────┴─────────────┐
                  Guest VM: QNX                Guest VM: Android
                  (安全关键 / Cluster)          (IVI / 信息娱乐)
                  - 仪表、ASIL                  - CarService
                  - vehicled                    - SystemUI / CarLauncher
                                                - 第三方 APK
```

开发时记住三条主线：
1. **Android 侧改动** 99% 落在 AOSP 的 `packages/services/Car`、`frameworks/base`、`packages/apps/Car*`。
2. **硬件能力** 全部经 HAL（HIDL/AIDL）暴露，OEM 在 `vendor/` 下实现。
3. **QNX 侧** 与 Android 通过 virtio / 共享内存跨 VM 通信，车机信号（车速、挡位…）通常由 QNX 拥有，Android 经 shim 读。

---

## 1. Android Framework / CarService

### 1.1 CarService 启动链（从 SystemServer 到 ICar）

| 阶段 | 位置 | 说明 |
|------|------|------|
| 启动 helper | `frameworks/base/services/java/com/android/server/SystemServer.java` → `startOtherServices()` | 检测到 `FEATURE_AUTOMOTIVE` 后 `mSystemServiceManager.startService(CarServiceHelperService.class)` |
| Helper | `frameworks/base/services/core/java/com/android/server/CarServiceHelperService.java` | 以 system user 身份 `startServiceAsUser` 拉起真正的 `CarService`（`com.android.car/.CarService`），并管理用户切换生命周期 |
| CarService | `packages/services/Car/service/src/com/android/car/CarService.java` | `onCreate()` 构造 `ICarImpl`，注册所有 `CarServiceBase` 子类服务 |
| 服务清单 | `packages/services/Car/service/src/com/android/car/ICarImpl.java` | 持有 `CarPropertyService` / `CarAudioService` / `CarUserManagerService` / `CarInputService` / `CarPackageManagerService` 等 |

客户端拿到 `Car` 对象的链路：

```
App: Car.createCar(context)
   -> bindService(CarService)             // Intent: com.android.car.CarService
   -> 拿到 ICar (AIDL)                     // packages/services/Car/car-lib/.../ICar.aidl
   -> car.getCarManager(Car.PROPERTY_SERVICE)
   -> new CarPropertyManager(ICar, ICarProperty)  // 通过 AIDL 跨进程
```

### 1.2 车辆属性（最核心的 HAL 交互）

调用链（读一个车速属性为例）：

```
CarPropertyManager.getFloatProperty(SPEED, areaId)   // 应用层 android.car
   -> ICarProperty (AIDL, binder 跨进程)
   -> CarPropertyService (CarService 内)
   -> VehicleHal (packages/services/Car/service/.../hal/VehicleHal.java)
   -> IVehicle (HIDL 2.0 / AIDL android.hardware.automotive.vehicle)
   -> OEM 实现的 vehicle HAL (vendor/...)
   -> 真实总线 (CAN/MOST/以太网) 或 QNX vehicled
```

- 属性定义：`android.hardware.automotive.vehicle` 里的 `VehicleProperty` / `VehiclePropValue`。
- 订阅：`CarPropertyManager.registerCallback()` → HAL `subscribe()` 推事件。
- 踩坑：**属性 ID 与 areaId 必须和 HAL 侧完全一致**；`HAL_PROPERTY_NOT_AVAILABLE` / `VEHICLE_PROPERTY_ACCESS_READ` 权限要在 `VehicleHal` 配置好；频繁高频属性（如车速）建议批量订阅，避免 binder 风暴。

### 1.3 驾驶模式与多用户

- **UX 限制**：`CarUxRestrictionsManager` → `CarUxRestrictionsService` 读取 `R.xml.car_ux_restrictions_map`（在 `packages/services/Car/service/res/xml/`），车速>0 时限制视频/键盘等。`CarUxRestrictionsConfiguration` 决定限制级别。
- **多用户**：`CarUserManager` / `CarUserManagerService`。AAOS 默认 **headless system user（用户 0）+ 一个真人用户（通常 u10）**。切换由 `CarService` 通过 `UserHalService`（HAL `IUser`）与 QNX 协调。
- 踩坑：系统用户 headless，很多系统服务只在 system user 起；**不要在 system user 启动带 UI 的组件**；用户切换时 `CarService` 会重建 manager 连接，客户端需监听 `Car.CAR_CONNECTED`/`Car.CAR_DISCONNECTED`。

### 1.4 SystemUI / CarLauncher 定制

- `CarLauncher`：`packages/apps/CarLauncher/`，OEM 通常替换掉 `CarLauncher` 或做 RRO overlay。
- `SystemUI`：`packages/services/Car/SystemUI/`（车机版，与普通手机 SystemUI 不同分支）。
- 定制手段：
  - **Overlay（推荐）**：`PRODUCT_PACKAGE_OVERLAYS` 指向 OEM overlay 目录，改 `config.xml` / `strings.xml` / 布局，不改源码。
  - **RRO（Runtime Resource Overlay）**：独立 APK 覆盖资源，OTA 友好。
  - 直接改源码仅用于深度改动。
- 踩坑：overlay 优先级与 overlay 目录顺序强相关；`SystemUI` 车机分支与手机分支 API 不全兼容，**不要直接 merge 手机 SystemUI 改动**。

---

## 2. HAL 开发

HAL 是 Android 与 8295 硬件之间的契约。Android 12+ 正从 HIDL 迁移到 **AIDL HAL**（`.aidl` 在 `hardware/interfaces/.../aidl`）。

### 2.1 Vehicle HAL（车辆信号）

- 接口：`hardware/interfaces/automotive/vehicle/`（HIDL `2.0`；AIDL `android.hardware.automotive.vehicle`）。
- 默认实现参考：`hardware/interfaces/automotive/vehicle/2.0/default/`。
- OEM 实现：在 `vendor/<oem>/vehicle/` 实现 `IVehicle`（get/set/subscribe/listProperties）。
- AIDL 版本下，`CarPropertyService` 直接 `android.hardware.automotive.vehicle.IVehicle` 的 AIDL 代理，少一层 HIDL→AIDL 转换，延迟更低。

### 2.2 Display HAL（多屏）

- HWC：`android.hardware.graphics.composer3`（`hardware/interfaces/graphics/composer/3/`）。
- Allocator：`android.hardware.graphics.allocator`（`/allocator4`）。
- 8295 支持最多 6 路显示（仪表/中控/副驾/后排/HUD/流媒体后视镜）。每路通常是一个独立 `Display`：
  - 物理屏由 HWC 管理，SurfaceFlinger 合成。
  - 虚拟屏用 `DisplayManager.createVirtualDisplay()`（如 EVS 环视投屏）。
- 踩坑：**多屏的 vsync 源、色彩空间、HDR** 在 HWC 配置里容易错；overlay 层数不够会掉帧；车机常要求特定屏在**冷启动即点亮**（早于 Android，由 QNX/ABL 或 SPL 点亮，避免黑屏）。

### 2.3 Audio HAL（车载音频分区）

- 接口：`android.hardware.audio`（AIDL）。车机用 **Audio Zone** 概念：主驾/副驾/后排各自独立音量/焦点。
- 配置：`car_audio_configuration.xml`（被 `CarAudioService` 解析），定义 zone、device、address。
- 调用链：`CarAudioManager` → `CarAudioService`（CarService 内）→ `AudioManager`/AudioPolicy → audio HAL `primary` module。
- 踩坑：分区切换、external source（蓝牙/USB）、混音优先级（`AudioFocus`），以及 **audio HAL 必须正确处理 `setAudioPortConfig`** 否则无声。

### 2.4 Camera / EVS HAL（环视）

- EVS：`android.hardware.automotive.evs`（`hardware/interfaces/automotive/evs/`）。`EvsManager` 管理相机流，用于倒车/360 环视，常要求在 **Android 起来之前或崩溃时仍能显示**（由 QNX 或单独轻量栈兜底）。
- Camera HAL：`android.hardware.camera`（普通相机/座舱内 DMS/OMS）。
- 踩坑：环视对**低延迟**敏感，EVS 走独立通路不经过完整 Camera2；多摄像头同步（sync）依赖 ISP/CV 引擎。

### 2.5 HAL 通用踩坑

- **VINTF manifest**：所有 HAL 必须在 `device/qcom/<board>/manifest.xml`（或 `compatibility_matrix.xml`）声明，否则 `hwservicemanager`/`aidl` 找不到，开机 `avc`/VINTF 校验失败。
- **HIDL↔AIDL 混用**：迁移期常见 HIDL 后端被 AIDL 客户端调用，需 passthrough 适配层，注意线程模型（`::android::hardware::RequestThreadPool`）。
- **死亡重连**：HAL 进程 crash 后 `CarPropertyService` 应有重连/重订阅逻辑，否则属性"假死"。

---

## 3. BSP / 内核 / 启动与烧录

### 3.1 启动分区（典型 GPT 布局）

```
xbl, xbl_config      # Secondary bootloader / 配置
abl                  # Android Boot Loader (UEFI/EDK2 based)，加载 hypervisor 或 boot
tz                   # TrustZone (tz.mbn) 安全世界
hyp                  # QNX Hypervisor 镜像 (hyp.mbn)
rpm / pmic           # 资源电源管理
modem / dsp / slpi   # 基带 / 音频DSP / 传感DSP
vbmeta               # AVB 验签元信息 (avbtool 生成)
boot                 # Android kernel + ramdisk (Guest VM 用)
vm-bootsys / bootconfig  # Hypervisor 启动配置 / Guest 配置
system / vendor / product / odm
userdata / metadata / persist / misc / cache
```

### 3.2 ABL / UEFI

- 代码：`bootable/bootloader/edk2`（AOSP）与 `vendor/qcom/opensource/bootloaders`（私有）。
- 行为：ABL 在 8295 上通常**先加载 QNX Hypervisor（`hyp.mbn`）**，由 Hypervisor 再拉起 QNX 与 Android Guest；纯 Android 方案（无 hypervisor）时 ABL 直接 `boot.img`。
- 踩坑：ABL 阶段点屏依赖 panel driver 与 `simplefb`/GOP；不同 board 的 `bootlogo`/点亮时序要单独调。

### 3.3 device/qcom 板级配置

- 目录：`device/qcom/<board>/`（如 ADP 参考板或 OEM 板）。
- 关键文件：
  - `BoardConfig.mk`：架构、内核 defconfig、dtb 选择、分区大小。
  - `device.mk`：产物包（PRODUCT_PACKAGES 加入 `CarService`、`CarLauncher`、`vehicles` 等）。
  - `manifest.xml`：VINTF HAL 声明。
  - `fstab.qcom`：挂载表。
  - `init.target.rc`：板级 init 动作（ko 加载、设备节点权限）。
- 踩坑：`PRODUCT_PACKAGE_OVERLAYS` 顺序、`BOARD_KERNEL_CMDLINE` 与 hypervisor 传参冲突。

### 3.4 内核与设备树

- 仓库：`kernel/msm-5.15`（或 5.10/6.1，随 Android 版本），`kernel/msm-<ver>/arch/arm64/boot/dts/qcom/`。
- 8295 DTS：`sa8295p.dtsi` + `sa8295p-<board>.dts`（如 `sa8295p-adp.dts`）。
- 关键驱动：
  - GPU：`drivers/gpu/drm/msm/`（Adreno，HWC 走 DRM 原子提交）。
  - 相机：`drivers/media/platform/qcom/camss/`、`camx`（用户态 CDK）。
  - 音频：`sound/soc/qcom/`（machine driver + codec）、`dsp/`（ADSP 音频）。
  - 显示：`drivers/gpu/drm/panel/`（屏驱）+ DSI host。
  - SoC：`drivers/soc/qcom/`（PIL 加载 DSP、rmtfs、SCM）。
  - 网络：`drivers/net/ethernet/qualcomm/`（EMAC，用于车载以太网/AVB）。
- 踩坑：
  - **设备树 overlay / dtbo** 与内核 defconfig 必须匹配。
  - 显示点亮：panel 的 `mode`/时序、`reset-gpios`、`regulator` 供电顺序，错一个就黑屏。
  - DSP（ADSP/CDSP/SLPI）固件加载依赖 `pil`/`rmtfs`，缺固件会音频/传感失灵。

### 3.5 编译与烧录

- Android 编译：
  ```bash
  source build/envsetup.sh
  lunch <target>-userdebug
  make -j$(nproc)
  ```
- QNX 侧：QNX SDP + `mkifs`/`mkboot` 打 IFS；Linux 侧常用 Yocto（QNX 提供 meta-qcom）。
- 烧录：
  - **QFIL**（Qualcomm Flash Image Loader）：用 Firehose programmer `prog_firehose_ddr.elf` + Sahara 协议，整片刷（需 EDL 模式）。
  - **fastboot**：解锁后 `fastboot flash boot/vendor/system ...`，`adb reboot bootloader`。
  - 仅 Guest 调试：`adb` + `fastboot boot` 临时内核。
- 踩坑：
  - **安全启动/AVB**：`vbmeta` 用 `avbtool` 签名，key 不匹配机子直接拒启动；`--disable-verification` 仅调试。
  - **anti-rollback**：`set_rollback_index` 升级后降版会被锁。
  - Firehose 必须匹配 board（DDR 类型/容量），错版会变砖。
  - 分区表 GPT 改动要同步 `xbl_config` 与 `fstab`，否则挂载失败。

---

## 4. 虚拟化与 QNX Hypervisor

### 4.1 架构

- QNX Hypervisor 是 **Type-1**，直接跑在 8295 上（由 ABL 加载 `hyp.mbn`）。
- 每个 Guest 用 `qvm` 配置文件描述：`memory`、`cpu`（vCPU 数）、`irq`、`vdev`（虚拟设备）、`passthrough` 设备。
- 两个 Guest：
  - **QNX 安全 VM**：仪表/Cluster，满足 ASIL；拥有部分安全外设。
  - **Android Guest**：IVI；通过 virtio 前端使用后端设备。

### 4.2 设备分配策略

| 设备 | 常见归属 | 方式 |
|------|----------|------|
| 仪表显示 | QNX | 物理 passthrough |
| 中控/副驾显示 | Android（或 QNX 拥有、Android 经 virtio-gpu 共享帧缓冲） | passthrough 或 paravirt |
| 音频 DSP | 共享 | virtio + QNX 资源仲裁 |
| 摄像头/ISP | Android（环视由 QNX 兜底） | PCIe/CSI passthrough 或 virtio |
| 车载以太网/ CAN | QNX 拥有，信号跨 VM 给 Android | 共享内存 / virtio-sock |
| 存储/网络 | 各自或共享 | virtio-blk / virtio-net |

### 4.3 跨 VM 通信（车机信号怎么到 Android）

典型方案：
```
[真实总线 CAN/以太网] -> QNX vehicled (resource manager)
        -> 共享内存区域 (qvm shared memory) 或 virtio-sock
        -> Android 侧 shim HAL (CarVehicleHal 适配)
        -> VehicleHal -> CarPropertyService -> 应用
```
- QNX 侧：`vehicled` 作为 resource manager 暴露路径，写共享内存。
- Android 侧：一个 `HAL`/native daemon 读共享内存，转成 `IVehicle` 供 `VehicleHal` 用。
- 踩坑：**共享内存缓存一致性**——跨 VM 必须 `cacheattr` 标记 non-cacheable 或显式 flush，否则读到脏数据；**中断路由**走 GIC vCPU，安全 VM 的中断延迟要单独验证。

### 4.4 启动与调试

- 启动：ABL → `hyp.mbn`（读 `qvm` 配置）→ 启动 QNX + Android。
- 调试：
  - QNX：`qconn` + QNX Momentics / `sin`, `pidin`, `tracelogger`, `devc-con` 串口。
  - Android：`adb`，Guest 内核 `earlycon` 串口。
  - 共享：`qvm` 提供 virtio-console 作为 Android 串口。
- 踩坑：
  - **设备独占**：一个物理设备只能 passthrough 给一个 VM，不能两 VM 同时持有，否则冲突。
  - **RAM carve-out**：hypervisor 与各 VM 内存要在 `xbl`/DTS 里精确划分，重叠会崩。
  - **安全 VM 抖动**：QNX 实时性受 Android 高负载影响时，需检查 vCPU 绑定与调度优先级。

---

## 5. 一条最小调试路径（IVI 黑屏为例）

1. 串口看 ABL 是否起来 → 是否加载 `hyp.mbn`。
2. QNX 侧 `pidin` 确认 Guest 进程状态。
3. Android Guest：`adb shell dmesg` 看 DRM/HWC 初始化；`logcat -b all` 看 `CarService` / `SurfaceFlinger`。
4. 显示问题先确认 HWC（`dumpsys SurfaceFlinger`）vs panel 供电（DTS regulator）。
5. 属性读不到：先 `lshal`/`aidl` 查 HAL 是否注册（VINTF），再看 `VehicleHal` 是否订阅。

---

## 6. 关键路径速查表

| 关注点 | 路径 |
|--------|------|
| SystemServer 起 CarService | `frameworks/base/services/java/com/android/server/SystemServer.java` |
| CarServiceHelper | `frameworks/base/services/core/java/com/android/server/CarServiceHelperService.java` |
| CarService 主体 | `packages/services/Car/service/src/com/android/car/CarService.java` |
| 服务清单 | `packages/services/Car/service/src/com/android/car/ICarImpl.java` |
| 车辆 HAL 桥接 | `packages/services/Car/service/src/com/android/car/hal/VehicleHal.java` |
| Car 客户端 API | `packages/services/Car/car-lib/src/android/car/` |
| 车机 SystemUI | `packages/services/Car/SystemUI/` |
| CarLauncher | `packages/apps/CarLauncher/` |
| vehicle HAL 接口 | `hardware/interfaces/automotive/vehicle/` |
| composer HAL | `hardware/interfaces/graphics/composer/` |
| audio HAL | `hardware/interfaces/audio/` |
| evs HAL | `hardware/interfaces/automotive/evs/` |
| 板级配置 | `device/qcom/<board>/`（BoardConfig.mk / manifest.xml / fstab.qcom） |
| 8295 DTS | `kernel/msm-<ver>/arch/arm64/boot/dts/qcom/sa8295p*.dts*` |
| Adreno DRM | `kernel/msm-<ver>/drivers/gpu/drm/msm/` |
| 烧录工具 | QFIL (Firehose) / fastboot / adb |

---

> 提示：高通 8295 的完整 BSP（ABL、TrustZone、QNX 侧、私有 HAL）在公开 AOSP 之外，需通过 Qualcomm 客户门户 / 芯片原厂或 Tier1 拿 `vendor/qcom` 闭源包。本文 AOSP 路径均为开源可查部分。
