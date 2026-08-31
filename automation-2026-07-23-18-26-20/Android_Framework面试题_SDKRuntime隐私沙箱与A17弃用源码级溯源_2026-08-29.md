# Android Framework 面试题 · SDK Runtime / 隐私沙箱：从 A13 进程隔离到 A17 弃用源码级溯源（第 46 篇）

> 日期：2026-08-29（周六）｜ baseline：Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX，内核 GKI android14-6.1) ｜ 系列第 46 篇 / 累计约 **295 专题**
> 适用场景：秋招白热期（9–11 月）· 跨版本演进 + 系统联动连环追问模拟
> 说明：8/28 已把「系统层 Rust 化与内存安全边界」这一真缺口补齐。本篇**不重复**，转而填补系列长期隐含、却从未独立成篇的另一个真缺口——**「SDK Runtime / 隐私沙箱（Privacy Sandbox on Android）」**。这是 2023–2026 上升极快、却在 **Android 17（API 37）被官方 deprecated** 的 Framework 面试增量题，且天然与系列主线（Binder IPC / 进程隔离 / 安全沙箱 / AAOS 隐私）强耦合。每题统一结构：**问题 -> 答案解析(源码路径/类名) -> A13->A17 演进 -> 易错点 -> 考官高频连环追问 -> 延伸阅读**。文末附「当日高频八股回炉压轴」速查。

---

## 0. 当日热点锚定（2026-08-29）

- **SDK Runtime 是 Google 把「第三方 SDK 关进独立进程」的平台级隔离机制**：传统上广告/分析 SDK 以 AAR 打进宿主 APK，和主应用**同进程、同 UID、同权限**，能读宿主 SharedPreferences、文件系统、甚至反射调宿主组件。SDK Runtime 把它改成「SDK 跑在专用 `sdk_sandbox` 进程、独立 UID 段、受限权限、靠受限 Binder 与宿主通信」。
- **关键跨版本事实（面试必背）**：`android.app.sdksandbox.SdkSandboxManager` **Added in API 33（Android 13），Deprecated in API 37（Android 17）**，官方标注 *"The SDK sandbox is no longer supported."*；与之呼应的 Privacy Sandbox 广告倡议于 **2025-10 被 Google 关闭**。即：**兴于 A13，成熟于 A14/A15，A17 弃用**。
- **为什么考官爱问**：它把「进程隔离 / 受限权限 / 受限 Binder IPC / 可信分发」四个 Framework 核心考点揉进一个真实子系统；且 deprecated 本身又是绝佳的「跨版本演进 + 辩证作答」题——考官用「你做的 SDK Runtime 适配是不是白做了」来区分候选人有没有版本演进意识。
- **本篇主线**：把 SDK Runtime 的架构版图、与 Binder/进程模型/权限模型的交界、以及「A17 弃用但隔离思想延续」做成立即可面试口述的体系。最后用 10 道跨域连环追问做回炉压轴。

---

## 专题一：SDK Runtime 全景 —— 为什么要把第三方 SDK 关进独立进程？

### 问题
"传统 Android 上第三方 SDK（广告/分析）是怎么集成的？有什么安全隐患？SDK Runtime 把模型改成了什么样？它和普通的『多进程 app』有什么本质区别？"

### 答案解析 + 底层原理
**传统模型（非 RE SDK）**：
- SDK 以 AAR/JAR 形式打进宿主 APK，和主应用**运行在同一个进程、同一个 UID（应用 UID）、继承全部权限**。
- 后果：SDK 能读宿主 `SharedPreferences`、内部存储、剪贴板；能反射调宿主组件；能静默收集/外传用户数据；宿主开发者甚至不清楚 SDK 到底访问了什么——这是隐私合规的「黑箱」。

