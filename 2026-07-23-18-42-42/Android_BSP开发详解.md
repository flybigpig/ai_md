# 系统 / BSP 开发详解（Android AOSP，Android 14 / API 34）

> 面向：需要在具体硬件板（SoC + 外设）上把 Android 跑起来、做板级 Bring-up、写驱动与 HAL、改内核与 vendor 分区的工程师。
> 视角：以 AOSP `android-14.0.0_rXX` 为准，内核走 GKI（`android14-6.1`）。

---

## 1. 概念与定位

### 1.1 什么是 BSP
**BSP（Board Support Package，板级支持包）** = 让一套操作系统（Linux/Android）能在某一款具体硬件板卡上运行的软件集合。它向下吃硬件（SoC、PMIC、屏、触控、Sensor、Camera、Audio Codec…），向上为系统提供稳定、统一的接口。

在 Android 里，BSP 不是单个模块，而是一组横跨 **Bootloader → Kernel → HAL → vendor 分区** 的适配代码与配置。

### 1.2 三层开发的边界
| 层级 | 关注点 | 典型产物 | 你改的代码 |
|------|--------|----------|------------|
| 应用开发 | 业务逻辑、UI | apk | `packages/apps/*`、`frameworks/base`(上层) |
| 系统开发 | Android 框架、系统镜像、服务 | `system.img`、framework jar、系统 App | `frameworks/base`、`system/*`、`build/*` |
| **BSP 开发** | 硬件适配、内核、驱动、HAL、板级配置 | `boot.img`/`vendor_boot.img`、`vendor.img`、ko、HAL so | `kernel/*`、`hardware/*`、`device/<oem>/<board>/`、`vendor/*` |

> 你常干的活（改 AMS/ATMS、加系统 app、改内核 binder）正好横跨「系统开发」和「BSP 内核层」。

### 1.3 Android 软件栈里 BSP 的位置
```
App
  └─ Framework (frameworks/base, system_server)
       └─ Native (binder, hwservicemanager, libcutils)
            └─ HAL (AIDL/HIDL, hardware/libhardware)   ← BSP 上沿
                 └─ Linux Kernel (GKI + vendor modules) ← BSP 核心
                      └─ Bootloader (ABL / U-Boot / LK)  ← BSP 底沿
                           └─ SoC + Board (硬件)
```

---

## 2. 总体架构与镜像

### 2.1 Android 14 关键分区（动态分区 + GKI）
| 分区/镜像 | 内容 | 谁来构建 |
|-----------|------|----------|
| `boot.img` | **GKI 内核** + 通用 ramdisk（`/init`、`first_stage`、generic `*.rc`） | 平台（GKI），一般不开源定制 |
| `vendor_boot.img` | vendor ramdisk + **DTB（设备树）** + vendor 内核模块（ko） | **BSP 负责** |
| `vendor.img` | 厂商闭源/板级 HAL、so、bin、固件、配置文件 | **BSP 负责** |
| `system.img` | AOSP 框架、系统 App、lib（只读系统） | 系统开发 |
| `dtbo.img` | 设备树 Overlay（板级差异） | **BSP 负责** |
| `vbmeta.img` | 启动校验（AVB） | 安全/BSP |
| `super.img` | system+vendor+product+odm 的动态分区合集 | 打包 |

> GKI 的核心思想：**内核通用化（由 Google/SoC 厂发布），板级差异全部下沉到 vendor_boot（DTB+ko）与 vendor 分区**。BSP 工程师的压力从「改内核」转移到「写设备树 + 做 vendor 模块 + 配 HAL」。

