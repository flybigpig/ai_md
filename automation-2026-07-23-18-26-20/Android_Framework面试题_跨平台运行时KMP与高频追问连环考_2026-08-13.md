# Android Framework 面试题 · 跨平台运行时（KMP / Compose Multiplatform）深挖 + 跨域高频追问连环考（第 28 篇）

> 日期：2026-08-13 ｜ 系列第 28 篇 ｜ 累计约 183 专题（本篇为跨平台运行时补齐篇，不做重复深水区）
> 主线 baseline：Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX，内核 GKI android14-6.1)
> 热点锚定：Android 17 stable（代号 CinnamonBun，2026-06-16）已发布；**A17 QPR2 Beta 2 于 2026-08-03 推送**（build CP41.260701.006 / 开发者站标注 CP41.260717.006，代号 DEV，无行为变更，stable 预计 2026-12，Pixel 6/6 Pro EOL 退出）；A17 八月安全补丁已推送到 Pixel 全系 + 小米 17 系列首发 stable（HyperOS 3）；**A18 路线图为"融合收官"**——桌面模式巩固（Project Ferrochrome / 与 ChromeOS 内核级融合）、通用剪贴板、跨设备 Handoff、AppFunctions/Gemini 扩张、手柄重映射 + 虚拟手柄、Material 3 Expressive 毛玻璃。结论：**2026 面试笔试之外，跨平台（KMP / Compose Multiplatform）运行时差异是 Android 岗被问得越来越多的"新八股"，而它恰好是本系列 27 篇唯一的真缺口——今天一次补齐。**

---

## 0. 为什么今天做"跨平台运行时 + 高频追问连环考"

前 27 篇（约 183 专题）已经把主线（Binder/AMS/WMS/SF/ART/HAL/内核）+ 盲区 + 深水区 + 智能层(AppFunctions/Compose)+ 安全世界(TEE/pKVM)+ 座舱(AAOS)+ 端侧 AI + 源码 code walk + Perfetto SQL + 基础八股 + 两版真题大乱斗 全部闭环。复盘时发现两个缺口：

```
缺口一（真缺口）：跨平台运行时差异
  第 15 篇只讲了"Android 侧 = Kotlin/JVM -> DEX -> ART，无额外 runtime"，
  但 skiko / Kotlin-Native / 非 Android target 的内存模型 / Compose Multiplatform
  与 Jetpack Compose 的关系，从来没独立成篇。今天补。

缺口二（形态缺口）：跨域"高频追问连环考"
  前 27 篇每篇末尾有"追问链"，但都是单篇内部追问。面试官真实连击是
  跨域的（Handler -> Binder -> AMS -> 启动 -> ANR 一气呵成）。今天把
  你列的领域（Handler/Looper、Binder、AMS/ATMS、WMS、View、启动、内存/卡顿/ANR、
  Compose 底层、HAL/Kernel/Drivers/MTK）串成"考官连续追问"形态，用于冲刺自检。
```

> 约定：文中 `frameworks/` `system/` `drivers/` 路径默认是 **Android 14 AOSP (android-14.0.0_rXX)**；内核路径为 **GKI common-android14-6.1**；JetBrains 开源路径单独标注（非 AOSP）。涉及 A17 新增项显式标注 `[A17]`。

---

## 专题一：KMP / Compose Multiplatform 在 Android 侧的运行时差异（真缺口深挖）

**现象 / 考官提问**
> 1) 你们用 KMP 共享逻辑，Android 端跑的是什么运行时？需要额外 runtime 吗？2) Compose Multiplatform 和 Jetpack Compose 是两套东西吗？3) 为什么 skiko 在 Android 上"用不到"？4) Kotlin/Native 在 iOS 上跑 ART 吗？它的内存模型和 Android 的 ART GC 有什么区别？5) Compose 编译器现在是个独立插件吗？

### 1.1 KMP 的编译管线：androidMain 就是 Kotlin/JVM，落到 ART

