# Android Framework 面试题 · A18 桌面融合与跨设备协同 Framework 深挖（2026-08-08）

> 系列第 **22** 篇。前 21 篇（约 148 专题）已闭环主线 + 盲区 + 深水区 + 智能层 + 安全世界（EL3 Trusty）+ 机密计算（EL2 pKVM/AVF）+ 座舱（AAOS）+ 端侧 AI + 速查卡 + 连击考 + 全链路排查 + 源码级 code walk + Perfetto SQL 范例库。
>
> 本篇落点：**A18 路线图里最值得提前押注的面试增量 = 桌面融合（Desktop Convergence）+ 跨设备协同（Cross-device Handoff / Universal Clipboard / Companion Device）**。这二者不是孤立新功能，而是把前面 21 篇讲过的 WMS / WindowOrganizer / AMS / Binder / AAOS 多显示 / 座舱电源 / AppFunctions 智能层全部"横向拉通到多设备、多窗口"的集大成形态。本篇以它为主轴，逐专题带 AOSP 源码路径 + 方法名佐证 + 易错点 + 高频追问，并补齐与既有体系的连接点。

---

## 0. 当日热点锚定（2026-08-08）

- **平台基线**：A17（CinnamonBun）已于 2026-06-16 stable；A17 QPR2 stable 预计 2026-12 随 Pixel Feature Drop 落地（build 线 `CP41.260701.x`）。**所有原理性八股（Handler/Looper、Binder 一次拷贝、AMS/ATMS、WMS/View 三阶段、冷启动、内存/卡顿/ANR、Compose、HAL/Treble、GKI）仍以 A14 源码为准、A17 行为为基线**，本篇不再复述。
- **增量热点**：Google 的 ChromeOS + Android "Desktop Convergence" 路线推进到 A15/A16 已让自由窗口、任务栏、键鼠在外部显示上可用；A17 把桌面模式做成可发现特性；**A18 前瞻 = 桌面融合（笔记本形态）+ 跨设备 handoff（Continuity 类）+ EU DMA 强制开放 AI 能力给第三方助手**。这三条都落到 Framework 层，是 2026 下半场到 2027 的高频新题。
- **关键认知**：跨设备 / 桌面功能里，**大量逻辑在 GMS / Mainline 模块而非 AOSP platform**（呼应第 13 篇 AppFunctions、第 2 篇 Mainline）。面试时能分清「AOSP platform 真身」与「GMS/Mainline 下发」是分水岭——这也是本篇贯穿的易错红线。
- **本篇定位**：不重复讲窗口/进程原理，只讲「桌面融合 + 跨设备协同」这套新形态在 Framework 里是怎么搭出来的、复用哪些既有机制、有哪些不可信边界。

---

## 一、当日热点面试题速递（10 题 · 轻量索引）

| # | 热点面试题 | 本篇落点 | 关联前篇 |
|---|-----------|---------|---------|
| 1 | Desktop Mode 和 ActivityEmbedding / freeform 是什么关系？ | 二、§Q2.1 | 10 篇 WMS、20 篇 §4 |
| 2 | 自由窗口的拖拽 / 缩放 / 任务栏是谁管的？走 WindowOrganizer 吗？ | 二、§Q2.2 | 10 篇 WindowOrganizer |
| 3 | CDM（Companion Device Manager）和普通蓝牙配对有什么本质区别？ | 三、§Q3.1 | 10 篇 BAL、12 篇 vsock |
| 4 | A17 QPR2 的「CDM 锁屏屏幕自动化权限」重写到底改了什么安全边界？ | 三、§Q3.2 | 13 篇无障碍语义树 |
| 5 | 跨设备「接续 / Handoff」在 Framework 层是怎么落地的？ | 四、§Q4.1 | — |
| 6 | Universal Clipboard（跨设备剪贴板）安全吗？加密在哪一层？ | 四、§Q4.2 | 13 篇 URI 授权、11 篇 TEE |
| 7 | EU DMA 对 Android Framework 的具体冲击是什么？ | 五、§Q5.1 | 13 篇 AppFunctions、10 篇 |
| 8 | 跨设备调用里，对端 `getCallingUid()` 可信吗？ | 六、§Q6.1 | 12/13 篇跨 pVM 不可信 |
| 9 | 桌面融合和 AAOS 座舱多显示（CarService 多显示）能共用一套机制吗？ | 七、查缺补漏 | 14/16 篇 AAOS |
| 10 | 跨设备能力和端侧机密计算（pKVM/TEE）的安全模型有何不同？ | 六、§Q6.2 | 11/12 篇 |

