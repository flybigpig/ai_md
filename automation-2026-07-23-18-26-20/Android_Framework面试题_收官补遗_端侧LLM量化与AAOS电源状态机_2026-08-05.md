# Android Framework 面试题 · 收官补遗：端侧 LLM 量化实操 x AAOS 整车电源状态机（第十六篇）

> 日期：2026-08-05（补遗篇）｜ Baseline：Android 14（UpsideDownCake, android-14.0.0_rXX）
> 定位：第十五篇（末轮缺口补全 + 体系总导航）已闭环 15 篇 / 约 130 专题，并明确列出「剩余真·未覆盖角度」只剩两块：(1) CarService 电源状态图细化（hibernation / 多显示时序）；(2) 端侧 LLM 量化实操脚本。本篇只做一件事——把这两块真缺口彻底补齐，带 AOSP 源码路径 + 可运行脚本。两篇合计把系列推进到约 **132 专题**，正式收官。

---

## 本篇速览

| # | 专题 | 填补的真缺口 | 关键 AOSP 落点 |
|---|------|--------------|----------------|
| 1 | CarService 整车电源状态机（hibernation / 多显示时序） | 8/4 只讲了 CPMS + Garage Mode 概览，从未拆完整状态图与多显示掉电/上电时序 | `packages/services/Car/service/.../power/CarPowerManagementService.java` + VHAL AIDL `AP_POWER_STATE_*` |
| 2 | 端侧 LLM 量化实操（INT4 / KV cache / 算子回退） | 8/4 讲原理，从未给可运行脚本与校准/回退链路 | `ai_edge_litert` / `llama.cpp` / `torch.ao` + NPU delegate 回退 |

---

# 专题一：CarService 整车电源状态机（hibernation / 多显示时序）

### 1.1 面试问题
> AAOS（Android Automotive OS）整车下电时，应用层、Framework、VHAL、Linux 内核是怎么协同完成「挂起到 RAM（S2R）/ 休眠到磁盘（hibernation / S4）」的？`CarPowerManagementService`（CPMS）的完整状态机有哪些状态、谁驱动状态迁移？为什么需要 `CarPowerPolicy`？多屏座舱（仪表 + 中控 + 副驾 + HUD）在关机/唤醒时**掉电与上电的时序**为什么必须严格排序，错序会出什么问题？`Garage Mode` 和 hibernation 是什么关系？

### 1.2 答案解析与底层原理

**座舱电源的三层角色**
```
应用/系统服务 (CarService: CPMS / CarDisplayPowerManagementService)
        |  (binder + CarPowerManager listener)
        v
VHAL (IVehicle AIDL, hardware/interfaces/automotive/vehicle)
        |  (AP_POWER_STATE_REQ / AP_POWER_STATE_REPORT)
        v
Linux Kernel (wakeup sources / autosleep / hibernate / freeze)
        |  (kernel suspend/hibernate entry)
        v
SoC + 外设 (display panels, CAN, etc.)
```
- AAOS 的电源不是 Framework 单方面决定的，而是 **VHAL 上报整车电源意图（AP_POWER_STATE_REPORT），Framework 协调应用/显示/存储后，再向 VHAL 回 ACK（AP_POWER_STATE_REQ 完成），最后内核真正 suspend/hibernate**。这是和手机最大的区别：手机是「用户按键 -> PowerManager -> 内核」，车是「整车 ECU -> VHAL -> CPMS -> 内核」。

**CPMS 状态机（核心落点 `packages/services/Car/service/src/com/android/car/power/CarPowerManagementService.java`）**
CPMS 把整车电源抽象成几个宏观状态（以 Android 14 AAOS 为准）：
- `ON`：整车正常供电，屏幕亮，所有系统服务活跃。
- `SHUTDOWN_PREPARE`（WAIT_FOR_VHAL / WAIT_FOR_FINISH）：CPMS 收到下电意图后，先发 `CarPowerStateListener` 通知所有监听者「准备下电」，给应用一个**有限的 grace 窗口**保存状态；同时 `GarageMode` 在此窗口内跑后台作业（见 8/4）。
- `SUSPEND`（Suspend-to-RAM，S2R）：内核 `freeze` / `mem` 状态，RAM 自刷新供电，唤醒快（数百 ms）。车停但随时可一键唤醒（遥控/钥匙）。
- `HIBERNATION`（Suspend-to-disk，S4 / `hibernate`）：把 RAM 镜像写盘（swap），彻底断电，唤醒慢（数秒级）但零静态功耗——适合长时间停放。Android 14 AAOS 正式引入 hibernation 作为 S2R 的更深一层。
- `OFF`：彻底关机。
- `POST_SHUTDOWN_ENTER` / `POST_SHUTDOWN_EXIT`：内核 suspend/hibernate 完成后的回调状态，CPMS 用 `PowerHalService` 向 VHAL 报 `AP_POWER_STATE_REPORT` 完成。

