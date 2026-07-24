# Linux 内核 Rust 改写 Drivers 全景分析

> 基于 `flybigpig/linux` 仓库，内核版本 **Linux 7.2-rc3**
> 分析日期: 2026-07-23

---

## 一、总览：Rust 驱动版图

Linux 7.2-rc3 中，Rust 已经从"基础设施搭建"阶段进入"大规模驱动落地"阶段。以下是 `drivers/` 目录下所有 Rust 驱动的完整清单：

### 1.1 Rust 驱动清单

| 驱动 | 路径 | 代码行数 | Kconfig | 说明 |
|------|------|---------|---------|------|
| **Binder IPC** | `drivers/android/binder/` | **9,536** | `ANDROID_BINDER_IPC_RUST` | Google 2025 年重写，Android 核心 IPC |
| **Nova-Core** | `drivers/gpu/nova-core/` | **13,451** | `NOVA_CORE` | NVIDIA GPU 核心驱动 (GSP 架构) |
| **Nova (DRM)** | `drivers/gpu/drm/nova/` | 226 | `DRM_NOVA` | NVIDIA DRM 层 |
| **Tyr** | `drivers/gpu/drm/tyr/` | **2,167** | `DRM_TYR` | ARM Mali CSF GPU 驱动 |
| **DRM Panic QR** | `drivers/gpu/drm/drm_panic_qr.rs` | 1,016 | `DRM_PANIC_SCREEN_QR_CODE` | 内核 panic 二维码生成 |
| **RNull** | `drivers/block/rnull/` | 361 | `BLK_DEV_RUST_NULL` | Null 块设备 (测试/基准) |
| **cpufreq-dt** | `drivers/cpufreq/rcpufreq_dt.rs` | 222 | `CPUFREQ_DT_RUST` | 设备树 CPU 频率驱动 |
| **PWM TH1520** | `drivers/pwm/pwm_th1520.rs` | 366 | `PWM_TH1520` | T-HEAD TH1520 PWM 控制器 |
| **AX88796B PHY** | `drivers/net/phy/ax88796b_rust.rs` | 133 | `AX88796B_RUST_PHY` | ASIX 以太网 PHY |
| **QT2025 PHY** | `drivers/net/phy/qt2025.rs` | 110 | `AMCC_QT2025_PHY` | AMCC 光纤 PHY |
| **合计** | — | **~27,588** | — | — |

### 1.2 Rust 安全抽象层 (`rust/kernel/`)

除了驱动本身，内核还提供了庞大的 Rust 安全抽象层，总计 **~55,317 行**，覆盖：

