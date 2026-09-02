# Binder 初始化时机详解

---

## 一、Binder 初始化总览

```
═══════════════════════════════════════════════════════════════════════════════════
  Binder 初始化时间轴
═══════════════════════════════════════════════════════════════════════════════════

  阶段 0: 内核启动
  ┌─────────────────────────────────────────────────────────────────────┐
  │ Linux Kernel 启动                                                    │
  │       │                                                             │
  │       ▼                                                             │
  │ 加载 binder.ko 内核模块                                              │
  │ → 注册 binder_driver                                                │
  │ → 创建 /proc/binder/* 调试接口                                       │
  │ → 创建 /dev/binder、/dev/hwbinder、/dev/vndbinder 设备节点            │
  │ → 初始化全局 binder_procs 链表、binder_context_mgr_node              │
  └─────────────────────────────────────────────────────────────────────┘
       │
       ▼
  阶段 1: init 进程启动
  ┌─────────────────────────────────────────────────────────────────────┐
  │ init 进程 (PID 1)                                                    │
  │       │                                                             │
  │       ▼                                                             │
  │ 解析 init.rc 启动 Native ServiceManager                              │
  │ → open("/dev/binder")                                               │
  │ → mmap(128KB)                                                       │
  │ → ioctl(BINDER_SET_CONTEXT_MGR)  ← 成为 handle=0 的上下文管理器      │
  │ → 进入 binder_loop() 监听请求                                       │
  └─────────────────────────────────────────────────────────────────────┘
       │
       ▼
  阶段 2: Zygote 启动
  ┌─────────────────────────────────────────────────────────────────────┐
  │ Zygote 进程 (init.rc 中启动)                                         │
  │       │                                                             │
  │       ▼                                                             │
  │ 加载 ZygoteInit.java                                                │
  │ → open("/dev/binder")                                               │
  │ → mmap(1MB)                                                         │
  │ → forkServer 循环                                                    │
  │ → 准备 fork 出应用进程                                               │
  └─────────────────────────────────────────────────────────────────────┘
       │
       ▼
  阶段 3: SystemServer 启动
  ┌─────────────────────────────────────────────────────────────────────┐
  │ Zygote fork → system_server 进程                                    │
  │       │                                                             │
  │       ▼                                                             │
  │ SystemServer.main()                                                  │
  │ → open("/dev/binder") (子进程继承/新建)                              │
  │ → 创建各种服务 (AMS, WMS, IMS...)                                    │
  │ → 各个服务通过 ServiceManager.addService() 注册到 Native SM          │
  │ → 通过 getService() 获取其他服务引用                                 │
  └─────────────────────────────────────────────────────────────────────┘
       │
       ▼
  阶段 4: 应用进程启动
  ┌─────────────────────────────────────────────────────────────────────┐
  │ Zygote fork → 应用进程 (如 Launcher, Settings, 微信等)              │
  │       │                                                             │
  │       ▼                                                             │
  │ ProcessState 单例创建                                                │
  │ → open("/dev/binder")                                               │
  │ → mmap(1MB)                                                         │
  │ → 创建 IPCThreadState (用于 Binder 线程)                            │
  │ → 通过 ServiceManager.getService() 获取系统服务                      │
  │ → 发送/接收 Binder 事务                                              │
  └─────────────────────────────────────────────────────────────────────┘
```

---

## 二、内核态 Binder 初始化

### 1. binder.ko 模块加载

