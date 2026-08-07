# Binder 各结构体详解（Android 14 / android14-6.1）

> 基线: kernel common `android14-6.1`
> 协议头: `include/uapi/linux/android/binder.h`
> 驱动内部: `drivers/android/binder_internal.h` + `drivers/android/binder_alloc.h` + `binder.c`
> 配套: `frameworks/native/libs/binder/`（`Parcel.cpp` / `IPCThreadState.cpp`）

Binder 的结构体天然分成两层，理解时必须区分“**协议结构（跨边界的 wire format）**”和“**驱动内部数据结构（仅存活于内核）**”：

- **协议层**（`uapi`）：用户态和内核态约定好的字节布局，通过 `ioctl(BINDER_WRITE_READ)` 的 `write_buffer`/`read_buffer` 传输。
- **内核层**（`binder_internal.h`）：驱动自己用来管理进程、线程、节点、引用、缓冲区的数据结构，用户态看不到。

---

## 一、用户态协议层（include/uapi/linux/android/binder.h）

### 1.1 `struct binder_write_read` —— 一次 ioctl 的载体

```c
struct binder_write_read {
    binder_size_t write_size;       /* 写区字节数，0 表示不写 */
    binder_size_t write_consumed;   /* 已消费的写字节数 */
    binder_uintptr_t write_buffer;  /* 指向 BC_* 命令流 */
    binder_size_t read_size;        /* 读区字节数，0 表示不读 */
    binder_size_t read_consumed;    /* 已消费的读字节数 */
    binder_uintptr_t read_buffer;   /* 指向 BR_* 返回流 */
};
```

IPCThreadState 每次 `talkWithDriver()` 就是填好这个结构，再 `ioctl(fd, BINDER_WRITE_READ, &bwr)`。`write_buffer`/`read_buffer` 里装的是一串 `BC_*`/`BR_*` 命令，命令体里嵌套下面这些 object。

### 1.2 Object 体系（generic object model）

Android 8+ 把老的 `flat_binder_object` 演进成带 `binder_object_header` 的统一头结构，所有对象都先有 `hdr.type`：

```c
struct binder_object_header {
    __u32 type;
};
```

`type` 取值（关键）：
- `BINDER_TYPE_BINDER` / `BINDER_TYPE_WEAK_BINDER`：本进程中真实的 BBinder 实体（本地引用）。
- `BINDER_TYPE_HANDLE` / `BINDER_TYPE_WEAK_HANDLE`：对端给的“句柄”，代表远程 BpBinder。
- `BINDER_TYPE_FD` / `BINDER_TYPE_FD_FILE`：文件描述符跨进程传递。
- `BINDER_TYPE_PTR`：普通 buffer（含嵌套 object 的父 buffer）。

#### 1.2.1 `struct flat_binder_object`（兼容老路径）

```c
struct flat_binder_object {
    struct binder_object_header hdr;
    __u32 flags;                    /* 如 FLAT_BINDER_FLAG_SCHED_POLICY / ONEWAY */
    union {
        binder_uintptr_t binder;    /* 本地实体指针（仅本进程有意义） */
        __s32 handle;               /* 远端句柄 */
    };
    binder_uintptr_t cookie;        /* BBinder::onTransact 透传的 user data */
};
```

`flags` 的 `FLAT_BINDER_FLAG_*` 决定调度策略、是否 accept buffers、inherit RT 等。

#### 1.2.2 `struct binder_handle`（纯句柄对象）

```c
struct binder_handle {
    struct binder_object_header hdr;
    __u32 handle;
};
```

#### 1.2.3 `struct binder_ptr_cookie`（实体对象，含 cookie）

```c
struct binder_ptr_cookie {
    struct binder_object_header hdr;
    binder_uintptr_t ptr;           /* BBinder 指针 */
    binder_uintptr_t cookie;
};
```

#### 1.2.4 `struct binder_buffer_object`（包裹一段内存，可含嵌套对象）

