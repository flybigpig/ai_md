# Android Framework 面试题 · 安全世界（Trusty TEE）全链路 × Android 17 架构级安全/内存新雷区

> 生成日期：2026-08-01（第 11 篇）
> 目标版本：Android 14（UpsideDownCake, API 34）源码路径为主线，Android 17（API 37, CinnamonBun, 2026-06-16 stable）作为热点演进对照
> 主线：本篇以 **「安全世界」（Secure World / Trusty TEE）** 为主轴——前十篇讲遍了「普通世界」（Normal World）的 AMS/WMS/Binder/渲染/内存/兼容性框架，却始终没跨过 **EL3 Secure Monitor** 这道墙。本篇补上这块真缺口：从 ARM TrustZone 的世界切换、libtrusty IPC，到 Keystore2/KeyMint、Gatekeeper/Weaver、Key Attestation、Widevine DRM，最后串起 Android 17 最新一批**架构级**变更（ION 弃用、硬件封装密钥、安全元件预热 `IWeaver#warmUp()`、锁屏速率限制、音频托管 SCO）。

---

## 当日热点锚定（2026-08-01）

Android 17 官方 Release Notes（source.android.com/docs/whatsnew/android-17-release）本季集中释放了一批**架构级**（Architecture / Security）改动，不是应用层 API 糖，而是动到内存分配器和信任根：

| 热点 | 分类 | 一句话 | 本篇落点 |
|---|---|---|---|
| **ION 弃用** | Architecture | 支持 ION 的内核已于 2025-12 全部 EOL，任何 Android 版本不再支持 ION，vendor 代码必须迁到 DMA-BUF heaps | §8 |
| **硬件封装密钥改进** | Security | Hardware-wrapped storage keys，FBE 存储密钥全程不出 TEE，只以 wrapped blob 落盘 | §7 |
| **安全元件预热 `IWeaver#warmUp()`** | Security | 开始输入锁屏 PIN/图案/密码时提前唤醒 SE，验证延迟最多降 200ms | §4 |
| **锁屏速率限制修复** | Security | `LockPatternUtils` 超时缓存 bug 修复：跨实例误判"无有效超时" | §4 |
| **音频托管 SCO 重构** | Audio | SCO 路由从 BT 框架移交 audio framework 管理 | §9 拓展 |
| **AOSP 源码树只读** | Setup | A17 起 build 期间源码树只读，产品配置若写源树直接报错 | §9 拓展 |
| **`memfd_class` 政策** | Compatibility | 新设备必须启用 `memfd_class` policy capability，共享内存策略支持 `memfd_file` | §8 关联 |

> 记忆钩子：A15/A16 的热点大多在**普通世界**（16KB 页、Predictive Back、Memory Limiter）；A17 这一批把刀伸进了**安全世界与内存分配器底座**。面试官今年爱问"密钥到底存在哪、怎么证明是硬件生成的"——这就是本篇要打穿的链路。

---

## 目录

1. Trusty TEE 架构与「世界切换」全链路（本篇主轴）
2. libtrusty / trusty-ipc：普通世界如何和安全世界通信
3. Keystore2 + KeyMint：密钥服务的 Rust 重写与 HAL→TA 链路
4. Gatekeeper / Weaver：锁屏凭据验证与 A17 预热/限速
5. Key Attestation + RKP：如何向服务器证明"密钥来自真硬件"
6. Widevine DRM / MediaDrm / OEMCrypto：安全视频管线 L1/L2/L3
7. 硬件封装密钥（Hardware-wrapped keys）与 FBE（A17 热点）
8. ION 弃用 → DMA-BUF heaps：内存分配器换代（A17 热点）
9. 查缺补漏 / 易错点 / 高频追问 / 延伸阅读
10. 十一篇交叉索引

---

## §1 Trusty TEE 架构与「世界切换」全链路（主轴）

### Q1.1 什么是 TEE？Trusty 在 Android 里扮演什么角色？为什么需要它？

**答案要点：**

TEE（Trusted Execution Environment，可信执行环境）是与 Android OS **运行在同一颗主处理器上、但通过硬件隔离**的第二个操作系统。Trusty 是 Google 提供的开源 TEE 实现（也可换成厂商的 QSEE、Kinibi 等）。

核心动机——**软件密钥的死穴**：如果一个私钥存在于 Linux 内核可寻址的内存里，那么一旦 root/内核被攻破，密钥就能被 dump 出来。磁盘加密、DRM、支付签名全线崩溃。TEE 的价值在于：**在不把原始密钥暴露给主 OS 的前提下，替它完成密码学运算**。

