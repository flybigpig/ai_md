# binder_proc 添加 binder_node 数据结构变化详解

---

## 一、触发场景：什么时候会"往 proc 里添加 node"

```
═══════════════════════════════════════════════════════════════════════════════════
  binder_node 的诞生时机 — 服务发布 (addService) 链路
═══════════════════════════════════════════════════════════════════════════════════

  Server 进程 A                              ServiceManager 进程 B
  ┌────────────────────────┐                 ┌────────────────────────┐
  │ BBinder::onTransact    │                 │                        │
  │  (本地服务对象)          │                 │                        │
  │  ptr=0x7f00, cookie=0xAA│                 │                        │
  └───────────┬────────────┘                 └────────────────────────┘
              │                                         ▲
              │ Parcel 写入 flat_binder_object           │
              │ type = BINDER_TYPE_BINDER  ◄── "这是我的本地对象"
              │ binder = 0x7f00                          │
              │ cookie = 0xAA                            │
              │                                         │
              │ BC_TRANSACTION (handle=0)                │
              └──────────────▶ [Binder 驱动] ───────────┘
                                      │
                    binder_transaction() 处理 BINDER_TYPE_BINDER:
                                      │
                    ┌─────────────────┴──────────────────┐
                    │ ① binder_get_node(proc_A, ptr)     │  在【发送方 A】的
                    │    → 查找/创建 binder_node          │  nodes 红黑树中
                    │                                     │  ★ node 挂在谁家由
                    │ ② binder_inc_ref_for_node(proc_B,   │    "谁拥有对象"决定
                    │    node)                            │
                    │    → 在【接收方 B】创建 binder_ref    │
                    │ ③ fixup: BINDER_TYPE_BINDER         │
                    │    → BINDER_TYPE_HANDLE (desc=N)    │
                    └─────────────────────────────────────┘

  ★ 核心结论: node 永远创建在【对象的拥有者进程】的 proc->nodes 中
             ref 创建在【引用方进程】的 proc->refs_by_node/refs_by_desc 中
```

---

## 二、数据结构变化：逐步快照

### Step 0 — 初始状态（node 添加之前）

```
═══════════════════════════════════════════════════════════════════════════════════
  快照 0: proc->nodes 是一棵空红黑树
═══════════════════════════════════════════════════════════════════════════════════

  struct binder_proc (进程 A)
  ┌─────────────────────────────────────────────────────────────────────────────┐
  │  ...                                                                        │
  │  struct rb_root nodes;            ──▶ rb_root { rb_node = NULL }  ← 空树!  │
  │  struct rb_root refs_by_node;     ──▶ 空                                    │
  │  struct rb_root refs_by_desc;     ──▶ 空                                    │
  │  spinlock_t inner_lock;           (保护 nodes/refs 树的插入删除)             │
  │  ...                                                                        │
  └─────────────────────────────────────────────────────────────────────────────┘

  内核中尚不存在任何代表 ptr=0x7f00 服务对象的结构
  flat_binder_object 只是用户态 Parcel 里的一串字节
```

### Step 1 — binder_new_node(): 分配 + 字段初始化

```c
// ═══════════════════════════════════════════════════════════════════════
// drivers/android/binder.c
// 作用: 为发送方的本地对象创建内核侧 binder_node
// 前置: proc->inner_lock 已持有 (ilocked 后缀表示需持有该锁)
// ═══════════════════════════════════════════════════════════════════════

static struct binder_node *binder_new_node(struct binder_proc *proc,
					   struct flat_binder_object *fp)
{
	// ① 先分配一块全零的 node 内存
	struct binder_node *new_node = kzalloc(sizeof(*new_node), GFP_KERNEL);
	if (!new_node)
		return NULL;

	// ② 持锁初始化 + 插树
	binder_inner_proc_lock(proc);
	node = binder_init_node_ilocked(proc, new_node, fp);
	binder_inner_proc_unlock(proc);

	if (node != new_node) {
		/* ★ 幂等保护: 另一个线程抢先插入了相同 ptr 的 node
		 *   返回已存在的 node, 释放刚分配的这块 */
		kfree(new_node);
		return node;
	}

	// ③ 事务期间临时引用保护
	//    防止 node 在本次事务结束前被并发释放
	binder_node_lock(node);
	binder_inc_node_tmpref_ilocked(node);   // node->tmp_refs++
	binder_node_unlock(node);

	return node;
}
```

