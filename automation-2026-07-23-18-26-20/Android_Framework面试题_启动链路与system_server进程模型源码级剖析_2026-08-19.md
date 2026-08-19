# Android Framework 面试题 · 启动链路与 system_server 进程模型源码级剖析（第 34 篇）

> 日期：2026-08-19 ｜ 系列第 34 篇 ｜ 累计约 219 专题
> 主线 baseline：Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX，内核 GKI android14-6.1)
> 热点锚定：Android 17 stable 已于 2026-06-16 发布（代号 CinnamonBun）；**A17 QPR2 Beta 2 于 2026-08-03 推送**（build CP41.260701.006 / 开发者站 CP41.260717.006，代号 DEV，仅图标微调 + 稳定性修复 + Pixel 6/6 Pro EOL 退出，无行为变更，stable 预计 2026-12）；A18 桌面融合 / 跨设备 Handoff / EU DMA 开放 11 项 AI 能力仍处路线图。**结论：启动链路 + 进程模型是"App 启动流程 / AMS / Binder"三大高频考点的底座，本篇补齐 8/6 code walk 只讲 app 侧（startActivity→首帧）而完全没碰的 boot 侧（init→Zygote→system_server→servicemanager→fork 协议）。**

---

## 0. 为什么今天讲"启动链路 + 进程模型"

前 33 篇把主线 + 盲区 + 深水区 + 智能层 + 安全世界(TEE/pKVM) + 座舱 + 端侧 AI + 源码 code walk + Perfetto SQL 全做完了。但复盘发现一个**结构性缺口**：

```
8/6 源码级 code walk 讲的是什么？          本篇要补的 boot 侧是什么？
--------------------------------------     --------------------------------------
ActivityStarter.startActivity(应用侧)      Linux 内核启动 -> init -> init.rc
-> Zygote socket 一笔带过(fork 之后)       -> Zygote 进程孵化(precache + socket)
-> ActivityThread.handleBindApplication    -> system_server fork + 分阶段服务注册
-> performLaunchActivity / onResume        -> servicemanager 成为 binder context
-> ViewRootImpl.setView -> 首帧上屏         -> App 进程 fork 协议(forkAndSpecialize)
                                           -> 进程优先级 / oom_adj / 三条杀路径
```

一句话：**8/6 是从"fork 之后"讲起，本篇是从"fork 之前 + fork 本身 + 谁在 fork"讲起**。两者拼起来才是完整的"App 启动流程"，也是面试官最爱连问的链路（"那 Zygote 自己是谁启动的？""system_server 挂了会怎样？""为什么 fork 比冷起进程快？"）。

> 约定：文中 `.java/.cpp` 路径默认是 **Android 14 AOSP (android-14.0.0_rXX)**；内核路径为 **GKI common-android14-6.1**；MTK/厂商为 vendor 实现，路径随平台变化，下列为常见形态，引用时以真机为准。涉及 A17 新增项显式标注 `[A17]`。

---

## 专题一：从 Linux 内核到 Zygote —— 谁孵化了第一个 Java 进程

**现象 / 考官提问**
> 1) 开机后第一个用户态进程是谁？Zygote 是谁启动的？2) 为什么 Android 不用 `fork()` 直接起 App，而要一个常驻的 Zygote？3) `init.rc` 和 `init.zygote64.rc` 是什么关系？4) 64 位机上为什么还有 `zygote32`？

**底层原理 + 源码落点**

整条 boot 链（自上而下）：
```
Linux 内核 start_kernel
  -> init (PID 1, system/core/init/)
      -> 解析 init.rc / init.${ro.zygote}.rc
          -> service zygote /system/bin/app_process64 -Xzygote /system/bin --zygote --start-system-server
              -> app_process64 的 main() -> AppRuntime.start()
                  -> AndroidRuntime.startVm() + startReg()
                  -> 反射调用 ZygoteInit.main()
                      -> Zygote 进入 runSelectLoop，等待 fork 请求
```

1. **init 是第一个用户态进程（PID 1）**
   `system/core/init/` 在 kernel `start_kernel` 之后挂载根文件系统、`selinux_setup`、`second_stage` 后解析 rc 文件。`init.rc` 是主配置，`init.${ro.zygote}.rc` 按属性拼接（常见值 `zygote64`、`zygote64_32`、`zygote32`）。Zygote 作为 `service` 声明，由 init 用 `fork()` + `execve()` 拉起 `app_process64`。

2. **`app_process64` → `AppRuntime` → `ZygoteInit`（`frameworks/base/cmds/app_process/app_main.cpp`）**
   `main()` 里建 `AppRuntime`，调 `AndroidRuntime::start("com.android.internal.os.ZygoteInit", ...)`：先 `startVm()` 创建 ART 虚拟机，再 `startReg()` 注册 JNI，最后用 `env->CallStaticVoidMethod` 反射进 `ZygoteInit.main()`。**从这一刻起 Zygote 才是一个 Java 进程**，但它已经持有一份预热好的 ART + 已 preload 的 framework 类，这就是后面 fork 快的秘密。