Kotlin Multiplatform（JetBrains，SDK 级）的核心是"共享逻辑、UI 各自原生"。它按 source set 把代码编译到不同后端：

```
commonMain  (共享大脑：网络 Ktor / 持久化 Room-SQLDelight / 业务状态)
   |
   +-- androidMain  --> Kotlin/JVM --> .class --> DEX --> 在 Android Runtime(ART) 上执行
   +-- iosMain      --> Kotlin/Native (LLVM) --> 原生 Mach-O .framework，跑在 iOS，无 ART/Dalvik
   +-- desktopMain  --> Kotlin/JVM (桌面 JVM) --> 原生窗口(Swing/窗口管理)
   +-- wasmJsMain   --> Kotlin/Wasm 或 Kotlin/JS --> 浏览器 Wasm/JS
```

- **关键点一**：`androidMain` 里写的 Kotlin，**编译链路和纯 Android 工程完全一样**——`kotlinc` -> JVM 字节码 -> `d8`/`r8` -> DEX -> 由 `app_process`(Zygote 派生的 ART 虚拟机)执行。**没有任何额外 runtime 注入**，它和你的 `MainActivity.kt` 在同一个 ART 实例里。
- **关键点二**：`expect/actual` 机制是编译期契约。`commonMain` 里 `expect fun getPlatformName()`，`androidMain` 里 `actual fun getPlatformName() = "Android ${Build.VERSION.SDK_INT}"`——`actual` 可以直接 import `android.os.Build`（第 13 篇讲过 AppFunctions 也依赖这套契约）。**expect/actual 让你在共享层直接触达平台类型，但不破坏"共享大脑"**。
- **面试坑**：KMP 不是"一个虚拟机罩在 OS 上"。Android target 直接是 JVM/ART；只有 iOS/桌面/Web 才是另类后端。所谓"Write Once Debug Everywhere"的吐槽，正是指对端 (Kotlin/Native/Wasm) 的调试与行为差异，而非 Android 侧。

### 1.2 Compose Multiplatform 与 Jetpack Compose：Android target 就是 Jetpack Compose 本身

Compose Multiplatform（CMP，JetBrains，声明式 UI 框架）是构建在 KMP 之上的 **UI 层**。它把 Jetpack Compose 的能力扩展到 iOS/桌面/Web：

```
Jetpack Compose (Google, Android 原生)
        ^
        |  同一套 Compose 编译器插件 + 同一套 compose-runtime
        |
Compose Multiplatform (JetBrains, 跨平台 UI 层)
   - Android target   : 直接用 Jetpack Compose（compose-ui / compose-runtime / material3），无任何重实现
   - iOS target       : 同一 @Composable，渲染走 Skiko -> Metal(CAMetalLayer)
   - Desktop(JVM)     : 同一 @Composable，渲染走 Skiko -> OpenGL/DirectX
   - Web(Wasm)        : 同一 @Composable，渲染走 Skiko -> Canvas(Wasm)
```

- **关键结论（必背）**：**在 Android 上，Compose Multiplatform 就是 Jetpack Compose**。它复用完全相同的 `androidx.compose.runtime` (`Snapshot`/`Recomposer`/`SlotTable`，第 13 篇已深挖)、`androidx.compose.ui`（`LayoutNode` -> `RenderNode` -> HWUI/Skia，第 13/20 篇讲过未绕过 HWUI）、`material3`。CMP 在 Android 上**没有第二份运行时、没有第二套渲染后端**——这是和 Flutter（自带 Skia + Dart VM）、React Native（自带 JS 引擎 + Yoga）本质不同的地方。
- **Compose 编译器去哪了**：自 **Kotlin 2.0（K2 编译器）** 起，Compose 编译器已并入 Kotlin 主编译器，不再有独立版本号；CMP 最新 stable 是 **v1.11.0（2026-05-13）**，要求 **最低 Kotlin 2.2.0**。Gradle 侧只需 `org.jetbrains.kotlin.plugin.compose` 插件，由 Kotlin Gradle Plugin 统一校验版本。这是 2026 面试的高频新考点（"Compose 编译器还要单独配吗？"-> 不用，已经合进 Kotlin 主编译器）。

