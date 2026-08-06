# Android Framework 面试题 · 端侧 AI 工程化 × AAOS 座舱电源 × 安全深水区（第十四篇）

> 日期：2026-08-04 ｜ Baseline：Android 14（UpsideDownCake, android-14.0.0_rXX）
> 定位：前十三篇 120 专题已闭环 EL0~EL3 四层世界（Binder/AMS/WMS/SF/ART/HAL/内核/TEE/pKVM/Compose/AppFunctions）。
> 本篇按 rotation 落到四个一直挂在计划里的「真缺口」+ 一条主线缝合：**端侧 AI 工程化**（LiteRT NPU delegate + LLM 量化）、**AAOS 座舱整车电源状态机**、**SE/StrongBox 深水区**、**Protected Confirmation**；并补 **AVF 隔离编译（odrefresh in pVM）**。全部贴合你的「AAOS 座舱 + 端侧 AI」主线。

---

## 本篇速览（6 大专题）

| # | 专题 | 填补的真缺口 | 关键 AOSP 落点 |
|---|------|--------------|----------------|
| 1 | LiteRT NPU delegate 全链路源码走读 | memory 挂 9 轮 | `external/tensorflow/tensorflow/lite/delegates/nnapi/` + `packages/modules/NeuralNetworks/` |
| 2 | 端侧 LLM 量化工程化（INT4/KV cache/算子回退） | memory 挂 9 轮 | `art/odrefresh/`、`packages/modules/Virtualization/`(litert_lm) |
| 3 | CarService 整车电源状态机（CPMS + Garage Mode） | memory 挂 9 轮 + AAOS | `packages/services/Car/service/.../power/` |
| 4 | StrongBox / Secure Element 深水区（OMAPI/applet） | memory 挂多轮 | `frameworks/base/core/java/android/se/omapi/` + `packages/apps/SecureElement/` |
| 5 | Protected Confirmation / ConfirmationUI | memory 挂 9 轮 | `frameworks/base/core/java/android/security/confirmation/` + `hardware/interfaces/confirmation/` |
| 6 | AVF 隔离编译（odrefresh in pVM / compos） | memory 挂 9 轮 | `packages/modules/Virtualization/compos/` + `art/odrefresh/` |

---

## 专题一：LiteRT NPU delegate 全链路源码走读

### 1.1 面试问题
> TFLite / LiteRT 推理时，`Interpreter::ModifyGraphWithDelegate()` 到底做了什么？NPU delegate 是怎么把计算图「卸载」到高通 Hexagon / 联发科 APU 的？NNAPI 在这里扮演什么角色？

### 1.2 答案解析与底层原理

**调用链起点**：`Interpreter::ModifyGraphWithDelegate(Interpreter::TfLiteDelegatePtr)`（`external/tensorflow/tensorflow/lite/interpreter.cc`）。它调用 `interpreter->ModifyGraphWithDelegate(delegate)`，内部走 `OpResolver` + `Prepare` 阶段，委托给 `delegate->Prepare(profiler)`。

**NNAPI delegate 的 Prepare（`external/tensorflow/tensorflow/lite/delegates/nnapi/nnapi_delegate.cc`）**：
1. `NNAPIDelegate::Prepare()` → 创建 `NNAPIModelBuilder`。
2. `NNAPIModelBuilder::Build()` 遍历 TFLite 的 `TfLiteNode` 图，把每个 op 通过** op 映射表**（`BuildAddOperationFnMap` / `BuildFloatOperationFnMap`）翻译成 NNAPI 原语：`ANeuralNetworksModel_addOperation()`（`ANEURALNETWORKS_ADD` / `CONV_2D` / `FULLY_CONNECTED` …）。
3. 操作数（tensor）通过 `ANeuralNetworksModel_addOperand()` + `ANeuralNetworksModel_setOperandValue()`（权重常量化进模型）。
4. 调用 `ANeuralNetworksModel_finish()`、`ANeuralNetworksCompilation_create()`、`ANeuralNetworksCompilation_setPreference()`（PREFER_LOW_POWER / FASTER），最后 `ANeuralNetworksExecution_create()`。

