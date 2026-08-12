# Android Framework 面试题 · 真题大乱斗 vol.2（第 27 篇 · 更刁钻跨子系统叠加压轴卷）

> 日期：2026-08-12 ｜ 系列第 27 篇 ｜ 累计约 183 专题
> 主线 baseline：Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX，内核 GKI android14-6.1)
> 热点锚定：Android 17 stable 已于 2026-06-16 发布（代号 CinnamonBun）；**A17 QPR2 Beta 2 于 2026-08-03 推送**（build CP41.260701.006，代号 DEV，仅图标微调 + 稳定性修复 + Pixel 6/6 Pro EOL 退出，无行为变更，stable 预计 2026-12）；A18 桌面融合 / 跨设备 Handoff / EU DMA 开放 11 项 AI 能力仍处路线图。无新增 Framework 破坏性变更 → **混合场景真题**是压轴中的压轴，本篇把前 26 篇的散点八股焊成「更刁钻、多子系统叠加更深」的 8 道压轴综合题。

---

## 0. 为什么还要有 vol.2

第 23 篇「真题大乱斗」已经做了 8 个跨子系统场景（冷启动ANR×Binder×AMS / A18桌面手势×WMS×Input / 跨设备AI×CDM×RPC Binder / 卡顿发热×Choreographer×SF×Thermal / 端侧LLM静默杀×三路杀 / Compose卡顿×ANI / 推理进pKVM×KeyMint / Perfetto混合SQL）。但复盘发现：那 8 道偏「两个子系统各说各话」，真正面试压轴考的是 **三个以上子系统同时叠加 + 时序竞态 + 隐蔽根因**（traces 上看不到明显主线程阻塞、没有 ANR 弹窗、没有 crash 栈）。

本篇 8 道全部是「叠加更深、根因更隐蔽」的形态，且刻意避开了 #23 已写的 8 个，互为递进：

```
#23（已写，偏两两串联）                  本篇 vol.2（新增，三+子系统叠加 + 时序竞态 + 隐蔽根因）
--------------------------------        ------------------------------------------------
冷启动ANR×Binder×AMS                   冷启动 + 16KB页 + dex2oat重编 + PinnerService（启动期I/O炸弹）
A18桌面手势×WMS×Input                  折叠屏旋转 + 多指 + ActivityEmbedding + Surface销毁时序（input丢+黑屏竞态）
跨设备AI×CDM×RPC Binder               跨VM RPC Binder + KeyMint + AVF隔离编译odrefresh + 端侧LLM（安全边界+启动成本）
卡顿发热×Choreographer×SF×Thermal      Thermal降频 + ADPF + RenderThread + 后台FGS受限 + Job配额（掉帧+任务不跑叠加）
端侧LLM静默杀×三路杀×A17 MemoryLimiter  MemoryLimiter + LMKD/PSI + ART分代GC + 大图native内存（三路杀+GC压力）
Compose卡顿×ANI语义树                  Compose重组 + ANI/A11y Agent + Recomposer挂Choreographer ANIMATION回调（互锁）
推理进pKVM×KeyMint attestation         同#8但更深：odrefresh首次重编 + pVM启动成本 + 安全边界失效
Perfetto混合SQL三表JOIN                Binder线程池耗尽 + oneway排队 + 对端LMK杀 + linkToDeath + 死锁（ANR无主线程阻塞）
```

> 约定：`.java/.cpp` 路径默认 **Android 14 AOSP (android-14.0.0_rXX)**；内核路径 **GKI common-android14-6.1**；`[A17]` 显式标注 Android 17 新增项。每题按「现象 → 定界 → 原理 + AOSP 路径 → 易错红榜 → 追问链 → 延伸阅读」六段式，可直接当口述模板。

---

## 场景一：折叠屏旋转 + 多指手势 + ActivityEmbedding + Surface 销毁时序（input 丢 + 黑屏竞态）

**现象**
> 折叠屏「展开/合拢」瞬间用户正用双指拖拽，App 偶发：①触摸直接丢（拖拽卡住）；②黑屏半秒后恢复；③ActivityEmbedding 分栏错位 / 一侧黑。复现率随旋转速度升高。

**定界（先分锅）**
- input 丢：责任在 **InputDispatcher 系统侧 vs App 主线程** 的窗口 Token 重连时序，不是 App 自己的 onTouchEvent 逻辑。
- 黑屏：责任在 **Surface 在 rotation 中被 destroy/recreate 的竞态**，WindowManager 与 SurfaceFlinger 的 buffer 状态不一致。
- 分栏错位：ActivityEmbedding 的 TaskFragment 与 DisplayContent 的 orientation/size 变更回调顺序问题。

**原理 + AOSP 源码落点**
1. 旋转入口：`DisplayContent.onConfigurationChanged` -> `updateOrientation()` -> `WindowManagerService.updateRotation` 触发 `SurfaceControl` 事务。旋转时 WMS 会 **销毁旧 Surface、创建新 Surface**，通过 `ViewRootImpl.relayoutWindow` 走 `IWindowSession.relayout` 拿到新的 `Surface`（见第 20 篇 code walk §4）。
2. input 重连：`InputDispatcher` 持有每个窗口的 `InputWindowHandle` 与 `InputChannel`。旋转时 Window Token 变化，`InputDispatcher::updateWindowInfo` 重新关联；**多指（split touch）场景下 `InputState` 里正在追踪的 pointer 在 Token 切换瞬间若未完成 dispatch，会被丢弃**——这正是 A17 QPR2 Beta 1 修复清单里的 #516836306「多指拖拽丢触摸」现场。
3. ActivityEmbedding 叠加：`TaskFragment` 在 `WindowOrganizerController` 下发 `WindowContainerTransaction` 时分栏；旋转时 `TaskFragment` 的 bounds 重算晚于根 Activity 的 relayout，导致一侧用旧 bounds 拿到了已销毁的 Surface → 黑屏。
4. 关键竞态：`SurfaceFlinger::onMessageInvalidate` 消费 buffer 队列时，若 App 端 `dequeueBuffer`/`queueBuffer` 与 WMS 的 `relayout`（销毁旧 Surface）在**同一帧窗口**交错，会出现 App 往已失效的 `BufferQueue` 投递 → SF 丢弃该帧 → 黑屏。