Trusty 组成（对应 AOSP 三块）：
- **Trusty OS 内核**：源自 LK（Little Kernel）的微内核，独立仓库（`trusty/` 系列 manifest，`external/lk` / `trusty/kernel`）。
- **Linux 内核驱动**：负责普通世界⇄安全世界数据搬运——`drivers/trusty/`（`trusty.c` / `trusty-ipc.c` / `trusty-irq.c` / `trusty-log.c` / `trusty-virtio.c`）。
- **用户态库 libtrusty**：`system/core/trusty/`，App/HAL 通过它经内核驱动与 TA（Trusted Application，安全任务）通信。

Trusty 里跑的关键 TA：KeyMint TA、Gatekeeper TA、Weaver、ConfirmationUI（Protected Confirmation）、部分 DRM/OEMCrypto。

**易错点：** TEE 不是"一个安全芯片"。它和 Android **共用 CPU 和物理内存**，靠 ARM TrustZone 的地址过滤（TZASC）+ 外设隔离（TZPC）做隔离。真正"物理独立的芯片"是 **StrongBox（SE，安全元件）**，见 §5/§7。

---

### Q1.2 ARM TrustZone 的「两个世界」是怎么切换的？SMC 指令走到哪一层？

**答案要点——这是本篇最硬核的一问：**

ARM 把 CPU 状态分成 **Normal World（NS=1）** 与 **Secure World（NS=0）**，两者由 **EL3 的 Secure Monitor** 看守。异常等级：
- EL0：App / TA 用户态
- EL1：Linux 内核 / Trusty 内核
- EL2：Hypervisor（pKVM / Gunyah）
- **EL3：Secure Monitor（世界切换的唯一闸门）**

切换动作靠 **SMC（Secure Monitor Call）** 指令触发同步异常，陷入 EL3。一次"Android 让 TEE 生成 AES 密钥"的完整栈：

```
App: KeyGenParameterSpec + KeyStore.getInstance("AndroidKeyStore").generateKey()
  └─ frameworks/base/keystore/.../AndroidKeyStoreKeyGenerator (SPI)
      └─ Binder → keystore2 daemon (system/security/keystore2/, Rust)
          └─ AIDL: IKeyMintDevice.generateKey()  (KeyMint HAL 服务)
              └─ libtrusty: tipc_connect("com.android.trusty.keymint") + write/read
                  └─ ioctl(/dev/trusty-ipc-dev0)  → drivers/trusty/trusty-ipc.c
                      └─ drivers/trusty/trusty.c: trusty_std_call32() → smc(SMC_SC_NOP/QUEUE...)
                          └─ ARM: SMC #0 → 陷入 EL3 Secure Monitor
                              └─ 保存 NS 上下文 → 切安全世界页表 → 跳 Trusty Kernel(EL1S)
                                  └─ 调度 KeyMint TA(EL0S)：在安全内存里生成密钥
                                      └─ 返回 key blob（加密句柄）沿原路回到普通世界
```

**关键点：** 原始密钥**从不向上返回**普通世界；返回的是 **key blob**（被 TEE 根密钥加密的密文句柄），Android 只能存储它、无法解密它。只有当初生成它的那个 TEE/StrongBox 能解开。

**高频追问：**
- Q：SMC 之后 Trusty 怎么被"调度"回来继续跑？A：Trusty 是**协作式/被动**执行的——普通世界通过 `SMC_SC_NOP`/中断把 CPU 时间片"借"给安全世界，Trusty 处理完主动 `smc_return` 回来。它不抢占 Android。
- Q：普通世界访问安全内存会怎样？A：TZASC 拦截，触发 **SError**（异步外部中止）。

---

## §2 libtrusty / trusty-ipc：普通世界如何与安全世界通信

### Q2.1 一个 HAL 进程要调 TA，代码上怎么走？TIPC 是什么？

**答案要点：**

TIPC（Trusty IPC）是 Trusty 提供的类 socket IPC。普通世界侧 API 在 `system/core/trusty/libtrusty`：

```c
// system/core/trusty/libtrusty/trusty.c
int fd = tipc_connect("/dev/trusty-ipc-dev0", "com.android.trusty.keymint.V3");
write(fd, req, req_len);     // 发送序列化请求
read(fd, resp, resp_len);    // 读取 TA 应答
close(fd);
```

`tipc_connect` 底层是 `ioctl(fd, TIPC_IOC_CONNECT, port_name)`，进内核 `drivers/trusty/trusty-ipc.c`。内核用 **virtio + shared memory**（`trusty-virtio.c`）在两个世界间传消息，用 SMC 做门铃（doorbell）通知对端。

