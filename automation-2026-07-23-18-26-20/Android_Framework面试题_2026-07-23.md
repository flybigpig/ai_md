# Android Framework 热点面试题深度解析（2026-07-23）

> 基准版本：**Android 14 (UpsideDownCake, API 34)**，AOSP 分支 android-14.0.0_rXX，内核 GKI android14-6.1。
> 今日热点方向（据近期面经/技术社区归纳）：Binder 驱动层细节、启动链路（ATMS 时代）、HAL 演进（HIDL→AIDL）、ANR 底层机制、Compose 重组原理、MTK 平台实战。

---

## 目录

1. Handler/Looper：主线程死循环为什么不卡死？
2. Handler 进阶：同步屏障与 Choreographer 的配合
3. Binder：一次拷贝到底拷了什么？mmap 细节
4. Binder 驱动：四大核心数据结构与事务流转
5. Binder 线程池与 TransactionTooLargeException
6. App 冷启动完整链路（Android 14 / ATMS 视角）
7. Zygote：为什么用 Socket 而不用 Binder？
8. AMS/ATMS：职责拆分与 oom_adj 计算
9. WMS 与 SurfaceFlinger：一帧画面的旅程
10. View 绘制三部曲与 MeasureSpec
11. ANR：5 秒到底从哪里开始算？
12. 内存优化：LMKD、PSI 与进程回收
13. Jetpack Compose：重组的底层机制（SlotTable/Snapshot）
14. HAL：Treble、HIDL→AIDL、FMQ
15. Linux Kernel/驱动：Framework 工程师需要懂多少内核？
16. MTK 平台专题：DuraSpeed、AEE 与平台差异排查
17. 查缺补漏清单 & 延伸阅读

---

## 1. Handler/Looper：主线程死循环为什么不卡死？

**面试题：`Looper.loop()` 是死循环，为什么主线程不会 ANR、不会占满 CPU？**

### 答案解析

主线程的生命本质就是这个循环。`ActivityThread.main()` 的最后两行：

```java
// frameworks/base/core/java/android/app/ActivityThread.java
Looper.prepareMainLooper();
...
Looper.loop();
throw new RuntimeException("Main thread loop unexpectedly exited");
```

不卡死的关键在 **消息队列空闲时线程会挂起让出 CPU**，底层依赖 Linux 的 **epoll**：

```
Looper.loop()
 └─ MessageQueue.next()                      // frameworks/base/core/java/android/os/MessageQueue.java
     └─ nativePollOnce(ptr, nextPollTimeoutMillis)
         └─ android_os_MessageQueue.cpp: NativeMessageQueue::pollOnce()
             └─ Looper::pollOnce() / pollInner()   // system/core/libutils/Looper.cpp
                 └─ epoll_wait(mEpollFd, ...)      // 无消息时阻塞，线程进入休眠态
```

- `Looper::Looper()` 构造时创建 `mWakeEventFd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC)`，并 `epoll_ctl(EPOLL_CTL_ADD)` 注册到 `mEpollFd`。
- 有新消息时 `MessageQueue.enqueueMessage()` → `nativeWake()` → `Looper::wake()` → 向 eventfd 写入 1，`epoll_wait` 返回，线程被唤醒。
- ANR 的本质不是"循环慢"，而是**某条消息执行太久**，导致后续的 input/生命周期消息不能及时处理（见第 11 题）。

### 易错点

- ❌ "死循环耗 CPU" —— epoll_wait 阻塞时线程是 **休眠态（S）**，CPU 占用为 0。
- ❌ 混淆 `nativePollOnce` 的超时参数：`-1` 表示无限等待，`0` 表示立即返回（有 IdleHandler 处理完后二次进入用 0）。
- ❌ 认为主线程 Looper 可以 `quit()`：`prepareMainLooper` 内部 `new Looper(false)`，`quitAllowed=false`，调用 `quit()` 会抛异常。

### 高频追问

1. **`Message.obtain()` 为什么用享元模式？** 全局链表池 `sPool`（最多 50 个，`MAX_POOL_SIZE`），避免频繁 GC；`recycleUnchecked()` 归还。
2. **延迟消息如何实现？** 队列按 `when`（`SystemClock.uptimeMillis()` 基准）排序插入，`next()` 计算 `nextPollTimeoutMillis = when - now` 传给 epoll。**注意用的是 uptimeMillis，深睡眠时不计时**。
3. **IdleHandler 的应用？** 队列空闲时回调，常用于启动优化延迟初始化；`ActivityThread` 里 GcIdler 就靠它。
4. **Native 层也有 Handler 体系吗？** 有：`Looper.cpp` + `MessageHandler`，InputDispatcher、SurfaceFlinger 都用 native Looper。

### 延伸阅读
- `frameworks/base/core/jni/android_os_MessageQueue.cpp`
- `system/core/libutils/Looper.cpp`（epoll 实例 + eventfd 唤醒）

---

## 2. Handler 进阶：同步屏障与 Choreographer 的配合

**面试题：什么是同步屏障（sync barrier）？UI 绘制为什么需要它？**

### 答案解析

同步屏障是一条 **target(Handler) 为 null 的 Message**。`MessageQueue.next()` 遇到屏障后会**跳过所有同步消息，只处理异步消息**：

```java
// frameworks/base/core/java/android/os/MessageQueue.java
if (msg != null && msg.target == null) {
    // Stalled by a barrier. Find the next asynchronous message.
    do { prevMsg = msg; msg = msg.next;
    } while (msg != null && !msg.isAsynchronous());
}
```

