# Android Framework 热点面试题（第十二篇）
## 主轴：EL2 机密计算世界 —— pKVM / AVF / Microdroid × Android 17 AISeal

> 日期：2026-08-02　|　基准源码：Android 14（UpsideDownCake, API 34）AOSP + Android 17（API 37, CinnamonBun, 2026-06-16 stable）差量
> 系列进度：本篇为第 12 篇，前 11 篇累计 103 个专题；本篇 8 大专题，累计 **111 专题**

---

## 0. 本篇定位：为什么是 pKVM

前十一篇里，我们把「普通世界（Normal World, EL0/EL1）」翻了个底朝天：Binder、AMS/ATMS、WMS、SF、ART、HAL、内核驱动。
第十一篇（08-01）跨过 **EL3 Secure Monitor**，讲了 **TrustZone 安全世界（Trusty TEE）**。

但 Android 的执行世界其实有 **三个**，第十一篇只讲了两个。今天补上中间那一层——**EL2 Hypervisor（pKVM）**：

```
┌─────────────────────────── Normal World ───────────────────────────┐   ┌──── Secure World ────┐
│  EL0  App / Microdroid payload                                     │   │  EL0  Trusted App    │
│  EL1  Android Kernel (host)   │  Microdroid Kernel (pVM guest)     │   │  EL1  Trusty OS      │
│─────────────────────────────────────────────────────────────────── │   │──────────────────────│
│  EL2  pKVM Hypervisor  ← 【本篇主角】host 也被它管着                  │   │                      │
└────────────────────────────────────────────────────────────────────┘   └──────────────────────┘
                                    EL3  Secure Monitor (ATF / BL31)  ← 第十一篇 §1
```

**一句话记住三者的分工差异**（这是面试官最爱问的对比题，见 §5）：

| | TrustZone TEE（EL3/S-EL1） | pKVM pVM（EL2 隔离的 EL1） | App 沙箱（EL0） |
|---|---|---|---|
| 隔离机制 | CPU 安全状态位（NS bit）+ TZASC | Stage-2 页表 + 内存捐赠 | UID/SELinux/seccomp |
| 谁是 TCB | BL31 + Trusty OS + 全部 TA | **仅 pKVM（约 1 万行）** | 整个 Linux 内核 |
| 内存量级 | 几 MB（稀缺） | 几百 MB ~ GB（充裕） | 受 Memory Limiter 管 |
| 编程模型 | 定制 TA + TIPC | **完整 Linux/Android API 子集** | 完整 Android |
| 动态部署 | 极难（要厂商签名进固件） | **APK 里塞 payload 即可** | 随便 |
| 认证等级 | 各厂商 CC EAL 不一 | **SESIP Level 5**（2026 认证） | — |

Android 17 用 **AISeal** 把 pKVM 从「实验室基建」变成了「产品主线」（§4），这是 2026 年 Android 架构侧最大的单点变更，也是今年面试的必考新题。

---

## §1 pKVM 架构原理：EL2 到底做了什么？

### 面试题 1.1
> pKVM 和普通 KVM 有什么本质区别？为什么说「host 内核被攻破，pVM 依然安全」？

### 答案解析

**普通 KVM 的信任模型：** host 内核 == hypervisor。KVM 是内核模块，VMM（QEMU/crosvm）通过 `/dev/kvm` 操作。host 内核可以随时读写 guest 的任意物理页（`kvm_read_guest()`）。所以 **host 内核在 TCB 内**——内核被攻破 = guest 全裸。

**pKVM 的信任模型：** 把 hypervisor 从内核里「劈」出来，单独跑在 **EL2**，而把 **host Linux 内核降级为一个特权受限的 VM（跑在 EL1）**。

关键点：**启动时序上 host 内核先在 EL2 跑，然后主动「自我降级」**。

```
启动链：BL31(EL3) → kernel 以 EL2 启动
  → arch/arm64/kernel/head.S: init_kernel_el() 检测当前 EL
  → 若 EL2 且启用 pKVM：安装 hyp vector，随后 ERET 到 EL1 继续跑 host
  → arch/arm64/kvm/arm.c : init_hyp_mode() / kvm_arm_init()
  → arch/arm64/kvm/hyp/nvhe/setup.c : __pkvm_init()  ← EL2 常驻代码入口
  → 之后 host 想做任何特权操作，只能走 HVC 陷入 EL2 请求
```

**AOSP / 内核源码路径（GKI android14-6.1）：**

| 组件 | 路径 |
|---|---|
| EL2 常驻 hyp 代码（nVHE） | `arch/arm64/kvm/hyp/nvhe/` |
| pKVM 初始化 | `arch/arm64/kvm/hyp/nvhe/setup.c` → `__pkvm_init()` |
| host 侧陷入处理 | `arch/arm64/kvm/hyp/nvhe/hyp-main.c` → `handle_host_hcall()` |
| **内存所有权状态机（核心）** | `arch/arm64/kvm/hyp/nvhe/mem_protect.c` |
| stage-2 页表操作 | `arch/arm64/kvm/hyp/pgtable.c` |
| hyp 私有页分配器 | `arch/arm64/kvm/hyp/nvhe/page_alloc.c` |
| pKVM 用户态入口 | `arch/arm64/kvm/pkvm.c` |

### 底层原理：内存所有权状态机

pKVM 的机密性 **不是靠加密**，是靠 **stage-2 页表的物理页归属跟踪**。ARMv8 两级地址翻译：

```
Guest VA --[stage-1: guest 内核管]--> Guest IPA --[stage-2: pKVM 管]--> Host PA
```

pKVM 在 EL2 为 **每个 VM（含 host 这个"特殊 VM"）** 各维护一份 stage-2 页表，并为每个物理页记录 owner：

```c
// arch/arm64/kvm/hyp/nvhe/mem_protect.c 中的所有权状态（概念）
enum pkvm_page_state {
    PKVM_PAGE_OWNED,             // 独占
    PKVM_PAGE_SHARED_OWNED,      // 我拥有，且已分享给别人
    PKVM_PAGE_SHARED_BORROWED,   // 别人分享给我的
    PKVM_NOPAGE,                 // 不归我
};
```

**创建 pVM 时发生「内存捐赠（donation）」**：host 分配的页 **从 host 的 stage-2 页表里 unmap 掉**，装进 guest 的 stage-2。
→ 此后 host 内核再访问那段 PA，**MMU 直接产生 stage-2 fault，被 pKVM 拦下**。不是"不该访问"，是**物理上访问不到**。

> **这就是"host 被攻破仍安全"的全部秘密**：不是策略检查，是地址翻译层面的物理隔离。攻破 EL1 拿到的仍是一个 stage-2 受限的地址空间。