### 1.3 skiko 仅非 Android target：Android 走 HWUI，其它走 Skia

Skiko = "Skia for Kotlin"，是 JetBrains 对 Skia 的 Kotlin 绑定，提供跨平台渲染 + 事件处理抽象。**它的服务对象是非 Android target**：

| Target | 渲染后端 | 硬件加速通道 | 是否用 skiko |
|--------|----------|--------------|--------------|
| Android | HWUI (Android Framework 的 Skia 封装，经 RenderThread/GL/Vulkan) | OpenGL ES / Vulkan（通过 Bitmap/RenderNode） | 否（直接用 Jetpack Compose 的 HWUI 路径） |
| Desktop (JVM) | Skia via Skiko | OpenGL / DirectX | 是 |
| iOS | Skia via Skiko -> Metal | CAMetalLayer（直接渲染 Metal，非模拟） | 是 |
| Web (Wasm) | Skia via Skiko -> Canvas | Wasm + Canvas 2D | 是 |

- **为什么 Android 不需要 skiko**：Android 已经有系统级 Skia 封装（HWUI，`frameworks/base/libs/hwui/`，第 7、24 篇讲过 RenderThread 把 DisplayList 经 OpenGL/Vulkan 提交给 GPU）。Jetpack Compose 的 `RenderNode` 直接对接 HWUI，**复用 Framework 既有的渲染管线 + 与 SurfaceFlinger 的 BufferQueue 对接**，没必要再叠一层 skiko。
- **iOS 端的真相**：Skiko 在 iOS 上是直接渲染到 `CAMetalLayer`（2026 已稳定，可上 120Hz ProMotion），不是"模拟原生控件"，所以文本/滚动/无障碍都是 Compose 自己绘制——这也是 CMP 在 iOS 上的经典坑：**XCTest 看整个 Compose 屏幕是一个 opaque UIView/UIViewController**，原生自动化拿不到内部语义树（呼应第 13 篇 Compose 语义树对 Agent 更友好的反向对照）。

### 1.4 Kotlin/Native 内存模型：iOS 上没有 ART，用的是 K/N 自己的 GC

这是"Android 侧 vs 非 Android target"运行时差异里最容易答错的点。

- **iOS 不跑 ART/Dalvik**：Kotlin/Native 用 LLVM 把 Kotlin 直接编译成原生机器码 + 一个轻量运行时（含自己的 GC）。iOS 没有 JVM，也没有 ART 的分代/CMC/并发标记。所以"iOS 上的对象怎么回收"和 Android 的 ART GC（第 8、19、29 篇讲过 CMC/分代）**完全是两套机制**。
- **内存模型演进（必背时间线）**：
  - **Legacy 模型（Kotlin < 1.7）**：默认"冻结(freeze)"——跨线程共享的对象会被冻结为不可变，违反就抛 `InvalidMutabilityException`；并发靠 `Worker`。这是老面经里"Kotlin/Native 对象不能随便跨线程"的根源。
  - **New Memory Model（默认自 1.7.20，稳定于 1.9/2.0）**：**默认不再全局冻结**，对象可在线程间自由传递；不可变共享用 `@SharedImmutable`，可变共享用 `AtomicReference`/`FreezableAtomicReference`/`StableRef`；并发仍用 `Worker` + `Future`。**面试纠正**：如果你背的是"K/N 对象 freeze 不能跨线程"，那是过时答案——2026 年默认是新模型。
- **与 ART 的区别一句话**：ART 是托管运行时（DEX/AOT/JIT 混合、分代/CMC/并发标记、对象头 LockWord/Monitor，第 8 篇），Kotlin/Native 是原生码 + 独立 GC（无 DEX、无 JIT、无对象头锁字）；两者只在 `androidMain` 汇聚到 ART，在 `iosMain` 完全分流。

### 1.5 KMP/CMP 在 Android 面试里怎么考（易错点 + 追问链）

