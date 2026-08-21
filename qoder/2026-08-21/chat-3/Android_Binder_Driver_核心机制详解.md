# Android Binder 驱动核心机制详解

## 一、整体架构概览

Binder 是 Android 系统的 IPC（进程间通信）基石，采用 **C/S 架构**，由四部分组成：

| 角色 | 说明 |
|------|------|
| **Binder Driver** | 内核驱动（`/dev/binder`），负责实际的数据传输 |
| **ServiceManager** | 守护进程，管理所有系统服务的注册与查找 |
| **Server 端** | 注册服务，创建 binder_node，等待请求 |
| **Client 端** | 获取服务引用，向 Server 发送事务请求 |

---

## 二、关键数据结构

### 1. `binder_proc` — 进程描述符

每个打开 `/dev/binder` 设备的进程都会在内核中创建一个 `binder_proc`，它是该进程在 binder 驱动中的**总入口**。

```c
struct binder_proc {
    struct hlist_node proc_node;      // 挂在全局 binder_procs 链表上
    struct rb_root threads;           // 本进程中所有 binder 线程（binder_thread）
    struct rb_root nodes;             // 本进程拥有的所有 binder_node（服务端）
    struct rb_root refs;              // 本进程持有的所有 binder_ref（客户端）
    struct rb_root refs_by_desc;      // 按描述符（handle）索引的引用
    struct list_head todo;            // 本进程的待处理工作队列
    wait_queue_head_t wait;           // 等待队列，线程在此阻塞等待任务
    struct binder_stats stats;        // 统计信息
    atomic_t ready_threads;           // 就绪线程数
    int ready_thread_count;           // 用于线程池管理
    struct rb_root alloc;             // 内存分配树（映射区域管理）
    struct user_struct *user;         // 所属用户
    size_t free_async_space;          // 剩余异步传输空间
};
```

**核心要点：**
- `nodes` 红黑树存储本进程**发布的服务**（binder_node）
- `refs` 红黑树存储本进程**持有的远程服务引用**（binder_ref）
- `todo` 链表是工作队列，`binder_thread` 在 `wait` 上睡眠等待 `todo` 中的任务

### 2. `binder_node` — 服务节点

代表一个**Binder 服务端实体**，即一个被发布的 Binder 对象在内核中的表示。

```c
struct binder_node {
    struct rb_node rb_node;           // 挂在其所属 proc 的 nodes 红黑树
    int debug_id;                     // 调试用 ID
    struct binder_proc *proc;         // 所属进程
    struct hlist_head refs;           // 所有指向该 node 的 binder_ref 链表
    struct binder_work work;          // 工作项（用于异步通知）
    void __user *cookie;              // 用户态指针，标识该 binder 对象
    void __user *ptr;                 // 用户态 binder 对象指针
    atomic_t has_strong_ref;          // 是否有强引用
    atomic_t pending_strong_ref;      // 待处理的强引用操作
    atomic_t has_weak_ref;            // 是否有弱引用
    atomic_t pending_weak_ref;        // 待处理的弱引用操作
    int has_async_transaction;        // 是否有未完成的异步事务
    int accept_fds;                   // 是否接受文件描述符传输
    int min_priority;                 // 最低优先级（影响调度）
    bool txn_security_ctx;            // 事务是否携带安全上下文
    spinlock_t lock;                  // 保护内部状态的锁
};
```

**核心要点：**
- `cookie` 和 `ptr` 是用户态地址，驱动不解析它们，仅在引用计数变化时回传给用户态
- `refs` 链表连接所有引用了该 node 的 `binder_ref`
- `has_async_transaction` 用于异步调用的流控

### 3. `binder_ref` — 服务引用

代表一个进程对某个远程 binder_node 的**引用**（句柄）。