**SDK Runtime 模型（RE SDK，Runtime-Enabled）**：
- 兼容 SDK（RE SDK）运行在**独立于宿主 app 进程的 `sdk_sandbox` 进程**里，平台在 app 进程与 SDK Runtime 之间做**双向受控通信**。
- 入口与契约（AOSP 真实类）：
  - 宿主侧：`android.app.sdksandbox.SdkSandboxManager`（`context.getSystemService(SdkSandboxManager.SDK_SANDBOX_SERVICE)` 获取），核心方法 `loadSdk(String sdkName, Bundle params, Executor, OutcomeReceiver)`、`unloadSdk()`。
  - SDK 侧：`android.app.sdksandbox.SandboxedSdkProvider`（SDK 的入口组件，类似 Service 的 `onCreate` 角色），重写 `onLoadSdk(Bundle): SandboxedSdk` 返回能力句柄。
  - 句柄：`android.app.sdksandbox.SandboxedSdk` 内部包一个 `IBinder`——这是宿主与 SDK 之间**跨进程 Binder 接口**的载体（详见专题三）。
- 清单声明：宿主用 `<uses-sdk-library android:name=... android:version=.../>` 声明依赖；SDK 用 `<property android:name="android.sdksandbox" .../>` 标记。系统保证「app 只能 `loadSdk` 它声明依赖的 SDK」。

**与普通多进程 app 的区别（本质）**：
- 普通多进程是**开发者自己**用 `android:process` 切分、**同 UID**（或 `android:isolatedProcess` 才不同 UID）。
- `sdk_sandbox` 是**平台强制**的隔离边界：独立 **UID 段（separate uid range）**、**每个 app 可有自己的 sandbox 进程**、默认**不具备宿主权限**、通信必须经平台网关——不是开发者「乐意就隔离」，而是平台「默认就隔离」。

### A13 -> A17 演进
- **A13（API 33）**：引入 SDK Runtime 平台能力（设计提案阶段），`SdkSandboxManager` 首发。
- **A14 / A15（API 34/35）**：能力成熟并落地于 GMS/Ad Services 扩展；`requestSurfacePackage()` 在 API 35 被标记 deprecated（UI 渲染改走 `androidx.privacysandbox` 库）。
- **A17（API 37）**：`SdkSandboxManager` 整类 **deprecated**，官方声明 *"The SDK sandbox is no longer supported."*；对应 Privacy Sandbox 广告倡议 2025-10 关闭。

### 易错点
1. **SDK Runtime ≠ 普通多进程**：它是平台级、独立 UID 段、默认无宿主权限的**强制隔离**，不是开发者自己 `android:process`。
2. **deprecated ≠ 没学过**：A14/A15 设备大量存在，且「进程隔离 + 受限 Binder + 可信分发」的思想延续到其它隔离机制（见专题五）；面试考的是**模式**不是「会不会调 API」。
3. **RE 与非 RE 并存**：非 RE SDK 仍像今天一样留在宿主进程；只有声明为 RE 的 SDK 才进 sandbox。别答「所有 SDK 都隔离了」。

### 考官高频连环追问（标准答案）
- "宿主怎么拿到 SDK 返回的 UI？" -> 早期 `SdkSandboxManager.requestSurfacePackage()`（API 35 dep）把 `SurfacePackage` 塞进宿主 `SurfaceView`；新版改由 `androidx.privacysandbox.ui` 库承载；本质是 SDK 在 sandbox 进程渲染、把 `Surface` 跨进程交给宿主显示。
- "一个设备多个 app 用同一个广告 SDK，是几个 sandbox 进程？" -> 每个 app 各自一个 sandbox 进程（按 app UID 段隔离）；但**可信分发**允许商店托管同一份 SDK 二进制、多 app 共享**磁盘实例**（非进程实例），省存储/可免应用更新修 bug（见专题四）。

### 延伸阅读
`frameworks/base/core/java/android/app/sdksandbox/`（SdkSandboxManager / SandboxedSdkProvider / SandboxedSdk）；`packages/modules/SdkSandbox/`；AOSP 设计提案《SDK Runtime》；官方文档 `design-for-safety/privacy-sandbox/sdk-runtime`。

---

## 专题二：sdk_sandbox 进程模型与受限权限 —— 它到底被拿走了什么？

### 问题
"sdk_sandbox 进程是一个什么性质的进程？它的 UID 有什么特殊？它能不能访问宿主的内存/存储、能不能自己启动 Activity、能不能随便用 JNI/反射？和 `android:isolatedProcess` 比谁更狠？"

