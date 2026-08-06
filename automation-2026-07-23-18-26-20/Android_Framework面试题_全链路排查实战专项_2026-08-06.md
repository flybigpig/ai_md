# Android Framework 面试题 · 全链路性能 / 问题排查实战专项（第十九篇）

> 日期：2026-08-06 ｜ 定位：系列第 19 篇，约 132 专题之后的**压轴实战篇**
> 主线：把前 18 篇分散在 Binder / AMS / WMS / SF / ART / LMKD / Input / HAL / 安全 / 端侧 AI 的 132 个知识点，串成面试官最爱考的「**从现象到根因的端到端定界能力**」。
> 当日热点锚定（2026-08-06 联网核验）：
> - **Android 17 QPR2 Beta 2** 已于 8/4 发布（build CP41.260701.006），仅小幅 UI 调整 + 后台稳定性优化，无行为变更；Beta 1（7/20）修复清单里藏了几个可当「真题现场」的 bug：多指拖拽丢触摸 #516836306、ML-DSA 用 "NONE" digest 抛异常 #525612735、窗口级模糊渲染失效 #527376569、AccessibilityNodeInfo.toString 窗口边界错记 #520428442。
> - **EU DMA 裁定（2026-07-16）**：Google 须在 Android 18（2027-08-01 前）向第三方 AI 助手开放 11 项能力（invocation / context / actions / resources），AppFunctions、屏幕自动化、端侧模型从 Gemini 专属变成「可认证接入」——与 QPR2 重写的 Companion Device Manager 锁屏屏幕自动化权限直接呼应。
> - **Perfetto 已成排查事实标准**：FrameTimeline 定帧、heapprofd 抓原生内存、trace_processor SQL 量化——本篇方法皆基于它。

> 阅读方式提示：本篇不是再讲一遍原理，而是给你一套「拿到症状 → 开哪个工具 → 看哪条轨道 → 怎么下结论」的肌肉记忆。每条结论都回扣 AOSP 14 源码路径，方便你「现场能答、答完能溯源」。

---

## 0. 为什么面试官爱问「怎么排查」

会背 Binder 一次拷贝的人一大把；能对着一份 `bugreport` 在 10 分钟内说出「主线程被 system_server 的一把 AMS 锁堵了 4.3s，根因是应用自己的 ContentProvider 在 bindApplication 阶段同步查了 Settings」的人，凤毛麟角。

排查题的底层考法永远是一套：

```
现象(卡/ANR/崩/发热/内存涨)
   -> 抓 trace (Perfetto / bugreport / anr traces)
   -> 定界 (问题在 App 主线程 / RenderThread / SF / system_server / 内核?)
   -> 根因 (Binder 阻塞 / 锁竞争 / IO / GC / 降频 / 内存三路杀)
   -> 修复 (异步化 / 缓存 / 合并 / Profile / 限流)
```

本篇用 6 个高频实战场景，把这条链路跑通。

工具栈速查（先记命令，后面每个场景复用）：

```bash
# 1) 抓 trace（启动 / 掉帧 / 卡顿通用）
adb shell perfetto -o /data/misc/perfetto-traces/issue.perfetto-trace -t 10s \
  sched freq idle am wm gfx view binder_driver dalvik hal
adb pull /data/misc/perfetto-traces/issue.perfetto-trace
# 拖进 https://ui.perfetto.dev/ ，用 trace_processor SQL 量化

# 2) 看 ANR
adb shell ls -lat /data/anr/          # 带时间戳的 traces 文件
adb bugreport ./bugreport.zip         # 全量（含 event log、dumpsys）

# 3) 看内存
adb shell dumpsys meminfo <pkg>
adb shell dumpsys gfxinfo <pkg>       # 渲染/帧
adb shell am dumpheap <pid> /data/local/tmp/hprof

# 4) 看系统状态
adb shell dumpsys cpuinfo
adb shell dumpsys thermalservice
adb shell dumpsys battery
adb shell dumpsys activity p <pkg>    # 进程/任务栈
```

---

## 专题一：冷启动慢 —— 全链路追踪与优化

### 1.1 时间窗定义（面试官第一问：你怎么量化启动耗时？）

```
Launcher 点击图标
  -> ATMS.startActivity (system_server)
  -> Zygote fork 新进程
  -> ActivityThread.main -> Looper 起来
  -> bindApplication (Application.attachBaseContext + onCreate)
  -> Activity.onCreate/onStart/onResume
  -> Choreographer 首帧 doFrame
  -> SurfaceFlinger 合成上屏  = 启动完成(首帧)
```

