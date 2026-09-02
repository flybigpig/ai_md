# Binder 初始化总结

---

## 一、核心一句话

**Binder 初始化按"内核 → 守护进程 → 系统服务 → 应用进程"四级递进，每一级都是下一级的前置条件。**

---

## 二、初始化全景图

```
═══════════════════════════════════════════════════════════════════════════════════
                          Binder 初始化四级递进模型
═══════════════════════════════════════════════════════════════════════════════════

  ┌─────────────────────────────────────────────────────────────────────────────┐
  │  Level 1: 内核层 (Kernel)                                                   │
  │  ─────────────────────────────────────────────────────────────────────────  │
  │  时机: Linux kernel device_initcall 阶段                                    │
  │  动作: 加载 binder.ko → 注册字符设备 → 创建 /dev/binder                      │
  │  结果: 内核提供 Binder IPC 基础能力                                          │
  │  验证: ls -l /dev/binder                                                    │
  └─────────────────────────────────────────────────────────────────────────────┘
                                     │
                                     │ 前置条件
                                     ▼
  ┌─────────────────────────────────────────────────────────────────────────────┐
  │  Level 2: 守护进程层 (Native ServiceManager)                                │
  │  ─────────────────────────────────────────────────────────────────────────  │
  │  时机: init 进程解析 init.rc 的 "on init"                                   │
  │  动作: open + mmap(128KB) + BINDER_SET_CONTEXT_MGR + binder_loop            │
  │  结果: 全局服务注册表就绪，所有 handle=0 路由到此进程                          │
  │  PID: ~2                                                                    │
  └─────────────────────────────────────────────────────────────────────────────┘
                                     │
                                     │ 前置条件
                                     ▼
  ┌─────────────────────────────────────────────────────────────────────────────┐
  │  Level 3: 系统服务层 (system_server)                                         │
  │  ─────────────────────────────────────────────────────────────────────────  │
  │  时机: Zygote fork → SystemServer.run()                                    │
  │  动作: 顺序创建 AMS → WMS → IMS 等，逐一通过 addService() 注册到 Native SM   │
  │  结果: 60+ 系统服务全部就绪                                                  │
  │  PID: 通常为 ~1000 (system 进程)                                            │
  └─────────────────────────────────────────────────────────────────────────────┘
                                     │
                                     │ 前置条件
                                     ▼
  ┌─────────────────────────────────────────────────────────────────────────────┐
  │  Level 4: 应用进程层 (App Process)                                          │
  │  ─────────────────────────────────────────────────────────────────────────  │
  │  时机: Zygote fork → 应用进程首次访问系统服务时                               │
  │  动作: ProcessState.getOrCreateSelf() → open + mmap(1MB)                    │
  │  结果: 应用进程可以发送/接收 Binder 事务                                      │
  │  数量: 每个应用一个独立进程                                                    │
  └─────────────────────────────────────────────────────────────────────────────┘
```

---

## 三、每一级的核心动作一览

### Level 1 — 内核级

| 项 | 详情 |
|----|------|
| **时机** | Linux kernel 启动，`device_initcall` 阶段（优先级 7） |
| **触发** | `binder_init()` 函数（通过 `device_initcall(binder_init)` 注册） |
| **关键动作** | 注册字符设备 → `class_create("binder")` → `device_create("binder")` → 创建调试文件系统 |
| **产生** | `/dev/binder` 设备节点 |
| **影响** | 所有用户态进程都可以 `open("/dev/binder")` |

### Level 2 — 守护进程级

| 项 | 详情 |
|----|------|
| **时机** | init.rc 解析 → `on init` → `start servicemanager` |
| **可执行文件** | `/system/bin/servicemanager` |
| **触发** | `main()` 函数 |
| **关键动作** | `binder_open(128KB)` → `binder_become_context_manager()` → `binder_loop()` |
| **产生** | handle=0 的全局上下文管理器 |
| **影响** | 所有进程通过 handle=0 访问 SM |

### Level 3 — 系统服务级

| 项 | 详情 |
|----|------|
| **时机** | Zygote fork → `SystemServer.run()` |
| **触发** | `startBootstrapServices()` → `startCoreServices()` → `startOtherServices()` |
| **关键动作** | 顺序创建 60+ 服务 → 逐个 `ServiceManager.addService()` 注册 |
| **产生** | 完整的服务注册表（服务名 → Binder 代理） |
| **关键依赖** | AMS → WMS → IMS（必须先有 WMS 才能有 IMS） |

### Level 4 — 应用进程级

