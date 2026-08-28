# ServiceManager 启动与初始化详解

---

## 一、ServiceManager 整体架构

```
═══════════════════════════════════════════════════════════════════════════════════
  ServiceManager 在 Android 系统中的位置
═══════════════════════════════════════════════════════════════════════════════════

  ┌─────────────────────────────────────────────────────────────────────────────┐
  │                                                                             │
  │  ┌──────────────────────────────────────────────────────────────────────┐   │
  │  │  Native ServiceManager (C++ 守护进程)                                │   │
  │  │  路径: /frameworks/native/cmds/servicemanager/                       │   │
  │  │  可执行文件: /system/bin/servicemanager                               │   │
  │  │  PID: 通常在 PID 2~10 之间 (系统最早启动的进程之一)                    │   │
  │  │                                                                      │   │
  │  │  职责:                                                               │   │
  │  │  - 维护全局服务注册表 (服务名 → Binder 代理)                           │   │
  │  │  - 提供 addService() / getService() / listServices() 接口            │   │
  │  │  - 监控服务死亡 (death recipient)                                    │   │
  │  │  - SELinux 权限检查                                                  │   │
  │  └──────────────────────────────────────────────────────────────────────┘   │
  │                              ▲                                              │
  │                              │ Binder IPC                                   │
  │                              │                                              │
  │  ┌───────────────────────────┼──────────────────────────────────────────┐   │
  │  │  Java 层 ServiceManager (framework 类)                               │   │
  │  │  路径: frameworks/base/core/java/android/os/ServiceManager.java      │   │
  │  │                                                                      │   │
  │  │  职责:                                                               │   │
  │  │  - 为 Java 层提供访问 Native ServiceManager 的封装                     │   │
  │  │  - addService() → 注册服务                                          │   │
  │  │  - getService() → 查找服务                                          │   │
  │  │  - checkService() → 检查服务是否存在                                 │   │
  │  │  - listServices() → 列出所有服务                                     │   │
  │  └──────────────────────────────────────────────────────────────────────┘   │
  │                                                                             │
  │  使用方:                                                                    │
  │  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐          │
  │  │ SystemServer     │  │ 应用进程          │  │ 其他系统进程      │          │
  │  │ (注册服务)        │  │ (查找服务)        │  │ (查找服务)        │          │
  │  │                  │  │                  │  │                  │          │
  │  │ addService(      │  │ getService(      │  │ getService(      │          │
  │  │  "window", wm)   │  │  "window")       │  │  "window")       │          │
  │  │ addService(      │  │ → 返回           │  │ → 返回           │          │
  │  │  "activity", am) │  │ IWindowManager   │  │ IWindowManager   │          │
  │  └──────────────────┘  └──────────────────┘  └──────────────────┘          │
  │                                                                             │
  └─────────────────────────────────────────────────────────────────────────────┘
```

---

## 二、Native ServiceManager — main() 入口

### 1. main() 函数