- **启动器首帧** vs **内容首帧**：windowBackground 预览出来的「首帧」是假的，应用真正可交互要等 `reportFullyDrawn()`。
- Perfetto 量化：用 `record_android_trace -o launch.perfetto-trace -t 10s am wm gfx view binder_driver dalvik`，在 Metrics 面板看 **App Launch** 指标，或直接在 `am` 轨道量 `activity_launch -> activity_idle` 的时差。

### 1.2 各阶段源码落点（AOSP 14）

| 阶段 | 源码位置 | 典型耗时占比 |
|------|----------|--------------|
| 进程创建 | `frameworks/base/core/java/com/android/internal/os/Zygote.java`（`forkAndSpecialize`，native 在 `com_android_internal_os_Zygote.cpp`）；AMS 侧 `ActivityManagerService.startProcessLocked()` | 中（fork 本身快，zygote 预加载的类要「复制」，见下） |
| bindApplication | `frameworks/base/core/java/android/app/ActivityThread.java`（`handleBindApplication` -> `bindApplication` -> `LoadedApk.makeApplication`） | **~30%+**，最大头 |
| Application.onCreate | 应用代码，但**注意 ContentProvider 在它之前** | 高（ notoriously） |
| Activity 启动 | `ActivityThread.performLaunchActivity` -> `handleResumeActivity` -> `ViewRootImpl` 首次 `scheduleTraversals` | 中 |
| 首帧 | `Choreographer.doFrame` -> `ViewRootImpl.doTraversal` -> `performTraversals` | 中 |

> **关键坑（必背）**：`ContentProvider` 的 `onCreate()` 在 `Application.onCreate()` **之前**执行，且在主线程。很多「启动慢」根因是 CP 里同步读了 SharedPreferences / 数据库 / 调了系统服务。Google 的 `androidx.startup` 就是用来把这类初始化显式化、可延迟化。

### 1.3 为什么 Zygote fork 后还要「加载」？—— 预加载与 Profile

- `ZygoteInit.preloadClasses()` / `preloadResources()` 在 zygote 进程里预加载几百个常用类与资源，fork 时靠**写时复制（CoW）**共享。换页（page fault）到私有页时才真正占内存——所以「冷启动慢」有时是 preload 命中差。
- **基线 Profile（baseline-profile）**：把热身后的热点方法/类编进 odex，跳过解释执行与 JIT  warm-up。`baseline-profile.gradle` 生成 `baseline.prof`，随 APK 发布；Android 上云 Profile（Cloud Profile）由 Play 商店按机型聚合下发。
- **PinnerService**：`frameworks/base/services/core/java/com/android/server/PinnerService.java`，把关键 dex/库 pin 在内存防被回收，加快二次启动（与启动强相关但常被忽略）。
- **AppImageManager / dex metadata**：`*.dm` 文件带训练后的 profile，dexopt 时生成 app image，类被预先布局进 `app-image` 直接 mmap，省去运行时解析。

### 1.4 优化清单（面试能直接列）

1. 合并 / 延迟 ContentProvider，杜绝 `attachBaseContext` 前的同步系统调用。
2. 主线程禁 IO：SharedPreference 大文件、数据库首开、文件扫描移到子线程或启动器预取。
3. 用 `baseline-profile` + `R8` 全量混淆优化；发版前生成云 Profile。
4. 视觉提速：`windowBackground` 设主题色预览（别用纯白闪屏），`reportFullyDrawn()` 标记真首帧。
5. `StrictMode.setThreadPolicy(detectAll())` 在 debug 包抓主线程 IO / 错误关闭。
6. 第三方 SDK 懒加载：很多 SDK 在 Application.onCreate 里同步 init，应按需或延迟。

### 1.5 易错点 / 高频追问

- ❌ 误以为「冷启动慢 = 自己代码慢」。实测 30%+ 在 `bindApplication`（系统侧类加载 + provider 初始化）。
- ❌ 用 `System.currentTimeMillis()` 打点量化启动——不准确，应该用 `reportFullyDrawn` + Perfetto。
- 🔁 追问：「app image 和基线 Profile 区别？」「为什么云 Profile 比基线 Profile 更准？」「Zygote 预加载过多会怎样（内存占用 vs 启动）？」

---

## 专题二：卡顿 / 掉帧 —— 端到端定界

### 2.1 渲染全链路（先把这条链背熟）

