# Android Framework 面试题 · ART 运行时与 dex2oat 冷启动深度优化（2026-08-10）

> 系列第 24 篇。前 23 篇 / 约 161 专题已把 Binder/AMS/WMS/SF/ART(碎片)/HAL/内核/TEE/pKVM/智能层/座舱/端侧AI/全链路排查/codewalk/Perfetto/真题大乱斗 全部闭环。
> 本篇落点 = 把散落在第 7/8/13/19/20 篇里只点到名的 **ART 运行时 + dex2oat + odex 布局 + profile-guided 冷启动** 焊成一个专门深水区。这是体系里唯一没独立成篇的真缺口，也是面试高频八股（"为什么第一次启动慢""App 冷启动优化""oat/odex/vdex 是啥"）。
> 形式：面试 Q&A + 详细答案解析 + 底层原理 + AOSP 源码路径佐证 + 易错点 + 高频追问 + 延伸阅读。

---

## §0 当日热点锚定（2026-08-10）

- **ART 已是 Mainline(APEX) 模块**：`com.android.art` APEX 让 CMC GC、A17 分代 GC、profile 改进能经 Google Play 系统更新下发，不必等整包 OTA（呼应第 7/8/12 篇的 ART 主线）。
- **AOSP trunk stable**：Google 宣布 2026 年 Q2/Q4 向 AOSP 发布源码，构建贡献用 `android-latest-release` 分支。
- **官方 Configure ART 文档确认 hybrid 模型**：自 Android 7 起 ART 用"AOT + JIT + 解释"混合，且 AOT 可由 profile 引导。Pixel 默认流程：安装带 `.dm`（云 profile）则安装期 AOT 编译云 profile 里的方法；不带 `.dm` 则不 AOT，首次运行解释、热点 JIT，本地 profile 与云 profile 合并，空闲充电时编译守护进程按合并 profile 重编。

---

## §1 面试 Q1：ART 为什么是"解释器 + JIT + AOT"三态共存？各态何时切换？

### 答案解析

**演进史**

| 版本 | 执行模型 | 问题 |
|------|----------|------|
| Dalvik (Android 4.4-) | 纯解释执行 | 慢 |
| ART (Android 5.0-6.0) | 纯 AOT（安装时 dex2oat 全量编译） | 安装极慢、占空间大、dex 小改就要重编整包 |
| Android 7.0+ | 解释 + JIT + AOT 混合，AOT 可 profile 引导 | 取三者之长 |

**为什么纯 AOT 不行**
- 全量编译使安装/OTA 等待爆炸，8MB dex 全 speed 编译可膨胀到 23MB OAT。
- 无法按真实运行路径优化（代码里 80% 是错误处理/冷路径，编译它们纯浪费）。
- 每次 dex 变化都要重编，OTA 后系统 app 的 dexpreopt 产物直接失效。

**为什么纯解释/JIT 不行**
- 冷启动首帧前要走大量解释/JIT warm-up，出现肉眼可见的"启动抖动"。
- JIT code cache 满后会 GC/回退解释，长尾请求可能突然变慢。

**三态职责与切换条件**

1. 首次执行某个方法 -> 解释器执行（Interpreter）。
2. 方法达到 hotness 阈值（调用次数/回边计数，通常上千次）-> JIT 编译，产物存入 **JIT Code Cache**（默认上限 64MB，满则触发 JIT GC）。
3. 设备空闲且充电 -> `BackgroundDexOptService` 唤醒 `dex2oat --compiler-filter=speed-profile`，把 profile 里的热点方法编译成机器码落盘为 `.odex`，下次运行直接 mmap 跳过解释。

### AOSP 源码落点（Android 14）

- `art/runtime/runtime.cc`：运行时初始化、解释/JIT/AOT 模式装配。
- `art/runtime/jit/jit.cc` + `art/runtime/jit/jit_code_cache.cc`：JIT 编译器与 code cache（64MB 上限、GC、method 热度计数）。
- `art/runtime/interpreter/`：解释器实现（含 quickened 指令解释）。
- `art/runtime/oat_file.cc`：AOT 产物加载（mmap .odex 的 oatexec 段，按 method 偏移跳转）。

### 易错点

