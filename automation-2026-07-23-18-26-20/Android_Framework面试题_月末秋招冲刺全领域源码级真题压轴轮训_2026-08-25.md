# Android Framework 面试题 · 月末秋招冲刺 · 全领域源码级真题压轴轮训（2026-08-25）

> 系列第 42 篇。前 41 篇（约 260 专题）已闭环主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧 AI + 源码 walk + Perfetto SQL + 基础八股 + 三版真题大乱斗 + Native 稳定性 + Compose 编译器/运行时 + 输入系统 + HAL/内核 + AAOS + 启动链路 + A18 桌面融合。
>
> 本篇定位：**月末秋招冲刺压轴轮训**。把用户显式列出的全部领域（Handler/Looper、Binder、AMS/ATMS、WMS、View、App 启动、内存/卡顿/ANR、Jetpack/Compose、HAL、Linux kernel、drivers、MTK）按「当月最高频真题 + 最易被漏掉的深点 + AOSP 14 源码路径 + 高频追问 + 易错点」重新焊成一份可直接口头复述的压轴卡，并**收官最后一个挂起真缺口：Compose 编译器 `ComposableFunctionBodyTransformer` IR 改写内部逐行走读**（#30/#36 只到生成代码级，本文下钻到 IR 改写实现）。
>
> 锚定（2026-08 当月热点，来源：Google I/O 2026 官方演讲 + 第三方 2026 Android 高级岗题库 mzlw.cn / CSDN）：秋招 2026 最高频深考点 = Handler/Looper（epoll/泄漏）、Binder（一次拷贝/安全/线程池）、Compose 重组/稳定性、ANR、Native crash、量化指标；Google I/O 2026 明确建议「新项目优先协程，遗留项目继续维护 Handler」，故 Handler 与协程的对照成为新晋必考题。

---

## 0. 当月热点锚定（2026-08）

| 热点 | 对面试的影响 | 落点章节 |
|------|--------------|----------|
| Google I/O 2026：新项目优先协程 | Handler 仍不可替代（View.post / ActivityThread 内部），「Handler vs 协程」成新晋必考题 | §1.1 |
| A17 QPR2 Beta 3（build CP41.260731.005，2026-08-14）本周期最大版，stable 跟踪 2026-12 | 新特性反推 Framework 底层（App Lock / 防呼叫转移诈骗 / 折叠屏窗口） | 见 #37 |
| A18 Aluminium OS（ChromeOS 技术栈重建于 Android 内核，外接显示 + true desktop windowing） | WMS/display/Input/CDM 重构级冲击 | 见 #40 |
| 秋招题库 2026：Binder 22 题连击、Handler 三件套、AMS 启动链路、View 绘制 | 本文 §1 全部按此真题清单组织 | §1 |
| 2026 Stack Overflow：~30% Android 内存泄漏与 Handler 未清理相关 | LeakCanary + removeCallbacksAndMessages(null) 必会 | §1.1 |

---

## 1. 全领域高频真题 Q&A（源码级 + 最易漏深点）

### 1.1 Handler / Looper（当月连击率最高）

**Q1：主线程 Looper.loop() 是死循环，为什么不会 ANR？**
- 答案：ANR 不是「主线程在跑」，而是「主线程在一条消息里卡超过阈值」。Looper 空闲时 `MessageQueue.next()` 走 **nativePollOnce() -> epoll_wait()** 进入**可中断休眠**（让出 CPU），由 epoll 监听 `mWakeEventFd`（Looper.cpp `rebuildEpollLocked` 注册 `mWakeEventFd` 的 EPOLLIN）被唤醒。休眠 ≠ 卡死，watchdog 也只管 `system_server` 的 `monitor` 锁超时，不管 app 主线程空转。
- AOSP 路径：`frameworks/base/core/java/android/os/Looper.java` `loop()` / `MessageQueue.next()` -> `frameworks/base/core/jni/android_os_MessageQueue.cpp` `nativePollOnce` -> `system/core/libutils/Looper.cpp` `pollOnce` -> `epoll_wait`。
- **最易漏深点**：是 `epoll_wait` 休眠，不是忙等；唤醒靠写 `mWakeEventFd`（写 1 个 uint64），不是信号。
- **高频追问**：同步屏障（SyncBarrier）怎么让异步消息插队？`postSyncBarrier()` 往队列头插一个 `target==null` 的屏障 Message，`next()` 遇屏障只取异步消息，直到 `removeSyncBarrier()` 移除（`ViewRootImpl` 在 `scheduleTraversals` 发屏障，保证 vsync 渲染消息优先）。
- **延伸**：IdleHandler 在队列空且无线程阻塞时触发（`MessageQueue.next()` 取 IdleHandler 列表逐个 `queueIdle()`），用于延迟加载。

