# Binder 数据结构字段注释大全

> **基准版本**：mainline 内核 master（2025-08 实测抓取）+ AOSP libbinder/framework（Android 15 同源）。
> 内核核心结构定义在 `drivers/android/binder_internal.h`（4.14 起从 binder.c 迁出），内存管理在 `binder_alloc.h`，UAPI 在 `include/uapi/linux/android/binder.h`。
> 注释直接源自上游 doc 注释 + 语义补充；★ 标记跨层关键字段。

---

## 一、UAPI 层（内核与用户态的接口契约）

### 1.1 binder_object_header —— 所有 Parcel 内嵌对象的公共头

```c
struct binder_object_header {
    __u32 type;    // 对象类型, 见下方枚举; 驱动在事务翻译时可★原地改写此字段
};

enum {  // B_PACK_CHARS: 类型值即 ASCII 码, hexdump Parcel 可直接读出 'sb*'/'sh*' 等
    BINDER_TYPE_BINDER       = B_PACK_CHARS('s','b','*',0x85), // 本进程服务实体
    BINDER_TYPE_WEAK_BINDER  = B_PACK_CHARS('w','b','*',0x85), // 弱引用实体
    BINDER_TYPE_HANDLE       = B_PACK_CHARS('s','h','*',0x85), // 远端服务句柄
    BINDER_TYPE_WEAK_HANDLE  = B_PACK_CHARS('w','h','*',0x85), // 弱句柄
    BINDER_TYPE_FD           = B_PACK_CHARS('f','d','*',0x85), // 文件描述符
    BINDER_TYPE_FDA          = B_PACK_CHARS('f','d','a',0x85), // fd 数组
    BINDER_TYPE_PTR          = B_PACK_CHARS('p','t','*',0x85), // 用户态缓冲对象
};
```

### 1.2 flat_binder_object —— 跨进程传递 Binder 对象的核心载体

```c
struct flat_binder_object {
    struct binder_object_header hdr;   // type (可被驱动改写 BINDER<->HANDLE)
    __u32 flags;                       // 位组合, 见 flat_binder_object_flags

    /* 8 字节联合体 */
    union {
        binder_uintptr_t binder;   // ★ type=BINDER: 本进程 BBinder 用户态地址 (==binder_node.ptr)
        __u32            handle;   // ★ type=HANDLE: 句柄号 (==binder_ref.data.desc)
    };

    binder_uintptr_t cookie;       // ★ type=BINDER: 上下文 (==binder_node.cookie, Java Binder 对象)
};

enum flat_binder_object_flags {
    FLAT_BINDER_FLAG_PRIORITY_MASK    = 0xff,   // 低 8 位: 最低调度优先级 (node.min_priority 来源)
    FLAT_BINDER_FLAG_ACCEPTS_FDS      = 0x100,  // 该服务允许接收 fd 事务 (node.accept_fds 来源)
    FLAT_BINDER_FLAG_TXN_SECURITY_CTX = 0x1000, // 请求驱动为发往该服务的事务附带 sender SELinux 上下文
};
```

### 1.3 fd / 缓冲 / fd 数组对象（stable AIDL 的泛型支持）

```c
struct binder_fd_object {              // fd 对象 (与 flat_binder_object 布局兼容)
    struct binder_object_header hdr;
    __u32 pad_flags;                   // 填充 (兼容旧 flat_binder_object.flags 位)
    union {
        binder_uintptr_t pad_binder;   // 填充 (兼容旧布局)
        __u32 fd;                      // 文件描述符值, 驱动在目标进程重新安装并改写
    };
    binder_uintptr_t cookie;           // 用户态自定义数据
};

struct binder_buffer_object {          // 指向用户态缓冲的对象 (数据不被拷贝, 内核校验+改写指针)
    struct binder_object_header hdr;
    __u32 flags;                       // BINDER_BUFFER_FLAG_HAS_PARENT=0x01: 有父缓冲需 fixup
    binder_uintptr_t buffer;           // 用户态缓冲地址 (驱动改写为目标进程地址)
    binder_size_t length;              // 缓冲长度
    binder_size_t parent;              // 父缓冲对象在偏移数组中的下标
    binder_size_t parent_offset;       // 本对象指针在父缓冲内的偏移 (fixup 用)
};

struct binder_fd_array_object {        // fd 数组 (如 native_handle_t 中的 fd 列表)
    struct binder_object_header hdr;
    __u32 pad;                         // 对齐填充
    binder_size_t num_fds;             // fd 个数
    binder_size_t parent;              // 父 binder_buffer_object 的偏移数组下标
    binder_size_t parent_offset;       // fd 数组在父缓冲内的起始偏移
};
```

### 1.4 binder_write_read —— BINDER_WRITE_READ ioctl 的参数

