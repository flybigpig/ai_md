# Android Framework 面试题 · 图形/多媒体/电源热控/通信篇（2026-07-24）

> 第五篇 · 承接主篇(16章)/拓展篇(10章)/深挖篇(11章)，本篇轮换到此前**完全未覆盖**的角度：
> **图形渲染全栈（HWUI→SurfaceFlinger→HWC→Display）、多媒体编解码（MediaCodec/Codec2）、电源与热控（PowerHAL/ThermalHAL）、通信连接（Telephony/RIL、Wi-Fi、Bluetooth）**。
> AOSP 基线：**Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX)**，内核 GKI `android14-6.1`。
> 面试定位：这些是「系统/平台/性能岗」的进阶追问区，能答出 buffer 生命周期与跨进程零拷贝，基本就把面试官镇住了。

---

## 目录
1. [硬件加速渲染：DisplayList / RenderNode / RenderThread](#1)
2. [Choreographer 与 VSync：一帧的精密调度](#2)
3. [SurfaceFlinger 合成：BufferQueue / Layer / HWComposer](#3)
4. [图形内存：Gralloc / ION → DMA-BUF / GraphicBuffer](#4)
5. [多刷新率与 DisplayManager（LTPO/帧率投票）](#5)
6. [MediaCodec 状态机与 Buffer 队列](#6)
7. [Codec2 (CCodec) vs OMX (ACodec)：编解码底层](#7)
8. [Thermal HAL：热控降频全链路](#8)
9. [Power HAL 与 CPU 调度提示（ADPF/hint）](#9)
10. [Telephony / RIL 框架](#10)
11. [Wi-Fi 框架架构](#11)
12. [Bluetooth 协议栈（Gabeldorsche）](#12)
13. [查缺补漏 · 易错点 · 高频追问 · 延伸阅读](#13)

---

<a id="1"></a>
## 1. 硬件加速渲染：DisplayList / RenderNode / RenderThread

**Q：一次 `invalidate()` 到 GPU 出图，UI 线程和 RenderThread 各做了什么？两者的同步点在哪？**

**答案解析：**

Android 4.0 引入硬件加速，5.0 引入独立 **RenderThread**，把「录制绘制命令」和「执行 GPU 命令」拆成两个线程：

- **UI 线程**：只负责 `measure/layout/draw`，`draw` 阶段并不真正画像素，而是把绘制操作**录制**成 `DisplayList`（挂在每个 View 的 `RenderNode` 上）。
- **RenderThread**：把 `RenderNode` 树翻译成 GL/Vulkan 命令提交给 GPU，并管理 `BufferQueue` 的生产者端（back buffer 渲染 + swap）。

**关键源码路径（Android 14）：**
- `frameworks/base/core/java/android/view/ViewRootImpl.java` → `performTraversals()` 末尾 `performDraw()` → `draw()` → `mThreadedRenderer.draw()`。
- `frameworks/base/graphics/java/android/graphics/RenderNode.java`、`android/view/ThreadedRenderer.java`（Java 门面）。
- Native HWUI：`frameworks/base/libs/hwui/` —— `RenderNode.cpp`、`DisplayList.cpp`、`renderthread/RenderThread.cpp`、`renderthread/CanvasContext.cpp`、`renderthread/DrawFrameTask.cpp`。
- 后端：`frameworks/base/libs/hwui/pipeline/skia/` —— `SkiaOpenGLPipeline.cpp` / `SkiaVulkanPipeline.cpp`（HWUI 现在统一走 **Skia** 后端，`ro.hwui.use_vulkan` 控制 GL/Vulkan）。

**同步点（重点，高频追问）：** `ThreadedRenderer.draw()` → `nSyncAndDrawFrame()` → HWUI `DrawFrameTask::run()`。在 **sync 阶段** UI 线程会把 dirty 的 `RenderNode` 的 `stagingDisplayList` 推给 RenderThread 的 active 副本，这一步需要**短暂锁 UI 线程**（`prepareTree`）。同步完成后 UI 线程即被释放，可以处理下一帧输入/动画，RenderThread 独立执行 GPU 命令。这就是「RenderThread 卡住也会拖慢主线程」的根因——sync 阶段要等 RenderThread 处理完上一帧。

**为什么快：** DisplayList 可缓存、可复用。若一个 View 没变（没调 `invalidate`），它的 RenderNode/DisplayList 不用重录，RenderThread 直接复用；`RenderNode.setTranslationX/Alpha` 这类属性动画甚至可以**只更新 RenderNode 属性、不重录 DisplayList**（这也是属性动画比补间动画流畅的底层原因）。

**易错点：**
- 「硬件加速就是把 draw 放到 GPU」——不准确。UI 线程 draw 只是**录制**命令，真正的栅格化在 RenderThread + GPU。
- `Canvas` 在硬件加速下是 `RecordingCanvas`（原 `DisplayListCanvas`），部分老 API（如 `Canvas.clipPath` 早期、`drawBitmapMesh`）曾不被硬件加速支持，会触发软件回退。

---

<a id="2"></a>
## 2. Choreographer 与 VSync：一帧的精密调度

**Q：`Choreographer` 是干什么的？VSync 信号怎么驱动一帧？为什么会掉帧？**

**答案解析：**

`Choreographer` 是「帧编舞者」，统一调度一帧内的 **INPUT → ANIMATION → INSERT_EVENT_TOUCH → TRAVERSAL → COMMIT** 五类回调，节奏由 **VSync** 驱动。

**源码路径：**
- `frameworks/base/core/java/android/view/Choreographer.java` —— `postCallback()`、`doFrame(long frameTimeNanos, ...)`、`CALLBACK_INPUT/ANIMATION/TRAVERSAL/COMMIT`。
- VSync 接收：`android/view/DisplayEventReceiver.java`（JNI 到 `frameworks/base/core/jni/android_view_DisplayEventReceiver.cpp`），底层 `libgui` 的 `DisplayEventReceiver` 从 SurfaceFlinger 的 `EventThread` 拿 VSync。
- `Choreographer.FrameDisplayEventReceiver.onVsync()` → `doFrame()`。

**一帧流程：**
1. 应用 `invalidate()`/`postFrameCallback()` → `Choreographer.postCallback()` 注册回调，并 `scheduleVsyncLocked()` 向 SF 订阅下一个 VSync。
2. VSync 到达 → `onVsync()` → `doFrame()`：依次跑 input、animation、traversal（即 `ViewRootImpl.doTraversal()`→`performTraversals()`）。
3. traversal 里完成 measure/layout/draw 录制，再 `syncAndDrawFrame` 交给 RenderThread。

**VSync-app / VSync-sf 偏移（追问点）：** Android 用 **VSync offset**（`Choreographer` app phase 与 SF phase 有相位差，见 `VsyncConfiguration`/`WorkDuration`），让 app 渲染和 SF 合成错峰，充分利用一个刷新周期。Android 14 的调度器 `frameworks/native/services/surfaceflinger/Scheduler/` 里 `VsyncSchedule`、`VSyncPredictor`（用历史帧时长预测下一个 VSync）、`WorkDuration` 动态调节 offset。

**掉帧本质：** doFrame 的 traversal 或 RenderThread 栅格化**超过一个 VSync 周期**（60Hz=16.6ms），swap 失败，SF 只能复用旧帧 → 用户看到卡顿。`FrameTimeline`（Perfetto/`dumpsys SurfaceFlinger --latency`）能精确定位是 app 端还是 SF 端超时（JankType）。

**易错点：** `Choreographer.postFrameCallback` 拿到的 `frameTimeNanos` 是**这一帧的预期 VSync 时间**，不是当前系统时间——做动画插值要用它，否则动画会抖。

---

<a id="3"></a>
## 3. SurfaceFlinger 合成：BufferQueue / Layer / HWComposer

**Q：SurfaceFlinger 怎么把多个 App 的画面合成到屏幕？GPU 合成和 Overlay（HWC）合成的区别？**

**答案解析：**

**BufferQueue 是核心（生产者-消费者模型）：**
- 生产者：App 的 RenderThread（通过 `Surface`/`ANativeWindow`，`dequeueBuffer`→ 渲染 →`queueBuffer`）。
- 消费者：SurfaceFlinger（`acquireBuffer`→合成→`releaseBuffer`）。
- 源码：`frameworks/native/libs/gui/` —— `BufferQueueProducer.cpp`、`BufferQueueConsumer.cpp`、`BufferQueueCore.cpp`、`Surface.cpp`。

**SurfaceFlinger 主流程（Android 14）：**
- `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp` —— `onMessageReceived()`→`composite()`（新架构 `CompositionEngine`）。
- 每个窗口 = 一个 `Layer`（`Layer.cpp`），SF 收集所有可见 Layer 的最新 buffer。
- **合成策略决策**：`CompositionEngine`（`frameworks/native/services/surfaceflinger/CompositionEngine/`）调用 **HWComposer** HAL 询问哪些 Layer 能走硬件 Overlay。

**两种合成方式（必答对比）：**
| 方式 | 谁来合成 | 特点 |
|---|---|---|
| **Device/Overlay (HWC)** | 显示控制器硬件 | 零拷贝、低功耗，Layer 数受硬件通道限制 |
| **Client/GPU (GLES)** | GPU（SF 用 RenderEngine 画到一张 framebuffer） | 灵活（可做混合/变换），耗电、占 GPU |

HWC HAL：`hardware/interfaces/graphics/composer/` (AIDL, Android 13+ 从 HIDL 迁移到 **AIDL composer3**)。SF 通过 `validateDisplay()` 让 HWC 决定每个 Layer 的 `compositionType`（`DEVICE` vs `CLIENT`），不能 Overlay 的 Layer 回退给 GPU 合成成一张，再交给 HWC。

**追问：为什么 Layer 太多会掉帧/耗电？** 硬件 Overlay 通道有限（通常 4~8 个），超出的 Layer 必须 GPU 合成，增加 GPU 负载与功耗。`dumpsys SurfaceFlinger` 可看每个 Layer 的合成类型；减少窗口层级/半透明叠加是优化手段。

**Gralloc 与最终送显：** 合成结果写入 `Gralloc` 分配的 framebuffer，HWC 把它扫描输出（scanout）到 panel。

---

<a id="4"></a>
## 4. 图形内存：Gralloc / ION → DMA-BUF / GraphicBuffer

**Q：一块图形 buffer 从分配到跨进程共享（App→SF）怎么实现零拷贝？**

**答案解析：**

- **GraphicBuffer**（`frameworks/native/libs/ui/GraphicBuffer.cpp`）是图形 buffer 的封装，底层内存由 **Gralloc HAL** 分配。
- **Gralloc**：`hardware/interfaces/graphics/allocator/` + `mapper/`（Android 13+ AIDL，`IAllocator`/`IMapper`）。`allocate()` 返回 `buffer_handle_t`（含 fd + 元数据）。
- 底层内存来自 **DMA-BUF**（老代号 ION，Android 12+ 内核统一走 `dma-buf heaps`：`/dev/dma_heap/system`、`/dev/dma_heap/...`）。内核：`drivers/dma-buf/`、`drivers/dma-buf/heaps/`。

**零拷贝原理（高频）：**
1. buffer 本质是一块物理内存，用 **fd（file descriptor）** 表示。
2. 跨进程通过 **Binder 传 fd**（Binder 支持 fd 传递，内核 `binder.c` 里 `BINDER_TYPE_FD`/`BINDER_TYPE_FDA`，会在目标进程 `dup` 出一个新 fd 指向同一 dma-buf）。
3. 双方各自 `mmap` 这个 dma-buf 到自己的地址空间 → 指向**同一物理页** → 无内存拷贝。
4. 同步用 **fence**（`sync_file`，`drivers/dma-buf/sync_file.c`）：`AcquireFence`（消费者等生产者画完）/`ReleaseFence`（生产者等消费者用完）。GraphicBuffer 随 buffer 传 fence fd，`queueBuffer/acquireBuffer` 携带。

**易错点：** BufferQueue 里传的不是「拷贝一份图像」，而是「buffer 的所有权（slot + fence）」。理解「所有权转移 + fence 同步」是图形栈的精髓。

---

<a id="5"></a>
## 5. 多刷新率与 DisplayManager（帧率投票）

**Q：Android 是怎么做到 120Hz 动态切 60Hz/24Hz 的？谁来决定刷新率？**

**答案解析：**

- Framework 侧：`frameworks/base/services/core/java/com/android/server/display/` —— `DisplayModeDirector.java`（**帧率投票核心**）、`DisplayManagerService.java`。
- 各方通过 `Vote` 投票期望的刷新率范围：App 的 `Surface.setFrameRate()`（`ANativeWindow_setFrameRate`）、系统设置（省电模式限 60）、亮度/温度限制、触摸提升（touch boost 拉到高刷）等。`DisplayModeDirector` 取交集/优先级得出最终允许的 refresh rate 范围。
- SF 调度器 `Scheduler`（`frameworks/native/services/surfaceflinger/Scheduler/RefreshRateSelector.cpp`）根据当前内容帧率在允许范围内选最优 mode，通过 HWC `setActiveConfigWithConstraints()` 切换。
- **LTPO 面板**支持无缝变频（seamless），HWC 上报哪些 mode group 可无缝切换。

**追问：为什么静止页面能降到 1~24Hz 省电？** 内容没有新帧（无 buffer 提交），`Scheduler` 检测到 idle → 投低刷新率；一旦触摸/动画来了，touch boost 立刻拉高。这是省电与流畅的平衡。

---

<a id="6"></a>
## 6. MediaCodec 状态机与 Buffer 队列

**Q：讲讲 MediaCodec 的工作流程、同步 vs 异步模式、以及 Surface 零拷贝。**

**答案解析：**

`MediaCodec`（`frameworks/base/media/java/android/media/MediaCodec.java`）是硬件编解码的门面，JNI 到 `frameworks/av/media/libmedia`/`frameworks/av/media/codec2` + `frameworks/av/media/libstagefright/`。

**状态机：** `Uninitialized → Configured → (Flushed) → Running → End-of-Stream → Released`。核心 API：
- `dequeueInputBuffer()` 取空输入 buffer → 填数据 → `queueInputBuffer()`。
- `dequeueOutputBuffer()` 取解码结果 → 用完 `releaseOutputBuffer()`。

**同步 vs 异步（追问点）：**
- 同步：轮询 `dequeueInputBuffer/dequeueOutputBuffer`（带 timeout），代码简单但易写错、易阻塞。
- 异步：`setCallback(MediaCodec.Callback)`，`onInputBufferAvailable/onOutputBufferAvailable` 回调驱动，无需轮询，官方推荐（避免主线程忙等）。

**Surface 零拷贝（精髓）：**
- 解码：`configure(..., surface, ...)` 直接把解码输出写到 Surface（如 SurfaceView/纹理），YUV 帧不经过 App 的 ByteBuffer，CPU 完全不碰像素。
- 编码：`createInputSurface()` 让相机/GL 直接把帧画到编码器输入 Surface。
- 底层靠 GraphicBuffer + BufferQueue（同第 4 节），实现 Codec↔Surface 的共享内存。

**易错点：**
- `IllegalStateException` 高频来源：`releaseOutputBuffer` 时机错、在错误状态调用 API。
- `INFO_OUTPUT_FORMAT_CHANGED` / `INFO_OUTPUT_BUFFERS_CHANGED`（后者已废弃）返回码要处理。
- 硬编码器数量有限（并发 MediaCodec 实例受 SoC 限制），拿不到会抛异常，需释放复用。

---

<a id="7"></a>
## 7. Codec2 (CCodec) vs OMX (ACodec)：编解码底层

**Q：Android 10+ 的 Codec2 相比老的 OMX 好在哪？C2Work / C2Buffer 是什么？**

**答案解析：**

MediaCodec 底层有两条路径：
```
android.media.MediaCodec (Java)
        │ JNI (android_media_MediaCodec.cpp)
        ├── ACodec  → OMXNodeInstance → libstagefright_omx  (旧, Android 10 前主流)
        └── CCodec  → C2Component → Vendor Codec2 HAL        (Android 10+ 主推)
```
- Codec2 源码：`frameworks/av/media/codec2/`（`hidl`/`aidl` HAL、`sfplugin/CCodec.cpp`、`vndk/`）。Vendor 实现如 `c2.qti.avc.decoder`（高通）、`c2.mtk.*`（MTK）、`c2.android.*`（Google 软解，跑在 `mediaswcodec` 进程）。

**OMX 的痛点（对比要点）：**
- C 接口 + 全局状态 + 同步阻塞调用（`OMX_SetParameter` 阻塞）；
- Buffer 所有权在 Framework/HAL 间频繁交接，每次转移两次函数调用，高帧率延迟大。

**Codec2 的改进：**
- **C++17 模板接口**，核心抽象 `C2Component`（处理）+ `C2Interface`（配置能力，与数据流解耦）。
- **C2Work**：一个工作单元 = 一组输入 buffer + 输出 buffer 描述 + metadata，异步流水线（stateless，喂 C2Work 产 C2Work）。
- **C2Buffer/C2Block**：引用计数 + 共享内存（DMA-BUF/Gralloc），配 **C2Fence** 异步同步，天然零拷贝。
- **Treble 合规**：原生 HIDL/AIDL 服务，vendor 解码器与 framework 干净隔离（`media.codec` 进程跑硬解，`mediaswcodec` 跑软解，`mediacodec` service 被拆分以满足 Treble）。

**追问：`vendor.` 开头的 MediaFormat 参数哪来的？** 厂商在 Codec2 里注册的 Vendor Parameter（如低延迟、LTR 长期参考帧），通过映射表暴露到 `MediaFormat`，实现标准 API 之外的硬件能力调优。

**调试：** `adb shell dumpsys media.player`、`dumpsys media.codec` 看当前实例走哪个 C2Component 及 buffer 队列。

---

<a id="8"></a>
## 8. Thermal HAL：热控降频全链路

**Q：手机发热时系统怎么降频/降亮度？App 怎么感知热状态？**

**答案解析：**

**全链路：** 温度传感器（内核 thermal zone）→ **Thermal HAL** → **ThermalManagerService** → App/系统调节。

- 内核：`drivers/thermal/`（thermal framework、cooling device、governor 如 `step_wise`/`IPA`）。sensor 走 `/sys/class/thermal/thermal_zoneN/`。
- Thermal HAL：`hardware/interfaces/thermal/`（Android 13+ AIDL `IThermal`），上报 `Temperature`、`ThrottlingStatus`（NONE/LIGHT/MODERATE/SEVERE/CRITICAL/EMERGENCY/SHUTDOWN）。
- Framework：`frameworks/base/services/core/java/com/android/server/power/ThermalManagerService.java` —— 订阅 HAL 回调，向上通过 `PowerManager.OnThermalStatusChangedListener` 通知。
- App 侧：`PowerManager.getCurrentThermalStatus()` / `addThermalStatusListener()` —— 游戏/相机可据此主动降负载（降分辨率/帧率）。

**降频动作：** 热控可触发 CPU/GPU 限频（cooling device 限制 `cpufreq` 最大频率）、限制亮度、限制充电电流、极端时关摄像头/关机（SHUTDOWN）。

**易错点：** Thermal throttling 是「系统性能岗」排查卡顿的隐形杀手——benchmark 前期高分、后期跳水通常就是热降频（结合 `dumpsys thermalservice` 与 perfetto 的 cpufreq track 定位）。

---

<a id="9"></a>
## 9. Power HAL 与 CPU 调度提示（ADPF / performance hint）

**Q：`PowerManager` 的 wakelock、Doze、以及游戏用的性能提示（ADPF）分别对应底层什么？**

**答案解析：**

**（1）电源状态与 wakelock：**
- `frameworks/base/services/core/java/com/android/server/power/PowerManagerService.java` —— 管理 wakelock、屏幕状态、Doze。
- wakelock 底层：内核 `/sys/power/wake_lock`（`drivers/base/power/wakeup.c`），阻止系统进入 suspend。
- **Doze/App Standby**：`DeviceIdleController.java` —— 屏灭静止后分阶段进入 Doze，收紧 alarm/network/job；`JobScheduler` + `AlarmManager` 配合省电。

**（2）Power HAL：**
- `hardware/interfaces/power/`（AIDL `IPower`）。系统在场景切换（如启动 App、滑动、触摸）通过 `IPower.setBoost()`/`setMode()` 给 SoC 提示（`INTERACTION` boost、`LAUNCH` mode 等），让 governor 提前升频，减少交互延迟。
- 对应内核侧 `schedutil`/EAS（Energy Aware Scheduling）governor：`kernel/sched/`（`fair.c`、`cpufreq_schedutil.c`）。

**（3）ADPF / Performance Hint（新热点，Android 12+）：**
- `android.os.PerformanceHintManager`（`hardware/interfaces/power/` 的 `IPowerHintSession`）。游戏/高负载 App 创建 hint session，上报「目标帧时长 + 实际 CPU 工作时长」，系统据此精准调度 CPU（比盲目 boost 省电）。这是近两年**性能优化面试新宠**（ADPF = Android Dynamic Performance Framework）。

**易错点：** Doze 下 `AlarmManager.setExact` 不保证唤醒，需 `setExactAndAllowWhileIdle`/`setAndAllowWhileIdle`；后台网络受限，长连接要用 FCM 高优先级消息。

---

<a id="10"></a>
## 10. Telephony / RIL 框架

**Q：一个电话/短信从 App 到 modem 经过哪些层？RILJ 和 RILD 是什么？**

**答案解析：**

**分层：** App → Telephony Framework（Java）→ **RILJ** → **RILD**（native daemon）→ **Radio HAL** → Vendor RIL → Modem。

- App API：`TelephonyManager`、`SmsManager`、`SubscriptionManager`（`frameworks/base/telephony/`）。
- Telephony 核心：`frameworks/opt/telephony/src/java/com/android/internal/telephony/` —— `Phone`/`GsmCdmaPhone`、`ServiceStateTracker`、`RIL.java`（即 **RILJ**，Java 侧发送请求/解析回复）。
- 独立进程：`com.android.phone`（`packages/services/Telephony/`）。
- **RILD**：native 守护进程 `hardware/ril/rild`（老架构），加载 vendor `libril`。Android 现走 **Radio HAL**（`hardware/interfaces/radio/`，AIDL，Android 13+ 从 HIDL 迁移），`RIL.java` 通过 HAL 与 vendor modem 通信。
- IMS/VoLTE：`frameworks/base/telephony/java/android/telephony/ims/` + vendor IMS stack。

**请求流：** `TelephonyManager.call/sendSms` → `Phone` → `RIL.java` 组装 request（solicited command）→ Radio HAL → modem；modem 主动上报（unsolicited，如来电、信号变化）→ HAL 回调 → `RIL.java` → `ServiceStateTracker`/各 Tracker → 广播/回调给 App。

**追问：双卡怎么实现？** 多个 `Phone` 实例 + `SubscriptionController`/`SubscriptionManager` 管理 SIM 槽（`phoneId`/`subId` 映射），Radio HAL 多实例对应多 modem stack。

**易错点：** `READ_PHONE_STATE`/`READ_PHONE_NUMBERS` 权限、Android 10+ 对 IMEI 等设备标识符收紧（普通 App 拿不到）。

---

<a id="11"></a>
## 11. Wi-Fi 框架架构

**Q：Wi-Fi 从扫描到连上 AP，framework 里的调用链是怎样的？**

**答案解析：**

**分层：** App(`WifiManager`) → **WifiService**(system_server) → `wpa_supplicant` / Wi-Fi HAL → 内核 `cfg80211`/`nl80211` → Wi-Fi 驱动。

- App API：`android.net.wifi.WifiManager`（`frameworks/base/wifi/` 已迁到 `packages/modules/Wifi/framework/`，Wi-Fi 成为 **Mainline 模块**）。
- Service：`packages/modules/Wifi/service/java/com/android/server/wifi/` —— `WifiServiceImpl`、`ClientModeImpl`（连接状态机，老名 `WifiStateMachine`）、`WifiNative`（JNI 到 HAL/supplicant）。
- 用户态：`external/wpa_supplicant_8`（`wpa_supplicant` 负责关联/认证/4 次握手）；Wi-Fi HAL `hardware/interfaces/wifi/`（AIDL）。
- 内核：`net/wireless/`（cfg80211）、`net/mac80211/`，vendor 驱动 `drivers/net/wireless/`，控制面走 **nl80211**（netlink）。

**连接流：** `WifiManager.connect()` → `ClientModeImpl` 状态机 → `WifiNative`/supplicant 发起 scan → associate → EAPOL 4-way handshake → DHCP（`IpClient`，`packages/modules/NetworkStack`）拿 IP → 上报 `NetworkAgent` 给 **ConnectivityService** 参与网络选择。

**追问：Wi-Fi 与数据网络怎么切换？** `ConnectivityService`（`packages/modules/Connectivity`）根据 `NetworkCapabilities`/评分选默认网络，`NetworkAgent` 上报网络质量（validation via captive portal 探测）。

---

<a id="12"></a>
## 12. Bluetooth 协议栈（Gabeldorsche）

**Q：Android 蓝牙栈的架构？经典蓝牙和 BLE 的区别在 framework 里怎么体现？**

**答案解析：**

- App API：`android.bluetooth.*`（`BluetoothAdapter`、`BluetoothGatt` for BLE、`BluetoothA2dp` 等）。蓝牙也是 **Mainline 模块**（`packages/modules/Bluetooth`）。
- 栈实现：`packages/modules/Bluetooth/system/` —— 传统栈 **Fluoride/BlueDroid**，Google 正在推 **Gabeldorsche (GD)** 重构（更模块化、可测试）。运行在独立进程 `com.android.bluetooth`。
- HAL：`hardware/interfaces/bluetooth/`（AIDL），`libbt-hci` 与 controller 通过 **HCI** 通信（UART/USB）。
- 内核：`net/bluetooth/`（BlueZ 内核部分：HCI/L2CAP/RFCOMM/SCO），vendor controller 驱动。

**协议分层：** HCI → L2CAP → 上层 profile（经典：A2DP/HFP/RFCOMM；BLE：ATT/GATT）。
- **经典蓝牙**：面向连接、高带宽（音频 A2DP）。
- **BLE**：低功耗，GATT（Service/Characteristic）模型，广播（advertising）+ 扫描，适合传感器/穿戴。

**流程（BLE 连接）：** `BluetoothAdapter.getBluetoothLeScanner().startScan()` → 发现设备 → `device.connectGatt()` → GATT 服务发现 → 读写 Characteristic/订阅 Notification。

**追问：为什么蓝牙音频延迟/断连难排查？** 涉及 controller 固件 + HAL + 栈 + A2DP codec（SBC/AAC/aptX/LDAC）协商，`dumpsys bluetooth_manager` + HCI snoop log（`btsnoop_hci.log`）是主要抓手。

---

<a id="13"></a>
## 13. 查缺补漏 · 易错点 · 高频追问 · 延伸阅读

### 13.1 一句话速记（面试快答）
| 主题 | 一句话本质 |
|---|---|
| RenderThread | UI 线程录制 DisplayList，RenderThread 执行 GPU 命令，sync 阶段短暂互锁 |
| Choreographer | VSync 驱动的一帧编舞者，frameTimeNanos 是预期 VSync 时间 |
| SurfaceFlinger | BufferQueue 生产消费 + HWC 决定 Overlay/GPU 合成 |
| GraphicBuffer | DMA-BUF fd 经 Binder 传递 + 双端 mmap = 零拷贝，fence 做同步 |
| MediaCodec | 门面，输入/输出 buffer 队列 + 状态机，Surface 走零拷贝 |
| Codec2 | Android 10+ 取代 OMX，C2Work/C2Buffer + 引用计数 + Treble 合规 |
| Thermal HAL | 温度→HAL→ThermalManagerService→限频/通知 App |
| Power HAL | 场景 boost + ADPF hint session 精准调度 |
| RIL | RILJ(RIL.java) ↔ Radio HAL(AIDL) ↔ vendor modem |
| Wi-Fi/BT | Mainline 模块，状态机 + supplicant/GD 栈 + HAL + 内核 |

### 13.2 高频追问链（面试官常这样往下挖）
1. RenderThread → 「TextureView 和 SurfaceView 渲染路径差异？」（SurfaceView 独立 Surface 直接给 SF 合成；TextureView 走 App 的 HWUI 纹理，多一次拷贝、能做变换但更耗）。
2. SurfaceFlinger → 「三缓冲(triple buffering)解决什么问题？」（减少生产者等待，避免 jank，但增加一帧延迟）。
3. 零拷贝 → 「Binder 传 fd 的内核实现？」（`binder.c` 的 `BINDER_TYPE_FD`，`binder_translate_fd` 在目标进程 `dup`）。
4. MediaCodec → 「tunneled playback 是什么？」（视频解码直通到显示，绕过 App，超低功耗看视频，`FEATURE_TunneledPlayback`）。
5. Thermal → 「skin temperature 和 CPU temperature 区别？」（skin 是外壳温度，直接关系用户体感与降频策略）。
6. RIL → 「solicited vs unsolicited response？」（App 主动请求 vs modem 主动上报）。

### 13.3 易错点清单
- ❌ 「硬件加速 = draw 在 GPU」→ ✅ UI 线程只录制，RenderThread+GPU 才栅格化。
- ❌ 「SurfaceView 有性能问题所以少用」→ ✅ 视频/游戏/相机场景 SurfaceView 反而更优（独立合成）。
- ❌ 「BufferQueue 传的是图像数据」→ ✅ 传的是 buffer 所有权 + fence。
- ❌ MediaCodec 同步模式在主线程轮询 → 卡 UI，应异步回调。
- ❌ Doze 下用 `setExact` 定时 → 不会准时，用 `AllowWhileIdle`。

### 13.4 延伸阅读 / 动手实验
- `dumpsys SurfaceFlinger`、`dumpsys gfxinfo <pkg>`（帧统计/jank）、`adb shell dumpsys SurfaceFlinger --latency`。
- Perfetto 抓 `gfx`/`view`/`sf`/`cpufreq`/`freq` track，看 FrameTimeline 的 JankType。
- `dumpsys thermalservice`、`dumpsys power`、`dumpsys media.codec`、`dumpsys wifi`、`dumpsys bluetooth_manager`。
- AOSP 阅读顺序建议：`ViewRootImpl.performTraversals` → `ThreadedRenderer` → `libs/hwui` → `libgui/BufferQueue*` → `surfaceflinger/SurfaceFlinger.cpp` → HWC HAL。

---

### 交叉索引（本系列五篇）
| 篇 | 文件 | 主线 |
|---|---|---|
| 主篇 | `Android_Framework面试题_2026-07-23.md` | Binder/启动/AMS/WMS/View/ANR/Compose/HAL/内核/MTK |
| 拓展篇 | `Android_Framework面试题_热点拓展_2026-07-23.md` | Input/PMS/ART/SystemUI/折叠屏/SELinux/OTA/JNI/Binder安全/Perfetto |
| 深挖篇 | `Android_Framework面试题_深挖篇_2026-07-23.md` | ART对象头/CMC GC/verify-deopt/Binder驱动调试/Rust Binder/多指/VSync/Camera/Audio/GKI/Perfetto SQL |
| **图形多媒体通信篇（本篇）** | `Android_Framework面试题_图形多媒体通信篇_2026-07-24.md` | **HWUI/Choreographer/SF合成/图形内存/多刷新率/MediaCodec/Codec2/Thermal/Power/RIL/Wi-Fi/BT** |

> 下次可轮换：Vulkan/ANGLE/HWUI Skia 后端深挖、Codec2 vendor plugin 开发、CarService/Automotive、NFC/SE、NNAPI/TFLite delegate、Perfetto trace processor SQL 进阶、A/B OTA 与 virtual A/B snapuserd 深水区。