典型使用者是 **ViewRootImpl**：

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java
void scheduleTraversals() {
    if (!mTraversalScheduled) {
        mTraversalScheduled = true;
        mTraversalBarrier = mHandler.getLooper().getQueue().postSyncBarrier(); // ① 插屏障
        mChoreographer.postCallback(Choreographer.CALLBACK_TRAVERSAL, mTraversalRunnable, null); // ② 等 Vsync
    }
}
```

Vsync 信号到来后，`Choreographer.FrameDisplayEventReceiver.onVsync()` 发送的 `Message` 调用了 `msg.setAsynchronous(true)`，从而**越过屏障优先执行 doFrame → performTraversals**。绘制完成后 `unscheduleTraversals()`/`doTraversal()` 中 `removeSyncBarrier` 移除屏障。

### 易错点

- 屏障不会自动移除，**postSyncBarrier/removeSyncBarrier 必须配对**，否则同步消息永久阻塞（表现为"主线程活着但 UI 冻结"）。
- `postSyncBarrier` 是 hide API，业务代码不能直接用；只能通过 `Message.setAsynchronous` 或 `Handler(async=true)`（`Handler.createAsync()`）沾光。

### 高频追问

1. **Choreographer 的 Vsync 从哪来？** SurfaceFlinger 的 DispSync/VsyncSchedule 模型分发，App 端通过 `DisplayEventReceiver`（socketpair + epoll）接收，`frameworks/base/core/java/android/view/Choreographer.java` + `frameworks/native/libs/gui/DisplayEventReceiver.cpp`。
2. **掉帧如何量化？** `doFrame()` 里 `jitterNanos = startNanos - frameTimeNanos`，超过 `frameIntervalNanos` 打印 "Skipped N frames"。
3. **为什么不直接 postAtFrontOfQueue？** 插队解决不了"排在后面的绘制消息被业务消息淹没"的问题，屏障是"暂停整类消息"，语义完全不同。

---

## 3. Binder：一次拷贝到底拷了什么？mmap 细节

**面试题：都说 Binder 只有一次拷贝，这次拷贝发生在哪？共享内存零拷贝为什么反而不用？**

### 答案解析

传统 IPC（socket/pipe）：发送方用户空间 → 内核缓冲区（第 1 次 `copy_from_user`）→ 接收方用户空间（第 2 次 `copy_to_user`）。

Binder 的做法：**接收方**在打开 binder 后 `mmap` 一段虚拟内存，这段虚拟地址与内核中分配的物理页**同时映射**（内核态地址与用户态地址指向同一物理页）：

```cpp
// frameworks/native/libs/binder/ProcessState.cpp
#define BINDER_VM_SIZE ((1 * 1024 * 1024) - sysconf(_SC_PAGE_SIZE) * 2)  // ≈1MB-8KB
mVMStart = mmap(nullptr, BINDER_VM_SIZE, PROT_READ, MAP_PRIVATE | MAP_NORESERVE, opened.value(), 0);
```

内核侧由 `binder_alloc.c` 管理这块 buffer：

```
drivers/android/binder.c:    binder_transaction()
 └─ binder_alloc_new_buf()          // drivers/android/binder_alloc.c，从目标进程 mmap 区分配 binder_buffer
 └─ binder_alloc_copy_user_to_buffer()
     └─ copy_from_user()            // ★ 唯一一次数据拷贝：发送方用户空间 → 目标进程的内核缓冲区
```

由于目标进程的用户态虚拟地址已映射同一物理页，Server 端**直接读**，无需第二次拷贝。

**为什么不用纯共享内存（0 次拷贝）？**
- 共享内存双方都可写，需要自己做同步/互斥，安全性差；
- Binder 驱动在内核层附带 **UID/PID 鉴权**（`binder_transaction_data` 由驱动填充 sender_euid，不可伪造）、对象生命周期管理（引用计数）、死亡通知；
- Android 中真正的大数据（图像/音频）走 `ashmem`/`dmabuf`，通过 Binder 只传 fd（fd 在驱动里做 `dup` 转换，`binder.c: binder_translate_fd()`）——**Binder 传大数据的正确姿势是传文件描述符**。

### 易错点

- "一次拷贝"指 **数据体**；控制协议（`binder_write_read` 结构）仍有 copy_from_user/copy_to_user，但那是小头部。
- mmap 区是 **只读的（PROT_READ）**，Server 不能改；应答数据是反方向再来一次"一次拷贝"。
- Android 8+ 有三个 binder 域：`/dev/binder`（framework）、`/dev/hwbinder`（HAL）、`/dev/vndbinder`（vendor 进程间），上下文管理器各自独立。

### 高频追问

1. **oneway 和非 oneway 区别？** oneway 异步不等待回复，且同一目标节点的 oneway 事务在驱动里**串行化**（`binder_node` 的 async_todo 队列）；async 空间只有 mmap 的一半（约 512KB）。
2. **ServiceManager 为什么 handle 恒为 0？** 它通过 `BINDER_SET_CONTEXT_MGR`(`binder_ioctl`) 注册为 context manager，驱动里特殊节点 `binder_context->binder_context_mgr_node`。Android 14 中 SM 自身也用 libbinder（`frameworks/native/cmds/servicemanager/main.cpp`）。
3. **linkToDeath 原理？** 驱动在持有者进程死亡（`binder_release`）时遍历 `binder_ref`，向注册过 death notification 的进程投递 `BR_DEAD_BINDER`。

---

## 4. Binder 驱动：四大核心数据结构与事务流转

**面试题：讲讲 binder 驱动里 binder_proc / binder_node / binder_ref / binder_thread 的关系。**

### 答案解析

全部位于 `common/drivers/android/binder.c`、`binder_internal.h`（GKI android14-6.1）：

| 结构 | 含义 | 关键字段 |
|---|---|---|
| `binder_proc` | 每个打开 /dev/binder 的进程 | `threads`(rb树)、`nodes`(rb树)、`refs_by_desc/refs_by_node`、`todo` 队列、`alloc`(binder_alloc) |
| `binder_node` | Binder 实体（Server 端对象在内核的化身） | `ptr/cookie`(指向用户态 BBinder)、`refs` 链表、`async_todo` |
| `binder_ref` | 某进程对远端 node 的引用（句柄） | `desc`(即用户态 handle 数字)、`node` 指针 |
| `binder_thread` | 进程内参与 binder 通信的线程 | `transaction_stack`(事务栈)、`todo`、`looper` 状态位 |

**一次同步事务的流转（Client → Server）：**

```
Client: BpBinder::transact()                        // frameworks/native/libs/binder/BpBinder.cpp
 └─ IPCThreadState::transact() → writeTransactionData(BC_TRANSACTION)
     └─ talkWithDriver() → ioctl(fd, BINDER_WRITE_READ)
         └─ [内核] binder_ioctl_write_read → binder_thread_write → binder_transaction()
              ├─ 由 handle 找到 binder_ref → binder_node → 目标 binder_proc
              ├─ 在目标进程 mmap 区分配 buffer，一次拷贝数据
              ├─ 挑选/唤醒目标 binder_thread（优先事务栈上有关联的线程 → 优先级继承）
              └─ 将 binder_transaction 挂入目标 todo，wake_up
Server: binder 线程从 binder_thread_read 返回 BR_TRANSACTION
 └─ IPCThreadState::executeCommand() → BBinder::transact() → onTransact()  // Binder.cpp
 └─ 应答走 BC_REPLY，原路返回 BR_REPLY
