# Android Framework 面试题 · ART 内存管理与 GC 实战（2026-08-31，第四十八篇）

> 本篇落点：把"内存优化"从面试八股里最常被讲浅的 `System.gc()` / `LeakCanary` 一层，向下凿穿到 **ART 堆空间布局 -> 对象分配 fast/slow path -> GC 收集器（CMS/CC/分代CC）与读屏障 -> GC 触发与停顿定界 -> Native/图形内存 -> 内存优化工具链** 的完整链路，并反向接入 **A17 分代 GC 经 Mainline 热更、A17 Memory Limiter、Bitmap 像素内存从 Java 堆迁到 Native/Graphics、Scudo/MTE 内存安全** 这些 2026 真·新边界。
>
> 补上全系列长期隐含、却从未独立成篇的"ART 内存与 GC 底座"真缺口（呼应 7/29 A17 分代 GC、7/30 A17 Memory Limiter、8/10 ART 运行时与 dex2oat、8/28 系统层 Rust 化与内存安全边界）。
>
> Baseline：**Android 14（UpsideDownCake, API 34）**，ART 源码路径对齐 `android-14.0.0_rXX`（AOSP `art/` 模块）；A17 增量标注 `API 37`。

---

## 0. 当日热点锚定（2026-08-31）

联网锚定（2026-08-31）：AOSP 官方文档《Debug ART garbage collection》与 2026 面试题库（juejin「Framework 补完计划 (7): ART 虚拟机与 GC 策略」「Android 运行时面试题: ART 和 JVM 的区别」等）一致确认——**ART 内存与 GC** 是 2026 资深岗分水岭，其中"Concurrent Copying 读屏障""Generational GC 的 young/full 区别""Bitmap 像素内存到底算不算 Java 堆""LeakCanary 为什么不用 finalize"是死亡陷阱题。

| 热点 | 形态 | 取代的旧范式 | 面试价值 |
|---|---|---|---|
| **Concurrent Copying (CC) 默认** | 每线程 TLAB + 读屏障并发复制，暂停与堆大小无关 | 旧 CMS（RosAlloc 空闲链表，碎片长暂停） | 必问：为什么 ART 卡顿少 |
| **Generational GC（A10+，A17 经 Mainline 热更）** | young CC 回收短命对象 / full CC 回收整堆 | 单代 CC | A17 行为变更高频 |
| **Bitmap 像素内存下沉 Native/Graphics（A8+）** | 像素放 Native 堆 / `GraphicBuffer`，Java 侧仅留壳 | A8 前像素在 Dalvik 堆 | OOM 根因必问 |
| **Scudo + MTE 内存安全** | libc malloc 默认 Scudo；MTE 硬件标记 | jemalloc | 与 8/28 Rust 化联动 |
| **A17 Memory Limiter** | 应用内存硬限额，与 LMKD/分代 GC 协同 | 仅 LMKD 软回收 | 配合 8/30 权限/8/28 内存安全 |

> 注：本篇是**纯 ART 运行时 + 内存优化**深挖，与「Binder」「AMS/WMS」「View 绘制」的联动见第 9 节交叉索引。

---

## 1. ART 堆内存全景（先建立全局心智模型）

### 1.1 问题
ART 的 Java 堆由哪几块 Space 组成？`ImageSpace` / `ZygoteSpace` / `RegionSpace` / `LargeObjectSpace` 各自装什么、能否写？分配大对象（如大数组）走哪条路？

### 1.2 答案解析（带 AOSP 14 源码路径/方法名）

ART 的 `Heap`（`art/runtime/gc/heap.h`）并不只有一个"大数组"，而是按**生命周期 + 对象大小**切成多个 Space，由 `Heap::Heap()` 构造时按 collector 类型组装：

