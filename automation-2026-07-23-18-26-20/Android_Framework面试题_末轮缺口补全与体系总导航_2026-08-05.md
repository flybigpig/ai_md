# Android Framework 面试题 · 末轮真缺口补全 × 热点前瞻 × 体系总导航（第十五篇）

> 日期：2026-08-05 ｜ Baseline：Android 14（UpsideDownCake, android-14.0.0_rXX）
> 定位：前 14 篇已累计 126 专题，闭环 EL0~EL3 四层世界（Binder/AMS/WMS/SF/ART/HAL/内核/TEE/pKVM/Compose/AppFunctions/AAOS/安全深水区）。
> 本篇做三件事：(1) 补齐三个一直挂在计划里、此前 14 篇**完全没讲过**的真缺口——**Codec2 vendor 组件调试实战**、**Kotlin/Compose Multiplatform 在 Android 侧运行时差异（skiko）**、**Robolectric shadow vs Ravenwood 对照**；(2) 锚定当日联网热点——**Android 18 隐式 URI 授权全面收紧 + 桌面模式融合**前瞻；(3) 给出**体系总导航**：14 篇 126 专题交叉索引 + 面试高频连环追问总表，让整套材料可被检索复习。

---

## 本篇速览（4 个新深水区专题 + 1 个总导航）

| # | 专题 | 填补的真缺口 | 关键 AOSP 落点 |
|---|------|--------------|----------------|
| 1 | Codec2 vendor 组件调试实战 | 此前只讲 CCodec 概念，从未拆 vendor 注册/参数协商/异步 work 队列/调试线索 | `frameworks/av/media/codec2/`（core/vndk/sfplugin/components）+ vendor store |
| 2 | Kotlin/Compose Multiplatform Android 侧运行时差异（skiko） | 此前从未讲 KMP 在 Android 的真实运行时 | `org.jetbrains.compose` + `androidx.compose.ui` + Kotlin/JVM→DEX→ART |
| 3 | Robolectric shadow vs Ravenwood 对照 | 8/2 只提 Ravenwood 跑真身，未与 Robolectric 系统对照 | `frameworks/base/ravenwood/` + `org.robolectric:android-all` |
| 4 | Android 18 前瞻：隐式 URI 授权收紧 + 桌面融合 | 衔接 8/3 的 A17 URI 授权，向前看 A18 | `StrictMode.detectImplicitUriPermissionGrant` + WMS/WindowOrganizer |
| 5 | 体系总导航：126 专题交叉索引 + 高频追问总表 | 14 篇散落，缺统一检索入口 | 全系列文件名映射 + 跨篇追问链 |

---

# 专题一：Codec2 vendor 组件调试实战

### 1.1 面试问题
> Android 12 起 MediaCodec 底层从 OMX（ACodec）切到 Codec2（CCodec）。厂商（高通/MTK/三星）的硬件编解码器是怎么以「vendor 组件」接入 Codec2 框架的？`CCodec` 是怎么发现并加载 vendor 的 `C2Component` 的？线上出现解码花屏 / 卡死 / stalled，你从哪些线索入手 dump 与调试？vendor 组件注册、参数协商、异步 work 队列的底层机制是什么？

### 1.2 答案解析与底层原理

**框架拓扑（调用链起点到 vendor）**
```
App (MediaCodec.java)
  -> native MediaCodec.cpp (configure/start/dequeueInputBuffer)
    -> CCodec.cpp  (sfplugin, 新播放管线的生产者-消费者适配层)
      -> Codec2 接口 (C2Component / C2ComponentStore)
        -> vendor 实现 (libcodec2_vendor.so / 厂商 store + 硬件驱动)
```
- Java 入口：`frameworks/base/media/java/android/media/MediaCodec.java`。
- Native 播放管控：`frameworks/av/media/libstagefright/MediaCodec.cpp`（`MediaCodec::configure()` / `start()` / `dequeueInputBuffer()`）。
- Codec2 适配层：`frameworks/av/media/codec2/sfplugin/CCodec.cpp` + `CCodecBufferChannel.cpp`。这是 OMX 的 `ACodec` 的替代者。