```c
struct binder_write_read {
    binder_size_t   write_size;      // 用户态写入的 BC_* 命令字节数 (0=只读)
    binder_size_t   write_consumed;  // 驱动已消费的写入字节数 (回填)
    binder_uintptr_t write_buffer;   // BC_* 命令缓冲的用户态地址 (即 IPCThreadState::mOut)

    binder_size_t   read_size;       // 允许驱动写入的 BR_* 字节数 (0=只写)
    binder_size_t   read_consumed;   // 驱动已写入的 BR_* 字节数 (回填)
    binder_uintptr_t read_buffer;    // BR_* 接收缓冲的用户态地址 (即 IPCThreadState::mIn)
};
```

### 1.5 binder_transaction_data —— 事务元数据（64 字节）

```c
struct binder_transaction_data {
    union {
        __u32 handle;                // ★ BC_TRANSACTION: 目标句柄 (0=服务目录 ctx mgr)
        binder_uintptr_t ptr;        // ★ BR_TRANSACTION: 目标 node.ptr (BBinder 地址)
    } target;
    binder_uintptr_t cookie;         // BR_TRANSACTION: 目标 node.cookie (Java Binder 对象)
    __u32 code;                      // 事务码 (AIDL 方法编号)

    __u32 flags;                     // 见 transaction_flags
    __kernel_pid_t   sender_pid;     // ★ 发起方 pid (内核填, 用户态不可伪造)
    __kernel_uid32_t sender_euid;    // ★ 发起方 euid (权限校验依据)
    binder_size_t data_size;         // Parcel 数据区大小
    binder_size_t offsets_size;      // 偏移数组大小 (每个 flat_binder_object 一个条目)

    union {                          // 数据本体 (ioctl 内联时用 buf, 否则用指针)
        struct {
            binder_uintptr_t buffer;   // 数据区指针 (mmap 共享区内地址, 零拷贝)
            binder_uintptr_t offsets;  // 偏移数组指针
        } ptr;
        __u8 buf[8];
    } data;
};

struct binder_transaction_data_secctx {              // BR_TRANSACTION_SEC_CTX 用
    struct binder_transaction_data transaction_data;
    binder_uintptr_t secctx;                         // sender SELinux 上下文字符串地址
};

struct binder_transaction_data_sg {                  // BC_TRANSACTION_SG / BC_REPLY_SG 用
    struct binder_transaction_data transaction_data;
    binder_size_t buffers_size;                      // extra_buffers (scatter-gather) 大小
};

enum transaction_flags {
    TF_ONE_WAY     = 0x01,  // oneway: 异步无返回
    TF_ROOT_OBJECT = 0x04,  // 组件根对象 (历史遗留)
    TF_STATUS_CODE = 0x08,  // data 是 32 位状态码 (reply 失败路径)
    TF_ACCEPT_FDS  = 0x10,  // 允许回复中携带 fd
    TF_CLEAR_BUF   = 0x20,  // 事务完成后清零缓冲 (防残留敏感数据)
    TF_UPDATE_TXN  = 0x40,  // 更新目标已挂起的同 code 旧 oneway 事务 (Android 15+)
};
```

### 1.6 短命令参数结构

```c
struct binder_ptr_cookie   { binder_uintptr_t ptr;  binder_uintptr_t cookie; }; // 引用回执类
struct binder_handle_cookie{ __u32 handle; binder_uintptr_t cookie; } __packed; // 死亡/冻结通知注册
struct binder_pri_desc     { __s32 priority; __u32 desc; };                     // BC_ATTEMPT_ACQUIRE(未启用)
struct binder_pri_ptr_cookie{ __s32 priority; binder_uintptr_t ptr; binder_uintptr_t cookie; };
```

### 1.7 ioctl 辅助结构

```c
struct binder_version          { __s32 protocol_version; }; // 协议版本: 64 位=8, 32 位=7
struct binder_node_debug_info  { binder_uintptr_t ptr; binder_uintptr_t cookie;
                                 __u32 has_strong_ref; __u32 has_weak_ref; }; // 遍历本进程 node
struct binder_node_info_for_ref{ __u32 handle; __u32 strong_count; __u32 weak_count;
                                 __u32 reserved1..3; };      // SM 专用: 查引用计数
struct binder_freeze_info      { __u32 pid; __u32 enable; __u32 timeout_ms; }; // BINDER_FREEZE
struct binder_frozen_status_info{ __u32 pid; __u32 sync_recv; __u32 async_recv; }; // 冻结期收包统计
struct binder_frozen_state_info{ binder_uintptr_t cookie; __u32 is_frozen; __u32 reserved; };
struct binder_extended_error   { __u32 id; __u32 command; __s32 param; }; // BINDER_GET_EXTENDED_ERROR
```