```

### 易错点

- handle（desc）是 **进程本地** 的，跨进程传 Binder 对象时驱动会做翻译（`binder_translate_binder()`：flat_binder_object 的 BINDER_TYPE_BINDER ↔ BINDER_TYPE_HANDLE 转换）。这是"Binder 是唯一能跨进程传对象引用的 IPC"的根本。
- `transaction_stack` 支撑了**同步调用的线程复用**：A→B 期间 B 回调 A，会复用 A 正在等待的那条线程，避免死锁（但双向同步调用 + 各自持锁仍可能互等——经典 system_server watchdog 死锁场景）。
- Android 14 内核已引入 **Rust Binder**（`drivers/android/binder/` Rust 实现，android14-6.1 后逐步落地），面试提一句是加分项。

### 高频追问

1. **优先级继承？** `binder_transaction_priority()`：同步事务中目标线程会继承调用方优先级（含 RT），避免优先级反转。
2. **`dumpsys binder` / debugfs 看什么？** `/sys/kernel/debug/binder/{stats,state,transactions}`：pending transaction 堆积的进程就是瓶颈。
3. **FLAT_BINDER_FLAG_TXN_SECURITY_CTX？** ServiceManager 用它拿调用方 SELinux context 做 `selinux_check_access`。

---

## 5. Binder 线程池与 TransactionTooLargeException

**面试题：Binder 线程池多大？打满会怎样？TransactionTooLargeException 怎么产生、怎么排查？**

### 答案解析

**线程池：**

```cpp
// frameworks/native/libs/binder/ProcessState.cpp
#define DEFAULT_MAX_BINDER_THREADS 15   // + 主 binder 线程，普通进程一般最多 16 个
ioctl(fd, BINDER_SET_MAX_THREADS, &maxThreads);
```

- 线程按需创建：驱动发现无空闲线程且未达上限时返回 `BR_SPAWN_LOOPER`，用户态 `ProcessState::spawnPooledThread()` 起新线程（名字 `Binder:pid_x`）。
- system_server 会调高上限（31）。**线程池打满后新请求在驱动 todo 队列排队**：若你的进程是服务方，所有客户端同步调用全部阻塞——app 侧表现为卡顿甚至连环 ANR。

**TransactionTooLargeException：**
- mmap 缓冲区约 1MB（且 async 只有一半），是**整个进程所有并发事务共享**的；单次事务过大或并发挤占都会导致驱动返回 `BR_FAILED_REPLY`，用户态抛 TTLE。
- 高发场景：`Intent`/`Bundle` 塞大图、`onSaveInstanceState` 存大数据（`ActivityClientRecord` 状态经 Binder 传给 AMS）、AIDL 返回巨型列表。
- 排查：异常栈 + `logcat` 中 `binder: ... transaction failed`，`debugfs` 的 transaction log；解决：传 fd（ashmem/ParcelFileDescriptor）、分页、`Bundle` 瘦身。

### 易错点

- ❌ "每个事务上限 1MB"：是**共享池**，实际单次建议 < 200KB。
- ❌ oneway 就安全：oneway 挤占 async 空间，堆积后同样失败，且失败**静默**（无 reply）。
- 主线程发起的同步 Binder 调用如果对端慢，自己就 ANR —— `ContentProvider.call`、`PackageManager` 批量查询都要小心。

### 高频追问

1. **如何监控 Binder 卡顿？** `StrictMode`（检测主线程 binder 调用需自定义）、`perfetto` 的 binder tracks、`am trace-ipc`。
2. **Java 层 Binder 对象与 Native 的关系？** `android.os.Binder`（Java）↔ `JavaBBinder`（`frameworks/base/core/jni/android_util_Binder.cpp`），`BinderProxy` ↔ `BpBinder`。

---

## 6. App 冷启动完整链路（Android 14 / ATMS 视角）

**面试题：从点击 Launcher 图标到首帧显示，完整讲一遍流程。**

### 答案解析

**阶段一：Launcher → system_server（Binder）**

```
Launcher: Activity.startActivity()
 └─ Instrumentation.execStartActivity()
     └─ ActivityTaskManager.getService().startActivity()   // IActivityTaskManager，Binder IPC
         └─ ActivityTaskManagerService.startActivity()     // services/core/java/com/android/server/wm/ActivityTaskManagerService.java
             └─ ActivityStarter.execute() → executeRequest()   // 同目录 ActivityStarter.java
                 ├─ 权限/intent 解析（resolveActivity → PMS）
                 ├─ 创建 ActivityRecord
                 └─ RootWindowContainer.resumeFocusedTasksTopActivities()
                     └─ TaskFragment.resumeTopActivity → 发现进程不存在
                         └─ ActivityTaskSupervisor.startSpecificActivity()
                             └─ ATMS.startProcessAsync → AMS.startProcessLocked()   // am/ActivityManagerService.java
```

**阶段二：Zygote fork（Socket）**

```
ProcessList.startProcess() → Process.start() → ZygoteProcess.startViaZygote()
 └─ LocalSocket 连接 /dev/socket/zygote，写入 argv
Zygote 进程: ZygoteServer.runSelectLoop()                    // core/java/com/android/internal/os/ZygoteServer.java
 └─ ZygoteConnection.processCommand()
     └─ Zygote.forkAndSpecialize()
         └─ nativeForkAndSpecialize → com_android_internal_os_Zygote.cpp → fork()
子进程: 关闭 zygote socket、设置 seccomp/SELinux domain、启动 binder 线程池
 └─ 反射调用 ActivityThread.main()
```

**阶段三：应用进程初始化**

```
ActivityThread.main()
 ├─ Looper.prepareMainLooper()
 ├─ thread.attach(false) → AMS.attachApplication(mAppThread)   // 反向 Binder：把 IApplicationThread 给 AMS
 │    └─ AMS.attachApplicationLocked()
 │        ├─ bindApplication → ApplicationThread.bindApplication → H.BIND_APPLICATION
 │        │    └─ handleBindApplication(): 创建 Application、attachBaseContext、装载 ContentProvider(installContentProviders)、Application.onCreate()
 │        └─ ATMS.attachApplication → RootWindowContainer.attachApplication
 │             └─ realStartActivityLocked()                    // ActivityTaskSupervisor.java
 └─ Looper.loop()
```

**阶段四：Activity 启动（事务化，Android 9+）**

```
realStartActivityLocked()
 └─ ClientTransaction + LaunchActivityItem(+ResumeActivityItem)   // core/java/android/app/servertransaction/
     └─ ApplicationThread.scheduleTransaction → H.EXECUTE_TRANSACTION
         └─ TransactionExecutor.execute()
             ├─ handleLaunchActivity → performLaunchActivity: 反射创建 Activity → attach → onCreate(setContentView)
             └─ handleResumeActivity: onResume → wm.addView(decor)
                 └─ ViewRootImpl.setView → requestLayout + WindowSession.addToDisplayAsUser (WMS)
                     └─ performTraversals: measure/layout/draw → 首帧 → SF 合成上屏
