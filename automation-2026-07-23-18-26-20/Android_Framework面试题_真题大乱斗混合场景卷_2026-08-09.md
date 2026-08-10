# Android Framework 面试题 · 真题大乱斗（混合场景压轴卷）

> 日期：2026-08-09 ｜ 系列第 23 篇 ｜ 累计约 161 专题
> 主线 baseline：Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX，内核 GKI android14-6.1)
> 热点锚定：Android 17 stable 已于 2026-06-16 发布（代号 CinnamonBun）；A17 QPR2 Beta 2 于 2026-08-03 推送（build CP41.260701.006，代号切到 DEV，仅图标微调+稳定性修复，无行为变更，stable 预计 2026-12）；A18 桌面融合 / 跨设备 Handoff / EU DMA 开放 11 项 AI 能力仍处路线图中。

---

## 0. 为什么要有"真题大乱斗"这篇

前 22 篇把 Android Framework 拆成了 153 个**单点专题**：Binder / AMS·ATMS / WMS / SF / ART / HAL / 内核 / TEE / pKVM / 智能层 / 座舱 / 端侧 AI / 排查实战 / 源码级 code walk / Perfetto SQL…… 单点你会答，但**真实面试和真实线上故障从来不是单点**——考官一句"你这个冷启动 ANR 跟 Binder 有关系吗"，或者工单里"滑着滑着掉帧还发热被限后台"，都是 3~4 个子系统的混合体。

本篇把分散的八股焊成**混合场景真题**，每题遵循同一套路：

```
[现象/考官提问] -> [定界: 用哪个子系统先下手] -> [底层原理 + AOSP 源码落点]
   -> [易错点(红榜)] -> [高频追问链] -> [延伸阅读(回指前 22 篇)]
```

> 约定：文中所有 `.java/.cpp/.aidl` 路径默认是 **Android 14 AOSP (android-14.0.0_rXX)**；涉及 A17 新增项会显式标注 `[A17]`。

---

## 场景一：冷启动偶发 ANR —— 启动流程 × Binder × AMS × 主线程阻塞

**现象 / 考官提问**
> 一个千万 DAU 的 App，冷启动后约 1/200 概率 ANR。/data/anr 里主线程栈顶是 `at android.os.BinderProxy.transact0(Native method)`，再往上是 `ActivityThread.handleBindApplication` 里某个第三方 SDK 的初始化。为什么主线程调一次 Binder 就 ANR？怎么根治？

**定界**
先用 event log 看 ANR 类型：`am_anr` 的 reason 通常是 `Input dispatching timed out` 或 `executing service`。栈顶 `BinderProxy.transact0` 说明主线程**卡在等一次跨进程 Binder 调用的返回**，而不是卡在本地计算。下一步要区分两件事：
1. 卡在"等待对端执行"（对端 binder 线程池满 / 对端本身慢）；
2. 卡在"本进程 binder 线程没起来 / 主线程自己发起同步调用"。

**底层原理 + 源码落点**
- 冷启动主链路：`ActivityThread.main()` -> `Looper.loop()` 起来后，`handleBindApplication()` 做 `installContentProviders()`（`frameworks/base/core/java/android/app/ActivityThread.java`）。**ContentProvider 的 `onCreate()` 在 `Application.onCreate()` 之前执行**，这是经典"前置坑"——很多 SDK 喜欢在 CP 里偷跑初始化，把这些初始化全部塞进了主线程且早于你自己的 Application。
- `installContentProviders` 内部对每个 CP 调 `ActivityThread.installProvider` -> 通过 Binder 向 `AMS.getContentProviderImpl()`（`frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java`）取 provider。如果 provider 在另一个进程（比如 `settings` / `system` / 多进程拆分），这就是一次**跨进程同步 Binder 调用**，主线程阻塞等返回。
- Binder 线程池：每个进程默认上限约 15（`ProcessState` 在 Zygote 启动时经 `setThreadPoolConfiguration` 设定，`frameworks/native/libs/binder/ProcessState.cpp`）。如果此时 system_server 的 binder 线程正被其他客户端打满，你的同步调用就会在**对端排队**——你主线程的阻塞是"对端执行慢"的连锁反应。
- 同步屏障与消息队列：ANR 计时是从 `InputDispatcher` 派发输入事件开始（5s），而主线程被 Binder 同步调用占住时，**连同步屏障后的异步消息都排不进去**，UI 渲染/输入响应全卡。注意 A17 把 `MessageQueue` 改成了 **lock-free** 实现（`frameworks/base/core/java/android/os/MessageQueue.java`），但这只优化了入队出队，不改变"主线程被同步 Binder 阻塞就 ANR"的事实。

**易错点（红榜）**
- 误以为"主线程调 Binder 一定 ANR"。错：只有**超过 ANR 超时（输入 5s / 前台 Service 20s / 后台 Service 200s）**才 ANR。偶发是因为对端偶发慢。
- 误以为"把 SDK 初始化挪到子线程就完事"。错：若 SDK 在 `ContentProvider.onCreate` 里跑，你根本控制不了线程——得先干掉那个 CP，或改 `android:initOrder` / 合并初始化。
- 混淆"阻塞在 binder 拷贝"和"阻塞在等对端返回"：前者是 driver 层面微秒级，后者才是秒级 ANR 元凶。

