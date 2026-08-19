# SurfaceFlinger 学习路线（AOSP 14 / UpsideDownCake）

> 核心心智模型：**SurfaceFlinger 是一个系统级 compositor**，负责把各 App 提交的图形 Buffer 合成到屏幕。它运行在 `system/bin/surfaceflinger`，由 `init` 拉起，通过 Binder 与 App/WMS 通信，通过 HWC HAL 与显示硬件交互。

---

## 一、先建立分层架构（不要急着读代码）

```
┌─────────────────────────────────────────────────────────────┐
│  App (UI 线程 / RenderThread)                                │
│   View.draw → Canvas / HWUI(GLES) → 画出像素到 GraphicBuffer │
└───────────────────────────┬─────────────────────────────────┘
                            │ IGraphicBufferProducer (Binder)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  BufferQueue  (生产者-消费者队列, 跨进程共享)                  │
│   Producer(App)            Consumer(SurfaceFlinger)          │
│   dequeue → fill → queue   acquire → latch → compose → release│
└───────────────────────────┬─────────────────────────────────┘
                            │ acquireBuffer 拿到 Buffer
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  SurfaceFlinger 主循环 (MessageQueue + VSYNC 驱动)           │
│   onMessageReceived: MSG_INVALIDATE → latchBuffer            │
│                      MSG_REFRESH   → 合成 → postFramebuffer  │
└───────────────────────────┬─────────────────────────────────┘
                            │ CompositionEngine 决定每帧怎么合成
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  合成方式分流:                                               │
│   • HWC Overlay (硬件层叠, 零 GPU 开销)  ← 优先              │
│   • GPU 合成 (RenderEngine, GLES/Vulkan)  ← fallback         │
└───────────────────────────┬─────────────────────────────────┘
                            │ present
                            ▼
                  HWC HAL → Display (屏幕)
```

**关键认知**：
- App 不直接画到屏幕，而是画到一个个 **GraphicBuffer**（BufferQueue 的 slot）。
- SurfaceFlinger 在 **VSYNC 信号**到来时,把"这一帧该显示的 Buffer"合成并送显。
- 合成分两条路：**HWC 硬件叠加**（最省电）和 **GPU 合成**（RenderEngine），SF 每帧都会做策略选择。

---

## 二、代码地图（AOSP 14，路径都以 aosp/ 根目录为基准）

| 模块 | 路径 | 说明 |
|------|------|------|
| **SF 主程序入口** | `frameworks/native/services/surfaceflinger/main_surfaceflinger.cpp` | `main()`，启动 SurfaceFlinger 进程 |
| **SF 核心类** | `frameworks/native/services/surfaceflinger/SurfaceFlinger.h/.cpp` | 合成主逻辑 |
| **消息队列/主循环** | `frameworks/native/services/surfaceflinger/MessageQueue.cpp` | 仿 Looper，承载 VSYNC 消息 |
| **BufferQueue** | `frameworks/native/libs/gui/BufferQueueCore.cpp` | 队列核心状态机 |
| | `frameworks/native/libs/gui/BufferQueueProducer.cpp` | 生产者端（App 用） |
| | `frameworks/native/libs/gui/BufferQueueConsumer.cpp` | 消费者端（SF 用） |
| | `frameworks/native/libs/gui/IGraphicBufferProducer.cpp` | Binder 接口定义 |
| **Layer** | `frameworks/native/services/surfaceflinger/BufferLayer.cpp` | 普通 Buffer 图层（最常见） |
| | `frameworks/native/services/surfaceflinger/BufferStateLayer.cpp` | 服务端驱动的图层状态 |
| | `frameworks/native/services/surfaceflinger/ColorLayer.cpp` | 纯色图层 |
| **合成引擎** | `frameworks/native/services/surfaceflinger/CompositionEngine/` | 新版合成引擎（Output/Layer/CompositionEngine） |
| **RenderEngine** | `frameworks/native/libs/renderengine/` | GPU 合成实现（GLES/Vulkan） |
| **调度/VSYNC** | `frameworks/native/services/surfaceflinger/Scheduler/` | VSyncReactor、EventThread、调度 |
| **显示硬件** | `frameworks/native/services/surfaceflinger/DisplayHardware/` | HWComposer、Display、ComposerHal 封装 |
| **Buffer/同步原语** | `frameworks/native/libs/ui/` | GraphicBuffer、Fence、FenceTime |

---

## 三、分阶段学习路线

### 阶段 0：跑通心智模型（1 天）
- 读 `main_surfaceflinger.cpp` 的 `main()`：看它怎么 `start` SurfaceFlinger、怎么把自身 `addService("SurfaceFlinger")`。
- 读 `SurfaceFlinger::init()`：看它初始化了哪些子系统（HWC、Scheduler、RenderEngine、MessageQueue）。
- **目标产出**：能在白纸上画出上面那张分层图，并说清 App→SF→HWC 三段各自跑在哪个进程、用什么 IPC。

### 阶段 1：跟着一条 Buffer 走（最重要，3~5 天）
读 BufferQueue 状态机，把下面四个动作的调用链摸透：

1. **dequeueBuffer**：App 申请一个空 Buffer。
   - `BufferQueueProducer::dequeueBuffer()` → 从 `BufferQueueCore::mSlots[]` 找一个 FREE slot → 若无则等待（`mDequeueCondition`）。