**唯一例外**是 guest **主动共享**的页（virtio 环形缓冲区等），走 `MEM_SHARE` hypercall 显式建立 `SHARED_OWNED / SHARED_BORROWED` 配对。

### 关键细节：nVHE vs VHE

- **VHE**（Virtualization Host Extensions, ARMv8.1）：host 内核直接跑在 EL2，`HCR_EL2.E2H=1`。性能好，但 host == hypervisor，**不能用于 pKVM**。
- **nVHE**（non-VHE）：hypervisor 是一段独立的、极小的 EL2 代码，host 跑 EL1。**pKVM 必须用 nVHE**（或 hVHE 过渡形态）。

代价：每次 host↔hyp 交互要完整 world switch（保存/恢复寄存器组），比 VHE 慢。这是**安全换性能**的典型取舍，面试可直接答这一句。

### DMA 也必须隔离

CPU 隔离了，**外设 DMA 绕过 MMU 直接打物理内存**怎么办？答案：**SMMU/IOMMU（ARM System MMU）必须由 pKVM 独占管理**。

- 内核路径：`drivers/iommu/arm/arm-smmu-v3/`，pKVM 版驱动在 GKI 中以 hyp 侧形式接管。
- 这是 Google 对 SoC 厂商的**硬性准入要求**：SoC 若无法让 pKVM 独占 SMMU，就无法宣称提供机密性。搜索结果里那句「SoC 供应商必须满足一组新要求才能支持 pKVM，否则供应商无法提供机密性」，指的就是这个。

### 易错点 ⚠️
1. **说错 pKVM 靠内存加密**——不是。内存加密是 AMD SEV / Intel TDX 的路子；pKVM 靠页表所有权。（A17 的硬件封装密钥、FBE 是另一回事，见第十一篇 §7）
2. **说 pKVM 跑在 EL3**——错，EL3 是 Secure Monitor（ATF/BL31）。pKVM 在 **EL2 Normal World**。
3. **说 host 是"宿主机所以权限最高"**——pKVM 下 host 是被降级的 VM，**权限低于 hypervisor**。
4. **忘了 DMA**——只讲 CPU 隔离会被追问死。

### 高频追问链 🔗
- Q：pVM 的内存被 host 回收吗？→ A：VM 停止时 hypervisor **先清零（scrub）再归还** host 内核，防残留泄漏。
- Q：pVM 崩了 host 会怎样？→ A：crosvm 进程退出，捐赠内存被 pKVM 回收清零；host 无感。反之 host OOM 杀 crosvm = 杀掉整个 VM（AVF 文档明确：guest 内存全部计在 crosvm 进程账上）。
- Q：为什么只支持 ARM64？→ A：依赖 ARMv8 的 EL2 + stage-2 + SMMU 组合语义，x86 上要走 SEV/TDX 完全另一套。

---

## §2 AVF 全栈：从 Java API 到 pvmfw 的启动链

### 面试题 2.1
> 一个 App 调 `VirtualMachineManager.create()` 后，到 Microdroid 里 payload 跑起来，中间经过哪些进程和二进制？

### 答案解析：五层调用链

```
[App]  VirtualMachineManager / VirtualMachine  (Java API, 非 bootclasspath，可选模块)
   │   packages/modules/Virtualization/javalib/src/android/system/virtualmachine/
   │   ↓ Binder (AIDL: IVirtualizationService)
[system] virtualizationservice  (Rust 守护进程)
   │   packages/modules/Virtualization/virtualizationservice/src/
   │   职责：鉴权(MANAGE_VIRTUAL_MACHINE 权限)、生命周期、组装 crosvm 命令行、实例磁盘
   │   ↓ fork/exec
[VMM]  crosvm  (Rust 虚拟机监视器)
   │   external/crosvm/
   │   职责：ioctl(/dev/kvm) 建 vCPU 线程、mmap 分配内存、virtio 后端(blk/net/vsock/console)
   │   ↓ KVM_CAP_ARM_PROTECTED_VM + --protected-vm
[Guest 首段代码] pvmfw  (pVM firmware)
   │   packages/modules/Virtualization/pvmfw/
   │   职责：①验证 payload 签名 ②派生 per-VM secret（DICE）③把 BCC 传给下一级
   │   ↓
[Guest OS] Microdroid  (迷你 Android)
       packages/modules/Virtualization/microdroid/
       init → microdroid_manager → apexd/zipfuse 挂载 → 拉起 payload(.so)
```

### crosvm 的内存布局（面试爱问「pVM 内存怎么分的」）

AVF 文档给出的固定布局（低→高）：

```
0x00010000 - 0x40000000   设备内存 (MMIO 区，guest 访问被 trap)
0x7FE00000                pvmfw
0x80000000                物理内存基址 / BIOS 模式 FDT
0x80080000                kernel
ALIGN_UP(KERNEL_END, 16M) ramdisk
...
PHYS_MEMORY_END - 0x200000  正常模式 FDT
```

- 物理内存用 `mmap` 分配，通过 `KVM_SET_USER_MEMORY_REGION` ioctl 填入 **memory slot**，再捐赠给 VM。
- **保护模式下 MMIO 必须由 guest 显式用 hypercall 声明**（`MMIO_GUARD`），避免"访问了一个自己以为是 RAM 的地址结果内容被 VMM 看见"的意外信息泄漏。
- **调度**：每个 vCPU 就是一个 POSIX 线程，调 `KVM_RUN` ioctl 后切进 guest 上下文；**host Linux CFS/EAS 正常调度它**，guest 里花的时间算在这个线程头上。所以 pVM 没有独立调度器，**它就是 host 眼里的一堆普通线程**——这点很多人答错。

### DICE / BCC：pVM 的身份从哪来

pvmfw 的第二个职责最有面试价值：**派生每-VM 密钥**。

- **DICE**（Device Identifier Composition Engine）：逐级度量启动链，`CDI_next = KDF(CDI_prev, measurement(next_stage))`。
- **BCC**（Boot Certificate Chain）：每级把自己的度量做成一个 CBOR/COSE 证书，串成链传给下一级。
- 结果：**payload 变了 → 度量变了 → 派生出的 secret 就变了**，之前加密的数据自动解不开。这叫 **sealing（封印）**。

> 这与第十一篇 §5 的 **Key Attestation / RKP** 是同一套 DICE 思想的两个落点，可以互相印证着答，会显得体系很完整。

**源码：** `packages/modules/Virtualization/pvmfw/src/dice.rs`、`libs/dice/`；开源实现 `external/open-dice/`。

### Microdroid 里的两个特色文件系统