**Vendor 组件注册与发现**
- Codec2 用 **ComponentStore** 抽象「一组组件」。系统通过 `C2ComponentStore::Create()` 加载。
- 传统（非 isolation）路径：`frameworks/av/media/codec2/vndk/C2ComponentStore.cpp` 的 `Create()` 通过 `dlopen` 加载 vendor 提供的 store 库（如 `libcodec2_vendor.so` / `libcodec2_softstore.so`），再调用其导出的工厂 `CreateCodec2ComponentStore()` / `GetCodec2ComponentStore()`。
- 组件清单来自 vendor 的 **`media_codecs.xml`**（`/vendor/etc/media_codecs.xml` 叠加 `/etc/media_codecs.xml`），由 `MediaCodecList`（`frameworks/av/media/mediaplayer/MediaCodecList.cpp`）解析。每个 `<MediaCodec>` 节点带 `type` / `rank` / `vendor` / `optional` 属性，并指向底层 Component 名（如 `c2.vendor.qti.avc.decoder`）。
- `CCodec` 在 `CCodec::CreateComponent()`（sfplugin/CCodec.cpp）里，通过 `mClient->createComponentByName(name, ...)` 经 **Codec2 客户端**（component store 的 in-process 实例，或走 `IComponentStore` AIDL HAL）实例化 `C2Component`。

**Vendor 组件接口（`frameworks/av/media/codec2/core/` + `components/`）**
- `C2Component` 接口：`start()` / `stop()` / `reset()` / `queue()`（提交 `C2Work`）/ `flush()` / `release()`。
- `C2Work`（`C2Work.h`）：一个待处理「帧任务」，含 `input`（`C2BufferPack` / `C2FrameData`）+ `worklets`（输出占位）。`C2FrameData` 持有 `C2Buffer`（包装 `C2GraphicBuffer` / `C2LinearBuffer`，底层是 `GraphicBuffer` / `ABuffer`）。
- **异步队列**：`C2Component` 通过 `setListener()` 注册 `C2Component::Listener`，`onWorkDone()` 回调把完成的 `C2Work`（含输出 buffer）回传。`CCodec` 在自己的 `CCodec::mCallback` 里把这些 workdone 转成 `MediaCodec` 的 `BufferCallback`（onOutputBufferAvailable）。关键点：**CCodec 是 producer-consumer 双向队列**——input 走 `CCodecBufferChannel`，output 经 listener 回调，全程不阻塞解码线程。

**参数协商（Settings / Params）—— 这是 90% "配置无效" 的根因**
- `C2Component` 的所有可调参数都是 **`C2Param`**（类型安全的 POD + 头部 `C2Param::CoreIndex`）。例如 `C2PortActualFormatSetting`（设色彩格式）、`C2StreamBitrateInfo`（设码率）、`C2PortMediaTypeSetting`、`C2StreamMaxBufferSizeInfo`。
- 查询能力：`query()` / `querySupportedParams()` / `querySupportedValues()`。
- 配置：`config()` 提交 `C2Param` 列表。**vendor 不支持的参数会返回 `C2_NOT_SUPPORTED` / `C2_BAD_VALUE` 而非崩溃**——很多「配置后无效果 / 偶发失败」就是硬 config 了 vendor 不支持的 param，必须先用 `querySupportedParams()` 拉全量支持列表再配。

**调试实战（面试官最爱的「实操线索」）**
1. **组件清单第一手**：`adb shell codec2-info`（`frameworks/av/media/codec2/tools/codec2info/`）——列出所有 store + 组件 + 支持的 MIME + 参数。直接看 vendor 到底注册了哪些组件、支持哪些 param。
2. **codec 进程 dump**：`adb shell dumpsys media.codec`、`dumpsys media.player`、`dumpsys media.resource_manager`。看组件状态、当前 pending 的 `C2Work`、stall 计数、buffer 占用。
3. **logcat 关键 tag**：`android.media.codec2`（CCodec / Codec2Config）、`MediaCodec`、`CCodecBufferChannel`。加 `-b all` 抓全缓冲。
4. **vendor 专属 dump**：很多 OEM 在 `libcodec2_vendor.so` 里实现 `C2ComponentStore::dump()`；配合 `c2status` / `service call media.codec`。
5. **stall / 花屏定位**：
   - stall 大半是 vendor `onWorkDone` 不回调（死锁 / 队列满）→ 看 `CCodec::onWorkDone` 是否触发、`C2Allocator` 分配是否失败（`C2_NO_MEMORY`）。
   - 花屏大半是 `C2GraphicBuffer` 的 `GRALLOC_USAGE_*` 不匹配、`C2ColorAspect` / `C2PixelFormat` 协商错、或 stride / pixel-layout 与 vendor 不一致（查 `C2StreamPixelAspectRatioInfo` / `C2PlaneLayout`）。
