# Android Framework 面试题 · HAL 抽象层与 Linux 内核驱动全链路（Treble / VINTF / GKI 2.0 / MTK 平台差异）（2026-08-17）

> 系列第 **32 篇** / 累计约 **207 专题**。
> 落点：把被点到却从未独立成篇的 **HAL 抽象层 + Linux 内核驱动 + GKI 2.0 + MTK 平台差异** 焊成一条端到端链路 —— 从 framework 如何跨进程调 HAL（`/dev/hwbinder`/`/dev/vndbinder` 三大 Binder 上下文），到 HAL 如何 `open/ioctl/mmap` 内核 `/dev` 字符设备，再到 GKI 2.0 如何用 KMI 符号白名单把「厂商驱动」与「Google 内核」彻底解耦。它正好命中用户显式列出的「Hal / linux kernel / drivers 驱动 / Mtk」四块，并补上第 8/12 篇（GKI 字符驱动骨架）只点到骨架、没展开全貌的真空。
> 横向衔接：第 8/12 篇讲过 GKI 字符驱动骨架（`miscdevice` + KMI/vendor hook）；第 8/11/8/25 篇讲过 DMA-BUF heaps（ION 弃用→dma-buf）；第 8/1/8/2 篇讲过 TEE/KeyMint HAL；第 8/20 篇讲过 Binder 一次事务。本篇是它们的「硬件落地层」底座，不是重复。

---

## 0. 当日热点锚定（为什么今天深挖 HAL / Kernel / 驱动）

| 信号 | 内容 | 对面试的影响 |
| --- | --- | --- |
| **A17 QPR2 Beta 3（2026-08-14）** | build `CP41.260731.005.A2/B1`，安全补丁 2026-08-05；头条功能 **App Lock**（长按图标→系统认证 sheet 拦截启动，隐藏通知/小组件）。Beta 3 覆盖 Pixel 6a..10 全系，QPR2 stable 仍预计 2026-12。 | 头条是「Framework 层认证拦截」—— 但要真正答透它，考官会反推到 Binder/Servicemanager 的权限校验、到 `ActivityManager` 如何暂停目标栈、再到 HAL 层 `Gatekeeper`/`Biometric` 的链路。本篇 §2/§6 是这条链的地基。另：App Lock 文档明确「已授权的 AI agent 仍可访问」—— 与第 8/3 篇 AppFunctions / AI agent 访问边界完全呼应。 |
| **GKI 2.0 / KMI 持续收紧** | Android 16 强制 GKI 认证；A14 baseline = kernel 6.1（`common-android14-6.1`）；KMI 符号白名单 `abi_gki_aarch64`；`vendor_dlkm` 承载 out-of-tree `.ko`；`init_boot` 与 `boot` 分区分离（A13+ 设备）。 | 「厂商驱动怎么活下来」是 2026 最高频 kernel 面试题。考官必问：GKI 之后 vendor `.ko` 只能调哪些符号？KMI 破坏会怎样？vendor hook 怎么用？见 §5。 |
| **Treble 进入第 9 年** | HIDL（hwbinder）仍在退役中，新 HAL 一律写 AIDL for HAL（`@VintfStability`，走 vndbinder）。VINTF manifest 仍是 framework↔vendor 兼容闸门。 | 区分「HIDL/hwbinder」与「AIDL/vndbinder」是必考题（§1/§2）。背错传输通道直接挂。 |
| 面试死亡陷阱题 | 第三方题库（2026）仍将「Binder/Handler/AMS/WMS」列为 90%/80%/70% 出现概率；但**能区分三大 Binder 上下文、能讲清 HAL 调内核 `ioctl`、能说清 GKI/KMI 解耦**的候选人极少 —— 这是区分「APP 层 CRUD」与「Framework/驱动层高级工程师」的分水岭。 | 本篇即为此而生：把硬件落地的全链路讲透。 |

**结论**：当用户问「Hal / linux kernel / drivers 驱动 / Mtk」时，真正能拉开差距的是把「framework→HAL→内核字符设备→GKI/KMI→MTK 平台差异」讲成一条贯穿链路。本篇即为此而生。

---

## 1. HAL 演进与 Treble 架构（从直接链接到独立进程）

### 1.1 三段演进

```
[Android 7 及之前]   framework 直接 dlopen vendor .so
     framework 进程 -> hw_get_module() -> dlopen("/system/lib/hw/xxx.default.so")
     缺点：framework 与 vendor 强耦合，OTA 升级 framework 必然带崩 vendor。

[Android 8 (Treble) 起]   HIDL + hwbinder + binderized HAL
     framework 进程 --hwbinder--> 独立 vendor 进程(hal) --ioctl--> /dev/xxx
     优点：framework 与 vendor 用稳定接口隔离，vendor 镜像可独立更新。

[Android 11+ 新 HAL]   AIDL for HAL + vndbinder（取代 HIDL）
     framework 进程 --vndbinder--> 独立 vendor 进程(hal) --ioctl--> /dev/xxx
     优点：复用 libbinder，工具链统一，interface 用 .aidl 而非 .hal。
```