```c
// ═══════════════════════════════════════════════════════════════════════
// 文件: frameworks/native/cmds/servicemanager/service_manager.c
// 方法: main()
// 作用: Native ServiceManager 守护进程的入口
// 启动时机: init 进程通过 init.rc 最早启动
// ═══════════════════════════════════════════════════════════════════════

// init.rc 中的配置:
// service servicemanager /system/bin/servicemanager
//     class core                  ← 核心服务类
//     user system                 ← 以 system 用户运行
//     group system readproc       ← 所属用户组
//     critical                    ← 关键进程，崩溃后系统重启
//     onrestart restart healthd   ← 重启时连带重启 healthd
//     onrestart restart zygote    ← 重启时连带重启 zygote
//     onrestart restart audioserver
//     onrestart restart surfaceflinger
//     onrestart restart inputflinger
//     onrestart restart media     ← 等等...

int main(int argc, char** argv)
{
    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 1: 初始化 Binder 驱动                                      │
    // └─────────────────────────────────────────────────────────────────┘

    // ① 打开 /dev/binder 设备
    // 这是 Binder 驱动的设备文件
    // 打开后会创建 binder_proc (内核中该进程的描述符)
    struct binder_state *bs = binder_open(128*1024);
    // 参数: 128KB — mmap 映射的缓冲区大小
    //
    // binder_open() 内部:
    //   // 打开设备文件
    //   fd = open("/dev/binder", O_RDWR | O_CLOEXEC);
    //
    //   // mmap 映射共享内存
    //   // 这块内存用于接收/发送 Binder 事务
    //   // ServiceManager 只需要 128KB (很小)
    //   // 而普通应用进程通常需要 1MB~4MB
    //   // 因为 SM 只处理 addService/getService 请求
    //   // 数据量很小
    //   mmap(NULL, mapsize, PROT_READ, MAP_SHARED, fd, 0);
    //
    //   // 返回 binder_state 结构
    //   // 包含 fd 和 mmap 区域指针

    if (bs == NULL) {
        // 打开失败 → 退出
        // 这是致命的，ServiceManager 无法运行
        return -1;
    }

    // ② 成为 Binder 上下文管理器 (Context Manager)
    // 这是 ServiceManager 最核心的操作!
    // 通过 BINDER_SET_CONTEXT_MGR ioctl 告诉内核:
    //   "我是 handle=0 的特殊节点"
    //
    // 在 Binder 协议中:
    //   handle = 0 固定指向 ServiceManager
    //   所有进程查找服务时，都通过 handle=0 发送请求
    //   内核将 handle=0 的事务路由到 ServiceManager 进程
    if (binder_become_context_manager(bs)) {
        // 失败退出
        return -1;
    }
    // binder_become_context_manager() 内部:
    //   // 通过 ioctl 设置当前进程为上下文管理器
    //   ioctl(bs->fd, BINDER_SET_CONTEXT_MGR, 0);
    //   //
    //   // 内核中:
    //   //   创建 binder_node (handle=0)
    //   //   所有进程的 handle=0 都指向这个 node
    //   //   所有 getService/addService 请求都路由到这里

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 2: SELinux 初始化                                           │
    // └─────────────────────────────────────────────────────────────────┘

    // ③ 初始化 SELinux 策略
    // ServiceManager 需要检查:
    //   - 哪个进程有权注册哪个服务
    //   - 哪个进程有权查找哪个服务
    // 例如:
    //   - system_server 可以注册 "window" 服务
    //   - 普通应用不能注册系统服务
    //   - 所有进程都可以查找 "window" 服务
    selinux_callback cb;
    cb.func_log = selinux_log;
    selinux_set_callback(SELINUX_CB_LOG, cb);

    // ④ 加载 SELinux 策略
    // 从 /sys/fs/selinux/policy 加载安全策略
    // 用于后续的权限检查
    if (sehandle) {
        // SELinux 已启用
        // 后续每个 addService/getService 都会检查权限
    }

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 3: 进入事件循环                                             │
    // └─────────────────────────────────────────────────────────────────┘

    // ⑤ 进入 Binder 事件循环
    // 这是 ServiceManager 的主循环，永不退出
    // 循环处理:
    //   - addService 请求 (注册服务)
    //   - getService 请求 (查找服务)
    //   - listServices 请求 (列出所有服务)
    //   - 服务死亡通知 (death notification)
    binder_loop(bs, svcmgr_handler);
    // ★ svcmgr_handler 是请求处理回调 (见下方详解)

    return 0;
    // 实际上永远不会执行到这里
    // binder_loop() 是一个无限循环
}
```

### 2. binder_loop() — 事件循环

```c
// ═══════════════════════════════════════════════════════════════════════
// 文件: frameworks/native/libs/binder/binder.c
// 方法: binder_loop()
// 作用: ServiceManager 的 Binder 事件循环
// 永不退出，持续处理来自各进程的请求
// ═══════════════════════════════════════════════════════════════════════

void binder_loop(struct binder_state *bs, binder_handler func)
{
    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 1: 告诉驱动开始循环                                         │
    // └─────────────────────────────────────────────────────────────────┘

    // ① 发送 BC_ENTER_LOOPER 命令
    // 告诉 Binder 驱动: "我开始轮询了，请给我分配事务"
    // 驱动会将此线程加入等待队列
    // 当有事务到达时，会唤醒此线程
    uint32_t cmd = BC_ENTER_LOOPER;
    write(bs->fd, &cmd, sizeof(cmd));
    // 写入 /dev/binder 设备
    // 驱动收到 BC_ENTER_LOOPER 后:
    //   - 标记此线程为 looper 线程
    //   - 将其加入等待队列
    //   - 当有 Binder 事务到达时唤醒它

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 2: 无限循环处理事务                                          │
    // └─────────────────────────────────────────────────────────────────┘

    // ② 主循环
    for (;;) {
        struct binder_transaction_data tr;
        struct binder_write_read bwr;
        uint32_t cmd;

        // ─── 等待事务 ───
        // 构造读取请求
        bwr.read.size = sizeof(tr);        // 期望读取一个事务数据
        bwr.read.buffer = (uintptr_t)&tr;  // 读取缓冲区

        // ★ 关键: 调用 ioctl 等待事务
        // 如果没有事务，当前线程会在此处阻塞 (睡眠)
        // 当有进程发送 addService/getService 请求时:
        //   1. 请求通过 Binder 驱动路由到 ServiceManager
        //   2. 驱动唤醒 ServiceManager 的等待线程
        //   3. ioctl 返回，tr 中包含请求数据
        int ret = binder_write_read(bs, &bwr);
        // binder_write_read() 内部:
        //   ioctl(bs->fd, BINDER_WRITE_READ, &bwr);
        //
        // 这是一个阻塞调用!
        // ServiceManager 大部分时间都在此处睡眠
        // 只有当有请求到达时才会被唤醒

        if (ret < 0) {
            // 读取失败
            break;
        }

        // ─── 解析返回的命令 ───
        // ioctl 返回后，需要解析驱动返回的命令
        // 常见命令:
        //   BR_TRANSACTION: 有事务到达 (addService/getService)
        //   BR_DEAD_BINDER: 有服务死亡
        //   BR_OK: 操作成功
        //   BR_NOOP: 无操作
        //   BR_SPAWN_LOOPER: 驱动要求创建新的 looper 线程

        // 从读取缓冲区中解析命令
        cmd = *(uint32_t*)(bwr.read.buffer);

        switch (cmd) {
        case BR_TRANSACTION:
            // ★ 有事务到达!
            // tr 中包含:
            //   tr.target.ptr  → ServiceManager 的 binder_node 指针
            //   tr.code        → 操作码 (SVC_MGR_ADD_SERVICE 等)
            //   tr.data        → 请求数据 (服务名、Binder 代理等)

            // 调用处理回调
            // func = svcmgr_handler (见下方详解)
            if (func(bs, &tr, &bwr) < 0) {
                // 处理失败
                return;
            }
            break;

        case BR_DEAD_BINDER:
            // 有服务死亡
            // 从注册表中移除该服务
            // (见下方死亡处理详解)
            break;

        case BR_SPAWN_LOOPER:
            // 驱动要求创建新的 looper 线程
            // 当当前线程忙于处理事务时
            // 新到达的请求需要另一个线程处理
            // (ServiceManager 通常只有一个线程)
            break;

        default:
            // 其他命令
            break;
        }
    }
}
```