6. **帧时间戳乱**：`C2FrameData::flags`（`FRAME_FLAG_INCOMPLETE` / `DROP_FRAME`）、`C2WorkOrdinal`（timestamp / frameIndex）错乱 → `CCodec` 报 `C2_BAD_VALUE` 或 `MediaCodec.ERROR_INSUFFICIENT_RESOURCE`。
7. **OMX→Codec2 迁移坑**：部分 OEM 旧 HAL 仍走 OMX（`ACodec`）；`MediaCodec` 默认优先 Codec2，可用 `debug.stagefright.ccodec` 关掉回退 OMX。Android 14 起 framework 层 OMX 基本移除，仅 vendor 旧实现残留。

### 1.3 易错点速记
- 把 Codec2 的「store」等同于「一个解码器」——错，一个 store 含多个组件（不同 MIME / profile / rank）。
- 以为 `config()` 一定成功——必须先 `querySupportedParams()` 再配，否则 vendor 静默返回 NOT_SUPPORTED。
- `C2Work` 是**复用对象**，回调后 framework 会 reuse；vendor 不能在 `onWorkDone` 后继续持有其 buffer 指针（悬挂引用 → 花屏 / 崩溃）。
- 混淆 `GraphicBuffer` / `C2GraphicBuffer` 与 `Surface` 的 slot 映射——解码输出最终要靠 `BufferQueue` 的 slot 与 `Surface` 对接（`CCodecBufferChannel::outputBuffer`）。

### 1.4 高频追问链
- CCodec 和 ACodec 的区别？→ ACodec 走 OMX 状态机（Loaded/Executing…），CCodec 走 Codec2 的 `C2Component` + 异步 work 队列；OMX 已 deprecated。
- 为什么要有 ComponentStore 这层抽象？→ 隔离 framework 与 vendor（支持多 store / 软件 vs 硬件 / hotplug），便于 Treble（codec2 也可走 `IComponentStore` AIDL HAL）。
- codec2 能跨进程吗？→ 能，`IComponentStore` HAL（media/codec2 aidl），由 `mediaserver` 承载；也可 in-process dlopen（legacy）。
- 编码首帧慢怎么查？→ `C2Work` pending 数、`query()` 参数协商耗时、`C2Component::start()` 内 vendor 初始化耗时。

### 1.5 延伸阅读
- 与 7/24「MediaCodec 状态机 + Codec2(CCodec) vs OMX(ACodec)/C2Work/C2Buffer」缝合；与 8/4「LiteRT NPU delegate」对照（NPU 走 NNAPI HAL，codec 走 Codec2）。
- AOSP 入口：`frameworks/av/media/codec2/`（core / vndk / sfplugin / components）、`frameworks/av/media/libstagefright/MediaCodec.cpp`、`frameworks/av/media/mediaplayer/MediaCodecList.cpp`。

---

# 专题二：Kotlin/Compose Multiplatform 在 Android 侧运行时差异（skiko / 字节码 / ART）

### 2.1 面试问题
> 现在都在推 Kotlin Multiplatform / Compose Multiplatform。一个 KMP 工程在 Android 上跑，它的代码编译成什么、跑在谁的运行时上？**skiko 在 Android 上会用吗？** `expect/actual` 在 `androidMain` 里最终调的是什么？Compose Multiplatform 和 Jetpack Compose 在 Android 上是一回事吗？为什么说「KMP 在 Android 侧没有额外 runtime」？

### 2.2 答案解析与底层原理

**KMP 的编译目标矩阵（核心认知）**
- Kotlin Multiplatform 把 `commonMain` 共享代码，按 target 编译成**不同后端产物**：
  - `androidMain` / JVM target → **Kotlin/JVM 编译器 → JVM 字节码 → DEX（经 d8/R8）→ 跑在 ART 上**。和「纯 Android 应用」是**同一个运行时**，没有任何额外 runtime / layer。
  - `iosMain` → **Kotlin/Native → LLVM → 机器码**，跑在 iOS 原生运行时（与 Objective-C runtime 互操作）。
  - `jvmMain`（桌面）→ Kotlin/JVM → 普通 JVM。
  - `jsMain` / `wasmJsMain` → Kotlin/JS → JS / WASM。