| 组件 | 作用 | 路径 |
|---|---|---|
| **zipfuse** | 把 host 传进来的 **APK 当只读文件系统挂载**，按需解压 | `packages/modules/Virtualization/zipfuse/` |
| **authfs** | host↔pVM 共享文件，**每个 block 带 Merkle 树校验**，host 篡改立即被发现 | `packages/modules/Virtualization/authfs/` |
| **apexd** | 在 guest 里挂载从 host 导入的 APEX | 复用 `system/apex/` |

**authfs 的设计动机很关键**：文件存在 host 的磁盘上（pVM 没有自己的存储介质），但 host 不可信。→ 用 **fs-verity 式的 Merkle 树**，读的时候校验，写的时候更新根哈希。这是"**不信任存储介质但仍能安全用它**"的经典范式，和 dm-verity（第七篇 §5）是同源思路，只是一个校验分区、一个校验单文件且可写。

### 易错点 ⚠️
1. **把 crosvm 说成 QEMU 的封装**——crosvm 是 Rust 从头写的、专为安全设计的独立 VMM，源自 ChromeOS。选它的核心理由是 **内存安全语言消除 VMM 层的 UAF/溢出**（VMM 是攻击面最大的组件）。
2. **说 pVM 可以自带内核**——不行。bootloader 只允许启动 **Google 或设备厂商签名** 的 pVM 映像，遵循 Verified Boot。App 只能塞 payload，不能塞内核。
3. **把 VirtualMachine API 当普通 SDK API**——它**不在 bootclasspath**，是可选模块，只在支持 AVF 的设备上存在，且需要 `MANAGE_VIRTUAL_MACHINE` 特权。

### 高频追问链 🔗
- Q：`--protected-vm` 和不加有啥区别？→ A：加了才走 `KVM_CAP_ARM_PROTECTED_VM`，crosvm 先查询并为 pvmfw 预留内存、开保护模式；不加就是**普通 VM**，host 仍能读 guest 内存（用于开发调试）。Microdroid 两种模式都能跑，**别把 Microdroid 和 pVM 划等号**。
- Q：pVM 冷启动多久？→ A：Microdroid 精简后典型几百 ms 量级；这也是为什么 AISeal 要**常驻**而不是按需拉起。

---

## §3 跨世界通信：vsock、Binder RPC 与「没有 /dev/binder 的 Binder」

### 面试题 3.1
> pVM 里的服务和 host 上的 App 怎么通信？还能用 Binder 吗？跟 `/dev/binder` 有什么区别？

### 答案解析

**能用 Binder，但不是你熟悉的那个 Binder。**

传统 Binder 依赖 **binder 驱动**（`drivers/android/binder.c`）做一次拷贝 + 线程池调度。但 pVM 和 host 是**两个内核**，没有共享的 binder 驱动实例。

AVF 的答案是 **RPC Binder（Binder over sockets，又叫 binderRPC）**：

```
     Host (Android)                        Guest (Microdroid)
┌──────────────────────┐              ┌──────────────────────┐
│ App                  │              │ payload 服务          │
│  ↓ IFoo (AIDL)       │              │  ↑ IFoo (AIDL)       │
│ RpcSession           │              │ RpcServer            │
│  ↓ 序列化 Parcel      │              │  ↑ 反序列化           │
│ vsock (AF_VSOCK)     │◄════════════►│ vsock                │
└──────────────────────┘  virtio-vsock └──────────────────────┘
```

**关键源码：**

| 组件 | 路径 |
|---|---|
| RPC Binder 会话（客户端） | `frameworks/native/libs/binder/RpcSession.cpp` |
| RPC Binder 服务端 | `frameworks/native/libs/binder/RpcServer.cpp` |
| 传输抽象（raw/TLS/Trusty） | `frameworks/native/libs/binder/RpcTransport*.cpp` |
| 对外头文件 | `frameworks/native/libs/binder/include/binder/RpcSession.h` |
| vsock 内核驱动 | `net/vmw_vsock/`、`drivers/vhost/vsock.c` |

**RPC Binder 与内核 Binder 的差异（高频对比题）：**

| 维度 | 内核 Binder (`/dev/binder`) | RPC Binder (vsock) |
|---|---|---|
| 传输 | binder 驱动 + `binder_alloc` mmap，**一次拷贝** | socket 字节流，**两次拷贝** |
| 服务发现 | servicemanager | **无全局 SM**，靠"根对象 + 引用传递"自举 |
| 传 fd | 支持（驱动做 fd 翻译） | 受限（跨内核 fd 无意义；需 `FileDescriptorTransportMode` 明确开启，仅 unix socket 场景） |
| 线程池 | `ProcessState` 设 max threads | `RpcServer::setMaxThreads()` |
| 死亡通知 | `linkToDeath` 由驱动保证 | 靠 socket 断开推断 |
| PID/UID 鉴权 | 驱动填 `IPCThreadState::getCallingUid()` **可信** | **跨 VM 的 uid 无意义**，必须自己设计鉴权 |

> **最容易被追问翻车的点就是最后一行**：跨 VM 后 `getCallingUid()` 不再可信。在 pVM 场景下身份认证要靠 **DICE 派生的 secret / 证书链**，而不是 uid。

**vsock 寻址：** `(CID, port)`。host 固定 `VMADDR_CID_HOST = 2`，每个 guest 分配唯一 CID。所以 host 侧连 guest 就是 `connect(AF_VSOCK, {cid: guest_cid, port: 5555})`。

**注意 Trusty 也用了 RPC Binder**：`RpcTransportTipcAndroid` / `RpcServerTrusty`——即 **同一套 binderRPC 栈同时支撑了「跨 VM」和「跨安全世界」两种跨界通信**。这是第十一篇 §2（libtrusty/TIPC）和本篇的交汇点，答出来很加分。

### 易错点 ⚠️
1. **说 pVM 用共享内存 + Binder 驱动通信**——不对，pVM 内存是捐赠隔离的，除显式共享的 virtio 队列外无共享内存。
2. **以为 RPC Binder 只用于 AVF**——它也用于 Trusty、跨主机 RPC（`RpcTransportTls`）、测试。
3. **说 vsock 比 Unix socket 快**——vsock 走 virtio 有虚拟化开销，语义上是"跨 VM 的 loopback"，**不是性能优化手段，是拓扑必需品**。

### 高频追问链 🔗
- Q：为什么不直接用 TCP over virtio-net？→ A：vsock 免去 IP 配置/路由/防火墙，天然点对点且不受 guest 网络栈影响；安全边界更清晰。
- Q：RPC Binder 的 Parcel 兼容内核 Binder 吗？→ A：AIDL 接口层面兼容（同一份 .aidl 生成），但底层 **wire format 有版本协商**（`RpcSession` 有 protocol version），不能混用 fd/binder 节点等驱动特有语义。