```c
struct binder_buffer_object {
    struct binder_object_header hdr;
    __u32 flags;                    /* 如 BINDER_BUFFER_FLAG_HAS_PARENT */
    binder_uintptr_t buffer;        /* 用户态 buffer 地址 */
    binder_size_t length;
    binder_size_t parent;          /* 父 object 在 offset 数组中的下标 */
    binder_size_t parent_offset;   /* 在父 buffer 内的偏移 */
};
```

`data_size` 之后紧跟 `offsets_size` 字节的“偏移表”，里面记录每个嵌套 object 相对 `data` 的偏移。驱动据此识别 Parcel 里哪些位置是 binder 实体/句柄/FD，做引用计数与转换。

#### 1.2.5 `struct binder_fd_object` / `struct binder_fd_array_object`

```c
struct binder_fd_object {
    struct binder_object_header hdr;
    __u32 pad_flags;
    union {
        binder_uintptr_t pad_binder;
        __s32 fd;
    };
    binder_sintptr_t cookie;
};

struct binder_fd_array_object {
    struct binder_object_header hdr;
    __u32 num_fds;
    binder_size_t parent;
    binder_size_t parent_offset;
};
```

FD 跨进程时，驱动在目标进程 `alloc` 出新的 fd（通过 `task_get_unused_fd_flags` + `fd_install`），并改写目标 Parcel 里的 fd 值。

### 1.3 `struct binder_transaction_data` —— 一次 transact 的数据包描述

```c
struct binder_transaction_data {
    union {
        __u32 handle;               /* 目标 handle（client 侧） */
        binder_uintptr_t ptr;       /* 目标 BBinder 指针（server 侧） */
    } target;
    binder_uintptr_t cookie;        /* server 侧 onTransact 的 cookie */
    __u32 code;                     /* transact code，即你调用的接口方法号 */
    __u32 flags;                    /* TF_ONE_WAY 等 */
    pid_t sender_pid;
    uid_t sender_euid;
    binder_size_t data_size;        /* 真正 payload 字节数 */
    binder_size_t offsets_size;     /* 偏移表字节数 */
    union {
        struct {
            binder_uintptr_t buffer;/* 指向 data 区 */
            binder_uintptr_t offsets;/* 指向偏移表 */
        } ptr;
        __s64 buf;
    } data;
};
```

扩展结构 `binder_transaction_data_sg` 在末尾加了 `binder_size_t buffers_size;`（scatter-gather 的额外 buffer 区）以及安全上下文相关字段，用于 `binder_txn_security_ctx`。

### 1.4 BC_* / BR_* 命令串

`write_buffer` 是 `BC_*` 命令流（Binder Command，用户→驱动）：`BC_TRANSACTION`、`BC_REPLY`、`BC_FREE_BUFFER`、`BC_INCREFS`、`BC_ACQUIRE`、`BC_REQUEST_DEATH_NOTIFICATION` 等。

`read_buffer` 是 `BR_*` 返回流（Binder Return，驱动→用户）：`BR_TRANSACTION`、`BR_REPLY`、`BR_TRANSACTION_COMPLETE`、`BR_DEAD_BINDER`、`BR_INCREFS`、`BR_ACQUIRE` 等。

这些不是结构体，而是以 `__u32 cmd` 打头的可变长命令，后面跟对应参数（如 `binder_transaction_data`、`binder_ptr_cookie`）。

---

## 二、内核驱动内部数据结构（仅存活于内核）

### 2.1 `struct binder_proc` —— 一个进程在驱动中的化身

路径：`binder_internal.h`