```

### 易错点

- Android 10+ 启动 Activity 的入口是 **ATMS**（`wm` 包），不是 AMS；AMS 只管进程/广播/服务/Provider。
- `onCreate` 里 `setContentView` 并没有渲染，**首帧在 onResume 之后**（ViewRootImpl 创建于 handleResumeActivity）。
- ContentProvider 在 **Application.onCreate 之前** 初始化（很多 SDK 借此自动初始化，App Startup 的原理）。

### 高频追问

1. **`am start -W` 的 TotalTime/WaitTime 差异？** WaitTime 包含 AMS 侧 paused 前一个 Activity 等的时间；差值大说明系统侧排队久。
2. **冷启动优化落点？** Application 阶段（SDK 延迟/异步 init）、Provider 合并（App Startup）、首帧（布局层级、异步 inflate、baseline profile / ART 预编译）。
3. **specialProcess 与 WebView zygote？** `webview_zygote`/appzygote 用于隔离进程加速 fork。

---

## 7. Zygote：为什么用 Socket 而不用 Binder？

**面试题：AMS 请求 Zygote fork 为什么走 LocalSocket？**

### 答案解析

三层原因（面试按层答）：

1. **fork 与多线程的天然矛盾**：POSIX `fork()` 只复制调用线程。Binder 通信必须开 binder 线程池（多线程），如果 Zygote 用 Binder，fork 出的子进程里其他线程持有的锁（如 malloc 锁、ART 内部锁）状态不一致，极易死锁。Zygote 必须保持**单线程收请求再 fork**（fork 前 `ZygoteHooks.preFork()` 会停掉 ART 守护线程，就是这个原因的注脚）。
2. **时序**：Zygote 由 init 通过 `init.zygote64_32.rc` 启动，早于 servicemanager 可用状态的依赖关系管理复杂；socket 由 init 创建（`/dev/socket/zygote`）并通过环境变量传 fd，简单可靠。
3. **需求简单**：只有 system_server 一个客户端、低频调用，不需要 Binder 的引用计数/死亡通知/并发能力。

源码佐证：
- `frameworks/base/core/java/com/android/internal/os/ZygoteInit.java: main()` → `forkSystemServer()` + `zygoteServer.runSelectLoop()`
- `ZygoteProcess.java: attemptZygoteSendArgsAndGetResult()`（AMS 侧）
- `com_android_internal_os_Zygote.cpp: ForkCommon()`（fork + 安全上下文设置）

### 易错点

- Zygote 预加载（`preload()`：preloadClasses ~数千个类、preloadResources、preloadSharedLibraries）+ **COW（写时复制）** 才是应用秒起的根基；面试常把"为什么要 Zygote"和"为什么用 socket"混着问。
- system_server 是 Zygote fork 的**第一个也是特殊的**子进程（`forkSystemServer()`），fork 后 Zygote 才进入 select loop。

### 高频追问

1. **USAP（Unspecialized App Process）池了解吗？** Android 10+ 可选特性，预 fork 一池空白进程，收到请求时 specialize，绕过 fork 延迟（`ZygoteServer.java: fillUsapPool`）。
2. **fork 后子进程如何变成"应用"？** `SpecializeCommon()`：setuid/setgid、setgroups、SELinux setcon、seccomp filter、进程名，然后才跑 Java 入口。

---

## 8. AMS/ATMS：职责拆分与 oom_adj 计算

**面试题：AMS 和 ATMS 怎么分工？oom_adj 是怎么算出来并生效的？**

### 答案解析

**拆分（Android 10 起）：**

| 服务 | 路径 | 职责 |
|---|---|---|
| AMS | `services/core/java/com/android/server/am/ActivityManagerService.java` | 进程管理、广播、Service、Provider、oom_adj |
| ATMS | `services/core/java/com/android/server/wm/ActivityTaskManagerService.java` | Activity 生命周期、Task/RootWindowContainer、多窗口 |

拆到 `wm` 包是为了让 Activity 管理与窗口层级（WindowContainer 树）**同锁同包**协作——Android 14 中 Task/ActivityRecord 本身就是 WindowContainer 子类，AMS 时代跨锁死锁问题被根治。

**oom_adj 链路：**

```
事件触发（组件状态变化/绑定关系变化）
 └─ OomAdjuster.updateOomAdjLocked()          // services/core/java/com/android/server/am/OomAdjuster.java
     ├─ computeOomAdjLSP(): 依据进程状态给 adj
     │    // ProcessList.java 中的档位：
     │    // FOREGROUND_APP_ADJ=0, VISIBLE_APP_ADJ=100, PERCEPTIBLE_APP_ADJ=200,
     │    // SERVICE_ADJ=500, HOME_APP_ADJ=600, PREVIOUS_APP_ADJ=700, CACHED_APP_MIN/MAX=900/999
     │    // + bindService 依赖链传递（BIND_IMPORTANT 等 flag 影响宿主 adj）
     └─ applyOomAdjLSP() → ProcessList.setOomAdj()
         └─ 写入 lmkd：通过 socket /dev/socket/lmkd 发送 LMK_PROCPRIO
             └─ lmkd 写 /proc/<pid>/oom_score_adj      // system/memory/lmkd/lmkd.cpp
```

Android 14 引入 **CachedAppOptimizer**（`am/CachedAppOptimizer.java`）：cached 进程冻结（cgroup freezer v2, `/sys/fs/cgroup/.../cgroup.freeze`）与 compaction（`process_madvise`），冻结的进程收不到 binder 事务（驱动配合 `BINDER_FREEZE` ioctl，`binder.c: binder_ioctl_freeze`）——**"进程被冻结后 binder 调用抛 DeadObjectException/阻塞"是 Android 12+ 新增高频实战题**。

### 易错点

- oom_adj 与 Linux OOM killer 的 oom_score_adj 是**联动但不同层**：真正杀进程的主力是用户态 **lmkd**（基于 PSI 压力事件），内核 OOM killer 是最后防线。
- `startForegroundService` 后 5 秒内必须 `startForeground()`，否则 ANR（`ActiveServices.SERVICE_START_FOREGROUND_TIMEOUT`）。
- 绑定关系会"抬"宿主进程优先级，这是保活手段（相互绑定）曾经有效的原理；Android 14 对后台启动/缓存进程限制更严（BAL 限制、`PROCESS_STATE_*` 收紧）。

### 高频追问

1. **Watchdog 机制？** `com/android/server/Watchdog.java` 监控 system_server 关键锁与线程（fg/ui/io/display），60s 无响应 kill system_server 重启框架。
2. **进程启动失败/被杀常见原因排查？** `am_proc_died`/`am_kill` event log + `dumpsys activity processes` 的 adj 快照。

---

## 9. WMS 与 SurfaceFlinger：一帧画面的旅程

**面试题：App 的一帧是如何显示到屏幕上的？WMS 在其中做什么？**

### 答案解析

**角色分工**：WMS 管"窗口的秩序"（层级、大小、焦点、动画、input 目标），SurfaceFlinger 管"像素的合成"。

```
① 建窗：ViewRootImpl.setView()
   └─ mWindowSession.addToDisplayAsUser()          // IWindowSession → WMS
       └─ WindowManagerService.addWindow()          // services/core/java/com/android/server/wm/WindowManagerService.java
           └─ 创建 WindowState，挂入 DisplayContent 窗口树
