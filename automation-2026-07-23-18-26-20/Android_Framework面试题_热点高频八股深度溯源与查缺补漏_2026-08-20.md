# Android Framework 面试热点·高频八股深度溯源与查缺补漏（综合轮训 + 末位真缺口补全版）

> 日期：2026-08-20 | Baseline：Android 14（UpsideDownCake, API 34, android-14.0.0_rXX）/ Kernel 6.1 GKI
> 系列定位：第 36 篇 / 累计约 230 专题。本篇是「综合轮训」形态——把前 35 篇分散的八股按领域焊成一份可日更速过的复习卡，并**关闭全系列最后一个真缺口：Compose `$changed` 位掩码源码逐行走读**（8/15 只到"生成代码级"，本篇下钻到 `ComposableFunctionBodyTransformer` 的 IR 改写内部）。

---

## 0. 当日热点锚定（2026-08-20）

**版本线（联网核实，截至 8/20）：**
- Android 17（CinnamonBun → 内部代号 DEV）stable 已于 **2026-06-16** 发布；A17 QPR1 预计 **2026-09** stable，QPR2 预计 **2026-12** stable（随 Pixel Feature Drop），QPR3 预计 **2027 初**。
- **A17 QPR2 Beta 3 已于 2026-08-14 推送**（build CP41.260731.005，安全补丁 2026-08-05）——本周期最大版本：原生 **App Lock**（长按图标 → 指纹/PIN 才能打开，隐藏锁应用通知与小组件；注意：已授权的 AI Agent 仍可读其数据）、重排 Quick Settings 编辑器、更多锁屏模糊、更深色彩自定义、防呼叫转移诈骗、折叠屏多任务更新。
- Android 已弃用独立 Developer Preview，改为常驻 **Android Canary** 测试轨道（QPR 新特性先在 Canary 出现）。
- A18 路线图（桌面融合巩固 / 通用剪贴板 / 跨设备 Handoff / AppFunctions·Gemini 扩张 / EU DMA 开放 11 项 AI 能力）仍在路线图中。

**面试热点趋势（2026 招聘侧核实）：**
- 出现率最高的底层题：Handler/Looper（epoll、内存泄漏引用链）、Binder（一次拷贝、安全边界、线程池、AIDL）、View 绘制、AMS 启动、Compose 重组/稳定性 —— 字节/美团/腾讯出现率近 90%。
- **量化数字**成为硬门槛："做了性能优化"必须有基准/手段/结果三连数字（如冷启动 1.8s → 0.9s）。
- Compose 考频 2025-2026 明显上升，且追问到重组触发条件、`derivedStateOf`、`LaunchedEffect` 生命周期三层深。
- Native Crash 定位（tombstone / addr2line / HWASan/MTE）与 ANR 回溯是中高级岗分水岭。

> 本篇策略：逐域给"最高频题 + 详细溯源（AOSP 14 路径/方法名）"，深度已在对应篇目展开处直接交叉索引，避免重复堆字；末位真缺口（Compose `$changed`）完整逐行走读。

---

## 1. 综合轮训总览（按领域：最高频八股 + 一句话考点 + 深挖落点）

