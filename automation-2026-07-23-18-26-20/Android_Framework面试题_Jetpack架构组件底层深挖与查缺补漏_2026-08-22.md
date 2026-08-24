# Android Framework 面试题 · Jetpack 架构组件底层深挖与查缺补漏（第三十八篇 · 2026-08-22）

> 系列主线：37 篇 / ~236 专题已闭环（Binder/AMS/WMS/SF/ART/HAL/内核/TEE/pKVM/AAOS/端侧AI/Compose/Perfetto/Native 稳定性/源码 walk/真题大乱斗…）。
> 本篇落点 = 此前从未独立成篇的 **Jetpack 非 Compose 架构组件底层**（Lifecycle / LiveData / ViewModel / SavedState）、**协程调度与 Handler 映射**、**RecyclerView 四级缓存**。这恰好命中你列的 "Jetpack/Compose 底层机制" 中 Compose 之外那一半。基准 AOSP 14（android-14.0.0_rXX），向前兼容 A17/A18 趋势。

---

## 0. 当日热点锚定

- **A17 QPR2 Beta 3**（build CP41.260731.005，2026-08-14，stable 预计 2026-12，Pixel6/6Pro EOL）已稳定；A18（Aluminium OS）路线图仍以桌面融合 + 跨设备协同 + AppFunctions 扩张为主。
- 2026 面试高频：Handler/Looper、Binder、Compose 重组、**Jetpack 组件生命周期**、Native crash、量化指标仍为最高频深考点；其中 Lifecycle/ViewModel/LiveData 与协程是「Framework 与 App 接缝」最容易答不深的地方。
- 本篇与第 8/12 篇（View 事件）、第 8/15 篇（Compose 编译器）、第 8/19 篇（启动链路）、第 8/13 篇（KMP）形成互补：那些篇讲「系统侧」，本篇讲「App 侧组件如何挂到系统之上」。

---

## 1. 总览：本篇覆盖领域与交叉索引

| 领域 | 本篇专题 | 对应你列的热点 | 交叉篇 |
|---|---|---|---|
| Jetpack 生命周期 | §2 Lifecycle 状态机 | — | 8/19 启动、8/ 15 Compose |
| 数据感知 | §3 LiveData 粘性/线程 | 内存/状态 | 8/03 AppFunctions |
| 状态留存 | §4 ViewModel + SavedState | App 启动/配置变更 | 8/19 启动链路 |
| 并发调度 | §5 协程 vs Handler | Handler/Looper | 8/12 主线程死循环 |
| 列表渲染 | §6 RecyclerView 缓存 | View 绘制/卡顿 | 8/24 渲染合成 |
| 查缺补漏 | §7 连接图 + §8 易错红榜 | 全栈 | 见 §11 |

---

## 2. Lifecycle 状态机底层（LifecycleRegistry）

**核心类**：`androidx.lifecycle.LifecycleRegistry`（非 framework，位于 prebuilt Jetpack 模块）、`ObserverWithState`、`Lifecycling`、`ReportFragment`。

**关键事实**：
- `Lifecycle.State`：`INITIALIZED → CREATED → STARTED → RESUMED`，事件 `ON_CREATE/ON_START/ON_RESUME/ON_PAUSE/ON_STOP/ON_DESTROY/ON_ANY`。
- 状态切换由 `LifecycleRegistry.handleLifecycleEvent(Event)` → `sync()` 驱动：`forwardPass()` 正向、`backwardPass()` 反向，按观察者当前的 `ObserverWithState.mState` 与 registry 目标 state 逐个派发，保证顺序。
- **事件来源**：API 29 之前，往 Activity 注入一个无 UI 的 `ReportFragment`（`ReportFragment.injectIfNeededIn(activity)`），在它的生命周期里回调 registry；API 29+ 改用 `Application.ActivityLifecycleCallbacks` 直接转发，避免 add fragment 的开销。最终都汇入 `LifecycleRegistry`。
- `Lifecycling` 负责把 `LifecycleObserver` 适配成 `LifecycleEventObserver`：注解 `@OnLifecycleEvent` 在 2.2 之前靠反射（`ClassesInfoCache` + `MethodReference`），2.2+ 优先用编译器生成的 `LifecycleEventObserver`（`GeneratedAdapter`）；若 observer 是接口且未生成适配器，则走反射。
- **高频追问 · 晚注册的观察者**：`addObserver()` 时若 registry 已处于 `RESUMED`，新 observer 会被「回放」所有历史事件（INITIALIZED→…→当前），直到追赶上当前 state。这是「Fragment 里先 addObserver 后 onCreate 仍能收到 ON_CREATE 之前事件」的真相——observer 收到的是「到达当前状态所需的所有事件」，而非「未来事件」。