- **"dex2oat 在 App 运行时跑"是误解**：运行时只做 JIT（内存编译），AOT 是 `dex2oat` 工具离线（安装期 / 空闲守护进程）产物。
- **混淆三者产物**：解释无产物；JIT 产物在内存 code cache（易失）；AOT 产物是落盘的 `.odex`。

### 高频追问

- JIT code cache 满了之后正在执行的方法会怎样？（答：已编译的保留，新热点排队，未编译回退解释；cache 内方法按 LRU/GC 回收。）
- hotness 阈值是固定值吗？（答：分方法大小/调用与回边两类计数器，阈值在 `jit.h` 的 `kJitHotMethodThreshold` 等常量，可编译期调。）

### 延伸阅读

- `art/runtime/jit/profile_saver.cc`：JIT 如何把热方法名/统计导出成 profile 文件。
- AOSP 文档《Configure ART》"How ART works" 一节。

---

## §2 面试 Q2：oat / odex / vdex / art 镜像 四件套到底是啥？布局与生成时机？

### 答案解析

**一句话区分**：`.odex` 是机器码，`.vdex` 是校验加速 + 未压缩 dex，`.art` 是启动期预加载的类/字符串内部表示，原始 `.dex` 仍在 APK 内。

| 文件 | 内容 | 生成时机 | 关键点 |
|------|------|----------|--------|
| `.dex` | Dalvik 字节码（原始） | APK 打包 | 永远在 APK 里，没被"搬走" |
| `.odex` (OAT) | AOT 编译后的机器码 | dex2oat（安装/空闲） | 含 OatHeader + oatdata + oatexec + oatlastword 段；运行时 mmap 直接执行 |
| `.vdex` | VerifierDeps + 未压缩 dex | dex2oat | 加速 verify（免重解压 dex），**不含机器码** |
| `.art` (image) | 预加载 classes/strings 内部表示 | dex2oat（app_image:true） | 可选；加速 App 启动，不是"ROM 镜像" |

**OAT 文件布局（内存映射视角）**
- `OatHeader`：magic、校验和、instruction set、各段偏移。
- `oatdata`：编译单元元数据（类/方法到机器码偏移的查找表）。
- `oatexec`：真正的机器码，运行时以 `PROT_EXEC` mmap 执行。
- `oatlastword`：结尾对齐填充。

**存放位置**
- 用户 App：`/data/app/<pkg>/<hash>/oat/arm64/base.odex` 与同目录 `base.vdex`、`base.art`。
- 系统：`/system/framework/<arch>/` 与 `/data/dalvik-cache/<arch>/`。
- `boot.art` / `boot.oat`：Zygote 预加载的系统类镜像（由 `PinnerService` 钉进内存，减少 page fault）。

### AOSP 源码落点

- `art/runtime/oat_file.cc` / `art/runtime/oat.h`：OAT 解析、`GetOatHeader()`、`FindDexFile()`。
- `art/runtime/vdex_file.cc`：vdex 解析、VerifierDeps 加载。
- `art/runtime/image.cc` / `art/runtime/gc/space/image_space.cc`：`.art` 镜像加载为 image space。
- `art/dex2oat/dex2oat.cc`：四件套的生成入口（`--oat-file / --output-vdex / --app-image-file`）。

### 易错点

- **".odex 是 dex 的副本"错**：odex 是机器码，dex 仍在 APK；运行时读 dex 拿字节码、读 odex 拿机器码。
- **"vdex 含机器码"错**：vdex 只加速校验 + 存未压缩 dex。
- **".art 是系统 ROM 镜像"错**：它是某 App 启动期预加载类/字符串的对象布局快照。

### 高频追问

- 删掉 `.odex` 应用还能跑吗？（答：能，回退解释/JIT；这也是为何 dex 必须留在 APK。）
- 为什么系统镜像用 dexpreopt 预编 boot classpath？（答：Zygote 预加载系统类，保证开机与 SystemUI 即时响应；见 §7。）

### 延伸阅读

- `oatdump --oat-file=base.odex`：反汇编 OAT 看方法偏移与编译状态。
- `art/runtime/gc/space/image_space.cc` 的 `ImageSpace::Init`：image 映射与 relocation。

---

## §3 面试 Q3：dex2oat 编译流水线 + compiler filter（verify/quicken/speed/speed-profile/everything）