```
Vsync(app)  -> Choreographer.scheduleFrameLocked
   -> Choreographer.doFrame  -> 三段回调:
        input -> animation -> traversal(measure/layout/draw)
   -> ViewRootImpl.doTraversal -> performTraversals
        measure (自顶向下, 可能两次) / layout / draw(构建 DisplayList)
   -> 同步 DisplayList 到 RenderThread
   -> RenderThread 生成 GL/Vulkan 命令 -> queueBuffer
   -> SurfaceFlinger 合成 -> HWC Overlay/GPU -> 上屏
```

- 60fps 单帧预算 ~16.6ms；120fps ~8.3ms。**任何一段超预算即掉帧**。

### 2.2 FrameTimeline（AOSP 14 定帧利器，必答）

`frameworks/native/services/surfaceflinger/FrameTimeline.cpp` + `FpsReporter.cpp`：把「应用期望提交时间、实际提交时间、SF 合成时间」钉成一条时间线。`JankType` 枚举直接告诉你掉帧责任方：

| JankType | 含义 | 责任方 |
|----------|------|--------|
| `AppDeadlineMissed` | 应用 doFrame 超时 | **App 主线程** |
| `SurfaceFlingerDeadlineMissed` | SF 合成超时 | **SF / HWC** |
| `AppChoice` | 应用主动跳帧（如 RecyclerView 惯性） | App（非 bug） |
| `BufferStuffing` | 缓冲区堆积 | SF 调度 |
| `Throttle` / `NoFence` / `Unknown` | 其他原因 | 需看 trace |

> 面试金句：**掉帧不一定是 App 卡**。先看 FrameTimeline 的 JankType，再决定查主线程还是 SF。

### 2.3 Perfetto 读 trace 四步（实战肌肉记忆）

1. **定时间窗**：启动看 Launcher 点击→首帧；滚动看红帧区间。
2. **看主线程**：`Choreographer#doFrame` slice 是否超 16.6ms；里面是 lifecycle / measure-layout-draw / Binder / 锁等待？
3. **看等待原因**：主线程 `monitor contention`（被谁持锁？）、`binder` 长事务（追到 system_server 目标线程）、`Blocked`/`Sleeping` 状态。
4. **看渲染路径**：主线程空闲但 RenderThread 满 → 复杂阴影 / 过度绘制 / 大图纹理上传 / hardware layer。SF 满 → HWC 合成决策或 GPU 超时。

量化 SQL（trace_processor）：

```sql
-- 主线程超 16.6ms 的长 slice
SELECT name, dur/1e6 AS ms FROM slice
WHERE name LIKE '%doFrame%' AND dur > 16600000 ORDER BY dur DESC LIMIT 20;
```

### 2.4 经典卡顿根因与源码

| 根因 | 表现 | 源码落点 |
|------|------|----------|
| 主线程同步 Binder 长调用 | doFrame 内 binder slice 长 | `Choreographer.doFrame` -> IPCThreadState |
| 锁竞争（非 UI 线程持锁做 IO） | monitor contention，owner 是某 Binder 线程 | `synchronized` / `ReentrantLock`，Perfetto `monitor contention` 标 owner |
| measure/layout 双遍历 | 嵌套 `RelativeLayout` / 不当 `requestLayout` | `View.measure` / `ViewGroup.requestLayout` 向上冒泡 |
| RecyclerView 不当 | `notifyDataSetChanged` 全量、 onCreateViewHolder 重 | `RecyclerView` / `RecycledViewPool` |
| RenderThread 重 | 复杂 `RenderEffect`/`Blur`、大图 `Bitmap` 上传 | `frameworks/base/libs/hwui/RenderThread.cpp` |
| 温控降频 | doFrame 虽短但 CPU 跑不满（sched 显示排队） | `thermalservice` + `cpu_frequency` track |

### 2.5 与当日 QPR2 bug 呼应（真题现场）

- **QPR2 Beta1 多指拖拽丢触摸 #516836306**：根因在 `InputDispatcher` 多指拆分（split touch）逻辑，与「深坑篇」讲的 `InputDispatcher::splitTouch` 直接相关——多点触控下 source view 切换时事件路由出错。这类 bug 在 Perfetto 里看 `input` track 的 `MotionEvent` 分发即可定位。
- **窗口级模糊渲染失效 #527376569**：`RenderEffect.createBlurEffect` / SF 的 blur 合成在重启后失效，属于 `hwui` + SF 的 blur 后端状态未持久化——提醒你「模糊效果」不是免费午餐，它走 SF/GPU 合成，过度使用直接拖 `SurfaceFlingerDeadlineMissed`。