---

## 二、Desktop Mode 2.0：自由窗口与任务管理（WMS / WM Shell）

### Q2.1 Desktop Mode、ActivityEmbedding、freeform 三者到底什么关系？

**答：三者是「三个不同层级」的概念，常被混淆，必须先拆清维度。**

- **freeform（窗口化模式）**：是 `WindowConfiguration` 的一种 `WindowingMode`（`WINDOWING_MODE_FREE_FORM`），与 `SPLIT_SCREEN`、`MULTI_WINDOW`、`FULLSCREEN` 同级。它是「这个 task 被当成一个可自由缩放/拖动窗口」的**底层属性**，由 `ActivityRecord` / `Task` 持有。本质是 WMS 多窗口能力的一种。
- **Desktop Mode（桌面模式）**：是**把 freeform 能力以「笔记本/外接显示器」形态暴露给用户**的一层 UX + 策略。它决定「什么时候启用自由窗口、任务栏显不显示、能不能键盘快捷键切应用」。实现主体是 **WM Shell**（systemui 进程里的 `wm.shell` 组件），不是 WMS 本身。
- **ActivityEmbedding（Activity 嵌入）**：是 **Jetpack（`androidx.window.embedding`）** 提供的能力，让一个 task 内把多个 Activity「分屏嵌在同一窗口里」（典型：折叠屏 / 平板双栏）。它走 `SplitController` + `WindowOrganizer` 的 `WindowContainerTransaction`，**和 freeform 是平行的两套多窗口范式**：freeform = 多 task 各自浮动窗口；embedding = 单 task 内多 Activity 同窗分栏。

面试一句话：**freeform 是 WMS 的「窗口形态属性」；Desktop Mode 是「在外接屏上启用 freeform + 任务栏」的 WM Shell 策略；ActivityEmbedding 是应用侧用 WindowOrganizer 把单 task 拆多栏，三者维度不同，不能互相替代。**

**AOSP 源码落点（A14）：**
- WindowingMode 常量：`frameworks/base/core/java/android/app/WindowConfiguration.java`（`WINDOWING_MODE_FREE_FORM`）
- Desktop Mode 主体：`frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/desktopmode/`
  - `DesktopTasksController.java` —— 入桌 / 出桌、toggle、moveToDesktop 的核心控制器（方法 `moveToDesktop()`、`showDesktop()`）
  - `DesktopModeStatus.java` —— 是否启用桌面模式（依赖 `ActivityManager.isDesktopModeSupported()` 与 settings flag）
  - `DesktopModeTaskRepository.java` —— 记录当前在桌面里的 task 集合
- 任务栏：`frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/taskbar/TaskbarController.java`
- 通用窗口事务原语：`frameworks/base/core/java/android/window/WindowOrganizer.java` + `WindowContainerTransaction.java`（freeform 切换最终也靠它 apply 一个 `WCT`，设置 target task 的 windowingMode = FREE_FORM）
- ActivityEmbedding：`frameworks/support/window/window/src/main/java/androidx/window/embedding/`（`SplitController`、`EmbeddingRule`），以及 Jetpack 通过 `WindowOrganizer` 向 system_server 下发。

### Q2.2 自由窗口的拖拽 / 缩放 / 任务栏，是谁在管？走 Binder 跨进程吗？

**答：分「输入 -> WM Shell 策略 -> WMS 落盘」三段，跨进程是有的，但要分清在哪一段。**

1. **输入段**：指针事件先在 `system_server` 的 `InputDispatcher` 走（与第 20 篇 code walk §4 同一条路），命中自由窗口的 drag 区/resize 手柄后，由 **WM Shell（`wm.shell`，跑在 `systemui` 进程）** 的 `DesktopTasksController` / `DragAndDropController` 接管手势。
2. **策略段（在 systemui 进程内）**：WM Shell 算出新的 bounds / position，构造一个 `WindowContainerTransaction`（WCT），里面放「对该 task 设置 bounds / windowingMode」的指令。
3. **落盘段（跨进程 Binder）**：WM Shell 通过 `WindowOrganizer.applyTransaction(wct)` —— 这是一个 **Binder 调用，从 systemui 进程跨到 system_server 的 `WindowOrganizerController`**（`frameworks/base/services/core/java/com/android/server/wm/WindowOrganizerController.java`），由其校验权限后真正改 `WindowContainer` 树并走 relayout。

