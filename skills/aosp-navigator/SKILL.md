---
name: aosp-navigator
description: AOSP 源码树导航与版本分支定位元技能。当用户需要定位 Android 13/14/15 某功能对应的源码文件路径与关键类/方法、使用 Repo 管理多仓、或在任何 AOSP/Framework/HAL/内核相关任务前确定版本分支与目录结构时使用。即使用户只说"Android 14 的 AMS 在哪个文件"、"切到 android-14 分支"、"frameworks/base 里 XX 怎么实现的"、"AOSP 怎么同步源码"，也应触发。本技能为 aosp-binder / aosp-systemserver / aosp-build-flash / aosp-hal-treble / aosp-can-selinux / aosp-doc-crawler 提供统一的版本映射、目录约定与输出风格，避免每次重新推导路径。
agent_created: true
---

# aosp-navigator — AOSP 源码树导航与版本定位（元技能）

本技能是 AOSP 技能集群的前置上下文。所有 aosp-* 技能默认基于 **Android 14 (UpsideDownCake, API 34)**，内核走 GKI 分支 **android14-6.1**。其他版本在 `references/android_branches.md` 给出分支/Tag 映射。

## 何时使用

- 用户问某 Framework / 系统服务 / 驱动在哪个文件、哪个类、哪个方法。
- 需要 `repo init/sync/start`、切分支、多仓批量操作。
- 动手改代码前需要确定改 `system` 还是 `vendor` 分区（Treble 隔离）。
- 任何其他 aosp-* 技能被触发时，先据此确认版本与目录坐标。

## 版本与分支映射（速查）

| Android | 代号 | API | AOSP 分支前缀 | GKI 内核分支 | 默认 lunch 目标(模拟器) |
|---|---|---|---|---|---|
| 13 | Tiramisu | 33 | `android-13.0.0_rXX` | `android13-5.15` | `sdk_phone_x86_64-userdebug` |
| 14 | UpsideDownCake | 34 | `android-14.0.0_rXX` | `android14-6.1` | `sdk_phone_x86_64-eng` |
| 15 | VanillaIceCream | 35 | `android-15.0.0_rXX` | `android15-6.6` | `sdk_phone_x86_64-eng` |

详细 Tag 列表与内核宏开关见 `references/android_branches.md`。

## AOSP 源码树关键目录

```
frameworks/base/        # Java Framework 核心：AMS/WMS/PMS/ATMS 均在此
  services/core/java/com/android/server/am/ActivityManagerService.java
  services/core/java/com/android/server/wm/WindowManagerService.java
  services/core/java/com/android/server/wm/ActivityTaskManagerService.java
  services/core/java/com/android/server/pm/PackageManagerService.java
  services/java/com/android/server/SystemServer.java
  core/java/android/os/Binder.java, BinderProxy.java
  core/java/com/android/internal/os/Zygote.java, ZygoteInit.java
packages/apps/Launcher3/  # 桌面 Launcher
frameworks/native/       # Native 层：Binder/Native 服务/SurfaceFlinger
  libs/binder/   -> Binder.cpp BpBinder.cpp IPCThreadState.cpp ProcessState.cpp IInterface.cpp
  cmds/servicemanager/
  libs/gui/      -> SurfaceFlinger 客户端
system/core/             # init / adb / logcat / libutils
system/sepolicy/         # SELinux 策略(te/contexts)
hardware/interfaces/     # HIDL 定义 + AIDL stable HAL(配合 aidl_api/)
hardware/libhardware/    # 老式 HAL stub
build/                   # Soong/Kati/Blueprint 构建系统
art/                     # ART 运行时
bionic/                  # bionic libc
device/                  # 设备/厂商配置(含 kernel cmdline, fstab)
kernel/ 或 独立 GKI 仓   # common/drivers/android/binder.c, binder_alloc.c
```

## Repo 工作流

```bash
# 初始化(国内走清华镜像)
repo init -u https://mirrors.tuna.tsinghua.edu.cn/git/AOSP/platform/manifest -b android-14.0.0_rXX
repo sync -j$(nproc) --no-clone-bundle        # 完整同步，建议 >200GB 空闲
# 基于稳定分支开厂商开发分支(禁止直推主线)
repo start aosp-main --all
repo start <device>-custom device/<vendor>/<device>
# 多仓批量执行
repo forall -c 'git status'
# 仅同步单仓(改某模块后快速编译)
repo sync frameworks/base
```

国内镜像：`mirrors.tuna.tsinghua.edu.cn/git/AOSP`、`aosp.tuna.tsinghua.edu.cn`、`mirrors.ustc.edu.cn`。

## 分区与 Treble 隔离（改动前先判定）

| 改动类型 | 落盘分区 | 路径示例 |
|---|---|---|
| Framework / 系统服务 | `system` | `frameworks/base/...` |
| Native 系统服务 | `system` | `frameworks/native/...` |
| 厂商 HAL / 驱动 / DTS | `vendor` | `device/<vendor>/...`, `hardware/...` |
| 内核驱动 | `vendor`(GKI 外) | `common/drivers/...`(需 vendor 模块或 ACK) |

**规则**：vendor 与 system 通过 HAL 接口(稳定 AIDL/HIDL)通信，禁止 vendor 直接依赖 system 私有符号。

## 统一输出风格约定（集群内所有技能遵守）

- 源头优先给 **真实文件路径 + 方法名 + 行号区间**，其次文字。
- 大流程先用 **Mermaid** 流程图总览，再分阶段拆解。
- 改造类需求先给 **方案速查表(需求→改动层级→难度)**，再展开步骤。
- 关键函数/参数 **加粗**，附 **踩坑清单** 与 **调试验证步骤**。
- 分层讲解顺序：内核驱动 → HAL/JNI → Framework 服务 → App。

## 关联技能

- 需要 Binder IPC 全链路 → `aosp-binder`
- 需要 Zygote/SystemServer/AMS/WMS/PMS → `aosp-systemserver`
- 需要编译/刷机/模拟器 → `aosp-build-flash`
- 需要 HAL/VNDK/Treble → `aosp-hal-treble`
- 需要 SocketCAN/驱动/DTS/SELinux → `aosp-can-selinux`
- 需要定时文档爬虫 → `aosp-doc-crawler`