**NNAPI 运行时（AOSP 侧）**：`packages/modules/NeuralNetworks/`（Mainline 模块，原 `frameworks/ml/nn/`）。其 `runtime/` 的 `ExecutionBuilder::finish()` 决定把图派发到哪个「执行设备」：
- **CPU fallback**：`nnapi_cpu` 用 `XNNPACK`（`external/XNNPACK`）。
- **厂商 NPU driver**：通过 **NNAPI HAL** `hardware/interfaces/neuralnetworks/`（AIDL `IDevice`/`IPreparedModel`）调厂商编译好的 driver（`.so`）。NNAPI 的 canonical 实现（`packages/modules/NeuralNetworks/driver/cache/` + `runtime/` 选 device）。

**厂商 NPU delegate 两条路**：
- **通用 NNAPI 路**：LiteRT 的 `nnapi_delegate` 经 NNAPI 再经 HAL 到厂商 driver。高通 `libQnnHtp*.so` / 联发科 NeuroPilot APU 都暴露为 NNAPI device。
- **厂商私有 delegate 路**：如高通 `QAIRT`（Hexagon NN SDK）→ `libQnnHtpStub.so`/`libQnnHtpPrepare.so`，联发科 `NeuronAdapter`。LiteRT-LM 跑 LLM 时显式 `--backend=npu` 配 vendor dispatch（`litert/vendors/qualcomm/dispatch`）。

**图编译 / 准备开销**：NNAPI delegate 的首调很慢——要把 TFLite 图翻译成 NNAPI 模型并让厂商 driver 做 `IPreparedModel` 编译（量化图编译成 Hexagon 微指令 / APU 指令）。这就是「预热（warmup）」存在的根本原因。

### 1.3 易错点速记
- NPU delegate **只支持特定位宽**：float32/int8/dynamic-range-int8/quant-aware。把一个 **INT4 模型**直接丢给「只支持 8-bit 的 delegate」，delegate 会**拒绝全部 op、整图回退 CPU**——不是部分慢，是全部慢。
- 同一 op 在 NPU 不支持时走 **CPU fallback**，但精度实现可能与 CPU 路径不同，会引入**轻微精度漂移**。
- delegate 二进制体积增大；要先 `benchmark_model` 验证真实收益，别想当然。

### 1.4 高频追问链
- TFLite delegate / NNAPI / 厂商私有 delegate 三者关系？→ delegate 是 TFLite 的「插件接口」；NNAPI 是 Google 的统一抽象层；厂商私有 delegate 绕过 NNAPI 直连 driver，延迟更低但碎片化。
- NNAPI 模型能缓存吗？→ 能，`ANeuralNetworksCompilation_createForDevices` + 缓存 `.cache`；厂商 driver 也可缓存编译结果（避免每次重新编译）。
- NNAPI 跨进程吗？→ NNAPI service 是独立进程（`android.hardware.neuralnetworks` HAL 跨 binder），但 delegate 也可 in-process 直链 driver。

### 1.5 延伸阅读
- 与第七篇「NNAPI/NPU 全链路（IDevice 分区调度/共享内存张量）」缝合；A17 要求 NPU 访问声明 `FEATURE_NEURAL_PROCESSING_UNIT`（见 7/28）。
- 新热点：**LiteRT-LM**（`.litertlm` 格式）+ **CompiledModel API**（自动选硬件 + 异步执行），把 LLM 跑在 NPU 上（见专题二）。

---

## 专题二：端侧 LLM 量化工程化（INT4 / KV cache / 算子回退）

### 2.1 面试问题
> 端侧跑 Gemma3-1B / Gemini Nano 这类 LLM，INT4 量化、KV cache 量化、算子回退分别解决什么问题？为什么「NPU 上跑 LLM」常常首 token 慢、还不一定比 CPU 快？

### 2.2 答案解析与底层原理

