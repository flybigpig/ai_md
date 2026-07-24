# Binder × AIDL HAL × ONEWAY × BINDER_FREEZE 专项详解

> 版本基准：AOSP `android-14.0.0_rXX`，内核 `common/android14-6.1`
> 承接《Binder 驱动全链路详解》。本文回答三个追问：
> 1. AIDL HAL 到底怎么架在 binder 上、framework 如何远程调它
> 2. `FLAG_ONEWAY` 异步路径在内核里怎么走
> 3. Android 14 用 `BINDER_FREEZE` 把进程「冻住」的机制

---

# 一、Binder 与 AIDL HAL 的关系：HAL 如何被 framework 远程调用

## 1.1 一句话结论
**AIDL HAL = binder 传输 + AIDL 代码生成。** AIDL 编译器把 `.aidl` 接口生成 `BnXxx`（服务端桩）/ `BpXxx`（客户端代理），它们本质就是 libbinder 的 `BBinder` / `BpInterface` 子类。所以从客户端 `transact` 到内核 `binder_ioctl` 的整条链路，和上一份文档完全一致——AIDL 只是替你写了 `onTransact` 的 `switch` 和 `Parcel` 的打包拆包。

> 关键区别（新手最容易混）：
> | 机制 | binder 设备 | 服务管理器 | 用途 |
> |------|------------|-----------|------|
> | Framework ↔ App | `/dev/binder` | `servicemanager` | 系统服务、App IPC |
> | **AIDL HAL / vendor-native** | `/dev/vndbinder` | `vndservicemanager` | 厂商 HAL、vendor 进程间 |
> | 旧 HIDL HAL | `/dev/hwbinder` | `hwservicemanager` | 老 HAL（逐步淘汰） |
>
> 驱动代码是同一份 `binder.c`，只是三个**设备节点 + 服务管理器实例**不同，再加上 SELinux 域隔离。AIDL HAL 最大的进步就是**不再需要独立的 hwbinder 体系**，统一回 binder 家族。

## 1.2 端到端调用流（framework → AIDL HAL）
```
[system_server / 某系统服务] (Java)
  └─ IFoo.Stub.asInterface(ServiceManager.getService("android.hardware.foo.IFoo/default"))
       → 生成 BpFoo（Java 侧 Proxy，内部持有一个 binder handle）
            └─ bpFoo.doSomething(a, b)
                 └─ Parcel 打包 + transact(TRANSACTION_doSomething, ...)   [android.os.Binder]
                      └─ (JNI) native transact → IPCThreadState::transact
                           └─ talkWithDriver → ioctl(/dev/vndbinder, BINDER_WRITE_READ)
                                └─ 内核 binder_transaction() 把数据搬到 HAL 进程
                                     └─ HAL 进程 IPCThreadState 读 BR_TRANSACTION
                                          └─ BnFoo::onTransact → doSomething() 你的实现
                                               └─ reply 原路返回 → framework 拿到结果
```

## 1.3 可运行模板：最小 AIDL HAL（cpp 后端）

**(1) 接口定义 `android/hardware/foo/IFoo.aidl`**
```aidl
package android.hardware.foo;

interface IFoo {
    int add(int a, int b);
    void ping();   // 可被标 @oneway（见第二章）
}
```

**(2) `Android.bp` —— 用 `aidl_interface` 生成 C++ 桩**
```bp
aidl_interface {
    name: "android.hardware.foo.IFoo",
    srcs: ["android/hardware/foo/IFoo.aidl"],
    stability: "vintf",              // HAL 必须声明 vintf 稳定性
    backend: {
        cpp: { enabled: true },     // 生成 BnFoo / BpFoo (C++)
        ndk: { enabled: true },     // 也可走 NDK
        java: { enabled: true },    // framework 侧用 Java 代理
    },
}
```