3. **为什么需要常驻 Zygote（写时复制 COW 的红利）**
   每个 App 都是 ART 虚拟机 + 一大堆 framework 类（`android.*`/`java.*`/`kotlin.*`）。如果每次冷起都重新 `fork()`+`exec()`+`startVm()`+加载上万类，启动会慢到不可用。Zygote 做法是：**先一次性把 ART 和常用类加载进内存，之后所有 App 都用 `fork()` 从 Zygote 复制**——fork 是**写时复制（Copy-On-Write）**，子进程共享父进程的只读页（代码、已加载的 class、heap 模板），只有真正写时才复制物理页。于是 App 启动 ≈ "复制一个已就绪的进程模板"，省掉 VM 初始化和类加载的数百毫秒。

4. **`zygote32` 为什么还在**
   `ro.zygote=zygote64_32` 时 init 同时拉 `zygote64` 和 `zygote32`，因为仍有 32 位原生库/老 App 需要 32 位运行时（如部分厂商 HAL 的 32 位 `.so`、遗留游戏）。`Zygote#forkSystemServer` 与 `forkAndSpecialize` 都按目标 ABI 选对应 Zygote。注意 64 位机跑 32 位 App 会额外占用内存（32/64 各一套 Zygote 常驻），所以 A17 起进一步收紧 32 位支持。

**易错点（红榜）**
- "init 是内核的一部分"。错：init 是第一个**用户态**进程（PID 1），内核是 `start_kernel` 之后才把它 exec 出来。
- "Zygote 一启动就是 Java"。错：app_process 先用 C++ 起 ART（startVm），再反射进 ZygoteInit.main() 才变成 Java 进程。
- "fork 会完整拷贝父进程内存"。错：COW 只复制被写的页，只读页（代码/类）共享，这是 Zygote 模型的核心性能来源。

**高频追问链**
1. Zygote 为什么不用 `exec()` 直接起 App？-> 见上，COW 共享 preload 的类和 ART，避免每个 App 重做 VM 初始化。
2. fork 之后 Zygote 怎么避免"共享状态串味"？-> 子进程在 `handleChildProc` 里重新初始化（`ZygoteInit.childZygote` 清理、关掉 Zygote socket、重置信号、SELinux 域切换），且 COW 保证父进程写不影响子。
3. `[A17]` 16KB 页面对 Zygote 有什么影响？-> 见第 14/29 篇：preload 的 `.art`/`.oat` 与 linker `max-page-size=16384` 必须对齐，否则 `mmap`/加载失败导致启动崩溃。

**延伸阅读**：第 6 篇（code walk·Zygote fork 之后）、第 14 篇（16KB 页）、第 29 篇（Native 稳定性·linker/页对齐）。

---

## 专题二：Zygote 初始化与 preload —— "进程模板"是怎么备好的

**现象 / 考官提问**
> 1) Zygote 启动后第一时间干了什么？2) `preloadClasses()` 加载的是哪些类、从哪来？3) Zygote 的 socket 是干嘛的、谁连它？4) `runSelectLoop` 在等什么？

**底层原理 + 源码落点（`frameworks/base/core/java/com/android/internal/os/ZygoteInit.java`）**

`ZygoteInit.main()` 核心顺序：
```
main()
 -> preload(new PreloadOptions())   // 预热：类、资源、共享库、字体、图形驱动
 -> gcAndFinalize()                 // fork 前先 GC，减少 COW 脏页
 -> registerZygoteSocket()          // 建 /dev/socket/zygote 的 LocalServerSocket
 -> startSystemServer()             // fork 出 system_server（专题三）
 -> runSelectLoop()                 // 死循环 accept，等待 AMS 发来的 fork 请求
```

1. **`preloadClasses()`（`/system/etc/preloaded-classes`）**
   读 `/system/etc/preloaded-classes`（编译期由 `frameworks/base/tools/preload` 根据使用热度生成），用 `Class.forName()` 把约 3000~4000 个 framework 核心类（如 `android.app.Activity`、`android.view.View`、`java.lang.*`）提前加载进 Zygote 的堆。这些类此后被所有 App 通过 COW 共享——**这是"App 启动为什么快"的第二条支柱**（第一条是 ART 已就绪）。

2. **`preloadResources()` / `preloadSharedLibraries()` / `preloadGraphicsDriver()`**
   预加载 `resources.arsc` 里的 drawable/color、共享 JNI 库（`libandroid_runtime.so` 等）、以及图形驱动（HWUI/Skia/GPU 上下文模板）。图形驱动 preload 让 App 不用各自初始化 EGL/GL 上下文。

3. **`registerZygoteSocket()`**
   在 `/dev/socket/zygote`（由 init 在 `init.rc` 里 `socket zygote ...` 创建）上建 `LocalServerSocket`。**所有 App 进程的 fork 请求都经这个 Unix Domain Socket 发来**，而不是 Binder——因为 fork 时期 Binder 线程还没就绪、且需要传递 FD/参数，`LocalSocket` 更适合"一次性传递复杂参数 + FD"。

4. **`runSelectLoop()`（`ZygoteServer.runSelectLoop`）**
   死循环 `poll()` 监听 Zygote socket + USAP（Unspecialized App Process）池。收到 `ZygoteConnection` 的 `fork` 命令后，在 `ZygoteConnection.processCommand` 里调 `Zygote.forkAndSpecialize` 真正 fork。Android 10+ 引入 **USAP 池**：预先 fork 一批"未特化"的空进程 standby，收到请求直接 `specialize` 而不现 fork，进一步压低冷启延迟（见专题七）。