**Q2：Handler 内存泄漏怎么解？与协程怎么选？（2026 新晋必考）**
- 答案：非静态内部类 `Handler` 隐式持有 `Activity`；消息在队列里停留（如 `postDelayed`）期间 Activity 不能回收。解法：静态内部类 + 弱引用，或在 `onDestroy` 调 `handler.removeCallbacksAndMessages(null)`（LeakCanary 会报）。
- **与协程对照（Google I/O 2026 口径）**：Handler 绑定 Looper 线程、消息驱动、需手动 remove；协程挂起恢复、结构化取消（`lifecycleScope`/`viewModelScope` 自动取消）、新项目优先。但 Handler 在 Framework 内部（View.post、ActivityThread H 消息）**不可替代**，面试要能讲清「为什么框架不用协程跑主线程消息」。
- AOSP 路径：`frameworks/base/core/java/android/app/ActivityThread.java` 内部类 `H extends Handler`，主线程所有跨进程回调（生命周期、绑定服务）都经 `H` 分发。

### 1.2 Binder IPC（连击 22 题）

**Q3：Binder 为什么是「一次拷贝」而不是零拷贝？**
- 答案：Client 用户态 `Parcel` 数据经 `IPCThreadState::writeTransactionData` 写入**内核 Binder 缓冲区**，驱动 `binder_transaction()` 通过 `binder_alloc_copy_user_to_buffer` 把源用户态数据**拷贝一次**到内核缓冲区，再 `binder_alloc_copy_buf` 把内核缓冲区映射到 Server 用户态（Server `mmap` 同一块，靠 `binder_buffer` + `vm_area`）。「一次」= 用户态->内核态这一跳；Server 用户态直接读到，省掉「内核->用户态」第二跳（这正是相对 socket/管道「两次拷贝」的优势）。物理上 Client/Server 共享同一内核缓冲区（mmap 映射），所以只拷一次。
- AOSP 路径：`drivers/android/binder.c` `binder_transaction()` / `binder_alloc.c` `binder_alloc_copy_user_to_buffer`；用户态 `frameworks/native/libs/binder/IPCThreadState.cpp` `transact()`。
- **最易漏深点**：① 不是零拷贝（零拷贝是共享内存/ION 那种）；② 大数据（>1MB-8 或含 FD）必须走 **FD 传递**（`Parcel::writeFileDescriptor`，经 `binder_fd` 在内核 dup），否则爆 `TransactionTooLargeException`；③ `oneway` 调用**也会排队**——线程池满（默认 15+1）时 `binder_transaction` 仍阻塞在 `binder_thread_read` 等待空闲线程，故 oneway 不是「永不阻塞」。
- **高频追问**：`getCallingUid()` 为什么不可信？（a）跨 pVM/AVF RPC Binder 时 uid 由 host 自报不可信；（b）AppFunctions Provider 侧拿到的是 `SYSTEM_UID`（系统代为调用），见 #12/#13。