**(3) HAL 服务端 `FooService.cpp`（实现 + 注册）**
```cpp
#include <android/hardware/foo/BnFoo.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <binder/IPCThreadState.h>

using namespace android;
using namespace android::hardware::foo;

class Foo : public BnFoo {
    Status add(int32_t a, int32_t b, int32_t* out) override {
        *out = a + b;
        return Status::ok();
    }
    Status ping() override { return Status::ok(); }
};

int main() {
    // 注意：vendor HAL 走 vndbinder。C++ 侧需指定驱动：
    ProcessState::initWithDriver("/dev/vndbinder");
    sp<Foo> service = new Foo();
    // 注册名 = "<包名>.<接口>/<实例>"
    defaultServiceManager()->addService(
        String16("android.hardware.foo.IFoo/default"), service);
    ProcessState::self()->startThreadPool();
    IPCThreadState::self()->joinThreadPool();
    return 0;
}
```
> NDK 写法等价：`ndk::SpAIBinder binder = ndk::SharedRefBase::make<Foo>();`
> `AServiceManager_addService(binder.get(), "android.hardware.foo.IFoo/default");`

**(4) VINTF 声明 `manifest.xml`（兼容性校验用）**
```xml
<hal format="aidl">
    <name>android.hardware.foo</name>
    <version>1</version>
    <interface>
        <name>IFoo</name>
        <instance>default</instance>
    </interface>
</hal>
```
`vintf` 会拿它和 framework 的 compatibility matrix 比对；`adb shell lshal` 能看到已注册的 AIDL HAL。

**(5) framework 客户端（Java 侧拿代理）**
```java
import android.hardware.foo.IFoo;
IFoo foo = IFoo.Stub.asInterface(
    ServiceManager.getService("android.hardware.foo.IFoo/default"));
int r = foo.add(3, 4); // → 走 binder → HAL 进程
```

> 把 server 加进 `device.mk` 的 `PRODUCT_PACKAGES`，并配好 `sepolicy`（HAL domain + `vndservicemanager_type` 的 `add`/`find`），烧进 vendor 即可。整条路径在底层就是上一篇的 `binder_transaction`。

---

# 二、FLAG_ONEWAY 异步路径

## 2.1 语义
`oneway` = **客户端发了就走，不等 reply**。常见于通知/回调/事件上报（传感器、电量、死亡通知）。
- AIDL 里用 `@oneway` 修饰方法：`@oneway void onEvent(...);`
- 用户态标记：`transact(code, data, reply, IBinder.FLAG_ONEWAY)`（Java）/ `FLAG_ONEWAY`（C++）。
- AIDL 生成代码会把 `@oneway` 方法的 `transact` 自动带上 `FLAG_ONEWAY`。

## 2.2 内核态差异（`binder_transaction`，`binder.c`）
核心判据是内核标志 `TF_ONEWAY`（由用户态 `FLAG_ONEWAY` 映射而来）：

```c
if (reply) {
    /* BC_REPLY：这是回复路径，不讨论 */
} else if (tr->flags & TF_ONEWAY) {
    /* ===== 异步分支 ===== */
    // 1) 给「发送方线程」只回一个 BR_TRANSACTION_COMPLETE，表示驱动已收下
    t_complete->type = BINDER_WORK_TRANSACTION_COMPLETE;
    binder_enqueue_thread_work(thread, t_complete);
    // 2) 真正的 transaction 工作入队到「目标进程/线程」
    binder_enqueue_thread_work(target_thread ? : target_proc, &t->work);
    binder_wakeup_thread(target_thread ? : target_proc);
    // 3) 发送方不会收到 BR_REPLY，talkWithDriver 拿到 BR_TRANSACTION_COMPLETE 即返回
} else {
    /* ===== 同步分支 ===== */
    // 发送方线程被挂起，等待目标处理完回 BR_REPLY（或异常 BR_*）
    binder_enqueue_thread_work(thread, t_complete);  // 之后还要等 reply
    binder_enqueue_thread_work(target, &t->work);
}
```

