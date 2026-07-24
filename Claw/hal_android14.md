# Android 14 HAL 架构深度解析

**Hardware Abstraction Layer · AIDL HAL · Treble 边界 · VINTF 契约**

> 目标版本：AOSP `android-14.0.0_r*`（API 34 / UpsideDownCake），内核 GKI `android14-6.1`。

---

## 1. 整体架构与 Treble 边界

四层结构：**Framework（system 分区）↔ Treble 接口边界（AIDL HAL + VINTF）↔ HAL 实现（vendor 分区）↔ Linux 内核（GKI）**。

Framework 与 HAL 分属不同分区、不同 SELinux 域、不同进程，经 `servicemanager` + binder 桥接。

```mermaid
flowchart TB
  subgraph SYS[system 分区 — Framework 侧]
    SS[System services\nAudioFlinger / SurfaceFlinger]
    CLI[AIDL 客户端桩\ngetService<IFoo>()]
    SM[servicemanager\nlibvintf 校验 + 路由]
  end
  SS --> CLI --> SM
  SM == Binder IPC == BORDER

  BORDER[Treble 接口边界\nAIDL HAL 接口定义 + VINTF manifest]

  subgraph VND[vendor 分区 — HAL 实现侧]
    SVC[HAL server 进程\nandroid.hardware.*.service]
    IMPL[AIDL 服务端实现\nIFoo impl + addService()]
    INIT[init + vintf manifest\nvendor/*.rc + vintf.xml]
    LIB[libhidlbase (legacy HIDL 兼容) / libbinder (AIDL)]
  end
  BORDER == ioctl / sysfs == KERNEL

  subgraph KERNEL[Linux 内核 — GKI android14-6.1]
    D1[字符设备驱动]
    D2[binder / ashmem]
    D3[vendor 内核模块]
  end
  SVC --> IMPL --> INIT --> LIB
```

---

## 2. Android 14 HAL 的核心变化

### 变化 1：HIDL 基本退场，AIDL HAL 成为唯一新增标准

老的 `hardware/interfaces/*` 下的 `.hal` 文件仍存在但冻结，新接口全部走 `aidl_interface`（Soong）。

| 维度 | HIDL（冻结） | AIDL HAL（现行） |
|------|-------------|------------------|
| 接口定义 | `hardware/interfaces/foo/1.0/IFoo.hal` | `hardware/interfaces/foo/aidl/android/hardware/foo/IFoo.aidl` |
| 生成桩 | `IFoo.hal → hwbinder` | 走 `libbinder` 的稳定桩 |
| binder 域 | `hwservicemanager`（`/dev/hwbinder`，仍保留） | `servicemanager`（`/dev/binder`） |
| Soong 声明 | `hidl_interface` | `aidl_interface` + `vendor_available: true` + `vndk.enabled`（或 `stability: "vintf"`） |

> Android 14 绝大多数新 HAL（audio、camera、vibrator、gnss、sensors、rebootescrow 等）都是 **AIDL**。

### 变化 2：`hwservicemanager` 仍在，但新 HAL 不再走它

`hwservicemanager` 进程在 Android 14 **依然存在**（`init.rc` 中 `start servicemanager` / `start hwservicemanager` / `start vndservicemanager` 三者都在），负责管理 **HIDL** HAL 服务（binder 域 `/dev/hwbinder`，独立二进制 `/vendor/bin/hwservicemanager`）。

但从 Android 11（`R`）起，**新 HAL 一律用 Stable AIDL，注册到标准 `servicemanager`（域 `/dev/binder`）**——所谓"不再有 hwservicemanager"指的是**新 AIDL HAL 不经过它**，而非该进程被删除。`servicemanager` 与 `vndservicemanager` 在 Android 12 起合并为同一二进制（`system/bin/servicemanager`，参数区分），`hwservicemanager` 仍是独立二进制，仅服务于遗留 HIDL。VINTF 校验由 `libvintf` 在 `servicemanager::addService` 路径上完成。

### 变化 3：VINTF（Vendor Interface）是 Treble 的契约

定义在 `system/libvintf/`，分两类清单：

- **vendor manifest**：`vendor/etc/vintf/manifest.xml`（或 `manifest/manifest.xml` 拆分）—— vendor 声明“我提供哪些 HAL”。
- **framework compatibility matrix**：`system/libvintf/` 编译产物 + `system/etc/vintf/compatibility_matrix.xml` —— framework 声明“我需要哪些 HAL”。

> **⚠️ 启动时 `libvintf` 做匹配检查**，不匹配的 HAL 会被 `servicemanager` 拒绝注册 → HAL server 进程 crash/退出。这就是“OTA 换了 framework 但 vendor 没动”不会崩的根因。

---

## 3. 关键源码路径（android-14.0.0_r*）

