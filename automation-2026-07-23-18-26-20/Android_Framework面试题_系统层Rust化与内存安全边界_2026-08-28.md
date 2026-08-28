# Android Framework 面试题 · 系统层 Rust 化与内存安全边界（第 45 篇）

> 日期：2026-08-28（周五）｜ baseline：Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX，内核 GKI android14-6.1) ｜ 系列第 45 篇 / 累计约 **285 专题**
> 适用场景：秋招白热期（9–11 月）· 增量热点填补 + 系统联动连环追问模拟
> 说明：8/27 已把用户显式列出的全部领域（Handler/Looper、Binder、AMS/ATMS、WMS/View、App 启动、内存/卡顿/ANR、Jetpack/Compose、HAL/内核/drivers、MTK）做了「源码级深挖 + 跨版本演进」总复盘。本篇**不重复**，转而填补全系列长期隐含、却从未独立成篇的**真缺口——「系统层 Rust 化与内存安全边界」**。这是 2025–2026 上升最快的 Framework 面试增量题，且与本系列主线（kernel 驱动 / HAL / 安全 / AAOS）强耦合。每题统一结构：**问题 -> 答案解析(源码路径/模块名) -> A14->A16/A17 演进 -> 易错点 -> 考官高频连环追问 -> 延伸阅读**。文末附「当日高频八股回炉压轴」速查。

---

## 0. 当日热点锚定（2026-08-28）

- **Rust 化是 2026 最强增量热点之一**：Google Android 安全团队 2025 统计——Android 内存安全类漏洞占比**首次降到全部漏洞的两成以下**（2019 年曾高达 76%）。AOSP 累计 Rust 代码已达**数百万行量级**，新增原生代码里 Rust 占比已与 C++ 相当（部分口径称逾两成）。
- **政策驱动**：CISA 2026-01-01 内存安全路线图截止；Linux 内核 2025-12 宣布 Rust「不再实验性」。行业从「要不要用」转向「多快迁移」。
- **为什么面试官开始问**：Rust 已不是「应用层玩具」，它直接落在 **Keystore2、Binder Rust 后端、AVF/pKVM 虚拟机监控器（crosvm/microdroid）、蓝牙/Wi-Fi 关键模块、加密库、新内存分配器** 等 Framework 核心路径。考官用「你知不知道 system_server 旁边跑着 Rust 写的 keystore2 / compos」来区隔候选人深度。
- **本篇主线**：把 Rust 在 AOSP 的落地版图、与 Binder/HAL/内核驱动的交界、以及「Rust 不等于绝对安全」的边界，做成可面试口述的体系。最后用 10 道跨域连环追问做回炉压轴。

---

## 专题一：Rust 在 AOSP 的落地版图 —— system 层哪些组件已经是 Rust？

### 问题
"Android 系统层现在哪些核心组件是用 Rust 写的？它们分别落在 AOSP 哪些路径/模块？为什么 Google 要把这些组件从 C/C++ 迁到 Rust？A14 baseline 上这些组件在不在？"

### 答案解析 + 底层原理
Rust 在 AOSP 不是「应用层语言」，而是**系统服务与底层基础设施的直接替代**。A14 baseline 上已经落地的 Rust 系统组件（按模块路径）：