```c
// ═══════════════════════════════════════════════════════════════════════
// 文件: drivers/android/binder.c
// 方法: initcall 机制
// 作用: 内核模块初始化入口
// 时机: Linux kernel 启动时的 initcall 阶段
// ═══════════════════════════════════════════════════════════════════════

// Linux 内核启动时，按优先级依次执行各模块的初始化函数
// binder_init 处于 device_initcall 阶段

static int __init binder_init(void)
{
    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 1: 初始化全局状态                                          │
    // └─────────────────────────────────────────────────────────────────┘

    // ① 初始化全局 binder_procs 链表
    // 用于挂载所有使用 Binder 的进程
    // (每个进程对应一个 binder_proc 结构)
    INIT_LIST_HEAD(&binder_procs);

    // ② 初始化 Binder 驱动属性
    // 这些属性可以通过 /sys/kernel/debug/binder/* 访问
    binder_debugfs_dir_entry_root = debugfs_create_dir("binder", NULL);
    // 创建调试目录:
    //   /sys/kernel/debug/binder/
    //     ├── state       ← 驱动状态
    //     ├── stats       ← 全局统计
    //     ├── transactions ← 事务记录
    //     ├── transaction_log ← 事务日志
    //     ├── failed_transaction_log ← 失败事务日志
    //     └── proc/<pid>  ← 每个进程的统计

    // ③ 创建设备类
    // /sys/class/binder/
    binder_class = class_create(THIS_MODULE, "binder");
    // 用于创建设备节点

    // ④ 创建设备节点
    // 创建 /dev/binder 设备
    // 后续用户态通过 open("/dev/binder") 访问
    binder_device = device_create(binder_class, NULL,
                                   MKDEV(major, 0),
                                   NULL, "binder");
    // 设备文件: /dev/binder
    // 设备类: /sys/class/binder/binder

    // ⑤ 注册字符设备
    // 注册 file_operations (open, mmap, ioctl 等回调)
    ret = register_chrdev(0, "binder", &binder_fops);
    // 获取主设备号
    // 注册操作函数集

    // ⑥ 注册 misc device (备选方式)
    // 现代 Android 内核使用 misc device 而非 chrdev
    ret = misc_register(&binder_miscdev);
    // misc device 主设备号固定为 10
    // 设备文件: /dev/binder
    // 提供更简化的注册流程

    // ⑦ 初始化上下文管理器
    // ★ 这是 ServiceManager 注册时的目标对象
    // 内核预先创建一个全局的 binder_node
    // 当进程调用 BINDER_SET_CONTEXT_MGR ioctl 时
    // 会查找/创建这个 node
    // 实际上，此 node 在 BINDER_SET_CONTEXT_MGR 时才创建
    // 但内核会预先初始化相关数据结构

    // ⑧ 初始化工作队列
    binder_deferred_workqueue = create_singlethread_workqueue(
            "binder");
    // 用于处理延迟释放的对象
    // 当 binder_proc 退出时，相关清理工作可能延迟到此工作队列执行
    // 避免在持有锁时执行耗时的释放操作

    return 0;
}

// ⑨ 注册为 initcall
device_initcall(binder_init);
//  device_initcall 的优先级在 subsys_initcall 之后
//  在 late_initcall 之前
//  意味着 binder 在大多数设备驱动之后初始化
//  但早于文件系统挂载
```

### 2. 内核初始化的关键时间点

```
═══════════════════════════════════════════════════════════════════════════════════
  内核启动时序 (简化)
═══════════════════════════════════════════════════════════════════════════════════

  T0: 启动 start_kernel()
      → 设置中断、内存管理、调度器
      → 初始化控制台

  T1: do_initcalls() 阶段
      → 依次调用所有 __init 修饰的初始化函数

  T2: pure_initcall (优先级 0)
      → 极早期初始化
      → (binder 不在此阶段)

  T3: core_initcall (优先级 1)
      → 核心子系统初始化
      → (binder 不在此阶段)

  T4: core_initcall_sync (优先级 1s)
      → 同步核心初始化
      → (binder 不在此阶段)

  T5: postcore_initcall (优先级 2)
      → postcore 初始化
      → (binder 不在此阶段)

  T6: postcore_sync_initcall (优先级 2s)
      → (binder 不在此阶段)

  T7: arch_initcall (优先级 3)
      → 架构特定初始化
      → (binder 不在此阶段)

  T8: arch_initcall_sync (优先级 3s)
      → (binder 不在此阶段)

  T9: subsys_initcall (优先级 4)
      → 子系统初始化
      → (binder 不在此阶段，但部分驱动在此)

  T10: subsys_initcall_sync (优先级 4s)
       → (binder 不在此阶段)

  T11: fs_initcall (优先级 5)
       → 文件系统初始化
       → (binder 不在此阶段)

  T12: fs_initcall_sync (优先级 5s)
       → (binder 不在此阶段)

  T13: rootfs_initcall (优先级 6)
       → 根文件系统初始化
       → (binder 不在此阶段)

  T14: device_initcall (优先级 7)
       → ★★★ 设备驱动初始化 ★★★
       → binder_init() 在此阶段执行!
       → /dev/binder 设备节点在此阶段被创建

  T15: device_initcall_sync (优先级 7s)
       → (binder 不在此阶段)

  T16: late_initcall (优先级 8)
       → 后期初始化
       → (binder 不在此阶段)

  T17: late_initcall_sync (优先级 8s)
       → (binder 不在此阶段)

  T18: 启动 init 进程
       → 解析 init.rc
       → 启动 Native ServiceManager
       → open("/dev/binder") → 成功!
```