### 答案解析

**dex2oat 全流程**：`DEX -> 前端解析 -> HGraph(SSA 中间表示) -> 优化 pass(20+：DCE/循环/常量折叠/边界检查消除/内联/去虚化) -> LIR -> 线性扫描寄存器分配 -> 机器码 -> OAT 打包`。优化 pass 的开关由 compiler filter 决定，这也是编译耗时主要来源。

**compiler filter —— 是"手术刀"不是银弹**

| filter | 行为 | OAT 体积 | 安装耗时 | 适用 |
|--------|------|----------|----------|------|
| `verify` | 只校验，不产机器码 | 最小 | 最快 | 系统分区冷代码 |
| `quicken` | 校验 + 轻量指令优化（**仅 Android 11 及以下**） | 小 | 快 | 旧版系统分区 |
| `speed-profile` | 校验 + 只编 profile 内方法 + 优化 profile 内类加载 | 中 | 中 | **Play 默认 / 安装期最优** |
| `speed` | 校验 + 全量 AOT（所有方法） | 大 | 慢 | 追求极致冷启动、不计空间 |
| `everything` | speed + 全量调试信息 | 最大 | 最慢 | 调试/不用于生产 |

**底层枚举**：`art/runtime/compiler_filter.h` 的 `Filter`（`kAssumeVerified/kExtract/kVerify/kQuicken/kSpeedProfile/kSpeed/kEverything`），`art/runtime/compiler_filter.cc` 负责字符串解析。`dex2oat` 用 `--compiler-filter=` 传入。

### AOSP 源码落点

- `art/dex2oat/dex2oat.cc`：`ParseCompilerFilter`、`Compile()` 主流程。
- `art/runtime/compiler_filter.cc`：filter 字符串<->枚举、各 filter 能力判断（`IsCompiling()`/`IsVerifying()`）。
- `art/compiler/optimizing/`：HGraph 构建与优化 pass（`optimizing_compiler.cc`）。

### 易错点

- **"everything 最快"是误区**：全量 AOT 让冷启动更快更稳，但安装/OTA 等待与 I/O 代价可能抵消收益（8MB->23MB 案例）。
- **quicken 仅 Android 11 及以下**，新系统已退场，别在 Android 14 面试里当成现役 filter。
- **verify 几乎不产机器码**，运行全靠解释/JIT，适合"几乎不跑"的系统代码。

### 高频追问

- speed-profile 没被 profile 标记的方法首帧怎么跑？（答：解释或 JIT，直到下次 bg-dexopt 把它们编入。）
- 怎么看某个 App 当前用哪个 filter？（答：`pm compile --get-status`（或 dumpsys package 看 `primaryCpuAbi` 下的 compile filter）、`oatdump` 看 OatHeader。）

### 延伸阅读

- `art/dex2oat/dex2oat.cc` 的 `--compiler-filter` 与 `--profile-file` 协同逻辑。
- AOSP《Configure ART》"Compiler filters" 表。

---

## §4 面试 Q4：PMS/Installer 如何触发 dexopt？安装时到底编译到什么程度？

### 答案解析

**安装期编译调用链**
```
PackageManagerService.performDexOptLocked()
  -> PackageDexOptimizer (choose filter + abi)
    -> Installer.dexopt()  // Binder 跨到 installd
      -> installd (native 守护进程) -> fork + exec dex2oat
```

**默认策略（Pixel/官方）**
- APK 带 `.dm`（云 profile）-> 安装期用 `speed-profile` 直接 AOT 编译云 profile 热点方法（首启即快）。
- APK 不带 `.dm` -> 安装期 **不做 AOT**（等价于 `interpret-only`），首次运行解释+JIT，之后空闲充电由 `BackgroundDexOptService` 跑 bg-dexopt 重编。

**触发时机分类**
- `first-boot`：开机首次，系统 app dexpreopt 已预编，用户 app 多走解释。
- `post-boot` / 安装：按需，受 filter 策略约束。
- `idle + charging`：`BackgroundDexOptService` 周期性对"变慢的/常用"App 做 speed-profile 重编。

**关键架构点**：dexopt 真正编译发生在 **installd 原生守护进程**，不在 system_server 的 Java 堆里，避免编译阻塞 framework。

