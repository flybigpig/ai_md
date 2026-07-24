# 从零编译 AOSP（Android 14）完整指南

> 适用环境：本机 Windows + WSL2 Debian 13（trixie），8 核 CPU、23GB 内存、WSL 根分区 952GB 可用。
> 目标：拉取并编译 **Android 14（API 34，分支 `android-14.0.0_rXX`）**，生成可在模拟器中运行的 `eng` 镜像，便于研究 Framework（AMS/WMS/IMS 等）。

---

## 0. 你的机器现状（已实测）

| 项 | 值 | 结论 |
|---|---|---|
| WSL 发行版 | Debian 13 (trixie)，WSL 版本 2 | 可用，但 AOSP 官方文档面向 Ubuntu，见第 7 节 Debian 差异 |
| CPU | 8 核 | 编译 `-j8` 足够 |
| 内存 | 23GB（WSL 分配） | 满足编译需求（建议 ≥16GB） |
| WSL 根分区 `/` | 952GB 可用 | 充足，AOSP 代码+编译产物约需 350–400GB |
| git / python3 | 已安装 | OK |
| Java | 未安装 | 需装 **OpenJDK 17** |
| repo | 未安装 | 需安装 |

**最重要的原则**：源码必须放在 **WSL 的 Linux 文件系统内**（如 `~/aosp`，即 `/home/<user>/aosp`），**不要**放在 `/mnt/c/...`（Windows NTFS）。NTFS 不支持符号链接与部分文件权限，且编译速度会慢一个数量级。

---

## 1. 第一步：安装编译依赖（Debian 13 适配）

打开 Debian 终端（Windows 终端里选 Debian，或 `wsl -d Debian`），执行：

```bash
# 1) 更新包索引
sudo apt-get update

# 2) 安装 AOSP 构建依赖（已按 Debian 13 包名修正）
sudo apt-get install -y \
  git gnupg flex bison build-essential zip curl \
  zlib1g-dev gcc-multilib g++-multilib libc6-dev-i386 \
  lib32ncurses-dev libncurses-dev \
  x11proto-core-dev libx11-dev lib32z1-dev libgl1-mesa-dev \
  libxml2-utils xsltproc unzip fontconfig \
  python3 python-is-python3 \
  openjdk-17-jdk rsync libssl-dev
```

要点说明：
- `python-is-python3`：Android 14 的部分脚本仍直接调用 `python`，Debian 13 默认只有 `python3`，该包建立 `/usr/bin/python -> python3` 软链。
- `lib32ncurses-dev`：Debian 13 已用 ncurses6，原 Ubuntu 文档里的 `lib32ncurses5-dev` 在此发行版更名为 `lib32ncurses-dev`。
- 不需要 `jack`：Jack 编译器在 Android 9 之后已废弃，Android 14 走 `jack` 无关的新工具链（prebuilts 自带），不要再装 `jack-server`。

### 设置 JDK 17 为默认

```bash
sudo update-alternatives --config java   # 选择 openjdk-17 那一项
java -version                            # 必须显示 17.x
```

### 安装 repo 工具

```bash
mkdir -p ~/.bin
curl https://storage.googleapis.com/git-repo-downloads/repo > ~/.bin/repo
chmod a+x ~/.bin/repo

# 把 repo 加入 PATH（写入 ~/.bashrc 持久化）
echo 'export PATH=~/.bin:$PATH' >> ~/.bashrc
source ~/.bashrc
repo --version      # 确认能运行
```

> 国内若 `storage.googleapis.com` 不通，可用清华镜像的 repo：
> `curl https://mirrors.tuna.tsinghua.edu.cn/git/git-repo > ~/.bin/repo`

---

## 2. 第二步：获取 AOSP 源码

你目前**没有 `aosp-latest.tar` 压缩包**，下面给出两条路线。

### 路线 A：用 `aosp-latest.tar` 官方整包（适合无完整 git 历史、想快速开始）

`aosp-latest.tar` 是 Google 官方打包好的最新 master 源码（含 `.repo`，约 100GB+ 下载），地址：

```
https://dl.google.com/dl/android/aosp/aosp-latest.tar
```

下载后解压并切换到 Android 14 分支：

```bash
cd ~
# 下载（需代理/直连 Google；或挂下载工具断点续传）
curl -O https://dl.google.com/dl/android/aosp/aosp-latest.tar

tar xf aosp-latest.tar        # 生成 ~/aosp 目录，内含 .repo
cd aosp

# 把 .repo 指向的 manifest 切换为 Android 14 分支（见下方选分支）
repo init -b android-14.0.0_rXX \
  -u https://mirrors.tuna.tsinghua.edu.cn/git/AOSP/platform/manifest.git
repo sync -j8 -c
```

### 路线 B（推荐，国内最稳）：直接 `repo` 从国内镜像初始化 Android 14