```
rust/kernel/
├── 设备与驱动框架
│   ├── device.rs          # Device, DeviceContext, Core, Bound 状态机
│   ├── driver.rs          # Driver trait, Adapter 模式, Registration
│   ├── platform.rs        # 平台总线 (platform_driver/platform_device)
│   ├── pci.rs             # PCI 总线 (pci_driver, Bar, config_space)
│   ├── usb.rs             # USB 总线
│   ├── i2c.rs             # I2C 总线
│   ├── auxiliary.rs       # auxiliary 总线
│   ├── faux.rs            # faux 设备
│   └── soc.rs             # SoC 设备
├── DRM/GPU 抽象
│   ├── drm/               # DRM 框架 (device, driver, file, gem, gpuvm, ioctl)
│   └── gpu.rs             # GPU buddy allocator
├── 内存管理
│   ├── alloc/             # 内核分配器 (kbox, kvec, layout)
│   ├── page.rs            # struct page 封装
│   ├── mm.rs              # 地址空间管理
│   ├── dma.rs             # DMA 映射
│   ├── scatterlist.rs     # scatter-gather list
│   └── uaccess.rs         # 用户空间内存访问 (UserSlice)
├── 同步原语
│   ├── sync/
│   │   ├── arc.rs         # Arc (内核 refcount_t)
│   │   ├── lock/mutex.rs  # Mutex (kernel mutex)
│   │   ├── lock/spinlock.rs # SpinLock
│   │   ├── condvar.rs     # Condvar
│   │   ├── completion.rs  # Completion
│   │   ├── rcu.rs         # RCU 读取侧
│   │   ├── atomic/        # 原子操作
│   │   └── poll.rs        # Poll table
│   ├── workqueue.rs       # 工作队列
│   └── irq.rs             # 中断管理
├── 数据结构
│   ├── list.rs            # 双向链表 (含 ListArc)
│   ├── rbtree.rs          # 红黑树
│   ├── xarray.rs          # XArray
│   └── maple_tree.rs      # Maple Tree
├── 子系统抽象
│   ├── block/mq/          # 块设备多队列 (gen_disk, operations, tag_set)
│   ├── net/phy.rs         # PHY 设备 (phylib)
│   ├── cpufreq.rs         # CPU 频率策略
│   ├── pwm.rs             # PWM 框架
│   ├── firmware.rs        # 固件加载
│   ├── fs.rs              # 文件系统 (File, kiocb)
│   ├── debugfs.rs         # debugfs
│   ├── configfs.rs        # configfs
│   └── seq_file.rs        # seq_file
├── 基础设施
│   ├── error.rs           # 错误码 (Result, ENOENT, etc.)
│   ├── str.rs             # CStr, CString
│   ├── prelude.rs         # 公共导出
│   ├── module_param.rs    # 模块参数
│   └── init.rs            # pin-init 框架
└── 其他
    ├── clk.rs, regulator.rs, opp.rs  # 时钟/稳压器/OPP
    ├── of.rs, acpi.rs                 # DT/ACPI
    ├── io.rs                          # I/O 内存访问 (IoMem, register!)
    ├── task.rs, cred.rs               # 任务/凭证
    ├── time.rs                        # 时间 (hrtimer)
    ├── kunit.rs                       # KUnit 测试
    └── tracepoint.rs                  # Tracepoint
```

---

## 二、构建系统：Kconfig + Makefile 模式

### 2.1 Kconfig 模式

Rust 驱动统一通过 `depends on RUST` 控制。以 Binder 为例：

```kconfig
# drivers/android/Kconfig
config ANDROID_BINDER_IPC
    bool "Android Binder IPC Driver"
    depends on MMU
    depends on NET
    default n

config ANDROID_BINDER_IPC_RUST
    bool "Rust version of Android Binder IPC Driver"
    depends on RUST && MMU && !ANDROID_BINDER_IPC
    help
      This enables the Rust implementation of the Binder driver.
```

关键点：
- **C 版和 Rust 版互斥**：`!ANDROID_BINDER_IPC` 确保 Rust 版启用时 C 版被禁用
- `depends on RUST` 是所有 Rust 驱动的硬依赖
- 部分驱动还有额外依赖，如 Nova-Core 需要 `depends on 64BIT && PCI`

### 2.2 Makefile 模式

```makefile
# drivers/android/Makefile
obj-$(CONFIG_ANDROID_BINDER_IPC)      += binder.o binder_alloc.o binder_netlink.o
obj-$(CONFIG_ANDROID_BINDER_IPC_RUST) += binder/

# drivers/android/binder/Makefile
obj-$(CONFIG_ANDROID_BINDER_IPC_RUST) += rust_binder.o
rust_binder-y := \
    rust_binder_main.o     \
    rust_binderfs.o        \
    rust_binder_events.o
```

关键点：
- Rust 驱动的目标文件后缀是 `.o`（由 Rust 编译器生成），但源文件是 `.rs`
- 使用 `rust_binder-y` 变量列出编译单元（Makefile 会自动将 `.o` 映射到 `.rs`）
- 对于单文件驱动，直接 `obj-$(CONFIG_xxx) += module_name.o`

### 2.3 GPU 驱动的 Kconfig

```kconfig
# drivers/gpu/nova-core/Kconfig
config NOVA_CORE
    tristate "Nova Core GPU driver"
    depends on 64BIT
    depends on PCI
    depends on RUST
    depends on !CPU_BIG_ENDIAN
    select AUXILIARY_BUS
    select RUST_FW_LOADER_ABSTRACTIONS
    default n
    help
      Choose this if you want to build the Nova Core driver for Nvidia
      GPUs based on the GPU System Processor (GSP). Turing and later.

# drivers/gpu/drm/tyr/Kconfig
config DRM_TYR
    tristate "Tyr (Rust DRM support for ARM Mali CSF-based GPUs)"
    depends on DRM=y
    depends on RUST
    depends on ARM || ARM64 || COMPILE_TEST
    depends on !GENERIC_ATOMIC64
    depends on COMMON_CLK
    select RUST_DRM_GEM_SHMEM_HELPER
```