**易错点（红榜）**
- "KMP 跨端都要装 runtime"。错：androidMain 直接是 ART，零额外 runtime；只有非 Android target 才有各自的原生运行时。
- "Compose Multiplatform 在 Android 上是另一套 Compose"。错：Android target 就是 Jetpack Compose 本身，共用 compose-runtime/compose-ui，无第二运行时。
- "skiko 是 Compose 必须的"。错：skiko 只服务非 Android target；Android 走 HWUI。
- "Kotlin/Native 对象必须 freeze 才能跨线程"。错：新内存模型（默认 1.7.20+）已取消默认冻结。
- "Compose 编译器要单独配版本"。错：Kotlin 2.0 起并入主编译器，CMP 1.11 要求最低 Kotlin 2.2.0。

**高频追问链**
1. KMP 共享层能直接 import `android.os.Build` 吗？-> 不能，只能在 `androidMain` 的 `actual` 里；共享层只能用 `expect` 声明 + 通用类型。
2. CMP 在 iOS 上无障碍为什么是痛点？-> 整个 Compose 屏幕对 XCTest 是单个 opaque UIView，拿不到内部控件树（与 Android 上 ANI 能跨进程拿语义树相反）。
3. `[A17]` Compose-First 纲领对 KMP 有何影响？-> A17 官宣新 API/库只面向 Compose（Fragment/RecyclerView/ViewPager/android.widget 进 maintenance mode，第 13 篇），意味着新业务 UI 天然倾向 Compose，KMP 共享 UI 的阻力更小。
4. Kotlin/Native 和 ART 谁做 JIT？-> 都"基本不做"：ART 以 AOT(dex2oat)为主 + 少量 JIT(第 24 篇)；Kotlin/Native 是全量 AOT 原生码，无 JIT。

**延伸阅读（开源路径）**：`github.com/JetBrains/compose-multiplatform`（含 `compose-multiplatform-core`/`compose-multiplatform`）、`github.com/JetBrains/kotlin`（Kotlin/Native 运行时与 new memory model）、`github.com/JetBrains/skiko`（Skia Kotlin 绑定）。

---

## 专题二：跨域高频追问连环考（考官连续追问形态）

下面每条是"考官一口气连问"，用于冲刺自评。答案只给**定位 + 一句话**，逼自己展开才是真会。

### 2.1 Handler / Looper 追问链
1. 主线程 `Looper.loop()` 死循环为什么不卡死？-> 无消息时 `nativePollOnce` -> `epoll_wait` 休眠（S 态），CPU 近 0（第 26 篇）。
2. 那为什么不 ANR？-> ANR 是 system_server 看门狗发现某条 `dispatchMessage` 太久没返回、饿死后续消息（含输入）才触发，loop 本身无罪（第 26 篇）。
3. `epoll` 还监听什么？-> `mWakeEventFd` + 通过 `addFd` 注册的 `LooperCallback`（InputChannel/Surface fd 等 native 事件）。
4. 同步屏障漏移除会怎样？-> 普通 UI 消息被永久挡住，界面假死但**不 ANR**（loop 在跑，只是你的消息排不上）。
5. `[A17]` MessageQueue 改 lock-free 影响上面哪条？-> 只改内部锁，epoll 唤醒模型与语义完全一致。

### 2.2 Binder IPC 追问链
1. 一个进程默认能并发扛几个 incoming binder 调用？-> 线程池默认 15 + 1 主线程 = 16 路（`ProcessState`/`max_threads`，第 26 篇）。
2. 谁决定再 spawn 线程？-> 内核 `binder.c` 回 `BR_SPAWN_LOOPER`，用户态 `spawnPooledThread`（第 26 篇）。
3. oneway 满了会立即失败吗？-> 不会，占线程 + 事务槽位，满了在驱动层阻塞排队（第 19/21/26 篇）。
4. 客户端怎么知道对端挂了？-> `linkToDeath` -> `binder.c` 死亡列表发 `BR_DEAD_BINDER` -> `binderDied()` 在 binder 线程回调（别在里做重活/跨进程）。
5. `getCallingUid()` 何时不可信？-> 跨 VM（AVF/pKVM，第 12 篇）映射 UID 不可信；Provider 侧拿到的是 SYSTEM_UID（第 13 篇），要用 AttributionSource。