### 2.2 目录布局约定
```
device/<oem>/<board>/        ← 板级配置（你主要改这里）
  ├─ BoardConfig.mk          ← 架构/内核/分区/镜像参数
  ├─ device.mk               ← 打包哪些模块进 vendor/system
  ├─ AndroidProducts.mk      ← 产品定义（PRODUCT_xxx）
  ├─ <board>.mk              ← 板级变量
  ├─ manifest.xml            ← VINTF HAL 声明
  ├─ fstab.<board>           ← 挂载表
  ├─ init.<board>.rc         ← 板级 init 脚本
  └─ overlay/                ← 资源 overlay

kernel/                      ← 内核源码（GKI + vendor 模块）
  ├─ common/                 ← GKI 通用（android14-6.1）
  └─ <oem>/<soc>/            ← 厂商内核 + 驱动 + dts

hardware/                    ← HAL 实现
  ├─ interfaces/             ← HIDL（legacy）
  ├─ interfaces/aidl/        ← AIDL HAL（Android 11+ 推荐）
  └─ libhardware/            ← 传统 HAL stub
```

---

## 3. 启动流程（Boot Flow）

### 3.1 冷启动时序
```
上电
 └─ BootROM（固化）→ 加载 XBL / PBL
      └─ XBL / Little Kernel (LK) → 基础硬件初始化、USB/充电
           └─ ABL (Application Bootloader) → 校验+加载 boot/vendor_boot
                └─ 跳入 Kernel (start_kernel)
                     └─ rest_init → kernel_thread(kernel_init)
                          └─ 解压 vendor ramdisk，运行 /init（first stage）
                               └─ 挂载 partitions → 切 second stage init
                                    └─ 解析 init.rc / <board>.rc → uevent
                                         └─ 启动 hwservicemanager / servicemanager
                                              └─ 启动 Zygote → fork system_server
                                                   └─ 系统就绪，Launcher 起来
```

### 3.2 内核入口（你最该看的几处）
- `init/main.c`：`start_kernel()` → `arch_call_rest_init()` → `rest_init()`
- `kernel_init()`（`init/main.c`）：加载 initramfs 里的 `/init`
- Android 的 `/init` 在 `system/core/init/`，`init.cpp` 的 `main()` 是关键入口
- `FirstStageMain()`（`first_stage_init.cpp`）→ 挂载 `/system`、`/vendor` 等 → `SetupSelinux()` → `SecondStageMain()`

### 3.3 Android init 关键文件
- `system/core/rootdir/init.rc`：通用
- `device/<oem>/<board>/init.<board>.rc`：板级（启动 vendor 守护进程、insmod ko）
- `system/core/init/`：`init.cpp`、`builtins.cpp`（处理 `mount`/`insmod`/`start` 等命令）

---

## 4. Bootloader 层（BSP 底沿）

Android 设备上常见三档：
| Bootloader | 用途 | 你改什么 |
|------------|------|----------|
| XBL / PBL | SoC 固化/初级 | 基本不碰 |
| ABL (EDK2) | 高通等现代平台，加载 boot | `bootloader/EDK2/*`，设备树传参 |
| U-Boot / LK | 老平台/开发板 | `u-boot/include/configs/`、`lk/` 的 `target/`、`app/` |

典型 BSP 在 Bootloader 阶段要做的：
- 配置 DDR 时序、电源轨（PMIC）
- 选择启动介质（eMMC/UFS/SD）
- 把 `boot.img` / `vendor_boot.img` 解出来，把 DTB 地址传给内核（`bootargs`）

> 新平台通常 Bootloader 由 SoC 厂交付，BSP 重点是**正确传递内核命令行与 DTB**，而非重写 Bootloader。

---

## 5. Linux Kernel 移植（BSP 核心）

### 5.1 GKI 下的内核组织
- **GKI 内核**：`kernel/common` 的 `android14-6.1`，构建出 `Image`（或 `Image.gz`），打进 `boot.img`。
- **vendor 模块**：厂商驱动编译为 `.ko`，随 `vendor_boot.img` 加载；或编译进 vendor ramdisk。
- **DTB**：板级设备树编译进 `vendor_boot.img`（GKI 把 DTB 从内核移到了 vendor_boot）。