分层：
- 用户态：libtrusty（`tipc_connect` / `tipc_send` / `tipc_recv`）
- 设备节点：`/dev/trusty-ipc-dev*`
- 内核驱动：`drivers/trusty/trusty-ipc.c`（TIPC 端点管理）+ `trusty.c`（SMC 调用封装）+ `trusty-irq.c`（安全世界中断转发回普通世界）
- 安全世界：Trusty Kernel 的 IPC 层把消息投递给对应 TA 的 port handler

**易错点：** TIPC 不是 Binder。它不走 binder 驱动、没有 `IBinder`、没有 `Parcel`——是 Trusty 自己的一套 IPC。KeyMint/Gatekeeper 的 **AIDL 只到 HAL 服务进程为止**；HAL 进程内部再用 libtrusty/TIPC 下探到 TA。别把 "AIDL HAL" 和 "TA 通信" 混成一层。

**高频追问：**
- Q：为什么要 virtio？A：virtio 是标准化的前后端队列模型，Trusty 复用它做跨世界的 vring 队列，避免每家重造 IPC 轮子。
- Q：日志怎么出来的？A：`drivers/trusty/trusty-log.c` 把 Trusty 内核 log 通过共享 ringbuffer 拉到普通世界，`dmesg` 里能看到 `trusty:` 前缀。

---

## §3 Keystore2 + KeyMint：密钥服务的 Rust 重写与 HAL→TA 链路

### Q3.1 keystore2 相比老 keystore 有什么变化？为什么用 Rust 重写？

**答案要点：**

Android 12 起 keystore 守护进程重写为 **keystore2**，源码 `system/security/keystore2/`（**Rust**）。同时 HAL 从 Keymaster（HIDL）升级为 **KeyMint（AIDL）**，接口 `hardware/interfaces/security/keymint/aidl/`（`IKeyMintDevice.aidl`、`KeyParameter`、`Tag`、`SecurityLevel`）。

术语层次（面试必背四件套）：
1. **AndroidKeyStore**：App 侧 Framework API，标准 JCA 的 Android 扩展，代码跑在 App 进程内——`frameworks/base/keystore/java/android/security/keystore2/`。
2. **keystore2 daemon**：系统守护进程，Binder 暴露所有 KeyStore 能力，负责**存储 key blob**（能存不能用/看）。Rust 模块：`service.rs`、`security_level.rs`、`operation.rs`、`database/`。
3. **KeyMint HAL 服务**：实现 `IKeyMintDevice` 的 AIDL server，接入底层 KeyMint TA。
4. **KeyMint TA**：跑在 Trusty/TrustZone 里，做真正的密码学运算，持有原始密钥。

用 Rust 的原因：keystore2 是**处理跨信任边界不可信输入**的高危组件，历史上 C++ keystore 出过内存安全 CVE；Rust 的所有权/借用检查在编译期消灭一整类 UAF/越界。

**易错点：** keystore2 里也有 SQLite 数据库（`database/`）存 key metadata 和 blob，但 **blob 本身是密文**——数据库泄露≠密钥泄露。

---

### Q3.2 Authorization Tags（授权标签）是什么？为什么说它是 KeyMint 的"灵魂"？

**答案要点：**

生成/导入密钥时必须附一组 **Authorization Tags**（`hardware/interfaces/security/keymint/aidl/.../Tag.aidl` + `KeyParameter`），它们被**烧进 key blob 且由 TEE 强制执行**，App 无法绕过：

- 目的 `Tag.PURPOSE`：`SIGN`/`VERIFY`/`ENCRYPT`/`DECRYPT`/`AGREE_KEY`/`WRAP_KEY`
- 算法/长度：`Tag.ALGORITHM`（RSA/EC/AES/HMAC）、`Tag.KEY_SIZE`
- 访问控制：
  - `Tag.NO_AUTH_REQUIRED`：随时可用
  - `Tag.USER_AUTH_TYPE` + `Tag.AUTH_TIMEOUT`：需用户认证（生物/锁屏）后 N 秒内可用（**auth-bound key**）
  - `Tag.USER_SECURE_ID`：绑定到某个 Gatekeeper/生物模板的 SID（见 §4）
- 其它：`Tag.ORIGIN`（生成于 TEE 还是导入）、`Tag.ROLLBACK_RESISTANCE`（抗回滚）、`Tag.UNLOCKED_DEVICE_REQUIRED`

