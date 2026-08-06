# Android Framework 高频面试题 · 考官连击模拟考（考前定向复习 · 第十八篇）

> 系列定位：前 17 篇（2026-07-23 ~ 2026-08-05）已沉淀约 132 个专题，覆盖 Binder / AMS / ATMS / WMS / SF / ART / HAL / 内核 / TEE(EL3) / pKVM(EL2) / 智能层 / 座舱 / 端侧 AI / 收官补遗，并于 8/5 上午产出《考前总复习速查卡》。
>
> 本篇不再堆新角度，而是把"已经学过的知识"变成"考场上能说出来、抗得住追问"的能力。形式是 **考官连击模拟考**：每道题给出标准答案骨架（带 AOSP 源码路径佐证）+ 递进追问链 + 易错红榜 + 答题话术框架，最后附跨域综合大题与自测评分 Rubric。
>
> 所有源码路径以 **Android 14 (android-14.0.0_rXX, kernel GKI android14-6.1)** 为准。

---

## 〇、怎么用这份连击考（3 分钟说明）

1. **自测优先**：先盖住"标准解析"，口头答一遍，再对照。
2. **追问连击才是重点**：考官不会只问一层。每题的"追问链"要能顺着答下去，答到第三层才算真懂。
3. **话术框架**：用"先结论、再分层、后源码/数据佐证"的金字塔结构，避免想到哪说到哪。
4. **易错红榜**：这些点 90% 候选人会翻车，考前必背。
5. **综合大题**：跨服务串讲，是 Senior / 架构岗的试金石。

评分标准见文末 Rubric。建议每天挑 3 题练连击，配合《速查卡》查漏。

---

## 第一部分：核心经典题 · 考官连击（12 题）

### 题 1：Handler / Looper / MessageQueue 是怎么转起来的？为什么子线程里直接 new Handler() 会崩？

**标准解析**
- `Looper.prepare()` 在 ThreadLocal 里存一个 `Looper` 实例（`Looper.sThreadLocal`），里面持有 `MessageQueue`（`nativeInit()` 建 native 消息队列，`frameworks/base/core/jni/android_os_MessageQueue.cpp` → `nativeMessageQueue`）。
- `Looper.loop()` 死循环 `queue.next()` 取消息，无消息时 `nativePollOnce(ptr, -1)` 进入 epoll 等待（`system/core/libutils/Looper.cpp` 的 `pollInner` 监听 mWakeEventFd / 管道），让出 CPU。
- `Handler` 只是个"投递 + 分发"的门面：`sendMessage` 把 `msg.target = this` 后 `enqueueMessage` → `MessageQueue.enqueueMessage`（按 when 时间排序插链）；`loop()` 取出后 `msg.target.dispatchMessage(msg)` → `handleMessage`。
- 子线程崩的根因：**只有主线程在 `ActivityThread.main()` 里调用过 `Looper.prepareMainLooper()`**；普通线程不 prepare 就 new Handler，构造里 `Looper.myLooper()` 拿到 null → `throw new RuntimeException("Can't create handler inside thread that has not called Looper.prepare()")`。
- Android 17 新增 **Lock-free MessageQueue**（`MessageQueue.java` 的 `enqueueMessage` 改无锁 CAS 入队），高并发 post 不再抢 `synchronized (this)` 锁，但 `nativePollOnce` 等待语义不变。

**追问链**
1. 主线程的 Looper 为什么不会因死循环卡死 ANR？（答：阻塞在 epoll，`nativePollOnce` 让出，CPU 空闲；ANR 是消息没被及时处理，不是循环本身）
2. `Message` 为什么建议 `obtain()` 复用？（答：对象池，避免高频 new/GC；`sPool` 单链，max 50）
3. 同步屏障（SyncBarrier）是什么？`postSyncBarrier()` 怎么让异步消息插队？（答：在队列头插一个 `target==null` 的屏障消息，`next()` 遇到屏障只取 `isAsynchronous()==true` 的消息；用于 VSYNC 驱动的绘制消息优先；`removeSyncBarrier` 撤销。`ViewRootImpl.scheduleTraversals` 即用）
4. `IdleHandler` 在什么时机触发？能做什么不能做什么？（答：`next()` 取空且无可运行同步屏障时，在 `pendingIdleHandlerCount` 阶段回调；适合闲时清理；**不能在里面 post 新消息导致永远不 idle**，且执行耗时会拖慢下一条消息）
5. A17 Lock-free 之后，`enqueueMessage` 还加 `synchronized` 吗？（答：主路径改 CAS，但 `mQuitting` / 屏障相关仍有少量同步保护；需读 A17 源码确认，别想当然说"完全无锁"）

**易错点**
- "Looper 死循环耗 CPU" 是经典误解；真实是 epoll 阻塞。
- 屏障消息 `target==null`，普通 `next()` 会跳过它，不是消费它。
- 子线程 Handler 记得 `Looper.prepare()` + `loop()`，且要 `quit()` 否则线程不退。

**话术框架**
> 结论：Handler 是门面，Looper 是发动机，MessageQueue 是带时间排序的队列，靠 ThreadLocal 绑定线程。
> 分层：prepare 建队列 → loop 取消息(epoll 等待) → dispatch 回 Handler。
> 佐证：崩溃点在 `Handler.<init>` 取 `Looper.myLooper()` 为 null；主线程在 `ActivityThread.main` 已 prepare。

---

### 题 2：Binder 一次拷贝是怎么做到的？为什么说它比传统 IPC 快？

**标准解析**
- 传统 IPC（管道/套接字）：发送方用户态 → 内核态拷贝一次 → 接收方内核态 → 用户态再拷贝一次，**两次拷贝**。
- Binder：`mmap` 把内核 `binder_buffer` 区域和用户空间映射同一块**物理内存**（`ProcessState::open()` → `open_driver` → `mmap`，`BINDER_VM_SIZE` 默认约 1M-8K，`frameworks/native/libs/binder/ProcessState.cpp`）。
- 发送：`IPCThreadState::transact` → `writeTransactionData` 把 `binder_transaction_data` 写入 `mOut`（Parcel）→ `talkWithDriver` 通过 `ioctl(BINDER_WRITE_READ)` 进入内核。
- 内核 `binder_transaction`（`drivers/android/binder.c`）：在目标进程的 `binder_buffer` 里分配空间，把发送方用户态数据**直接拷贝到这块共享物理内存**（一次拷贝），同时构造 `binder_transaction` 节点入目标 `todo` 队列，唤醒目标 `binder_thread`。
- 目标线程 `binder_thread_read` 拿到事务，用户态 `executeCommand` → `BBinder::transact` → `onTransact` 直接读**自己已 mmap 的那块内存**，无需再拷贝。
- "快"的三点：① 只一次拷贝；② `mmap` 零拷贝读；③ 用 `ioctl` 单系统调用完成"写+读唤醒"，不像 socket 要多次 syscall。

