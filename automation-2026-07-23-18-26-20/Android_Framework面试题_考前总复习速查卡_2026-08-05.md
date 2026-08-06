# Android Framework 面试题 · 考前总复习速查卡（2026-08-05）

> 系列收官后的定向复习导航。本仓库已沉淀 **15 篇 / 约 132 专题**，主线闭环：
> Binder / AMS / WMS / SF / ART / HAL / 内核 -> TEE(EL3) -> pKVM/AVF(EL2) -> 智能层(AppFunctions/Compose/端侧 AI) -> 座舱(AAOS 电源) -> 安全深水区(SE/Confirmation) -> 测试双雄(Robolectric/Ravenwood) -> 体系总导航 -> 收官补遗。
>
> 本文**不再新增深水区**，而是把 132 专题压成「考前可速查」的形态：高频题速答 + 易错红榜 + 追问链 + 源码索引 + 复习节奏。Baseline：Android 14 (android-14.0.0_rXX)；新特性锚定 Android 17 (CinnamonBun, 2026-06-16 stable) 与 Android 18 前瞻。

---

## 〇、15 篇知识地图（按子系统导航）

| # | 文件名 | 日期 | 定位一句话 | 专题数 |
|---|--------|------|-----------|-------|
| 1 | Android_Framework面试题_2026-07-23.md | 07-23 | 主篇：Binder/启动/AMS/WMS/View/ANR/HAL/内核/MTK 主线 | 16 |
| 2 | Android_Framework面试题_热点拓展_2026-07-23.md | 07-23 | 拓展篇：Input 全链路 / PMS / SELinux / OTA / JNI / Binder 安全 / Perfetto | 10 |
| 3 | Android_Framework面试题_深挖篇_2026-07-23.md | 07-23 | 深水区：ART 对象头 / CMC GC / Rust Binder / VSync 时序 / Camera / Audio / GKI | 11 |
| 4 | Android_Framework面试题_图形多媒体通信篇_2026-07-24.md | 07-24 | HWUI / Choreographer / SF 合成 / MediaCodec / Codec2 / Thermal / Power / RIL / WiFi / BT | 12 |
| 5 | Android_Framework面试题_系统基建与可观测性篇_2026-07-27.md | 07-27 | 16KB 页 / ClassLoader / 权限 / Keystore2 / AVB / Vold / logd / 可观测性 / RRO / Doze | 11 |
| 6 | Android_Framework面试题_端侧AI与Android17演进_2026-07-28.md | 07-28 | NNAPI/NPU / LiteRT delegate / CarService / Vulkan / ART 镜像 / virtual A/B | 10 |
| 7 | Android_Framework面试题_2026-07-29.md | 07-29 | A17 新雷区：Lock-free MQ / 分代 GC / hiddenapi / ProfilingManager / NFC / Media3 | 8 |
| 8 | Android_Framework面试题_渲染合成与A17安全内存_2026-07-30.md | 07-30 | RenderEngine / Codec2 vendor / Memory Limiter / 安全 DCL / Keystore 限额 / CarService 多用户 | 7 |
| 9 | Android_Framework面试题_兼容性框架与A17跨设备窗口隐私_2026-07-31.md | 07-31 | platform_compat 引擎 / letterbox / BAL / Bubbles / Handoff / PointerCapture / ECH | 10 |
| 10 | Android_Framework面试题_安全世界TEE与A17架构级安全内存_2026-08-01.md | 08-01 | Trusty TEE / TIPC / Keystore2 / Gatekeeper / Key Attestation / Widevine / ION->DMA-BUF | 8 |
| 11 | Android_Framework面试题_pKVM机密计算与A17_AISeal_2026-08-02.md | 08-02 | pKVM / AVF / AISeal / vsock+RpcBinder / Memory Limiter vs LMKD / eBPF / Ravenwood | 8 |
| 12 | Android_Framework面试题_智能系统AppFunctions与ComposeFirst_2026-08-03.md | 08-03 | AppFunctions / AppSearch / Compose 编译器+运行时 / APK 签名 v3.2 / ApplicationExitInfo / 无障碍 | 9 |
| 13 | Android_Framework面试题_端侧AI工程化与AAOS座舱_2026-08-04.md | 08-04 | LiteRT NPU delegate / LLM INT4 / CarService 电源 / StrongBox / Protected Confirmation / AVF 编译 | 6 |
| 14 | Android_Framework面试题_末轮缺口补全与体系总导航_2026-08-05.md | 08-05 | Codec2 调试 / KMP / Robolectric vs Ravenwood / A18 前瞻 / 14 篇交叉索引 | 4 |
| 15 | Android_Framework面试题_收官补遗_端侧LLM量化与AAOS电源状态机_2026-08-05.md | 08-05 | CarService 整车电源状态机 / 端侧 LLM INT4 量化实操脚本 | 2 |