- **Keystore2（密钥库重写）**：`system/security/keystore2/`（`src/main.rs` 为入口二进制 `keystore2`）。Android 11 起用 Rust 重写 Keystore，替代旧 `keystore` daemon；通过 `android_security_keystore2` AIDL 与 `keystore2_key` 暴露接口，是 **Rust 实现 Binder 服务** 的标杆案例（详见专题三）。
- **Binder Rust 后端（binder crate）**：`frameworks/native/libs/binder/rust/`。Google 提供了 Rust 版 `binder` crate，允许用 Rust 实现 Binder 服务端/客户端，与 C++ `libbinder` 互通（同 `/dev/binder` 上下文）。Keystore2、`compos`（启动校验）、`virtualizationservice` 都用它。
- **AVF / pKVM 虚拟化栈（大量 Rust）**：`packages/modules/Virtualization/`。crosvm（VMM）、microdroid（pVM 内 Android）、pVM 固件、virtualizationservice 几乎全是 Rust——呼应本系列 8/2 的 EL2 机密计算篇。A14 起该模块即为主力。
- **加密与解析类**：`external/rust/crates/`（vendored crate 仓库，含 `ring`、`rustls` 相关绑定、JSON/PNG/AVIF/WebP 解析器）。Google 公开把 **PNG 解析器、JSON 解析器、Web 字体解析器** 从 C/C++ 迁到 Rust——这些是历史上内存破坏漏洞的高发区。
- **蓝牙 / Wi-Fi 关键模块（Android 15/16 增量）**：`packages/modules/Bluetooth/` 与 `packages/modules/Wifi/` 的部分协议栈/抽象层用 Rust 重写。Android 16（Berlin）把蓝牙协议栈部分高危缓冲区模块用 Rust 重新实现，编译期杜绝同类溢出（CVE-2025 蓝牙栈缓冲区溢出是催化剂）。
- **新内存分配器（Android 16 起）**：Native 层新的内存分配器以 Rust 实现随 Android 16 设备出货，数百万消费设备日常跑 Rust 生产代码。

**为什么迁**：内存安全漏洞占 Android 历史严重漏洞的 70%+；Rust 的所有权 + 借用检查在**编译期**消除整类 use-after-free / 越界 / data race，无需 GC、零运行时开销，性能对齐 C/C++。

### A14 -> A16/A17 演进
- A14：Keystore2、binder Rust、AVF 已是 Rust 主力；加密/解析 crate 持续扩充。
- A15/A16：蓝牙/Wi-Fi 关键模块 + 新内存分配器 Rust 化加速；新增原生代码 Rust 占比追上 C++。
- A17（CinnamonBun, 2026-06-16 stable）：在 A14 安全内存基础上叠加 **Memory Limiter（应用内存限额）、DCL 加固（dlopen 的 .so 必须只读）**——Rust 负责「写新代码不出内存洞」，A17 负责「兜住残留 C/C++ 与 FFI 边界」（见专题五）。

### 易错点
1. **Rust 不是「应用层语言」**：面试说「Rust 用在 Android 上」要立刻落到 `keystore2` / `binder` / `Virtualization` 这些系统模块，而不是 App 里的 Rust。
2. **A14 baseline 已有 Rust 系统组件**：别答「Rust 是新东西」，A14 的 keystore2/AVF 就是 Rust。
3. **Rust 与 C++ Binder 互通**：Rust Binder 服务跑在同一个 `/dev/binder` 上下文，client 可以是 Java/C++/Rust。

### 考官高频连环追问（标准答案）
- "Rust 代码就绝对安全吗？" -> 否。unsafe 块、FFI（C 互操作）、硬件操作仍可能出问题；Google 统计 Rust 代码内存安全漏洞密度约 0.2/百万行 vs C/C++ 约 1000/百万行（约千倍差距），且迄今未现内存安全类漏洞（唯一近似案例 CrabbyAVIF 线性溢出被 Scudo 多层防御拦截）。详见专题五。
- "Rust 会不会拖慢 system_server？" -> 不会。Rust 是 AOT 编译的原生代码，零运行时、无 GC，性能对齐 C/C++；且 keystore2 等是独立进程，不走 system_server 主线程。

### 延伸阅读
`system/security/keystore2/`、`frameworks/native/libs/binder/rust/`、`packages/modules/Virtualization/`；Google Android 安全博客《Memory safety》年度统计。

---

## 专题二：为什么是 Rust —— 所有权/借用检查 vs C/C++ 内存安全漏洞

### 问题
"Rust 凭什么在编译期消除内存安全漏洞？所有权、借用检查、生命周期具体怎么管住 use-after-free 和 data race？和 C/C++ 的手动管理、智能指针本质区别在哪？"

### 答案解析 + 底层原理
Rust 的内存安全来自**三条编译期强制规则**（borrow checker 在编译时 enforce，运行期零成本）：

1. **单一所有权（Ownership）**：每个值有且仅有一个 owner；owner 离开作用域自动 drop（RAII，无 GC）。转移（move）后旧绑定失效——从语言层杜绝「悬垂指针」。
2. **借用规则（Borrowing）**：要么**一个可变引用**，要么**任意多个不可变引用**，不能并存。编译期拒绝「可变 + 共享」重叠，从根上消除 data race 与迭代器失效。
3. **生命周期（Lifetimes）**：引用必须比被引用值活得短；`'a` 标注让编译器验证「不会返回指向局部变量的引用」。