### 答案解析 + 底层原理
**进程性质**：
- 官方定义原文：*"SDK sandbox is a java process running in a separate uid range. Each app may have its own SDK sandbox process."* —— 它是**独立 UID 段**的 Java 进程，不是宿主进程的子线程，也不是普通同 UID 多进程。
- 后台支撑：`com.android.server.sdksandbox.SdkSandboxManagerService`（`frameworks/base/services/core/java/com/android/server/sdksandbox/`）负责校验「app 只能加载其声明依赖的 SDK」、拉起/回收 sandbox 进程；sandbox 进程本体由 `packages/modules/SdkSandbox` 下的 `SdkSandboxService` 承载。

**被拿走的权限/能力（受限执行环境）**，这是面试核心：
1. **不继承宿主权限**：sandbox 进程运行在独立 UID 段，**默认不拥有宿主 app 的任何权限**（包括存储、定位、相机等）。想用敏感能力必须显式声明且经平台网关约束。
2. **不能访问宿主内存/存储**：与宿主进程地址空间完全隔离，读不到宿主 `SharedPreferences`/内部文件（平台提供受控的 `addSyncedSharedPreferencesKeys()` 把**白名单 key** 单向同步进 sandbox，而不是全量共享）。
3. **不能读写外部存储 / 无宿主文件视图**：存储被隔离。
4. **Activity 限制（演进）**：早期版本 sandbox **不能启动 Activity**；后续 API 加入了 `startSdkSandboxActivity(fromActivity, sdkActivityToken)` 允许在宿主 Activity 上下文中启动，但仍是受控的、非任意自启。
5. **JNI / 反射受限**：设计上**限制不安全语言构造（如 JNI）**与部分反射，防止 SDK 通过 native 层逃逸沙箱或篡改其它 SDK。
6. **网络配置受限**：sandbox 内不能自行定义网络安全配置（network security config），网络访问受平台约束。

**与 `android:isolatedProcess` 对比**：
- `isolatedProcess` 也是独立 UID（从 `isolated_uid` 段分配）、无权限、不能访问宿主——和 sandbox 同属「最小特权隔离进程」。
- 区别：sandbox 是**面向第三方 SDK 的专用隔离框架**，带 `SdkSandboxManager` 生命周期管理、受控 Binder 接口、可信分发；`isolatedProcess` 是更通用的「跑不可信代码」原语（如 `WebView` 的某些渲染子进程、剪贴板、gms 核心服务也用）。sandbox 可视为「isolatedProcess 思想 + SDK 专属治理」的上层封装。

### A13 -> A17 演进
- A13/A14：基线隔离（独立 UID、无宿主权限、无 Activity、限 JNI/反射）。
- A15 起：逐步放开受控 Activity（`startSdkSandboxActivity`）、细化 `SyncedSharedPreferences` 白名单同步。
- A17：整能力 deprecated，但「独立 UID + 最小特权」的隔离范式被 WebView / AVF / 其它沙箱吸收。

### 易错点
1. **sandbox 不是「宿主进程的子线程」**：是独立进程、独立 UID 段、独立地址空间——跨进程通信是硬性要求。
2. **SharedPreferences 不是全量共享**：只有 `addSyncedSharedPreferencesKeys()` 白名单里的 key 才单向同步进 sandbox。
3. **isolatedProcess ≠ SDK Runtime**：前者是通用隔离原语，后者是其上 SDK 专属治理层。

### 考官高频连环追问（标准答案）
- "sandbox 进程死了，宿主怎么知道？" -> `SdkSandboxManager.addSdkSandboxProcessDeathCallback(Executor, SdkSandboxProcessDeathCallback)` 注册死亡回调，平台在 sandbox 进程异常退出时通知宿主，宿主可自行 reload 或降级。
- "SDK 想读宿主的某个配置，正确姿势是什么？" -> 不要试图突破隔离；用 `addSyncedSharedPreferencesKeys()` 把需要的 key 显式同步；或经宿主侧 AppOwned 接口（`registerAppOwnedSdkSandboxInterface`）由宿主主动下发。

### 延伸阅读
`frameworks/base/services/core/java/com/android/server/sdksandbox/SdkSandboxManagerService.java`；`packages/modules/SdkSandbox/`；`ActivityThread` 中对 `android:isolatedProcess` 的处理（对比学习）。