**易错点**：
- `ON_CREATE` 只会派发一次，但 `ON_ANY` 会随每次事件触发；不要把一次性初始化写进 ON_ANY。
- `getCurrentState().isAtLeast(STARTED)` 是判断「页面可见」的标准写法，不要用 `==`。

**延伸阅读**：`LifecycleRegistry.sync()` 的 `mObserverMap`（FastSafeIterableMap）支持迭代中增删，避免并发修改异常。

---

## 3. LiveData 的粘性真相、线程约束与版本号

**核心类**：`LiveData`、`LifecycleBoundObserver`、`ObserverWrapper`。

**关键事实**：
- 观察入口 `observe(owner, observer)` 包成 `LifecycleBoundObserver`（绑定 owner），从 `INITIALIZED` 之后且 owner 处于 `STARTED/RESUMED` 才算 active。`observeForever()` 则是 `AlwaysActiveObserver`，无视生命周期（要手动 `removeObserver`）。
- **粘性事件根源**（经典坑）：`ObserverWrapper.mLastVersion` 初始为 `START_VERSION(-1)`，而 `LiveData.mVersion` 每 `setValue` 自增。observer 首次激活时 `considerNotify()` 判断 `mLastVersion < mVersion` 即「补发」——所以先 `setValue` 再 `observe` 也能收到旧值。想避免粘性：用 `SingleLiveEvent`（仅消费一次）或 `EventWrapper` 包裹，或显式用 `observe` 前先 reset 版本。
- **线程**：`setValue` 必须在主线程；`postValue` 走 `ArchTaskExecutor.getInstance().postToMainThread()`（内部即主线程 Handler），且多次 `postValue` 只保留最后一次——但 `postValue` 后立刻 `getValue()` 仍是旧值（要等主线程落地）。`postValue` 与 `setValue` 并发时以 `postValue` 的 pending 为准。
- `onActive()` / `onInactive()` 控制「无观察者时停掉数据源」（如停定位、停轮询）。

**易错点**：
- `LiveData` 存的是快照，大对象会常驻内存；不应放一次性事件（导航、toast），否则配置重建会重复触发。
- `Transformations.map/switchMap` 是惰性绑定，只有在有活跃观察者时才计算；`switchMap` 切换 source 时会自动移除旧 source 的观察。

**延伸阅读**：`MediatorLiveData` 多源合并；`ComputableLiveData` 后台计算 + 主线程发布（Room 是它的消费者）。

---

## 4. ViewModel 存储、清除与配置留存（SavedState）

**核心类**：`ViewModelStore`、`ViewModelProvider`、`ViewModel`、`SavedStateHandle`、`SavedStateRegistryController`。