```c
struct binder_proc {
    struct hlist_node proc_node;        /* 全局 binder_procs 哈希链表节点 */
    struct rb_root threads;             /* 本进程所有 binder_thread，按 pid 红黑树 */
    struct rb_root nodes;               /* 本进程导出的 binder_node，按 ptr 红黑树 */
    struct rb_root refs_by_desc;        /* 引用：按 desc(handle) 红黑树 */
    struct rb_root refs_by_node;        /* 引用：按 node 指针红黑树 */
    struct list_head waiting_threads;   /* 等待 command 的线程链表 */
    int pid;
    struct task_struct *tsk;
    bool is_dead;                       /* 进程已死，清理中 */
    struct list_head todo;              /* 进程级待处理 work 队列 */
    struct binder_stats stats;
    struct list_head delivered_death;   /* 已投递的死亡通知 */
    int max_threads;                    /* BC_SET_MAX_THREADS 设置 */
    int requested_threads;
    int requested_threads_started;
    int tmp_ref;
    struct binder_alloc alloc;          /* ★ 本进程的 mmap 缓冲区管理器 */
    struct mutex inner_lock;            /* 保护 proc/thread/node/ref/buffer 字段 */
    struct mutex outer_lock;            /* 保护拓扑关系（加锁顺序 outer→inner） */
    struct binder_context *context;     /* binder / hwbinder / vndbinder 上下文 */
    ...
};
```

要点：
- 一个进程只 `mmap` 一次，得到一块共享内存，由 `alloc` 管理。
- `refs_by_desc` 是“handle → binder_ref”的索引；用户态 `BpBinder(handle)` 拿到 handle，驱动通过它查到 `binder_ref`，再查到 `binder_node`。
- `context` 区分 `/dev/binder`、`/dev/hwbinder`、`/dev/vndbinder`（三者共用一份驱动，但 `binder_context` 不同，互不互通）。

### 2.2 `struct binder_thread` —— 一个参与 IPC 的线程

```c
struct binder_thread {
    struct binder_proc *proc;
    struct rb_node rb_node;             /* 挂入 proc->threads */
    struct list_head waiting_thread_node;
    int pid;
    int looper;                         /* BINDER_LOOPER_STATE_* 位图 */
    bool looper_need_return;
    struct binder_transaction *transaction_stack; /* 该线程进出中的事务栈 */
    struct list_head todo;              /* 线程级 todo 队列 */
    bool process_todo;
    struct binder_error return_error;
    struct binder_error reply_error;
    wait_queue_head_t wait;             /* 无消息时在此等待 */
    struct binder_stats stats;
};
```

`looper` 标志：`BINDER_LOOPER_STATE_REGISTERED`（已 BC_ENTER_LOOPER）、`BINDER_LOOPER_STATE_ENTERED`、`BINDER_LOOPER_STATE_EXITED`、`BINDER_LOOPER_STATE_INVALID`。线程由 `binder_get_thread()` 懒创建，首次 `BINDER_WRITE_READ` 且 `thread == NULL` 时分配。

### 2.3 `struct binder_node` —— 一个 Binder 实体（BBinder）在内核的表示

```c
struct binder_node {
    struct binder_proc *proc;           /* 拥有该实体的进程 */
    struct rb_node rb_node;             /* 挂入 proc->nodes */
    struct hlist_node dead_node;        /* 进程死后移入 binder_dead_nodes */
    struct kref refs_ref;               /* 引用这个 node 的 ref 计数 */
    int internal_strong_refs;
    int local_weak_refs;                /* 实体所在进程本地持的弱引用 */
    int local_strong_refs;              /* 实体所在进程本地持的强引用 */
    int tmp_refs;
    binder_uintptr_t ptr;               /* 对应 BBinder 用户态指针 */
    binder_uintptr_t cookie;            /* onTransact 透传值 */
    struct {
        u8 has_strong_ref:1;
        u8 pending_strong_ref:1;
        u8 has_weak_ref:1;
        u8 pending_weak_ref:1;
    };
    struct kref ref;
    bool has_async_transaction;
    struct list_head async_todo;
    enum binder_node_inherit_rt inherit_rt;
    struct binder_work work;
    bool ok_to_free;
};
```