### 5.2 设备树（DTS/DTB）—— 板级差异的主战场
- 源码：`kernel/<oem>/<soc>/arch/arm64/boot/dts/<vendor>/<board>.dts`
- 编译：`make dtbs` → 产物 `arch/arm64/boot/dts/<vendor>/<board>.dtb`
- 关键点：
  - `compatible = "vendor,board"` 字符串是**驱动匹配板子的钥匙**
  - `chosen { bootargs = "..."; };` 传内核参数
  - 外设节点（i2c/spi/usb/gpu/display）在这里描述
- Overlay：`dtbo.img` 用 `fdtoverlay` 叠加板级微小差异（同一 SoC 不同板型）

### 5.3 defconfig
- 路径：`arch/arm64/configs/<soc>_defconfig` / `gki_defconfig`
- 生成：`make ARCH=arm64 <soc>_defconfig` → `make ARCH=arm64 menuconfig`
- 常用开关：
  - `CONFIG_ANDROID_BINDER_IPC=y`（你关注的 binder 驱动）
  - `CONFIG_MODULES=y`（vendor ko）
  - 各外设驱动 `CONFIG_XXX=y/m`

### 5.4 驱动开发（以 platform 设备为例）
典型「设备树 + platform driver」配对：
```c
// 驱动侧
static const struct of_device_id mydev_of_match[] = {
    { .compatible = "vendor,mydevice", },
    { }
};
MODULE_DEVICE_TABLE(of, mydev_of_match);

static struct platform_driver mydev_driver = {
    .probe  = mydev_probe,
    .remove = mydev_remove,
    .driver = {
        .name = "mydevice",
        .of_match_table = mydev_of_match,
    },
};
module_platform_driver(mydev_driver);
```
- `probe()`：申请资源、注册字符设备/`miscdevice`/input 设备
- 用户态通过 `/dev/mydevice` 或 sysfs 交互
- binder 驱动本体：`drivers/android/binder.c`（`binder_open`/`binder_ioctl`/`binder_thread_write`），`binder_alloc.c` 管缓冲区——这是你研究 IPC 的重点文件

### 5.5 内核编译（交叉编译）
```bash
# 设定工具链（AOSP 自带 aarch64-linux-android- 或厂商工具链）
export ARCH=arm64
export CROSS_COMPILE=aarch64-linux-android-

# GKI / vendor 模块
make -j$(nproc) <soc>_defconfig
make -j$(nproc) Image dtbs modules

# 产物
#   arch/arm64/boot/Image
#   arch/arm64/boot/dts/.../*.dtb
#   *.ko
```

---

## 6. HAL 层开发（BSP 上沿）

### 6.1 HIDL vs AIDL HAL（Android 14 现状）
- **HIDL**：Android 8–10 主流，`.hal` 在 `hardware/interfaces/`，生成 C++/Java 桩
- **AIDL HAL**：**Android 11 起官方推荐**，新 HAL 一律用 AIDL（`hardware/interfaces/aidl/`、`aidl_interface`）
- 遗留 HIDL 仍大量存在，新项目优先 AIDL

### 6.2 实现一个 AIDL HAL 服务（最小骨架）
1. 定义接口：`hardware/interfaces/<iface>/aidl/<pkg>/IFoo.aidl`
2. 实现服务端：`Foo.cpp` 继承 `BnFoo`，注册到 `aidl` 服务
3. `Android.bp` 用 `aidl_interface` / `cc_binary` 声明
4. 在 `device/<oem>/<board>/manifest.xml` 声明 HAL 实例（VINTF）
5. 客户端（framework/JNI）通过 `IFoo::fromBinder(ndk::SpAIBinder(...))` 获取

### 6.3 传统 HAL（libhardware）
- `hardware/libhardware/include/hardware/<module>.h`：定义 `hw_module_t` / `hw_device_t`
- 实现编成 `*.so` 放 `vendor/lib64/hw/`，按 `open()` 时 `id` 匹配加载
- 例：`gralloc`、`audio`、`camera` 早期都用这套