**易错点（红榜）**
- "App 的类是 App 自己加载的"。错：大量 framework 类在 Zygote 阶段就 preload 并 COW 共享，App 只加载自己的业务类 + `dex` 里的类。
- "socket 连接 Zygote 的是 Binder"。错：Zygote 用 `LocalSocket`（Unix Domain Socket），不是 Binder——fork 期 Binder 未就绪且需传 FD。
- "runSelectLoop 是主线程死循环会 ANR"。错：Zygote 不是应用进程，没有 AMS 的输入/生命周期超时看门狗在监视它。

**高频追问链**
1. preload 的类越多越好吗？-> 不是，preload 多 = Zygote 常驻内存大 + 每个 App COW 后私有化成本；`preloaded-classes` 是"热度阈值"裁剪出来的。
2. USAP 池是什么？-> Unspecialized App Process，预 fork 的半成品进程，专用于压低冷启 fork 延迟；`usap_pool_enabled` 控制。
3. Zygote 自己会被 LMK 杀吗？-> 不会，Zygote 是 `system` 关键进程，lmkd 永不杀；它死了所有 App 都无法 fork（系统需重启）。

**延伸阅读**：第 6 篇（fork 之后）、第 9 篇（ART 镜像/OAT）、第 19 篇（冷启动全链路）。

---

## 专题三：system_server 是怎么来的 —— fork 出来的"系统大脑"

**现象 / 考官提问**
> 1) system_server 是哪个进程 fork 的？它在 Zygote 之前还是之后？2) `forkSystemServer` 和 App 的 fork 有什么区别？3) SystemServer.main 之后做了什么、为什么分阶段启动服务？

**底层原理 + 源码落点**

1. **`ZygoteInit.startSystemServer()`（`frameworks/base/core/java/com/android/internal/os/ZygoteInit.java`）**
   在 `runSelectLoop` **之前**，`ZygoteInit.main` 先调 `startSystemServer()`，用 `Zygote.forkSystemServer()` fork 出 PID 固定的 system_server（UID 1000、`system`、带 `CAP_*`、SELinux `system_server` 域）。注意：**system_server 是 Zygote 的第一个孩子，且是唯一一个从 `startSystemServer` 路径 fork 的"特殊进程"**，其余 App 都走 `runSelectLoop` 的按需 fork。

2. **fork 后的孩子：`handleSystemServerProcess`（`ZygoteInit.java`）**
   子进程里：关掉 Zygote socket（system_server 不再接受 fork 请求）、`ZygoteInit.setApiBlacklistExemptions`、重新初始化 `Process.setArgV0("system_server")`，最后 `RuntimeInit.applicationInit()` -> `invokeStaticMain("com.android.server.SystemServer", ...)`。

3. **`SystemServer.main()` → `run()`（`frameworks/base/services/java/com/android/server/SystemServer.java`）**
   关键的**分阶段启动**（`run()` 内）：
   ```
   run():
     -> createSystemContext()        // 先建 ActivityThread + Context，PMS 等需要
     -> startBootstrapServices()     // 引导服务：ATMS、AMS、PMS、PowerManagerService、RecoverySystem
     -> startCoreServices()          // 核心服务：BatteryService、UsageStatsService、WebViewUpdateService
     -> startOtherServices()         // 其他服务：WMS、NotificationManagerService、LocationManagerService、...
     -> 各服务 publishBinderService() 注册到 ServiceManager（专题五）
   ```
   - 为什么分阶段？因为服务之间有强依赖（如 WMS 依赖 AMS、AMS 依赖 PMS），必须按拓扑序启动；且**分批启动避免 system_server 在启动期就触发看门狗超时**（见专题六）。`startOtherServices` 末尾才发 `systemReady()`，通知各服务"可以接客"。
   - AMS 在 `startBootstrapServices` 里 `mActivityManagerService = ActivityManagerService.Lifecycle.startService()`；PMS 在 `SystemServer` 里 `PackageManagerService.main()` 后 `mPackageManagerService` 注入 AMS。

4. **`system_server` 与 Zygote 的内存关系**
   system_server 也是 COW 自 Zygote，所以**它也共享 Zygote preload 的类**（AMS/WMS 用的 framework 类早就在 Zygote 里了）。但它运行久了、私有化页多，会逐渐偏离 Zygote 模板——这也是为什么 system_server 内存常驻高、需要 `dumpheap` 分析。

**易错点（红榜）**
- "system_server 是 init 直接拉的"。错：它和 App 一样是 Zygote fork 出来的，只是走 `startSystemServer` 特殊路径、且是第一个孩子。
- "AMS/WMS 在 Zygote 里就启动了"。错：Zygote 只 preload 类，服务实例在 system_server 的 `run()` 分阶段创建。
- "fork 出来就立刻能服务"。错：要等 `startOtherServices` + `systemReady()` 之后服务才注册完成、可对外。