对比 C/C++：
- C 手动 `malloc/free`，靠人；C++ `shared_ptr` 解决共享所有权但**不解决 data race**（仍需 `mutex`），且循环引用漏内存、原子开销。
- Rust 把「谁拥有、谁能改、活多久」编码进类型系统，**编译不通过就编译不出二进制**——漏洞在出厂前消失，而非运行时崩溃或靠 fuzzing 碰运气。

**量化证据**（Google/行业统计，2025–2026）：
- Android 内存安全类漏洞占比从 2019 年 76% 降到 2025 年**两成以下**。
- Rust 代码内存安全漏洞密度约 **0.2/百万行** vs C/C++ 约 **1000/百万行**（约 **1000x** 差距）。
- DORA 指标：同等规模变更，Rust 代码 review 被要求修改次数比 C++ 少约两成、停留审查时间少约四分之一、中大型变更被迫回退比例仅为 C++ 的四分之一。

### A14 -> A16/A17 演进
- 语言机制稳定跨版本；演进在**落地规模**与**生态**（更多 crate、更稳定 ABI、kernel crate 抽象层扩展）。
- A17 配合 Rust 化把安全重心从「少写漏洞」推进到「兜住遗留 C/C++ + FFI 边界」（Memory Limiter / DCL / Keystore 每应用密钥限额）。

### 易错点
1. **零成本 ≠ 零 unsafe**：`unsafe` 块、`FFI`、裸指针 `*` 仍可绕过检查；Rust 安全是「默认安全 + 显式 unsafe 边界审计」。
2. **borrow checker 不解决逻辑 bug**：只解决内存安全类（UAF/越界/数据竞争），不解决业务错误。
3. **data race 在单线程不是问题**：借用规则主要防跨线程共享可变状态；单线程内部可变用 `Cell`/`RefCell`（运行时检查）绕过编译期。

### 考官高频连环追问（标准答案）
- "Rust 没有 GC，怎么保证不内存泄漏？" -> 靠所有权 RAII 自动 drop；但**逻辑泄漏**（如忘了从全局集合移除）仍可能发生，只是不会有 UAF。
- "那 Rc/RefCell 是不是破坏规则？" -> `Rc` 是单线程引用计数（非原子），`RefCell` 把借用检查推迟到运行期；二者都不引入 UAF，只是把某些检查从编译期移到运行期并在违反时 panic，而非未定义行为。

### 延伸阅读
《The Rust Book》Ownership/Borrowing/Lifetimes 三章；Google《Memory safety》统计原文；Android 源码 `external/rust/crates/`。

---

## 专题三：Rust 与 Binder —— 用 Rust 实现 Binder 服务（Keystore2 案例）

### 问题
"Android 现在能用 Rust 写 Binder 服务吗？和 C++ 版 libbinder 比，线程模型、oneway、死亡通知怎么对应？Keystore2 作为 Rust Binder 服务，client 端（Java/C++）怎么调它？"

### 答案解析 + 底层原理
**能。** AOSP 提供 Rust 版 `binder` crate（`frameworks/native/libs/binder/rust/`），与 C++ `libbinder` 共享同一套 Binder 内核驱动（`/dev/binder`），因此 **Rust Binder 服务与 Java/C++ Binder 服务完全互通**。

实现机制（以 Keystore2 为例）：
- 服务端：Rust 侧用 `#[derive(BinderService)]` + `impl IKeystoreService for MyService`，`binder::add_service()` 把服务注册进 `servicemanager`（同一 `servicemanager`，与 C++ 一致）。
- 客户端：Java 端 `IKeystoreService.Stub.asInterface(ServiceManager.getService("keystore2"))` —— 与调 C++ 服务的写法**完全一致**，因为 AIDL 接口定义是语言无关的（AIDL 可生成 Java/C++/Rust 三套 stub）。
- 线程模型：Rust Binder 服务端线程池由 `binder::ProcessState` 管理，语义同 C++（默认上限 15，`BR_SPAWN_LOOPER` 动态扩容），`onTransact` 对应 Rust 的 trait 方法分发。
- `oneway` / `linkToDeath`：AIDL 里声明的 `oneway` 在 Rust 生成代码里同样非阻塞；`linkToDeath` 对应 `DeathRecipient` trait，对端死时回调。