```c
static struct binder_node *binder_init_node_ilocked(
					struct binder_proc *proc,
					struct binder_node *new_node,
					struct flat_binder_object *fp)
{
	// ─── 字段初始化 (kzalloc 已保证全零) ───────────────────────────────
	new_node->debug_id = atomic_inc_return(&binder_last_id); // 全局唯一调试 ID
	new_node->proc  = proc;            // ★ 回指拥有者进程
	new_node->ptr   = fp->binder;      // 用户态 BBinder 地址 (红黑树的 key!)
	new_node->cookie = fp->cookie;     // 用户态上下文指针, 事务时原样回传
	new_node->work.type = BINDER_WORK_NODE;  // 预置工作类型 (异步 todo 用)

	// 引用相关计数: kzalloc 全零 → local/internal/tmp refs 均为 0
	// refs hlist: 全零 → 空链表, 尚无任何 ref 指向它

	// ─── 从 fp->flags 解析属性 ─────────────────────────────────────────
	new_node->accept_fds = !!(fp->flags & FLAT_BINDER_FLAG_ACCEPTS_FDS);
	new_node->txn_security_ctx = !!(fp->flags & FLAT_BINDER_FLAG_TXN_SECURITY_CTX);
	// min_priority/sched_policy: 从 flags 提取, 影响同步事务的调度优先级继承

	// ─── ★ 插入 proc->nodes 红黑树 (key = ptr) ─────────────────────────
	// 新内核 (6.x): binder_insert_node(proc, new_node)
	//   - 持 proc->inner_lock 遍历树
	//   - key 相同 → 返回 false (已有节点, 由上层做幂等处理)
	//   - 否则 tmp_refs++ 并 rb_link_node + rb_insert_color
	// 老内核 (4.x): 上述逻辑内联在本函数尾部
	...
	return new_node;
}
```

### Step 2 — proc->nodes 树的变化（before / after）

```
═══════════════════════════════════════════════════════════════════════════════════
  快照 1: 一棵空树 → 一棵含单节点的红黑树
═══════════════════════════════════════════════════════════════════════════════════

  ── BEFORE ─────────────────────────────────────────────────────────────────────

  proc_A->nodes:  rb_root ──▶ NULL

  ── AFTER ──────────────────────────────────────────────────────────────────────

  proc_A->nodes (按 ptr 升序的红黑树, key = fp->binder 用户指针)
        │
        ▼
  ┌──────────────────────────────────────────────────────────────┐
  │ binder_node                                                  │
  │                                                              │
  │  debug_id = 12            (全局递增, 调试用)                   │
  │  lock        = spinlock   (保护本 node 字段)                   │
  │  work        = { type = BINDER_WORK_NODE }                    │
  │                                                              │
  │  union {                                                     │
  │      rb_node   ──挂入──▶ proc_A->nodes 红黑树  ★ 当前态      │
  │      dead_node ─(备用)   ─挂入──▶ binder_dead_nodes 全局链表  │
  │  }                             (仅 proc 死亡后启用, 见第七节) │
  │                                                              │
  │  proc  = ────▶ proc_A          (回指拥有者)                  │
  │  refs  = hlist ──▶ NULL        (尚无任何 ref 指向本 node)     │
  │                                                              │
  │  ptr    = 0x7f00              (用户态 BBinder 地址, 树的 key)  │
  │  cookie = 0xAA                                               │
  │                                                              │
  │  internal_strong_refs = 0     (远程强引用聚合数, 尚无)          │
  │  local_weak_refs      = 0                                    │
  │  local_strong_refs    = 0                                    │
  │  tmp_refs             = 1  ★ (事务期间临时保护, 事务结束-1)    │
  │                                                              │
  │  min_priority / sched_policy  (优先级继承配置)                 │
  │  accept_fds = ? / txn_security_ctx = ?                       │
  │  has_strong_ref / pending_strong_ref / has_weak_ref /        │
  │  pending_weak_ref = false     (owner 侧引用协商状态)           │
  │  has_async_transaction = false (oneway 流控)                  │
  │  async_todo = list_head       (oneway 事务排队链)             │
  └──────────────────────────────────────────────────────────────┘

  ★ binder_proc 侧变化: 仅有 nodes.rb_root 从 NULL → 指向此节点
    其余字段 (threads/refs_by_node/refs_by_desc/alloc...) 本步不变
```