**高频追问链**
1. 怎么证明不是你的锅？-> 看 traces 里 main 线程 `binderTo` 指向哪个 pid/ tid（binder 线程名 `binder:xxxxx`），再抓 system_server 的 traces / Perfetto 看那个 binder 线程在干嘛。
2. 根治手段？-> Jetpack **App Startup** 合并 `ContentProvider`（把 N 个 CP 合成 1 个 `InitializationProvider`，在 `androidx.startup` 里声明 `Initializer`，可控顺序 + 可延后）；首屏必需同步初始化，非必需异步化；`ContentProvider` 能不写就不写。
3. 为什么 Android 不干脆禁止主线程 Binder？-> 历史包袱 + 大量系统 API 本身就是同步 Binder（`getSystemService`/`getPackageManager` 等），只能靠"快"和"异步化"治理。

**延伸阅读**：第 19 篇（全链路排查·冷启动/ANR）、第 20 篇（code walk·startActivity→首帧）、第 7 篇（A17 演进·Lock-free MessageQueue）。

---

## 场景二：A18 桌面模式窗口拖拽手势冲突 —— WMS/WM Shell × InputDispatcher × ActivityEmbedding × 多指

**现象 / 考官提问**
> A18 Desktop Mode（freeform 窗口）下，你的应用内部画布要双指缩放，但系统把双指判定成了窗口 resize 手柄拖拽。手势到底是谁先吃的？怎么让应用内手势不被系统抢走？

**定界**
手势路由是**先全局后局部**：Input 系统先问系统策略（导航栏、系统手势、窗口装饰如 resize handle），再派发给应用。所以双指"被系统吃"是因为它命中了 freeform 窗口的 resize 命中区，先于应用 View 拿到事件。

**底层原理 + 源码落点**
- Input 派发起点：`InputDispatcher::dispatchOnce()`（`frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp`）-> `InputDispatcherPolicyInterface::interceptMotionBeforeDispatching` 先把 motion 交给系统策略（WMS 的 `InputManagerCallback`）。
- 窗口管理策略（WM Shell）：freeform 窗口的 resize handle、caption bar 由 `DesktopTasksController`（`frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/desktopmode/DesktopTasksController.java`）通过 `WindowOrganizerController.applyTransaction()`（`frameworks/base/services/core/java/com/android/server/wm/WindowOrganizerController.java`）下发 `WindowContainerTransaction` 改变窗口形态。handle 命中区域是 window decoration 层画的，属于"系统窗口装饰"，优先级高于应用 surface。
- **三维辨析（本场景核心考点）**：
  - `WindowConfiguration.WINDOWING_MODE_FREE_FORM`：WM Shell 在 **task 级别**给窗口 freeform 属性，resize/move 由系统装饰处理 —— 这是"系统抢手势"的来源。
  - WM Shell 策略：是否启用 freeform、taskbar 行为，由 Shell 侧的 `DesktopTasksController` / `TaskbarController` 决定。
  - `ActivityEmbedding`（`androidx.window`）：是**应用侧分栏**，把同一 task 里的 Activity 拆成两个容器（Primary/Secondary），**不创建新窗口、不触发系统 resize handle**。它和 freeform 完全是两个维度，常被混淆。
- 多指拆分：Input 层 `InputDispatcher` 支持 split touch（`isSplit()`），但 resize handle 是单指/双指都可能在装饰层拦截，应用拿不到被系统装饰消费的那部分指针。

**易错点（红榜）**
- 把 `ActivityEmbedding`（应用分栏）当成"多窗口"，以为它会产生 freeform 窗口。错：Embedding 不产生新窗口，只是同 task 内布局切分。
- 以为 `onInterceptTouchEvent` 能拦住系统手势。错：系统装饰在 Input 派发阶段就消费了，应用 View 的 `onInterceptTouchEvent` 根本收不到那部分事件。
- 混淆 resize handle（系统装饰，在 window decoration surface）和应用内自定义手势区域。

**高频追问链**
1. 应用如何"圈地"不让系统手势抢？-> `View.setSystemGestureExclusionRects()`（边缘返回手势排除区）；`requestUnbufferedDispatch()`（需要原生事件的游戏/画布场景）。
2. Compose 里怎么等价实现？-> `Modifier.systemGestureExclusion()`；手势用 `pointerInput { detectTransformGestures { } }`，但注意它**没有 onClick 语义**（第 13 篇语义树坑）。
3. freeform 窗口最小化/最大化走哪条链路？-> `DesktopTasksController` -> `WindowOrganizerController.applyTransaction` -> `Task` 的 `WindowContainer` 形态变更，最终由 WMS `RootWindowContainer` 重新布局。

**延伸阅读**：第 22 篇（A18 桌面融合·freeform/WM Shell/ActivityEmbedding 三维辨析）、第 13 篇（Compose 接缝·三段 PointerEventPass 替代 onInterceptTouchEvent）、第 2 篇（WMS/SF 专题）。

