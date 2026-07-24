# Android 14 (AOSP) 编译指南

> 代号 **UpsideDownCake**,API 34。内核 GKI 分支 `android14-6.1`。

## 1 环境要求

| 项 | 推荐配置 |
|----|----------|
| 系统 | **Ubuntu 22.04 LTS**(64-bit)。20.04 也行,18.04 已不推荐。macOS 仅支持到 Android 13 之前,Android 14 必须在 Linux 上编。 |
| 内存 | 最低 16GB;Google 官方建议 64GB 以加速。编译进程数受内存约束,经验值 `并发数 ≈ 内存(GB)/2`。 |
| 磁盘 | 源码 checkout ~100GB + 编译产物 ~150GB,**至少留 250GB 空闲**。 |
| JDK | **OpenJDK 17**(Android 14 用 prebuilts 里的 JDK17,系统装一份 `openjdk-17-jdk` 兜底即可,无需配 `JAVA_HOME`)。 |
| Python | 3.8+(Ubuntu 22.04 自带 3.10,`repo` 依赖它)。 |

## 2 安装依赖(Ubuntu 22.04)

```bash
sudo apt-get update
sudo apt-get install -y git-core gnupg flex bison build-essential zip curl \
  zlib1g-dev gcc-multilib g++-multilib libc6-dev-i386 lib32ncurses-dev \
  x11proto-core-dev libx11-dev lib32z-dev libgl1-mesa-dev libxml2-utils \
  xsltproc unzip libncurses-dev libssl-dev
```

> 22.04 上旧文档里的 `lib32ncurses5-dev` 已改名,用 `lib32ncurses-dev`;若某包找不到,把 `5` 后缀去掉再试。

配置 git(Repo 强制要求):

```bash
git config --global user.name "Your Name"
git config --global user.email "you@example.com"
```

## 3 获取源码(国内走镜像!)

### 3.1 安装 repo

```bash
mkdir -p ~/bin
export PATH=~/bin:$PATH
# 国内用清华镜像下载 repo 本身:
curl https://mirrors.tuna.tsinghua.edu.cn/git/git-repo > ~/bin/repo
chmod a+x ~/bin/repo
```

### 3.2 初始化 manifest

**方式 A:国内镜像(推荐)**
```bash
export REPO_URL='https://mirrors.tuna.tsinghua.edu.cn/git/git-repo'
mkdir ~/aosp && cd ~/aosp
repo init -u https://mirrors.tuna.tsinghua.edu.cn/git/AOSP/platform/manifest \
  -b android-14.0.0_rXX
```
> `rXX` 选具体 tag,如 `android-14.0.0_r74`(越新补丁越全)。可先
> `git ls-remote https://mirrors.tuna.tsinghua.edu.cn/git/AOSP/platform/manifest | grep android-14`
> 看可用 tag。

**方式 B:官方源(需梯子)**
```bash
repo init -u https://android.googlesource.com/platform/manifest -b android-14.0.0_rXX
```

### 3.3 同步

```bash
repo sync -j$(nproc) -c --no-clone-bundle
```
`-c` 只同步当前分支,省时间;全量同步视网速 1~数小时。建议挂在 `nohup`/`tmux` 里跑,断线可重入。

## 4 编译

```bash
cd ~/aosp
source build/envsetup.sh          # 加载 lunch/m 等命令
lunch aosp_cf_x86_64_phone-userdebug   # Cuttlefish 虚拟设备(纯编译验证首选)
# 真机示例: lunch aosp_redfin-userdebug  (Pixel 5, codename redfin)
m -j$(nproc)                      # 等价于 make,使用 Soong/Ninja
```

**编译变体(variant)**：
- `user` —— 量产版,无 root、权限收紧。
- `userdebug` —— 同 user + root + 调试工具(日常开发选这个)。
- `eng` —— 工程师版,大量调试符号、关闭部分优化。