- **Treble** 是 Android 8 引入的系统架构重组，核心目标 = 让 **vendor 分区**（`/vendor`、`/vendor_dlkm`）与 **system 分区**（`/system`）通过**稳定接口**解耦，使 Google 能独立推送 framework 更新而无需 OEM 重新适配。
- **VINTF（Vendor Interface）**：framework 启动时用 `system/libvintf/` 比对 vendor 提供的 `manifest.xml` 与 framework 的 `compatibility_matrix.xml`，不匹配直接拒绝启动（bootloop with "incompatible"）。这是 framework↔vendor 的「兼容性闸门」。

### 1.2 binderized vs passthrough

- **binderized（绑定式）**：HAL 跑在独立进程，通过 hwbinder/vndbinder 跨进程调用。生产环境绝大多数 HAL 是 binderized（Camera/ Audio/Graphics 等）。
- **passthrough（直通式）**：HAL 仍以 `.so` 形式被加载进调用方进程，但**通过 HIDL/AIDL 的 passthrough 适配器**暴露接口。主要用于：①legacy HAL 迁移期；②性能极敏感、无法接受 IPC 开销的场景（少数）。passthrough 的 `HIDL_FETCH_xxx` / `IMyHal::getService("default", true /*getStub*/)` 会直接 load 本进程 `.so`。

> 易错点：passthrough 不是「回到 Android 7 的 dlopen」—— 它仍走 HIDL/AIDL 接口声明，只是传输层被替换成本进程函数调用。接口契约不变，只是省了一次 IPC。

### 1.3 面试高频追问

- **Q：Treble 到底解决了什么痛点？**
  **A**：Android 8 之前，framework 直接 `dlopen` vendor HAL `.so`，framework 与芯片强耦合。结果：Google 推 framework 更新后，OEM 必须等芯片厂重新编译 vendor 才能发版，碎片化严重。Treble 用「稳定 HAL 接口 + 独立 vendor 进程 + VINTF 兼容性校验」把两者解耦，让 framework 与 vendor 可独立更新。
- **Q：为什么新 HAL 用 AIDL 不用 HIDL？**
  **A**：HIDL 是 Android 8 临时发明的接口语言，工具链孤立、学习成本高。AIDL 早已用于 framework Binder，复用 `libbinder` + `aidl` 编译器 + NDK 后端（`AIBinder`），统一生态。Google 自 Android 11 起**新 HAL 一律用 AIDL**（`stability: "vintf"`），HIDL 进入维护模式逐步退役。

---

## 2. Binder 三大上下文：dev/binder、dev/hwbinder、dev/vndbinder（核心易错）

### 2.1 同一驱动，三个设备节点，三个 context manager

`drivers/android/binder.c` 这一个字符设备驱动，通过「多设备节点 + 每节点独立 context」实现**三条相互隔离的 Binder 域**。每条域有自己独立的：设备节点、`servicemanager`（context manager）、服务注册表、线程池。

| 设备节点 | 用途 | ServiceManager | 典型使用者 |
| --- | --- | --- | --- |
| `/dev/binder` | framework Binder（App↔system_server↔framework） | `servicemanager`（framework） | 所有 App、system_server、framework 服务 |
| `/dev/hwbinder` | **HIDL** HAL Binder（framework↔HAL、HAL↔HAL） | `hwservicemanager` | HIDL HAL 服务/客户端 |
| `/dev/vndbinder` | **vendor** Binder（vendor↔vendor、AIDL HAL） | `vndservicemanager` | AIDL for HAL、vendor 进程间 |

- `/dev/binder` 的 context manager 是 `servicemanager`（`frameworks/native/cmds/servicemanager/`）。
- `/dev/hwbinder` 的 context manager 是 `hwservicemanager`（`system/hwservicemanager/`），专服务 HIDL。
- `/dev/vndbinder` 的 context manager 是 `vndservicemanager`（`servicemanager` 二进制换名 + 绑定到 vndbinder 设备，服务 vendor 域）。

### 2.2 源码事实：ProcessState 按设备初始化

`frameworks/native/libs/binder/ProcessState.cpp`：

```cpp
// framework 默认走 /dev/binder
sp<ProcessState> ProcessState::self() {
    return initWithDriver("/dev/binder");
}
// 需要跟 vendor 说话的进程（如 HAL 客户端）会显式切换：
sp<ProcessState> ProcessState::initWithDriver(const char* driver) {
    // mDriverName = driver; 打开该设备节点并 mmap
}
```

- 一个进程**可以**同时持有多个 `ProcessState`（分别初始化到不同设备），从而既能跟 framework 聊（`/dev/binder`）又能跟 HAL 聊（`/dev/vndbinder`）。`libbinder` 会根据接口的稳定性声明（`@VintfStability` AIDL / HIDL）自动选对设备。
- **AIDL for HAL 默认走 `/dev/vndbinder`**；HIDL 走 `/dev/hwbinder`。这是 §1.3 追问「为什么新 HAL 用 AIDL」的**传输层答案**：通道从 hwbinder 迁到 vndbinder。

### 2.3 面试高频追问