### AOSP 源码落点

- `frameworks/base/services/core/java/com/android/server/pm/PackageDexOptimizer.java`：filter 选择、abi 决策、调 Installer。
- `frameworks/base/services/core/java/com/android/server/pm/DexOptHelper.java`：performDexOpt 编排（Android 13+ 抽出）。
- `frameworks/base/services/core/java/com/android/server/pm/Installer.java`：Binder 代理，把 dexopt 请求发到 installd。
- `frameworks/native/cmds/installd/`：native 端执行 `dex2oat`（`commands.cpp` 的 `dexopt()`）。

### 易错点

- **`adb install` 默认不带 `.dm` 云 profile**，所以本地装的包首启比 Play 装的慢近 40%（真实案例：同 APK 冷启差 40%）。这也是"为什么测试机首启慢"。
- **dexopt != 手敲 dex2oat 命令**：filter/abi/类加载上下文都由 PMS 策略与 `class_loader_context` 决定，手写命令极易编出不可加载的产物。
- **安装期"编译到什么程度"不是固定值**，取决于是否带云 profile + 设备策略。

### 高频追问

- 多 dex / 动态下发插件（如加 dexElements）会影响 dexopt 吗？（答：会，类加载上下文变化可能让已编 odex 失效，需重新 dexopt；插件化要处理 `classpath`/context。）
- installd 为什么要独立进程？（答：dex2oat 编译重、吃 CPU/内存，放原生守护进程隔离，避免拖垮 system_server 与 Zygote。）

### 延伸阅读

- `frameworks/base/services/core/java/com/android/server/pm/dex/DexManager.java`：dex 使用记录与失效管理。
- `BackgroundDexOptService.java` 的 `runIdleOptimization`。

---

## §5 面试 Q5：Profile-guided 全链路——基线 Profile / 云 Profile / ProfileSaver / bg-dexopt

### 答案解析

**三类 profile 辨析**

1. **本地 profile（运行时产出）**
   - 路径：`/data/misc/profiles/cur/0/<pkg>/primary.prof` + `primary.profm`（metadata，把 profile 映射回具体 dex 版本）。
   - 来源：JIT 在 code cache 满/周期触发时，由 `ProfileSaver` 把热方法名 + 类加载信息导出落盘。

2. **云 profile（Play 聚合）**
   - 全球用户匿名本地 profile 被 Play 聚合 -> 以 `.dm`（dex metadata）随 APK 下发。
   - 安装期 `dex2oat --compiler-filter=speed-profile --profile-file=<云profile>` 直接 AOT 编译全局热点，**首启即快，消灭 JIT warm-up 期**。

3. **基线 Profile（开发者侧）**
   - APK 内 `assets/dexopt/baseline.prof`（bundletool 打包；或开发者用 Macrobenchmark + Baseline Profile Gradle 插件在真机跑关键路径生成）。
   - 作用与云 profile 类似：安装/构建期即编译核心路径，不必等 JIT 积累。

**合并与重编**
- 本地 profile 与云 profile 合并 -> 设备空闲充电时 `BackgroundDexOptService` 调 dexopt 用合并 profile 重编，App "越用越快"。
- `profman` 工具负责 profile 合并/分析/校验。

### AOSP 源码落点

- `art/runtime/jit/profile_saver.cc`：JIT -> profile 文件导出主逻辑。
- `frameworks/base/services/core/java/com/android/server/pm/BackgroundDexOptService.java`：空闲重编调度。
- `art/tools/profman/`：profile 合并与格式处理。
- `frameworks/base/core/java/android/content/pm/`（编译指令常量 `COMPILE_FILTER_*`）。

### 易错点

- **基线 Profile（开发者生成）≠ 云 profile（Play 下发）**：前者打包进 APK，后者经 Play 动态下发，二者都用 speed-profile 编译但来源不同。
- **`adb install` 不带 `.dm`** 所以没有云 profile，"越用越快"要从首次运行后才开始。
- **profile 是与 dex 版本绑定的**（`.profm` 做版本校验），dex 变了旧 profile 失效。

### 高频追问