**追问链**
1. 那"一次拷贝"拷贝的是谁到谁？（答：发送方用户态 → 内核为接收方分配的 `binder_buffer` 共享物理页；接收方读的是 mmap 后的同一物理页，第 2 次省掉）
2. 内核态到内核态那一步算拷贝吗？（答：Binder 驱动在内核态把数据从发送方缓冲区搬到接收方的 `binder_buffer`，这是唯一一次拷贝；不是"零拷贝"，是"一次拷贝"）
3. `mmap` 大小有限制（1M-8K），一次传超大对象（如大 Bitmap）会怎样？（答：超过 `BINDER_VM_SIZE` 或 `transaction` buffer 限制会 `FAILED_TRANSACTION` / `TransactionTooLargeException`；大对象要走 `ashmem` / `MemoryFile` / `ParcelFileDescriptor` 传 fd 而非实体）
4. `oneway` 和同步调用在内核有什么区别？（答：同步调用 `binder_transaction` 会挂在 `thread->transaction_stack` 等待 reply，`BR_TRANSACTION_COMPLETE` 后再等 `BR_REPLY`；oneway 设 `TF_ONEWAY`，不阻塞等回复，直接返回，服务端异步处理）
5. Binder 线程池满了（默认 15 线程，`binder_threads` 上限）新请求怎么办？（答：请求在 `binder_proc->todo` 排队；若客户端同步等待且服务端线程全忙做同步 Binder 调用会死锁——经典"Binder 线程耗尽"ANR）

**易错点**
- Binder 是"一次拷贝"不是"零拷贝"；零拷贝是 mmap 那一侧。
- `TransactionTooLargeException` 是 `Parcel` 整体过大，常见坑：Intent 传大 Bundle / onSaveInstanceState 存大对象。
- `oneway` 不等于"线程无忧"，服务端仍可能阻塞。

**话术框架**
> 结论：Binder 靠 mmap 共享物理内存做到发送方→内核一次拷贝，接收方零拷贝读取，比 socket 两次拷贝省一半，且单 ioctl 完成读写唤醒。
> 佐证：`ProcessState::open` 的 mmap、`binder.c` 的 `binder_transaction` 内存分配与拷贝、`IPCThreadState::talkWithDriver`。

---

### 题 3：Binder 跨进程对象引用（IBinder）是怎么保活的？死亡通知怎么实现？

**标准解析**
- Binder 句柄不是对象本身，而是 `handle`（32 位引用号）。内核 `binder_ref` 把"本进程 handle"映射到目标 `binder_node`（`binder_get_ref`）。跨进程传 `IBinder` 时 `flatten_binder` 把 `handle` / `binder_node` 写进 Parcel，对端 `unflatten_binder` 还原成 `BpBinder(handle)`。
- 死亡通知：`linkToDeath(DeathRecipient)` → `BpBinder::linkToDeath` → `IPCThreadState` 发 `BC_REQUEST_DEATH_NOTIFICATION` → 内核在 `binder_node` 上挂 `binder_ref_death`；当目标进程死亡（`binder_cleanup_ref` / `binder_free_proc`），内核向所有引用方发 `BR_DEAD_BINDER`，用户态 `IPCThreadState::executeCommand` 收到后回调 `DeathRecipient.binderDied()`。
- 用途：Service 连接断开后主动重连 / 释放资源（如 `ActivityManager` 死了对端要处理）。

**追问链**
1. `linkToDeath` 注册在客户端还是服务端？（答：客户端注册，监听**服务端**的死亡）
2. 死亡通知是同步还是异步到达？（答：异步，由内核在目标死亡时推送 `BR_DEAD_BINDER`，走 `talkWithDriver` 的读路径）
3. 为什么 `unlinkToDeath` 很重要？（答：不解除会导致 DeathRecipient 泄漏 / 反复触发；且 `binderDied` 回调里不能再持有已死 Binder 做同步调用，否则 `DEAD_OBJECT`）
4. 同一 IBinder 在多个进程里的 handle 一样吗？（答：不一样；handle 是"每进程命名空间"内的引用号，由内核 `binder_ref` 分配）

**易错点**
- `binderDied` 回调在**客户端 Binder 线程**执行，别在里面做重活或同步跨进程调用（可能死锁/ANR）。
- handle 是进程局部的，不能跨进程直接比大小当身份。

---

### 题 4：App 冷启动到 Activity 可交互，全链路经历了什么？

**标准解析**（配合题 11 综合大题更完整）
- 用户点击 → `Launcher` 通过 `ActivityManagerService.startActivity`（`frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java`）→ `ATMS.startActivityAsUser`。
- 若目标进程未起，`ActivityStackSupervisor.startSpecificActivity` 判断 `app == null` → `AMS.startProcessLocked` → 通过 **Zygote** `Process.start` → `ZygoteProcess` 走 `zygoteSendArgsAndGetResult` 经 **LocalSocket** 发给 `Zygote`（`frameworks/base/core/java/com/android/internal/os/ZygoteInit.java` 的 `ZygoteConnection.runOnce`）。
- Zygote fork 子进程（`Zygote.forkAndSpecialize`，fork 前已预加载 Class/drawable/资源，COW 共享）→ 子进程入口 `ActivityThread.main()`（`RuntimeInit` 通过 `invokeStaticMain` 反射调用）。
- `ActivityThread.main`：`Looper.prepareMainLooper()` + `new ActivityThread()` + `attach(false)` → `AMS.attachApplication` 回连 → `bindApplication`（`handleBindApplication`：创建 `LoadedApk`、反射 `Application`、调 `Application.onCreate`、安装 `Instrumentation`、调度 `Provider` 安装）。
- 之后 `ActivityThread.handleLaunchActivity` → `performLaunchActivity`（反射 `Activity`、`attach` 建 `PhoneWindow` / `WindowManager`）→ `Activity.onCreate` → `setContentView` 建 `DecorView` → `handleResumeActivity` → `WindowManagerGlobal.addView` → `ViewRootImpl.setView` 发起首次 `performTraversals`（measure/layout/draw）→ `Choreographer` 等 VSYNC 上屏。