### 1.8 ioctl 命令总表

```c
BINDER_WRITE_READ                = _IOWR('b', 1,  bwr)                  // 主干: 收发命令
BINDER_SET_MAX_THREADS           = _IOW('b', 5,  __u32)                 // 线程池上限 (framework 默认 15)
BINDER_SET_CONTEXT_MGR           = _IOW('b', 7,  __s32)                 // 注册 context manager (老接口)
BINDER_SET_CONTEXT_MGR_EXT       = _IOW('b', 13, flat_binder_object)    // 带 flags 的新版注册
BINDER_THREAD_EXIT               = _IOW('b', 8,  __s32)                 // 线程上下文注销
BINDER_VERSION                   = _IOWR('b', 9,  version)              // 协议版本协商
BINDER_GET_NODE_DEBUG_INFO       = _IOWR('b', 11, node_debug_info)      // 遍历 node (调试)
BINDER_GET_NODE_INFO_FOR_REF     = _IOWR('b', 12, node_info_for_ref)    // 仅 SM 可用
BINDER_FREEZE                    = _IOW('b', 14,  freeze_info)          // 冻结/解冻进程 (cached app)
BINDER_GET_FROZEN_INFO           = _IOWR('b', 15, frozen_status_info)   // 查冻结期收包情况
BINDER_ENABLE_ONEWAY_SPAM_DETECTION = _IOW('b', 16, __u32)              // 开启 oneway 洪泛检测
BINDER_GET_EXTENDED_ERROR        = _IOWR('b', 17, extended_error)       // 拉取扩展错误
```

### 1.9 BR_ 返回协议（驱动 → 用户态）

```c
BR_ERROR / BR_OK                        // 错误码 / 空
BR_TRANSACTION / BR_REPLY               // 事务本体 (binder_transaction_data)
BR_TRANSACTION_SEC_CTX                  // 同 BR_TRANSACTION + secctx
BR_DEAD_REPLY                           // 目标进程已死
BR_FAILED_REPLY                         // 事务失败 (句柄无效/内存不足/翻译失败)
BR_FROZEN_REPLY                         // 目标进程已冻结
BR_ONEWAY_SPAM_SUSPECT                  // oneway 洪泛告警
BR_TRANSACTION_PENDING_FROZEN           // oneway 目标已冻结(挂起)
BR_TRANSACTION_COMPLETE                 // 同步事务已送达 (发起方收)
BR_INCREFS / BR_ACQUIRE                 // 引用计数递增通知 (node 管理)
BR_RELEASE / BR_DECREFS                 // 引用计数递减通知
BR_SPAWN_LOOPER                         // 请求用户态新开线程 (池扩容握手)
BR_NOOP / BR_FINISHED                   // 空操作 / 停线程(未启用)
BR_DEAD_BINDER / BR_CLEAR_DEATH_NOTIFICATION_DONE  // 死亡通知 (cookie)
BR_FROZEN_BINDER / BR_CLEAR_FREEZE_NOTIFICATION_DONE // 冻结状态通知 (cookie)
```

### 1.10 BC_ 命令协议（用户态 → 驱动）

```c
BC_TRANSACTION / BC_REPLY               // 事务 / 回复
BC_TRANSACTION_SG / BC_REPLY_SG         // 带 extra_buffers 版本
BC_FREE_BUFFER                          // 归还事务 buffer (用户态消费完)
BC_INCREFS / BC_ACQUIRE / BC_RELEASE / BC_DECREFS  // 对 desc 引用计数操作 (参数=句柄)
BC_INCREFS_DONE / BC_ACQUIRE_DONE       // 引用通知回执 (ptr+cookie)
BC_REQUEST_DEATH_NOTIFICATION           // 注册死亡通知 (handle+cookie)
BC_CLEAR_DEATH_NOTIFICATION             // 注销死亡通知
BC_DEAD_BINDER_DONE                     // 死亡通知处理完毕确认
BC_REQUEST_FREEZE_NOTIFICATION          // 注册冻结通知 (Android 13+)
BC_CLEAR_FREEZE_NOTIFICATION / BC_FREEZE_NOTIFICATION_DONE
BC_REGISTER_LOOPER / BC_ENTER_LOOPER / BC_EXIT_LOOPER  // 线程池生命周期
```

---

## 二、内核核心结构（binder_internal.h）

### 2.1 binder_work —— 调度单元

