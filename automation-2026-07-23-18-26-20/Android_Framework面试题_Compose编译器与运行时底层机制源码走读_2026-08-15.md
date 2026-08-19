# Android Framework 面试题 · Compose 编译器与运行时底层机制深度源码走读（2026-08-15）

> 系列第 **30 篇** / 累计约 **195 专题**。
> 落点：关闭全系列最后一个真缺口 —— **Compose 编译器插件 IR lowering（`$composer` / `$changed` 位掩码 / restart-replaceable-movable group / 强跳过模式）源码级走读**，并把它与运行时（SlotTable / Anchor / Snapshot MVCC / Recomposer-Choreographer 时序）焊成一条完整链路。
> 横向衔接：第 13 篇讲过 Compose 编译器「高层的 IR lowering 注入 `$composer`/`$changed`」，第 20 篇讲 AndroidComposeView 在 View 树里的地位，第 28 篇讲 KMP —— 本篇是把第 13 篇「编译器」那一段**真正落到位掩码与生成代码级**，属深水区补强，不是重复。

---

## 0. 当日热点锚定（为什么今天深挖 Compose 底层）

| 信号 | 内容 | 对面试的影响 |
| --- | --- | --- |
| A17 QPR2 Beta 2 | 2026-08-03 推送，build `CP41.260701.006`，内部代号由 `CinnamonBun` 切到 `DEV`；stable 预计 2026-12；Pixel 6/6Pro 退出（EOL）。无行为变更，纯打磨。 | 经典八股仍是最高频考点，但 **Compose-First** 已成官方纲领（新 API/库/工具只面向 Compose；`Fragment`/`RecyclerView`/`ViewPager`/`android.widget` 进入 maintenance mode），Compose 底层机制权重持续上升。 |
| Kotlin 2.x / K2 编译器 | Compose 编译器自 Kotlin 2.0（K2）起**并入主编译器**，不再独立的 kotlin-compiler-embeddable 插件。 | 面试常问「为什么 Compose 要编译器插件而不是注解处理器」——答案正是 K2 的 IR 统一中间表示让 Compose 能在 lowering 阶段改写函数体。 |
| Strong Skipping 默认开启 | Compose Compiler 1.5.4 实验 → **Kotlin 2.0.20 起默认开启**（2026 已是默认）。 | 改变了 stability 心智模型：`changedInstance()` 用 `!==` 比较 unstable 参数，是本篇核心考点之一。 |
| Compose 源码归属 | Compose 是 **Jetpack 模块**（独立 release train，不走 framework Mainline），源码树在 `platform/frameworks/support`（现 `androidx` 仓库）。 | 路径前缀统一用 `frameworks/support/...`，不属于 `frameworks/base`，但真题仍把它归入「Framework 底层机制」。 |

**结论**：当用户列出「Jetpack/Compose 底层机制」时，光背 `@Composable`/`remember`/`mutableStateOf` 已经不够；考官现在会追到「`$changed` 位掩码怎么算」「强跳过改不改 stability」「SlotTable 为什么是 gap buffer」这一层。本篇即为此而生。

---

## 1. 为什么要在编译期做 Compose —— 编译器插件全景

### 1.1 它不是注解处理器，是 Kotlin 编译器插件（IR 插件）

Compose 的「魔法」全部发生在 **IR（Intermediate Representation）lowering 阶段**，而不是 kapt/ksp 的源码生成。原因：

- 注解处理器只能生成新源码，无法改写**调用方**传参；
- Compose 需要在**每一个** `@Composable` 函数签名里注入两个额外参数（`%composer`、`%changed`），并在函数体首尾插入 `startXXXGroup`/`endGroup` —— 这只能由编译器插件在 IR 上做。

源码路径（androidx 仓库）：