- **Q：`/dev/binder` 和 `/dev/vndbinder` 是两套驱动吗？**
  **A**：**不是**。是同一个 `binder.c` 驱动实例化的两个设备节点，每个节点是一个独立的 Binder「上下文」（context）。隔离发生在驱动层面（各自独立的 `binder_context`、各自注册一个 context manager）。所以 vendor 域的服务不会污染 framework 域的服务表，反之亦然——这正是 Treble 安全隔离的底层保障。
- **Q：App 能直接调 HAL 吗？**
  **A**：不能直接。App 只能走 `/dev/binder` 跟 framework 服务（如 `CameraManager`/`AudioManager`/`LocationManager`）交互；framework 服务再经 `/dev/vndbinder`（AIDL HAL）或 `/dev/hwbinder`（HIDL）跨到 vendor 的 HAL 进程，HAL 最后 `ioctl` 内核 `/dev`。三层：App→framework→HAL→kernel。SELinux 也分别用 `system_app`/`system_server`/`hal_*` 域约束每一跳。

---

## 3. HAL 接口定义与代码生成（HIDL vs AIDL for HAL）

### 3.1 HIDL（.hal + hidl-gen）

接口声明在 `hardware/interfaces/<subsystem>/<version>/Ixxx.hal`，如 `hardware/interfaces/camera/provider/2.4/ICameraProvider.hal`。`system/tools/hidl/hidl-gen` 据此生成：
- C++ 端：`BnHwXxx`（服务端桩）/ `BpHwXxx`（客户端代理）+ `IXxx`（接口类）。
- Java 端：对应 `android.hardware.xxx.V2_x.IXxx`。
- 客户端拿服务：`IXxx::getService("default")` → 经 hwservicemanager 查 `/dev/hwbinder`。

> AOSP 14 关键路径：`hardware/interfaces/`、`system/tools/hidl/`、`system/libhidl/`。

### 3.2 AIDL for HAL（.aidl + aidl 编译器 + NDK 后端）

接口声明在 `.aidl`，但带 **`@VintfStability`** 注解（声明这是 vintf 稳定接口），用 `aidl_interface` 构建规则：

```aidl
// vendor/foo/hardware/aidl/foo/IFoo.aidl
@VintfStability
interface IFoo {
    int doThing(in int arg);
}
```

构建产出：
- **NDK 后端**（`frameworks/native/libs/binder/ndk/`）：`AIBinder_*` API（`AIBinder_new`、`AIBinder_transact`）—— C++ HAL 实现首选，无 libc++ 版本耦合。
- **Java 后端**：`android.hardware.foo.IFoo.Stub` / `.Proxy`（系统服务侧）。
- 客户端：`IFoo.Stub.asInterface(serviceManager.waitForDeclaredService("foo.IFoo/default"))`（经 vndservicemanager）。

### 3.3 VINTF manifest 落地

HAL 实例在 `manifest.xml` 中声明（vendor 分区），framework 用 `compatibility_matrix.xml` 校验版本/接口齐备：

```xml
<!-- /vendor/etc/vintf/manifest.xml (节选) -->
<hal format="aidl">
    <name>foo.IFoo</name>
    <version>1</version>
    <interface>
        <name>IFoo</name>
        <instance>default</instance>
    </interface>
</hal>
```

> 易错点：`@VintfStability` 与「普通 AIDL Binder 接口」的根本区别 = 前者受 VINTF 版本化约束、走 vndbinder、参与 OTA 兼容性校验；后者只是普通 framework 接口。漏掉 `@VintfStability` 的 HAL 接口会被 VINTF 拒绝加载。

### 3.4 面试高频追问

- **Q：HIDL 和 AIDL for HAL 的客户端代码长啥样，差异在哪？**
  **A**：HIDL 用 `IXxx::getService("default")`（hwservicemanager）；AIDL HAL 用 `IFoo.Stub.asInterface(sm.waitForDeclaredService("foo.IFoo/default"))`（vndservicemanager）。底层传输通道不同（hwbinder vs vndbinder），但**都是一次 Binder 事务，都是独立进程，都最终落到 HAL 的 `ioctl`**。
- **Q：HAL 版本不兼容 framework 会怎样？**
  **A**：VINTF 校验在启动早期失败 → 设备进入「incompatible」bootloop，log 里能看到 `libvintf` 报 manifest/matrix 不匹配。所以 HAL 接口一旦 `@VintfStability` 发布就**不能改签名**，只能加新接口、升 minor version。

---

## 4. 内核驱动基础：HAL 如何打开 `/dev` 字符设备

HAL 在独立进程里，最终要跟硬件说话，方式是经典的 **UNIX 字符设备**：`open("/dev/xxx")` → `ioctl/read/write/mmap`。所以「Framework 工程师懂不懂内核驱动」的分水岭，就在这一跳。

### 4.1 platform 设备驱动（最典型）

片上外设（I2C/SPI/GPIO 控制器、PWM、codec）几乎都用 `platform_driver` + **设备树（Device Tree）** 描述：