| 项 | 详情 |
|----|------|
| **时机** | 应用进程首次访问系统服务时（懒加载） |
| **触发** | `ProcessState.getOrCreateSelf()`（首次调用时） |
| **关键动作** | `open("/dev/binder")` → `mmap(1MB)` → 创建 IPCThreadState |
| **产生** | 进程独立的 binder_proc，可以收发 Binder 事务 |
| **影响** | 应用可以使用 AIDL、系统服务、Binder IPC |

---

## 四、关键时序对照表

| 阶段 | 启动顺序 | 是否需要 Binder 已就绪 | Binder 状态 |
|------|---------|----------------------|------------|
| **Linux Kernel** | 1 | — | 内核模块加载中 |
| **init 进程** | 2 | 不需要 | — |
| **Native SM** | 3 | 需要 `/dev/binder` | 刚就绪 |
| **其他守护进程** | 4 | 需要 Native SM | 注册服务 |
| **Zygote** | 5 | 不直接需要（仅预加载类） | 预加载 Java Binder 类 |
| **SystemServer** | 6 | 需要 Native SM | 注册 60+ 服务 |
| **Launcher** | 7 | 需要 Native SM | 查找/调用服务 |
| **应用进程** | 8+ | 需要 Native SM + 各种服务 | 完整 IPC 能力 |

---

## 五、Binder 资源生命周期对照

```
═══════════════════════════════════════════════════════════════════════════════════
  Binder 资源在不同阶段的分配
═══════════════════════════════════════════════════════════════════════════════════

  资源           内核           Native SM        system_server       应用进程
  ──────────   ────────────   ──────────────   ───────────────   ─────────────
  binder_proc  设备节点创建   第一个 binder_proc  第二个 binder_proc  各自独立
                                                       (AMS)              (每个)
  mmap 大小     —              128KB            ~1MB               ~1MB
  handle=0     内核预留       占用 (成为 SM)     查找时引用         查找时引用
  binder_node  —              (空, 等注册)      AMS/WMS/IMS 等     (基本无)
  binder_ref   —              (空)             (少量, 引用 SM)     (引用各服务)
  线程数       —              1 (主循环)        默认 15            默认 15
  操作权限     全部           仅处理 SM 业务   注册/查找          注册/查找
```

---

## 六、关键时序数字参考

```
═══════════════════════════════════════════════════════════════════════════════════
  典型启动时间参考 (不同设备会有差异)
═══════════════════════════════════════════════════════════════════════════════════

  T = 0ms       Linux Kernel 启动
  T = ~50ms     device_initcall → binder.ko 加载，/dev/binder 就绪
  T = ~200ms    init 进程执行
  T = ~300ms    Native ServiceManager 启动完成
  T = ~500ms    Zygote 启动
  T = ~800ms    Zygote preload 完成，开始等待 fork
  T = ~1500ms   SystemServer 启动
  T = ~3000ms   AMS 启动完成
  T = ~5000ms   PKMS 扫描完成 (耗时最长)
  T = ~7000ms   WMS 启动
  T = ~7500ms   IMS 启动
  T = ~8000ms   systemReady() → Launcher 启动
  T = ~10000ms  锁屏界面出现，用户可以使用

  上述时间为典型值，实际设备：
  - 高速设备 (旗舰机): 可能快 30~50%
  - 低端设备: 可能慢 100~200%
  - 冷启动 vs 热启动差异巨大
```

---

## 七、核心要点速记

### 7.1 三个最关键时机

1. **内核加载 binder.ko** — 这是所有 Binder 的基础，没有这一步整个机制不存在
2. **Native SM 启动** — 这是所有服务的"目录"，没有它应用无法找到任何服务
3. **WMS 启动** — 这是 Android 显示系统的核心，没有它屏幕不会有任何画面

### 7.2 三个最关键 ioctl

```c
ioctl(fd, BINDER_SET_CONTEXT_MGR, 0);  // 成为 ServiceManager
ioctl(fd, BINDER_SET_MAX_THREADS, n);  // 设置 Binder 线程数上限
ioctl(fd, BINDER_WRITE_READ, &bwr);    // 读写 Binder 事务 (最常用)
```

### 7.3 三个最关键服务名

```
"window"          → IWindowManager        (WMS)
"activity"        → IActivityManager      (AMS)
"input_method"    → IInputMethodManager   (IMS)
```

### 7.4 三个最关键数据结构

```
binder_proc    → 进程在内核的表示
binder_node    → 服务端实体的表示
binder_ref     → 客户端引用的表示
```

### 7.5 三个最关键的红黑树

```
proc->refs_by_desc    → 按 handle 查找目标服务
proc->nodes           → 管理本进程发布的服务
proc->alloc           → 管理 mmap 区域的缓冲区
```