| 领域 | 最高频题 | 一句话考点 | 深度落点（交叉索引） |
|---|---|---|---|
| Handler/Looper | 主线程死循环为什么不 ANR？ | epoll 休眠让出 CPU；ANR 是消息处理超时而非循环本身 | 核心基础篇(8/12)、codewalk(8/6) |
| Binder IPC | 一次拷贝原理 + 为什么不用共享内存 | mmap 内核缓冲区 + 驱动拷贝一次；安全/UID 校验是选型关键 | 主篇(7/23)、深挖篇(7/23)、全链路(8/6) |
| AMS/ATMS | startActivity 全链路 | ActivityStarter→realStartActivityLocked→Zygote socket | codewalk(8/6)、启动链路(8/19) |
| WMS | 窗口层级/软键盘/横竖屏 | WindowState + WindowManagerPolicy + relayout | 图形多媒体(7/24)、A18桌面(8/8) |
| View | 事件分发三方法 + MeasureSpec | dispatch/onIntercept/onTouch 责任链；AT_MOST 坑 | 核心基础篇(8/12) |
| App 启动 | 冷启动三段 | 进程创建→Application→首帧 | 启动链路(8/19)、codewalk(8/6) |
| 内存/卡顿/ANR | 三路杀 + 四类超时 | LMKD/PSI vs A17 MemoryLimiter vs 内核 OOM | 全链路(8/6)、排查(8/6)、Native(8/14) |
| Compose | 重组/稳定性/强跳过 | SlotTable + Snapshot MVCC + `$changed` 位掩码 | Compose底层(8/15) + **本篇 §3** |
| HAL/Kernel/Drivers | Treble/GKI 2.0/字符设备 | hwbinder/vndbinder 分离；KMI 解耦 | HAL内核(8/17) |
| MTK | AEE/mtklog/PerfService | exp_main 异常引擎 + 性能调度 API | 核心基础篇(8/12)、HAL内核(8/17) |

---

## 2. 逐域热点 Q&A（深度溯源，带 AOSP 14 路径）

### 2.1 Handler / Looper / MessageQueue

**Q1：主线程 Looper.loop() 是死循环，为什么不会 ANR / 卡死？**
- 真相：`loop()` 在 `MessageQueue.next()` 里没有消息或延时消息未到点时，走到 native 侧 `nativePollOnce()` → 进入 `Looper.cpp` 的 `pollInner()` → `epoll_wait()` 阻塞，**让出 CPU**，线程进入休眠而非空转。
- ANR 的本质是"消息处理超时"：当一条消息（如 `InputDispatcher` 派发的触摸、`Broadcast` 的 `onReceive`、`Service` 的 `onCreate`）在**规定时间内没处理完**，system_server 的监控（或 native InputDispatcher 的 5s 计时器）判定超时并dump。死循环本身 consuming 0 CPU 的休眠态，与"处理不过来"是两件事。
- 易错点：把"主线程阻塞在死循环"和"主线程阻塞在某条消息里的同步 Binder/死锁"混淆——前者是正常态，后者才会 ANR。（深度见 8/12 核心基础篇、8/6 codewalk 首帧链路）

**Q2：MessageQueue 为什么用链表而非数组？postDelayed 怎么实现？**
- `Message` 自带 `next` 指针，`MessageQueue` 维护按 `when`（执行时刻）升序的单链表；插入延时消息按时间找位插入（不是尾插），`next()` 取出队首，若队首 `when > now` 则计算 `timeout` 传给 `nativePollOnce(timeout)` 精确唤醒。
- 对象池：`Message.obtain()` 从 `sPool` 静态单链表取复用，`MAX_POOL_SIZE = 50`，避免高频消息 GC 抖动。

**Q3：同步屏障（SyncBarrier）与 IdleHandler 各用在哪？**
- 同步屏障：`postSyncBarrier()` 向队列插入一条 `target == null` 的屏障消息，`next()` 遇到屏障会**优先取出异步消息（isAsynchronous）**并跳过普通消息，直到屏障移除。典型用途：VSync 到来时 `Choreographer` 派发的绘制消息设为异步，保证帧绘制优先于普通 UI 消息。移除用 `removeSyncBarrier(token)`。
- `IdleHandler`：`MessageQueue.IdleHandler` 在 `next()` 发现"无立即可处理消息"时回调 `queueIdle()`，返回 false 一次性、true 常驻；常用于延迟初始化、GC 触发、LeakCanary 的 watch。

### 2.2 Binder IPC