**auth-bound key 的精髓（高频追问）：** 用户指纹解锁后，Gatekeeper/BiometricManager 会拿到一个 **HAT（Hardware Auth Token，带 HMAC 签名的时间戳+SID）**，keystore2 把 HAT 随操作一起传给 KeyMint TA；TA 校验 HAT 的 HMAC 和时间窗，通过才允许用密钥。整个校验在**安全世界内**完成，普通世界伪造不了 HAT（HMAC 密钥只有 TEE 知道）。

**易错点：** `setUserAuthenticationRequired(true)` 的密钥，如果用户改了锁屏密码或删了指纹，密钥会**永久失效（invalidated）**——因为绑定的 SID 变了。这是很多 App "重启后密钥用不了" 的根因。

---

## §4 Gatekeeper / Weaver：锁屏凭据验证与 A17 预热/限速

### Q4.1 你输入锁屏 PIN，系统在安全世界里做了什么？Gatekeeper vs Weaver 区别？

**答案要点：**

锁屏凭据**绝不明文比对**。链路（`frameworks/base/services/core/java/com/android/server/locksettings/`）：

```
LockSettingsService.verifyCredential()
  └─ SyntheticPasswordManager（合成密码，A9+ 统一凭据抽象）
      ├─ Gatekeeper 路径：IGatekeeper HAL → Gatekeeper TA
      └─ Weaver 路径：IWeaver HAL → Secure Element（SE）
```

**Gatekeeper**（`hardware/interfaces/gatekeeper/aidl/IGatekeeper.aidl`，TA 在 TEE）：
- `enroll(password)`：用 scrypt 派生哈希 + TEE 私钥签名，产出 **password handle** 落盘。
- `verify(handle, password)`：在 TEE 内比对，成功返回 **HAT**（供 auth-bound key 用，见 §3.2），并返回一个 **SID（Secure User ID）**。
- **限流（throttling）**：连续失败时 TA 返回 `timeout`（如 30s），由 TEE 计时，普通世界改不了。

**Weaver**（`hardware/interfaces/weaver/aidl/IWeaver.aidl`，跑在 **SE 安全元件** 而非 TEE）：
- 把凭据校验绑定到 SE 的 **slot**（`read`/`write`/`getConfig`），提供比 TEE 更强的**抗物理攻击+硬件级限流**。
- 现代 Pixel/旗舰用 Weaver+SE 做磁盘加密密钥的解封，Gatekeeper 退居生物/HAT 场景。

**易错点：** Gatekeeper 的 SID 和 KeyMint 的 `USER_SECURE_ID` 是同一个东西——这就是"改密码导致密钥失效"的机制根源（enroll 新密码→新 SID→旧 auth-bound key 失配）。

---

### Q4.2 【A17 热点】`IWeaver#warmUp()` 是什么？为什么能省 200ms？锁屏限速又修了什么 bug？

**答案要点（当日热点）：**

**安全元件预热 `IWeaver#warmUp()`**（A17 新增，`hardware/interfaces/weaver/aidl/`）：
- 问题：SE 是低功耗独立芯片，平时深度休眠，第一次访问要**冷启动+建立安全通道**，耗时可达上百毫秒。用户输完 PIN 按确认那一刻才唤醒 SE，验证就慢。
- 方案：当存在支持该方法的 Weaver HAL 时，Android 在用户**开始输入**锁屏 PIN/图案/密码的瞬间就调 `warmUp()`，让 SE 提前上电、预建通道。等用户输完，SE 已就绪，验证延迟最多降 **200ms**。
- 本质：把"串行的唤醒+验证"改成"输入期间并行预热"，是典型的**延迟隐藏（latency hiding）**优化。HAL 实现者可选支持。

**锁屏速率限制修复**（A17）：
- 老问题：超时（throttle timeout）状态只按 `LockPatternUtils` **实例**缓存。若 A 实例触发了超时，B 实例查不到，会**错误显示"没有有效超时"**，等于绕过了限速 UI。
- 修复：系统凭据提示（锁屏、Settings 认证 Activity 等）必须**先校验现存超时**再放行下一次尝试。开发者若自建凭据提示需同步更新。

**高频追问：**
- Q：`warmUp()` 会不会带来安全风险（提前上电泄露信息）？A：不会，它只做上电和通道预建，不做任何凭据校验；校验仍需用户输入完成后走 `read`/`verify`。
- Q：为什么是 Weaver 而不是 Gatekeeper 加这个方法？A：预热针对的是**独立 SE 芯片**的冷启动延迟；Gatekeeper 的 TA 和 CPU 同片，无此冷启动问题。