**产物位置**:`out/target/product/<product>/`
- `system.img`、`vendor.img`、`boot.img`、`userdata.img`
- `out/host/linux-x86/bin/` 里有 `fastboot`、`adb`。

**刷机(真机,需解锁 bootloader):**
```bash
fastboot flashall -w     # 在产物目录下执行,会清 data
```
**Cuttlefish 启动(无需硬件):**
```bash
source build/envsetup.sh && lunch aosp_cf_x86_64_phone-userdebug
acloud create --local-image -w
```

## 5 单独编译内核(GKI,android14-6.1)

Android 14 默认用 prebuilt 内核,但修改 `binder.c` 等需要自己编内核。Android 14 内核已切 **Bazel(kleaf)** 构建:

```bash
cd kernel/common
# 切到 Android 14 对应的 GKI 分支
repo init -u ... -b common-android14-6.1   # 若未在主 manifest
tools/bazel build //common:kernel_aarch64_dist    # 64 位 ARM
# 产物: bazel-bin/common/kernel_aarch64/dist/{Image,vmlinux,*.ko}
```
编出的内核替换到 `out/.../kernel` 后重 `m` 即可打包进 `boot.img`。x86_64 用 `//common:kernel_x86_64_dist`。

## 6 加速与常用技巧

```bash
export USE_CCACHE=1                 # 开启 ccache(Android 14 默认启用 prebuilt ccache)
export CCACHE_DIR=$HOME/.ccache
ccache -M 50G                       # 分配缓存上限
m <模块名>                          # 增量编单个模块,如 m services
m snode                             # 只生成 ninja 依赖图(不编)
```

- 增量编译直接再跑 `m` 即可,Ninja 只重编变更。
- 清产物:`m clean` 或 `rm -rf out/`(彻底重来)。
- 内存不足编译崩溃:把并发数降到 `m -j4` 或加 swap。

## 7 常见坑

1. **GFW 下载失败** —— 必须配 `REPO_URL` 镜像 + `repo sync` 用清华/USTC 的 AOSP manifest 源,否则 `android.googlesource.com` 极慢/超时。
2. **`lib32ncurses5-dev` 找不到** —— 22.04 改名(见 §2)。
3. **Jack 已被移除** —— Android 14 不用 Jack,网上旧教程的 `export JACK_*` 全部作废。
4. **磁盘爆满** —— `out/` 极大,确保挂载点有足够空间,别编在 `/` 根分区小盘上。
5. **Python 版本错** —— 系统默认 Python 必须是 3,确认 `python3 --version` ≥ 3.8;不要用 Python 2。
6. **内核与系统版本不匹配** —— 刷机时内核分支要对应 Android 14(`android14-6.1`),否则 `vendor`/`system` 接口校验不过。

## 8 改完 `binder.c` 的最短反馈链路(只重编内核 + 刷 boot)

你一直在看 `drivers/android/binder.c`,最实用的闭环是:**改内核 → Bazel 重编 → 用 GKI 内核重新打包 `boot.img` → 只刷 `boot`**。不用重编整个 AOSP。

### 8.1 重编 GKI 内核

```bash
cd ~/aosp/kernel/common
# 确认在 Android 14 分支
git rev-parse --abbrev-ref HEAD        # 应为 common-android14-6.1
# 改完 drivers/android/binder.c 后:
tools/bazel build //common:kernel_aarch64_dist     # ARM64(真机)
# 或 x86_64(Cuttlefish 模拟器):
# tools/bazel build //common:kernel_x86_64_dist
# 产物: bazel-bin/common/kernel_aarch64/dist/{Image,vmlinux,System.map,*.ko}
```

### 8.2 把新内核塞进 boot.img(不重编 framework)

Android 14 的 `boot.img` 由 GKI `Image` + ramdisk(vendor_boot 拆出)组成。有两种做法:

**做法 A:直接用 `m` 让 build 系统吃你的 dist 产物(推荐)**
```bash
cd ~/aosp
# 把 bazel 产物软链/拷贝到 prebuilt 约定的内核目录
export TARGET_PREBUILT_KERNEL=$PWD/kernel/common/bazel-bin/common/kernel_aarch64/dist/Image
export TARGET_PREBUILT_KERNEL_MODULES=$PWD/kernel/common/bazel-bin/common/kernel_aarch64/dist
source build/envsetup.sh
lunch aosp_redfin-userdebug          # 目标机型必须和内核 ABI 匹配
m bootimage -j$(nproc)               # 只重打 boot.img,几分钟
```
产物:`out/target/product/redfin/boot.img`。

**做法 B:用 `build.sh` + `mkbootimg` 手工拼(无 AOSP 全编时也行)**
```bash
# 取 ramdisk(从旧 boot 解,或用 build 产物 out/.../ramdisk.img)
unpack_bootimg --boot_img out/target/product/redfin/boot.img \
  --out /tmp/old_boot --format=mkbootimg
mkbootimg --kernel bazel-bin/common/kernel_aarch64/dist/Image \
  --ramdisk /tmp/old_boot/ramdisk \
  --cmdline "$(cat /tmp/old_boot/cmdline)" \
  --base "$(cat /tmp/old_boot/base)" --pagesize 4096 \
  --kernel_offset "$(cat /tmp/old_boot/kernel_offset)" \
  --ramdisk_offset "$(cat /tmp/old_boot/ramdisk_offset)" \
  --tags_offset "$(cat /tmp/old_boot/tags_offset)" \
  --os_version 14 --os_patch_level 2024-xx \
  -o /tmp/new_boot.img
```

### 8.3 只刷 boot(保留 system/userdata,反馈最快)

```bash
# 设备已进入 fastboot(bootloader 解锁)
fastboot flash boot /tmp/new_boot.img     # 或 out/.../boot.img
fastboot reboot
```

> 若改的是 KO(可加载模块)而非内置进 Image 的符号,可只 `fastboot flash vendor_kernel_modules` 或 adb push 后 `insmod`,更快。但 `binder.c` 是核心驱动,编进 Image,必须走 boot.img。
> **AB 分区注意**:直接 `flash boot` 会写当前 slot;想可回退先 `fastboot set_active` 切到未用 slot 再刷。

### 8.4 验证

```bash
adb shell uname -a            # 看内核构建时间/版本,确认新内核已生效
adb shell dmesg | grep binder # 看你加的 binder 日志
```

---

## 9 指定 Pixel 机型的完整流程(以 Pixel 5 / redfin 为例)

### 9.1 选 tag + lunch

```bash
# tag 要与机型匹配,redfin 用 android-14.0.0_rXX(看官方 build 号表)
repo init -u https://mirrors.tuna.tsinghua.edu.cn/git/AOSP/platform/manifest -b android-14.0.0_r74
repo sync -j$(nproc) -c --no-clone-bundle
source build/envsetup.sh
lunch aosp_redfin-userdebug
```

### 9.2 拉厂商/驱动二进制(必须,否则无法点亮硬件)

从 https://developers.google.com/android/drivers 下载对应机型的 **`vendor/google` + `vendor/partner`** 两个 `extract-*.sh`(需接受协议,注意年份/月份与 tag 对齐):

```bash
# 把两个脚本放到 AOSP 根目录执行,会把闭源驱动解到 vendor/
chmod +x extract-google_devices-redfin.sh extract-qcom-redfin.sh
./extract-google_devices-redfin.sh
./extract-qcom-redfin.sh
```

> 驱动二进制与 AOSP tag 必须严格对应,否则编译或刷机后无法开机。

### 9.3 编译 + 刷机

```bash
m -j$(nproc)
cd out/target/product/redfin
fastboot flashall -w     # -w 清 data;首次刷建议带,后续增量可只 flash 变更分区
```