所以「拖一下窗口」其实跨了 **InputDispatcher -> WM Shell -> WindowOrganizerController** 三个环节，真正的窗口属修改发生在 system_server，且必须经过 WCT 的事务化（原子、可回滚、带权限校验），不是 WM Shell 直接改 WMS 内部对象。

**易错点：**
- 误以为「桌面模式的窗口管理在 WMS 里」。错——**策略与 UX 在 WM Shell（systemui 进程），WMS 只通过 WindowOrganizer 暴露受控事务接口**，这是 A14 起「WMS 暴露面收窄、逻辑上移 Shell」的架构主线。
- 误以为「resize 是实时改 SurfaceFlinger」。错——改的是 window bounds（WMS 层），SF 只负责按新 geometry 合成，真正的连续拖拽有 resize 时的「半透明预览 + 松手才 commit WCT」逻辑，避免每帧一次 Binder 事务。
- 任务栏不是 launcher——`TaskbarController` 是 WM Shell 组件，通过 `TaskbarOrganization` 把最近 task 显示为悬浮条，点击也是发 WCT 拉起/聚焦 task。

**高频追问链（Desktop Mode）：**
- 追问 1：freeform 和 split-screen 在多窗口栈里怎么共存？-> 答：都是 `WindowingMode` + `ActivityStack` 的不同配置，root task 各管各的窗口形态，WM Shell 决定是否同时呈现。
- 追问 2：一个 app 声明不支持 resize（`resizeableActivity=false`），在桌面模式里会被强制浮动吗？-> 答：不会，会被 letterbox / 居中固定尺寸呈现（呼应第 10 篇 SizeCompat / letterbox 逻辑）。
- 追问 3：桌面模式在外接显示器上，和 AAOS 的车机多显示有何异同？-> 见第七节查缺补漏。

---

## 三、Companion Device Manager（CDM）：跨设备配对与权限模型

### Q3.1 CDM 和普通蓝牙配对 / 普通绑定有什么区别？为什么跨设备协同必须靠它？

**答：CDM 解决的是「一个 handset 长期、可信、系统级地绑定一组 companion 设备（手表 / 车机 / TV / 笔记本）」的问题，它比裸蓝牙配对多三层东西。**

普通蓝牙配对（`BluetoothAdapter`、`BluetoothDevice`）只解决「这条链路通了」，不解决：
1. **持久关联（Association）**：设备重启、蓝牙重连后，系统要能认出「这还是我绑定的那块表」并自动恢复能力。CDM 用 `Association` 在 `AssociationStore` 里持久化（数据库 + 开机恢复）。
2. **系统级角色与权限**：绑定 companion 后，companion app 要能在**锁屏可见、后台自启、替设备跑前台服务、甚至驱动手机做屏幕自动化**（A17 QPR2 新权限）。这些是普通绑定给不了的，必须 CDM 通过 `USE_COMPANION_RUN_ON_*` / `REQUEST_COMPANION_RUN_ON_*` / `BIND_COMPANION_DEVICE_SERVICE` 等权限体系授予。
3. **跨进程 / 跨设备可信通道**：CDM 关联后，`CompanionDeviceService`（app 侧系统服务）能在设备上线时被系统唤起，并通过 CDM 提供的通道与 companion 通信，且这套通道的「设备身份」是系统背书的可信身份（区别于任意 BLE 广播）。

一句话：**蓝牙配对 = 建链路；CDM = 在链路之上建立「系统背书的长期设备身份 + 一揽子系统权限 + 自动恢复」**。跨设备协同（手表唤醒手机、车机投屏、笔记本接续）缺了第二层就落不了地，所以必须靠 CDM 而非裸蓝牙。

**AOSP 源码落点（A14）：**
- 公开 API：`frameworks/base/core/java/android/companion/`
  - `CompanionDeviceManager.java` —— app 侧入口（`associate()`、`startObservingDevicePresence()`、`requestNotificationAccess()`）
  - `AssociationRequest.java` —— 关联请求（按 BLE / Bluetooth / WiFi / USB 等设备选择器）
- 系统服务：`packages/modules/CompanionDeviceManager/`（Mainline 模块！注意：**CDM 是 Mainline 可热更模块**，呼应第 2/13 篇 Mainline 主题）
  - `services/companion/java/com/android/server/companion/CompanionDeviceManagerService.java` —— 核心服务（`associate()`、`onDevicePresenceEvent()`）
  - `AssociationStore.java` —— 关联记录的持久化与内存索引
  - `CompanionDeviceService.java`（framework API）—— app 实现，接收设备上线/下线回调