---

## §5 Key Attestation + RKP：证明"密钥来自真硬件"

### Q5.1 服务器怎么确认一个公钥对应的私钥真的在 TEE 里、而不是模拟器软造的？

**答案要点：**

靠 **Key Attestation（密钥认证）**。生成密钥时请求一条**认证证书链**（`KeyGenParameterSpec.setAttestationChallenge()`）：

- TEE/StrongBox 内部签发 leaf 证书，链根是 **Google/OEM 在工厂预置的 Root of Trust**。
- leaf 证书里有一段特定 X.509 扩展（OID **1.3.6.1.4.1.11129.2.1.17**，"attestation extension"），记录：
  - `attestationSecurityLevel`：`Software` / `TrustedEnvironment` / `StrongBox`
  - `RootOfTrust`：**Verified Boot 状态**（verifiedBootState = Verified/SelfSigned/Unverified/Failed）、bootloader 是否锁、`verifiedBootHash`
  - 密钥的授权集（purpose、origin、auth 要求等，与 §3.2 一致）
  - `attestationChallenge`：服务器下发的随机数，防重放

服务器验证：校验证书链到 Google 根 → 读扩展 → 确认 `TrustedEnvironment`/`StrongBox` + `verifiedBootState=Verified` + challenge 匹配，才信任该密钥。

**最佳实践（高频追问）：** 不要自己写 X.509 解析逻辑——用 **Play Integrity API** 或成熟库校验证书链和设备状态（自研解析容易被绕过）。

**易错点：** 模拟器/软实现只能给出 `Software` 级 attestation，且证书链根不是 Google 硬件根——这正是风控识别"非真机/已 root"的关键信号。

---

### Q5.2 RKP（Remote Key Provisioning，远程密钥配置）解决了什么？

**答案要点：**

老方案是工厂给每台设备烧一个**固定的 attestation 私钥+证书批次**。问题：一旦某批次私钥泄露，整批设备的 attestation 全部作废且无法补救；隐私上还能被用来跨应用追踪设备。

**RKP**（`IRemotelyProvisionedComponent.aidl`，keystore2 侧 `system/security/keystore2/src/remote_provisioning.rs`）改为：
- 设备出厂只带一对**不可提取的 HMAC/EC 根密钥**（DICE / boot 证书链）。
- 运行时设备向 Google 后端**批量申请短期证书**，用完即弃、可轮换、可吊销。
- 好处：泄露可控（短期证书）、隐私更好（证书不长期固定绑定设备）、可远程修复。

关键词记忆：**DICE（Device Identifier Composition Engine）** 提供从 boot 各阶段度量派生的证书链，是 RKP 信任根。

---

## §6 Widevine DRM / MediaDrm / OEMCrypto：安全视频管线

### Q6.1 你放一部 Netflix 4K，帧数据是怎么做到"截屏是黑的、CPU 读不到像素"的？

**答案要点：**

DRM 框架是**插件化**的，AOSP 只提供 `MediaDrm`/`MediaCrypto` API，真正的密码学在 vendor 插件（Widevine/PlayReady/ClearKey）里，最终落到 TEE/SE。三层：

- **App 层**：`android.media.MediaDrm` / `MediaCrypto`（`frameworks/base/media/java/android/media/`）
- **Framework native 层**：`mediadrmserver` 进程（`frameworks/av/services/mediadrm/`），创建 `DrmHal`/`CryptoHal`（`frameworks/av/drm/libmediadrm/`）路由到 HAL
- **HAL / 安全世界**：DRM AIDL HAL（`hardware/interfaces/drm/aidl/`：`IDrmFactory`/`IDrmPlugin`/`ICryptoFactory`/`ICryptoPlugin`），vendor 插件（`/vendor/lib*/mediadrm/`）→ OEMCrypto → TEE

> A13+ DRM HAL 必须是 **AIDL**（早期是 HIDL）。vendor manifest 里声明 `IDrmFactory/widevine`、`ICryptoFactory/widevine`；SELinux 需要 `mediadrm_vendor_data_file` 等 label。

**取证式流程（面试爱考）：**
1. `new MediaDrm(WIDEVINE_UUID)` → `openSession()` 在 HAL 建安全上下文
2. `MediaExtractor` 解出 PSSH box（DRM init data）→ `getKeyRequest()`
3. App 把请求 HTTPS 发到 license server → 拿到 license → `provideKeyResponse()` 把密钥**注入安全硬件**（普通世界看不到明文密钥）
4. `MediaCrypto` 关联 session，传给 `MediaCodec.configure()`
5. 解码时 `queueSecureInputBuffer()` 把**密文** NAL 单元喂给 Codec2 组件 → TEE 用注入的密钥**直接解密到 Secure GraphicBuffer**
6. SurfaceFlinger 只合成 UI，安全 buffer 由 HWC/Display 直接读取上屏；截屏/录屏时该 buffer 被**硬件涂黑**