### 2.6 易错点 / 高频追问

- ❌ 看到掉帧就优化 onDraw。先 FrameTimeline 定责。
- ❌ `requestLayout()` 在动画里乱调导致全树重测。
- 🔁 追问：「如何区分 App 卡 vs SF 卡？」「`doFrame` 三段回调顺序是什么、为什么 input 在最前？」「过绘制怎么量化（overdraw 工具 / GPU 渲染模式条）？」

---

## 专题三：ANR —— 完整回溯

### 3.1 四类超时（背默认值，OEM 可能改）

| 类型 | 默认超时 | 触发方 |
|------|----------|--------|
| Input dispatching | **5s** | 主线程没处理 input 事件（`InputDispatcher` 侧计时） |
| Service 前台 / 后台 | **20s / 200s** | `ActiveServices.scheduleServiceTimeout` |
| Broadcast 前台 / 后台 | **10s / 60s** | `BroadcastQueue.processNextBroadcastLocked` |
| ContentProvider publish | **10s** | `ActivityManagerService` |

### 3.2 回溯链路（面试要能口述端到端）

```
system_server 侧计时器到点
  -> event log 打 "am_anr" (adb logcat -b events | grep am_anr)
  -> AnrHelper.appNotResponding / AppErrors
  -> 取目标进程所有线程栈:
       /data/anr/anr_<timestamp>_<pid>  (旧版 /data/anr/traces.txt)
  -> 同时 dump system_server / binder 线程栈
  -> 弹 "应用无响应" 对话框 (handleShowAnrUi)
```

> 关键：`/data/anr/` 现在按时间戳分文件（不再是单一 `traces.txt`），且只抓**直接相关进程 + system_server**，不是全系统。想拿全量用 `adb bugreport`。

### 3.3 根因归类（按出现频率）

1. **主线程同步 Binder 长调用**（最高频）：主线程调 `getSystemService` 相关、AMS/PMS/WMS 慢响应拖死。Perfetto 看主线程 `binder` slice，顺箭头追到 system_server 目标线程看它为何慢（往往是锁竞争）。
2. **锁竞争**：主线程 `monitor contention`，持锁线程正在做 IO / Binder。Perfetto 标出 owner 线程，去查它。
3. **binder 线程池耗尽**：默认 15 线程（见专题六），oneway 也会排队，表现像「卡死」。
4. **主线程 IO / 大计算**：SharedPreferences 全量读、JSON 解析、图片解码。
5. **系统问题 vs 应用问题**：区分方法（官方 ANR 文档原话）——Perfetto 看 main 是否 **scheduled**（running/runnable），看 system_server 是否有锁竞争，看 binder 回复线程是否慢。

### 3.4 源码落点（AOSP 14）

- `frameworks/base/services/core/java/com/android/server/am/AnrHelper.java`（`appNotResponding`）
- `frameworks/base/services/core/java/com/android/server/am/AppErrors.java`（`handleShowAnrUi`）
- `frameworks/base/services/core/java/com/android/server/am/ActiveServices.java`（`SERVICE_TIMEOUT` / `scheduleServiceTimeoutLocked`）
- `frameworks/base/services/core/java/com/android/server/am/BroadcastQueue.java`（`BROADCAST_TIMEOUT_MSG`）
- `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp`（`handleTargetsNotReadyLocked`，5s 计时 `mInputTargetWaitTimeout`）
- `frameworks/base/core/java/android/app/ActivityThread.java`（主线程 `Handler` 分发，ANR 时主线程栈就在这里）

### 3.5 易错点 / 高频追问

- ❌ 认为「only main thread 导致 ANR」。binder 线程池满、system_server 慢也会间接触发。
- ❌ 用 `Thread.sleep` 复现 ANR 调试——栈里只看到 sleep，没价值；要抓真实现场。
- 🔁 追问：「Input ANR 5s 是从哪开始算（down 事件还是 dispatch 起点）？」「binder 线程池满了对 oneway 有影响吗？」「`kill -3 <pid>` 抓的栈和 `/data/anr/` 里的一样吗？」（一样，都是 SIGQUIT 触发的 `dumpStackTraces`）

---

## 专题四：内存涨 / OOM / 低内存 —— 三路杀与分类排查

### 4.1 三条「杀进程」路径（必背辨析，串联第十二/十三篇）