---

## 三、联动变化：接收方 ref 的创建（同一事务内完成）

node 落树只是第一步。同一事务中，内核会立刻在**接收方**建立 ref，形成完整闭环：

```
═══════════════════════════════════════════════════════════════════════════════════
  快照 2: ref 创建后的全局关系 (node 侧 + ref 侧 同时变化)
═══════════════════════════════════════════════════════════════════════════════════

  proc_A (Server)                                proc_B (ServiceManager)
  ┌──────────────────────────┐                   ┌──────────────────────────────────┐
  │  nodes (rb_tree by ptr)  │                   │  refs_by_node (rb_tree by node)  │
  │        │                 │                   │        │                         │
  │        ▼                 │     node 指针      │        ▼                         │
  │  ┌────────────────────┐  │                   │  ┌──────────────────┐            │
  │  │ binder_node        │◀─┼───────────────────┼──│ binder_ref       │            │
  │  │  ptr=0x7f00        │  │                   │  │  node ──────────▶│ (回指)      │
  │  │  cookie=0xAA       │  │                   │  │  proc = proc_B   │            │
  │  │                    │  │                   │  │                  │            │
  │  │  refs (hlist)      │◀─┼───┐               │  │  desc = 1        │            │
  │  │   │                │  │   │               │  │  strong/weak = 0 │            │
  │  │   ▼                │  │   │               │  └───────┬──────────┘            │
  │  │ [binder_ref]───────┼──┼───┼───────────────┼──────────┘                       │
  │  │  node_entry        │  │   │  (同一 ref 同时出现在:                           │
  │  └────────────────────┘  │   │   node->refs hlist                            │
  │                          │   │   proc_B->refs_by_node 树                      │
  │                          │   │   proc_B->refs_by_desc 树  ← 按 desc=1 挂入)   │
  │                          │   │                                               │
  │                          │   │               │  refs_by_desc (rb_tree by desc)│
  │                          │   │               │        │                        │
  │                          │   └───────────────┼────────▼                        │
  │                          │                   │  ┌──────────────────┐           │
  │                          │                   │  │ binder_ref       │           │
  │                          │                   │  │  desc = 1        │           │
  │                          │                   │  └──────────────────┘           │
  └──────────────────────────┘                   └──────────────────────────────────┘

  一个 binder_ref 的"三处挂接" (同时属于三个容器):
  ┌─────────────────────────────────────────────────────────────────────────────┐
  │ ① node->refs          hlist   (node 侧: 知道"谁引用了我")                    │
  │ ② proc_B->refs_by_node rb 树  (按 node 指针查找 → 引用计数/死亡通知管理)       │
  │ ③ proc_B->refs_by_desc rb 树  (按 desc 查找   → 事务路由)                    │
  └─────────────────────────────────────────────────────────────────────────────┘

  desc 分配规则:
  ┌─────────────────────────────────────────────────────────────────────────────┐
  │ if (node == context->binder_context_mgr_node)                               │
  │     desc = 0;            /* ★ ServiceManager 固定 0 */                      │
  │ else                                                                        │
  │     desc = 最低未使用值 (≥1);                                                │
  │     /* 老内核: 线性查找空闲 desc; 新内核: proc 内 dbitmap 位图管理 */          │
  └─────────────────────────────────────────────────────────────────────────────┘

  flat_binder_object 的 fixup (同一份 Parcel 数据, 两端看到的类型不同):
  ┌─────────────────────────────────────────────────────────────────────────────┐
  │  发送方写入:  type = BINDER_TYPE_BINDER,  binder=0x7f00, cookie=0xAA         │
  │                    │ copy_from_user + 逐对象 fixup                          │
  │                    ▼                                                        │
  │  接收方读取:  type = BINDER_TYPE_HANDLE, handle=1,  cookie 保留              │
  │              (0x7f00 这个用户态指针绝不跨进程泄漏!)                           │
  └─────────────────────────────────────────────────────────────────────────────┘
```

### 同进程特例：不产生 ref