注意点：
- `ptr`/`cookie` 就是用户态 `flat_binder_object.binder`/`cookie`，驱动据此识别“这是哪个实体”。
- 强/弱引用的最终裁决在**用户态**（`BBinder::getStrongProxy` 等），驱动只是转发 `BR_INCREFS`/`BR_ACQUIRE` 等命令，并维护 `local_strong_refs` 用于判断实体是否可被回收。

### 2.4 `struct binder_ref` / `binder_ref_data` / `binder_ref_death` —— 引用（handle 的本体）

```c
struct binder_ref_data {
    int desc;           /* handle 值（描述符） */
    int strong;         /* ref 持有的强引用计数 */
    int weak;           /* ref 持有的弱引用计数 */
};

struct binder_ref {
    struct binder_proc *proc;       /* 引用方进程 */
    struct rb_node rb_node_desc;    /* 挂入 proc->refs_by_desc */
    struct rb_node rb_node_node;    /* 挂入 proc->refs_by_node */
    struct hlist_node node_entry;   /* 挂入 target binder_node->refs 链表 */
    struct binder_node *node;       /* 指向目标实体 */
    struct binder_ref_data data;    /* desc/strong/weak */
    struct binder_ref_death *death; /* 注册死亡通知时非空 */
};

struct binder_ref_death {
    struct binder_work work;
    binder_uintptr_t cookie;        /* 用户在 BC_REQUEST_DEATH_NOTIFICATION 给的回调 cookie */
};
```

关系：`handle(desc)` ↔ `binder_ref` ↔ `binder_node`。client 端持有 `handle`，驱动用 `refs_by_desc` 反查 `binder_ref`，再摸到 `binder_node` 完成定向。

### 2.5 `struct binder_buffer` —— 一次事务的共享内存载体

定义在 `binder_alloc.h`：

```c
struct binder_buffer {
    struct list_head entry;         /* 按地址串入 alloc->buffers */
    struct rb_node rb_node;         /* Free: 按 size 红黑树；Allocated: 按地址红黑树 */
    unsigned free:1;
    unsigned allow_user_free:1;
    unsigned async_transaction:1;   /* 是否 one-way 异步事务 */
    unsigned free_in_progress:1;
    unsigned is_failure:1;
    unsigned debug_id:27;
    struct binder_transaction *transaction;
    struct binder_node *target_node;
    size_t data_size;
    size_t offsets_size;
    size_t extra_buffers_size;
    void __user *user_data;         /* 在用户态的映射地址 */
    int pid;
};
```

一次 `BC_TRANSACTION`，驱动在**目标进程**的 `alloc` 里切一块 `binder_buffer`，把 `binder_transaction_data` 描述的 data/offset 拷进去（含 object 转换），然后入队到目标线程 todo。

### 2.6 `struct binder_transaction` —— 一次事务的上下文

```c
struct binder_transaction {
    int debug_id;
    struct binder_work work;
    struct binder_thread *from;             /* 发起线程 */
    struct binder_transaction *from_parent; /* 嵌套事务链 */
    struct binder_proc *to_proc;            /* 目标进程 */
    struct binder_thread *to_thread;        /* 目标线程（同步时指定） */
    struct binder_transaction *to_parent;
    unsigned need_reply:1;                  /* 同步事务需要 reply */
    unsigned is_dead:1;
    struct binder_node *target_node;
    struct binder_buffer *buffer;           /* 关联的 binder_buffer */
    unsigned int code;
    unsigned int flags;
    long priority;
    long saved_priority;
    kuid_t sender_euid;
    struct list_head fd_fixups;             /* FD 对象修正链表 */
    binder_uintptr_t security_ctx;          /* selinux secctx */
    char target_name[TASK_COMM_LEN];        /* 目标进程名，便于调试 */
};
```

`transaction_stack` 在 `binder_thread` 上构成嵌套调用链，这是 ANR 时 `binder_gpu`/`dmesg` 能看到“Binder: calling -> called”的依据。