```
frameworks/support/compose/compiler/compiler-hosted/src/main/kotlin/
  androidx/compose/compiler/plugin/
    ComposableFunctionBodyTransformer.kt   // 函数体改写：插入 %composer/%changed、start/end group
    ComposableDeclarationsTransformer.kt   // 声明改写：给函数签名加参数、标 @Composable 内部标记
    IrComposableExprCodegenExtension.kt    // 表达式级 codegen 钩子
    FeatureFlag.kt                         // StrongSkipping 等开关
```

插件注册：

```
META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
  -> androidx.compose.compiler.plugins.kotlin.ComposeCompilerPluginRegistrar
```

### 1.2 编译流水线中的位置（为何能并入 K2 主编译器）

```
K1 解析(FE1.0) → K2(FIR) → IR lowering  ←── Compose 在这里改写 ──→ 后端(JVM / JS / Native)
```

K2 把所有前端统一成 FIR → IR，Compose 的 `ComposableFunctionBodyTransformer` 在 IR lowering 阶段运行，因此自 Kotlin 2.0 起 Compose 编译器能直接并进 Kotlin 主编译器，不再依赖独立嵌入的 `kotlin-compiler-embeddable`。这也是第 28 篇「KMP 编译器并入 K2」结论的延续。

### 1.3 面试高频追问

- **Q：为什么 `@Composable` 函数不能有默认参数以外的「函数重载」歧义 / 不能是抽象函数 / 不能被 `inline` 包成普通 lambda？**
  **A**：`@Composable` 在 IR 上被改写成多一个 `Composer?` 参数，普通函数类型 `(P) -> R` 与 `@Composable (P) -> R`（本质是 `(P, Composer?, Int) -> R`）是**不同类型**，所以普通 lambda 不能传给 `@Composable` 形参 —— 必须 `@Composable` lambda。抽象函数同理（没有函数体可注入 group）。

- **Q：为什么 Compose 不用反射/运行时代理来做这套？**
  **A**：反射无法在编译期算出每个参数的 stability、无法注入 group 边界、无法生成跳过判定代码，性能与确定性都不可接受。编译期 IR 改写是唯一能在零运行时成本下做到「声明式 diff」的路径。

---

## 2. `$composer` 参数契约 —— Composer 接口与 ComposerImpl

运行时路径：

```
frameworks/support/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/
  Composer.kt        // Composer 接口（startRestartGroup/startReplaceableGroup/startMovableGroup/endGroup/changed/changedInstance/skipToGroupEnd）
  ComposerImpl.kt    // 唯一实现，持有 SlotTable + Applier + group 栈 + readObserver
  SlotTable.kt       // gap buffer 实现
  Recomposer.kt      // 重组调度
```

### 2.1 编译后每个 `@Composable` 函数多两个（有时三个）参数

```
// 源码
@Composable fun Foo(x: Int, y: String) { ... }

// 编译后（示意）
fun Foo(x: Int, y: String, %composer: Composer?, %changed: Int) {
    %composer = %composer.startRestartGroup(<key>)
    ...
    %composer.endRestartGroup()?.updateScope { Foo(x, y, %composer, %changed) }
}
```

- `%composer: Composer?`：当前组合上下文（**不是全局单例**）。`ComposerImpl` 内部持有 SlotTable、Applier、group 栈、读观察者。
- `%changed: Int`：位掩码，记录「哪些参数变了 / 哪些类型 stable」—— 第 3 节核心。

### 2.2 group key 怎么来的（面试易错）

group key 是「组的身份」。重组时靠它在前一次组合的 SlotTable 里**匹配**同一个子树。key 由编译器用**源码位置（文件路径 + 行号 + 列号）哈希成 Int** 生成（restartable 函数则用函数名哈希 + 位置）。

**易错点**：很多人以为 key 用「函数名」—— 错。同一函数内多次调用、内联 lambda、`if/else` 分支都需要区分，所以必须用**源码位置**哈希，才能保证同一函数不同调用点拿到不同 key。这也解释了为什么「错误地抽取/内联」会改变 key 并破坏重组/状态保持。