> 检索习惯：先按本表定位子系统 -> 打开对应篇 -> 看该篇「易错点速记 / 高频追问链」。

---

## 一、高频考点速答表（按子系统）

### 1. Binder IPC
| 最常考题 | 一句话答案 | 关键 AOSP 路径 | 出自 |
|---------|-----------|---------------|------|
| 一次拷贝在哪发生？ | 内核 `binder_alloc` 把发送方用户态 buffer 映射进内核，接收方 `mmap` 同一内核物理页，仅内核->用户一次拷贝 | drivers/android/binder.c / binder_alloc.c | 主篇/深挖篇 |
| 为什么 Binder 比 socket 快 | 共享内存 + 一次拷贝 + 线程池（每个 Binder 线程对应一个 LOOPER 轮询）| frameworks/native/libs/binder/IPCThreadState.cpp | 主篇 |
| getCallingUid/pid 可信吗 | 普通 app->system 可信；**跨 pVM / Binder RPC / AppFunctions Provider 侧拿到 SYSTEM_UID 不可信**，须用 attestation | IPCThreadState::getCallingUid | 12/11 篇 |
| TTLE / oneway 阻塞 | oneway 不阻塞等待，非 oneway 在 `waitForResponse` 等 BR_REPLY；binder 线程耗尽会触发 ANR | binder_transaction | 主篇 |

### 2. Handler / Looper / MessageQueue
| 最常考题 | 一句话答案 | 关键路径 | 出自 |
|---------|-----------|---------|------|
| 同步屏障如何工作 | `postSyncBarrier` 插入 target=null 的屏障消息，Looper 在 `next()` 跳过同步消息优先取异步消息（Vsync/绘制）| MessageQueue::postSyncBarrier / next | 主篇 |
| A17 有何变化（雷区） | MessageQueue **Lock-free** 重构，移除了传统 synchronized 锁实现，老的「反射改队列」黑科技失效 | frameworks/base/core/java/android/os/MessageQueue.java | 7 篇 |
| IdleHandler / 退出 | `queueIdle` 返回 false 只执行一次；`quit` 后 `next()` 返回 null 终⽌循环 | MessageQueue | 主篇 |

### 3. AMS / ATMS
| 最常考题 | 一句话答案 | 关键路径 | 出自 |
|---------|-----------|---------|------|
| 冷启动链路 | Zygote fork -> ActivityThread.main -> Looper 起 -> attach -> ATMS.attachApplication -> scheduleTransaction 启动根 Activity | ActivityStackSupervisor::realStartActivityLocked | 主篇 |
| oom_adj 如何计算 | AMS `computeOomAdjLSP` 按进程状态/可见性/前台服务打分，LMKD 据此杀进程 | com.android.server.am.OomAdjuster | 主篇 |
| 四大组件调度入口 | 均在 system_server 的 AMS/ATMS，经 Binder 调回 App 的 ApplicationThread | ActivityManagerService | 主篇 |

### 4. WMS / SurfaceFlinger / 渲染
| 最常考题 | 一句话答案 | 关键路径 | 出自 |
|---------|-----------|---------|------|
| VSync 如何驱动绘制 | Choreographer 注册 VSYNC 回调，App 在 `doFrame` 做 input/animation/traversal 三阶段；SF 在 `onMessageReceived` 合成 | frameworks/base/core/java/android/view/Choreographer.java | 主篇/深挖篇 |
| HWC Overlay vs GPU 合成 | SF 把 Layer 丢给 HWC（硬件 composer）尽量走 Overlay，无法处理（圆角/模糊/旋转）才回退 GPU（RenderEngine GL/Vulkan）| SurfaceFlinger::composeSurfaces / RenderEngine | 4/8 篇 |
| View 测量为何两次 | 父 View 在 `measure` 用 MeasureSpec 约束子 View，wrap_content 需要子先量一次拿真实尺寸再约束父 | View::measure / onMeasure | 主篇 |