**易错点：**
- CDM 是 **Mainline 模块**（`packages/modules/CompanionDeviceManager`），不是 `frameworks/base/services` 里的常驻服务——这意味着它的行为可经 Google Play 更新，版本碎片化要留意（呼应第 13 篇 AppFunctions 同样走 Mainline/GMS）。
- `associate()` 是**异步 + 需要用户确认 UI** 的，不是静默绑定；考官常问「怎么做到无感重连」——答靠 `AssociationStore` 持久化 + `startObservingDevicePresence` 系统级监听，而非重新配对。

### Q3.2 A17 QPR2 的「CDM 锁屏屏幕自动化权限」重写，改了什么安全边界？

**答：这是 A17 QPR2 一个很关键、且面试极爱问的「安全边界重画」改动。**

背景：过去要让「companion 设备（如车机/手表）驱动手机自动点按屏幕」只能走**无障碍（AccessibilityService）全量授权**——这是一把巨大权限的「万能钥匙」，任何 app 拿到就能读屏 + 全局手势，隐私风险极高。

A17 QPR2 的做法：在 **CDM 体系内新增「屏幕自动化（Screen Automation）」细粒度权限**，让已通过 CDM 关联的可信 companion 设备，能在**受限、可审计、带 PIN 门控**的前提下驱动手机执行 UI 自动化，**而不必申请完整无障碍权限**。具体在 `CompanionDeviceManagerService` 增加 screen-automation 的角色/权限授予路径，并要求：
- 设备必须是**已 CDM 关联的可信设备**（系统背书身份）；
- 敏感操作需**用户 PIN / 生物认证门控**；
- 行为可**审计与撤回**（不像无障碍那样一给全给）。

**安全模型含义（面试核心）：** 把「全局无障碍万能钥匙」拆成「系统背书设备身份 + 细粒度动作 + 显式认证门控」三层。这与第 13 篇讲的「隐私范式从运行时权限转向系统托管 UI + 一次性凭证」「Compose 语义树对 AI Agent 更友好」是一脉相承的——**系统在收拢「谁能替用户操作 UI」的权限边界**。

**AOSP 源码落点（A17 QPR2 路线）：**
- `packages/modules/CompanionDeviceManager/services/companion/.../CompanionDeviceManagerService.java`（screen automation 授权分支）
- 关联角色常量在 `android/companion` 的 role 定义；PIN 门控走 `KeyguardManager` / 生物认证回调
- 与无障碍的关系：`AccessibilityManagerService`（`frameworks/base/services/accessibility/`）仍是最粗粒度通道，QPR2 旨在把 companion 场景从它身上「分流」出去

**高频追问链（CDM）：**
- 追问 1：CDM 关联的设备身份，在跨进程 Binder 调用里 `getCallingUid()` 能直接信吗？-> 答：可信用来「识别是哪个 companion app 的 UID」，但**不能用来信任对端设备本身发来的指令语义**（呼应第 12/13 篇跨 pVM / Provider 侧 getCallingUid=SYSTEM_UID 不可信）。
- 追问 2：为什么车机投屏 / 笔记本接续不直接用 CDM + 普通 Binder？-> 答：因为车机/笔记本是**另一台设备**，Binder 只在本机进程间；跨设备要走 Nearby / 网络通道（见第四、六节）。

---

## 四、Cross-device Handoff / Universal Clipboard（跨设备接续与通用剪贴板）

### Q4.1 跨设备「接续 / Handoff（Continue On）」在 Framework 层怎么落地？

**答：要分清「系统级接续」和「应用级接续」，它们的 Framework 落点完全不同。**

- **应用级接续（主流、真实可落地）**：app 自己通过**云端同步 + 自身账号**实现「手机看到一半的文档，平板上接着看」。Framework 只提供「设备在线状态 / 账号 / 同步触发」的基础设施，并不存在一个中央「handoff 总线」。典型落点：
  - 设备在线/邻近感知：`CompanionDeviceManager`（已关联设备）或 Nearby（邻近发现）。
  - 媒体/投屏类接续：`MediaRouter`（`frameworks/base/media/java/android/media/MediaRouter.java` + `androidx.mediarouter`）把「正在播放的 session」投到另一台设备的 `MediaRouteProvider`。