**追问链**
1. Zygote 为什么用 Socket 不用 Binder 孵化进程？（答：fork 子进程时若父进程有 Binder 线程，子进程会继承紊乱的 Binder 状态（多线程 fork 死锁风险）；Socket 单线程、状态简单，fork 安全。且 Zygote 在 fork 前就停了其他线程）
2. `Application.onCreate` 在 `Activity.onCreate` 之前还是之后？（答：之前；`bindApplication` 在 `handleLaunchActivity` 之前）
3. 首帧绘制（draw）完成就算"可交互"吗？（答：不算；可交互还要 `onResume` 后 View 完成 measure/layout/draw 且 `Window` 添加、输入就绪；`reportFullyDrawn` 才是业务自认可交互）
4. 热启动和冷启动差在哪？（答：热启动进程在、Activity 可能只 `onRestart`，省去 fork + Application 初始化；温启动介于两者之间，进程在但 Activity 需重建）

**易错点**
- `Application` 可能多次创建（多进程 App 每进程一个 Application 实例），别在 `Application.onCreate` 做重活。
- `setContentView` 只是建 View 树，真正的 measure/layout/draw 在 `onResume` 后的 `performTraversals`。

**话术框架**（金字塔）
> 结论：fork 进程 → main 建 Looper → attach 回连 AMS → bindApplication 起 Application → launchActivity 建 Activity/Window → onResume 后首次遍历上屏。
> 分层：进程创建(Zygote) / 运行时初始化(ActivityThread) / 组件生命周期(Activity) / 渲染上屏(ViewRootImpl+VSYNC)。
> 佐证：`ZygoteInit` fork、`ActivityThread.main/attach/handleBindApplication`、`ViewRootImpl.performTraversals`。

---

### 题 5：AMS / ATMS 如何调度 Activity 生命周期？onCreate/onStart/onResume 是同步串行还是跨进程多次往返？

**标准解析**
- `startActivity` 经 `ATMS.startActivityAsUser` → `ActivityStarter.execute` → `startActivityUnchecked` → `startActivityInner`（计算 launch mode / flag / task 复用）。
- 真正的生命周期由 `ActivityTaskSupervisor.realStartActivityLocked` → `ClientTransaction`（`frameworks/base/core/java/android/app/servertransaction/`）封装 `LaunchActivityItem` / `ResumeActivityItem` 等回调 → `ClientLifecycleManager.scheduleTransaction` 通过 **Binder** 发到应用端 `ApplicationThread.scheduleTransaction`。
- 应用端 `TransactionExecutor.execute` 依次 `cycleToPath`（补齐中间状态，如 onCreate→onStart→onResume 会补 onStart）→ 调 `ActivityThread.handle*`。
- 关键：**生命周期调度是异步 Binder 往返**，不是 AMS 直接调方法；每次状态切换是一笔 ClientTransaction，应用端按 `LifecycleState` 顺序补齐。

**追问链**
1. `onStart` 一定在 `onResume` 之前吗？AMS 能保证吗？（答：保证；`TransactionExecutor.cycleToPath` 用 `LifecycleState` 的 `getTargetState` 顺序（PRE_ON_CREATE→ON_CREATE→ON_START→ON_RESUME）补齐，缺哪个补哪个）
2. `onSaveInstanceState` 在 `onStop` 前还是后？（答：API 11+ 在 `onStop` **之前**（旧版在之前更早的 onPause 后）；新版本为了不阻塞 onPause，移到 onStop 前通过 `PauseActivityItem`/`StopActivityItem` 调度）
3. 横竖屏切换走哪些生命周期？（答：默认销毁重建：onPause→onSaveInstanceState→onStop→onDestroy→onCreate→onStart→onRestoreInstanceState→onResume；可配 `configChanges` 拦截避免重建）
4. `launchMode` 在哪一层被解析？singleTask/singleInstance 的 task 归属？（答：`ActivityStarter` 解析 `Intent` flag + manifest `launchMode`；singleTask 在 `Task` 栈底复用，`RootWindowContainer.findTask`）

**易错点**
- 生命周期是"事务 + 补齐"，不是 AMS 直调；所以你在 `onCreate` 里 post 一个消息，可能 `onStart` 已经在另一个事务里先跑了（但同一 Activity 的生命周期事务在应用端是串行排队的）。
- `onSaveInstanceState` 存的数据走 Binder 有大小限制（和题 2 的 TransactionTooLarge 同源）。

---

### 题 6：View 的 measure / layout / draw 三阶段，为什么 measure 可能执行多次？自定义 View 怎么避免？

**标准解析**
- `ViewRootImpl.performTraversals` → `performMeasure` → `performLayout` → `performDraw`。
- `measure`：`View.measure(int widthSpec, int heightSpec)` → `onMeasure`；核心是 `MeasureSpec`（30 位 size + 2 位 mode：UNSPECIFIED / EXACTLY / AT_MOST，由父 `ViewGroup` 经 `getChildMeasureSpec` 计算）。`measure` 会缓存 `mMeasuredWidth/Height`，但父容器尺寸不确定（如 `WRAP_CONTENT` 父 + 子 `MATCH_PARENT`）需要"先量一遍子 → 父再定自己 → 再量子"，故可能 **两次甚至多次**。
- `layout`：`View.layout(l,t,r,b)` → `onLayout`（`ViewGroup` 在此摆子）。
- `draw`：`View.draw` 六步：背景 → 自身 `onDraw` → `dispatchDraw`(子) → 前景 → 滚动条 → 默认/覆盖（`ViewGroup` 默认 `dispatchDraw` 画子）。