---

## 专题三：受限 Binder IPC —— 宿主与 SDK 之间为什么不是「普通 Binder」？

### 问题
"宿主 `loadSdk` 之后，怎么调 SDK 的方法？返回的 `SandboxedSdk(IBinder)` 是什么？这条 Binder 通道和普通 app→system_server 的 Binder 比，有哪些额外约束？从 system_server 视角看，sandbox 进程的 `getCallingUid()` 是什么？"

### 答案解析 + 底层原理
**通信骨架**：
- `SandboxedSdkProvider.onLoadSdk()` 返回一个 `SandboxedSdk`，其构造函数接收一个 `IBinder`——这个 `IBinder` 就是 SDK 暴露给宿主的**自定义 AIDL/Binder 接口**（例如 `IMyAdSdk`），由 `asBinder()` 跨进程传出。
- 宿主侧拿到 `SandboxedSdk.getInterface()`（或 `getSdkInterface()`）得到 `IBinder`，`asInterface` 成 AIDL stub，之后所有调用都走**这条 Binder 事务**——和普通的「app 拿 system_server 的 Binder 代理」机制完全一致（都是 `/dev/binder` 上的事务）。

**为什么「不是普通 Binder」——额外约束**：
1. **生命周期受平台托管**：这条 Binder 不是 SDK 自己 `Service` 注册到 `servicemanager`，而是经 `SdkSandboxManagerService` 校验「app 只能连它声明依赖的 SDK」后才建立；非授权 app 拿不到对应 `IBinder`。
2. **单向能力暴露**：SDK 只能暴露它在 `onLoadSdk` 里主动返回的接口，不能反向随意调宿主——反向需要宿主注册 `AppOwnedSdkSandboxInterface` 并把 `IBinder` 主动传给 SDK。
3. **跨 UID 段**：事务两端是**不同 UID 段**（宿主应用 UID vs sandbox 独立 UID 段），Binder 驱动照常做 UID/PID 鉴权（`binder.c` 的 `binder_transaction` 填 `sender_euid`）。
4. **Surface 跨进程**：UI 通过 `SurfacePackage`/`Surface` 跨进程投到宿主 `SurfaceView`，不是普通 Binder 数据，而是 `BufferQueue`/GraphicBuffer 句柄传递（呼应本系列 7-30 渲染篇）。

**`getCallingUid()` 在 sandbox 上下文的含义（高频追问，呼应 8/27 Binder 篇）**：
- 一条从 sandbox 进程进来的 Binder 调用，在 system_server 侧 `Binder.getCallingUid()` 返回的是 **sandbox 的独立 UID 段**（不是宿主应用 UID）。
- 因此：system_server 若以「调用方 UID」做权限判定，必须**把 sandbox UID 映射回其宿主 app UID** 才能正确归属（`SdkSandboxManagerService` 内部维护 sandbox UID ↔ 宿主 app UID 的映射）。
- 这与本系列 8/27/8/02 反复强调的「跨隔离边界 `getCallingUid()` 不可直接信任」一脉相承：pKVM 跨 VM 不可信、sandbox 跨 UID 段需映射、普通 app 间也需校验 `getCallingUid()` 而非包名。

### A13 -> A17 演进
- A13/A14：基础 `SandboxedSdk(IBinder)` + `loadSdk` 异步回调落地。
- A15：`requestSurfacePackage` 走 Surface 跨进程渲染（API 35 dep，移交 `androidx.privacysandbox`）。
- A17：能力弃用，但「跨 UID 段 Binder + UID 映射归属」的鉴权范式在其它隔离（WebView、AVF）继续成立。

### 易错点
1. **返回的 `IBinder` 就是普通 Binder 接口**：技术上无魔法，区别在于「谁来托管生命周期 + 跨 UID 段 + 平台鉴权」。
2. **`getCallingUid()` 返回 sandbox UID，不是宿主 UID**：归因必须映射回宿主，否则权限判定错位。
3. **反向调用需宿主主动暴露**：SDK 不能随便调宿主，宿主要注册 `AppOwnedSdkSandboxInterface` 并下发 `IBinder`。