```
Zygote fork 之前(进程共享, 只读映射)
   |
   |  boot image: /system/framework/boot*.art (oat 镜像里的不可变对象)
   v
[ImageSpace]  存 boot classpath 的预编译不可变对象(String/Class 等)
               源码: art/runtime/gc/space/image_space.cc  ImageSpace::Create()
               -> mmap boot image, PROT_READ, 进程间共享(同一物理页)
   |
   |  Zygote 进程在 fork 前分配的对象(系统预加载的 resources/类)
   v
[ZygoteSpace]  存 zygote 预分配、fork 后不再变化的对象(不可变)
               源码: art/runtime/gc/space/zygote_space.cc (本质是冻结的 BumpPointerSpace)
   |
   |========== fork 之后, 每个 App 进程私有 ==========
   v
[RegionSpace]  默认 CC 收集器的主分配区(region 大小 1MB, bump-pointer + TLAB)
               源码: art/runtime/gc/space/region_space.cc  RegionSpace::Alloc()
               - 普通对象(new 出来大多数)都在这里
               - 分代模式下分 young region / old region
   |
   v
[LargeObjectSpace]  大对象专用: 基本类型大数组(byte[]/int[] 等) 与 大 String
               源码: art/runtime/gc/space/large_object_space.cc
               - FreeListSpace (离散空闲链表) + MapSpace (整页 mmap)
               - 阈值: 默认 > 3 * kPageSize(12KB) 或 "基本类型大数组" 走这里
               - 不进 RegionSpace, 避免把大块连续内存塞进 region 造成浪费
```

**关键事实（易错点）**
- `ImageSpace` 与 `ZygoteSpace` 是 **fork 后只读** 的：所有 App 进程共享同一份物理页（COW 之前），所以"系统预加载 2000 个类"不会在每个 App 里各占一份。这也是 Zygote 预加载能省内存的根本原因。
- 默认收集器是 **CC（Concurrent Copying）**，因此主分配区是 `RegionSpace`（region-based）。只有当 fallback 到 CMS 时，主分配区才是 `RosAllocSpace`（`art/runtime/gc/space/rosalloc_space.cc`，用 `RosAlloc` 空闲链表分配器）。
- 大对象**不在普通分配区**，走 `LargeObjectSpace`。这意味着大 `byte[]` 的分配/回收路径与 `new Object()` 完全不同——它不参与 region 复制，GC 时用 mark-sweep 而非 copying。

**A17 增量（API 37）**
- A17 的 **Generational GC** 通过 **art APEX（Mainline 模块）热更**下发，不依赖整机 OTА：zygote 启动后 `RegionSpace` 内部维护 young/old region 集合，`young GC` 只扫 young region，回收"朝生夕死"的 Compose/`StringBuilder` 临时对象开销极低；只有晋升失败或主动 `full CC` 才扫整个堆。

---

## 2. 对象分配 fast/slow path（TLAB 与无锁分配）

### 2.1 问题
`new Object()` 在 ART 里是怎么分配到内存的？为什么"大量临时对象"在 ART 上比在旧 Dalvik 上便宜？`OutOfMemoryError` 究竟在哪里抛出？

### 2.2 答案解析（带 AOSP 14 源码路径）

分配主路径（CC 模式，`RegionSpace`）：

```
Thread::AllocObject -> artAllocObjectFromCode  (art/runtime/entrypoints)
   |
   v
Heap::AllocObjectWithAllocator  (art/runtime/gc/heap.cc)
   |
   +-- fast path: 从当前线程的 TLAB 直接 bump 指针(无锁)
   |     RegionSpace::AllocTLAB / Thread.tlsPtr_.region_space_tlab_ (start/top/end)
   |     -> 若 top + size <= end, *top += size, 返回, 全程零锁
   |
   +-- slow path(1): TLAB 不够, RegionSpace::RefillTLAB() 申请新 region
   |
   +-- slow path(2): 没 region 了 / 超过 footprint -> Heap::Allocate* 走分配失败处理
         -> TryToAllocate 失败
         -> CollectGarbageForAllocator(kGcCauseForAlloc)  // 先 GC 一次
         -> 再 TryToAllocate
         -> 仍失败且超过 heap_growth_limit / max_allowed_footprint
         -> Heap::ThrowOutOfMemoryError  // 抛 OOM
```

**为什么临时对象便宜（面试核心答点）**
1. **TLAB 无锁**：每个线程持有自己的 TLAB（`art/runtime/thread.h` 的 `tlsPtr_->region_space_tlab_`），bump 指针分配**不需要原子锁**，并发分配几乎零竞争。这是 ART 比旧 Dalvik/CMS（`RosAlloc` 要抢锁或 CAS）快的关键。
2. **region bump-pointer**：`RegionSpace` 内分配就是 `top` 指针后移，比 `RosAlloc` 的空闲链表（找合适 size 的 slot）快一个量级。
3. **复制算法回收年轻代**：young region 满了触发 young CC，存活对象复制到 to-space，死亡对象整块 region 直接回收——**不产生碎片、不遍历死亡对象**。