**关键事实**：
- `ViewModel` 存活在 `ViewModelStore` 里，而 store 的持有者是 **Activity/Fragment 本身**（ComponentActivity 持有 `mViewModelStore`），不是 application。
- **配置变更不死**：`Activity.onRetainNonConfigurationInstance()` 把 `NonConfigurationInstances`（含 `mViewModelStore`）交给 `ActivityThread`，重建后通过 `getLastNonConfigurationInstance()` 取回。所以 rotation 后新 Activity 拿到的是同一个 store → 同一个 ViewModel。
- **真正销毁才清**：`ComponentActivity.onDestroy()` 中仅当 `isFinishing()` 为真（而非 `isChangingConfigurations()`）才调 `mViewModelStore.clear()` → `ViewModel.onCleared()`。这就是为什么「旋转不死、finish 才死」。
- **SavedState**：`SavedStateRegistry` 在 `onCreate` 前（attachBaseContext 阶段）就从 `onSaveInstanceState` 的 Bundle 恢复了 `SavedStateHandle`，让 ViewModel 跨进程死亡/恢复持有 `Bundle` 级数据；`@SavedState` 通过 `SavedStateRegistryController` 注入。
- Fragment 的 ViewModel：`Fragment.mViewModelStore` 独立于 Activity；`activityViewModels()` 拿到的是同一个 Activity 级 store；`navGraphViewModels()` 绑定 NavGraph 作用域。

**易错点**：
- 不要把 `Context`（尤其 Activity）存进 ViewModel——会泄漏；用 `AndroidViewModel` 拿 `ApplicationContext` 也要谨慎。
- `ViewModel` 不等于「全局单例」；多个 Fragment 共享同一 Activity 的 View 级 VM，但每个 Fragment 自己的 VM 独立。

**延伸阅读**：`ViewModelProvider.Factory` 不传会走 `AndroidViewModelFactory`；Hilt/Compose 的 `hiltViewModel()` / `viewModel()` 都基于同一套 `ViewModelStoreOwner` 契约。

---

## 5. 协程调度器与 Handler/Looper 的映射（Framework 接缝）

**核心类**：`kotlinx.coroutines.Dispatchers`、`MainDispatcherFactory`、`HandlerContext`、`CoroutineScheduler`。

**关键事实**：
- `Dispatchers.Main` 在 Android 上由 `kotlinx.coroutines.android.MainDispatcherFactory` 构造，内部就是 `HandlerContext(Looper.getMainLooper())` —— 即 **主线程 Handler 的封装**。所以「协程切主线程」底层就是 `handler.post()`。
- `Dispatchers.Main.immediate`：若当前已在主线程（`Looper.myLooper() == mainLooper`）则**直接同步执行**，不走 post——这是「避免无谓 post」的优化，也是 `Dispatchers.Main` 与裸 Handler 的差异点。
- `Dispatchers.Default` / `Dispatchers.IO` 共用同一个 `CoroutineScheduler` 线程池（IO 默认 64 上限、Default 为 CPU 核数），IO 任务不阻塞 Default 调度。
- **与第 8/12 篇呼应**：主线程 `Looper` 靠 `epoll` 休眠不被 ANR（ANR 是 system_server 看门狗超时，不是 loop 卡死）；协程 `Main` 只是把任务投递到同一个 Looper 的消息队列，本质上仍是 `MessageQueue` 调度——所以「主线程协程挂起 ≠ 主线程空闲」，UI 帧调度仍是 Choreographer 驱动。

**易错点**：
- `withContext(Dispatchers.Main)` 在 already-main 时如果外层是 `immediate` 会变同步执行，可能改变预期时序——这正是 Concurrency Modification 陷阱来源。
- `Dispatchers.IO` 不适合做「长期阻塞 + 大量 CPU」的活，会挤占 Default 队列；重活应自建 `Executor` 或 `limitedParallelism`。

**延伸阅读**：`CoroutineDispatcher.dispatch` 与 Handler 的 `dispatchMessage` 等价；`Flow` 的 `flowOn(Dispatchers.IO)` 切换的是「上游生产者」线程，不是收集线程。

---

## 6. RecyclerView 四级缓存源码走读

**核心类**：`RecyclerView`、`Recycler`、`RecyclerViewPool`、`GapWorker`、`AdapterHelper`。

**四级缓存（复用优先级从高到低）**：
1. `mAttachedScrap` / `mChangedScrap`：仍 attach 在屏幕、仅做局部刷新/动画的 ViewHolder（position/id 精确匹配，`RecyclerView.ViewHolder.mPosition`）。
2. `mCachedViews`：刚滑出屏幕的 ViewHolder，**默认容量 2**（可通过 `setItemViewCacheSize` 调），保留原 position + 内容，命中可直接复用无需 rebind。
3. `mViewCacheExtension`：开发者自定义兜底（极少用，需自己管理复用）。
4. `mRecyclerPool`：按 `itemType` 分桶，每桶默认 5 个；**跨 RecyclerView 可共享**（如 ViewPager2 多页共用一个 Pool）——这是「列表复用在多列表间」的关键。