### Q6.2 Widevine L1/L2/L3 的区别？

| 级别 | 密钥/解密 | 视频解码 | 典型清晰度 |
|---|---|---|---|
| **L1** | 全部在 TEE + 安全硬件 | TEE 内，解密帧进 secure buffer | HD/4K/HDR |
| **L2** | 密码学在 TEE | 视频处理在非安全硬件/软件 | 少见 |
| **L3** | 软件（普通世界沙箱进程） | 明文解码 | 通常限 SD/480p |

`adb shell dumpsys media.drm` 可查设备支持的 DRM 插件与 Widevine 级别。

**易错点：** "有 TEE = L1" 是错的。L1 还要求**视频解码和显示通路全程 secure**（secure decoder + secure buffer + protected output）。少了任何一环只能到 L3，内容方就把你限到 480p。

**高频追问：**
- Q：Widevine 代码在 AOSP 里吗？A：只有 `MediaDrm`/`MediaCrypto`/框架路由在 AOSP；WVCDM/OEMCrypto 专利代码需 Google 授权，通常在 `vendor/widevine/`，最底层认证在 TEE。
- Q：Widevine Classic 和 Modular？A：Classic（wvm 封装、单 session）自 Android M 起废弃；现在都是 **Modular**（CENC + MPEG-DASH，配合 MediaCodec/MediaCrypto，即 WVCDM）。

---

## §7 硬件封装密钥（Hardware-wrapped keys）与 FBE（A17 热点）

### Q7.1 【A17 热点】文件级加密（FBE）的密钥存在哪？"硬件封装密钥"改进了什么？

**答案要点：**

Android 用 **FBE（File-Based Encryption）**：每个文件用文件密钥加密，文件密钥又受**类密钥（class key，与用户凭据/DE-CE 绑定）**保护。管理者是 **vold**（`system/vold/`）+ 内核 fscrypt（`fs/crypto/`）+ dm-default-key（metadata 加密）。

**传统软件密钥的风险**：class key 在某个环节会以**明文**出现在内核/vold 可寻址内存里，内核被攻破就可能泄露。

**硬件封装密钥（Hardware-wrapped keys，A17 改进）：**
- 存储密钥全程**不以明文出现在普通世界**：KeyMint TA 生成一个 wrapped key blob，落盘的是 blob；使用时把 blob 交给**内联加密引擎（Inline Crypto Engine, ICE）** 的硬件，硬件在 TEE 协助下解封并直接用于块加解密。
- 普通世界（含内核）**始终拿不到明文类密钥**，只经手 wrapped blob。
- 依赖 KeyMint 的 `convertStorageKeyToEphemeral()` 一类接口把长期 wrapped key 转成一次性临时密钥交给 ICE。

**记忆钩子：** §3 的应用密钥用 key blob 不出 TEE；§7 把**同一思路推广到磁盘/存储密钥**——A17 的"硬件封装密钥改进"就是让 FBE 也享受"明文密钥永不进普通世界内存"的待遇。

**易错点：** FBE ≠ FDE（全盘加密，已废弃）。FBE 支持 **Direct Boot**（开机未解锁也能跑闹钟/来电等 DE 数据），FDE 不行。别答成全盘加密。

---

## §8 ION 弃用 → DMA-BUF heaps：内存分配器换代（A17 热点）

### Q8.1 【A17 热点】ION 为什么被彻底弃用？DMA-BUF heaps 好在哪？

**答案要点（当日热点）：**

**背景：** ION 是早期 Android 的**跨进程/跨设备共享物理内存**分配器（相机、GPU、编解码器共享 GraphicBuffer 靠它）。A17 官方宣布：支持 ION 的内核已于 **2025-12 全部 EOL**，任何 Android 版本不再支持 ION，vendor 代码用 ION 应直接失败——必须迁到 **DMA-BUF heaps**。

