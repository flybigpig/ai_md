# Binder 结构体版本级校订（对照 Android 14 / android14-6.1）

> 校订对象：你贴的那版「Binder 各结构体详解」。
> 验证来源：`torvalds/linux` master 的 `drivers/android/binder_internal.h` / `binder_alloc.h` / `include/uapi/linux/android/binder.h`（android-common 的 binder 驱动与主线同源，结构一致）。
> 重要：主线比 `android14-6.1`（基于 6.1）新，少数字段有差异，见第 6 节「android14-6.1 差异表」，请以你本地 AOSP kernel checkout 的 `git grep` 为准。

---

## 0. 结论速览

你原稿**方向对、但混入了老内核（~3.10/3.18 时代）的字段**。三处是「结构性错误」（跨版本），不是 android14 的小差异：

1. **缓冲区管理已被抽离成 `struct binder_alloc`** —— 你把它整段内联进了 `binder_proc`。
2. **`binder_ref` 用 `struct binder_ref_data` 打包 desc/strong/weak** —— 你写成独立字段。
3. **`binder_node` 的位域是匿名结构体、`death` 归属 `binder_ref`、`rb_node`/`dead_node` 是 union** —— 你把位域和 `work` 混成一个 `struct{...} work`，并把 `death` 错放到 node。

你**写对的**：`binder_write_read` 逐字正确；`binder_node` 里 `rb_node`/`dead_node` 是 union、`refs`(hlist_head) 在——这两点正确。

---

## 1. 最关键的结构性错误

### 1.1 缓冲区管理已不在 `binder_proc`，而在 `struct binder_alloc`

你原稿把 `buffer` / `user_buffer_offset` / `buffers` / `free_buffers` / `allocated_buffers` / `buffer_size` / `buffer_free` / `pages` / `pages_high` 全写进了 `binder_proc`。这是 **3.18 之前**的布局。`~3.18` 起这些全部抽进 `struct binder_alloc`，`binder_proc` 只留一句 `struct binder_alloc alloc;`。

真实 `binder_alloc`（来自 `binder_alloc.h`）：

```c
struct binder_alloc {
    struct mutex mutex;
    struct mm_struct *mm;
    unsigned long vm_start;          /* mmap 得到的进程地址空间基址 */
    struct list_head buffers;        /* 所有 binder_buffer 链表 */
    struct rb_root free_buffers;     /* 空闲块，按 size 红黑树 */
    struct rb_root allocated_buffers;/* 已分配块，按地址红黑树 */
    size_t free_async_space;         /* 留给 one-way 异步的 VA 配额(初值 1/2) */
    struct page **pages;             /* 物理页指针数组 */
    struct list_lru *freelist;       /* 可回收页的 LRU */
    size_t buffer_size;              /* mmap 大小 */
    int pid;
    size_t pages_high;               /* pages[] 高水位 */
    bool mapped;                     /* 仅允许一次 mmap */
    bool oneway_spam_detected;
};
```

### 1.2 `binder_buffer` 没有 `uint8_t data[0]` 柔性数组

buffer 的**数据本体**在 mmap 的用户空间（`user_data` 指向），结构体本身只做簿记。你写 `uint8_t data[0];` 是错的——那会让内核以为结构体尾部跟着数据，实际数据在另一块 VA 里。

真实 `binder_buffer`（`binder_alloc.h`）：

```c
struct binder_buffer {
    struct list_head entry;          /* 挂入 alloc->buffers */
    struct rb_node rb_node;          /* free: 按 size；allocated: 按地址 */
    unsigned free:1;
    unsigned clear_on_free:1;
    unsigned allow_user_free:1;
    unsigned async_transaction:1;
    unsigned oneway_spam_suspect:1;
    unsigned debug_id:27;
    struct binder_transaction *transaction;
    struct binder_node *target_node;
    size_t data_size;
    size_t offsets_size;
    size_t extra_buffers_size;       /* sg 等额外对象空间 */
    unsigned long user_data;         /* 在用户态的映射地址 */
    int pid;
};
```

### 1.3 `binder_ref` 用 `binder_ref_data` 打包

你写 `uint32_t desc; int strong; int weak;` 独立字段。真实是把三者（加 debug_id）打进 `binder_ref_data`：

```c
struct binder_ref_data {
    int debug_id;
    uint32_t desc;                   /* 就是用户态的 handle */
    int strong;
    int weak;
};

struct binder_ref {
    struct binder_ref_data data;     /* ← desc/strong/weak 在这里 */
    struct rb_node rb_node_desc;     /* 按 desc 查 */
    struct rb_node rb_node_node;     /* 按 node 查 */
    struct hlist_node node_entry;    /* 挂入 node->refs */
    struct binder_proc *proc;
    struct binder_node *node;
    struct binder_ref_death *death;  /* 死亡通知(per-ref)，不是 per-node */
    struct binder_ref_freeze *freeze;/* 主线新增:freezer 通知 */
};
```