**Q4：Binder 线程池默认多大？为什么池耗尽会 ANR？**
- 答案：每个 Binder 服务进程默认 **15 个 Binder 线程 + 1 个 main 线程**（`ProcessState` `DEFAULT_MAX_BINDER_THREADS = 15`）。驱动在需要时用 `BR_SPAWN_LOOPER` 通知进程 `spawnPooledThread` 起新线程，直到上限。`joinThreadPool()` 让主线程也进池。若 16 条全卡在同步调用（对端又反向同步调回本进程 → 死锁），新事务 `binder_transaction` 在 `binder_thread_read` 处挂起 → 调用方主线程同步等待 → 触发 5s/10s ANR（见 #6）。
- AOSP 路径：`frameworks/native/libs/binder/ProcessState.cpp` `mMaxThreads=15`；`IPCThreadState::joinThreadPool`；`drivers/android/binder.c` `binder_thread_read` 发 `BR_SPAWN_LOOPER`。
- **易错点**：`linkToDeath` 死亡通知若不 `unlinkToDeath` 会泄漏 Binder 引用计数，且 Server 端 `binderDied` 回调在 Binder 线程跑，别在里面做重活。

### 1.3 AMS / ATMS / App 启动

**Q5：startActivity 从 AMS 到应用首帧，AMS/ATMS 做了什么？**
- 答案链路：`ContextImpl.startActivity` → `Instrumentation.execStartActivity`（拿 `ActivityTaskManager.getService()` 即 `IActivityTaskManager` 的系统服务 Binder 代理）→ `ATMS.startActivityAsUser` → `ActivityStarter.execute` → `startActivityUnchecked` → `ResumeActivityItem`/`PauseActivityItem` 经 `ClientTransaction` 回到 App 进程 `ActivityThread` → `handleLaunchActivity`（创建 `Activity` + `attach` + `performCreate`）→ `handleResumeActivity`（`WindowManagerGlobal.addView`）→ `ViewRootImpl.setView` → `requestLayout` 触发首帧。
- AOSP 路径：`frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java`；`frameworks/base/core/java/android/app/ActivityThread.java` `handleLaunchActivity`/`handleResumeActivity`；`frameworks/base/core/java/android/app/servertransaction/ClientTransaction.java`。
- **最易漏深点（ContentProvider 前置坑）**：`handleBindApplication` 里 **`installContentProviders()` 在 `callApplicationOnCreate()` 之前**执行。若某个 CP 的 `onCreate` 慢或同步 Binder 卡，整个 App `Application.onCreate` 被推迟 → 冷启动暴涨。这是冷启动优化的头号隐蔽点（见 #6/#19）。
- **高频追问**：Zygote fork 为什么用 socket 而非 Binder？（fork 多进程时 Binder 线程状态无法安全继承，故 Zygote 用 `LocalServerSocket` 接收 fork 命令，fork 后子进程才 `open` Binder 设备。）

### 1.4 WMS / View 事件分发与绘制

**Q6：View 事件分发三方法的责任链？为什么 `requestDisallowIntercept(true)` 对 DOWN 无效？**
- 答案：`dispatchTouchEvent`（总调度）→ ViewGroup 内 `onInterceptTouchEvent`（是否拦截，仅 ViewGroup 有）→ `onTouchEvent`（消费）。DOWN 事件时 ViewGroup **必定**先走 `onInterceptTouchEvent` 决定，并据此重置「子 View 是否可禁止父拦截」标志；`requestDisallowIntercept` 只在 **MOVE/UP** 阶段生效，DOWN 时父容器已经重置了 `FLAG_DISALLOW_INTERCEPT`，故对 DOWN 无效（经典滑动冲突解法：父在 MOVE 里判方向再拦截，子在 DOWN 后 `requestDisallowIntercept(true)`）。
- AOSP 路径：`frameworks/base/core/java/android/view/ViewGroup.java` `dispatchTouchEvent` / `onInterceptTouchEvent`；`requestDisallowInterceptTouchEvent` 设 `FLAG_DISALLOW_INTERCEPT`。
- **CANCEL 语义**：父容器在子已接收事件后中途拦截，会给子发 `ACTION_CANCEL`（子应视作事件终止，重置按下态），否则会出现「按下高亮、抬起却无点击」的视觉 bug。
- **MeasureSpec 三模式易错点**：`AT_MOST` 父给「上限」，子不能超过，但很多自定义 View 在 `wrap_content` 时忘了处理 AT_MOST 直接用 `specSize` 当精确值 → wrap_content 退化成 match_parent；`getMeasuredWidth()`（measure 后）≠ `getWidth()`（layout 后，可能含偏移/被裁剪），在 `onMeasure` 里读 `getWidth()` 得到 0。