**DMA-BUF heaps 优势（对比 ION）：**
1. **精细化 SELinux 访问控制**：ION 所有分配走单一 `/dev/ion`，难以按堆/按进程限权；DMA-BUF heaps 每个堆是独立设备节点 `/dev/dma_heap/system`、`/dev/dma_heap/system-uncached`、`/dev/dma_heap/<vendor>`，可对每个节点单独设 SELinux 策略（如相机堆只有 cameraserver 能开）。
2. **上游化 + 代码更干净**：ION 长期在 `drivers/staging/`（`drivers/staging/android/ion/`），带一堆 legacy 包袱；DMA-BUF heaps 是上游正式框架 `drivers/dma-buf/heaps/`（`system_heap.c`、`cma_heap.c`），符合 GKI 主线化要求。
3. **标准 fd 语义**：分配即返回标准 dma-buf fd，天然可 mmap、可经 Binder 传 fd 跨进程零拷贝。

**用户态适配：**
- 库：`system/memory/libdmabufheap/`，核心类 **`BufferAllocator`**（`Alloc("system", size)`）。
- vendor 需把原来 `ion_alloc` 的调用替换为 `BufferAllocator`/dma-heap ioctl。

**关联（A17 `memfd_class`）：** A17 还要求新设备启用 `memfd_class` policy capability，让共享内存策略支持 `memfd_file` 类对象——同属"共享内存治理更精细化"的大方向。

**高频追问：**
- Q：GraphicBuffer 分配路径变了吗？A：Gralloc HAL 底层从 ion 换成 dma-heap，但上层 `AHardwareBuffer`/`GraphicBuffer` API 不变，App 无感。
- Q：DMA-BUF 怎么跨进程共享？A：分配得到 dma-buf fd，通过 Binder（`ParcelFileDescriptor`）把 fd 传给对端，对端 mmap 同一物理页，实现零拷贝（这点和 §6 的 secure buffer、图形篇一脉相承）。

---

## §9 查缺补漏 / 易错点 / 高频追问 / 延伸阅读

### 9.1 易错点速记（15 条）

1. **TEE ≠ SE**：TEE（Trusty）与主 CPU 同片、TrustZone 隔离；SE/StrongBox 是物理独立微控制器，抗物理攻击更强。
2. **AIDL HAL 只到 HAL 进程**，HAL 进程内部再用 libtrusty/TIPC 下探 TA——别把两段当一层。
3. **key blob 是密文句柄**，数据库/磁盘泄露 ≠ 密钥泄露；只有原 TEE/SE 能解。
4. **SMC 陷 EL3**，世界切换唯一闸门是 Secure Monitor；普通世界访问安全内存触发 SError。
5. **Gatekeeper SID = KeyMint USER_SECURE_ID**：改锁屏密码/删指纹会让 auth-bound key 永久失效。
6. **HAT（Hardware Auth Token）** 带 HMAC，普通世界伪造不了；auth-bound 校验在安全世界内完成。
7. **keystore2 是 Rust**，KeyMint 是 AIDL（老的是 keystore + Keymaster HIDL）。
8. **`IWeaver#warmUp()`（A17）** 只预热不校验，省的是 SE 冷启动的 ≤200ms 延迟。
9. **锁屏限速修复（A17）**：超时不能只按 LockPatternUtils 实例缓存，否则可被绕过。
10. **Attestation 扩展 OID = 1.3.6.1.4.1.11129.2.1.17**，含 Verified Boot 状态与 challenge。
11. **RKP + DICE**：短期证书替代固定 attestation 批次，泄露可控、隐私更好。
12. **Widevine L1 要求解码+显示全程 secure**，光有 TEE 不够，否则限 480p。
13. **DRM 明文密钥不进普通世界**：`provideKeyResponse` 注入安全硬件，`queueSecureInputBuffer` 喂密文。
14. **FBE ≠ FDE**；A17 硬件封装密钥让存储密钥也"明文永不进普通世界内存"。
15. **ION 已死（A17）**：迁 DMA-BUF heaps（`/dev/dma_heap/*`），每堆独立 SELinux 策略。

### 9.2 高频追问链（面试官顺着问）

- 「密钥存哪」→「blob 怎么防提取」→「怎么向服务器证明是硬件」→ Attestation → RKP/DICE。
- 「指纹解锁后密钥为什么能用」→ HAT → SID → auth-bound key → 改密码为什么失效。
- 「截屏为什么黑」→ secure buffer → Widevine L1 → OEMCrypto/TEE → HWC secure overlay。
- 「ION 没了怎么办」→ DMA-BUF heaps → 每堆 SELinux → GraphicBuffer/Gralloc 底层替换 → 跨进程传 fd 零拷贝。
- 「锁屏为什么快了」→ `IWeaver#warmUp()` → SE 冷启动 → 延迟隐藏。

### 9.3 拓展补充（A17 其它架构/音频变更）