- **系统级 handoff（A18 前瞻路线）**：Google 的 Desktop Convergence 想做的是「系统能感知你正在哪台设备干活，自动把 activity / 文档接过去」。这一层目前**大量在 GMS / Mainline（如 Nearby、Quick Share、Cross-device SDK）而非 AOSP platform**，AOSP 侧主要提供「跨设备 clipboard」「邻近发现 hook」「CDM 设备身份」三块地基。

面试红线：**别把 handoff 说成「Framework 有个 HandoffManager 统一调度」——没有**。真实结构 = 应用用账号/云同步 + 系统给的邻近/设备身份/剪贴板/投屏能力拼出来；系统级统一 handoff 仍在演进中（A18 前瞻）。

**AOSP 源码落点：**
- 邻近/投屏：`frameworks/base/media/java/android/media/MediaRouter.java`、Jetpack `androidx.mediarouter`
- 设备身份地基：`packages/modules/CompanionDeviceManager/`（见第三节）
- 跨设备传输：Nearby / Quick Share —— **主要在 GMS/Play Services 与 `packages/modules/Connectivity` 邻近能力**，AOSP platform 仅提供底层 WiFi/BLE 与部分 nearby API surface

### Q4.2 Universal Clipboard（跨设备剪贴板）安全吗？加密在哪一层？

**答：跨设备剪贴板 = 本地 `ClipboardService` + 跨设备安全传输通道，安全在「传输中加密 + 设备绑定身份」，不是「端侧机密计算」。**

- **本地剪贴板**：`ClipboardManager`（`frameworks/base/core/java/android/content/ClipboardManager.java`） + `ClipboardService`（`frameworks/base/services/core/java/com/android/server/clipboard/ClipboardService.java`）。本地复制本质是往一个系统服务里存 `ClipData`，按 UID 做访问隔离（和第 13 篇 URI 授权同属「系统托管数据」思路）。
- **跨设备传输**：把 `ClipData` 经 Nearby / Quick Share 推到另一台已配对设备。安全性来自：
  - **设备身份**：必须是 CDM 关联 / Nearby 已认证端点（系统背书），不是任意设备能收；
  - **传输中加密**：Nearby Connections 端点间用 **AES-GCM**（基于配对密钥协商的会话密钥），属于「传输中加密（encryption in transit）」；
  - **一次性 / 短时效**：跨设备剪贴板通常带 TTL，不会长期驻留对端。

**关键辨析（面试必考）：** 跨设备剪贴板的加密是「**传输中**加密」——数据离开本机到对端之间加密，对端收到后就是明文 `ClipData`。它**不等于**第 11 篇 TEE / 第 12 篇 pKVM 的「**使用中**加密」（数据在内存里也受硬件/VM 保护）。这条区分直接对上第 12 篇的「保护边界精确表述」红线。

**易错点：**
- 误以为剪贴板跨设备走 Binder——错，Binder 本机进程间，跨设备必须走网络/Nearby。
- 误以为跨设备剪贴板内容在传输全程都「硬件加密不可读」——错，只是传输通道加密，落在对端 ClipboardService 就是明文，且受对端 UID 隔离约束。

---

## 五、EU DMA 第三方助手接入对 Framework 的影响

### Q5.1 EU DMA 到底对 Android Framework 冲击了什么？

**答：DMA（Digital Markets Act，欧盟《数字市场法》）把 Google 列为「看门人（gatekeeper）」，强制其开放互操作性与默认项选择权。落到 Android Framework，不是改一行代码，而是「重画系统能力开放边界」。**

具体冲击（2024–2027 渐进落地，A18 前瞻延续）：
1. **默认助手可替换**：第三方 AI 助手可作为默认 `VoiceInteractionService`（`frameworks/base/core/java/android/service/voice/VoiceInteractionService.java` + `services/voiceinteraction/`），不再被 Google Assistant 独占系统级唤醒入口。
2. **第三方助手可调系统能力**：DMA 的「互操作性」要求下，系统要把原本封闭的能力（如 App 提供的功能）通过**开放接口**暴露给合规第三方助手。这正好与第 13 篇 **AppFunctions（Android 原生 MCP）** 对接——第三方助手经由 AppFunctions 调用 App 能力，而非走封闭 assistant shortcut。
3. **CDM 锁屏屏幕自动化重写（A17 QPR2，见 §Q3.2）**：让可信 companion / 助手能在受控前提下驱动 UI，是 DMA 下「开放自动化能力给第三方」在 Framework 的具体落子之一。
4. **默认项选择屏（Choice Screen）**：浏览器 / 搜索 / 助手在 EU 出现系统级选择屏，要求 Framework 的默认项解析（`PackageManager.resolveActivity`、role 体系 `android.app.role`）真正可替换。

