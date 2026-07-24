# Binder 驱动全链路详解（内核 binder.c ↔ 用户态 IPCThreadState）

> 版本基准：AOSP `android-14.0.0_rXX`，内核 `common/android14-6.1`
> 目标：一次 IPC 从「客户端 transact」到「服务端 onTransact」再到「reply」，逐层落到真实函数、真实结构体、真实 ioctl。文末附**可直接放进 AOSP 编译运行的 native binder 服务模板**与**内核侧 ftrace 调试模板**。

---

## 0. 为什么先吃透 Binder

- 它是 Android IPC 的唯一主干：AMS/ATMS/WMS/PMS 与 App 之间、App 与 HAL 之间全走它。
- 它横跨内核与用户态，是理解「系统/BSP」桥接的最佳样本。
- 你关注的 `drivers/android/binder.c`、`binder_alloc.c` 就在内核侧，用户态对应 `frameworks/native/libs/binder/`。

---

## 1. 全局视角：一次 IPC 穿过了哪些层

```
Client (BpXxx)
  └─ transact(code, data, reply)            [BpInterface, IInterface.h]
       └─ IPCThreadState::transact()         [libs/binder/IPCThreadState.cpp]
            └─ writeTransaction() 组装 BC_TRANSACTION + binder_transaction_data
                 └─ talkWithDriver()         填充 binder_write_read
                      └─ ioctl(fd, BINDER_WRITE_READ, &bwr)   ← 陷入内核 ★
                           ┌──────────────── 内核态 (binder.c) ────────────────┐
                           │ binder_ioctl() → binder_ioctl_write_read()        │
                           │   binder_thread_write(): 解析 BC_* 命令            │
                           │     BC_TRANSACTION → binder_transaction()          │
                           │       - 解析 target (handle→node)                  │
                           │       - binder_alloc_new_buf() 分配内核 buffer     │
                           │       - copy_from_user 拷贝数据                    │
                           │       - 处理 flat_binder_object (handle↔node 转换) │
                           │       - binder_enqueue_thread_work() 入队 + 唤醒  │
                           │   binder_thread_read(): 取事务 → 生成 BR_*         │
                           └────────────────────────────────────────────────────┘
                      └─ (返回用户态) talkWithDriver 拿到 BR_TRANSACTION/BR_REPLY
                           └─ waitForResponse() → executeCommand() 处理 BR_*
                                └─ BnXxx::onTransact() → 你的业务方法   [服务端]
                                     └─ reply->writeXxx()  ← 同样走 BC_REPLY 回去
```
一句话：**客户端 `transact` 进 `ioctl`，内核 `binder_transaction` 把数据搬进目标进程的内核 buffer 并唤醒目标线程，目标 `IPCThreadState` 读出来交给 `onTransact`**。

---

## 2. 内核驱动全链路（common/drivers/android/）

### 2.1 核心数据结构（`binder_internal.h`）
| 结构 | 含义 |
|------|------|
| `binder_proc` | 一个打开 `/dev/binder` 的进程，持有 threads / nodes / refs / allocated buffers |
| `binder_thread` | 进程内一条 binder 线程，带 `wait` 队列和 `todo` work 队列 |
| `binder_node` | 服务端在驱动里的一个 Binder 实体（对应一个 BBinder） |
| `binder_ref` | 某进程对某个 node 的引用，`desc` 即该进程视角的 `handle` |
| `binder_buffer` | 一次事务在目标进程分配的内核 buffer（由 `binder_alloc` 管理） |

> 关键不变量：**`handle` 是「进程相对的」**。A 进程里的 handle=5 和 B 进程里的 handle=5 指向不同 node。驱动在跨进程搬运 `flat_binder_object` 时做 handle↔node 翻译。

### 2.2 打开与释放
- `binder_open`（`file_operations->open`）：分配 `binder_proc`，`binder_proc->alloc` 交给 `binder_alloc_init()`，把 proc 挂到 `filp->private_data` 和全局 `binder_procs` 链表。
- `binder_mmap`：为进程映射一块内核/用户共享的 buffer 区（`binder_alloc_mmap()`），这是 zero-copy 的基础。
- `binder_flush` / `binder_release`：进程退出时清理 node/ref/buffer，给对端发死亡通知（`BR_DEAD_BINDER`）。