注意 `select RUST_FW_LOADER_ABSTRACTIONS` 和 `select RUST_DRM_GEM_SHMEM_HELPER` ——这些是 Rust 专用的框架依赖，对应 `rust/kernel/` 中的特定抽象模块。

---

## 三、驱动编写范式

### 3.1 模块定义：`module!` 宏

每个 Rust 驱动的入口都是 `module!` 宏：

```rust
// drivers/android/binder/rust_binder_main.rs
module! {
    type: BinderModule,
    name: "rust_binder",
    authors: ["Wedson Almeida Filho", "Alice Ryhl"],
    description: "Android Binder",
    license: "GPL",
}

// drivers/gpu/nova-core/nova_core.rs
module! {
    type: NovaCoreModule,
    name: "nova-core",
    authors: ["Danilo Krummrich"],
    description: "Nova Core GPU driver",
    license: "GPL v2",
    firmware: [],
}
```

`module!` 宏展开后会生成 C 兼容的 `init_module` / `cleanup_module` 符号，以及 `MODULE_LICENSE` 等元数据。

### 3.2 总线驱动 Trait 模式

Rust 内核驱动采用**面向 trait** 的设计。每种总线类型定义一个 `Driver` trait：

```rust
// 平台总线驱动 (来自 samples/rust/rust_driver_platform.rs)
impl platform::Driver for SampleDriver {
    type IdInfo = Info;
    type Data<'bound> = Self;
    const OF_ID_TABLE: Option<of::IdTable<Self::IdInfo>> = Some(&OF_TABLE);
    const ACPI_ID_TABLE: Option<acpi::IdTable<Self::IdInfo>> = Some(&ACPI_TABLE);

    fn probe<'bound>(
        pdev: &'bound platform::Device<Core<'_>>,
        info: Option<&'bound Self::IdInfo>,
    ) -> impl PinInit<Self, Error> + 'bound {
        // probe 逻辑
    }
}

kernel::module_platform_driver! {
    type: SampleDriver,
    name: "rust_driver_platform",
    ...
}
```

```rust
// PCI 驱动 (来自 samples/rust/rust_driver_pci.rs)
impl pci::Driver for SampleDriver {
    type IdInfo = TestIndex;
    type Data<'bound> = SampleDriverData<'bound>;
    const ID_TABLE: pci::IdTable<Self::IdInfo> = &PCI_TABLE;

    fn probe<'bound>(
        pdev: &'bound pci::Device<Core<'_>>,
        info: &'bound Self::IdInfo,
    ) -> impl PinInit<Self::Data<'bound>, Error> + 'bound {
        pdev.enable_device_mem()?;
        pdev.set_master();
        let bar = pdev.iomap_region_sized::<{ regs::END }>(0, c"rust_driver_pci")?;
        // ...
    }

    fn unbind<'bound>(_pdev: &'bound pci::Device<Core<'_>>, this: Pin<&Self::Data<'bound>>) {
        // 清理逻辑
    }
}
```

### 3.3 宏简化注册

内核提供了一系列宏来自动生成模块注册代码：

| 宏 | 用途 |
|----|------|
| `module!` | 定义模块元数据 (name, license, firmware 等) |
| `module_platform_driver!` | 注册平台总线驱动 |
| `module_pci_driver!` | 注册 PCI 驱动 |
| `of_device_table!` | 生成 Device Tree 匹配表 |
| `acpi_device_table!` | 生成 ACPI 匹配表 |
| `pci_device_table!` | 生成 PCI ID 匹配表 |
| `vtable` | 生成 vtable (用于回调接口) |
| `pin_data` | 标记需要 pin-init 的结构体 |
| `module_firmware!` | 声明模块所需固件 |

### 3.4 pin-init 框架

Rust 内核驱动大量使用 **pin-init** 模式，确保自引用结构在初始化后不被移动：