### 5. App 启动 / Zygote
| 最常考题 | 一句话答案 | 关键路径 | 出自 |
|---------|-----------|---------|------|
| Zygote 为什么用 socket 不用 Binder | Zygote 进程先 fork 再初始化，Binder 线程有复杂状态无法安全跨 fork 继承，socket 简单无锁 | frameworks/base/core/java/com/android/internal/os/ZygoteServer.java | 主篇 |
| 启动慢怎么定位 | Perfetto 抓 `am` 起点 -> bindApplication -> 首帧；看 `ActivityThread.handleBindApplication` 里 ContentProvider 初始化、MultiDex | Perfetto trace | 主篇/5 篇 |

### 6. 内存 / 卡顿 / ANR
| 最常考题 | 一句话答案 | 关键路径 | 出自 |
|---------|-----------|---------|------|
| ANR 触发三路径 | Input 5s / Broadcast 前台 10s 后台 60s / Service 20s；本质是主线程超时未响应 | ActivityManagerService::appNotResponding | 主篇 |
| 三条杀进程路径辨析 | ①内核 OOM ②LMKD(PSI 压力) ③A17 Memory Limiter 个体内存超标静默杀 | LMKD / MemoryLimiter | 11/7 篇 |
| 卡顿怎么量化 | Looper Printer 卡 >16ms、Choreographer FrameCallback 掉帧、Perfetto 看 Binder/CPU | BlockCanary / Matrix | 5 篇 |

### 7. ART / JIT / AOT
| 最常考题 | 一句话答案 | 关键路径 | 出自 |
|---------|-----------|---------|------|
| odex/vdex/art 区别 | vdex 存验证字节码去重、odex 存编译后机器码、art 存类元数据镜像加速加载 | dex2oat / art/runtime/oat* | 6/8 篇 |
| A17 分代 GC | CMC（并发标记压缩）之上加 young/old 分代，经 art apex Mainline 热更 | art/runtime/gc/collector | 7 篇 |
| 对象头与锁 | Object 头含 MarkWord(29+3) + Klass ptr；偏向锁/轻量锁/重量锁状态机 | art/runtime/monitor | 深挖篇 |

### 8. HAL / Treble / 内核 / driver
| 最常考题 | 一句话答案 | 关键路径 | 出自 |
|---------|-----------|---------|------|
| HIDL -> AIDL stable | Treble 隔离 vendor/system，AIDL stable 接口向后兼容、可版本化 | hardware/interfaces / aidl | 主篇/2 篇 |
| GKI / KMI | 通用内核镜像 + 内核模块接口稳定，vendor 模块可跨 GKI 版本加载 | drivers/android/binder.c (GKI) | 深挖篇 |
| ION -> DMA-BUF | ION 已弃用(2025-12 EOL)，迁 DMA-BUF heaps，每堆独立 SELinux | drivers/dma-buf/heaps | 10 篇 |

### 9. Compose（A17 Compose-First）
| 最常考题 | 一句话答案 | 关键路径 | 出自 |
|---------|-----------|---------|------|
| 重组如何跳过 | 编译器注入 $changed 位掩码 + 稳定性推断（stable 类型才能跳过），强跳过三要素：判定+稳定+指标 | androidx.compose.runtime | 12 篇 |
| SlotTable 数据结构 | 平坦 IntArray 的 gap buffer，存 group key/value，重组时按位置读 | SlotTable | 12 篇 |
| Compose 与 View 接缝 | AndroidComposeView 在 View 树里就是一个普通 View；WindowRecomposer 泄漏坑 | AndroidComposeView | 12 篇 |

### 10. 安全（TEE / pKVM / Keystore）
| 最常考题 | 一句话答案 | 关键路径 | 出自 |
|---------|-----------|---------|------|
| Trusty 世界切换 | SMC 指令切 NS->Secure(EL3 做 monitor)，TIPC 而非 Binder 通信 | system/core/trusty | 10 篇 |
| pKVM vs TEE | pKVM 在 EL2 用 stage-2 页表隔离，TCB ~1万行；短板：无早期启动/无安全外设/仅 ARM64/内存成本 | arch/arm64/kvm/hyp | 11 篇 |
| Key Attestation | X.509 扩展 OID 1.3.6.1.4.1.11129.2.1.17，RootOfTrust 链到 RKP/DICE | keystore2 | 10 篇 |