---

## 3. `$changed` 位掩码源码走读（本篇核心）

这是 Compose 性能模型的真正引擎，也是考官最爱追的细节。

### 3.1 位分配：每个参数占 2 bit

`%changed` 是一个 `Int`，**每个函数参数占据 2 个 bit**，从低位开始：

| 位 | 名称 | 含义 |
| --- | --- | --- |
| 低位（real bit） | `real` | 该参数本次调用「是否有新值」—— 1 表示 changed |
| 高位（static bit） | `static` | 该参数类型在**编译期**推断的 stability —— 来自类型的 `$stable` 位域 |

参数 `i` 的 real = `(changed >> (i*2)) & 1`，static = `(changed >> (i*2+1)) & 1`。

**为什么要 2 bit 而不是 1 bit？** 因为需要表达四种组合：`(stable, changed)`、`(stable, unchanged)`、`(unstable, changed)`、`(unstable, unchanged)`；并且 `static` 位可以由**父调用方在编译期直接填好**，从而避免子函数运行时再做一次 equality 比较（见 3.3）。

### 3.2 编译器生成的跳过判定代码（经 Strong Skipping 验证）

以 unstable 参数 `x: Foo` 为例（来自 Compose 编译器实际产物，doveletter/juejin 已对照）：

```
@Composable fun Test(x: Foo, %composer: Composer?, %changed: Int) {
    %composer = %composer.startRestartGroup(0x123456)
    val %dirty = %changed
    if (%changed and 0b0110 == 0) {                    // 前两个 bit 未被父方填 → 运行时判定
        %dirty = %dirty or if (%composer.changedInstance(x)) 0b0100 else 0b0010
    }
    if (%dirty and 0b0011 != 0b0010) {                  // 不是「确定未变」
        A(x, %composer, 0b1110 and %dirty)
    } else {
        %composer.skipToGroupEnd()                      // 跳过整个子树，零重组
    }
    %composer.endRestartGroup()?.updateScope { Test(x, %composer, %changed) }
}
```

逐行解读：

1. `0b0110` = 参数 0 的 real(位 1) + static(位 2)。`and 0b0110 == 0` 表示父调用方已经在编译期知道了这个参数变没变（static 位已知、real 位已知），无需运行时比较。
2. `%composer.changedInstance(x)`：**用 `!==`（引用相等）** 比较当前 `x` 与上一次存进 SlotTable 的 `x`。返回 true = 变了。这是 **Strong Skipping 下 unstable 参数的比较策略**。
3. `%dirty` 用 `or` 累加所有参数的判定结果。
4. 末尾 `if (%dirty and 0b0011 != 0b0010)`：若「确定未变」（恰好落在 `0b0010` 模式）就 `skipToGroupEnd()`，否则执行函数体。

### 3.3 stable 参数走 `changed()`，不是 `changedInstance()`

若参数是 stable 类型（`Int` / `String` / `@Immutable` data class），编译器生成的是 `changed(x)` 而非 `changedInstance(x)`：

```
if (%changed and 0b0110 == 0) {
    %dirty = %dirty or if (%composer.changed(x)) 0b0100 else 0b0010
}
```

`ComposerImpl.changed(value)` 用 `!=`（结构相等 `equals`）比较；`changedInstance(value)` 用 `!==`（引用相等）。源码（`ComposerImpl.kt`）差异：

```
override fun changed(value: Any?): Boolean {       // stable 参数
    val previous = nextSlot()
    if (previous != value) { updateValue(value); return true }  // != → equals
    return false
}
override fun changedInstance(value: Any?): Boolean { // unstable 参数（强跳过）
    val previous = nextSlot()
    if (previous !== value) { updateValue(value); return true } // !== → 引用相等
    return false
}
```

还有 `changedInstanceInlined()` 用于 `value class`/`inline class` 拆箱后比较。三者都调用 `updateRecordedValueLocked/updateValue` 把本次值写回 SlotTable，供下次比较。