```rust
#[pin_data]
struct NovaCoreModule {
    #[pin]
    _driver: Registration<pci::Adapter<driver::NovaCoreDriver>>,
    _debugfs_guard: DebugfsRootGuard,
}

impl InPlaceModule for NovaCoreModule {
    fn init(module: &'static kernel::ThisModule) -> impl PinInit<Self, Error> {
        try_pin_init!(Self {
            _driver <- Registration::new(MODULE_NAME, module),
            _debugfs_guard: DebugfsRootGuard,
        })
    }
}
```

---

## 四、核心驱动深度分析

### 4.1 Rust Binder (`drivers/android/binder/`, 9,536 行)

**作者**: Wedson Almeida Filho, Alice Rydl (Google)
**Kconfig**: `ANDROID_BINDER_IPC_RUST`，与 C 版 `ANDROID_BINDER_IPC` 互斥

#### 模块结构

```
drivers/android/binder/
├── rust_binder_main.rs   (618 行)  # 模块入口, BinderModule, ioctl 分发
├── process.rs           (1776 行)  # Process 结构体, 进程级状态
├── thread.rs            (1688 行)  # Thread 结构体, 线程级状态和命令处理
├── node.rs              (1141 行)  # Binder Node (服务端对象)
├── page_range.rs         (775 行)  # 内存页范围管理 + Shrinker
├── allocation.rs         (612 行)  # binder_alloc 的 Rust 版
├── transaction.rs        (501 行)  # BC_TRANSACTION 处理
├── freeze.rs             (405 行)  # 进程冻结 (cached app)
├── range_alloc/
│   ├── mod.rs            (329 行)  # 范围分配器接口
│   ├── tree.rs           (488 行)  # 红黑树实现
│   └── array.rs          (281 行)  # 数组实现
├── deferred_close.rs     (204 行)  # 延迟关闭机制
├── defs.rs               (182 行)  # BC_*/BR_* 常量定义
├── context.rs            (175 行)  # Binder Context (context manager)
├── trace.rs              (105 行)  # tracepoint 封装
├── stats.rs               (89 行)  # 统计信息
├── error.rs               (89 行)  # 错误类型
└── node/wrapper.rs         (78 行)  # Node wrapper
```

#### 关键设计

1. **C/Rust 混合**: binderfs 仍用 C 实现，通过 FFI 声明调用：

```rust
mod binderfs {
    extern "C" {
        pub fn init_rust_binderfs() -> kernel::ffi::c_int;
        pub fn rust_binderfs_create_proc_file(
            nodp: *mut inode, pid: kernel::ffi::c_int,
        ) -> *mut dentry;
    }
}
```

2. **布局安全**: 通过 `RUST_BINDER_LAYOUT` 静态变量向 C 侧暴露 Rust 结构体的布局信息：

```rust
#[no_mangle]
static RUST_BINDER_LAYOUT: rust_binder_layout = rust_binder_layout {
    t: transaction::TRANSACTION_LAYOUT,
    p: process::PROCESS_LAYOUT,
    n: node::NODE_LAYOUT,
};
```

3. **范围分配器**: Rust 版用了两种实现（红黑树和数组），通过 trait 抽象，比 C 版的单链表更灵活。

### 4.2 Nova-Core NVIDIA GPU (`drivers/gpu/nova-core/`, 13,451 行)

**作者**: Danilo Krummrich (Red Hat / NVIDIA)
**Kconfig**: `NOVA_CORE`，支持 Turing 及之后的 NVIDIA GPU

这是目前**最复杂的 Rust 内核驱动**，采用完整的 HAL (Hardware Abstraction Layer) 架构。

#### 模块结构