### 2.7 `struct binder_work` —— 驱动内部的工作项

```c
struct binder_work {
    struct list_head entry;
    enum binder_work_type type;
};
enum binder_work_type {
    BINDER_WORK_TRANSACTION = 1,
    BINDER_WORK_TRANSACTION_COMPLETE,
    BINDER_WORK_RETURN_ERROR,
    BINDER_WORK_DEAD_BINDER,
    BINDER_WORK_DEAD_BINDER_AND_CLEAR,
    BINDER_WORK_CLEAR_DEATH_NOTIFICATION,
};
```

所有“待办”都包装成 `binder_work`，挂在 `proc->todo` / `thread->todo` 或嵌在 `binder_transaction` / `binder_ref_death` 里，由 `binder_thread_read()` 取出转成 `BR_*` 命令。

### 2.8 `struct binder_alloc` + `struct binder_lru_page` —— 缓冲区与内存管理

```c
struct binder_alloc {
    struct mutex mutex;
    struct vm_area_struct *vma;
    struct mm_struct *vma_vm_mm;
    void __user *buffer;             /* 用户态映射基址（一次 mmap 的 VA 起点） */
    struct list_head buffers;        /* 所有 binder_buffer 链表 */
    struct rb_root free_buffers;     /* 空闲块，按 size 红黑树 */
    struct rb_root allocated_buffers;/* 已分配块，按地址红黑树 */
    size_t free_async_space;         /* 留给 one-way 异步的 VA 配额 */
    size_t buffer_size;
    uint32_t buffer_free;
    int pid;
    size_t pages_high;
    struct binder_lru_page *pages;   /* 按页号索引的 LRU 页数组 */
    ...
};

struct binder_lru_page {
    struct list_head lru;
    struct page *page_ptr;           /* 物理页 */
    struct binder_alloc *alloc;
    atomic_t map_count;              /* 该页当前被多少 buffer 映射 */
};
```

内存管理要点：
- 缓冲区采用“红黑树 + 伙伴式合并”分配，`free_buffers` 按 size、`allocated_buffers` 按地址，便于分配后合并相邻空闲块。
- 异步（one-way）事务只能占用 `free_async_space`（默认上限 `MAX_BUFFER` 的一半，受 `BINDER_SET_MAX_ALLOC_ASYNC` 可调），防止异步洪流饿死同步事务。
- 页面走 LRU，长时间不用的页可被回收（`binder_free_page`），按需再 `vm_insert_page` 映射回用户态。

### 2.9 辅助结构

```c
struct binder_priority {
    unsigned int sched_policy;       /* SCHED_*: NORMAL / FIFO / BATCH ... */
    int prio;                         /* nice 值或 RT 优先级 */
};

struct binder_context {
    const char *name;                 /* "binder" / "hwbinder" / "vndbinder" */
    struct binder_node *manager_node; /* 如 servicemanager 的实体 */
    bool manager_appointed;
};

struct binder_txn_security_ctx {
    u32 secctx_size;
    u32 secctx_offset;                /* 在 binder_buffer 内偏移，供 selinux 校验 */
};
```

---

## 三、核心关系图（进程/线程/节点/引用 环路）

```
                用户态 P1 (client)                    用户态 P2 (server)
                ┌─────────────────┐                  ┌─────────────────┐
                │ BpBinder(handle)│                  │  BBinder 实体    │
                └────────┬────────┘                  └────────▲────────┘
                         │ handle(desc)                       │ ptr/cookie
        ┌────────────────┼────────────────┐   ┌──────────────┴───────────────┐
        内核态（同一份驱动，不同 binder_context 互不可见）
        │                ▼                │   │              ▼                │
        │      binder_proc (P1)           │   │     binder_proc (P2)          │
        │   refs_by_desc ─┐               │   │      nodes  ─────────┐        │
        │                ▼               │   │        │             │        │
        │         binder_ref             │   │        ▼             │        │
        │      (data.desc=handle)        │   │   binder_node        │        │
        │        │ node_entry           │   │  (ptr/cookie)        │        │
        │        └──────┬───────────────┼───┼──────────────────────┘        │
        │               ▼               │   │   refs 链表(反向)              │
        │         binder_node ◄─────────┼───┘                               │
        │   (P2 实体在 P1 视角下的代理)  │                                    │
        └───────────────────────────────┘                                    │
                  binder_transaction (挂在 from/to 线程 transaction_stack)
                  binder_buffer (在 to_proc->alloc 中分配，存 data+offsets)
```