### 考官高频连环追问（标准答案）
- "这条 Binder 一次事务拷贝几次？" -> 与普通 Binder 一致：发送方 `copy_from_user` 一次进内核 `binder_buffer`（经 `binder_mmap` 共享），接收方零二次拷贝（详见 8/27 专题二、8/6 binder.c code walk）。sandbox 隔离不改变 Binder 内存模型。
- "sandbox 进程能直接 bind 系统服务吗？" -> 受平台网关约束；敏感系统服务调用需经 `SdkSandboxManagerService` 这类中介或显式授权，不能像普通 app 那样自由 `getSystemService` 后直连高权限服务。

### 延伸阅读
`frameworks/base/core/java/android/app/sdksandbox/SandboxedSdk.java`、`SdkSandboxManager.java`；`frameworks/native/libs/binder/`（`binder_transaction`/`getCallingUid`）；8/27 Binder 跨 UID 鉴权专题、8/02 pKVM 跨 VM 身份不可信。

---

## 专题四：可信分发（Trusted Distribution）—— SDK 不再打进 APK 意味着什么？

### 问题
"SDK Runtime 提出了一套新的 SDK 分发模型，它和传统的 Maven AAR 分发有什么本质不同？为什么说它能『免应用更新修 SDK bug』、能多 app 共享一份 SDK？这又带来什么新问题？"

### 答案解析 + 底层原理
**传统分发（AAR / Maven）**：
- SDK 开发者把库发到 Maven；app 开发者 `implementation` 进来，**编译期把 SDK 字节码打进自己的 APK**；SDK 升级必须 app 重新发版。
- 缺陷：SDK bug 修复依赖每个 app 各自更新；同一 SDK 在多 app 上多份拷贝；app 开发者对 SDK 行为「黑箱」。

**SDK Runtime 可信分发（设计提案）**：
- SDK 开发者把**带版本的 SDK 上传到应用商店**，与应用**分离托管**。
- app 开发者只在清单里声明 `SDK 名 + 版本 + build`，**APK 不包含 SDK 实际代码**。
- 用户装 app 时，安装流程按声明去商店拉取对应 SDK 装入该 app 的 SDK Runtime。
- 收益：
  1. **免应用更新修 bug**：SDK 开发者发非破坏式（API/语义不变）更新，可直接下发到设备，**无需 app 开发者重新发版**。
  2. **多 app 共享一份磁盘实例**：同设备多 app 依赖同一 SDK 时，商店托管单份二进制，省存储。
  3. **防伪 SDK**：可信分发 + 安装校验，防止恶意/伪造 SDK 被加载。
- 底层支撑：`SharedLibraryInfo.TYPE_SDK_PACKAGE`——SDK 以**共享库包**形式安装（类似 `android:sharedUserId`/共享库机制演进），由 PMS 管理版本与依赖解析。

**新问题（面试要辩证说）**：分发灵活性下降、SDK 间兼容性测试更难、商店成为强依赖、灰度/回滚链路变长。

### A13 -> A17 演进
- A13 设计提案提出；A14/A15 随 Ad Services 扩展在 GMS 设备落地可信分发雏形。
- A17 整能力 deprecated——可信分发作为「Privacy Sandbox 子项」随倡议关闭而停止推进，但「SDK 与应用分离托管」的思路在后续模块化/按需交付（如 App Bundle / 动态交付）中仍有影子。

### 易错点
1. **SDK 二进制不在 APK 里**：是商店托管的共享库包，安装时解析拉取——这是和传统 AAR 最根本的区别。
2. **共享的是磁盘实例不是进程实例**：每个 app 仍有自己独立的 sandbox 进程（独立 UID 段），只是底层 SDK 文件可共享。
3. **deprecated 后别拿它当现网方案**：A17+ 设备不再支持，面试讲它是「架构范式 + 历史演进」，不是「最佳实践」。