**四层工程化架构（2026 实测范式）**：
1. **模型压缩层**：INT4 **per-channel** 权重量化（`group_size=128`）。FP16 → INT4，**体积缩 ~75%**，推理提速 2~3 倍。注意：通常 **attention / embedding 层留高精度**，不是整网 4-bit。
2. **推理引擎层**：LiteRT / LiteRT-LM（`runtime/engine:litert_lm_main`）。NPU delegate（INT4 支持依赖厂商 driver）vs GPU delegate。NPU 比纯 CPU 快 3~5 倍的前提是**算子覆盖全**。
3. **内存管理层**：**KV cache 量化**（FP16 → INT8），4K 上下文下 ~1.2GB → ~600MB（省 ~50%）；超阈值按 attention 权重**动态淘汰**低价值条目。
4. **应用接口层**：Google Play Services 的 **Private Compute Core（PCC）** 沙箱（呼应 8/2 AISeal/PCC），或原生 JNI。

**算子回退（graph partition）**：TFLite delegate / NNAPI 在构图时做**子图切分**——NPU 支持的 op 放进一个 delegate partition，不支持的（如 RoPE 变体、MoE routing、特定激活）回退 CPU/GPU。问题：**频繁回退 + 跨执行单元的数据搬运（NHWC 布局转换 / NPU↔CPU 拷贝）会抵消加速**，甚至更慢。

**首 token 延迟（TTFT）与吞吐分离**：
- TTFT 受 **prefill（整段 prompt 并行算）** + **图编译预热** 主导；
- 解码阶段受 **decode step + KV cache 带宽** 主导。
- NPU 慢在「首次」= 图编译（专题一 1.2）。

**投机解码（speculative decoding）**：小 draft model 快出 k 个候选 token，大 model 一次验证，提高 decode 吞吐——端侧常用（Gemma 系列 .litertlm 内置）。

### 2.3 易错点速记
- INT4 ≠ 全层 4-bit；embedding/attention 常留高精度，否则掉点严重。
- KV cache **对精度更敏感**，量化过度会直接掉点，需实测 PPL。
- **NPU 算子覆盖不全**是端侧 LLM「翻车」头号原因：回退 + 搬运抵消加速。
- 动态 shape（变长 prompt）对 NNAPI 编译缓存不友好，需 `setAllowFp16PrecisionForFp32` 等调优。

### 2.4 高频追问链
- per-channel vs per-token 量化？→ 权重常 per-channel（按输出通道缩放）；激活常 per-token（按 token 动态）。
- 为什么 KV cache 比权重更吃显存？→ 权重一次性载入，KV cache 随 ctx 长度线性增长，长上下文下远超权重。
- MoE 怎么上 NPU？→ expert 路由 op 易回退；常用「共享专家留 NPU + 路由回退 CPU」。
- 与 7/28 端侧 LLM（AICore 专有 / ODP AOSP 开放路径）、7/29 端侧 LLM 量化缺口缝合。

### 2.5 延伸阅读
- `ai.google.dev/edge/litert/next/litert_lm_npu`（LiteRT-LM NPU backend，Qualcomm/联发科 SoC 表）。
- 量化工程化完整闭环还需 **AVF 隔离编译**（专题六）：pVM 内重编 dex/oat 才能被信任。

---

## 专题三：CarService 整车电源状态机（CPMS + Garage Mode）

### 3.1 面试问题
> 手机的电源只有 awake/sleep/off，车载为什么复杂得多？`CarPowerManagementService` 的状态机怎么流转？为什么「车库模式（Garage Mode）」不是关机？

### 3.2 答案解析与底层原理

**入口与状态定义**：`packages/services/Car/service/src/com/android/car/power/CarPowerManagementService.java`（实现 `CarServiceBase`）。核心状态（`CarPowerManager`）：
`STATE_ON` → `STATE_PRE_SHUTDOWN_PREPARE` → `STATE_SHUTDOWN_PREPARE` → `STATE_SHUTDOWN_ENTER` → `STATE_POST_SHUTDOWN_ENTER`（AP 准备断电）；
另有 `STATE_SUSPEND_ENTER`/`STATE_POST_SUSPEND_ENTER`（Suspend-to-RAM）、`STATE_HIBERNATION_ENTER/EXIT`、`STATE_SHUTDOWN_CANCELLED`（回 ON）、`STATE_WAIT_FOR_VHAL`（启动等 VHAL 就绪）。