一句话：**handle 是 ref 的“门牌号”，ref 指向 node，node 指向 server 进程的实体；一次调用由 transaction 串联 from 线程到 to 线程，数据在 to_proc 的 binder_buffer 中。**

---

## 四、关键方法 ↔ 结构体 对照（驱动侧）

| 动作 | 涉及结构 | 关键函数（binder.c） |
|------|----------|----------------------|
| 进程打开 binder | binder_proc | `binder_open()` |
| 建立共享内存 | binder_alloc / binder_buffer | `binder_mmap()` → `binder_alloc_mmap()` |
| 线程加入 | binder_thread | `binder_get_thread()` / `binder_ioctl_set_ctx_mgr` |
| 注册实体 | binder_node | `binder_new_node()`（transact 携带 BINDER_TYPE_BINDER 时） |
| 获取引用 | binder_ref | `binder_get_ref_for_node()` / `binder_get_ref()` |
| 发起调用 | binder_transaction / binder_buffer | `binder_transaction()` |
| 投递到目标 | binder_work / todo 队列 | `binder_transaction()` → `binder_proc_transaction()` |
| 目标线程取命令 | binder_thread | `binder_thread_read()`（把 work 转 BR_*） |
| 释放 buffer | binder_buffer / alloc | `binder_free_buf()` → `binder_alloc_free_buf()` |
| 死亡通知 | binder_ref_death | `binder_send_death()`（进程死亡时遍历 `delivered_death`）|

---

## 五、速记表（面试/复习用）

- `binder_proc`：**进程级**，4 棵红黑树（threads/nodes/refs_by_desc/refs_by_node）+ 一个 `alloc`。
- `binder_thread`：**线程级**，有自己的 `todo` 和 `transaction_stack`，靠 `looper` 状态机。
- `binder_node`：**实体**，存 `ptr`/`cookie`，强/弱引用裁决在用户态。
- `binder_ref`：**引用**，`desc` 就是用户态的 handle，红黑树索引，反向链到 node。
- `binder_buffer`：**数据载体**，切自 `alloc`，一次事务一块，存 Parcel 的 data+offsets。
- `binder_transaction`：**事务上下文**，串起 from→to，决定同步/异步、优先级继承、selinux ctx。
- `binder_work`：**待办单位**，一切异步动作都包成 work 排队，最终翻译成 `BR_*`。
- `binder_alloc`/`binder_lru_page`：**内存管家**，红黑树分配 + LRU 页面回收 + 异步空间配额。
- 协议层 `flat_binder_object`/`binder_*_object`：**跨边界 wire format**，驱动靠 `offsets` 表识别并做引用计数与转换。

---

## 六、版本提示（相对老版本的差异）

- Android 14 的 `binder.c` 已是 generic object 模型，`flat_binder_object` 主要用于兼容，新代码走 `binder_object_header` + 各类 `*_object`。
- 新增 `BINDER_TYPE_FD_FILE`（FD 带 file 信息，便于文件跨进程校验）。
- 异步事务空间由 `free_async_space` 严格限制，且 `binder_alloc` 用 `binder_lru_page` 数组 + LRU 回收，缓解大内存占用。
- `binder_transaction` 带 `security_ctx` 与 `target_name`，selinux 与 `binderfs` 调试更完善。
```