### 考官高频连环追问（标准答案）
- "PMS 怎么保证加载的是正确版本的 SDK？" -> 清单声明 `version`/`build`，PMS 按 `SharedLibraryInfo` 解析依赖，加载失败返回 `LOAD_SDK_NOT_FOUND` / `LOAD_SDK_INTERNAL_ERROR`（见 `SdkSandboxManager` 常量）。
- "多 app 共享一份 SDK 文件，如何保证一个 app 的 sandbox 不读到另一个 app 的数据？" -> 文件可共享，但**进程与 UID 仍按 app 隔离**；sandbox 进程各自独立，运行时数据不互通，隔离在进程层而非文件层。

### 延伸阅读
SDK Runtime 设计提案「Trusted Distribution」章节；`frameworks/base/services/core/java/com/android/server/pm/`（SharedLibraryInfo / TYPE_SDK_PACKAGE）；Play 分发模型文档。

---

## 专题五：A17 弃用 + 面试正解 —— 为什么弃用？隔离思想去哪了？

### 问题
"SdkSandboxManager 在 A17（API 37）被 deprecated 了，是不是说明这个技术失败了、不用学了？Google 为什么弃用？被弃用之后，『隔离不可信第三方代码』这件事 Android 还做不做？和 isolatedProcess / WebView / AVF pKVM / A17 DCL 什么关系？"

### 答案解析 + 底层原理
**为什么弃用（事实链）**：
- **Privacy Sandbox 广告倡议 2025-10 被 Google 关闭**：以 Topics / Attribution / Protected Audience 为代表的「去交叉应用标识符广告 API」整体停止推进。
- 作为该倡议在 Android 上的承载，**SDK Runtime 平台能力随之在 A17 被 deprecated**（官方 *"The SDK sandbox is no longer supported."*）。
- 本质：**商业/隐私监管路线调整**，不是「进程隔离技术本身被证伪」。

**面试正解（辩证作答模板）**：
1. **弃用的是「Privacy Sandbox 广告 SDK 隔离方案」，不是「进程隔离思想」**。Android 隔离不可信代码的底层能力一直存在且增强：
   - `android:isolatedProcess`：通用最小特权隔离进程原语（WebView 渲染子进程、gms 核心、剪贴板服务等都在用）。
   - **WebView / Chrome 渲染进程**：多进程 + 站点隔离 + 沙箱，是移动端最成熟的第三方代码隔离实践。
   - **AVF / pKVM（EL2）**：本系列 8/02 讲的虚拟机级隔离，隔离强度远高于 sandbox 进程，是端侧 AI / 敏感计算的新沙箱。
   - **A17 安全内存（7-30/7-31/8-01）**：Memory Limiter、DCL 加固（dlopen .so 必须只读）、Keystore 每应用密钥限额——从「运行时加载不可信代码」侧收口。
2. **学到的价值在「模式」**：进程隔离 + 独立 UID 段 + 受限权限 + 受限 Binder + 可信分发，这套方法论可直接迁移到上述任一隔离机制，以及面试中「如何设计一个隔离不可信插件的系统」开放题。
3. **版本意识是加分项**：能说出 *"Added API 33，Deprecated API 37"* 的候选人，比只会背 A14 用法的候选人高一个段位。

### A13 -> A17 演进（收口）
- A13（API 33）：引入；A14/A15（34/35）：成熟 + Surface 渲染（API 35 部分 dep）；A17（API 37）：整类 deprecated，Privacy Sandbox 广告倡议 2025-10 关闭。

### 易错点
1. **别答「SDK Runtime 没用了所以不用学」**：要答「弃用的是广告场景承载，隔离范式延续」。
2. **别把 deprecated 说成 removed**：deprecated 表示不再推荐/维护新能力，存量设备与历史代码仍要懂。
3. **别混淆孤立机制**：sandbox / isolatedProcess / WebView / pKVM 是不同层级的隔离，强度与用途不同，面试要能区分。

### 考官高频连环追问（标准答案）
- "如果让你设计一个隔离不可信插件的 Framework，借鉴哪些？" -> 借 sandbox 的「独立 UID 段 + 受限权限 + 受控 Binder 接口 + 可信分发」，借 pKVM 的「强隔离 + attestation 身份」，借 A17 DCL 的「运行时加载必须只读」——按威胁模型选层级。
- "WebView 多进程和 SDK Runtime 谁隔离更强？" -> 同级进程隔离（各自独立 UID/地址空间）；但 WebView 渲染进程有更成熟的 seccomp/gpu 沙箱，sandbox 偏权限与数据面隔离；强度接近，用途不同。