### 2.3 ioctl 入口
`binder_ioctl`（`binder.c`）按 `cmd` 分发：
```c
static long binder_ioctl(struct file *filp, unsigned int cmd, unsigned long arg)
{
    switch (cmd) {
    case BINDER_WRITE_READ:        // ★ 90% 的流量走这里
        ret = binder_ioctl_write_read(filp, cmd, arg, thread);
        break;
    case BINDER_SET_MAX_THREADS:   // 告诉驱动本进程最多起几条 binder 线程
    case BINDER_SET_CONTEXT_MGR:   // 注册 ServiceManager（handle 0）
    case BINDER_VERSION:
    case BINDER_FREEZE:            // Android 14 冻结/杀进程用
    case BINDER_GET_FROZEN_INFO:
    case BINDER_ENABLE_ONEWAY_SPAM_DETECTION:
    ...
    }
}
```
`binder_ioctl_write_read` 把用户态 `binder_write_read` 拷进来，先 `binder_thread_write`（处理写缓冲里的 BC_*），再 `binder_thread_read`（填充读缓冲的 BR_*），最后整体拷回用户态。

### 2.4 写路径：`binder_thread_write` 的 BC_* 命令
函数里一个大 `switch`，常见命令：
| 命令 | 动作 |
|------|------|
| `BC_TRANSACTION` / `BC_REPLY` | 调 `binder_transaction()`（核心） |
| `BC_FREE_BUFFER` | 释放目标进程的 `binder_buffer`（`binder_alloc_free_buf`） |
| `BC_INCREFS` / `BC_ACQUIRE` / `BC_DECREFS` / `BC_DECREASE` | node/ref 引用计数 |
| `BC_ENTER_LOOPER` / `BC_REGISTER_LOOPER` / `BC_EXIT_LOOPER` | 线程池状态机 |
| `BC_REQUEST_DEATH_NOTIFICATION` | 注册死亡通知 |
| `BC_DEAD_BINDER_DONE` | 客户端确认已处理死亡通知 |

### 2.5 核心：`binder_transaction()`（数据搬运的心脏）
简化流程（真实代码在 `binder.c`，数百行）：
1. 从 `binder_transaction_data` 取 `target.handle`；`handle==0` 是 ServiceManager，否则 `binder_get_ref_for_node` 找到 `binder_ref` → 目标 `binder_node` → 目标 `binder_proc`。
2. `binder_alloc_new_buf()`（6.1；老版本叫 `binder_alloc_buf`）在**目标进程**分配内核 buffer。
3. `copy_from_user` 把发送方 parcel 数据拷进目标 buffer。
4. **对象修正循环（最重要的魔法）**：遍历 parcel 里的 `offsets`，对每个 `flat_binder_object`：
   - 若类型是 `BINDER_TYPE_BINDER`（本地实体），驱动在接收进程创建/获取一个 `binder_ref`，把对象改写成 `BINDER_TYPE_HANDLE`，`handle` 填该 ref 的 `desc`。
   - 这样「我手里的实体」在对方进程里变成了「一个 handle」，引用计数随之 `+1`。
5. `binder_enqueue_thread_work()` / `binder_enqueue_proc_work()` 把事务挂到目标；
6. `binder_wakeup_thread()` 唤醒目标线程（或 `BR_SPAWN_LOOPER` 让对方新建线程）。

### 2.6 读路径：`binder_thread_read`
- 从线程/进程的 `todo` 队列取 `binder_work`（事务、死亡通知等）。
- 构造返回码写进读缓冲：
  - `BR_TRANSACTION`（收到调用）、`BR_REPLY`（收到回复）
  - `BR_DEAD_REPLY` / `BR_FAILED_REPLY` / `BR_DEAD_BINDER` 等异常
- 把 `binder_transaction_data`（含 sender_pid/euid、code、data 指针）填好，`copy_to_user` 交还用户态。