- 怎么验证某 App 的 profile 是否生效？（答：`adb shell dumpsys package <pkg>` 看 compile filter；`/data/misc/profiles/cur/0/<pkg>/` 看 profile 文件大小变化；`oatdump` 看 odex 里方法是否编译。）
- 云 profile 隐私如何保证？（答：匿名聚合、不含用户数据，只含方法/类热点统计。）

### 延伸阅读

- Android 官方《Baseline Profile》文档 + `androidx.benchmark` Macrobenchmark。
- `art/tools/profman/profman.cc` 的 `Merge` / `Analyze` 子命令。

---

## §6 面试 Q6：从 ART 视角，App 冷启动为什么"第一次慢"？优化矩阵？

### 答案解析

**根因（ART 维度）**
1. 安装无云 profile（尤其 `adb install`）-> 首次运行大量方法解释/JIT warm-up，肉眼抖动。
2. 多 dex / 复杂 `class_loader_context` -> 首次加载大量类、校验耗时长。
3. 首次运行要填充 JIT code cache、建立 profile，几帧后才稳定。
4. 系统侧 page fault：类/资源从存储读入内存，未预热时缺页多（系统用 `PinnerService` 钉 boot.art/framework oat 缓解）。

**应用侧优化矩阵**
- **接入 Baseline Profile**：构建期编译核心启动路径（最关键、收益最大）。
- **上架 Play 拿云 profile**：安装期即 AOT 核心路径。
- **减少 dex 数量 / 控制首个 dex 体积**：降低类加载与校验成本。
- **避免首次加载重类/重对象**：延迟初始化非首屏依赖（呼应第 19/20 篇冷启动排查的 ContentProvider 前置坑）。
- **`androidx.startup` 收敛 ContentProvider**：每个 ContentProvider 都拖慢 Application 阶段（第 20 篇 codewalk 已强调）。

**系统侧**
- `PinnerService` 把 `boot.art` 与 framework oat 钉进内存，降低 Zygote/SystemUI/App 冷启的 page fault。

### AOSP 源码落点

- `frameworks/base/services/core/java/com/android/server/am/PinnerService.java`：pin boot image 与 framework、app 的 oat/art。
- `frameworks/base/core/java/android/content/pm/PackageManager.java`：`COMPILE_FILTER_*` 与 `pm compile` 指令。
- 协同：第 19 篇《全链路排查》冷启动段（bindApplication 占比、基线/云 profile、PinnerService）。

### 易错点

- **冷启动优化不只是"主线程别阻塞"**：ART 编译态（解释/JIT/AOT 切换）是根因之一，单优化主线程线程模型治标不治本。
- **PinnerService 钉的是系统镜像/框架，不是应用私有文件**；应用冷启改善靠的是 boot.art 预热 + 自身 profile。
- **"装完立刻快"只发生在带云 profile 的 Play 包**，测试机 `adb install` 必然首启慢。

### 高频追问

- 怎么量化冷启动里"ART 编译"占了多少？（答：Perfetto `android_startup` 表 + slice 拆解 `bindApplication`/`verify`/`dex2oat` 等待；第 21 篇 Perfetto SQL 库有范例。）
- 热启动 vs 温启动从 ART 视角差在哪？（答：热启动进程常驻、odex 已 mmap、JIT cache 命中；温启动进程死但 odex/art 已落盘，省了解释 warm-up。）

### 延伸阅读

- 第 19 篇《全链路排查实战》§1 冷启动；第 20 篇《codewalk》§2 Application 启动。
- AOSP《Configure ART》"App startup" 与 dexpreopt 段。

---

## §7 面试 Q7：空间 vs 时间 编译权衡 + 安装/OTA dexopt 成本，为什么不能 everything？

### 答案解析

**存储/时间代价**
- 全量 `speed`：8MB dex -> 23MB OAT，安装等待 + I/O 可能抵消启动收益（尤其存储敏感设备）。
- 系统分区用 `verify`/`quicken` 省空间，但运行靠 JIT，首次执行慢。

**dexpreopt（构建期系统预编）**
- 系统 ROM 构建时用 `dexpreopt` 把 framework jar（framework.jar/services.jar）与系统 app 预编。
- **boot classpath 默认 `speed-profile`**（用 baseline profile 基准），保证 Zygote/SystemUI 即时响应。
- dexpreopt 产物**依赖 boot classpath 不变**——一旦 `com.android.art` Mainline 更新换掉 boot classpath，系统 app 的 dexpreopt 直接失效，需重新 dexopt（`reason: ART module update`）。