### 3. svcmgr_handler() — 请求处理回调

```c
// ═══════════════════════════════════════════════════════════════════════
// 文件: frameworks/native/cmds/servicemanager/service_manager.c
// 方法: svcmgr_handler()
// 作用: 处理来自各进程的 Binder 请求
// 被 binder_loop() 中的 BR_TRANSACTION 分支调用
// ═══════════════════════════════════════════════════════════════════════

int svcmgr_handler(struct binder_state *bs,
                   struct binder_transaction_data *tr,
                   struct binder_io *msg,
                   struct binder_io *reply)
{
    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 解析请求                                                        │
    // └─────────────────────────────────────────────────────────────────┘

    // ① 获取操作码
    // tr->code 标识请求类型:
    //   SVC_MGR_GET_SERVICE    = 4  → 查找服务 (阻塞等待)
    //   SVC_MGR_CHECK_SERVICE  = 3  → 检查服务 (立即返回)
    //   SVC_MGR_ADD_SERVICE    = 2  → 注册服务
    //   SVC_MGR_LIST_SERVICES  = 5  → 列出所有服务
    uint32_t code = tr->code;

    // ② 解析请求数据
    // 从 msg (binder_io) 中读取参数
    // 请求格式:
    //   [服务名 (字符串)] [Binder 代理 (仅 addService)] [权限信息]
    struct flat_binder_object *obj;
    uint32_t len;
    uint16_t *svc_name;

    // 读取服务名 (UTF-16 字符串)
    svc_name = bio_get_string16(msg, &len);
    // 例如: "window", "activity", "input_method" 等

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 分发处理                                                        │
    // └─────────────────────────────────────────────────────────────────┘

    switch (code) {

    // ═══════════════════════════════════════════════════════════════════
    // 操作 1: SVC_MGR_CHECK_SERVICE — 检查服务是否存在
    // ═══════════════════════════════════════════════════════════════════
    case SVC_MGR_CHECK_SERVICE: {
        // 在服务注册表中查找
        // svc_to_handle() 遍历链表，根据名称查找对应的 Binder 代理
        void *handle = svc_to_handle(svc_name, len);
        // 内部逻辑:
        //   struct svcinfo *si = svclist;  // 全局服务链表头
        //   while (si) {
        //       if (si->len == len &&
        //           memcmp(si->name, svc_name, len * 2) == 0) {
        //           // 找到! 返回 handle
        //           return si->handle;
        //       }
        //       si = si->next;
        //   }
        //   return NULL;  // 未找到

        // 将结果写入 reply
        bio_put_ref(reply, (uintptr_t)handle);
        // 返回 Binder 代理的 handle 值
        // 调用方通过此 handle 获取服务的 Binder 代理
        break;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 操作 2: SVC_MGR_GET_SERVICE — 查找服务 (阻塞等待)
    // ═══════════════════════════════════════════════════════════════════
    case SVC_MGR_GET_SERVICE: {
        // 与 checkService 类似，但如果服务不存在会等待
        void *handle = svc_to_handle(svc_name, len);

        if (handle == NULL) {
            // 服务不存在 → 返回 NULL
            // 调用方 (SystemServer) 会重试
            bio_put_ref(reply, 0);
        } else {
            bio_put_ref(reply, (uintptr_t)handle);
        }
        break;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 操作 3: SVC_MGR_ADD_SERVICE — 注册服务
    // ═══════════════════════════════════════════════════════════════════
    case SVC_MGR_ADD_SERVICE: {
        // ③ 解析 Binder 代理对象
        // 请求中包含要注册的服务的 Binder 代理
        obj = bio_get_obj(msg);
        // obj->binder → 服务的 Binder 本地对象指针
        // obj->handle → 服务的 handle 值

        // ④ SELinux 权限检查
        // 检查调用方是否有权注册此服务
        // 例如:
        //   - system_server 可以注册 "window" 服务
        //   - 普通应用不能注册系统服务
        if (sehandle) {
            int access = check_access(svc_name, obj);
            if (access != 0) {
                // 权限不足 → 拒绝
                return -1;
            }
        }

        // ⑤ 检查是否已存在同名服务
        if (svc_to_handle(svc_name, len) != NULL) {
            // 服务已存在 → 拒绝 (防止重复注册)
            return -1;
        }

        // ⑥ 注册服务
        // 创建 svcinfo 结构，加入全局链表
        svcinfo *si = malloc(sizeof(*si));
        si->next = svclist;           // 挂到链表头部
        si->len = len;                // 服务名长度
        memcpy(si->name, svc_name, len * 2);  // 服务名
        si->handle = obj->binder;     // Binder 代理
        si->death_func = NULL;        // 死亡回调 (后续设置)
        si->pid = tr->sender_pid;     // 注册方 PID
        si->uid = tr->sender_euid;    // 注册方 UID
        si->is_allowed = 1;           // 是否允许

        svclist = si;  // 更新链表头

        // ⑦ 注册死亡通知
        // 当服务进程死亡时，ServiceManager 会收到通知
        // 并从注册表中移除该服务
        binder_acquire(bs, obj->binder);
        // 获取强引用，防止服务被意外释放

        binder_link_to_death(bs, obj->binder, &svcmgr_death_notifier);
        // 注册死亡回调:
        //   当服务进程退出时:
        //     1. Binder 驱动发送 BR_DEAD_BINDER 到 ServiceManager
        //     2. ServiceManager 的 binder_loop 处理此命令
        //     3. 调用 svcmgr_death_handler()
        //     4. 从 svclist 中移除该服务

        // ⑧ 返回成功
        bio_put_ref(reply, 0);
        break;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 操作 4: SVC_MGR_LIST_SERVICES — 列出所有服务
    // ═══════════════════════════════════════════════════════════════════
    case SVC_MGR_LIST_SERVICES: {
        // ⑨ 获取偏移量
        // 调用方可以分页获取服务列表
        // n = 偏移量，从第 n 个服务开始返回
        uint32_t n = bio_get_uint32(msg);

        // ⑩ 遍历链表找到第 n 个服务
        struct svcinfo *si = svclist;
        while (n-- && si) {
            si = si->next;
        }

        if (si != NULL) {
            // 返回服务名
            bio_put_string16(reply, si->name, si->len);
        } else {
            // 已到末尾
            bio_put_string16(reply, NULL, 0);
        }
        break;
    }

    default:
        // 未知操作码
        return -1;
    }

    return 0;
}
```