```
drivers/gpu/nova-core/
├── nova_core.rs          (82 行)   # 模块入口
├── driver.rs             # PCI 驱动实现, probe/remove
├── gpu.rs                (347 行)  # GPU 枚举 (Architecture, Chipset)
├── gpu/hal.rs            # GPU HAL trait + 工厂函数
├── gpu/hal/tu102.rs      # Turing/Ampere/Ada 实现
├── gpu/hal/gh100.rs      # Hopper/Blackwell 实现
├── falcon.rs             (798 行)  # Falcon 微控制器框架
├── falcon/hal.rs         # Falcon HAL trait
├── falcon/hal/tu102.rs   # Turing Falcon
├── falcon/hal/ga102.rs   # Ampere Falcon
├── falcon/fsp.rs         # FSP (Firmware Security Processor)
├── gsp.rs                (191 行)  # GSP (GPU System Processor) 框架
├── gsp/cmdq.rs           (849 行)  # GSP 命令队列
├── gsp/cmdq/continuation.rs (307 行) # 命令续传
├── gsp/sequencer.rs      (399 行)  # 启动序列器
├── gsp/fw.rs            (1001 行)  # GSP 固件接口
├── gsp/fw/r570_144.rs    # 固件版本绑定
├── gsp/fw/r570_144/bindings.rs (1059 行) # 自动生成的固件命令结构
├── gsp/hal.rs            # GSP HAL trait
├── gsp/hal/tu102.rs      # Turing GSP
├── gsp/hal/gh100.rs      # Hopper GSP
├── fsp.rs                (320 行)  # FSP 通信
├── fsp/hal.rs            # FSP HAL
├── fsp/hal/gb100.rs      # Blackwell FSP
├── fsp/hal/gb202.rs      # Blackwell FSP
├── fsp/hal/gh100.rs      # Hopper FSP
├── fb.rs                 (272 行)  # Frame Buffer (显存)
├── fb/hal.rs             # FB HAL
├── fb/hal/tu102.rs       # Turing FB
├── fb/hal/ga100.rs       # Ampere FB
├── fb/hal/ga102.rs       # Ampere FB
├── fb/hal/gb100.rs       # Blackwell FB
├── fb/hal/gb202.rs       # Blackwell FB
├── fb/hal/gh100.rs       # Hopper FB
├── firmware.rs           (665 行)  # 固件加载框架
├── firmware/booter.rs    (463 行)  # Booter 固件
├── firmware/gsp.rs       (188 行)  # GSP 固件
├── firmware/fsp.rs       (128 行)  # FSP 固件
├── firmware/fwsec.rs     (416 行)  # FWSEC 固件
├── firmware/fwsec/bootloader.rs (350 行) # FWSEC bootloader
├── firmware/riscv.rs     # RISC-V 固件
├── regs.rs               (666 行)  # 寄存器定义
├── vbios.rs             (1004 行)  # VBIOS 解析
├── bitfield.rs           (329 行)  # 位域宏
├── num.rs                (297 行)  # 数值类型
├── mctp.rs               # MCTP 协议
├── sbuffer.rs            (224 行)  # 序列化缓冲区
└── bitfield.rs           (329 行)  # 位域工具宏
```

#### HAL 架构设计

Nova-Core 使用了标准的 HAL trait 模式来处理不同 GPU 代际的差异：

```rust
// drivers/gpu/nova-core/gpu/hal.rs
pub(crate) trait GpuHal {
    fn wait_gfw_boot_completion(&self, bar: Bar0<'_>) -> Result;
    fn dma_mask(&self) -> DmaMask;
    fn pci_config_mirror_range(&self) -> Range<u32>;
}

// 工厂函数：根据 chipset 选择对应的 HAL 实现
pub(super) fn gpu_hal(chipset: Chipset) -> &'static dyn GpuHal {
    match chipset.arch() {
        Architecture::Turing | Architecture::Ampere | Architecture::Ada => tu102::TU102_HAL,
        Architecture::Hopper | Architecture::BlackwellGB10x | Architecture::BlackwellGB20x => {
            gh100::GH100_HAL
        }
    }
}
```

Falcon HAL 更为复杂，使用了泛型 trait：

```rust
// drivers/gpu/nova-core/falcon/hal.rs
pub(crate) trait FalconHal<E: FalconEngine>: Send + Sync {
    fn select_core(&self, _falcon: &Falcon<E>, _bar: Bar0<'_>) -> Result { Ok(()) }
    fn signature_reg_fuse_version(
        &self, falcon: &Falcon<E>, bar: Bar0<'_>,
        engine_id_mask: u16, ucode_id: u8,
    ) -> Result<u32>;
    fn program_brom(&self, falcon: &Falcon<E>, bar: Bar0<'_>, params: &FalconBromParams);
    fn is_riscv_active(&self, bar: Bar0<'_>) -> bool;
    fn reset_wait_mem_scrubbing(&self, bar: Bar0<'_>) -> Result;
    // ...
}
```