| 路径 | 触发 | 特点 | 源码 |
|------|------|------|------|
| 内核 OOM killer | 整机内存彻底耗尽，分配失败 | 按 oom_score 杀，最暴力 | 内核 `mm/oom_kill.c` |
| LMKD（PSI 驱动） | 内存压力（PSI stall 超阈） | 按 oom_adj / 优先级挑进程杀；`lowmemorykiller` 驱动已退场，改用户态 LMKD | `system/core/lmkd/lmkd.cpp` + `ProcessList.java`（`applyOomAdjLocked` 通信） |
| **A17 Memory Limiter** | **单应用内存超个体限额**（非整机压力） | 静默杀、前台也可能杀；与 LMKD 正交 | `frameworks/base` 配额管理 + `ActivityManager` |

> 金句：**LMKD 是「整机快撑不住了，挑个不重要的杀」；Memory Limiter 是「你这应用自己超标了，杀你一个」**。两条路径独立，所以「整机内存还够但我的 App 被杀了」往往是 Memory Limiter。

### 4.2 内存分类排查（面试按图索骥）

| 类别 | 工具 | 典型泄漏 |
|------|------|----------|
| Java 堆 | Memory Profiler / `hprof` / MAT | static 引用 Activity、非静态内部类 Handler/Thread 持 Context、未注销监听器 |
| Native | **heapprofd**（Perfetto，低开销替代 malloc_debug）/ `libmemunreachable` | so 里 `malloc` 不归 ART 管；C++ 对象循环引用、忘了 `free` |
| Graphics | `dumpsys SurfaceFlinger` / `dumpsys gfxinfo` / GPU meminfo | `GraphicBuffer` _surface_ 未 `release`、TextureView 滥用 |
| Binder | `dumpsys activity binder` / 内核 `binder_transaction` | 大 binder 事务（见专题六）、DeathRecipient 泄漏致引用计数不降 |
| Ashmem / file / code | `dumpsys meminfo` 分项 | 大文件映射、dex 未释放 |

### 4.3 ART GC 演进（串联深坑篇 + 第十三篇）

- **Android 14 默认 CMC（Concurrent Mark-Compact）GC**，基于 `userfaultfd` 实现并发压缩、减少碎片与暂停。
- **Android 17 在 CMC 之上加「分代」**（young / old gen），经 `art` apex Mainline 可热更——所以 A17 上 GC 行为可能与出厂不同，排查时要看 `art` 模块版本。

### 4.4 onTrimMemory 信号（A14 起收敛）

`frameworks/base/core/java/android/content/ComponentCallbacks2.java`：
- `TRIM_MEMORY_UI_HIDDEN`：UI 不可见，释放 UI 资源（图片缓存、View 引用）。
- `TRIM_MEMORY_RUNNING_*`（moderate / low / critical）：App 仍在前台但内存紧，逐级释放。
- `TRIM_MEMORY_BACKGROUND / MODERATE / COMPLETE`：App 进后台，按 LRU 被杀风险递增。

> A14 起 trim 信号做了收敛（深度 trimmed 只剩少数关键级别），重点响应 `UI_HIDDEN` 与 `COMPLETE`；别再依赖曾被移除的中间级别。

### 4.5 易错点 / 高频追问

- ❌ 用 `largeHeap=true` 治泄漏——只延后爆，不治本，还挤占整机。
- ❌ native 泄漏用 LeakCanary（只查 Java）——要用 heapprofd / libmemunreachable。
- 🔁 追问：「native 泄漏怎么查？」「binder 内存为何会涨（哪个环节没释放）？」「Memory Limiter 和 LMKD 怎么区分现场？」「A17 分代 GC 对卡顿有什么影响（young gen minor GC 更频繁但更短）？」

---

## 专题五：发热掉速 / 后台受限 / 资源治理

### 5.1 热力链路

```
温度传感 -> Thermal HAL (hardware/interfaces/thermal/)
   -> ThermalService -> Power HAL / ADPF PerformanceHintManager
   -> CPU 限频 (cpu_frequency track 看到降频)
   -> 应用 doFrame 预算被压缩 -> 掉帧 + 体感卡 + 发热
```

- **ADPF（Android Dynamic Performance Framework）**：`frameworks/base/core/java/android/os/PerformanceHintManager.java`，应用上报「预期工作量」，系统据此给 CPU 带宽；游戏/相机常用。面试能说「用 `PerformanceHintManager` 告诉系统我的帧工作量，避免被误降频」就是加分项。