2. **queueBuffer**：App 画完，归还。
   - `BufferQueueProducer::queueBuffer()` → 标记 `QUEUED` → 触发 `onFrameAvailable` 回调（SF 这边会收到）。
3. **acquireBuffer**：SF 取走 Buffer 准备合成。
   - `BufferQueueConsumer::acquireBuffer()` → 标记 `ACQUIRED`。
4. **releaseBuffer**：SF 合成完，归还。
   - `BufferQueueConsumer::releaseBuffer()` → 标记回 `FREE`。

**关键概念**：3 个 slot = 双/三缓冲；**Fence**（见 `libs/ui/Fence.cpp`）是跨进程同步 GPU/显示完成的机制——App 画完、SF 合成完都靠 fence 确认"Buffer 真的可用了"，这是理解合成时序的钥匙。

**验证**：`adb shell dumpsys SurfaceFlinger` 看每个 Layer 的 `mQueuedFrames / mAcquiredFrames` 计数，对应上你的理解。

### 阶段 2：SurfaceFlinger 主循环与 VSYNC（3~5 天）
- 入口：`SurfaceFlinger::onMessageReceived(int32_t what)`（`SurfaceFlinger.cpp`）。
  - `MSG_INVALIDATE` → `handleMessageInvalidate()` → 调 `handlePageFlip()` → 各 Layer `latchBuffer()`（即 acquire + 拷贝 meta）。
  - `MSG_REFRESH` → `handleMessageRefresh()`，骨架是：
    ```
    preComposition();
    rebuildLayerStacks();     // 按 Z-order 整理图层
    setUpHWComposer();        // 决定每块走 HWC 还是 GPU
    doComposition();          // 真正画
    postComposition();        // present + 释放 fence
    ```
- VSYNC 来源：`Scheduler/`（Android 14 用 `VSyncReactor` + 内核 timeline，取代了老的 `DispSync`）。`EventThread` 把 vsync 事件发给需要者（SF、App 的 Choreographer）。
- **目标产出**：能说清"一次 VSYNC 如何从内核到 SF 主循环，触发一帧合成"。

### 阶段 3：合成策略（HWC vs GPU，2~3 天）
- `setUpHWComposer()` 里 `HWComposer::createLayer` / `setLayerCompositionType`，逐 layer 询问 HWC「你能否用 overlay 叠？」
- 能 overlay 的标 `HWC2::Composition::Device`；不能的标 `Client`，回退到 `RenderEngine` 用 GPU 画到一个离屏 Buffer 再叠。
- 读 `DisplayHardware/HWComposer.cpp` 和 `ComposerHal`（HIDL/AIDL 稳定 HAL，对应 `android.hardware.graphics.composer`）。
- **目标产出**：`dumpsys SurfaceFlinger` 里能看到每层的 `Composition type`，能解释为什么某个 layer 走了 GPU。

### 阶段 4：调试验证（贯穿全程）
见下方「调试验证速查表」。

---

## 四、调试验证速查表

| 命令 | 用途 |
|------|------|
| `adb shell dumpsys SurfaceFlinger` | 全景：displays、layers、HWC、各 layer 的 buffer 计数与 composition type |
| `adb shell dumpsys SurfaceFlinger --latency <display>` | 帧延迟直方图（判断掉帧/抖动） |
| `adb shell dumpsys SurfaceFlinger --list` | 列出所有 layer 名 |
| `adb shell lshal | grep composer` | 看 HWC HAL 实现（厂商） |
| `adb shell service call SurfaceFlinger 1008` | 截屏（captureScreen）调试 |
| **Perfetto / systrace** | 抓 `gfx`、`view`、`wm`、`surfaceflinger`、`sched` 标签，看一帧的 `deliverInputEvent → doFrame → queueBuffer → onMessageRefresh` 时间线 |
| 开发者选项 → **"显示 Surface 更新"/"GPU 呈现模式分析"** | 直观看 jank |

**关键日志 tag**：`SurfaceFlinger`、`BufferQueue`、`BufferQueueProducer`、`BufferQueueConsumer`、`HWC`、`VSyncReactor`。

---

## 五、建议的顺序与资料

1. **先读代码，再读文档**（SF 演进很快，博客容易过时，AOSP 14 源码为准）。
2. 官方文档：Android 开发者站 "Graphics architecture" / "SurfaceFlinger" 章节（概念层,搜 "SurfaceFlinger architecture"）。
3. 演讲：Chet Haase & Romain Guy 的 "The Android Graphics Pipeline"（Google I/O），建立流水线直觉。
4. 老罗《Android 源码情景分析》SurfaceFlinger 章——**仅作历史参考**，AOSP 14 已大改（CompositionEngine 取代旧 composeSurfaces、VSyncReactor 取代 DispSync）。
5. 自顶向下拆一个真实 jank 案例：用 Perfetto 抓一帧，顺藤摸到 `onMessageRefresh` 里的耗时函数，是最高效的进阶方式。

---

## 六、一句话总结

> 学 SurfaceFlinger = **先懂 BufferQueue 的跨进程生产者/消费者模型 → 再懂 VSYNC 如何驱动 SF 主循环一帧帧合成 → 最后懂 HWC overlay 与 GPU 合成的二选一策略**。代码从 `main_surfaceflinger.cpp` 进，`SurfaceFlinger::onMessageReceived` 是心脏，`BufferQueue` 是血脉。