---

## 10 快速决策表

| 你想干嘛 | 命令/动作 |
|----------|-----------|
| 纯验证 AOSP 编译能过(无硬件) | `lunch aosp_cf_x86_64_phone-userdebug && m` + `acloud create` |
| 改 framework Java/C++ | `m <模块>`(如 `m services`、`m framework-minus-adata`)后 `adb sync` 或重刷 `system` |
| 改 `binder.c` 等内核 | §8:Bazel 重编 → 重打 `boot.img` → `fastboot flash boot` |
| 真机首次点亮 | §9:tag + extract 驱动 + `m` + `fastboot flashall -w` |
| 只想增量验证某一模块 | `m <模块名>`,Ninja 只编变更 |
| **在模拟器里跑(x86_64 主机)** | §11:`lunch sdk_phone_x86_64-userdebug && m` + `emulator`(需 KVM) |

---

## 11 编译 `sdk_phone_x86_64`(官方模拟器 / goldfish 目标)

`sdk_phone_*` 系列是给 **Android Emulator(goldfish)** 用的,与 Cuttlefish(`aosp_cf_x86_64_phone`)是两套:**emulator 吃 `-qemu` 后缀镜像 + `kernel-ranchu`(goldfish 内核),用 `emulator` 命令启动;cuttlefish 用 `cvd`/`acloud` 启动**。在 x86_64 Linux 主机上配 KVM,emulator 速度接近原生,是纯软件验证(含改 binder 内核)最省事的选择。

### 11.1 编译

```bash
cd ~/aosp
source build/envsetup.sh
lunch sdk_phone_x86_64-userdebug
m -j$(nproc)
```

- 该 target 会**一并编出 host 端 `emulator` 工具**(落在 `out/host/linux-x86/bin/emulator`)。
- **内核默认用 prebuilt `kernel-ranchu-64`(goldfish 内核)**,不要求单独编内核,首次 `m` 即可直接跑。

### 11.2 产物位置

> 注意:lunch 名是 `sdk_phone_x86_64`,但**产物目录名是 `emulator_x86_64`**(由 `PRODUCT_DEVICE` 决定),别找错目录。

`out/target/product/emulator_x86_64/`:
- `system-qemu.img`、`vendor-qemu.img`、`ramdisk-qemu.img`、`userdata-qemu.img` —— emulator 专用镜像(带 `-qemu` 后缀)
- `kernel-ranchu-64` —— goldfish 预编译内核
- `advancedFeatures.ini`、`encryptionkey.img`、`system-qemu-config.txt` 等辅助文件

### 11.3 启动模拟器

在已 `lunch` 的同一 shell 里直接:

```bash
emulator                 # 自动探测 out/ 下刚编好的镜像
emulator -no-window      # 无头(CI / 远程机)
emulator -wipe-data      # 清 data 分区重来
emulator -selinux permissive   # 关 SELinux,调内核时少踩权限
```

**KVM 加速(关键)**:emulator 默认探测 `/dev/kvm`,有则硬件加速、速度飞起;无则巨慢。
```bash
ls -l /dev/kvm           # 必须存在且当前用户可读写
sudo usermod -aG kvm $USER   # 没权限就加组,重登生效
```
- **WSL2 用户注意**:WSL2 默认没有 `/dev/kvm`,需 Windows 主机开启 Hyper-V/WHPX 并装 Intel HAXM 或在 WSL 里启用 KVM 透传,比较折腾;**强烈建议用原生 Linux 或一台远程 Linux 编译/运行机**。
- macOS/Windows 主机上跑 emulator 也行,但**编 AOSP 本身必须在 Linux**(见 §1),所以编译和运行的宿主要分开。

### 11.4 在 emulator 上验证 `binder.c` 改动

emulator 默认吃 **goldfish 内核**,不是 GKI common 内核。要让你的 binder 改动生效:

```bash
cd ~/aosp/kernel/common
git checkout common-android14-6.1          # 同一 GKI 基线
# 用 goldfish/ranchu 配置编:
tools/bazel build //common:kernel_x86_64_dist   # 或手动 make goldfish_defconfig + make
# 产物 Image 改名为 kernel-ranchu-64 放回去:
cp bazel-bin/common/kernel_x86_64/dist/Image \
   ~/aosp/out/target/product/emulator_x86_64/kernel-ranchu-64
emulator -kernel ~/aosp/out/target/product/emulator_x86_64/kernel-ranchu-64
# 或编译后直接: emulator -kernel <你的 Image 路径> 显式指定
```
> GKI 通用内核 vs goldfish 内核是两回事:emulator 默认只认 goldfish prebuilt。直接把 §8 编出的 GKI `Image` 丢给 emulator 大概率因设备树/配置不匹配起不来,务必用 goldfish/ranchu 配置编出的内核。

验证同 §8.4:`adb shell uname -a` + `adb shell dmesg | grep binder`。

---

## 12 添加系统 App(编进 system.img)

把自研/预编译的 app 打成**系统应用**,随 `system.img` 一起烧录,装在 `/system/app`(普通系统 app)或 `/system/priv-app`(特权 app)。两种方式:**源码编译**(`android_app`)或**塞入已有 APK**(`android_app_import`)。

### 12.1 目录约定

新建模块放进 AOSP 任意能被 build 系统扫到的路径,常见两处:
- `packages/apps/<YourApp>/`(源码 app 推荐位置)
- 或你的 device/product 目录下(随产品走)

```
packages/apps/MySystemApp/
├── Android.bp
├── AndroidManifest.xml
├── src/com/example/mysystemapp/MainActivity.java
└── res/...
```

### 12.2 方式 A:源码 App(`Android.bp`)

```bp
android_app {
    name: "MySystemApp",
    srcs: ["src/**/*.java"],
    resource_dirs: ["res"],
    manifest: "AndroidManifest.xml",

    platform_apis: true,        // 用 framework 内部/隐藏 API(而非 SDK 公开 API)
    certificate: "platform",    // 用 platform 密钥重签 → 拥有系统签名
    privileged: true,           // true → 装到 /system/priv-app;省略 → /system/app
    // system_ext_specific / product_specific: true 可改投对应分区

    optimize: { enabled: false },   // 调试期关混淆,方便看栈
    dex_preopt: { enabled: false }, // 调试期关 dex2oat 预编译,编得快

    static_libs: ["androidx.appcompat.appcompat"],
    libs: ["framework-impl"],   // 仅当用了 @hide 的 framework 内部类
}
```

### 12.3 方式 B:预编译 APK(`Android.bp`)

```bp
android_app_import {
    name: "MyPrebuiltApp",
    apk: "prebuilt/MyPrebuiltApp.apk",
    privileged: true,
    certificate: "platform",    // 用 platform 重签(需与系统同签)
    // presigned: true,         // 若想保留 APK 原签名(此时 certificate 不生效)
    dex_preopt: { enabled: false },
}
```
> 想保留原厂签名就 `presigned: true` 并删掉 `certificate`;想用系统签名让其获得系统权限就 `certificate: "platform"`。

### 12.4 注册进产品(最关键一步!)

无论哪种,**必须加进 `PRODUCT_PACKAGES`,否则不会被编进 image**:

```mk
# 在对应 device/product 的 .mk 里(如 device/google/emulator/emulator_x86_64.mk
# 或 device/generic/goldfish/.../system.mk)
PRODUCT_PACKAGES += MySystemApp
# 仅 eng/userdebug 包含(调试 app):
# PRODUCT_PACKAGES_DEBUG += MyDebugApp
# 仅 user 包含:
# PRODUCT_PACKAGES_ENGINEERING += ...   (视版本)
```