**高频追问链**
1. system_server 挂了会怎样？-> 它死会触发 `Watchdog`（专题六）杀掉自己并重启（Zygote 会重新 fork 一个新的 system_server），期间系统服务不可用、手机会"黑屏/重启 framework"。
2. 为什么 AMS 要在引导阶段、WMS 在其它阶段？-> 依赖顺序：WMS 需要 AMS 先存在（窗口操作要查 Activity 栈），所以 AMS 引导、WMS 其它。
3. system_server 和普通 App 进程在 fork 上差在哪？-> `forkSystemServer` 指定 UID 1000/系统权限/固定参数；`forkAndSpecialize` 按 App UID/包名/SELinux 域特化（专题七）。

**延伸阅读**：第 6 篇（ActivityThread/应用侧）、第 17 篇（AMS 进程管理 oom_adj）、本篇专题六/七。

---

## 专题四：ServiceManager 与 Binder 上下文 —— "电话簿"什么时候上岗

**现象 / 考官提问**
> 1) App 调 `getSystemService(AM)` 拿到的 Binder 是怎么找到对端的？2) ServiceManager 是谁启动的、它自己又是怎么被找到的？3) `/dev/binder`、`/dev/hwbinder`、`/dev/vndbinder` 三者区别？

**底层原理 + 源码落点**

1. **servicemanager 是最早的 binder 服务（`frameworks/native/cmds/servicemanager/servicemanager.c`）**
   init 在 Zygote **之前**就拉起 `servicemanager`（它是 `critical` 服务，挂了系统重启）。它 `binder_open()` 打开 `/dev/binder`，然后 `bs->svcmgr = BINDER_SERVICE_MANAGER`（handle 0）——**handle 0 是"上下文管理者"的约定值，不需要查表就知道**。之后 `binder_become_context_manager()` 向驱动声明"我是 0 号"，进入 `binder_loop()` 等待 `SVC_MGR_ADD_SERVICE` / `SVC_MGR_GET_SERVICE` 请求。

2. **服务注册与查询（C/S 的"电话簿"）**
   system_server 的各服务在 `publishBinderService()` 里调 `ServiceManager.addService(name, binder)` -> 经 `/dev/binder` 发到 servicemanager 的 handle 0 记录 `{name -> handle}`。`Context.getSystemService()` / `ServiceManager.getService(name)` 反向查 handle。所以 **ServiceManager 是 Binder 名字解析中枢，但它本身用固定的 handle 0 自举**，不依赖任何"更上级"的目录服务。

3. **三个 binder 设备（Treble 之后）**
   - `/dev/binder`：**framework ↔ framework / app ↔ system_server** 的普通 Binder（Java/AIDL 默认走它）。
   - `/dev/hwbinder`：**framework ↔ HAL**（HIDL 时代引入，HIDL 服务注册在 hwservicemanager，handle 0 是 `hwservicemanager`）。
   - `/dev/vndbinder`：**vendor ↔ vendor**（AIDL for HAL 时代，vndservicemanager 管理，让 vendor 进程也能用 AIDL 且不污染 framework 命名空间）。
   - 三者驱动同源（`drivers/android/binder.c`），只是 `binder_context`（设备上下文）不同，各自一套 handle 空间与 servicemanager 实例。详见第 17/32 篇（HAL/Treble）。

4. **binder 驱动层打开（`drivers/android/binder.c`）**
   `binder_open()` 分配 `binder_proc`、`binder_mmap()` 映射一块内核共享缓冲区（`binder_buffer`，默认 1MB-8KB 上限，见第 19/21 篇）；`binder_ioctl(BINDER_WRITE_READ)` 是传输主入口。ServiceManager 的 `binder_become_context_manager` 最终置 `binder_context->binder_context_mgr_node`。

**易错点（红榜）**
- "ServiceManager 也要被查才能找到"。错：它是 handle 0 自举，约定值，驱动硬编码认 0 号。
- "binder / hwbinder / vndbinder 是三个驱动"。错：同一份 `binder.c`，三个 `binder_context` 设备节点，handle 空间隔离。
- "App 直接持有 system_server 的 socket"。错：App 拿的是 Binder handle（经 ServiceManager 查），走 `/dev/binder` ioctl，不是 socket。

**高频追问链**
1. 如果 servicemanager 挂了会怎样？-> init 把它标 `critical`，死了直接重启系统（因为它一死所有跨进程服务发现都崩）。
2. handle 0 被占用了怎么办？-> 不会，handle 0 专留给 context manager，驱动在 `binder_new_node` 时把 0 预留。
3. 跨 VM 的 RPC Binder（第 12 篇）也走 servicemanager 吗？-> 不走，它经 vsock + `RpcSession`，handle 是跨 VM 映射值，`getCallingUid` 不可信（呼应第 12/13 篇）。

**延伸阅读**：第 2/17/32 篇（Binder/HAL/Treble）、第 12/13 篇（跨 VM Binder 安全边界）、第 19/21 篇（binder 缓冲与 oneway 排队）。

---

## 专题五：Watchdog —— system_server 也会"ANR"且会自杀重启

**现象 / 考官提问**
> 1) App 的 ANR 是 system_server 发现的；那 system_server 自己卡死了谁发现？2) Watchdog 监测什么、怎么判定、后果是什么？3) `addMonitor` 和"主线程超时"是两回事吗？

