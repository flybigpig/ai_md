# Android 系统核心模块全景梳理

> 关联文档：[aosp-repo-analysis.md](./aosp-repo-analysis.md)（仓库结构与树状图）、[aosp-harness-blog-summary.md](./aosp-harness-blog-summary.md)（整机源码 Harness）、[aosp-am-wm-core-analysis.md](./aosp-am-wm-core-analysis.md)（AMS/WMS 深度分析）
>
> 本文目标：把 Android 系统自下而上的核心模块（init → kernel → HAL → Zygote → system_server → 各系统服务 → Binder → Handler）逐一梳理。**所有源码路径均已用 `git partial clone` 从 cnb.cool 的 AOSP `android-14.0.0_r1` monorepo 真实核实**，关键文件附 `EXISTS` 校验。

---

## 1. 系统分层架构（自下而上）

```
┌─────────────────────────────────────────────────────────────────┐
│  App 进程 (每个 App 一个，由 Zygote fork)                         │
│   ActivityThread / ApplicationThread / Binder 客户端              │
├──────────────────────────────┬──────────────────────────────────┤
│  system_server 进程           │   surfaceflinger 进程            │
│   AMS  ATMS  PMS  WMS  IMS    │   (图形合成, 独立进程)            │
│   … (Java 框架系统服务)        │                                │
├──────────────┬───────────────┴───────────────┬──────────────────┤
│  Binder (横切) │   HAL 进程 (vendor, 独立)      │  InputReader/   │
│  IPCThreadState│   hwservicemanager / aidl HAL │  Dispatcher     │
│  /ProcessState│   (graphics/audio/camera…)     │  (inputflinger) │
├──────────────┴────────────────────────────────┴──────────────────┤
│  init (PID 1) ── 启动 servicemanager / zygote / surfaceflinger /  │
│                  hwservicemanager / ueventd / logd …              │
├───────────────────────────────────────────────────────────────────┤
│  Linux Kernel + Android 驱动 (binder / dmabuf / f2fs / selinux /  │
│  wakeup / lowmemorykiller / gpu …)                                │
└───────────────────────────────────────────────────────────────────┘
        ▲ Handler/Looper 是「线程内」消息机制，贯穿上述每个 Java 线程
        （system_server 主线程、App 主线程、Binder 线程回调切回主线程）
```

> 注：用户原列表编号有重复（3、5 各出现两次），本文按逻辑重排为 1–11。

---

## 2. 启动流程时序

```
bootloader → Linux Kernel → 挂载 rootfs
   └─ init (PID 1, system/core/init)
        ├─ 第一阶段: 挂载 /sys /dev, SELinux 初始化, ueventd
        ├─ 解析 init.rc, 启动属性服务 (property_service)
        ├─ start servicemanager        (/dev/binder 的"电话簿"守护)
        ├─ start hwservicemanager       (HAL 服务注册中心)
        ├─ start surfaceflinger         (图形合成进程)
        └─ start zygote (app_process)
             ├─ ZygoteInit: preload framework 类/资源
             ├─ forkSystemServer() → SystemServer 进程
             │     └─ SystemServer.run()
             │          ├─ startBootstrapServices(): ATMS, AMS, PMS …
             │          ├─ startCoreServices()
             │          └─ startOtherServices():   WMS, IMS …
             └─ runSelectLoop(): 监听 socket, 按需 fork App 进程
```

---

## 3. 各模块梳理

### 3.1 init —— 用户空间第一个进程（PID 1）
- **真实路径**：`system/core/init/` ✅
- **关键文件**：`init.cpp`(main)、`service.cpp`(服务定义/启动)、`action.cpp`/`action_parser.cpp`(init.rc action 解析)、`builtins.cpp`(内置命令 mount/start/…)、`devices.cpp`(设备节点)、`epoll.cpp`、`property_service.cpp`、`ueventd*`、`selinux*`
- **职责**：Android 用户空间起点。解析 `init.rc`/`*.rc`，按 trigger 启动服务（servicemanager、zygote、surfaceflinger、hwservicemanager…）；管理设备节点、属性服务、SELinux、uevent、bootchart、first/second stage 切换。
- **与其他模块**：是所有 Native 守护与 Zygote 的"父启动器"；属性服务是系统全局 KV 基础。