**易错红榜**
- 错：以为是 App `onTouchEvent` 没处理。`onTouchEvent` 根本没被调到——是系统侧 input channel 重连时丢的。
- 错：以为黑屏是内存不足被 LMK 杀。LMK 杀进程是整个进程没了，不会「黑半秒恢复」。
- 错：用 `requestDisallowIntercept` 解决旋转丢触摸。旋转时的丢和 `disallow` 无关（`disallow` 只作用于 DOWN 之后的 move/cancel 拦截，见第 26 篇）。
- 错：在 `onConfigurationChanged` 里同步重建 View 树。旋转由 WMS 驱动，App 侧应等 `ViewRootImpl` 完成 relayout 再操作，否则抢跑触发竞态。

**追问链**
1. 如何用 Perfetto 定界 input 丢是系统侧还是 App 侧？（答：`android_input_events` 四段延迟——dispatch vs handling vs ack vs end_to_end，见第 25 篇；若 dispatch→handling 之间 gap 巨大说明 App 主线程卡，若 dispatch 本身没产生说明系统侧丢。）
2. ActivityEmbedding 与 freeform（A18 Desktop Mode）在旋转时序上有何差异？（答：freeform 窗口 bounds 由 WM Shell 策略管，旋转时窗口不一定跟随 display 旋转，见第 22 篇。）
3. 怎么在代码里防御这种竞态？（答：监听 `ViewTreeObserver.OnWindowAttachListener` / `SurfaceControl` 事务回调，确保 Surface 可用后再恢复手势；用 `WindowInsets` 动画而非硬重建。）

**延伸阅读**
- `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp`（enqueueInboundEventLocked / updateWindowInfo）
- `frameworks/base/services/core/java/com/android/server/wm/DisplayContent.java`（handleConfigurationChanged）
- `frameworks/base/core/java/android/view/ViewRootImpl.java`（relayoutWindow / Surface 生命周期）
- AOSP bug #516836306（QPR2 Beta 1 多指拖拽丢触摸修复）

---

## 场景二：Binder 线程池耗尽 + oneway 排队 + 对端 LMK 被杀 + linkToDeath + 死锁（ANR 无主线程阻塞）

**现象**
> App 偶发 ANR，但 `/data/anr/traces.txt`（或 `am hang` 抓的栈）里主线程**没有明显的死循环或 sleep**，只是停在某个 `Binder` 调用上。重启后即好，难以稳定复现。

**定界（这是最隐蔽的一类）**
- 主线程停在 `BinderProxy.transact` → 它**在等内核 `binder_transaction` 返回 `BR_REPLY`**，而对端进程要么线程池满了、要么已经被 LMK 杀但死亡通知没及时送达。
- 关键点：**这不是 App 自己的死锁**，是跨进程 + 线程池 + 进程死亡通知三者叠加的「跨进程死锁」。

**原理 + AOSP 源码落点**
1. 线程池上限：`ProcessState::startThreadPool()` 先 spawn **1** 个主 binder 线程；之后每次 `IPCThreadState::joinThreadPool` 处理事务时，若发现「当前 pool 内空闲线程为 0 且还有事务在等」，内核回 `BR_SPAWN_LOOPER`，用户态再 spawn，直到 **上限 15**（`ProcessState::MAX_THREADS = 15`，见第 26 篇）。
2. oneway 也会排队（反直觉）：`oneway` 只表示「调用方不等 `BR_REPLY`」，但**对端线程池满时，事务在 `binder.c` 的 `binder_transaction` 里仍要等一个空闲线程 `BR_TRANSACTION` 才被派发**。所以对端线程池满 → oneway 调用在对端排队，调用方虽不等回包，但若调用方主线程又在同步等这个 oneway 的「发送完成」或后续依赖，就会卡。
3. 对端被 LMK 杀：`LowMemoryKiller` 选中对端进程，`binder.c` 在 `binder_release` 里会向所有引用它的进程发 `BR_DEAD_BINDER`，用户态经 `IPCThreadState::executeCommand` -> `BpBinder::sendDeathNotice` -> 调 `linkToDeath` 注册的 `DeathRecipient.binderDied`。
4. **隐蔽死锁根因**：调用方 App 的主线程（binder 线程之一）正 `talkWithDriver` 死等对端的 `BR_REPLY`；而对端的 `BR_DEAD_BINDER` 死亡通知需要调用方的**另一个 binder 线程**来处理，但调用方的 binder 线程池此刻全被「等对端回包」占满 → 死亡通知得不到线程处理 → 主线程永远等不到回包也等不到死亡通知 → **经典跨进程死锁，traces 上看不到任何锁，只看到卡在 transact**。

**易错红榜**
- 错：认为 oneway 一定不阻塞调用线程。oneway 不阻塞「等回包」，但可能阻塞「发送」且依赖它的后续逻辑会卡。
- 错：看到主线程停在 `transact` 就认定是「自己代码慢」。这是跨进程等待，根因在对端。
- 错：以为对端进程死了 `binderDied` 立刻回调。它要等本端有空闲 binder 线程，线程池满时可能永远等不到。
- 错：用 `getCallingUid()` 在对端判断调用方。跨这种死锁场景时 UID 可能已不可信（呼应第 12/13 篇跨 VM 不可信）。