---

## 三、Native ServiceManager 初始化时机

### 1. init.rc 中的启动配置

```ini
# ═══════════════════════════════════════════════════════════════════════
# 文件: system/core/rootdir/init.rc
# 配置: Native ServiceManager 的启动时机
# ═══════════════════════════════════════════════════════════════════════

# 启动顺序由 init.rc 的 class 决定
# on early-init  → 最早执行
# on init        → 紧随 early-init
# on late-init   → 较晚执行
# on boot        → 系统启动完成

on early-init
    # 最早执行的初始化
    # 例如: 设置 cgroups、挂载 tmpfs 等

on init
    # 基础初始化
    # 启动核心守护进程

    # ★ 启动 Native ServiceManager
    # class core 表示此服务属于 "核心类"
    # core 类的服务在非 core 类之前启动
    start servicemanager

on late-init
    # 启动其他类服务
    trigger post-fs
    trigger zygote-start  # 触发 Zygote 启动

on post-fs-data
    # 数据分区挂载后的操作

# ┌─────────────────────────────────────────────────────────────────┐
# │ ServiceManager 的服务定义                                        │
# └─────────────────────────────────────────────────────────────────┘

service servicemanager /system/bin/servicemanager
    class core                          # 核心类，最先启动
    user system                         # 以 system 用户身份运行
    group system readproc               # 所属用户组
    critical                            # 关键服务，崩溃后系统重启
    onrestart restart healthd           # 重启时连带重启其他服务
    onrestart restart zygote            # Zygote 必须重启
    onrestart restart audioserver
    onrestart restart surfaceflinger
    onrestart restart inputflinger
    onrestart restart media
    onrestart restart cameraserver
    onrestart restart keystore
    onrestart restart gatekeeperd
    # critical: 如果 servicemanager 崩溃
    #   → init 会触发系统重启
    #   → 重启时 onrestart 列表中的服务也会被重启
    #   → 确保系统状态一致
```

### 2. Native ServiceManager 启动详细时序

```
═══════════════════════════════════════════════════════════════════════════════════
  Native ServiceManager 启动时序
═══════════════════════════════════════════════════════════════════════════════════

  Linux Kernel
       │
       │ 加载 binder.ko
       │ → /dev/binder 设备节点创建
       │
       ▼
  init 进程 (PID 1)
       │
       │ 解析 init.rc
       │
       │ 触发 on early-init
       │ → 设置 cgroups
       │ → 挂载 tmpfs, devpts
       │
       │ 触发 on init
       │ → start servicemanager
       │
       ▼
  Native ServiceManager 进程 (PID ~2)
       │
       │ ┌────────────────────────────────────────────────────────┐
       │ │ main() 入口                                              │
       │ │                                                         │
       │ │ ① binder_open(128*1024)                                 │
       │ │    → open("/dev/binder", O_RDWR)                        │
       │ │    → 创建设备文件描述符 (fd)                              │
       │ │    → 触发内核: open → binder_open() → binder_proc_create│
       │ │    → 创建 binder_proc 加入 binder_procs 链表              │
       │ │    → mmap(128KB)                                         │
       │ │    → 触发内核: mmap → binder_mmap()                     │
       │ │    → 分配物理页，建立虚拟地址映射                         │
       │ │    → 初始化空闲红黑树                                    │
       │ │                                                         │
       │ │ ② binder_become_context_manager(bs)                    │
       │ │    → ioctl(fd, BINDER_SET_CONTEXT_MGR, 0)              │
       │ │    → 触发内核: ioctl → binder_ioctl()                   │
       │ │    → 创建 binder_context_mgr_node                       │
       │ │    → 此 node 是全局唯一的 handle=0 节点                  │
       │ │    → 后续所有进程的 handle=0 都指向此 node               │
       │ │                                                         │
       │ │ ③ selinux_setup()                                       │
       │ │    → 初始化 SELinux 上下文                                │
       │ │    → 加载策略文件                                        │
       │ │                                                         │
       │ │ ④ binder_loop(bs, svcmgr_handler)                      │
       │ │    → 发送 BC_ENTER_LOOPER                                │
       │ │    → 进入无限循环                                        │
       │ │    → 阻塞等待 Binder 事务                                │
       │ │                                                         │
       │ └────────────────────────────────────────────────────────┘
       │
       │ 此时 Native ServiceManager 已就绪
       │ 可以处理 addService / getService 请求
       │
       ▼
  init 继续执行 on init
       │
       │ start other core services
       │ → healthd (电池健康守护)
       │ → lmkd (低内存守护)
       │ → logd (日志守护)
       │
       │ 触发 late-init
       │
       ▼
```