---

## §4 【A17 新增·最重磅】AISeal：机密计算落地个人 AI

### 面试题 4.1
> Android 17 的 AISeal 是什么？它和 Private Compute Core 是一回事吗？为什么说它是 A17 AI 故事的中心？

### 答案解析

**AISeal = 一个常驻的 AVF 保护型 VM，专门装「个人数据 + 端侧大模型推理 + AI Agent」。**

先说清楚一个**极易混淆的点**：

| 名称 | 是什么 | 隔离强度 |
|---|---|---|
| **Private Compute Core (PCC)** | Android 12 起的概念，**进程/沙箱级**隔离 + 无网络出口，跑 Live Caption/Smart Reply 等 | 普通沙箱（EL0） |
| **Private AI Compute** | 云侧 TEE 的隐私云计算（跑不下的大模型上云但云端也隔离） | 服务器 TEE |
| **AISeal（A17 新）** | **pKVM 保护型 VM（Microdroid）** 里的机密计算沙箱 | **Hypervisor 级（EL2）** |

三者是 Google「Private」品牌伞下的**三层**，不是同义词。面试官问「PCC 和 AISeal 区别」，标准答案就是**隔离层级从进程级抬到了 hypervisor 级，威胁模型从"防其他 App"抬到了"防被攻破的 host 内核"**。

### AISeal 内部结构（来自 A17 源码配置）

```
┌──────────── AISeal pVM (Microdroid, protected=true 默认开) ────────────┐
│  ~300 MB RAM  +  ~16 GB 加密存储                                       │
│                                                                        │
│  ┌────────────────┐  ┌─────────────────────┐  ┌────────────────────┐   │
│  │ AppSearch DB   │  │ 端侧推理服务          │  │ AI Agents          │   │
│  │ (个人数据语料)   │  │ (大模型 inference)   │  │ (解析用户请求)      │   │
│  └────────────────┘  └─────────────────────┘  └────────────────────┘   │
│           ▲ 多租户：各 AI provider 作为 tenant，各自暴露 vsock 服务       │
└───────────┼────────────────────────────────────────────────────────────┘
            │ 受控 vsock 单通道（host App 只能按 tenant 名走这一条路）
┌───────────┴────────────────────────────────────────────────────────────┐
│ Host: Rust native host service  +  Java 系统服务  +  Manager API        │
│       ↑ Personal Context 采集屏幕/使用信号                               │
│       ↑ 新 intelligence role + screen-context AppOp 做准入               │
└────────────────────────────────────────────────────────────────────────┘
```

**几个必须记住的硬指标（面试问细节就靠这些）：**
- **protected-VM 标志默认开启**，且在 release config 里打开——**不是实验特性**。
- **~300 MB RAM / ~16 GB 加密存储**——够放端侧模型 + 个人数据语料。
- **存储按用户解锁**：用户解锁设备时把 key 递进去，形态与 **CE（credential-encrypted）存储**一致 → 与第七篇 §5、第十一篇 §7 的 FBE/vold 是同一套心智模型。
- **多租户 + 单通道**：AI provider 以 tenant 身份跑在 VM 内，host App 只能通过 **受控 vsock 命名通道** 找到指定 tenant。
- **实现语言**：host 侧 **Rust native service + Java system service + Manager API** 三件套。

### 「AISeal 不是新沙箱技术」——这句务必答出来

AVF/Microdroid 早就有（Android 13 起）。**A17 新增的是 AISeal 框架本身**（A16 没有），它把这套一直"备而不用"的虚拟化基座，**第一次指向了真实产品面**。

面试时说出这层区分，等于告诉面试官你读的是源码 diff 而不是发布会 PPT。

### 保护边界的精确表述（防止吹过头被反杀）

**受保护的：** 落在加密存储里的个人数据 + 在 VM 内运行的 AI 计算，**对 host OS 不可见，包括已被攻破的 host 内核**。背书是 **pKVM 通过 SESIP Level 5 认证**（2026，首个达此等级的大规模消费电子软件安全系统；SESIP 已被欧洲采纳为 EN-17927，L5 要求覆盖 ISO 15408 的 AVA_VAN.5 渗透测试，即可抵御"高技能、资源充足、可能有内部知识"的攻击者）。

**不受保护的（必须主动补充，否则面试官会追问）：**
1. **数据进 VM 的路径要经过 host**——采集侧（Personal Context 抓屏幕信号）仍在 host 上跑，这一段不在 pVM 保护内。
2. **准入控制在 host**——intelligence role + screen-context AppOp 是 host 侧的策略检查，可被绕过的话数据一样会被喂进去。
3. **侧信道**——hypervisor 隔离不天然防 cache/时序侧信道。

> 一句话总结话术：**"AISeal 保护的是数据 at-rest 和 in-compute，不保护 in-transit-to-VM 的采集链路；它把信任根从整个 Linux 内核收缩到了一万行 hypervisor，但没有把它收缩到零。"**

### 高频追问链 🔗
- Q：AISeal 和 AICore / ODP 什么关系？→ A：第八篇 §8 讲过 AICore（Google 专有，Gemini Nano 运行时）和 ODP（AOSP 开放）。**AISeal 是"房子"，AICore/tenant 模型是"住户"**；AISeal 提供隔离容器与多租户接入规范。
- Q：为什么不用 TEE 跑大模型？→ A：见 §5——TEE 内存以 MB 计、编程模型受限、部署要厂商签名；跑 GB 级模型 + AppSearch 数据库完全不现实。**这正是 pKVM 相对 TEE 的核心价值主张。**
- Q：常驻 300MB 会不会被 Memory Limiter 干掉？→ A：见 §6，AISeal 是系统组件，走独立的内存策略；而 crosvm 进程的内存计账问题恰恰是 pVM 落地的工程难点。

---

## §5 pKVM vs TrustZone TEE：怎么选？（承接第十一篇）

### 面试题 5.1
> 有了 TEE 为什么还要 pKVM？两者是替代关系吗？给三个具体场景说明该用哪个。

### 答案解析：不是替代，是分层

**TEE 守"小而恒定的秘密"，pKVM 跑"大而动态的计算"。**