不必先下 `aosp-latest.tar`，直接从清华镜像按 Android 14 分支拉取，省流量、速度快：

```bash
mkdir -p ~/aosp && cd ~/aosp

repo init -u https://mirrors.tuna.tsinghua.edu.cn/git/AOSP/platform/manifest.git \
  -b android-14.0.0_rXX

repo sync -j8 -c
```

参数说明：
- `-u`：manifest 仓库地址。国内用清华镜像 `https://mirrors.tuna.tsinghua.edu.cn/git/AOSP/platform/manifest.git`；直连 Google 用 `https://android.googlesource.com/platform/manifest`。
- `-b android-14.0.0_rXX`：指定 Android 14 分支。**务必把 `XX` 换成具体小版本号**（见下）。
- `-j8`：8 线程并发同步。
- `-c`（`--current-branch`）：只拉取当前分支的代码，显著减少下载量。

### 选定 Android 14 的具体小版本（android-14.0.0_rXX）

`android-14.0.0_r` 后跟两位数字（如 `_r1`、`_r30`、`_r74`…），代表 14.0.0 的月度安全更新。Framework 研究选任意一个 `_rXX` 代码结构都一致。查最新可用标签：

```bash
git ls-remote https://mirrors.tuna.tsinghua.edu.cn/git/AOSP/platform/manifest.git \
  | grep android-14
```

把输出里最大的 `android-14.0.0_rXX` 填进上面的 `-b` 参数即可。例（以你执行时实际存在为准）：

```bash
repo init -u ... -b android-14.0.0_r74
```

### 网络与代理（国内常见）

- 已用清华镜像，大部分流量走国内。但 `repo` 内仍可能回源 `googlesource`，可设：
  ```bash
  export REPO_URL='https://mirrors.tuna.tsinghua.edu.cn/git/git-repo'
  ```
- 若需代理：
  ```bash
  export HTTP_PROXY=http://<host>:<port>
  export HTTPS_PROXY=http://<host>:<port>
  ```
- 同步中断可重复执行 `repo sync -j8 -c` 续传。

---

## 3. 第三步：开始编译

```bash
cd ~/aosp

# 1) 初始化构建环境（定义 lunch / m / mm / mmm / croot 等 shell 函数）
source build/envsetup.sh

# 2) 选择构建目标（product-variant）
lunch aosp_x86_64-eng
```

`lunch` 目标格式为 `<产品名>-<编译类型>`。常见组合：

| 目标 | 说明 | 适用场景 |
|---|---|---|
| `aosp_x86_64-eng` | x86_64 模拟器 + 工程版 | **Framework 研究首选**：模拟器跑得快、带 root、带调试符号 |
| `aosp_arm64-eng` | ARM64 + 工程版 | 真机/部分模拟器，交叉编译更慢 |
| `sdk_phone64_x86_64-userdebug` | SDK 模拟器 + userdebug | 需要 userdebug 特性时 |

随后开编：

```bash
# 推荐开启 ccache 加速二次编译
export USE_CCACHE=1
export CCACHE_DIR=$HOME/.ccache
prebuilts/misc/linux-x86/ccache/ccache -M 50G   # 分配 50GB 缓存

# 开始编译（-j 后跟并行数，等于 CPU 核数即可）
m -j$(nproc)
```

- `m` 是 `envsetup.sh` 提供的封装，等价于 `make` 但会自动处理输出目录与并行数。
- 首次全编在 8 核机器上约 **2–4 小时**（取决于 CPU 单核性能与磁盘 IO）。
- 编译产物统一输出到 `out/`。

### 关键产物路径（Framework 研究重点）

```
out/target/product/emulator64_x86_64/      # x86_64-eng 镜像目录
├── system.img        # system 分区（含 framework.jar / services.jar）
├── vendor.img
├── userdata.img
├── ramdisk.img
└── boot.img          # 含 kernel + ramdisk（init 进程入口）

out/target/common/obj/JAVA_LIBRARIES/
├── framework_intermediates/    # android.* 框架层（核心 API）编译中间产物，含 .class/.jar
└── services.core_intermediates/ # services.jar 来源（AMS/WMS/IMS/PMS 在此编译）

out/host/linux-x86/bin/         # host 端工具：emulator / adb / fastboot / mksdcard
```

**Framework 源码位置（修改后 `m` 即可重编对应模块）**：
- 系统服务：`frameworks/base/services/core/java/com/android/server/`（`ActivityManagerService`、`WindowManagerService`、`InputManagerService`、`PackageManagerService`…）
- 公开 API：`frameworks/base/core/java/android/`
- 应用框架：`frameworks/base/core/java/com/android/`
- init / 早期用户态：`system/core/`（`init` 进程、`logcat`）

增量编译技巧（只编改动模块，秒级~分钟级）：
```bash
mmm frameworks/base/services        # 只编 services
mm                                  # 在当前模块目录编当前模块
```