### 1.5 内存 / 卡顿 / ANR（量化指标必考）

**Q7：ANR 有哪几类？「Input ANR」计时器到底在谁那里？**
- 答案：四类超时——① 输入事件 5s（`InputDispatcher` 派发超时）；② 广播 `onReceive` 前台 10s/后台 60s；③ Service 生命周期 20s（前台）/200s（后台）；④ `ContentProvider` `query`/`insert` 等 10s。
- **最易漏深点**：Input ANR 的 5s 计时器在 **native InputDispatcher**（`DEFAULT_INPUT_DISPATCHING_TIMEOUT = 5s`，`frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp`），**不在 App Looper**。主线程卡在某条消息里（同步 Binder / 死锁）导致 `finishDispatchCycleLocked` 无法被调用才超时；`dispatchOnceInnerLocked` 启动计时，`handleTargetsNotReadyLocked` 累加。
- AOSP 路径：`frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp` `dispatchOnceInnerLocked` / `handleTargetsNotReadyLocked`；`frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java` `appNotResponding`。
- **三条杀路径辨析**（必考连环）：① `lmkd` 基于 **PSI** 杀（整机内存压力）；② A17 **Memory Limiter** 个体超标静默杀（`REASON_MEMORY_LIMITER`，见 #29）；③ 内核 **OOM killer**（`oom_score_adj` 高者先死）。三者叠加，面试要能分清「整机压力 vs 个体超标 vs 内核兜底」。

**Q8：掉帧怎么定责到 App / RenderThread / SF / HWC？**
- 答案：Perfetto `actual_frame_timeline_slice` JOIN `expected_frame_timeline_slice`（by `frame_number` + `upid`），`jank_type` 字段定责：`App Deadline Missed`（主线程慢）/ `SurfaceFlinger Scheduling` / `HWC` / `GPU`。`present_type` 区分 `OnTime`/`Late`。配合 `Choreographer` 同帧回调时序：`CALLBACK_INPUT → CALLBACK_ANIMATION（Compose 重组挂这）→ CALLBACK_TRAVERSAL（measure/layout/draw）→ CALLBACK_COMMIT`。主线程在 INPUT 阶段卡 → App 责任；RenderThread 慢 → GPU 责任。
- AOSP 路径：`frameworks/base/core/java/android/view/Choreographer.java` `CALLBACK_*` 常量；`frameworks/native/services/surfaceflinger/` `onMessageRefresh`；Perfetto 表见 #21/#25。

### 1.6 Jetpack / Compose 底层

**Q9：Compose 重组为什么「读取即订阅」？Recomposer 挂在哪个 Choreographer 回调？**
- 答案：Compose 用 `Snapshot` 的 **MVCC 版本链**——读 `State.value` 时 `Snapshot` 注册 `readObserver` 把当前 `Snapshot` 加入该 State 的「订阅者」集合；State 在 `apply` 时对比版本号，只通知受影响的 `Snapshot` 失效并调度重组。重组走 `Recomposer`，它注册在 **`Choreographer.CALLBACK_ANIMATION`**（不是 TRAVERSAL），与 View 的 measure/layout/draw（TRAVERSAL）同帧但先后执行。
- AOSP 路径：`frameworks/base/core/java/android/view/Choreographer.java`；`packages/.../compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Recomposer.kt`；`Snapshot.kt` `readObserver`。
- **强跳过模式澄清（#30/#36 强调）**：强跳过（Kotlin 2.0.20+ 默认）**不改变 `$stable` 位域、不改 `BitsPerParameter`**，只把「跳过策略」从「相等比较」改成「引用相等 `===`」；`unstable` 类型**仍可能每帧重组**（引用不变才跳），故 `@Stable`/`Immutable` 仍必要。
- **SlotTable gap buffer**：重组树是平坦 `IntArray`（key + 值索引），`Anchor` 在数组搬移（插入/删除）时保持稳定，实现 O(1) 定位 + O(k) 局部搬移（类似文本编辑器的 gap buffer）。