**为什么用 Rust 写 Keystore**：密钥库是攻击者首选目标，任何 UAF/越界都可能泄露私钥；用 Rust 把整类内存洞在编译期消灭，比事后沙箱/审计更根本。

### A14 -> A16/A17 演进
- A14 起 Keystore2（Rust）+ binder Rust crate 已成熟可用。
- AIDL for Rust backend 持续完善，新 HAL/系统服务可优先选 Rust 实现（与「AIDL 取代 HIDL」进程叠加）。
- A17 端侧 AI 跑 Microdroid（AVF/pKVM）变多，Rust Binder 跨 VM 调用（`/dev/vbinder` 之类）成为更突出的身份/安全边界话题（呼应 8/2 EL2 篇与 8/27 Binder 跨 VM `getCallingUid` 不可信）。

### 易错点
1. **Rust Binder 不是新上下文**：它复用 `/dev/binder`，只是实现语言换成 Rust；别答成「Rust 走单独的 binder 设备」。
2. **AIDL 是语言中立的**：接口定义一份，生成 Java/C++/Rust stub——所以 client 无感服务端语言。
3. **互通但有 FFI 边界**：Rust 服务内部调用 C 库（如 boringssl）仍走 unsafe FFI，安全边界在 `unsafe` 块而非整个服务。

### 考官高频连环追问（标准答案）
- "Rust Binder 服务的线程池也是 15 吗？" -> 语义同 C++ `libbinder`（默认 15，`BR_SPAWN_LOOPER` 可扩）；具体上限由 `ProcessState` 配置，Rust crate 镜像 C++ 行为。
- "跨 VM 调 Rust Binder，getCallingUid 可信吗？" -> 不可信（同 8/27 专题二结论）。pKVM 下 guest/host 内核命名空间不同，`getCallingUid()` 返回 VM 内 UID，必须 attestation/签名校验身份。

### 延伸阅读
`frameworks/native/libs/binder/rust/src/`、`system/security/keystore2/src/`；AIDL 文档「Rust backend」。

---

## 专题四：Rust 与 HAL / 内核驱动 —— AIDL Rust backend、GKI 驱动与 unsafe 边界

### 问题
"Rust 能写 HAL 实现吗？Android 内核驱动（GKI）现在能用 Rust 写吗？和 MTK 这类 vendor C 驱动怎么共存？Rust 驱动真的安全吗？"

### 答案解析 + 底层原理
**HAL 侧（用户态）**：
- AIDL for Rust backend 让 HAL 实现可以用 Rust 写（`aidl_interface` 配 `rust: { … }`）。Google 新 HAL 倾向 AIDL（取代 HIDL），Rust backend 是「内存安全 HAL」的自然选择。
- 经典路径：`hardware/interfaces/` 与 `packages/modules/` 下部分 HAL 已实现 Rust 后端；加密、传感器、部分音频/媒体 HAL 是优先迁移对象。

**内核驱动侧（GKI / EL1）**：
- Linux 主线 2025-12 宣布 Rust「不再实验性」；AOSP GKI（android14-6.1 及更新）内核已携带 `rust/` 支持，提供 `kernel` crate（安全抽象层）+ `bindings`（bindgen 自动生成的 C FFI，unsafe）分层。
- 驱动开发者写到的代码几乎全是 safe Rust；C 继续作为内核心脏，Rust 当「高风险边缘的安全护栏」。中断/DMA/设备树等仍走 `bindings` 层 unsafe。
- **GKI 2.0 约束**：loadable vs built-in、KMI 稳定性——Rust 驱动也要过 KMI；vendor 驱动（如 MTK）仍以 C 为主，但可经 FFI 与 Rust 抽象共存。

**与 MTK/vendor 共存**：
- MTK 等 SoC 厂商的相机/显示/调制解调器驱动是重度 C/C++，短期不会 Rust 化；Rust 增量主要在 AOSP 通用模块（蓝牙/Wi-Fi/加密/虚拟化）。
- 边界靠 `unsafe` + FFI 桥接；这正是「Rust 安全边界」最该被追问的地方（见专题五）。