**追问链**
1. `onMeasure` 里必须调 `setMeasuredDimension` 吗？（答：必须；不调抛 `IllegalStateException("onMeasure() did not set the measured dimension")`）
2. `wrap_content` 在自定义 View 不处理会怎样？（答：默认等价于 `MATCH_PARENT`（因为 `getDefaultSize` 对 AT_MOST 用 `specSize` 即父尺寸）；要在 `onMeasure` 给默认最小尺寸）
3. `requestLayout` vs `invalidate` 区别？（答：`requestLayout` 触发 measure+layout+draw（从根重排，`mPrivateFlags` 置 `PFLAG_FORCE_LAYOUT`）；`invalidate` 只触发 draw（重绘，`PFLAG_DIRTY`））
4. `onDraw` 里 new 对象为什么不好？（答：每帧分配触发 GC，造成卡顿；应提到 `Canvas` 复用、避免 `Paint` 反复创建）
5. `DecorView` 是 `FrameLayout` 吗？`measure` 从哪开始？（答：是；从 `ViewRootImpl` 用 `DecorView` 的 `MeasureSpec`（窗口尺寸，EXACTLY）开始向下递归）

**易错点**
- `onMeasure` 调 `setMeasuredDimension` 是硬要求。
- `requestLayout` 会冒泡到根重排，滥用导致全树重测量（性能坑）。
- measure 多次是正常机制（WRAP_CONTENT 父），不是 bug。

---

### 题 7：ANR 是怎么触发的？input / Service / Broadcast 超时阈值各是多少？怎么溯源？

**标准解析**
- **Input ANR**（最常见）：`InputDispatcher` 派发触摸/按键事件后，目标 App 在 **5 秒**内没处理完（`frameworks/native/services/inputflinger/InputDispatcher.cpp` 的 `mAnrTimeouts`/`handleTargetsNotReadyLocked` 触发 `onANR` → `InputManagerService.notifyANR` → `AMS.appNotResponding`）。
- **Service ANR**：`ActiveServices`：`onCreate` 前台 **20 秒** / 后台 **200 秒**（`SERVICE_TIMEOUT` / `SERVICE_BACKGROUND_TIMEOUT`）。
- **Broadcast ANR**：`BroadcastQueue`：前台 `onReceive` **10 秒** / 后台 **60 秒**（`BROADCAST_FG_TIMEOUT` / `BG_TIMEOUT`）。
- **ContentProvider ANR**：`publish` 超时 **10 秒**（`CONTENT_PROVIDER_PUBLISH_TIMEOUT`）。
- 触发后 `ActivityManagerService.appNotResponding`：采集 `traces.txt`（`/data/anr/`）、`cpuinfo`、`binder` 状态、`/proc/pid` 堆栈；若是输入 ANR 还带 `reason` "Input dispatching timed out"。
- 现代溯源首选 **Perfetto / systrace**：看主线程是否被 Binder 阻塞（`binder_thread` 等待）、long `Choreographer#doFrame`、锁竞争（`monitor contention`）。

**追问链**
1. 为什么主线程 sleep 1 秒不一定 ANR？（答：只要没在"等待输入事件的前台"且无 Service/Broadcast 在超时窗口内；但若恰好有触摸事件派发中，5s 就 ANR）
2. `onReceiver` 里开线程做耗时操作能躲 Broadcast ANR 吗？（答：不能；`onReceive` 返回即认为处理完毕，且 10s 窗口算的是 `onReceive` 本身；耗时该用 `goAsync()` + 异步后 `PendingResult.finish()`）
3. `StrictMode` 能防 ANR 吗？（答：能提前暴露主线程 IO / 磁盘读写等隐患，但不直接防 ANR）
4. 鸿蒙/其他系统没 ANR 概念吗？（答：概念不同；Android 的 ANR 核心是"系统服务等不到应用响应"，属响应式超时保护）

**易错点**
- 阈值因前台/后台、机型 ROM 略有差异（厂商可能改），但官方基准是上述值。
- ANR 不一定是主线程死循环，常见真凶是**主线程同步 Binder 调用卡在远端**（如 `getProvider` 等 ContentProvider、`getInstalledPackages`）。

**话术框架**
> 结论：ANR 是系统服务在固定窗口内没收到应用响应；类型和阈值不同。
> 分层：Input 5s / Service 前台20s后台200s / Broadcast 前台10s后台60s / Provider 10s。
> 溯源：先 `traces.txt` 看主线程栈，再用 Perfetto 看是否卡在 Binder/锁/长帧。

---

### 题 8：Android 内存不足时，进程是怎么被杀的？LMKD / PSI / cgroup / ART GC 各自角色？

**标准解析**
- **ART GC**：应用内回收。Android 14 默认 **CMC（Concurrent Mark-Compact）**（`art/runtime/gc/collector/concurrent_copying.cc`），并发标记-压缩，减少碎片、低暂停；A17 在 CMC 上叠加**分代 GC**（young/old，young 用 `young_concurrent_copying`，`art/runtime/gc/heap.cc` 的 `young_gen`），短命对象只扫年轻代。
- **LMKD**（Low Memory Killer Daemon，`system/core/lmkd/lmkd.cpp`）：用户态守护，监听 **PSI**（Pressure Stall Information，`/proc/pressure/memory`）与 `memory.events`；根据 `oom_adj`（由 AMS 按进程重要性 `computeOomAdjLocked` 打分，`-1000`~`1000`）在内存压力下杀低优先级进程。取代旧内核 `lowmemorykiller` 驱动。
- **cgroup v2**：LMKD 用 `cgroup` 内存上限 + `memory.reclaim` 触发回收；`Process.java` 的 `applyOomAdj` 写 `oom_score_adj` 到 `/proc/<pid>/oom_score_adj`。
- **A17 Memory Limiter**（`frameworks/base/services/core/java/com/android/server/am/MemoryLimiter*`）：对单应用设内存硬上限，超标**静默杀**（死因 `MemoryLimiter:AnonSwap`），与 LMKD（PSI 触发）、内核 OOM（cgroup 超限）是**三条独立杀路径**（详见第十二、十三篇）。
- 应用侧：`ComponentCallbacks2.onTrimMemory(level)`（A14 起只剩 `TRIM_MEMORY_UI_HIDDEN` / `TRIM_MEMORY_RUNNING_CRITICAL` 等少量常量，旧的 `TRIM_MEMORY_BACKGROUND` 等被合并语义）用于释放缓存。