**OOM 触发链路（精确到方法）**
- 软上限：`Heap::max_allowed_footprint_` 由 `heap_growth_limit_`（非 largeHeap 应用的 Java 堆硬上限，取自 `dalvik.vm.heapgrowthlimit`）动态调整。
- `Heap::Allocate` -> `TryToAllocate` -> 失败 -> `CollectGarbage` -> 再失败 -> `Heap::ThrowOutOfMemoryError`。
- 注意：`OutOfMemoryError` 是**虚拟机层**抛的，和 Linux OOM Killer（`LMKD` 杀进程）不是一回事——前者是 Java 堆超额，后者是整机/per-process 物理内存（RSS）超额。`dumpsys meminfo` 看到的 `Java Heap` 接近 `heapgrowthlimit` 时最容易触发前者。

**dalvik.vm.* 属性（常被追问）**
| 属性 | 含义 | 默认值量级 |
|---|---|---|
| `dalvik.vm.heapstartsize` | 起始堆 | 8m |
| `dalvik.vm.heapgrowthlimit` | **非 largeHeap 应用 Java 堆上限**（软上限可短暂超过到 max） | 192m/256m |
| `dalvik.vm.heapsize` | **绝对上限**（largeHeap 或硬顶，`Heap::HeapMaximumSize`） | 512m |
| `dalvik.vm.heaptargetutilization` | 目标利用率（影响下次 GC 后 footprint 目标） | 0.5 |
| `dalvik.vm.heapminfree` / `heapmaxfree` | GC 后空闲区间 | 2m/8m |

> 易错：很多候选人把 `heapgrowthlimit` 和 `heapsize` 混为一谈。**`heapgrowthlimit` 是普通 App 的"日常天花板"，`heapsize` 是 `android:largeHeap="true"` 或绝对硬顶**。普通 App 即便只占 200M 也会被 `heapgrowthlimit` 卡住而 OOM，跟整机还剩多少内存无关。

---

## 3. GC 算法全景：CMS vs CC vs 分代 CC

### 3.1 问题
ART 有哪几种 GC 收集器？为什么现在默认 Concurrent Copying 而不是 CMS？"并发复制"怎么做到"不暂停应用线程"？读屏障（read barrier）到底是什么？

### 3.2 答案解析（带 AOSP 14 源码路径/方法名）

**收集器三件套（`art/runtime/gc/collector/`）**

| 收集器 | 源码 | 分配器 | 压缩 | 默认? | 特点 |
|---|---|---|---|---|---|
| **Concurrent Mark Sweep (CMS)** | `mark_sweep.cc` / `concurrent_mark_sweep.cc` | `RosAlloc`(空闲链表) | 可后台 compaction | 否（旧/兼容） | 标记-清除，碎片化时可能长暂停；避免在**前台**压缩 |
| **Concurrent Copying (CC)** | `concurrent_copying.cc` | `RegionSpace`(region bump) | 复制即压缩 | **是（A8+ 默认）** | 并发复制，暂停与堆大小无关 |
| **Generational CC** | 同一 `concurrent_copying.cc` 开 `kUseGenerationalCC` | `RegionSpace`(young/old region) | 复制 | A10+ 启用 | young 只扫新生代，full 扫整堆 |

**CC 为什么是默认（核心答点）**
1. **复制即压缩**：对象从 from-space region 复制到 to-space region，死亡对象整块丢弃，**天然无碎片**——而 CMS 的 mark-sweep 会留碎片，碎片化到分配失败时只能做一次长暂停的 compaction。
2. **暂停与堆大小无关**：CC 的总暂停时间极短且**不随堆增大而增大**（复制成本只与存活对象量相关）；CMS 的 compaction 暂停随堆碎片变长。
3. **前台体验**：CMS 为了不卡 UI，尽量把 compaction 推迟到后台，导致前台可能碎片化加剧；CC 没有这个权衡。

**"并发复制"怎么不暂停应用线程（读屏障）**
CC 在回收时**应用线程还在跑**，对象可能在你读它的瞬间被搬到 to-space。ART 用 **Baker 式读屏障** 解决：

```
任何从堆里读取一个"引用字段"的代码, 都会被编译器/解释器插入读屏障:
   Object ref = obj.field;   // 普通写法
   => 实际: Object ref = ReadBarrier::Barrier(obj, offset, obj.field);
       源码: art/runtime/read_barrier.h  ReadBarrier::Barrier()
             art/runtime/gc/collector/concurrent_copying.cc  ConcurrentCopying::ReadBarrier
   读屏障逻辑(简化):
     if (对象处于"正在被 GC 复制"的标记阶段) {
        把 obj.field 指向的 from-space 对象 复制到 to-space, 返回新地址
     }
     return 正确的(可能已移动的)引用
```