```c
struct binder_work {
    struct list_head entry;     // 挂载点: proc->todo / thread->todo / node->async_todo
    enum binder_work_type type;
};

enum binder_work_type {
    BINDER_WORK_TRANSACTION = 1,             // 事务本体投递
    BINDER_WORK_TRANSACTION_COMPLETE,        // 同步事务送达回执 (发起方)
    BINDER_WORK_TRANSACTION_PENDING,         // oneway 目标已冻结, 事务挂起 (Android 15+)
    BINDER_WORK_TRANSACTION_ONEWAY_SPAM_SUSPECT, // 洪泛告警
    BINDER_WORK_RETURN_ERROR,                // 返回错误 (thread->return_error)
    BINDER_WORK_NODE,                        // node 强/弱引用变化通知
    BINDER_WORK_DEAD_BINDER,                 // 死亡通知
    BINDER_WORK_DEAD_BINDER_AND_CLEAR,       // 死亡通知+同时已请求清除
    BINDER_WORK_CLEAR_DEATH_NOTIFICATION,    // 清除完成通知
    BINDER_WORK_FROZEN_BINDER,               // 冻结状态变化通知
    BINDER_WORK_CLEAR_FREEZE_NOTIFICATION,   // 冻结通知清除完成
};
```

### 2.2 binder_node —— 服务实体锚点（全局唯一）

```c
struct binder_node {
    int debug_id;               // 调试 ID (debugfs/binder_logs 可见)

    spinlock_t lock;            // node 自身字段的自旋锁 (与 proc 锁分离, 减少争用)
    struct binder_work work;    // node 工作项 (引用通知用)
    union {
        struct rb_node rb_node; // 存活时: 挂 proc->nodes 红黑树 (按 ptr 排序)
        struct hlist_node dead_node; // 实体进程死后仍有引用: 挂全局 dead_nodes 链
    };
    struct binder_proc *proc;   // ★ 实体归属进程 (事务路由的最终目的地)
    struct hlist_head refs;     // ★ 所有引用本 node 的 ref 链表 (保活+死亡通知遍历)

    int internal_strong_refs;   // 内核强引用计数 (有外部 ref 存在则 >0, 实体不可释放)
    int local_weak_refs;        // 本进程弱引用计数 (BR_DECREFS 回执)
    int local_strong_refs;      // 本进程强引用计数
    int tmp_refs;               // 临时内核引用 (遍历/清理期间防提前释放)

    binder_uintptr_t ptr;       // ★ 用户态 BBinder 地址 (== flat_binder_object.binder)
    binder_uintptr_t cookie;    // ★ 用户态上下文 (Java 层即 Binder 对象)

    struct {                    // 通知握手位 (protected by proc inner_lock)
        u8 has_strong_ref:1;      // 已通知用户态持有强引用
        u8 pending_strong_ref:1;  // 用户态已回执 (BC_ACQUIRE_DONE)
        u8 has_weak_ref:1;
        u8 pending_weak_ref:1;
    };
    struct {                    // 初始化后不变
        u8 accept_fds:1;          // 是否接收 fd (来自 FLAT_BINDER_FLAG_ACCEPTS_FDS)
        u8 txn_security_ctx:1;    // 是否要求 sender 安全上下文 (SM 置位)
        u8 min_priority;          // 最低调度优先级 (来自 flags 低 8 位)
    };
    bool has_async_transaction; // oneway 去重: 该 node 是否有 oneway 事务在执行
    struct list_head async_todo;// oneway 排队链 (per-node 有序保证)
};
```

### 2.3 binder_ref —— 引用（每进程 × 每 node 一条）

```c
struct binder_ref {
    struct binder_ref_data data;   // 值语义体 (锁外快照返回用)
    struct rb_node rb_node_desc;   // 挂 proc->refs_by_desc (按 data.desc 排序, transact 查它)
    struct rb_node rb_node_node;   // 挂 proc->refs_by_node (按 node 排序, 清理用)
    struct hlist_node node_entry;  // 挂 node->refs 链表 (反向保活)
    struct binder_proc *proc;      // 引用方进程
    struct binder_node *node;      // ★ 被引用实体 (handle 反查的终点)
    struct binder_ref_death *death;// 死亡通知注册 (BC_REQUEST_DEATH_NOTIFICATION)
    struct binder_ref_freeze *freeze; // 冻结通知注册 (BC_REQUEST_FREEZE_NOTIFICATION)
};

struct binder_ref_data {
    int debug_id;       // 调试 ID
    uint32_t desc;      // ★ 句柄号 (==BpBinder.handle==flat_binder_object.handle); 0 保留给 ctx mgr
    int strong;         // 强引用计数
    int weak;           // 弱引用计数
};

struct binder_ref_death {
    struct binder_work work;    // 触发时 type 变为 DEAD_BINDER / CLEAR_DEATH_NOTIFICATION
    binder_uintptr_t cookie;    // 用户态 DeathRecipient 标识 (BR_DEAD_BINDER 原样带回)
};

struct binder_ref_freeze {
    struct binder_work work;
    binder_uintptr_t cookie;
    bool is_frozen:1;   // 目标进程当前冻结状态
    bool sent:1;        // 通知是否已投递
    bool resend:1;      // 投递失败需重发
};
```