### A14 -> A16/A17 演进
- A14 GKI 内核已含 Rust 支持基础；A15/A16 内核 `kernel` crate 抽象层扩展（块设备/网络/设备树/DMA/中断）。
- A17/A18（含 Aluminium OS 的 x86 端口）vendor 模块须同时支持 ARM64 + x86_64 两套 KMI——Rust 驱动的架构无关性（一次编写多目标编译）反而成为优势。

### 易错点
1. **Rust 驱动 ≠ 全 Rust 内核**：C 仍是主心骨，Rust 是新增驱动的护栏；答「内核用 Rust 重写了」是错的。
2. **KMI 仍约束 Rust 驱动**：GKI 的 KMI 稳定性要求对 Rust 驱动同样适用。
3. **vendor C 驱动短期不迁**：MTK/高通相机调制解调器仍是 C/C++，别夸大 Rust 渗透度。

### 考官高频连环追问（标准答案）
- "Rust 驱动和 C 驱动性能差多少？" -> 控制微基准 C++ 快 5–10%，真实负载差距消失；Rust 安全抽象编译期内联，零运行时开销。
- "Rust 能消除内核 UAF 吗？" -> 在 safe Rust 范围内能；但 `bindings`/FFI/裸指针/汇编边界仍需人工审计 + Scudo/KASAN 等运行时防护兜底（见专题五）。

### 延伸阅读
AOSP 内核 `rust/` 与 `kernel` crate；`Documentation/rust/`；GKI 2.0 文档；MTK 平台差异参见本系列 8/17《HAL 与 Linux 内核驱动全链路》的 MTK 小节。

---

## 专题五：内存安全边界与面试 —— Rust 不是银弹，与 A17 安全内存呼应

### 问题
"既然 Rust 这么安全，Android 还有内存漏洞吗？Rust 代码本身会不会出内存安全问题？Google 怎么兜住 Rust 之外的 C/C++ 与 FFI 边界？这和 A17 的 Memory Limiter / DCL 加固什么关系？"

### 答案解析 + 底层原理
**Rust 不是银弹，三条真相**：
1. **unsafe 块与 FFI 仍危险**：Rust 服务内部调 C 库（boringssl、SQLite、硬件 HAL）走 unsafe FFI；Rust 的安全保证在 `unsafe` 边界外「暂停」。这正是攻击者仍会盯 FFI 边界的原因。
2. **Rust 也有过近似内存安全问题**：Google 披露 CrabbyAVIF 组件曾出现线性缓冲区溢出，常规情况可能形成无症状内存破坏；但因 Android 默认 **Scudo 强化分配器**（在内存块周边加防护页）让其表现为可观测崩溃，在测试阶段被拦截——说明「语言层安全 + 分配器多层防御」互相补强。
3. **逻辑漏洞不在 Rust 管辖内**：权限绕过、TOCTOU、逻辑越权——Rust 管不了。

**Google 怎么兜住残留风险（与 A17 呼应）**：
- **Scudo 强化分配器**：默认分配器，防护页 + 隔离，把静默内存破坏变成崩溃。
- **A17 Memory Limiter**：对应用设内存限额，限制单个 App 把内存安全缺陷放大成 DoS 的爆炸半径。
- **A17 DCL 加固（Dynamic Code Loading）**：`dlopen` 的 `.so` 必须只读，堵住「运行时加载可写 .so」这类代码注入面（与内存安全同源）。
- **Keystore 每应用密钥限额**、**跨资料环回流量默认阻断**、**隐式 URI 授权收紧**（见 8/30/7-31 兼容性框架篇与 7-30 渲染安全篇）——都是「Rust 写新代码 + 旧代码加护栏」的双轨策略。

**面试正解**：Rust 把「新增代码的 UAF/越界」在编译期消灭（密度降千倍），但**整体内存安全是分层防御**——Rust（写新代码）+ Scudo/KASAN（运行时）+ A17 Memory Limiter/DCL（缩小爆炸半径）+ FFI 审计（边界）。

### A14 -> A16/A17 演进
- A14 起 Scudo 为默认分配器；Rust 系统组件逐步替代高危 C/C++。
- A17 在 Rust 化基础上叠加 Memory Limiter / DCL / 密钥限额等「护栏层」，把存量 C/C++ 与 FFI 边界纳入管控。