② 拿 Surface：performTraversals → relayoutWindow()
   └─ WMS.relayoutWindow → WindowStateAnimator/createSurfaceControl
       └─ 返回 SurfaceControl，App 端得到 Surface（生产者）
③ 生产帧：draw → ThreadedRenderer(RenderThread, hwui) 
   └─ skia/GL/Vulkan 渲染到从 BLASTBufferQueue 申请的 GraphicBuffer
   └─ Android 12+ 全面 BLAST：帧 buffer 随 SurfaceControl.Transaction 提交给 SF
       // frameworks/base/libs/hwui/、frameworks/native/libs/gui/BLASTBufferQueue.cpp
④ 合成：SurfaceFlinger 在 Vsync-sf 到来时
   └─ MessageQueue::handleMessage → commit()/composite()   // frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp
       ├─ 尝试 HWC 硬件合成（DisplayHAL: composer3 AIDL）
       └─ 兜底 GPU 合成（RenderEngine）
⑤ 上屏：FramebufferSurface/DisplayDevice → DRM/KMS 内核显示驱动
```

**Vsync 模型**：SF 的 `Scheduler/VsyncSchedule` 基于硬件 Vsync 建模出软件 Vsync，App（Choreographer）与 SF 各有相位偏移（app phase / sf phase），构成流水线：N 帧 App 绘制、N+1 帧 SF 合成、N+2 帧上屏 —— 这也是"三缓冲"存在的原因（吸收单帧抖动）。

### 易错点

- "双缓冲/三缓冲"指 BufferQueue 里 GraphicBuffer 数量，与 View 层无关；Triple buffer 增加一帧延迟换稳定性。
- Android 14 上窗口动画大多由 **SurfaceControl.Transaction + Shell(WindowOrganizer)** 驱动（WMShell），不再是 WMS 内老 WindowAnimator 一家独大。
- `Surface` 跨进程传的是生产端句柄，真正内存是 GraphicBuffer（dmabuf），跨进程零拷贝。

### 高频追问

1. **软件绘制 vs 硬件加速？** 软件：`Surface.lockCanvas` CPU 画；硬件：RenderThread + DisplayList（RenderNode），主线程只录制不光栅化。
2. **为什么 Activity 切换会黑屏/白屏？** StartingWindow（Splash Screen）由 WMS 先挂一个临时窗口；主题不当就露底色。
3. **Choreographer 与 SF Vsync 的关系？**（见第 2 题）App 不直接收硬件 Vsync，收的是 SF 分发的软件 Vsync。

---

## 10. View 绘制三部曲与 MeasureSpec

**面试题：measure/layout/draw 流程？MeasureSpec 怎么由父子共同决定？invalidate 与 requestLayout 区别？**

### 答案解析

入口是 `ViewRootImpl.performTraversals()`（`frameworks/base/core/java/android/view/ViewRootImpl.java`）：

```
performTraversals()
 ├─ performMeasure(childWidthMeasureSpec, childHeightMeasureSpec)
 │    └─ mView.measure() → onMeasure() → setMeasuredDimension()
 ├─ performLayout() → mView.layout() → onLayout()（ViewGroup 摆放子 View）
 └─ performDraw() → drawSoftware() 或 mThreadedRenderer.draw()
      └─ View.draw(): ①背景 ②onDraw ③dispatchDraw(子) ④前景/滚动条
```

**MeasureSpec = 2bit mode + 30bit size**，由 **父容器的 MeasureSpec + 子 View 的 LayoutParams** 共同生成：

```java
// ViewGroup.getChildMeasureSpec() 规则表（必背）：
// 父 EXACTLY  + 子固定值      → EXACTLY(childSize)
// 父 EXACTLY  + 子 match_parent → EXACTLY(parentSize)
// 父 EXACTLY  + 子 wrap_content → AT_MOST(parentSize)
// 父 AT_MOST  + 子 match_parent → AT_MOST(parentSize)   ★ 易错：不是 EXACTLY
// 父 UNSPECIFIED（ScrollView 高度方向）→ UNSPECIFIED
```

顶层 DecorView 的 spec 来自窗口尺寸：`ViewRootImpl.getRootMeasureSpec()`。

**invalidate vs requestLayout：**

| | invalidate() | requestLayout() |
|---|---|---|
| 触发 | 仅重绘（draw） | measure + layout（不必然 draw） |
| 传播 | 硬件加速下走 `onDescendantInvalidated` 冒泡标脏 RenderNode | 逐级设 `PFLAG_FORCE_LAYOUT` 到 ViewRootImpl |
| 终点 | `ViewRootImpl.invalidate()` → scheduleTraversals | `ViewRootImpl.requestLayout()` → scheduleTraversals |

两者最终都走 scheduleTraversals（同步屏障 + Vsync，见第 2 题），traversal 里按标志位决定执行哪些阶段。

### 易错点

- `wrap_content` 不处理 `AT_MOST` 时表现同 `match_parent`（自定义 View 经典 bug，`onMeasure` 要对 AT_MOST 给默认尺寸）。
- `onCreate` 里拿不到宽高：measure 发生在 resume 之后的 traversal；用 `View.post{}`（消息排在 traversal 后）或 `OnGlobalLayoutListener`。
- 子线程能更新 UI 吗？——检查发生在 `ViewRootImpl.checkThread()`，**ViewRootImpl 创建前**（onResume 前）子线程改 UI 不会崩，但绝不是推荐做法。

### 高频追问

1. **getMeasuredWidth 与 getWidth 区别？** 前者 measure 阶段产物，后者 layout 后 `right-left`；一般相等，layout 里可改。
2. **draw 如何走到 GPU？** RenderNode 树 → RenderThread 同步（syncAndDrawFrame）→ skia 光栅化 → BLASTBufferQueue。
3. **过度绘制优化？** 移除多余 background、`clipRect`、ViewStub/merge、扁平化（ConstraintLayout/Compose）。

---

## 11. ANR：5 秒到底从哪里开始算？

**面试题：ANR 的触发机制？Input ANR 和 Service/Broadcast ANR 底层有何不同？**

### 答案解析

**超时阈值（Android 14）：**

| 类型 | 阈值 | 源码 |
|---|---|---|
| Input dispatch | 5s（默认） | `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp`（DEFAULT_INPUT_DISPATCHING_TIMEOUT，可由 app 的 `android:allowUntrustedActivityRestart` 等属性调整） |
| 前台 Service | 20s（SERVICE_TIMEOUT） | `services/core/java/com/android/server/am/ActiveServices.java` |
| 后台 Service | 200s | 同上 |
| 前台广播 | 10s（BROADCAST_FG_TIMEOUT） | `am/BroadcastQueue*.java`（14 上为 BroadcastQueueModernImpl） |
| 后台广播 | 60s | 同上 |
| Provider publish | 10s | AMS `CONTENT_PROVIDER_PUBLISH_TIMEOUT` |

**两种截然不同的机制：**

1. **埋炸弹型（Service/Broadcast）**：调度组件前先 `Handler.sendMessageDelayed(SERVICE_TIMEOUT_MSG)`，组件按时完成则 remove；超时消息被执行 = ANR。
   `ActiveServices.bumpServiceExecutingLocked() → scheduleServiceTimeoutLocked()`。
2. **事后追责型（Input）**：InputDispatcher 每次派发时检查 **connection 的等待队列**：上一个事件未被 App 消费（`waitQueue` 里 entry 超过 timeout）才触发。所以 input ANR 弹窗时，**罪魁祸首往往是"上一件事"**，不是当前触摸。
   `InputDispatcher::processAnrsLocked() → onAnrLocked()` → 通知 `InputManagerService` → `ActivityManagerInternal.inputDispatchingTimedOut`。

**ANR 落地流程：**

```
AnrHelper.appNotResponding()                       // services/core/java/com/android/server/am/AnrHelper.java
 └─ ProcessErrorStateRecord.appNotResponding()     // 同目录，Android 12+ 从 AMS 拆出
     ├─ 冻结进程列表快照、dump 主进程+关键进程的 traces
     │    → /data/anr/anr_<timestamp>（SIGQUIT 触发 ART dumpThreadStack）
     ├─ event log: am_anr
     └─ 弹框或后台直接杀（bg ANR 默认静默 kill）