### 2.7 缓冲管理：`binder_alloc.c`
- `binder_alloc_new_buf` / `binder_alloc_free_buf`：管理 `binder_buffer` 红黑树与空闲列表。
- `binder_alloc_copy_user_to_buffer` / `..._from_buffer`：在「用户 parcel + 内核 buffer」之间按 `offsets` 分段拷贝（因为 `flat_binder_object` 需要改写，不能整段盲拷）。
- 这是你排查「binder buffer 泄漏 / 内存不足」的唯一现场（`/d/binder/stats` 能看到 buffer 占用）。

---

## 3. 用户态 libbinder 全链路（frameworks/native/libs/binder/）

### 3.1 ProcessState：每个进程一个，管 `/dev/binder`
`ProcessState.cpp`：
- `ProcessState::self()`：单例，构造函数里 `open_driver()`：
  ```cpp
  mDriverFD = open("/dev/binder", O_RDWR | O_CLOEXEC);
  ioctl(mDriverFD, BINDER_VERSION, &version);
  ioctl(mDriverFD, BINDER_SET_MAX_THREADS, &maxThreads);  // 默认 15
  mmap(...);  // 与驱动共享 buffer
  ```
- 之后所有 `IPCThreadState` 都用这个 `mDriverFD`。

### 3.2 IPCThreadState：每条 binder 线程一个，干脏活
`IPCThreadState.cpp`：
- `talkWithDriver()`：组装 `binder_write_read`，调用 `ioctl(mProcess->mDriverFD, BINDER_WRITE_READ, &mOut/mIn)`。
- `transact()`：本地先 `writeTransaction()` 把 BC_TRANSACTION 写进 `mOut`，再 `waitForResponse()`。
- `waitForResponse()`：循环 `talkWithDriver()`，按 BR_* 分派：
  - `BR_TRANSACTION` → `executeCommand()` → 调 `BnInterface::onTransact()` 真正执行业务
  - `BR_REPLY` → 把 reply 收进 `mReply`，结束等待
  - `BR_DEAD_REPLY` / `BR_FAILED_REPLY` → 返回错误
- `joinThreadPool()`：服务端主线程循环：`talkWithDriver()` 等 `BR_TRANSACTION`，处理，再等。
- `executeCommand()` 对 `BR_TRANSACTION` 调用 `tr.target.ptr` 对应的 `BBinder->transact()` → `onTransact()`。

### 3.3 Parcel：跨进程数据的载体
`Parcel.cpp`：
- `writeInt32/writeString16/writeStrongBinder` 把数据按约定字节序排进缓冲区。
- `writeStrongBinder()` 会写一个 `flat_binder_object`（`BINDER_TYPE_BINDER`），并把它在缓冲区中的偏移记到 `mObjects`——这个 `offsets` 数组就是内核 2.5 步「对象修正循环」的依据。
- 服务端 `readStrongBinder()` 拿到的是本进程视角的 `handle`，`interface_cast` 把它包成 `BpXxx`。

### 3.4 ServiceManager：handle 0 的特殊存在
`IServiceManager.cpp`：
- `defaultServiceManager()`：`getStrongProxyForHandle(0)`——**handle 0 永远指向 ServiceManager**（由 `BINDER_SET_CONTEXT_MGR` 注册）。
- 服务端 `addService(name, binder)` → 通过 handle 0 把自身 `BINDER_TYPE_BINDER` 对象发出去 → ServiceManager 在自身进程里把它变成 handle 存表。
- 客户端 `getService(name)` → 从 ServiceManager 拿到对应 handle → `interface_cast` 成 `BpXxx`，之后所有调用都冲着这个 handle 去。

---

## 4. 可运行模板：手写 native binder 服务（AOSP 内编译）

> 放进任意 AOSP 仓库的 `vendor/<oem>/<board>/binderdemo/`，`mm` 即可编出 server/client。
> 这是**最贴近真实驱动的 legacy libbinder 路径**——直接用 `IPCThreadState`/`BnInterface`，每一笔调用都会真正砸到 `binder_ioctl`。