| 场景 | 选谁 | 为什么 |
|---|---|---|
| 存设备根密钥、做 Key Attestation、Gatekeeper 验密码 | **TEE** | 秘密极小、生命周期=设备寿命、需要硬件信任根与 eFuse 绑定；且必须在最早启动阶段可用 |
| Widevine L1 解密 + secure buffer 视频通路 | **TEE 为主**（历史包袱）；**AVF 是新出路** | OEMCrypto 传统在 TEE；但 AVF 文档明确把「DRM/媒体处理」列为 pVM 用例——把解密搬进 pVM 可避免为每个 SoC 定制 TA |
| 端侧个人数据 AI 推理（AISeal） | **pKVM** | 需要 GB 级内存 + 完整 Android API + 动态部署，TEE 全不满足 |
| 隔离编译（odrefresh / DEX→OAT） | **pKVM** | 编译器攻击面大，放 pVM 里"炸了也炸在里面"；AVF 官方首个落地用例就是 isolated compilation |

### 威胁模型对照（记住这张表就够答大部分对比题）

```
                        谁在 TCB 里
TEE 方案:   BL31 + Trusty OS + 所有 TA + SoC 厂商固件      (代码量大、闭源多、CVE 面广)
pKVM 方案:  pKVM (~1 万行, 开源, 形式化验证在做) + pvmfw   (代码量小、可审计、SESIP L5)
```

**pKVM 的杀手锏是 TCB 小且开源**。TEE 的问题在于：TA 越加越多，每个 TA 都在同一个安全世界里，**一个 TA 被攻破往往等于整个安全世界失守**（历史上 QSEE/TrustedCore 多次 CVE 均是此模式）。pVM 之间则**互不信任、互相隔离**——多加一个 pVM 不扩大他人攻击面。

**pKVM 的短板**（一定要主动说，否则显得只会背优点）：
1. **无法参与早期启动**——EL3/TEE 在 bootloader 阶段就在，pKVM 要等内核起来。所以 Verified Boot、Gatekeeper 这些**必须留在 TEE**。
2. **无独立安全外设通道**——TEE 能独占触摸屏/指纹（Protected Confirmation / ConfirmationUI），pVM 目前拿不到。
3. **仅 ARM64**。
4. **内存/功耗成本高**——常驻 pVM 吃几百 MB。

### 高频追问链 🔗
- Q：pKVM 能替代 TEE 做指纹匹配吗？→ A：算法层面可以，但**传感器数据通路**要么走 host（不可信）要么要 SMMU 把指纹控制器直通给 pVM——目前生态没铺开，仍在 TEE。
- Q：SESIP L5 到底意味着什么？→ A：SESIP 共五级，L5 最高，要求覆盖 Common Criteria 的 **AVA_VAN.5**（最高等级脆弱性分析+渗透测试）。意义在于**可复用的安全证据**：厂商拿同一套评估结果应对不同市场法规，降低重复认证成本。Google 的潜台词是"多数现行 TEE 还没做到同等级正式认证"。

---

## §6 A17 Memory Limiter × ProfilingManager 异常检测（经典内存/卡顿面试的 2026 版）

### 面试题 6.1
> Android 17 的 Memory Limiter 是什么？它和 LMKD、cgroup memcg、ART GC 是什么关系？App 该怎么适配？

### 答案解析：三条独立的"杀"路径要分清

这是 2026 年内存题的**核心分辨点**，很多人会混为一谈：

| 机制 | 触发条件 | 谁执行 | 现象 |
|---|---|---|---|
| **LMKD**（传统） | **全局**内存压力（PSI）达阈值 | `lmkd` 用户态守护 | 按 oom_adj 从低优先级开始杀，**你可能很无辜** |
| **cgroup memcg OOM** | **进程组**超 `memory.max` | 内核 OOM killer | 组内挑分数最高的杀 |
| **A17 Memory Limiter**（新） | **单应用**超系统设定的内存上限 | 系统框架层 | **精准杀你**，退出信息明确标注 `Memory Limiter` |

> **答题要点：Memory Limiter 把"公地悲剧式的连坐"变成了"谁超标杀谁"。** 从 LMKD 的全局压力驱动，转向 per-app 配额驱动。这是治理逻辑的根本变化。

**关联源码基础（A14 基线，A17 在此之上加配额层）：**
- LMKD：`system/memory/lmkd/lmkd.cpp`，PSI 监听 `init_psi_monitors()`
- oom_adj 计算：`services/core/java/com/android/server/am/OomAdjuster.java`
- 内存回调分发：`ActivityManagerService` → `ApplicationThread.scheduleTrimMemory()`
- 应用侧：`ComponentCallbacks2.onTrimMemory()`

**适配三板斧：**

1. **onTrimMemory 只剩两个常量有意义**。从 **Android 14 起系统不再发送其他 legacy 常量**，只需处理：
   - `TRIM_MEMORY_UI_HIDDEN`：UI 不可见 → 放 Bitmap 缓存、视频 buffer、动画资源
   - `TRIM_MEMORY_BACKGROUND`：进程已进后台、是候选被杀对象 → 激进释放"能低成本重建"的资源，**换更久的 cached 存活 = 减少冷启动**

```kotlin
class App : Application(), ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // 释放与界面强绑定的大对象
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            // 更激进：可重建的全局 cache、临时数据
        }
    }
}
```

2. **Bitmap 是大头**：不需要透明通道就用 `RGB_565`（内存是 `ARGB_8888` 的一半）；用 Glide/Coil 的复用池。
3. **别等系统决定回收什么**——主动在合适时机释放，Application 层统管全局缓存。

### 面试题 6.2
> `TRIGGER_TYPE_ANOMALY` 能检测「binder 调用过多」，系统是怎么知道你 binder spam 的？

### 答案解析（这题能拉开差距）

A17 的 ProfilingManager 触发器全家桶：

| 触发器 | 时机 | 产物 |
|---|---|---|
| `TRIGGER_TYPE_COLD_START` | 冷启动期间 | 调用栈采样 **+** 系统 trace |
| `TRIGGER_TYPE_OOM` | 抛 `OutOfMemoryError` **的精确时刻** | Java heap dump |
| `TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE` | 因 CPU 异常被杀 | 调用栈采样 |
| `TRIGGER_TYPE_ANOMALY`（新） | 设备端异常检测服务判定异常 | binder spam→事务栈采样；内存异常→heap dump |

**关键机制：回调发生在系统采取任何强制措施之前。** 你在被杀/被限流/被降级**之前**拿到第一现场 profile。

**binder spam 是怎么被统计出来的？** 数据源在驱动和 native 层早就有：
- 驱动侧统计：`drivers/android/binder.c` 的 `binder_stats` / `binder_transaction_log`，`/sys/kernel/debug/binder/stats`、`transaction_log`（binderfs 下为 `/dev/binderfs/binder_logs/`）
- native 侧：`IPCThreadState::transact()` 是所有同步事务必经之路
- 框架侧：`Binder.setTracingEnabled()`、`BinderCallsStats`（`frameworks/base/core/java/com/android/internal/os/BinderCallsStats.java`）——这个类**早就在按 uid/接口统计调用次数与耗时**，`dumpsys binder_calls_stats` 可看