**面试核心认知：DMA 不是「加个 API」，而是把「系统能力 → 谁能调用」的权限边界从「Google 自家」重画到「合规第三方」。** AppFunctions + CDM + role 体系是这套重画的三个 Framework 支柱。

**AOSP / 模块源码落点：**
- 助手入口：`frameworks/base/core/java/android/service/voice/VoiceInteractionService.java`、`frameworks/base/services/voiceinteraction/java/com/android/server/voiceinteraction/`
- 默认项 / role：`frameworks/base/core/java/android/app/role/RoleManager.java`
- 能力开放：AppFunctions（`packages/modules/...` + GMS，见第 13 篇）
- 设备身份地基：CDM（`packages/modules/CompanionDeviceManager/`）

**易错点：**
- 误以为 DMA 改的是 `frameworks/base` 一大片——实际上**很多落在 Mainline/GMS 模块（AppFunctions、CDM、Nearby）**，platform 只改 role / voiceinteraction 等入口，呼应第 13 篇「智能层走 Mainline 热更」主线。
- 误以为第三方助手能无差别调系统——错，仍受 AppFunctions 的 `BIND_APP_FUNCTION_SERVICE` 保护模式 + CDM 设备身份 + 权限门控三重约束。

---

## 六、跨设备安全边界：设备间通道 / 可信 / 端到端加密

### Q6.1 跨设备调用里，对端 `getCallingUid()` 可信吗？

**答：完全不可信，而且理由比本机跨进程更彻底。**

本机 Binder 跨进程时，`getCallingUid()` 是 **kernel binder 驱动背书**的（第 20 篇 §6：`binder_transaction` 里塞 `sender_euid`），至少能信「是哪个 UID 发的」。但**跨设备根本不走 Binder**——数据经 Nearby / 网络从另一台机器过来，落到本机时：
- 本机那一侧的 `getCallingUid()` 顶多告诉你「是哪个**本地代理进程**（如 Nearby 的 GMS 进程 / 某个系统服务）在转发」，**完全反映不出对端设备的真实身份 / 对端 app 的 UID**；
- 对端设备是谁，只能由 **CDM Association（系统背书的设备身份）** 或 Nearby 端点认证来背书，且这个背书是「设备级」而非「对端 app UID 级」。

这与第 12 篇（跨 pVM：`getCallingUid()` 在 RPC Binder 下不可信，跨 VM 拿不到真实 uid）和第 13 篇（AppFunctions Provider 侧 `getCallingUid()` 拿到 `SYSTEM_UID` 不可信）是**同一个红线在不同边界的投影**：**凡跨越「本机 Binder 信任域」（跨 VM / 跨设备 / 经系统服务转发），`getCallingUid()` 都不再代表真实调用方**。

### Q6.2 跨设备能力和端侧机密计算（pKVM/TEE）安全模型有何不同？

**答：一个是「传输中加密」，一个是「使用中加密」，威胁模型完全两码事。**

| 维度 | 跨设备协同（Nearby/CDM） | 端侧机密计算（TEE pKVM/AVF） |
|------|------------------------|------------------------------|
| 保护对象 | 设备 A -> 设备 B 的**传输数据** | 本机**运行中的数据/代码**（第 11/12 篇） |
| 加密时机 | 离开本机到对端之间（in transit） | 内存里也受硬件/VM 保护（in use） |
| 信任根 | CDM Association / Nearby 端点认证 | 硬件信任根 DICE / ARM TrustZone / pKVM stage-2 |
| 攻击面 | 中间人 / 仿冒设备 | 本机 OS 被攻破 / 物理提取 |
| Framework 落点 | CDM + Nearby + ClipboardService | Keystore2/KeyMint + AVF/pKVM |

面试一句话：**跨设备解决「数据在路上别被偷看」，机密计算解决「数据在内存里也别被本机恶意 OS 偷看」——前者是网络层信任，后者是计算层信任。**

---

## 七、查缺补漏：与既有体系的连接点

桌面融合 + 跨设备协同不是凭空出现，它**复用并放大**了前 21 篇的既有机制。一张连接图（纯 ASCII，避免框图字符损坏）：