### 4.1 `IMyService.h`
```cpp
#pragma once
#include <binder/IInterface.h>
#include <binder/Parcel.h>
#include <utils/String16.h>

namespace my {

class IMyService : public android::IInterface {
public:
    DECLARE_META_INTERFACE(MyService);
    static const android::String16 descriptor;
    virtual int32_t getPid() = 0;
    virtual int32_t add(int32_t a, int32_t b) = 0;
    enum {
        TRANSACTION_getPid = android::IBinder::FIRST_CALL_TRANSACTION,
        TRANSACTION_add,
    };
};

class BnMyService : public android::BnInterface<IMyService> {
public:
    android::status_t onTransact(uint32_t code, const android::Parcel& data,
                                 android::Parcel* reply, uint32_t flags = 0) override;
};

} // namespace my
```

### 4.2 `IMyService.cpp`（Bp 客户端桩 + Bn 分发）
```cpp
#include "IMyService.h"
#include <binder/IPCThreadState.h>

using namespace my;
using android::IBinder;
using android::Parcel;
using android::status_t;
using android::NO_ERROR;
using android::String16;
using android::CHECK_INTERFACE;

const String16 IMyService::descriptor("com.example.IMyService");

// ---- Bp (proxy, 跑在客户端) ----
class BpMyService : public android::BpInterface<IMyService> {
public:
    explicit BpMyService(const sp<IBinder>& impl) : BpInterface<IMyService>(impl) {}
    int32_t getPid() override {
        Parcel data, reply;
        data.writeInterfaceToken(IMyService::descriptor);
        remote()->transact(TRANSACTION_getPid, data, &reply);
        return reply.readInt32();
    }
    int32_t add(int32_t a, int32_t b) override {
        Parcel data, reply;
        data.writeInterfaceToken(IMyService::descriptor);
        data.writeInt32(a);
        data.writeInt32(b);
        remote()->transact(TRANSACTION_add, data, &reply);
        return reply.readInt32();
    }
};

IMPLEMENT_META_INTERFACE(MyService, "com.example.IMyService");

// ---- Bn (stub, 跑在服务端) ----
status_t BnMyService::onTransact(uint32_t code, const Parcel& data,
                                 Parcel* reply, uint32_t flags) {
    switch (code) {
        case TRANSACTION_getPid: {
            CHECK_INTERFACE(IMyService, data, reply);
            reply->writeInt32(getPid());
            return NO_ERROR;
        }
        case TRANSACTION_add: {
            CHECK_INTERFACE(IMyService, data, reply);
            int32_t a = data.readInt32();
            int32_t b = data.readInt32();
            reply->writeInt32(add(a, b));
            return NO_ERROR;
        }
        default:
            return android::BBinder::onTransact(code, data, reply, flags);
    }
}
```

### 4.3 `MyService.cpp`（业务实现）
```cpp
#include "IMyService.h"
#include <unistd.h>

using namespace my;

class MyService : public BnMyService {
public:
    int32_t getPid() override { return getpid(); }
    int32_t add(int32_t a, int32_t b) override { return a + b; }
};

sp<MyService> createMyService() { return new MyService(); }
```

### 4.4 `server.cpp`
```cpp
#include "IMyService.h"
#include "MyService.cpp"
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <binder/IPCThreadState.h>

using namespace my;

int main() {
    // 注册到 ServiceManager（handle 0）
    defaultServiceManager()->addService(IMyService::descriptor, createMyService());
    // 起线程池 + 主线程进 looper，等着收 BR_TRANSACTION
    android::ProcessState::self()->startThreadPool();
    android::IPCThreadState::self()->joinThreadPool();
    return 0;
}
```

### 4.5 `client.cpp`
```cpp
#include "IMyService.h"
#include <binder/IServiceManager.h>
#include <stdio.h>

using namespace my;

int main() {
    sp<IBinder> binder = defaultServiceManager()->getService(IMyService::descriptor);
    sp<IMyService> svc = interface_cast<IMyService>(binder);
    if (svc == nullptr) { printf("getService failed\n"); return 1; }
    printf("server pid=%d, 3+4=%d\n", svc->getPid(), svc->add(3, 4));
    return 0;
}
```

### 4.6 `Android.bp`
```bp
cc_library_shared {
    name: "libmymservice",
    srcs: ["IMyService.cpp", "MyService.cpp"],
    shared_libs: ["libbinder", "libutils"],
    export_include_dirs: ["."],
}

cc_binary {
    name: "mymservice_server",
    srcs: ["server.cpp"],
    shared_libs: ["libmymservice", "libbinder", "libutils"],
}

cc_binary {
    name: "mymservice_client",
    srcs: ["client.cpp"],
    shared_libs: ["libmymservice", "libbinder", "libutils"],
}
```