**Q4：Binder 一次拷贝的原理？为什么不用共享内存？**
- 一次拷贝：发送方用户态把 Parcel 写入**内核态的 binder 缓冲区**（由 `/dev/binder` 通过 `mmap` 映射到收发双方用户态，双方共享同一块内核缓冲的映射），驱动在**内核态**把数据从发送方缓冲区拷贝到接收方缓冲区对应的内核页（`binder_transaction` 中的 `copy_from_user`/`copy_to_user` 实为一次内核→内核拷贝），接收方用户态直接读映射。**全程只有一次数据拷贝**（对比 socket/管道两次：用户→内核→用户）。
- 为什么不用共享内存：共享内存零拷贝但要**自己解决并发同步、生命周期、跨进程对象引用、UID/PID 鉴权**；Binder 的"一次拷贝 + 驱动集中鉴权（`binder_transaction` 里填 `sender_euid`/`sender_pid`，对端 `getCallingUid()` 可信）+ 对象引用（handle/binder 节点）"在安全性与易用性上更优，且绝大多数 IPC 数据量小，一次拷贝成本可忽略。（深度见 7/23 主篇/深挖篇、8/6 codewalk 一次事务链路）

**Q5：Binder 线程池默认多大？oneway 满了也排队吗？**
- 默认上限 **15**（由 `BINDER_SET_MAX_THREADS` 设置，libbinder 默认 15），外加 `joinThreadPool` 启动的 1 个主线程，实际最多 16 个 binder 线程服务该进程。驱动侧 `binder_proc.max_threads` 配合；当线程不够且对端发来新事务时，当前线程在 `BR_SPAWN_LOOPER` 指示下 `spawnPooledThread` 新建。
- **oneway（TF_ONE_WAY）不阻塞调用方等待 reply，但仍会排队**：事务先进入目标进程的 `binder_proc` 待处理队列，若该进程 binder 线程全忙（尤其对端主线程正同步调用别处导致回环），oneway 也会延迟处理；大量 oneway 轰击可把线程池打满并触发 `BinderCallsStats` 记录 spam（详见 8/6 全链路排查、8/2 pKVM 的 binder spam 溯源）。

**Q6：linkToDeath 死亡通知链路？getCallingUid 为什么"不可信"？**
- `linkToDeath(DeathRecipient)` → 驱动 `BC_REQUEST_DEATH_NOTIFICATION` 在 `binder_ref` 上挂死亡监听；对端进程死亡时驱动发送 `BR_DEAD_BINDER`，用户态 `IPCThreadState` 收到后回调 `DeathRecipient.binderDied()`，用于释放资源/重连。
- `getCallingUid()` 不可信的两类场景：①**跨 VM（AVF/pKVM）RPC Binder**：对端是另一个 VM，UID 映射不保证真实（8/2）；②**AppFunctions Provider 侧**：Binder 调用经 `system_server`（SYSTEM_UID）转发，Provider 拿到的 `callingUid` 是 SYSTEM_UID 而非真实 App UID，需走 `AppFunctions` 框架的身份校验（8/3）。

### 2.3 AMS / ATMS 启动

**Q7：startActivity 从 AMS 到应用进程的完整链路？**
- `ATMS.startActivityAsUser` → `ActivityStarter.execute()` → `startActivityUnchecked()` → `startActivityInner()` → `RootWindowContainer.resumeFocusedTasksStacksTopActivities()` → `ActivityTaskSupervisor.realStartActivityLocked()` → 通过 `IApplicationThread` 跨进程 → `ClientLifecycleManager.scheduleTransaction(LaunchActivityItem)` → 应用侧 `ActivityThread.handleLaunchActivity()` → `performLaunchActivity()`（反射 newActivity + `attach` + `callActivityOnCreate`）。
- **ContentProvider 前置坑**：`handleBindApplication` 在 `callApplicationOnCreate` 之前会先 `installContentProviders`，若某个 CP 的 `onCreate` 慢/阻塞，会直接拖累冷启动首屏（8/6 codewalk、8/19 启动链路均强调）。

### 2.4 WMS / View

**Q8：View 事件分发三方法责任链？requestDisallowIntercept 对 DOWN 无效？**
- `dispatchTouchEvent` → `ViewGroup.onInterceptTouchEvent`（仅 ViewGroup 有）→ `onTouchEvent`。返回 true 表示"消费/拦截"。
- `requestDisallowInterceptTouchEvent(true)`：子 View 要求父不拦截；但**父在 `DOWN` 事件一开始就 `reset` 该标志**，所以"对 DOWN 无效"——必须在 MOVE 等后续事件前调用才有意义；若父在 DOWN 就决定拦截，子来不及禁止。（详见 8/12 核心基础篇、8/16 输入系统）