**关键结论（面试必答）**：**Compose Multiplatform 在 Android 上 = Jetpack Compose**。Android target 用的就是 `androidx.compose.*`（同 Jetpack Compose），编译进同一个 APK 的 DEX，跑在 ART。所谓「跨平台」只发生在：共享的 `commonMain` 业务逻辑 + Compose UI 声明，被 Kotlin 编译器针对不同 target 分别编译；**Android 端产物就是标准 ART DEX，不存在第二个 UI runtime、也不存在第二个 GC**。

**skiko 的角色边界（最易被误解的点）**
- `skiko` = Skia 的 Kotlin 绑定，是 **Compose UI 在非 Android 平台（桌面 JVM / iOS 经 Skiko-Native / WASM）上的渲染后端**——它用 Skia 画到 Surface / Canvas，替代各平台原生 UI toolkit。
- **在 Android 上，Compose UI 不使用 skiko**。Android 端 Compose 渲染走 `AndroidComposeView` → `RenderNode` / `Canvas`（HWUI / Skia 经 Android 的 `RenderThread` / `HardwareRenderer`），与 Jetpack Compose 完全一致。skiko 在 Android 依赖里是 `compileOnly` / 不打包，或仅作为 `expect` 在桌面侧的 `actual`。
- 一句话：**skiko 解决「桌面/iOS 没有 HWUI」的问题；Android 天生有 HWUI，所以不需要 skiko。**

**expect / actual 的 Android 落地**
- `expect class PlatformLogger`（`commonMain`）→ `actual class PlatformLogger`（`androidMain`）里直接 `android.util.Log.d(...)`。即 actual 实现就是普通 Android SDK 调用，编译进 DEX，**没有桥接开销**——Kotlin 编译器在编译期就把 actual 解析/内联掉。
- 注意 `actual` 不能「部分 expect」、`expect` / `actual` 签名必须一致；`androidMain` 能访问完整 Android SDK，可自由用 `@OptIn(ExperimentalMaterial3Api::class)` 等 AndroidX API。

**与 A17 Compose-First 的咬合**
- A17 官宣 Compose-First（新 API 只面向 Compose；`android.widget` / RecyclerView / Fragment 进入 maintenance）。KMP 的 Android target 天然享受 Jetpack Compose，**「KMP app 在 Android 上」直接吃到 Compose-First 红利，无需任何迁移**——这正是 Google 推 KMP 的底层动机之一（见 8/3 智能系统主线）。

### 2.3 易错点速记
- 以为 KMP 在 Android 上会引入 Kotlin/Native 运行时——**错**，Android target 永远是 Kotlin/JVM + ART。
- 以为 Compose Multiplatform 在 Android 上「另起一套 UI 框架」——错，就是 Jetpack Compose。
- 以为 skiko 在 Android 参与渲染——错，Android 走 HWUI / RenderNode；skiko 只在非 Android target。
- 混淆 `commonTest` / `androidUnitTest`（跑在 JVM / Ravenwood）与 iOS test（跑在 simulator / Native）。

### 2.4 高频追问链
- KMP 和 Flutter 在 Android 的运行时差异？→ Flutter 自带 Dart VM / AOT + Skia / Impeller 引擎（独立 runtime，不共享 ART 的 UI 栈）；KMP Android 端纯 ART + HWUI，无额外引擎。
- `commonMain` 能直接调 Android API 吗？→ 不能，必须经 `expect/actual`，否则破坏跨平台契约；Android 专属逻辑放 `androidMain`。
- KMP 在 Android 上的包体积 / 启动影响？→ 共享代码多编译一份 DEX（与手动写两份代码体积相当），但无额外 runtime；相比 Flutter 少一个引擎 so。
- Compose Compiler 插件在 KMP 下的角色？→ Kotlin 2.x 起 Compose 编译器作为独立 compiler plugin，对所有 target 统一注入 `$composer` / `$changed`（见 8/3 专题三）。

### 2.5 延伸阅读
- 与 8/3「Compose 编译器插件 / Compose 运行时（SlotTable / Snapshot / Recomposer）/ Compose↔Framework 六接缝」缝合。
- 入口：`org.jetbrains.compose`（Compose Multiplatform）、`kotlin-multiplatform` plugin、`androidx.compose.ui:ui-android`（Android target 实际依赖）。

---

# 专题三：Robolectric shadow vs Ravenwood 对照（Framework 单测双雄）