> A17 的异常检测服务本质是**把 `BinderCallsStats` 这类既有计数器接到了一个设备端判定器上**，超过基线就触发 profiling。答出 `BinderCallsStats` + `binder_transaction_log` 这两个名字，基本就赢了。

**结果获取姿势**：OOM 触发的 heap dump 是在 **crash 时刻**采集的，但要 **App 下次启动并注册 `registerForAllProfilingResults()` 回调后**才交付。这个"跨进程生命周期交付"是个容易踩的坑。

分析工具：Perfetto UI 的 **Heap Dump Explorer**——看分配层级、retained size、到 GC root 的最短路径、flamegraph 找大对象。

### 与 ART 分代 GC 的协同（串起第九篇 §3、第八篇 §2）
- A14 引入 **CMC GC**（userfaultfd 并发压缩）
- A17 在 CMC 之上加 **young/old 分代**，经 **art APEX Mainline 热更新** 下发
- GC 更轻更频 → 减少"卡一下"的体感 → 与 Memory Limiter 配合：**GC 及时把可回收内存还回去，才不至于撞上配额红线**

### 易错点 ⚠️
1. **把 Memory Limiter 说成 cgroup memory.max**——不是同一层，Memory Limiter 是框架层 per-app 配额策略。
2. **以为 onTrimMemory 还会收到 `TRIM_MEMORY_RUNNING_*`**——A14 起系统已不再发送。
3. **以为 `TRIGGER_TYPE_OOM` 的 dump 当场就能拿到**——要下次启动注册回调才交付。

### 高频追问链 🔗
- Q：ProfilingManager 是哪个版本引入的？→ A：**Android 15** 引入（让 App 在真实用户设备上程序化采集 Perfetto profile），A17 新增事件驱动触发器。
- Q：这会取代 Matrix/BlockCanary 吗？→ A：这是**监控能力的底层下沉**——过去只有大厂 APM 自研 + hook framework 才能做到，现在是系统 API。国内 APM 厂商大概率会普遍接入，但 App 内业务维度的归因仍需自研层。

---

## §7 【真缺口补全】Connectivity eBPF：网络策略是怎么在内核里执行的

### 面试题 7.1
> `NetworkPolicyManagerService` 里的"后台限流""省流量模式""VPN lockdown"，最终是怎么落到内核执行的？iptables 还在用吗？

### 答案解析：iptables 基本退场，eBPF 上位

**演进：** Android 9 之前靠 `netd` 疯狂拼 `iptables` 规则（xt_owner/xt_quota），规则数一多性能崩、原子性差。Android 10 起大规模改用 **eBPF**，Android 11+ 基本完成迁移，Android 12 起 Connectivity 变为 **Mainline 模块**（`com.android.tethering` APEX）。

**全链路：**

```
[Framework]  NetworkPolicyManagerService  (省流量/后台限制/计费策略)
   │  frameworks/base/services/core/java/com/android/server/net/NetworkPolicyManagerService.java
   │  ↓ Binder
[Mainline]   ConnectivityService / BpfNetMaps
   │  packages/modules/Connectivity/framework/src/android/net/
   │  packages/modules/Connectivity/service/src/com/android/server/BpfNetMaps.java   ← 直接写 BPF map
   │  ↓ JNI
[Native]     netd / libnetd_updatable / TrafficController
   │  system/netd/server/
   │  packages/modules/Connectivity/service/native/TrafficController.cpp
   │  ↓ bpf(2) 系统调用
[Kernel]     eBPF 程序挂在 cgroup hook
             packages/modules/Connectivity/bpf_progs/netd.c        ← 核心 BPF 程序
             packages/modules/Connectivity/bpf_progs/clatd.c       ← 464XLAT
             packages/modules/Connectivity/bpf_progs/offload.c     ← tethering 硬件卸载
```

**几个必须知道的名字：**

| 组件 | 作用 |
|---|---|
| **bpfloader** | 开机时由 init 拉起，把 `.o` BPF 程序 load 并 pin 到 `/sys/fs/bpf/`。`system/bpf/bpfloader/` |
| **cgroup_skb/ingress|egress** | 挂在 cgroup v2 上的 hook，**每个 socket 收发包都过一遍**，据此按 uid 放行/丢弃 |
| **BPF maps** | `uid_owner_map`（谁被限）、`cookie_tag_map`（socket→tag）、`stats_map_A/B`（双缓冲统计） |
| **`bpf_get_socket_uid()`** | BPF 侧拿到 socket 归属 uid，这是"按应用限速/计费"的地基 |

**为什么用双 stats map（A/B）？** 统计要边写边读，直接读会与内核写竞争。所以做**双缓冲切换**：用户态读 A 时内核写 B，读完切换。这是个很妙的无锁设计，面试问「流量统计怎么保证一致性」就答这个。

**A17 相关：** 第十篇提到的 **`ACCESS_LOCAL_NETWORK` 权限**（本地网络访问需声明）、第十一篇提到的 **跨资料环回流量默认阻断**，执行点都在这里——都是往 `uid_owner_map` 里加规则位，由 `netd.c` 的 BPF 程序在 cgroup hook 上判定。

**调试命令（面试实操题很爱问）：**
```bash
adb shell dumpsys connectivity trafficcontroller   # 看 BPF map 内容
adb shell ls /sys/fs/bpf/net_shared/               # 看 pin 住的 map/prog
adb shell dumpsys netpolicy                        # 看策略层
adb shell cat /proc/net/xt_qtaguid/stats           # 老机器；新机器已废弃
```

### 易错点 ⚠️
1. **说流量统计靠 `/proc/net/xt_qtaguid`**——那是 Android 9 之前的老路，已被 eBPF stats map 取代。
2. **以为 eBPF 程序在 APEX 里随模块更新**——BPF 程序有**内核 ABI 依赖**，bpfloader 有版本兼容矩阵，不是随便热更的。
3. **把 cgroup hook 和 XDP 混淆**——Android 用的是 `cgroup_skb`（socket 层，能拿 uid），不是 XDP（网卡驱动层，太早拿不到 uid）。

### 高频追问链 🔗
- Q：VPN 是怎么实现的？→ A：`Vpn.java` 建 tun 设备 + 路由，配合 BPF 规则做 lockdown（禁止绕过 VPN 的流量）。
- Q：eBPF 相比 iptables 的核心优势？→ A：①规则复杂度从 O(n) 链式匹配变成 O(1) map 查找 ②map 更新原子、不用重建规则集 ③可编程性强，统计与策略在同一程序里完成。