**底层原理 + 源码落点（`frameworks/base/services/core/java/com/android/server/Watchdog.java`）**

1. **Watchdog 是 system_server 内部的看门狗线程**
   在 `SystemServer.run()` 里 `Watchdog.getInstance().init()` + `start()`。它是一个独立线程，周期性（默认 **30s** 一轮）检查：
   - **system_server 主线程（Looper）是否卡死**：向主线程 `Handler` 发一个 `monitor` 消息，若 30s 内没被处理（主线程在干别的重活/死锁），判定超时。
   - **注册的 Monitor 锁是否拿不到**：各关键服务（AMS、WMS、PMS…）实现 `Watchdog.Monitor` 并在 `onBinderThread` 里 `addMonitor(this)`；Watchdog 在 watchdog 线程调 `monitor()`，里面通常 `synchronized (this) {}` 尝试拿该服务的锁——若某服务主锁被长时间持有（死锁），`monitor()` 拿不到就超时。

2. **判定后的"自杀 + 重启"**
   超时会：`Watchdog#run` -> `evaluateCheckerCompletionLocked` 判定 -> `getBlockedCheckers` -> 最终 `Process.killProcess(Process.myPid())` + `System.exit(10)`。**system_server 自杀后，init 会重新 fork 一个新的 system_server**（Zygote 仍在），手机表现为"system_server 重启 / 短暂黑屏 / 所有 App 被杀"。同时 Watchdog 会写 `/data/anr/traces.txt` + `dropbox` 一份完整栈，供事后分析。

3. **与 App ANR 的本质区别**
   - App ANR：system_server 的 `AppErrors`/`AnrHelper` 监测某 App 主线程对输入/生命周期超时未响应，**弹对话框、进程可存活**。
   - system_server Watchdog：监测**系统自身**卡死，**直接杀自己重启**，不弹框（用户看到的是 framework 重启）。
   - 二者监测对象、后果、栈落点都不同；但底层都依赖"向目标线程丢一个消息/尝试拿锁，超时即判死"的思想。

4. **`monitor` 锁设计的意义**
   很多死锁发生在"持有 AMS 大锁的同时又等别的锁"。Watchdog 的 `monitor()` 直接 `synchronized(mLock)` 试探——如果连"单纯拿一下自己的主锁"都超 30s，说明主锁被某条路径长期霸占，必是死锁/极长临界区，必须重启保全系统。

**易错点（红榜）**
- "Watchdog 监测 App"。错：它只监测 system_server 自身（主线程 + 各服务 monitor 锁）。
- "Watchdog 超时也会弹 ANR 框"。错：它直接杀 system_server 重启，不弹框；App ANR 框是另一套。
- "monitor 是监测 binder 调用耗时"。错：monitor 是**尝试拿服务主锁**的存活探针，与 binder 调用耗时无关。

**高频追问链**
1. Watchdog 默认 30s，App 输入 ANR 才 5s，为什么 system_server 容忍更久？-> system_server 启动/gc/大锁操作本就重，30s 是平衡"误杀"与"真死"的阈值；且它一死代价巨大，阈值更保守。
2. 如何排查 Watchdog 重启？-> 抓 `/data/anr/traces.txt` + dropbox 的 `SYSTEM_SERVER_WATCHDOG`，看哪个 Monitor 超时 + 主线程栈卡在哪把锁。
3. 厂商能否改阈值？-> 可，但改小易误杀、改大延长真死卡顿，需谨慎（MTK/Qualcomm 常有定制，见第 12/32 篇 MTK）。

**延伸阅读**：第 6 篇（App ANR 触发）、第 12 篇（MTK 平台差异）、第 19 篇（ANR 全链路回溯）。

---

## 专题六：App 进程 fork 协议 —— forkAndSpecialize 到底特化了什么

**现象 / 考官提问**
> 1) AMS 决定启动一个 App 后，从"发指令"到"App 进程起来"经历了什么？2) `forkAndSpecialize` 和 `forkSystemServer` 差在哪？3) fork 之后 App 进程第一句 Java 代码是什么？4) SELinux 域、UID、gids 是什么时候定的？

**底层原理 + 源码落点**

1. **AMS 发 fork 请求（`frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` + `ProcessList.java`）**
   `ProcessList.startProcessLocked` -> 通过 `ZygoteProcess` 打开 `ZygoteState` 的 socket 连接（连 `/dev/socket/zygote`），用 `zygoteSendArgsAndGetResult` 把 `ZygoteArguments`（`--runtime-args --uid=10100 --gid=10100 --setgroups=... --target-sdk-version=34 --nice-name=com.xxx --package-name=...`）写成一行发过去。

2. **Zygote 侧 `forkAndSpecialize`（`frameworks/base/core/java/com/android/internal/os/Zygote.java`）**
   `ZygoteConnection.processCommand` 解析参数后调 `Zygote.forkAndSpecialize(pid, uid, gids, ...)`：
   - native 侧 `com_android_internal_os_Zygote.cpp :: nativeForkAndSpecialize` 真正 `fork()`；
   - fork 之后子进程里做**特化**：`setuid(uid)`/`setgid(gids)`（降权到 App UID）、`setgroups`、设置 `capabilities`、`selinux_android_setcontext`（切到 `untrusted_app` 域）、`setpriority`、关掉 Zygote 的 socket FD、重置信号 handler、建立 ART 的 `procstate`。
   - **这就是 fork 与 forkSystemServer 的最大区别**：system_server 升到 UID 1000 + `system_server` 域；App 降到真实 App UID + `untrusted_app` 域 + 按包名设 seinfo。