---

## 四、Zygote 初始化

### 1. Zygote 启动时序

```
═══════════════════════════════════════════════════════════════════════════════════
  Zygote 启动和 Binder 初始化
═══════════════════════════════════════════════════════════════════════════════════

  Native ServiceManager 已就绪
       │
       ▼
  init 触发 on late-init
       │
       │ start zygote
       │
       ▼
  Zygote 进程 (/system/bin/app_process64)
       │
       │ 加载 ZygoteInit.java
       │
       ▼
  ZygoteInit.main()
       │
       │ ┌────────────────────────────────────────────────────────┐
       │ │ ① registerZygoteSocket()                                │
       │ │    → 创建本地 socket (用于接收 fork 命令)                 │
       │ │    → 文件描述符通过环境变量传给 Zygote 进程                │
       │ │                                                         │
       │ │ ② Zygote 本身不直接 open /dev/binder                    │
       │ │    → Zygote 进程不需要主动使用 Binder                     │
       │ │    → 它只负责 fork 其他进程                              │
       │ │    → 子进程会在自己的初始化中创建 Binder                   │
       │ │                                                         │
       │ │ ③ preload() (预加载类/资源)                              │
       │ │    → 加载常用类到内存，加快 fork 后子进程启动               │
       │ │    → 加载资源、字体、OpenGL 等                            │
       │ │    → 预加载完成后等待 fork 命令                          │
       │ │                                                         │
       │ │ ④ runSelectLoop()                                       │
       │ │    → 在 socket 上等待 fork 命令                          │
       │ │    → 收到命令后 fork 出子进程                            │
       │ │    → 子进程的 Binder 由子进程自己初始化                    │
       │ └────────────────────────────────────────────────────────┘
       │
       │ 等待 fork 命令
       │
       ▼
  Zygote 收到启动 system_server 的命令
       │
       │ fork() → 创建子进程
       │
       ▼
```

### 2. Zygote 中的 pre-fork 与继承