### 3.1 面试问题
> 给 Android Framework / 系统 app 写单元测试，Robolectric 和 Ravenwood 怎么选？Robolectric 的「shadow」是什么、为什么它会和真机行为漂移？Ravenwood 为什么敢说自己跑的是「真 AOSP 代码」？两者在 Binder、UI、覆盖率、维护成本上怎么取舍？

### 3.2 答案解析与底层原理

**Robolectric（老牌，shadow 派）**
- 机制：JUnit 跑在 **JVM**，通过 **classloader 插桩**（`org.robolectric:android-all` 提供 `android.jar` 的「shadow 实现」）替换 `android.*` / `java.*` 里的 framework 类。所谓 **shadow**（如 `ShadowActivity` / `ShadowLooper` / `ShadowApplication`）是 Robolectric **手写的一份「假 framework」**——方法体是模拟实现（`ShadowLooper` 直接控制消息泵，`ShadowBitmap` 用简单像素数组）。
- `@Config(sdk=...)` 选 shadow 版本；`RuntimeEnvironment` 提供环境查询。
- 优点：快（秒级）、无需 device / emulator、能做「点击→状态变更」的 UI 逻辑测试、生态成熟。
- 缺点：**fidelity 天然有损**——shadow 是人肉复刻 framework 行为，AOSP 一改就容易漂移（如 `Looper` 在 A17 改成 lock-free MessageQueue，shadow 还在模拟旧链表泵）；很多 API 没 shadow 或行为不全；native 代码（SurfaceFlinger / Binder 驱动）根本没跑。

**Ravenwood（新，Android 14 起，真身派）**
- 机制：在 **host JVM 上跑「真 AOSP 代码」**（`frameworks/base/ravenwood/`），不替换 framework，而是把 `frameworks/base` 的 Java 代码（含 `android.*` / `com.android.*` 真实现）连同 **host 版 ART subset** 一起加载执行。`Handler` / `Looper` / `ContentProvider` / `SharedPreferences` / Binder 都是**真身**。
- 支持 `@RavenwoodClassloaders` / `@RavenwoodTestRunner`；AndroidX 大量测试已迁 Ravenwood（`device-side` vs `host-side(ravenwood)` 双 runner）。
- 优点：**fidelity 高**——测的就是 framework 真实现，行为不会漂移；native 部分用 host stub 但仍比 shadow 准；Google 主推，是 AndroidX / Platform 单测的未来。
- 缺点：覆盖面仍在补齐（部分依赖 HAL / 系统服务的 API 还不能 host 跑）、首次跑需下载 Ravenwood runtime、生态比 Robolectric 年轻。

**对照表**

| 维度 | Robolectric | Ravenwood |
|---|---|---|
| 运行时 | JVM + shadow「假 framework」 | host JVM + 真 AOSP 代码 |
| Fidelity | 中（手写复刻，易漂移） | 高（真身） |
| 速度 | 快 | 快（首次略慢） |
| Binder / 系统服务 | 不支持（或 shadow 模拟） | 真 Binder host 实现可用 |
| UI 渲染 | shadow 模拟 View 树逻辑 | 不渲染像素，但真 View 测量/布局逻辑可跑 |
| Native（SF / 驱动） | 无 | host stub |
| 维护成本 | 高（跟 AOSP 改动） | 低（framework 自带） |
| 适用 | 老项目 / UI 逻辑快测 | 新项目 / 高保真 framework 单测 |

**怎么选**：新代码优先 Ravenwood（保真、Google 主线）；存量 Robolectric 老测试不必强行迁；UI 像素级 / 交互逻辑仍可用 Robo（Ravenwood 不渲染像素）。**两者都能跑在 CI 的 JVM，不需要 device。**

### 3.3 易错点速记
- 以为 Robolectric 跑的是真 framework——错，是 shadow 复刻。
- 以为 Ravenwood 能测 SurfaceFlinger 真实合成——错，native 渲染仍 host stub，只保证 Java 逻辑真。
- 在 Robolectric 里 `Looper` 行为直接等于真机——A17 lock-free 后已不等。
- 把 `@Config(sdk=34)` 当成「覆盖所有 A14 行为」——shadow 覆盖率有限。