---

## 2. 逐结构体对照

### 2.1 `struct binder_proc`（你的 vs 正确）

你版本的错误点：
- ❌ 内联了所有 buffer 管理字段（见 1.1）。
- ❌ 有 `struct files_struct *files;` → 现代已改为 `const struct cred *cred;`（open 时记录 cred）。
- ❌ 有 `ptrdiff_t user_buffer_offset;` → 已删除（VA 基址就是 `alloc->vm_start`）。
- ❌ 有 `int ready_threads;` → 现代由 `waiting_threads` 链表 + 线程的 `waiting_thread_node` 表达，没有这个计数器。
- ❌ 有 `wait_queue_head_t wait;` → 等待队列在**线程级**（`binder_thread.wait`），进程级只有 `todo` 链表。
- ❌ 完全没有锁 → 现代有 `inner_lock` / `outer_lock`（**android14-6.1 是 `struct mutex`**，主线最新已改成 `spinlock_t`，见第 6 节）。
- ✅ `long default_priority;` —— 这个类型对了（**不存在 `struct binder_priority`**，见第 5 节）。

真实定义（主线，`android14-6.1` 仅锁类型与少量字段差异）：

```c
struct binder_proc {
    struct hlist_node proc_node;
    struct rb_root threads;
    struct rb_root nodes;
    struct rb_root refs_by_desc;
    struct rb_root refs_by_node;
    struct list_head waiting_threads;
    int pid;
    struct task_struct *tsk;
    const struct cred *cred;                 /* 替代老的 files */
    struct hlist_node deferred_work_node;
    int deferred_work;
    int outstanding_txns;
    bool is_dead;
    bool is_frozen;                          /* freezer 支持 */
    bool sync_recv;
    bool async_recv;
    wait_queue_head_t freeze_wait;
    struct dbitmap dmap;                     /* 主线:handle 位图分配 */
    struct list_head todo;
    struct binder_stats stats;
    struct list_head delivered_death;
    struct list_head delivered_freeze;
    u32 max_threads;
    int requested_threads;
    int requested_threads_started;
    int tmp_ref;
    long default_priority;
    struct dentry *debugfs_entry;
    struct binder_alloc alloc;               /* ★ 缓冲区管理在这里 */
    struct binder_context *context;
    spinlock_t inner_lock;                   /* android14-6.1: struct mutex */
    spinlock_t outer_lock;                   /* android14-6.1: struct mutex */
    struct dentry *binderfs_entry;
    bool oneway_spam_detection_enabled;
};
```

### 2.2 `struct binder_thread`（你的 vs 正确）

你版本的错误点：
- ❌ `uint32_t return_error; uint32_t return_error2;` → 现代是 `struct binder_error return_error;`（发端错误）+ `struct binder_error reply_error;`（对端错误）+ `struct binder_extended_error ee;`。
- ❌ 缺 `looper_need_return`、`process_todo`、`waiting_thread_node`、`is_dead`、`tmp_ref`(atomic)、`ee`。

真实定义：

```c
struct binder_error {
    struct binder_work work;     /* 错误也当成 work 入队 */
    uint32_t cmd;
};

struct binder_thread {
    struct binder_proc *proc;
    struct rb_node rb_node;
    struct list_head waiting_thread_node; /* 挂入 proc->waiting_threads */
    int pid;
    int looper;                  /* BINDER_LOOPER_STATE_* 位图 */
    bool looper_need_return;     /* 对应 NEED_RETURN 标志 */
    struct binder_transaction *transaction_stack;
    struct list_head todo;
    bool process_todo;
    struct binder_error return_error;
    struct binder_error reply_error;
    struct binder_extended_error ee;
    wait_queue_head_t wait;
    struct binder_stats stats;
    atomic_t tmp_ref;
    bool is_dead;
};
```

`looper` 状态（uapi）：`REGISTERED(0x01)` / `ENTERED(0x02)` / `EXITED(0x04)` / `INVALID(0x08)` / `WAITING(0x10)` / **`NEED_RETURN(0x20)`** —— 你漏了 `NEED_RETURN`（对应上面的 `looper_need_return`）。

### 2.3 `struct binder_node`（你的 vs 正确）