```
═══════════════════════════════════════════════════════════════════════════════════
  Zygote fork 时 Binder 资源如何处理
═══════════════════════════════════════════════════════════════════════════════════

  ┌─────────────────────────────────────────────────────────────────────────────┐
  │  Zygote 进程的地址空间                                                       │
  │                                                                             │
  │  ┌─────────────────────────────────────────────────────────────────────┐   │
  │  │  共享内存 (mmap 区域, /dev/binder 的映射)                            │   │
  │  │  假设已经 mmap 过 (例如通过 JNI 调用触发)                             │   │
  │  └─────────────────────────────────────────────────────────────────────┘   │
  │                                                                             │
  │  注意: 实际上 Zygote 进程通常不会主动 open /dev/binder                     │
  │  因为它只是一个 "进程孵化器"                                                  │
  │  Binder 初始化发生在每个 fork 出的子进程中                                    │
  │                                                                             │
  │  ★ 但是: 当 Zygote 的 Java 运行时初始化时 (例如 RuntimeInit)                  │
  │     如果有任何代码触发了 open /dev/binder                                    │
  │     那么 Zygote 进程也会有 binder_proc                                       │
  │     此时 fork 出的子进程会继承 fd                                            │
  │     但子进程通常会自己 open 一次                                              │
  └─────────────────────────────────────────────────────────────────────────────┘
       │
       │ fork()
       │
       ▼
  子进程 (system_server / 应用进程)
       │
       │ fork 时:
       │ ① 地址空间被复制 (Copy-on-Write)
       │    → 如果父进程有 mmap 映射，子进程会复制页表
       │    → 但物理页是共享的，直到子进程写入时才真正复制
       │
       │ ② 打开的文件描述符被继承
       │    → 如果父进程打开了 /dev/binder
       │    → 子进程会继承这个 fd
       │    → 但是内核会为子进程创建新的 binder_proc
       │    → fd 指向新的 binder_proc
       │
       │ ③ 内核的 fork 处理
       │    → 遍历父进程的所有 binder_thread
       │    → 为每个 binder_thread 在子进程中创建对应结构
       │    → 重置 todo 队列
       │    → 子进程的 binder_proc 重新添加到 binder_procs 链表
```

---

## 五、SystemServer 初始化时序

### 1. SystemServer 创建过程

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: SystemServer.java
// 时机: Zygote fork 后立即执行
// 作用: 创建并注册所有系统服务
// ═══════════════════════════════════════════════════════════════════════

public static void main(String[] args) {
    new SystemServer().run();
}

private void run() {

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 1: 初始化 Looper 和 SystemContext                          │
    // └─────────────────────────────────────────────────────────────────┘

    Looper.prepareMainLooper();
    // 创建 system_server 的主线程 Looper

    // 创建 system_server 进程的系统上下文
    ActivityThread thread = new ActivityThread();
    thread.attach(true, 0);
    // ActivityThread.attach() 内部:
    //   → getSystemContext() 初始化系统 Context
    //   → 此过程可能触发 open("/dev/binder")
    //   → system_server 进程的 binder_proc 被创建

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 2: 启动引导服务 (Bootstrap Services)                       │
    // └─────────────────────────────────────────────────────────────────┘

    startBootstrapServices();
    // ① Installer
    // ② ActivityManagerService (AMS) ← 第一个重要服务
    //    - ActivityManagerService.Lifecycle.startService()
    //    - 创建 AMS 实例
    //    - ServiceManager.addService("activity", ams)
    //    - ★ 此时 AMS 已经向 Native ServiceManager 注册
    //    - 之后其他服务可以通过 getService("activity") 获取 AMS
    // ③ PowerManagerService
    //    - ServiceManager.addService("power", pms)
    // ④ PackageManagerService (PKMS)
    //    - 需要最长时间启动 (~2~5秒)
    //    - 扫描所有 APK
    //    - ServiceManager.addService("package", pm)
    // ⑤ DisplayManagerService
    // ⑥ SensorService
    // ...

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 3: 启动核心服务 (Core Services)                           │
    // └─────────────────────────────────────────────────────────────────┘

    startCoreServices();
    // ① DropBoxManagerService
    // ② BatteryService
    // ③ UsageStatsService
    // ...

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 4: 启动其他服务 (Other Services) - 包括 WMS 和 IMS         │
    // └─────────────────────────────────────────────────────────────────┘

    startOtherServices();
    // ★ 此处创建 WMS 和 IMS

    // ① InputManagerService
    //    inputManager = new InputManagerService(context);
    //    inputManager.start();
    //    ServiceManager.addService(Context.INPUT_SERVICE, inputManager);
    //    ★ 启动时机: startOtherServices 开始时

    // ② WindowManagerService
    //    wm = WindowManagerService.main(context, inputManager, ...);
    //    ServiceManager.addService(Context.WINDOW_SERVICE, wm);
    //    ★ 启动时机: startOtherServices 中

    // ③ InputMethodManagerService
    //    imm = new InputMethodManagerService(context, wm);
    //    ServiceManager.addService(Context.INPUT_METHOD_SERVICE, imm);
    //    ★ 启动时机: WMS 之后立即

    // ④ 其他服务...

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 5: 进入主循环                                              │
    // └─────────────────────────────────────────────────────────────────┘

    Looper.loop();
    // 进入主线程循环
    // 处理 Binder 事务、消息队列
    // 永不停歇，直到进程退出
}
```

### 2. 各服务之间的依赖关系（初始化顺序）

```
═══════════════════════════════════════════════════════════════════════════════════
  SystemServer 中各服务初始化的依赖顺序