**追问链**
1. 怎么从 `binder_transaction` 日志（`/sys/kernel/debug/binder` 或 `binder_transaction_log`）看出是「等对端线程」还是「等对端回包」？（答：看 to_thread / need_reply 标志与对端 proc 的 `requested_threads`/`ready_threads` 计数。）
2. 如何破这个跨进程死锁？（答：主线程不要同步等跨进程结果；用异步回调 + 超时；或对端服务做成 `foregroundService` 提优先级避免被 LMK 选；或调用方限制同步 binder 调用深度。）
3. `BinderCallsStats` / `binder_transaction_log` 怎么用来事后溯源？（答：第 12 篇讲了 binder spam 检测溯源到 `BinderCallsStats`；这里用 transaction 等待时长 + 对端 PID 定位卡点。）

**延伸阅读**
- `frameworks/native/libs/binder/ProcessState.cpp`（startThreadPool / MAX_THREADS）
- `frameworks/native/libs/binder/IPCThreadState.cpp`（talkWithDriver / waitForResponse / executeCommand BR_DEAD_BINDER）
- `drivers/android/binder.c`（binder_transaction / binder_release / BR_SPAWN_LOOPER）
- `frameworks/native/libs/binder/BpBinder.cpp`（linkToDeath / sendDeathNotice）

---

## 场景三：冷启动 + 16KB 页面 + dex2oat 重编 + PinnerService（启动期 I/O 炸弹）

**现象**
> 在 Android 15（默认 16KB 页面）设备，App **首次安装 / OTA 升级后第一次冷启动极慢**（比后续慢数秒到十几秒），`bindApplication` 阶段吃满主线程 + 大量 I/O。

**定界**
- 不是业务逻辑慢，是 **启动时 ART 被迫重新 `dex2oat` 编译**，且 16KB 页使旧的 4KB 对齐 `.odex` 失效。
- `PinnerService` 预热 boot image 的 I/O 也叠加在启动关键路径上。

**原理 + AOSP 源码落点**
1. 16KB 页面强制：Android 15 起内核页大小默认 16KB（`ro.product.page_size = 4096` 的机器被要求迁移）；Android 14 已支持（开发者选项可切）。`.odex` / `.vdex` 文件里 code 段按 4KB 对齐生成的产物在 16KB 内核上**映射对齐失效**，ART 拒绝直接 `mmap` 执行，回退到解释或重编。
2. 重编触发：`PackageManagerService` 在扫描/安装时通过 `Installer.dexopt` -> `dexopt`/`dex2oat` 触发编译（`--compiler-filter` 默认 `speed-profile` 或 `verify`）。首次启动若无兼容 odex，ART 在 `bindApplication` 阶段由 `PMS`/`dexopt` 触发 **bg-dexopt 之外的启动期重编**，直接吃主线程 I/O。
3. `PinnerService`（`frameworks/base/services/core/java/com/android/server/PinnerService.java`）：把 boot image、`/system/framework` 等关键 dex/oat **pin 进内存**（`mlock` 类语义）避免被回收，减少运行时 page fault。但它启动期 pin 文件本身是一次性 I/O，叠加在冷启动关键路径。
4. 与 Profile 的关系：第 24 篇讲的「基线 Profile + 云 Profile」在 OTA 后可能**版本不匹配** → 触发重编；`ProfileSaver` 的本地 profile 也需重新收集才有意义。

**易错红榜**
- 错：以为「装完就有 odex，启动就该快」。16KB 不兼容会让已有 odex 失效，必须重编。
- 错：把所有启动慢都归到 `ContentProvider` 前置（第 20 篇讲的真坑）。本场景是 dex2oat I/O，两者可叠加，要分别用 Perfetto 的 `android_startup` + `slice`(installContentProviders) + cpu 频率/disk I/O 区分。
- 错：认为 16KB 只影响内存省电。它对 **冷启动编译产物兼容性** 是硬伤，是大版本升级首启动慢的头号元凶。
- 错：把 `PinnerService` 当成「越 pin 越快」。pin 过多反而挤占可用内存，且启动期 pin 是一次 I/O 成本。

**追问链**
1. 如何确认是 dex2oat 重编导致的慢？（答：Perfetto 抓 `dex2oat`/`odrefresh` slice + `android_startup` 的 bindApplication 时长 + disk I/O counter；`dumpsys package` 看 `primaryCpuAbi`/`compileFilter` 与 odex 路径是否存在且对齐。）
2. `dexpreopt` 预编译（构建期）能绕过这个问题吗？（答：能减少运行时重编，但 OTA 后 system 映像变化仍可能让预编译失效；第 24 篇讲过 dexpreopt/OTA 失效。）
3. 16KB 与 A17 的 16KB 强制升级路线图如何衔接？（答：Android 15 默认 16KB，A17 继续收紧，targetSdk 高的 App 必须 16KB 兼容，否则装不上/启动崩。）

**延伸阅读**
- `art/dex2oat/dex2oat.cc`（编译入口 / --compiler-filter）
- `frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java`（dexopt 触发）
- `frameworks/base/services/core/java/com/android/server/PinnerService.java`
- `art/runtime/gc/...` 与 `oat_file.cc`（odex 映射对齐校验）

---

## 场景四：Compose 重组 + ANI / A11y Agent + Recomposer 挂 Choreographer ANIMATION 回调（互锁）

**现象**
> 开启无障碍 UI 自动化 / AI Agent（AccessibilityService 驱动）时，App 主线程明显卡顿，Agent 的查询/点击也变慢，二者**互相拖慢**，关掉 Agent 就正常。

**定界**
- 不是纯 Compose 重组慢，是 **ANI（无障碍节点信息）查询跨进程、且跳到目标 App 的 UI 线程执行** —— 主线程被 Agent 查询占用；而 Compose 的 `Recomposer` 挂在 `Choreographer` 的 **ANIMATION** 回调，和 View traversal 的 **TRAVERSAL** 回调同帧先后执行，重组耗时拉长进一步拖慢下一帧，形成互锁。