**OTA 后代价**
- OTA 改 boot classpath -> 系统 app dexpreopt 失效 -> 开机后触发重新 dexopt（这也是 OTA 后头几天"系统忙/耗电"的成因之一）。

**调试手段**
- `dexoptanalyzer`：分析某包该不该重编、当前 filter、空间成本。
- `oatdump`：看 OAT 方法与编译状态。

### AOSP 源码落点

- `art/tools/dexoptanalyzer/`：重编决策分析。
- `art/tools/oatdump/`：OAT 反汇编与状态查看。
- `frameworks/base/services/core/java/com/android/server/pm/dex/DexManager.java`：dex 使用/失效记录。
- `build/core/dex_preopt*.mk` + `dex_preopt { profile, app_image }`（`Android.bp`）：构建期 dexpreopt 配置。

### 易错点

- **"everything 最快"是误区**：全量编译的安装/体积/I-O 代价常抵消收益；生产用 `speed-profile` 是性价比最优。
- **系统分区冷代码用 verify/quicken 省空间，运行靠 JIT**，别以为是"没编译就慢"——它本就不常跑。
- **ART Mainline 更新会让系统 dexpreopt 失效**，这是 OTA 后重编与短暂卡顿的隐藏根因。

### 高频追问

- 为什么 boot classpath 默认 speed-profile 而不是 speed？（答：boot 类极多，全 speed 体积/构建时间爆炸；profile 已覆盖启动热路径，性价比最优。）
- dexpreopt 与 runtime dexopt 的产物能混用吗？（答：不能，前者构建期、后者设备期，且都绑定 boot classpath 版本。）

### 延伸阅读

- AOSP《Configure ART》"System ROM configuration / dexpreopt" 段。
- `art/tools/dexoptanalyzer/dexoptanalyzer.cc` 的 `--analyze` 输出含义。

---

## 易错红榜 TOP15（ART / dex2oat 专版）

1. dex2oat 在 App 运行时跑 —— 错，运行时只 JIT，AOT 是离线产物。
2. .odex 是 dex 的副本 —— 错，是机器码，dex 仍在 APK。
3. .vdex 含机器码 —— 错，只加速校验 + 存未压缩 dex。
4. .art 是系统 ROM 镜像 —— 错，是某 App 启动期预加载类/字符串快照。
5. quicken 是现役 filter —— 错，仅 Android 11 及以下。
6. everything 最快 —— 错，安装/体积/I-O 代价常抵消收益。
7. verify 会产机器码 —— 错，几乎不产，运行靠解释/JIT。
8. adb install 与 Play 装包冷启一样快 —— 错，缺 .dm 云 profile，首启慢约 40%。
9. Baseline Profile == 云 Profile —— 错，前者打包进 APK（开发者），后者 Play 下发。
10. PinnerService 钉应用私有文件 —— 错，钉的是 boot.art/framework oat（系统侧）。
11. 冷启动慢只因主线程阻塞 —— 错，ART 编译态（解释/JIT/AOT 切换）是根因之一。
12. dexopt = 手敲 dex2oat —— 错，filter/abi/class_loader_context 由 PMS 策略决定。
13. 删 .odex 应用就崩 —— 错，回退解释/JIT，dex 仍在 APK。
14. ART Mainline 更新不影响系统 app —— 错，boot classpath 变会让 dexpreopt 失效需重编。
15. profile 与 dex 版本无关 —— 错，.profm 做版本校验，dex 变旧 profile 失效。

---

## 三条高频追问链

**链 A：冷启动"越用越快"全链路**
JIT 热方法 -> ProfileSaver 导出 primary.prof -> 本地+云 profile 合并 -> BackgroundDexOptService 空闲充电重编（speed-profile）-> odex 扩编 -> 下次冷启更多方法走 AOT -> 首帧前解释/JIT 占比下降。
（衔接第 19 篇冷启动排查、第 20 篇 Application 启动、第 21 篇 Perfetto 启动 SQL。）