### 3.4 面试高频追问（位掩码专场）

- **Q：`static` 位和 `@Stable`/`@Immutable` 注解什么关系？**
  **A**：编译器对**每个类**在编译期推断 stability，生成一个 `$stable` 位域（`true`/`false`/`couldBeStable`）。函数参数的 `static` bit 直接来自其类型的 `$stable`。`static=1` 表示「编译器相信它不可变/可观测变化会通知 Compose」，于是走 `changed()`（结构相等）且可被父方预填，无需运行时 `===`。

- **Q：为什么 unstable 参数在强跳过之前「永远不能跳过」？位掩码里怎么体现？**
  **A**：关掉强跳过时，编译器对「含 unstable 必需参数」的函数设 `mightSkip = false`，**根本不生成 skip 分支**，函数体无条件执行。源码判定（`FeatureFlag.kt` + `ComposableFunctionBodyTransformer`）：
  ```
  if (!FeatureFlag.StrongSkipping.enabled && isUsed && isUnstable && isRequired) {
      mightSkip = false   // 不生成 skipToGroupEnd 分支
  }
  ```

- **Q：父调用方怎么「在编译期填好 static/real 位」？**
  **A**：父 composable 自己也经过同样的 lowering，它知道自己传给子函数的参数是常量、还是上一个 slot 里读出的值、还是本次 changed—— 这些信息在父函数编译产物里直接 OR 进传给子函数的 `%changed` 实参（如 `0b1110 and %dirty`），子函数见到非零对应位就跳过运行时比较。这就是「一层层短路」的性能来源。

---

## 4. group 三态语义：restart / replaceable / movable

`Composer` 接口提供三种 `startXxxGroup`，对应三种组合作用域：

| 类型 | 调用 | 可独立重组 | 可跳过 | 典型用途 |
| --- | --- | --- | --- | --- |
| restart group | `startRestartGroup(key)` | 是 | 是 | 普通 `@Composable` 函数体 |
| replaceable group | `startReplaceableGroup(key)` | **否** | 是 | 无控制流分支的内联内容（`if`/`when` 分支常生成这个） |
| movable group | `startMovableGroup(key)` | 是 | 是（且可整体移动） | `LazyColumn` item、`SubcomposeLayout` 子项 |

- **restart group**：`endRestartGroup()` 返回非空并 `.updateScope { ... }` 把「重组这个 group 的 lambda」存进 SlotTable。后续 `invalidate` 只重跑这一个 group，不波及父/兄弟。
- **replaceable group**：可跳过但**没有自己的重组作用域**（inline 进父作用域），所以不能单独 `invalidate`，只能随父一起重组。
- **movable group**：关键特性是可以在 SlotTable 中**整体重定位而不重跑函数体**。`LazyColumn` 滚动时 item 在 slot 流里前后移动，靠 `ComposerImpl` 配合 `SlotTable.Anchor`  relocate —— 这就是为什么滚动时 item 的本地状态（`remember`）能保持、且不发生重组。

**面试点**：「为什么 `LazyColumn` 滚动能保持 item 状态且不重组？」→ movable group + 每个 item 的 `key` → Anchor 重定位（见第 6 节），状态随 slot 一起搬家，函数体不重跑。

---

## 5. 强跳过模式（Strong Skipping）三要素

`FeatureFlag.StrongSkipping` 自 Compose Compiler 1.5.4 实验、Kotlin 2.0.20 起默认开启（2026 已是默认）。它做三件事：

1. **所有 restartable 函数即使含 unstable 参数也变 skippable**（解除「unstable 参数毒化整函数」的旧规则）。
2. **含 unstable 捕获的 lambda 自动 `remember`**（子 composable 用 lambda 参数也能跳过）。
3. **unstable 参数运行时用 `===`（引用相等）比较跳过**（`changedInstance`）。