```c
// 内核驱动骨架（drivers/xxx/foo.c）
static int foo_probe(struct platform_device *pdev) {
    // 拿设备树资源、ioremap 寄存器、申请中断、注册字符设备
    return 0;
}
static int foo_remove(struct platform_device *pdev) { ... }

static const struct of_device_id foo_of_match[] = {
    { .compatible = "vendor,foo-controller", },  // 匹配 DT 的 compatible
    { },
};
MODULE_DEVICE_TABLE(of, foo_of_match);

static struct platform_driver foo_driver = {
    .probe  = foo_probe,
    .remove = foo_remove,
    .driver = {
        .name = "foo",
        .of_match_table = foo_of_match,  // 由内核在 DT 解析后自动 probe
    },
};
module_platform_driver(foo_driver);  // drivers/base/platform.c 负责注册/匹配/probe
```

- 设备树节点（`arch/arm64/boot/dts/` 或 vendor dts）写 `compatible = "vendor,foo-controller"`，内核启动解析 DT → 找到匹配的 `platform_driver` → 调 `probe()`。
- `drivers/base/platform.c` 提供 `platform_device_register` / `platform_driver_register`，实现「设备-驱动」匹配与 `probe` 调度。

### 4.2 字符设备：cdev 与 miscdevice

HAL 看到的 `/dev/xxx` 由内核字符设备提供：

```c
// 方式一：标准 cdev（灵活，需手动分配设备号/创建设备）
static const struct file_operations foo_fops = {
    .owner = THIS_MODULE,
    .open  = foo_open,
    .release = foo_release,
    .unlocked_ioctl = foo_ioctl,   // ★ HAL 主要交互点
    .mmap  = foo_mmap,             // 共享内存（如图形/相机 buffer）
    .read  = foo_read,
    .write = foo_write,
};
// cdev_init(&cdev, &foo_fops); cdev_add(...); device_create(...)

// 方式二：miscdevice（最简，自动分配次设备号，GKI 友好）
static struct miscdevice foo_misc = {
    .minor = MISC_DYNAMIC_MINOR,
    .name  = "foo",                // 最终 /dev/foo
    .fops  = &foo_fops,
};
misc_register(&foo_misc);          // drivers/char/misc.c
```

> 第 8/12 篇的「GKI 字符驱动骨架」就是这一段的简化版：优先用 `miscdevice` 而非自定义 `cdev`，因为它不依赖任何非 KMI 的内部 API，能在 GKI 2.0 下干净编译。

### 4.3 ioctl / mmap 是 HAL↔内核的契约

- **ioctl**：用 `_IO`/`_IOR`/`_IOW`/`_IOWR` 宏（`include/uapi/asm-generic/ioctl.h`）定义命令字，携带结构体指针（**必须 `copy_from_user`/`copy_to_user`**，内核不能直接解引用用户指针）。这是 HAL 下发控制命令、读取状态的主通道。
- **mmap**：把内核分配的连续/ DMA 内存映射到 HAL 用户空间，避免 `read/write` 的拷贝（图形 buffer、相机帧、音频buffer 都用它）。与第 8/11/8/25 篇的 DMA-BUF heaps 配合：gralloc 从 dma-buf heap 分配，再 `mmap` 给 HAL/GFX。

```c
// 典型 ioctl 命令定义（uapi 头）
#define FOO_GET_STATUS  _IOR('F', 0x01, struct foo_status)
#define FOO_SET_CONFIG  _IOW('F', 0x02, struct foo_cfg)

static long foo_ioctl(struct file *filp, unsigned int cmd, unsigned long arg) {
    switch (cmd) {
    case FOO_GET_STATUS: {
        struct foo_status st;
        copy_from_user(&st, (void __user *)arg, sizeof(st));  // 必做
        // ... 读硬件 ...
        copy_to_user((void __user *)arg, &st, sizeof(st));    // 必做
        return 0;
    }
    }
    return -EINVAL;
}
```

### 4.4 面试高频追问

- **Q：为什么内核 `ioctl` 里不能直接用用户传来的指针？**
  **A**：用户态指针在 kernel 地址空间里不一定有效（可能越界、被换出、是恶意地址）。必须 `copy_from_user`/`copy_to_user` 做边界检查 + 缺页处理，否则直接解引用会触发 kernel oops / 安全漏洞。这是驱动安全的第一条铁律。
- **Q：platform_driver 和字符设备的区别？**
  **A**：`platform_driver` 解决「设备与驱动怎么匹配、何时 `probe`」（靠 DT `compatible`）；`file_operations`/`cdev`/`miscdevice` 解决「probe 之后怎么把 `/dev/xxx` 暴露给用户态、HAL 怎么 `open/ioctl`」。两者是「发现设备」与「服务设备」的两层，通常一起用。

---

## 5. GKI 2.0 与 KMI：厂商驱动如何「活」在 Google 内核之上（2026 最高频）

### 5.1 GKI 是什么，KMI 是什么

- **GKI（Generic Kernel Image）**：Google 统一构建的内核二进制（`common-android14-6.1`），所有 OEM 共用同一份「核心内核」，硬件相关代码拆成**可加载模块**。GKI 2.0 自 Android 12 起强制（kernel ≥5.10）。
- **KMI（Kernel Module Interface）**：GKI 维护的**稳定 ABI 边界**——一组被白名单允许、承诺跨 kernel 小版本二进制兼容的**导出符号**。vendor 的 out-of-tree `.ko`（`vendor_dlkm` 分区）**只能调用 KMI 白名单里的符号**，不能依赖内核内部函数/结构体布局。
- 白名单文件：`abi_gki_aarch64`（或 `abi_gki_aarch64.xml`，定义 KMI 符号集）。每次 GKI 内核更新，Google 保证这个集合的 ABI 不变，从而 vendor `.ko` 无需重编译即可随 GKI 内核 OTA。