状态迁移由谁驱动：
- 进入下电：VHAL 通过 `PowerHalService` 收到 `AP_POWER_STATE_REPORT(STATE_SHUTDOWN_PREPARE)` -> CPMS 切 `SHUTDOWN_PREPARE` -> 广播 `CarPowerManager#STATE_SHUTDOWN_PREPARE` -> 各 listener 处理 -> CPMS 调 `PowerHalService.setCurrentPowerState(REQUEST_SHUTDOWN_PREPARE)` ACK 给 VHAL -> 内核 suspend/hibernate。
- 唤醒：VHAL 报 `STATE_ON` / `STATE_WAKEUP` -> CPMS 切 `ON` -> 恢复显示与服务。

**CarPowerPolicy（为什么必须有它）**
- 座舱下电时**不能一刀切**：有些硬件（如 CAN、RTC、特定传感器、数字仪表）在「整车 OFF 但车仍在监控」时要保留供电；有些（中控大屏）必须断电。
- `CarPowerPolicy`（`packages/services/Car/car-lib/src/android/car/power/CarPowerPolicy.java`，定义在 `/vendor/etc/automotive/power_policy.xml`）用**组件化权限**描述「在某个电源状态下，哪些硬件组件允许被开/关」。`PowerComponent` 枚举覆盖 `AUDIO` / `DISPLAY` / `BLUETOOTH` / `WIFI` / `CELLULAR` / `CAN` / `TRUNK` 等。
- 例：hibernation 策略里 `DISPLAY` = off、`CAN` = on、`AUDIO` = off；SUSPEND 策略里 `DISPLAY` = off 但 `WIFI` = on（保持远程唤醒能力）。CPMS 在切状态时按 policy 调 `CarPowerPolicyDaemon` 实际开关组件。

**多显示时序（multi-display power sequencing）—— 面试高频坑**
座舱典型拓扑：主中控（main display）+ 副驾屏 + 数字仪表（cluster，常亮）+ HUD。掉电/上电必须有序：
- **下电顺序（关键）**：先停应用与 SurfaceFlinger 的辅助显示合成 -> 依次关闭副驾屏、HUD、中控主屏 -> **最后**关数字仪表（仪表常需撑到整车彻底下电、显示「已熄火」动画）-> 然后 CPMS 才允许内核 suspend。
  - 落点：`CarDisplayPowerManagementService` 监听 CPMS 状态，按 `DisplayManager` 的 `globalDisplayState` 与每个 `Display` 的 `Display.State` 编排；`DisplayPowerController`（AAOS 定制版）对每个逻辑显示下发 `setDisplayState`；cluster 显示通常绑定独立 `Display` 且标专用 id（非 `DEFAULT_DISPLAY`）。
  - 错序后果：若主屏比仪表先灭，仪表动画未播完就黑屏 -> 用户体验/合规问题；若显示未全部 off 就 suspend，内核 resume 时显示驱动状态机错乱 -> 花屏/无法唤醒显示（典型「黑屏唤醒」bug）。
- **上电顺序（唤醒）**：内核 resume 成功 -> CPMS 切 `ON` -> 先点亮数字仪表与 HUD（关键安全信息优先）-> 再点亮中控/副驾 -> SurfaceFlinger 重建显示合成链路。
  - 落点：CPMS 通过 `CarPowerStateListener#onStateChanged(STATE_ON)` 触发各显示服务按预设顺序 `setDisplayState(ON)`；若某显示 panel 上电慢（如 OLED 冷启动 > 1s），需 `DisplayManager` 的 `waitForStableDisplay` / 时序补偿，否则 compositor 在 panel 未 ready 时提交 -> 丢帧/闪烁。

**Garage Mode 与 hibernation 的关系**
- `GarageMode`（`packages/services/Car/service/src/com/android/car/garage/`）：在 `SHUTDOWN_PREPARE` 的 grace 窗口内，强制跑完被推迟的后台作业（OTA 下载、日志上传、索引重建）。它是「下电前的最后一段活跃期」。
- 关系：**Garage Mode 跑在 SUSPEND/HIBERNATION 之前**；只有 Garage Mode 作业完成（或超时），CPMS 才允许进入 SUSPEND 或 HIBERNATION。hibernation 是更深的省电态，Garage Mode 不等同于 hibernation——前者是「作业窗口」，后者是「省电态」。