**触发源 = VHAL**：车机底层（VMCU）通过 **Vehicle HAL**（`AP_POWER_STATE_REQ`）发指令。关键方法 `CarPowerManagementService.onApPowerStateChange()` 翻译 VHAL 的硬线/总线信号为标准电源状态，再通过 `CarPowerManager.CarPowerStateListener` 广播给应用层。

**Garage Mode（车库模式）全链路**：
1. 用户熄火 → VHAL 发 `AP_POWER_STATE_REQ`：`SHUTDOWN_PREPARE`，附加参数 `SHUTDOWN_ONLY` 或 `CAN_SLEEP`（`SHUTDOWN_PREPARE` **必须带 state + 附加参数两个字段才生效**）。
2. CPMS 进入 `STATE_SHUTDOWN_PREPARE` → **GarageModeController**（`packages/services/Car/service/.../garagemode/`）激活，框架向全系统宣告「车库模式时间」。
3. `JobScheduler` 中 `setRequiresDeviceIdle(true)` 的 idle job 开始执行（OTA 升级、日志上传、地图增量更新等）。
4. 框架**请求 VHAL 延长时间**直到 job 完成或超时；完成后才真正关机/挂起。
5. 若 VHAL 在电量不足等场景发 `SHUTDOWN_IMMEDIATELY`/`SLEEP_IMMEDIATELY`，则**提前终止**车库模式。

**多显示协调**：CPMS 同时协调多屏断电（中控 + 仪表 cluster 独立供电），与 7/30「CarService 多用户/多显示」呼应——cluster display 在 `STATE_SHUTDOWN_PREPARE` 阶段可能仍由 VHAL 单独维持。

### 3.3 易错点速记
- 车库模式**不是关机**：AP 仍上电、屏关、跑 idle job；它是「给车造一个空闲窗口」（手机靠用户 60 分钟不碰屏，车熄火就没有窗口，故人为保持唤醒）。
- `SHUTDOWN_PREPARE` 单字段无效；`CAN_SLEEP`（挂起）与 `SHUTDOWN_ONLY`（关 AP）语义不同。
- **应用不直接碰 Garage Mode**，只调度 `setRequiresDeviceIdle(true)` 的 JobInfo；直接监听 CPMS 状态是系统/车控应用的事。

### 3.4 高频追问链
- 手机 Deep Doze vs 车载 Garage Mode？→ Doze 是空闲后限制；Garage 是「熄火但保持唤醒」跑完关键任务，方向相反。
- VHAL 怎么和 framework 通信？→ Vehicle HAL AIDL（`IVehicle`/`subscribeProperty` + `onPropertyEvent`），`AP_POWER_STATE_REQ` 是 property 0x110aXXXX 族。
- OTA 升级怎么防断电打断？→ Garage Mode 期间跑、且可请求 VHAL 延长时间；CDD 规定特定场景才能提前终止。

### 3.5 延伸阅读
- 与 7/30（CarService 多用户/多显示/整车电源）、7/27（Doze/AppStandby/JobScheduler/WakeLock）缝合。
- 调试：`adb shell dumpsys car_service --power`、`cmd car_service force-power-state SUSPEND`。

---

## 专题四：StrongBox / Secure Element 深水区（OMAPI + applet）

### 4.1 面试问题
> `android.se.omapi` 是什么？app 怎么和一个 SIM/ eSE 里的 applet 通信？它和 Keystore2 / StrongBox 是什么关系？为什么 `openLogicalChannel` 有时抛 `SecurityException`？

### 4.2 答案解析与底层原理

**客户端 API**：`frameworks/base/core/java/android/se/omapi/`（`SEService`、`Session`、`Channel`、`Reader`）。`SEService` 通过 binder 连到系统服务 `ISEService`（`packages/apps/SecureElement/` 的 `SecureElementService`，内部 `OmapiService` 实现 binder）。

