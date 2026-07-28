# Android Framework 热点面试题深度解析（2026-07-28 · 端侧 AI 与 Android 17 演进专题）

> 基准版本：**Android 14 (UpsideDownCake, API 34)**，AOSP 分支 `android-14.0.0_rXX`，内核 GKI `android14-6.1`；涉及 Android 16/17 演进的会显式标注。
>
> 今日热点来源（2026-07-28 联网归纳）：
> - **Android 17（API 37, CinnamonBun）已于 2026-06-16 正式发布**，主基调 = **Compose-First（View 体系进入维护模式）+ Adaptive-First（大屏 sw≥600dp 锁方向/resize/比例彻底失效）+ 端侧 AI 化（NPU 直接访问需声明 FEATURE_NEURAL_PROCESSING_UNIT）+ 隐私收紧**。
> - **端侧 AI / NPU / NNAPI** 是 2026 年最大增量热点：A17 明确要求 NPU 访问在 Manifest 声明特性，NNAPI 本身被标注为 deprecated，迁移到 LiteRT（原 TF-Lite）NPU delegate。
> - 此前五篇（主篇/拓展篇/深挖篇/图形多媒体篇/系统基建篇）已闭环 Binder、启动、WMS、View、ANR、Compose、HAL、Input、PMS、ART GC、Camera/Audio、16KB 页、权限、Keystore、AVB、logd、Doze 等；本篇聚焦**完全未覆盖的真缺口**：NNAPI/NPU、CarService、Vulkan/ANGLE、ART oat/art 镜像布局、virtual A/B（snapuserd），并把这些缺口与 A16/A17 行为变更热点衔接。
>
> 面试定位：端侧 AI + A17 演进是 2026 下半年「系统/平台/性能/AI 终端」岗的高频新考点；能答出 NNAPI 的 IDevice 分区调度、ART oat/art 镜像、virtual A/B 的 COW 快照，基本能把面试官按在地上摩擦。

---

## 目录

