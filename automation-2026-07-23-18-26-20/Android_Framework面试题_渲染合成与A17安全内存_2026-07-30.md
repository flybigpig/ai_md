# Android Framework 热点面试题深度解析（2026-07-30 · 渲染合成深水区 · 多媒体 HAL 落地 · Android 17 安全/内存新雷区）

> 基准版本：**Android 14 (UpsideDownCake, API 34)**，AOSP 分支 `android-14.0.0_rXX`，内核 GKI `android14-6.1`；涉及 Android 16/17 演进的会显式标注，并说明版本边界。
>
> **今日热点来源（2026-07-30 联网归纳，Google I/O 2026 / Android 17 stable 2026-06-16）：**
> - **图形栈原生化**：Android 17 起 **Vulkan 成为平台原生 GPU API**，OpenGL(ES) 经 ANGLE 翻译到 Vulkan，WebGPU 通过 Jetpack 提供 Kotlin/Java API（底层仍走 Vulkan）。这是渲染深水区的最大增量。
> - **A17 安全/内存收紧**：① 新增 **Memory Limiter**（应用级内存限额，kill 原因含 "Memory Limiter" 字符串）；② **更安全原生 DCL**（动态代码加载的 .so 必须只读、防篡改，否则 `System.load` 失败）；③ **Keystore 每应用密钥限额**（按 target SDK 分级，超额抛异常）；④ **跨资料（work profile）环回流量默认阻断**；⑤ **限制隐式 URI 授权**。
> - **此前八篇（主篇16 / 拓展篇10 / 深挖篇11 / 图形多媒体篇12 / 系统基建篇11 / 端侧AI篇10 / A17 新雷区篇8）已闭环** Binder、启动、WMS、View、ANR、Compose、HAL、Input、PMS、ART GC/对象头、Camera/Audio、16KB 页、权限、Keystore2、AVB、logd、Doze、NNAPI、CarService 基础、Vulkan/ANGLE 概念、Media3、端侧 LLM、Lock-free MessageQueue、hiddenapi 等。
> - 本篇按「**A17 真·新热点 + 完全未覆盖真缺口**」双线推进，并在第 8 章给出九篇交叉索引：**① SF RenderEngine（GL/Vulkan 合成后端）+ HWC 合成决策深水区；② Codec2 vendor plugin（CCodec→C2Component 厂商扩展）；③ A17 Memory Limiter 与 LMKD/ART 分代 GC 协同；④ 安全原生 DCL 加固；⑤ Keystore 限额 + 跨资料环回阻断；⑥ CarService 多用户/多显示/整车电源；⑦ ART oat/odex/vdex/art 镜像布局深水区**。**
>
> 面试定位：能答出「Vulkan 原生化下 SF 到底怎么合成一帧」、「Codec2 厂商 plugin 要写哪几个类」、把「内存优化题」答出「A17 Memory Limiter + LMKD + 分代 GC 三层协同」、把「插件化/Hook 题」答出「A17 安全 DCL + hiddenapi + 16KB 三连击」，即建立强区分度。

---

## 目录