**applet 生命周期（APDU 通道）**：
1. `SEService.isConnected()` 就绪 → `seService.getReaders()` 拿 `Reader`（eSE / SIM / SD）。
2. `reader.openSession()` → `session.openBasicChannel(aid)` 或 `openLogicalChannel(aid)`：发 **SELECT AID** 指令建立逻辑通道。
3. `channel.transmit(byte[] apdu)`：发 C-APDU 收 R-APDU（ISO 7816-4）。多个 applet 靠 **AID 路由表**共存。
4. `channel.close()` / `session.close()` 释放。

**访问授权 = ARA-M**：安全元件里有 **Access Rule Applet（ARA-M）**，存 **Access Rule File（ARF）**——「哪个 Android 应用的签名/包名允许开哪个 applet 的通道」。若 ARF 没有对应规则，`openLogicalChannel` 直接 `SecurityException`。这是 OEM/TEE 侧预置的，应用无法绕过。

**与 Keystore2 的接缝**：StrongBox 是一颗**独立安全芯片**（区别于 TrustZone TEE）。`IKeyMintDevice` 有**两个实例**——TEE 版（Keymint TA）与 **StrongBox 版**（Keymint 跑在 eSE/StrongBox 上）。Keystore2（`system/security/keystore2`）生成 key 时可指定 `SecurityLevel` = `STRONGBOX`（`KEYSTORE_ALIAS` 指向 SE-backed key）。不是所有设备都有 StrongBox（CDD 仅要求部分旗舰/Pixel）。

**HAL**：`hardware/interfaces/secure_element/`（`ISecureElement` / `ISecureElementListener`），OMAPI 服务经它触达 eSE driver。

### 4.3 易错点速记
- OMAPI 是**标准化 applet 访问通道**，不等于直接调 vendor SE HAL；多数应用走 OMAPI，厂商预置规则。
- `openLogicalChannel` 的 `SecurityException` 多半是 **ARA-M 没配访问规则**（不是代码 bug）。
- StrongBox ≠ TEE：StrongBox 是独立芯片（抗物理攻击更强、但能力有限），TEE 是 TrustZone 软件隔离（见 8/1）。Keystore2 选 STRONGBOX 失败会**回退 TEE**。
- applet AID 冲突 / 通道数上限也会失败。

### 4.4 高频追问链
- OMAPI 与 Keystore2 关系？→ 两者共享 SE 硬件；Keystore2 用 SE 存 key（StrongBox），OMAPI 用 SE 跑 app 自己的 applet，互不直接耦合。
- applet 怎么安装/更新？→ 经 TSM / OEM 专有通道写 ARF + applet 镜像，普通应用无此权限。
- 与第八篇 Weaver（`IWeaver`）、第十一篇 Keystore2/KeyMint/Weaver 缝合。

### 4.5 延伸阅读
- 安全元件预热 `IWeaver#warmUp()`（8/1，省 ≤200ms）；锁屏速率限制 bug 修复（8/1）。
- StrongBox 与 pKVM/AVF 的「硬件信任根」是不同层级（EL3 vs 独立 SE），勿混。

---

## 专题五：Protected Confirmation / ConfirmationUI

### 5.1 面试问题
> `ConfirmationPrompt` 弹出的「用户确认」对话框，和普通的 `AlertDialog` 有什么本质区别？为什么说普通世界（Android/Linux）连它的截图都拿不到、也无法伪造它的签名？

### 5.2 答案解析与底层原理

**API**：`frameworks/base/core/java/android/security/confirmation/`（`ConfirmationPrompt`、`ConfirmationCallback`、`ConfirmationAlreadyPresentingException`）。

**信任边界在 TEE**：普通 dialog 由 Android 应用进程渲染，屏幕录制/无障碍/恶意 overlay 都能篡改或读取。Protected Confirmation 的 UI 由 **TEE 内的 Confirmation UI TA** 通过 **ConfirmationUI HAL**（`hardware/interfaces/confirmation/`，`IConfirmationUI`）在**受保护显示 + 受保护 touch** 上渲染——普通世界**无法绘制、无法截图、无法注入事件**。