### 11. 端侧 AI / AAOS 座舱
| 最常考题 | 一句话答案 | 关键路径 | 出自 |
|---------|-----------|---------|------|
| LiteRT NPU delegate | TFLite 把算子图卸载到 NPU，不支持的算子回退 CPU/GPU（delegate 选择 fallback）| tensorflow/lite/delegates | 6/13 篇 |
| INT4 量化代价 | 权重 4bit 显存减半但精度掉；group-wise + 校准集缓解；KV cache 也可量化 | llama.cpp quantize | 13/15 篇 |
| CarService 电源状态机 | ON -> SHUTDOWN_PREPARE -> SUSPEND/HIBERNATION -> OFF；VHAL AP_POWER_STATE_* 驱动 | packages/services/Car | 13/15 篇 |

---

## 二、易错点红榜（跨篇 TOP 25）

1. **Binder 跨 pVM / AppFunctions Provider 侧 getCallingUid 是 SYSTEM_UID，不可信**（11/12 篇）
2. **A17 MessageQueue Lock-free** 重构，反射改消息队列的野路子全废（7 篇）
3. **static final 在 A17 被视作真不可变**，编译期内联后改值不生效（7 篇）
4. **View 测量两次**不是 bug，是 wrap_content 约束必需的（主篇）
5. **Zygote 用 socket 不用 Binder** 是 fork 安全，不是历史包袱（主篇）
6. **HWC Overlay 失败才回退 GPU**，不是所有图层都 GPU 合成（4 篇）
7. **三条杀进程路径**混淆：内核 OOM / LMKD-PSI / A17 Memory Limiter 个体杀（11 篇）
8. **hiddenapi 三档**：light/dark/black greylist，A17 封死 final 接口（7 篇）
9. **platform_compat 是行为变更引擎**，@ChangeId/@EnabledSince + ART disabled_compat_changes_（9 篇）
10. **BAL 区分 callingUid vs realCallingUid** 防 confused deputy，ALLOW_IF_VISIBLE（9 篇）
11. **Compose 带返回值的 Composable 不是重组边界**（12 篇）
12. **Compose 强制单遍测量**，嵌套滚动/SubcomposeLayout 是特例（12 篇）
13. **ART 分代 GC 经 art apex Mainline 热更**，可不经 OTA 升级（7 篇）
14. **ION 已弃用**，新代码必须 DMA-BUF heaps（10 篇）
15. **Keystore2 是 Rust 实现**，KeyMint 走 AIDL HAL 到 TA（10 篇）
16. **pKVM 跨 VM getCallingUid 不可信**，与内核 Binder 六点差异（11 篇）
17. **Memory Limiter 杀的是"个体超标"**，LMKD 杀的是"整体压力"，别混（11 篇）
18. **APK 签名 v2 摘要覆盖三段且改 EOCD 中央目录偏移**，v1 只 Jar 签名易篡改（12 篇）
19. **ApplicationExitInfo 三条死因来源**不同：内核/LMKD/MemoryLimiter（12 篇）
20. **无障碍 ACTION_CLICK 不是注入 MotionEvent**，是语义动作（12 篇）
21. **Robolectric 用 shadow 假 framework，Ravenwood 跑 AOSP 真身**（14 篇）
22. **Codec2(CCodec) 取代 OMX(ACodec)**，C2Work 异步队列模型（4 篇）
23. **Widevine L1 才硬件解密，L3 软件**，secure buffer 不入 CPU 可寻址内存（10 篇）
24. **16KB 页面 A15/16 强制 + 兼容模式**，native 库需重编（5 篇）
25. **AVF / AISeal 采集链路在 host 不受保护**，保护边界只到 VM 内推理（11 篇）

---

## 三、面试官高频追问链（10 条）

**链 1 · Binder**
主：一次拷贝原理 -> 追：mmap 映射的是内核还是对方用户态？-> 追：binder 线程池耗尽为何 ANR？-> 追：跨 pVM 时 uid 还可信吗？

**链 2 · Handler**
主：同步屏障用途 -> 追：异步消息怎么标记？-> 追：A17 为什么改 Lock-free？-> 追：老代码反射插消息现在为何崩？

**链 3 · 启动**
主：冷启动从 Zygote 到首帧 -> 追：为什么 socket 不用 Binder？-> 追：ContentProvider 在 bindApplication 何时初始化？-> 追：MultiDex 卡在哪？