### 易错点
1. **「Rust 零漏洞」是误导**：准确说法是「迄今未发现内存安全类漏洞」，且唯一近似案例被 Scudo 拦截；unsafe/FFI 仍是真实攻击面。
2. **Rust 与 A17 安全不是替代关系**：Rust 治新增代码，A17 护栏治存量 + 边界；双轨。
3. **不要神化**：borrow checker 不防逻辑 bug、不防权限问题、不防侧信道。

### 考官高频连环追问（标准答案）
- "如果让你在 Framework 选一个模块用 Rust 重写，选哪个为什么？" -> 优先选「历史高危 + 攻击价值高 + 解析/反序列化密集」的模块：如 Keystore（已做）、媒体/图像解析器（PNG/AVIF/WebP，已做）、蓝牙/Wi-Fi 协议栈（进行中）。标准：内存破坏后果严重 + 输入来自不可信源。
- "Scudo 和 Rust 安全哪个更关键？" -> 互补。Rust 在编译期消灭 safe 代码的整类漏洞；Scudo 兜住 Rust 之外的 C/C++ 与 FFI 边界，并把残留破坏变成可观测崩溃。少一个都不完整。

### 延伸阅读
Google《Memory safety》年度统计；AOSP `external/scudo/`；A17 安全变更见 7-30/7-31/8-01 系列安全篇。

---

## 专题六：当日高频八股回炉压轴（跨域连环追问速查）

> 以下每题给「考官最爱追问 + 标准答案骨架」，完整源码级拆解见对应历史篇（括号标注），避免与 8/27 总复盘重复。

**Q1（Handler）**："`Looper.loop()` 死循环为啥不 ANR？空闲时主线程在干嘛？"
-> 不占 CPU：`MessageQueue.next()` 无消息时 `nativePollOnce(-1)` → `Looper.cpp` 的 `epoll_wait` 在 `mWakeEventFd` 上休眠；ANR 是「处理某条消息超时」而非「在跑循环」。（详见 8/27 专题一）

**Q2（Binder）**："一次事务到底拷贝几次？为什么是 mmap 不是两次 copy？"
-> 发送方用户态→内核 `copy_from_user` **一次**；接收方经 `binder_mmap` 共享内核 buffer，**零二次拷贝**；相对 socket/管道（两次拷贝）更优，但非零拷贝。（详见 8/27 专题二、8/6 binder.c code walk）

**Q3（AMS/启动）**："冷启动为什么有时被 ContentProvider 拖慢？"
-> `ActivityThread.handleBindApplication()` 先 `installContentProviders()`，Provider `onCreate()` 跑在 `Application.onCreate()` **之前**；三方 SDK 借 Provider 自启做耗时 I/O 拖慢首帧。（详见 8/19 启动链路源码级、8/6 启动到首帧）

**Q4（WMS/View）**："`requestDisallowInterceptTouchEvent(true)` 对 DOWN 为什么无效？"
-> `ViewGroup.dispatchTouchEvent()` 在 `ACTION_DOWN` 时 `mGroupFlags &= ~FLAG_DISALLOW_INTERCEPT` 重置标志，DOWN 期间的子 View 请求被忽略；标志只对 MOVE/UP 生效（滑动嵌套经典坑）。（详见 8/27 专题四、8/16 输入系统全链路）

**Q5（ANR/卡顿）**："主线程被锁或 IO 阻塞，Choreographer 怎么定责掉帧？"
-> vsync 到 `MSG_DO_FRAME`（异步消息，过同步屏障优先）若错过 deadline，`FrameTimeline` 标记 `jankType`；用 `dumpsys gfxinfo` / Perfetto `android_frame_timeline` 定界「应用耗时 vs 合成耗时」。（详见 8/6 全链路排查实战、Perfetto SQL 篇 8-11/8-25）

**Q6（Compose）**："Compose 重组为什么能跳过未变化子树？编译器插桩做了什么？"
-> Compose 编译器对 `@Composable` 做源码插桩，自动记录参数稳定性 + 生成 `remember`/位置缓存；运行时按 `compositionLocal` + 参数 equality 决定跳过；`key()`/`derivedStateOf` 控制重组粒度。（详见 8-15 Compose 编译器与运行时底层）