### 1.3 易错点速记
- 以为座舱下电和手机一样由 PowerManager 直接驱动——错，AAOS 是 VHAL 主导 + CPMS 协调 + 内核执行。
- 把 SUSPEND（S2R，RAM 供电）和 HIBERNATION（S4，镜像落盘）混为一谈——前者快可随时唤醒、有静态漏电；后者零漏电、唤醒慢。
- 以为 CarPowerPolicy 是「开关机策略」——错，它是「各电源态下**组件级**供电白名单」，粒度到 CAN/WiFi/Display。
- 以为多屏可以「一起关」——错，掉电/上电必须按安全优先级排序（仪表最后关、最先亮），否则黑屏唤醒。
- 把 Garage Mode 当成 hibernation——错，Garage Mode 是下电前的作业窗口，hibernation 是省电态。

### 1.4 高频追问链
- CPMS 怎么知道「可以安全 suspend」？-> 等所有 `CarPowerStateListener` 的 `onStateChanged` 回调 ACK + Garage Mode 作业完成 + 显示全部 off。
- VHAL 断电意图和 kernel suspend 的中间人？-> CPMS 是唯一协调者，向 VHAL `AP_POWER_STATE_REQ` ACK，再触发 `PowerManager.goToSleep` / `autosleep`。
- 多屏时序谁保证不抖？-> `CarDisplayPowerManagementService` + `DisplayPowerController` 按显示 id 顺序下发，并等 panel ready。
- hibernation 镜像写哪？-> swap 分区/文件，内核 `hibernate` 走 `swsusp`；AAOS 设备需 bootloader 支持 resume from disk。

### 1.5 延伸阅读
- 与 8/4「CarService CPMS + Garage Mode」缝合（本篇细化状态图与多屏时序）。
- AOSP 入口：`packages/services/Car/service/src/com/android/car/power/CarPowerManagementService.java`、`packages/services/Car/service/src/com/android/car/hardware/power/PowerHalService.java`、`packages/services/Car/car-lib/src/android/car/CarPowerManager.java`；VHAL AIDL：`hardware/interfaces/automotive/vehicle/aidl/android/hardware/automotive/vehicle/IVehicle.aidl`（`AP_POWER_STATE_REPORT` / `AP_POWER_STATE_REQ`）。

---

# 专题二：端侧 LLM 量化实操（INT4 / KV cache / 算子回退）

### 2.1 面试问题
> 端侧跑 7B/13B LLM，显存/内存根本放不下 FP16 权重。量化到底在量化什么？**权重量化（weight-only）和激活量化（activation）有什么区别？INT8 和 INT4 的存储/精度代价怎么算？KV cache 为什么要单独量化？** 给我一个**可运行的**权重 INT4 group-wise 量化 + 反量化的 Python 脚本，并说明在 Android 端侧（NPU delegate）上跑量化模型时，**算子不支持怎么办（算子回退）**？校准集（calibration）是干什么的？

### 2.2 答案解析与底层原理

**量化的本质：用低比特定点近似高比特浮点**
- 线性量化公式：`quant(x) = round(x / scale) + zero_point`；`dequant(q) = (q - zero_point) * scale`。`scale` 把浮点动态范围映射到整数域，`zero_point` 处理非对称分布（如 ReLU 后的激活只非负）。
- **权重量化（weight-only）**：只量化模型权重（占参数 99% 的体积），激活保持高精度（FP16/BF16）。推理时**权重量化后在端侧反量化回 FP16 再做矩阵乘**，或用专门支持「权重 INT4 + 激活 FP16」的 tensor core / NPU 指令（如 INT4 x FP16 的 MMA）。优势：体积压到 1/4（INT4 vs FP16），推理精度损失小（权重分布稳定、易量化）。**端侧 LLM 主流方案（GPTQ / AWQ / llama.cpp Q4）几乎都是 weight-only。**
- **激活量化（weight + activation，W8A8 / W4A4）**：权重和激活都量化，省算力（整数 MAC 比浮点快数倍）但精度损失大、需要**校准集**确定激活的动态范围（per-tensor / per-channel / per-token）。端侧 NPU 若只支持 INT8 MAC，会走 W8A8；要更激进才上 W4A4。

**INT8 vs INT4 的代价（以 7B 模型为例）**
- FP16 权重：7B x 2 字节 = 14 GB（放不下）。
- INT8：7B x 1 字节 = 7 GB（仍偏大）。
- INT4（weight-only）：7B x 0.5 字节 = **3.5 GB**（手机/车机 NPU 内存可容纳）。
- 精度代价：INT8 通常 < 1% 困惑度上升；INT4 用 group-wise（每 128 个权重共享一个 scale/zero_point，即 group_size=128）可把损失压到可接受；再激进 INT2/INT3 会明显掉点。