### 延伸阅读
Android 开发者文档 `design-for-safety/privacy-sandbox`（已标注 deprecated）；`frameworks/base/core/java/android/app/sdksandbox/`；本系列 8/02（EL2/pKVM/AVF）、7-30/7-31/8-01（A17 安全内存）、8/17（HAL/内核/MTK 隔离与 SELinux）。

---

## 专题六：当日高频八股回炉压轴（跨域连环追问速查）

> 以下每题给「考官最爱追问 + 标准答案骨架」，完整源码级拆解见对应历史篇（括号标注），避免与 8/28 Rust 篇重复。

**Q1（进程隔离）**："SDK Runtime 的 sandbox 进程和宿主是同一 UID 吗？它为什么读不到宿主 SharedPreferences？"
-> 否。sandbox 是**独立 UID 段**的 Java 进程；与宿主地址空间隔离，默认无宿主权限；只有 `addSyncedSharedPreferencesKeys()` 白名单 key 才单向同步进 sandbox。（详见本篇专题二）

**Q2（Binder）**："sandbox 调 system_server，`getCallingUid()` 返回什么？怎么正确归属到宿主 app？"
-> 返回 sandbox 独立 UID 段，不是宿主 UID；`SdkSandboxManagerService` 维护 sandbox UID ↔ 宿主 UID 映射，system_server 须映射后归因。（详见本篇专题三、8/27 Binder 鉴权）

**Q3（Handler）**："`Looper.loop()` 死循环为啥不 ANR？sandbox 进程也有自己的 Looper 吗？"
-> 不占 CPU：`MessageQueue.next()` 无消息时 `nativePollOnce(-1)` → `epoll_wait` 休眠；ANR 是「处理某消息超时」。sandbox 进程作为 Java 进程同样有主线程 Looper。（详见 8/27 专题一）

**Q4（AMS/启动）**："冷启动为什么有时被 ContentProvider 拖慢？sandbox `loadSdk` 算启动必经路吗？"
-> `handleBindApplication()` 先 `installContentProviders()`，Provider `onCreate()` 早于 `Application.onCreate()`；三方 SDK 借 Provider 自启耗时 I/O 拖首帧。`loadSdk` 是**运行时按需**调，不走启动必经路（除非宿主在 Application 里强制 load）。（详见 8/19 启动链路、8/06 启动到首帧）

**Q5（WMS/View）**："SDK 在 sandbox 渲染的 UI 怎么显示到宿主？`requestSurfacePackage` 为什么 API 35 就 dep 了？"
-> 经 `SurfacePackage`/`Surface` 跨进程投到宿主 `SurfaceView`（`getHostToken()` 拿宿主 token）；UI 渲染职责 API 35 起移交 `androidx.privacysandbox.ui` 库，平台 API 退场。（详见本篇专题三、8/16 输入系统）

**Q6（ANR/卡顿）**："sandbox 进程卡死会拖垮宿主吗？怎么监控？"
-> 进程隔离，sandbox 卡死默认不直接 ANR 宿主主线程；用 `addSdkSandboxProcessDeathCallback` 感知沙箱死亡，宿主侧降级/重载。（详见本篇专题二）

**Q7（Compose）**："Compose 编译器插桩为什么能跳过未变化子树？和 sandbox 隔离有关系吗？"
-> 编译器对 `@Composable` 插桩记参数稳定性 + 位置缓存，运行期按 equality 跳过重组成；与 sandbox 无直接关系，属 UI 层独立优化。（详见 8-15 Compose 编译器与运行时底层）

**Q8（HAL/内核/隔离）**："SELinux 和 SDK Runtime 的隔离谁更底层？GKI 驱动怎么过 KMI？"
-> SELinux 是**内核层强制访问控制**，比 sandbox 进程隔离更底层、兜底所有进程；sandbox 是用户态进程/权限隔离，二者互补。GKI 冻结 `common` 符号为 KMI，vendor 驱动只依赖稳定 KMI。（详见 8-17 HAL 与 Linux 内核驱动全链路、MTK 小节）

