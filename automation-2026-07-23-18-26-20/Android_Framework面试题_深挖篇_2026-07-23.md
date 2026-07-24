# Android Framework 热点面试题 · 深挖篇（2026-07-23）

> 系列第三篇。与同日《主篇》（16 章主线）、《热点拓展篇》（10 章查缺补漏）互补，本篇专攻**此前未覆盖的深水区**：ART 内存布局与 CMC GC、Binder 驱动调试实战、Rust Binder、Input 多指分发、VSync/Frame Pacing 时序、Camera/Audio HAL、GKI KMI 模块开发、Perfetto SQL 实战。
>
> 基线：**Android 14 (UpsideDownCake, API 34)**，AOSP 分支 `android-14.0.0_rXX`，内核 GKI `android14-6.1`。所有路径均为真实 AOSP 路径。

---

## 目录

1. [ART 对象内存布局与对象头（LockWord）](#1)
2. [Android 14 新 GC：CMC（userfaultfd 并发标记压缩）](#2)
3. [ART 字节码校验（verify）与逆优化（deopt）](#3)
4. [Binder 驱动全链路调试实战（binderfs / binder_logs / tracepoint）](#4)
5. [Rust in Android：libbinder_rs 与内核 Rust 化趋势](#5)
6. [Input 深水区：多指触控的拆分与分发（split / pointer id）](#6)
7. [VSync 时序精算：VsyncSchedule、FrameTimeline 与丢帧归因](#7)
8. [Camera HAL 全链路：CameraService → Camera3Device → AIDL HAL](#8)
9. [Audio 全链路：AudioFlinger MixerThread / FastMixer / AAudio MMAP](#9)
10. [GKI 内核模块开发：KMI、符号列表与 DDK](#10)
11. [Perfetto SQL 实战：用 SQL 查出主线程卡顿真凶](#11)
12. [三篇交叉索引与复习路线](#12)

---

<a id="1"></a>
## 1. ART 对象内存布局与对象头（LockWord）

### 面试题
「一个 Java 对象在 ART 里到底占多少内存？对象头里有什么？`synchronized` 的锁状态存在哪？」

### 答案解析

**对象头只有 8 字节**（两个 32 位字段），定义在 `art/runtime/mirror/object.h`：

```cpp
// art/runtime/mirror/object.h
class MANAGED LOCKABLE Object {
  ...
  // The Class representing the type of the object.
  HeapReference<Class> klass_;   // 4B，压缩指针指向 Class 对象
  // Monitor and hash code information.
  uint32_t monitor_;             // 4B，即 LockWord
};
```

与 HotSpot（MarkWord 8B + KlassPointer 4/8B）不同，ART 把哈希码、锁状态、GC 状态全部塞进 4 字节的 `monitor_`，即 **LockWord**（`art/runtime/lock_word.h`）：

LockWord 高 2 位是状态位（`kStateThinOrUnlocked / kStateFat / kStateHash / kStateForwardingAddress`）：

| 状态 | 含义 | 剩余位存什么 |
|---|---|---|
| ThinOrUnlocked | 无锁/瘦锁 | 持有线程 ID（16b）+ 重入计数（12b） |
| Fat | 胖锁（发生竞争后膨胀） | `Monitor` 对象的 MonitorId |
| Hash | 已计算过 identity hashCode | 28 位哈希值 |
| ForwardingAddress | GC 移动对象时的转发地址 | 新地址 |

关键推论（高频追问点）：
- **瘦锁 → 胖锁的膨胀**发生在 `art/runtime/monitor.cc` 的 `Monitor::MonitorEnter()`：自旋（`kExtraSpins`）等待失败后调用 `Monitor::Inflate()`。ART **没有 HotSpot 的偏向锁**（HotSpot 自己在 JDK15 后也废弃了）。
- **hashCode 与锁互斥占位**：如果对象先算了 hashCode 又被 `synchronized`，LockWord 放不下两者 → 直接膨胀为胖锁，把 hash 存进 `Monitor` 对象。这就是「调用过 hashCode 的对象加锁更贵」的底层原因。
- ��象整体布局：8B 对象头 + 字段（ART 会做字段重排：引用字段在前、按大小降序，见 `art/runtime/class_linker.cc` 的 `LinkFields()`）+ 8 字节对齐。所以 `new Object()` 实际占 **8 字节**（ART）而非 HotSpot 的 16 字节。

### 易错点
- ❌ 把 HotSpot 的 MarkWord/偏向锁那套背给 ART——面试官问的是 Android，ART 没有偏向锁。
- ❌ 认为数组对象头也是 8B：数组还有 4B 的 `length`（`mirror::Array`，`art/runtime/mirror/array.h`），对齐后头部 12→16B。

### 面试高频追问
1. 为什么 ART 的 Class 指针只要 4 字节？→ 堆地址空间限制在低 4GB（`art/runtime/gc/heap.cc` 中堆基址布局），引用本身就是 32 位压缩形式 `HeapReference<T>`。
2. `System.identityHashCode` 第一次调用做了什么？→ `Object::IdentityHashCode()`（`art/runtime/mirror/object.cc`）生成随机 hash 并 CAS 写入 LockWord。

### 延伸阅读
- `art/runtime/lock_word.h` 头部大段注释（位布局图）
- `art/runtime/monitor.cc`：`Inflate()` / `Deflate()`（GC 时还会做锁收缩！）

---

<a id="2"></a>
## 2. Android 14 新 GC：CMC（userfaultfd 并发标记压缩）

### 面试题
「Android 14 的 ART GC 有什么大变化？CMC 和之前的 CC（Concurrent Copying）有什么区别？」

### 答案解析

这是 Android 14 最硬核的变化之一：默认 GC 从 **CC（Concurrent Copying，基于读屏障 + RegionSpace）** 切换为 **CMC（Concurrent Mark-Compact，基于 Linux userfaultfd）**。

源码位置：
- CMC 收集器：`art/runtime/gc/collector/mark_compact.cc` / `mark_compact.h`
- 旧 CC 收集器：`art/runtime/gc/collector/concurrent_copying.cc`
- 空间：CMC 使用 `art/runtime/gc/space/bump_pointer_space.cc`（CC 用 `region_space.cc`）

**CC 的痛点**：
1. 读屏障（read barrier）侵入所有编译代码，常态带来 ~1-3% CPU 开销；
2. from-space/to-space 复制式回收，瞬时需要 2 倍内存；
3. 代码体积膨胀（每次引用加载都要插桩）。

**CMC 的做法**：
1. 并发标记后**原地滑动压缩**（sliding compaction），不需要双倍空间；
2. 压缩阶段不用读屏障，而是用内核 **userfaultfd**：先把堆页面权限撤掉（`UFFDIO_REGISTER`），mutator 线程访问未搬移完的页面时触发 minor fault，由 GC 线程按需搬移该页并 `UFFDIO_CONTINUE` 恢复访问——**把「停顿整个世界」变成「按页粒度的微停顿」**；
3. 应用线程几乎零屏障开销，二进制更小、常态 CPU 更省。

一句话对比：**CC 用「空间 + 读屏障」换并发，CMC 用「内核缺页拦截」换并发**。

判断当前设备 GC：`adb shell getprop ro.dalvik.vm.native.bridge` 无关，看 ART 启动日志或 `art/runtime/gc/heap.cc` 中 `kUseUserfaultfd` 相关逻辑；Android 14 上 `ro.build.version.sdk >= 34` 且内核支持 userfaultfd（GKI 5.4+ 已开启 `CONFIG_USERFAULTFD`）默认走 CMC。

### 易错点
- ❌ 还在讲「ART GC = CMS/分代标记清除」——那是 Dalvik/早期 ART 的说法。Android 8-13 主力是 CC，14 起是 CMC。
- ❌ 把 userfaultfd 说成「共享内存」：它是缺页事件转发到用户态处理的机制（`man userfaultfd`），GC 线程扮演了「页面搬运工 + 缺页处理器」。

### 面试高频追问
1. CMC 压缩时 mutator 写了旧地址怎么办？→ 压缩前 STW 一小段（flip），之后所有访问都会因页面被 UFFD 保护而阻塞到该页搬移完成，引用更新在搬移时批量完成（`MarkCompact::CompactionPause()` / `ConcurrentCompaction()`）。
2. 大对象怎么处理？→ 仍在 `large_object_space.cc`，不参与滑动压缩。
3. Zygote 空间会被压缩吗？→ 不会，`zygote_space.cc` 只标记不移动，保证共享页不被写时复制炸开。

### 延伸阅读
- `art/runtime/gc/collector/mark_compact.cc` 文件头注释（算法四阶段描述）
- 内核侧：`fs/userfaultfd.c`（android14-6.1）

---

<a id="3"></a>
## 3. ART 字节码校验（verify）与逆优化（deopt）

### 面试题
「dex2oat 编译过的方法，什么情况下会退回解释执行？debugger 一 attach 为什么 app 会变卡？」

### 答案解析

**verify（校验）**：类加载链路 `ClassLinker::DefineClass()` → `VerifyClass()`（`art/runtime/class_linker.cc`）→ `verifier::ClassVerifier::VerifyClass()` → 逐方法 `MethodVerifier`（`art/runtime/verifier/method_verifier.cc`）做数据流分析：寄存器类型推导、访问权限、跳转合法性。安装期 dex2oat 已经校验过的类会打上 `kStatusVerified`，运行时跳过 → 这也是「首次冷启动比后续慢」的因素之一（vdex 里存了校验结果，`art/runtime/vdex_file.h`）。

**deopt（逆优化）**：AOT/JIT 编译的机器码基于假设（无 debugger、类层次稳定、无异常路径）。假设被打破就要**从优化代码安全退回解释器**，核心在：
- `art/runtime/instrumentation.cc`：`Instrumentation::DeoptimizeEverything()` / `Deoptimize(ArtMethod*)`
- `art/runtime/quick_exception_handler.cc`：`DeoptimizeStack()`——把机器栈帧转换成解释器 ShadowFrame
- 入口切换：`ArtMethod::SetEntryPointFromQuickCompiledCode()` 换成 `GetQuickToInterpreterBridge()`

**触发 deopt 的典型场景**（追问必考）：
1. **Debugger attach / 断点**：JDWP 走 `Instrumentation`，需要方法进出、单步事件 → 全局或按方法 deopt → 这就是「attach 后 app 明显变卡」的原因；
2. Instrumentation stubs：方法 trace（`Debug.startMethodTracing`）；
3. 结构性重定义（JVMTI redefine，热修复框架有时触碰）；
4. HDeoptimize 指令：编译器对边界检查消除（BCE）等激进优化埋的兜底点（`art/compiler/optimizing/`）。

### 易错点
- ❌ 「AOT 编译过就永远跑机器码」——deopt 随时可能发生，且是**逐栈帧**转换，不是简单重启方法。
- ❌ 混淆 vdex/odex/art 三个产物：vdex = dex + 校验结果；odex(oat) = 机器码；.art = 预初始化镜像（类对象/字符串）。

### 面试高频追问
1. JIT 的机器码存哪？→ `art/runtime/jit/jit_code_cache.cc`，dual-mapping（一段 rx 一段 rw）防 W^X 冲突。
2. Baseline Profile 为何能加速首启？→ 安装期 dex2oat 只编 profile 中的热点方法（speed-profile），其余留给 JIT——省安装时间又保证首屏路径是 AOT 的。

### 延伸阅读
- `art/runtime/oat_file_manager.cc`（oat 加载校验）
- `art/dex2oat/dex2oat.cc`（compiler-filter：verify / speed-profile / speed）

---

<a id="4"></a>
## 4. Binder 驱动全链路调试实战（binderfs / binder_logs / tracepoint）

### 面试题
「线上出现 Binder 调用卡死/失败，怎么从驱动层拿到证据？说说你会看哪些节点、哪些字段。」

### 答案解析

Android 14 的 binder 已迁移到 **binderfs**（`drivers/android/binderfs.c`），调试节点在：

```
/dev/binderfs/binder_logs/
├── state          # 所有进程的 node/ref/thread/buffer 全量快照
├── stats          # 全局统计：BC/BR 命令计数、事务数、死亡通知数
├── transactions   # 当前正在进行的事务
├── transaction_log        # 最近 32 条已完成事务（环形缓冲）
├── failed_transaction_log # 最近 32 条失败事务 ★排障首选
└── proc/<pid>     # 单进程详情
```

（旧世界在 `/sys/kernel/debug/binder/`，userdebug 版本仍可用。）

**transaction_log 一行怎么读**（`drivers/android/binder.c` 的 `print_binder_transaction_log_entry()`）：

```
8: call  from 1234:1250 to 567:0 context binder node 42 handle 3 size 148:0 ret 0/0 l=0
```
→ 第 8 条，pid 1234 线程 1250 → 目标进程 567（线程 0=尚未分配），node/handle 定位服务，size 数据大小，ret 为 `BR_` 返回码。

**排障字段速查**：
- `failed_transaction_log` 里 `ret 29189/-28` → `-28 = ENOSPC`：目标进程 **binder buffer 耗尽**（async 空间只有 mmap 的一半，约 512KB）；
- `state` 中某进程 `requested_threads_started=15` 且全部线程 `state=BINDER_LOOPER_STATE_WAITING` 之外 → **线程池耗尽**，典型是对端持锁做同步 binder 互调；
- `pending async transactions` 堆积 → oneway 风暴（如疯狂 notify 回调）。

**动态追踪**：内核 tracepoint 定义在 `drivers/android/binder_trace.h`：
```bash
echo 1 > /sys/kernel/tracing/events/binder/binder_transaction/enable
echo 1 > /sys/kernel/tracing/events/binder/binder_transaction_received/enable
cat /sys/kernel/tracing/trace_pipe
```
两个事件的 `debug_id` 相同即可配对，算出**驱动内排队时延**。Perfetto 抓 trace 时勾选 `binder_driver` 类目就是采的这些点。

**用户态一键分析**：ANR trace 中 `binder:` 开头的线程栈 + `am trace-ipc start/stop --dump-interactions`。

### 易错点
- ❌ 只会说「一次拷贝」原理，不知道任何可落地的排障节点——大厂面试官现在专挑这个区分度。
- ❌ 以为 buffer 满只影响本次调用：async 事务 ENOSPC 时驱动直接丢弃并返回失败，**发送端很多框架代码不检查 oneway 返回值**，表现为「回调莫名丢失」。

### 面试高频追问
1. 同步事务卡住时，如何找到「卡在谁身上」？→ `transactions` 节点显示 outstanding 事务的 from/to，配合对端线程的内核栈（`/proc/<pid>/task/<tid>/stack`）。
2. `binder_alloc.c` 中 buffer 分配是什么算法？→ 按大小组织的红黑树 `free_buffers`，best-fit，分裂合并（`binder_alloc_new_buf()`）。

### 延伸阅读
- `drivers/android/binder_alloc.c`：`debug_low_async_space_locked()`（async 空间水位告警）
- 同日主篇 Binder 三章（原理）+ 本章（实战）配套复习

---

<a id="5"></a>
## 5. Rust in Android：libbinder_rs 与内核 Rust 化趋势

### 面试题
「听说 Android 在用 Rust 重写系统组件？Rust 怎么和 Binder/C++ 服务互通？」

### 答案解析

Android 12 起 AOSP 正式支持 Rust 写平台代码，Android 13/14 中已上线的 Rust 组件：**Keystore2**（`system/security/keystore2/`）、**DNS-over-HTTP/3**（`packages/modules/DnsResolver`）、**Ultra-wideband stack**（`packages/modules/Uwb`）、**virtualization/AVF**（`packages/modules/Virtualization`，pVM firmware）。

**Rust Binder 绑定**：`frameworks/native/libs/binder/rust/`
- crate 名 `binder`，核心文件 `src/binder.rs`（`Interface` / `Remotable` trait）、`src/proxy.rs`（`SpIBinder`）、`src/native.rs`（`Binder<T>` 服务端）；
- 底层不重写协议，而是通过 `libbinder_ndk` 的 C ABI 包一层（`sys` 模块 FFI），与 C++/Java Binder **完全互通**；
- AIDL 编译器直接支持 `--lang=rust`（`system/tools/aidl`），`aidl_interface` Soong 模块加 `backend: { rust: { enabled: true } }` 即生成 Rust stub。

服务端示例形态：
```rust
use binder::{BinderFeatures, Interface};
impl Interface for MyService {}
let service = BnMyService::new_binder(MyService, BinderFeatures::default());
binder::add_service("myservice", service.as_binder());
```

**内核侧**：android14-6.1 GKI 已带 `CONFIG_RUST` 基础设施（`rust/` 目录），Binder 驱动的 Rust 重写版（Rust Binder driver）在更新的内核分支上试点——面试聊到即可，Android 14 量产内核仍是 C 版 `binder.c`。

**为什么是 Rust**（追问必考）：Android 安全公告中 ~70% 高危漏洞是内存安全问题；Rust 在编译期消灭 UAF/越界，且无 GC、无运行时开销，适合替代 C/C++ 写解析器、驱动毗邻层这类「攻击面大」的代码。Google 公布的数据：随 Rust 代码占比上升，内存安全漏洞占比已降到 40% 以下。

### 易错点
- ❌ 「Rust Binder 是新协议」——错，只是语言绑定，wire protocol 与内核接口不变。
- ❌ 认为 app 开发也能直接用 platform Rust binder crate——NDK 对外只暴露 `libbinder_ndk` C API 与 AIDL NDK backend，Rust crate 目前是平台内部使用。

### 面试高频追问
1. Rust 和 C++ 服务共存时，异常/panic 怎么处理？→ Rust panic 默认 abort（平台构建 `panic=abort`），Binder 层错误用 `Status` 传递，与 C++ `binder::Status` 对应。
2. AVF/pKVM 是什么？→ `packages/modules/Virtualization`，基于 pKVM（内核 `arch/arm64/kvm/hyp/nvhe/`）的受保护虚拟机，跑隔离负载（如隔离编译 DICE、证书操作）。

### 延伸阅读
- `frameworks/native/libs/binder/rust/README.md`
- `system/security/keystore2/src/`（最成熟的 Rust 系统服务范例）

---

<a id="6"></a>
## 6. Input 深水区：多指触控的拆分与分发（split / pointer id）

### 面试题
「两根手指分别按在两个不同的 View/窗口上，事件是怎么被拆开的？pointer id 和 pointer index 有什么区别？」

### 答案解析

**驱动 → 框架**：多指来自内核多点触控协议 B（slot 机制，`Documentation/input/multi-touch-protocol.rst`），`EV_ABS/ABS_MT_SLOT + ABS_MT_TRACKING_ID`。InputReader 的 `TouchInputMapper`（`frameworks/native/services/inputflinger/reader/mapper/TouchInputMapper.cpp`）把 slot 状态合成 `NotifyMotionArgs`，每个触点分配稳定的 **pointer id**。

**窗口级拆分（split touch）**：`InputDispatcher::findTouchedWindowTargetsLocked()`（`frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp`）：
- 窗口带 `FLAG_SPLIT_TOUCH`（默认开启）时，`ACTION_POINTER_DOWN` 会按落点命中不同窗口，`TouchState` 记录每个窗口拥有哪些 pointer；
- 发给每个窗口前调用 `splitMotionEvent()`：**只保留属于该窗口的 pointer，并把动作重映射**——例如全局第二指落下（POINTER_DOWN）对窗口 B 而言是它的第一根手指，事件被改写成 `ACTION_DOWN`。这是很多人不知道的关键点。

**View 层拆分**：`ViewGroup.dispatchTouchEvent()`（`frameworks/base/core/java/android/view/ViewGroup.java`）用 `TouchTarget` 链表记录「pointer id 集合 → 子 View」映射；`FLAG_SPLIT_MOTION_EVENTS`（3.0+ 默认开）时，`ACTION_POINTER_DOWN` 可命中新的子 View，同样经 `MotionEvent.split()` 改写为该子 View 的 `ACTION_DOWN`。

**id vs index**（必考）：
- `pointer id`：触点生命周期内稳定不变（0~31）；
- `pointer index`：事件数组下标，**随其他手指抬起会变**；
- 正确写法：`event.findPointerIndex(mActivePointerId)`；`ACTION_POINTER_UP` 时若抬起的是 active 指针，需换 `mActivePointerId`（经典模板见 `ScaleGestureDetector.java`）。

### 易错点
- ❌ 用 index 存成员变量跨事件使用 → 多指场景乱跳，低级但高频的实战 bug。
- ❌ 以为 `getX()` 无参就是「当前手指」：无参恒为 index 0。
- ❌ 认为拆分后各窗口收到的是同一个 MotionEvent 副本：动作码都可能被改写（POINTER_DOWN→DOWN）。

### 面试高频追问
1. `ANR 的 input timeout` 判定在哪？→ `InputDispatcher::processAnrsLocked()`，waitQueue 头部事件超时（默认 5s）→ 通知 `InputManagerService` → AMS。
2. 手势导航的边缘滑动怎么优先拿到事件？→ `EdgeBackGestureHandler`（SystemUI）注册 `InputMonitor`（`frameworks/base/services/core/java/com/android/server/input/InputManagerService.java` 的 `monitorGestureInput`），旁路监听而非窗口竞争。

### 延伸阅读
- `frameworks/native/services/inputflinger/dispatcher/TouchState.cpp`
- `frameworks/base/core/java/android/view/ScaleGestureDetector.java`（官方多指模板）

---

<a id="7"></a>
## 7. VSync 时序精算：VsyncSchedule、FrameTimeline 与丢帧归因

### 面试题
「App 收到的 VSYNC 和硬件 VSYNC 是一回事吗？`Choreographer` 的帧回调时间是怎么算出来的？怎么精确归因一帧掉在谁头上？」

### 答案解析

**不是一回事。** 硬件 VSYNC（HW_VSYNC）只在校准期开启，SurfaceFlinger 用软件模型**预测**出两路虚拟 VSYNC：
- 源码：`frameworks/native/services/surfaceflinger/Scheduler/`
  - `VsyncSchedule.cpp`：管理一块屏幕的 vsync 体系
  - `VSyncPredictor.cpp`：对最近若干个 HW_VSYNC 时间戳做**最小二乘拟合**，预测未来 vsync
  - `VSyncDispatchTimerQueue.cpp`：按预测值定时回调
  - `EventThread.cpp`：把 vsync 分发给 app / sf 两类连接
- 校准闭环：预测误差超阈值 → `VSyncTracker` 重新打开 HW_VSYNC 收样本（`SurfaceFlinger::setVsyncEnabled`）。

**两路相位（vsync-app / vsync-sf）**：app 先于 sf 一个 offset 被唤醒，让「app 画完 → sf 合成」流水线化。Android 12+ 用 `WorkDuration` 模型替代旧 phase-offset（`Scheduler/VsyncConfiguration.cpp`）。

**App 侧**：`Choreographer`（`frameworks/base/core/java/android/view/Choreographer.java`）通过 `DisplayEventReceiver`（`android_view_DisplayEventReceiver.cpp` → BitTube socket）收 vsync，按 `CALLBACK_INPUT → ANIMATION → TRAVERSAL` 顺序执行。掉帧日志 "Skipped N frames" 就是 `doFrame()` 里 `jitterNanos / frameIntervalNanos`。

**精确归因：FrameTimeline**（Android 12+，14 已成熟）：
- `frameworks/native/services/surfaceflinger/FrameTimeline/FrameTimeline.cpp`
- 每帧有 token：app 的 `expectedPresentTime` / `actualPresentTime`，SF 的合成时间，Jank 分类字段 `JankType`：`AppDeadlineMissed`（app 慢）、`SurfaceFlingerCpuDeadlineMissed`（SF 慢）、`BufferStuffing`（缓冲堆积）、`DisplayHAL` 等；
- Perfetto 勾选 `FrameTimeline` 数据源，UI 里直接显示每个 janky frame 的责任方——**面试说出 JankType 枚举即可证明真的用过**。

### 易错点
- ❌ 「60Hz 就是每 16.6ms 一个硬件中断且常开」：HW vsync 常态是关的，全靠预测模型。
- ❌ 掉帧全赖 app：BufferStuffing / SF deadline miss / 变刷新率切换期都可能背锅，先看 FrameTimeline 再定位。

### 面试高频追问
1. LTPO 高刷屏变频时 vsync 怎么办？→ `RefreshRateSelector.cpp` 按策略（触摸 boost、内容帧率 heuristics、`setFrameRate` API）选档，`VsyncSchedule` 换 period 重新预测。
2. `dequeueBuffer` 阻塞说明什么？→ BufferQueue 无空闲 slot（消费端没消费完），典型 BufferStuffing。

### 延伸阅读
- `frameworks/native/services/surfaceflinger/Scheduler/README.md`（官方时序文档，强烈推荐）
- Perfetto 文档 Frame timeline 章节

---

<a id="8"></a>
## 8. Camera HAL 全链路：CameraService → Camera3Device → AIDL HAL

### 面试题
「从 app 调 `CameraManager.openCamera()` 到出第一帧预览，链路上有哪些进程和关键对象？」

### 答案解析

**四个进程**：App → `system_server`（仅权限/策略）→ **cameraserver**（`frameworks/av/camera/cameraserver/`，独立进程）→ **camera provider HAL 进程**（`android.hardware.camera.provider@..-service`，vendor 侧）→ 内核 V4L2/私有驱动。

链路与源码：
1. App：`CameraManager.openCamera()`（`frameworks/base/core/java/android/hardware/camera2/CameraManager.java`）→ binder 到 cameraserver 的 `CameraService::connectDevice()`（`frameworks/av/services/camera/libcameraservice/CameraService.cpp`）；
2. cameraserver 创建 `CameraDeviceClient`，底层设备抽象是 `Camera3Device`（`frameworks/av/services/camera/libcameraservice/device3/Camera3Device.cpp`）；
3. `Camera3Device` 通过 **AIDL Camera HAL**（Android 13 起新 HAL 用 AIDL：`hardware/interfaces/camera/device/aidl/`，`ICameraDevice / ICameraDeviceSession`）调 vendor 实现；
4. 配流：`configureStreams()`；请求循环：app 每帧一个 `CaptureRequest` → `Camera3Device::RequestThread`（同文件内）批量 `processCaptureRequest()` 下发 → HAL 异步回 `processCaptureResult()` + buffer；
5. 预览 buffer 是 `Surface`（BufferQueue 生产端在 HAL/cameraserver 侧，消费端是 SurfaceFlinger/SurfaceTexture）——**零拷贝**，靠 gralloc/ion(dmabuf) 句柄跨进程传 fd。

**关键设计（追问点）**：
- **request/result 流水线**：HAL3 是「每帧显式请求」模型（对比 HAL1 的 start-preview 黑盒），in-flight 深度由 `pipelineMaxDepth` 描述；
- **metadata**：`CameraMetadata`（`system/media/camera/`）压缩键值表承载 3A 参数；
- cameraserver 独立于 system_server 的原因：HAL 崩溃隔离 + 权限最小化（面试常问「为什么摄像头不放 system_server」）。

### 易错点
- ❌ 说 Camera HAL 还是 HIDL：Android 13+ 新设备是 AIDL HAL（HIDL 已冻结废弃）。
- ❌ 认为预览帧会经过 app 进程内存：Surface 模式下 app 只传 Surface 句柄，数据 fd 直达消费端。

### 面试高频追问
1. 多摄逻辑相机怎么暴露？→ `ICameraProvider` 报 logical camera + physical id 列表，metadata `ANDROID_LOGICAL_MULTI_CAMERA_*`。
2. 拍照卡顿如何定位？→ Perfetto + `dumpsys media.camera`（in-flight 请求、各流 buffer 状态）。

### 延伸阅读
- `hardware/interfaces/camera/device/aidl/android/hardware/camera/device/ICameraDeviceSession.aidl`
- `frameworks/av/services/camera/libcameraservice/device3/Camera3OutputStream.cpp`

---

<a id="9"></a>
## 9. Audio 全链路：AudioFlinger MixerThread / FastMixer / AAudio MMAP

### 面试题
「一段 PCM 从 AudioTrack 到扬声器经过了什么？普通路径延迟为什么高？低延迟方案（FastTrack/AAudio MMAP）原理是什么？」

### 答案解析

**普通路径**：
1. App `AudioTrack.write()`（`frameworks/base/media/java/android/media/AudioTrack.java` → `frameworks/av/media/libaudioclient/AudioTrack.cpp`）写入**共享内存环形缓冲**（`audio_track_cblk_t`，`AudioTrackShared.cpp`——AudioTrack 与 AudioFlinger 之间是共享内存+futex，不是每帧 binder！）；
2. **AudioFlinger**（`frameworks/av/services/audioflinger/AudioFlinger.cpp`）中该输出设备对应一个 `MixerThread`（`Threads.cpp`），周期性（如 20ms 一个 buffer）把所有活跃 Track 用 `AudioMixer`（重采样/音量/混音）混成一路；
3. 混音结果写入 **Audio HAL**：Android 14 新平台用 AIDL HAL（`hardware/interfaces/audio/aidl/`，`IModule/IStreamOut`），经 tinyalsa（`external/tinyalsa`）`pcm_write()` 到内核 ALSA 驱动（`sound/soc/`）→ DSP/Codec。
4. 策略侧：`AudioPolicyService` + `audio_policy_configuration.xml` 决定路由（哪个 device、哪个 output profile）。

**延迟构成**：app 缓冲 + MixerThread 周期 + HAL 缓冲 + DSP，普通路径轻松 40-100ms。

**低延迟**：
- **FastMixer**（`frameworks/av/services/audioflinger/FastMixer.cpp`）：MixerThread 内嵌的高优先级（SCHED_FIFO）小周期（如 2-5ms）混音线程，FAST 标志的 track（`AUDIO_OUTPUT_FLAG_FAST`）直接进它；
- **AAudio MMAP**（性能巅峰）：`frameworks/av/media/libaaudio/` + `services/oboeservice/`；EXCLUSIVE 模式下 app 拿到 **HAL/DSP 缓冲区的 mmap 映射**，直接写硬件环形缓冲，旁路 AudioFlinger 混音（NOIRQ 模式连内核中断都省），延迟可 <10ms。Oboe 库是其官方 C++ 封装。

### 易错点
- ❌ 「AudioTrack 每次 write 都走 Binder」：数据面是共享内存，Binder 只在建立/控制面。
- ❌ 把 AAudio MMAP 说成「还是 AudioFlinger 混音」：EXCLUSIVE MMAP 是旁路混音器的，代价是独占设备、路由受限。

### 面试高频追问
1. 音频欠载（underrun）表现和排查？→ `dumpsys media.audio_flinger` 看 track 的 underrun 计数；FastMixer dump 有每周期时序统计。
2. 为什么通话/闹钟能打断音乐？→ AudioFocus（框架层协商，`AudioService`）+ AudioPolicy 强制路由，两层机制。

### 延伸阅读
- `frameworks/av/services/audioflinger/Threads.cpp`（`MixerThread::threadLoop()`，音频面试的「主干道」）
- `frameworks/av/media/libaaudio/src/client/AudioStreamInternal.cpp`

---

<a id="10"></a>
## 10. GKI 内核模块开发：KMI、符号列表与 DDK

### 面试题
「厂商想在 GKI 内核上加自己的驱动，流程和约束是什么？KMI 是什么？符号列表怎么回事？」

### 答案解析

**GKI 架构回顾**：Android 11+ 强制「**一个 Google 签名的通用内核镜像（boot 分区） + 厂商可加载模块（vendor_boot/vendor_dlkm 分区）**」。Android 14 对应 GKI 内核 `android14-6.1`（也支持 android14-5.15）。

**KMI（Kernel Module Interface）**：GKI 内核对模块暴露的**稳定符号+结构体 ABI 契约**。同一 KMI 世代内（如 `android14-6.1`），Google 升级内核 LTS 补丁**不允许破坏 KMI**，厂商模块无需重编。
- ABI 描述文件：内核源码树 `android/abi_gki_aarch64.stg`（Android 14 起用 STG 格式取代旧 XML）；
- **符号列表**：`android/abi_gki_aarch64_qcom`、`abi_gki_aarch64_mtk` 等——GKI 用 `CONFIG_MODULE_SIG` + **导出符号裁剪**（`CONFIG_UNUSED_KSYMS_WHITELIST`），只有列表内的符号才对模块可见。厂商要用新符号必须向 Google 提交符号列表更新（Gerrit ACK 流程）。

**模块开发/构建**：
- 构建系统：Android 14 内核用 **Kleaf（Bazel）**：`tools/bazel run //common:kernel_aarch64_dist`；模块用 `ddk_module` 规则；
- **DDK（Driver Development Kit）**：`build/kernel/kleaf/` 提供 `ddk_module()`，声明式指定 srcs/deps/内核头，保证针对正确 KMI 编译；
- 模块签名与加载：`insmod` 时内核校验 KMI 版本字符串（`vermagic`）与符号 CRC（`CONFIG_MODVERSIONS`）。

**分区落位**（追问点）：启动关键模块 → `vendor_boot`（ramdisk 阶段由第一阶段 init 加载，列表在 `modules.load`）；非关键 → `vendor_dlkm`。

**与 MTK/QCOM 的关系**：平台 BSP 驱动（如 MTK 的 `connectivity`、GPU DDK）都以 GKI 模块形式交付，`abi_gki_aarch64_mtk` 就是 MTK 报备的符号需求清单——这把本篇与主篇 MTK 章节串起来了。

### 易错点
- ❌ 「厂商还能随便改内核核心代码」：GKI 后 boot 分区内核必须过 GKI boot-time 验证（VTS `vts_gki_compliance_test`），核心改动只能走 upstream 或 vendor hook；
- ❌ 混淆 **vendor hook** 与普通模块：vendor hook（`ANDROID_VENDOR_HOOK`，`drivers/android/vendor_hooks.c`）是 Google 预埋的 tracepoint 式挂钩，允许厂商模块在调度器/内存等核心路径注入逻辑而不改核心代码——MTK DuraSpeed、各家调度优化都靠它。

### 面试高频追问
1. KMI 冻结时间点？→ 每个 GKI 世代发布后冻结（KMI freeze），之后只加不改。
2. 模块用了未导出符号会怎样？→ 链接期报 `no symbol version for xxx` / insmod `Unknown symbol`，必须提符号列表 CL。

### 延伸阅读
- `build/kernel/kleaf/docs/ddk/main.md`（DDK 官方文档）
- `common/android/abi_gki_aarch64_galaxy` 等文件直观感受各厂商符号需求

---

<a id="11"></a>
## 11. Perfetto SQL 实战：用 SQL 查出主线程卡顿真凶

### 面试题
「给你一个 100MB 的 Perfetto trace，不许用 UI 拖时间轴，用 SQL 把主线程最大的阻塞点找出来。」

### 答案解析

Perfetto trace 可用 `trace_processor`（`external/perfetto/src/trace_processor/`）加载为 SQLite 视图。核心表：
- `slice`：所有 atrace/自定义 slice（`name, ts, dur, track_id`）
- `thread` / `process`：`utid/upid` 维度
- `thread_state`：线程调度状态区间（`state`: Running / R(unnable) / S / D / ...）
- `sched`：CPU 维度调度切片
- `android_anr` / `expected_frame_timeline_slice` / `actual_frame_timeline_slice`：ANR 与 FrameTimeline

**实战 SQL 三板斧**：

① 找超长帧（>50ms 的 doFrame）：
```sql
SELECT s.ts, s.dur/1e6 AS ms, s.name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
WHERE t.name = 'com.example.app'   -- 主线程名=进程名
  AND s.name LIKE 'Choreographer#doFrame%' AND s.dur > 50e6
ORDER BY s.dur DESC LIMIT 10;
```

② 该时间窗内主线程都处于什么状态（谁在阻塞）：
```sql
SELECT ts.state, SUM(ts.dur)/1e6 AS total_ms, COUNT(*) AS cnt
FROM thread_state ts JOIN thread t USING(utid)
WHERE t.name = 'com.example.app'
  AND ts.ts > <frame_ts> AND ts.ts < <frame_ts + frame_dur>
GROUP BY ts.state ORDER BY total_ms DESC;
```
判读：`D` 多 → IO/页错误；`R` 多 → CPU 被抢（看大核被谁占）；`S` 且伴随 binder slice → 卡在同步 Binder 对端。

③ 揪出阻塞的 Binder 对端：
```sql
SELECT s.ts, s.dur/1e6 AS ms, s.name,
       EXTRACT_ARG(s.arg_set_id, 'args.dest_name') AS dest
FROM slice s JOIN thread_track tt ON s.track_id=tt.id
JOIN thread t ON tt.utid=t.utid
WHERE t.is_main_thread AND s.name = 'binder transaction' AND s.dur > 10e6
ORDER BY s.dur DESC;
```

④ 直接用官方封装（standard library）：`INCLUDE PERFETTO MODULE android.binder;` 后查 `android_binder_txns` 表，自带 client/server 线程、时延分解。

命令行批量跑：`trace_processor_shell trace.pftrace -q query.sql`，可以塞进 CI 做性能回归门禁——说到这一步基本面试官就满意了。

### 易错点
- ❌ `dur` 单位是纳秒，忘记 `/1e6` 得出离谱结论；
- ❌ 只看 slice 不看 `thread_state`：slice 只有插桩过的段，真正的调度真相（Runnable 排队、D 状态）在 thread_state；
- ❌ 主线程名判断：主线程 `thread.name` 等于进程名（可能被截断到 15 字符），稳妥用 `thread.is_main_thread`。

### 面试高频追问
1. Runnable 时间长但 CPU 空闲？→ 查 `sched` 看是否被限核（cpuset/affinity）或 uclamp/降频（结合 `cpu_frequency` counter track）。
2. 怎么在 app 里自定义 slice？→ `Trace.beginSection()/endSection()`（Java）、`ATrace_beginSection`（NDK），Perfetto 中归入对应 thread_track。

### 延伸阅读
- Perfetto 官方 Trace Analysis 文档（PerfettoSQL standard library）
- `external/perfetto/src/trace_processor/perfetto_sql/stdlib/android/`（内置 SQL 模块源码）

---

<a id="12"></a>
## 12. 三篇交叉索引与复习路线

| 主题域 | 主篇（2026-07-23） | 拓展篇（同日） | 深挖篇（本篇） |
|---|---|---|---|
| Binder | 原理/一次拷贝/线程池 3 章 | Binder 安全（callingIdentity/SELinux） | §4 驱动调试实战、§5 Rust Binder |
| ART | —— | 类加载/JIT/AOT/基线 Profile | §1 对象头、§2 CMC GC、§3 verify/deopt |
| 显示 | WMS/SF、View 三部曲 | 折叠屏/TaskFragment | §7 VSync 时序/FrameTimeline |
| Input | —— | Input 全链路 | §6 多指拆分 |
| HAL | Treble/HIDL→AIDL 总览 | —— | §8 Camera HAL、§9 Audio 全链路 |
| 内核 | GKI/LMKD/PSI | SELinux、OTA/AB | §10 KMI/DDK/vendor hook |
| 性能 | ANR、启动优化 | Perfetto 入门实战 | §11 Perfetto SQL 进阶 |

**建议复习路线**（面向资深岗）：
1. 先主篇过主线（广度）→ 2. 拓展篇补机制盲区 → 3. 本篇按目标岗位挑 3-4 章打穿（深度）；
2. 每章至少能**脱稿讲出 1 个源码文件 + 1 个函数名 + 1 个排障动作**，这是「背题」与「懂原理」的分水岭；
3. 系统/BSP 岗重点：§4/§10 + 主篇 MTK 章；性能岗：§2/§7/§11；多媒体岗：§8/§9；安全岗：§5 + 拓展篇 Binder 安全。

---

*生成时间：2026-07-23 · 基线 Android 14 (API 34) / GKI android14-6.1 · 本系列由每日自动化任务产出*