### 5.1 最关键的澄清（易错红榜 TOP1）

> **强跳过不改变任何类型的 stability。`$stable` 位域完全相同。它只改变「跳过判定策略」。**

- `List` 在强跳过后**仍是 unstable**；
- 只是运行时用 `===` 比较它，若传入**同一个实例**就跳过；
- 一旦业务里每次传入**新实例**（典型：Room 每次 emit 都 new 一个 `List` 引用、或 `map{}` 产生新 List），`===` 失败 → **照样重组**。

所以 `@Stable`/`@Immutable` 仍有价值：当类型有「昂贵的 `equals()`」、来自外部模块（编译器无法推断 stability，默认 unstable）、或你**明确想要 `===` 语义优化**时，仍需手写注解。

### 5.2 lambda 记忆化差异

- 弱跳过（pre-2.0）：lambda **必须手动 `remember`** 才能跳过；
- 强跳过：每个 lambda **自动 wrap `remember`**，无需手写。

### 5.3 怎么验证（实战手段）

```
// build.gradle.kts / module build
composeCompiler {
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
    reportsDestination  = layout.buildDirectory.dir("compose_metrics")
}
```

产物 `composables.txt` 直接给出每个函数的 `restartable` / `skippable` / `scheme(...)` 与参数的 `stable`/`unstable` 标记，以及类的 `$stable`。面试被问「你怎么证明某个 composable 真的可跳过」时就甩这个。

---

## 6. 编译器 → 运行时接缝：SlotTable（gap buffer）

运行时路径：`frameworks/support/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/SlotTable.kt`

### 6.1 为什么是 SlotTable 而不是「树」

声明式 UI 的本质是「前后两次组合结果做 diff」。Compose 不用 View 那种父子指针树，而是把组合结果写成一条**线性的 slot 流**，再用 `groups` 元数据数组标注每个 group 的边界（anchor / key / slot count / parent）。原因：

- 线性流 + gap buffer 支持**摊销 O(1)** 的中间插入/删除（重组时在 group 边界增删高效）；
- 配合 `Anchor` 可在数组物理平移后保持逻辑位置有效（movable group 基础）。

### 6.2 两个内部数组

- `IntArray groups`：每个 group 一条记录（anchor index、key、自身 slots 数、子 group 数、parent 索引）。
- `Object[] slots`（或 `Array<Any?>`）：存放参数值、`LayoutNode`、`ReusableComposeNode` 等真实对象。

### 6.3 Anchor 与 `skipToGroupEnd()`

- **Anchor**：slot 的「逻辑位置句柄」。数组因 gap buffer 平移导致物理 index 变化时，`anchorIdx` 会被重映射，Anchor 始终指向同一个逻辑位置——这是 movable group 能「移动而不重跑」的物理基础。
- **`skipToGroupEnd()`**：读取 `groups` 里当前 group 记录的 slot 数量，把读指针直接跳到下个 group，**跳过已确定不变的整棵子树**。这正是第 3 节 `if (%dirty ... != 0b0010) skipToGroupEnd()` 的运行时落点——「跳过」不是跳过函数调用，而是跳过 SlotTable 里一大段 slot 的读取与重建。

---

## 7. Snapshot MVCC —— 「读取即订阅」

运行时路径：`snapshot/Snapshot.kt`、`SnapshotImpl.kt`、`StateObject.kt`、`derivedStateOf.kt`；`mutableStateOf()` → `StateObjectImpl`。

### 7.1 经典误区 vs 真相

- 误区：Compose 是靠 `LiveData`/`Flow` 驱动重组的。
- 真相：**Compose 用 Snapshot 系统做状态管理，天然 MVCC（多版本并发控制）**，组合作用域自带订阅生命周期。

### 7.2 readObserver：「读取即订阅」

`ComposerImpl` 在组合期间注册 `readObserver`。`StateObject.getValue()` 被读取时，把自己登记进**当前 snapshot 的 reads 集合**：