#### 支持的 GPU 架构

| 架构 | 代号 | GPU HAL | Falcon HAL | FB HAL | GSP HAL | FSP HAL |
|------|------|---------|------------|--------|---------|---------|
| Turing | tu102 | ✅ tu102 | ✅ tu102 | ✅ tu102 | ✅ tu102 | — |
| Ampere (GA10x) | ga100/ga102 | ✅ tu102 | ✅ ga102 | ✅ ga100/ga102 | — | — |
| Ada Lovelace | — | ✅ tu102 | ✅ ga102 | — | — | — |
| Hopper | gh100 | ✅ gh100 | — | ✅ gh100 | ✅ gh100 | ✅ gh100 |
| Blackwell GB10x | gb100/gb202 | ✅ gh100 | — | ✅ gb100/gb202 | — | ✅ gb100/gb202 |

### 4.3 Tyr - ARM Mali GPU (`drivers/gpu/drm/tyr/`, 2,167 行)

**Kconfig**: `DRM_TYR`，针对 **ARM Mali CSF (Command Stream Frontend) 架构**的 GPU

```kconfig
config DRM_TYR
    tristate "Tyr (Rust DRM support for ARM Mali CSF-based GPUs)"
    depends on DRM=y
    depends on RUST
    depends on ARM || ARM64 || COMPILE_TEST
    select RUST_DRM_GEM_SHMEM_HELPER
    help
      Rust DRM driver for ARM Mali CSF-based GPUs.
      This driver is for Mali (or Immortalis) Valhall Gxxx GPUs.
```

模块结构：
```
drivers/gpu/drm/tyr/
├── tyr.rs      (22 行)   # 模块入口
├── driver.rs   (211 行)  # DRM 驱动注册
├── file.rs     (60 行)   # 文件操作
├── gem.rs      (43 行)   # GEM (Graphics Execution Manager) 对象
├── gpu.rs      (175 行)  # GPU 初始化
└── regs.rs     (1656 行) # 寄存器定义 (大量)
```

Tyr 是 Panfrost 的 Rust 替代品，但只支持 CSF 架构的 Mali GPU（Mali-G78 之后的 Valhall 架构），非 CSF 的旧 GPU 仍由 Panfrost (C) 处理。

### 4.4 其他驱动

#### cpufreq-dt Rust 版 (`drivers/cpufreq/rcpufreq_dt.rs`, 222 行)

C 版 `cpufreq-dt.c` 的 Rust 等价物，功能完全对等：

```rust
struct CPUFreqDTDevice {
    opp_table: opp::Table,
    freq_table: opp::FreqTable,
    _mask: CpumaskVar,
    _token: Option<opp::ConfigToken>,
    _clk: Clk,
}

#[vtable]
impl cpufreq::Driver for CPUFreqDTDriver {
    const NAME: &'static CStr = c"cpufreq-dt";
    const FLAGS: u16 = cpufreq::flags::NEED_INITIAL_FREQ_CHECK | cpufreq::flags::IS_COOLING_DEV;
    const BOOST_ENABLED: bool = true;
    type PData = Arc<CPUFreqDTDevice>;
    fn init(policy: &mut cpufreq::Policy) -> Result<Self::PData> { ... }
}
```

#### PWM TH1520 (`drivers/pwm/pwm_th1520.rs`, 366 行)

T-HEAD TH1520 SoC 的 PWM 控制器驱动，Samsung 贡献：

```rust
impl pwm::Chip for Th1520PwmChip {
    fn apply(&self, hw: &pwm::PwmHandle, waveform: &pwm::Waveform) -> Result {
        // 配置周期和占空比寄存器
    }
}
```

#### 网络 PHY 驱动

两个 Rust PHY 驱动，功能与 C 版等价：
- `ax88796b_rust.rs` (133 行) - ASIX 以太网 PHY，`AX88796B_RUST_PHY` 选项
- `qt2025.rs` (110 行) - AMCC QT2025 光纤 PHY，`AMCC_QT2025_PHY`