**链 B：安装期编译策略决策树**
带 .dm 云 profile？是 -> speed-profile 安装期 AOT（首启快）；否 -> 不 AOT（interpret-only），首次运行解释+JIT，空闲后 bg-dexopt。系统 ROM -> dexpreopt（boot classpath 默认 speed-profile）。
（衔接 §3 filter 表、§4 PMS/Installer、§7 dexpreopt。）

**链 C：OTA/Mainline 更新后的编译失效链**
OTA 改 boot classpath / art APEX 更新 -> 系统 app dexpreopt 失效 -> 开机后重新 dexopt（reason）-> 头几天系统忙/耗电/偶顿 -> 重编完成恢复。
（衔接第 7 篇 ART Mainline、第 8 篇分代 GC 经 Mainline 热更。）

---

## AOSP 源码路径清单（Android 14）

| 主题 | 路径 |
|------|------|
| 运行时初始化 | `art/runtime/runtime.cc` |
| JIT + code cache | `art/runtime/jit/jit.cc`, `art/runtime/jit/jit_code_cache.cc` |
| 解释器 | `art/runtime/interpreter/` |
| OAT 解析/加载 | `art/runtime/oat_file.cc`, `art/runtime/oat.h` |
| vdex 解析 | `art/runtime/vdex_file.cc` |
| art 镜像 | `art/runtime/image.cc`, `art/runtime/gc/space/image_space.cc` |
| dex2oat 主流程 | `art/dex2oat/dex2oat.cc` |
| compiler filter | `art/runtime/compiler_filter.cc/.h` |
| HGraph 优化 | `art/compiler/optimizing/` |
| ProfileSaver | `art/runtime/jit/profile_saver.cc` |
| profman | `art/tools/profman/` |
| PMS dexopt 编排 | `frameworks/base/services/core/java/com/android/server/pm/PackageDexOptimizer.java`, `DexOptHelper.java` |
| Installer(Binder) | `frameworks/base/services/core/java/com/android/server/pm/Installer.java` |
| installd(native) | `frameworks/native/cmds/installd/` |
| BackgroundDexOpt | `frameworks/base/services/core/java/com/android/server/pm/BackgroundDexOptService.java` |
| DexManager | `frameworks/base/services/core/java/com/android/server/pm/dex/DexManager.java` |
| PinnerService | `frameworks/base/services/core/java/com/android/server/am/PinnerService.java` |
| dexoptanalyzer/oatdump | `art/tools/dexoptanalyzer/`, `art/tools/oatdump/` |
| dexpreopt 构建 | `build/core/dex_preopt*.mk`, `Android.bp` 的 `dex_preopt { profile, app_image }` |

---

## 24 篇交叉索引（本篇 + 前 23 篇）

- 第 7 篇《端侧AI与A17演进》：首次点到 ART oat/odex/vdex/art 与 profile-guided（本篇把它焊成专门深水区）。
- 第 8 篇《A17新雷区》：ART 分代 GC 经 `com.android.art` Mainline 热更（呼应 §7 dexpreopt 失效）。
- 第 13 篇《AppFunctions与ComposeFirst》：Compose 编译器插件产出 dex，与 ART 加载/JIT 协同。
- 第 19 篇《全链路排查实战》§1 冷启动：bindApplication 占比、基线/云 profile、PinnerService（本篇 §6 给 ART 视角的根因）。
- 第 20 篇《codewalk》§2 Application 启动 / ContentProvider 前置坑：首屏类加载与 ART 编译态叠加。
- 第 21 篇《Perfetto SQL 范例库》§2 冷启动 SQL：`android_startup` + slice 拆解 dex2oat/verify 等待，量化本篇 §6 的"ART 编译占比"。
- 第 23 篇《真题大乱斗》：跨子系统场景里"端侧LLM静默杀×三路杀"与 ART/runtime 关联，本篇补全 runtime 编译侧。

> 至此系列 24 篇 / 约 166 专题：主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧AI + 全链路排查 + codewalk + Perfetto + 真题大乱斗 + **ART 运行时专用深水区** 完整闭环。
> 后续若继续日更可轮换：Perfetto SQL 扩充（input 延迟 / GPU 计数器 / battery 耗电细分）、真题大乱斗 vol.2（更刁钻多子系统叠加）、KMP/skiko 非 Android target 运行时深水区（第十五篇已部分覆盖 Android 侧差异）。