---

## 场景三：跨设备 AI 助手调用 —— CDM × RPC Binder × 跨 VM getCallingUid 不可信 × 安全边界

**现象 / 考官提问**
> EU DMA 强制开放后，第三方助手可替换 `VoiceInteractionService` 并调用你的 `AppFunctions`。你的 Provider 侧用 `Binder.getCallingUid()` 做鉴权，为什么在跨设备场景会失效？正确的端到端鉴权怎么做？

**定界**
先看调用方"隔了几层世界"：同进程 → 跨进程（普通 Binder）→ 跨 VM（RPC Binder，如 AVF/pKVM）→ 跨设备（CDM + 网络）。每一层 `getCallingUid()` 的语义都不同，不能套用同一假设。

**底层原理 + 源码落点**
- AppFunctions Provider 运行在 `system_server`，经 `BIND_APP_FUNCTION_SERVICE` 受保护模式暴露（`packages/modules/AppSearch` + `AppsIndexerManagerService`）。客户端（助手 App）跨进程调用时，Provider 侧 `Binder.getCallingUid()` 拿到的是 **SYSTEM_UID**（不是调用方助手的 uid）——这是第 13 篇的经典坑：**跨进程 Binder 的 getCallingUid 不可信（拿到的是中间人 uid）**。
- 跨设备 + CDM：`CompanionDeviceManager`（`frameworks/base/core/java/android/companion/CompanionDeviceManager.java`）的持久 `Association` 是系统级角色权限，`CompanionDeviceManagerService`（`frameworks/base/services/core/java/com/android/server/companion/`）做关联校验。A17 QPR2 把"锁屏屏幕自动化"权限从无障碍分流到 companion 角色，进一步收紧。
- 跨 VM（RPC Binder）：走 `RpcSession`/`RpcServer`（`frameworks/native/libs/binder/ndk/` + `RpcTransport`），与内核 Binder 有六点差异，**关键差异是"跨 VM getCallingUid 不可信"**——VM 边界切断了 uid 的硬件背书（第 12 篇）。
- 安全边界对照（传输中 vs 使用中）：跨设备传输走 **AES-GCM 加密**（在传输层）；"使用中"加密靠 TEE/Trusty 或 pKVM protected VM（第 11/12 篇）。但注意 **采集链路（麦克风/相机）在 host 普通世界，不受 protected VM 保护**——这是 AISeal 精确边界（第 12 篇）。

**易错点（红榜）**
- 把"跨进程不可信"和"跨设备不可信"当成同一回事，但根因不同：跨进程是中间人 uid（system_server 代发），跨设备/跨 VM 是 uid 在边界处根本无法硬件背书。
- 以为 CDM 关联 = 鉴权完成。错：关联只是"设备关系"，每次调用仍需应用层/系统层凭证校验。
- 以为 protected VM 内"一切安全"。错：采集阶段在 host，只有推理/存储在使用中受保护。

**高频追问链**
1. 不信任 getCallingUid 时怎么做端到端鉴权？-> 应用层 token（OAuth-like）+ `clearCallingIdentity()`/`restoreCallingIdentity()` 正确配对 + SELinux ctx 校验 + 必要时 key attestation。
2. 第三方助手替换 VoiceInteractionService 对 Framework 冲击？-> role 体系重画开放边界（第 22 篇 EU DMA 段），`RoleManager` 暴露更多可替换角色。
3. 跨 VM 调用怎么传递可信身份？-> 走 RKP/DICE 派生的 per-VM secret + 应用层会话凭证，不能依赖 kernel uid。

**延伸阅读**：第 22 篇（CDM/跨设备 handoff/安全边界）、第 13 篇（AppFunctions BIND_APP_FUNCTION_SERVICE + getCallingUid 坑）、第 12 篇（RPC Binder 跨 VM 不可信 + AISeal 边界）、第 11 篇（TEE/KeyMint 使用中加密）。

---

## 场景四：长列表滑动卡顿 + 发热降频 + 后台受限 —— Choreographer × SF/HWC × Thermal HAL × Power HAL/ADPF × Doze

**现象 / 考官提问**
> 电商详情页长列表，滑到一半帧率从 120 掉到 40，机身发热。Perfetto 里 `cpu_frequency` 从 2.8G 掉到 1.2G。是降频导致的卡顿吗？能关掉降频吗？

**定界**
先用 Perfetto `actual_frame_timeline_slice` 看 `jank_type` / `present_type` 定责到 **App 主线程 / RenderThread / SF / HWC**；同时看 `cpu_frequency` counter 确认降频。两件事相关但因果要分清：**降频是发热的果，不是卡顿的因**——要先看"降频前这一帧是不是本来就超预算"。