### 2.3 AMS / ATMS + App 启动 追问链
1. 点击图标到 Activity 起来，谁调度？-> `ATMS.startActivity` -> `ActivityStarter` -> `realStartActivityLocked` -> 经 `ZygoteProcess` socket fork（第 20 篇）。
2. `ContentProvider` 在启动里是什么坑？-> `handleBindApplication` 里 `installContentProviders` 先于 `onCreate`，Provider 慢会拖冷启动（第 18/19/20 篇）。
3. 进程怎么来的？-> Zygote 预孵 `app_process`，fork 后 `ActivityThread.main` -> `Looper.prepareMainLooper` + `loop`（第 20 篇）。
4. `[A17]` 启动为何可能因 16KB 页崩溃？-> 旧 so 按 4KB 页对齐，16KB 页要求重新对齐，否则 crash（第 16/27 篇）；配合 hiddenapi/非 SDK 接口收紧叠加（第 8/31 篇）。

### 2.4 WMS + View 绘制测量 追问链
1. 事件分发三个方法谁在 ViewGroup？-> `dispatchTouchEvent`(所有 View) / `onInterceptTouchEvent`(仅 ViewGroup) / `onTouchEvent`(所有 View)（第 26 篇）。
2. `requestDisallowIntercept` 对 DOWN 有效吗？-> 无效，DOWN 会重置该标志（第 26 篇）。
3. `onTouch` 和 `onClick` 谁先？-> `onTouch`(返回 true 吞掉) 优先于 `onClick`；`onClick` 由 `OnClickListener` 在 UP 后触发（第 26 篇）。
4. `getMeasuredWidth()` 和 `getWidth()` 何时不等？-> `measure` 之后 `layout` 之前不等；`AT_MOST` 下父给上限、子实际可能更小（第 26 篇）。
5. 绘制三阶段谁触发一次遍历？-> `ViewRootImpl.performTraversals` -> measure/layout/draw，经 `Choreographer` VSYNC 驱动（第 20 篇）。

### 2.5 内存 / 卡顿 / ANR 追问链
1. 三条杀进程路径怎么区分？-> 内核 OOM / LMKD(PSI) / `[A17]` Memory Limiter 个体超标静默杀（第 12/19/27 篇）。
2. 掉帧定责用 Perfetto 哪个表？-> `actual_frame_timeline_slice` JOIN `expected_frame_timeline_slice` 按 `frame_number`+`upid`，看 `jank_type`/`present_type` 定到 App/RenderThread/SF/HWC（第 7/21/25 篇）。
3. 主线程锁竞争怎么量化？-> `thread_state` 状态分布 + `monitor_contention` slice（第 21 篇）。
4. ANR 四类超时？-> 输入 5s / 广播前台 10s 后台 60s / Service 20s / ContentProvider 10s（第 19/23 篇）。
5. `[A17]` Art 分代 GC 经什么热更？-> 经 `com.android.art` APEX(Mainline) 下发，无需整系统升级（第 8/29 篇）。

### 2.6 HAL / Kernel / GKI / Drivers / MTK 追问链
1. Treble 之后 HAL 用什么描述？-> HIDL(旧) -> **AIDL HAL(新)**，跨进程经 `/dev/hwbinder`（第 1/23 篇）。
2. GKI 为何要 KMI 稳定？-> 厂商内核模块(vendor module)要匹配稳定 Kernel Module Interface，否则 OTA 后模块加载失败（第 1/12 篇）。
3. 字符驱动骨架？-> `miscdevice` + `file_operations`，注册到 `/dev/xxx`，配 SELinux + KMI/vendor hook（第 26 篇）。
4. MTK AEE 是什么？-> vendor 的异常捕获框架（`exp_main` + `mtklog` 三件套：aee/`db`/mobilelog），死机/重启抓 `db.xxx` 给售后分析（第 26 篇）。
5. MTK `PerfService` / thermal 干什么？-> `PerfService` 锁 CPU 频点/核（游戏防降频），thermal 走 vendor thermal HAL 做温控（第 26 篇）。