| 角色 | 路径 |
|------|------|
| AIDL HAL 接口定义总目录 | `hardware/interfaces/` |
| 参考默认实现 | `hardware/interfaces/<mod>/aidl/default/`（如 `hardware/interfaces/vibrator/aidl/default/Vibrator.cpp`） |
| HIDL（冻结）工具链 | `system/tools/hidl/` |
| AIDL 编译器 | `system/tools/aidl/` |
| servicemanager | `system/core/servicemanager/`（`service-manager.c`、`binder.c`） |
| VINTF 库 | `system/libvintf/`（`VintfObject.cpp`、`parse_string.cpp`） |
| libhidlbase | `system/libhidl/transport/` |
| libbinder（vendor 用） | `frameworks/native/libs/binder/` |
| init + rc 解析 | `system/core/init/`（`service.cpp`、`parser.cpp`） |
| SELinux 策略 | `system/sepolicy/` + `device/<oem>/<device>/sepolicy/` |
| 音频 HAL（server） | `frameworks/av/services/audioflinger/`（client）+ vendor 侧 `android.hardware.audio` |
| 相机 HAL | `hardware/interfaces/camera/` + `frameworks/av/services/camera/` |
| 图形 / SurfaceFlinger | `frameworks/native/services/surfaceflinger/` + `hardware/interfaces/graphics/` |

---

## 4. HAL server 启动链（init.rc → servicemanager → 注册）

```
1. init 读取 vendor 分区的 *.rc
   例:device/google/pixel/aoc.rc 或 vendor/etc/init/android.hardware.vibrator-service.example.rc
   声明:
     service vendor.vibrator-aidl /vendor/bin/hw/android.hardware.vibrator-service.example
         class hal
         user system
         group system
         capabilities WAKE_ALARM

2. init 拉起该进程 → main() 调用 ABinderProcess_setThreadPoolMaxThreadCount(1)
   → 实例化 Vibrator impl(android::hardware::vibrator::Vibrator)
   → AIBinder* b = AIBinder_from_Vibrator(this)   // 或 C++ aidl 类
   → AIBinder_registerService(b, "android.hardware.vibrator.IVibrator/default")

3. servicemanager::addService 路径:
   → svcmgr_handler → do_add_service()
   → 调 libvintf: 检查该 service name 是否在 manifest.xml 声明
   → 检查 SELinux: caller 进程的 type 是否有 "add" 权限到对应 service_contexts
   → 通过则写入 svclist,map[name] = binder handle

4. framework 侧(如 VibratorService.java in frameworks/base):
   → IVibrator.Stub.asInterface(
         ServiceManager.getService("android.hardware.vibrator.IVibrator/default"))
   → 拿到代理,后续调用走 binder transaction 到 vendor 进程
```

---

## 5. 具体调用链（vibrator 为例：framework → kernel）

```
VibratorService.vibrate(...)                  // frameworks/base/.../VibratorService.java
  → mVibrator.vibrate(...)                          // IVibrator AIDL proxy
    → Binder.transact(TRANSACTION_vibrate)          // libbinder
      → servicemanager 路由 → vendor 进程           // binder driver ioctl
        → Vibrator::vibrate()                       // .../vibrator/aidl/default/Vibrator.cpp
          → writeEffectNode() / ioctl(/sys/...)     // 操作字符设备
            → 内核驱动                              // drivers/*/vibrator 或 leds-class
```

---

## 6. 与你已学内容的衔接

- **Settings / DeviceConfig（之前分析）是 framework 侧配置；HAL 是 vendor 侧硬件接口。** 二者通过 `servicemanager` + binder 桥接，但属于不同分区、不同 SELinux 域、不同进程。framework 改 Settings 不会直接动 HAL，但某些 Settings 键（如音频路由、屏幕亮度）的监听会间接调用 HAL。
- **binder 驱动本身是 HAL 的“地下层”。** `drivers/android/binder.c`（GKI android14-6.1）同时服务 framework binder 和 vndbinder/hwbinder。Android 14 起 vndbinder 与 binder 共用同一驱动实例，但不同 `/dev` 节点（`binder`、`hwbinder`、`vndbinder`）。

---

## 7. 可继续深挖的方向

- **VINTF manifest 逐字段拆解** + `libvintf` 校验流程源码
- **从零写一个 AIDL HAL**（接口定义 → Soong → 默认实现 → init.rc → sepolicy → 编译进 vendor 镜像），给可 apply 的 patch
- **binder 在 HAL 场景的三个域**（`binder` / `hwbinder` / `vndbinder`）在 Android 14 的实际现状
- **`servicemanager` 源码逐行拆解**（`do_add_service` → VINTF + SELinux 双重检查）

---

*基于 AOSP android-14.0.0_r* · 配合 Binder / Settings 分析食用*