```c
struct binder_ref {
    struct rb_node rb_node_desc;      // 按 desc（handle）索引
    struct rb_node rb_node_node;      // 挂在其指向的 binder_node 的 refs 链表
    struct hlist_node node_entry;     // 挂在 binder_node.refs 上
    int debug_id;
    u32 desc;                         // 用户态看到的 handle 值（0 = ServiceManager）
    int node_debug_id;                // 指向的 binder_node 的 debug_id
    struct binder_node *node;         // 指向实际的 binder_node
    struct binder_proc *proc;         // 持有该引用的进程
    struct binder_ref_death *death;   // 死亡通知（可选）
    atomic_t strong;                  // 强引用计数
    atomic_t weak;                    // 弱引用计数
};
```

**核心要点：**
- `desc` 就是用户态使用的 **handle**，`desc == 0` 固定指向 ServiceManager
- 多个进程的 `binder_ref` 可以指向同一个 `binder_node`，形成多对一关系
- `death` 字段用于注册死亡通知回调

### 4. `binder_thread` — 线程描述符

```c
struct binder_thread {
    struct binder_proc *proc;         // 所属进程
    struct rb_node rb_node;           // 挂在 proc->threads 红黑树
    pid_t pid;                        // 线程 PID
    int thread_io_done;               // 线程是否已完成 IO
    struct binder_wait_for_work wait; // 线程等待队列
    struct binder_transaction *transaction_todo; // 正在处理的事务
    struct list_head waiting_thread_node; // 线程池管理链表
    int lo;                           // binder_loop_read_opts 状态标志
};
```

### 5. `binder_transaction` — 事务

代表一次完整的 IPC 调用。

```c
struct binder_transaction {
    struct binder_proc *from_proc;    // 发起方进程
    struct binder_thread *from_thread; // 发起方线程
    struct binder_proc *to_proc;      // 目标进程
    struct binder_thread *to_thread;  // 目标线程
    binder_size_t data_size;          // 数据大小
    binder_size_t offsets_size;       // 偏移表大小
    binder_size_t extra_buffers_size; // 额外缓冲区大小
    void __user *buffer;              // 内核映射的用户空间缓冲区
    struct binder_buffer *buf;        // 内核缓冲区描述符
    struct binder_frozen_status_info *frozen_status;
    int priority;                     // 事务优先级
    int saved_priority;               // 保存的优先级（用于优先级继承）
    int debug_id;                     // 调试 ID
};
```

### 数据结构关系图

```
┌─────────────────┐          ┌─────────────────┐
│   binder_proc A │          │   binder_proc B │
│   (Client)      │          │   (Server)      │
│                 │          │                 │
│  refs ──────────┼──┐       │  nodes ─────────┼──┐
│                 │  │       │                 │  │
│  threads ───────┼──┘       │  threads ───────┼──┘
│                 │          │                 │
└─────────────────┘          └─────────────────┘
         │                            │
         ▼                            ▼
┌─────────────────┐          ┌─────────────────┐
│  binder_ref     │          │  binder_node    │
│  desc=3         │─────────▶│  (Service X)    │
│  strong=1       │          │  refs ──────────┼──▶ [ref from A]
│  death ─────────┼──┐       │  cookie=0xXX    │
└─────────────────┘  │       └─────────────────┘
                     │
                     ▼
              ┌─────────────────┐
              │ binder_ref_death│
              │ (死亡通知)       │
              └─────────────────┘
```

---

## 三、Binder 事务传输原理

### 1. 整体流程

一次典型的同步 Binder 调用（如 Client 调用 Server 的方法）：