---

## 专题三：跨子系统高频追问链（综合压轴，考官最爱）

**链 A：冷启动慢 + ANR + Binder 三方叠加（呼应第 19/23/27 篇）**
现象：App 冷启偶发 ANR。考官连问：① 冷启时间窗怎么用 Perfetto 切？(`android_startup` + `slice` 拆 `bindApplication`/`installContentProviders`，第 21 篇) ② `installContentProviders` 为什么是坑？(先于 `onCreate`，Provider 慢拖全局，第 20 篇) ③ 是不是 Binder 对端阻塞？(看 `binder_transaction` 的 kernel 拷贝 vs 对端执行两段延迟，第 21 篇) ④ 会不会是 15 线程池耗尽？(oneway 满也排队，第 26 篇) ⑤ 有没有被 LMK 杀？(三路杀辨析，第 19 篇)。

**链 B：Compose 卡顿 + 无障碍 Agent + 语义树（呼应第 13 篇）**
现象：Compose 页面 AI 自动化点击偶尔失效。考官连问：① Compose 为什么对 Agent 更友好？(语义树 `SemanticsNode` 直接映射到 ANI，第 13 篇) ② `pointerInput` + `detectTapGestures` 为何没有 `onClick` 语义？(它只产生手势，不生成语义点击，需补 `clickable`，第 13 篇) ③ Recomposer 挂在哪个 Choreographer 回调？(ANIMATION 回调，View traversal 在 TRAVERSAL 回调，同帧先后，第 13 篇) ④ iOS 上 XCTest 为什么拿不到内部树？(skiko/Metal 渲染成单个 opaque UIView，第 1.3 节)。

**链 C：跨设备 AI + CDM + RPC Binder 安全边界（呼应第 12/13/22/27 篇）**
现象：跨设备把任务交给 AI Agent 执行。考官连问：① 跨 VM 的 `getCallingUid` 可信吗？(不可信，RPC Binder 经 vsock 映射 UID，第 12 篇) ② Provider 侧拿到的是谁？(SYSTEM_UID，要用 AttributionSource，第 13 篇) ③ CDM 在 A17 QPR2 改了什么？(锁屏屏幕自动化权限重写，把 companion 从无障碍分流，第 22 篇) ④ EU DMA 对 Framework 冲击？(强制开放 11 项 AI 能力，助手可替换 VoiceInteractionService，第 22 篇)。

---

## 专题四：易错红榜 TOP20（跨域速记）

1. 主线程死循环耗 CPU -> 错，epoll 休眠近 0。
2. ANR 是 Looper 的锅 -> 错，是某条 dispatchMessage 太久饿死后续消息。
3. 同步屏障可随便加 -> 错，漏 remove 致界面假死不 ANR。
4. Binder 单线程串行 -> 错，默认 15+1 并发。
5. oneway 满了立即失败 -> 错，驱动层阻塞排队。
6. linkToDeath 在开关线程回调可弹 UI -> 错，在 binder 线程，需 post 主线程。
7. getCallingUid 永远可信 -> 错，跨 VM / Provider 侧不可信。
8. KMP Android 端需额外 runtime -> 错，直接 ART。
9. Compose Multiplatform 在 Android 是另一套 -> 错，就是 Jetpack Compose。
10. skiko 是 Compose 必须的 -> 错，仅非 Android target。
11. Kotlin/Native 对象必须 freeze -> 错，新内存模型默认不冻结。
12. Compose 编译器要单独配版本 -> 错，K2 起并入主编译器。
13. requestDisallowIntercept 对 DOWN 有效 -> 错，DOWN 重置标志。
14. onTouch 晚于 onClick -> 错，onTouch 优先，返回 true 吞掉 onClick。
15. getMeasuredWidth == getWidth 恒成立 -> 错，measure 后 layout 前不等。
16. 16KB 页只是大小变化 -> 错，旧 so 未对齐会 crash（A16+ 强制）。
17. 三条杀进程路径等价 -> 错，内核 OOM / LMKD(PSI) / A17 Memory Limiter 个体超标。
18. Treble 仍主要用 HIDL -> 错，新 HAL 全面 AIDL（`/dev/hwbinder`）。
19. GKI 厂商可随意改内核符号 -> 错，受 KMI 稳定约束。
20. AppFunctions Provider 拿真实调用方 UID -> 错，拿到 SYSTEM_UID，用 AttributionSource。

