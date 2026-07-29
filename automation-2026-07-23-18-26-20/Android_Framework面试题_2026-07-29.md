# Android Framework 热点面试题深度解析（2026-07-29 · Android 17 行为变更新考点 + 真缺口补全）

> 基准版本：**Android 14 (UpsideDownCake, API 34)**，AOSP 分支 `android-14.0.0_rXX`，内核 GKI `android14-6.1`；涉及 Android 16/17 演进的会显式标注。
>
> 今日热点来源（2026-07-29 联网归纳）：
> - **Android 17（API 37, CinnamonBun）已于 2026-06-16 正式发布（stable）**，其 Framework 层破坏性变更集中爆发，正是 2026 下半年「系统/性能/平台/AI 终端」岗的高频新考点。本次聚焦最易被追问的几条：**Lock-free MessageQueue、ART 分代 GC、static final 真不可变（衔接 hiddenapi）、ProfilingManager 新触发器、后台音频加固 + 自定义通知限制**。
> - 同时补齐此前七篇**完全未覆盖的真缺口**：**ART hiddenapi / 非 SDK 接口管制、NFC / Secure Element 全链路、Media3 / ExoPlayer 底层、端侧 LLM 运行时（AICore / ODP）**。
> - 前七篇（主篇16章 / 拓展篇10章 / 深挖篇11章 / 图形多媒体篇12章 / 系统基建篇11章 / 端侧AI篇10章）已闭环 Binder、启动、WMS、View、ANR、Compose、HAL、Input、PMS、ART GC、Camera/Audio、16KB 页、权限、Keystore、AVB、logd、Doze、NNAPI、CarService、Vulkan、virtual A/B 等。本篇按"Android 17 新雷区 + 真缺口"双线推进，并在第 9 章给出八篇交叉索引，避免重复、提示复习路径。
>
> 面试定位：能把"Handler/Looper 经典题"答出 **Android 17 lock-free MessageQueue 的破坏性**，把"内存优化题"答出 **ART 分代 GC + Mainline 热更**，把"插件化/Hook 题"答出 **hiddenapi 名单机制 + A17 final 封死**，基本能把面试官按在地上摩擦。

---

## 目录