---

## §8 【真缺口补全】Ravenwood：AOSP 测试体系与 host 侧单测

### 面试题 8.1
> AOSP 里 CTS / VTS / GTS / MTS / CTS-V 各自测什么？Ravenwood 又解决什么问题？

### 答案解析

**先把测试套件矩阵背清楚**（这是系统岗必考的送分题，答错很掉分）：

| 套件 | 全称 | 测什么 | 谁必须过 |
|---|---|---|---|
| **CTS** | Compatibility Test Suite | **Java API 行为兼容性**（应用视角） | 所有想装 GMS 的设备 |
| **VTS** | Vendor Test Suite | **HAL / 内核接口**（Treble 视角），HIDL/AIDL 接口一致性、GKI KMI | Treble 设备 |
| **GTS** | Google Test Suite | GMS 应用相关行为（**闭源**） | GMS 设备 |
| **MTS** | Mainline Test Suite | **Mainline 模块（APEX）** 的兼容性 | 带 Mainline 的设备 |
| **CTS-V** | CTS Verifier | 需**人工操作**的项（传感器、摄像头、NFC） | 同 CTS |
| **STS** | Security Test Suite | 安全补丁是否真的打上 | 声明 SPL 的设备 |

跑测统一入口：`atest`（`tools/asuite/atest/`），底层是 **Tradefed**（`tools/tradefederation/`）。

### Ravenwood：把 framework 单测搬到 host JVM 上跑

**痛点：** 改一行 `ActivityManagerService` 的代码，要验证就得 **整编 → 刷机/起模拟器 → 跑 instrumentation test**，一轮十几分钟起步。对 framework 开发者而言，这是最大的效率杀手。

**Robolectric 为什么不够？** 它是第三方的、用 shadow 类模拟 Android，**与 AOSP 真实实现会漂移**，且不在 AOSP 构建体系内、系统开发者不能直接用来测 `services.jar` 内部类。

**Ravenwood 的答案：在 host JVM 上直接跑 AOSP 真实的 framework 代码。**

- 路径：`frameworks/base/ravenwood/`
- 思路：为在 host 上跑不了的部分（JNI、Binder 驱动、native 依赖）提供 **stub / 重定向**，其余 **直接用 AOSP 真身**（不是 shadow）。
- 用法：测试类加 `@RunWith(AndroidJUnit4.class)` + `RavenwoodRule`，Android.bp 里用 `android_ravenwood_test` 模块类型。
- 收益：**秒级反馈**，可在 CI 上大规模并行，无需设备。

```
                 Robolectric              Ravenwood
被测代码          shadow 替身              AOSP 真实实现
维护方            第三方(Google 收编)       AOSP 树内
适用对象          App 开发者                framework/系统开发者
运行环境          host JVM                 host JVM
```

**定位要说准：** Ravenwood **不替代** device test。它覆盖的是「纯逻辑、少 native 依赖」的部分（数据结构、状态机、策略计算，如 `OomAdjuster` 的打分逻辑）。凡是涉及真实 Binder、真实 SurfaceFlinger、真实驱动的，仍必须上设备。

### 配套：系统开发者的验证工具箱

| 需求 | 工具 |
|---|---|
| 单模块快编快推 | `m <module> && adb sync` / `adb install` |
| 改 framework 后免整编 | `m framework-minus-apex && adb sync system` + `adb shell stop/start` |
| 查系统服务状态 | `dumpsys <service>`；服务列表 `service list` |
| 性能/卡顿 | Perfetto（第二篇 §10、第四篇 §11） |
| 兼容性变更调试 | `am compat enable/disable <CHANGE_ID> <pkg>`（第十一篇 §1） |
| VM/pVM 调试 | `adb shell ps -A \| grep crosvm`；`vm` 命令行工具 |

### 易错点 ⚠️
1. **把 CTS 和 VTS 说反**——CTS 面向 App API 兼容，VTS 面向 vendor HAL/内核接口。
2. **说 Ravenwood 是 Robolectric 的分支**——不是，是独立方案；Google 确实也收编维护 Robolectric，但两者定位不同。
3. **以为 Ravenwood 能测 UI**——不能，没有真实 WindowManager/SurfaceFlinger。

---

## 📌 易错点速记（本篇 15 条）

1. pKVM 靠 **stage-2 页表所有权**，不是内存加密。
2. pKVM 在 **EL2 Normal World**，不是 EL3。EL3 是 Secure Monitor。
3. pKVM 下 **host 内核是被降级的 VM**，权限低于 hypervisor。
4. pKVM 必须用 **nVHE**（host 在 EL1），VHE 模式下 host==hypervisor 不成立隔离。
5. 只讲 CPU 隔离不讲 **SMMU/DMA 隔离** = 答案不完整。
6. **Microdroid ≠ pVM**：Microdroid 也能跑在非保护 VM 里（`--protected-vm` 才是开关）。
7. pVM **不能自带内核**，映像须 Google/厂商签名。
8. VM 停止时内存 **先 scrub 再归还** host。
9. pVM 的 vCPU 就是 **host 上的普通 POSIX 线程**，由 host 调度器管。
10. 跨 VM 用 **RPC Binder over vsock**，不是 `/dev/binder`；**`getCallingUid()` 跨 VM 不可信**。
11. **AISeal 是 A17 新增的框架**，AVF/Microdroid 基座是旧的——别说成"A17 新发明了 pVM"。
12. **PCC（进程级）≠ AISeal（hypervisor 级）≠ Private AI Compute（云侧 TEE）**。
13. **Memory Limiter（per-app 配额）≠ LMKD（全局压力）≠ cgroup OOM（组内）**。
14. `onTrimMemory` 从 **A14 起只发 `UI_HIDDEN` 和 `BACKGROUND`** 两个常量。
15. Android 流量统计早已从 `xt_qtaguid` 迁到 **eBPF stats map（双缓冲 A/B）**。

---

## 🔁 高频追问链速查

```
pKVM 怎么隔离?
  → stage-2 页表 + 内存捐赠
    → 那 DMA 呢? → SMMU 由 pKVM 独占
      → SoC 厂商要做什么? → 满足 pKVM SMMU 准入要求，否则无机密性可言
        → host 攻破后能做什么? → 只能拒绝服务(不给资源/杀 crosvm)，读不到内存
          → 那可用性呢? → CIA 三要素里 pKVM 只保证 C 和 I，A 由 host 掌握 ★

AISeal 保护了什么?
  → 加密存储的数据 + VM 内的推理计算
    → 没保护什么? → host 侧采集链路 / 准入策略 / 侧信道
      → 数据怎么进去的? → Personal Context 在 host 采集 → 走受控 vsock
        → 那不是白搭吗? → 不是：把"永久留存+被反复分析"的风险面收进了 VM ★

Memory Limiter 杀了我，怎么定位?
  → registerForAllProfilingResults + TRIGGER_TYPE_ANOMALY/OOM
    → 什么时候拿到 dump? → 下次启动
      → 用什么分析? → Perfetto Heap Dump Explorer，看 retained size + GC root 最短路径
        → 怎么预防? → onTrimMemory 两个常量 + RGB_565 + 全局缓存 Application 层统管 ★
```