### 2.4 binder_transaction —— 事务本体（存活期）

```c
struct binder_transaction {
    int debug_id;
    struct binder_work work;        // 投递载体
    struct binder_thread *from;     // ★ 发起线程 (reply 路径靠它精确回传)
    pid_t from_pid;                 // from 的进程 pid (from 被清理后仍可查日志)
    pid_t from_tid;                 // from 的线程 tid
    struct binder_transaction *from_parent; // ★ 嵌套调用链: 发起线程的前一个事务
    struct binder_proc *to_proc;    // ★ 目标进程 (buffer 分配在其 mmap 池)
    struct binder_thread *to_thread;// 目标线程 (reply 时精确; 普通投递为 NULL)
    struct binder_transaction *to_parent;   // 目标线程侧的父事务
    unsigned is_async:1;            // oneway 事务
    unsigned is_reply:1;            // 回复事务

    struct binder_buffer *buffer;   // ★ 数据缓冲 (与 buffer->transaction 互指)
    unsigned int code;              // 事务码
    unsigned int flags;             // TF_* 标志
    long priority;                  // 事务优先级 (target node.min_priority 参与计算)
    long saved_priority;            // 发起线程原优先级 (回复后恢复)
    kuid_t sender_euid;             // 发起方 euid
    ktime_t start_time;             // 起始时间戳 (oneway 洪泛检测/诊断)

    struct list_head fd_fixups;     // fd 翻译待办链 (fd 需在目标进程上下文安装)
    binder_uintptr_t security_ctx;  // sender SELinux 上下文缓冲 (txn_security_ctx 时)

    spinlock_t lock;                // 保护 from/to_proc/to_thread (线程退出会置 NULL)
};

struct binder_txn_fd_fixup {        // fd 翻译链表元素
    struct list_head fixup_entry;
    struct file *file;              // 待安装的文件
    size_t offset;                  // buffer 内 fd 所在偏移
    int target_fd;                  // 目标进程分配的新 fd
};
```

### 2.5 binder_proc —— 进程上下文

```c
struct binder_proc {
    struct hlist_node proc_node;    // 挂全局 binder_procs 链表
    struct rb_root threads;         // 本进程 binder_thread 红黑树 (按 pid)
    struct rb_root nodes;           // 本进程创建的服务实体树 (按 node->ptr)
    struct rb_root refs_by_desc;    // 引用别人的 ref 树 (按 desc) ★事务寻址入口
    struct rb_root refs_by_node;    // 同上, 按 node 排序 (清理用)
    struct list_head waiting_threads; // 当前空闲等待工作的线程链 (调度候选)

    int pid;                        // 进程组主线程 pid
    struct task_struct *tsk;        // group_leader 的 task_struct
    const struct cred *cred;        // 打开 /dev/binder 时的凭据 (权限判定)

    struct hlist_node deferred_work_node; // 挂 binder_deferred_list
    int deferred_work;              // 位图: FLUSH=0x01 / RELEASE=0x02
    int outstanding_txns;           // 未完成事务数 (冻结前等待归零)

    bool is_dead;                   // 进程已死, 等待清理
    bool is_frozen;                 // 进程已冻结 (cgroup freezer), 无法处理事务
    bool sync_recv;                 // 冻结后收到过同步事务
    bool async_recv;                // 冻结后收到过异步事务
    wait_queue_head_t freeze_wait;  // 等 outstanding_txns 归零的等待队列

    struct dbitmap dmap;            // ★ desc 句柄位图 (动态扩缩, 0 位保留给 ctx mgr)
    struct list_head todo;          // 进程级待处理队列 (无空闲线程时事务落这里)
    struct binder_stats stats;      // 统计 (原子量)
    struct list_head delivered_death; // 已投递待确认的死亡通知
    struct list_head delivered_freeze; // 已投递待确认的冻结通知

    u32 max_threads;                // 线程池上限 (BINDER_SET_MAX_THREADS)
    int requested_threads;          // 已请求未启动的线程数 (0 或 1)
    int requested_threads_started;  // 已启动的请求线程计数
    int tmp_ref;                    // 临时引用 (防并发释放)

    long default_priority;          // 默认调度优先级 (open 时 task_nice)
    struct dentry *debugfs_entry;   // debugfs 日志节点

    struct binder_alloc alloc;      // ★ 内嵌内存分配器 (mmap 池管理)
    struct binder_context *context; // 所属 context (binder/hwbinder/vndbinder...)

    spinlock_t inner_lock;          // 内锁: 保护 todo/refs 链/thread 字段
    spinlock_t outer_lock;          // 外锁: 保护 nodes/refs 树 (锁序 outer→node→inner)
    struct dentry *binderfs_entry;  // binderfs 进程日志
    bool oneway_spam_detection_enabled; // 是否开启 oneway 洪泛检测
};
```