**底层原理 + 源码落点**
- 帧生产：`Choreographer` 收到 VSYNC 后，在 **ANIMATION** 回调跑动画/重组，在 **TRAVERSAL** 回调跑 measure/layout/draw（`frameworks/base/core/java/android/view/Choreographer.java`）。主线程超预算 → 丢帧。
- 帧消费：`SurfaceFlinger::onMessageRefresh()`（`frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp`）-> `HWC` 的 `setClientTarget`/`setOutputBuffer`，决定 **Overlay 合成（省电）vs GPU 合成（费电）**。overdraw 多 → 被迫 GPU 合成 → 更费电 → 更热 → 更易降频，形成负反馈。
- Thermal 链路：`Thermal HAL`（`hardware/interfaces/thermal/aidl`）上报温度 -> `Power HAL` 会话 -> 通过 `ThermalManagerService` 通知降频。`ADPF`（Android Dynamic Performance Framework）：应用可经 `PerformanceHintManager.createSession()`（`frameworks/base/core/java/android/os/PerformanceHintManager.java`）调 `updateTargetWorkDuration()` / `reportActualWorkDuration()` 给调度器喂 hint，**前提是你的实际耗时真实反映了工作负载**，调度器据此给 CPU 频点和核调度。
- 后台受限：发热 + 切后台后 `Doze` / `AppStandby` / `BackgroundActivityStart` 限制 / A16 `JobScheduler` 配额同时生效（第 5、19 篇），但这不是前台的锅。

**易错点（红榜）**
- "想关掉降频"——不可能也不可取：降频是物理热保护，绕开会烧机/触发 thermal shutdown。正确做法是**降耗**（减 overdraw、HWC Overlay 合成、精简主线程）。
- 把"发热导致降频"当"卡顿根因"。要先证明降频前帧预算就超标（Perfetto 看降频时间点 vs jank 时间点先后）。
- 以为 ADPF 是"要性能"的开关。错：它是"诚实上报实际耗时"的协作机制，骗调度器反而更糟。

**高频追问链**
1. 怎么把 GPU 合成变成 Overlay 合成？-> 减少叠加层数 / 减少透明 overdraw / 避免 `SurfaceView` 与 `View` 频繁交替，让 HWC 能直接合成硬件图层。
2. ADPF 实战注意？-> session 的 target 要随内容动态更新，长任务拆成多个 hint 段，避免一次性报超大耗时。
3. Perfetto 怎么一眼看是谁的锅？-> JOIN `actual_frame_timeline_slice` 与 `thread_state`/`cpu_frequency`，按 `jank_type` 分组统计 App/RenderThread/SF 各占多少（第 21 篇 SQL 范例）。

**延伸阅读**：第 19 篇（全链路排查·卡顿/发热掉速）、第 21 篇（Perfetto SQL·掉帧定责 + 电源/唤醒）、第 24 篇即第 4 篇（图形·HWUI/SF/HWC 合成）、第 5 篇（系统基建·Doze/JobScheduler/Power HAL）。

---

## 场景五：端侧 LLM 推理吃满内存被静默杀 —— 内存三路杀 × A17 分代 GC × LMKD/PSI × Memory Limiter × ApplicationExitInfo

**现象 / 考官提问**
> App 内嵌端侧小模型推理，跑到一半进程没了，没有任何崩溃日志。`ApplicationExitInfo` 里 `reason=REASON_MEMORY_LIMIT`，`description` 里有 `AnonSwap`。这是被谁杀的？和 LMK 是一回事吗？

**定界**
"被静默杀 + 无崩溃 + REASON_MEMORY_LIMIT" 是 **A17 Memory Limiter** 的特征，不是传统 LMK，也不是内核 OOM。先做三条杀路径辨析（第 12/19 篇核心）：

```
内核 OOM killer        -> 系统整体内存耗尽, 按 oom_score_adj 杀, reason=REASON_LOW_MEMORY
LMKD (PSI 触发)        -> 内存压力达阈值, 按 oom_adj 杀低优先级, reason=REASON_LOW_MEMORY
A17 Memory Limiter     -> 单应用内存超个体上限(含 AnonSwap), 静默杀, reason=REASON_MEMORY_LIMIT
```

**底层原理 + 源码落点**
- `ApplicationExitInfo.getDescription()` 新增死因 `MemoryLimiter:AnonSwap`（`frameworks/base/core/java/android/app/ApplicationExitInfo.java`，[A17]）。采集链路：`AppExitInfoTracker` 四路采集（`frameworks/base/services/core/java/com/android/server/am/AppExitInfoTracker.java`），`/data/system/procexitstore` 每 UID 存 16 条。
- Memory Limiter 与 LMKD / ART 分代 GC 协同（`MemoryLimiter` 在 `frameworks/base/services/core/java/com/android/server/am/`）：它按**应用级别内存上限**（不是滑动 PSI 阈值）做个体限流，越界即静默 kill；LMKD 仍按全局 PSI 工作；ART 分代 GC（A17，在 `art` apex 经 Mainline 热更）只回收 **Java 堆**，管不到 native。
- 端侧 LLM 内存特征（第 14 篇）：weight 常量化（INT4）后仍在 **native heap**，KV cache 也是 native；`onTrimMemory()` 自 A14 起仅剩 `TRIM_MEMORY_UI_HIDDEN` / `TRIM_MEMORY_RUNNING_CRITICAL` 两常量。ART 分代 GC 救不了 native 权重。