**Q9（安全/跨版本）**："A17 除了弃用 SDK Runtime，在内存/安全上还多了哪些新雷区？"
-> Memory Limiter（应用内存限额）、DCL 加固（dlopen .so 必须只读）、Keystore 每应用密钥限额、跨资料环回默认阻断、隐式 URI 授权收紧。（详见 7-30/7-31/8-01 安全篇）

**Q10（Rust/收口）**："system_server 旁边跑着哪些 Rust 进程？和 sandbox 隔离是一个思路吗？"
-> `keystore2`、`compos`、`virtualizationservice` 等 Rust 进程，经 Binder 与 Java/C++ 互通；与 sandbox 都属「把不可信/高危代码放进独立隔离边界」思路，但 Rust 进程偏**语言级内存安全**、sandbox 偏**第三方 SDK 权限/数据面隔离**。（回扣 8/28 Rust 篇）

---

## 易错点速记（面试避坑清单）

1. SDK Runtime 是**平台级强制隔离**（独立 UID 段、默认无宿主权限），不是开发者自己 `android:process`。
2. `SdkSandboxManager` **Added API 33，Deprecated API 37**；Privacy Sandbox 广告倡议 2025-10 关闭——要能说清演进。
3. sandbox 进程**读不到宿主 SharedPreferences/存储**，只有白名单 key 单向同步；不能随便用 JNI/反射。
4. `SandboxedSdk(IBinder)` 就是普通 Binder 接口，区别在于「平台托管生命周期 + 跨 UID 段 + 鉴权」。
5. `getCallingUid()` 返回 sandbox UID（非宿主），归因必须**映射回宿主 UID**。
6. SDK 二进制**不在 APK 里**，是商店托管的共享库包（免应用更新修 bug、多 app 共享磁盘实例）。
7. deprecated 的是「广告场景承载」，**进程隔离范式延续**到 isolatedProcess/WebView/AVF pKVM/A17 DCL。

## 考官连环追问索引

- 版本锚点：Added API 33（A13）→ 成熟 A14/A15（34/35）→ `requestSurfacePackage` API 35 dep → 整类 Deprecated API 37（A17）；Privacy Sandbox 倡议 2025-10 关闭。
- 隔离层级：sandbox 进程（用户态权限/数据面）≈ isolatedProcess < SELinux（内核 MAC）≈ WebView 渲染沙箱 < pKVM/AVF（EL2 虚拟机级）。
- UID 映射：sandbox UID ↔ 宿主 app UID 由 `SdkSandboxManagerService` 维护，归因必须映射。
- 可信分发：`SharedLibraryInfo.TYPE_SDK_PACKAGE` 支撑「SDK 与应用分离托管」。

## 延伸阅读

- AOSP：`frameworks/base/core/java/android/app/sdksandbox/`（SdkSandboxManager / SandboxedSdkProvider / SandboxedSdk）、`frameworks/base/services/core/java/com/android/server/sdksandbox/SdkSandboxManagerService.java`、`packages/modules/SdkSandbox/`。
- 官方文档：`design-for-safety/privacy-sandbox`（已标注 deprecated，注意 API 33/37 演进注释）。
- 本系列交叉索引：8/28（系统层 Rust 化与内存安全边界）、8/27（跨版本全域八股总复盘·Binder 鉴权）、8/02（EL2/pKVM/AVF 机密计算）、8/17（HAL/内核/MTK 隔离与 SELinux）、7-30/7-31/8-01（A17 安全内存）、8/19（启动链路源码级）、8/16（输入系统全链路）、8/15（Compose 编译器与运行时底层）、8/06（全链路排查实战 + binder.c code walk）。

---

> 第 46 篇完。本篇刻意只做「SDK Runtime / 隐私沙箱（含 A17 deprecated）」这一真缺口，不重复 8/28 的 Rust 篇与 8/27 的全域总复盘；三者互为补充。后续若继续日更，建议下一缺口角度：**「Android 权限模型演进：Runtime Permission / Scoped Storage / 细粒度位置 / A17 隐私新边界源码级」或「WebView 多进程与渲染沙箱（隔离强度对比 sandbox/pKVM）」**。