#### RNull (`drivers/block/rnull/`, 361 行)

Null 块设备的 Rust 版本，主要用于测试块设备栈的性能：

```rust
// drivers/block/rnull/rnull.rs
struct RNullDriver;

impl block::mq::Operations for RNullDriver {
    fn queue_rq(rq: &mut Request, is_last: bool) -> Result {
        rq.end_ok();
        Ok(())
    }
}
```

---

## 五、C → Rust 改写的关键技术模式

### 5.1 安全抽象边界

Rust 内核驱动的核心思路是：**在 `rust/kernel/` 中用 unsafe 封装 C 接口，在 `drivers/` 中用 safe Rust 编写驱动逻辑**。

```
┌─────────────────────────────────┐
│  drivers/ (safe Rust)           │  ← 驱动逻辑，无 unsafe
│  ┌───────────────────────────┐  │
│  │ impl platform::Driver     │  │
│  │ impl pci::Driver          │  │
│  │ impl cpufreq::Driver      │  │
│  └───────────────────────────┘  │
├─────────────────────────────────┤
│  rust/kernel/ (safe + unsafe)   │  ← 安全抽象层
│  ┌───────────────────────────┐  │
│  │ Device, Mutex, Arc        │  │  safe API
│  │ (内部 unsafe 调用 C)       │  │
│  └───────────────────────────┘  │
├─────────────────────────────────┤
│  rust/bindings/ (auto-generated)│  ← bindgen 生成
│  ┌───────────────────────────┐  │
│  │ bindings::platform_driver │  │  C 结构体映射
│  │ bindings::mutex_lock      │  │
│  └───────────────────────────┘  │
├─────────────────────────────────┤
│  C 内核 (drivers/..., kernel/...)│  ← 原始 C 代码
└─────────────────────────────────┘
```

### 5.2 Adapter 模式

总线抽象通过 Adapter 泛型结构体桥接 C 回调和 Rust trait：

```rust
// rust/kernel/platform.rs
pub struct Adapter<T: Driver>(T);

// SAFETY: 实现 C 侧的 platform_driver 注册
unsafe impl<T: Driver> driver::DriverLayout for Adapter<T> {
    type DriverType = bindings::platform_driver;
    type DriverData<'bound> = T::Data<'bound>;
    const DEVICE_DRIVER_OFFSET: usize = core::mem::offset_of!(Self::DriverType, driver);
}

unsafe impl<T: Driver> driver::RegistrationOps for Adapter<T> {
    unsafe fn register(...) { /* 调用 C 的 platform_driver_register */ }
    unsafe fn unregister(...) { /* 调用 C 的 platform_driver_unregister */ }
}
```

当内核探测到设备时，C 侧的 `platform_driver.probe` 回调会被 Adapter 拦截，转发到 Rust 的 `T::probe()`。

### 5.3 寄存器访问：`register!` 宏

Nova-Core PCI 示例展示了类型安全的寄存器访问：

```rust
register! {
    pub(super) TEST(u8) @ 0x0 {
        7:0 index => TestIndex;
    }

    pub(super) OFFSET(u32) @ 0x4 {
        31:0 offset;
    }

    pub(super) COUNT(u32) @ 0xC {
        31:0 count;
    }
}

// 使用
bar.write_reg(regs::TEST::zeroed().with_index(*index));
let count = bar.read(regs::COUNT).into();
```

这比 C 传统的 `ioread32(bar + 0xC)` 安全得多——编译器保证偏移量正确，位域不会越界。

### 5.4 错误处理

Rust 用 `Result<T, Error>` 替代 C 的负错误码：

```rust
// C:
// int ret = platform_driver_register(&drv);
// if (ret < 0) return ret;

// Rust:
Registration::new(MODULE_NAME, module)?;  // ? 自动传播错误
```

`rust/kernel/error.rs` 将 C 的 `ERR_PTR` / `IS_ERR` 机制封装为 Rust 的 `Result`。

---

## 六、当前状态与挑战

### 6.1 已进入主线但标记为 WIP 的驱动

多个 Rust 驱动在 Kconfig help 文本中明确标注 "work in progress"：