- **音频托管 SCO 重构（A17）**：蓝牙通话 SCO 路由从 BT 框架移交 **audio framework**（AudioPolicyManager/AudioFlinger，`frameworks/av/services/audioflinger/`）统一管理，路由决策更一致、减少 BT/audio 两套状态机打架。
- **AOSP 源码树只读（A17）**：build 期间源码树只读，产品配置阶段若尝试写源树直接报"read-only filesystem"——推动构建可复现/可缓存（配合 Bazel 迁移大方向）。
- **StrongBox 深挖**：独立安全微控制器，`SecurityLevel.STRONGBOX`；`setIsStrongBoxBacked(true)` 请求 StrongBox 支持的密钥，抗侧信道/物理攻击但吞吐低，适合少量高价值密钥（如支付、passkey）。

### 9.4 延伸阅读（建议按序精读）

1. source.android.com/security/trusty（Trusty 官方架构）
2. source.android.com/docs/security/features/keystore（硬件支持的 KeyStore）
3. source.android.com/docs/whatsnew/android-17-release（A17 Architecture/Security 段）
4. `hardware/interfaces/security/keymint/aidl/`、`hardware/interfaces/weaver/aidl/`、`hardware/interfaces/gatekeeper/aidl/`、`hardware/interfaces/drm/aidl/`（四组 HAL AIDL 定义）
5. `system/security/keystore2/`（Rust keystore2）、`system/core/trusty/`（libtrusty）、`drivers/trusty/`（内核驱动）
6. `system/memory/libdmabufheap/`（BufferAllocator）+ `drivers/dma-buf/heaps/`

---

## §10 十一篇交叉索引

| # | 篇目 | 主线 | 关键专题数 |
|---|---|---|---|
| 1 | 主篇（2026-07-23） | Handler/Binder/AMS/WMS/View/ANR/LMKD/Compose/HAL/内核/MTK | 16 |
| 2 | 热点拓展（07-23） | Input/PMS/ART-JIT/SystemUI/折叠屏/SELinux/OTA-AB/JNI/Binder安全/Perfetto | 10 |
| 3 | 深挖篇（07-23） | ART对象头/CMC GC/deopt/Binder驱动调试/Rust Binder/多指/VSync/Camera-Audio HAL/GKI/Perfetto SQL | 11 |
| 4 | 图形多媒体通信篇（07-24） | HWUI/Choreographer/SF/Gralloc-DMABUF/多刷新率/MediaCodec/Codec2/Thermal/Power HAL/RIL/WiFi/BT | 12 |
| 5 | 系统基建可观测性篇（07-27） | 16KB页/ClassLoader/权限/Keystore2-Keymint(HAL层)/AVB/Vold/logd/可观测性/RRO/Doze | 11 |
| 6 | 端侧AI与A17演进（07-28） | NNAPI/NPU/LiteRT/CarService/Vulkan/ART产物/virtual A/B | 10 |
| 7 | A17新雷区（07-29） | Lock-free MQ/ART分代GC/hiddenapi/ProfilingManager/后台音频/NFC-SE/Media3/端侧LLM | 8 |
| 8 | 渲染合成与A17安全内存（07-30） | SF RenderEngine/Codec2 vendor/Memory Limiter/DCL加固/Keystore限额/CarService/ART镜像 | 7 |
| 9 | 兼容性框架与A17跨设备（07-31） | platform_compat/letterbox/BAL/Bubbles/Handoff/Pointer Capture/SMS OTP/ECH/SQL严格/hiddenapi流水线 | 10 |
| 10 | 兼容性框架续（07-31 归档） | 见上（合并统计） | — |
| **11** | **安全世界TEE与A17架构级安全内存（08-01·本篇）** | **Trusty TEE/世界切换/libtrusty/Keystore2-KeyMint/Gatekeeper-Weaver/Attestation-RKP/Widevine/硬件封装密钥/ION→DMA-BUF** | **8** |

> 与前篇的衔接：第 5 篇讲过 Keystore2/Keymint **HAL 层** 与 AVB/dm-verity/fscrypt，第 8 篇讲过 Keystore **每应用密钥限额**与安全原生 DCL——本篇**下探到 HAL 之下的 TEE/TA 与世界切换**，并把 Attestation/RKP/Widevine/硬件封装密钥/ION 替换补齐，形成"普通世界←→安全世界"完整闭环。

---

*本材料由每日自动化任务生成，聚焦 AOSP 源码级深度，可直接用于面试复习。源码路径以 Android 14（API 34）为基准，A17 相关处已单独标注。*