═══════════════════════════════════════════════════════════════════════════════════

  阶段 1: startBootstrapServices (引导服务)
  ┌─────────────────────────────────────────────────────────────────┐
  │  ① Installer                                                  │
  │     ↓ 依赖                                                     │
  │  ② ActivityManagerService (AMS)                                │
  │     ↓ 依赖                                                     │
  │  ③ PowerManagerService (PMS)                                   │
  │     ↓ 依赖                                                     │
  │  ④ PackageManagerService (PKMS)                                │
  │     ↓ 依赖                                                     │
  │  ⑤ DisplayManagerService                                      │
  │     ↓ 依赖                                                     │
  │  ⑥ LightsService                                              │
  │     ↓ 依赖                                                     │
  │  ⑦ SensorService                                              │
  └─────────────────────────────────────────────────────────────────┘

  阶段 2: startCoreServices (核心服务)
  ┌─────────────────────────────────────────────────────────────────┐
  │  ① DropBoxManagerService                                      │
  │  ② BatteryService                                             │
  │  ③ VibratorService                                            │
  │  ④ UsbService                                                 │
  │  ⑤ ...                                                        │
  └─────────────────────────────────────────────────────────────────┘

  阶段 3: startOtherServices (其他服务)
  ┌─────────────────────────────────────────────────────────────────┐
  │  ① InputManagerService  ★ Binder 事务的源头                     │
  │     ↓ 依赖                                                     │
  │  ② WindowManagerService (WMS)  ★ 通过 Native SM 获取服务         │
  │     ↓ 依赖                                                     │
  │  ③ InputMethodManagerService (IMS)                             │
  │     ↓ 依赖                                                     │
  │  ④ ActivityManagerService.systemReady() 通知 AMS 系统就绪        │
  │     ↓ 依赖                                                     │
  │  ⑤ Launcher 启动 (系统桌面)                                     │
  └─────────────────────────────────────────────────────────────────┘
```

---

## 六、应用进程初始化时机

### 1. 应用进程的 Binder 初始化

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: frameworks/base/core/java/android/os/ProcessState.java
// 作用: 每个进程一个 ProcessState 单例，负责管理该进程的 Binder 资源
// 初始化时机: 第一次使用 Binder 时 (懒加载)
// ═══════════════════════════════════════════════════════════════════════

public class ProcessState {

    // 单例 (每个进程一个)
    private static ProcessState sInstance;

    // 关键资源
    int mDriverFD;             // /dev/binder 的文件描述符
    final long mMaxThreads;    // 最大 Binder 线程数
    final boolean mIsDriver;
    // ... 其他字段

    // ★ 静态工厂方法 (懒加载)
    public static ProcessState getOrCreateSelf() {
        synchronized (ProcessState.class) {
            if (sInstance == null) {
                sInstance = new ProcessState();
            }
            return sInstance;
        }
    }

    // 构造函数
    private ProcessState() {
        // ┌─────────────────────────────────────────────────────────────┐
        // │ ★ 第一次调用 ProcessState.getOrCreateSelf() 时执行           │
        // │   这是进程初始化 Binder 的关键节点                            │
        // └─────────────────────────────────────────────────────────────┘

        mDriverFD = open_driver();  // 打开 /dev/binder
        mMaxThreads = getMaxThreads();
    }

    private int open_driver() {
        // ① 打开 /dev/binder
        int fd = open("/dev/binder", O_RDWR | O_CLOEXEC);
        // 触发内核:
        //   binder_open() → binder_proc_create()
        //   → 创建 binder_proc 加入全局 binder_procs 链表
        //   → 分配 PID

        if (fd < 0) {
            return fd;
        }

        // ② 设置最大线程数
        // 告诉驱动本进程最多能有多少个 Binder 线程
        int result = ioctl(fd, BINDER_SET_MAX_THREADS, mMaxThreads);
        // mMaxThreads 默认值为 15
        // 可在系统属性中调整: persist.sys.max_binder_threads

        // ③ mmap 映射 (申请共享缓冲区)
        // 这是关键的内存映射操作
        int version = ioctl(fd, BINDER_VERSION, &vers);
        // 获取驱动版本，确保兼容性

        // 实际 mmap 在 ProcessState.startThreadPool() 之前的某个时刻
        // 参见下方

        return fd;
    }
}
```