你版本的错误点：
- ❌ `struct { ... } __attribute__((packed)) work;` —— 你把**位域**和 **`struct binder_work work`** 混成一个成员了。真实是：位域是**匿名结构体**（没有名字、没有 `work` 成员），而 `struct binder_work work;` 是**独立**的末尾成员。
- ❌ `struct binder_ref_death *death;` 放在 node 上 —— **死亡通知是 per-ref 的**（`binder_ref->death`），node 上没有 `death` 字段。
- ❌ 缺 `spinlock_t lock;`（node 自身锁）、`accept_fds`、`min_priority`、`txn_security_ctx`、`has_async_transaction`、`async_todo`。

你**写对的**：`rb_node`/`dead_node` 是 union（✓）、`refs`(hlist_head) 在（✓）。

真实定义（主线）：

```c
struct binder_node {
    int debug_id;
    spinlock_t lock;                     /* node 级锁 */
    struct binder_work work;             /* 独立的 work 项 */
    union {
        struct rb_node rb_node;          /* 活:挂入 proc->nodes */
        struct hlist_node dead_node;     /* 死:挂入 binder_dead_nodes */
    };
    struct binder_proc *proc;
    struct hlist_head refs;              /* 引用本 node 的 ref 链表 */
    int internal_strong_refs;
    int local_weak_refs;
    int local_strong_refs;
    int tmp_refs;
    binder_uintptr_t ptr;                /* 用户态 BBinder 指针 */
    binder_uintptr_t cookie;
    struct {                             /* ← 匿名位域结构体,无 work 成员 */
        u8 has_strong_ref:1;
        u8 pending_strong_ref:1;
        u8 has_weak_ref:1;
        u8 pending_weak_ref:1;
    };
    struct {
        u8 accept_fds:1;
        u8 txn_security_ctx:1;
        u8 min_priority;
    };
    bool has_async_transaction;
    struct list_head async_todo;
};
```

> android14-6.1 注：该版本 `binder_node` 很可能仍带 `struct kref ref;` / `struct kref refs_ref;`（kref 引用计数方案），主线已重构为上面的 `tmp_refs` + 显式计数。以你的 checkout 为准。

### 2.4 `struct binder_ref` / `binder_ref_data` / `binder_ref_death`

见 1.3。`binder_ref_death` 真实定义（**死亡通知本体在这里，不在 node**）：

```c
struct binder_ref_death {
    struct binder_work work;
    binder_uintptr_t cookie;             /* BC_REQUEST_DEATH_NOTIFICATION 给的回调 cookie */
};
```

### 2.5 `struct binder_transaction`（你的 vs 正确）

你版本的错误点：
- ❌ `unsigned int flags;` 与 `unsigned flags;` **重复声明**同一字段。
- ❌ 有 `struct binder_proc *from_proc;` → 早已移除，发起进程用 `from->proc` 取。
- ❌ 有 `struct binder_node *buffer_target_node;` / `size_t buffer_size;` / `uint32_t fd_fixups;` → 这些不对：`target_node` 在 `binder_buffer` 上；`fd_fixups` 是链表 `struct list_head fd_fixups;`；没有 `buffer_size`。
- ⚠️ `need_reply` → 主线改名为 `is_reply:1` 并新增 `is_async:1`（android14-6.1 多为 `need_reply:1` / `is_dead:1`，见第 6 节）。

真实定义（主线）：

```c
struct binder_transaction {
    int debug_id;
    struct binder_work work;
    struct binder_thread *from;
    pid_t from_pid;                      /* 主线新增调试字段 */
    pid_t from_tid;
    struct binder_transaction *from_parent;
    struct binder_proc *to_proc;
    struct binder_thread *to_thread;
    struct binder_transaction *to_parent;
    unsigned is_async:1;
    unsigned is_reply:1;
    struct binder_buffer *buffer;
    unsigned int code;
    unsigned int flags;
    long priority;
    long saved_priority;
    kuid_t sender_euid;
    ktime_t start_time;
    struct list_head fd_fixups;
    binder_uintptr_t security_ctx;
    spinlock_t lock;
};
```

### 2.6 `struct binder_work` / `enum binder_work_type`

你版本的错误点：
- ❌ 当成独立顶层 `enum` → 真实是**内嵌在 `struct binder_work` 内部**的匿名枚举，且**从 `= 1` 起步**（不是 0）。
- ⚠️ 你列的 `BINDER_WORK_NODE` —— **其实存在**（你这点没错，赞 👍）；但你漏了若干个。

真实定义（主线）：