```

### 易错点

- ❌ "主线程耗时超 5s 就 ANR"：主线程忙但**没有待处理的 input/组件超时**就不会 ANR（比如纯后台计算）。ANR 的充要条件是"特定事件在期限内未被确认"。
- traces 里主线程 `Native` 态 + `epoll_wait` = 主线程其实空闲，此时 ANR 多半是**历史消息堆积后已恢复**或 CPU 整体饥饿（看 `CPU usage from ...` 段的 iowait/kswapd）。
- 广播 ANR 只针对**串行（ordered/前台队列）**处理；Android 14 的 BroadcastQueueModernImpl 按进程分队列，面试可提。

### 高频追问

1. **如何监控线上 ANR？** `ApplicationExitInfo`（REASON_ANR）、Matrix/自研 WatchDog（子线程 5s 心跳检查主线程消息）、`Looper.setMessageLogging` / `Printer` 统计单消息耗时。
2. **SIGQUIT 怎么被 ART 处理？** ART SignalCatcher 线程捕获 SIGQUIT dump 所有 Java 线程栈——App 可 hook 该信号实现无感 ANR 采集。
3. **为什么有时 ANR 无弹窗直接杀？** 后台 ANR（`ANR_SHOW_BACKGROUND` 未开）默认 killProcessQuiet。

---

## 12. 内存优化：LMKD、PSI 与进程回收

**面试题：Android 的低内存管理链路？App 侧内存优化如何做体系化？**

### 答案解析

**系统侧回收梯队（从温和到暴力）：**

```
① kswapd 后台回收 / 直接回收（内核 mm）
② ART GC（App 内部，CC/CMC 并发拷贝收集器，Android 14 默认 CMC userfaultfd GC）
③ onTrimMemory 回调（AMS 广播内存压力等级）
④ lmkd 杀进程：PSI(memory pressure) 事件驱动
     // system/memory/lmkd/lmkd.cpp
     // 监听 /proc/pressure/memory 的 some/full 阈值 (epoll)
     // 按 oom_score_adj 从高到低（999→0）挑选 victim，kill(SIGKILL)