**原理 + AOSP 源码落点**
1. Recomposer 回调位置：`androidx.compose.runtime.Recomposer` 通过 `AndroidUiDispatcher` 把自己安排在 `Choreographer` 的 **CALLBACK_ANIMATION** 档；而传统 View 的 `doTraversal` 在 **CALLBACK_TRAVERSAL** 档（见第 13 篇）。两者同帧：**ANIMATION 先跑（重组）→ TRAVERSAL 后跑（测量布局绘制）**。Agent 的查询若在 ANIMATION 之后、TRAVERSAL 期间触发主线程长任务，会直接推后本帧上屏。
2. ANI 跨进程跳 UI 线程：无障碍框架 `AccessibilityNodeInfo` 的 query（如 `findAccessibilityNodeInfosByViewId`）走到 `ViewRootImpl` / `Compose` 的 `SemanticsNode` 时，**在目标 App 的 UI 线程（主线程）执行**（第 13 篇已点出「ANI 查询跨进程并跳目标 App UI 线程执行」）。所以 Agent 的 N 次查询 = N 次主线程任务。
3. Compose 语义树友好但有代价：`SemanticsNode` 比 View 树更结构化（对 Agent 友好），但 `SemanticsNode` 的 fetch 仍需遍历 `SlotTable` + 跑 `getSemantics` 收集，重组期间树在变 → Agent 触发「重新收集语义树」→ 又触发一次重组依赖 → 互锁放大。
4. `ACTION_CLICK` 不是注入 `MotionEvent`：Agent 点 Compose 按钮走 `SemanticsAction`（`onClick` 语义），若按钮用 `pointerInput { detectTapGestures { ... } }` 而**没有 `onClick` 语义**（第 13 篇经典坑），Agent 点不到 → Agent 重试/降级 → 更多主线程占用。

**易错红榜**
- 错：以为无障碍查询在 Agent 进程算完、不影响目标 App。它跳到目标 App 主线程执行。
- 错：以为 Compose 比 View 对 Agent 更省。结构化更友好，但 fetch 仍在主线程 + 重组互锁会放大卡顿。
- 错：用 `onClick = {}` 就一定能被 Agent 点到。必须用 `Modifier.clickable` / 带 semantics 的写法，纯 `pointerInput` 无 onClick 语义 Agent 点不到。
- 错：把卡顿全归 Compose 重组。本场景根因是「Agent 查询占用主线程」叠加「重组挂 ANIMATION 回调」，要分别看 `Choreographer` 各 callback 档耗时。

**追问链**
1. 怎么用 Perfetto 拆出「ANI 查询占主线程」vs「重组占主线程」？（答：`android_startup`/`slice` 里查 `Recomposer`/`semantics` 相关 slice；thread_state 看主线程在 Agent binder 调用上的阻塞。）
2. Compose 强跳过模式（第 13 篇）能缓解吗？（答：能减少不必要重组，但 Agent 触发的语义树重收集绕不过，需减少语义树变更频率。）
3. 为什么 Recomposer 要挂 ANIMATION 而非 TRAVERSAL？（答：动画/重组需在绘制前完成，且 ANIMATION 档优先级保证动画帧优先；但副作用是与 Agent 查询同帧竞争主线程。）

**延伸阅读**
- `androidx/compose/runtime/Recomposer.kt`（AndroidUiDispatcher / Choreographer 注册）
- `frameworks/base/core/java/android/view/Choreographer.java`（CALLBACK_ANIMATION / CALLBACK_TRAVERSAL）
- `frameworks/base/core/java/android/view/accessibility/AccessibilityNodeInfo.java`
- `androidx/compose/ui/platform/AndroidComposeView.kt`（SemanticsNode 出口）

---

## 场景五：Thermal 降频 + ADPF + RenderThread + 后台 FGS 受限 + Job 配额（掉帧 + 任务不跑叠加）

**现象**
> 长时间游戏/视频后：①帧率从 60 抖到 30 甚至更低，明显掉帧；②后台同步任务（JobScheduler / WorkManager）长时间不执行；③前台服务被限流。用户感知「又卡又同步不了」。

**定界**
- 掉帧：责任在 **Thermal HAL 上报 throttling → Power HAL / ADPF 降频**，RenderThread / GPU 算力被砍。
- 任务不跑：责任在 **后台 FGS 类型限制 + JobScheduler 配额（A16 收紧）**，与掉帧是两个独立子系统，恰好同时发生被误认为「一个原因」。

**原理 + AOSP 源码落点**
1. Thermal 链路：`hardware/interfaces/thermal`（AIDL `IThermal`），HAL 上报温度到 `frameworks/base/services/core/java/com/android/server/thermal/ThermalManagerService`，再广播 `ACTION_THERMAL_STATUS_CHANGED` 并通知 `PowerHAL`。
2. 降频与 ADPF：`android.hardware.power` AIDL `IPower` / `Session`（ADPF，`android.os.PerformanceHintManager`）用 `updateTargetWorkDuration` / `reportActualWorkDuration` 让调度器（EAS + `uclamp`）给渲染线程更多 CPU 算力。**但 thermal throttling 是更高优先级的硬约束**——`cpufreq` 直接降频，ADPF 的 hint 被 thermal 覆盖，于是 `targetWorkDuration` 怎么调都救不回帧率。
3. RenderThread：`FrameTimeline` 里 `GPU` / `Display` 段变长 → `JankType` 标 `AppDeadlineMissed` 或 `SurfaceFlingerDeadlineMissed`；降频后 GPU 合成/着色器执行变慢。
4. 后台受限（独立根因）：`JobScheduler` 自 A16 起按 **应用standby bucket + 配额（每小时执行分钟数/次数上限）** 限制；`foregroundService` 在 A14 起要求声明 **FGS type**（如 `mediaPlayback`/`dataSync`），类型不对会被系统限流甚至 crash。这两项与 thermal 无关，只是「发热场景用户最容易同时撞上」。