```
Client 用户态                Client 内核态               Server 内核态              Server 用户态
     │                           │                           │                          │
     │  ioctl(BINDER_WRITE_READ) │                           │                          │
     ├──────────────────────────▶│                           │                          │
     │                           │  1. 查找目标 binder_node   │                          │
     │                           │  2. 分配 binder_buffer    │                          │
     │                           │  3. 拷贝数据到缓冲区       │                          │
     │                           │  4. 找到目标 proc/thread   │                          │
     │                           │  5. 将事务加入目标 todo    │                          │
     │                           │  6. 唤醒目标等待线程        │                          │
     │                           │──────────────────────────▶│                          │
     │                           │  (wake_up)                │                          │
     │                           │                           │  7. 线程被唤醒             │
     │                           │                           │  8. 从 todo 取出事务       │
     │                           │                           │  9. 拷贝数据到用户空间      │
     │                           │                           │─────────────────────────▶ │
     │                           │                           │                          │ 处理请求
     │                           │                           │                          │ 生成回复
     │                           │                           │◀─────────────────────────┤
     │                           │◀──────────────────────────│  10. 回复事务              │
     │◀──────────────────────────│  11. 唤醒 Client 线程      │                          │
     │  返回结果                  │                           │                          │
```

### 2. 事务处理核心函数

**`binder_transaction()`** 是整个驱动最核心的函数，负责：

```c
static void binder_transaction(struct binder_proc *proc,
                               struct binder_thread *thread,
                               struct binder_transaction_data *tr,
                               int reply,
                               binder_size_t extra_buffers_size)
```

关键步骤：

**Step 1 — 定位目标**

```c
// 如果是回复（reply），直接找到原始调用者
if (reply) {
    in_reply_to = thread->transaction_stack;
    target_thread = in_reply_to->from_thread;
    target_proc = in_reply_to->from_proc;
} else {
    // 根据 handle 找到 binder_ref，再找到 binder_node 及其所属 proc
    ref = binder_get_ref_for_node(target_proc, node);
    target_proc = ref->node->proc;
}
```

**Step 2 — 分配缓冲区**

```c
// 在目标进程的 mmap 映射区域分配缓冲区
t->buffer = binder_alloc_buf(target_proc, tr->data_size,
                              tr->offsets_size, extra_buffers_size,
                              is_async);
```

**Step 3 — 拷贝数据（一次拷贝）**

```c
// 从用户空间一次性拷贝到内核缓冲区
if (binder_alloc_copy_user_to_buffer(target_proc, t->buffer, 0,
                                      tr->data.ptr.buffer,
                                      tr->data_size)) {
    // 拷贝失败处理
}
```

**Step 4 — 修复偏移量（Fixup）**

```c
// 对于数据中包含的 binder 对象引用（binder_object），
// 需要将其从用户态指针转换为内核态可追踪的引用
binder_transaction_object_fixup(t);
```

**Step 5 — 入队并唤醒**

```c
// 将事务加入目标线程/进程的 todo 队列
binder_enqueue_transaction(t);
wake_up_interruptible(&target_thread->wait);
```

### 3. 同步 vs 异步事务

| 特性 | 同步事务（SYNC） | 异步事务（ASYNC） |
|------|-----------------|------------------|
| 线程阻塞 | 调用线程阻塞等待回复 | 调用线程立即返回 |
| 缓冲区限制 | 无特殊限制 | 受 `max_async_space` 限制 |
| 使用场景 | 普通 AIDL 调用 | `oneway` 接口调用 |
| 流控 | 无 | 缓冲区满时阻塞/丢弃 |

---

## 四、一次拷贝的实现

### 1. 传统 IPC 的两次拷贝

传统 Linux IPC（如管道、Socket）的数据传输需要**两次拷贝**：

```
发送方用户空间 ──拷贝①──▶ 内核缓冲区 ──拷贝②──▶ 接收方用户空间
```

### 2. Binder 的一次拷贝

Binder 通过 **内存映射（mmap）** 实现只需要一次拷贝：

```
发送方用户空间 ──拷贝①──▶ 内核缓冲区（同时映射到接收方地址空间）──▶ 接收方直接读取
```

### 3. 实现细节

**Step 1 — 服务端 mmap 映射内核缓冲区**

Server 进程在启动时调用 `mmap()` 映射 `/dev/binder` 设备：