### 5.2 后台限制全家福（串联第七/十/十一篇）

| 机制 | 作用 | 源码 |
|------|------|------|
| Doze | 熄屏静止后限制网络/Job/同步/alarm | `DeviceIdleController.java` |
| App Standby / Standby Bucket | 按使用频度分桶，限制后台网络/Job | `UsageStatsManager` / `AppStandbyController` |
| BAL（Background Activity Launch） | 后台启动 Activity 受限（`callingUid` vs `realCallingUid` 防 confused deputy） | `BackgroundActivityStartController.java` |
| JobScheduler 配额（A16 收紧） | 后台 Job 执行时长/次数配额 | `JobSchedulerService.java` |
| 前台服务类型（FGS） | A14 强制 `startForeground(type)`，超时/类型错配被系统处置 | `Service.startForeground` / `FgsManager` |

> 体感总结：**后台保活越来越难**。WakeLock 不释放、Alarm 频繁、错误 FGS 类型、后台 startActivity 被 BAL 拦截，都会表现为「功能时灵时不灵 + 掉电快 + 被系统杀」。

### 5.3 排查命令

```bash
adb shell dumpsys thermalservice      # 当前温度等级/限频
adb shell dumpsys cpuinfo              # CPU 占用
adb shell dumpsys battery             # 充电/省电模式
adb shell dumpsys usagestats           # Standby bucket
adb shell dumpsys jobscheduler         # Job 执行历史/配额
```

### 5.4 易错点 / 高频追问

- ❌ 用 `PARTIAL_WAKE_LOCK` 长期保活——Doze 下仍可能被限制，且费电。
- ❌ A14 后 `startForeground` 不传类型——直接 `Missing foreground service type` 异常。
- 🔁 追问：「ADPF 怎么用、为什么能降发热？」「BAL 怎么判断一个后台启动是否允许（ALLOW_IF_VISIBLE 等）？」「A16 JobScheduler 配额变了什么？」

---

## 专题六：Binder / 跨进程通信实战坑 + A17 安全实战新特性收尾

### 6.1 Binder 线程池（高频必考）

- 默认 **max 15 线程**：`frameworks/native/libs/binder/ProcessState.cpp` 里 `mMaxThreads = 15`（主线程不处理 binder，由 `IPCThreadState` 在 `BR_SPAWN_LOOPER` 时孵化）。
- **满了会怎样**：新事务排队等待空闲 binder 线程；**连 oneway 也受影响**（oneway 只是「调用方不等结果」，服务端仍要线程处理）。表现 =「明明没同步等，却整体卡」。
- 排查：`adb shell dumpsys activity binder` 看各进程 binder 线程数与阻塞事务；Perfetto `binder_driver` 看事务排队。

### 6.2 oneway vs two-way

- **two-way（默认）**：调用方阻塞等返回，易被对方慢响应拖死主线程（与 ANR 专题呼应）。
- **oneway（`@Oneway` / `FLAG_ONEWAY`）**：fire-and-forget，调用方立即返回；适合事件通知、状态上报。
- 误区：oneway 不是「不占资源」，服务端处理仍吃 binder 线程；高频 oneway 也会把线程池打满。

### 6.3 大 binder 事务

- 事务缓冲区有限（每进程 mmap 的 binder 区有限，单事务上限约 1MB 量级，受 `BINDER_VM_SIZE` 约束），超限抛 `TransactionTooLargeException`。
- **大数据怎么传**：走 fd / `BinderProxy` / 共享内存（Ashmem / **A17 推 DMA-BUF heaps**，见第十一篇）/ `ParcelFileDescriptor`，别把大 byte[] 直接塞 Parcel。
- **binder 引用计数泄漏**：忘了注册 `DeathRecipient` 或对方异常死亡，引用不降 -> binder 内存涨（专题四）。

### 6.4 跨 VM RpcSession（呼应第十二篇）

- `frameworks/native/libs/binder/RpcSession.cpp`：binder RPC 同时服务 AVF(pKVM) 与 Trusty(TEE)，走 vsock。
- **坑（必背）**：跨 VM 时 `getCallingUid()` **不可信**（hypervisor/TA 边界外的 UID 无法由内核 binder 保证），必须额外认证。这与第十三篇「AppFunctions Provider 侧 `getCallingUid()` 拿到 SYSTEM_UID 不可信」是同一类考点——**UID 不可信的边界场景**。

### 6.5 A17 安全实战新特性（收尾最后一个真缺口）