**易错点（红榜）**
- 以为 LMK 是唯一的低内存杀手。错：A17 多了 Memory Limiter 这条**个体上限**静默杀路径。
- 以为 GC 能回收模型权重。错：权重在 native heap，ART（含分代 GC）只管 Java 堆。
- 看到 `AnonSwap` 就以为是 swap 把机器拖慢。错：在 Memory Limiter 语境下它只是"匿名页+swap 计入了你这个应用的上限"的标识，死因是**超个体上限**。

**高频追问链**
1. 怎么在受限内存里跑 LLM？-> 模型量化（INT4 group-wise）+ 权重 mmap（不进 Java 堆）+ KV cache 流式 + 主动 `onTrimMemory` 释放 + 用 `LiteRT` NPU delegate 把算子卸载到 NPU（第 14 篇）。
2. 怎么区分三种死因？-> `ApplicationExitInfo.getReason()` + `getDescription()` + `getImportance()`（区分前台/后台），配合 `/data/anr` 与 `dmesg` 的 oom 日志。
3. A17 分代 GC 对端侧 AI 有什么实际好处？-> 减少 Java 层推理胶水代码的 GC 暂停，但**对 native 权重无能为力**。

**延伸阅读**：第 14 篇（端侧 LLM INT4/KV cache 量化 + LiteRT delegate）、第 19 篇（内存三路杀辨析）、第 12 篇（Memory Limiter vs LMKD vs 内核 OOM 三条杀路径）、第 13 篇（ApplicationExitInfo 死因全表）、第 3 篇（ART 对象头/分代 GC）。

---

## 场景六：Compose 列表卡顿 + 无障碍 AI Agent 操作慢 —— Compose 重组 × 主线程 × 语义树 ANI × 跨进程

**现象 / 考官提问**
> Compose 写的列表快速滚动掉帧；同时无障碍 AI Agent（ANI）操作这个列表时延很高。两个现象有关联吗？怎么同时优化？

**定界**
有关联，且都收敛到**主线程**：Compose 重组发生在主线程 `Recomposer`（挂在 `Choreographer` 的 **ANIMATION** 回调），而 ANI 查询语义树后在**目标 App 的 UI 线程（即主线程）执行**操作——主线程卡，两边一起慢。

**底层原理 + 源码落点**
- Compose 运行时：`Recomposer` 挂在 `Choreographer` ANIMATION 回调；`SlotTable` 用平坦 `IntArray` gap buffer；`Snapshot` 用 MVCC 版本链 + `readObserver`（"读取即订阅"）（`frameworks/support/compose/runtime`，第 13 篇）。**强跳过模式 = 判定 + 稳定性推断 + 编译器指标**：只有被标记为 stable 的参数 + 带 `@Stable`/不可变才能在父重组时跳过子节点。
- 不掉帧的前提：列表项 `Composable` 参数稳定（基本类型 / `@Immutable` 数据类 / `remember` 缓存）+ 正确 `key`。不稳定参数（如每次重组 new 一个 lambda / 非稳定 data class）会让整列表重组。
- 无障碍语义树：`Compose SemanticsNode` -> `AccessibilityNodeInfo` 映射给 ANI（`AccessibilityManager` / `androidx.compose.ui.platform` 的语义导出）。ANI 的 `ACTION_CLICK` **不是注入 MotionEvent**，而是走语义动作，且在**目标 App 主线程**执行——主线程卡顿直接拖慢 Agent 响应（第 13 篇）。
- 经典坑：`pointerInput { detectTapGestures { } }` 没有 `onClick` 语义，ANI 无法把它当可点击节点，Agent 自动化会失败。

**易错点（红榜）**
- 以为"Compose 一定比 View 快"。错：不稳定参数导致整列表重组时，Compose 可能比优化过的 RecyclerView 还慢。
- 以为 ANI 操作走 Input 注入。错：走语义动作，依赖语义树 + 主线程，所以性能与可访问性是一体的。
- 忘记 `key` / 用不稳定 data class 当列表项参数。

**高频追问链**
1. 怎么让 Agent 既能点又不掉帧？-> 用 `Modifier.clickable`（产出 onClick 语义）而非裸 `pointerInput`；列表项标 `@Immutable` + 正确 `key`；重组热点用 `derivedStateOf` / `remember`。
2. Compose 与 Framework 接缝坑？-> `AndroidComposeView` 在 View 树里只是一个 View；`WindowRecomposer` + `ViewCompositionStrategy` 泄漏坑；`LayoutNode -> RenderNode` **未绕过 HWUI**（第 13 篇六接缝）。
3. 如何量化 Compose 重组开销？-> 开 `CompositionTracer` / `RecompositionStats` + Perfetto 看 ANIMATION 回调耗时。

**延伸阅读**：第 13 篇（Compose 编译器插件 + 运行时 + 六接缝 + 语义树 + ANI）、第 19 篇（卡顿掉帧定界）、第 21 篇（主线程阻塞 SQL）。

---