**追问链**
1. `onTrimMemory` 和 `onLowMemory` 区别？（答：`onLowMemory` 是旧接口≈`TRIM_MEMORY_COMPLETE`；`onTrimMemory` 更细，带级别，能区分后台/前台临界）
2. `OOM_ADJ` 怎么影响被杀顺序？（答：adj 越大越先杀；前台 `FOREGROUND_APP_ADJ=0`，cache 进程 adj 大；AMS `updateOomAdj` 周期更新）
3. PSI 的 some/full 含义？（答：`some` = 部分任务因缺页阻塞；`full` = 全部任务都阻塞；LMKD 主要看 `full` 与内存压力阈值）
4. 64 位机型 bitmap 内存算在 Java 堆还是 native？（答：Android 8+ `Bitmap` 像素在 **native**（`Ashmem`/`HardwareBuffer`），不在 Dalvik 堆；但 Java 侧 `Bitmap` 对象仍占少量堆，记得 `recycle()` 在 8+ 非必须但大图仍建议）

**易错点**
- "内存不足就是 OOM 崩溃" 是误解：多数情况是 LMKD 先按 adj 杀进程，OOM 是最后兜底。
- A17 三条杀路径（LMKD / 内核 cgroup OOM / Memory Limiter）相互独立，定位死因要看 `ApplicationExitInfo.getReason()`。

---

### 题 9：掉帧（Jank）是怎么发生的？VSYNC / Choreographer / FrameTimeline 怎么配合保证 60/120fps？

**标准解析**
- 屏幕按 **VSYNC**（垂直同步，通常 60Hz→16.6ms / 120Hz→8.3ms）刷新。`Choreographer`（`frameworks/base/core/java/android/view/Choreographer.java`）是"VSYNC 节拍器"：应用通过 `postFrameCallback` 注册，收到 VSYNC 信号后按回调类型分三个阶段依次执行：
  - `CALLBACK_INPUT`（输入处理）
  - `CALLBACK_ANIMATION`（动画，`AnimationHandler`）
  - `CALLBACK_TRAVERSAL`（布局绘制，`ViewRootImpl.doTraversal`）
- 一帧预算：VSYNC 到来 → 应用必须在下一个 VSYNC 前完成 measure/layout/draw + SurfaceFlinger 合成上屏。若 `doFrame` 耗时 > 帧预算 → **掉帧**（丢 VSYNC，画面停留）。
- `FrameTimeline`（`frameworks/native/services/surfaceflinger/FrameTimeline/`）：记录每帧期望_present vs 实际_present，SF 据此判定 `JankType`（`AppDeadlineMissed` / `SurfaceFlingerDeadlineMissed` / `PredictionError` 等），在 `dumpsys gfxinfo` / Perfetto 可见。
- VSYNC 偏移（`VSyncOffset`）：app 和 SF 的 VSYNC 有相位差，给应用留绘制时间，避免 SF 等不及。

**追问链**
1. `Choreographer.postFrameCallback` 和 `postOnAnimation` 关系？（答：`View.postOnAnimation` 内部就是 `Choreographer.postFrameCallback`；动画基于它保证每 VSYNC 一帧）
2. 为什么 `onDraw` 里做网络/IO 必卡？（答：在 TRAVERSAL 阶段执行，占帧预算，超预算掉帧；且可能触发 StrictMode）
3. 主线程被 Binder 阻塞导致掉帧，责任在 App 还是远端？（答：App 主线程发起同步 Binder 调用本身就不该，责任在调用方设计；可用异步/`oneway`/移到子线程）
4. 120Hz 手机上 60fps 内容会怎样？（答：每 2 个 VSYNC 才需一帧，但若内容按 60 锁帧会"掉到"视觉 60；`DisplayManager` / `SurfaceFlinger` 处理多刷新率，`DisplayModeDirector` 投票选模式，详见图形篇）

**易错点**
- 掉帧≠卡顿感知：偶尔 1 帧丢用户无感，连续丢 / 长帧才明显。
- `Choreographer` 三回调有严格顺序，别在 INPUT 阶段做重活拖慢后续。

---

### 题 10：Compose 为什么能"只重组该重组的"？SlotTable / Snapshot / 强跳过模式讲讲。

**标准解析**
- **SlotTable**（`androidx/compose/runtime/SlotTable.kt`）：用**扁平 IntArray + 对象数组**表示组合树（group 与值交替），类似 gap buffer；重组时按 `key`/位置比对，跳过未变子树。
- **编译器插件**：Kotlin IR lowering 在 Composable 注入 `$composer` 与 `$changed` 位掩码；`$changed` 记录参数是否"changed"，用于**跳过判定**。
- **Snapshot 状态**（`Snapshot.kt` / `snapshotState.kt`）：`mutableStateOf` 是带版本的快照状态。`snapshot` 提供 MVCC 版本链；读状态时 `readObserver` 记录"读取即订阅"（记录到 `SnapshotStateObserver`），状态 `setValue` 时通知观察者标记重组失效。
- **强跳过模式（Strong Skipping）**：A14+ Compose 1.5+ 默认开启——只要参数**稳定**（基本类型 / `@Stable` / 不可变集合 / 带 `@Immutable`）且**未发生结构性变化**，即使函数有默认参数/返回值也跳过重组。
- **Recomposer** 挂在 `Choreographer` 的 **ANIMATION** 回调（先于 TRAVERSAL），与应用 View 遍历同帧先后；子组合 `SubcomposeLayout`、强制单遍测量（`measure` 只能调一次，避免双遍测量死循环）。

**追问链**
1. 带返回值的 Composable 能作为重组边界吗？（答：旧版不能，强跳过后稳定参数下可跳过；但带返回值仍需注意"计算副作用"放 `remember`）
2. `remember` 和 `rememberSaveable` 区别？（答：`remember` 重组保留、进程死丢；`rememberSaveable` 经 `Saver` 存 `Bundle`（跨配置变更/进程重建保留，同样受 Binder 大小限制））
3. `derivedStateOf` / `Flow.collectAsState` 在重组里的坑？（答：频繁变的通知会全树失效；用 `derivedStateOf` 收敛、用 `Flow` 经 `stateIn` 降频）
4. Compose 和 View 接驳的泄漏坑？（答：`ViewCompositionStrategy` 默认 `DisposeOnDetachedFromWindow`；在 `Fragment` 里若 `viewLifecycleOwner` 已销毁但 Compose 没 dispose 会泄漏；用 `DisposeOnViewTreeLifecycleDestroyed`）