**关键事实**：
- `Recycler.tryGetViewHolderForPositionByDeadline()` 依次查上述四级；命中 scrap/cache 不调 `onBindViewHolder`，命中 pool 必调 `onBindViewHolder`，都没有才 `createViewHolder`。
- **局部刷新**：`notifyItemChanged(pos, payload)` → `AdapterHelper` 生成 `UpdateOp` → `mChangedScrap` + `onBindViewHolder(holder, pos, payload)` 走 payload 分支，避免整项重绑。
- **预取**：`GapWorker` 借 `Choreographer` 的 idle 时机（或 `onNestedPreScroll`）提前创建/绑定即将进入屏幕的 item，降低滑动卡顿；prefetch 的 item 进 `mCachedViews`。
- `getAdapterPosition()` vs `getLayoutPosition()`：动画/移除进行中，layout position（屏幕位置）与 adapter position（数据源位置）可能不一致；需要「当前数据索引」用 adapter position，需要「屏幕上位置」用 layout position。

**易错点**：
- ViewHolder 复用 ≠ 内容正确：复用后必须 `onBindViewHolder` 重置所有可变状态，否则出现「错位显示」（经典 convertView 陷阱的 RecyclerView 版）。
- `RecyclerViewPool` 复用要求同 itemType；不同 layout 却同 type 会导致错乱。
- 不要用 `notifyDataSetChanged()` 触发全量 diff，优先 `DiffUtil` + `ListAdapter`。

**延伸阅读**：`RecyclerView` 的 `SmoothScroller`、`ItemAnimator`（DEFAULT 动画 = `DefaultItemAnimator`）与 `PositionMap` 的关系；RecyclerView 与 `NestedScrolling` 协作在第 8/16 篇 Input 链路里有呼应。

---

## 7. 查缺补漏连接图

```
        App 生命周期 / 数据 / 状态
   +----------------+   observe    +----------------+
   |  Activity      |<------------|  LiveData      |
   | (Component)    |             |  mVersion      |
   |  mViewModelStore --+         +----------------+
   +------+---------+   |               |
          |             v
   LifecycleRegistry    ViewModelStore
   (ReportFragment/     (NonConfigInstance -> 配置变更不死)
    ACLB >=29)              |
          |                 v
          +----------> ViewModel.onCleared (仅 finish)
                        |
                  SavedStateHandle (跨进程死亡恢复)

   并发:  Dispatchers.Main  --> HandlerContext(Looper.main) --> MessageQueue
         Dispatchers.IO/Default --> CoroutineScheduler 线程池
   渲染:  RecyclerView.Recycler --> 四级缓存 --> GapWorker(prefetch via Choreographer idle)
```

---

## 8. 易错红榜 TOP18

1. Lifecycle 晚注册 observer 会「回放」事件到当前 state，不是只收未来事件。
2. LiveData 粘性是 `mVersion` 机制，不是 bug；避免靠 `observe` 时序解决一次性事件。
3. `postValue` 多次只取最后一次；`postValue` 后立刻 `getValue()` 拿不到新值。
4. LiveData 存大对象会常驻，别塞一次性事件（导航/toast）。
5. ViewModel 在 Activity 级 store，旋转不死靠 `onRetainNonConfigurationInstance`。
6. ViewModel.onCleared 只在 `isFinishing()` 时触发，不是 `isChangingConfigurations()`。
7. 不要把 Activity Context 存进 ViewModel，会泄漏。
8. `Dispatchers.Main.  immediate` 在主线程同步执行，可能改变预期时序。
9. 协程 Main 是 Handler 封装，主线程协程挂起也占用 MessageQueue。
10. `getAdapterPosition()` / `getLayoutPosition()` 在动画中可能不同。
11. RecyclerView 复用后必须重置可变状态，否则错位显示。
12. `RecyclerViewPool` 同 type 才可复用，谨慎共享（跨列表）。
13. `notifyDataSetChanged()` 应避免，用 `DiffUtil`/`ListAdapter`。
14. `mCachedViews` 默认容量 2，命中无需 rebind；`mRecyclerPool` 默认每 type 5。
15. Lifecycle `ON_ANY` 会随每次事件触发，别写一次性初始化。
16. `isAtLeast(STARTED)` 判可见，不用 `==`。
17. `observeForever` 不自动移除，必须手动 remove，否则泄漏。
18. SavedState 只恢复 Bundle 级数据，不恢复内存对象。