---

## 三、Java 层 ServiceManager

### 4. ServiceManager.java — Java 层封装

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: frameworks/base/core/java/android/os/ServiceManager.java
// 作用: 为 Java 层提供访问 Native ServiceManager 的封装
// 所有进程 (包括 system_server 和应用进程) 都使用此类
// ═══════════════════════════════════════════════════════════════════════

public final class ServiceManager {
    private static final String TAG = "ServiceManager";

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 核心成员: IServiceManager 代理                                    │
    // └─────────────────────────────────────────────────────────────────┘

    // ① IServiceManager 代理
    // 这是 Native ServiceManager 在 Java 层的 Binder 代理
    // 通过它与 Native SM 通信
    private static IServiceManager sServiceManager;
    // IServiceManager 是 AIDL 生成的接口
    // 定义了: getService(), addService(), checkService(), listServices()

    // ② 服务缓存
    // 缓存已查找的服务 Binder 代理，避免重复查询
    // key: 服务名 (如 "window")
    // value: IBinder (服务的 Binder 代理)
    private static HashMap<String, IBinder> sCache =
            new HashMap<String, IBinder>();

    // ═══════════════════════════════════════════════════════════════════
    // 方法: getIServiceManager() — 获取 IServiceManager 代理
    // 懒加载模式，首次调用时创建
    // ═══════════════════════════════════════════════════════════════════
    private static IServiceManager getIServiceManager() {
        if (sServiceManager != null) {
            return sServiceManager;
        }

        // 通过 Binder 内部机制获取 IServiceManager 代理
        // 关键: handle = 0 固定指向 ServiceManager
        sServiceManager = ServiceManagerNative.asInterface(
                Binder.allowBinderInternalCreation(
                    BinderInternal.getContextObject()));
        // 分解:
        //
        // BinderInternal.getContextObject():
        //   → 返回 handle=0 的 Binder 代理
        //   → 即 Native ServiceManager 的 Binder 代理
        //   → 所有进程的 handle=0 都指向同一个 Native SM
        //
        // ServiceManagerNative.asInterface(binder):
        //   → 将 Binder 代理转换为 IServiceManager 接口
        //   → 内部使用 AIDL 生成的 Proxy 类
        //   → 后续调用 getService()/addService() 都通过此代理
        //      发送 Binder 事务到 Native SM

        return sServiceManager;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 方法: getService() — 查找服务
    // 应用进程通过此方法获取系统服务的 Binder 代理
    // ═══════════════════════════════════════════════════════════════════
    public static IBinder getService(String name) {
        try {
            // ① 先查缓存
            IBinder service = sCache.get(name);
            if (service != null) {
                return service;
                // 缓存命中 → 直接返回
                // 避免每次都通过 Binder IPC 查询 Native SM
            }

            // ② 缓存未命中 → 通过 Binder IPC 查询
            IBinder binder = getIServiceManager().getService(name);
            // 内部:
            //   → 通过 Binder 事务发送 SVC_MGR_GET_SERVICE 到 Native SM
            //   → Native SM 的 svcmgr_handler() 处理请求
            //   → 在 svclist 中查找服务名
            //   → 返回服务的 Binder 代理的 handle
            //   → Java 层将 handle 转换为 IBinder 代理

            if (binder != null) {
                // 加入缓存
                sCache.put(name, binder);
            }

            return binder;
            // 返回的 IBinder 是目标服务的代理
            // 例如: getService("window") → IWindowManager.Proxy
            //       getService("activity") → IActivityManager.Proxy
            //       getService("input_method") → IInputMethodManager.Proxy

        } catch (RemoteException e) {
            // Native SM 进程死亡 (极罕见)
            Log.e(TAG, "error in getService", e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 方法: checkService() — 检查服务是否存在 (非阻塞)
    // ═══════════════════════════════════════════════════════════════════
    public static IBinder checkService(String name) {
        try {
            // 先查缓存
            IBinder service = sCache.get(name);
            if (service != null) {
                return service;
            }

            // 通过 Binder IPC 查询
            // 与 getService 不同:
            //   getService: 如果服务不存在，可能等待
            //   checkService: 立即返回，不存在则返回 null
            IBinder binder = getIServiceManager().checkService(name);

            if (binder != null) {
                sCache.put(name, binder);
            }

            return binder;
        } catch (RemoteException e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 方法: addService() — 注册服务
    // 仅 system_server 调用，普通应用无权调用
    // ═══════════════════════════════════════════════════════════════════
    public static void addService(String name, IBinder service,
            boolean allowIsolated, int dumpPriority) {
        try {
            // 通过 Binder IPC 注册服务
            getIServiceManager().addService(name, service,
                    allowIsolated, dumpPriority);
            // 内部:
            //   → 发送 SVC_MGR_ADD_SERVICE 事务到 Native SM
            //   → Native SM 的 svcmgr_handler() 处理:
            //     1. SELinux 权限检查
            //     2. 检查是否已存在同名服务
            //     3. 创建 svcinfo，加入 svclist
            //     4. 注册死亡通知

            // 同时加入本地缓存
            sCache.put(name, service);
        } catch (RemoteException e) {
            Log.e(TAG, "error in addService", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 方法: listServices() — 列出所有已注册服务
    // ═══════════════════════════════════════════════════════════════════
    public static String[] listServices() {
        try {
            return getIServiceManager().listServices(0);
            // 内部:
            //   → 循环发送 SVC_MGR_LIST_SERVICES 事务
            //   → 每次返回一个服务名
            //   → 直到返回 null 表示结束
            //   → 汇总所有服务名返回
        } catch (RemoteException e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 方法: initServiceCache() — 初始化缓存
    // 在进程启动时调用，预填充已知的服务
    // ═══════════════════════════════════════════════════════════════════
    public static void initServiceCache(Map<String, IBinder> cache) {
        if (sCache.size() != 0) {
            throw new IllegalStateException("service cache already initialized");
        }
        sCache = cache;
        // 在 Zygote fork 子进程时调用
        // 将 Zygote 进程中已缓存的服务传递给子进程
        // 避免每个子进程都重新查询
    }
}
```

---

## 四、SystemServer 中注册服务

### 5. SystemServer 注册所有系统服务

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: SystemServer.java
// 方法: startBootstrapServices() / startCoreServices() / startOtherServices()
// 作用: 创建并注册所有系统服务到 ServiceManager
// ═══════════════════════════════════════════════════════════════════════

// 注册服务的统一模式:
// 1. 创建服务实例
// 2. 调用 ServiceManager.addService() 注册

// ┌─────────────────────────────────────────────────────────────────────┐
// │ 引导服务 (Bootstrap Services)                                       │
// └─────────────────────────────────────────────────────────────────────┘

private void startBootstrapServices() {

    // ① ActivityManagerService (AMS)
    // 管理所有 Activity 的生命周期
    mActivityManagerService = ActivityManagerService.Lifecycle.startService(
            mSystemContext);
    ServiceManager.addService("activity",
            mActivityManagerService,
            /* allowIsolated= */ true,
            DUMP_FLAG_PRIORITY_CRITICAL);
    // 注册为 "activity"
    // 应用通过 ServiceManager.getService("activity") 获取

    // ② PowerManagerService
    // 管理电源状态 (亮屏/休眠/唤醒)
    mPowerManagerService = new PowerManagerService(mSystemContext);
    ServiceManager.addService(Context.POWER_SERVICE,
            mPowerManagerService,
            /* allowIsolated= */ false,
            DUMP_FLAG_PRIORITY_CRITICAL);
    // 注册为 "power"

    // ③ PackageManagerService
    // 管理应用安装/卸载/APK 解析
    mPackageManagerService = PackageManagerService.main(
            mSystemContext, mInstaller, ...);
    ServiceManager.addService("package",
            mPackageManagerService,
            /* allowIsolated= */ true,
            DUMP_FLAG_PRIORITY_CRITICAL);
    // 注册为 "package"

    // ... 更多引导服务
}

// ┌─────────────────────────────────────────────────────────────────────┐
// │ 核心服务 (Core Services)                                            │
// ┌─────────────────────────────────────────────────────────────────────┘

private void startCoreServices() {

    // ④ DropBoxManagerService
    // 管理系统日志 (crash log, ANR log)
    ServiceManager.addService(Context.DROPBOX_SERVICE,
            new DropBoxManagerService(mSystemContext),
            /* allowIsolated= */ false,
            DUMP_FLAG_PRIORITY_CRITICAL);
    // 注册为 "dropbox"

    // ... 更多核心服务
}

// ┌─────────────────────────────────────────────────────────────────────┐
// │ 其他服务 (Other Services) — 包括 WMS 和 IMS                         │
// ┌─────────────────────────────────────────────────────────────────────┘

private void startOtherServices() {

    // ⑤ InputManagerService (输入事件管理)
    inputManager = new InputManagerService(context);
    inputManager.start();
    ServiceManager.addService(Context.INPUT_SERVICE,
            inputManager,
            /* allowIsolated= */ false,
            DUMP_FLAG_PRIORITY_CRITICAL);
    // 注册为 "input"

    // ⑥ WindowManagerService (窗口管理)
    wm = WindowManagerService.main(context, inputManager, ...);
    ServiceManager.addService(Context.WINDOW_SERVICE, wm,
            /* allowIsolated= */ false,
            DUMP_FLAG_PRIORITY_CRITICAL);
    // 注册为 "window"

    // ⑦ InputMethodManagerService (输入法管理)
    imm = new InputMethodManagerService(context, wm);
    ServiceManager.addService(Context.INPUT_METHOD_SERVICE, imm,
            /* allowIsolated= */ false,
            DUMP_FLAG_PRIORITY_CRITICAL);
    // 注册为 "input_method"

    // ⑧ 更多服务...
    // "alarm"         → AlarmManagerService
    // "notification"  → NotificationManagerService
    // "statusbar"     → StatusBarManagerService
    // "clipboard"     → ClipboardService
    // "connectivity"  → ConnectivityService
    // "wifi"          → WifiService
    // "bluetooth"     → BluetoothService
    // "media.session" → SessionManagerService
    // ... 总共约 60+ 个系统服务
}
```

---

## 五、服务查找流程

```
═══════════════════════════════════════════════════════════════════════════════════
  应用进程查找系统服务的完整流程
═══════════════════════════════════════════════════════════════════════════════════

  应用进程                                    Native ServiceManager
  ┌──────────────────┐                        ┌──────────────────────────────────┐
  │                  │                        │                                  │
  │  方式 1: 通过    │                        │                                  │
  │  getSystemService │                        │                                  │
  │                  │                        │                                  │
  │  Context.getSystemService("window")        │                                  │
  │       │          │                        │                                  │
  │       ▼          │                        │                                  │
  │  SystemServiceRegistry                      │                                  │
  │  .getService()   │                        │                                  │
  │       │          │                        │                                  │
  │       ▼          │                        │                                  │
  │  ServiceManager  │                        │                                  │
  │  .getService()   │                        │                                  │
  │       │          │                        │                                  │
  │       ├─ 查缓存 ─┤                        │                                  │
  │       │ (命中)   │                        │                                  │
  │       │ 直接返回  │                        │                                  │
  │       │          │                        │                                  │
  │       ├─ 缓存未命中                        │                                  │
  │       │          │                        │                                  │
  │       │  IPC     │                        │                                  │
  │       │ (Binder) │                        │                                  │
  │       ├──────────────────────────────────▶│                                  │
  │       │          │                        │  SVC_MGR_GET_SERVICE             │
  │       │          │                        │  name = "window"                 │
  │       │          │                        │       │                          │
  │       │          │                        │       ▼                          │
  │       │          │                        │  svcmgr_handler()                │
  │       │          │                        │       │                          │
  │       │          │                        │       ▼                          │
  │       │          │                        │  svc_to_handle("window")         │
  │       │          │                        │       │                          │
  │       │          │                        │       ▼                          │
  │       │          │                        │  遍历 svclist:                   │
  │       │          │                        │  ┌──────────────────────┐        │
  │       │          │                        │  │ "activity" → handle_1│        │
  │       │          │                        │  │ "power"    → handle_2│        │
  │       │          │                        │  │ "package"  → handle_3│        │
  │       │          │                        │  │ "input"    → handle_4│        │
  │       │          │                        │  │ "window"   → handle_5│ ← 找到│
  │       │          │                        │  │ "input_method"→ h_6  │        │
  │       │          │                        │  └──────────────────────┘        │
  │       │          │                        │       │                          │
  │       │          │                        │       ▼                          │
  │       │          │                        │  返回 handle_5                   │
  │       │◀──────────────────────────────────┤                                  │
  │       │          │                        │                                  │
  │       ▼          │                        │                                  │
  │  IWindowManager  │                        │                                  │
  │  .Proxy          │                        │                                  │
  │  (Binder 代理)   │                        │                                  │
  │       │          │                        │                                  │
  │       ▼          │                        │                                  │
  │  加入缓存         │                        │                                  │
  │  sCache.put(     │                        │                                  │
  │   "window",      │                        │                                  │
  │   wm_proxy)      │                        │                                  │
  │                  │                        │                                  │
  │  后续调用:        │                        │                                  │
  │  直接返回缓存!    │                        │                                  │
  │  (零 IPC 开销)   │                        │                                  │
  │                  │                        │                                  │
  └──────────────────┘                        └──────────────────────────────────┘
```

---

## 六、服务注册表数据结构

```
═══════════════════════════════════════════════════════════════════════════════════
  Native ServiceManager 内部的服务注册表 (svclist 链表)
═══════════════════════════════════════════════════════════════════════════════════

  svclist (单向链表):

  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
  │ svcinfo      │    │ svcinfo      │    │ svcinfo      │    │ svcinfo      │
  │              │    │              │    │              │    │              │
  │ next ────────┼──▶ │ next ────────┼──▶ │ next ────────┼──▶ │ next ────────┼──▶ NULL
  │ name="window"│    │ name="input" │    │ name="input_ │    │ name="alarm" │
  │ handle=h5    │    │ handle=h4    │    │ method"      │    │ handle=h7    │
  │ pid=1234     │    │ pid=1234     │    │ handle=h6    │    │ pid=1234     │
  │ uid=1000     │    │ uid=1000     │    │ pid=1234     │    │ uid=1000     │
  │ is_allowed=1 │    │ is_allowed=1 │    │ uid=1000     │    │ is_allowed=1 │
  └──────────────┘    └──────────────┘    │ is_allowed=1 │    └──────────────┘
                                          └──────────────┘

  特点:
  - 单向链表，按注册顺序排列 (后注册的在头部)
  - 查找时间 O(n)，n = 服务数量 (~60+)
  - 插入时间 O(1)，直接挂到头部
  - 删除时间 O(n)，需要遍历找到前驱节点

  为什么不用更高效的数据结构?
  - 服务数量少 (~60 个)，链表足够快
  - 查找操作不频繁 (Java 层有缓存)
  - 实现简单，C 语言链表操作
  - 稳定性优先 (SM 是系统核心，不能出错)
```

---

## 七、死亡通知处理

```c
// ═══════════════════════════════════════════════════════════════════════
// 文件: service_manager.c
// 方法: svcmgr_death_handler()
// 作用: 处理服务进程死亡事件
// 当注册了服务的进程退出/崩溃时，Binder 驱动通知 ServiceManager
// ═══════════════════════════════════════════════════════════════════════

void svcmgr_death_handler(struct binder_state *bs, void *handle, void *cookie)
{
    // ① 遍历 svclist，找到死亡的服务
    struct svcinfo **psi = &svclist;
    struct svcinfo *si;

    while (*psi) {
        si = *psi;

        if (si->handle == handle) {
            // ★ 找到死亡的服务!

            // ② 从链表中移除
            *psi = si->next;
            // 将前驱节点的 next 指向死亡节点的后继
            // 相当于: 前驱 → 后继 (跳过死亡节点)

            // ③ 释放内存
            free(si);

            // ④ 释放 Binder 引用
            binder_release(bs, handle);
            // 告诉 Binder 驱动: 不再需要此 handle 的引用
            // 驱动会清理相关的 binder_ref

            // ⑤ 打印日志
            ALOGI("service '%s' died", si->name);

            return;
        }

        psi = &si->next;
    }

    // 未找到 → 可能是未知服务
    ALOGW("death notification for unknown service");
}
```

```
═══════════════════════════════════════════════════════════════════════════════════
  服务死亡处理流程
═══════════════════════════════════════════════════════════════════════════════════

  场景: system_server 进程崩溃

  ┌─────────────────────────────────────────────────────────────────────────────┐
  │                                                                             │
  │  system_server 崩溃                                                         │
  │       │                                                                     │
  │       ▼                                                                     │
  │  Binder 驱动检测到进程退出                                                   │
  │       │                                                                     │
  │       ▼                                                                     │
  │  遍历该进程注册的所有 binder_node                                            │
  │       │                                                                     │
  │       ▼                                                                     │
  │  向 ServiceManager 发送 BR_DEAD_BINDER                                      │
  │  (因为 SM 对每个注册的服务都调用了 binder_link_to_death)                     │
  │       │                                                                     │
  │       ▼                                                                     │
  │  ServiceManager 的 binder_loop 被唤醒                                       │
  │       │                                                                     │
  │       ▼                                                                     │
  │  处理 BR_DEAD_BINDER 命令                                                   │
  │       │                                                                     │
  │       ▼                                                                     │
  │  调用 svcmgr_death_handler()                                                │
  │       │                                                                     │
  │       ▼                                                                     │
  │  从 svclist 中移除所有属于该进程的服务                                        │
  │  ┌──────────────────────────────────────────────────────────────────┐       │
  │  │  移除前:                                                         │       │
  │  │  "activity" → system_server  → 移除                              │       │
  │  │  "window"   → system_server  → 移除                              │       │
  │  │  "input"    → system_server  → 移除                              │       │
  │  │  "input_method" → system_server → 移除                           │       │
  │  │  ... 约 60+ 个服务全部移除                                        │       │
  │  │                                                                  │       │
  │  │  移除后:                                                         │       │
  │  │  svclist = NULL (空链表)                                         │       │
  │  └──────────────────────────────────────────────────────────────────┘       │
  │       │                                                                     │
  │       ▼                                                                     │
  │  init 进程检测到 servicemanager 仍然存活                                     │
  │  但 init 同时检测到 system_server 死亡                                       │
  │       │                                                                     │
  │       ▼                                                                     │
  │  init 重启 system_server                                                    │
  │       │                                                                     │
  │       ▼                                                                     │
  │  新的 system_server 重新启动                                                 │
  │  → 重新创建所有系统服务                                                      │
  │  → 重新调用 addService 注册到 ServiceManager                                │
  │  → 系统恢复正常                                                              │
  │                                                                             │
  └─────────────────────────────────────────────────────────────────────────────┘
```

---

## 八、总结

| 组件 | 语言 | 入口方法 | 核心职责 |
|------|------|---------|---------|
| **Native SM** | C | `main()` → `binder_loop()` | 维护全局服务注册表，处理 add/get/list 请求 |
| **Java SM** | Java | `ServiceManager.getService()` | 为 Java 层封装 Native SM 的 Binder 代理 |
| **SystemServer** | Java | `startBootstrapServices()` 等 | 创建系统服务并调用 `addService()` 注册 |

**关键设计决策：**

| 决策 | 原因 |
|------|------|
| SM 用 C 实现而非 Java | 必须最先启动，不能依赖 JVM |
| SM 缓冲区只分配 128KB | 只处理简单的注册/查找请求，数据量极小 |
| handle=0 固定指向 SM | 所有进程无需知道 SM 地址，统一通过 handle=0 访问 |
| Java 层使用缓存 | 减少 Binder IPC 开销，提高查找速度 |
| svclist 用链表而非哈希表 | 服务数量少，实现简单稳定优先 |
| SM 标记为 critical | 崩溃后 init 会重启 SM 及关联进程 |