- 读屏障只在一小段"并发标记窗口"内真正干活，绝大多数时候是个**几乎零成本的判断**（对象头里有个 marking 标志位）。
- CC 的真正暂停只有两次短暂停：① 让所有线程"停下来"翻转 marking 状态（开始标记）；② 标记结束再翻转一次（结束标记）。这两次 STW 极短，与堆大小无关。
- 对比 Dalvik / 早期 CMS：有长 STW（尤其是 `GC_CONCURRENT` 的清理阶段），所以老 Android 容易掉帧。

**logcat 里的 GC 日志（必备辨识力）**
```
I/art: Background concurrent copying GC freed 2MB, 15% free, 32MB/38MB, paused 1.2ms total 23ms
      ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^  collector 名(CC)
                                   kGcCauseBackground / ForAlloc / Explicit
```
现代 ART（CC）常见 cause（`art/runtime/gc/gc_cause.h` `GcCause`）：
- `kGcCauseForAlloc`：分配失败触发（最影响卡顿）
- `kGcCauseBackground`：后台阈值触发，不卡前台
- `kGcCauseExplicit`：`System.gc()` 触发（**面试常问：为什么不要调 System.gc()** —— 它强制一次 full GC，打乱 ART 的并发计划，且 Android 上 `System.gc()` 不一定立即执行，可能被延迟/合并）
- `kGcCauseNativeAlloc`：Native 侧 `RegisterNativeAllocation` 压力触发
- `kGcCauseTrim`：内存整理/归还

---

## 4. GC 触发时机与停顿定界（性能岗必考）

### 4.1 问题
一次 UI 掉帧，你怎么判断是"GC 导致的"还是"主线程耗时"还是"渲染线程/VSync"问题？在 Perfetto / Systrace 里 GC 长什么样？

### 4.2 答案解析

**GC 与卡顿的关系（因果链）**
```
主线程每帧 doFrame (Choreographer) 预算 ~16.6ms (60Hz) / ~8.3ms (120Hz)
   |
   | 你的代码在帧内 new 了大量临时对象 (如拼接字符串/Compose 重组闭包)
   v
Java 堆快速触顶 -> 分配失败 -> kGcCauseForAlloc 触发 CC
   |
   | CC 的两次短 STW 各 ~1-3ms, 但若 young region 晋升压力大/频繁 ForAlloc
   v
doFrame 超时被 Vsync 跳过 -> 掉帧(jank), Perfetto 里出现 "Jank" 标注
```
注意：**CC 单次暂停很短，但"频繁触发"本身会累积**；更严重的是**内存抖动**（短时间内大量分配+回收）会让 `ForAlloc` 型 GC 在帧内多次发生，直接吃掉帧预算。

**Perfetto / Systrace 定界步骤（实战）**
1. 抓 trace：`adb shell perfetto --txt -c - -o /data/memtrace.pftrace <<EOF ... EOF`（或用 `ui.perfetto.dev` 的 Android 模板勾选 `Art`、`binder`、`gfx`、`sched`）。
2. 在 trace 里搜 `GC` / `ConcurrentCopying` / `HeapTaskDaemon`：GC 线程活动 + 主线程上对应的 `Alloc` 暂停段。
3. 看 `Choreographer#doFrame` 黄色/红色段：如果超时被 `msg` 标记为 `Jank`，且同一窗口内有 `HeapTaskDaemon` 密集活动，基本锁定 GC。
4. 区分：主线程自身 `inflate`/`measure` 长 -> 是 View 问题；`RenderThread` 长 -> 是绘制/合成；`binder` 长 -> 是 IPC；`HeapTaskDaemon`/帧内 `Alloc` 长 -> 是 GC/抖动。

**16KB 页面（Android 15/16 强制，呼应 7/27）对 GC 的二次影响**
- A14 仍然 4KB 页；A15+ 默认 16KB 页。region 大小/TLAB 粒度随之变化，`RegionSpace` 的 region 数变少、单 region 内可容纳对象数变化，极端情况下会改变 young/old 晋升节奏——是 2026 面试的"版本差异"加分点。

---