**链 4 · 渲染**
主：VSync 怎么驱动绘制 -> 追：Choreographer 三个回调顺序 -> 追：SF 何时走 HWC 何时走 GPU？-> 追：掉帧在 Perfetto 看什么？

**链 5 · GC/内存**
主：ART GC 类型 -> 追：CMC 怎么并发标记压缩？-> 追：A17 分代 GC 加了什么？-> 追：三条杀进程路径怎么区分？

**链 6 · Compose**
主：重组为什么能跳过 -> 追：$changed 位掩码怎么来？-> 追：强跳过三要素 -> 追：Compose 在 View 树里是什么？

**链 7 · 安全世界**
主：Trusty 怎么切世界 -> 追：TIPC 为什么不是 Binder？-> 追：Key Attestation 链到哪？-> 追：pKVM 比 TEE 强在哪弱在哪？

**链 8 · HAL/Treble**
主：HIDL 为什么改 AIDL stable -> 追：vendor/system 怎么隔离？-> 追：GKI/KMI 解决什么问题？-> 追：ION 为什么弃用？

**链 9 · 端侧 AI**
主：NPU delegate 怎么卸载算子 -> 追：不支持的算子怎么办？-> 追：INT4 量化代价 -> 追：KV cache 能不能量化？

**链 10 · 座舱**
主：CarService 电源状态机 -> 追：VHAL 怎么驱动状态？-> 追：hibernation 与 GarageMode 关系 -> 追：多显示掉电时序？

---

## 四、AOSP 源码路径速查（按子系统）

```
Binder 驱动        : drivers/android/binder.c, binder_alloc.c
Native Binder      : frameworks/native/libs/binder/{IPCThreadState,Binder}.cpp
Handler/Looper     : frameworks/base/core/java/android/os/{Handler,Looper,MessageQueue}.java
AMS/ATMS           : frameworks/base/services/core/java/com/android/server/am/{ActivityManagerService,ActivityStackSupervisor}.java
                     frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java
WMS                : frameworks/base/services/core/java/com/android/server/wm/{WindowManagerService,DisplayContent,LetterboxUiController}.java
Choreographer      : frameworks/base/core/java/android/view/Choreographer.java
SurfaceFlinger      : frameworks/native/services/surfaceflinger/{SurfaceFlinger,Scheduler}.cpp
RenderEngine       : frameworks/native/services/surfaceflinger/RenderEngine/{GL,skia,vulkan}
Zygote             : frameworks/base/core/java/com/android/internal/os/{Zygote,ZygoteServer}.java
ART GC             : art/runtime/gc/collector/{ConcurrentCopying,GenerationalCC}.cc
ART 镜像           : art/runtime/oat*, dex2oat
LMKD               : system/core/lmkd/lmkd.cpp
Keystore2          : system/security/keystore2 (Rust)
Trusty             : system/core/trusty, drivers/trusty
pKVM/AVF           : arch/arm64/kvm/hyp/, packages/modules/Virtualization/
Compose 运行时      : androidx.compose.runtime (SlotTable, Snapshot, Recomposer)
AppFunctions       : frameworks/base/.../appfunctions, packages/modules/AppSearch
platform_compat    : frameworks/base/services/core/java/com/android/server/compat/
CarService         : packages/services/Car/{service,packages/car-ui}
```

---

## 五、考前复习路径

### 临场前 3 天（系统级扫盲）
- Day 1：主篇(1) + 拓展篇(2) + 深挖篇(3) —— 把 Binder/AMS/WMS/SF/ART/内核主线过一遍
- Day 2：图形多媒体(4) + 系统基建(5) + 兼容性框架(9) —— 渲染/存储/行为变更引擎
- Day 3：安全世界(10) + pKVM(11) + 智能层(12) —— EL0-EL3 四层 + Compose + AppFunctions

### 临场前 1 天（速答 + 易错）
- 通读本文「一、高频考点速答表」+「二、易错点红榜」，每条能口述 30 秒答案
- 把「三、追问链」当成模拟面试，自问自答每条链 3 跳

### 临场当天（临阵磨枪）
- 只看「四、源码路径速查」确认文件名/方法名拼写
- 回忆红榜 TOP 10 最易错点，进场前在脑里过一遍

---

> 本卡为系列收官后的复习导航，不替代 15 篇原文。原文含完整答案解析、底层原理、源码佐证与延伸阅读，按「〇、知识地图」定位后精读。