### 6.4 vendor 分区里放什么
- HAL 动态库：`/vendor/lib64/hw/`、`/vendor/lib64/`
- 固件：`/vendor/firmware/`（WiFi/BT/GPU/Modem 的 `.bin`/`.fw`）
- 守护进程：`/vendor/bin/`（sensorhub、audio hal daemon）
- 配置：`/vendor/etc/`（`manifest.xml`、`vintf/`、`init/*.rc`）

---

## 7. Board 配置与 Build 系统

### 7.1 进入板级目录
```bash
source build/envsetup.sh
lunch aosp_<board>-userdebug   # 或 <board>-eng
make -j$(nproc)
```
`lunch` 会执行 `device/<oem>/<board>/vendorsetup.sh` 注册产品。

### 7.2 BoardConfig.mk 关键变量（务必懂）
```makefile
# 架构
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_ABI := arm64-v8a
TARGET_2ND_ARCH := arm

# 内核（GKI 下常指向预编译或独立构建）
TARGET_KERNEL_SOURCE   := kernel/<oem>/<soc>
TARGET_KERNEL_CONFIG   := <soc>_defconfig
TARGET_KERNEL_VERSION  := 6.1
BOARD_KERNEL_IMAGE_NAME := Image
BOARD_KERNEL_CMDLINE   := ...  # 传给内核的 bootargs

# GKI 开关
BOARD_USES_GENERIC_KERNEL_IMAGE := true   # 启用 GKI 流程
BOARD_VENDOR_BOOTIMAGE_PARTITION_SIZE := ...

# 镜像/分区
BOARD_PREBUILT_DTBOIMAGE := $(LOCAL_PATH)/dtbo.img   # 或用 dtbo 构建规则
BOARD_BOOTIMAGE_PARTITION_SIZE := ...
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := ext4
PRODUCT_USE_DYNAMIC_PARTITIONS := true

# 构建 system 还是 vendor 镜像
TARGET_NO_KERNEL := false
BOARD_INCLUDE_DTB_IN_BOOTIMG := false   # GKI 下 DTB 在 vendor_boot
```

### 7.3 device.mk 关键变量
```makefile
PRODUCT_PACKAGES += \
    vendor.mytest.hal@1.0-service \     # 你的 HAL 服务
    MyBoardSpecificApp

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/init.<board>.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.<board>.rc \
    $(LOCAL_PATH)/fstab.<board>:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.<board>

PRODUCT_PROPERTY_OVERRIDES += \
    ro.product.board=<board>

# 打包 vendor 模块
BOARD_VENDOR_KERNEL_MODULES := \
    $(KERNEL_MODULES_OUT)/mydevice.ko
```

### 7.4 产品定义
- `AndroidProducts.mk`：`PRODUCT_MAKEFILES := $(LOCAL_DIR)/<board>.mk`
- `<board>.mk`：`PRODUCT_NAME`、`PRODUCT_DEVICE`、`PRODUCT_BRAND` 等
- `manifest.xml`：声明本机提供的 HAL（VINTF 兼容性检查用）

---

## 8. 板级 Bring-up 流程（实战步骤）

1. **准备工具链**：AOSP 自带 `prebuilts/gcc/linux-x86/aarch64/aarch64-linux-android-`；或用 SoC 厂工具链。
2. **编译内核**：按 §5.5 出 `Image` + `dtb` + `ko`。
3. **组装镜像**：用 `mkbootimg`/`mkdtboimg` 或 AOSP build 产出 `boot.img`/`vendor_boot.img`/`dtbo.img`。
4. **烧写**：`fastboot flash boot boot.img`、`fastboot flash vendor_boot vendor_boot.img`、`fastboot flash dtbo dtbo.img`、`fastboot flash vendor vendor.img`、`fastboot flash super super.img`。
5. **串口抓 log**：最小系统先看 UART 有没有过 kernel `start_kernel`、有没有 panic。
6. **内核驱动验证**：`dmesg` 看 probe 是否成功；`/dev`、`/sys` 节点是否生成；`insmod`/`modprobe` 加载 ko。
7. **Android 启动验证**：`logcat` 看 init / hwservicemanager / Zygote / system_server 是否起来；SurfaceFlinger、音频、相机逐步点亮。
8. **HAL 对接**：VINTF 校验通过（`adb shell lshal` 看 HAL 列表），framework 能拿到 HAL 实例。
9. **系统功能联调**：显示、触摸、WiFi/BT、音频、相机、传感器逐项目标清单（SQC）验证。