## 5. 内存抖动与卡顿（代码级根治）

### 5.1 问题
什么是内存抖动（Memory Churn）？为什么在 `onDraw` / `onBindViewHolder` / Compose 重组里 new 对象是毒药？怎么根治？

### 5.2 答案解析

**定义**：短时间内**频繁分配 + 频繁回收**同量级的小对象，导致 GC（尤其 `ForAlloc`）被反复触发。

**毒药代码（面试常给这段代码让你挑错）**
```java
// 反例：在列表滚动/每帧回调里 new StringBuilder / 数组 / 闭包
void onDraw(Canvas c) {
    for (int i = 0; i < items.size(); i++) {
        String s = new StringBuilder().append(prefix).append(items.get(i)).toString(); // 每帧 N 个新对象
        c.drawText(s, ...);
    }
}
```
每次 `new StringBuilder()` + `toString()` 都走 TLAB 分配，帧内产生数百个短命对象 -> 下一帧前 `ForAlloc` GC -> 抖动。

**根治三板斧**
1. **对象复用（对象池）**：`Handler` 的 `Message` 就是经典池化（`Message.obtain()` 复用 `sPool` 链表，见 8/12 核心基础篇）；`StringBuilder` 用 `setLength(0)` 复用；`RecyclerView.ViewHolder` 复用。
2. **避免在帧回调里分配**：`onDraw` / `onBindViewHolder` / Compose 重组 lambda 里不 new 集合、不拼接字符串。Compose 尤其要注意**重组闭包捕获**（`8/29`/前文 Compose 篇）——每次重组都会 new 一堆 `Modifier`/状态包装/lambda，所以 Composable 里不要做重分配。
3. **大对象/长生命周期对象移出热路径**：大数组走 `LargeObjectSpace`，长生命周期对象用单例/缓存而非每帧重建。

**Compose 特有关注（联动 8/15 Compose 编译器篇）**
- Compose 声明式 = 对象爆炸：`Modifier`、`State` 包装、重组闭包大量短命对象。ART 的 young CC 正是为这种负载设计的——但若 Composable 里捕获了 `ViewModel`/`Context`（闭包捕获泄漏，见 5.2 延伸），会晋升到 old region 长期占用，变成真实内存压力。

---

## 6. Native 内存与图形内存（Java 堆之外的"隐形吞噬者"）

### 6.1 问题
为什么我的 App `Java Heap` 才 80M，却还是被 LMKD 杀了？Native 内存、Bitmap 像素、GraphicBuffer 分别存在哪？`/proc/pid/smaps` 的 `Pss` 怎么算？

### 6.2 答案解析（带 AOSP 14 源码路径）

**进程内存四大块（误区：只盯 Java Heap）**
```
App 进程 RSS = Java Heap + Native Heap + Graphics + Code(dex/oat/jit) + Stack + Others
   |
   |  dumpsys meminfo <pkg> 四个主分类:
   |   Java Heap   <- ART RegionSpace/LargeObjectSpace (受 heapgrowthlimit 管)
   |   Native Heap <- libc malloc(Scudo) / new(nothrow) / JNI 直接分配
   |   Graphics    <- Bitmap 像素(Native/GraphicBuffer) + GL 纹理 + Surface
   |   Code        <- dex(已加载) + oat(AOT) + JIT 编译产物 + .so
```

**Bitmap 像素内存演进（必考陷阱题）**
- **Android 8.0（API 26）之前**：Bitmap 像素存在 **Java 堆**（Dalvik/ART 堆），所以一张 4MB 图直接吃 Java 堆配额、容易 OOM、还会触发大 GC。
- **Android 8.0+**：像素下沉到 **Native 堆**（`frameworks/base/libs/hwui/hwui/Bitmap.cpp` `Bitmap::allocateHeapBitmap` -> `calloc` 分配像素；早期版本用 ashmem fd mmap，后统一到 native heap）。Java 侧只留一个轻量 `Bitmap` 壳对象（含 `mNativePtr`）。**所以现在大图主要吃 Native 内存，不再直接顶 Java 堆上限，但仍计入进程 RSS 并受 LMKD 约束**。
- **`Bitmap.Config.HARDWARE`**：像素直接放 **GPU 可访问内存**（`GraphicBuffer`，Gralloc/dmabuf），连 App 进程 Native 堆都不占，最省系统内存，但**不能被 CPU 读取/软件绘制**——这是"为什么 HARDWARE bitmap 不能 `getPixels()`"的底层原因。
- `BitmapFactory.Options.inBitmap`：复用同一块像素内存，避免反复分配（经典优化）。