```
Nova Core:  "This driver is work in progress and may not be functional."
Tyr:        "This driver is work in progress and may not be functional."
```

### 6.2 C/Rust 共存策略

当前策略是**渐进式共存**，而非一步替换：

| 驱动类型 | 策略 |
|---------|------|
| Binder | C/Rust 互斥 (Kconfig 级别) |
| AX88796B PHY | C 版为默认，Rust 版可选替换 |
| cpufreq-dt | 独立的 Kconfig 选项，可共存 |
| GPU (NVIDIA) | Rust Nova 是全新驱动，取代闭源 Nouveau/NVIDIA |
| GPU (Mali) | Rust Tyr 只支持 CSF 架构，旧架构仍用 Panfrost |

### 6.3 尚未改写的重要驱动

以下关键驱动目前仍为 C 实现，尚未有 Rust 版本：
- **文件系统** (ext4, btrfs, etc.) — `rust/kernel/fs.rs` 有基础抽象但无完整文件系统驱动
- **网络协议栈** — 只有 PHY 层有 Rust 驱动
- **USB 控制器** (xHCI, etc.) — 有 USB 抽象 (`rust/kernel/usb.rs`) 但无控制器驱动
- **SCSI / NVMe** — 块设备层有抽象但无实际存储驱动
- **音频 (ALSA)** — 无 Rust 抽象
- **显示控制器** (除 GPU 外的 KMS) — 无独立 Rust 驱动

### 6.4 技术挑战

1. **编译器版本绑定**: Rust 内核代码绑定到特定的 `rustc` 版本，Linux 7.2-rc3 需要较新的 Rust 工具链
2. **交叉编译**: ARM/ARM64 交叉编译 Rust 驱动需要对应 target 的 core/std
3. **ABI 兼容**: Rust 结构体布局必须与 C 完全一致（`#[repr(C)]`），Nova-Core 的 HAL trait 依赖此保证
4. **异步支持**: 当前无 Rust async/await 支持，复杂异步逻辑仍用 workqueue + completion 模式
5. **KASAN/KCSAN**: Rust 的 unsafe 代码需要与内核的 sanitizer 协同工作

---

## 七、Sample 驱动参考

`samples/rust/` 目录提供了 15 个参考驱动，覆盖所有主要总线类型：

| 文件 | 说明 |
|------|------|
| `rust_minimal.rs` | 最小内核模块 |
| `rust_misc_device.rs` | miscdevice 字符设备 |
| `rust_driver_platform.rs` | 平台总线驱动 (含 OF + ACPI 匹配) |
| `rust_driver_pci.rs` | PCI 驱动 (含寄存器访问) |
| `rust_driver_usb.rs` | USB 驱动 |
| `rust_driver_i2c.rs` | I2C 驱动 |
| `rust_driver_auxiliary.rs` | auxiliary 总线驱动 |
| `rust_driver_faux.rs` | faux 设备驱动 |
| `rust_dma.rs` | DMA 操作示例 |
| `rust_configfs.rs` | configfs 示例 |
| `rust_debugfs.rs` | debugfs 示例 |
| `rust_i2c_client.rs` | I2C 客户端示例 |
| `rust_print_main.rs` | 打印/日志示例 |
| `rust_soc.rs` | SoC 设备示例 |

这些 sample 是编写新 Rust 驱动的最佳起点。

---

## 八、总结

Linux 7.2-rc3 中的 Rust 驱动生态已经相当成熟：

1. **驱动规模**: ~27,588 行 Rust 驱动代码 + ~55,317 行安全抽象层
2. **覆盖范围**: 从 Android IPC (Binder) 到 GPU (NVIDIA/Mali)，从 CPU 频率到网络 PHY
3. **架构模式**: 统一的 trait + Adapter + HAL 模式，类型安全的寄存器访问
4. **共存策略**: C/Rust 渐进式共存，通过 Kconfig 互斥或可选替换
5. **最大亮点**: Nova-Core (13,451 行) 展示了复杂 GPU 驱动的完整 Rust 实现可能性

Rust 在内核中的角色正在从"实验性支持"转变为"生产级选择"，特别是在新驱动的编写中。