## 场景七：端侧大模型推理进 protected VM + 密钥绑定 —— AVF/pKVM × KeyMint × TEE/Trusty × AISeal

**现象 / 考官提问**
> 把端侧推理搬进 pKVM protected VM（AISeal 模式），推理用的密钥来自 KeyMint，要对密钥做 attestation。怎么证明 VM 内的密钥没被篡改？AISeal 的"安全边界"到底包住什么？

**定界**
先分清"三层边界"（第 12 篇）：**PCC（Private Compute Core）/ Private AI Compute / AISeal**，以及它们各自的保护范围。再确认采集链路 vs 推理链路的边界差异。

**底层原理 + 源码落点**
- AVF 五层 + Microdroid：`packages/modules/Virtualization/`，protected VM 默认开 ~300MB RAM / ~16GB 加密存储，多租户 vsock，Rust host service + Java 系统服务。`DICE`/`BCC` 做 per-VM secret 派生（每个 VM 启动派生独立密钥）。
- KeyMint AIDL HAL：`hardware/interfaces/security/keymint`，经 `system/security/keystore2`（Rust）落到 TA；支持 **auth-bound key**（绑定指纹/HAT）、**key attestation**（X.509 OID `1.3.6.1.4.1.11129.2.1.17` + `RootOfTrust`），配合 `RKP`/`DICE` 做远程证明（第 11 篇）。
- AISeal 精确边界（第 12 篇）：**采集链路（麦克风/相机输入）在 host 普通世界，不受 protected VM 保护**；只有"推理计算 + 个人数据库存储"在使用中受保护。误以为"进 VM 就全安全"是最大误区。
- pKVM 威胁模型：`arch/arm64/kvm/hyp/nvhe/mem_protect.c` 的 stage-2 页表所有权状态机 / 内存捐赠 / host 自我降级；四短板：无早期启动 / 无安全外设 / 仅 ARM64 / 内存成本。

**易错点（红榜）**
- 以为 protected VM 内"一切安全"。错：采集阶段在 host。
- 以为 attestation 能证明"代码逻辑正确"。错：它证明"密钥在某个可信硬件环境生成且未被导出"，不证明业务逻辑无 bug。
- 把 PCC / Private AI Compute / AISeal 当同义词。错：三者保护范围和生命周期不同（第 12 篇）。

**高频追问链**
1. 如何端到端证明密钥未被篡改？-> RKP/DICE per-VM secret + key attestation 证书链 + `RootOfTrust` + 远程验证服务校验。
2. 为什么不用 TEE 而用 pKVM 跑大模型？-> TEE TA 代码量受控（TCB 小但扩展难），pKVM 可跑完整 Linux/Microdroid（灵活但内存成本高），二者威胁模型互补（第 11/12 篇对比）。
3. 跨 VM 调 KeyMint 走什么 Binder？-> RPC Binder（vsock 传输），且跨 VM getCallingUid 不可信（回到场景三）。

**延伸阅读**：第 12 篇（pKVM/AVF + AISeal 三层辨析 + 跨 VM Binder）、第 11 篇（TEE/Keystore2/KeyMint/attestation）、第 14 篇（端侧 AI 工程化）。

---

## 场景八：Perfetto 混合 SQL 实战 —— trace_processor 多表 JOIN 综合定位

**现象 / 考官提问**
> 一条线上卡顿工单，要你用**一条 SQL**同时回答三个问题：主线程卡在哪个 binder 调用？该调用对端执行花了多久？当帧是不是 jank？能写出来吗？

**定界 / SQL**
这是第 21 篇 Perfetto SQL 范例的"综合升级版"——把 `binder_transaction` × `thread_state` × `actual_frame_timeline_slice` 三表 JOIN。先明确三表各自语义：
- `binder_transaction`：一次事务，含 `txn_id`/`reply_id`、`is_oneway`、对端 pid/tid、`buf` 大小（注意它记的是"事务本身"，kernel 拷贝 vs 对端执行要分开看）。
- `thread_state`：线程状态切片，`state='R'`(running)/`'S'`(sleep)/`'D'`(io wait)/`monitor_contention` 量化锁竞争。
- `actual_frame_timeline_slice` / `expected_frame_timeline_slice`：按 `frame_number` + `upid` JOIN，`jank_type`/`present_type` 定责到 App/RenderThread/SF/HWC。

```sql
-- 主线程卡在哪个 binder 调用 + 对端执行耗时 + 当帧 jank 一锅出
SELECT
  f.frame_number,
  f.jank_type, f.present_type,
  b.tx_code, b.dest_pid, b.dest_tid,
  (b.end_ts - b.start_ts) AS binder_total_ns,
  ts.state AS caller_state
FROM actual_frame_timeline_slice f
JOIN binder_transaction b
  ON b.upid = f.upid
  AND b.start_ts BETWEEN f.start_ts AND f.end_ts
LEFT JOIN thread_state ts
  ON ts.utid = b.utid
  AND ts.ts BETWEEN b.start_ts AND b.end_ts
WHERE f.upid = (SELECT upid FROM process WHERE name='com.xxx.app')
  AND f.jank_type != 'None'
ORDER BY binder_total_ns DESC
LIMIT 20;
```