### 3.4 高频追问链
- Ravenwood 为什么不用 shadow？→ 它直接加载 framework 真 .class，靠 host ART subset 执行，不需要替换。
- Robolectric 的 shadow 怎么生效？→ 自定义 classloader + `@Implements` 注解 + `RobolectricTestRunner` 在加载 `android.*` 类时换成 shadow 类。
- 两者能混用吗？→ 一般不能混同一测试；按模块选其一；AndroidX 提供 `device-side` / `host-side` 双 runner。
- 和 8/2 的 Ravenwood 衔接？→ 8/2 已讲 Ravenwood 跑 AOSP 真身而非 shadow，本篇补全与 Robo 的对照。

### 3.5 延伸阅读
- 与 8/2 专题八「Ravenwood（frameworks/base/ravenwood/，跑 AOSP 真身而非 shadow）+ CTS / VTS / GTS / MTS / CTS-V / STS 矩阵」缝合。
- 入口：`frameworks/base/ravenwood/`、`org.robolectric:robolectric`、`androidx.test:core`（host-side runner）。

---

# 专题四：近期热点前瞻（Android 18 / QPR2 行为变更）—— 向前看

> 联网锚定（2026-08-05）：Android 17（CinnamonBun）已于 2026-06-16 stable；Android 17 QPR2 预计 2026-12；Android 18 路线图聚焦「桌面融合 + 隐私收紧」。以下为框架面试需提前布局的增量考点。