```c
// 用户态
mmap(NULL, MAP_SIZE, PROT_READ, MAP_SHARED, binder_fd, 0);

// 内核态 binder_mmap()
static int binder_mmap(struct file *filp, struct vm_area_struct *vma)
{
    // 1. 分配物理页面
    for (i = 0; i < pages; i++) {
        page = alloc_page(GFP_KERNEL | __GFP_HIGHMEM);
        proc->pages[i] = page;
    }

    // 2. 建立用户空间映射（vma 指向用户地址空间）
    //    将这些物理页面映射到 proc 的用户空间虚拟地址

    // 3. 同时内核也持有这些物理页面的指针
    //    内核可以直接通过 page_address(page) 访问

    // 关键：同一块物理内存同时被用户空间和内核空间访问！
}
```

**Step 2 — 发送方写入数据（一次拷贝）**

```c
// binder_alloc_copy_user_to_buffer()
// 将发送方用户空间的数据拷贝到目标进程映射的物理页面
// 这只需要一次拷贝：从发送方用户空间 → 共享物理页面
copy_from_user(buffer->data + offset, user_ptr, size);
```

**Step 3 — 接收方直接读取（零拷贝）**

```c
// 接收方不需要拷贝！
// 因为物理页面已经映射到了接收方的用户空间
// 接收方直接通过指针读取数据即可
// binder_thread_read() 中只需告诉接收方数据的偏移量
```

### 4. 缓冲区管理

```
binder_proc 的 mmap 区域布局：

┌─────────────────────────────────────────────────┐
│              连续的虚拟地址空间                    │
│  (通过 mmap 映射到一组物理页面)                    │
├──────────┬──────────┬──────────┬────────────────┤
│ 已分配    │ 已分配    │ 空闲      │ 空闲           │
│ buffer A │ buffer B │ buffer C │ ...            │
│ (事务1)  │ (事务2)  │          │                │
└──────────┴──────────┴──────────┴────────────────┘
     ▲                        ▲
     │                        │
  binder_buffer 链表管理     空闲区域通过红黑树管理
```

`binder_buffer` 结构管理每个分配的缓冲区：

```c
struct binder_buffer {
    struct list_head entry;           // 挂在全局或空闲链表
    struct rb_node rb_node;           // 挂在空闲区域红黑树（按地址）
    unsigned free:1;                  // 是否空闲
    unsigned allow_user_free:1;       // 是否允许用户释放
    unsigned debug_id;                // 调试 ID
    size_t size;                      // 缓冲区大小
    void __user *user_data;           // 用户态数据指针
};
```

---

## 五、Binder 死亡通知机制

### 1. 为什么需要死亡通知

Client 持有 Server 的 `binder_ref`，如果 Server 进程异常退出，Client 需要被通知，否则会继续向一个不存在的 Server 发送请求。

### 2. `binder_ref_death` 结构

```c
struct binder_ref_death {
    struct binder_work work;          // 工作项，类型标识为死亡通知
    void __user *cookie;              // 用户态回调标识
};
```

### 3. 注册死亡通知

Client 通过 `BINDER_REQUEST_DEATH_NOTIFICATION` 命令注册：

```c
// 用户态
struct binder_handle_cookie {
    uint32_t handle;
    void *cookie;
};
ioctl(binder_fd, BINDER_REQUEST_DEATH_NOTIFICATION, &handle_cookie);

// 内核态 binder_request_death_notification()
static int binder_request_death_notification(struct binder_thread *thread,
                                             struct binder_transaction_data *tr)
{
    ref = binder_get_ref_for_proc(thread->proc, tr->target.handle);

    // 分配 binder_ref_death 并挂到 ref->death
    death = kzalloc(sizeof(*death), GFP_KERNEL);
    death->cookie = (void __user *)(uintptr_t)tr->cookie;
    death->work.type = BINDER_WORK_DEAD_BINDER;

    ref->death = death;

    // 将工作项加入目标 proc 的 todo 队列
    binder_enqueue_work(thread->proc, &death->work);
}
```

### 4. 触发死亡通知

当 Server 进程退出或关闭 binder fd 时：