### 2. 应用进程 Binder 初始化的关键时刻

```
═══════════════════════════════════════════════════════════════════════════════════
  应用进程从 fork 到可以发送 Binder 事务的完整时序
═══════════════════════════════════════════════════════════════════════════════════

  Zygote 收到启动应用的命令 (例如: ActivityManager 启动 Launcher)
       │
       │ fork() → 创建子进程
       │
       ▼
  子进程 (应用进程)
       │
       │ ┌────────────────────────────────────────────────────────┐
       │ │ ① fork 后立即执行 RuntimeInit.commonInit()              │
       │ │    → 设置默认 uncaught exception handler                │
       │ │    → 准备时区等                                          │
       │ │    → 此时尚未使用 Binder                                  │
       │ │                                                         │
       │ │ ② ActivityThread.main()                                │
       │ │    → 创建主线程 Looper                                   │
       │ │    → 调用 Looper.loop() 进入主循环                      │
       │ │    → 此时尚未使用 Binder                                  │
       │ │                                                         │
       │ │ ③ 第一次访问系统服务时                                   │
       │ │    → 例如: Context.getSystemService()                   │
       │ │    → 内部: ServiceManager.getService("window")          │
       │ │    → 内部: getIServiceManager()                          │
       │ │    → 内部: ProcessState.getOrCreateSelf()                │
       │ │    → 触发 open("/dev/binder")  ← ★ Binder 初始化点       │
       │ │    → 触发 mmap(1MB)                                       │
       │ │    → 之后可以发送 Binder 事务                             │
       │ │                                                         │
       │ │ ④ 应用继续启动                                          │
       │ │    → attachApplication()                                │
       │ │    → 创建 ViewRootImpl 等                                │
       │ │    → 与 AMS 通信 (Binder IPC)                            │
       │ │                                                         │
       │ │ ⑤ 启动 Binder 线程池 (可选)                              │
       │ │    → ProcessState.startThreadPool()                     │
       │ │    → 创建线程处理来自服务端的请求                          │
       │ └────────────────────────────────────────────────────────┘
       │
       ▼
  应用进程就绪
  可以正常使用 Binder IPC

  ★ 关键点:
  - open("/dev/binder") 是延迟初始化的
  - 第一次使用 Binder 时 (例如查服务) 才打开
  - 这避免了 fork 时的开销
```

### 3. Zygote 的优化策略

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: frameworks/base/core/java/android/os/ZygoteInit.java
// 方法: preload()
// 作用: 预加载常用类，加快 fork 后子进程启动
// 注意: Zygote 自身不使用 Binder，只是为子进程预加载 Java 类
// ═══════════════════════════════════════════════════════════════════════

static void preload() {
    Log.i(TAG, "begin preload");

    // 预加载常用类到内存
    // 这样 fork 后子进程可以直接使用这些类，无需再次加载
    preloadClasses();
    // 预加载的类:
    //   - Binder, IBinder, IInterface (Binder 核心类!)
    //   - ServiceManager
    //   - ProcessState
    //   - Parcel
    //   - Activity, Service 等
    //   - 大部分 framework 类

    // 预加载资源
    preloadResources();
    preloadOpenGL();
    preloadSharedLibraries();
    preloadTextResources();

    Log.i(TAG, "end preload");
    // 预加载完成后，等待 fork 命令
}
```

---

## 七、完整的启动时序图

```
═══════════════════════════════════════════════════════════════════════════════════
  Android 启动完整时序