**Native 分配器（联动 8/28 系统层 Rust 化与内存安全边界）**
- Android 10+ libc 默认用 **Scudo**（hardened allocator，`external/scudo`，由 `MALLOC_IMPL=scudo` 启用），取代旧 jemalloc，抗堆溢出/堆喷射。
- ARM64 上 **MTE（Memory Tagging Extension）** 可在调试/部分量产机型开启，硬件级检测 use-after-free / 越界（GKI 内核 + Scudo 配合）。
- `RegisterNativeAllocation(size)`（`art/runtime/gc/heap.cc`）：Native 侧（如 Bitmap 像素、DirectByteBuffer）分配大块时通知 ART，计入 GC 压力，可触发 `kGcCauseNativeAlloc`——**这就是为什么大 Native 分配也会间接引发 Java GC**。

**ion / dmabuf（图形内存底座）**
- 老内核用 `/dev/ion`（`ion_alloc`）；Android 12+ / GKI 统一改用 **`/dev/dma_heap/system`**（dmabuf heaps）。Gralloc 从 dmabuf heap 分配 `GraphicBuffer`（`frameworks/native/libs/ui/GraphicBuffer.cpp` `GraphicBufferAllocator`），跨进程用 Binder 传 `fd`（零拷贝，呼应 7/24 图形多媒体通信篇）。
- `ashmem`（匿名共享内存）在 Android 10+ 基本被 `/dev/dma_heap` + `memfd` 替代。

**Pss 计算（易错点）**
- `Pss`（Proportional Set Size）= 物理页大小 / **共享该页的进程数**。一块被 5 个进程共享的物理页，每个进程记 `1/5` 到自己的 Pss。`dumpsys meminfo` 的 `TOTAL` 列就是 Pss 求和——所以"共享库多"的 App 真实独占内存其实比 RSS 小。面试常问"为什么我的进程 RSS 比 meminfo 显示的大"——因为 RSS 含共享页全量，Pss 才反映独占。

**A17 Memory Limiter（API 37，联动 7/30）**
- A17 引入应用级**内存硬限额**，超过后联动 `LMKD` 优先回收/限制该进程，与分代 GC（及时回收 young）协同把峰值内存压住。面试答"内存优化"必须提到它已从"只靠 LMKD 软回收"演进到"限额 + GC 协同"。

---

## 7. 内存优化面试题：泄漏、LeakCanary、工具链

### 7.1 问题
什么是内存泄漏？常见场景有哪些？**LeakCanary 为什么不用 `finalize()` 而用 `WeakReference` + `ReferenceQueue`？** 怎么用 HPROF / MAT / Memory Profiler / Perfetto 定位泄漏？

### 7.2 答案解析

**内存泄漏本质**：对象已经不用了，却被 **GC Root 强引用链** 挂着，GC 永远回收不掉（`8/30` 权限篇也强调"强引用是根因"）。

**常见泄漏场景（面试背诵级）**
1. **静态变量 / 单例持有 Activity/Context**：`static Context sCtx = activity;` —— Activity 销毁后仍被静态引用。
2. **非静态内部类 / 匿名类隐式持有外部类**：`Handler` 匿名内部类持有 Activity；`Thread`/`AsyncTask` 未取消，跑完后还引用 Activity。
3. **未注销监听**：`BroadcastReceiver`、`EventBus`、`LifecycleObserver`、`ContentObserver` 在 `onDestroy` 没 `unregister`。
4. **资源未关闭**：`Cursor` / `InputStream` / `Bitmap`（`recycle()`，不过 A8+ Native 像素由 GC 管，仍建议大图及时置 null）/ `WebView`（建议独立进程）。
5. **Compose 闭包捕获**（见 5.2）：重组 lambda 捕获 ViewModel/Context，被长生命周期状态持有。

**LeakCanary 原理（2026 死亡陷阱题）**
> 核心误区：很多人以为 LeakCanary 用 `finalize()`。错。它用 **`WeakReference` + `ReferenceQueue`**。