**易错红榜**
- 错：以为掉帧 + 后台不跑是同一根因（thermal）。其实是 thermal 降频 + Job 配额两个独立机制同时触发。
- 错：以为 ADPF `targetWorkDuration` 调小能提帧。thermal 降频时 hint 无效，得先降温或降画质。
- 错：以为 `foregroundService` 启动就安全。A14 起必须配正确 **FGS type**，否则被限流。
- 错：把 `JankType=AppDeadlineMissed` 全归 App 代码慢。降频下是系统算力不足，不是 App 逻辑问题。

**追问链**
1. 怎么用 Perfetto 区分「掉帧是 App 主线程慢」vs「thermal 降频」？（答：`cpu_frequency` counter 看是否降频；`actual_frame_timeline_slice` 的 jank_type + GPU 段时长；第 25 篇 gpu_counter 判定 GPU bound vs CPU bound。）
2. FGS type 配错的具体后果？（答：A14 起 `Missing foreground service type` 直接 `SecurityException` / 限流；`dataSync` 类型有时长上限会被系统停止。）
3. ADPF Session 与 `FrameTimeline` 如何联动做自适应画质？（答：用 `reportActualWorkDuration` 反馈，超阈值降分辨率/关闭特效，绕开 thermal 硬降频。）

**延伸阅读**
- `hardware/interfaces/thermal/aidl/...`（IThermal）
- `frameworks/base/services/core/java/com/android/server/power/PerformanceHintManagerService.java`
- `frameworks/base/core/java/android/app/job/JobScheduler.java`（配额逻辑）
- `frameworks/base/core/java/android/app/ForegroundService.java`（FGS type，[A14]）

---

## 场景六：A17 Memory Limiter + LMKD / PSI + ART 分代 GC + 大图 native 内存（三路杀 + GC 压力）

**现象**
> 图片/相册类 App 浏览大图时**被静默杀掉（没有 ANR、没有 crash 弹窗）**，重新打开后从首页开始；`dumpsys meminfo` 显示 Java 与 native 都高。

**定界**
- 这是 **三路杀叠加**：①`LMKD` 基于 PSI 杀；②`[A17] Memory Limiter` 对单个应用内存超配额**静默杀**；③内核 OOM 兜底。
- native 侧大图解码（`Bitmap` 的 `GraphicBuffer` / `ashmem` 后端）占大头，Java 侧 `ART` 分代 GC 频繁 young GC 但回收不了 native 像素。

**原理 + AOSP 源码落点**
1. LMKD / PSI：`system/core/lmkd/lmkd.cpp` 监听 `PSI`（Pressure Stiffness Info，`/proc/pressure/memory`）的 `some` / `full`  stall，超阈值按 `oom_score_adj`（AMS 算的 `oom_adj`，见第 1/19 篇）选进程杀。
2. `[A17] Memory Limiter`：Android 17 新增的**应用级内存限额**，在 `LMKD` 之外独立评估单个 App 的 RSS/内存占用，超标即杀，**不弹 ANR、不通知**——这是「静默杀」的真正来源，区别于传统 LMKD（通常伴随系统级内存压力）。
3. ART 分代 GC `[A17]`：A17 在 A14 的 CMC（concurrent mark-compact，见第 7/8 篇）之上加 **young/old 分代**，young gen 频繁回收短命对象。但 `Bitmap` 像素走 native 分配（`GraphicBuffer` / `ashmem`），**不被 ART GC 回收**——只有 Java 侧 `Bitmap` 对象被回收后，native 像素才在 `finalize`（`BitmapFinalizer`）里释放，存在延迟窗口。
4. 三条杀路径辨析（呼应第 12/19 篇）：**内核 OOM**（全局内存耗尽，SIGKILL）→ **LMKD/PSI**（用户态基于 PSI 提前杀，避免真 OOM）→ **A17 Memory Limiter**（单 App 个体超标静默杀，最隐蔽）。`ApplicationExitInfo` 的 `REASON` 字段可区分（`REASON_LOW_MEMORY` / `[A17] MemoryLimiter:AnonSwap` 等，见第 13 篇）。

**易错红榜**
- 错：以为「被杀一定有 ANR」。Memory Limiter 静默杀没有 ANR，traces 抓不到。
- 错：只看 Java 堆（`dumpsys meminfo` 的 Java 行）。大图像素在 native，`native` 行 + `Graphics` 行才是大头。
- 错：以为 `Bitmap.recycle()` 还必要。A14+ 由 GC finalizer 管，但大图密集场景主动释放仍有意义（避免 finalize 延迟窗口堆积）。
- 错：把 `REASON_LOW_MEMORY` 全当 LMKD。A17 起要区分 Memory Limiter 个体超标与系统级 PSI。

**追问链**
1. 怎么确认是被 Memory Limiter 而非 LMKD 杀的？（答：`dumpsys activity exit-info` / `ApplicationExitInfo.getReason()`，看是否 `MemoryLimiter` 前缀；`logcat` 搜 `memory_limiter` / `lmkd`。）
2. `inBitmap` / `BitmapPool` 怎么缓解 native 像素堆积？（答：复用 `GraphicBuffer` 后端避免反复分配/释放，减少 finalize 延迟窗口与 PSI 抖动。）
3. ART 分代 GC 对大图场景有帮助吗？（答：几乎无——大图像素在 native，分代只优化 Java 短命对象；真优化在 native 复用 + 及时释放。）

**延伸阅读**
- `system/core/lmkd/lmkd.cpp`（PSI 监控 / kill 决策）
- `frameworks/base/services/core/java/com/android/server/am/AppExitInfoTracker.java`
- `art/runtime/gc/...`（[A17] generational collector）
- `frameworks/base/graphics/java/android/graphics/Bitmap.java`（native 像素 / BitmapFinalizer）

---

## 场景七：Zygote fork + SELinux + 16KB + hiddenapi + 非 SDK 接口（升级启动崩溃）

**现象**
> App 把 `targetSdkVersion` 升到 A17（37）后：**①启动即 `VerifyError` / `NoSuchMethodError`（运行时反射调不到）；②偶发 SELinux `avc: denied` 导致功能失效；③旧机上正常的 native 库在 16KB 设备映射失败。**