要点：
- **发送方不等 reply**：`waitForResponse()` 收到 `BR_TRANSACTION_COMPLETE` 就结束（oneway 时不再等 `BR_REPLY`）。
- **目标仍正常收 `BR_TRANSACTION` 并执行**；它的 `BC_FREE_BUFFER` 负责释放这次分配的 `binder_buffer`（而不是由发送方释放）。
- **顺序保证**：同一 `(sender, target)` 对的 oneway 事务之间仍保序；不同发送方之间不保证全局顺序。
- **无返回值**：`@oneway` 方法返回类型只能是 `void`。

## 2.3 Android 14 的 oneway 防护：`BR_ONEWAY_SPAM_SUSPECT`
恶意/失控客户端狂灌 oneway 会冲击目标。内核提供：
- `BINDER_ENABLE_ONEWAY_SPAM_DETECTION` ioctl：开启后，若某线程短时间内 oneway 过量，驱动返回 `BR_ONEWAY_SPAM_SUSPECT`，用户态可据此限流/杀进程。
- 这是 framework 对 oneway 做 DoS 防御的一环。

## 2.4 观察模板
```bash
# 内核侧看 oneway 事务（TF_ONEWAY 标志可见）
adb shell "echo 1 > /sys/kernel/tracing/events/binder/enable"
adb shell cat /sys/kernel/tracing/trace_pipe
# 关注 binder_transaction 行里的 flags / oneway 标记
```

---

# 三、Android 14 的 BINDER_FREEZE 冻进程机制

## 3.1 为什么需要 freeze
后台/缓存 App（cached）、应用待机、低内存（`lmkd`）时，系统希望**冻结**某进程：
- 它不该再处理 binder 调用（避免被唤醒做活）。
- 别的活进程调它时，**不能傻等**——必须快速失败，否则会卡死系统服务。

旧做法可能让调用方阻塞；Android 14 通过 `BINDER_FREEZE` 让被冻结目标的事务**直接返回 `BR_FROZEN_REPLY`**，调用方立即失败而非挂起。

## 3.2 接口与内核结构

**uapi 结构（`uapi/linux/android/binder.h`）**
```c
struct binder_freeze {
    __u32 pid;        // 要冻结的进程 pid
    __u32 enable;     // 1 = 冻结, 0 = 解冻
    __u32 timeout_ms; // 等其未决事务清空的最长等待，0 表示立即
};

struct binder_frozen_status_info {
    __u32 pid;
    __u32 sync_recv;  // 是否还有未处理的「同步」入站事务
    __u32 async_recv; // 是否还有未处理的「异步(oneway)」入站事务
};
```

**ioctl（`binder.c` 的 `binder_ioctl`）**
```c
case BINDER_FREEZE:
    ret = binder_ioctl_freeze(filp, arg);   // → binder_freeze(proc, ...)
    break;
case BINDER_GET_FROZEN_INFO:
    ret = binder_ioctl_get_frozen_info(filp, arg); // → binder_get_frozen_info()
    break;
```
`binder_freeze()` 设置 `binder_proc->is_frozen = true/false`。

## 3.3 冻结后事务如何被拦截（`binder_transaction`）
在 `binder_transaction` 找到 target 之后、真正入队之前，内核检查目标冻结状态：
```c
if (target_proc->is_frozen &&
    !(tr->flags & TF_ALLOW_FROZEN)) {   // 除非调用方显式允许
    // 不入队、不唤醒，直接给调用方回冻结失败
    // 走 BR_FROZEN_REPLY 路径
    return BR_FROZEN_REPLY;   // 调用方 waitForResponse 收到后失败返回
}
```
- 调用方：`waitForResponse()` 收到 `BR_FROZEN_REPLY` → 用户态 transact 失败（AIDL 侧表现为 `Status` 异常，framework 抛 `Frozen`-类异常）。
- **双向保护**：冻结进程本身发往活进程的事务同样受控（避免被冻进程还去打扰别人）。
- `TF_ALLOW_FROZEN`：少数系统调用（如解冻控制本身）用此标志绕过冻结检查。