### 1.7 HAL / Linux kernel / drivers

**Q10：Binder 三个上下文分别给谁用？HIDL 与 AIDL for HAL 怎么选？**
- 答案：① `/dev/binder` 给 framework（Java 服务 <-> App）；② `/dev/hwbinder` 给 **HAL（HIDL，跨进程到 vendor 进程）**；③ `/dev/vndbinder` 给 **vendor 内部（AIDL for HAL，同供应商多进程）**。Treble 把 framework 与 vendor 解耦，VINTF 在开机校验 `compatibility_matrix` vs `manifest`。
- AOSP 路径：`drivers/android/binder.c` 单驱动多上下文；`frameworks/native/libs/binder/ProcessState.cpp` `init` 选设备；`hardware/interfaces/`（HIDL）vs `aidl/`（AIDL for HAL，A12+ 主推）。
- **GKI 2.0 / KMI**：内核拆成通用 GKI + 厂商模块，厂商驱动须用稳定 KMI 接口（含 `vendor_hook` 扩展点），否则 OTA 后不兼容。`16KB` 页：A15 起强制对齐，`ld-android.so` `p_align>=16384`，否则 crash（见 #14/#29）。
- **图形内核**：外接显示走 **DRM-KMS**（`drivers/gpu/drm/`），输入走 **HID**（`usbhid`/`i2c-hid`），Aluminium OS 在 x86 上同样适用 GKI KMI（见 #40）。

### 1.8 MTK 平台真缺口

**Q11：MTK 死机/重启怎么抓现场？thermal 怎么降频？**
- 答案：① **AEE**（mtk AEE / `exp_main`）在 kernel panic / watchdong 时收集 `db.*` 现场（含 kernel log、memory、native backtrace）；② **mtklog** 三件套（`aee_exp`、`mobilelog`、`netlog`）经 EngineerMode 开启；③ **PerfService**（`/vendor/bin/perfserviced` 或 `libs/perfservice`）给关键线程提频/绑核（game/相机场景常用）；④ **thermal** 走 MTK thermal HAL + `thermal_config.xml` 多 zone 降频（`/sys/class/thermal/`），与 AOSP `Thermal HAL` 对齐（见 #17）。
- AOSP 对照：`hardware/interfaces/thermal/`（AIDL），`frameworks/base/services/core/java/com/android/server/thermal/ThermalManagerService.java`。
- **最易漏深点**：MTK 重启后 `db` 文件落在 `/data/aee_exp/` 需 root/adb pull；thermal 降频会与 **ADPF**（Android Dynamic Performance Framework，`PowerHal` `setHint`）抢频，优化卡顿要先看是不是被 thermal 限了（见 #19 卡顿定界）。

---

## 2. 收官末位真缺口：Compose 编译器 `ComposableFunctionBodyTransformer` IR 改写内部逐行走读

> #30 讲清了生成代码级（`$composer`/`$changed`/`skipToGroupEnd`），#36 给了位掩码解码。**本文下钻到 IR 改写实现**：Compose 编译器插件（`org.jetbrains.kotlin.backend.compose`）如何在 K2 的 IR 上把 `@Composable fun` 改写成携带重组协议的函数。

### 2.1 插件挂载点
- Kotlin 2.0（K2）起，Compose 编译器**并入主编译器**，作为 IR  lowered 阶段（`IrGenerationExtension`）注册。入口 `ComposePlugin` -> `ComposableFunctionBodyTransformer`（在 `lower` 阶段对每一个 `@Composable` 函数体做 IR 重写）。