**定界**
- 反射失效：责任在 **hiddenapi 非 SDK 接口管制**（light/dark/black greylist，A17 封死）。
- SELinux：责任在 `seapp_contexts` 按 `targetSdk` / `isSystemApp` 分配的 domain 变化。
- 16KB：责任在 `.so` 的加载地址 / 对齐（A17 安全原生 DCL 还要求 `.so` 只读，见第 9/30 篇）。

**原理 + AOSP 源码落点**
1. hiddenapi：`art/runtime/hidden_api.cc` 在类/成员访问时检查 **greylist 等级**（light / dark / black）。`targetSdk` 越高，dark/black 限制越严；**A17 把一批原本 light 的接口移到 dark/black，且 `static final` 字段被判定「真不可变」——反射改 `static final` 直接抛异常**（第 8 篇已点）。访问受限接口走 `ShouldDenyAccessToMember` 返回 `kDeny`。
2. Zygote fork：`Zygote.forkAndSpecialize`（`frameworks/base/core/java/com/android/internal/os/Zygote.java`）在 fork 后设 `SELinux` context（`selinux_android_setcontext`）——context 由 `seapp_contexts` 按 `targetSdk` / `isSystemApp` / `isInstantApp` 等选 domain。升级后 domain 变了，旧 `allow` 规则不匹配 → `avc: denied`。
3. 16KB + 安全原生 DCL `[A17]`：A17 要求动态加载的 `.so` **必须只读**（`dlopen` 的 `PROT_EXEC` 段不可写，防 JIT-in-.so 注入）；16KB 页下 `.so` 的加载基址与 ELF 段对齐需满足 16KB，否则 `mmap` 失败（呼应场景三的 16KB 主题）。
4. 兼容模式：Android 提供短期 `compat` 框架（第 10 篇 `platform_compat`）对非 SDK 接口「暂时放行」，但 **A17 起多数豁免被收回**，不能长期依赖。

**易错红榜**
- 错：以为 `targetSdk` 只是行为变更开关。它直接改 SELinux domain 选择 + hiddenapi 限制等级，影响启动与安全。
- 错：以为反射不到字段是「混淆问题」。是 hiddenapi 在 ART 访问检查层拦的，混淆不会拦、但 greylist 会。
- 错：以为 `static final` 还能靠反射改。A17 判定真不可变，改了直接异常。
- 错：以为 16KB 只影响 App 代码。native `.so` 的加载对齐同样受限，且 A17 要求 `.so` 只读。

**追问链**
1. 怎么查某个接口在哪个 greylist？（答：`art/tools/hiddenapi` 生成 `hiddenapi-flags.csv`；`pm list packages -f` + `dumpsys package` 看 `hiddenApiEnforcementPolicy`。）
2. SELinux `avc: denied` 怎么快速定位缺哪条 rule？（答：`audit2allow` 解析 `dmesg`/`logcat` 的 avc，但要确认 domain/type 是否该有该权限，不能无脑 allow。）
3. 非 SDK 接口有合规替代吗？（答：能用公开 SDK / `androidx` 的就用；实在要用框架内部 API 只能接受高 targetSdk 下被拒，需改架构。）

**延伸阅读**
- `art/runtime/hidden_api.cc` / `art/tools/hiddenapi/`（greylist 生成）
- `system/sepolicy/private/seapp_contexts`（domain 选择）
- `frameworks/base/core/java/com/android/internal/os/Zygote.java`（forkAndSpecialize / SELinux）
- `system/core/libutils/...` + `bionic/linker/`（16KB .so 加载对齐）

---

## 场景八：跨 VM RPC Binder + KeyMint + AVF 隔离编译 odrefresh + 端侧 LLM（安全边界 + 启动成本）

**现象**
> 把端侧大模型推理塞进 **pKVM 保护 VM（Microdroid）** 后：①App 首次调用推理**卡好几秒**；②推理结果的可信边界说不清（「这是不是在 pVM 里算的？」）；③`KeyMint`  attestation 在 pVM 内做，但调用方拿到的证据链不完整。

**定界**
- 首次卡：责任在 **`odrefresh` 在 pVM 内首次重编 ART 编译产物**（AVF 隔离编译），+ pVM 启动本身 ~300MB RAM / ~16GB 加密存储成本。
- 安全边界：责任在 **跨 VM RPC Binder 的 `getCallingUid()` 不可信**（呼应第 12/13 篇），+ `KeyMint` 在 pVM 内的 TA 边界。
- 这是 #23 场景七（推理进 pKVM × KeyMint）的**更深版**：叠加了「odrefresh 首次重编」与「安全边界失效的具体触发点」。

**原理 + AOSP 源码落点**
1. AVF / Microdroid：`packages/modules/Virtualization/`，`VirtualizationService`（Rust host）起 pVM；`Microdroid` 是精简 Android 跑在 EL2 保护 VM 里。pVM 启动要分配内存（捐赠 stage-2 页表，见第 12 篇 pKVM）+ 加载镜像，成本约 300MB RAM。
2. `odrefresh` 隔离编译：pVM 内运行 ART 需要**自己的编译产物**。`odrefresh`（`packages/modules/Virtualization/odrefresh`）在 pVM 首次启动或 system 映像变化（如 OTA）时，把 `dex` 编译成 pVM 内可用的 `oat`/缓存。**首次重编是同步阻塞的 I/O+CPU 炸弹**，直接表现为「首次调用推理卡几秒」；之后复用缓存。
3. 跨 VM RPC Binder：`RpcSession` / `RpcServer`（`frameworks/native/libs/binder/ndk/` 或 `libbinder_rs`），走 **vsock** 而非内核 Binder 驱动。关键：**跨 VM 时 `IPCThreadState::getCallingUid()` 拿到的是 pVM 内映射的 UID，host 世界无法据此信任调用方**——若把 pVM 内服务误当成「系统可信」去授权敏感操作，就是安全边界失效。
4. `KeyMint` attestation：pVM 内 `IKeyMintDevice` TA 做密钥证明（第 11 篇），但 attestation 链的可信根依赖 **DICE/BCC per-VM secret 派生**（第 12 篇）。调用方拿到的 `Certificate` 要校验 `pVM` 指纹；若只校验到 host 层就信，等于边界提前结束。