### 5.2 vendor 驱动怎么共存（四大约束）

1. **只能 EXPORT_SYMBOL_GPL 的 KMI 符号**：vendor `.ko` 调用的每个内核符号必须出现在 `abi_gki_aarch64` 白名单（且通常是 GPL 导出）。若某核心函数没进 KMI，vendor 不能调用——除非推动 Google 把它加入 KMI，或用 vendor hook。
2. **vendor hook（厂商钩子）**：核心路径预埋 `tracepoint`-style hook（`include/trace/hooks/` 或 `vendor_hooks.c`），OEM 注册回调注入定制逻辑（如调度、thermal、电量），**不改核心代码**即可定制。这是 GKI 下 OEM 定制的主要合法手段。
3. **模块签名 + dm-verity**：`vendor_dlkm` 受 AVB/dm-verity 保护，`.ko` 必须签名，未签名/被篡改的模块加载会被拒绝（防供应链攻击，也防 root 注入）。
4. **不能改内核内部**：vendor 代码只能作为独立 `.ko` 存在，不能 patch GKI 内核本身（boot 分区由 Google 签名）。`init_boot` 与 `boot` 分离（A13+）：`boot`=GKI 内核，`init_boot`=通用 ramdisk，`vendor_boot`/`vendor_dlkm`=厂商定制。

### 5.3 KMI 破坏的后果

若厂商误用了非 KMI 符号，或 GKI 内核某次更新移除了白名单符号：
- 该 `.ko` 加载时报 `Unknown symbol` → 设备关键功能（相机/音频/触控）失效。
- 更严重：若签名/verity 不匹配 → `vendor_dlkm` 挂载失败 → 设备起不来。
- 这是为什么 GKI 时代「内核版本升级」对 OEM 是低风险（KMI 保证 ABI），但「擅自改内核」是高风险的——直接破坏整个兼容契约。

> AOSP 14 关键路径：`common-android14-6.1` 内核树、`abi_gki_aarch64`、`drivers/`（GKI 部分）、`vendor_dlkm` 分区、`init_boot`/`boot`/`vendor_boot` 分区布局。

### 5.4 面试高频追问

- **Q：GKI 之后，厂商还能不能往内核里加自己的代码？**
  **A**：不能直接改 GKI 内核（`boot` 由 Google 签名）。OEM 只能：① 把定制做成 `vendor_dlkm` 里的 `.ko`，且只依赖 KMI 白名单符号；② 用预埋的 vendor hook 注入逻辑；③ 改设备树（DT overlay，这是允许的，因为 DT 在 vendor 分区）。核心内核保持「Google 单行本」。
- **Q：KMI 和「内核 API 稳定」是一回事吗？**
  **A**：不是。Linux 主线**不保证**内核内部 API 稳定（Linus 名言）。KMI 是 **Android GKI 额外加的一层承诺**：Google 冻结一组符号的白名单 ABI，仅这组对 vendor 模块稳定。vendor 模块如果去依赖白名单之外的内部符号，随时会随内核更新断裂。

---

## 6. 硬件 buffer 通路：HAL 与 SF/GPU 如何零拷贝共享内存（联动 8/11/8/25）

HAL 不只是控制硬件，更要把**数据 buffer**（相机帧、图形 surface、音频 PCM）在 HAL ↔ SurfaceFlinger ↔ GPU ↔ 显示之间高效传递。答案是 **DMA-BUF**（第 8/1/8/25 篇讲过的 ION 替代者）：

- 分配：gralloc（`android.hardware.graphics.mapper`/`allocator` HAL）从 **dma-buf heap**（`drivers/dma-buf/heaps/`，如 system heap / cma heap）分配，用户态用 `libdmabufheap`/`BufferAllocator`。
- 共享：buffer 以 **fd** 形式存在，fd 经 HIDL/AIDL/`/dev/binder` 跨进程传递（Binder Parcel 原生支持 fd），**不拷贝像素数据**。
- 内核侧：`drivers/dma-buf/dma-buf.c` 维护 `dma_buf` 对象的引用计数；`mmap` 把物理/ION 内存映射到各进程；`fence`（`drivers/dma-buf/sync_file.c`）做生产者/消费者之间的**就绪信号**（避免 GPU 读半帧）。
- 与 §4.3 的 `mmap` 一脉相承：HAL 拿到 dma-buf fd → `mmap` 进自己地址空间直接读写 → 把 fd 传 SF → SF `mmap` 同一个物理页 → GPU/HWC 合成。全程零拷贝。

> 易错点：A17 起 **ION 彻底弃用**（支持内核 2025-12 EOL），必须迁移到 DMA-BUF heaps（第 8/1 篇已深挖）。考官若问「buffer 怎么跨进程共享」，答 dma-buf heap + fd + mmap + fence，别再答 ION。