```
1) AppWatcher 在 Activity.onDestroy / Fragment.onDestroyView 等生命周期点
   把"被观察对象"包进一个 WeakReference, 并关联一个 ReferenceQueue:
     art/runtime/ 侧概念:  WeakReference(ref, referenceQueue)
   源码: leakcanary-object-watcher 的 ObjectWatcher.watch()
2) 5s 后(默认) 触发一次 GC(System.gc() + Runtime.runFinalization + 再 gc)
3) 检查该 WeakReference 是否已进入 ReferenceQueue:
   - 已进入 => 对象已被回收 => 没泄漏
   - 没进入 => 对象仍被强引用 => 怀疑泄漏, 进入 retainedObjects
4) 若 retainedObjects 在再等 5s 后仍未清空 => 触发 heap dump
   leakcanary-android 的 AndroidHeapDumper.dumpHeap() -> Debug.dumpHprofData(path)
5) HeapAnalyzerService(shark 库) 解析 HPROF:
   - 构建 HeapGraph(对象图)
   - 从 GC Roots 出发 BFS, 找到"泄漏对象"到 Root 的最短强引用路径
   - 生成 LeakTrace(哪行代码、哪个字段持有了它)
```
**为什么不用 `finalize()`**：`finalize()` 不可靠（调用时机不确定、可能根本不调用、且会让对象延迟到下一轮 GC 才死，反而加重内存压力）；`WeakReference` + `ReferenceQueue` 是 JVM 标准机制，对象一旦弱可达就被入队，判定干净利落。

**工具链（定位四件套）**
| 工具 | 用途 | 关键命令/入口 |
|---|---|---|
| `dumpsys meminfo <pkg>` | 看四大块内存分布、Activity 数量 | `adb shell dumpsys meminfo com.xxx` |
| Android Studio **Memory Profiler** | 实时 Java/Native 内存曲线、捕获堆转储、看 `Allocations` | AS Profiler 面板 |
| **HPROF + MAT** | 离线分析泄漏引用链、`Dominator Tree` 找大对象 | `Debug.dumpHprofData` / MAT `OQL` |
| **Perfetto** | 看 GC 频率/停顿、Native 分配（`heapprofd` 可抓 native 分配栈） | `ui.perfetto.dev` 勾 `Art` + `heapprofd` |

> 延伸：native 内存泄漏用 **`heapprofd`**（Perfetto 的 native heap profiler）抓分配调用栈，比 `malloc_debug` 轻量；`libmemunreachable` 可检测 C++ 侧不可达内存。

---

## 8. 易错点速记 + 高频追问连环考

### 8.1 易错点速记（12 条，先背再理解）
1. **CC 是默认，不是 CMS**；CMS 还在但仅兼容/特定场景。
2. **读屏障**是 CC 并发复制不暂停线程的关键，不是"GC 时停一下"。
3. `heapgrowthlimit` ≠ `heapsize`：前者是普通 App 日常天花板。
4. **OOM（Java）≠ LMKD 杀进程（Native/RSS）**，两套机制。
5. **Bitmap 像素 A8+ 在 Native/Graphics，不在 Java 堆**；HARDWARE 在 GPU 显存。
6. **不要调 `System.gc()`**：强制 full GC 打乱并发计划。
7. **LeakCanary 用 WeakReference+ReferenceQueue，不是 finalize**。
8. **Pss 是平分共享页**，RSS 含共享全量，二者不等。
9. `LargeObjectSpace` 装大基本类型数组，**不参与 region 复制**。
10. **Generational GC 经 art APEX Mainline 热更**（A17），不依赖整机 OTA。
11. `alloc` 失败先 GC 再抛 OOM（`Heap::ThrowOutOfMemoryError`）。
12. Native 大分配经 `RegisterNativeAllocation` 会触发 `kGcCauseNativeAlloc`。

### 8.2 高频追问连环考（考官最爱这么接）
- Q：`new Object()` 分配失败会立刻 OOM 吗？ → A：先 `ForAlloc` GC 一次，再失败才抛。
- Q：CC 真的一次都不 STW 吗？ → A：两次极短 STW（翻转 marking 状态），与堆大小无关。
- Q：为什么 Compose 项目 GC 压力更大？ → A：重组对象爆炸；young CC 缓解但闭包捕获会晋升 old。
- Q：我 Java 堆才 80M 为什么被杀了？ → A：看 Native/Graphics Pss，LMKD 按 RSS/Pss 杀。
- Q：`System.gc()` 一定会马上执行吗？ → A：ART 可能延迟/合并，且是全堆 full GC，成本高。
- Q：Bitmap 为什么不吃 Java 堆了？ → A：A8+ 像素下沉 Native；HARDWARE 在 GPU 显存。
- Q：怎么证明一个对象泄漏了？ → A：LeakCanary 的 WeakReference 入队判定 + shark 最短引用链。
- Q：A17 在内存上还改了什么？ → A：分代 GC Mainline 热更 + Memory Limiter 硬限额 + Scudo/MTE 安全。