1. [SF RenderEngine（GL/Vulkan 合成后端）与 HWC 合成决策深水区（热点·真缺口）](#1)
2. [Codec2 vendor plugin 开发：CCodec → C2Component → 厂商组件对接（真缺口）](#2)
3. [Android 17 Memory Limiter（应用内存限额）与 LMKD / ART 分代 GC 协同（热点）](#3)
4. [安全原生 DCL 加固 + 更安全的原生代码加载（A17 新雷区）](#4)
5. [Keystore2 每应用密钥限额 + 跨资料环回阻断（A17 安全收紧）](#5)
6. [CarService 多用户 / 多显示 / 整车电源（Automotive 深水区）](#6)
7. [ART oat/odex/vdex/art 镜像布局深水区（头部结构 + 加载 + deopt 联动）](#7)
8. [查缺补漏 · 易错点速记 · 高频追问 · 九篇交叉索引](#8)

---

<a id="1"></a>
## 1. SF RenderEngine（GL/Vulkan 合成后端）与 HWC 合成决策深水区（热点·真缺口）

**Q：Android 17 说 Vulkan 成了「原生 GPU API」、WebGPU 进了 Jetpack；那 SurfaceFlinger 真正合成一帧时走哪条路？RenderEngine 的 GL/Vulkan 后端怎么切换？HWC 什么时候接管、什么时候退给 GPU 合成？**

### 答案解析

这是把「图形多媒体通信篇（2026-07-24）」的 HWC/BufferQueue 概念往**合成引擎内部**钻的关键一步（07-24 只讲了 SF 用 BufferQueue/HWC overlay vs GPU 合成的概念，没进 RenderEngine 内部；07-28 只讲了 HWUI/ANGLE 的 App 侧后端）。

**① Vulkan 原生化（A17 热点）：**
- A17 起 **Vulkan 是平台底层原生 GPU API**；OpenGL(ES) 不再是一等公民，而是经 **ANGLE** 翻译成 Vulkan 跑（07-28 ch7 已讲 ANGLE = GLES→Vulkan 翻译层）。
- **WebGPU** 通过 Jetpack 提供惯用 Kotlin/Java API（`androidx.graphics.webgpu` 之类），底层同样访问 Vulkan——这是给应用「桌面级图形/计算」的新入口。
- App 侧 HWUI 默认 Vulkan 后端（07-28 ch7），产出的 UI 帧 buffer 经 BufferQueue 给 SF。

**② RenderEngine 是什么：**
- RenderEngine 是 **SurfaceFlinger 的 GPU 合成引擎**，只负责画「**HWC 无法 overlay、必须由 GPU 画的 layer**」（即 client composition）。App 帧本身由 HWUI 在 App 进程画好，SF 只是把多 App 帧 + 系统层合到屏幕——两者处于不同进程/不同阶段（07-28 ch7 的图已区分）。
- 接口与实现（`frameworks/native/libs/renderengine/`）：
  - `include/renderengine/RenderEngine.h` —— 抽象接口（`drawLayers`、`init` 等）。
  - `impl/RenderEngine.cpp` —— **工厂** `RenderEngine::create()`，按设备后端能力构造具体实现。
  - `gl/GLESRenderEngine.cpp` —— **GL 后端**（用 GLES 提交绘制）。
  - `skia/SkiaGLRenderEngine.cpp` —— **SkiaGL 后端**（用 Skia 录制命令、经 GLES 提交）。
  - `vulkan/VulkanRenderEngine.cpp` —— **Vulkan 后端**（A14+ 已有、A17 强化，直接用 Vulkan command buffer 画）。
- **后端切换**：由 `RenderEngine::create()` 工厂决定，读设备属性（如 `ro.hwui.use_vulkan` / 厂商 overlay 的渲染能力），优先选 Vulkan，否则 GL/SkiaGL。同一个 SF 进程内只有一个 RenderEngine 实例。

**③ HWC vs GPU（client composition）合成决策（最核心考点）：**
- SF 在 `rebuildLayerStacks` / `calculateWorkingSet` 后，对每个 `BufferLayer` 调用 `BufferLayer::updateCompositionState()`（`frameworks/native/services/surfaceflinger/BufferLayer.cpp`）决定该 layer 的 `mCompositionState.hwc`：
  - `HWC2::Composition::Device` —— 交给 **HWC overlay** 直投（最省电，不走 GPU）。
  - `HWC2::Composition::Client` —— SF 用 **RenderEngine（GPU）** 画进 `RenderSurface` 的 framebuffer。
- 决策依据（`frameworks/native/services/surfaceflinger/HWComposer.cpp` 的 `getDeviceCompositionType` / `createLayer` / `setClientComposition`）：layer 数量是否超 HWC  overlay 上限、尺寸/混合/受保护内容（DRM）/模糊(Blur)/圆角/色彩空间/旋转等 HWC 能力，以及 `HWC2::Capability`。任一不满足 → 退为 Client 合成。
- **合成上屏**：被标 Client 的 layer → `SurfaceFlinger::doComposition` → `BufferLayer::onDraw` → `RenderEngine::drawLayers`（GPU 画到 target buffer）；Device layer 由 HWC 直投；最后 HWC 把两类 layer 合成到 Display。
- **fence 同步**：GPU 画完产生 `releaseFence`，HWC 合成前需等 `acquireFence`；fence 经 `frameworks/native/libs/nativewindow/` 与 `system/core/libsync/` 的 sync 机制传递（与 07-24 ch4 讲的 GraphicBuffer/dma-buf 零拷贝 + fence 是同一套机制，只是这里在 SF 合成侧协作），避免读写竞态。

### 易错点
- 「Vulkan 替代了 SurfaceFlinger」——错。Vulkan 只是 RenderEngine 的一个 GPU 后端；SF/HWC 的合成**决策层**独立存在（07-28 ch7 已强调）。
- 「HWC 永远比 GPU 快」——错。layer 过多/带复杂特效（模糊、圆角、混合、受保护内容）时，SF 退化为 GPU client composition，多窗口/大量模糊叠加时整屏可能都走 GPU。
- 「ANGLE 只服务 App」——错。ANGLE 把系统 GL 驱动也收编，SF 若选 GL 后端同样走 ANGLE→Vulkan。
- 「RenderEngine 负责画 App 的 UI」——错。App UI 由 HWUI 在 App 进程画；RenderEngine 只做「多窗口/多 App 帧合成」。

### 高频追问
- 为什么 A17 强推 Vulkan？→ 更显式的内存/同步控制、更可预测的帧率、统一驱动栈收敛厂商 GLES 差异；WebGPU/ANGLE 都收敛到它。
- client composition 掉帧怎么查？→ Perfetto 看 `surfaceflinger` 的 `onMessageInvalidate`/`doComposition` 时长，统计转 `Client` 合成的 layer 数量与占比。
- RenderEngine 和 HWUI 后端什么关系？→ 都用 Skia/Vulkan，但 HWUI 在 App 进程画单 App 帧，RenderEngine 在 SF 进程合成多 App 帧——阶段与进程都不同。

### 延伸阅读
- `frameworks/native/libs/renderengine/`（`impl/`、`gl/`、`skia/`、`vulkan/`）
- `frameworks/native/services/surfaceflinger/BufferLayer.cpp`、`SurfaceFlinger.cpp`、`HWComposer.cpp`
- `external/angle/`（07-28 ch7）；图形多媒体通信篇（2026-07-24）第 1/3/4 章

---

<a id="2"></a>
## 2. Codec2 vendor plugin 开发：CCodec → C2Component → 厂商组件对接（真缺口）

**Q：MediaCodec 是怎么用上厂商硬件编解码器的？Codec2 框架下，厂商要实现一个「plugin」需要写哪些类？C2Work/C2Buffer 怎么从 App 的 Surface 流到 NPU/DSP？**

### 答案解析

这是把「图形多媒体通信篇（2026-07-24）第 6/7 章 MediaCodec/Codec2 概念」往**厂商接入**钻的真缺口。

**① 演进与桥接：**
- 老的 OMX 路径（`ACodec` + `OMXNodeInstance`）已被 **Codec2** 取代，Android 11+ 默认 Codec2（`frameworks/av/media/codec2/`）。
- App 用 `MediaCodec`（`frameworks/base/media/java/android/media/MediaCodec.java`）→ native `android_media_MediaCodec.cpp` → `MediaCodec.cpp`（`frameworks/av/media/libstagefright/`）→ 选择 **Codec2 插件 `CCodec`**（`frameworks/av/media/codec2/sfplugin/CCodec.cpp`）。`CCodec` 是 **SF 侧的 Codec2 插件**，把 MediaCodec 的 API 翻译成对 `C2Component` 的调用。
- **关键认知**：MediaCodec **不直接**调厂商 HAL。`CCodec` → `C2ComponentStore`（组件仓库）→ 厂商 `C2Component`（组件实例）；更底层的硬件访问由厂商组件内部经 `android.hardware.media.c2` HAL/驱动完成。

**② 厂商 plugin 必须实现的类（核心考点，都在 `frameworks/av/media/codec2/core/`）：**
- **`C2Component`**：一个编解码组件实例，执行实际编/解码。`start()` / `stop()` / `queue(&work)` / `flush()`，经 `onWorkDone` 异步回调返回完成的 `C2Work`。
- **`C2ComponentInterface`**：组件暴露的参数/配置接口，用类型安全的 **`C2Param`**（如 `C2StreamWidthInfo`、`C2StreamBitrateInfo`、`C2PortMediaTypeSetting`）描述能力（分辨率、码率、profile、色彩空间等）。
- **`C2ComponentStore`**（`frameworks/av/media/codec2/vndk/C2Vndk.cpp` / `C2Store.cpp`）：组件**工厂/仓库**，按组件名 `createComponent(name, listener)` 创建 `C2Component`，并提供 `C2Param` 描述清单。厂商继承它实现自己的 store 并注册。
- **`C2Work` / `C2Buffer`**：`C2Work` 是工作单元（输入 `C2FrameData` + 输出占位）；`C2Buffer` 是数据载体——`C2GraphicBuffer` 包裹 `C2Handle` → `GraphicBuffer`/`AHardwareBuffer`/`dma-buf`，实现**零拷贝**（与 07-24 ch4 的 GraphicBuffer/dma-buf 零拷贝直接复用）。

**③ 厂商实现位置与加载：**
- AOSP 参考软解：`frameworks/av/media/codec2/components/`（`C2Soft*` 软件参考组件，可跑 CPU/DSP）+ `hardware/google/media/codec2/`（Google 设备参考）。
- 厂商闭源实现：`vendor/<oem>/media/codec2/`（高通/MTK/等），编译成 `libcodec2_vendor*.so` 放到 `/vendor/lib64/`，由 `C2Store`（`C2PlatformComponentStore` 或厂商 store）动态加载注册。
- 加载流程：`CCodec` 拿到 `C2Store` → `createComponent(name, listener)` → 厂商 `C2Component` → `config()` 设 `C2Param` → `start()` → 循环 `queue(&work)` 喂入编码/解码任务。

**④ 数据流（零拷贝）：**
```
App Surface(输入帧)
  → CCodecBufferChannel(frameworks/av/media/codec2/sfplugin/CCodecBufferChannel.cpp)
      把 GraphicBuffer 包成 C2Buffer
  → C2Component(厂商硬件: NPU/DSP/Codec IP)  解码/编码
  → 输出 C2Buffer 回填 Surface(显示) 或 AudioTrack(音频, 见深挖篇 Audio)
```
全程传 **buffer handle**，无 memcpy——这是 Codec2 相对 OMX 的核心改进之一。

### 易错点
- 「MediaCodec 直接调厂商 HAL」——错。中间经 `CCodec`（SF 插件）→ `C2ComponentStore` → 厂商 `C2Component`，HAL 是更底层。
- 「厂商 plugin 就是丢个 .so 进去」——不全。必须实现 `C2Component`/`C2ComponentInterface`/`C2ComponentStore` 契约并正确注册，参数用 `C2Param` 描述，否则 `CCodec` 无法识别组件。
- 「OMX 已删除」——错。OMX 仍在（兼容旧设备/特定组件），但新设备新组件走 Codec2。
- 「C2Work 就是一块内存」——错。它是带输入/输出占位、时间戳、标志的工作单元，`C2Buffer` 才是数据载体。

### 高频追问
- Codec2 相比 OMX 优势？→ 类型安全的 `C2Param`、更好的 buffer 管理（C2Buffer/Gralloc 零拷贝）、支持动态配置/多端口。
- 厂商怎么调试 Codec2 组件？→ `codec2-info`/`codec2-dump` 看 store 注册的组件与参数；Perfetto 抓 `codec2` 轨迹看 `queue`/`onWorkDone` 时延。
- 软件组件 `C2Soft` 干什么？→ AOSP 自带参考软解，无硬件时仍能播（CPU 解码），也是厂商组件的参考实现。

### 延伸阅读
- `frameworks/av/media/codec2/`（`core/`、`vndk/`、`sfplugin/`、`components/`）
- `hardware/google/media/codec2/`、`hardware/interfaces/media/c2/`
- 图形多媒体通信篇（2026-07-24）第 6/7 章（MediaCodec 状态机、Codec2 vs OMX）

---

<a id="3"></a>
## 3. Android 17 Memory Limiter（应用内存限额）与 LMKD / ART 分代 GC 协同（热点）

**Q：Android 17 的「Memory Limiter」是什么？它和 LMKD/PSI、ART 分代 GC 怎么协同？App 内存超限后会发生什么、怎么排查？**

### 答案解析

这是把「内存/卡顿/ANR 优化」经典题答出 **2026 新深度** 的关键（系统基建篇讲了 LMKD/PSI，07-29 讲了 ART 分代 GC，本篇把它们和 A17 新机制串起来）。

**① 定位与版本边界（重要）：**
- **Memory Limiter** 是 **Android 17（API 37）新增**的**应用级内存限额**机制，针对「内存泄漏 / 占用过高」的 App 进行限流/回收，其 kill 原因在 `ActivityManager` 的进程致命错误 `getDescription()` 中含字符串 **"Memory Limiter"**（来自 Google I/O 2026 行为描述）。
- ⚠️ **版本边界**：这是 A17 机制，**Android 14 基线源码树里没有对应类**。它建立在 A14 已有的 LMKD/PSI 体系之上（见系统基建篇）。面试按「行为 + 演进」答即可，不必编造 A14 路径。

**② 与已有机制的三层协同：**
- **LMKD/PSI（系统基建篇）**：A14 已用 PSI（`/proc/pressure/memory`）触发 `lmkd` 回收/杀进程。Memory Limiter 在 LMKD 之上加了**按 App 的硬性内存上限**（更激进的 `cached` 回收 + 前台限额），专门限流「常驻后台且 RSS 飙升」的 App。
- **ART 分代 GC（07-29 ch2）**：分代 GC 降低 GC CPU/卡顿，但**救不了泄漏**——泄漏对象进了 old gen 仍占内存。Memory Limiter 正是兜泄漏的底：老对象堆积超阈值 → 被限流/杀，退出信息标注。
- **ProfilingManager 新触发器（07-29 ch4 扩展）**：A17 给 `ProfilingManager` 加了 **ANOMALY 类型触发器**（除已有的 `COLD_START`、`KILL_EXCESSIVE_CPU_USAGE`、`OOM`），内存异常被杀时自动抓 trace，直接用 Perfetto 定位泄漏点；Android Studio 还能让 Gemini 解释泄漏栈。

**③ App 侧表现与排查：**
- 超限被限流 → 进程被 kill，用户再次打开冷启动；`dumpsys meminfo <pkg>` 看占用峰值。
- dropbox / `am get-process-fatal-error` 看是否标 "Memory Limiter"。
- ProfilingManager 抓的 trace 用 `trace_processor` + SQL 分析（深挖篇 Perfetto SQL 实战）。

**④ 应对（面试加分）：** 用 Memory Profiler/LeakCanary 查泄漏；大对象在 `onTrimMemory` 释放；Bitmap/ImageDecoder 复用；避免 static 持有 Context/View；配合 A17 `ProfilingManager` 触发器做线上内存异常自动采集。

### 易错点
- 「Memory Limiter 是 LMKD 换个名」——错。LMKD 按**系统整体**内存压力杀，Memory Limiter 按**单 App 内存上限**限流，粒度更细、面向泄漏。
- 「分代 GC 能防泄漏」——错。GC 只回收不可达对象，泄漏（仍被引用）对象分代也救不了。
- 「A14 就有 Memory Limiter」——错。A17 新增。
- 「超限只是变慢」——错。严重超限额会被 kill（进程消失、冷启动）。

### 高频追问
- 内存上限按什么定？→ 与设备 RAM、App target SDK、前台/后台状态、是否媒体/游戏相关（Google 未公开精确公式，按行为答）。
- 和 iOS Jetsam 类似吗？→ 思路类似（按内存上限杀进程），但 Android 多了 LMKD/PSI 全局压力层 + ART GC 层。
- 怎么验证没踩？→ 压测后台常驻 + `dumpsys meminfo` 监控 RSS，配合 LeakCanary 自动化。

### 延伸阅读
- developer.android.com → "Android 17 memory limits"（Memory Limiter）
- 系统基建与可观测性篇（2026-07-27）LMKD/PSI 章节
- 2026-07-29 篇 ch2（ART 分代 GC）、ch4（ProfilingManager）

---

<a id="4"></a>
## 4. 安全原生 DCL 加固 + 更安全的原生代码加载（A17 新雷区）

**Q：Android 17 说「更安全的原生 DCL（动态代码加载）」，具体改了什么？`System.load()` 加载一个可写的 .so 为什么会失败？和 16KB 页、SELinux、hiddenapi 什么关系？**

### 答案解析

这是把「插件化/Hook/热修复」这条线（系统基建篇 ClassLoader、07-29 hiddenapi）往 **native 动态加载** 收口的 A17 新雷区。

**① 背景：** 动态代码加载（DCL）= 运行时加载 .so（`System.loadLibrary`/`dlopen`），是插件化/Hook/热修复的底层手段。Android 17 收紧：**dlopen/加载的 DCL 模块（native .so）必须不可被未检测地覆盖/篡改**（官方表述 "C apps must ensure DCL modules aren't overwritten undetected"），即模块需**只读 + 完整性可验证**。

**② 具体表现：**
- **native 必须 read-only**：A17 起 `System.load()`/`dlopen` 加载的 .so 若文件**可写**（如下载到可写目录、`chmod 666`），加载直接失败（`UnsatisfiedLinkError`）。07-28 ch3 表已提「动态代码加载：native 必须 read-only」，此处讲底层。
- **完整性**：系统希望 DCL 模块来源可信、未被篡改，配合 linker 的完整性校验（类似 16KB 对齐校验 + Verified Boot 思路）。

**③ 底层链路：**
```
java.lang.System.loadLibrary(name)
  → Runtime.loadLibrary0(...)            (libcore/ojluni)
  → nativeLoad(...)                       (java_lang_Runtime.cc)
  → android::OpenNativeLibrary(...)       → bionic/linker/linker.cpp 加载 ELF
```
`bionic/linker` 在 A17 对 DCL 模块做额外校验：文件权限（**可写则拒**）、ELF LOAD 段对齐（16KB 页）、SELinux 文件类型 (`execmod`/`linker` 域)。

**④ 与周边三连击（必考联动）：**
- **16KB 页**（系统基建篇 ch1）：linker 校验 ELF LOAD 段对齐（动态 `getpagesize()`）；未对齐的 .so 在 16KB 设备直接失败——DCL 模块须**同时满足 16KB 对齐 + 只读**。
- **SELinux**（拓展篇）：DCL 模块文件需正确 `file_type` + `exec_type`；违规 `execmod` 被 SELinux 拒（如 `denied { execute }` on `unlabeled`）。
- **hiddenapi**（07-29 ch3）：DCL + 反射 @hide 是老 Hook 组合拳；A17 同时封 static final 反射 + 收紧 DCL，双管齐下堵 Hook。

**⑤ 影响与出路：** 自研插件化/热修复若把补丁 .so 下到**可写目录**再 `dlopen`，A17 直接崩；必须放只读位置或走签名校验。现代方案走 **art method entrypoint 替换**（native 层、不动 final 字段、不依赖可写 DCL，07-29 ch3 已讲）更稳。

### 易错点
- 「DCL 限制只影响 Java 代码」——错。native `dlopen` 同样受限，A17 明确强调「原生 DCL 模块防篡改」。
- 「把 .so 放可读写目录更方便更新」——A17 下行不通，必须只读 + 可信来源。
- 「DCL 限制 = 不能热更新」——错。不能「未检测地覆盖」，但可走只读分区/签名校验/官方机制。

### 高频追问
- 老插件化框架怎么活？→ 补丁 .so 放只读 + 签名校验，或迁移到不依赖可写 DCL 的 native hook（art method 指针替换）。
- linker 怎么判断文件可写？→ 查 `fstat` 的 `st_mode` 写位 + SELinux 上下文。
- 和 16KB 一起踩坑怎么排？→ `zipalign -c -P 16` 验 so 对齐 + `ls -l` 看权限 + `logcat -b kernel`/`dmesg` 看 linker/selinux 拒绝日志。

### 延伸阅读
- `bionic/linker/linker.cpp`、`bionic/linker/linker_phdr.cpp`（对齐校验）
- `libcore/ojluni/src/main/java/java/lang/System.java`、`Runtime.java`
- 系统基建篇（2026-07-27）ch1（16KB）、拓展篇 SELinux、07-29 篇 ch3（hiddenapi）

---

<a id="5"></a>
## 5. Keystore2 每应用密钥限额 + 跨资料环回阻断（A17 安全收紧）

**Q：Android 17 对 Keystore 和跨资料（work profile）流量各加了什么限制？和已有的 Keystore2/Keymint、多用户 Framework 怎么衔接？**

### 答案解析

这是把「系统基建篇（2026-07-27）第 4 章 Keystore2/Keymint」和「多用户模型」往 **A17 安全收紧** 推进的新考点。

**① Keystore 每应用密钥限额（A17 新）：**
- 应用可创建的 **Keystore 密钥数量按 target SDK 受限**（低 target 限额更少），超额**抛异常**。
- 建立在 Keystore2/Keymint（系统基建篇 ch4）之上：`AndroidKeyStore` Provider 创建密钥走 `keystore2` 守护（`system/security/keystore2/`）→ `IKeyMintDevice` HAL；限额在 keystore2 或 `frameworks/base/services/core/java/com/android/server/security/keystore/KeystoreService.java` 侧按 UID 计数。
- 意义：防 App 滥用密钥（如大量生成密钥做指纹追踪/拒绝服务）。

**② 跨资料环回流量默认阻断（A17 新）：**
- 「跨资料（cross-profile）环回（loopback）流量」指工作资料（work profile）与个人资料经 `127.0.0.1`/loopback 互相通信。A17 起**默认不允许**，需显式授权。
- 建立在多用户 Framework（`UserManager`/`UserManagerService`）+ `ConnectivityService`/`Vpn`/`netd` 防火墙之上：`frameworks/base/services/core/java/com/android/server/connectivity/`（ConnectivityService）、`system/netd/` 下发 firewall 规则隔离资料间 loopback。
- 意义：堵住工作资料 App 经本机回环偷连个人资料服务的隐私漏洞。

**③ 限制隐式 URI 授权（A17 新，附带）：** 用 URI 启动 Intent 时，建议显式 `grantUriPermissions` 预分配权限，不再依赖系统自动授予——衔接权限篇（系统基建篇 ch3）的 URI 权限模型。

**④ 衔接：** Keystore2/Keymint（系统基建篇 ch4）讲密钥存 TEE/StrongBox；本限制是「数量管控」层。多用户/work profile 是 Android 多用户模型（`UserHandle`/`UserManagerService`）应用，跨资料环回是网络层加固。

### 易错点
- 「Keystore 限额是存储容量限制」——错。是**每个 App 可创建密钥的数量**上限，按 target SDK 分级，超额抛异常（非磁盘满）。
- 「loopback 阻断只影响真机多用户」——错。work profile（大量企业设备）最受影响；个人/工作 App 经 127.0.0.1 通信会失败。
- 「跨资料流量走公网」——错。原走本机 loopback，阻断后必须走显式授权的跨资料通道或公网。

### 高频追问
- 企业 MDM 怎么管这些？→ `DevicePolicyManager` 可配置跨资料策略、密钥配额豁免。
- URI 授权怎么改？→ `Intent.FLAG_GRANT_READ/WRITE_URI_PERMISSION` 显式授予，或用 `ClipData` 附带授权。
- 和 SELinux 关系？→ 网络隔离由 netd firewall + ConnectivityService 实现，SELinux 管 socket/网络域访问，二者互补。

### 延伸阅读
- `system/security/keystore2/`、`frameworks/base/keystore/`
- `frameworks/base/services/core/java/com/android/server/security/keystore/KeystoreService.java`
- `frameworks/base/services/core/java/com/android/server/connectivity/`、`system/netd/`

---

<a id="6"></a>
## 6. CarService 多用户 / 多显示 / 整车电源（Automotive 深水区）

**Q：车载 Android 怎么支持多用户（司机/乘客）、多显示（仪表/中控/副驾）、整车电源管理？Framework 层和手机的差异在哪？**

### 答案解析

承接「端侧AI与Android17演进篇（2026-07-28）第 6 章 CarService 基础」，本篇钻**多用户/多显示/整车电源**深水区。

**① 多用户（CarUserService）：**
- 车载基于 Android 多用户（`UserManager`/`UserManagerService`），但扩展出 `CarUserService`（`packages/services/Car/service/src/com/android/car/user/CarUserService.java`，`ICarUserService`）。
- 司机/乘客各是**独立全功能 user**，切换由 `CarUserManager`（`packages/services/Car/car-lib`）驱动；HAL 侧 `Vehicle HAL` 上报 `USER_IDENTIFICATION_ASSOCIATION`（钥匙→用户绑定）。
- 与手机差异：手机是单主用户 + 工作资料，车载是**多全功能用户同时切换**（乘客上车即切到其 user）。

**② 多显示（CarOccupantZoneService）：**
- 仪表（Cluster）、中控（Main）、副驾（HVAC/娱乐）是独立 `Display`。`CarOccupantZoneService`（`packages/services/Car/.../occupantzone/`）把「乘员区（occupant zone）」映射到 `Display` + `AudioZone`。
- `CarActivityManager`/WMS 多显示把 Activity 投到指定 display（`DisplayArea` 扩展）。
- 与手机差异：手机单显示为主，车载是**多显示 + 乘员区路由**（07-28 ch5 讲手机大屏 WindowSizeClass，车载是更硬的多显示）。

**③ 整车电源（CarPowerManagementService）：**
- `CarPowerManagementService`（`packages/services/Car/.../power/`）+ `CarPowerPolicy`（`car-lib` `android.car.hardware.power`）管理整车电源状态机：`ON` / `ON_DISP` / `OFF` / `SHUTDOWN` / `SUSPEND`。
- 经 `Vehicle HAL` 的 `AP_POWER_STATE` / `AP_POWER_STATE_REPORT` 属性与硬件协商（熄火→SUSPEND，钥匙拔出→SHUTDOWN）。`CarPowerManager` 给 App 注册电源状态监听。
- 与手机 `PowerManager`/`WakeLock`（系统基建篇 ch10）差异：手机管设备休眠，车载管**整车电气状态**（涉及 CAN/车辆供电）。

**④ 与前面衔接：** Vehicle HAL property 模型（端侧AI篇 ch6）；多显示走 WMS（拓展篇折叠屏 WM）；电源最终影响 WakeLock/Doze（系统基建篇 ch10）。

### 易错点
- 「CarService 多用户 = 手机多用户」——错。车载是**多全功能用户同时切换 + 钥匙绑定**，比手机工作资料复杂。
- 「车载多显示 = 手机双屏」——错。车载是仪表/中控/副驾**功能分离 + 乘员区路由**，不是简单镜像。
- 「车载电源 = WakeLock」——错。整车电源状态机经 Vehicle HAL 协商，WakeLock 只是其中一环。

### 高频追问
- 乘客屏怎么隔离数据？→ 独立 user + 独立 storage/display zone，HAL 上报乘员关联。
- 熄火时 App 怎么收尾？→ `CarPowerManager` 监听 `SHUTDOWN_PREPARE`，release 资源。
- 与 NNAPI（端侧AI篇 ch1）关系？→ 车载 DMS/感知用 NPU，同样走加速器 HAL，且受整车电源状态约束（熄火不能跑重推理）。

### 延伸阅读
- `packages/services/Car/service/src/com/android/car/user/`、`.../occupantzone/`、`.../power/`
- `packages/services/Car/car-lib`（CarUserManager / CarPowerManager / CarOccupantZoneManager）
- `hardware/interfaces/automotive/vehicle/`（AP_POWER_STATE 等 property）

---

<a id="7"></a>
## 7. ART oat/odex/vdex/art 镜像布局深水区（头部结构 + 加载 + deopt 联动）

**Q：`base.odex` / `base.vdex` / `boot.art`（若有）这几个文件头部长什么样？`ClassLinker` 怎么从 oat 里找到并加载一个类的机器码？`dex2oat` 的 compiler filter 怎么决定编译程度？deopt 在什么情况下触发？**

### 答案解析

承接「端侧AI与Android17演进篇（2026-07-28）第 9 章 oat/odex/vdex/art 基础」，本篇钻**头部结构 + 加载流程 + deopt 联动**。

**① OAT 文件头（`art/runtime/oat.h` `OatHeader` + `art/runtime/oat_file.cc` `OatFile`）：**
- 含 magic/version、校验和、指令集（`kThumb2`/`kArm64`/`kX86_64`）、`oat_dex_files`（该 oat 对应哪些 dex）、**方法偏移表**（`OatMethodOffsets`，每个编译方法指向机器码在 .oat 中的 offset）、`boot_image` 引用/patch 信息（链接 boot image）、`ClassOffsets`（类元数据偏移）。
- 加载：`OatFile::Open` 解析头 → 建 `OatDexFile` 索引。

**② VDEX（`art/runtime/vdex_file.h` `VdexFile`）：**
- 存**原始 dex 字节 + VerifierDeps**（验证依赖，加速重编译）。多 oat 可共享一个 vdex。`VdexFile::Open` 解析 `VdexHeader`（magic/version/dex 段大小/verifier deps 大小）。

**③ ART 镜像（boot image）（`art/runtime/image.h` `ImageHeader` + `art/runtime/gc/space/image_space.cc`）：**
- `boot.art`（对象镜像：预创建的核心类对象、String、Class 实例、InternTable 等）+ `boot.oat`（这些类的机器码）。两者**配套**，缺一不可。
- `ImageHeader` 含各 `ImageSection`（`kSectionObjects`/`kSectionArtFields`/`kSectionArtMethods`/`kSectionImTable` 等）偏移、boot image 校验、指向 `boot.oat` 的引用。
- `image_space.cc` 在 Zygote 启动时 `MapBootImage` 把 .art mmap 进 `ImageSpace`，进程 fork 时直接共享——这是 Zygote 加速根因之一（主篇启动流程讲过）。

**④ ClassLinker 加载流程（`art/runtime/class_linker.cc`）：**
- `FindClass`/`DefineClass` → 若有 oat，调 `oat_file->GetOatClass()` 拿 `OatClass` → 读方法偏移 → `LinkCode` 把 `ArtMethod::entry_point_from_quick_compiled_code_` 指向 oat 里的机器码（或 JIT/解释入口）。无 oat（`verify` 过滤）则方法指向解释器。

**⑤ dex2oat compiler filter（`art/dex2oat/dex2oat.cc` `--compiler-filter` + `frameworks/native/cmds/installd/commands.cpp` `dexopt`）：**
- `verify`（只校验，解释执行）/ `quicken`（旧默认，少量热路径）/ `speed-profile`（**普通 App 安装后台按 profile 编译热方法**）/ `speed`（全量）/ `everything`。
- `installd` 在 `dexopt` 时按 `pm compile` 或 profile 触发选择 filter（端侧AI篇 ch9 讲过 profile 采集）。

**⑥ deopt（反优化）触发（`art/runtime/oat_file_assistant.cc` + 07-23 深挖篇 verify/deopt）：**
- 系统 OTA 后 **boot image 变更** → 旧 oat 引用的 boot 偏移失效 → `OatFileAssistant::GetDexOptNeeded` 返回 `kDex2OatForBootImage` 触发重编。
- 安装更新导致依赖变化；`cmd package compile` 强制。
- deopt 本质：把方法从 AOT 机器码降回解释/JIT，**正确性优先于性能**。`vdex`/`art` 校验和不匹配是触发判断依据。

### 易错点
- 「oat 头部只是指向 dex」——错。含方法偏移表、指令集、boot image patch、类偏移等，是完整索引。
- 「vdex 没用」——错。存 dex 字节 + VerifierDeps，加速重编译、去重（多 oat 共享）。
- 「deopt = 性能变差」——对但片面。deopt 是**正确性优先**兜底（boot image 变了必须重编），短暂回退解释后后台按 profile 重新 AOT。
- 「boot.art 和 boot.oat 独立」——二者配套，.art 是对象镜像、.oat 是机器码，ClassLinker 同时加载。

### 高频追问
- 改了 framework 代码为何要重编所有 App oat？→ boot image 偏移变了，App 的 oat 引用失效 → 全量 deopt + 重编（OTA 慢的根因之一）。
- 16KB 页影响 oat 吗？→ oat/art 按页对齐，跨页大小需重新 dexopt（系统基建篇 ch1 已讲）。
- profile 怎么采、存哪？→ ART JIT 采热方法写 `/data/misc/profiles/`（端侧AI篇 ch9 讲过）。

### 延伸阅读
- `art/runtime/oat.h` / `oat_file.cc`、`art/runtime/vdex_file.h`
- `art/runtime/image.h` / `gc/space/image_space.cc`
- `art/runtime/class_linker.cc`、`art/dex2oat/dex2oat.cc`、`art/runtime/oat_file_assistant.cc`

---

<a id="8"></a>
## 8. 查缺补漏 · 易错点速记 · 高频追问 · 九篇交叉索引

### 8.1 本篇 7 章速览
| # | 专题 | 类型 | 核心 AOSP 落点 |
|---|------|------|----------------|
| 1 | SF RenderEngine（GL/Vulkan）+ HWC 合成决策 | 热点·真缺口 | `frameworks/native/libs/renderengine/`、`BufferLayer.cpp`、`HWComposer.cpp` |
| 2 | Codec2 vendor plugin（CCodec→C2Component） | 真缺口 | `frameworks/av/media/codec2/`（core/vndk/sfplugin/components） |
| 3 | A17 Memory Limiter + LMKD + 分代 GC | 热点 | A17 新增；LMKD/PSI（系统基建篇）、ART GC（07-29 ch2） |
| 4 | 安全原生 DCL 加固 | 热点·新雷区 | `bionic/linker/linker.cpp`、`Runtime.java`、16KB/SELinux/hiddenapi 联动 |
| 5 | Keystore 限额 + 跨资料环回阻断 | 热点·安全 | `system/security/keystore2/`、`ConnectivityService`、`system/netd/` |
| 6 | CarService 多用户/多显示/电源 | 真缺口 | `packages/services/Car/.../user|occupantzone|power/` |
| 7 | ART oat/odex/vdex/art 布局深水区 | 真缺口 | `art/runtime/oat.h|vdex_file.h|image.h|class_linker.cc`、`oat_file_assistant.cc` |

### 8.2 易错点速记（背这 7 条）
1. **Vulkan 不替代 SF**——它是 RenderEngine 的 GPU 后端之一；HWC 合成决策层独立。
2. **MediaCodec 不直接调厂商 HAL**——中间经 `CCodec`→`C2ComponentStore`→厂商 `C2Component`。
3. **Memory Limiter ≠ LMKD**——前者按单 App 内存上限限流，A17 新增（A14 无）。
4. **A17 安全 DCL**——dlopen 的 .so 必须只读，可写即 `UnsatisfiedLinkError`；与 16KB/SELinux/hiddenapi 三连击。
5. **Keystore 限额是「数量」非「容量」**——按 target SDK 分级，超额抛异常。
6. **车载多用户 ≠ 手机工作资料**——多全功能用户 + 钥匙绑定；多显示是乘员区路由。
7. **oat/vdex/art 各司其职**——oat 含方法偏移表、vdex 存 dex+VerifierDeps、art 是对象镜像（与 boot.oat 配套）。

### 8.3 高频追问链（面试官常这样往下挖）
1. RenderEngine → Vulkan 后端怎么选？→ client composition 掉帧怎么查（Perfetto）？→ HWUI 与 RenderEngine 边界？
2. Codec2 → 厂商 plugin 写哪几个类？→ C2Work/C2Buffer 怎么零拷贝？→ 与 OMX 区别？
3. Memory Limiter → 和 LMKD/PSI 什么关系？→ 分代 GC 能防泄漏吗？→ ProfilingManager ANOMALY 触发器？
4. 安全 DCL → linker 怎么判可写？→ 和 16KB 一起踩怎么排？→ 老插件化框架怎么活？
5. Keystore 限额 → 和 Keymint 什么关系？→ 跨资料环回阻断实现层？→ MDM 怎么豁免？
6. CarService → CarUserService 怎么切换？→ 多显示怎么路由？→ 整车电源状态机？
7. ART 产物 → oat 头部有哪些表？→ ClassLinker 怎么链机器码？→ 改 framework 为何全量重编？

### 8.4 九篇交叉复习索引（避免重复、规划路径）
- **主篇(16章, 07-23)**：Binder/启动/WMS/View/ANR/LMKD/Compose/HAL/GKI/MTK —— 主线地基。
- **拓展篇(10章, 07-23)**：Input 全链路 / PMS / ART-JIT-AOT / SystemUI / 折叠屏WM / SELinux / OTA-AB / JNI-hook / Binder安全 / Perfetto —— 盲区补强。
- **深挖篇(11章, 07-23)**：ART 对象头/CMC GC / Binder 调试 / Rust Binder / Input 多指 / VSync / Camera HAL / Audio / GKI KMI / Perfetto SQL —— 深水区。
- **图形多媒体通信篇(12章, 07-24)**：HWUI / Choreographer / SF / 图形内存 / 多刷新率 / MediaCodec / Codec2 / Thermal / PowerHAL / RIL / Wi-Fi / BT。
- **系统基建与可观测性篇(11章, 07-27)**：16KB 页 / ClassLoader插件化 / 权限 / Keystore2 / AVB / Vold-FUSE / logd / 可观测性 / RRO / Doze-JobScheduler / A15-16 变更。
- **端侧AI与Android17演进篇(10章, 07-28)**：NNAPI/NPU / LiteRT delegate / A17 NPU声明 / CarService基础 / Vulkan-ANGLE / ART oat布局 / virtual A/B / WindowInsets / WindowSizeClass。
- **A17 新雷区与真缺口篇(8章, 07-29)**：Lock-free MessageQueue / ART 分代GC / hiddenapi+static final / ProfilingManager / 后台音频+通知 / NFC-SE / Media3-ExoPlayer / 端侧LLM(AICore-ODP)。
- **本篇(7章, 07-30)**：SF RenderEngine / Codec2 vendor plugin / Memory Limiter / 安全DCL / Keystore限额+跨资料环回 / CarService多用户多显示电源 / ART oat布局深水区。

**经典题 → 新深度映射（复习时对照答）：**
- 图形/渲染题 → 本篇第 1 章（RenderEngine/Vulkan/HWC 决策）+ 07-28 ch7（Vulkan/ANGLE）
- 多媒体编解码题 → 本篇第 2 章（Codec2 vendor plugin）+ 图形多媒体篇（MediaCodec/Codec2）
- 内存优化题 → 本篇第 3 章（Memory Limiter）+ 系统基建篇 LMKD/PSI + 07-29 ch2（分代GC）
- 插件化/Hook 题 → 本篇第 4 章（安全 DCL）+ 07-29 ch3（hiddenapi）+ 系统基建篇 ClassLoader
- 安全/密钥题 → 本篇第 5 章（Keystore限额/跨资料环回）+ 系统基建篇 ch4（Keystore2/Keymint）
- 车载题 → 本篇第 6 章（多用户/多显示/电源）+ 端侧AI篇 ch6（CarService基础）
- 启动/ART 题 → 本篇第 7 章（oat布局深水区）+ 端侧AI篇 ch9（oat基础）+ 主篇启动流程

### 8.5 后续可轮换的真·未覆盖角度
- **LiteRT NPU delegate 源码走读**（TF-Lite → NNAPI delegate 选择逻辑，端侧AI篇 ch2 只讲了高层）
- **SF RenderEngine Vulkan 后端细节**（本篇 ch1 已开题，可再深：VulkanRenderEngine 的 command buffer/descriptor 管理）
- **Codec2 vendor 组件调试实战**（codec2-info/dump + Perfetto codec2 轨迹）
- **ART hiddenapi 名单生成流水线**（`frameworks/base/config/hiddenapi-*` → 构建期元数据）
- **端侧 LLM 量化工程化**（INT4/INT8、KV-cache 管理、NPU 带宽瓶颈）
- **CarService 电源状态机完整状态图**（AP_POWER_STATE 全状态迁移）

---

> 复习建议：本篇是九篇中「真缺口 + A17 新雷区」密度最高的一篇——RenderEngine/Codec2 vendor/CarService 多用户多显示/ART oat 布局是此前八篇完全没钻的硬骨头，Memory Limiter/安全 DCL/Keystore 限额/跨资料环回是 2026-07 才落地的 A17 新约束。建议按「经典题→新深度映射」对照复习，面试时优先抛 A17 新点 + 真缺口细节建立区分度。九篇合计 **85 个专题**已闭环主线(16)+盲区(10)+深水区(11)+图形多媒体(12)+系统基建(11)+端侧AI(10)+A17新雷区(8)+本篇(7)。