### 3.2 Zygote —— 应用进程孵化器
- **真实路径**：
  - Java：`frameworks/base/core/java/com/android/internal/os/ZygoteInit.java` ✅、`Zygote.java` ✅、`ZygoteServer.java` ✅
  - Native 入口：`frameworks/base/cmds/app_process/app_main.cpp` ✅
- **职责**：预加载 framework 类与资源 → fork 出 system_server → `runSelectLoop()` 监听 socket，收到请求时 `fork()` 新进程（Copy-on-Write 共享已加载 ART 堆，启动极快）。新进程执行 `ActivityThread.main()`。存在 32/64 位、primary/secondary、isolated 多实例。
- **与其他模块**：被 init 启动；fork 出 system_server 与所有 App 进程；是 AMS `ProcessList.startProcess` 的下游。

### 3.3 SystemServer —— 系统服务容器进程
- **真实路径**：`frameworks/base/services/java/com/android/server/SystemServer.java` ✅
- **职责**：由 Zygote `forkSystemServer` 产生，是几乎所有 Java 框架系统服务的宿主进程。分三阶段启动：
  - `startBootstrapServices()`：ATMS、AMS、PMS、PowerManagerService…
  - `startCoreServices()`：BatteryStats、UsageStats…
  - `startOtherServices()`：WMS、IMS、NotificationManagerService、Wallpaper…
- **与其他模块**：AMS/ATMS/PMS/WMS/IMS 全部在它体内运行，通过 Binder 对外暴露。

### 3.4 AMS / ATMS —— 活动与任务管理（详见前篇）
- **真实路径**：`frameworks/base/services/core/java/com/android/server/am/`（122 文件）+ `…/wm/`（219 文件，Activity 栈逻辑在此）
- **关键类**：`ActivityManagerService`、`ActivityTaskManagerService`、`ActivityTaskSupervisor`、`RootWindowContainer`、`Task`、`ActivityRecord`
- **职责**：AMS 管进程/Service/Broadcast/Provider/OOM/错误；ATMS（在 wm 包）管 Activity 任务栈与生命周期。
- 深度分析见 [aosp-am-wm-core-analysis.md](./aosp-am-wm-core-analysis.md)。

### 3.5 PackageManagerService (PMS) —— 包管理核心
- **真实路径**：`frameworks/base/services/core/java/com/android/server/pm/` ✅（`PackageManagerService.java` 已核实）
- **关键文件**：`PackageManagerService.java`、`PackageManagerServiceUtils.java`、`ApexManager.java`、`AppsFilter*.java`、`Installer.java`、`PackageInstaller*.java`、`parsing/`(Manifest 解析)
- **职责**：APK/Manifest 解析、权限授予、安装/卸载/更新、dexopt、多用户包状态、`ApexManager`(APEX 模块)、`resolveIntent`(组件查询)。是 App 安装与"谁能动谁"的权威。
- **与其他模块**：在 `SystemServer.startBootstrapServices` 最先启动；AMS 启动 Activity 时通过 PMS 解析 Intent/校验组件；与 `installd`(native) 协作落盘。

### 3.6 IMS —— 输入管理系统服务
- **真实路径**：`frameworks/base/services/core/java/com/android/server/input/InputManagerService.java` ✅
- **关键文件**：`InputManagerService.java`、`NativeInputManagerService.java`、`InputManagerInternal.java`、`KeyboardLayoutManager.java`、`KeyRemapper.java`、`PersistentDataStore.java`
- **职责**：管理系统输入（触摸/按键/轨迹板）。Java 层 `InputManagerService` 创建 native `InputManager`（位于 `frameworks/native/services/inputflinger`），后者起 `InputReaderThread`（从 kernel input 设备读事件）+ `InputDispatcherThread`（分发到焦点窗口）。
- **与其他模块**：与 **WMS** 紧密协作——WMS 告知 IMS 当前焦点窗口/焦点应用，IMS 据此把事件派发过去。
- ⚠️ **缩写歧义说明**：IMS 也常指 `InputMethodManagerService`（输入法服务，路径 `…/inputmethod/InputMethodManagerService.java` ✅，已核实存在）。二者不同：前者管"输入事件采集与分发"，后者管"输入法（软键盘）"。本文指**输入事件管理**的 `InputManagerService`。