### 2.2 改写三大动作（对照生成代码）
1. **注入 `$composer` 参数**：函数签名尾部追加 `composer: Composer, $changed: Int`（及 `$default` 用于默认参）。原调用点被改写为先取 `Composer`（`ComposerKt.getCurrentComposer()` 在 @Composable 上下文里），再把自己的 `$changed` 位传给被调函数。
2. **注入 `$changed` 位掩码初始化**：对每个**参数**分配 **2 bit**（`BitsPerParameter = 2`）——第 0 位 `realChanged`、第 1 位 `staticChanged`。父函数在编译期根据「实参是否 `static`（编译期常量 / 不变引用）」**预填**子函数的 `$changed`：若参数值 `$stable` 且本次调用实参与上次「相等」（父方 `changed()` 判定），置 `staticChanged`，子函数可跳过。
3. **包裹 `Composer.startXXX` / `endXXX` + `skipToGroupEnd`**：函数体首插 `composer.startRestartGroup(key)`（key = 源码位置哈希 `composer.joinKey`）；函数体尾插 `composer.endRestartGroup()`；若 `$changed` 判定可跳过，IR 直接生成 `composer.skipToGroupEnd()` 并返回（不执行函数体）。`restart` 标记的 group 会在 State 变化时经 `Invalidation` 重新调用本函数（不是重新执行 IR，是重新跑 Kotlin 函数体，slot 表复用）。

### 2.3 `ComposerImpl` 端解码（运行时落点）
- `ComposerImpl.changed(value)`：`updateValue` 取 SlotTable 中上次存的值，与当前值 `equals()`；返回 `Changed`/`Unchanged`/`Unknown`。
- `BitsPerParameter = 2`、`ChangedMask`/`StaticMask`：从 `$changed` 整型里按参数索引移位取 2 bit；`StaticMask` 表示「父方已知该位不会变」，从而子方**免比较直接复用**。
- **两大误解澄清（面试必背）**：
  1. 「unstable 类型就会每帧重组」——**错**。unstable 只是该参数位**无法静态判定稳定性**，运行时仍走 `equals()` 比较，值不变则不重组；但若类型是 `var` 且每次 new 新对象（引用变 + 值可能相等），`equals` 仍可能判相等而跳过，这是常见陷阱。
  2. 「强跳过模式改了稳定判断」——**错**。强跳过只把「相等比较」换成「引用相等 `===`」作为跳过策略，**不改 `$stable` 位域、不改 `BitsPerParameter`**；引用相等陷阱（每次 new）更需要 `@Stable` 注解。

### 2.4 一组参数 2 bit 的推导（源码注释级）
```
BitsPerParameter = 2
// 参数 i：bit 偏移 = i * 2
realChanged   = ($changed >> (i*2))     & 0b01   // 本次值是否变
staticChanged = ($changed >> (i*2+1))   & 0b01   // 父方已知该位是否恒定
// 子方 skip 条件：realChanged==0 或 (staticChanged==1)
```
父方在调用点用 `composer.changedInstance(v)` / `changedInstanceInlined(v)` 算出自身实参的 2 bit 后填入 `$changed` 再传子——这就是「父方编译期预填」的内存态来源。

---

## 3. 易错红榜 TOP20（跨全领域，秋招高频踩坑）