## 3.4 未决事务与 `BINDER_GET_FROZEN_INFO`
冻结不是「一刀切」：若目标还有**未处理入站事务**，可能希望等它们排空。
- `BINDER_GET_FROZEN_INFO` 返回 `sync_recv` / `async_recv`，告诉调用方：该 pid 是否仍有同步/异步事务没收。
- 系统（如 `lmkd`、ActivityManager）据此决定「先等排空再彻底冻结」或「立即冻结并接受失败」。

## 3.5 可运行模板：在设备上冻结一个进程
> 需 root，且对目标 pid 有冻结权限（通常是 system/root）。编译进 AOSP 后 push 到设备运行。
```c
// freeze_test.cpp  —— 演示 BINDER_FREEZE ioctl
#include <linux/android/binder.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char** argv) {
    if (argc < 3) { printf("usage: %s <pid> <1|0>\n", argv[0]); return 1; }
    int fd = open("/dev/binder", O_RDONLY | O_CLOEXEC);
    if (fd < 0) { perror("open binder"); return 1; }

    struct binder_freeze bf;
    bf.pid        = (unsigned)atoi(argv[1]);
    bf.enable     = (unsigned)atoi(argv[2]);   // 1=冻, 0=解
    bf.timeout_ms = 0;

    if (ioctl(fd, BINDER_FREEZE, &bf) < 0) {
        perror("BINDER_FREEZE");
        close(fd);
        return 1;
    }
    printf("pid %s -> frozen=%d\n", argv[1], bf.enable);
    close(fd);
    return 0;
}
```
配套 `Android.bp`：
```bp
cc_binary {
    name: "binder_freeze_test",
    srcs: ["freeze_test.cpp"],
    shared_libs: ["libbinder"],   // 仅为拿到 uapi 头；其实只用 open/ioctl
    cflags: ["-Wno-error"],
}
```
运行效果：冻结某 App 后，再让 system_server 调它的 binder 接口，framework 会拿到 `BR_FROZEN_REPLY` 快速失败，而不是卡死。

> 生产环境里冻结由 `ActivityManagerService` / `lmkd` 通过 `android.os.Process` 的 freeze API 触发，最终落到内核这个 ioctl；上层不需要自己 open `/dev/binder`。

---

# 四、三者串起来看

| 关注点 | 内核落点 | 用户态落点 |
|--------|----------|------------|
| AIDL HAL 远程调用 | `binder_transaction`（handle 是 vndbinder 域的） | AIDL 生成 `BnFoo`/`BpFoo` + `servicemanager`/`vndservicemanager` |
| ONEWAY | `tr->flags & TF_ONEWAY` 分支，只回 `BR_TRANSACTION_COMPLETE` | `FLAG_ONEWAY` / AIDL `@oneway` |
| FREEZE | `target_proc->is_frozen` 检查 → `BR_FROZEN_REPLY`；`BINDER_FREEZE`/`GET_FROZEN_INFO` ioctl | AMS/`lmkd` freeze API，AIDL `Status` 异常 |

共同底座：无论 AIDL HAL、oneway、还是 freeze，都只是 **`binder_ioctl` + `binder_transaction` 的不同分支与标志位**。理解透上一篇的 `binder_transaction`，这一篇全是它的「开关组合」。

---

## 五、下一步可深挖
- **HIDL vs AIDL 迁移**：旧 HAL 在 `/dev/hwbinder` 上的 Passthrough/Binderized 模型，如何迁到 AIDL on vndbinder。
- **binder 缓冲与优先级继承**：`BINDER_SET_INHERIT_RT`、`min_scheduling_policy`、binder 线程的优先级传递（避免优先级反转）。
- **vendor 域 SELinux**：HAL domain 如何拿到 `vndservicemanager_type` 的 `add`/`find`、`binder_device` 的 `ioctl` 权限。
- **frozen + cached 进程实测**：用 `am`（或 `cmd activity`）冻结一个 App，抓 `binder_transaction` 看 `BR_FROZEN_REPLY` 实测轨迹。
