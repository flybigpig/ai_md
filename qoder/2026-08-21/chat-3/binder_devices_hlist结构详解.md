# binder_devices 链表结构详解（hlist 双指针设计）

---

## 一、背景：为什么 Binder 驱动有三台设备

现代 Android 内核（4.14+，binderfs 引入后）注册**三台 Binder 设备**，按用途隔离：

```
CONFIG_ANDROID_BINDER_DEVICES="binder,hwbinder,vndbinder"

┌──────────────┬─────────────────────┬────────────────────────────┐
│ 设备节点      │ 使用者               │ 隔离目的                    │
├──────────────┼─────────────────────┼────────────────────────────┤
│ /dev/binder  │ App 框架 (system_server, 应用) │ App↔App 通信         │
│ /dev/hwbinder│ HAL 服务 (vendor 进程)         │ App↔HAL 隔离 (Treble)│
│ /dev/vndbinder│ vendor 组件                    │ vendor↔vendor 隔离   │
└──────────────┴─────────────────────┴────────────────────────────┘
```

驱动用一个**全局 hlist** 把这三台设备串起来：

```c
// drivers/android/binder_internal.h
struct binder_device {
    struct hlist_node hlist;      // ← 挂到全局 binder_devices 的链表节点
    struct miscdevice miscdev;    // misc 设备描述
    struct binder_context context;// 该设备的独立上下文 (mgr_node/uid 各自独立!)
    struct binderfs_device device_info;
};

// drivers/android/binder.c
static HLIST_HEAD(binder_devices);   // 全局头节点
static char *binder_devices_param = CONFIG_ANDROID_BINDER_DEVICES; // "binder,hwbinder,vndbinder"
```

---

## 二、hlist 结构设计（与 list_head 的关键区别）

```c
// include/linux/list.h
struct hlist_head {
    struct hlist_node *first;     // 头节点只有一个指针（省内存）
};

struct hlist_node {
    struct hlist_node *next;      // 指向下一个节点
    struct hlist_node **pprev;    // ★ 指向指针的指针！
};
```

**`pprev` 的精髓：它存的是"前一个 `next` 指针自身的地址"，而不是前一个节点的地址。**

| 位置 | pprev 的实际值 | 意义 |
|------|---------------|------|
| 第一个节点 | `&h->first`（头节点的 first 字段地址） | 删除时改写头指针 |
| 中间节点 | `&prev->next`（前一节点的 next 字段地址） | 删除时改写前驱的 next |
| **好处** | 删除任意节点都是 `*n->pprev = n->next`，**O(1)** 且无需知道自己在头还是在中间 | — |

对比 `list_head`（双向循环链表）每个节点存 prev/next 两个完整指针，`hlist` 把头节点压缩成单指针——这正是哈希表 bucket 选择的方案（bucket 数量大时省一半内存），Binder 用它管理设备列表同理。

---

## 三、注册过程逐步追踪（对照内核源码）

```c
// drivers/android/binder.c
static int __init binder_init(void)
{
    device_names = kstrdup(binder_devices_param, GFP_KERNEL);
    // "binder,hwbinder,vndbinder" → 依次注册
    while ((device_name = strsep(&device_tmp, ","))) {
        ret = init_binder_device(device_name);   // 按字符串顺序: binder → hwbinder → vndbinder
        ...
    }
}

static int __init init_binder_device(const char *name)
{
    ...
    ret = misc_register(&binder_device->miscdev);   // 注册为 misc 设备
    hlist_add_head(&binder_device->hlist, &binder_devices);  // ★ 头插!
    return ret;
}
```

**关键：`hlist_add_head` 是头插法**，所以链表顺序是**注册顺序的倒序**。

### `hlist_add_head()` 源码

```c
static inline void hlist_add_head(struct hlist_node *n, struct hlist_head *h)
{
    struct hlist_node *first = h->first;

    WRITE_ONCE(n->next, first);              // 新节点 next 指向原头
    if (first)
        WRITE_ONCE(first->pprev, &n->next);  // 原头的 pprev 改指新节点的 next 字段
    WRITE_ONCE(h->first, n);                 // 头指针指向新节点
    WRITE_ONCE(n->pprev, &h->first);         // 新节点 pprev 指向头指针自身的地址
}
```

### 三次头插的逐帧追踪

```
初始状态:
  binder_devices.first ──▶ NULL

═══ 第 1 次插入: binder ═══════════════════════════════════════════
  n->next  = first(NULL)          → binder.hlist.next  = NULL
  first 为 NULL, 跳过
  h->first = &binder.hlist        → binder_devices.first 指向 binder
  n->pprev = &h->first            → binder.hlist.pprev = &binder_devices.first

  binder_devices.first ──▶ [binder | next=NULL, pprev=&binder_devices.first]

═══ 第 2 次插入: hwbinder ═════════════════════════════════════════
  n->next  = first(&binder.hlist) → hwbinder.hlist.next = &binder.hlist
  first->pprev = &n->next         → binder.hlist.pprev = &hwbinder.hlist.next  ★被改写!
  h->first = &hwbinder.hlist      → binder_devices.first 指向 hwbinder
  n->pprev = &h->first            → hwbinder.hlist.pprev = &binder_devices.first

  binder_devices.first ──▶ [hwbinder] ──▶ [binder]

═══ 第 3 次插入: vndbinder ════════════════════════════════════════
  n->next  = first(&hwbinder.hlist) → vndbinder.hlist.next = &hwbinder.hlist
  first->pprev = &n->next         → hwbinder.hlist.pprev = &vndbinder.hlist.next  ★被改写!
  h->first = &vndbinder.hlist     → binder_devices.first 指向 vndbinder
  n->pprev = &h->first            → vndbinder.hlist.pprev = &binder_devices.first

  最终状态: binder_devices.first ──▶ [vndbinder] ──▶ [hwbinder] ──▶ [binder] ──▶ NULL
```