**签名链路**：
1. app 调 `ConfirmationPrompt.presentPrompt()` → framework → **keystore2/confirmation**（Rust，`system/security/keystore2/`）。
2. keystore2 把「要展示的文本 + data」交给 TEE Confirmation TA。
3. TEE 渲染、用户物理确认 → TA 用**确认密钥**对 `data + 显示文本哈希` 签名，返回 `signature`。
4. 普通世界只拿到 signature（拿不到明文确认密钥），但可**离线验证**签名 → 证明「用户在那个时刻确实看到了那段确切文字并确认」。

**与 7/31「可信 UI 三等级」缝合**：
- L1 系统渲染（防 tapjacking，普通世界渲染）；
- L2 **TEE 渲染（ConfirmationUI）** ← 本专题；
- L3 Agent 显式确认（用户主动在可信 UI 点确认）。
Protected Confirmation = L2，是「系统托管 UI + 一次性凭证」隐私范式（见 8/3）的硬核底座。

### 5.3 易错点速记
- 它是**密码学确认**，不是 UI 控件：签名只证明「看到了那段确切文字」，不证明用户「理解」了内容。
- 必须有**硬件 TEE + 受保护显示**；部分设备/配置不支持会抛异常。
- 普通世界**拿不到确认明文、造不了签名**——这是它与 FIDO/WebAuthn 外的根本差异（确认密钥永不离开 TEE）。

### 5.4 高频追问链
- 为什么必须 TEE 渲染？→ 防屏幕录制、无障碍、overlay 中间人篡改/偷看（7/31 tapjacking 攻击面）。
- 签名在哪验证？→ 应用服务器/依赖方离线验（用确认密钥公钥），framework 不保管私钥。
- 与 Android Key 确认（Proof of Key / PK）区别？→ PK 证明 key 存在，确认 UI 证明用户看到了特定文本。

### 5.5 延伸阅读
- 与 8/1 Trusty/TEE、8/3 系统托管 UI + 可信 UI三等级缝合。
- 下一步真缺口：**StrongBox/SE 深水区**（专题四）已补；**AVF 隔离编译**见下。

---

## 专题六：AVF 隔离编译（odrefresh in pVM / compos）

### 6.1 面试问题
> 普通世界已经编译好的 `.odex/.oat`，为什么 pVM（受保护的 AVF 虚拟机）不能直接用？`compos` 是什么，它和 `odrefresh` 又是什么关系？

### 6.2 答案解析与底层原理

**信任前提（承接 8/2 pKVM/AVF）**：pVM 的安全模型要求「**加载进 VM 的代码必须经过可信编译**」。普通世界（Android/Linux）是**不可信**的——它编译的 odex 可能被 dex 注入 / 热修篡改，若直接被 pVM 信任就破了机密计算边界。

**odrefresh**：`art/odrefresh/`。原本是普通世界给 ART 用的「按需编译 dex → odex/oat」工具（类似 `dex2oat` 的受管控封装，受 Compilation OS 约束）。在 AVF 场景，普通世界的 odrefresh 产物**仍不可信**。

**compos（Compilation OS）**：`packages/modules/Virtualization/compos/`——一个跑在 **pVM 内**的 microdroid 虚拟机，专门在**受保护环境**里执行编译。流程：
1. host 请求为某 app 编译 → 启动 compos pVM。
2. compos 内用受控的 `odrefresh` 把 dex 编译成 odex/oat。
3. 产物经 **DICE/BCC** 派生的 VM 专属 key **签名/哈希上链**（`packages/modules/Virtualization/lib/client/` 取到 artifact 指纹）。
4. host 验证产物哈希/签名与 pVM 的 DICE 状态一致，才算**可信**，加载进 App VM。

**安全意义**：普通世界无法伪造 pVM 内已编译代码——任何篡改都会让 host 的哈希校验失败，编译产物被拒。这就把「代码完整性」也纳入 pVM 的 TCB。

