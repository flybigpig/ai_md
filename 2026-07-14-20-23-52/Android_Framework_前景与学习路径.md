# Android Framework / 系统开发 前景分析与学习路径

> 目标版本基线：AOSP **Android 14 (UpsideDownCake, API 34)**，分支对齐 `android-14.0.0_rXX`。
> 适用读者：已在啃 AOSP 源码、改过 AMS/ATMS、加过系统 app、动过内核 binder 的 Framework / 系统向开发者。

---

## 一、先对齐三个方向，别混为一谈

| 方向 | 所处层 | 典型工作 | 核心门槛 |
|---|---|---|---|
| **应用开发** | App 层（Java/Kotlin/Compose） | 业务 UI、网络、架构、Jetpack | 低 → 中，易入行 |
| **Framework 开发** | 系统框架层 | 改 AMS/WMS/PMS、Binder、系统服务定制、ROM 定制 | 高（要懂源码 + 进程模型） |
| **系统开发** | BSP / 内核 / HAL / 驱动 | bootloader、kernel、驱动、NPU/GPU 调度 | 最高（软硬通吃） |

你现在做的（改 AMS/ATMS、加系统 app、改内核 binder、AOSP 编译）正好卡在 **Framework + 系统开发** 这条线上。

---

## 二、前景五维对比

五个维度 1–5 分（越高越好）：需求热度 / 薪资 / 稀缺性（门槛） / 抗 AI 替代 / 长期稳定性。

```
            应用开发         Framework开发      系统开发
需求热度        4                4                 4
薪资            3                4                 4.5
稀缺性(门槛)    2                4                 5
抗AI替代       2                4.5               5
长期稳定性      3                4.5               5
```

**结论**：应用开发分化（纯搬砖死、系统向活）；Framework / 系统开发向上且越来越稀缺。

---

## 三、行业大趋势（有数据支撑）

1. **移动 App 红利见顶，但 Android 没死，只是"下沉"**
   - 智联数据：2026 国内安卓岗位同比 **+8.2%**，但 **初级岗 −12%、中高级 +23%**；
   - 纯 UI 搬砖岗 AI 替代率已达 **~68%**。死的是"搬砖人"，活的是"啃硬骨头"的系统级工程。

2. **车载座舱（AAOS）是最大增量市场**
   - 猎聘上 "Android Framework（车载/座舱方向）" 岗位密集：延锋、中科创达、车企招 AMS/WMS/PMS/Display/Power 定制；
   - 薪资 **15–25K·13薪**，座舱架构师 **2–3 万/月**；
   - 中科创达一家同时挂出 VHAL、Camera Framework、Android Framework、AI 部署等十几个岗位；
   - 理想 / 蔚来 / 小鹏 / 比亚迪 / 华为车 BU 都在扩招。

3. **端侧 AI（NPU / HAL 调度）成新增长极**
   - 端侧大模型落地要"绕过云端高延迟、直接调系统 NPU API、在有限内存里跑量化模型还不 OOM"——全是硬核底层资源调度；
   - 联通"端智能"岗 **2.8–3.5 万·16薪**；
   - 中科创达"AI 部署"岗要求精通 TensorRT / ONNX Runtime / QNN、CUDA / OpenCL 算子优化。

4. **鸿蒙（HarmonyOS NEXT）分流但不淘汰**
   - 纯血鸿蒙在剥离安卓，但懂 Android Framework 的人看 ArkUI / ArkTS 概念相通（单向数据流如出一辙）；
   - **转鸿蒙只需约两周平滑切**；且大量厂商仍双栈，懂底层的人反而更值钱。

---

## 四、给你的判断：你正卡在最硬的赛道上

你在做的 **AOSP 编译 + 改 AMS/ATMS + 内核 binder**，正是座舱 / OS 厂商（中科创达、华为、车企）点名要的能力。

- **别焦虑"安卓有没有前途"**：你不在做会被 AI 生成的 UI，你做的是 AI 生成不了的 Binder / 进程模型 / 电源管理。这条线稀缺性、抗周期最强。
- **往两个增量方向加码**：
  - **车载座舱**：补 AAOS、CarService、多屏 / 音频焦点 / 电源管理（你已懂 WMS/PMS，顺手）；
  - **端侧 AI 集成**：学 NNAPI / 高通 QNN / TFLite-MNN 部署，把"系统服务 + NPU 调度"打通——未来 3 年最值钱的组合。