3. **子进程第一句 Java：`handleChildProc` → `ZygoteInit.childZygote` → `RuntimeInit.applicationInit` → 反射 `ActivityThread.main`**
   `ActivityThread.main()` 建主 Looper（`Looper.prepareMainLooper()`）、建 `ActivityThread` 实例、调 `attach(false)` 经 Binder 向 AMS `attachApplicationLocked` 报道（把自己的 `ApplicationThread` Binder 交给 AMS），然后 `Looper.loop()` 进入主线程消息循环。**从这一刻起，App 主线程才真正开始收消息**（之后 AMS 才会发 `bindApplication` / `scheduleTransaction` 启动 Activity，见第 6 篇）。

4. **USAP 特化路径（Android 10+）**
   若开启 USAP 池，`forkAndSpecialize` 可能落在"已预 fork 的未特化进程"上，只做 `specialize` 不做 `fork`，延迟更低（呼应专题二）。

5. **SELinux / UID 在 fork 期定，不是在 exec**
   因为 Android 用 `fork()` 而非 `fork()+exec()`，子进程继承 Zygote 的 SELinux 上下文，**必须在 fork 后立即 `setcontext` 降权**，否则会出现"以 Zygote 的 `zygote` 域跑 App"的安全漏洞。这是为什么 `nativeForkAndSpecialize` 里 `selinux_android_setcontext` 是强制步骤。

**易错点（红榜）**
- "App 是 exec 新二进制起来的"。错：Android 用 fork 自 Zygote + 特化，不 exec（除少数 native 守护）。
- "UID 在 manifest 里运行时定"。错：AMS 在 forkAndSpecialize 用 `setuid` 强制降权，manifest 只声明权限、UID 由 installd/PMS 分配后由 AMS 下发。
- "fork 后立刻能收系统消息"。错：要先 `attach` 到 AMS 完成 `attachApplicationLocked`，AMS 才调度 Activity；fork 完的第一句是 `ActivityThread.main`。

**高频追问链**
1. fork 之后为什么要先 GC 再 fork（专题二）？-> 减少 COW 后的脏页，避免 Zygote 堆里一堆待回收对象在子进程私有化。
2. 多进程 App（:remote）怎么 fork？-> 同一个 Zygote 协议，只是 AMS 给不同 UID 段/`process=` 名，各自独立 fork 一个 App 进程。
3. `[A17]` 16KB 页 + hiddenapi 在 fork 期有影响吗？-> fork 后 `handleBindApplication` 里按 targetSdk 设 hiddenapi 豁免名单（第 8/29 篇），页大小由 Zygote 预载的 `boot.art` 决定（第 14 篇）。

**延伸阅读**：第 6 篇（fork 之后 → ActivityThread → 首帧）、第 14 篇（16KB 页）、第 29 篇（SELinux/linker）、第 8/12 篇（hiddenapi/A17）。

---

## 专题七：进程优先级与 oom_adj —— 谁先死、为什么是它

**现象 / 考官提问**
> 1) 后台 App 为什么先被杀、前台的不杀？依据是什么？2) `oom_adj` / `oom_score_adj` 是谁设置的、lmkd 怎么用？3) 三条"杀进程"路径（内核 OOM / LMKD / A17 Memory Limiter）怎么区分？

**底层原理 + 源码落点**

1. **AMS 设置 `oom_adj`（`frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` + `ProcessList.java`）**
   AMS 按进程"重要性"分级，调 `Process.setOomAdj(pid, uid, oomScoreAdj)`（最终写 `/proc/<pid>/oom_score_adj`，范围 -1000~1000）。分级（由高到低重要性）：
   ```
   NATIVE(-1000) / SYSTEM(-900) / PERSISTENT(-800)   // 永不被 lmkd 杀
   FOREGROUND(0) / VISIBLE(100) / PERCEPTIBLE(200)
   BACKUP(300) / HEAVY_WEIGHT(400) / SERVICE(500)
   HOME(600) / PREVIOUS(700) / CACHED_EMPTY(900)      // 最先被杀
   ```
   AMS 在 `updateOomAdjLocked` / `applyOomAdjLocked` 里随 Activity 栈、可见性、前台服务状态实时刷新——**前台 Activity 的进程永远是 0（或更低），缓存的后台进程趋近 900**。

2. **lmkd 读 `oom_score_adj` 决定杀谁（`system/core/lmkd/`）**
   lmkd（Low Memory Killer Daemon，用户态替代旧内核 lowmemorykiller）监听内核的 **PSI（Pressure Stall Information）** 或 `vmpressure` 事件；内存紧张时，按 `oom_score_adj` 从大到小挑进程发 `SIGKILL`。它**不直接看 RSS，而是看"谁最不重要 + 谁占内存多"**，优先回收 `CACHED_EMPTY` 这类可无损重建的进程。