---

## 八、初始化过程的常见问题

| 问题 | 原因 | 现象 |
|------|------|------|
| **/dev/binder 找不到** | 内核未加载 binder.ko | 系统无法启动任何服务 |
| **Native SM 反复重启** | 系统服务主动崩溃或权限问题 | `init` 重启整个系统 |
| **AMS 注册失败** | 权限不足或重复注册 | 后续服务无法启动 |
| **应用无法获取服务** | 服务尚未注册或已崩溃 | 进程启动后立即崩溃 |
| **Binder 事务卡住** | 目标进程无空闲 Binder 线程 | 系统 ANR 或卡顿 |

---

## 九、与其他初始化机制的关系

```
═══════════════════════════════════════════════════════════════════════════════════
  Binder 初始化与其他系统初始化机制的关系
═══════════════════════════════════════════════════════════════════════════════════

  ┌────────────────────────────────────────────────────────────────────────┐
  │  Binder 与 SurfaceFlinger 关系                                          │
  │  ─────────────────────────────────────────────────────────────────────  │
  │  - 两者都通过 /dev/binder 通信                                           │
  │  - 但 SurfaceFlinger 启动比 system_server 早                             │
  │  - SurfaceFlinger 接收 Surface 来自 Binder 进程间的 SurfaceControl       │
  │  - 初始化顺序: SurfaceFlinger (init) → SystemServer                     │
  └────────────────────────────────────────────────────────────────────────┘

  ┌────────────────────────────────────────────────────────────────────────┐
  │  Binder 与 InputManager 关系                                            │
  │  ─────────────────────────────────────────────────────────────────────  │
  │  - InputManager (Native 层) 启动早于 system_server                       │
  │  - InputManagerService (Java 层) 在 system_server 中启动                 │
  │  - WMS 依赖 InputManager (构造函数需要)                                   │
  │  - 初始化顺序: InputManager (Native) → SystemServer 中的 InputManagerSvc │
  └────────────────────────────────────────────────────────────────────────┘

  ┌────────────────────────────────────────────────────────────────────────┐
  │  Binder 与 Zygote 关系                                                  │
  │  ─────────────────────────────────────────────────────────────────────  │
  │  - Zygote 本身不直接初始化 Binder                                         │
  │  - 但 Zygote 预加载 Binder 相关 Java 类                                  │
  │  - fork 时子进程继承 Zygote 预加载的类                                    │
  │  - 子进程在首次使用时才打开 /dev/binder                                   │
  └────────────────────────────────────────────────────────────────────────┘

  ┌────────────────────────────────────────────────────────────────────────┐
  │  Binder 与 ServiceManager 关系                                          │
  │  ─────────────────────────────────────────────────────────────────────  │
  │  - ServiceManager 是 Binder 的"用户"                                      │
  │  - 借助 Binder 提供的 IPC 能力实现服务注册/查找                            │
  │  - ServiceManager 必须最先启动 (class core, critical)                    │
  │  - 所有服务都通过 ServiceManager 注册                                     │
  │  - 所有进程都通过 ServiceManager 查找服务                                 │
  └────────────────────────────────────────────────────────────────────────┘
```

---

## 十、最终流程图（一张图总结）