### 6.3 易错点速记
- pVM **不能复用 host 已编译 odex** 的根本原因是「host 不可信」，不是性能。
- `compos` ≠ 普通 `odrefresh`：前者在**受保护 pVM** 内跑、产物可验证；后者在**不可信普通世界**跑。
- 产物必须绑定 **DICE 派生密钥**签名，host 才能信任——这是和 8/2 DICE per-VM secret 派生一脉相承。

### 6.4 高频追问链
- odrefresh vs dex2oat？→ odrefresh 是「按需 + 受控 + 可验证」的封装，dex2oat 是底层编译器；compos 场景用 odrefresh 在 pVM 内调用 dex2oat。
- 性能开销？→ 首次编译进 pVM 有启动 + 编译成本，产物可缓存复用（authfs/zipfuse 见 8/2）。
- 与 8/2 AISeal/PCC 关系？→ AISeal 把端侧推理搬进 pVM，其代码同样走 compos 隔离编译。

### 6.5 延伸阅读
- 与 8/2 pKVM/AVF 五层链路、DICE/BCC per-VM secret、authfs Merkle/zipfuse 缝合。
- 与专题二「端侧 LLM 量化工程化」衔接：经 AISeal 进 pVM 的推理代码也受 compos 保护。

---

## 查缺补漏（本篇收口 + 剩余真缺口）

**本篇收口的 memory 缺口**：LiteRT NPU delegate 源码走读、端侧 LLM 量化工程化、CarService 整车电源状态机、StrongBox/SE 深水区、Protected Confirmation、AVF 隔离编译（odrefresh in pVM）——**6 项全清**。

**截至本篇（第十四篇）累计 126 专题**。仍在「真·未覆盖」清单里的所剩无几：
1. Codec2 vendor 组件调试实战（CCodec → C2Component 厂商扩展断点）
2. CarService 电源状态机完整**状态图细化**（本篇已开，可再补 hibernation/多显示时序图）
3. 端侧 LLM **量化工程化实操**（INT4/KV cache 调优脚本，本篇已开原理层）
4. Kotlin/Compose Multiplatform 在 Android 侧运行时差异（skiko/原生互操作）
5. Robolectric shadow vs Ravenwood 取舍（Ravenwood 已于 8/2 开，可补对照）

---

## 易错点速记（15 条 · 第十四篇）

1. INT4 模型直接丢「只支持 8-bit 的 NPU delegate」→ 整图回退 CPU，不是部分慢。
2. NNAPI delegate 首调慢 = 图编译（`IPreparedModel`），务必 warmup。
3. 同一 op NPU fallback CPU 时精度实现可能不同，引入轻微漂移。
4. KV cache 对量化精度比权重更敏感，过度量化直接掉 PPL。
5. NPU 算子覆盖不全时「回退 + 跨单元搬运」会抵消加速，端侧 LLM 头号翻车点。
6. 车库模式不是关机：AP 上电、屏关、跑 idle job。
7. `SHUTDOWN_PREPARE` 必须带 state + 附加参数两字段才生效。
8. 应用不直接碰 Garage Mode，只调度 `setRequiresDeviceIdle(true)` 的 JobInfo。
9. `CAN_SLEEP`（挂起）≠ `SHUTDOWN_ONLY`（关 AP）。
10. OMAPI `openLogicalChannel` 的 SecurityException 多半是 ARA-M 没配访问规则。
11. StrongBox（独立 SE 芯片）≠ TEE（TrustZone 软件隔离）；Keystore2 选 STRONGBOX 失败回退 TEE。
12. Protected Confirmation 是密码学确认，签名只证明「看到了那段确切文字」，不证明「理解」。
13. ConfirmationUI 必须 TEE + 受保护显示，普通世界拿不到明文也造不了签名。
14. pVM 不能复用 host 已编译 odex 因 host 不可信，非性能。
15. compos 在受保护 pVM 内跑 odrefresh，产物经 DICE key 签名 host 才信任。

---

## 高频追问链（三条）