3. **三条"杀进程"路径辨析（呼应第 2/6/19 篇）**
   - **内核 OOM Killer**：系统级内存彻底耗尽、分配失败触发，按 `oom_score`（含 `oom_score_adj` 偏移）挑靶，`SIGKILL`，不可预测、最后手段。
   - **lmkd（用户态 LMK）**：内存"将紧未竭"时由 PSI 触发，按 AMS 设的 `oom_score_adj` 有策略地先杀后台，是**日常**后台回收主力。
   - **`[A17]` Memory Limiter**：**个体进程内存超标**（如单 App 匿名内存/anon swap 触顶）由 framework 静默杀（见 `ApplicationExitInfo.REASON_MEMORY_LIMITER`），区别于"系统整体紧张"的 lmkd。

4. **`onTrimMemory` / `onLowMemory` 是回调不是救命**
   AMS 在内存分级切换时回调 App 的 `onTrimMemory(level)`（如 `TRIM_MEMORY_UI_HIDDEN` / `TRIM_MEMORY_MODERATE` / `TRIM_MEMORY_COMPLETE`），App 应据此释放 cache；但**它只是通知，lmkd 该杀还是杀**——A14 起 `ComponentCallbacks2` 常量只剩 `TRIM_MEMORY_COMPLETE` 与 `TRIM_MEMORY_UI_HIDDEN` 两个强信号（见第 2/8 篇）。

**易错点（红榜）**
- "后台被杀是 AMS 直接 kill"。错：AMS 只设 `oom_score_adj`，真正发 `SIGKILL` 的是 lmkd（或极端时内核 OOM）。
- "前台 App 永远不会被杀"。错：极端内存压力下内核 OOM 也会杀前台；但 lmkd 优先杀高 adj 的后台，前台（adj≤0）最后才轮到。
- "oom_adj 越小越先死"。错：越小越重要（0 前台、-900 system），越大越先死（900 缓存）。

**高频追问链**
1. 为什么用 `oom_score_adj` 而不是 RSS 直接排序？-> 因为"重要性"是 AMS 最清楚（它知道谁在前台、谁可见），单纯按内存大小杀会误杀前台。
2. PSI 是什么、比旧 lowmemorykiller 好在哪？-> PSI 报告"任务因内存压力阻塞的时长占比"，让 lmkd 在**快不够用**时就行动，而非等到 OOM；旧内核 LMK 是固定阈值、粒粗糙。
3. `[A17]` Memory Limiter 和 lmkd 怎么共存？-> lmkd 管"系统整体紧张"，Memory Limiter 管"单进程超标"，两条独立触发路径（第 2/19 篇详述）。

**延伸阅读**：第 2 篇（pKVM·三条杀路径辨析）、第 6 篇（内存三路杀）、第 8 篇（A17 Memory Limiter/隐藏 API）、第 19 篇（内存排查 heapprofd）。

---

## 跨篇易错红榜 TOP20（启动链路 / 进程模型专场）

1. "init 是内核的一部分" — 错，PID 1 用户态进程。
2. "Zygote 启动即 Java" — 错，先 C++ 起 ART 再反射进 ZygoteInit。
3. "fork 完整拷贝父内存" — 错，COW 只复制被写页。
4. "App 自己加载所有类" — 错，framework 类由 Zygote preload 并 COW 共享。
5. "Zygote 用 Binder 收 fork 请求" — 错，用 LocalSocket（`/dev/socket/zygote`）。
6. "system_server 由 init 直接拉" — 错，Zygote `startSystemServer` fork 出来。
7. "AMS/WMS 在 Zygote 里就启动" — 错，system_server 分阶段 `run()` 创建并注册。
8. "ServiceManager 也要查表才能找到" — 错，handle 0 自举约定。
9. "binder/hwbinder/vndbinder 是三个驱动" — 错，同 `binder.c` 三个 context。
10. "Watchdog 监测 App" — 错，只监测 system_server 自身。
11. "Watchdog 超时弹 ANR 框" — 错，直接杀 system_server 重启。
12. "App 是 exec 新二进制" — 错，fork 自 Zygote + 特化。
13. "UID 运行时由 manifest 定" — 错，AMS `setuid` 强制降权。
14. "fork 完立刻收系统消息" — 错，先 `attach` 到 AMS 报道。
15. "后台被杀是 AMS 直接 kill" — 错，AMS 设 adj，lmkd 发 SIGKILL。
16. "oom_adj 越小越先死" — 错，越小越重要。
17. "前台 App 永不被杀" — 错，极端 OOM 也会杀前台。
18. "onTrimMemory 能阻止被杀" — 错，只是通知，lmkd 照杀。
19. "USAP 池是另一个进程" — 错，是 Zygote 预 fork 的未特化进程 standby。
20. "Zygote 会被 lmkd 杀" — 错，system 关键进程，永不被 LMK。

---

## 三条高频追问链（启动 / 进程模型专场）