### 4.7 编译与运行
```bash
# 在 AOSP 根目录，进入目录后单编
mm  # 或在根目录 lunch 后 m mymservice_server mymservice_client

# push 到设备/模拟器（需 root）
adb push out/target/product/<board>/system/bin/mymservice_server /system/bin/
adb push out/target/product/<board>/system/bin/mymservice_client /system/bin/
adb shell chmod +x /system/bin/mymservice_server /system/bin/mymservice_client

# 运行（Android 14 SELinux 默认会拦，先关做实验）
adb shell setenforce 0
adb shell mymservice_server &     # 后台起服务
sleep 1
adb shell mymservice_client       # 预期输出: server pid=xxxx, 3+4=7
```
> 想用于真实设备：把 server 加进 `device.mk` 的 `PRODUCT_PACKAGES`，并在 `sepolicy` 里给 domain + `service_manager_type` 的 `add`/`find` 权限（见本系列「板级 SELinux」专题）。

---

## 5. 可运行模板：内核侧 ftrace 抓 Binder 全量轨迹

设备端（需 root，Android 14 的 tracefs 在 `/sys/kernel/tracing`）：
```bash
adb shell
su
cd /sys/kernel/tracing
echo 0 > tracing_on
echo > trace                       # 清缓冲
echo 1 > events/binder/enable      # 打开所有 binder 事件
echo 1 > tracing_on

# （另开一个 shell 跑你的 client，触发一次 IPC）

echo 0 > tracing_on
cat trace                         # 看 binder_transaction / binder_transaction_received 等
```
关键 tracepoint：`binder_transaction`（谁发给谁、code、目标 node）、`binder_transaction_alloc_buf`、`binder_ioctl`、`binder_locked` 系列。

更省事的上层工具：
```bash
# 实时内核态事务日志（调试「卡死/无响应」神器）
adb shell cat /d/binder/proc/<pid>     # 看某进程待处理事务、已分配 buffer
adb shell cat /d/binder/failed_transaction_log

# 用户态耗时
adb shell atrace --async_start -b 8192 binder
adb shell atrace --async_dump          # 看每次 transact 的 CPU 耗时
```

---

## 6. 排错速查（都和上面函数一一对应）
| 现象 | 根因定位点 |
|------|-----------|
| `Transaction failed` / `BR_DEAD_REPLY` | 服务端进程已死；查 `binder_transaction` 里 target node 的 `proc==NULL` |
| `FAILED_BINDER` / `BR_FAILED_REPLY` | 目标线程池满 / `binder_alloc_new_buf` 失败（buffer 耗尽） |
| 跨进程传 Binder 对象后对方拿到空 | `flat_binder_object` 类型/offset 错，`binder_transaction` 对象修正循环没改出 handle |
| 卡在 `waitForResponse` 不返回 | 服务端 `onTransact` 没写 reply，或 BC_REPLY 没发回 |
| `getService` 超时 | ServiceManager 没注册 / SELinux 拦了 `service_manager_type` 的 `find` |
| buffer 泄漏 | `BC_FREE_BUFFER` 没配对调用，`/d/binder/stats` 看 `buffers` 增长 |

---

## 7. 下一步
- **Binder 与 AIDL HAL 的关系**：AIDL HAL 底层仍是这套 `binder_ioctl`，只是 `AIDL` 生成了 `BnXxx`/`BpXxx` 桩，可对照本模板理解「HAL 是如何被 framework 远程调用的」。
- **oneway / 异步**：`FLAG_ONEWAY` 走 `binder_transaction` 但不等 `BR_REPLY`，对应 `BC_TRANSACTION` 后立刻返回。
- **frozen / 杀进程**：Android 14 的 `BINDER_FREEZE` ioctl 与 `binder_freeze` 是如何让一个被杀进程的事务失败而不是挂死。
- **与 BSP 的交叉点**：vendor 进程（HAL）也是 binder 客户端/服务端，vendor 分区里的 HAL 服务同样通过 handle 0 注册到 ServiceManager 的 vendor 实例。