---

## 📚 延伸阅读

**官方（AOSP）**
- AVF 概览：`source.android.com/docs/core/virtualization`
- AVF 架构（含 crosvm 内存布局表）：`/virtualization/architecture`
- AVF 安全模型（CIA 三要素、分层防御）：`/virtualization/security`
- Microdroid：`/virtualization/microdroid`
- A17 行为变更 & 功能：`developer.android.com/about/versions/17/`

**源码入口（建议按此顺序读）**
1. `packages/modules/Virtualization/` —— 整个 AVF 用户态（Rust 为主）
2. `arch/arm64/kvm/hyp/nvhe/mem_protect.c` —— pKVM 的心脏
3. `external/crosvm/src/` —— VMM 主循环
4. `frameworks/native/libs/binder/RpcSession.cpp` —— binderRPC
5. `packages/modules/Connectivity/bpf_progs/netd.c` —— 网络策略 BPF
6. `frameworks/base/ravenwood/` —— host 单测框架

**外部**
- pKVM SESIP L5 认证公告（2026）
- logcat.ai《Android 17 Built a Sealed Room for On-Device AI》—— AISeal 源码 diff 分析
- open-dice 规范：`external/open-dice/`

---

## 🗂 十二篇交叉索引

| # | 日期 | 篇名 | 主轴 | 专题数 |
|---|---|---|---|---|
| 1 | 07-23 | 主篇 | Framework 主线（Handler/Binder/AMS/WMS/View/ANR/Compose/HAL/MTK） | 16 |
| 2 | 07-23 | 热点拓展篇 | 盲区补全（Input/PMS/ART/SystemUI/折叠屏/SELinux/OTA/JNI/Perfetto） | 10 |
| 3 | 07-23 | 深挖篇 | 深水区（ART 对象头/CMC GC/Binder 驱动调试/Rust Binder/VSync/Camera/Audio/GKI） | 11 |
| 4 | 07-24 | 图形多媒体通信篇 | HWUI/SF/Gralloc/多刷新率/MediaCodec/Codec2/Thermal/Power/RIL/WiFi/BT | 12 |
| 5 | 07-27 | 系统基建与可观测性篇 | 16KB 页/ClassLoader/权限/Keystore2/AVB/Vold/logd/RRO/Doze | 11 |
| 6 | 07-28 | 端侧 AI 与 A17 演进篇 | NNAPI→NPU/LiteRT/CarService/Vulkan/ART 产物/virtual A-B | 10 |
| 7 | 07-29 | A17 新雷区 + 真缺口 | Lock-free MessageQueue/ART 分代 GC/hiddenapi/ProfilingManager/NFC/Media3/端侧 LLM | 8 |
| 8 | 07-30 | 渲染合成 + A17 安全内存 | SF RenderEngine/Codec2 vendor/Memory Limiter/DCL 加固/Keystore 限额/CarService/oat 布局 | 7 |
| 9 | 07-31 | 兼容性框架 × A17 跨设备 | platform_compat 引擎/letterbox/BAL/Bubbles/Handoff/Pointer Capture/SMS OTP/ECH/Strict SQL | 10 |
| 10 | 08-01 | 安全世界 TEE 篇 | Trusty/TIPC/Keystore2-KeyMint/Gatekeeper-Weaver/Attestation/Widevine/FBE/ION→DMA-BUF | 8 |
| **11** | **08-02** | **本篇（第十二篇）：pKVM 机密计算 × AISeal** | **pKVM/EL2 · AVF 全栈 · vsock-RPC Binder · AISeal · TEE 对比 · Memory Limiter · eBPF · Ravenwood** | **8** |
| | | | **累计** | **111** |

### 三个世界的完整拼图（十、十一、十二篇串读）

```
第 5/7/8 篇  ── 普通世界基建（权限/Keystore2 客户端/hiddenapi/Memory Limiter）
      ↓
第 11 篇     ── EL3 安全世界：Trusty TEE、KeyMint TA、Gatekeeper、Widevine OEMCrypto
      ↓
第 12 篇     ── EL2 机密计算：pKVM、pVM、Microdroid、AISeal
      ↓
   交汇点：binderRPC 同时服务于「跨 VM(vsock)」与「跨安全世界(TIPC)」
           DICE 同时用于「Key Attestation/RKP」与「pvmfw per-VM secret 派生」
           fs 校验思想贯穿 dm-verity(分区) → fs-verity(文件) → authfs(可写共享文件)
```

---

## ✅ 本篇自检清单

- [ ] 能画出 EL0/EL1/EL2/EL3 四层 + 普通/安全世界的完整图，并说清 pKVM 位置
- [ ] 能解释「内存捐赠」为什么让 host 内核物理上读不到 pVM 内存
- [ ] 能说出 nVHE 与 VHE 的区别及为什么 pKVM 必须 nVHE
- [ ] 能背出 AVF 五层链路：Java API → virtualizationservice → crosvm → pvmfw → Microdroid
- [ ] 能说清 RPC Binder 与内核 Binder 的 6 点差异，尤其 uid 不可信
- [ ] 能精确表述 AISeal 保护什么、不保护什么，并区分 PCC / Private AI Compute
- [ ] 能对比 pKVM 与 TEE 并主动说出 pKVM 的 4 个短板
- [ ] 能区分 Memory Limiter / LMKD / cgroup OOM 三条杀路径
- [ ] 能说出 binder spam 检测的数据来源（BinderCallsStats / binder_transaction_log）
- [ ] 能画出 NetworkPolicyManagerService → BpfNetMaps → TrafficController → netd.c 的链路
- [ ] 能说清 CTS/VTS/GTS/MTS 各测什么，Ravenwood 与 Robolectric 的定位差异

---

> **明日可轮换的真·未覆盖角度**：LiteRT NPU delegate 源码走读、CarService 电源状态机完整状态图、Codec2 vendor 组件调试实战、端侧 LLM 量化工程化、Protected Confirmation(ConfirmationUI)、StrongBox/SE 深水区、AVF 隔离编译(odrefresh in pVM)实战、Compose 编译器插件与 A17 Compose-First 演进。