---

## 4. 第四步：运行（模拟器）

编译完成后，在同一 shell（已 `lunch aosp_x86_64-eng`）直接：

```bash
emulator
```

首次启动会创建 `out/target/product/emulator64_x86_64/*.img` 对应的虚拟设备，几分钟进入桌面。若想加快启动加 `-writable-system` 或 `-no-snapshot` 按需。

验证 Framework 已带符号/可调试：
```bash
adb shell getprop ro.build.type     # 输出 eng
adb root                            # eng 版可直接 root
adb shell ps -A | grep system_server
```

---

## 5. 针对 Framework 研究的建议

1. **永远用 `eng` 构建类型**：自带 `adb root`、关闭部分 SELinux 限制、保留调试符号，便于 `gdb`/`art` 调试与 `logcat` 全量输出。
2. **用 x86_64 模拟器**：比 ARM 模拟快数倍，单步调试 `system_server` 不卡顿。
3. **attach system_server**：
   ```bash
   adb shell ps -A | grep system_server   # 拿到 pid
   gdbclient.py -p <pid>                  # AOSP 自带 gdb 封装（需 lunch 环境）
   ```
4. **改完即重编**：改 `frameworks/base/services` 后 `mmm frameworks/base/services && m snod`（重新生成 `system.img`），无需全编。
5. **源码对照**：配合 Android Code Search（cs.android.com）按方法名检索，再在本机 `frameworks/` 下精确改。

---

## 6. 编译命令速查表

```bash
# 环境
source build/envsetup.sh
lunch aosp_x86_64-eng

# 全编 / 增量
m -j$(nproc)            # 全编
mm                      # 当前目录模块
mmm <path>             # 指定模块
m snod                  # 只重新打包 system.img（不重编）

# 运行
emulator
adb shell
```

---

## 7. 常见问题与排错（Debian 13 / WSL2）

**Q1：`python: command not found`**
A：装 `python-is-python3`（第 1 节已含），或手动 `sudo ln -s /usr/bin/python3 /usr/bin/python`。

**Q2：`repo: command not found`**
A：`~/.bin` 未进 PATH。确认 `~/.bashrc` 里有 `export PATH=~/.bin:$PATH` 且已 `source`。

**Q3：Java 版本不对（提示需要 17）**
A：`sudo update-alternatives --config java` 切到 openjdk-17；`java -version` 复核。

**Q4：磁盘空间不足 / WSL 虚拟磁盘报错 `No space left on device`**
A：AOSP 需约 350–400GB。WSL2 虚拟磁盘默认自动随 C 盘空间增长，但若 C 盘本身紧张，可把 WSL 迁移到其他盘：
```powershell
# 在 Windows PowerShell 中
wsl --export Debian D:\wsl\debian.tar
wsl --unregister Debian
wsl --import Debian D:\wsl\debian D:\wsl\debian.tar
```
迁移后 `~/aosp` 仍在 Linux 文件系统内，不受影响。

**Q5：内存不足导致 `java.lang.OutOfMemoryError` 或 `ld` 被杀**
A：在 Windows 用户目录建 `%USERPROFILE%\.wslconfig`：
```
[wsl2]
memory=16GB
processors=8
swap=8GB
```
保存后 `wsl --shutdown` 重启 Debian 生效。你机器 WSL 已分 23GB，一般无需改。

**Q6：`repo sync` 卡在 `Receiving objects` / 网络超时**
A：确认用了清华镜像 + 设置 `REPO_URL`；必要时挂代理；中断后重复 `repo sync` 会自动续传。

**Q7：编译报 `flex`/`bison` 版本相关错误**
A：Debian 13 的 flex/bison 较新，AOSP 14 一般兼容；若报具体符号错，优先 `repo sync` 到最新 `_rXX`，或 `make clean` 后重编。

**Q8：模拟器启动黑屏 / `KVM` 不可用**
A：WSL2 默认无 KVM，x86_64 模拟器走纯软件渲染仍可跑（较慢）。如需加速，可在 `.wslconfig` 加 `nestedVirtualization=true`（需 Windows 11 + 主机 BIOS 开启 VT-x）。若仍慢，考虑宿主机装 Android Studio 模拟器或用真机 `fastboot flash`。

---

## 8. 总流程一览

```
安装依赖(Debian13) → 装 JDK17 + repo → 获取源码(repo+清华镜像, -b android-14.0.0_rXX)
     → source build/envsetup.sh → lunch aosp_x86_64-eng
     → m -j8 → 产物 out/target/product/emulator64_x86_64/*.img
     → emulator 启动 → adb root 调试 Framework
```

> 下一步：若你希望我直接在你的 WSL 里**执行**上述步骤（安装依赖 → 拉源码 → 编译），告诉我即可；该过程耗时较长（首次全编数小时），建议放在后台运行并挂载镜像加速。