```
val x = remember { mutableStateOf(0) }
Text("${x.value}")   // 读 x.value → readObserver 把 x 登记进当前组合的 reads
```

下次 `x.value = 1` 时，写入发生在某个 `mutableSnapshot` 下；`Snapshot.apply {}` 提交时检查 **MVCC 乐观冲突**（无冲突则推进 global snapshot 版本），并通知「所有 read 过该 state 的组合作用域」失效 → Recomposer 把它加入 `pendingInvalidations` → 下一帧重组。

### 7.3 为什么这正是 Compose 的「自动订阅」

- 你**不需要** `observe()`/`collect()``；只要在 composable 里读了 `State.value`，订阅就自动建立、且随组合作用域结束自动注销（不会泄漏）。
- `derivedStateOf { ... }`：内部同样是 Snapshot 读，依赖的 state 变了才重算，且**惰性**（只在被读时计算）。

### 7.4 面试追问

- **Q：Snapshot 和普通 `Flow` 区别？**
  **A**：Snapshot 是「线程/组合作用域内的乐观并发版本视图」——读不阻塞写、写不阻塞读，提交时冲突检测；且与组合作用域绑定，自动管理订阅生命周期。`Flow` 是推送式数据流，需手动 `collect` 且要管协程作用域。

- **Q：改了 `mutableStateOf` 为什么 globalSnapshot 会影响所有组合？**
  **A**：组合发生在某个 snapshot 内；`apply` 推进 global 版本后，所有未提交的组合看到新值 → 触发失效。这就是「一处写、处处失效」的底层。

---

## 8. Recomposer 与 Choreographer —— VSYNC 时序精算

运行时路径：`Recomposer.kt`、`AndroidUiDispatcher.kt`、`androidx.compose.ui.platform.AndroidUiFrameClock`。

### 8.1 重组挂在哪个 Choreographer 回调？（高频易错）

`Recomposer` 不自己跑循环，它挂到 **`Choreographer.CALLBACK_ANIMATION`**（不是 `TRAVERSAL`）。

Choreographer 每帧回调顺序：`INPUT → ANIMATION → TRAVERSAL → COMMIT`。

- 重组调度在 **ANIMATION** 阶段：这一帧**先跑重组**（算出 `LayoutNode` 变更并标记 measure/draw 脏）；
- 紧接着 **TRAVERSAL** 阶段，`AndroidComposeView`（它本质就是一个 `View`）的 `measure/layout/draw` 被触发 → 消费重组产生的脏标记；
- 于是**同一帧内「重组 + 布局 + 绘制」一气呵成**，无额外帧延迟。

> 衔接第 13/20 篇：第 13 篇讲「Recomposer 挂在 Choreographer ANIMATION 回调」，本篇补**精确时序**——重组产物通过 AndroidComposeView 的 View 树在 TRAVERSAL 阶段消费，所以 Compose 渲染与经典 View 渲染在同帧先后完成。

### 8.2 invalidate 链路

```
state.value = x
  → Snapshot 失效（§7）
  → Recomposer.invalidateScope(scope)
  → pendingInvalidations += scope
  → 等到下一帧 Choreographer 到达（ANIMATION 阶段）
  → performRecompose → 重跑 dirty scope