---

## 专题五：AOSP / 开源路径清单（本篇新增 + 索引）

**本篇跨平台运行时（JetBrains 开源，非 AOSP）**
- Compose Multiplatform：`github.com/JetBrains/compose-multiplatform`（含 `compose-multiplatform-core`）
- Kotlin / Kotlin-Native：`github.com/JetBrains/kotlin`（new memory model、K2 Compose 编译器并入）
- Skiko（Skia for Kotlin）：`github.com/JetBrains/skiko`

**复用前 27 篇 AOSP 关键路径（本篇追问链落点）**
- Handler/Looper：`frameworks/base/core/java/android/os/{Looper,MessageQueue}.java`、`frameworks/base/core/jni/android_os_MessageQueue.cpp`、`system/core/libutils/Looper.cpp`
- Binder：`frameworks/native/libs/binder/{ProcessState,IPCThreadState}.cpp`、`drivers/android/binder.c`、`frameworks/base/core/java/android/os/{Binder,BinderProxy}.java`
- 启动/AMS：`frameworks/base/core/java/android/app/ActivityThread.java`、`frameworks/base/core/java/android/os/ZygoteProcess.java`、`frameworks/base/services/core/java/com/android/server/am/`、`com/android/server/wm/`
- View/WMS：`frameworks/base/core/java/android/view/{View,ViewGroup,ViewRootImpl}.java`
- 渲染/Perfetto：`frameworks/base/libs/hwui/`、`frameworks/native/services/surfaceflinger/`、`frameworks/base/core/java/android/view/Choreographer.java`
- 内存/ANR：`system/core/lmkd/`、`frameworks/base/services/core/java/com/android/server/am/ProcessList.java`、`frameworks/native/services/inputflinger/InputDispatcher.cpp`
- GKI/MTK：`common-android14-6.1/drivers/`、`vendor/mediatek/`（AEE/PerfService/thermal）

---

## 专题六：28 篇交叉索引 + 复习节奏