═══════════════════════════════════════════════════════════════════════════════════

  T0: Linux Kernel 启动
  │
  │  加载 binder.ko
  │  → /dev/binder 设备节点创建 (device_initcall 阶段)
  │
  ▼
  T1: init 进程 (PID 1)
  │
  │  解析 init.rc
  │  启动核心类服务
  │
  ▼
  T2: Native ServiceManager (PID ~2)
  │     │
  │     │  open("/dev/binder")
  │     │  mmap(128KB)
  │     │  BINDER_SET_CONTEXT_MGR ← 成为 SM
  │     │  binder_loop() ← 等待请求
  │     │
  │     ▼
  │  Native SM 就绪
  │
  ▼
  T3: 其他核心守护进程
  │     - healthd
  │     - lmkd
  │     - logd
  │     - vold
  │
  ▼
  T4: Zygote 进程 (fork 自 init)
  │     │
  │     │  加载 ZygoteInit
  │     │  preload() 预加载类
  │     │  等待 fork 命令
  │     │
  │     ▼
  │  Zygote 就绪
  │
  ▼
  T5: SystemServer (fork 自 Zygote)
  │     │
  │     │  startBootstrapServices()
  │     │    → AMS
  │     │    → PMS
  │     │    → ServiceManager.addService(...)
  │     │
  │     │  startCoreServices()
  │     │
  │     │  startOtherServices()
  │     │    → InputManagerService
  │     │    → WindowManagerService
  │     │    → InputMethodManagerService
  │     │
  │     ▼
  │  system_server 就绪
  │
  ▼
  T6: Launcher 启动 (启动系统桌面)
  │     │
  │     │  Zygote fork → Launcher 进程
  │     │  ActivityThread.main()
  │     │  ProcessState.getOrCreateSelf() ← 首次打开 Binder
  │     │  加载 Launcher UI
  │     │
  │     ▼
  │  Launcher 显示
  │
  ▼
  T7: 用户打开应用
        │
        │  Zygote fork → 应用进程
        │  ProcessState.getOrCreateSelf() ← 首次打开 Binder
        │  ActivityThread.main()
        │  attachApplication()
        │  加载 UI
        │
        ▼
  应用进程就绪
  可以正常使用 Binder IPC
```

---

## 八、关键时间点总结

| 阶段 | 时间点 | Binder 状态 | 关键动作 |
|------|--------|------------|---------|
| **内核启动** | device_initcall 阶段 | 内核模块初始化 | 创建 /dev/binder 设备节点 |
| **init** | on init 触发 | 设备可用 | start servicemanager |
| **Native SM 启动** | init 之后 ~100ms | open + mmap + ioctl | 成为上下文管理器，进入事件循环 |
| **Zygote fork** | Native SM 之后 ~1-2s | 不主动 open | 等待 fork 命令 |
| **SystemServer 启动** | Zygote fork 后 ~5-10s | open + mmap | 创建所有服务并注册到 SM |
| **WMS 启动** | startOtherServices | open + mmap | WMS.main() |
| **IMS 启动** | WMS 之后 | open + mmap | new InputMethodManagerService() |
| **应用进程 fork** | 首次启动应用时 | 懒加载 open | 首次访问系统服务时触发 |
| **应用首次 IPC** | 应用初始化后 | fd 已就绪 | 发送 Binder 事务 |

**关键设计决策：**

1. **内核模块 device_initcall 优先级** — 确保所有设备驱动初始化完成后 Binder 才初始化
2. **Native SM 在 init 阶段** — 必须最先启动，否则后续进程无法注册/查找服务
3. **SystemServer 中的服务按依赖顺序初始化** — AMS → WMS → IMS 这种顺序保证依赖关系正确
4. **应用进程懒加载 Binder** — ProcessState 懒加载，避免 fork 时不必要的开销
5. **WMS 必须先于 IMS 初始化** — 因为 IMS 构造函数需要 WMS 参数
6. **Zygote 预加载 Binder 类** — 虽然 Zygote 不主动初始化 Binder，但预加载 Java Binder 类加快 fork 后子进程启动