### 2.6 binder_thread —— 线程上下文

```c
struct binder_thread {
    struct binder_proc *proc;       // 所属进程
    struct rb_node rb_node;         // 挂 proc->threads (按 pid)
    struct list_head waiting_thread_node; // 空闲时挂 proc->waiting_threads

    int pid;                        // 线程 tid
    int looper;                     // looper 状态位图 (见下)
    bool looper_need_return;        // 要求 looper 退出驱动 (可被其他线程写)

    struct binder_transaction *transaction_stack; // ★ 本线程进行中的事务栈 (reply 寻址)
    struct list_head todo;          // 线程私有工作队列 (优先于 proc->todo)
    bool process_todo;              // todo 是否待处理

    struct binder_error return_error; // 发起事务失败的错误载体 (BR_*_REPLY)
    struct binder_error reply_error;  // 回复失败的错误载体
    struct binder_extended_error ee;  // 扩展错误 (BINDER_GET_EXTENDED_ERROR 读出)
    wait_queue_head_t wait;         // 空闲等待队列 (binder_wait_for_work 睡这)
    struct binder_stats stats;      // 线程级统计
    atomic_t tmp_ref;               // 临时引用
    bool is_dead;                   // 线程已退出等清理
};

// looper 状态位 (binder.c):
//   REGISTERED=0x01 (BC_REGISTER_LOOPER, 池线程)
//   ENTERED   =0x02 (BC_ENTER_LOOPER, 主线程)
//   EXITED    =0x04
//   INVALID   =0x08
//   WAITING   =0x10 (正在驱动内休眠)
```

### 2.7 binder_context / binder_device —— 域与设备

```c
struct binder_context {
    struct binder_node *binder_context_mgr_node; // ★ handle 0 指向的 node (SM)
    struct mutex context_mgr_node_lock;          // 保护 mgr node 的互斥锁
    kuid_t binder_context_mgr_uid;               // 首个注册者 euid (防抢占)
    const char *name;                            // 域名 ("binder"/"hwbinder"...)
};

struct binder_device {
    struct hlist_node hlist;        // 全局设备链
    struct miscdevice miscdev;      // 字符设备 (/dev/binder 等)
    struct binder_context context;  // ★ 每设备独立 context => 每域独立 handle 0
    struct inode *binderfs_inode;   // 所属 binderfs 超级块
    refcount_t ref;                 // 设备引用计数
};
```

---

## 三、内存管理（binder_alloc.h）

### 3.1 binder_buffer —— 事务数据缓冲

```c
struct binder_buffer {
    struct list_head entry;     // 按地址挂 alloc->buffers 链 (合并判断)
    struct rb_node rb_node;     // 空闲时: free_buffers 树 (按大小) / 已分配: allocated_buffers (按地址)

    unsigned free:1;                    // 空闲态
    unsigned clear_on_free:1;           // 归还时清零 (TF_CLEAR_BUF, 敏感数据)
    unsigned allow_user_free:1;         // 允许用户态 BC_FREE_BUFFER 归还 (读出后置位)
    unsigned async_transaction:1;       // oneway 事务缓冲 (占用 async 半池)
    unsigned oneway_spam_suspect:1;     // 分配时已触发洪泛阈值 (回执 BR_ONEWAY_SPAM_SUSPECT)
    unsigned debug_id:27;

    struct binder_transaction *transaction; // 所属事务 (互指)
    struct binder_node *target_node;        // oneway 时记目的地 (read 路径补 target.ptr)
    size_t data_size;                       // Parcel 数据大小
    size_t offsets_size;                    // 偏移数组大小
    size_t extra_buffers_size;              // 额外区 (scatter-gather/安全上下文)
    unsigned long user_data;                // ★ 用户态数据指针 (落在 mmap 区内, 零拷贝)
    int pid;                                // 归属发起方 pid (计账)
};
```

### 3.2 binder_alloc —— 每进程内存池管理器（内嵌 proc.alloc）