**易错点（红榜）**
- 混淆 `binder_transaction` 的"事务总耗时"和"对端执行耗时"：事务总耗时含本进程等待 + 对端执行；要单独看对端需追 `reply_id` 对应的事务。
- 混淆 `expected` vs `actual` frame：掉帧看 `actual_frame_timeline_slice` 的 `jank_type`，`expected` 只是理想时间表。
- 误以为 `jank_type='Self'` 就是 App 的锅：要配合 `present_type` 和 RenderThread/SF 切片细分（第 21 篇）。

**高频追问链**
1. 怎么用 Perfetto 证明"是系统服务拖慢了我，不是我的锅"？-> 抓 system_server 的 Perfetto，看那个 dest_pid 的 binder 线程在干嘛；若对端自己 `monitor_contention` 严重，就是你被它拖累。
2. 如何扩展成"发热 + 卡顿"联合分析？-> 再 JOIN `cpu_frequency` counter 与 `binder_transaction`，看 jank 是否集中在降频区间（呼应场景四）。
3. trace 太大怎么抓线上？-> 用 ring buffer + 短时长 + 按需开 `binder`/`sched`/`frame` 三类数据源，避免全量。

**延伸阅读**：第 21 篇（Perfetto SQL 范例库·掉帧/Binder/主线程阻塞）、第 19 篇（全链路排查·定界四步法）。

---

## 9. 跨场景易错红榜 TOP18（压轴速记）

1. 主线程调 Binder 不必然 ANR，超时才 ANR（场景一）。
2. ContentProvider.onCreate 早于 Application.onCreate（场景一坑）。
3. Binder 线程池满会连锁拖慢主线程（场景一）。
4. freeform resize handle 是系统装饰，先于应用 View 吃手势（场景二）。
5. ActivityEmbedding 不创建新窗口，与 freeform 是两个维度（场景二）。
6. 跨进程 getCallingUid 拿到 SYSTEM_UID；跨 VM/跨设备不可信（场景三，呼应 12/13 篇）。
7. CDM 关联 ≠ 鉴权完成（场景三）。
8. 降频是发热的果不是卡顿的因（场景四）。
9. ADPF 是诚实上报耗时，不是要性能开关（场景四）。
10. 内存三路杀：内核 OOM / LMKD(PSI) / A17 Memory Limiter 个体上限（场景五，呼应 12/19 篇）。
11. ART 分代 GC 只管 Java 堆，救不了 native 模型权重（场景五）。
12. Compose 不稳参数 = 整列表重组，未必比 RecyclerView 快（场景六）。
13. ANI 操作走语义动作 + 主线程，非 Input 注入（场景六）。
14. pointerInput 无 onClick 语义，Agent 点不到（场景六坑）。
15. protected VM 只保护推理/存储，采集链路在 host（场景七）。
16. key attestation 证明密钥环境可信，不证明业务逻辑正确（场景七）。
17. Perfetto 里 expected≠actual，jank 看 actual（场景八）。
18. Binder 事务总耗时含对端执行，要追 reply_id 才分得清（场景八）。

---

## 10. 三条高频追问链（跨子系统综合）

**链 A：一次冷启动 ANR 能挖多深？**
启动流程(ActivityThread.handleBindApplication) -> ContentProvider 前置坑 -> 跨进程 Binder 阻塞 -> 对端 system_server 线程池 -> ANR 超时计时 -> 同步屏障失效 -> 根治(Jetpack Startup 合并 CP / 异步化) -> A17 Lock-free MessageQueue 改了什么没改什么。

**链 B：一个手势从手指到窗口变形走过哪些世界？**
InputDispatcher 派发 -> 系统策略拦截(导航/resize handle) -> WM Shell DesktopTasksController -> WindowOrganizerController.applyTransaction -> WindowContainer 形态变更 -> WMS 重新布局 -> SurfaceFlinger 合成上屏；同时 ActivityEmbedding 在应用侧另开一路分栏。

**链 C：一份个人数据从采集到端侧推理的安全边界？**
麦克风采集(host 普通世界, 不受保护) -> 经 CDM/加密传输 -> 进 pKVM protected VM 推理(AISeal) -> KeyMint auth-bound 密钥 + attestation 证明 -> 结果回传。每一跳的"可信 uid"假设都不同（跨进程/跨 VM/跨设备 getCallingUid 不可信）。

---

## 11. AOSP 源码路径清单（本篇引用）