---

## 7. MTK 平台差异：真缺口（AEE / mtklog / PerfService / thermal / vendor HAL）

联发科平台是车载/工控/中低端手机的主力，其 Framework 工程师必须懂的「MTK 专属」知识，是其它篇没覆盖的真缺口。

### 7.1 AEE（Android Exception Engine）+ mtklog —— 崩溃/ANR 排查第一现场

- **AEE** 是 MTK 的异常收集引擎，运行在 `system`/`vendor`，捕获 kernel panic、native crash、NE（Native Exception）、KE（Kernel Exception）、system server watchdog、ANR、HWT（Hang Watchdog Timeout）等。
- 落盘目录 **`/data/aee_exp/`**（或 `/sdcard/mtklog/aee_exp/`），每个异常一个 **`.db` 文件**：`db.fatal`（致命）、`db.anr`、`db.wdt`（watchdog）、`db.ne`（native）。`.db` 是 MTK 私有格式，需 `aee_extract` / `mtklog` 工具解包看 `exp_main.txt`、`SYS_ANDROID_LOG`、`SYS_KERNEL_LOG`、`PROCESS_COREDUMP`。
- **mtklog** 是整体日志框架，三件套：
  - `mobile log`（logcat + 内核 ring buffer，相当于普通 Android logcat 增强）
  - `meta log`（modem/基带，联网问题用）
  - `aee_exp`（异常数据库，见上）
- 抓取：`adb shell mtklogd` 或工程模式（`*#*#3646633#*#*` → Engineer Mode → Log and Debugging → MTK Logger）。
- **调试姿势**：普通 Android 的 `tombstone` 只覆盖 native crash；MTK 设备上**优先看 `/data/aee_exp/*.db`**，因为 AEE 在 tombstone 之前就截获了更完整的现场（含 kernel 栈、寄存器、内存快照）。

### 7.2 PerfService —— MTK 性能提频（游戏/车机常用）

- MTK 专属性能 HAL/服务：`vendor.mediatek.hardware.perfservice`（AIDL/HIDL），或早期 `/proc/perfsrv`、`/dev/perfsrv`。
- 作用：应用申请 `perf_lock`（scenario boost）时，把指定 CPU 簇拉到最高频、绑定大核、提升 GPU 频率、关掉调度降频，退出场景 `perf_unlock`。车机/游戏的「性能模式」「游戏加速」底层就是它。
- 与 Android 标准 **ADPF（Android Dynamic Performance Framework，`android.hardware.power.PowerHintSession`）** 不同：PerfService 是 MTK 私有、更激进的整机提频；ADPF 是 AOSP 标准、精细到线程级的 hint（第 8/24 篇 Power HAL/ADPF 已讲）。考官常问「MTK 怎么提频」——答 PerfService + ADPF 双轨。

### 7.3 MTK thermal —— 温控降频链路

- 驱动：`drivers/thermal/mediatek/`（如 `mtktscpu`、`tscpu` 温度sensor）、`drivers/misc/mediatek/thermal/`。
- 链路：温度 sensor → thermal zone → cooling device（`cpufreq_cooling`/`devfreq_cooling`）→ 降频/限核。MTK 有私有 thermal HAL `vendor.mediatek.hardware.thermal` 上报温度、接收 framework 的 throttling 策略。
- 与第 8/6/8/19 篇 Thermal HAL → Power HAL/ADPF → 降频发热完全呼应：**MTK 上这条链路的「降频执行者」一边是 AOSP 标准 thermal HAL，一边是 MTK 私有 thermal 驱动 + PerfService 的反向提频**，两者博弈决定整机温度/性能。

### 7.4 MTK vendor HAL 与内核驱动位置

- vendor HAL/驱动源码：`vendor/mediatek/` 或 `vendor/mediatek/proprietary/hardware/`、`vendor/mediatek/proprietary/kernel/drivers/`。
- 典型 MTK 驱动：`drivers/input/touchscreen/mediatek/`（触控）、`drivers/misc/mediatek/`（各子系统 misc）、`drivers/power/mediatek/`（电池/充电）、`drivers/gpu/mali/` 或 MTK 自有 GPU 栈。
- 这些驱动在 GKI 2.0 下必须编译成 `.ko` 进 `vendor_dlkm`，且只调 KMI 符号（见 §5）—— MTK 平台是「GKI/KMI 约束」最真实的练兵场。

### 7.5 面试高频追问

- **Q：MTK 设备上 app 崩溃了，你第一眼看哪里？**
  **A**：先看 `/data/aee_exp/*.db`（AEE 截获最全现场：kernel 栈+寄存器+内存），再用 `mtklog` 抓 mobile log 看 logcat/kenel 时间线；最后才看普通 `tombstone`。单纯看 tombstone 会漏掉 KE/HWT 这类系统级异常。
- **Q：MTK 的温度到了，为什么游戏还在掉帧？**
  **A**：thermal 驱动经 thermal HAL 触发 `cpufreq_cooling` 降频，同时 PerfService 的提频请求被 thermal 节流压制——thermal 优先级高于 PerfService，所以再怎么 `perf_lock` 也挡不住过热降频。这是 MTK 上「发热掉帧」的标准根因（呼应第 8/6 篇 Thermal→ADPF→RenderThread）。