```
binder_release() / binder_flush()
    │
    ▼
binder_deferred_release()
    │
    ▼
binder_release_work() ──▶ 遍历该 proc 的所有 binder_node
    │
    ▼
对每个 binder_node，遍历其 refs 链表上的所有 binder_ref
    │
    ▼
对于有 death 的 ref：
    │
    ▼
将 BINDER_WORK_DEAD_BINDER 工作项加入 Client proc 的 todo 队列
    │
    ▼
wake_up() 唤醒 Client 中等待的线程
```

### 5. 用户态接收死亡通知

Client 的 binder 循环在 `ioctl(BINDER_WRITE_READ)` 返回时，检查 `work_type`：

```c
// 内核态 binder_thread_read()
case BINDER_WORK_DEAD_BINDER:
    // 将死亡通知传递给用户态
    tr->cookie = death->cookie;  // 用户态用来标识哪个 binder 对象
    // 设置返回类型为 BR_DEAD_BINDER
    put_user(BR_DEAD_BINDER, (uint32_t __user *)ptr);

// 用户态收到 BR_DEAD_BINDER 后：
// 1. 调用 binder 库的 death callback
// 2. 发送 BINDER_SEND_DEAD_BINDER_ACK 确认
```

### 6. 完整生命周期

```
时间线：

T1: Server 注册服务
    └─▶ 内核创建 binder_node (proc=S, node=ServiceX)

T2: Client 获取服务引用
    └─▶ 内核创建 binder_ref (proc=C, desc=3, node=ServiceX)

T3: Client 注册死亡通知
    └─▶ 创建 binder_ref_death, 挂到 ref->death

T4: Client 发起同步调用
    └─▶ binder_transaction() → 数据拷贝 → Server 处理 → 返回结果

T5: Server 进程崩溃/退出
    └─▶ binder_release() 被调用
    └─▶ 遍历所有 binder_node 的 refs
    └─▶ 发现 ref->death != NULL
    └─▶ 向 Client proc 的 todo 队列加入 BINDER_WORK_DEAD_BINDER
    └─▶ wake_up(Client proc)

T6: Client 收到通知
    └─▶ ioctl 返回，work_type = BR_DEAD_BINDER
    └─▶ 用户态触发 onBinderDied() 回调
    └─▶ Client 可以重新获取服务或做降级处理
```

### 7. 强/弱引用与死亡通知的关系

| 引用类型 | 命令 | 影响 |
|---------|------|------|
| **强引用** | `BINDER_INCREFS` / `BINDER_ACQUIRE` | 保持 binder_node 存活，计数 > 0 时 node 不会被释放 |
| **弱引用** | `BINDER_INCREFS_WEAK` | 仅跟踪，不阻止释放 |
| **死亡通知** | `BINDER_REQUEST_DEATH_NOTIFICATION` | 当 node 被释放时通知引用方 |

当 binder_node 的强引用和弱引用都降为 0 时：

1. 内核向所有持有该 node 引用的进程发送 `BR_DEAD_BINDER`
2. 各 Client 回复 `BR_DEAD_BINDER_ACK`
3. 内核释放 `binder_node` 和所有关联的 `binder_ref`

---

## 六、总结

| 机制 | 核心思想 |
|------|---------|
| **数据结构** | `binder_proc` 管理进程全局，`binder_node` 代表服务实体，`binder_ref` 代表远程引用，三者通过红黑树高效组织 |
| **事务传输** | 通过 `binder_transaction` 完成查找目标→分配缓冲区→拷贝数据→入队唤醒的完整流程 |
| **一次拷贝** | 利用 `mmap` 将内核物理页同时映射到用户空间和内核空间，发送方写入一次，接收方直接读取 |
| **死亡通知** | 通过 `binder_ref_death` 注册回调，当 binder_node 被释放时向所有引用方发送 `BR_DEAD_BINDER` 通知 |

Binder 驱动的设计哲学是**最小化数据拷贝、最大化传输效率**，通过共享内存映射和精心设计的引用计数体系，在微内核架构的 Android 系统中实现了高效的进程间通信。