**易错点**
- "参数变了才重组"是简化；真实靠 `$changed` + 稳定性推断；**非稳定参数（如可变 data class 无 @Stable）会强制重组整子树**。
- Compose 的语义树对 UI 自动化 Agent 更友好（见第十三篇无障碍语义树），但 `pointerInput{ detectTapGestures }` 没有 `onClick` 语义，自动化点不中。

---

### 题 11：HAL 怎么从 Framework 调到硬件？HIDL→AIDL 迁移、binderized vs passthrough、FMQ 是什么？

**标准解析**
- **Treble 隔离**（Android 8+）：Framework（`system` 分区）与 Vendor（`vendor` 分区）通过 **HAL 接口**解耦，VNDK 提供稳定版 NDK。
- **HIDL**（旧，`hardware/interfaces`，`.hal` → C++/Java）：`IBase` 经 `hwservicemanager` 注册发现；分 `binderized`（独立 HAL 进程，跨进程 Binder 调）和 `passthrough`（同进程 dlopen，`@1.0::IFoo` 直接 `getService` 走 `HIDL_FETCH_*`），passthrough 仅为兼容旧设备。
- **AIDL HAL**（新，`aidl` 接口，Android 10+ 主推，Android 14 大量 HAL 已 AIDL）：用标准 Binder，经 `servicemanager`（或 `vndservicemanager` 供应商域）注册；`IFoo` 直接 `ServiceManager.getService`。**AIDL 统一了 Framework 与 HAL 的 IPC**（不再需要 HIDL 专用栈）。
- **FMQ（Fast Message Queue）**（`android.hardware.fast_msgq`）：基于 **共享内存 + 环形队列**的零拷贝大块数据传输（如音频/相机帧），绕开 Binder 单次 buffer 限制；支持 `unsynchronized`/`synchronized` 两种。
- **SELinux**：HAL 服务有独立域（`hal_foo_server` / `hal_foo_client`），`sepolicy` 限定 system↔vendor 跨分区访问。

**追问链**
1. 为什么 HIDL 要被 AIDL 取代？（答：HIDL 是 Android 专用、维护两套栈；AIDL 已是 Framework 标准，统一后减少复杂度、复用 Binder 生态、便于 Rust 实现）
2. `passthrough` 现在还有必要吗？（答：仅 legacy 设备过渡，新 HAL 一律 binderized；passthrough 无法跨进程隔离、不稳）
3. 大块图像数据为什么不用 Binder 直接传？（答：Binder buffer ~1MB 上限 + 一次拷贝对大帧太重；FMQ / `GraphicBuffer`(DMA-BUF fd) / `Ashmem` 才是正道）
4. vendor 分区怎么保证 Framework 升级不破坏 HAL？（答：VNDK 版本绑定 + `linker namespace` 隔离 + `compat` 矩阵；`lshal` 可查 HAL 版本）

**易错点**
- "AIDL 只能用于 App 间"是错的；AIDL 现在也是 HAL 主用 IPC（与 HIDL 并列但新项目选 AIDL）。
- HAL 进程死亡不会崩 App，但会 `DEAD_OBJECT`；看清 `linkToDeath`。

---

### 题 12：Binder 驱动在内核态怎么工作？binder_ioctl / binder_thread_write / binder_transaction 各自职责？

**标准解析**（`drivers/android/binder.c`，配套 `binder_alloc.c`）
- `binder_open`：每进程打开 `/dev/binder`，建 `binder_proc`，mmap 出 `binder_buffer` 池（`binder_alloc_mmap`）。
- `binder_ioctl`：处理 `BINDER_WRITE_READ`（核心）、`BINDER_SET_MAX_THREADS`（线程池上限 15）、`BINDER_SET_CONTEXT_MGR`（servicemanager 注册）等。
- `binder_thread_write`：解析用户态 `binder_write_read.write_buffer` 的命令流（`BC_*`）：`BC_TRANSACTION`（发起调用）、`BC_REPLY`（回包）、`BC_FREE_BUFFER`、`BC_REQUEST_DEATH_NOTIFICATION` 等，逐条 `binder_transaction`。
- `binder_transaction`：核心搬运——找目标 `binder_proc`/`binder_node`，在目标 `binder_buffer` **分配+拷贝**数据（一次拷贝），处理 `binder_ref` handle 转换、对象（IBinder/文件 fd）的"重定位"（`binder_transaction_ref_to_node` / fd 跨进程 `binder_translate_fd`），构造 `binder_transaction` 入目标 `todo` 队列。
- `binder_thread_read`：从 `todo`/`proc->todo` 取事务，封装 `BR_*`（`BR_TRANSACTION`/`BR_REPLY`/`BR_DEAD_BINDER`）回用户态。
- `binder_alloc.c`：管理 `binder_buffer` 的分配/释放、与 `vm_area` 映射、延迟释放（`binder_free_buf` 入 `delivered_free_buffer` 等用户态确认后真释放）。

**追问链**
1. `binder_buffer` 怎么避免内存泄漏？（答：发送方 `BC_FREE_BUFFER` 确认后才能复用；内核侧 `alloc->free_buffer` 延迟到对端 `BR_FREE_BUFFER`）
2. fd 怎么跨进程传？（答：`binder_translate_fd` 把本进程 fd 在目标进程 `dup` 出新 fd，挂在事务 `binder_fd` 上，目标 `unflatten` 拿到自己空间的 fd——这就是 GraphicBuffer / ParcelFileDescriptor 跨进程零拷贝传数据的底）
3. 一个进程能有几个 Binder 线程？（答：默认上限 15（`DEFAULT_MAX_BINDER_THREADS`），由 `BINDER_SET_MAX_THREADS` 设；线程池由用户态 `IPCThreadState` 按需 `BR_SPAWN_LOOPER` 孵化）
4. `binder_alloc` 的 buffer 用尽会怎样？（答：`binder_transaction` 失败返回 `BR_FAILED_REPLY` / `-ENOMEM`，应用侧 `FAILED_TRANSACTION`）

**易错点**
- 内核 `binder_transaction` 的"拷贝"是**唯一一次**；fd/IBinder 是引用传递不是数据拷贝。
- 驱动层看不懂 Java 对象，只认 Parcel 字节流 + 特殊对象标记（扁平化 `flat_binder_object`）。

---

## 第二部分：跨域综合大题（Senior / 架构岗试金石）