1. Binder 说成「零拷贝」——实为一次拷贝（mmap 共享内核缓冲区）。
2. 主线程死循环会 ANR——错，是 `epoll_wait` 休眠，watchdog 只管 system_server。
3. `oneway` 永不阻塞——错，线程池满仍排队。
4. `getCallingUid()` 永远可信——错（跨 pVM / AppFunctions Provider 侧拿到 SYSTEM_UID）。
5. `requestDisallowIntercept(true)` 对 DOWN 生效——错，只 MOVE/UP 生效。
6. `AT_MOST` 下 `wrap_content` 直接用 `specSize`——退化成 match_parent。
7. `getMeasuredWidth()` == `getWidth()`——错，measure 后才有 measured，layout 后才 width。
8. Input ANR 计时器在 App Looper——错，在 native InputDispatcher 5s。
9. 三路杀分不清：lmkd(PSI) / A17 Memory Limiter / 内核 OOM。
10. `ContentProvider.onCreate` 在 `Application.onCreate` 之后——错，之前（冷启动前置坑）。
11. Compose unstable 类型必每帧重组——错，仍走 equals 比较。
12. 强跳过改了 `$stable` 位域——错，只改跳过策略为 `===`。
13. 16KB 页只是「推荐」——错，A15+ 强制对齐，否则 crash。
14. `linkToDeath` 不 `unlink`——Binder 引用泄漏。
15. Handler 泄漏只靠弱引用——还需 `removeCallbacksAndMessages(null)`。
16. HIDL 与 AIDL for HAL 混用不分——hwbinder vs vndbinder 上下文不同。
17. Zygote fork 用 Binder 收命令——错，用 LocalServerSocket（fork 不安全继承 Binder 线程）。
18. `onSaveInstanceState` 跨进程保活 Activity——错，只同一进程配置变更；跨进程靠 SavedStateHandle + AMS 进程模型。
19. MTK 重启无现场——错，AEE `db.*` + mtklog 三件套。
20. `TransactionTooLargeException` 只因数据 >1MB——错，还包括 Binder 缓冲区整体占用 + FD 未走 fd 传递。

---

## 4. 三条高频追问链（考官连击）

**链 A：冷启动 ANR × Binder × AMS**
`installContentProviders` 前置卡（#3 深点）→ Application.onCreate 推迟 → 首帧晚 → 若主线程在 onCreate 里同步 Binder 等 AMS 回包，AMS 自身又因其他事务忙 → Binder 线程池等待 → 5s/10s ANR。定界：Perfetto `android_startup` + `binder_transaction` 看对端执行时间。

**链 B：一次点击 × Input × WMS × View × Choreographer**
`evdev` → `EventHub` → `InputReader` → `InputDispatcher`（5s 计时器起点）→ `InputChannel`(socketpair) → `InputEventReceiver` → `ViewRootImpl` InputStage → `DecorView` → View 树 `dispatchTouchEvent` → `onTouchEvent` → `View.post`(Handler) → `Choreographer` CALLBACK_TRAVERSAL 绘制。任一环主线程卡 → Input ANR。

**链 C：跨设备 AI × CDM × RPC Binder 安全边界**
AppFunctions Provider 经 BIND_APP_FUNCTION_SERVICE 被系统调用 → `getCallingUid()` 拿到 SYSTEM_UID（不可信，#13）→ AI Agent 读锁 App 数据时 NMS 强制 redaction（#37）→ 跨 VM 走 RPC Binder（AVF）`getCallingUid` 更不可信（#12）→ 必须靠 `KeyMint` 证明 + `RKP/DICE` 锚定信任。

---

## 5. AOSP 14 源码路径清单（本篇引用）