```c
struct binder_alloc {
    struct mutex mutex;             // 保护本结构字段
    struct mm_struct *mm;           // 进程 mm (open 时快照)
    unsigned long vm_start;         // ★ mmap 基址 (=ProcessState.mVMStart)

    struct list_head buffers;       // 全部 buffer 按地址排序的链
    struct rb_root free_buffers;    // 空闲树 (按大小, 最佳适配)
    struct rb_root allocated_buffers; // 已分配树 (按地址)

    size_t free_async_space;        // oneway 半池剩余 (初始 = buffer_size/2)
    struct page **pages;            // 页描述数组 (buffer_size/PAGE_SIZE 项, 按需填充)
    struct list_lru *freelist;      // 全局空闲页 LRU (shrinker 回收池)
    size_t buffer_size;             // 池大小 (mmap 长度, 内核 clamp SZ_4M)
    int pid;                        // 归属进程 pid
    size_t pages_high;              // 页数组高水位 (已用过的最大页下标)
    bool mapped;                    // 是否已 mmap (每实例终身仅一次)
    bool oneway_spam_detected;      // 洪泛已触发标志 (async 恢复健康后清除)
};

struct binder_shrinker_mdata {      // 页回收元数据 (挂在 page->private)
    struct list_head lru;           // 挂全局 binder_freelist
    struct binder_alloc *alloc;     // 所属分配器
    unsigned long page_index;       // 页下标 (回收/重装定位)
};
```

---

## 四、Native 层（libbinder, AOSP 15 同源）

### 4.1 ProcessState —— 每进程单例

```cpp
class ProcessState {
    int mDriverFD;                 // /dev/binder fd
    void* mVMStart;                // ★ mmap 基址 (==alloc.buffer/vm_start)
    size_t mVMSize;                // 映射大小 (BINDER_VM_SIZE = 1M-8K)
    mutable Mutex mLock;           // 单例/句柄表锁
    Vector<handle_entry> mHandleToObject; // handle → BpBinder 缓存表 (防重复创建代理)
    bool mThreadPoolStarted;       // 线程池是否已启动
    volatile int32_t mThreadPoolSeq; // 线程池代数 (spawn 判重)
    String8 mDriverName;           // 驱动路径
    CallRestriction mCallRestriction; // 调用限制 (system_server 对自己启用 FATAL)
};
struct handle_entry { IBinder* binder; RefBase::weakref_type* refs; };
```

### 4.2 IPCThreadState —— 每线程单例

```cpp
class IPCThreadState {
    PID_T mCallingPid;             // ★ 当前事务发起方 pid (内核回填, 不可伪造)
    uid_t mCallingUid;             // ★ 发起方 euid
    int32_t mStrictModePolicy;     // strictmode 传递
    int64_t mLastTransactionCode;  // 最近事务码 (诊断)
    uint32_t mIsServing;           // 正在 joinThreadPool
    sp<ProcessState> mProcess;     // 进程单例引用
    Vector<BBinder*> mPendingStrongRef / mPendingWeakRef; // 待回执引用
    Parcel mIn;                    // ★ BR_* 接收缓冲 (数据区直接指向 mmap 池)
    Parcel mOut;                   // ★ BC_* 发送缓冲
    status_t mLastError;
    const char* mServingStackPointer; // 递归调用深度检测
    FreeBufferInfo mFreeBuffer...; // 延迟归还
    int mServingStackIdentifier...
};
```

### 4.3 BBinder / BpBinder

```cpp
class BBinder {                    // 服务实体 (onTransact 被驱动事务触发)
    const BBinder::Extras* mExtras; // attachObject 附加数据
    // (继承 IBinder: onTransact(code, data, reply, flags))
    uint32_t mMinPriority;         // 最低优先级
    bool m_PROP...;                // (版本差异)
};

class BpBinder {                   // 代理 (只持句柄)
    const int32_t mHandle;         // ★ ==binder_ref.data.desc (0=SM)
    pid_t mSteadyPid?              // (版本差异)
    Vector<Obituary> mObituaries;  // 死亡通知注册表 (linkToDeath)
    bool mAlive / mObitsSent;      // 存活标志 / 死亡通知已发
    ExtendedOSScheduleArgs?        // 版本差异
    std::mutex mLock;
};
```

### 4.4 JavaBBinder（frameworks/base/core/jni/android_util_Binder.cpp）

```cpp
class JavaBBinder : public BBinder {   // Java Binder 对象的 native 壳
    JavaVM* const   mVM;               // JVM 指针 (onTransact 时 attach 线程)
    jobject const   mObject;           // ★ Java 层 Binder 对象的全局引用 (==node.cookie)
    jboolean volatile mPromoteErrNo?;  // (版本差异)
    // onTransact(): JNI 回调 Java Binder.execTransact()
};

class JavaBBinderHolder {              // Java Binder.mObject 实际指向它
    wp<JavaBBinder> mBinder;           // 惰性创建的 JavaBBinder
    jobject mPromotedObject?;          // (版本差异)
    // get(env): 无则 new JavaBBinder 并持有全局引用
};
```

### 4.5 Parcel（native）

