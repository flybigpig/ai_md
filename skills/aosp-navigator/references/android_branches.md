# Android 版本 / 分支 / GKI 内核映射（详细）

> 用于 aosp-navigator 的精确版本定位。所有 aosp-* 技能默认 Android 14。

## 1. 发布分支与 Tag

AOSP 每月从 `aosp-main` 切出稳定分支 `android-<X>.0.0_r<NN>`，Tag 形如 `android-14.0.0_rXX`。
常用查询：

```bash
# 列出某个版本所有 Tag
git ls-remote https://android.googlesource.com/platform/manifest | grep android-14.0.0_r
```

### 分支对照

| Android | API | AOSP 分支(前缀) | 典型首个 Tag | GKI 内核分支 |
|---|---|---|---|---|
| 13 Tiramisu | 33 | `android-13.0.0_r` | `android-13.0.0_r1` | `android13-5.15` |
| 14 UpsideDownCake | 34 | `android-14.0.0_r` | `android-14.0.0_r1` | `android14-6.1` |
| 15 VanillaIceCream | 35 | `android-15.0.0_r` | `android-15.0.0_r1` | `android15-6.6` |

## 2. GKI 内核分支

通用内核镜像(Generic Kernel Image)分支在 `android.googlesource.com/kernel/common`：

- `android14-6.1` —— Android 14 GKI
- `android13-5.15` —— Android 13 GKI
- `android15-6.6` —— Android 15 GKI
- ACK(Android Common Kernel)开发分支：`android-14.0.0_rXX` 对应 `common-android14-6.1`

binder 驱动位置（所有 GKI 分支一致）：
`common/drivers/android/binder.c`、`common/drivers/android/binder_alloc.c`、`common/drivers/android/binder_internal.h`

## 3. 关键子系统所在仓

| 子系统 | 仓 | 关键路径 |
|---|---|---|
| Binder 驱动 | kernel/common | `drivers/android/binder*.c` |
| Binder 用户态 | platform/frameworks/native | `libs/binder/` |
| ActivityManager | platform/frameworks/base | `services/core/java/com/android/server/am/` |
| WindowManager | platform/frameworks/base | `services/core/java/com/android/server/wm/` |
| PackageManager | platform/frameworks/base | `services/core/java/com/android/server/pm/` |
| SystemServer | platform/frameworks/base | `services/java/com/android/server/SystemServer.java` |
| Zygote | platform/frameworks/base | `core/java/com/android/internal/os/Zygote*.java` + `core/jni/com_android_internal_os_Zygote.cpp` |
| SurfaceFlinger | platform/frameworks/native | `services/surfaceflinger/` |
| init | platform/system/core | `init/` |
| SELinux | platform/system/sepolicy | `public/`, `private/`, `vendor/` |
| AIDL 工具 | platform/system/tools/aidl | `aidl.cpp`, `generate*.cpp` |
| HIDL 工具 | platform/system/tools/hidl | — |
| Launcher3 | platform/packages/apps/Launcher3 | `src/com/android/launcher3/` |

## 4. 内核 defconfig 关键宏（CAN/驱动相关，详见 aosp-can-selinux）

- `CONFIG_CAN=y`、`CONFIG_CAN_RAW=y`、`CONFIG_CAN_BCM=y`
- `CONFIG_CAN_DEV=y`、`CONFIG_CAN_M_CAN=y`（M_CAN 控制器）
- `CONFIG_CAN_VCAN=y`（虚拟 CAN，调测用）
- `CONFIG_ANDROID_BINDER_IPC=y`、`CONFIG_ANDROID_BINDERFS=y`
- GKI 模块须 `=m`（vendor 可加载模块），不可直接改 GKI 内置符号。