⑤ 内核 OOM killer（最后防线，选 oom_score 最高者）
```

老的内核 lowmemorykiller 驱动（minfree 水位）已废弃，**PSI（Pressure Stall Information）+ 用户态 lmkd** 是现代方案；`ProcessList.updateOomLevels()` 仍会下发 minfree 参考值。

**App 侧体系化（面试要点框架）：**

1. **发现**：Java 堆（LeakCanary/自研 hprof 裁剪上报）、Native（malloc_debug、`libmemunreachable`、Android 14 可用 MTE 硬件检测）、图片（大图检测 hook `Bitmap` 构造）。
2. **大头治理**：Bitmap（RGB_565/inSampleSize/复用池、放 native 不再适用——8.0 后像素本来就在 native heap）、So/线程/WebView 独立进程。
3. **兜底**：`onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` 释放 UI 缓存；关键指标 PSS/RSS 用 `Debug.getMemoryInfo`、`dumpsys meminfo <pid>` 对账。

### 易错点

- Java 堆上限（`dalvik.vm.heapgrowthlimit`）OOM ≠ 设备没内存；Native 泄漏不触发 Java OOM 却会推高 RSS 被 lmkd 杀 —— 表现为"无 Java 崩溃日志的闪退"，用 `ApplicationExitInfo.REASON_LOW_MEMORY` 佐证。
- `largeHeap=true` 是双刃剑：GC 停顿变长、更容易被 lmkd 盯上。
- 线程也是内存：每线程默认 ~1MB 栈（vss），线程爆炸常导致 `pthread_create failed`。

### 高频追问

1. **ART GC 演进？** Android 14 默认 **CMC（Concurrent Mark-Compact，基于 userfaultfd）** 替代 CC（读屏障），压缩更彻底、吞吐更高（`art/runtime/gc/collector/mark_compact.cc`）。
2. **进程冻结与内存的关系？** CachedAppOptimizer 冻结 + `process_madvise(MADV_PAGEOUT)` 压缩 cached 进程，Android 14 上后台进程"假死"问题的根源之一。

---

## 13. Jetpack Compose：重组的底层机制（SlotTable/Snapshot）

**面试题：Compose 的重组（Recomposition）是怎么发生的？为什么改一个 State 只有部分函数重新执行？**

### 答案解析

三大支柱（注意：代码在 androidx 仓库，非 AOSP frameworks）：

**① 编译器插桩**：Compose Compiler（K2 编译器插件）给每个 `@Composable` 注入 `$composer: Composer` 与 `$changed: Int` 参数，并把函数体包进 `startRestartGroup/endRestartGroup`：

```kotlin
// 编译后伪码
fun Counter($composer: Composer, $changed: Int) {
    $composer.startRestartGroup(key)
    ... // 参数没变且状态未失效 → $composer.skipToGroupEnd() 直接跳过
    $composer.endRestartGroup()?.updateScope { Counter($composer, $changed or 1) }
}
```

**② SlotTable（Gap Buffer）**：`androidx.compose.runtime.SlotTable`。组合的结果（group 结构、remember 的值、CompositionLocal）线性存储在 gap buffer 数组里，重组时按位置比对（**positional memoization**）：位置相同 + 输入相等 → 跳过；插入/删除通过移动 gap 实现 O(1) 均摊。

**③ Snapshot 状态系统**：`mutableStateOf` 返回 `SnapshotMutableState`，其读写走 `androidx.compose.runtime.snapshots.Snapshot`（MVCC 多版本并发控制）：
- 组合期间**读**某 State → 记录到当前 `RecomposeScope` 的依赖集合；
- 之后该 State **写**入 → snapshot apply 时通知观察者 → `Recomposer` 把对应 scope 标 invalid → 在下一帧（`MonotonicFrameClock`，Android 上由 Choreographer 驱动的 `AndroidUiDispatcher`）只重组失效 scope。

所以"只重组一部分"的本质：**依赖追踪精确到 RestartGroup（通常是一个可跳过的 @Composable 函数）**。

### 易错点

- 参数为 **unstable 类型**（如 `List`、来自无 stability 推断的模块的类）会导致无法跳过——高频卡顿根因；用 `@Stable/@Immutable`、kotlinx immutable collections 或 Strong Skipping（新版本默认开启）解决。
- Lambda 每次重组新建实例导致子组件重组？—— Compose 会对 lambda 做 remember（composable lambda 记忆化），但**捕获了 unstable 值的 lambda 除外**。
- 重组是**乐观的可放弃的**：动画每帧驱动的重组可被取消，业务代码不能在组合期做副作用（必须用 `LaunchedEffect/SideEffect`）。

### 高频追问

1. **三个阶段？** Composition（构建/更新 LayoutNode 树）→ Layout（measure/place，支持 intrinsic 与 lookahead）→ Draw（RenderNode，最终仍走 hwui/RenderThread 管线——与 View 殊途同归）。
2. **Compose 与 View 混排的桥？** `AndroidComposeView`（本身是个 View，承接 input/焦点/无障碍）。
3. **derivedStateOf 和 remember(key) 区别？** 前者依赖变化且**计算结果变化**才失效下游，适合高频源→低频结果。

---

## 14. HAL：Treble、HIDL→AIDL、FMQ

**面试题：Treble 架构解决什么问题？HIDL 为什么被 AIDL HAL 取代？Framework 到驱动的完整调用链？**

### 答案解析

**Treble（Android 8.0+）**：把 vendor 实现与 system 框架解耦，system/vendor 分区独立升级。核心机制：
- **Binderized HAL**：HAL 跑独立进程，经 `/dev/hwbinder` 通信；接口由 HIDL（`.hal`）定义。
- **VINTF**：`/vendor/etc/vintf/manifest.xml`（设备提供什么 HAL）与框架的 compatibility matrix 开机校验；配错 = 服务起不来/开机异常，是定制 ROM 高频故障点。
- **VNDK**：限定 vendor 可链接的系统库快照。

**HIDL → AIDL HAL（Android 11 起新 HAL 强制 AIDL，14 上主流 HAL 已迁移）**：
- 一套 IDL 统一 app/framework/HAL 三层，工具链只维护一份；
- AIDL 支持**版本冻结**（`aidl_api/` freeze），比 HIDL 的 major.minor 更灵活；
- AIDL HAL 走 `/dev/binder` 亲和的 stability 标记（`--stability=vintf`），`libbinder_ndk` 提供 C++ NDK backend（`android/binder_ibinder.h`）。

**完整调用链示例（以 Vibrator 为例）：**

```
App: Vibrator.vibrate()
 └─ VibratorManagerService (system_server)          // services/core/java/com/android/server/vibrator/
     └─ IVibrator (AIDL HAL) Binder 调用             // hardware/interfaces/vibrator/aidl/
         └─ android.hardware.vibrator-service.<vendor> 进程（vendor 分区）
             └─ open("/dev/xxx") / sysfs 写入 → 内核驱动（drivers/...）→ 硬件