**Q9：MeasureSpec 三模式 + AT_MOST 坑？getMeasuredWidth ≠ getWidth？**
- `UNSPECIFIED / EXACTLY / AT_MOST`；`getChildMeasureSpec` 按父 spec + 子 layoutParams 推导子 spec。AT_MOST 坑：子 View 在 `wrap_content` 时若未在 `onMeasure` 自己约束最大值，会直接撑到父给的上限（如 TextView 高度爆炸）。
- `getMeasuredWidth()` 是 measure 阶段结果，`getWidth()` 是 layout 后 `right-left`；**measure 完成但 layout 未执行前前者有效后者为 0**，所以不要在 `onMeasure` 里用 `getWidth()`。

### 2.5 App 启动 / 内存·卡顿·ANR

**Q10：冷启动三段 + 三路杀进程路径辨析？**
- 三段：①进程创建（Zygote fork + 类/资源 preload 红利）②`Application.onCreate`（ContentProvider 前置、初始化重灾区）③Activity 启动到首帧（`handleResumeActivity` → `ViewRootImpl.performTraversals` → SurfaceFlinger 上屏）。
- **三路杀**（面试题必考辨析）：①**LMKD / PSI**：用户态 `lmkd` 监听内核 PSI 内存压力，按 `oom_adj` 杀低优先级进程；②**A17 Memory Limiter**：应用个体内存超配额被**静默杀**（无 ANR），`ApplicationExitInfo.REASON_MEMORY_LIMITER`；③**内核 OOM Killer**：极端内存耗尽时内核直接 SIGKILL。三者触发主体与"是否 ANR"不同（8/6 全链路、8/19 启动链路、8/2 三条杀辨析）。