1. [NNAPI 全链路：从 TF-Lite 到 NPU 的旅程（热点·缺口）](#1)
2. [端侧 AI 工程化：NPU delegate / LiteRT / 厂商 SDK 与 A17 NPU 声明（热点）](#2)
3. [Android 17 行为变更与 Framework 演进热点串讲（热点·衔接）](#3)
4. [WindowInsets / Edge-to-Edge / Predictive Back（A16 强制 · A17 默认，热点衔接）](#4)
5. [大屏自适应：WindowManager / WindowSizeClass 与 WMS 底层衔接（热点·衔接）](#5)
6. [CarService / Automotive：车载 Framework 全链路（缺口）](#6)
7. [Vulkan / ANGLE / HWUI Skia 后端：图形深水区（缺口）](#7)
8. [virtual A/B 与 snapuserd：动态分区 OTA 的 COW 快照（缺口）](#8)
9. [ART 镜像与 oat/odex 布局：AOT、vdex、profile-guided 编译（缺口）](#9)
10. [查缺补漏 · 易错点 · 高频追问 · 延伸阅读](#10)

---

<a id="1"></a>
## 1. NNAPI 全链路：从 TF-Lite 到 NPU 的旅程（热点·缺口）

**Q：App 里一段 TF-Lite 模型推理，最终怎么跑到手机 NPU 上的？中间经过哪些层、哪些进程、哪些 Binder 调用？NNAPI 为什么需要 HAL？**

### 答案解析

NNAPI（Neural Networks API）是 Android 8.1 引入、Android 14 仍在用的**端侧推理抽象层**。它的定位类似 OpenGL 之于 GPU：上层推理框架写一次，下层由不同加速器（NPU / DSP / GPU / CPU）的实现来跑。

**分层与调用流（Android 14）：**

```
[App / 推理框架]  TF-Lite(NNAPI delegate) 或 直接调用 NDK NNAPI
 │   android/NeuralNetworks.h  :  ANeuralNetworksModel / Compilation / Execution
 ▼
NnApiRuntime（用户态，App 进程内）
 │   实现目录：packages/modules/NeuralNetworks/runtime/
 │   - NeuralNetworks.cpp        NDK 入口，把 ANeuralNetworks* 转成内部 Builder
 │   - Manager.cpp               getDevices():通过 IServiceManager 枚举 IDevice 服务
 │   - Driver.cpp                把一个 IDevice binder 封装成 Driver 对象
 │   - ModelBuilder/CompilationBuilder/ExecutionBuilder.cpp
 │   - ExecutionPlan.cpp         ★ 算子分区(partitioning):按各 IDevice 能力拆分图
 ▼  Binder (AIDL: android.hardware.neuralnetworks.IDevice)
[Hal 服务 / vendor 进程]  IDevice::prepareModel() → IPreparedModel::execute()
 │   实现目录：hardware/interfaces/neuralnetworks/aidl/  (1.0~1.3)
 │   vendor 具体实现：高通 HTP / MTK APU / 华为 NPU / 谷歌 EdgeTPU 的 driver
 ▼
加速器硬件（NPU/DSP）+ 内核驱动（通过 ioctl/dma-buf 把权重/激活值搬进 NPU 内存）
```

**关键源码点（务必能说路径）：**

- **运行时实现位置**：Android 12 起 NNAPI 运行时已从 `frameworks/ml/nn` 迁移到 **`packages/modules/NeuralNetworks/`**（Mainline 模块化，可独立更新）。公开 NDK 头是 `android/NeuralNetworks.h`。
- **设备发现**：`Manager::getDevices()`（`packages/modules/NeuralNetworks/runtime/Manager.cpp`）通过 **`android::hardware::IServiceManager`** 枚举所有 `android.hardware.neuralnetworks.IDevice` 实例，每个实例对应一个已注册的加速器驱动。
- **算子分区（最重要考点）**：`ExecutionPlan::prepareForExecution()` / `ExecutionPlan::Partitioning()`（`ExecutionPlan.cpp`）把计算图按算子支持度拆分到多个 IDevice + CPU 回退。例如卷积交给 NPU、某个 NPU 不支持的自定义算子在 CPU 上跑，跨设备之间通过**共享内存（ashmem/dmabuf）传递张量**，避免反复序列化。
- **HAL 形态演进**：NNAPI HAL 早期是 HIDL（`1.0~1.2`），新版改为 **AIDL**（`aidl/1.0~1.3`），与整个 Treble 向 AIDL 迁移的大趋势一致。接口核心是 `IDevice`（能力声明 + prepareModel）、`IPreparedModel`（执行）、`IExecution`（异步回调）。

**为什么需要 HAL（高频追问）：** 没有 HAL，框架就得为每个厂商 NPU 写一套专有对接代码（像早期各家用私有 so）。有了 `android.hardware.neuralnetworks.IDevice` 这个稳定 AIDL 契约，vendor 在自己的进程里实现 HAL，NnApiRuntime 通过 Binder 调用，**系统升级不破坏厂商驱动、厂商驱动升级也不影响 framework**——这就是 Treble 隔离思想在 AI 加速上的落地。

### 易错点
- 「NNAPI 就是 NPU 驱动」——错。NNAPI 是**抽象层 + 调度器**，真正的 NPU 推理在 vendor HAL 里；NNAPI 还支持把算子回退到 CPU（`nnapi-reference` 默认 CPU 实现）或 GPU。
- 「一次推理一次 Binder 调用」——错。prepareModel（建图/编译）和执行（execute）是分开的，建图开销大、应缓存 `IPreparedModel`；真正热路径是 execute，但张量数据走共享内存而非 Binder 拷贝。
- 「NNAPI 只跑 NPU」——错。分区结果可能是 NPU+CPU 混合，性能分析时别只看 NPU 占用。

### 高频追问
- NNAPI 和 TF-Lite 什么关系？→ TF-Lite 是推理框架，NNAPI 是其可选后端之一（NNAPI delegate）；TF-Lite 也能直接用 CPU/GPU delegate 而不碰 NNAPI。
- 厂商怎么接入 NNAPI？→ 实现 `android.hardware.neuralnetworks.IDevice` AIDL HAL，在 `manifest.xml` 声明，NnApiRuntime 自动发现。
- 算子不支持怎么办？→ 分区时回退 CPU，或厂商实现自定义算子扩展。

### 延伸阅读
- AOSP：`packages/modules/NeuralNetworks/runtime/`、`hardware/interfaces/neuralnetworks/aidl/`
- NNAPI 官方「Neural Networks API runtime」文档；`source.android.com/devices/neural-networks`

---

<a id="2"></a>
## 2. 端侧 AI 工程化：NPU delegate / LiteRT / 厂商 SDK 与 A17 NPU 声明（热点）

**Q：2026 年做端侧 AI，NNAPI 还值得学吗？A17 对 NPU 访问做了什么限制？LiteRT 的 NPU delegate 怎么选后端？**

### 答案解析

**NNAPI 在 2026 的状态：仍基础、但被标注 deprecated。** Android 17 明确把 NNAPI 列入**废弃路径**，鼓励迁移到 **LiteRT（原 TensorFlow Lite）** 及其新一代 delegate。原因是 NNAPI 的分区/回退策略过于保守、算子覆盖演进慢、厂商实现质量参差；Google 想把控制权收归 LiteRT 的 delegate 体系。

**A17 对 NPU 访问的硬性约束（面试必背）：**
- targetSdk 37 起，App 若**直接访问 NPU**（Neural Processing Unit），必须在 Manifest 声明 `<uses-feature android:name="android.hardware.feature.neural_processing_unit" />`（即 `FEATURE_NEURAL_PROCESSING_UNIT`），否则访问被拦截。
- 受影响范围：**使用 LiteRT NPU delegate 的 App、使用厂商 NPU SDK 的 App、以及使用（已废弃）NNAPI 的 App** 全部包含。
- 底层含义：系统把「能否用 NPU」从隐式能力变成**显式特性声明**，便于权限/功耗/隐私治理（NPU 推理涉及模型权重这种敏感资产，也涉及可观功耗）。

**LiteRT delegate 的后端选择（工程化考点）：**
```
LiteRT Interpreter
 ├─ NPU delegate      → 经 NNAPI(已 deprecated) 或直接对接厂商 NPU SDK
 ├─ GPU delegate      → OpenGL/Vulkan 计算（TfLiteGpuDelegateV2）
 ├─ CPU delegate      → XNNPACK（默认 fallback，性能稳）
 └─ Hexagon/DSP delegate（高通）等厂商专用 delegate
```
选后端本质是**延迟/功耗/精度/算子覆盖**的权衡：NPU 延迟最低功耗最优但算子覆盖窄；GPU 覆盖广、适合中等模型；CPU(XNNPACK) 最稳。生产环境通常**默认 GPU、关键路径尝试 NPU、不支持自动回退 CPU**。

**与 Framework 的关系：** 无论哪条路径，最终都收敛到「厂商在 `android.hardware.neuralnetworks.IDevice` 或自家 HAL 上实现的加速器驱动」。所以**第 1 题的 NNAPI 全链路即使被废弃，其 HAL 隔离思想依然是端侧 AI 的骨架**——学它不亏。

### 易错点
- 「A17 不让用 NPU」——错。是**不声明就不让用**，声明 `FEATURE_NEURAL_PROCESSING_UNIT` 即可。
- 「NNAPI 废弃 = 不用学了」——错。其分区调度/共享内存张量/HAL 隔离模型仍是所有端侧 AI 框架的底层范式；而且大量存量设备（A14~A16）还在跑 NNAPI。
- 「GPU delegate 一定比 CPU 快」——错。小模型 + 高频调用时，CPU(XNNPACK) 因无 kernel launch 开销可能更快；要实测。

### 高频追问
- 端侧 vs 云端推理的区别与取舍？→ 延迟、隐私、离线、功耗 vs 模型规模、算力上限。
- 模型权重怎么保护？→ 与 Keystore2/Keymint（见系统基建篇）、fscrypt 加密存储配合；NPU 访问声明也是治理一环。
- 为什么端侧 AI 现在火？→ 2026 年 Gemini Nano / 端侧 LLM 普及，A17 把系统定位成「Intelligence System」，App 能力可被 AI Agent 直接调用。

### 延伸阅读
- Android 17 behavior changes（developer.android.com/about/versions/17）
- LiteRT (TF Lite) delegate 文档；各厂商 NPU SDK（高通 AI Engine Direct、MTK NeuroPilot、华为 CANN）

---

<a id="3"></a>
## 3. Android 17 行为变更与 Framework 演进热点串讲（热点·衔接）

**Q：Android 16/17 有哪些对 Framework/应用影响最大的行为变更？Compose-First、Adaptive-First 到底改了什么？**

### 答案解析

Android 16（API 36, Baklava，2025-06-10 stable）与 Android 17（API 37, CinnamonBun，2026-06-16 stable）是近年来变动最猛的两代。核心三张牌：

**① Compose-First（A17 明确）：**
- A17 起官方**新 API/库/工具只面向 Jetpack Compose**，传统 View 体系进入维护模式（不再新增能力，只修 bug）。
- Framework 影响：View 相关源码路径（`frameworks/base/core/java/android/view/`、`widget/`）增量收敛；Compose 编译器/运行时（`frameworks/base/...` 之外，主要在 `androidx.compose.*`）成为主战场。
- 衔接深挖篇：Compose 重组的 SlotTable/Snapshot 机制（见 2026-07-23 深挖篇第 11 章）是这道题的底层支撑。

**② Adaptive-First（大屏强制 resizable）：**
- A16：在大屏（sw ≥ 600dp，折叠屏展开/平板/外接屏）上**忽略** `screenOrientation`、`resizeableActivity=false`、min/maxAspectRatio——但给了一年 opt-out 窗口。
- **A17（targetSdk 37）：opt-out 彻底移除**。大屏上你无法再用代码/配置锁定横竖屏、限制宽高比，App 必须随窗口自由伸缩。游戏类（`android:appCategory="game"`）与 sw<600dp 手机豁免。
- Framework 链路：这背后是 **WMS / WindowOrganizer / Configuration 变更** 的强化（见拓展篇「折叠屏 WM」），以及 `android.content.res.Configuration` 中 `screenLayout` / `smallestScreenWidthDp` 的频繁重算。

**③ 隐私与安全收紧（A17 最重）：**
| 变更 | 影响 | Framework 关联 |
|---|---|---|
| `ACCESS_LOCAL_NETWORK` 新权限 | 访问局域网设备需声明（NEARBY_DEVICES 组） | 网络/Connectivity 栈 |
| OTP 短信延迟 3 小时 | 非 SMS Retriever 格式的 OTP 短信 3h 后才可读 | SMS Provider / `SmsRetriever` |
| **NPU 访问需 `FEATURE_NEURAL_PROCESSING_UNIT`** | 见第 2 题 | NNAPI / 加速器 HAL |
| **动态代码加载：native 必须 read-only** | `System.load()` 加载的 so 若可写 → `UnsatisfiedLinkError` | linker / selinux / 动态分区 |
| **Certificate Transparency 默认开启** | A16 需 opt-in，A17 默认 | 网络/Conscrypt |
| 后台音频加固 | 后台 audio focus/音量修改受限 | AudioService / AudioFlinger |
| 本地网络保护 | 默认阻止，优先用隐私 picker | Connectivity |

**发布模型变化（面试加分）：** A16 起改为 **Major（行为变更+核心 API，Beta3 锁定）+ Minor（仅加 API，12 月补发，不改行为）** 双轨；A17 进一步改为**常态化 Canary（always-on）+ 季度更新（QPR）**。targetSdkVersion=36/37 才触发行为变更，Minor SDK 是纯加法。

### 易错点
- 「A17 只是加 API」——错。A17 有大量**破坏性**行为变更（大屏 resizable 强制、NPU 声明、native read-only、CT 默认），且 Google Play 强制 targetSdk 37 的截止是 **2027-08**。
- 「Compose-First 等于 View 不能用了」——错。View 仍可用、仍维护，只是**不再有新增能力**；存量 View 代码不会坏。
- 「opt-out 一直有」——错。A16 有、A17 彻底移除，别再依赖 `resizeableActivity=false` 逃避大屏适配。

### 高频追问
- 大屏相机预览变形怎么修？→ 优先 CameraX `PreviewView`（FILL_CENTER/FIT_CENTER），见第 4/5 题衔接。
- targetSdk 和 compileSdk 区别？→ targetSdk 触发行为变更，compileSdk 决定可用 API；Minor SDK 只加 API 不要求升 target。
- 怎么提前测 A17？→ `targetSdkPreview="CinnamonBun"` + 兼容性框架 `UNIVERSAL_RESIZABLE_BY_DEFAULT` 标志 + Pixel Tablet/Fold 模拟器。

### 延伸阅读
- developer.android.com/about/versions/17/behavior-changes-all
- 掘金/CSDN「Android 17 来了」系列（2026-06 多篇）

---

<a id="4"></a>
## 4. WindowInsets / Edge-to-Edge / Predictive Back（A16 强制 · A17 默认，热点衔接）

**Q：Android 16/17 的 edge-to-edge 强制到底改了什么？`fitsSystemWindows` 还生效吗？`OnBackPressedCallback` 和 predictive back 是什么关系？**

### 答案解析

**Edge-to-Edge（A16 强制，A17 持续）：**
- A16 起，targetSdk 36 的 App **不能再 opt-out edge-to-edge**：系统忽略 `Window.setDecorFitsSystemWindows(true)` 和旧版 SystemUI 可见性 flag。App 必须自己正确处理窗口 inset，否则内容被状态栏/导航栏遮挡。
- `fitsSystemWindows="true"`（XML）**仍然生效，但语义变了**：它只负责给系统栏区域加 padding，**不再阻止 App 画到边缘之外**。真正的全屏绘制由 `Window.setDecorFitsSystemWindows(false)` 开启。
- 正确姿势：
  - Compose：`enableEdgeToEdge()` + `Scaffold(innerPadding)` 自动消费 `WindowInsets`。
  - View：`ViewCompat.setOnApplyWindowInsetsListener` + `WindowInsetsCompat.Type.systemBars()`。

**底层链路：** `ViewRootImpl` 在 `dispatchApplyInsets()` 时把 `WindowInsets`（由 `ViewRootImpl` 从 WMS 的 `RelayoutResult` 拿到，含 `systemBars()` / `displayCutout()` / `ime()` 等类型）下发到 DecorView；`fitsSystemWindows` 触发的 `onApplyWindowInsets` 只是默认实现之一。Insets 本质是 **WMS 计算窗口布局时生成的矩形区域描述**，经 `IWindow.dispatchWallpaperCommand`/`dispatchSystemUiVisibility` 之外的 `dispatchWindowInsets` 通道传递。

**Predictive Back（A16 强化，A17 默认开启）：**
- 旧：`Activity.onBackPressed()` + `OnBackPressedCallback`（`androidx.activity`）。
- 新：平台级 `android.window.OnBackInvokedCallback` / `OnBackInvokedDispatcher`（`frameworks/base/core/java/android/window/OnBackInvokedDispatcher.java`），系统能在用户**开始**滑动返回时就预览「要回到哪」，而非手势结束才触发。
- `OnBackPressedCallback` 在底层已桥接到 `OnBackInvokedDispatcher`；A17 默认开启 predictive back 后，没迁移的老 `onBackPressed` 仍可用但拿不到「预览动画」。

### 易错点
- 「`fitsSystemWindows` 能阻止全屏」——错（见上）。
- 「edge-to-edge 只影响状态栏」——错。还涉及导航栏、刘海（`displayCutout`）、手势区（`mandatorySystemGestures`）、IME 等全部 inset 类型。
- 「predictive back 是新 API 必须全改」——错。`OnBackPressedCallback` 自动桥接；只有要拿「返回预览」体验才需直接用 `OnBackInvokedCallback`。

### 高频追问
- inset 和 padding/margin 什么关系？→ inset 是系统给的「被占区域」，你要决定消费（加 padding）还是忽略（画到下面）。
- 为什么 A16 强制 edge-to-edge？→ 统一全面屏体验、消除刘海/圆角处的尴尬留白，是 Material You 之后的又一次视觉统一。
- 输入法（IME）的 inset 怎么处理？→ `WindowInsetsCompat.Type.ime()` + `WindowInsetsAnimationCompat` 做同步动画。

### 延伸阅读
- `frameworks/base/core/java/android/view/WindowInsets.java`
- `androidx.core.view.WindowInsetsCompat`、`androidx.activity.EdgeToEdge`、`android.window.OnBackInvokedDispatcher`

---

<a id="5"></a>
## 5. 大屏自适应：WindowManager / WindowSizeClass 与 WMS 底层衔接（热点·衔接）

**Q：大屏（折叠屏/平板）适配在 Framework 层怎么实现的？Jetpack 的 WindowSizeClass 底层读的是什么？**

### 答案解析

大屏自适应的**应用层入口**是 Jetpack WindowManager 库，它把 WMS 的窗口度量封装成 `WindowSizeClass`：

```
App (Compose)
 └─ currentWindowAdaptiveInfo().windowSizeClass
     ├─ WindowWidthSizeClass.COMPACT   (<600dp)
     ├─ WindowWidthSizeClass.MEDIUM    (600~840dp)  ← 折叠屏展开/小平板
     └─ WindowWidthSizeClass.EXPANDED  (>840dp)     ← 平板/外接屏
         ↓ （Jetpack WindowManager 库）
     WindowMetricsCalculator.computeCurrentWindowMetrics()
         ↓ Binder → WMS
     WindowManager.getCurrentWindowMetrics()  → WindowMetrics(bounds, WindowInsets)
         ↓ 来自
     WMS 的 Configuration/smallestScreenWidthDp 计算（窗口级，非屏幕级）
```

**关键认知：大屏适配的「窗口」是 Activity 的 window，不是物理屏。** WMS 为每个窗口维护 `Configuration`，其中 `smallestScreenWidthDp` 随分屏比例/折叠状态实时变化；Jetpack 库只是把 `bounds.widthdp` 映射到三档 SizeClass，方便你写 `when`。

**底层衔接（与拓展篇「折叠屏 WM」呼应）：**
- 窗口尺寸变化由 **`WindowOrganizer`** 体系驱动（`frameworks/base/core/java/android/window/WindowOrganizer.java` 及其 `WindowContainerTransaction`），折叠/展开/分屏比例调整都走它。
- `TaskFragment`（多窗口/折叠屏分屏的基本单位）的 `Configuration` 由 WMS 在 `relayoutWindow` 时重算并下发。
- A17 的「大屏锁方向失效」本质就是：**WMS 在窗口 Configuration 里不再尊重 App 声明的 orientation，强制让 bounds 随可用区域伸缩**（见第 3 题）。

### 易错点
- 「sw≥600dp 指物理屏」——错。指**当前窗口**的 `smallestScreenWidthDp`，分屏时一个小窗口也可能是 COMPACT。
- 「WindowSizeClass 是系统新 API」——错。它本身是 **Jetpack 库**（`androidx.window`），底层读的是 WMS 给的 WindowMetrics。
- 「适配大屏就是改布局」——不全。还要正确处理**更频繁的配置变更**（折叠/旋转/分屏），用 `rememberSaveable` / ViewModel 保状态，见主篇/Compose 篇状态保存。

### 高频追问
- 折叠屏展开瞬间 Activity 会重建吗？→ 默认会（Configuration 变），可用 `configChanges` 拦截或 Compose 只重组不重建。
- CameraX 怎么避免大屏预览变形？→ `PreviewView` 的 `ScaleType.FILL_CENTER`（保持比例，默认）或 `FIT_CENTER`（letterbox）；底层 `PreviewView` 根据 `WindowMetrics` 自动算变换矩阵，避免硬编码屏幕方向。

### 延伸阅读
- `frameworks/base/core/java/android/app/Activity.java`（`getWindowManager`）
- `androidx.window.layout`、`androidx.window.core.WindowSizeClass`

---

<a id="6"></a>
## 6. CarService / Automotive：车载 Framework 全链路（缺口）

**Q：车载 Android（Automotive）的 Framework 和手机有什么不同？CarService、Vehicle HAL 各是什么？一次读车速是怎么从 CAN 总线到 App 的？**

### 答案解析

Android Automotive 是**把 Android 直接做成车机 OS**（不是手机投屏）。它在手机 Framework 之上叠了一套车载专属层。

**核心组件与源码路径：**
- **Car API（应用层）**：`packages/services/Car/car-lib/src/android/car/Car.java`
  - `Car.createCar(context)` 连接 CarService；`car.getCarManager("car_info")` 拿到 `CarInfoManager` / `CarSensorManager` / `CarAudioManager` / `CarPropertyManager` 等。
- **CarService（系统服务）**：`packages/services/Car/service/src/com/android/car/CarService.java`，`onBind()` 返回 `ICarImpl`（`ICar.aidl` 的 stub）。
  - 它是一堆**子 manager 的集合**，每个 manager 通过 Binder 暴露能力给 App，内部再去对接 Vehicle HAL / 音频 / 电源等。
- **Vehicle HAL（车载专属 HAL）**：`hardware/interfaces/automotive/vehicle/`（AIDL `2.0` / `3.0`）
  - 核心接口 `android.hardware.automotive.vehicle.IVehicle` + `IVehicleCallback`。
  - 以**属性（property）**为单位通信：车速 `VEHICLE_PROPERTY_SPEED`、挡位 `GEAR_SELECTION`、里程、胎压、HVAC 等，每个 property 有 zone（左/右/全车）。

**一次「读车速」的链路：**
```
[CAN/Ethernet 总线]  ECU 上报车速
 │  vehicle daemon (vendor, HAL 实现) 解析总线帧
 ▼
IVehicle::set(VEHICLE_PROPERTY_SPEED)            ← Binder(AIDL)
 ▼
CarService: VehicleHal / PropertyHalService
 │  监听属性变化，按 zone/权限路由
 ▼
CarPropertyManager (Binder) → App 的 CarPropertyListener.onPropertyEvent()
```
反向（App 设空调温度）则 App → CarPropertyManager → CarService → IVehicle.set(HVAC) → HAL → CAN。

**与手机 Framework 的本质差异：**
- 手机 HAL 用 `android.hardware.*`（相机/音频/传感器等通用 HAL）；车载多了**整车级 HAL（Vehicle）**，且强调**多显示（Cluster/主屏/副驾）、多用户、驾驶安全（驾驶中禁某些 UI）**。
- CarService 是**独立 apk（`packages/services/Car`）**，不是 `system_server` 内置服务；通过 `ICar` binder 与 `system_server` 协作（共享部分 AMS/WMS 能力）。
- 安全模型更严：驾驶状态（`CarUXRestrictions`）会限制 App 交互，防止司机分心。

### 易错点
- 「CarService 是 system_server 里的服务」——错。它是独立系统 App（`packages/services/Car/service`），通过 `ICar` binder 与 framework 交互。
- 「车载就是手机 Android + 大屏」——错。Vehicle HAL、多显示、UX 限制、整车电源管理都是车载专属。
- 「直接读 CAN 就行」——错。App 永远走 Car API → CarService → Vehicle HAL，无法直接碰总线（隔离 + 安全）。

### 高频追问
- Vehicle HAL 为什么用 property 模型？→ 统一管理异构 ECU 信号，增加/减少信号不影响框架接口（Treble 思想）。
- 车载怎么处理多用户/多显示？→ `CarUserManager` + 多 `Display` 与 WMS 的 `DisplayArea` 扩展。
- 与 NNAPI 的衔接？→ 车载也在用 NPU 做 DMS（驾驶员监测）/ 感知，同样走加速器 HAL。

### 延伸阅读
- `packages/services/Car/`、`hardware/interfaces/automotive/vehicle/`
- source.android.com/docs/automotive

---

<a id="7"></a>
## 7. Vulkan / ANGLE / HWUI Skia 后端：图形深水区（缺口）

**Q：Android 的图形渲染后端到底有哪些？HWUI 用 GL 还是 Vulkan？ANGLE 是干什么的？和 SurfaceFlinger/HWC 什么关系？**

### 答案解析

这是图形多媒体篇（2026-07-24）未深挖的**后端选型层**。

**HWUI 的渲染后端（Android 14）：**
- HWUI 统一走 **Skia** 后端，但 Skia 自身可选 **OpenGL** 或 **Vulkan**：
  - `frameworks/base/libs/hwui/pipeline/skia/SkiaOpenGLPipeline.cpp`
  - `frameworks/base/libs/hwui/pipeline/skia/SkiaVulkanPipeline.cpp`
- 后端选择发生在 `renderthread/CanvasContext.cpp` 初始化时，读属性 `ro.hwui.use_vulkan`（默认在很多设备上是 Vulkan）。
- UI 线程录制 `DisplayList`（见图形多媒体篇第 1 题），RenderThread 把 `RenderNode` 树翻译成 Skia 命令，再经 GL/Vulkan 提交 GPU。

**ANGLE 是什么：**
- ANGLE（Almost Native Graphics Layer Engine）在 `external/angle/`，作用是**把 OpenGL ES 调用翻译成 Vulkan（或 Metal/D3D）**。
- 在 Android 上，ANGLE 通常作为**系统 GL 驱动**：那些直接用 OpenGL ES 的 App，其 GLES 调用经 ANGLE 转到 Vulkan 执行（`ro.hwui.use_ANGLE` / `debug.hwui.use_ANGLE` 控制）。
- 为什么要有 ANGLE：① 统一驱动栈，减少各 GPU 厂商 GLES 驱动的差异与 bug；② Vulkan 后端更现代、更可调试、性能更稳；③ 让「GLES 应用」也能享受 Vulkan 的底层能力。

**三者关系图：**
```
[App UI]  View.draw → DisplayList (UI 线程)
                     ↓
[RenderThread]  HWUI(Skia) → SkiaVulkanPipeline / SkiaOpenGLPipeline
                     ↓ (ANGLE: GLES→Vulkan 在此介入)
[GPU 驱动 / Vulkan]  渲染 back buffer
                     ↓ BufferQueue (生产者)
[SurfaceFlinger]  acquire buffer → 选 Layer 合成策略
                     ↓
[HWC]  Overlay 直投 或 GPU 合成  → Display
```
注意：**HWUI/ANGLE 负责「画一帧内容」，SurfaceFlinger/HWC 负责「把多个 App 的帧合成到屏幕」**——这是两个不同阶段，别混。

### 易错点
- 「HWUI 用 OpenGL」——过时。现代设备默认 **Vulkan**（`ro.hwui.use_vulkan`），且 Skia 是统一中间层。
- 「ANGLE 是另一个渲染器」——不准确。ANGLE 是**翻译层**（GLES→Vulkan），让老 GLES App 跑在 Vulkan 后端上。
- 「Vulkan 替代了 SurfaceFlinger」——错。Vulkan 是 HWUI 的 GPU 后端；SurfaceFlinger/HWC 的合成逻辑独立存在（部分合成也可用 Vulkan，但概念层分离）。

### 高频追问
- 为什么 Vulkan 比 GL 好？→ 更显式的内存/同步控制、更少驱动开销、更可预测的性能（对帧率稳定有利）。
- ANGLE 出 bug 谁负责？→ 它现在是系统组件（Mainline? 部分），由 Google 维护，厂商 GLES 驱动的差异被收敛。
- 这与 16KB 页/图形内存（Gralloc/DMA-BUF，见图形多媒体篇第 4 题）什么关系？→ 后端产出的 buffer 仍走 `GraphicBuffer`/`dma-buf` + fence 的零拷贝链路到 SF。

### 延伸阅读
- `frameworks/base/libs/hwui/pipeline/skia/`、`external/angle/`、`frameworks/native/vulkan/`
- source.android.com/docs/graphics

---

<a id="8"></a>
## 8. virtual A/B 与 snapuserd：动态分区 OTA 的 COW 快照（缺口）

**Q：virtual A/B 和普通 A/B 有什么区别？OTA 更新时为什么不需要预留双倍空间？snapuserd 怎么实现「边用边合并」？**

### 答案解析

**普通 A/B**：设备有两套完整系统分区（slot A / slot B），更新写空闲 slot，重启切换。代价：**占用双倍空间**。

**virtual A/B（Android 10+，现为默认）**：不再物理复制整套分区，而是用**动态分区 + 快照（snapshot）**模拟出「另一 slot」。核心组件：
- `system/update_engine/` —— OTA 引擎，下载 payload、校验、写入。
- `system/core/fs_mgr/snapshot/` —— `SnapshotManager.cpp` / `SnapshotFuzz.cpp`，管理快照生命周期。
- `external/snapuserd/` —— **snapuserd 守护进程**，实现 COW（Copy-On-Write）快照。

**COW 快照原理（关键）：**
- OTA 时，update_engine 要修改某个块，但旧块还要保留（用于回滚）。snapuserd 在**设备映射层**拦截写请求：
  - 旧数据先拷到 **COW 设备**（copy-on-write），再让新写落到「逻辑上的新 slot」。
  - 读旧 slot 时，snapuserd 根据 COW 元数据决定读原块还是 COW 副本。
- 这样**物理上只占增量空间**，不用整盘双份。

**「边用边合并」（merge）流程：**
```
OTA 下载 → update_engine 经 snapuserd 写 COW → 重启
 │  fs_mgr 挂载快照（snapshot），系统从「新 slot 视图」启动
 ▼  开机后系统正常运行
后台空闲时：snapuserd 把 COW 里的块**真正合并**回底层分区
 │  合并完成 → 删除 COW，快照消失，新 slot 成为真实分区
 ▼  若合并中失败/用户回滚 → 用 COW 还原旧块，回到旧 slot
```
源码：`external/snapuserd/snapuserd_client.cpp`（客户端）、`cow_reader.cpp`/`cow_writer.cpp`（COW 格式）、`system/core/fs_mgr/snapshot/SnapshotManager.cpp`（`CreateSnapshot`/`ProcessSnapshot`）。

**与普通 A/B 的对比考点：**
| | 普通 A/B | virtual A/B |
|---|---|---|
| 空间 | 双倍 | 仅增量（COW） |
| 回滚 | 切 slot | 还原 COW |
| 合并 | 无 | 后台 merge |
| 动态分区 | 不需要 | 必须（super 分区 + lp metadata） |

### 易错点
- 「virtual A/B 不用双倍空间 = 无成本」——错。COW 有**写放大**（首次修改要拷贝），合并期有 I/O 开销，低存储设备仍可能 OTA 失败。
- 「snapuserd 是内核模块」——错。它是**用户态守护进程**，工作在设备映射（dm-user / dm-linear）之上。
- 「OTA 完立即生效」——错。要等后台 merge 完成、重启后才算真正落定；merge 失败会自动回滚。

### 高频追问
- 动态分区（super）和 virtual A/B 什么关系？→ virtual A/B 依赖动态分区（把 system/vendor/product 合并进 `super` 分区，用 `lp` metadata 描述），才能做逻辑快照。
- OTA 校验怎么保证安全？→ 与 AVB/dm-verity（系统基建篇第 5 题）配合，payload 带 vbmeta 签名。
- COW 和文件系统的 copy-on-write（如 btrfs）区别？→ 这里是**块设备级** COW（dm 层），与具体文件系统无关。

### 延伸阅读
- `system/update_engine/`、`system/core/fs_mgr/snapshot/`、`external/snapuserd/`
- source.android.com/docs/core/ota

---

<a id="9"></a>
## 9. ART 镜像与 oat/odex 布局：AOT、vdex、profile-guided 编译（缺口）

**Q：APK 里的 dex 是怎么变成机器码的？oat / odex / vdex / art 镜像分别是什么？profile-guided 编译怎么工作？为什么手机首次开机很慢？**

### 答案解析

这是 ART 编译体系的「产物布局」层，与深挖篇（ART GC / verify / deopt）互补。

**编译流水线（dex → 机器码）：**
```
classes.dex (APK)
 │  dex2oat (art/dex2oat/)  按 compiler filter 编译
 ▼
.oat/.odex   机器码 + OAT 头（含校验、偏移表）
.vdex        校验信息 + 原始 dex（去重，多个 odex 共享）
.art         boot image（预编译的核心类对象镜像，启动加速）
```

**各产物含义（面试常混淆点）：**
- **oat/odex**：包含编译后的本地机器码。系统 App 在 `/system/framework/*.oat`，普通 App 在 `/data/app/.../oat/`。`odex` 是「optimized dex」的历史叫法，现与 oat 同义（App 的叫 `base.odex`/`base.vdex`）。
- **vdex**：存 dex 的**校验和 + 已验证的 dex 字节**，让二次 dex2oat 跳过重校验；多个 odex 可共享一个 vdex（如多 ABI）。
- **.art（boot image）**：`/system/framework/boot.art` + `boot.oat`，是**预创建的核心类对象镜像**（String、Class 对象等），开机直接 mmap 进内存，省去运行时初始化开销——这是「为什么 ART 启动比 Dalvik 快」的关键之一。

**Compiler filter（决定编译程度）：**
| filter | 含义 | 触发 |
|---|---|---|
| `verify` | 只校验，不编译（解释执行） | 安装默认（部分） |
| `quicken` | 仅编译少量热路径 | 旧默认 |
| `speed-profile` | 仅按 profile 编译热方法 | **普通 App 安装后后台编译** |
| `speed` | 全量编译 | `pm compile -m speed` |
| `everything` | 全量+更多优化 | 诊断 |

**Profile-guided（最核心考点）：**
- App 运行期，ART 的 **JIT**（`art/runtime/jit/`）记录热方法，写入 profile（`profile_compilation_info.cc` / `profile_saver.cc`，存 `/data/misc/profiles/`）。
- 设备空闲+充电时，`installd`（`frameworks/native/cmds/installd/commands.cpp` 的 `dexopt`）或 `dexoptanalyzer` 按 profile 做 `speed-profile` 编译，把热方法 AOT 化。
- **系统镜像也有 boot profile**：`frameworks/base/config/boot-image.prof.txt` 描述哪些框架类该进 boot image。
- 这就是为什么「首次开机/首次启动 App 慢，用几天变快」——profile 在积累，AOT 在后台补全。

**源码路径（Android 14）：**
- 编译器：`art/dex2oat/dex2oat.cc`、`art/dex2oat/`、`art/libdexfile/`。
- 运行时产物加载：`art/runtime/oat_file.cc`、`art/runtime/image.cc`。
- JIT/profile：`art/runtime/jit/jit_code_cache.cc`、`profile_compilation_info.cc`、`profile_saver.cc`。
- 系统侧触发：`frameworks/native/cmds/installd/commands.cpp`（`dexopt`）、`frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java`（安装时安排编译）。

### 易错点
- 「dex2oat 安装时就全编译」——错。普通 App 安装默认是 `verify`/`speed-profile`（按需），全量 `speed` 才全编，全编很慢很占空间。
- 「oat 就是机器码文件」——对但不全。oat 还含 OAT 头、dex 偏移、类元数据，且通常伴随 vdex。
- 「vdex 是老格式没用了」——错。vdex 仍在，用于去重校验、加速重编译。
- 「boot image 是 boot.oat」——两者一起：`boot.art`（对象镜像）+ `boot.oat`（机器码），缺一不可。

### 高频追问
- 解释执行、JIT、AOT 三者关系？→ 冷路径解释执行 → 热方法 JIT 编译并采 profile → 后台按 profile AOT 成 oat，形成「解释→JIT→AOT」分级。
- 为什么 dex 不删？→ vdex/odex 仍依赖原始 dex 做校验与回退（如 deopt，见深挖篇 verify/deopt）。
- 16KB 页（系统基建篇）对 oat 有什么影响？→ oat/art 镜像布局按页对齐，16KB 页下对齐基数变，未对齐的 so/oat 在新设备加载失败。

### 延伸阅读
- `art/dex2oat/`、`art/runtime/oat_file.cc`、`art/runtime/image.cc`
- 「ART 概述」「dex2oat」「Profile-guided optimization」官方文档

---

<a id="10"></a>
## 10. 查缺补漏 · 易错点 · 高频追问 · 延伸阅读

### 10.1 六篇知识网络总图（截至 2026-07-28）

| 篇章 | 日期 | 主题覆盖 | 本文互补 |
|---|---|---|---|
| 主篇 | 07-23 | Binder/启动/AMS·ATMS/WMS/View/ANR/LMKD/Compose/HAL/内核/MTK | — |
| 拓展篇 | 07-23 | Input/PMS/ART-JIT·AOT/SystemUI/折叠屏WM/SELinux/OTA/AB/JNI/Binder安全/Perfetto | — |
| 深挖篇 | 07-23 | ART对象头/CMC GC/verify·deopt/Binder调试/Rust Binder/Input多指/VSync时序/Camera·Audio HAL/GKI KMI/Perfetto SQL | — |
| 图形多媒体通信篇 | 07-24 | HWUI/Choreographer/SF/图形内存/多刷新率/MediaCodec/Codec2/Thermal·Power HAL/Telephony·RIL/Wi-Fi/BT | — |
| 系统基建与可观测性篇 | 07-27 | 16KB页/ClassLoader/权限/Keystore2/AVB/fscrypt/Vold·FUSE/logd/可观测性/RRO/Doze·JobScheduler/A15·16变更 | 衔接 A17 变更 |
| **本篇（端侧AI与A17演进）** | **07-28** | **NNAPI/NPU/LiteRT/CarService/Vulkan·ANGLE/ART oat·art/virtual A/B/WindowInsets·Predictive Back/WindowSizeClass** | A17 热点 + 真缺口 |

### 10.2 今日易错点速记（高频踩坑）
1. NNAPI = 抽象层+调度器，**不是 NPU 驱动本身**；A17 已标 deprecated，但底层 HAL 思想不过时。
2. A17 用 NPU **必须声明 `FEATURE_NEURAL_PROCESSING_UNIT`**，并非禁止。
3. `fitsSystemWindows` 在 A16+ **不再阻止全屏**，只加 padding；edge-to-edge 靠 `setDecorFitsSystemWindows(false)`。
4. 大屏 sw≥600dp 指**窗口** smallestScreenWidthDp，非物理屏；A17 彻底移除 orientation opt-out。
5. HWUI 默认 **Vulkan** 后端，ANGLE 是 GLES→Vulkan 翻译层，二者不替代 SurfaceFlinger/HWC。
6. virtual A/B 用 **COW 快照**省空间，snapuserd 是**用户态**守护进程；合并失败自动回滚。
7. oat/odex/vdex/art 四件套各司其职；普通 App 默认 `verify`/`speed-profile`，全量 `speed` 才慢。

### 10.3 高频追问清单（按主题）
- **NNAPI**：分区策略？张量怎么跨设备传？厂商如何接入 HAL？与 LiteRT 关系？
- **A17**：Compose-First 对 View 的影响？Adaptive-First 怎么强制？NPU 声明 penalties？
- **CarService**：与 system_server 区别？Vehicle HAL property 模型？多显示怎么管？
- **Vulkan/ANGLE**：GL vs Vulkan 后端怎么选？ANGLE 的代价？与 SF 合成边界？
- **virtual A/B**：COW 实现？merge 时机？与普通 A/B 空间对比？
- **ART 产物**：解释/JIT/AOT 分级？profile 怎么采？boot image 为什么加速启动？

### 10.4 后续可轮换的真·未覆盖角度
- Media3 / ExoPlayer 底层、Codec2 vendor plugin 开发
- NNAPI 之后的 **LiteRT NPU delegate 源码走读**、端侧 LLM（Gemini Nano）运行时
- CarService 多用户/多显示、Automotive 电源管理
- Vulkan 在 SF 合成中的使用（`RenderEngine` Vulkan 后端）
- OTA 安全：AVB 与 virtual A/B 组合、snapuserd 回滚的攻击面
- ART `hiddenapi` 名单、非 SDK 接口管制、runtime 黑名单机制

### 10.5 延伸阅读总入口
- AOSP 代码：`cs.android.com`（按本文路径对照阅读，分支 `android-14.0.0_rXX`）
- 官方文档：`source.android.com`（Neural Networks / Automotive / Graphics / OTA / ART）
- 行为变更：`developer.android.com/about/versions/17`（与 15/16 串讲）
- 社区：掘金「Android 17 来了」、sharpskill.dev「Android 16 interview questions」、CSDN 高级知识图谱

---

> 复习建议：本篇与「系统基建与可观测性篇（07-27）」的 A15/16 变更章节连续看，可形成完整的「版本演进」时间线；NNAPI/CarService/Vulkan/ART 产物/virtual A/B 五块是此前完全未覆盖的真缺口，建议优先吃透。每日一专题、保持手感，面试时从入口串到源码路径即为高分。