**Q7（HAL/内核）**："GKI 2.0 下 loadable module 和 built-in 怎么选？vendor 驱动怎么过 KMI？"
-> GKI 冻结 `common` 内核符号为 KMI；vendor 驱动要么 built-in 进 vendor 模块、要么 loadable 但只依赖稳定 KMI 符号；自 Android 14 起 `vendor_boot` 分离。（详见 8-17 HAL 与 Linux 内核驱动全链路、MTK 差异小节）

**Q8（MTK）**："MTK 平台怎么抓系统稳定性问题？AEE / mtklog / PerfService / thermal 各自干嘛？"
-> `AEE`（AE Error）抓 native/system 崩溃并落 `db` 文件；`mtklog` 一键抓取 modem/logcat/kernel 全量日志；`PerfService` 提频防卡顿；`thermal` 限频降温。定位死机/重启优先 pull `/data/aee_exp/`。（详见 8-17 MTK 小节）

**Q9（安全/跨版本）**："A17 相比 A14 在内存/安全上多了哪些新雷区？"
-> Memory Limiter（应用内存限额）、DCL 加固（dlopen .so 必须只读）、Keystore 每应用密钥限额、跨资料环回默认阻断、隐式 URI 授权收紧。（详见 7-30/7-31/8-01 安全篇）

**Q10（Rust 收口）**："system_server 旁边跑着哪些 Rust 进程？它们和 Java 服务怎么通信？"
-> `keystore2`、`compos`、`virtualizationservice` 等是 Rust 进程；经 Binder（`/dev/binder` 或跨 VM）与 Java/C++ 服务互通，AIDL 语言中立，client 无感服务端语言。（回扣本篇专题一/三）

---

## 易错点速记（面试避坑清单）

1. Rust 是**系统层**语言，落在 keystore2/binder/AVF/蓝牙-Wi-Fi/加密/分配器，不是 App 层玩具。
2. A14 baseline 已有 Rust 系统组件，别当新东西；演进在**规模**不是**机制**。
3. Rust Binder 复用 `/dev/binder`，AIDL 语言中立，client 无感语言。
4. Rust 不是银弹：unsafe/FFI/逻辑漏洞仍危险；与 Scudo/A17 护栏是**双轨分层防御**。
5. 内核 Rust 是「护栏」不是「重写」；C 仍是主心骨，GKI KMI 同样约束 Rust 驱动。
6. vendor（MTK/高通）相机调制解调器短期不 Rust 化，别夸大渗透度。

## 考官连环追问索引

- 内存安全量化：76%（2019）→ 两成以下（2025）；Rust 密度 0.2/百万行 vs C/C++ 1000/百万行（千倍）。
- 唯一近似 Rust 漏洞：CrabbyAVIF 线性溢出，被 Scudo 防护页拦截。
- 跨 VM Binder：`getCallingUid()` 不可信（pKVM guest/host 命名空间不同），需 attestation。
- 政策：CISA 2026-01-01 内存安全路线图；Linux 2025-12 宣布 Rust 非实验性。

## 延伸阅读

- AOSP：`system/security/keystore2/`、`frameworks/native/libs/binder/rust/`、`packages/modules/Virtualization/`、`external/rust/crates/`、`external/scudo/`。
- Google Android 安全博客《Memory safety》年度统计（2019–2025）。
- 《The Rust Book》Ownership / Borrowing / Lifetimes。
- 本系列交叉索引：8/27（跨版本总复盘）、8/02（EL2/pKVM/AVF）、8/17（HAL/内核/MTK）、7-30/7-31/8-01（A17 安全内存）、8/19（启动链路源码级）、8/06（全链路排查 + binder.c code walk）。

---

> 第 45 篇完。本篇刻意只做「系统层 Rust 化与内存安全边界」这一真缺口，不重复 8/27 的全域总复盘；二者互为补充。后续若继续日更，建议下一缺口角度：**「Rust 在 Android 应用/Native 侧的可落地实践（ndk、crate 选型、与 C++ 混编 ABI）」或「Android 16/17 隐私沙箱与 SDK 运行时（PRR/SdkRuntime）源码级」**。