```
═══════════════════════════════════════════════════════════════════════════════════
  Binder 初始化全流程一图总结
═══════════════════════════════════════════════════════════════════════════════════

  ┌─────────────────────────────────────────────────────────────────────────┐
  │                    Linux Kernel 启动                                    │
  │                                                                         │
  │   device_initcall: binder_init()                                        │
  │        │                                                                │
  │        │  ① alloc_chrdev_region (分配设备号)                              │
  │        │  ② class_create("binder")                                       │
  │        │  ③ device_create("binder") → 创建 /dev/binder                   │
  │        │  ④ debugfs_create_dir("binder") (调试接口)                       │
  │        │                                                                │
  │        ▼                                                                │
  │   /dev/binder 设备就绪 ✓                                                 │
  └─────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                    init 进程                                            │
  │                                                                         │
  │   解析 init.rc → on init → start servicemanager                          │
  │        │                                                                │
  │        ▼                                                                │
  │   /system/bin/servicemanager 启动                                       │
  │        │                                                                │
  │        │  ① open("/dev/binder") → fd                                    │
  │        │     → 触发内核 binder_open() → 创建 binder_proc                │
  │        │  ② mmap(NULL, 128KB, ...) → 分配共享缓冲区                       │
  │        │     → 触发内核 binder_mmap() → 初始化空闲红黑树                  │
  │        │  ③ ioctl(fd, BINDER_SET_CONTEXT_MGR, 0)                        │
  │        │     → 触发内核 binder_ioctl() → 创建 handle=0 的 node           │
  │        │  ④ binder_loop(bs, svcmgr_handler)                              │
  │        │     → 进入无限循环，处理 add/get/list 请求                       │
  │        │                                                                │
  │        ▼                                                                │
  │   Native ServiceManager 就绪 ✓                                          │
  │   (全局服务注册表可用)                                                    │
  └─────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                    Zygote 启动                                          │
  │                                                                         │
  │   ZygoteInit.main()                                                     │
  │        │                                                                │
  │        │  ① registerZygoteSocket() (本地 socket)                         │
  │        │  ② preload() 预加载常用 Java 类 (含 Binder 相关)                 │
  │        │  ③ runSelectLoop() 等待 fork 命令                               │
  │        │                                                                │
  │        ▼                                                                │
  │   Zygote 就绪 ✓                                                         │
  │   (等待 fork 启动 system_server 和应用)                                  │
  └─────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                    SystemServer 启动 (fork 自 Zygote)                    │
  │                                                                         │
  │   SystemServer.run()                                                    │
  │        │                                                                │
  │        │  阶段 1: startBootstrapServices()                               │
  │        │     ├── Installer                                               │
  │        │     ├── AMS  → addService("activity")                           │
  │        │     ├── PMS  → addService("package")                            │
  │        │     └── ...                                                     │
  │        │                                                                │
  │        │  阶段 2: startCoreServices()                                    │
  │        │     └── DropBoxManagerService → addService("dropbox")           │
  │        │     └── ...                                                     │
  │        │                                                                │
  │        │  阶段 3: startOtherServices()                                   │
  │        │     ├── InputManagerService  → addService("input")              │
  │        │     ├── WindowManagerService  → addService("window")            │
  │        │     ├── InputMethodManagerSvc → addService("input_method")      │
  │        │     └── ... (约 60+ 服务)                                       │
  │        │                                                                │
  │        │  阶段 4: Looper.loop()                                         │
  │        │     └── 进入主循环，处理 Binder 事务                             │
  │        │                                                                │
  │        ▼                                                                │
  │   system_server 就绪 ✓                                                  │
  │   (60+ 系统服务全部注册完毕)                                              │
  └─────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                    应用进程启动 (fork 自 Zygote)                          │
  │                                                                         │
  │   ActivityThread.main()                                                 │
  │        │                                                                │
  │        │  ① Looper.prepareMainLooper()                                   │
  │        │  ② attachApplication()                                          │
  │        │     → 通过 ServiceManager.getService() 获取 AMS 代理              │
  │        │     → 触发 ProcessState.getOrCreateSelf()                       │
  │        │        → open("/dev/binder")  ← ★ 首次初始化本进程的 Binder      │
  │        │        → ioctl(BINDER_SET_MAX_THREADS, 15)                      │
  │        │        → mmap(1MB)                                                │
  │        │           → 创建本进程的 binder_proc                              │
  │        │     → 发送 Binder 事务到 AMS                                     │
  │        │  ③ Looper.loop()                                                │
  │        │                                                                │
  │        ▼                                                                │
  │   应用进程就绪 ✓                                                         │
  │   (可以正常使用 Binder IPC)                                               │
  └─────────────────────────────────────────────────────────────────────────┘

  至此，完整的 Binder 体系建立完成！
```

---

## 十一、面试/学习速记

| 顺序 | 组件 | 启动时间 | 关键文件 | 核心动作 |
|------|------|---------|---------|---------|
| 1 | `binder.ko` | Kernel 启动 | `drivers/android/binder.c` | `binder_init()` |
| 2 | Native SM | init 阶段 | `cmds/servicemanager/main.cpp` | `binder_open + BINDER_SET_CONTEXT_MGR` |
| 3 | Zygote | init 阶段 | `ZygoteInit.java` | `preload + runSelectLoop` |
| 4 | SystemServer | Zygote fork | `SystemServer.java` | `startBootstrap/Core/OtherServices` |
| 5 | WMS | startOtherServices | `WindowManagerService.java` | `WMS.main()` |
| 6 | IMS | WMS 之后 | `InputMethodManagerService.java` | `new IMS()` |
| 7 | 应用进程 | 用户启动应用 | `ProcessState.java` | `open + mmap(1MB)` (懒加载) |

---

## 十二、一句话总结

> **Binder 初始化的本质是"四级依赖链的建立"**：
> 内核提供 IPC 能力 → 守护进程提供注册表 → 系统服务填充注册表 → 应用进程消费注册表。
> 每一级都是下一级的前置条件，整个链路建立完成后 Android 系统才真正可用。
