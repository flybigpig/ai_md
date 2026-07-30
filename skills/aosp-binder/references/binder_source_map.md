# Binder 源码文件:方法映射（Android 14）

> aosp-binder 的详细索引。行号为大致区间，以实际 checkout 为准。

## 内核驱动（kernel/common, 分支 android14-6.1）

| 文件 | 关键函数 | 作用 |
|---|---|---|
| drivers/android/binder.c | `binder_open` | 创建 `binder_proc`，初始化 `binder_alloc` |
| | `binder_mmap` | 映射 4MB(或 1MB+页) 缓冲区到用户态 |
| | `binder_ioctl` | 处理 `BINDER_WRITE_READ`/`BINDER_SET_MAX_THREADS`/`BINDER_SET_CONTEXT_MGR` |
| | `binder_thread_write` | 解析 `BC_*` 命令(`BC_TRANSACTION`,`BC_REPLY`) |
| | `binder_transaction` | 核心：定位 target node/ref，分配 buffer，入队，唤醒 |
| | `binder_thread_read` | 从 `todo` 取事务，发 `BR_*` 到用户态 |
| | `binder_alloc_new_buf` | 在 `binder_alloc` 分配事务 buffer |
| drivers/android/binder_alloc.c | `binder_alloc_mmap` | 建立缓冲区映射 |
| | `binder_alloc_new_buf_locked` | 找空闲 buffer 槽 |
| | `binder_free_buf` | 释放 buffer |
| drivers/android/binder_internal.h | `struct binder_proc/thread/node/ref/buffer` | 核心数据结构 |

**事务大小上限**：`binder_alloc.c` 中 `alloc->buffer_size` 由 `mmap` 大小决定；单事务上限约 `buffer_size - (4KB 对齐开销)`，实践中建议 < 1MB。超大会触发 `BR_FAILED_REPLY`。

## Native（frameworks/native/libs/binder，分支 android-14.0.0_rXX）

| 文件 | 关键函数 | 作用 |
|---|---|---|
| ProcessState.cpp | `self()` / `open_driver()` / `mmap` / `spawnPooledThread()` / `setThreadPoolMaxThreadCount()` | 进程单例，开驱动，起线程池 |
| IPCThreadState.cpp | `self()` / `talkWithDriver()` / `executeCommand()` / `joinThreadPool()` / `transact()` | 收发命令，分发 BR_* |
| BpBinder.cpp | `transact()` | 客户端代理，带 flags(oneway) |
| BnInterface.cpp (IInterface.cpp) | `onTransact()` | 服务端分发基类 |
| Parcel.cpp | `writeInt32/writeStrongBinder/readStrongBinder` | 序列化 |
| include/binder/IServiceManager.h | `defaultServiceManager()` | 取 servicemanager 代理 |

## Java（frameworks/base/core/java/android/os）

| 文件 | 关键方法 | 作用 |
|---|---|---|
| Binder.java | `onTransact(code, data, reply, flags)` | 服务端实体 |
| BinderProxy.java | `transact(code, data, reply, flags)` | 客户端代理(持有 native BpBinder) |
| Parcel.java | `writeXXX/readXXX` | 序列化(native: android_os_Parcel.cpp) |
| IBinder.java | `FLAG_ONEWAY` / `FLAG_ROOT_OBJECT` | 标志位定义 |

## servicemanager（frameworks/native/cmds/servicemanager）

- `service_manager.c`：`svcmgr_handler` 处理 `SVC_MGR_ADD_SERVICE`/`SVC_MGR_GET_SERVICE`。
- `binder.c`(cmds)：`binder_open`/`binder_become_context_manager`/`binder_loop`。