```

`monotonicFrameClock` / `AndroidUiFrameClock` 用 Choreographer 提供帧边界；`LaunchedEffect` / `animateXxx` 的 `withFrameNanos` 都走它。

### 8.3 面试追问

- **Q：改状态后为什么有时「下一帧」才更新而不是立即？**
  **A**：重组是**帧驱动**的，不是同步的。即使主线程空闲，也等下一个 VSYNC 边界（除非在 measure/layout 阶段内的 immediate 操作如 `SubcomposeLayout` 即时测量）。这正是 Compose 避免「一帧内多次无效重绘」的设计。

- **Q：为什么重组放 ANIMATION 而不是 INPUT？**
  **A**：INPUT 阶段处理触摸，如果每帧输入抖动都触发重组会太频繁且抢占输入处理；ANIMATION 介于输入与遍历之间，给动画/重组一个稳定的帧内位置，且紧邻 TRAVERSAL 便于同帧完成布局绘制。

---

## 9. 易错红榜 TOP20（Compose 底层专场）

1. **强跳过≠类型变 stable**：`$stable` 位域不变，只是 unstable 参数改用 `===` 比较跳过。
2. **`===` 陷阱**：业务每次 new 实例（Room emit、`.map{}`），强跳过后仍会重组。
3. **`@Composable` 函数本质是「多一个 `Composer?` 参数」的不同类型**，普通 lambda 不能传。
4. **group key 用源码位置哈希，不是函数名**；错误抽取/内联会改变 key，破坏状态保持。
5. **`%changed` 每参数占 2 bit**（real + static），static 可由父方编译期预填。
6. **`mightSkip=false` 时不生成 skip 分支**（pre-strong-skipping 的 unstable 必需参数）。
7. **`changed()`（结构相等）≠ `changedInstance()`（引用相等）**：前者 stable 参数，后者强跳过下的 unstable 参数。
8. **SlotTable 是线性 slot 流 + gap buffer，不是树**。
9. **Anchor 提供逻辑位置句柄**，是 movable group「移动不重跑」的物理基础。
10. **`skipToGroupEnd()` 跳过的是 SlotTable 里的 slot 段，不是函数调用**。
11. **Compose 状态管理靠 Snapshot MVCC，不是 LiveData/Flow**。
12. **「读取即订阅」**：读 `State.value` 自动登记进 reads，组合结束自动注销，无泄漏。
13. **`derivedStateOf` 惰性且只在被读时重算**，依赖 state 变才失效。
14. **重组挂 `CALLBACK_ANIMATION`，不是 TRAVERSAL`**；顺序 INPUT→ANIMATION→TRAVERSAL→COMMIT。
15. **同帧内 重组(ANIMATION) → 布局绘制(TRAVERSAL)**，无额外帧延迟。
16. **改状态不会立即重组，等下一 VSYNC 边界**（帧驱动）。
17. **restart group 才有独立重组作用域**；replaceable group 只能随父重组。
18. **`LazyColumn` 滚动保持状态 = movable group + key + Anchor 重定位**。
19. **`CompositionLocal` 静态 vs 非静态**：非静态（普通 `compositionLocalOf`）在重组时需读 slot，频繁读有成本；`staticCompositionLocalOf` 编译期常量，零运行时查找（但不可动态变）。
20. **Compose 编译器自 Kotlin 2.0 并入主编译器**，靠 K2 的 IR 统一中间表示。

---

## 10. 高频追问链（3 组）

**链 A · Compose 性能（考官最爱连环）**
为什么这个列表滚动掉帧 → 是不是重组太重 → 怎么看一个 composable 是否被跳过 → `composables.txt` 的 `skippable`/`stable` 标记 → 强跳过后还需要 `@Stable` 吗 → `===` 陷阱怎么破 → derivedStateOf 何时用 → 状态粒度（把大 state 拆小，避免无关读触发重组）。

**链 B · 编译期魔法**
`@Composable` 为什么不能当普通 lambda → 为什么是编译器插件不是注解处理器 → 编译后多哪两个参数 → `$changed` 每参数几位、怎么算 → static 位和 `$stable` 什么关系 → 父方怎么预填位 → 强跳过改不改 stability（回到红榜 #1）。

**链 C · 与 Android Framework 接缝**
`AndroidComposeView` 在 View 树里是什么地位（就是一个 View）→ `Recomposer` 挂哪个 Choreographer 回调、为什么 → CompositionLocal 的 static/非 static 差异 → `Modifier.Node` 为何比 `Modifier.composed{}` 快（节点复用 vs 每次重组重建）→ 与第 20 篇 `ViewRootImpl.performTraversals` 的同帧衔接。