### 4.1 Android 18 全面收紧「隐式 URI 授权」
- A17 已限制部分隐式 URI 授权（见 8/3 专题八 `UriGrantsManagerService`）；**Android 18 起，对 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` / `ACTION_IMAGE_CAPTURE` 携带的 URI，系统「不再自动」向目标授予读写权限**。开发者必须显式 `takePersistableUriPermission()` / `grantUriPermission()`，否则目标 `openInputStream` 抛 `SecurityException`（经典坑：分享图片后对方读不到）。
- 调试：`StrictMode.VmPolicy.Builder().detectImplicitUriPermissionGrant().penaltyLog()`，日志含 `Please set the grant explicitly in the app`。
- 面试落点：与 8/3 的 `UriGrantsManagerService`（`urigrants.xml` LRU 上限、授权不可传递防 confused deputy）串成「URI 授权演进线」。

### 4.2 Android 18 桌面融合（Desktop Mode as PC）
- 路线图：Android 17 引入原生桌面模式雏形（taskbar / 自由窗口 / 外接键鼠高级配置），**Android 18 是「移动即 PC」的整合之年**——接显示器即出真实桌面（任务栏、可停靠浮动窗、跨应用拖 content）。这对 WMS / WindowOrganizer / TaskFragment（见 7/23 拓展篇「折叠屏/多窗口 WM」）是增量考点。
- 通用剪贴板（Universal Clipboard）+ 跨设备接力（Cross-device Handoff，见 7/31 专题五 `CDM` / `Handoff`）是同一「设备融合」主线。

### 4.3 仍在发酵的 A17 雷区（复习锚点）
- Memory Limiter（`ApplicationExitInfo.getDescription()` 含 `MemoryLimiter:AnonSwap`，见 8/2 / 8/3）、Lock-free MessageQueue（7/29）、ART 分代 GC（7/29 / 8/30）、hiddenapi 封死静态 final（7/29 / 7/31）、`usesCleartextTraffic` 弃用计划（7/31 / 本次 web 确认）。

---

# 专题五：体系总导航 —— 14 篇 126 专题交叉索引 + 面试高频连环追问总表

> 目的：让前 14 篇散落的 126 专题变成可被检索的复习地图。下面按「模块」聚合，并给出跨篇蒸馏的追问链。

## 5.1 模块总索引（126 专题 → 14 篇映射）

| 模块 | 涉及篇目（日期） | 专题数 | 关键落点（一句话） |
|---|---|---|---|
| 启动 / 进程 / IPC 基座 | 主篇(7/23) + 拓展篇(7/23) + 深挖篇(7/23) | ~37 | Binder 驱动一次拷贝/mmap/线程池/TTLE、Zygote socket、冷启动、AMS/ATMS oom_adj、WMS/SF、View 三部曲、ANR、LMKD/PSI、Binder 安全 |
| 图形 / 多媒体 / 通信 | 图形多媒体通信篇(7/24) | 12 | HWUI/DisplayList/RenderThread、Choreographer/VSync offset、SF/BufferQueue/HWC、Gralloc/DMA-BUF/fence、多刷新率、MediaCodec/Codec2、Thermal/Power HAL、Telephony/RIL、Wi-Fi、BT |
| 系统基建 / 安全 / 可观测 / 版本 | 系统基建篇(7/27) | 11 | 16KB 页面、ClassLoader/插件化、权限、Keystore2/Keymint、Verified Boot/AVB、Vold/FUSE、logd、性能可观测、RRO/Overlay、Doze/JobScheduler/WakeLock、A15/16 变更 |
| 端侧 AI / Android 17 演进 | 7/28 篇 + 8/4 篇 | 16 | NNAPI/NPU、LiteRT delegate、CarService、Vulkan/ANGLE、ART 产物、virtual A/B、端侧 LLM 量化、AAOS 电源、StrongBox/SE、Protected Confirmation、AVF 隔离编译 |
| A17 新雷区 | 7/29 + 7/30 + 7/31 | 25 | Lock-free MQ、ART 分代 GC、hiddenapi、ProfilingManager、后台音频/通知、NFC/SE、Media3/ExoPlayer、SF RenderEngine、Memory Limiter、compat 框架、letterbox/BAL/Bubbles、Pointer Capture、SMS OTP、ECH、SQLite strict |
| 安全世界（TEE / pKVM） | 8/1 + 8/2 | 16 | Trusty/TIPC、Keystore2/KeyMint、Gatekeeper/Weaver、Key Attestation/RKP/DICE、Widevine DRM、ION→DMA-BUF、pKVM/AVF/AISeal、Connectivity eBPF、Ravenwood |
| 智能系统层 | 8/3 | 9 | AppFunctions、AppSearch/Icing、Compose 编译器/运行时、Compose↔Framework 接缝、APK 签名 v1→v3.2、ApplicationExitInfo、系统托管 UI/UriGrants、无障碍语义树 |
| 深水区 + 总导航 | 本篇(8/5) | 4 | Codec2 vendor 调试、KMP/skiko、Robolectric vs Ravenwood、A18 前瞻 |

## 5.2 14 篇速查表（文件名 + 一句话定位）

1. `Android_Framework面试题_2026-07-23.md`（主篇，16 章）：Binder/Handler/启动/AMS/ATMS/WMS/View/ANR/HAL/GKI/MTK 主线。
2. `Android_Framework面试题_热点拓展_2026-07-23.md`（拓展篇，10 章）：Input 全链路、PMS、ART/JIT/AOT、SystemUI、折叠屏 WM、SELinux、OTA/AB、JNI/hook、Binder 安全、Perfetto 实战。
3. `Android_Framework面试题_深挖篇_2026-07-23.md`（深挖篇，11 章）：ART 对象头/CMC GC、Binder 驱动调试、Rust Binder、Input 多指、VSync 时序、Camera HAL、Audio、GKI KMI、Perfetto SQL。
4. `Android_Framework面试题_图形多媒体通信篇_2026-07-24.md`（12 章）：HWUI/SF/MediaCodec/Codec2/Thermal/Power/Telephony/RIL/Wi-Fi/BT。
5. `Android_Framework面试题_系统基建与可观测性篇_2026-07-27.md`（11 章）：16KB、ClassLoader、权限、Keystore2、AVB、Vold、logd、可观测、RRO、Doze。
6. `Android_Framework面试题_端侧AI与Android17演进_2026-07-28.md`（10 章）：NNAPI/NPU、LiteRT、CarService、Vulkan、ART 产物、virtual A/B、A16/A17 变更。
7. `Android_Framework面试题_2026-07-29.md`（8 章）：A17 Lock-free MQ、ART 分代 GC、hiddenapi、ProfilingManager、后台音频/通知、NFC/SE、Media3、端侧 LLM。
8. `Android_Framework面试题_渲染合成与A17安全内存_2026-07-30.md`（7 章）：SF RenderEngine、Codec2 vendor plugin、Memory Limiter、安全 DCL、Keystore 限额、CarService 多用户。
9. `Android_Framework面试题_兼容性框架与A17跨设备窗口隐私_2026-07-31.md`（10 章）：compat 框架主轴、letterbox、BAL、Bubbles、Handoff、Pointer Capture、SMS OTP、ECH、SQLite strict、hiddenapi 流水线。
10. `Android_Framework面试题_安全世界TEE与A17架构级安全内存_2026-08-01.md`（8 章）：Trusty/TIPC、Keystore2/KeyMint、Gatekeeper/Weaver、Key Attestation、Widevine、硬件封装密钥、ION→DMA-BUF。
11. `Android_Framework面试题_pKVM机密计算与A17_AISeal_2026-08-02.md`（8 章）：pKVM、AVF、AISeal、跨世界通信、三条杀路径、Connectivity eBPF、Ravenwood。
12. `Android_Framework面试题_智能系统AppFunctions与ComposeFirst_2026-08-03.md`（9 章）：AppFunctions、AppSearch、Compose 编译器/运行时、APK 签名、ApplicationExitInfo、系统托管 UI、无障碍语义树。
13. `Android_Framework面试题_端侧AI工程化与AAOS座舱_2026-08-04.md`（6 章）：LiteRT NPU delegate、端侧 LLM 量化、CarService 电源状态机、StrongBox/SE、Protected Confirmation、AVF 隔离编译。
14. `Android_Framework面试题_末轮缺口补全与体系总导航_2026-08-05.md`（本篇，4 章 + 总导航）：Codec2 vendor 调试、KMP/skiko、Robolectric vs Ravenwood、A18 前瞻、总索引。

## 5.3 面试高频连环追问 TOP 实战（跨篇蒸馏）

**链 1 · IPC / 身份信任链**
Binder 一次拷贝(mmap) → `getCallingUid` 谁填（内核） → 「Provider 侧 `getCallingUid` 拿到 `SYSTEM_UID` 不可信」（8/3 AppFunctions + 8/2 跨 pVM） → `clearCallingIdentity` / `restoreCallingIdentity`（7/23 拓展） → oneway 占满线程池 → `BinderCallsStats`/`binder_transaction_log`（8/2）。

**链 2 · 进程死因三条路径**（8/2 / 8/3）
内核 OOM killer / LMKD（PSI） / A17 Memory Limiter 个体超标静默杀 —— 区分靠 `ApplicationExitInfo.getReason()` + `getImportance()`。

**链 3 · 渲染不掉帧链**
Choreographer VSYNC → RenderThread / DisplayList → SF BufferQueue / HWC overlay vs GPU → 同步屏障跳过非必要 task（主篇） → A17 Lock-free MQ 减少丢帧（7/29）。

**链 4 · Compose 性能链**（8/3）
`$changed` 位掩码强跳过 → stability 推断 → Recomposer 挂 ANIMATION 回调同帧先后 → SubcomposeLayout 强制单遍测量 → 语义树对 AI Agent 友好。

**链 5 · 内存三条杀 + GC 演进**
LMKD / PSI → CMC（userfaultfd，7/23 深挖） → A17 分代 GC（7/29） → Memory Limiter（8/2） → `onTrimMemory`（仅两常量自 A14）。

**链 6 · 安全边界递进**
普通世界（Binder/AMS） → EL3 Trusty TEE（8/1） → EL2 pKVM/AVF（8/2） → 系统托管 UI / 可信 UI 三等级（8/3） → 端侧 AI 进 pVM（AISeal，8/2）。

**链 7 · 版本行为变更引擎**
compat 框架（`@ChangeId` / `DisabledCompatChanges`，7/31）是「所有 targetSdk 变更的总开关」 → 各子系统实例（letterbox / BAL / SMS OTP / ECH / hiddenapi / URI 授权 / 桌面模式）。

## 5.4 复习建议（按岗位）

- **应用层 / 性能岗**：主篇 + 图形多媒体篇 + 系统基建篇 + 智能系统篇（Compose / AppFunctions） + 本篇 KMP。
- **Framework / 系统开发岗**：全部，重点 Binder 驱动（深挖篇） + TEE / pKVM（8/1 / 8/2） + compat 框架（7/31） + 本篇 Codec2。
- **座舱 / 端侧 AI 岗**：7/28 + 8/4（AAOS / CarService / NNAPI / LiteRT） + 智能系统篇 + 本篇。

---

## 本篇小结
- 今日新增 4 个深水区专题（Codec2 vendor 调试、KMP/skiko、Robolectric vs Ravenwood、Android 18 前瞻），全系列累计约 **130 专题 / 15 篇**。
- 三个此前完全未覆盖的真缺口（Codec2 vendor / KMP / Robolectric）今日补齐；Robolectric vs Ravenwood 与 8/2 的 Ravenwood 形成完整对照。
- 体系总导航把 14 篇 126 专题按模块聚合 + 给出 7 条跨篇追问链，便于考前定向复习。
- 剩余零散真缺口仅剩：CarService 电源状态图细化（hibernation / 多显示时序）、端侧 LLM 量化实操脚本。