**易错红榜**
- 错：以为「进 pVM 就一定安全」。pVM 提供机密性/完整性，但**采集链路（数据进 pVM 前）在 host 不受保护**（第 12 篇明确点出）。
- 错：以为跨 VM `getCallingUid()` 可信。它与跨普通 Binder 一样不可信（第 12/13 篇），pVM 内 UID 是映射值。
- 错：以为推理慢是模型大。首次卡主要是 `odrefresh` 重编 + pVM 启动，模型推理本身在预热后正常。
- 错：以为 attestation 链到「Android 系统」就够。要验到 **pVM 指纹 + DICE** 才算可信边界完整。

**追问链**
1. 怎么避免 odrefresh 首次重编卡用户？（答：构建期预编译 pVM 内 ART 产物 / `compos` 离线编译 / 首调前预热 pVM；参考第 14 篇 AVF 隔离编译。）
2. 跨 VM 调用怎么做「可信授权」？（答：不依赖 UID，用 **端侧证明（attestation）+ 签名校验 + pVM 指纹**；普通 Binder 的 `clearCallingIdentity` 思路在跨 VM 下不适用。）
3. pKVM vs TEE 做推理，安全边界怎么选？（答：pKVM 适合通用计算/多租户（~1万行 TCB），TEE 适合早期启动/安全外设；推理进 pVM 更灵活但有采集链路盲区，见第 12 篇对比。）

**延伸阅读**
- `packages/modules/Virtualization/`（VirtualizationService / Microdroid / odrefresh / compos）
- `frameworks/native/libs/binder/ndk/util/BinderRpcs.cpp`（RpcSession / vsock）
- `hardware/interfaces/security/keymint`（IKeyMintDevice TA）
- 第 12 篇（pKVM/AVF）/ 第 13 篇（跨 VM getCallingUid 不可信）/ 第 14 篇（odrefresh 隔离编译）

---

## 跨场景易错红榜 TOP18（压轴必背）

1. **「主线程卡在 Binder transact」≠ 自己慢** → 跨进程等对端，根因可能在对方线程池/死亡通知（场景二）。
2. **oneway 也会因对端线程池满而排队** → 不是「发了就完」。
3. **对端进程死，binderDied 要等本端空闲 binder 线程** → 线程池满时可能永远等不到，形成隐蔽死锁。
4. **ANR 不一定有主线程死循环** → 主线程等跨进程回包也会 ANR，traces 看不出锁。
5. **Memory Limiter 静默杀无 ANR、无 crash** → 只有 `ApplicationExitInfo` 的 `REASON` 能区分（场景六）。
6. **大图像素在 native，ART GC 回收不了** → 只看 Java 堆会误判（场景六）。
7. **16KB 页让旧 4KB 对齐 odex 失效 → 首启动 dex2oat 重编炸弹**（场景三）。
8. **PinnerService 启动期 pin 是一次 I/O 成本**，不是纯加速（场景三）。
9. **旋转时 input 丢是系统侧 channel 重连时序**，不是 App onTouchEvent（场景一，呼应 #516836306）。
10. **黑屏半秒恢复 ≠ LMK 杀进程** → 是 Surface destroy/recreate 竞态（场景一）。
11. **ANI/A11y Agent 查询跳目标 App 主线程执行** → 拖慢主线程 + 与 Compose 重组互锁（场景四）。
12. **Compose Recomposer 挂 Choreographer ANIMATION 回调**，与 View traversal TRAVERSAL 同帧先后（场景四）。
13. **纯 pointerInput 无 onClick 语义 → Agent 点不到**（场景四，呼应第 13 篇）。
14. **thermal 降频时 ADPF targetWorkDuration 无效** → hint 被硬降频覆盖（场景五）。
15. **掉帧 + 后台任务不跑是两个独立子系统** → 别当成同一根因（场景五）。
16. **A14+ FGS 必须声明 type**，否则限流/崩溃（场景五）。
17. **A17 static final 真不可变，反射改直接异常**；非 SDK 接口随 targetSdk 收紧（场景七）。
18. **跨 VM getCallingUid 不可信 + 采集链路在 host 不受保护** → pVM 安全边界有盲区（场景八）。

---

## 三条跨子系统追问链（考官最爱连击）

**链 A：一个「启动慢」能挖多深？**
冷启动慢 → `bindApplication` 占比（Perfetto `android_startup`）→ `ContentProvider` 前置（第 20 篇）→ 16KB 不兼容触发 dex2oat 重编 + PinnerService I/O（场景三）→ 基线/云 Profile 不匹配（第 24 篇）→ 能不能用 `dexpreopt`/构建期预编译绕开？→ OTA 后失效怎么办？

**链 B：一个「卡顿/掉帧」怎么定责到子系统？**
掉帧 → `FrameTimeline` jank_type（第 19/21 篇）→ App 主线程阻塞 vs RenderThread vs SF/HWC（第 20 篇 code walk）→ thermal 降频（场景五，`cpu_frequency` counter）→ GPU bound vs CPU bound（`gpu_counter`，第 25 篇）→ Compose 重组/ANI Agent 互锁（场景四）→ 多指/旋转 input 丢（场景一）→ 三者叠加时先救哪个？

**链 C：一个「被杀/安全边界」怎么分层？**
进程消失 → `ApplicationExitInfo.REASON`（第 13 篇）→ LMKD/PSI vs A17 Memory Limiter vs 内核 OOM（场景六，三路杀辨析）→ 跨进程 Binder 死锁（场景二）→ 跨 VM RPC Binder 的 UID 不可信（场景八）→ 采集链路在 host 不受保护（场景八）→ 安全边界到底画在哪？