### 3.7 SurfaceFlinger —— 图形合成器
- **真实路径**：`frameworks/native/services/surfaceflinger/` ✅（独立进程 `surfaceflinger`）
- **关键文件**：`SurfaceFlinger.cpp/.h`、`CompositionEngine/`、`DisplayDevice.cpp`、`Client.cpp`、`SurfaceFlingerDefaultFactory.cpp`
- **职责**：接收各窗口（App/WMS/系统）提交的 `BufferQueue` 图层，按 Z-order/透明度/变换经 **HWC（Hardware Composer，HAL）** 合成到显示设备；管理 Display、VSYNC、刷新。WMS 通过它创建 Surface/Layer。
- **与其他模块**：被 init 启动；与 Kernel 显示驱动 + HAL(graphics) 交互；WMS 是其上层客户。
- 合成主循环：`SurfaceFlinger::handleMessageInvalidate / handleMessageRefresh`；`CompositionEngine` 负责实际合成策略。

### 3.8 Binder —— 跨进程通信基石
- **真实路径**：
  - Native：`frameworks/native/libs/binder/` ✅（`Binder.cpp`、`BpBinder.cpp`、`IPCThreadState.cpp`、`ProcessState.cpp`、`IInterface.cpp`、`Parcel.cpp` 已核实）
  - Java：`frameworks/base/core/java/android/os/Binder.java` ✅、`IBinder.java` ✅、`Parcel.java`
- **职责**：Android IPC 核心。
  - **C++ 层**：`ProcessState` 管理 `/dev/binder` 句柄；`IPCThreadState` 管理 binder 线程循环（`talkWithDriver`）；`BpBinder` 为代理端。
  - **Java 层**：`Binder` 基类 + AIDL 生成的 Stub/Proxy；`transact()`→`onTransact()`。
  - **servicemanager**：服务注册/查询的"电话簿"（init 启动）。
- **与其他模块**：几乎所有系统服务（AMS/PMS/WMS/IMS）都通过 Binder 暴露接口；内核侧 `binder` 驱动（见 3.10）做数据中转。

### 3.9 HAL —— 硬件抽象层
- **真实路径**：
  - 传统 HAL：`hardware/libhardware/` ✅（`hardware.c`、`include/`、`modules/`）
  - Treble HAL：`hardware/interfaces/` ✅（`audio`、`bluetooth`、`biometrics`、`camera`、`graphics`、`automotive`… 已核实）
- **职责**：屏蔽硬件差异，向框架提供统一接口。
  - **传统 HAL**：`hw_module_t`/`hw_device_t`，`dlopen` 加载 `.so`（`hw_get_module`）。
  - **Treble（Android 8+）**：HIDL/AIDL 跨进程 HAL，运行在独立 `vendor` 进程，经 `hwservicemanager`/aidl 服务与 framework 通过 binder 通信。
- **与其他模块**：surfaceflinger 用 `graphics` HAL（HWC）；audio/相机等框架服务经 HAL 触达硬件；HAL 进程由 init 启动。

### 3.10 Kernel drivers —— Linux 内核 + Android 驱动
- **真实路径**：主仓 `kernel/` 目录仅含 `configs` / `prebuilts` / `tests` ✅（**占位/构建配置**，**完整内核源码在独立 repo**，如 `kernel/common`、`kernel/msm` 等，需单独 clone）
- **职责**：Linux 内核 + Android 特有驱动：
  - `binder` 驱动：Binder IPC 的内核侧（3.8 用户态库的底层）
  - `dmabuf`/`ion`：跨进程共享内存（SurfaceFlinger 图层、Binder 大对象）
  - `f2fs`/`ext4`：文件系统；`selinux`：访问控制
  - `wakeup sources`/`wake locks`：功耗管理；`lowmemorykiller`(上游)：与 lmkd 协同
  - `gpu` 驱动、`usb gadget` 等