**全系列 28 篇知识地图（截至 2026-08-13）**
| # | 篇名（主题） | 关键落点 |
|---|--------------|----------|
| 1 | 主篇(16章) | Handler/Binder/冷启动/Zygote/AMS/ANR/HAL/GKI/MTK 主线 |
| 2 | 热点拓展(10章) | Input 全链路/PMS/ART/JIT/AOT/SystemUI/SELinux/OTA/Perfetto |
| 3 | 深挖篇(11章) | ART 对象头/CMC/Binder 驱动/Rust Binder/Vulkan/Codec2/Thermal |
| 4 | 图形多媒体通信(12章) | HWUI/SF/HWC/MediaCodec/Codec2/Thermal HAL/Power HAL/RIL/Wi-Fi/BT |
| 5 | 系统基建(11章) | 16KB 页/ClassLoader/权限/Keystore2/AVB/fscrypt/logd/RRO/Doze |
| 6 | 端侧AI+A17(10章) | NNAPI/NPU/CarService/Vulkan/ART 镜像/virtual A/B |
| 7 | A17 新雷区(8章) | lock-free MQ/ART 分代 GC/hiddenapi/ProfilingManager/NFC/SE/Media3/LLM |
| 8 | 渲染合成+A17安全(7章) | RenderEngine/HWC/Memory Limiter/DCL 加固/Keystore 限额/CarService |
| 9 | 兼容性框架(10章) | platform_compat/SizeCompat/BAL/Bubbles/Handoff/Pointer Capture/ECH |
| 10 | 安全世界TEE(8章) | Trusty/SMC/TIPC/KeyMint/Gatekeeper/Attestation/Widevine/ION->DMA-BUF |
| 11 | pKVM/AVF(8章) | pKVM/AVF/AISeal/RPC Binder/Connectivity eBPF/Ravenwood |
| 12 | 智能系统(9章) | AppFunctions/AppSearch/Compose 编译器+运行时/APK 签名 v3.2/ApplicationExitInfo |
| 13 | 端侧AI+AAOS(6章) | LiteRT NPU/LLM INT4/KV cache/CarService 电源/StrongBox/Protected Confirmation/AVF 隔离编译 |
| 14 | 末轮补全+导航 | Codec2 vendor/KMP Android 侧/Robolectric vs Ravenwood/A18 前瞻 |
| 15 | 收官补遗 | CarService 电源状态机/端侧 LLM 量化实操 |
| 16 | 考前速查卡 | 15 篇知识地图 + 子系统速答 + 易错红榜 |
| 17 | 高频连击模拟考 | 132 专题转考场 |
| 18 | 全链路排查实战 | 冷启动/卡顿/ANR/内存三路杀/发热/Binder 实战 |
| 19 | 源码 code walk | startActivity->首帧/Binder 一次事务全追踪 |
| 20 | Perfetto SQL 库 | 冷启动/掉帧/主线程/Binder/电源 SQL |
| 21 | A18 桌面融合 | Desktop Mode/WM Shell/ActivityEmbedding/CDM/Universal Clipboard |
| 22 | 真题大乱斗 | 8 跨子系统混合场景压轴卷 |
| 23 | ART 运行时 | 三态/dex2oat/compiler filter/Profile 全链路/冷启动根因 |
| 24 | Perfetto SQL 扩充 | input 延迟/GPU 计数器/battery 耗电 |
| 25 | 核心基础深挖 | native Looper/IdleHandler/同步屏障/消息池/Binder 池/linkToDeath/View 事件/MeasureSpec/GKI/MTK |
| 26 | 真题大乱斗 vol.2 | 8 更深三+子系统叠加压轴 |
| 27 | （本系列此前末篇索引见上） | — |
| **28** | **跨平台运行时 KMP + 高频追问连环考** | **KMP/CMP 在 Android 侧运行时差异(skiko/K-Native 内存模型)、跨域追问 drill、跨子系统链** |

**复习节奏建议（冲刺期）**
- 每日：抽 1 条"跨域高频追问链"闭卷自答，卡住就回对应篇的定位路径重新读源码。
- 每周：跑 1 条"跨子系统高频追问链"（链 A/B/C），训练把单点八股焊成面试现场推理。
- 上机：每道题尽量 `grep`/`cs` 到 AOSP 真实路径 + 方法名，能背出 `文件:方法` 才是真会。

**全系列真缺口状态**：27 篇已清零全部规划缺口；本篇补上最后一块"跨平台运行时（KMP/CMP/非 Android target skiko/Kotlin-Native 内存模型）"，系列至此 28 篇闭环，主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧 AI + 源码 walk + Perfetto SQL + 基础深挖 + 两版真题大乱斗 + 跨平台运行时 完整覆盖。

---

> 小结：今天的核心是**跨平台运行时差异**——一句话记住："Android target 的 KMP 就是 Kotlin/JVM 跑在 ART 上，Compose Multiplatform 在 Android 就是 Jetpack Compose 本身，skiko 只服务 iOS/桌面/Web，Kotlin/Native 在 iOS 上用自己的 GC 而非 ART"。其余篇幅把 Handler/Binder/AMS/启动/WMS/View/内存/卡顿/ANR/HAL/内核/MTK 串成跨域追问，用于冲刺自检。