**Q11：ANR 四类超时 + Input ANR 计时器在哪？**
- 输入分发 **5s**（native `InputDispatcher::DEFAULT_INPUT_DISPATCHING_TIMEOUT`，非 App Looper）、广播 **10s**（前台）/60s（后台）、Service **20s**（前台）/200s（后台）、ContentProvider **10s`。
- 关键：**主线程卡在某条消息里的同步 Binder/死锁**才会导致 Input 5s 超时（native 计时器在 `InputDispatcher`，`finishDispatchCycleLocked` 停表）；若主线程只是 idle，不会 ANR（呼应 §2.1）。（8/16 输入系统专章、8/6 全链路）

---

## 3. 末位真缺口补全：Compose `$changed` 位掩码源码逐行走读

> 这是全系列 35 篇唯一没"逐行下钻"的点。8/15 讲到"生成代码级 + `$changed` 位掩码"为止；本篇下钻到编译器 IR 改写与运行时解码的内部实现。

### 3.1 为什么需要 `$changed`？

Compose 的"智能跳过"要在**运行时**判断"本次调用的参数相对上次是否变化"。但逐参数比较有成本，且**稳定类型 vs 不稳定类型**比较语义不同（稳定类型可依赖 `equals`，不稳定类型不能）。编译器在**编译期**就已知每个参数的稳定性（stability analysis），把这个信息编码进一个 `Int`（`$changed`），运行时只需按位解码 + 实测值比较，即可 O(1) 决定"跳过整个 group"还是"重组"。

### 3.2 注入点：`ComposableFunctionBodyTransformer`（IR 改写）

现位于 Kotlin 仓库 `compiler/ir/backend.common.jvm.compose/.../lower/ComposableFunctionBodyTransformer.kt`（此前在 `androidx.compose.compiler.plugins.kotlin`）。它对每个**有参数**的 `@Composable` 函数做两件事：

1. 注入两个额外参数：`$composer: Composer` 和 `$changed: Int`（参数多于 ~15 个时用 `$changed1/$changed2...`，因为 int 32 位、每参数 2 位 → 单 int 最多 15 参数）。
2. 在方法体开头注入"参数是否变化"的判断序列；当所有参数**未变化且均稳定**时插入 `composer.skipToGroupEnd()` 提前返回；否则执行函数体，末尾 `endRestartGroup()` 注册 restart 闭包（失效时重新调用本函数）。

### 3.3 位编码（运行时侧 `ComposerImpl.kt`）

```kotlin
private const val BitsPerParam = 2
private const val ChangedMask = 0b01   // bit0: 本次值是否变化 (1=变, 0=同)
private const val StaticMask  = 0b10   // bit1: 参数类型是否静态稳定 (1=stable, 0=dynamic)
// 第 i 个参数的两位落在 $changed 的 [i*2, i*2+1]
// 组合含义:
// 0b00 = 未变 + 稳定   -> 可安全跳过比较
// 0b01 = 已变 + 稳定   -> 需要重组
// 0b10 = 未变 + 不稳定 -> 因不稳定, 运行期按"已变"对待, 失去跳过优化
// 0b11 = 已变 + 不稳定
```

`StaticMask` 位由**编译器在调用点硬编码**（它知道该参数类型稳定性）；`ChangedMask` 位由**运行时 `ComposerImpl.changed(value)` 比较上次 slot 值得出**。

### 3.4 调用点生成的代码骨架（反编译视角）

```kotlin
fun Foo(x: Int, $composer: Composer, $changed: Int) {
    $composer.startRestartGroup(0x1234)          // restart group 标记
    val xChanged = $composer.changed(x)          // 解码 ChangedMask + 实测 previous != value
    val forced   = ($changed and StaticBit(x)) == 0  // 该参数不稳定 -> 强制重
    if (xChanged || forced) {
        // ... 正常 compose 函数体 ...
        $composer.endRestartGroup()
            ?.updateScope { Foo(x, $composer, $changed) }  // 注册重启闭包
    } else {
        $composer.skipToGroupEnd()               // 跳过整个 group, 函数体不执行
    }
}
```

调用方传入的 `$changed` 字面量形如 `0b0000_0010`（第 0 参数 Static 位置 1，表示 `x` 是稳定类型）——这是编译器**编译期写死**的。

### 3.5 `ComposerImpl.changed()` 真身

```kotlin
override fun <T> changed(value: T): Boolean {
    val slot = reader.currentSlot()          // 取出上次该参数存的旧值
    val previous = reader.get(slot)
    val changed = previous == null || previous != value   // 与上次比较
    writer.update(value)                     // 写回新值, 供下次比较
    return changed
}
```

### 3.6 面试高频误解澄清（重要）

- **误解一："unstable 参数一定每次重组"** —— 不完全。运行时仍会比较 `previous != value`，但因为调用点传入的 `StaticMask = 0`，`ComposerImpl` 把 unstable 参数当成"每帧都要重新判定"，叠加组的 invalidation 后等价于"任何对该组的失效都会重跑它"，**失去跳过优化**。准确说法：unstable 使编译器无法生成可靠 skip 判定。
- **误解二："强跳过（Strong Skipping）改了稳定性推断"** —— **否**。强跳过（Kotlin 2.0.20+/Compose Compiler 1.5.4 实验、K2 默认）改变的是**跳过策略**：从"依赖编译器稳定性推断"改为"只要参数类型可被引用相等（===）判定即用引用相等跳过"，并放宽对 `inline` lambda 参与跳过的约束。它**不改 `$stable`/`$changed` 位域布局**，只是改变了 `changed()` 比较时是否允许 inline lambda 参与跳过（以前 inline lambda 永远不稳）。（与 8/15 强跳过三要素呼应）

---

## 4. 易错红榜 TOP20（跨域高频易错点）

1. 主线程死循环 = 休眠（epoll），≠ ANR 根因（消息超时）。
2. Binder 一次拷贝发生在**内核态**，不是"零拷贝"；共享内存零拷贝但无内建鉴权。
3. oneway 不阻塞调用方，但**仍会排队**（线程池满则延迟）。
4. `getCallingUid()` 跨 VM / AppFunctions 场景下拿到的是中转方 UID，不可信。
5. ContentProvider 在 `Application.onCreate` **之前**初始化，是冷启动隐形杀手。
6. `requestDisallowInterceptTouchEvent` 对 **DOWN 事件无效**（父在 DOWN 先 reset）。
7. `getMeasuredWidth()` ≠ `getWidth()`（measure 后、layout 前后者为 0）。
8. `AT_MOST` + `wrap_content` 不自查最大值会撑爆父上限。
9. 三路杀：LMKD/PSI vs A17 MemoryLimiter（静默、无 ANR）vs 内核 OOM。
10. Input ANR 5s 计时器在 **native InputDispatcher**，不在 App Looper。
11. 同步屏障 `target == null`，优先异步消息（VSync 绘制用）。
12. idle handler 返回 true 常驻、false 一次性。
13. 消息池 `MAX_POOL_SIZE = 50`，`obtain()` 复用。
14. Compose unstable 参数 → 失去跳过优化，不是"必然每帧重组"。
15. 强跳过不改 `$changed` 位域，只改跳过策略。
16. Binder 线程池默认 15 + 1 主线程。
17. `linkToDeath` 经 `BR_DEAD_BINDER` 回调 `binderDied()`。
18. 折叠屏旋转 + 多指拆分时序竞态（QPR2 #516836306 现场溯源，见 8/16）。
19. 16KB 页面要求 `.so`/镜像 `max-page-size=16384`（A17 强制趋势，见 8/14）。
20. A17 新增 App Lock **不阻断已授权 AI Agent** 读锁应用数据（8/20 热点）。

---

## 5. 高频追问链（三条跨域）

**链 A：冷启动 ANR × Binder × AMS**
"冷启动超时怎么定界？" → `am start` 到首帧时间窗 → `bindApplication` 占比 → ContentProvider 前置坑 → 某 CP 内同步 Binder 阻塞 → 线程池满 → Input 5s 计时器触发 ANR（无主线程死锁痕迹）→ 抓 `trace` 看 `binder_transaction` 对端执行耗时。（落点：8/6 全链路、8/19 启动链路、8/16 输入）

**链 B：Compose 重组 × ANI 语义树 × Recomposer-Choreographer**
"为什么 Compose 卡顿？" → SlotTable gap buffer → Snapshot MVCC 读取即订阅 → Recomposer 挂 **CALLBACK_ANIMATION**（非 TRAVERSAL）→ 同帧重组先于 View 遍历 → ANI/A11y Agent 跨进程查询跳目标 App UI 线程执行，主线程卡顿拖慢 Agent（8/15、8/3、8/13）。

**链 C：跨设备 AI × CDM × RPC Binder 安全边界**
"跨设备调 AI 怎么保证身份？" → CDM 持久 Association + 系统角色权限 → RPC Binder 跨 VM → `getCallingUid` 不可信 → 必须框架层身份校验（8/22 A18 桌面、8/2 pKVM、8/3 AppFunctions）。

---

## 6. AOSP 14 源码路径清单（本篇引用）

| 主题 | 路径 / 方法 |
|---|---|
| 主线程 Looper | `frameworks/base/core/java/android/os/Looper.java`（`loop`/`prepareMainLooper`）、`MessageQueue.java`（`next`/`nativePollOnce`） |
| native Looper | `system/core/libutils/Looper.cpp`（`pollInner`/`epoll_wait`）、`frameworks/base/core/jni/android_os_MessageQueue.cpp` |
| 同步屏障/Idle | `MessageQueue.postSyncBarrier` / `MessageQueue.IdleHandler` |
| Binder 驱动 | `drivers/android/binder.c`（`binder_transaction`/`binder_ioctl`）、`binder_alloc.c` |
| Binder 用户态 | `frameworks/native/libs/binder/ProcessState.cpp`（BINDER_SET_MAX_THREADS=15）、`IPCThreadState.cpp`（`BR_SPAWN_LOOPER`） |
| AMS/ATMS | `services/core/java/com/android/server/wm/ActivityStarter.java`、`ActivityTaskSupervisor.realStartActivityLocked`、`ClientLifecycleManager` |
| View 事件 | `frameworks/base/core/java/android/view/ViewGroup.java`（`dispatchTouchEvent`/`onInterceptTouchEvent`）、`View.java`（`onTouchEvent`/`requestDisallowInterceptTouchEvent`） |
| MeasureSpec | `View.java`（`measure`/`getChildMeasureSpec`/`MeasureSpec`） |
| 输入 ANR | `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp`（`DEFAULT_INPUT_DISPATCHING_TIMEOUT`、`finishDispatchCycleLocked`） |
| Compose 编译器 | `ComposableFunctionBodyTransformer.kt`（IR 改写注入 `$composer`/`$changed`/`skipToGroupEnd`） |
| Compose 运行时 | `runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/ComposerImpl.kt`（`changed`、`BitsPerParam`/`ChangedMask`/`StaticMask`、`startRestartGroup`/`endRestartGroup`、`skipToGroupEnd`） |
| 启动/oom_adj | `frameworks/base/services/core/java/com/android/server/am/`（`ActivityManagerService`、`ProcessList`）、`OomAdjuster.java` |

---

## 7. 35 → 36 篇交叉索引（全系列闭环导航）

**主线（1-16 篇）**：7/23 主篇(16章) / 热点拓展(10章) / 深挖篇(11章) / 7/24 图形多媒体通信 / 7/27 系统基建可观测 / 7/28 端侧AI与A17 / 7/29 A17新雷区 / 7/30 渲染合成 / 7/31 兼容性框架 / 8/1 安全世界TEE / 8/2 pKVM机密计算 / 8/3 智能系统AppFunctions / 8/4 端侧AI工程化 / 8/5 末轮缺口+收官补遗+速查卡+连击考。

**主线（17-35 篇）**：8/6 全链路排查 + codewalk启动到首帧 / 8/7 Perfetto SQL库 / 8/8 A18桌面协同 / 8/9 真题大乱斗 / 8/10 ART与dex2oat / 8/11 PerfettoSQL扩充 / 8/12 核心基础八股 + 真题大乱斗vol2 / 8/13 KMP与高频追问 / 8/14 Native稳定性 / 8/15 Compose编译器与运行时 / 8/16 输入系统 / 8/17 HAL与内核驱动 / 8/18 AAOS座舱 / 8/19 启动链路system_server + KMP实战坑SwiftExport。

**本篇（36）新增价值**：①2026-08-20 当日热点锚定（A17 QPR2 Beta3 App Lock 等 + Android Canary + 面试趋势量化）②逐域最高频八股速答 + 交叉索引，便于日更速过 ③**关闭末位真缺口：Compose `$changed` 位掩码源码逐行走读**（IR 改写 + `ComposerImpl.changed` 解码 + 两大误解澄清）。

> 全系列至此 36 篇 / 约 230 专题，**主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧AI + 源码walk + Perfetto SQL + 基础八股 + 两版真题大乱斗 + Native稳定性 + Compose编译器逐行 + 综合轮训** 完整闭环。

---

## 8. 延伸阅读（按方向）

- **Handler/Looper**：读 `MessageQueue.java` + `Looper.cpp` 的 `pollInner`，自己画 epoll 唤醒时序。
- **Binder**：`drivers/android/binder.c` 的 `binder_transaction`；`frameworks/native/libs/binder` 的 `IPCThreadState`/`ProcessState`；对比 `hwbinder`/`vndbinder`（8/17）。
- **Compose 编译器**：Kotlin 仓库 `compiler/ir/backend.common.jvm.compose` 下 `ComposableFunctionBodyTransformer` + `ComposerParam`，配合 `androidx.compose.runtime` 的 `ComposerImpl` 对照读。
- **启动/oom_adj**：`ActivityManagerService` + `ProcessList` + `OomAdjuster`（8/19）。
- **Input ANR**：`InputDispatcher.cpp` 的 `dispatchOnceInnerLocked` 与超时分支（8/16）。
- **近期版本**：Android 17 behavior-changes、A17 QPR2 Beta 3 release notes（App Lock / 锁屏模糊 / 防呼叫转移诈骗）。