```c
// binder_translate_binder() 中:
if (node->proc == target_proc) {
	// 发送方 == 接收方 (进程内自调用):
	// ① 不创建 ref, 不分配 desc
	// ② 对象类型保持 BINDER_TYPE_BINDER, ptr/cookie 原样传递
	// ③ nodes 树中本次查找命中已有节点 (幂等), 无新增
	...
}
```

---

## 四、字段初值速查表（kzalloc 后 + init 后）

| 字段 | 初值 | 由谁设置 | 意义 |
|------|------|---------|------|
| `debug_id` | 全局自增 | `binder_init_node_ilocked` | 调试追踪 |
| `lock` | 已初始化 | kzalloc+init | 保护 node 字段 |
| `work.type` | `BINDER_WORK_NODE` | init | 驱动侧工作项类型 |
| `rb_node` | **挂入 `proc->nodes`** | 插树 | 存活时的容器 |
| `dead_node` | (union 备用) | proc 死亡时 | 死亡后容器 |
| `proc` | `proc_A` | init | **回指拥有者，判空即知死活** |
| `refs` | 空 hlist | kzalloc | 所有引用方集合 |
| `internal_strong_refs` | 0 | 引用协商时 | 远程强引用聚合 |
| `local_weak_refs` / `local_strong_refs` | 0 | 引用协商时 | owner 侧引用 |
| `tmp_refs` | **1**（事务期间） | `binder_new_node` 尾部 | 事务临时保护 |
| `ptr` / `cookie` | `fp->binder` / `fp->cookie` | init | 用户态标识 / 树 key |
| `min_priority`/`sched_policy` | 从 `fp->flags` | init | 优先级继承 |
| `accept_fds` | `fp->flags` 位 | init | 是否允许传 fd |
| `txn_security_ctx` | `fp->flags` 位 | init | 是否携带安全上下文 |
| `has/pending_strong/weak_ref` | false | 引用协商时 | owner 侧 BR_ACQUIRE 协商状态 |
| `has_async_transaction` | false | init | oneway 流控标志 |
| `async_todo` | 空 list | kzalloc | oneway 事务排队 |

---

## 五、不变量（Invariants）——理解结构的钥匙

```
═══════════════════════════════════════════════════════════════════════════════════
  添加 node 后必须恒成立的五条不变量
═══════════════════════════════════════════════════════════════════════════════════

  I1. 唯一性:  同一 proc->nodes 中, ptr 互不相同
      → 幂等插入保证: 相同 ptr 的第二次发布只会命中已有节点

  I2. 双向一致:  node->proc == proc  ⇔  node.rb_node 挂在 proc->nodes 中
      → node->proc 置 NULL 的那一刻必然已从树上摘除 (见第七节)

  I3. 引用闭合:  任意 ref 满足 ref->node->proc == ref->proc->... 
      → 每条 node→ref 边都在 node->refs hlist 中有反向边 (node_entry)

  I4. 计数守恒:  node 的存活 ⇔ (tmp_refs > 0) 或 (proc 存在且 owner 侧计数 > 0)
                              或 (∃ ref 使 ref->strong/weak > 0)
      → 所有归零路径最终都汇聚到 binder_dec_node → 触发销毁判定

  I5. 锁序:      proc->inner_lock (树) → node->lock (字段) → proc->outer_lock
      → 插入/查找 nodes 树必须持 inner_lock; 改 node 字段必须持 node->lock
```

---

## 六、老内核 vs 新内核的差异速查

| 项 | 老内核 (4.x) | 新内核 (5.x/6.x) |
|----|-------------|------------------|
| ref 树命名 | `proc->refs`（按 node）+ `refs_by_desc` | `proc->refs_by_node` + `refs_by_desc` |
| node 锁 | 无独立锁，靠 proc 锁 | `node->lock` 独立 spinlock |
| 树锁 | `proc->proc_node_lock` 等多个锁 | 统一 `proc->inner_lock`（读）+ `outer_lock`（写） |
| desc 分配 | 遍历查找最小空闲值 | `dbitmap` 位图 O(1) 分配/回收 |
| 插树函数 | 逻辑内联于 `binder_init_node_ilocked` | 独立 `binder_insert_node()` |
| `tmp_refs` | 无（用临时计数策略不同） | 显式 `tmp_refs` 字段 |

---

## 七、反向过程：node 从树中消失时发生了什么