---

## 8. 易错红榜 TOP20（HAL / Kernel / GKI / MTK 专版）

1. **三大 Binder 上下文是同一驱动的三个节点**，不是三套驱动：`/dev/binder`（framework）、`/dev/hwbinder`（HIDL）、`/dev/vndbinder`（AIDL HAL/vendor）。
2. **AIDL for HAL 走 vndbinder，HIDL 走 hwbinder** —— 背错通道直接挂（§1.3/§2.2）。
3. **hwservicemanager / vndservicemanager / servicemanager 各管一个域**，服务表互不可见，这是 Treble 隔离的底层。
4. **passthrough HAL ≠ Android 7 的 dlopen**：接口契约不变，只是传输层换成本进程调用。
5. **`@VintfStability` 是 AIDL HAL 的灵魂**：漏了它，VINTF 拒绝加载；且接口一旦发布不能改签名。
6. **VINTF 不匹配 → incompatible bootloop**：manifest.xml 与 compatibility_matrix.xml 对不上，设备起不来。
7. **HAL 最终靠 `open/ioctl/mmap` 内核 `/dev` 字符设备** —— framework 工程师也要懂字符设备驱动。
8. **ioctl 里必须 `copy_from_user`/`copy_to_user`**，内核不能直接解引用用户指针（安全铁律）。
9. **`platform_driver` 管「设备-驱动匹配+probe」，`file_operations/cdev/miscdevice` 管「暴露 /dev 给用户态」** —— 两层，通常一起用。
10. **GKI 2.0 自 Android 12 强制，A14 baseline kernel 6.1**（`common-android14-6.1`）。
11. **KMI 是 Android 额外加的稳定 ABI 白名单，不是 Linux 主线保证** —— Linux 主线不保证内部 API 稳定。
12. **vendor `.ko` 只能调 KMI 白名单符号（通常 GPL 导出）**，否则 `Unknown symbol` 加载失败。
13. **GKI 下定制三招**：vendor_dlkm 的 `.ko`（限 KMI）+ vendor hook 注入 + DT overlay；不能改 GKI 内核本身。
14. **vendor_dlkm 受 AVB/dm-verity 签名保护**，`.ko` 必须签名，未签名/篡改拒绝加载。
15. **`init_boot` 与 `boot` 分离（A13+）**：`boot`=GKI 内核，`init_boot`=通用 ramdisk。
16. **硬件 buffer 共享用 DMA-BUF heaps + fd + mmap + fence，零拷贝**；A17 起 ION 弃用，别再答 ION。
17. **MTK 崩溃第一现场是 `/data/aee_exp/*.db`**，不是普通 tombstone（AEE 截获更全）。
18. **mtklog 三件套 = mobile log + meta log + aee_exp**；抓 log 用 `mtklogd` 或工程模式。
19. **MTK 提频用 PerfService，标准接口用 ADPF（PowerHintSession）**；thermal 优先级高于 PerfService。
20. **MTK 温控链路**：温度 sensor → thermal zone → cooling device（cpufreq/devfreq）→ 降频，私有 thermal HAL + 驱动在 `drivers/thermal/mediatek/`。

---

## 9. 三条高频追问链（HAL / Kernel / GKI / MTK 专版）

### 链 A：App 点一下相机，到底发生了什么（全链路溯源）
追问：App 调 `CameraManager.openCamera()` 走哪条 Binder？→ framework `CameraService` 经哪个域跨到 HAL？→（答：AIDL Camera HAL 走 `/dev/vndbinder`，或旧 HIDL 走 `/dev/hwbinder`）→ HAL 进程里怎么跟内核说话？→（`open("/dev/videoX")` + `ioctl` + dma-buf `mmap`）→ buffer 怎么到 SurfaceFlinger 不拷贝？→（dma-buf heap 分配 + fd 跨进程 + fence）→ 这整条链在 GKI 下，内核驱动必须是 `.ko` 且只调 KMI 符号吗？

### 链 B：GKI/KMI 到底解决了什么，又制造了什么
追问：Android 8 之前厂商怎么改内核？→（直接改，碎片化）→ Treble 之后呢？→（vendor 独立，GKI 统一内核）→ KMI 白名单是什么？→（vendor `.ko` 只能调白名单符号）→ 那 OEM 想加定制功能怎么办？→（vendor hook + DT overlay + vendor_dlkm `.ko`）→ KMI 破坏会怎样？→（`Unknown symbol` 功能失效 / verity 失败起不来）→ Linux 主线保证 API 稳定吗？→（不保证，KMI 是 Android 额外承诺）

### 链 C：MTK 设备发热掉帧，怎么定位
追问：用户说游戏掉帧 → 先看什么？→（mtklog + `/data/aee_exp/*.db` 排除 KE/HWT）→ 是 thermal 降频吗？→（thermal zone + cpufreq_cooling）→ PerfService 提频为什么压不住？→（thermal 优先级高于 PerfService）→ 这条链跟 AOSP 标准 Thermal HAL/ADPF 怎么对应？→（MTK 私有 thermal 驱动 + thermal HAL，呼应第 8/6 篇）→ 在 GKI 下 MTK 温控驱动也是 `.ko` 且限 KMI 吗？

