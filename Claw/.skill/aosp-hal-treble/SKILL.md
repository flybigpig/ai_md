---
name: aosp-hal-treble
description: HAL 层与 Treble 厂商隔离技能。当用户新增/迁移 HAL 服务、编写 AIDL stable HAL 或 HIDL、理解 VNDK、配置 vendor 分区、处理 system 与 vendor 跨分区依赖、或为厂商 HAL 写 SELinux 域时触发。即使用户只说"怎么加一个 HAL"、"AIDL 稳定接口怎么写"、"HIDL 怎么迁移"、"vendor 分区放什么"、"VNDK 是什么"、"Treble 隔离规则"，也应触发。源码默认 Android 14，HAL 落 vendor 分区。
agent_created: true
---

# aosp-hal-treble — HAL / VNDK / Treble 厂商隔离

核心约束：**system 与 vendor 通过稳定接口(Stable AIDL / HIDL)解耦**，vendor 禁止直接依赖 system 私有符号，反之亦然。改动落 `vendor` 分区。

## 何时使用

- 新增/改造硬件抽象层（传感器、CAN、显示、音频…）。
- 迁移 HIDL → AIDL，或定义 frozen stable AIDL 接口。
- 排查 `ld` 找不到符号、`avc: denied`、VNDK 链接错误。

## 一、HAL 接口类型（Android 14 推荐）

| 类型 | 位置 | 用途 |
|---|---|---|
| **Stable AIDL** | `hardware/interfaces/<sub>/<ver>/` + `aidl_api/` | 新 HAL 首选，版本可冻结 |
| HIDL | `hardware/interfaces/<sub>/<ver>/`(`*.hal`) | 旧 HAL，逐步迁移 |
| libhardware | `hardware/libhardware/` | 传统 legacy HAL（不推荐新写） |

**Stable AIDL 示例结构**：
```
hardware/interfaces/canif/1.0/
  ICanController.aidl        # interface ICanController { ... }
  Android.bp                 # aidl_interface { name, srcs, stability: "vintf", ... }
  aidl_api/1.0/              # frozen 接口快照(一经发布不可改字段)
```

## 二、新增一个 Stable AIDL HAL 服务

1. 定义 `.aidl`：`interface ICanController { boolean open(int ifindex); ... }`，设 `stability: "vintf"`。
2. `Android.bp`：`aidl_interface { name: "canif", srcs: ["ICanController.aidl"], stability: "vintf", backend: { cpp: {enabled: true}, java: {enabled: true} } }`。
3. 实现 server（C++）：继承 `BnCanController`，注册到 `ServiceManager`（`/dev/vndbinder`，属于 vendor 域）。
4. `AndroidManifest.xml`(compatibility matrix) 在 `device/<vendor>/<device>/` 声明 HAL 版本。
5. SELinux：建 `canif` 域、`hal_canif_default` 类型，详见 `aosp-can-selinux`。

**冻结规则**：`aidl_api/<ver>/` 一旦发布，字段不可增删改；变更须升主/次版本。

## 三、VNDK 与 vendor 分区

- **VNDK**(Vendor NDK)：vendor 可链接的 system 库白名单，由 `SOONG` 在 `out` 生成 `vndk` 集。
- vendor 模块在 `Android.bp` 标 `vendor: true` / `vendor_available: true`。
- 禁止 vendor 链接非 VNDK 的 system 库（链接期报 `error: vendor module X links against non-vendor Y`）。
- GKI 内核驱动以 `=m` 模块加载，不可改 GKI 内置符号（见 `aosp-can-selinux`）。

## 四、Binder 域隔离

- `/dev/binder` — framework（system 进程间）
- `/dev/vndbinder` — vendor HAL 通信
- `/dev/hwbinder` — HIDL 跨 HAL/框架
- native 侧 `ProcessState` 用 `vndbinder`/`hwbinder` 需 `ProcessState::initWithDriver("/dev/vndbinder")`。

## 五、踩坑清单

- **`avc: denied`**：SELinux 域未授权，补 `allow hal_canif_default ...`(见 aosp-can-selinux)。
- **接口未注册**：server 未在 `init` 拉起 / rc 写错；`service list` 查不到。
- **VNDK 版本错配**：system/vendor 版本不一致导致 bootloop，AVB 校验失败。
- **HIDL→AIDL 迁移**：旧 client 用 `hwbinder`，新用 `vndbinder`，需双栈过渡期。

## 关联

- SELinux te 域 → `aosp-can-selinux`
- Binder 驱动/vndbinder → `aosp-binder`
- 编译进 vendor → `aosp-build-flash`
- 源码路径坐标 → `aosp-navigator`