### 8.3 延伸阅读（按优先级）
- AOSP：`art/runtime/gc/heap.h`、`concurrent_copying.cc`、`region_space.cc`、`space/` 全套、`gc_cause.h`、`read_barrier.h`。
- AOSP：`frameworks/base/libs/hwui/hwui/Bitmap.cpp`（像素分配）、`frameworks/native/libs/ui/GraphicBuffer.cpp`（dmabuf）。
- 官方文档：《Debug ART garbage collection》《Overview of ART garbage collection》。
- 工具：Perfetto `heapprofd`、LeakCanary `shark` 源码、`libmemunreachable`。
- 联动前序：7/29 A17 分代 GC、7/30 A17 Memory Limiter 与 LMKD、8/10 ART 运行时与 dex2oat（镜像/Profile）、8/28 系统层 Rust 化与内存安全边界（Scudo/MTE）。

---

## 9. 与前序 47 篇的体系联动索引

> 本篇（ART 内存/GC/内存优化）补全了"运行时底座"真缺口，与以下篇章构成完整知识网：

| 主题 | 关联篇章 | 衔接点 |
|---|---|---|
| ART 运行时 / dex2oat / Profile | 8/10 ART 运行时与 dex2oat 冷启动、7/29 A17 分代 GC | 镜像/CompilerFilter/分代 GC 同属 ART 模块 |
| 内存安全（Scudo/MTE/Rust） | 8/28 系统层 Rust 化与内存安全边界 | libc 分配器与硬件内存安全 |
| A17 内存限额 / LMKD | 7/30 渲染合成与 A17 安全内存 | Memory Limiter 与 LMKD/分代 GC 协同 |
| 权限/SELinux（强引用根因） | 8/30 权限模型全景与隐私边界演进 | 内存泄漏的"强引用根"与"权限根"同构 |
| Compose 对象爆炸 | 8/15 Compose 编译器与运行时、8/3 AppFunctions | 重组闭包是 GC 压力源 |
| 卡顿/ANR 定界 | 8/6 全链路排查实战、8/16 输入系统、7/24 图形多媒体 | GC 致 jank 的 Perfetto 定界 |
| 图形内存（dmabuf/Gralloc） | 7/24 图形多媒体通信篇 | Bitmap HARDWARE 落 GraphicBuffer |
| Native 分配/JNI | 7/23 深挖篇（JNI/hook）、7/27 系统基建 | RegisterNativeAllocation 触发 NativeAlloc GC |

---

## 10. 一页速记卡（考前 3 分钟过一遍）

```
ART 堆 = ImageSpace(共享只读) + ZygoteSpace(冻结) + RegionSpace(主分配, TLAB 无锁) + LargeObjectSpace(大数组)
分配 = TLAB bump(无锁) -> RefillTLAB -> ForAlloc GC -> 再失败 OOM(Heap::ThrowOutOfMemoryError)
GC   = 默认 CC(并发复制, 读屏障 Baker, 两次极短 STW, 暂停与堆无关)
       分代 CC(A10+, A17 经 Mainline 热更): young 扫新生代 / full 扫整堆
CMS  = 旧, RosAlloc 空闲链表, 碎片化长暂停, 前台不压缩
Bitmap = A8+ 像素下沉 Native; HARDWARE 在 GPU 显存; inBitmap 复用
内存四大块 = Java / Native(Scudo) / Graphics(dmabuf) / Code; 看 Pss 不是 RSS
泄漏 = GC Root 强引用链; LeakCanary 用 WeakReference+ReferenceQueue(非 finalize)
工具 = dumpsys meminfo / Memory Profiler / HPROF+MAT / Perfetto heapprofd
A17  = 分代 GC 热更 + Memory Limiter 硬限额 + Scudo/MTE
禁区 = 别 System.gc(); 别在 onDraw/重组里 new; 别静态持 Activity
```

> 落盘说明：本文件为当日（2026-08-31）Android Framework 热点面试题整理之「ART 内存管理与 GC 实战」专题（第四十八篇），与 `/workspace` 下 7/23 ~ 8/30 共 47 篇互为体系、可交叉索引复习。