---

## 9. 三条高频追问链

**链 A（生命周期串讲）**：Activity 启动 → ComponentActivity 何时注入 ReportFragment → LifecycleRegistry 如何回放事件 → ViewModel 为何旋转不死 → onCleared 触发条件 → SavedState 跨进程恢复。

**链 B（数据驱动 UI）**：LiveData.setValue 改 mVersion → considerNotify 版本判定（粘性）→ dispatchingValue 遍历 observer → 协程 Main 切回主线程 → RecyclerView 局部刷新 payload。

**链 C（性能）**：滑动卡顿 → GapWorker prefetch 时机（Choreographer idle）→ 四级缓存命中率 → DiffUtil 减少 bind → IO 调度别占 Default → 主线程协程与帧调度共存。

---

## 10. AOSP / AndroidX 源码路径清单（AOSP 14 baseline）

| 模块 | 路径 |
|---|---|
| LifecycleRegistry / ObserverWithState | androidx/lifecycle/LifecycleRegistry.java（prebuilt: frameworks/support/lifecycle） |
| 生命周期事件桥接 | androidx/lifecycle/ReportFragment.java |
| 注解适配 | androidx/lifecycle/Lifecycling.java、ClassesInfoCache.java |
| LiveData | androidx/lifecycle/LiveData.java |
| ViewModelStore / Provider | androidx/lifecycle/ViewModelStore.java、ViewModelProvider.java |
| SavedState | androidx/lifecycle/SavedStateHandle.java、SavedStateRegistryController.java |
| 协程 Main | kotlinx.coroutines.android.MainDispatcherFactory.kt、HandlerContext.kt |
| 协程调度 | kotlinx.coroutines.CoroutineScheduler.kt、Dispatchers.kt |
| RecyclerView 缓存 | frameworks/base/core/java/androidx/recyclerview/widget/RecyclerView.java、Recycler.java、RecyclerViewPool.java、GapWorker.java |
| Adapter diff | androidx/recyclerview/widget/AdapterHelper.java、DiffUtil.java |

---

## 11. 与 37 篇的交叉索引（知识地图）

- 主线程调度：`8/12` Handler/Looper epoll 休眠真相 → `§5` 协程 Main = Handler 封装
- 启动链路：`8/19` ActivityThread / NonConfigurationInstances → `§4` ViewModel 配置留存
- Compose：`8/15` SlotTable/Recomposer 挂 Choreographer → Compose 用同一 Looper
- Input：`8/16` Choreographer idle → `§6` GapWorker 预取借同一 idle 时机
- 渲染：`8/24` SF/HWC 合成 → `§6` RecyclerView 是 UI 侧复用，合成是 SurfaceFlinger 侧
- KMP：`8/13` Android 侧 Kotlin-JVM→ART → `§5` 协程在 Android 即运行在 ART 主线程

---

### 累计
本篇为系列 **第 38 篇**，新增 Jetpack 架构组件（Lifecycle/LiveData/ViewModel/SavedState）+ 协程调度 + RecyclerView 缓存 5 大专题，约 **241 专题**闭环。后续可选增量：KMP 实战坑下钻（Swift Export 内部 IR）、A18 Aluminium OS 桌面融合对 WMS/CDM 的源码重构、真题大乱斗 vol.3。