| 子系统 | 路径（android-14.0.0_rXX） |
| --- | --- |
| 启动/Application | `frameworks/base/core/java/android/app/ActivityThread.java` |
| AMS | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` |
| Binder native | `frameworks/native/libs/binder/ProcessState.cpp` / `IPCThreadState.cpp` |
| MessageQueue | `frameworks/base/core/java/android/os/MessageQueue.java` [A17 lock-free] |
| Input 派发 | `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp` |
| WM Shell Desktop | `frameworks/base/libs/WindowManager/Shell/.../desktopmode/DesktopTasksController.java` |
| WindowOrganizer | `frameworks/base/services/core/java/com/android/server/wm/WindowOrganizerController.java` |
| Choreographer | `frameworks/base/core/java/android/view/Choreographer.java` |
| SurfaceFlinger | `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp` |
| Thermal HAL | `hardware/interfaces/thermal/aidl/` |
| Power/ADPF | `frameworks/base/core/java/android/os/PerformanceHintManager.java` |
| Memory Limiter | `frameworks/base/services/core/java/com/android/server/am/MemoryLimiter.java` [A17] |
| AppExitInfo | `frameworks/base/services/core/java/com/android/server/am/AppExitInfoTracker.java` |
| AppFunctions | `packages/modules/AppSearch/` + `AppsIndexerManagerService` |
| CDM | `frameworks/base/core/java/android/companion/CompanionDeviceManager.java` |
| AVF/pKVM | `packages/modules/Virtualization/` / `arch/arm64/kvm/hyp/nvhe/mem_protect.c` |
| KeyMint | `hardware/interfaces/security/keymint` / `system/security/keystore2` |
| Compose runtime | `frameworks/support/compose/runtime/` (Recomposer / SlotTable / Snapshot) |
| ANI | `frameworks/base/core/java/android/accessibilityservice/` + `AccessibilityManager` |

---

## 12. 23 篇交叉索引（前 22 篇 -> 本篇）

| 篇 | 主题 | 本篇衔接 |
| --- | --- | --- |
| 01 主篇 | Handler/Binder/AMS/WMS/View/启动/优化 | 场景一/二/四 底层 |
| 02 拓展 | Input/PMS/ART/SystemUI/折叠屏/SELinux/OTA/Perfetto | 场景二 Input |
| 03 深挖 | ART 对象头/CMC GC/Rust Binder/Input 多指/VSync/Camera/Audio/GKI | 场景一 Binder 线程 |
| 04 图形多媒体通信 | HWUI/SF/HWC/多刷新率/MediaCodec/Thermal/Power/Telephony/WiFi/BT | 场景四 合成/降频 |
| 05 系统基建 | 16KB/ClassLoader/权限/Keystore2/AVB/Vold/logd/RRO/Doze/JobScheduler | 场景四 受限 |
| 06 端侧AI/A17 | NNAPI/CarService/Vulkan/ART 产物/virtual A/B | 场景五/七 |
| 07 A17 新雷区 | Lock-free MQ/分代 GC/hiddenapi/ProfilingManager/后台音频/NFC/Media3/LLM | 场景一/五 |
| 08 渲染合成/A17安全 | RenderEngine/Codec2 plugin/Memory Limiter/Keystore限额/CarService/ART镜像 | 场景四/五 |
| 09 兼容性框架 | platform_compat/BAL/Bubbles/Handoff/Pointer Capture/隐藏API流水线 | 场景二/三 |
| 10 TEE | Trusty/Keystore2/KeyMint/Gatekeeper/Attestation/Widevine/ION->DMA-BUF | 场景七 |
| 11 pKVM/AVF | pKVM/AVF/AISeal/跨VM Binder/eBPF/Ravenwood | 场景三/七 |
| 12 智能系统 | AppFunctions/AppSearch/Compose 编译器+运行时/APK签名/ExitInfo/语义树 | 场景三/六 |
| 13 端侧AI/座舱 | LiteRT NPU/LLM量化/CarService/StrongBox/Protected Confirmation/AVF编译 | 场景五/七 |
| 14 收官补遗 | CarService 电源状态机/LLM 量化实操 | 场景五 |
| 15 速查卡 | 15 篇知识地图 | 全局 |
| 16 连击考 | 考官连击形态 | 本篇升级为"混合场景连击" |
| 17 全链路排查 | 冷启动/卡顿/ANR/内存/发热/Binder | 场景一/四/五/八 |
| 18 code walk | startActivity->首帧/Binder 事务 | 场景一/二 |
| 19 Perfetto SQL | 启动/掉帧/主线程/Binder/电源 SQL | 场景四/八 |
| 20 A18 桌面融合 | freeform/WM Shell/ActivityEmbedding/CDM/Handoff | 场景二/三 |
| 21 系列累计 | 153 专题闭环 | — |

> 至此系列 23 篇 / 约 161 专题：单点专题(1~14) + 复习形态(15~16) + 实战形态(17~19) + 源码形态(20~21) + 综合形态(22~23)。**真·未覆盖角度所剩**：专项"真题大乱斗 vol.2"（更刁钻的多子系统叠加）、Perfetto SQL 扩充（input 延迟/GPU 计数器/battery 耗电细分）、KMP/skiko 非 Android target 运行时深水区、ART 镜像 odex 布局优化实战。

---

*本篇为每日自动化产出，落盘工作区根目录（文件名带日期），并推送飞书云文档 AOSP 文件夹 + bot 私聊链接。复习建议：先通读 8 个场景的"定界"思路，再回头补各篇源码落点；面试时按"现象->定界->原理->易错->追问"五段式口述，比背单点八股得分高得多。*