```

**FMQ（Fast Message Queue）**：`system/libfmq`。基于共享内存的无锁环形队列 + futex/EventFlag 通知，解决 Binderized 后高频小数据（传感器批量数据、音频）IPC 开销：延迟从 ~8μs 降到百 ns 级。Binder 只用来传一次 MQDescriptor，之后数据不过驱动。

### 易错点

- Passthrough（同进程 dlopen）在图形等极端性能路径仍存在；"所有 HAL 都独立进程"是错的。
- `hwservicemanager`（HIDL 用）与 `servicemanager`（framework/AIDL HAL 用）是两个服务；Android 14 上 AIDL HAL 直接注册到 servicemanager（带 vintf stability）。
- 调试工具：`lshal`（HIDL）、`dumpsys -l` + `service list`（AIDL）、`adb shell cmd`。

### 高频追问

1. **如何新增一个自定义 AIDL HAL？** hardware/interfaces 定义 `.aidl` + `aidl_interface` bp 模块 → freeze API → vendor 实现 service + `.rc` + vintf manifest 片段 + SELinux 策略（`vendor_service.te` 等）。
2. **GKI 与 vendor 驱动的关系？**（见第 15 题）

---

## 15. Linux Kernel/驱动：Framework 工程师需要懂多少内核？

**面试题：GKI 是什么？Android 专属内核机制有哪些？binder 之外还常问什么？**

### 答案解析

**GKI（Generic Kernel Image，Android 12+ 强制）**：Google 统一维护核心内核（如 android14-6.1），SoC/板级代码全部下沉为 **vendor module（.ko）**，通过稳定的 **KMI（Kernel Module Interface）** 符号表约束。解决内核碎片化，安全补丁直达。

**Android 专属内核组件（androidboot 常问清单）：**

| 组件 | 位置 | 作用 |
|---|---|---|
| binder | `drivers/android/binder.c`, `binder_alloc.c` | IPC（见 3/4 题） |
| ashmem→memfd | 旧 `drivers/staging/android/ashmem.c`，现推 memfd | 匿名共享内存 |
| ION→dmabuf heaps | `drivers/dma-buf/heaps/` | 图形/多媒体大块内存 |
| lowmemorykiller | 已删除，被 PSI+lmkd 取代 | 低内存 |
| wakelock→wakeup source | `kernel/power/wakeup.c` | 阻止休眠（PowerManager.WakeLock 底层） |
| PSI | `kernel/sched/psi.c` | 内存/CPU/IO 压力上报（lmkd 依赖） |
| cgroup v2 | freezer/cpuset/blkio | 进程冻结、大小核调度（top-app/foreground/background cpuset） |
| SELinux | `security/selinux/` | 强制访问控制，`sepolicy` 二进制在 system/vendor 拼合 |
| epoll/eventfd | `fs/eventpoll.c` | Looper、lmkd、InputDispatcher 的事件底座 |

**Framework 工程师的内核考点通常落在"交界处"：**
- 主线程调度：`sched_setscheduler`/EAS 能耗感知调度、top-app cpuset、`setThreadPriority`（`libcutils/sched_policy.cpp`）；
- 触摸链路的内核段：`/dev/input/eventX`（evdev）→ EventHub（`frameworks/native/services/inputflinger/reader/EventHub.cpp`，epoll + inotify）；
- 存储：F2FS、fsync 卡顿（IO 优先级 / bfq/blkcg）；
- perfetto/ftrace：`atrace` 类别背后是 ftrace tracepoint。

### 易错点

- "Android 内核 = 标准 Linux"不对，但差异在收敛：ashmem/ION/lmk 等 staging 特性已被上游标准机制（memfd/dmabuf heap/PSI）替换 —— 面试说旧机制要带上演进。
- 驱动开发面试（BSP 向）常问 platform driver 模型、设备树（DTS）、probe 流程、中断上下半部（threaded irq）、`copy_from_user` 为什么不能在原子上下文睡眠等 —— Framework 候选人至少要能讲清 **binder 和 input 两条链路的内核段**。

---

## 16. MTK 平台专题：DuraSpeed、AEE 与平台差异排查

**面试题：在 MTK 平台做系统开发，和原生 AOSP 有哪些差异？常见问题如何排查？**

### 答案解析

**MTK 代码结构（alps 工程）：**
- `vendor/mediatek/proprietary/`：MTK 私有 framework 扩展（operator 定制、外挂服务）、HAL 实现；
- `device/mediatek/`、`kernel-6.1/`（对应 GKI + mtk vendor modules）；
- framework 修改常见于 `vendor/mediatek/proprietary/frameworks/`，通过 patch/plugin（MPlugin）机制注入。

**高频实战点：**

1. **DuraSpeed（后台查杀）**：MTK 自带的激进后台管控（`vendor/mediatek/proprietary/packages/apps/DuraSpeed`），会绕过标准 oom_adj 逻辑直接杀后台。典型现象：**退游戏回桌面黑屏 2 秒 = Launcher 被杀后冷启**。排查：
   ```
   adb logcat -b events | grep am_proc_start   # Launcher 是否刚被重启
   adb shell dumpsys activity processes         # 看 Launcher PID 存活时间
   ```
   定制 ROM 常需给自家 app 加白名单（DuraSpeed 白名单 xml / settings provider）。
2. **AEE（Android Exception Engine）**：MTK 的异常捕获体系，NE（native exception）/JE（java exception）/KE（kernel exception）/EE 分类，落盘 `/data/aee_exp/db.xx.dbg`，用 MTK 工具解包分析；`aee_aed` 守护进程。KE 分析对应 `SYS_LAST_KMSG`/ramdump。
3. **日志体系**：mtklog（mobile log/modem log/network log）、`adb shell aee -h`；性能问题配合 MET/systrace。
4. **性能调优**：MTK PowerHAL/perfservice 的 boost 策略（scn 场景表）、thermal 策略（`/proc/mtk_thermal`）影响跑分与卡顿，改机常涉及 `powerscntbl.xml`。
5. **显示/HWC 差异**：MTK DispSvc/HWC 的模式切换日志（回桌面刷新率切换黑屏问题看 `HWC`、`ddp` 关键字）。

### 易错点

- 在 MTK 平台调 framework 问题，先分清是 **AOSP 原生逻辑** 还是 **MTK patch 改动**（搜 `MTK` 注释块/`vendor/mediatek` 下同名类），否则对着原生源码猜半天。
- 保活/杀进程问题在 MTK/各 OEM 上不可移植：DuraSpeed、OEM 电池优化各自为政，答题时要体现"平台差异意识"。

### 高频追问

1. **展锐/高通对应物？** 高通：perf-hal + LMK 调参 + `android.hardware.power-service.qti`；面试官常借此考"你是否只会一家平台"。
2. **如何给 MTK 平台新加一个系统服务？** 标准 AOSP 流程（SystemServer 注册 + Context.getSystemService + SELinux 策略 + API 白名单）+ MTK 签名/编译配置（`ProjectConfig.mk` 时代 vs 现 bp）。

---

## 17. 查缺补漏清单 & 延伸阅读

### 今日知识体系补足（面向已有 AOSP 基础者）

- [ ] **Binder 冻结交互（Android 12+）**：`binder_ioctl_freeze` 与 CachedAppOptimizer 联动；App 收到 `DeadObjectException` 的新场景。
- [ ] **Rust in AOSP**：binder Rust 驱动、`libbinder_rs`、keystore2/DNS resolver 已是 Rust——系统岗新谈资。
- [ ] **BroadcastQueueModernImpl（Android 14）**：按进程独立广播队列 + defer 机制，广播 ANR 语义变化。
- [ ] **ART CMC GC（userfaultfd）**：替代 CC 读屏障，理解"为什么 14 上 GC 暂停更短"。
- [ ] **BLAST/SurfaceControl.Transaction**：BufferQueue 时代答案已过时，帧提交模型要更新。
- [ ] **USAP 池 & App Zygote**：进程创建加速的两个冷门考点。
- [ ] **Strong Skipping（Compose）**：稳定性判断规则的重大变化。
- [ ] **16KB page size（Android 15 预热）**：so 对齐（`-Wl,-z,max-page-size=16384`），系统岗前瞻题。

### 延伸阅读（源码路径速查）

```
消息机制   frameworks/base/core/java/android/os/{Looper,Handler,MessageQueue}.java
           system/core/libutils/Looper.cpp
Binder     drivers/android/{binder.c,binder_alloc.c}
           frameworks/native/libs/binder/{ProcessState,IPCThreadState,BpBinder,Binder}.cpp
           frameworks/native/cmds/servicemanager/
启动       frameworks/base/core/java/com/android/internal/os/{ZygoteInit,Zygote,ZygoteServer}.java
           frameworks/base/core/java/android/app/{ActivityThread}.java + servertransaction/
AMS/ATMS   frameworks/base/services/core/java/com/android/server/am/{ActivityManagerService,OomAdjuster,ProcessList,ActiveServices,AnrHelper}.java
           frameworks/base/services/core/java/com/android/server/wm/{ActivityTaskManagerService,ActivityStarter,ActivityTaskSupervisor,RootWindowContainer}.java
窗口/显示  frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java
           frameworks/native/services/surfaceflinger/
           frameworks/base/core/java/android/view/{ViewRootImpl,Choreographer,View}.java
Input      frameworks/native/services/inputflinger/{reader/EventHub.cpp,dispatcher/InputDispatcher.cpp}
内存       system/memory/lmkd/lmkd.cpp    art/runtime/gc/collector/mark_compact.cc
HAL        hardware/interfaces/    system/libfmq/    /vendor/etc/vintf/manifest.xml
```

---

*生成于 2026-07-23，每日自动更新。建议配合 cs.android.com 按路径对照阅读源码。*