```
═══════════════════════════════════════════════════════════════════════════════════
  node 的两条消亡路径 (union 字段切换是核心看点)
═══════════════════════════════════════════════════════════════════════════════════

  路径 1: owner 存活, 引用全部释放
  ────────────────────────────────────────────────────────────────────────────────
  所有 ref 释放 (BC_RELEASE/BC_DECREFS 归零) 且 owner 侧 local refs 归零
        │
        ▼
  binder_dec_node() 判定可销毁
        │
        ▼
  摘除: rb_erase(&node->rb_node, &proc->nodes)     ← 树变化: 单节点树 → 空树
  node->proc = NULL                                 ← I2 不变量解除
  kfree(node)  (tmp_refs 归零后延迟释放)

  路径 2: owner 进程死亡, 但仍有进程持有 ref
  ────────────────────────────────────────────────────────────────────────────────
  proc A 退出 → binder_free_proc() → 逐个 binder_node_release(node)
        │
        ▼
  ┌─ if (hlist_empty(&node->refs)) ────────────────────────────────────────────┐
  │   无任何 ref → 直接 kfree, 树中摘除, 流程同路径 1                           │
  └────────────────────────────────────────────────────────────────────────────┘
        │ refs 非空
        ▼
  ┌─ "化尸为鬼": union 字段切换 ────────────────────────────────────────────────┐
  │                                                                            │
  │   BEFORE (存活态):                    AFTER (死亡态):                       │
  │   union { rb_node; dead_node; }       union { rb_node; dead_node; }         │
  │            │                                       │                        │
  │   rb_node 挂在 proc_A->nodes          dead_node 挂到全局 binder_dead_nodes   │
  │   (proc_A 死亡时树已随 proc 整体失效)   hlist (由 binder_free_proc 驱动       │
  │                                        遍历清理残余死节点)                    │
  │                                                                            │
  │   node->proc = NULL          ★ 之后任何 binder_get_node 都不可能再命中它     │
  │   local_*_refs = 0            (仍活着只是为了给 ref 持有者发死亡通知)         │
  │   has_strong/weak_ref = false                                               │
  └────────────────────────────────────────────────────────────────────────────┘
        │
        ▼
  遍历 node->refs hlist → 对每个 ref 的 proc 投递 BINDER_WORK_DEAD_BINDER
        │
        ▼
  各 Client 收到 BR_DEAD_BINDER → ACK → ref 释放
        │
        ▼
  最后一个 ref 释放 → 死 node 无引用 → 从 binder_dead_nodes 摘除 → kfree
```

---

## 八、一图总结：添加前后的全量差异

```
═══════════════════════════════════════════════════════════════════════════════════
  DIFF 视图: binder_proc 添加 binder_node 引起的所有结构变化
═══════════════════════════════════════════════════════════════════════════════════

  变化点                                   之前            之后
  ─────────────────────────────────────────────────────────────────────────────
  proc_A->nodes.rb_root                   NULL           ──▶ binder_node  ★唯一变化点(proc 侧)
  ─────────────────────────────────────────────────────────────────────────────
  (新对象) binder_node 本体                不存在          分配+初始化+入树
  (新对象) node->proc 回指                 —              ──▶ proc_A
  (新对象) node->refs hlist                —              空链表
  (新对象) node->tmp_refs                  —              1 (事务期)
  ─────────────────────────────────────────────────────────────────────────────
  proc_A->refs_by_node / refs_by_desc     不变            不变 (node 不是 ref!)
  proc_A->threads / alloc / todo          不变            不变
  ─────────────────────────────────────────────────────────────────────────────
  (同一事务内联动) proc_B->refs_by_node    NULL           ──▶ binder_ref
  (同一事务内联动) proc_B->refs_by_desc    NULL           ──▶ 同一 binder_ref
  (同一事务内联动) node->refs hlist        NULL           ──▶ binder_ref.node_entry
  (同一事务内联动) Parcel 中对象类型        TYPE_BINDER    TYPE_HANDLE (desc=1)
  ─────────────────────────────────────────────────────────────────────────────
```

**记忆口诀**：**"proc 加 node 只动一棵树；ref 补三处；类型改一个。"**
- proc 加 node → 只动 `proc->nodes` 这一棵红黑树
- ref 补三处 → node->refs 链表 + 接收方两棵 ref 树
- 类型改一个 → `BINDER_TYPE_BINDER` fixup 成 `BINDER_TYPE_HANDLE`