### 12.5 priv-app 必须声明特权权限

`privileged: true`(落在 `/system/priv-app`)的 app,**必须在 `system/etc/permissions/` 放一份权限白名单**,否则启动时会被框架拒绝授予特权权限(甚至起不来):

```xml
<!-- 放到 frameworks/base/data/etc/ 或 device/.../permissions/ 下,
     文件名 privapp-permissions-myapp.xml,随系统拷贝到 /system/etc/permissions/ -->
<permissions>
    <privapp-permissions package="com.example.mysystemapp">
        <permission name="android.permission.READ_PRIVILEGED_PHONE_STATE"/>
        <permission name="android.permission.WRITE_SECURE_SETTINGS"/>
    </privapp-permissions>
</permissions>
```
并在产品 mk 里让该 xml 进 `PRODUCT_COPY_FILES` 或放入 `PRODUCT_PACKAGES`(若包成 module)。漏写这条是 priv-app 最常见的"装上了但用不了特权权限/反复崩溃"根因。

### 12.6 AndroidManifest 要点

- 与系统同 UID(不推荐新 app 用):
  ```xml
  android:sharedUserId="android.uid.system"   <!-- 需 platform 签名;Android 10+ 限制变严 -->
  ```
- 想申请 `signature|privileged` 级权限:必须 `privileged: true` + §12.5 白名单。
- 普通系统 app(非 privileged)用 `android:protectionLevel="signature"` 的权限即可,无需 priv 白名单。

### 12.7 装到哪个分区

| 分区 | bp 写法 | 路径 |
|------|---------|------|
| system(普通) | 默认 | `/system/app/<name>` |
| system(特权) | `privileged: true` | `/system/priv-app/<name>` |
| system_ext | `system_ext_specific: true` | `/system_ext/app/<name>` |
| product | `product_specific: true` | `/product/app/<name>` |
| vendor | `vendor: true`(极少) | `/vendor/app/<name>` |

### 12.8 编译与验证

```bash
source build/envsetup.sh
lunch sdk_phone_x86_64-userdebug    # 或你的 target
m MySystemApp -j$(nproc)            # 单模块编,几分钟
# 或整编: m -j$(nproc)
```
烧录/启动后:
```bash
adb shell pm list packages | grep mysystemapp
adb shell pm path com.example.mysystemapp      # 看落在 /system/priv-app 还是 /system/app
adb shell dumpsys package com.example.mysystemapp | grep -i "privileged\|primaryCpuAbi"
adb logcat | grep mysystemapp                  # 抓启动/运行日志
```
> 若改了 bp 或 mk,记得 `m` 后整个 `system.img` 才会包含新 app;`m <模块>` 只编 app 本身,但刷机前要确认 `system.img` 也重生成(直接 `m` 会连带重打 image)。

---

## 13 相关主题索引

- Binder 内核机理 / 一次拷贝 / 异步空间 / deferred gc → 见 `binder_aidl.md`、`android_framework_paper.md`
- 全量/增量/内核/模拟器/真机编译 → 见本文 §1–§11
- 添加系统 App → §12
- 改 `binder.c` 后刷机/模拟器验证最短链路 → §8、§11.4
- 修改 AMS / ATMS 实战 → §14

---

## 14 修改 AMS 实战(Android 14)

### 14.1 先认清 AMS 在 Android 14 的位置(易踩坑)

Android 10 起 activity 栈逻辑被拆到独立的 **`ActivityTaskManagerService`(ATMS)**,AMS 只保留进程/任务/广播/内存等总管职能。改之前先确认代码在哪:

| 想改的行为 | 真正落点的文件 |
|------------|----------------|
| `startActivity` 入口、权限/调用方校验、跨进程派发 | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` |
| Activity 栈、Task、Window、Resume/Pause 流转 | `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java` + `ActivityStarter.java`、`ActivityStack.java`、`Task.java`(同在 `.../server/wm/`) |
| 应用进程孵化(fork Zygote) | `.../am/ProcessList.java`(`startProcessLocked`) |
| Service 启停 | `.../am/ActiveServices.java` |
| 广播 | `.../am/BroadcastQueue.java` / `BroadcastHistory.java` |
| OOM / adj 评分 | `.../am/OomAdjuster.java` |

AMS/ATMS 的客户端接口与 Binder 定义:
- AIDL:`frameworks/base/core/java/android/app/IActivityManager.aidl`(AMS)、`frameworks/base/core/java/android/app/IActivityTaskManager.aidl`(ATMS)
- AMS 类签名:`public class ActivityManagerService extends IActivityManager.Stub implements ...`(同时持有 `mAtmInternal` 指向 ATMS)

> 关键认知:AMS 与 ATMS 是**两个独立 Binder 服务**,`system_server` 里 AMS 通过 `LocalServices.getService(ActivityTaskManagerInternal.class)` 直接调 ATMS(同进程,不走 Binder)。所以"加一个启动拦截"若在客户端入口改 `IActivityManager`,改 AMS;若改栈行为,改 ATMS。

### 14.2 实战示例 A:在 `startActivity` 加日志(最常用起手)

编辑 `ActivityManagerService.java`,在 `startActivityAsUser`(真正的统一入口)里插日志:

```java
// frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java
@Override
public final int startActivityAsUser(IApplicationThread caller, String callingPackage,
        String callingFeatureId, Intent intent, String resolvedType,
        IBinder resultTo, String resultWho, int requestCode, int startFlags,
        ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
    // —— 实战插入:打印调用方与要启动的 target ——
    Slog.d("MyAMS", "startActivityAsUser: caller=" + callingPackage
            + " uid=" + Binder.getCallingUid()
            + " target=" + intent.getComponent()
            + " action=" + intent.getAction());
    // —— 结束插入 ——
    return startActivityAsUser(caller, callingPackage, callingFeatureId, intent, resolvedType,
            resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions,
            userId, true /*validateIncomingUser*/);
}
```
`Slog` 已在 `ActivityManagerService` 中 import(`import android.util.Slog;`),`TAG` 常量复用或自起一个。日志走 `logcat -b all`。

### 14.3 实战示例 B:新增一个隐藏系统 API(改 AIDL)

若想从别的系统模块(或你的系统 app)调用 AMS 新逻辑,需扩展 `IActivityManager.aidl`:

1. **改 AIDL 加方法**:
```aidl
// frameworks/base/core/java/android/app/IActivityManager.aidl
interface IActivityManager {
    // ... 已有方法 ...
    boolean myCustomCheck(String pkg, int uid);   // ← 新增
}
```
2. **在 `ActivityManagerService` 实现该方法**(`.aidl` 改了,服务端必须实现,否则 `system_server` 启动报 `AbstractMethodError`):
```java
@Override
public boolean myCustomCheck(String pkg, int uid) {
    Slog.d("MyAMS", "myCustomCheck pkg=" + pkg + " uid=" + uid);
    return true;
}
```
3. **客户端暴露**(可选,供 app/framework 调用):在 `frameworks/base/core/java/android/app/ActivityManager.java` 包一层:
```java
public static boolean myCustomCheck(String pkg, int uid) {
    try {
        return getService().myCustomCheck(pkg, uid);
    } catch (RemoteException e) {
        throw e.rethrowFromSystemServer();
    }
}
```
4. **若方法要给系统 app 当 @SystemApi 用**,还需在 `frameworks/base/config/hiddenapi-unsupported.txt` / `hiddenapi-force-blacklist.txt` 等里处理 hiddenapi 标志(否则调用方会被黑名单拦)。普通 `@hide` 仅需同签名模块能调。

> **接口版本一致性**:AIDL 改了方法签名/增删方法,**所有实现类(含测试桩 `ActivityManagerService` 的 mock)** 都要同步改,否则编译或运行期报错。ATMS 同理改 `IActivityTaskManager.aidl`。

### 14.4 只编译 AMS 相关模块(最短反馈)

AMS/ATMS 源码编进 **`services`** 这个 java_library(具体 jar 是 `services.jar`,内含 `services.core` 等)。改完只编它:

```bash
source build/envsetup.sh
lunch sdk_phone_x86_64-userdebug     # 或你的 target
m services -j$(nproc)               # 重编 services.jar(含 AMS/ATMS/所有 am/wm 服务)
# 产物: out/target/product/.../system/framework/services.jar
```
> 只编 `services` 通常 1–3 分钟(取决于改动量),远快于全编。若同时改了 `frameworks/base/core`(如 §14.3 的 `ActivityManager.java` 客户端壳),要一起 `m framework` 或干脆 `m`。

### 14.5 推送 / 烧录验证

**(A) emulator / userdebug 真机:直接 push jar(免重刷整 image)**
```bash
adb root
adb remount                 # userdebug 且 avb 关闭才能 remount /system
adb push out/target/product/emulator_x86_64/system/framework/services.jar /system/framework/
adb reboot
```
> Android 10+ `/system` 默认只读;`adb remount` 需 userdebug + `adb disable-verity`(真机首次)或 emulator 默认可 remount。push 后必须 `reboot`,因为 `services.jar` 在启动期被加载,运行中替换不生效。

**(B) 整编重打 system.img(最稳妥)**
```bash
m -j$(nproc)               # 连带重打 system.img
# emulator: 直接 emulator 启动即读新 image;真机: fastboot flash system
```

### 14.6 验证

```bash
adb logcat -b all | grep MyAMS            # 看 §14.2/14.3 日志
adb shell dumpsys activity activities | head -40   # 看 ATMS 栈/Task 状态
adb shell dumpsys activity processes | grep -i <pkg>  # 看进程/adj
adb shell am start -n com.xxx/.MainActivity            # 触发一次启动,观察日志
```
- 若 `system_server` 起不来(改崩了):`adb logcat -b all | grep -i "AndroidRuntime\|system_server"` 看崩溃栈;`emulator` 下可加 `-wipe-data` 或看 `logcat -b crash`。

### 14.7 常见坑

1. **改错文件**:想改启动栈却去改 AMS——记住栈在 ATMS(`.../server/wm/`)。
2. **AIDL 不一致**:`.aidl` 加了方法但 `ActivityManagerService` 没实现 → `system_server` 启动直接 `AbstractMethodError` 崩。
3. **hiddenapi 黑名单**:新增方法若被 hiddenapi 标记,非特权调用方会被 `NoSuchMethodError`/抛异常拦。系统内部同进程调用(`LocalServices`)不受影响。
4. **只 push jar 不 reboot**:`services.jar` 启动期加载,push 后必须重启才生效。
5. **改了 framework 客户端壳却只编 services**:`ActivityManager.java` 在 `framework` 模块,需 `m framework` 或全 `m`。
6. **签名**:`services.jar` 由 platform 签名,不要手抖用别的 key 重签,否则 `system_server` 校验失败起不来。

### 14.8 调试进阶

- **live 改逻辑**:`adb shell cmd activity` 是 ATMS/AMS 暴露的 shell 命令入口(`ActivityManagerShellCommand`),可加自定义子命令做运行时验证。
- **单步调试**:`adb shell ps -e | grep system_server` 拿 pid,Android Studio Attach Debugger to Process 选 `system_server`,断点打在 AMS/ATMS(需 `eng` 或 `userdebug` + `debuggable`)。
- **开关控制**:实战中常用 `Settings.Global` / `SystemProperties` 做功能开关,避免每次改逻辑都重编(如 `if (SystemProperties.getBoolean("persist.myams.enable", false)) {...}`)。