1. [Android 17 Lock-free MessageQueue：Handler/Looper 的新雷区（热点）](#1)
2. [ART 分代 GC（Generational GC）：CMC 之后的又一次 GC 升级（热点）](#2)
3. [Static final 真不可变 + ART hiddenapi / 非 SDK 接口管制（真缺口·核心）](#3)
4. [ProfilingManager 新触发器：性能可观测性的平台级武器（热点）](#4)
5. [后台音频加固 + 自定义通知限制：Framework 后台行为再收紧（热点）](#5)
6. [NFC / Secure Element 全链路：刷公交/卡模拟走哪条路（真缺口）](#6)
7. [Media3 / ExoPlayer 底层机制：构建在 MediaCodec 之上的播放流水线（真缺口）](#7)
8. [端侧 LLM 运行时：Gemini Nano / AICore / OnDevicePersonalization（真缺口）](#8)
9. [查缺补漏 · 易错点速记 · 高频追问 · 八篇交叉索引](#9)

---

<a id="1"></a>
## 1. Android 17 Lock-free MessageQueue：Handler/Looper 的新雷区（热点）

**Q：Android 17 把 `android.os.MessageQueue` 改成了 lock-free，为什么？对 Handler/Looper 机制意味着什么？哪些老代码会直接挂？**

### 答案解析

这是 Android 17（targetSdk 37+）对 **Handler/Looper 这条万年经典面试题线** 的首次破坏性重构，必须会答。

**经典实现（A17 之前）：** 看 `frameworks/base/core/java/android/os/MessageQueue.java`：
- `enqueueMessage(Message msg, long when)`：`synchronized (this)` 加锁，把消息按 `when` 插入单向链表 `mMessages`。
- `next()`：`synchronized (this)` 加锁，从 `mMessages` 取头节点；队列空时通过 `nativePollOnce(ptr, nextPollTimeoutMillis)` 进入 epoll 阻塞等待（底层 `frameworks/base/core/jni/android_os_MessageQueue.cpp` → `system/core/libutils` 的 `Looper.cpp` epoll）。
- 主线程 `Looper.loop()`（`frameworks/base/core/java/android/os/Looper.java`）就是 `for (;;) { Message msg = queue.next(); ... msg.target.dispatchMessage(msg); }`——它是 epoll **空闲等待**而非忙等，这正是"主线程 loop 不 ANR"的根因。

**A17 新实现（lock-free）：** SDK 37+ 的应用拿到一套**无锁（lock-free）的 MessageQueue**。动机很直接——主线程就是 UI 线程，当后台线程高频 `handler.post()` / `sendMessage()` 时，经典实现下 `enqueueMessage` 的 `synchronized (this)` 会和主线程 `next()` 的锁**竞争**，极端情况下阻塞 UI 线程取消息，导致掉帧（missed frames）。无锁入队让"后台 post"不再阻塞"主线程消费"，降低掉帧。

**边界与不变：**
- `Handler` / `Looper` / `Message` 的公开 API 契约不变；`postDelayed` 的"基于 `SystemClock.uptimeMillis()` 的定时 + `MessageQueue` 按 `when` 排序"原理不变。
- `IdleHandler`（`addIdleHandler`）、`Barrier`（同步屏障，`postSyncBarrier`/`removeSyncBarrier`，驱动 vsync/Choreographer 的异步消息）语义保留，但内部实现随无锁化重写。
- **被打破的是"反射私有实现"**：任何直接读 `mMessages`、`mBlocked`、`mQuitAllowed` 字段，或调用 `MessageQueue` 私有方法的代码，在 A17 上会抛 `NoSuchFieldException` / `IllegalAccessException`。

### 易错点
- 「MessageQueue 一直是 lock-free 的」——**错**。A17 之前是 `synchronized (this)`，无锁化是 A17 新引入。
- 「升级 A17 不影响老代码」——**错**。大量自研 Handler 监控、旧版 LeakCanary、靠反射 `mMessages` 数消息的第三方 SDK 会崩。
- 「lock-free = 绝对更快」——**错**。主受益是"后台 post 不再阻塞主线程取消息→少掉帧"，不是消弭所有同步开销。

### 高频追问
- 怎么排查自己 App 是否踩雷？→ 全局搜 `MessageQueue` 反射 / `mMessages` / `getDeclaredField("mMessages")`；升级到 A17 官方给出的 *MessageQueue behavior change guidance* 做兼容。
- 无锁队列底层怎么写？→ 可延伸到 Michael-Scott 无锁队列 / CAS（`AtomicReference` 头节点）；但 AOSP 实际是用更精细的原子字段 + 单消费者（主线程）假设优化。
- `postDelayed` 的定时精度受无锁化影响吗？→ 不受影响，时间轴仍由 `uptimeMillis` + epoll 超时驱动。

### 延伸阅读
- `frameworks/base/core/java/android/os/MessageQueue.java`、`Looper.java`
- `frameworks/base/core/jni/android_os_MessageQueue.cpp`
- developer.android.com → "MessageQueue behavior change guidance"（Android 17）

---

<a id="2"></a>
## 2. ART 分代 GC（Generational GC）：CMC 之后的又一次 GC 升级（热点）

**Q：Android 17 给 ART 的 GC 加了"分代回收"，它和之前的 CMS、CMC 什么关系？为什么能降 CPU 占用？能通过 Mainline 热更到老设备吗？**

### 答案解析

这是把"内存/卡顿/ANR 优化"经典题答出 **2026 新深度** 的关键。

**ART GC 演进时间线：**
- Android 4–6：**CMS（Concurrent Mark-Sweep）**，实现于 `art/runtime/gc/collector/mark_sweep.cc`。并发标记但**不压缩**，靠 card table / remembered set 处理跨代，长期运行产生**堆碎片**，且需 STW 的 mark/sweep。
- Android 7–11：引入多种 Compacting GC（SemiSpace / Generational SemiSpace / MarkCompact）。
- **Android 12 起默认 CMC（Concurrent Mark-Compact）**，实现于 `art/runtime/gc/collector/concurrent_copying.cc`（`ConcurrentCopying` 类）。并发标记 + **并发搬运**，消除碎片、暂停极短；通过 forwarding pointer + 读屏障/短暂冻结线程完成对象移动。核心调度在 `art/runtime/gc/heap.cc`（`art/runtime/gc/heap.cc` 的 `CollectGarbage` / `GarbageCollect`）。
- **Android 17：在 CMC 之上加 Generational GC（分代）**——划分 young / old 代。绝大多数对象"朝生夕死"，只回收 young gen（频率高、成本低），full-heap（old gen）收集显著变少。

**为什么降 CPU：**
- 经典 CMC 是 **full-heap** 收集——哪怕 99% 对象是短命的，每次也要遍历+搬运整堆。大堆 App（游戏、大型应用）GC 抖动（大量 young 对象反复创建）会拖高 CPU、造成掉帧。
- 分代后：young GC 只扫新生代（很小一块），old GC 才扫全堆且频率大幅下降。整体 GC CPU cost 与时长下降，App 启动更快、多任务更顺、续航更好。

**Mainline 热更是亮点：** ART 已是 Mainline 模块（`com.google.android.art` apex）。Android 17 的 Generational GC 通过 **Google Play System 更新下放到 Android 12（API 31）+ 的 10 亿+ 设备**——即 GC 改进不依赖整机 OTA，靠 ART 模块热更。这是面试"Framework 模块化/Mainline"的高阶衔接点（参考系统基建篇的 Mainline 思想）。

**跨代引用：** young/old 之间用 remembered set / card table（`art/runtime/gc/accounting/`）记录，避免 young GC 时全堆扫描 old 代引用。

### 易错点
- 「ART 用的是 JVM 的 GC」——**错**。ART 自研 GC，且"无分代→分代"是 A17 才加的。
- 「CMS 还在默认用」——**错**。Android 12 起默认 CMC，CMS 仅极少数兼容回退。
- 「分代 = 没有 full GC」——**错**。old gen 满仍触发 full-heap 收集。

### 高频追问
- CMC 和 CMS 区别？→ CMC 是 compacting（消碎片、对象搬运用 forwarding pointer + 读屏障），CMS 是 mark-sweep（有碎片、不搬迁）。
- 分代怎么处理跨代引用？→ remembered set / card table 记录 old→young 引用。
- 线上怎么分析 GC 导致的掉帧？→ `adb shell am dumpheap` / SIGQUIT(`kill -10 <pid>`) 拿 trace 看 GC 原因；Perfetto 抓 `art` GC 事件（见深挖篇 Perfetto SQL 实战）。

### 延伸阅读
- `art/runtime/gc/collector/concurrent_copying.cc`、`mark_compact.cc`
- `art/runtime/gc/heap.cc`、`art/runtime/gc/accounting/`
- source.android.com → "ART garbage collection"；Android 17 Generational GC 公告

---

<a id="3"></a>
## 3. Static final 真不可变 + ART hiddenapi / 非 SDK 接口管制（真缺口·核心）

**Q：Android 为什么要限制"非 SDK 接口"（反射调 @hide / 系统私有 API）？hiddenapi 名单怎么分级？Android 17 又加了 static final 不可变，底层怎么强制的？**

### 答案解析

这是**插件化 / Hook / 热修复 / 兼容性**这条高频线的底层根因，此前七篇从未系统讲过，今天补全。

**什么是"非 SDK 接口"：** 一切**不在公开 SDK 里**的类/方法/字段——包括被 `@hide` 标注的、以及 SDK 故意不暴露的内部成员。App 用反射拿它们，一旦 Google/厂商改内部实现，App 就崩，破坏系统升级兼容性。

**hiddenapi 名单分级（构建期由 `frameworks/base/config/` 下的名单 + `hiddenapi` 标志生成元数据）：**
- **light greylist（浅灰）**：可访问，未来版本可能提示警告（目标 SDK 低于某阈值时）。
- **dark greylist（深灰）**：**目标 SDK ≥ 阈值后禁止访问**（抛 `NoSuchMethodException` 等），低版本仍可。
- **blacklist（黑）**：无论目标 SDK 都**禁止访问**。
- 还有按 max target SDK 限制的 `greylist-max-*` 系列。

**运行时强制（在 ART native 层，不在 framework 层）：** `art/runtime/hidden_api.cc` 的 `ShouldDenyAccessToMember<T>()` + `GetMemberAction()`，依据调用者的隐藏 API 策略（`ApplicationInfo.hiddenApiEnforcementPolicy`，来自 `dalvik.vm.hidden-api-policy` 或 `targetSdkVersion`）返回 `kAllow` / `kAllowWithWarning` / `kDeny`。检查插桩在 `FindClass` / `GetMethodID` / `GetDeclaredMethod` 等反射与 JNI 入口，靠 `hidden_api.h` 的 `kLightGreylist`/`kDarkGreylist`/`kBlacklist` 标志 + dex 中 `hiddenapi` 元数据判定。**反射和 JNI 两条路都被拦。**

**绕过与对抗（老招式，逐渐失效）：**
- `VMRuntime.setHiddenApiExemptions(...)` 把某些签名加入豁免（需特殊权限，普通 App 拿不到）。
- 改 `runtime flags`（`adb shell cmd -d` 调试态）。
- **Android 17 进一步封死 static final：** `static final` 字段在 SDK 37+ 上通过反射修改会 `IllegalAccessException`；通过 JNI `SetStatic*Field`（如 `SetStaticLongField`）修改会**直接 crash**。原因：ART 把 `static final` 当编译期常量做更激进优化（内联、常量传播），一旦允许运行时改，优化结果就错；A17 在 verifier/runtime 层直接 DENY，对应 `kAccFinal` 标志检查（`art/runtime/reflection.cc` 的设值路径 / `art/runtime/jni/` 的 `SetStatic*Field`）。

**与插件化/Hook 衔接：** 老 Hook 框架（VirtualXposed 等）靠反射私有 + 改 final 实现；A17 下这类路径更难。现代热修复（如 native method entrypoint 替换）走 art method 指针（`ArtMethod::entry_point_from_quick_compiled_code_`）而非改 final 字段，因此不踩这条限制。

### 易错点
- 「非 SDK 接口 = @hide 方法」——**不完整**。是"所有不在公开 SDK 的成员"，含**字段**。
- 「深灰名单永远禁止」——**错**。是"目标 SDK ≥ 阈值才禁止"。
- 「A17 之前能改 static final」——A17 之前靠改 `modifiers` 反射可改；**A17 封死**。
- 「hiddenapi 只在 framework 层」——**错**。强制在 ART runtime（native）层，反射和 JNI 都拦。

### 高频追问
- 怎么自查 App 用了非 SDK 接口？→ 用官方 **`veridex`** 工具扫 apk；Google Play 上架也会扫并警告。
- 热修复在 A17 怎么存活？→ 走 art method entrypoint 替换（native 层），不动 final 字段，不碰 hiddenapi 黑名单。
- Tinker 的 dex 插桩和 final 限制有关系吗？→ Tinker 走 dex 全量/差量替换，不直接改 final 字段，故不受此限。

### 延伸阅读
- `art/runtime/hidden_api.cc` / `hidden_api.h`
- `frameworks/base/config/hiddenapi-*`
- developer.android.com → "Non-SDK interface restrictions"；官方 `veridex` 工具

---

<a id="4"></a>
## 4. ProfilingManager 新触发器：性能可观测性的平台级武器（热点）

**Q：Android 17 给 `ProfilingManager` 加了哪些系统触发器？相比以前的手动 Profiler，对线上性能分析意味着什么？**

### 答案解析

这是把"性能可观测性"题从"自研埋点"升级到"平台原生事件驱动采集"的关键，衔接系统基建篇的 Looper Printer / BlockCanary / Matrix。

**API 与定位：** `android.os.ProfilingManager`（`frameworks/base/core/java/android/os/ProfilingManager.java`）——App 注册性能采集请求，系统在**特定事件触发**时自动抓 profile（system trace / heap / java heap dump 等）。

**A17 新增触发器（热点）：**
- `TRIGGER_TYPE_COLD_START`：冷启动时自动抓。
- `TRIGGER_TYPE_OOM`：被 LMKD 回收前后自动抓（衔接系统基建篇 LMKD/PSI）。
- `TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE`：CPU 占用过高被杀时自动抓。

**系统服务端：** 由 system server 中的 Profiling 相关 controller 监听这些事件并触发采集（`frameworks/base/services/core/java/com/android/server/am/` 下对应逻辑，经 `IProfilingService` binder）。

**价值：** 以前线上卡顿/ANR/内存问题只能靠自采或用户反馈；现在可让系统在特定**失败点**自动抓 trace，直接拿现场数据。采集结果多为 **Perfetto trace**，可用 `trace_processor` + SQL 分析（见深挖篇 Perfetto SQL 实战）。

### 易错点
- 「ProfilingManager 是 Profiler GUI」——**错**。是程序化 API，平台按触发条件**自动采集**。
- 「只能手动触发」——**错**。A17 加了冷启动 / OOM / 高 CPU 三种自动触发器。

### 高频追问
- 采集数据格式？→ 多为 Perfetto / system trace。
- 隐私？→ 采集受权限与用户控制，企业 MDM 可下发。
- 和 Macrobenchmark 区别？→ Macrobenchmark 是本地基准测试；ProfilingManager 是线上**事件驱动**采集。

### 延伸阅读
- `frameworks/base/core/java/android/os/ProfilingManager.java`
- developer.android.com → "Trigger-based profiling"
- Perfetto 官方文档（trace_processor / SQL）

---

<a id="5"></a>
## 5. 后台音频加固 + 自定义通知限制：Framework 后台行为再收紧（热点）

**Q：Android 17 对后台音频交互和自定义通知视图做了什么限制？背后是哪些服务/类在管？**

### 答案解析

这条和"系统基建篇"的 WakeLock / Doze / JobScheduler 一脉相承——Android 持续收紧后台行为以提升续航与体验，是 Framework 面试"后台限制演进"高频线。

**后台音频加固（A17）：** 当 App **不在有效生命周期**（非前台 / 无活跃媒体会话）时调用以下 API 会**静默失败（不抛异常）**：
- 播放：`AudioTrack` / `MediaPlayer`
- 焦点：`AudioManager.requestAudioFocus(AudioFocusRequest)` → 返回 `AUDIOFOCUS_REQUEST_FAILED`
- 音量：`AudioManager.setStreamVolume` 等

源码：`frameworks/base/media/java/android/media/AudioManager.java`、焦点决策在 service 侧 `frameworks/base/services/core/java/com/android/server/audio/`（`MediaFocusControl` / `AudioService`），"有效生命周期"由 AMS 的 activity/service 状态 + `AudioPlaybackConfiguration` 判定。

**自定义通知视图限制（A17, SDK 37+）：** 限制自定义通知视图尺寸，堵住用 URI 绕过旧限制的漏洞，降低通知内存占用。源码：`frameworks/base/core/java/android/app/Notification.java`（contentView / bigContentView / style 限制）、`frameworks/base/services/core/java/com/android/server/notification/NotificationManagerService.java`。

### 易错点
- 「音量 API 失败会抛异常」——**错**。A17 是**静默失败**（设计选择，避免后台崩）。
- 「自定义通知随便大」——**错**。A17 限制尺寸。

### 高频追问
- 怎么合规播放后台音频？→ 用 `MediaSession` + `MediaStyle` 通知 + 前台服务（`startForegroundService` + `MediaSessionCompat`），保持有效媒体会话。
- 大图/大视图通知替代？→ 用标准 `Notification.BigPictureStyle` / `BigTextStyle`，别自绘。

### 延伸阅读
- `frameworks/base/media/java/android/media/AudioManager.java`
- `frameworks/base/services/core/java/com/android/server/audio/`
- `frameworks/base/core/java/android/app/Notification.java`、`NotificationManagerService.java`

---

<a id="6"></a>
## 6. NFC / Secure Element 全链路：刷公交/卡模拟走哪条路（真缺口）

**Q：手机刷公交/卡模拟（HCE / SE）走的是哪条 Framework 链路？NFC 服务、HAL、Secure Element 怎么分工？和 Keymaster/Keystore2 是什么关系？**

### 答案解析

这是此前完全未覆盖的"近场通信 + 安全芯片"线，2026 车载/支付/门禁场景常考。

**App 层服务：** `packages/apps/Nfc/` 是系统级 NFC 应用，核心 `NfcService`（`packages/apps/Nfc/src/com/android/nfc/NfcService.java`）——处理 tag 发现、P2P、卡模拟路由。

**NFC HAL：** `android.hardware.nfc`（`hardware/interfaces/nfc/`，早期 HIDL 1.0/1.2，正迁移 AIDL）。`NfcService` 经 `INfc` HAL 与厂商 NFC 控制器（CLF，Contactless Front-end）通信，下发 **NCI**（NFC Controller Interface）指令。NCI 协议栈在 `packages/apps/Nfc/nci/`（旧路径 `system/nfc/`）。

**卡模拟两条路：**
1. **HCE（Host Card Emulation）**：卡逻辑跑在 **App 进程**（基于 `HostApduService`），经 `NfcService` → HAL 到 CLF。AOSP 原生支持，API 在 `frameworks/base/core/java/android/nfc/cardemulation/`（`CardEmulation`、`HostApduService`、`OffHostApduService`）。
2. **SE（Secure Element）**：卡逻辑跑在**硬件安全芯片**（eSE / UICC / SD）。代码 `packages/apps/Nfc/src/com/android/nfc/se/`（`SecureElement`、`EseAdapter`、`UiccAdapter`）。访问 SE 需系统级 `android.permission.SECURE_ELEMENT_PRIVILEGED`；SE 内 applet 由 TSM 下发。

**路由：** `OffHostApduService` 把 AID 路由到 SE；`HostApduService` 路由到 App。`NfcService` 维护 AID 路由表（`AidRoutingManager`）。

**与 Keystore2 / Keymaster 关系（衔接系统基建篇）：** SE、Keymaster、GateKeeper 都是安全硬件（TEE / 安全芯片）上的不同 **TA（Trusted Application）**。SE 跑支付 applet（App 拿不到密钥，只传 APDU）；Keymaster/Keystore2 管密钥（见系统基建篇 Keystore2 / Keymint HAL）；GateKeeper 管认证。三者职责隔离。

### 易错点
- 「刷公交就是 App 直接读卡」——**错**。是**卡模拟**（手机当卡），HCE 或 SE 模式。
- 「HCE 和 SE 一样安全」——**错**。SE 进安全芯片、硬件隔离；HCE 卡逻辑在 App 进程（root 机可攻），靠云端 token 化缓解。
- 「NFC 服务在 framework」——**错**。在 `packages/apps/Nfc`（app 级系统应用），经 HAL 通信。

### 高频追问
- 双卡/多 SE 怎么选？→ AID 路由 + `OffHostApduService` 指定 SE 类别。
- NFC 与支付安全？→ SE + 交易 token 化（如 Google Pay 用 HCE + 云端）。
- 读卡（tag）vs 卡模拟区别？→ 前者 `NfcAdapter.enableReaderMode` 读外部 tag；后者手机当卡。

### 延伸阅读
- `packages/apps/Nfc/`（NfcService.java、`se/`）
- `frameworks/base/core/java/android/nfc/`、`android/nfc/cardemulation/`
- `hardware/interfaces/nfc/`
- source.android.com → "Secure Element"

---

<a id="7"></a>
## 7. Media3 / ExoPlayer 底层机制：构建在 MediaCodec 之上的播放流水线（真缺口）

**Q：ExoPlayer 被并入 Media3 后，底层是怎么基于 MediaCodec 播放的？和老的 MediaPlayer 有什么区别？**

### 答案解析

这是"多媒体播放"线的 App 层补全（底层 Codec2/HAL 已在图形多媒体篇讲过）。

**关键定位：** Media3 是 **Jetpack 库**（`androidx.media3.*`），ExoPlayer 成为 `androidx.media3.exoplayer`。**注意：Media3/ExoPlayer 不在 AOSP framework 里**，它构建在 framework 的 `MediaCodec` / `MediaExtractor` / `AudioTrack` / `Surface` 之上。

**ExoPlayer 播放流水线：**
- **Extractor**：`ProgressiveMediaSource` + `DefaultExtractorsFactory` 用 `MediaExtractor`（`frameworks/base/media/java/android/media/MediaExtractor.java`）把容器（mp4/mkv）拆 track，分离出带 pts 的 sample。
- **Decoder**：视频走 `MediaCodec`（`frameworks/base/media/java/android/media/MediaCodec.java`，经 `MediaCodecList` 选 codec，底层 ACodec/Codec2 → 厂商 OMX/Codec2 HAL，见图形多媒体篇）；音频解码后 → `AudioTrack`。
- **Renderer**：`MediaCodecVideoRenderer` / `MediaCodecAudioRenderer` 按时序渲染；视频帧喂到 `Surface`（经 SurfaceFlinger 上屏，见图形篇），音频喂 `AudioTrack`（`AudioFlinger` 混音）。
- **Timeline / TrackSelector / LoadControl**：负责 seek、选轨、缓冲控制（DASH/HLS 自适应码率的 `AdaptiveTrackSelection`）。

**与 `MediaPlayer`（framework 老 API）对比：**
- `MediaPlayer` 是黑盒，内部也是 MediaExtractor + MediaCodec + **NuPlayer**（`frameworks/av/media/libmediaplayerservice/` 的 NuPlayer），但不可插拔、定制差。
- ExoPlayer 全 Java、**每一环可替换**（Extractor/Source/Renderer）、原生支持 DASH/HLS/SmoothStreaming、DRM（Widevine 经 `MediaDrm`）、自适应码率——这是它取代 MediaPlayer 的原因。

**A17 新适配（衔接端侧AI篇/图形篇）：** CameraX/Media3 更新；`CameraCaptureSession.updateOutputConfigurations()` 动态相机会话；VVC（H.266）支持。

### 易错点
- 「ExoPlayer 是 framework 类」——**错**。是 androidx 库，包名 `androidx.media3`。
- 「MediaPlayer 比 ExoPlayer 新」——**错**。MediaPlayer 更老、不可扩展；ExoPlayer 是官方推荐。
- 「视频帧直接画到 View」——**错**。解码到 Surface，经 SurfaceFlinger 合成上屏。

### 高频追问
- 为什么 ExoPlayer 比 MediaPlayer 流畅？→ 可控缓冲 + 自适应 + 可插拔 renderer。
- DRM 怎么接？→ `MediaSource` + `DrmSessionManager`（`MediaDrm` Widevine）。
- 音视频不同步？→ 音视频 renderer 时钟对齐（基于 `MediaClock` / `AudioTrack.getTimestamp`）。

### 延伸阅读
- `androidx.media3.exoplayer`（源码已并入 media 模块）
- `frameworks/base/media/java/android/media/MediaCodec.java`、`MediaExtractor.java`
- `frameworks/av/media/libmediaplayerservice/NuPlayer`

---

<a id="8"></a>
## 8. 端侧 LLM 运行时：Gemini Nano / AICore / OnDevicePersonalization（真缺口）

**Q：端侧大模型（如 Gemini Nano）在 Android 上怎么跑？AICore、OnDevicePersonalization(ODP) 各是什么？Framework 层涉及哪些模块？**

### 答案解析

这是 2026 最大增量考点（端侧 AI）的"运行时机理"补全（NNAPI/NPU 抽象层已在端侧AI篇讲过）。

**三层架构要分清：**
1. **AICore（Google 专有）**：运行 Gemini Nano 等端侧模型的基础设施，管理模型下载/更新（`com.google.android.aicore` apex）、推理调度、硬件加速（NPU，见端侧AI篇 NNAPI）。App 经 `com.google.ai.edge.aicore` / GenerativeAI API 调用。**非 AOSP**，仅 Pixel/部分设备。
2. **OnDevicePersonalization (ODP)** = `packages/modules/OnDevicePersonalization/`——**AOSP Mainline 模块**（包名 `android.ondevicepersonalization`），是端侧个性化/推理的**开放框架**。App 可在**隔离沙箱**内下发计算到端侧 ODP 运行时跑（数据不出端、保护隐私）。API：`OnDevicePersonalizationManager`（`android.ondevicepersonalization.OnDevicePersonalizationManager`），由 `packages/modules/OnDevicePersonalization/service/` 的 `OnDevicePersonalizationManagerService` 提供。
3. **NNAPI（抽象层）**：端侧 LLM 最终在 NPU/DSP 上跑，经 NNAPI 或厂商 delegate（端侧AI篇已讲）。

**大模型约束（衔接系统基建篇 16KB 页、深挖篇内存）：** 端侧 LLM 权重量大，依赖 **16KB 页 / 大内存 / 量化（INT4/INT8）/ KV-cache 管理**。NPU 带宽与 KV cache 是主要瓶颈。

**A17 衔接：** App 直接访问 NPU 需在 Manifest 声明 `FEATURE_NEURAL_PROCESSING_UNIT`（端侧AI篇已讲）；ART 分代 GC（第 2 章）也间接提升端侧推理的内存效率。

### 易错点
- 「Gemini Nano 是 AOSP 的」——**错**。是 Google 专有（AICore），仅部分设备。
- 「跑端侧 LLM 不用 NPU」——**错**。大模型靠 NPU 量化推理，否则 CPU 太慢、耗电。
- 「ODP 能随意读 App 数据」——**错**。ODP 设计即隔离沙箱、数据不出端、受隐私约束。

### 高频追问
- 和普通 SDK 推理（TF-Lite）区别？→ ODP/AICore 是平台级编排（模型管理/硬件调度更系统）；TF-Lite 是 App 自带 runtime。
- 端侧 LLM 的 Framework 瓶颈？→ 内存（KV cache）、NPU 带宽、模型量化精度。
- A17 对端侧 AI 还改了啥？→ 见端侧AI篇（NPU 声明）+ 第 2 章 Generational GC。

### 延伸阅读
- `packages/modules/OnDevicePersonalization/`
- developer.android.com → "On-device personalization"
- "AI Core" / Gemini Nano 公告；端侧AI篇（NNAPI / NPU delegate）

---

<a id="9"></a>
## 9. 查缺补漏 · 易错点速记 · 高频追问 · 八篇交叉索引

### 9.1 本篇 8 个专题速览
| # | 专题 | 类型 | 核心 AOSP 落点 |
|---|------|------|----------------|
| 1 | Lock-free MessageQueue | Android 17 热点 | `MessageQueue.java` / `android_os_MessageQueue.cpp` / `Looper.java` |
| 2 | ART 分代 GC | Android 17 热点 | `gc/collector/concurrent_copying.cc` / `gc/heap.cc` / `art` apex(Mainline) |
| 3 | hiddenapi + static final | 真缺口·核心 | `art/runtime/hidden_api.cc` / `frameworks/base/config/hiddenapi-*` |
| 4 | ProfilingManager 触发器 | Android 17 热点 | `ProfilingManager.java` / `services/core/.../am/` |
| 5 | 后台音频 + 通知限制 | Android 17 热点 | `AudioManager.java` / `Notification.java` / `NotificationManagerService` |
| 6 | NFC / Secure Element | 真缺口 | `packages/apps/Nfc/` / `android.hardware.nfc` |
| 7 | Media3 / ExoPlayer | 真缺口 | `androidx.media3` / `MediaCodec.java` / `MediaExtractor.java` |
| 8 | 端侧 LLM(AICore/ODP) | 真缺口 | `packages/modules/OnDevicePersonalization/` / AICore(专有) |

### 9.2 易错点速记（背这 8 条）
1. **MessageQueue 不是天生无锁**——A17 才 lock-free，反射 `mMessages` 会崩。
2. **ART GC 不是 JVM**——无分代→分代是 A17 加的；12 起默认 CMC 不是 CMS。
3. **hiddenapi 强制在 ART native 层**——反射和 JNI 都拦；深灰是"目标SDK≥阈值才禁"。
4. **A17 static final 真不可变**——反射改抛异常、JNI 改直接 crash。
5. **ProfilingManager 是事件驱动自动采集**——非 GUI，A17 加冷启动/OOM/高CPU 触发。
6. **后台音频失败是静默失败**——不抛异常，AudioFocus 返回 FAILED。
7. **HCE≠SE 安全等级**——SE 进安全芯片，HCE 卡逻辑在 App 进程。
8. **ExoPlayer 是 androidx 库**——构建在 MediaCodec 之上，不在 framework。

### 9.3 八篇交叉复习索引（避免重复、规划路径）
- **主篇(16章, 07-23)**：Binder/启动/WMS/View/ANR/Compose/HAL/GKI/MTK —— 主线地基。
- **拓展篇(10章, 07-23)**：Input 全链路 / PMS / ART-JIT-AOT / SystemUI / 折叠屏WM / SELinux / OTA-AB / JNI-hook / Binder安全 / Perfetto —— 盲区补强。
- **深挖篇(11章, 07-23)**：ART 对象头/CMC GC / Binder 调试 / Rust Binder / Input 多指 / VSync / Camera HAL / Audio / GKI KMI / Perfetto SQL —— 深水区。
- **图形多媒体通信篇(12章, 07-24)**：HWUI / Choreographer / SurfaceFlinger / 图形内存 / 多刷新率 / MediaCodec / Codec2 / Thermal / PowerHAL / RIL / Wi-Fi / BT。
- **系统基建与可观测性篇(11章, 07-27)**：16KB 页 / ClassLoader插件化 / 权限 / Keystore2 / AVB / Vold / logd / 可观测性 / RRO / Doze-JobScheduler / A15-16 变更。
- **端侧AI与Android17演进篇(10章, 07-28)**：NNAPI/NPU / LiteRT delegate / A17 NPU声明 / CarService / Vulkan-ANGLE / ART oat布局 / virtual A/B / A16-17 变更。
- **本篇(8章, 07-29)**：A17 Lock-free MessageQueue / ART 分代GC / hiddenapi+static final / ProfilingManager / 后台音频+通知 / NFC-SE / Media3-ExoPlayer / 端侧LLM(AICore-ODP)。

**经典题→新深度映射（复习时对照答）：**
- Handler/Looper 题 → 本篇第 1 章（A17 lock-free 破坏性）+ 主篇 Binder。
- 内存/卡顿/ANR 题 → 本篇第 2 章（分代GC）+ 系统基建篇（LMKD/PSI）+ 拓展篇 Perfetto。
- 插件化/Hook/热修复题 → 本篇第 3 章（hiddenapi + A17 final 封死）+ 系统基建篇 ClassLoader。
- 多媒体播放题 → 本篇第 7 章（ExoPlayer）+ 图形多媒体篇（MediaCodec/Codec2/SurfaceFlinger）。
- 端侧 AI 题 → 本篇第 8 章（AICore/ODP）+ 端侧AI篇（NNAPI/NPU）。

### 9.4 后续可轮换的真·未覆盖角度
- **Codec2 vendor plugin 开发**（深抠 `CCodec`/`C2Component` 厂商扩展）
- **SF RenderEngine Vulkan 后端**（`RenderEngine` + `GpuFence` + Vulkan HAL）
- **LiteRT NPU delegate 源码走读**（TF-Lite → NNAPI delegate 选择逻辑）
- **CarService 多用户/多显示**（`CarUserService` / `CarInputService` 深挖）
- **ART 镜像 odex 布局深水区**（`.art`/`.vdex`/`.oat` 头部结构与 `dex2oat` 参数）

---

> 八篇至此已闭环主线(16)+盲区(10)+深水区(11)+图形多媒体通信(12)+系统基建可观测性(11)+端侧AI演进(10)+A17新雷区与真缺口(8)，合计 **78 个专题**。建议按"经典题→新深度映射"对照复习，面试时优先抛 A17 新点建立区分度。