### 综合大题 1：从手指点击桌面图标，到 App 首页可交互，跨了哪些系统服务？逐跳说明 IPC 与线程。

**答题骨架**
1. **InputDispatcher → Launcher（Native→App）**：触摸事件经 `InputReader`/`InputDispatcher`（native）派发到 Launcher 的 `ViewRootImpl`，主线程 `onTouchEvent` 识别点击。
2. **Launcher → AMS（Binder，App→system_server）**：`startActivity` → `ActivityManagerService.startActivity` → `ATMS`。
3. **AMS → Zygote（Socket，system_server→zygote）**：进程不存在 → `Process.start` → Zygote fork（见题 4）。
4. **新进程 → AMS（Binder，App→system_server）**：`ActivityThread.attach` → `AMS.attachApplication`，回传 `ApplicationInfo`、Provider 列表。
5. **App 内部（主线程）**：`bindApplication`（Application）→ `handleLaunchActivity`（Activity + Window）→ `onResume` → `ViewRootImpl.performTraversals`。
6. **ViewRootImpl → SurfaceFlinger（Binder，App→system_server/sf）**：`relayoutWindow` 申请 `Surface`，经 `WindowManagerService` → `SurfaceComposerClient` → SF 的 `createSurface`；绘制数据走 `BufferQueue`（GraphicBuffer / DMA-BUF fd，一次拷贝 + 共享内存）。
7. **SF → 屏幕（HWC + VSYNC）**：SF 在 VSYNC 到达时合成（`HWC` 决定 overlay vs GPU），上屏；`Choreographer` 保证 App 绘制与 VSYNC 对齐（见题 9）。
8. **可交互判定**：首帧 draw 完成 + `onResume` 之后 + `reportFullyDrawn`（业务自认）。

> 考官追问：哪一跳最容易成为性能瓶颈？（答：① AMS 主线程锁竞争；② fork 后 Application 初始化（multidex/ContentProvider 安装）；③ 首帧 measure/layout/draw 过重在主线程；④ Binder 阻塞）

### 综合大题 2：一次"App 调 Camera 拍照存盘"的旅程，数据从 HAL 到磁盘经过哪些层？哪些是一次拷贝、哪些零拷贝？

**答题骨架**
1. App `Camera2` API → `CameraManager` → `CameraService`（Binder，Framework `system` 分区）。
2. `CameraService` → **Camera HAL**（AIDL，跨 `system`→`vendor` 分区，Binder）→ `Camera3Device`/`CaptureSession` 下发 `CaptureRequest`。
3. 传感器出帧 → HAL 填充 `Stream` 的 `GraphicBuffer`（HAL 通过 **gralloc/DMA-BUF** 拿到共享内存，零拷贝从驱动到 HAL）。
4. 帧经 **FMQ / `GraphicBuffer` fd** 回传 Framework（fd 跨进程 dup，数据不拷贝）；App 侧 `ImageReader` 拿到 `HardwareBuffer`。
5. 编码：`MediaCodec`/`Codec2`（`CCodec`）经 **Codec2 HAL**（AIDL）把帧压成 JPEG/H264（Surface 零拷贝输入，经 `BufferQueue`/`GraphicBuffer`）。
6. 写盘：`MediaMuxer`/`FileOutputStream` → `Vold`/`FUSE` 存储栈 → `fscrypt` 加密 → 真实文件系统（ext4/f2fs）。
7. 全程关键的"零拷贝"点：GraphicBuffer(DMA-BUF fd 跨进程) + FMQ 共享内存；"一次拷贝"点：Binder Parcel 传元数据、编码输入经 Surface（BufferQueue 本身靠共享内存，非真的逐字节拷）。

> 考官追问：为什么 Camera 帧不直接用 Binder 传？（答：帧是 MB 级大块，Binder buffer ~1MB + 一次拷贝代价高，必须用共享内存 fd/FMQ）

### 综合大题 3：线上反馈"首页偶发卡顿 + 输入偶发 ANR"，你如何系统性定位？

**答题骨架（金字塔）**
- 结论：先区分是"App 主线程重活"还是"远端 Binder 阻塞"还是"SF 合成慢"。
- 分层定位：
  1. **主线程**：`Choreographer.FrameCallback` 记 `doFrame` 耗时，超帧预算即掉帧；`Looper.getMainLooper().setMessageLogging`（`>>>>> Dispatching`/`<<<<< Finished`）抓长消息；`StrictMode` 开磁盘/网络检测。
  2. **Binder 阻塞**：Perfetto 看主线程是否停在 `binder_thread_read`（等远端）；`dumpsys binder` 看 `binder stats`、慢调用（`BinderCallsStats`）。
  3. **锁竞争**：`monitor contention` 日志 / Perfetto `lock` 标注。
  4. **SF 合成**：`dumpsys gfxinfo <pkg>` 看 `Janky frames`；Perfetto 的 `surfaceflinger` track 看 `FrameTimeline` 的 `JankType`（`AppDeadlineMissed` vs `SurfaceFlingerDeadlineMissed` 区分责任方）。
  5. **内存压力**：`dumpsys meminfo` + `lmkd` 日志看是否因 PSI 杀后台导致冷启重负载。
- 佐证工具：Perfetto（首选）/ systrace / `dumpsys gfxinfo` / `adb shell am hang` / `bugreport`。

> 考官追问：怎么区分 ANR 是输入派发慢还是 App 处理慢？（答：ANR traces 里看主线程栈——停在 `nativePollOnce` 说明在等（可能是输入没来/被别的占），停在业务方法说明 App 自己慢；InputDispatcher 侧的 `dispatchingTimedOut` reason 直接报超时）

---

## 第三部分：易错红榜 TOP 20（考前必背速记）