---

## 9. 调试手段速查
| 手段 | 命令 | 用途 |
|------|------|------|
| 串口 | minicom / putty @ 115200 8N1 | 看 Bootloader + 早期内核 |
| fastboot | `fastboot devices/flash/erase/getvar` | 烧写/解锁 |
| adb | `adb shell dmesg` / `adb logcat` | 内核 + 用户态日志 |
| 内核日志 | `dmesg | grep <driver>` | 驱动 probe/错误 |
| HAL 列表 | `adb shell lshal` | 检查 HAL/VINTF |
| 属性 | `adb shell getprop` | 板级属性、启动状态 |
| 设备节点 | `ls -l /dev /sys/class` | 驱动注册结果 |
| 栈/崩溃 | `tombstone`、`bugreport` | native 崩溃分析 |

---

## 10. 常见问题排查
- **卡 Bootloader / 不进内核**：DTB 地址或 `bootargs` 错误；DDR/电源时序不对。看串口。
- **kernel panic 在 mount**：`fstab.<board>` 分区名与动态分区实际名不符；`PRODUCT_USE_DYNAMIC_PARTITIONS` 配错。
- **HAL 起不来 / `lshal` 缺项**：`manifest.xml` 实例名拼错；SELinux 域未声明（`sepolicy` 缺 `type`/`allow`）。
- **ko 加载失败**：内核与模块版本/`CONFIG` 不一致（GKI 要求严格 ABI 匹配）。
- **binder 相关异常**（你重点方向）：`drivers/android/binder.c` 的 `binder_ioctl` 返回 `-EINVAL/-ENOMEM`；`binder_alloc` 内存不足；`servicemanager` 未起。

---

## 11. Android 14 关键源码路径速查
```
system/core/init/                        init 进程、init.rc 解析、first/second stage
system/core/rootdir/init.rc              通用 init 脚本
frameworks/base/                         Framework（AMS/ATMS/WMS/PMS）
frameworks/native/libs/binder/           Native Binder 实现
kernel/common/drivers/android/binder.c   Binder 驱动（GKI）
kernel/common/drivers/android/binder_alloc.c  Binder 缓冲管理
kernel/common/arch/arm64/boot/dts/       设备树源（GKI 通用）
kernel/<oem>/<soc>/arch/arm64/boot/dts/  厂商板级 dts
hardware/interfaces/                     HIDL HAL（legacy）
hardware/interfaces/aidl/                AIDL HAL（推荐）
hardware/libhardware/                    传统 HAL stub
device/<oem>/<board>/                   板级配置（你主战场）
build/core/Makefile, build/target/       镜像打包规则
```

---

## 12. 下一步可以深挖的方向
- **Binder 驱动全链路**：`binder_open/ioctl/thread_write` + 用户态 `IPCThreadState`
- **GKI 模块 ABI 稳定性**与 vendor ko 兼容性
- **SELinux 板级策略**：`device/<oem>/<board>/sepolicy/`、`file_contexts`
- **显示/相机/音频** 某一类 HAL 的完整实现
- **AVB / 安全启动**：`vbmeta`、密钥、`avbtool`

需要我针对上面任意一节（比如「GKI 内核 + vendor 模块实战」「AIDL HAL 从 0 到 1」「板级 SELinux 策略」）展开成带可运行模板的专项文档吗？