**KV cache 为什么要单独量化（端侧最易被忽视的内存杀手）**
- 自回归生成时，每生成一个 token 都要缓存所有历史 token 的 Key/Value 矩阵（KV cache）。**KV cache 随上下文长度线性增长**，长上下文（32K/128K）时 KV cache 可能比权重还大。
- 量化方式：per-head 或 per-token 把 KV 从 FP16 压到 INT8（甚至 INT4），内存省一半到 3/4；精度敏感，常用**只量化 V、K 保留较高精度**或 group-wise。落点：端侧推理框架（llama.cpp / LiteRT / MediaPipe LLM）都把 KV cache 量化作为默认选项（`llama.cpp` 的 KV cache 类型 / context KV quant）。

**可运行脚本：INT4 group-wise 权重量化 + 反量化 + 前向（PyTorch 演示真实数学）**
```python
import torch

def quantize_int4_groupwise(w: torch.Tensor, group_size: int = 128):
    """权重 INT4 group-wise 量化：每个 group 共享对称 scale。
    w: [out, in] FP32 权重。返回量化后 int8 容器(每字节塞两个 int4) + scales。"""
    out, in_ = w.shape
    assert in_ % group_size == 0, "in_features 必须能被 group_size 整除"
    w = w.reshape(out, in_ // group_size, group_size)
    # 对称量化：scale = max(|w|) / 7  (int4 范围 [-8,7])
    max_abs = w.abs().amax(dim=-1, keepdim=True)
    scale = max_abs / 7.0
    q = torch.round(w / scale).clamp(-8, 7).to(torch.int8)   # [-8,7]
    # 打包：两个 int4 -> 一个 int8 (low 4 bits + high 4 bits)，省一半存储
    q = q.reshape(out * in_)
    low = (q[0::2] & 0xF).to(torch.int8)
    high = (q[1::2] << 4).to(torch.int8)
    packed = (low | high).to(torch.int8)
    return packed, scale.reshape(out, in_ // group_size)

def dequantize_int4_groupwise(packed: torch.Tensor, scale: torch.Tensor,
                              out: int, in_: int, group_size: int = 128):
    """反量化：拆包 -> 还原 [-8,7] -> *scale 回 FP。"""
    q = packed.to(torch.int32)
    low = (q & 0xF)
    high = ((q >> 4) & 0xF)
    # 把 4bit 无符号还原成有符号 [-8,7]：>=8 的当作负数
    low = torch.where(low >= 8, low - 16, low)
    high = torch.where(high >= 8, high - 16, high)
    q = torch.stack([low, high], dim=1).reshape(-1).to(torch.float32)
    q = q[:out * in_].reshape(out, in_ // group_size, group_size)
    return (q * scale.reshape(out, in_ // group_size, 1)).reshape(out, in_)

# ---- 演示 ----
torch.manual_seed(0)
W = torch.randn(64, 256)               # 模拟一层权重 [64,256]
x = torch.randn(1, 256)                # 一个输入 token
packed, scale = quantize_int4_groupwise(W, group_size=128)
W_q = dequantize_int4_groupwise(packed, scale, 64, 256, group_size=128)
y_fp = x @ W.T
y_q = x @ W_q.T
print("INT4 压缩比(相对FP32):", W.numel() * 4 / packed.numel() / 8, "x")
print("最大前向误差:", (y_fp - y_q).abs().max().item())
```
要点：group_size 越小精度越好（scale 更贴合局部分布）但存储 overhead 越大（scale 本身要存）；group_size=128 是「精度/体积」甜点。脚本跑出来压缩比约 8x（相对 FP32，即相对 FP16 是 4x），前向误差通常 < 1e-2 量级（随机权重下），真实模型靠校准 + GPTQ/AWQ 进一步压误差。