```
                 [ 前 21 篇既有机制 ]
                          |
      +-------------------+--------------------+
      |                   |                    |
  WMS / WindowOrganizer   AMS / Binder        AAOS 多显示 / CarPowerPolicy
  (10/20 篇)              (主篇/20 篇)         (14/16 篇)
      |                   |                    |
      v                   v                    v
  freeform 窗口属性    CDM 跨设备身份       车机/笔记本外接显示
  + WM Shell 策略     + Binder 本机事务     + 多 Display 电源时序
  (Desktop Mode)      (系统服务调用)        (与桌面多显示同构)
      |                   |                    |
      +-------------------+--------------------+
                          |
                  [ A18 新形态：横向拉通多设备 + 多窗口 ]
                          |
      +-------------------+--------------------+
      |                   |                    |
  Desktop Convergence   Cross-device Handoff  EU DMA 能力开放
  (自由窗口+任务栏)     (Universal Clipboard   (AppFunctions + CDM
                          / MediaRouter)         + role 体系)
```

**关键连接点（面试可主动串讲）：**
1. **Desktop Mode 复用 WindowOrganizer**（第 10 篇）：自由窗口本质是把 `WindowContainerTransaction` 用于设置 freeform windowingMode，与折叠屏 letterbox / split 同源。
2. **CDM 复用 Binder 信任域概念**（主篇/20 篇）：但立刻引出「跨设备不信任」红线（第 6 节），是对 Binder `getCallingUid()` 考点的升维。
3. **AAOS 多显示与桌面外接显示同构**（14/16 篇）：都是「多个 `Display` 内容合成 + 各自电源/聚焦策略」，CarService 的 `CarDisplay` / multi-display 与桌面模式在外接屏上的 `DisplayContent` 可类比——这是 §Q2.2 追问 3 的落点。
4. **AppFunctions 是 DMA 能力开放的承载**（第 13 篇）：第三方助手经它调 App 能力，把「智能层」和「DMA 开放边界」焊在一起。
5. **跨设备加密 vs 机密计算**（11/12 篇）：直接对上 TEE/pKVM 的保护边界红线，避免把二者混为一谈。

---

## 八、易错点 & 面试高频追问（红榜 TOP 15）

1. **freeform / Desktop Mode / ActivityEmbedding 三者维度混淆**——务必先拆「WMS 窗口属性 vs WM Shell 策略 vs 应用侧单 task 分栏」。
2. **以为桌面窗口管理在 WMS 里**——错，策略在 WM Shell（systemui 进程），WMS 只经 `WindowOrganizer` 暴露受控 WCT 事务。
3. **以为 resize 实时改 SF**——错，改的是 window bounds，且有预览/commit 两阶段避免每帧 Binder。
4. **CDM 当普通蓝牙配对**——错，CDM 多了持久 Association + 系统级角色权限 + 可信通道三层。
5. **忽略 CDM 是 Mainline 模块**——行为可热更，版本碎片化要提（呼应 Mainline 主线）。
6. **A17 QPR2 屏幕自动化权限当「新增无障碍」**——错，它是把 companion 场景从无障碍「分流」出来，做细粒度 + PIN 门控，收拢权限边界。
7. **以为有统一的 HandoffManager**——没有，handoff 是「应用云同步 + 系统邻近/设备身份/剪贴板/投屏」拼出来的。
8. **跨设备走 Binder**——错，Binder 本机，跨设备走 Nearby/网络。
9. **跨设备剪贴板「硬件全程加密」**——错，只是传输中加密，落对端即明文（受 UID 隔离）。
10. **跨设备 `getCallingUid()` 可信**——错，不可信，与跨 pVM / AppFunctions Provider 同红线。
11. **DMA 当「加个 API」**——错，是重画系统能力开放边界（助手可替换 + AppFunctions + CDM + role）。
12. **DMA 改一大片 `frameworks/base`**——错，多落在 Mainline/GMS（AppFunctions、CDM、Nearby）。
13. **跨设备加密 ≈ 机密计算**——错，in transit vs in use，威胁模型两码事（见 §Q6.2 表）。
14. **以为 Universal Clipboard 无 TTL 长期驻留**——错，通常短时效，且受对端 UID 隔离。
15. **混淆「设备身份（CDM Association）」与「对端 app UID」**——跨设备只能信设备级身份，信不到对端 app 的 UID。