1. **Binder 是一次拷贝，不是零拷贝**（mmap 那侧才零拷贝）。
2. **Looper 死循环不耗 CPU**（epoll `nativePollOnce` 阻塞等待）。
3. **子线程 new Handler 先 `Looper.prepare()`**（主线程在 `ActivityThread.main` 已 prepare）。
4. **`TransactionTooLargeException`** 是 Parcel 整体过大，常见于 Intent/Bundle/savedInstanceState。
5. **生命周期是异步 ClientTransaction 往返**，不是 AMS 直调；`TransactionExecutor.cycleToPath` 补齐中间状态。
6. **`onSaveInstanceState` 在 `onStop` 之前**（API 11+）。
7. **`onMeasure` 必须 `setMeasuredDimension`**，否则崩溃。
8. **`wrap_content` 不处理 = 当 `match_parent`**（用父 specSize）。
9. **`requestLayout` 触发 measure+layout+draw（根重排）；`invalidate` 只重绘**。
10. **ANR 阈值**：Input 5s / Service 前台20s后台200s / Broadcast 前台10s后台60s / Provider 10s。
11. **ANR 真凶常是主线程同步 Binder 卡远端**，不是死循环。
12. **内存不足先 LMKD 按 adj 杀，OOM 是兜底**；A17 有第三条 Memory Limiter 静默杀。
13. **Bitmap 像素在 native（8+）**，不占 Dalvik 堆但总量要控。
14. **掉帧=超帧预算**，120Hz 帧预算 8.3ms；`FrameTimeline` 记 `JankType`。
15. **`Choreographer` 三回调顺序**：INPUT→ANIMATION→TRAVERSAL；Recomposer 挂 ANIMATION 回调。
16. **Compose 强跳过靠参数稳定性**；非 `@Stable` 参数强制重组整子树。
17. **AIDL 现在也是 HAL 主用 IPC**（取代 HIDL），不限于 App 间。
18. **大块数据走 FMQ / GraphicBuffer(DMA-BUF fd) / Ashmem**，不直传 Binder。
19. **Zygote 用 Socket 不用 Binder**（fork 安全，避免多线程 Binder 状态继承）。
20. **`oneway` 不阻塞等回复但服务端仍可能卡**；Binder 线程池满(15)会排队甚至死锁。

---

## 第四部分：自测评分 Rubric + 答题话术框架

### 评分 Rubric（每题 10 分）

| 维度 | 0 分 | 4 分 | 7 分 | 10 分 |
|------|------|------|------|-------|
| 结论准确 | 答错/混乱 | 结论对但说不清 | 结论对+分层 | 结论+分层+源码/数据佐证 |
| 源码/路径 | 无 | 记错路径 | 关键类/方法对 | 类+方法+文件都对，给版本 |
| 追问抗压 | 一层就卡 | 两层 | 三层 | 四层以上且能纠偏 |
| 易错规避 | 踩坑 | 部分规避 | 主动指出易错 | 易错+延伸阅读都到位 |

**自测用法**：口头答完自己打分，低于 7 分回《速查卡》对应子系统补；能稳定 8+ 说明该域已具备面试级。

### 答题话术框架（金字塔 / STAR）

1. **先结论**（一句话点透本质）。
2. **再分层**（按"进程/线程/IPC/数据"或"启动→初始化→生命周期→渲染"拆）。
3. **后佐证**（报 AOSP 类/方法/文件 + Android 版本差异）。
4. **补易错**（主动说"这里容易错的是…"）。
5. **接追问**（把追问链当彩蛋，考官不问也抛一点显深度）。

> 反例："Binder 就是跨进程通信"（0 分，没有信息量）。
> 正例："Binder 靠 mmap 共享物理内存做到发送方→内核一次拷贝、接收方零拷贝读取…（见题 2）"（10 分骨架）。

### 复习节奏建议（收官冲刺）

- **每日**：3 题连击自测（盖答案→口述→对照→纠错），配《速查卡》查漏。
- **每两日**：1 道综合大题串讲（强迫跨服务连起来讲）。
- **每周**：易错红榜 TOP20 默写 + 一轮 Perfetto 真机抓帧实操。
- **考前 3 天**：重点复盘第十三篇"三条杀进程路径"、第十二篇"pKVM vs TEE 威胁模型"、第十篇"compat 框架引擎"——这些是区分 Senior 的高频深挖点。

---

## 附：AOSP 源码路径速查（考前索引）

| 子系统 | 关键文件（Android 14） |
|--------|------------------------|
| Handler/Looper | `frameworks/base/core/java/android/os/{Handler,Looper,MessageQueue}.java`、`system/core/libutils/Looper.cpp`、`core/jni/android_os_MessageQueue.cpp` |
| Binder(Framework) | `frameworks/base/core/java/android/os/Binder.java`、`frameworks/native/libs/binder/{BpBinder,IPCThreadState,ProcessState}.cpp` |
| Binder(驱动) | `drivers/android/binder.c`、`drivers/android/binder_alloc.c` |
| 启动/Zygote | `frameworks/base/core/java/com/android/internal/os/{ZygoteInit,ZygoteConnection,RuntimeInit}.java`、`ActivityThread.java` |
| AMS/ATMS | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java`、`.../wm/{ActivityTaskSupervisor,ActivityRecord,RootWindowContainer}.java`、`core/java/android/app/servertransaction/` |
| WMS/View | `frameworks/base/core/java/android/view/{View,ViewRootImpl,Choreographer}.java` |
| SF/图形 | `frameworks/native/services/surfaceflinger/{SurfaceFlinger,FrameTimeline}/`、`Scheduler/` |
| ANR | `.../am/ActivityManagerService.java(appNotResponding)`、`ActiveServices.java`、`BroadcastQueue.java`、`native/services/inputflinger/InputDispatcher.cpp` |
| LMKD/内存 | `system/core/lmkd/lmkd.cpp`、`art/runtime/gc/{heap,collector/concurrent_copying}.cc`、`.../am/MemoryLimiter*`(A17) |
| Compose | `androidx/compose/runtime/{SlotTable,Snapshot,Recomposer}.kt`、`snapshotState.kt` |
| HAL | `hardware/interfaces/`(HIDL)、`aidl/`(AIDL HAL)、`hardware/libhardware/`、`android.hardware.fast_msgq`(FMQ) |
| 内核 | `drivers/android/binder.c`、`kernel/sched/`、`mm/`、`fs/` |

> 完整 132 专题交叉索引见前 17 篇（尤其 8/5 上午《考前总复习速查卡》）。本篇是"把知识变成考场输出能力"的连击训练，不新增角度。

---

*本篇为 Android Framework 面试题自动化系列第十八篇（2026-08-05），承接前 17 篇约 132 专题与《考前总复习速查卡》，定位于"高频考官连击模拟考"。所有源码路径以 Android 14 (android-14.0.0_rXX) 为基线，版本差异（A17 等）已标注。*