- **守住性能 / 功耗护城河**：Perfetto / systrace 抓卡顿、ANR、内存泄漏——这是 Framework 工程师区别于应用开发的核心壁垒，也是大厂溢价 40–60% 的来源。
- **坑**：别把路走窄成"只会改某家 ROM"；保持 AOSP 主线（Android 14/15）能力 + 跨芯片平台（高通 / 瑞萨 / MTK）经验，可迁移性才高。

---

## 五、学习路径（分阶段，附真实 AOSP 路径）

### 阶段 0：地基（必会，约 1–2 周）

- **编译环境**：Ubuntu 22.04 + 清华镜像拉 AOSP；`repo init -b android-14.0.0_rXX`，`lunch sdk_phone_x86_64`（需 KVM）。
- **源码阅读方法**：`cscope` / `Sourcegraph`；先读 `frameworks/base` 与 `frameworks/native`。
- **进程模型**：Zygote → `app_process` → `ActivityThread.main()`。
  - 入口：`frameworks/base/core/java/android/app/ActivityThread.java`
- **Binder 入门**：驱动 `drivers/android/binder.c` + `binder_alloc.c`；用户态 `frameworks/native/libs/binder/`。

### 阶段 1：Framework 核心（约 4–6 周，你已在路上）

- **AMS / ATMS**（Activity 生命周期、任务栈、启动流程）
  - `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java`
  - `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java`
- **WMS**（窗口、Surface、多屏）
  - `frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java`
- **PMS**（包管理、权限、安装）
  - `frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java`
- **system_server 启动**：
  - `frameworks/base/services/java/com/android/server/SystemServer.java`
  - 启动脚本：`system/core/rootdir/init.rc` + `system/core/init/`

### 阶段 2：系统 / BSP 向（约 6–10 周）

- **内核 Binder 驱动**：`drivers/android/binder.c`，重点看 `binder_ioctl` / `binder_transaction` / `binder_thread_write`。
- **HAL / HIDL / AIDL stable**：
  - `hardware/interfaces/`，新增 HAL 时写 `.aidl` + 实现 `.cpp`。
- **启动流程**：`bootloader → kernel → init → zygote → system_server`。
- **加一个系统 app**：放到 `packages/apps/<YourApp>`，加 `Android.bp` + `AndroidManifest.xml`，编进 `PRODUCT_PACKAGES`。

### 阶段 3：增量方向加码（长期，配岗位）

- **车载座舱（AAOS）**
  - `packages/services/Car/`：CarService、CarPowerManagementService、CarAudioService、多屏（CarProjection / Cluster）。
  - 关键点：音频焦点、电源状态机、多屏焦点切换。
- **端侧 AI 集成**
  - NNAPI：`frameworks/base/core/java/android/neuralnetworks/`，运行时 `frameworks/ml/`。
  - 高通 QNN：QNN SDK + `libQnnHtp.so` 调度；TFLite-MNN 量化部署。
  - 目标：系统服务里封装"加载模型 → 申请 NPU → 内存零拷贝推理 → 回收"。
- **性能 / 功耗护城河**
  - `perfetto`（取代 systrace）抓 trace；`atrace` / `am trace-ipc`；
  - 定位 ANR：`/data/anr/traces.txt`；内存泄漏用 `dumpsys meminfo` + leakcanary 系统侧。

### 阶段 4：工程化与产出（简历 / 面试弹药）

- 向 AOSP 提交 patch（Gerrit `android-review.googlesource.com`）。
- 沉淀可复用产物：编译指南、patch 文件、可 apply 的 diff、系统 app 模板。
- 跨芯片平台经验：高通 / 瑞萨 / MTK 的 BSP 差异，提升可迁移性。

---

## 六、避坑清单

- **别只改某家 ROM**：保持 AOSP 主线能力，否则换平台即失业。
- **别忽视性能 / 功耗**：这是 Framework 溢价来源，不是可选项。
- **别把端侧 AI 当黑盒**：要懂 NPU 调度，才值钱。
- **别裸奔上线**：系统级改动必过烤机 / monkey / CTS 验证。

---

## 七、一句话结论

**应用开发 = 分化（纯搬砖死、系统向活）；Framework / 系统开发 = 前景向上且越来越稀缺。**
你已经是最硬的赛道上，继续深挖 Framework + 往"座舱 + 端侧 AI"靠，比纠结"安卓有没有前途"实在得多。
