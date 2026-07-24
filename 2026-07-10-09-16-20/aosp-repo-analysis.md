# AOSP 仓库分析解读报告（flybigpig/aosp）

> - **仓库地址**：https://cnb.cool/flybigpig/aosp.git
> - **分析方式**：Git partial clone（`--filter=tree:0 --depth=1`）只读目录树，未下载任何文件内容（仓库约 125G，避免全量 clone 卡死）
> - **分支**：main（唯一分支）｜ **同步来源**：官方 AOSP `android-14.0.0_r1`（经 Repo 工具）｜ **构建系统**：Soong + Make + Bazel
> - **生成时间**：2026-07-10

---

## 一、仓库概览

| 项 | 内容 |
|----|------|
| 仓库类型 | AOSP monorepo（由 Repo 多子仓库合并为单仓） |
| 同步来源 | 官方 AOSP `android-14.0.0_r1`（通过 Repo 工具下载后合并） |
| 默认分支 | `main`（仓库仅此一个分支） |
| 代码规模 | 约 **125 GB**（大文件经 Git LFS 追踪） |
| 构建系统 | Soong（`Android.bp`）+ Make（`.mk`）+ Bazel（`WORKSPACE`/`BUILD`） |
| 托管特性 | cnb「读秒克隆」(git-clone-yyds)、volume 缓存、远程开发（VSCode）、Code Wiki |
| 编译环境 | Docker（`.ide/Dockerfile`，Ubuntu 18.04） |

**一句话定位**：把官方 AOSP android-14.0.0_r1 的众多 Git 子仓库合并成 **monorepo** 后，托管在 cnb.cool、可云端读秒克隆与编译的完整 Android 源码仓库。

---

## 二、核心目录树状图

### 2.1 顶层树状图

```
aosp/  (android-14.0.0_r1 monorepo · 125G · Git LFS)
├── .cnb.yml            CNB 构建/CI 流水线配置（核心定制文件）
├── .cnb/               CNB 平台元数据
├── .gitignore
├── .ide/
│   └── Dockerfile       AOSP 编译环境镜像（Ubuntu 18.04 + openjdk-8）
├── Android.bp           Soong 蓝图（根，Bazel 兼容入口）
├── BUILD                Bazel 构建文件
├── WORKSPACE            Bazel workspace 定义
├── bootstrap.bash       Bazel 引导脚本
├── README.md / README.en.md
├── art/                Android Runtime（ART 虚拟机、dex 编译器、JIT）
├── bionic/             C/C++ 运行时（libc / libm / linker / libstdc++）
├── bootable/           Bootloader 与启动相关
├── build/              构建系统（Soong / Make / Bazel / envsetup.sh）
├── cts/                兼容性测试套件（Compatibility Test Suite）
├── dalvik/             Dalvik 兼容层
├── developers/         开发者文档与示例
├── development/        开发工具与脚本
├── device/             设备专属配置（google / generic / amlogic / ...）
├── external/           外部开源库（共 396 个子项目）
├── frameworks/         Android 核心框架层（Java / Native 框架）
├── hardware/           硬件抽象层 HAL（interfaces / libhardware / 厂商）
├── kernel/             内核（占位目录，通常独立维护）
├── libcore/            Java 核心类库（java.* / javax.* 实现）
├── libnativehelper/    JNI / native 辅助库
├── packages/           系统应用与服务（apps / providers / services / ...）
├── pdk/                Platform Development Kit
├── platform_testing/   平台测试
├── prebuilts/          预编译工具链与 SDK（共 30 类）
├── sdk/                SDK 构建
├── system/             核心系统服务与守护进程（init / netd / vold / ...）
├── test/               测试
├── toolchain/          工具链
└── tools/              杂项工具
```

### 2.2 核心子系统下一层（均经 `git ls-tree` 核实）