---

## 11. AOSP / JetBrains 源码路径清单

| 主题 | 路径（androidx 仓库 `frameworks/support` 或 `androidx` 源码） |
| --- | --- |
| 编译器插件注册 | `compose/compiler/compiler-hosted/.../ComposeCompilerPluginRegistrar.kt` |
| 函数体改写（注入 `$composer`/`$changed`、group） | `compose/compiler/.../ComposableFunctionBodyTransformer.kt` |
| 声明改写 | `compose/compiler/.../ComposableDeclarationsTransformer.kt` |
| 强跳过开关 | `compose/compiler/.../FeatureFlag.kt`（`StrongSkipping`） |
| Composer 接口 | `compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Composer.kt` |
| ComposerImpl（changed/changedInstance/updateValue） | `.../runtime/ComposerImpl.kt` |
| SlotTable（gap buffer / Anchor / skipToGroupEnd） | `.../runtime/SlotTable.kt` |
| Recomposer（帧驱动重组调度） | `.../runtime/Recomposer.kt` |
| Snapshot MVCC（readObserver / apply / 冲突检测） | `.../runtime/snapshot/Snapshot.kt`、`SnapshotImpl.kt` |
| StateObject / mutableStateOf | `.../runtime/StateObject.kt`、`SnapshotState.kt` |
| derivedStateOf | `.../runtime/derivedStateOf.kt` |
| Android 帧时钟 / Choreographer 绑定 | `.../ui/platform/AndroidUiFrameClock.kt`、`AndroidUiDispatcher.kt` |
| AndroidComposeView（Compose 在 View 树里的根） | `.../ui/platform/AndroidComposeView.android.kt` |
| CompositionLocal（static/非 static） | `.../runtime/CompositionLocal.kt` |
| Modifier.Node | `.../ui/node/ModifierNodeElement.kt`、`.../ui/node/LayoutNode.kt` |

---

## 12. 29 → 30 篇交叉索引

| 篇 | 主题 | 与本篇关系 |
| --- | --- | --- |
| 13 | 智能系统 AppFunctions × Compose-First | 高层讲过 Compose 编译器「注入 `$composer`/`$changed`」→ 本篇把它落到**位掩码与生成代码级** |
| 20 | 源码级 code walk 启动到首帧 | 讲 `AndroidComposeView`/`ViewRootImpl.performTraversals` 同帧衔接 → 本篇补 **Recomposer 挂 ANIMATION 回调的精确时序** |
| 23 / 27 | 真题大乱斗 vol1/vol2 | 含「Compose 重组 × ANI/A11y Agent 互锁」混合场景 → 本篇给该场景的**底层机制依据** |
| 28 | 跨平台运行时 KMP / CMP | 讲「Compose Multiplatform = Jetpack Compose 本身、编译器并入 K2」→ 本篇 §1.2 给**并入 K2 的工程原因** |
| 24 | ART dex2oat | 编译期 IR 改写的思想同源（编译期优化） |

**全系列至此 30 篇 / 约 195 专题**，主线（Binder/AMS/WMS/SF/ART/HAL/内核）+ 盲区 + 深水区 + 智能层 + 安全世界（TEE/pKVM）+ 座舱 + 端侧 AI + 源码 walk + Perfetto SQL + 基础八股 + 两版真题大乱斗 + Native 稳定性 + **Compose 编译器/运行时底层** 完整闭环。

---

> 复习建议：本篇是「Compose 底层」的封底章。配合第 13 篇（高层概览）与第 20 篇（与 View 树接缝）一起看，可形成「编译期魔法 → 运行时 SlotTable/Snapshot → 与 Android View 渲染同帧」的完整心智模型。面试被问到 `$changed` 位掩码或强跳过是否改 stability 时，本篇 §3 与 §5 是标准答案。