**链 A：一条 App 冷启动的完整链路（把本篇与 8/6 焊起来）**
init→Zygote(preload+socket)→AMS 在 system_server 决定启动→ZygoteProcess 经 socket 发 ZygoteArguments→Zygote.forkAndSpecialize(setuid/selinux 特化)→ActivityThread.main(prepareMainLooper)→attach 到 AMS(attachApplicationLocked)→AMS 发 bindApplication→handleBindApplication(建 Application/钉 ART/ContentProvider 前置坑)→scheduleTransaction 启动 Activity→performLaunchActivity→handleResumeActivity→ViewRootImpl.setView→首帧(SurfaceFlinger)。

**链 B：system_server 死亡连锁**
某服务 monitor 锁死锁→Watchdog 30s 超时→killProcess(myPid)+System.exit(10)→写 traces.txt/dropbox→init 见 system_server 退出→由 Zygote 重新 fork 新 system_server→SystemServer.run 分阶段重启服务→期间所有 App 被清、短暂黑屏/重启 framework。

**链 C：内存压力下"谁先死"决策流**
AMS 实时 `updateOomAdjLocked` 按前台/可见/缓存设 `oom_score_adj`→lmkd 监听 PSI/vmpressure→内存紧张时按 adj 从大到小挑 `SIGKILL`→极端时内核 OOM Killer 兜底（按 oom_score）→`[A17]` 单进程超标由 Memory Limiter 静默杀（REASON_MEMORY_LIMITER）。三条路径并存、触发条件不同。

---

## AOSP 14 源码路径清单（本篇）

| 主题 | 关键文件 | 关键方法/符号 |
|------|----------|---------------|
| init 启动 | `system/core/init/` | `init.rc` / `init.${ro.zygote}.rc` |
| app_process | `frameworks/base/cmds/app_process/app_main.cpp` | `main()` / `AppRuntime` |
| ART 启动 | `frameworks/base/core/jni/AndroidRuntime.cpp` | `startVm()` / `startReg()` |
| Zygote 初始化 | `frameworks/base/core/java/com/android/internal/os/ZygoteInit.java` | `preloadClasses()` / `registerZygoteSocket()` / `startSystemServer()` / `runSelectLoop()` |
| Zygote Server | `frameworks/base/core/java/com/android/internal/os/ZygoteServer.java` | `runSelectLoop()` / `acceptCommandPeer()` |
| fork 协议 | `frameworks/base/core/java/com/android/internal/os/Zygote.java` | `forkAndSpecialize()` / `forkSystemServer()` |
| fork native | `frameworks/base/core/jni/com_android_internal_os_Zygote.cpp` | `nativeForkAndSpecialize()` / `selinux_android_setcontext` |
| SystemServer | `frameworks/base/services/java/com/android/server/SystemServer.java` | `main()` / `run()` / `startBootstrapServices()` / `startOtherServices()` |
| AMS | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` | `updateOomAdjLocked()` / `applyOomAdjLocked()` |
| ProcessList | `frameworks/base/services/core/java/com/android/server/am/ProcessList.java` | `startProcessLocked()` / `ZygoteProcess` |
| Watchdog | `frameworks/base/services/core/java/com/android/server/Watchdog.java` | `addMonitor()` / `run()` / `evaluateCheckerCompletionLocked()` |
| ServiceManager | `frameworks/native/cmds/servicemanager/servicemanager.c` | `binder_open()` / `binder_become_context_manager()` / `svcmgr_handler` |
| binder 驱动 | `drivers/android/binder.c` (GKI android14-6.1) | `binder_open()` / `binder_mmap()` / `binder_ioctl()` / `binder_transaction()` |
| lmkd | `system/core/lmkd/lmkd.cpp` | PSI 监听 / `oom_score_adj` 读取 / `SIGKILL` |
| ActivityThread | `frameworks/base/core/java/android/app/ActivityThread.java` | `main()` / `attach()` / `handleBindApplication()` |

---

## 33 篇 → 34 篇交叉索引

- **与 8/6 源码级 code walk（应用侧）**：本篇是它"fork 之前 + fork 本身"的镜像补全；8/6 从 `startActivity` 讲起，本篇从 `init` 讲到 `ActivityThread.main`，两者首尾相接成完整启动链路。
- **与 8/2 pKVM / 8/13 智能层**：本篇专题四/七的 `forkSystemServer` vs `forkAndSpecialize` 区分，呼应"谁在 fork、用什么权限"；跨 VM RPC Binder 见 8/12。
- **与 8/12 核心基础（Looper/同步屏障）**：本篇专题五/七的 `prepareMainLooper` 与 Watchdog 监测主线程，是 8/12 主线程死循环不 ANR 的"系统侧守护"补充。
- **与 8/14 Native 稳定性 / 8/29**：SELinux 域切换、16KB 页、linker 在 fork 特化期的作用。
- **与 8/17 HAL / Treble**：专题四的 `/dev/binder` vs `/dev/hwbinder` vs `/dev/vndbinder` 三上下文。
- **与 8/19 之前的内存三路杀（8/2、8/6、8/19 专题七）**：本篇把 `oom_adj`/lmkd/PSI/Memory Limiter 三条路径统一到"进程优先级"视角。

> 全系列至此 34 篇 / 约 219 专题完整闭环（启动链路 + system_server 进程模型补齐了"App 启动流程"的 boot 侧真缺口）。
