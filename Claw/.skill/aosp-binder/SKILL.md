---
name: aosp-binder
description: Binder IPC 内核驱动到 Framework 全链路解析技能。当用户深入追问/修改 Binder 时触发：binder.c/binder_alloc.c 驱动层(open/mmap/ioctl)、frameworks/native 的 Bp/Bn/IPCThreadState/ProcessState、Java 层 Binder/BinderProxy、AIDL 生成代码、Binder 线程池、transaction 溢出(TransactionTooLargeException)、oneway/flags、handle/ref 管理、binderfs。即使用户只说"Binder 事务为什么溢出"、"Binder 一次能传多大"、"aidl 生成的类在哪"、"binder 线程池怎么起来的"，也应触发。
agent_created: true
---

# aosp-binder — Binder IPC 全链路

分层顺序：**内核驱动 → Native( libs/binder ) → Java Framework → AIDL 生成**。源码默认 Android 14。详细文件:方法映射到 `references/binder_source_map.md`。

## 何时使用

- 解析 Binder 调用栈 / 事务流程。
- 排查 `TransactionTooLargeException`、Binder 阻塞、死锁、线程池耗尽。
- 新增/修改 AIDL 接口，理解生成代码。
- 内核 binder 驱动改造（binder 缓冲区、binderfs）。

## 一、内核驱动层（GKI：`common/drivers/android/`）

- `binder.c`：`binder_open`(建 `binder_proc`)、`binder_mmap`（建 `binder_buffer` 环形映射，`struct binder_alloc`）、`binder_ioctl`（`BINDER_WRITE_READ` 入口，解析 `binder_write_read`）。
- `binder_alloc.c`：缓冲区分配 `binder_alloc_new_buf`（**单个事务默认上限 ≈ 1MB-(其他开销)**，所有并发事务共享 4MB `mmap` 区），`binder_free_buf`。
- `binder.c`：`binder_transaction` 核心——找目标 `binder_node`/`binder_ref`，拷贝数据到目标 `binder_buffer`，插入 `binder_thread` 的 `todo` 队列，唤醒 `binder_thread_read`。
- `binder_thread` 在 `binder_ioctl` 的 `BINDER_SET_MAX_THREADS` 控制线程池上限（默认 **15** 个 Binder 线程）。

**关键约束**：一个 `Parcel` 事务的 `data` 不得超过 `(1<<20) - 4*1024` 左右（约 1MB 减去线程/其他开销），否则驱动返回 `BR_FAILED_REPLY` 并抛 `TransactionTooLargeException`。大对象走 `ashmem`/`ParcelFileDescriptor` 而非内联。

## 二、Native 层（frameworks/native/libs/binder）

- `ProcessState.cpp`：进程内单例，`open_driver()` 打开 `/dev/binder`（或 `/dev/vndbinder`、`/dev/hwbinder`），`mmap` 申请 `MMAP_SIZE`(1MB-2页，外加线程管理页)，`setThreadPoolMaxThreadCount`/`spawnPooledThread`。
- `IPCThreadState.cpp`：`talkWithDriver()` 经 `ioctl(BINDER_WRITE_READ)` 收发；`executeCommand()` 处理 `BR_TRANSACTION`/`BR_REPLY`；`joinThreadPool()` 循环读命令。
- `BpBinder.cpp`：`transact(code, data, reply, flags)` —— `flags` 含 `FLAG_ONEWAY`（异步，不阻塞等 reply）。
- `IInterface.cpp`：`asBinder`/`interface_cast`/`BnInterface::onTransact` 分发到子类 `onTransact`。
- `Parcel.cpp`：序列化载体，`writeStrongBinder`/`readStrongBinder` 走 `flat_binder_object`。

## 三、Java Framework 层（frameworks/base/core/java/android/os）

- `Binder.java`：服务端 `Binder` 实体，`onTransact(code, data, reply, flags)` 分发。
- `BinderProxy.java`（由 `aidl` 生成的 stub 内部类持有）：客户端代理，`transact()` 最终落到 `BpBinder`。
- `Parcel.java`：Java 侧序列化，native 实现在 `frameworks/base/core/jni/android_os_Parcel.cpp`。

## 四、AIDL 生成代码与构建

- AIDL 定义 `.aidl` → `system/tools/aidl` 生成 `I<Name>.java`（含 `Stub`/`Proxy`）。
- 声明 `oneway` 接口 → 生成 `FLAG_ONEWAY` 调用。
- 构建：`aidl_interface` 模块（Soong）或 `framework` 内预置。稳定 AIDL 见 `aosp-hal-treble`。

## 五、调试与踩坑清单

- **事务溢出**：`logcat` 搜 `!!! FAILED BINDER TRANSACTION !!!`；减小 Parcel、改 `ashmem`/FD。
- **Binder 线程耗尽**：`cat /proc/<pid>/task | wc -l`，长阻塞调用挪到工作线程，勿在 Binder 线程做 IO/锁。
- **死锁**：A 调 B、B 又同线程回 A（同步嵌套）→ 用 `oneway` 或拆分。
- **驱动日志**：`adb shell cat /sys/kernel/debug/binder/proc/*`（需 debugfs）；`dmesg | grep binder`。
- **binderfs**：Android 12+ 用 `/dev/binderfs`，驱动节点动态创建。

## 关联

- 版本/分支坐标见 `aosp-navigator`。
- 系统服务如何使用 Binder 注册到 servicemanager → `aosp-systemserver`。
- vendor binder(`/dev/vndbinder`) 与 HAL 隔离 → `aosp-hal-treble`。