**端侧部署链路（Android + NPU delegate）**
- 训练/桌面侧量化：`llama.cpp` 用 `./llama-quantize model_fp16.gguf model_q4_0.gguf q4_0` 直接产出 INT4 GGUF；或用 `optimum` / `AutoGPTQ`（GPTQ 二阶补偿）/ `autoawq`（AWQ 激活感知）。
- 转 Android 端侧：`ai_edge_litert`（原 TF Lite）-> `.tflite` / `.task` FlatBuffer；或 `ai-edge-torch` 把 PyTorch 模型导出；NPU 加速走 **LiteRT NPU delegate**（见 8/4 专题一），把算子 offload 到 NPU。
```bash
# 例：llama.cpp 权重 INT4 量化（可运行）
./llama-quantize ./models/llama-7b-f16.gguf ./models/llama-7b-q4_0.gguf q4_0
```
```python
# 例：LiteRT 转换 + NPU delegate 回退（骨架）
import tensorflow as tf
converter = tf.lite.TFLiteConverter.from_saved_model("llm_savedmodel")
converter.optimizations = [tf.lite.Optimize.DEFAULT]            # 权重量化
converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT4]  # INT4
tflite = converter.convert()
open("llm_int4.tflite", "wb").write(tflite)
# Android 侧：NPU delegate 不支持的算子自动回退 CPU/XNNPACK
# InterpreterOptions().addDelegate(NnApiDelegate())  # 失败算子回退 builtin
```

**算子回退（operator fallback）—— 端侧量化的现实**
- NPU（尤其车机/手机 NPU）只实现了**有限算子集**。量化模型里若出现 NPU 不支持的算子（如某些自定义 RoPE、采样 top-p、特定激活），**delegate 会把该子图回退到 CPU（XNNPACK / builtin）执行**，其余仍在 NPU。
- 代价：回退的算子要「NPU 张量 -> CPU 张量」跨引擎拷贝（DMA/内存屏障），若频繁回退会在 layer boundary 产生**巨大延迟尖刺**；严重时整体比纯 CPU 还慢。
- 排查：LiteRT 的 `NnApiDelegate` 有 `allow_fp16` / `fallback` 选项；用 `nnapi-sample-driver` 看哪些算子回退；vendor 不支持就回退（思路同本系列 Codec2 vendor 篇——不支持即回退）。

**校准集（calibration）是干什么的**
- 激活量化（W8A8）需要知道激活的真实动态范围才能定 scale。用一小批**代表性真实输入**（几十到几百条）前向跑一遍，统计每层激活的 min/max/分布，据此定 `scale`/`zero_point`。**校准集越代表真实分布，量化精度越高**；用错分布（如全零输入）会严重掉点。weight-only 不需要校准（权重静态已知）。

### 2.3 易错点速记
- 以为量化是「整体换精度」——错，权重/激活/KV cache 三处可独立量化，端侧 LLM 几乎都是 weight-only + KV cache 量化。
- 以为 INT4 一定配 INT4 激活——错，weight-only INT4 配 FP16 激活更稳。
- 以为 group_size 越大越好——错，越大 storage overhead 小但精度差；128 是甜点。
- 以为 NPU 能跑全部算子——错，不支持就回退 CPU，频繁回退反而更慢（跨引擎拷贝）。
- 以为校准集随便取——错，激活量化精度高度依赖校准集代表性。

### 2.4 高频追问链
- INT4 怎么存（打包）？-> 两个 int4 塞一个 int8（low/high 4bit），省一半；反量化拆包还原 [-8,7]。
- KV cache 量化为什么单独讲？-> 它随上下文线性增长、常比权重还大，是端侧长上下文的内存瓶颈。
- NPU delegate 回退怎么排查？-> NNAPI sample driver + delegate 日志看哪些算子没 offload；减少自定义算子。
- GPTQ/AWQ 比朴素量化好在哪？-> 二阶补偿（GPTQ）/ 激活感知缩放（AWQ），INT4 下把精度损失压到接近 FP16。

### 2.5 延伸阅读
- 与 8/4「LiteRT NPU delegate 全链路 / 端侧 LLM INT4/KV cache 量化」缝合（本篇补**可运行脚本 + 校准/回退链路**）。
- 工具入口：`ai_edge_litert` / `ai-edge-torch` / `llama.cpp`(`llama-quantize`) / `autoawq` / `auto-gptq` / `torch.ao.quantization`。
- Android 端侧推理 runtime：MediaPipe LLM Inference API / LiteRT NPU delegate（NNAPI HAL，见 7/28 NNAPI 篇）。

---

## 本篇小结
- 补齐第十五篇 TODO 里最后两块真缺口：**CarService 整车电源状态机（hibernation / 多显示时序）** 与 **端侧 LLM 量化实操（INT4 / KV cache / 算子回退 + 可运行脚本）**。
- 系列从 15 篇 / 约 130 专题，正式收官于 **16 篇 / 约 132 专题**，覆盖 EL0~EL3 四层世界 + 智能层 + 座舱 + 端侧 AI 全栈。
- 至此「主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧 AI + 收官补遗」完整闭环，可作为考前定向复习底稿。