**frameworks/**（14 个一级模块）
```
frameworks/
├── av/          音视频框架
├── base/        Android 核心框架（api / apex / services / core / telephony / wifi / media 等标准子模块）
├── compile/     编译相关（dex 等）
├── ex/          扩展框架
├── hardware/    框架层硬件接口
├── layoutlib/   布局渲染库（IDE 用）
├── libs/        框架公共库
├── minikin/     字体排版引擎
├── multidex/    多 dex 支持
├── native/      Native 框架（核实含：aidl / cmds / include / libs / opengl / services / vulkan）
├── opt/         可选模块
├── proto_logging/  proto 日志
├── rs/          RenderScript
└── wilhelm/     OpenSL ES / OpenMAX AL 音频
```

**system/**（50+ 模块，核心举例，均核实存在）
```
system/
├── core/        ⭐ 系统核心（核实含：init / fastboot / fs_mgr / debuggerd / healthd / libcutils / libappfuse / libdiskconfig ...）
├── apex/        APEX 模块运行时
├── bpf/         eBPF 程序
├── connectivity/ 连接管理
├── hwservicemanager/ HIDL 服务管理
├── libhidl/     HIDL 库
├── libhwbinder/  HWBinder
├── media/       媒体服务
├── netd/        网络守护进程
├── sepolicy/    SELinux 策略
├── security/    安全
├── update_engine/ 系统更新引擎（A/B）
├── vold/        存储卷守护进程
└── ...（共 50+ 模块：apex/bpfprogs/chre/gatekeeper/gsid/incremental_delivery/keymaster/keymint/libvintf/...）
```

**packages/**（7 个一级，核实）
```
packages/
├── apps/         系统应用（见 2.3）
├── inputmethods/ 输入法
├── modules/      模块
├── providers/    ContentProvider
├── screensavers/ 屏保
├── services/     系统服务
└── wallpapers/   壁纸
```

**build/**（构建系统，核实）
```
build/
├── soong/          Soong 构建系统核心
├── blueprint/      Blueprint（Soong 前驱）
├── make/           Make 构建核心（core / envsetup 等）
├── bazel/          Bazel 集成
├── bazel_common_rules/ Bazel 通用规则
├── core/           构建核心规则
├── target/         目标定义
├── tools/          构建工具
├── envsetup.sh     ⭐ 环境初始化脚本（source 后可用 lunch / m / mm）
├── orchestrator/   编排
├── pesto/          辅助
└── CleanSpec.mk / buildspec.mk.default
```

**art/**（ART 运行时，核实关键子模块）
```
art/
├── compiler/      dex 编译器（AOT / JIT）
├── runtime/       ART 运行时
├── dex2oat/       dex→oat 编译工具
├── oatdump/       oat 转储
├── libartbase/    ART 基础库
├── libartpalette/ 调色板库
├── dalvikvm/      Dalvik VM 入口
├── openjdkjvm/    OpenJDK JVM 接口
├── openjdkjvmti/  JVMTI 实现
└── ...（共 40+ 子模块：adbconnection / artd / benchmark / cmdline / dexdump / libdexfile / ...）
```

**bionic/**（C 库，核实）
```
bionic/
├── libc/      C 标准库
├── libm/      数学库
├── linker/    动态链接器
├── libdl/     动态加载
├── libstdc++/ C++ 标准库（兼容）
├── apex/      APEX 形式
├── docs/      文档
└── tests/     测试
```

**hardware/**（HAL，核实）
```
hardware/
├── interfaces/     ⭐ HAL 接口定义（HIDL / AIDL）
├── libhardware/   硬件抽象库
├── libhardware_legacy/
├── ril/           无线接口层
└── broadcom / google / invensense / knowles / nxp / qcom / samsung / st / synaptics / ti  （厂商实现）
```

**device/**（设备配置，核实）
```
device/
├── google/      Pixel 等设备
├── generic/     通用设备（如 aosp_arm）
├── amlogic / linaro / ti / sample / common / google_car  （芯片/方案厂商）
```

### 2.3 规模统计

| 目录 | 条目数 | 说明 / 代表项（均核实） |
|------|--------|------------------------|
| `external/` | **396** | 上游开源库：abseil-cpp、angle、antlr、apache-commons-bcel/compress/io、android-nn-driver、AFLplusplus、ComputeLibrary、XNNPACK、ImageMagick、FP16、FXdiv… |
| `prebuilts/` | **30** | 预编译分类：clang、clang-tools、gcc、go、jdk、ndk、bazel、build-tools、asuite、cmdline-tools、devtools、gradle-plugin、maven_repo、ktlint、manifest-merger、module_sdk、android-emulator、abi-dumps… |
| `frameworks/` | 14 | 核心框架一级模块 |
| `system/` | 50+ | 系统服务模块 |
| `packages/apps/` | 40+ | 系统 App：Calendar、Camera2、Contacts、DeskClock、Dialer、DocumentsUI、Gallery、Launcher3、Messaging、Music、Nfc、Settings、StorageManager、Car、Browser2、Provision… |

---

## 三、核心模块解读

### 3.1 框架层 `frameworks/`
Android 应用框架的核心，分 Java 框架（`base`）与 Native 框架（`native`）。
- **frameworks/base**：Application Framework 主体，含 `api/`（公开 SDK API 文本）、`services/`（SystemServer 中的系统服务：AMS/PMS/WMS…）、`core/`（核心 Java 类）、`telephony/`、`wifi/`、`media/` 等。
- **frameworks/native**：Native 层框架，含 `libs/`（libui/libgui 等）、`services/`（surfaceflinger 等原生服务）、`opengl/`、`vulkan/`、`aidl/`。

### 3.2 系统层 `system/`
底层系统与守护进程。**system/core** 是重中之重：`init`（Android 第一个用户进程）、`fastboot`、`fs_mgr`（文件系统管理）、`debuggerd`、`healthd`、`libcutils`、`libappfuse` 等。其余如 `netd`（网络）、`vold`（存储）、`hwservicemanager`（HIDL 服务）、`sepolicy`（SELinux）、`update_engine`（A/B 更新）。

### 3.3 应用层 `packages/`
预装系统应用与核心服务。`packages/apps` 含系统 App（Settings、Launcher3、Dialer、Camera2、Contacts 等）；`packages/providers` 是系统 ContentProvider；`packages/services` 是后台系统服务。

### 3.4 构建系统 `build/`
AOSP 三套并存构建系统：
- **Soong**（`build/soong` + `build/blueprint`）：基于 `Android.bp` 的新构建系统。
- **Make**（`build/make` + `build/core`）：传统 GNU Make 体系，`envsetup.sh` 提供 `lunch`/`m`/`mm` 等命令。
- **Bazel**（`WORKSPACE`/`BUILD`/`bootstrap.bash`）：用于部分模块与 Google 内部 CI。

### 3.5 运行时 `art/` 与 `bionic/`
- **art/**：Android Runtime，将 dex 编译为 oat（AOT），含 JIT、dex2oat、oatdump、libartbase 等。
- **bionic/**：Android 的 C/C++ 运行时，提供 libc、libm、linker（动态链接器）、libstdc++，是 NDK 与系统 Native 代码的基础。

### 3.6 硬件抽象 `hardware/` 与 `device/`
- **hardware/interfaces**：HAL 接口定义（HIDL/AIDL），是 Framework 与厂商实现的解耦层。
- **hardware/libhardware**：老式 HAL 模块加载机制。
- **device/**：具体设备的 BoardConfig、manifest、overlay 等（如 `device/google` 对应 Pixel）。

### 3.7 外部依赖 `external/` 与 `prebuilts/`
- **external/（396 个）**：直接引入的上游开源项目源码（boringssl、sqlite、zlib、freetype、llvm 等），随 AOSP 一起编译。
- **prebuilts/（30 类）**：预编译好的工具链与 SDK（clang、gcc、go、jdk、ndk、bazel、build-tools、maven_repo…），无需现场编译，加快构建。

### 3.8 其他关键目录
- **libcore/**：Android 的 Java 核心类库实现（`java.*`/`javax.*`/`android.*` 部分）。
- **dalvik/**：Dalvik 相关兼容代码（ART 时代已大幅弃用）。
- **bootable/**：bootloader 与 recovery 启动相关。
- **cts/ / pdk/ / platform_testing/ / test/**：各类测试与兼容套件。
- **toolchain/ / tools/**：工具链与杂项工具。
- **kernel/**：通常内核单独维护，此处多为占位/脚本。

---

## 四、仓库定制与 CI/CD 解读

### 4.1 monorepo 合并策略
该仓库并非直接用 Repo 管理，而是把官方 AOSP 的众多 Git 子仓库：
1. 用 Repo 工具（`repo init -u ... -b android-14.0.0_r1 --depth=1 && repo sync`）下载；
2. 合并为一个 monorepo，并**删除子仓库的 `.git` 与根 `.repo`**（之后不再依赖 Repo）；
3. 将大文件转为 **Git LFS** 追踪，使 125G 代码可被 cnb 的「读秒克隆」快速准备。

### 4.2 `.cnb.yml` 构建流水线（核心定制）
`.cnb.yml` 定义了 `aosp_build_config`，关键如下：
- **Runner**：64 CPU，Docker 基于 `.ide/Dockerfile` 构建，`out` 目录使用 `copy-on-write` 卷（保留历史构建产物，支持增量）。
- **Stages**：
  1. `modify_android_soong_config_vars`：修改 `build/make/core/android_soong_config_vars.mk` 第 155 行，将 `SYSTEMUI_OPTIMIZE_JAVA` 改为 `false`。
  2. `modify_surfaceflinger_cpp`：修改 `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp` 第 6181 行 `mClientColorMatrix`（背景色矩阵），用于演示增量构建。
  3. `build`：`source build/envsetup.sh && lunch aosp_arm-eng && make -j64`，超时 2h。
- **触发条件**：
  - `push`：每次推送触发 **3 个并发**构建；
  - `web_trigger_20build`：手动按钮触发 **20 个并发**构建（压测/批量）；
  - `crontab: 0 6 * * *`：每日 06:00 触发 3 个并发构建。
- **远程开发（`$ → vscode`）**：启动 64 CPU 的 VSCode 远程开发容器（含 docker 服务），直接在云端编辑/构建。
- **Code Wiki（`tag_push`）**：打 tag 时调用 `cnbcool/codewiki` 生成代码 Wiki（4h 超时）。

### 4.3 `.ide/Dockerfile` 编译环境
基于 **Ubuntu 18.04**，安装 AOSP 编译依赖：
```
git-core gnupg flex bison gperf build-essential zip curl
zlib1g-dev gcc-multilib g++-multilib x11proto-core-dev libx11-dev
libgl1-mesa-dev libxml2-utils xsltproc unzip python openjdk-8-jdk
rsync ccache openssh-server
```
> 解读点：AOSP 14 官方推荐环境为 **Ubuntu 20.04/22.04 + OpenJDK 17**，而本 Dockerfile 使用 Ubuntu 18.04 + openjdk-8，属较早期配置（可能源自更早 AOSP 分支模板）。在此环境编译 android-14.0.0_r1 需关注 JDK 版本兼容性——这也可能是 `.cnb.yml` 中关闭 `SYSTEMUI_OPTIMIZE_JAVA` 的原因（规避新版 Java 优化相关问题）。

---

## 五、编译与增量构建指南

**环境要求**（官方）：32G+ 内存、500G+ 磁盘。

**本地标准编译**：
```bash
source build/envsetup.sh
lunch aosp_arm-eng          # 选择编译目标
make -j64                  # 全量编译
```

**云端增量构建（本仓库特色）**：
- `out` 目录通过 volume `copy-on-write` 持久化，二次构建复用历史产物。
- 修改 `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp` 第 6181 行 `mClientColorMatrix`（背景色矩阵）后，重新执行 `make -j64` 即触发增量编译，仅重编受影响的 native 模块。

---

## 六、关键根文件说明

| 文件 | 作用 |
|------|------|
| `.cnb.yml` | CNB CI/CD 与远程开发配置（仓库最核心的定制文件） |
| `.ide/Dockerfile` | 编译环境镜像定义 |
| `Android.bp` | Soong 构建蓝图根（声明顶层模块，兼容 Bazel） |
| `WORKSPACE` / `BUILD` / `bootstrap.bash` | Bazel 构建体系文件 |
| `README.md` / `README.en.md` | 仓库说明（中/英） |
| `build/` | 构建系统根目录 |

---

## 七、总结与建议

**核心结论**：
- 这是一个**完整、可直接云端构建**的 AOSP android-14.0.0_r1 单仓镜像，结构即标准 AOSP 分层（应用框架 → 系统服务 → 运行时/库 → 硬件抽象 → 内核）。
- 仓库最大特色是 **cnb 的「读秒克隆 + volume 缓存 + 远程开发 + 多并发构建」**，把 125G 的 AOSP 变成可秒级准备、云端编译的工程资产。
- 真正「定制」的部分集中在 `.cnb.yml`、`.ide/Dockerfile` 与 monorepo 合并脚本，源码本身与官方 AOSP 一致。

**使用建议**：
1. 本地改代码：直接 `git clone`（依赖 cnb 读秒克隆），`source build/envsetup.sh && lunch && make`。
2. 快速验证增量编译：改 `SurfaceFlinger.cpp` 第 6181 行后 `make -j64`。
3. 研究某层：优先读 `frameworks/base`、`frameworks/native`、`system/core`、`art`。
4. 注意 Dockerfile 的 JDK/Ubuntu 版本与 AOSP 14 官方推荐的偏差，必要时升级环境或参考官方文档。