| 特性 | 是什么 | 面试怎么答 |
|------|--------|-----------|
| **Verified Financial Calls** | A17 新增：通话中核验金融信息（如银行转账确认），由系统托管可信 UI + 安全通道呈现，防中间人/假 App 钓鱼 | 「金融类操作从 App 自绘变成系统托管可信界面，类似 ConfirmationUI 思路」 |
| **Live Threat Detection** | A17 平台级实时威胁检测：监测设备上的恶意行为模式（异常权限使用、可疑后台活动）并提示/阻断 | 「把原先散落各处的异常检测收敛成平台能力，配合 A17 安全原生 DCL 加固（dlopen 的 .so 必须只读）」 |
| Memory Limiter 个体超标静默杀 | 见专题四 | 三路杀辨析 |
| 安全原生 DCL 加固 / Keystore 限额 / 跨资料环回阻断 | 见第十一篇 | 与 EL3/TEE 联动 |

> 当日报错呼应：**QPR2 Beta1 ML-DSA 用 "NONE" digest 抛异常 #525612735**——根因在签名实现里 digest 参数没走常量（`SHA_512` 等）而用了字符串 `"NONE"`，与第十三篇讲的「APK 签名方案 v3.2 混合 ML-DSA（FIPS 204）」直接衔接：**后量子签名落地时 digest/参数要显式且校验，否则运行时炸**。

### 6.6 易错点 / 高频追问

- ❌ 以为 oneway 一定不卡（线程池满照样排队）。
- ❌ 跨进程传大对象（Bitmap bytes、大 List）直接 Parcel。
- 🔁 追问：「binder 线程池默认多少、满了会怎样？」「跨进程传大文件用什么（fd/共享内存）？」「`getCallingUid` 在哪些场景不可信（跨 VM / AppFunctions Provider / 跨资料）？」

---

## 7. 跨全篇易错点红榜 TOP 20

1. 启动慢根因常不在自己代码，而在 `bindApplication` + ContentProvider 前置初始化。
2. ContentProvider.onCreate 早于 Application.onCreate，且主线程。
3. 掉帧先 FrameTimeline 定责，别上来就优化 onDraw。
4. `JankType.AppDeadlineMissed`（App 卡）vs `SurfaceFlingerDeadlineMissed`（SF 卡）责任方不同。
5. Perfetto 四步：定窗 → 主线程 → 等待原因 → 渲染路径。
6. ANR 5s 是 Input dispatch；Service 20s/200s；Broadcast 10s/60s。
7. 主线程同步 Binder 调用是 ANR 头号元凶。
8. binder 线程池默认 15，oneway 满也会排队。
9. 三条杀路径：内核 OOM / LMKD(PSI) / A17 Memory Limiter（个体超标，静默）。
10. native 泄漏 LeakCanary 查不到，用 heapprofd / libmemunreachable。
11. GraphicBuffer 泄漏看 `dumpsys SurfaceFlinger`，不在 Java 堆。
12. A14 默认 CMC GC(userfaultfd)；A17 加年轻代（经 art apex 热更）。
13. largeHeap 治标不治本。
14. 发热链路：Thermal HAL -> Power HAL/ADPF -> CPU 限频 -> 帧预算被压缩。
15. ADPF PerformanceHintManager 可主动上报工作量防误降频。
16. 后台保活越来越难：Doze/AppStandby/BAL/Job 配额(A16)/FGS 类型(A14)。
17. 大 binder 事务走 fd/共享内存，别塞 Parcel。
18. 跨 VM / AppFunctions Provider / 跨资料 下 `getCallingUid` 不可信。
19. A17 安全原生 DCL：dlopen 的 .so 必须只读；Keystore/跨资料环回已加固。
20. 查问题先 `adb bugreport` / Perfetto，不要用 log 猜；trace 给「发生了什么」，根因还要回源码。

---

## 8. 三条高频追问链（连击训练）

**链 A：启动 -> 卡顿 -> ANR（主线程一条线）**
主线程为什么卡？（bindApplication/CP 前置 / 同步 Binder / 锁竞争）
-> 卡久了会怎样？（Input 5s ANR / Service 20s）
-> 怎么量化？（Perfetto App Launch / doFrame slice / am_anr + /data/anr）
-> 怎么修？（异步化 / 合并 CP / baseline-profile / 缓存 Binder 结果）