**三条高频追问链：**
- **桌面融合链**：freeform 是什么 -> Desktop Mode 谁管 -> 拖拽怎么落到 WMS -> 和 embedding/AAOS 多显示异同。
- **跨设备信任链**：CDM 关联是什么 -> 跨设备走什么通道 -> 对端 getCallingUid 可信吗 -> 和 pKVM/TEE 保护模型区别。
- **DMA 开放链**：DMA 强制什么 -> 助手怎么可替换 -> AppFunctions 怎么承载 -> CDM 屏幕自动化怎么收边界。

---

## 九、延伸阅读（AOSP / 模块路径清单）

- Desktop Mode / WM Shell：`frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/desktopmode/`、`.../taskbar/`、`frameworks/base/core/java/android/window/WindowOrganizer.java`
- 窗口形态：`frameworks/base/core/java/android/app/WindowConfiguration.java`
- ActivityEmbedding（Jetpack）：`frameworks/support/window/window/src/main/java/androidx/window/embedding/`
- CDM（Mainline）：`packages/modules/CompanionDeviceManager/`、`frameworks/base/core/java/android/companion/`
- 剪贴板：`frameworks/base/core/java/android/content/ClipboardManager.java`、`frameworks/base/services/core/java/com/android/server/clipboard/ClipboardService.java`
- 投屏/媒体路由：`frameworks/base/media/java/android/media/MediaRouter.java`、`androidx.mediarouter`
- 助手/语音：`frameworks/base/core/java/android/service/voice/VoiceInteractionService.java`、`frameworks/base/services/voiceinteraction/`
- 默认项/role：`frameworks/base/core/java/android/app/role/RoleManager.java`
- 邻近/跨设备传输：Nearby / Quick Share（主要在 GMS/Play Services 与 `packages/modules/Connectivity` 邻近能力）
- 关联前篇：第 10 篇（WMS/WindowOrganizer/BAL）、第 11 篇（TEE）、第 12 篇（pKVM/AVF 跨 VM 不可信）、第 13 篇（AppFunctions/无障碍语义树/URI 授权）、第 14/16 篇（AAOS 多显示/电源）、第 20 篇（code walk §4/§6）

---

## 十、22 篇交叉索引（系列总导航补遗）

| 篇 | 主题 | 与本篇关系 |
|----|------|-----------|
| 1–3 | 主线 / 拓展 / 深挖（Binder/AMS/WMS/ART/HAL/内核/MTK） | 本篇跨设备信任建立在 Binder 信任域之上 |
| 4 | 图形多媒体通信 | 桌面多窗口最终由 SF 合成 |
| 5 | 系统基建/可观测性 | CDM/Mainline 属系统基建范畴 |
| 6 | 端侧 AI 与 A17 演进 | AppFunctions/智能层是 DMA 开放承载 |
| 7 | A17 新雷区 | freeform/Desktop 受 compat 框架影响 |
| 8 | 渲染合成/A17 安全内存 | 跨设备 vs 机密计算（§Q6.2） |
| 9 | 兼容性框架/A17 跨设备窗口 | Handoff/CDM 同源话题 |
| 10 | 安全世界 TEE | 跨设备加密 vs TEE 保护边界 |
| 11 | pKVM/AVF | 跨 VM getCallingUid 不可信（§Q6.1） |
| 12 | 智能系统 AppFunctions | DMA 能力开放承载（§Q5.1） |
| 13 | 端侧 AI 工程化/AAOS/安全深水区 | AAOS 多显示与桌面外接显示同构 |
| 14–16 | 收官补遗/速查卡/连击考 | 体系导航 |
| 17 | 全链路排查实战 | 桌面/跨设备问题同样用 Perfetto 定界 |
| 18 | 源码级 code walk | windowingMode/task 链路同源 |
| 19 | Perfetto SQL 范例库 | 跨设备协同卡顿亦可用 SQL 定界 |
| **22（本篇）** | **A18 桌面融合与跨设备协同** | **把前述机制横向拉通多设备多窗口** |

> 系列至此 **22 篇 / 约 153 专题** 闭环：主线 + 盲区 + 深水区 + 智能层 + 安全世界（EL3）+ 机密计算（EL2）+ 座舱 + 端侧 AI + 速查卡 + 连击考 + 全链路排查 + code walk + Perfetto SQL + **A18 桌面融合/跨设备协同**。后续若继续日更，剩余可轮换角度：真题大乱斗混合场景卷（A17 QPR2 多子系统叠加压轴综合题）、Perfetto SQL 范例库扩充（input 延迟 / GPU 计数器 / battery 耗电细分）。