```c
struct binder_work {
    struct list_head entry;
    enum binder_work_type {
        BINDER_WORK_TRANSACTION = 1,
        BINDER_WORK_TRANSACTION_COMPLETE,
        BINDER_WORK_TRANSACTION_PENDING,
        BINDER_WORK_TRANSACTION_ONEWAY_SPAM_SUSPECT,
        BINDER_WORK_RETURN_ERROR,
        BINDER_WORK_NODE,                /* ← 你列的这个确实存在 */
        BINDER_WORK_DEAD_BINDER,
        BINDER_WORK_DEAD_BINDER_AND_CLEAR,
        BINDER_WORK_CLEAR_DEATH_NOTIFICATION,
        BINDER_WORK_FROZEN_BINDER,       /* freezer 相关 */
        BINDER_WORK_CLEAR_FREEZE_NOTIFICATION,
    } type;
};
```

### 2.7 `struct binder_write_read`

✅ **逐字正确，无需修改。**

---

## 3. 协议层（uapi）补充校订

- ❌ `struct binder_handle` **不存在**。头文件里只有 `struct binder_handle_cookie { __u32 handle; binder_uintptr_t cookie; } __packed;`。
- `binder_transaction_data`：`sender_pid` 类型是 `__kernel_pid_t`、`sender_euid` 是 `__kernel_uid32_t`；`data` 联合里是 `buf[8]` 或 `{ buffer, offsets }`。
- `flat_binder_object`：union 内 `binder`（本地）/ `handle`（远端），外加 `cookie`。
- `binder_fd_array_object` 有 `pad` 占位；`binder_fd_object` 有 `pad_flags` / `pad_binder`。
- `BINDER_TYPE_*` 共 7 个（来自 uapi 的 enum）：

```c
BINDER_TYPE_BINDER       /* 's','b','*' */
BINDER_TYPE_WEAK_BINDER  /* 'w','b','*' */
BINDER_TYPE_HANDLE       /* 's','h','*' */
BINDER_TYPE_WEAK_HANDLE  /* 'w','h','*' */
BINDER_TYPE_FD           /* 'f','d','*' */
BINDER_TYPE_FDA          /* 'f','d','a' (fd array) */
BINDER_TYPE_PTR          /* 'p','t','*' */
```

> 主线 uapi 枚举里**没有 `BINDER_TYPE_FD_FILE`**（只有 FDA）。若你看到 `FD_FILE` 是某些 android 补丁引入，请以你 checkout 的 uapi 为准。

---

## 4. Android 14 (android14-6.1) 与主线 master 差异表

| 字段 / 结构 | android14-6.1（基于 6.1） | 主线 master |
|---|---|---|
| `inner_lock` / `outer_lock` | `struct mutex` | `spinlock_t` |
| handle 描述符分配 | 递增 `last_id`（你说的“从 1 递增”在 14 上对） | `struct dbitmap dmap`（6.3+ 引入） |
| `binder_node` 引用计数 | 很可能仍带 `struct kref ref;` / `refs_ref;` | 重构为 `tmp_refs` + 显式计数 |
| `binder_transaction` 回复标记 | `need_reply:1` / `is_dead:1` | 改为 `is_async:1` / `is_reply:1` |
| `binder_transaction` 调试字段 | 可能无 `from_pid` / `from_tid` | 有 |
| `binder_error` | 可能为 `{ uint32_t cmd; int32_t param; }` 早期形态 | 内嵌 `struct binder_work work` |
| `BINDER_TYPE_FD_FILE` | 视 android 补丁 | 主线 uapi 枚举无 |

> 这些差异不影响你原稿的**结构性错误判定**（1.1/1.2/1.3 在任何 6.x 上都成立）。

---

## 5. 我第一份文档（binder_structures.md）的 3 处错误

校订你也顺带帮我纠偏（我之前那份也写得不严谨）：

1. ❌ 写了 `struct binder_priority default_priority;` → 实为 `long default_priority;`（**不存在 `struct binder_priority`**）。你原稿的 `long` 是对的。
2. ❌ 提到 `struct binder_handle` 存在 → 错，只有 `binder_handle_cookie`。
3. ❌ uapi 列了 `BINDER_TYPE_FD_FILE` → 主线 uapi 枚举未定义该常量（只有 `FDA`）。
4. （次要）`binder_buffer.user_data` 我写成 `void __user *` → 实为 `unsigned long`。

---

## 6. 一句话总结

你的稿子是「**老 binder**（buffer 内联进 proc + `binder_ref` 用独立 desc 字段 + `binder_node` 把位域和 `work` 揉一起且 `death` 错放 node）」的拼装。
与 android14-6.1 的三处结构性不符：
① 缓冲区管理抽到了 `binder_alloc`；
② `binder_ref` 用 `binder_ref_data` 打包 desc/strong/weak；
③ `binder_node` 位域是匿名结构体、`death` 归属 `binder_ref`、`rb_node`/`dead_node` 是 union。

`binder_write_read` 你写对了（✓）。

> 需要我直接把 `binder_structures.md` 按本校正表改一版（含 android14-6.1 差异注释）就说一声。