```cpp
class Parcel {
    uint8_t* mData;            // 数据区
    size_t mDataSize;          // 已用大小
    size_t mDataCapacity;      // 容量
    mutable size_t mDataPos;   // 读写游标
    binder_size_t* mObjects;   // ★ flat_binder_object 偏移数组
    size_t mObjectsSize, mObjectsCapacity;
    mutable size_t mObjectsPos;
    release_func mOwner;       // 外部缓冲的释放回调 (从驱动收到的 buffer 只读)
    void* mOwnerCookie;
    bool mFdsKnown?/mAllowFds; // fd 收集
    status_t mError;
    // writeStrongBinder: 生成 flat_binder_object(BINDER) 写入 mObjects
    // readStrongBinder: 读 HANDLE → BinderProxy/BpBinder
};
```

---

## 五、Java 层（frameworks/base/core/java/android/os/）

### 5.1 Binder —— 服务实体（Java 侧）

```java
public class Binder implements IBinder {
    private long mObject;      // ★ native JavaBBinderHolder* (唯一的 native 锚点)
    private IInterface mOwner; // attachInterface 的业务接口
    private String mDescriptor; // 接口描述符 (queryLocalInterface 匹配用)
    // execTransact(): JNI 入口 → onTransact(code, data, reply, flags)
    // static execTransact?  实际: private boolean execTransact(int code, long dataObj, long replyObj, int flags)
}
```

### 5.2 BinderProxy —— 代理（JNI 创建, 无法 new）

```java
final class BinderProxy implements IBinder {
    private long mObject;      // ★ native BpBinder* (handle 在 native 侧)
    private final WeakReference<BinderProxy> mSelf; // 全局注册表项 (资源回收)
    private String mDescriptor; // 接口描述符缓存
    private volatile int m warn? // 版本差异: mInvokedTids / mFrozen...
    // transact(): native → IPCThreadState::transact(mObject 的 handle, ...)
    // linkToDeath: native → BC_REQUEST_DEATH_NOTIFICATION
}
```

### 5.3 Parcel（Java）

```java
public final class Parcel {
    private long mNativePtr;          // ★ native Parcel* (JNI 双向桥)
    private boolean mOwnsNativeParcelObject; // 是否拥有 native 对象 (freeBuffer 时销毁)
    private long mNativeContext?;     // 版本差异
    // writeStrongBinder(IBinder): native → flatten 进 mObjects
    // readStrongBinder(): native → BinderProxy 或本地 Binder
}
```

---

## 六、跨层字段硬关联（全文档的三根钉子）

```
① Java Binder.mObject → JavaBBinderHolder → JavaBBinder.mObject(jobject)
     ↕ (BBinder 用户态地址)                      ↕ (cookie)
   binder_node.ptr                          binder_node.cookie

② BinderProxy.mObject → BpBinder.handle(42)
     ↕
   binder_ref.data.desc(42)

③ ProcessState.mVMStart == binder_alloc.vm_start == mmap 基址
   Parcel 数据区指针 ∈ [vm_start, vm_start + buffer_size)   ← 零拷贝
```

| # | 用户态视角 | 内核视角 | 断言 |
|---|-----------|---------|------|
| 1 | BBinder 地址 / Java Binder | `node.ptr` / `node.cookie` | 相等（node 建立时记录） |
| 2 | BpBinder.handle / BinderProxy | `ref.data.desc` | 相等（句柄即引用编号） |
| 3 | mVMStart / Parcel 数据指针 | `alloc.vm_start` / `buffer.user_data` | 同一映射区 |

---

## 附：版本差异备忘

| 内容 | 变化 |
|------|------|
| 结构体位置 | 4.14 前全在 binder.c，之后 proc/node/ref/thread/txn 迁入 binder_internal.h |
| desc 分配 | next_desc 线性递增 → 2024 合入 dbitmap 动态位图（0 位保留 ctx mgr） |
| 引用计数回执 | PUT_FILES/BINDER_DEFERRED_PUT_FILES 在新内核已移除（cred 重构） |
| 冻结族 | BINDER_FREEZE / ref_freeze / BR_FROZEN_* 为 Android 13+ 新增 |
| 扩展错误 | BINDER_GET_EXTENDED_ERROR / binder_thread.ee 为 Android 12+ 新增 |
| oneway 洪泛 | BINDER_ENABLE_ONEWAY_SPAM_DETECTION / spam_suspect 位为 Android 12+ 新增 |
| 页回收 | binder_alloc shrinker（freelist + binder_shrinker_mdata）为 mainline 2024+ 新增 |
| flat_binder_object | 8.0 前无 hdr（直接 type 字段），老二进制布局靠 binder_fd_object 的 pad 兼容 |

> 配套阅读：`Binder双向通信数据结构.md`（机制与流程）、本文（字段级 API 参考）。