- **与其他模块**：Binder 驱动支撑 3.8；共享内存支撑 SurfaceFlinger；是 HAL（3.9）与框架之间的最后一道边界。

### 3.11 Handler / Looper / MessageQueue —— 线程内消息机制
- **真实路径**：`frameworks/base/core/java/android/os/Handler.java` ✅、`Looper.java` ✅、`MessageQueue.java` ✅、`Message.java`
- **职责**：线程内异步调度（**注意：是"线程内"而非跨进程**，与 Binder 不同）。
  - `Looper.prepare()` 创建 `MessageQueue` 并 `loop()`；`loop()` 循环 `queue.next()`（native 侧用 `epoll` 等待）。
  - `Handler` 在任意线程 `post(Message/Runnable)`，在目标线程（如主线程）`dispatchMessage` 执行。
- **与其他模块**：贯穿每个 Java 线程——system_server 主线程、App 主线程、Binder 线程收到跨进程调用后常 `Handler` 切回主线程处理。是 Android 异步编程的底座。

---

## 4. 模块依赖关系一览

| 模块 | 依赖（下层/横切） | 被谁依赖（上层） |
|---|---|---|
| Kernel drivers | —（最底层） | Binder、HAL、SurfaceFlinger、lmkd |
| HAL | Kernel 驱动 | SurfaceFlinger、Audio/Camera 等框架服务 |
| init | Kernel（挂载 rootfs） | 启动 servicemanager/zygote/surfaceflinger/hwservicemanager |
| Binder | Kernel binder 驱动 + servicemanager | 几乎所有系统服务 & App |
| Zygote | init 启动；Kernel fork | fork system_server 与所有 App |
| SystemServer | Zygote fork；Binder | 承载 AMS/ATMS/PMS/WMS/IMS |
| AMS/ATMS | SystemServer；Binder；PMS(解析 Intent)；Zygote(fork app) | App 的组件生命周期 |
| PMS | SystemServer；installd(native) | AMS(组件查询)、App 安装 |
| WMS | SystemServer；Binder；IMS(焦点)；SurfaceFlinger(合成) | App 窗口 |
| IMS | SystemServer；Binder；WMS(焦点窗口)；Kernel input 设备 | 输入事件分发 |
| SurfaceFlinger | init 启动；Kernel 显示驱动；HAL(graphics) | WMS、所有可见窗口 |
| Handler/Looper | —（线程内） | 所有 Java 线程的异步调度 |

---

## 5. 一句话串联

> **Kernel 提供 binder 驱动与共享内存 → init 拉起 servicemanager/zygote/surfaceflinger → Zygote fork 出 system_server → SystemServer 在其内启动 AMS/ATMS/PMS/WMS/IMS → 这些服务靠 Binder 互调、经 HAL 触达 Kernel 硬件、由 SurfaceFlinger 把画面合成上屏 → 全程每个 Java 线程用 Handler/Looper 做线程内异步调度。**

---

## 6. 与你的关联 & 下一步

- 你是 Android/Kotlin 开发者（erp-pda）。日常 `startActivity` / `bindService` / 输入事件 / 窗口，底层正是这套链路：App → Binder → AMS/ATMS/WMS/IMS → SurfaceFlinger → HAL → Kernel。
- 前文 [aosp-harness-blog-summary.md](./aosp-harness-blog-summary.md) 的"让 agent 在整机源码树上干活"，导航目标就是本文这些目录（`system/core/init`、`frameworks/base/.../am|wm|pm|input`、`frameworks/native/services/surfaceflinger`、`frameworks/native/libs/binder`）。

**可按需继续深入的方向**：
1. `SurfaceFlinger` 合成主循环 + `CompositionEngine` + HWC 交互（图形栈最硬核）
2. `Binder` 全链路：Java `transact` → native `IPCThreadState::talkWithDriver` → 内核 binder 驱动
3. `init.rc` 解析引擎（`action_parser`/`builtins`）与 sepolicy 初始化
4. `Zygote` fork 与 ART 堆 COW 细节（`Zygote.forkAndSpecialize`）
5. `PMS` 包扫描与 `apexd`/APEX 装载流程
6. `HAL` Treble 跨进程（HIDL/AIDL）与 `hwservicemanager` 注册