**链 B：掉帧 -> 渲染路径 -> 发热（渲染+资源一条线）**
掉帧谁的责任？（FrameTimeline JankType）
-> 渲染路径哪段慢？（主线程 vs RenderThread vs SF vs HWC）
-> 为什么突然全慢？（温控降频 -> Thermal HAL -> ADPF）
-> 怎么缓解？（限帧/降复杂度/PerformanceHintManager/减少 blur）

**链 C：内存涨 -> 三路杀 -> GC（内存一条线）**
涨的是哪类内存？（Java/native/graphics/binder）
-> 谁会杀我？（LMKD 整机压力 vs Memory Limiter 个体超标 vs 内核 OOM）
-> GC 为什么救不了？（native 不归 ART；CMC 压缩仍受限；分代 young/old）
-> 怎么查？（heapprofd / dumpsys meminfo / SurfaceFlinger / binder）

---

## 9. 与其他 18 篇的交叉索引（复习导航）

| 本篇场景 | 关联篇 | 关联专题 |
|----------|--------|----------|
| 启动/进程创建 | 主篇 / 深挖篇 | Zygote socket、fork、bindApplication |
| Binder 全链路 | 主篇(3 篇) / 深挖篇 | 一次拷贝/mmap/线程池/TTLE、Rust Binder、binderfs 调试 |
| AMS/ATMS/oom_adj | 主篇 / 拓展篇 | startProcessLocked、oom_adj、AppExitInfo |
| WMS/SF 合成 | 主篇 / 图形篇 / 渲染合成篇 | BufferQueue、HWC、RenderEngine(GL/Vulkan)、letterbox |
| Choreographer/VSync | 图形篇 / 深挖篇 | Vsync offset、FrameTimeline、JankType、DisplayModeDirector |
| View 测量 | 主篇 | measure/layout/draw 三阶段、requestLayout 冒泡 |
| ANR | 主篇 / 本篇 | 超时表、am_anr、/data/anr |
| LMKD/PSI | 系统基建篇 / 渲染合成篇 / 本篇 | PSI、cgroup OOM、Memory Limiter |
| ART GC | 深挖篇 / 端侧AI篇(A17) | CMC、分代、hiddenapi、oat/odex/vdex/art |
| Input 全链路 | 拓展篇 / 深挖篇 | InputDispatcher、split touch（呼应 QPR2 #516836306） |
| 安全世界 | 安全世界篇 / pKVM篇 / 智能系统篇 | TEE、Keystore2、AppFunctions getCallingUid 不可信 |
| 端侧 AI | 端侧AI篇 / AAOS篇 | LiteRT NPU delegate、端侧 LLM 量化 |
| 查缺补漏 | 拓展篇 / 总导航 / 速查卡 | 11 子系统速答表、易错红榜 TOP25 |

> 19 篇累计约 **138 专题**（前 18 篇 ~132 + 本篇 6 大实战场景）。主线（Binder/AMS/WMS/SF/ART/HAL/内核）→ 智能层（AppFunctions/Compose/端侧AI）→ 座舱（AAOS）→ 安全深水区（TEE/pKVM/Confirmation）→ 测试双雄（Robolectric/Ravenwood）+ 体系总导航 + 连击模拟考 + **本篇全链路排查实战**。

---

## 10. 复习节奏建议（收官后怎么用这套 19 篇）

1. **日常**：通勤看「速查卡」+「本篇易错红榜 TOP20」，保持肌肉记忆。
2. **临考 3 天**：按「连击模拟考」做口述；把本篇三条追问链（启动/渲染/内存）当脚本自问自答。
3. **现场**：遇到排查题，先报工具（Perfetto/bugreport/dumpsys），再报定界结论，最后给源码佐证与修复——这比背八股分数高得多。
4. **延伸阅读**：
   - AOSP：`frameworks/base/core/java/android/app/ActivityThread.java`、`ViewRootImpl.java`、`Choreographer.java`；`frameworks/native/services/surfaceflinger/`、`inputflinger/`；`frameworks/native/libs/binder/`。
   - 官方文档：ANR 诊断与修复（developer.android.com/topic/performance/anrs）、Perfetto 文档（perfetto.dev）、行为变更 17（about/versions/17）。
   - 实战：/ui.perfetto.dev 拖一份自己 App 的启动 trace，亲手量一次 App Launch 指标；故意在主线程加一次同步 Binder 调用，抓一份 ANR traces 读懂它。

---

> 本篇收尾语：前 18 篇让你「知道每个零件」，本篇让你「会修整台机器」。面试里，**能定界的人，比能背诵的人贵**。