| 子系统 | 关键路径 |
|--------|----------|
| Looper/MessageQueue | `frameworks/base/core/java/android/os/Looper.java`, `MessageQueue.java`；`system/core/libutils/Looper.cpp` |
| Binder 驱动 | `drivers/android/binder.c`, `drivers/android/binder_alloc.c` |
| Binder 用户态 | `frameworks/native/libs/binder/IPCThreadState.cpp`, `ProcessState.cpp` |
| AMS/ATMS | `frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java`；`ActivityThread.java` |
| View 事件/绘制 | `frameworks/base/core/java/android/view/ViewGroup.java`, `View.java`, `ViewRootImpl.java` |
| Choreographer | `frameworks/base/core/java/android/view/Choreographer.java` |
| Input | `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp` |
| Compose 运行时 | `packages/.../compose/runtime/.../Recomposer.kt`, `Snapshot.kt`, `ComposerImpl.kt` |
| Compose 编译器 | `org.jetbrains.kotlin.backend.compose.ComposableFunctionBodyTransformer`（K2 IR lowered） |
| HAL 三上下文 | `hardware/interfaces/`（HIDL）, `aidl/`（AIDL for HAL）；`ProcessState.cpp` |
| GKI/KMI | `drivers/android/`（驱动）, `common/`（GKI），`include/linux/gki/` |
| 内核图形/输入 | `drivers/gpu/drm/`（DRM-KMS）, `drivers/hid/`（usbhid/i2c-hid） |
| Thermal | `hardware/interfaces/thermal/`, `ThermalManagerService.java` |
| MTK | `vendor/mediatek/proprietary/.../aee/`, `perfservice/`, `thermal_config.xml` |

---

## 6. 41 -> 42 篇交叉索引

| 本篇章节 | 关联历史篇 | 关系 |
|----------|-----------|------|
| §1.1 Handler/Looper | #26 核心基础八股、#36 热点轮训、#41 联动轮训 | 补「Handler vs 协程 2026 新必考」 |
| §1.2 Binder 一次拷贝/线程池 | #1/#3 Binder 三篇、#20 源码 walk、#34 启动 | 补 oneway 满也排队 / 三上下文对照 |
| §1.3 AMS 启动 + CP 前置坑 | #19 启动链路、#20 code walk | 强调 CP 在 Application.onCreate 之前 |
| §1.4 WMS/View | #8/#12 事件分发、#20 绘制三阶段、#41 | 强调 requestDisallowIntercept 对 DOWN 无效 |
| §1.5 ANR/三路杀/掉帧 | #19 排查实战、#21 Perfetto SQL、#25 input/GPU/battery | 强调 Input ANR 计时器在 native |
| §1.6 Compose | #13 智能系统、#30 编译器源码走读、#36 位掩码 | 承上启下到 §2 内部 IR 走读 |
| §2 Compose 编译器 IR 内部 | #15 Compose 编译器、#30、#36 | **收官末位真缺口**（ComposableFunctionBodyTransformer 内部） |
| §1.7 HAL/内核 | #17 HAL 全链路、#32 | 补 16KB 强制 / GKI KMI x86 |
| §1.8 MTK | #17 HAL 篇 MTK 段 | 补 AEE/mtklog/PerfService/thermal 真抓法 |
| §4 追问链 | #23/#27/#39 真题大乱斗 vol1-3 | 压轴轮训版连击链 |

---

## 7. 延伸阅读

- AOSP 源码：`android-14.0.0_rXX`（`drivers/android/binder.c`、`frameworks/base`、`packages/modules/...`）。
- 官方：Android 14 Source（`cs.android.com`）、Google I/O 2026「Handler vs 协程」演讲、A17 behavior-changes-17、A18 Aluminium OS 路线图。
- 工具：Perfetto trace_processor（`actual_frame_timeline_slice`/`binder_transaction`/`android_input_events`）、LeakCanary、`lldb`/`llvm-symbolizer`（tombstone 符号化）、MTK AEE db 解析。
- 系列前 41 篇（工作区同名 md）：从主篇 16 章到 #40 A18 桌面融合 WMS 源码重构，构成完整复习地图。

---

> 系列状态：42 篇 / 约 265 专题。全系列真缺口至此**全部清零**（含最后一个 Compose 编译器 `ComposableFunctionBodyTransformer` IR 内部走读）。主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧 AI + 源码 walk + Perfetto SQL + 基础八股 + 三版真题大乱斗 + Native 稳定性 + 输入系统 + HAL/内核 + AAOS + 启动链路 + A18 桌面融合 + 全领域压轴轮训 完整闭环。后续若继续日更仅剩可选增量：Aluminium OS 落地后对照 A14 真实 diff 复盘（待 A18 源码/AOSP 二次开源）。