---

## AOSP 源码路径清单（本篇速查）

| 子系统 | 路径 |
| --- | --- |
| Input 重连 / 多指 | `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp` |
| 旋转 / Surface 生命周期 | `frameworks/base/services/core/java/com/android/server/wm/DisplayContent.java`、`frameworks/base/core/java/android/view/ViewRootImpl.java` |
| ActivityEmbedding | `frameworks/base/libs/WindowManager/Shell/.../TaskFragment`，`WindowOrganizerController.java` |
| Binder 线程池 | `frameworks/native/libs/binder/ProcessState.cpp`、`IPCThreadState.cpp`、`BpBinder.cpp` |
| Binder 驱动 | `drivers/android/binder.c`（binder_transaction / binder_release / BR_SPAWN_LOOPER） |
| dex2oat / odex | `art/dex2oat/dex2oat.cc`、`PackageManagerService.java`（dexopt）、`PinnerService.java` |
| Compose Recomposer | `androidx/compose/runtime/Recomposer.kt`、`AndroidComposeView.kt` |
| Choreographer | `frameworks/base/core/java/android/view/Choreographer.java`（ANIMATION / TRAVERSAL） |
| Thermal / Power | `hardware/interfaces/thermal`、`PerformanceHintManagerService.java`、`android.os.PerformanceHintManager` |
| Job / FGS | `frameworks/base/core/java/android/app/job/JobScheduler.java`、`ForegroundService.java` |
| LMKD / PSI | `system/core/lmkd/lmkd.cpp` |
| Memory Limiter [A17] | `frameworks/base/...`（应用级内存限额，A17 新增） |
| ART GC [A17 分代] | `art/runtime/gc/...` |
| hiddenapi | `art/runtime/hidden_api.cc`、`art/tools/hiddenapi/` |
| SELinux domain | `system/sepolicy/private/seapp_contexts`、`Zygote.java` |
| AVF / pVM | `packages/modules/Virtualization/`（VirtualizationService / Microdroid / odrefresh / compos） |
| RPC Binder | `frameworks/native/libs/binder/ndk/util/BinderRpcs.cpp`（RpcSession / vsock） |
| KeyMint | `hardware/interfaces/security/keymint`（IKeyMintDevice TA） |

---

## 27 篇交叉索引（知识地图闭环）

```
第 1-3 篇（7/23）  主线路：Binder / 启动 / AMS·WMS·SF / View / ANR / HAL / GKI / MTK
第 4 篇（7/24）    图形多媒体通信：HWUI / SF / MediaCodec / Thermal / Power / Telephony / Wi-Fi / BT
第 5 篇（7/27）    系统基建可观测性：16KB / ClassLoader / 权限 / Keystore2 / AVB / logd / RRO / Doze
第 6 篇（7/28）    端侧AI + A17 演进：NNAPI / CarService / Vulkan / ART 镜像 / virtual A/B
第 7 篇（7/29）    A17 新雷区：Lock-free MQ / 分代GC / hiddenapi / ProfilingManager / NFC / Media3 / 端侧LLM
第 8 篇（7/30）    渲染合成 + A17 安全内存：RenderEngine / Codec2 / Memory Limiter / DCL / CarService
第 9 篇（7/31）    兼容性框架 × A17 跨设备窗口隐私：platform_compat / letterbox / BAL / Bubbles / Handoff
第 10 篇（8/1）    TEE / Widevine / KeyMint / ION→DMA-BUF
第 11 篇（8/2）    pKVM / AVF / AISeal / Connectivity eBPF / Ravenwood
第 12 篇（8/3）    AppFunctions / Compose / APK 签名 v3.2 / ApplicationExitInfo / 系统托管 UI / ANI
第 13 篇（8/4）    端侧AI 工程化 / AAOS 电源状态机 / StrongBox / Protected Confirmation / odrefresh
第 14 篇（8/5）    末轮缺口补全 / 体系总导航
第 15 篇（8/5）    收官补遗：AAOS 电源状态机 / 端侧LLM量化
第 16 篇（8/5）    考前总复习速查卡
第 17 篇（8/5）    高频考官连击模拟考
第 18 篇（8/6）    全链路排查实战：冷启动/卡顿/ANR/内存三路杀/发热/Binder
第 19 篇（8/6）    源码级 code walk：startActivity→首帧 / Binder 一次事务
第 20 篇（8/7）    Perfetto SQL 范例库
第 21 篇（8/8）    A18 桌面融合 / 跨设备协同
第 22 篇（8/9）    真题大乱斗 vol.1（8 个跨子系统场景）
第 23 篇（8/10）    ART 运行时 / dex2oat / odex / 冷启动
第 24 篇（8/11）    Perfetto SQL 扩充：input 延迟 / GPU 计数器 / battery
第 25 篇（8/12）    核心基础高频八股深挖与查缺补漏（native Looper / Binder 池 / View 事件 / MeasureSpec / GKI / MTK）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
第 26 篇（本篇·8/12） 真题大乱斗 vol.2 —— 8 个更刁钻跨子系统叠加压轴卷
                      （旋转+多指+Embedding / Binder死锁 / 16KB+dex2oat / Compose+ANI /
                       thermal+FGS / MemoryLimiter三路杀 / hiddenapi+SELinux / 跨VM推理）
```

> 全系列至此 **27 篇 / 约 183 专题**，覆盖主线 + 盲区 + 深水区 + 智能层 + 安全世界(TEE/pKVM) + 座舱 + 端侧 AI + 源码 walk + Perfetto SQL + 基础深挖 + 两版真题大乱斗，形成完整闭环。剩余真·未覆盖角度仅剩：KMP/skiko 非 Android target 运行时深水区（第 15 篇已部分覆盖 Android 侧差异）。