---

## 四、最终内存关系图（验证你的图）

```
binder_devices.first                          (struct hlist_head, 单指针)
        │
        ▼
   [vndbinder.hlist] ──next──▶ [hwbinder.hlist] ──next──▶ [binder.hlist] ──next──▶ NULL
        │ pprev                     │ pprev                     │ pprev
        │                           │                           │
        ▼                           ▼                           ▼
 &binder_devices.first    &vndbinder.hlist.next      &hwbinder.hlist.next   ★
 (头插第一个: 指向头指针     (指向前一节点的 next 字段    (指向前一节点的 next 字段
  自身的地址)                自身的地址)                  自身的地址)
```

**对原图的一处修正：**

你的图中最后一项 `pprev` 写的是 `&hwbinder.hlist`，正确值应为 **`&hwbinder.hlist.next`** —— pprev 存的永远是"前驱 next 指针字段的地址"，末节点的前驱是 hwbinder，所以是 `&hwbinder.hlist.next`。前两项都正确。

---

## 五、pprev 指针指针设计的威力：O(1) 删除

```c
// 删除任意节点，无需遍历、无需知道是否是头节点:
static inline void __hlist_del(struct hlist_node *n)
{
    struct hlist_node *next = n->next;
    struct hlist_node **pprev = n->pprev;

    WRITE_ONCE(*pprev, next);          // ★ 一行完成摘除！
    if (next)
        WRITE_ONCE(next->pprev, pprev); // 后继的 pprev 改指我前驱的 next 字段
}
```

三种位置的删除效果：

```
删 [vndbinder] (头节点):
  *vndbinder.pprev = vndbinder.next   ≡  binder_devices.first = &hwbinder.hlist
  hwbinder.pprev = vndbinder.pprev    ≡  &binder_devices.first

删 [hwbinder] (中间节点):
  *hwbinder.pprev = hwbinder.next     ≡  vndbinder.hlist.next = &binder.hlist
  binder.pprev = hwbinder.pprev       ≡  &vndbinder.hlist.next

删 [binder] (尾节点):
  *binder.pprev = binder.next         ≡  hwbinder.hlist.next = NULL
  (binder.next 为 NULL, 无后继可改)
```

驱动卸载/设备注销时的遍历删除也依赖此结构：

```c
// binder_exit() 中
hlist_for_each_entry_safe(device, tmp, &binder_devices, hlist) {
    misc_deregister(&device->miscdev);
    hlist_del(&device->hlist);      // O(1) 摘除
    kfree(device);
}
```

---

## 六、hlist 与 binder_context 的关联

每个 `binder_device` 内嵌独立的 `binder_context`，这正是三设备隔离的实现基础：

```c
struct binder_context {
    struct binder_node *binder_context_mgr_node; // 各设备独立的 SM 节点
    struct mutex context_mgr_node_lock;
    kuid_t binder_context_mgr_uid;               // 各设备独立的 SM uid
    const char *name;                            // "binder"/"hwbinder"/"vndbinder"
};

// open 时通过 miscdev 关联到对应 context:
static int binder_open(struct inode *nodp, struct file *filp)
{
    ...
    proc->context = &binder_device->context;   // ★ binder_proc 绑定到所属设备的 context
    ...
}

// BINDER_SET_CONTEXT_MGR 时按各自 context 记录 mgr node:
// → /dev/binder 的 SM 是 system_server (framework)
// → /dev/hwbinder 的 SM 是 hwservicemanager
// → /dev/vndbinder 的 SM 是 vendor 侧管理进程
```

三台设备 → 三个 `binder_context` → 三棵独立的 `nodes/refs` 树 → 完全隔离的服务命名空间。

---

## 七、速记总结

| 要点 | 内容 |
|------|------|
| **注册顺序** | `binder → hwbinder → vndbinder`（CONFIG 字符串顺序） |
| **链表顺序** | `vndbinder → hwbinder → binder`（头插倒序） |
| **hlist_head** | 只有一个 `first` 指针（省内存） |
| **hlist_node** | `next` + `pprev`（指向指针的指针） |
| **pprev 规则** | 头节点存 `&head->first`，其余存 `&prev->next` |
| **删除复杂度** | O(1)，一行 `*n->pprev = n->next` |
| **原图修正** | 末节点 pprev 应为 `&hwbinder.hlist.next`（原缺 `.next`） |
| **隔离原理** | 每设备独立 `binder_context` → 独立 mgr_node/uid/命名空间 |
