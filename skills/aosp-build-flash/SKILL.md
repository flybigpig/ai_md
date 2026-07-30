---
name: aosp-build-flash
description: AOSP 编译体系与刷机/模拟器验证技能。当用户需要编译 AOSP(整编/模块编译)、配置 lunch 目标、排障编译报错(OOM/线程过载/依赖缺失/defconfig 宏)、刷写设备、或用 sdk_phone_x86_64 模拟器(KVM)验证改动时触发。即使用户只说"怎么编译 aosp"、"make 报 OOM"、"模块怎么单独编"、"模拟器怎么跑起来"、"lunch 选哪个"、"刷机命令"，也应触发。默认 Android 14，编译宿主须 Linux。
agent_created: true
---

# aosp-build-flash — 编译 / 刷机 / 模拟器

**硬性前提**：AOSP 只能在 **Linux** 编译（macOS 自 Android 10 起不再支持设备编译）。Windows 用户走 WSL2 或独立 Linux 编译机（用户环境：E5-2697A v4 / 64G，标准 `make -j32`）。

## 何时使用

- 整编 / 单模块编译 / Soong 构建。
- 编译报错排查（内存、线程、依赖、defconfig）。
- 刷机（fastboot）或模拟器（KVM）验证。

## 一、标准编译流程

```bash
cd <AOSP_ROOT>
source build/envsetup.sh
lunch aosp_arm64-eng          # 或 sdk_phone_x86_64-eng(模拟器)
make -j$(nproc) 2>&1 | tee build.log
```

- **模块编译**（优先于整编，省时）：
  ```bash
  m <module>            # 如 m services 编 framework services
  mmm frameworks/base/services/   # 旧式，整目录
  m snu                    # 校验 Soong 配置
  ```
- 编译产物：`out/target/product/<device>/`（system.img / vendor.img / boot.img）。

## 二、资源控制（用户 64G 内存，`-j32`）

- 内存是瓶颈：每个 `jack`/java 编译进程吃 1~2G。64G 下 `-j32` 基本安全，但开启 `WITH_DEXPREOPT` 时峰值更高。
- 防 OOM：
  ```bash
  export USE_NINJA=true
  export GC_ONLY_FOR_TOOLCHAIN=false
  # 限制并行 java 数
  export JAVAC_STACK_SIZE=4M
  # 交换分区兜底（避免直接 OOM kill）
  sudo fallocate -l 32G /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
  ```
- 多线程过载：核心数远大于内存时降 `-j`，依 `空闲内存/2G` 估算上限。

## 三、常见编译报错排查

| 现象 | 根因 | 处理 |
|---|---|---|
| `internal compiler error: Killed` | OOM | 降 `-j`、加 swap |
| `ninja: build stopped: subcommand failed` | 单模块错 | `grep -i error build.log` 定位 |
| `missing separator / Soong 配置错误` | Android.bp 语法 | `m snu` 校验 |
| `defconfig 未开某宏` | 内核宏缺失 | 改 `arch/.../configs/<defconfig>` 或 `*.fragment`（见 aosp-can-selinux） |
| `cannot find -lxxx` | 依赖未编 | 先 `m <lib>` 或整编 |

## 四、刷机（fastboot）

```bash
adb reboot bootloader
fastboot flashall -w        # 刷全部 img（清空 data）
# 或分区单独刷
fastboot flash system out/target/product/<device>/system.img
fastboot flash vendor out/target/product/<device>/vendor.img
fastboot reboot
```

**Treble 注意**：vendor 与 system 必须版本配对（AVB 校验），单独刷错 version 会 bootloop。

## 五、模拟器验证（sdk_phone_x86_64 + KVM）

```bash
lunch sdk_phone_x86_64-eng
make -j$(nproc) emu_img_zip sdk_repo        # 或直接 make 出 system.img
# KVM 须可用
ls -l /dev/kvm
emulator -accel on -gpu swiftshader -show-kernel
```

无 KVM 时加 `-accel off -gpu swiftshader`（慢，仅调测）。详细脚本见 `scripts/flash_emulator.sh`。

## 六、国内镜像同步

```bash
repo init -u https://mirrors.tuna.tsinghua.edu.cn/git/AOSP/platform/manifest -b android-14.0.0_rXX
```

## 关联

- 源码坐标 → `aosp-navigator`
- 驱动/DTS/SELinux 改动后刷 vendor → `aosp-can-selinux`
- HAL 服务编译进 vendor → `aosp-hal-treble`
- 打包完整编译脚本见 `scripts/build_aosp.sh`