---

## 10. AOSP 14 源码路径清单（HAL / Kernel / GKI / MTK）

```
# HAL / Treble / VINTF
hardware/interfaces/                       # HIDL HAL 定义（ICameraProvider 等）
system/tools/hidl/                         # hidl-gen 代码生成
system/libhidl/                            # HIDL 运行时（BnHw/BpHw）
frameworks/native/libs/binder/ndk/         # AIDL for HAL NDK 后端（AIBinder）
frameworks/native/libs/binder/ProcessState.cpp   # initWithDriver(/dev/binder|/dev/vndbinder)
system/libvintf/                           # VINTF manifest/matrix 校验
/vendor/etc/vintf/manifest.xml             # vendor HAL 实例声明
/compatibility_matrix.xml                  # framework 期望

# Binder 三大上下文
drivers/android/binder.c                   # 同一驱动，多设备节点（binder_context）
frameworks/native/cmds/servicemanager/     # servicemanager(framework) + vndservicemanager(vendor)
system/hwservicemanager/                   # hwservicemanager(HIDL / hwbinder)

# 内核驱动（GKI 2.0, common-android14-6.1）
drivers/base/platform.c                    # platform_device/device_driver 注册与 probe
include/linux/platform_device.h            # struct platform_driver / platform_device
drivers/char/misc.c                        # miscdevice 简易字符设备
include/linux/miscdevice.h
include/linux/cdev.h / fs/char_dev.c       # 标准 cdev
include/uapi/asm-generic/ioctl.h           # _IO/_IOR/_IOW/_IOWR
include/linux/uaccess.h                    # copy_from_user / copy_to_user
arch/arm64/boot/dts/                       # 设备树（compatible 匹配）

# GKI / KMI
common-android14-6.1 内核树                 # GKI 通用内核（Google 统一构建）
abi_gki_aarch64 / abi_gki_aarch64.xml       # KMI 符号白名单
vendor_dlkm 分区                            # 厂商 out-of-tree .ko
init_boot / boot / vendor_boot 分区布局     # A13+ 分离

# DMA-BUF（硬件 buffer 通路）
drivers/dma-buf/dma-buf.c                  # dma_buf 对象与引用计数
drivers/dma-buf/heaps/                     # system/cma dma-buf heap
drivers/dma-buf/sync_file.c                # fence（sync_file）
frameworks/native/libs/nativewindow/        # gralloc / BufferQueue 用户态
system/memory/libdmabufheap/               # BufferAllocator

# MTK 平台（真缺口）
/data/aee_exp/*.db                          # AEE 异常数据库（第一现场）
vendor/mediatek/proprietary/hardware/       # MTK vendor HAL
vendor/mediatek/proprietary/kernel/drivers/ # MTK 内核驱动
drivers/thermal/mediatek/                   # MTK thermal 驱动
drivers/input/touchscreen/mediatek/         # MTK 触控驱动
vendor.mediatek.hardware.perfservice         # PerfService 性能提频 HAL
vendor.mediatek.hardware.thermal             # MTK thermal HAL
```

---

## 11. 31 → 32 篇交叉索引（HAL / Kernel 视角）

| 主题 | 本篇衔接点 | 关联篇 |
| --- | --- | --- |
| GKI 字符驱动骨架（`miscdevice`/KMI/vendor hook） | §4.2、§5 | 第 8/12 篇（核心基础八股） |
| DMA-BUF heaps（ION 弃用） | §6 | 第 8/1 篇（TEE 与 A17 安全内存）、第 8/25 篇（Perfetto SQL 扩充） |
| Binder 一次事务（驱动层） | §2、§3 | 第 8/20 篇（源码级 code walk 启动到首帧） |
| KeyMint / TEE HAL（HAL 实例） | §1、§3 | 第 8/1 篇（TEE/KeyMint） |
| Thermal HAL → ADPF → 降频发热 | §7.3、链 C | 第 8/6 篇（全链路排查）、第 8/19 篇（图形/电源） |
| 三大 Binder 上下文与 SELinux 域 | §2.3、§3 | 第 8/12 篇（Binder 线程池/linkToDeath） |
| 车机/座舱（AAOS）vendor HAL | §7.4 | 第 8/4 篇（AAOS 座舱电源）、第 8/8 篇（A18 桌面融合） |

---

> 本篇把「framework → HAL（hwbinder/vndbinder 三大 Binder 上下文）→ 内核字符设备（platform_driver/cdev/miscdevice/ioctl/mmap）→ GKI 2.0/KMI 解耦 → MTK 平台差异（AEE/mtklog/PerfService/thermal）」焊成一条端到端硬件落地链路，补齐了此前 Java 侧（8/12 GKI 骨架）与测量侧（8/25 DMA-BUF）之间的内核驱动真空。系列至此 **32 篇 / 约 207 专题**：主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧 AI + 源码 walk + Perfetto SQL + 基础八股 + 两版真题大乱斗 + Native 稳定性 + Compose 编译/运行时 + 输入系统 + HAL/Kernel/GKI/MTK 全链路，完整闭环。