**A. 端侧 AI 工程化**
NPU delegate 怎么把图卸载到 Hexagon/APU？→ 只支持哪些位宽、INT4 为何整图回退？→ NNAPI 跨进程还是 in-process？→ 模型编译缓存？→ KV cache 为何比权重更吃显存？→ MoE 怎么上 NPU（回退策略）？→ pVM 内推理代码怎么保证可信（compos）？

**B. AAOS 座舱电源**
CPMS 状态机怎么流转？→ VHAL AP_POWER_STATE_REQ 怎么触发？→ Garage Mode 为什么不是关机？→ 怎么防 OTA 被断电打断？→ 多显示/多用户电源怎么协调（呼应 7/30）？→ cluster 仪表为什么独立供电？

**C. 安全世界深水区**
OMAPI 怎么和 applet 通信？→ ARA-M 访问规则为何导致 SecurityException？→ StrongBox vs TEE 区别？→ Keystore2 怎么选 SE-backed key？→ Protected Confirmation 为何 TEE 渲染不可伪造？→ 与 7/31 可信 UI 三等级怎么对应？

---

## 十四篇交叉索引（知识体系全景）

| 篇 | 日期 | 主题 | 专题数 | 累计 |
|----|------|------|--------|------|
| 1 主篇 | 07-23 | Binder/启动/AMS/WMS/View/ANR/Compose/HAL/内核/MTK | 16 | 16 |
| 2 拓展 | 07-23 | Input/PMS/ART/SystemUI/折叠屏/SELinux/OTA/JNI/Binder安全/Perfetto | 10 | 26 |
| 3 深挖 | 07-23 | ART对象头/CMC GC/Binder调试/Rust Binder/Input多指/VSync/Camera/Audio/GKI/Perfetto | 11 | 37 |
| 4 图形多媒体通信 | 07-24 | HWUI/SF/图形内存/多刷新率/MediaCodec/Codec2/Thermal/Power/Telephony/WiFi/BT | 12 | 49 |
| 5 系统基建 | 07-27 | 16KB页/ClassLoader/权限/Keystore2/AVB/Vold/logd/可观测性/RRO/Doze/版本演进 | 11 | 60 |
| 6 端侧AI与A17 | 07-28 | NNAPI/NPU/LiteRT/CarService/Vulkan/ART产物/virtual A/B | 10 | 70 |
| 7 A17新雷区 | 07-29 | Lock-free MQ/ART分代GC/hiddenapi/ProfilingManager/后台音频/NFC/SE/Media3/端侧LLM | 8 | 78 |
| 8 渲染合成+A17安全 | 07-30 | RenderEngine/Codec2插件/Memory Limiter/DCL/Keystore限额/CarService多用户多显示/ART镜像 | 7 | 85 |
| 9 兼容性框架 | 07-31 | compat框架/letterbox/BAL/Bubbles/Handoff/PointerCapture/SMS OTP/ECH/hiddenapi流水线 | 10 | 95 |
| 10 TEE | 08-01 | Trusty/SMC/libtrusty/Keystore2/KeyMint/Gatekeeper/Weaver/Attestation/Widevine/ION→DMA-BUF | 8 | 103 |
| 11 pKVM/AVF | 08-02 | pKVM/AVF/crosvm/DICE/vsock/RPC Binder/AISeal/Connectivity eBPF/Ravenwood | 8 | 111 |
| 12 智能系统 | 08-03 | AppFunctions/AppSearch/Compose插件/Compose运行时/Compose↔FW缝/APK签名/ExitInfo/系统UI/无障碍 | 9 | 120 |
| 13 本篇 | 08-04 | LiteRT NPU delegate/LLM量化/CPMS+GarageMode/StrongBox/Protected Confirmation/AVF隔离编译 | 6 | **126** |

> 主线已闭环：EL0~EL3 四层世界（应用/Binder/系统服务/内核/TEE/pKVM）+ 智能层（AppFunctions/Compose/端侧 AI）+ 座舱（AAOS 电源）+ 安全深水区（SE/Confirmation）。剩余零散真缺口见上「查缺补漏」。
