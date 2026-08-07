# Android Framework 面试题 · 第 20 篇 · 源码级 code walk 专项：从 startActivity 到首帧上屏

> 整理日期：2026-08-06
> 基线：AOSP Android 14 (UpsideDownCake, API 34) / kernel GKI android14-6.1
> 系列定位：前 19 篇约 138 专题已闭环「主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧 AI + 收官补遗 + 速查卡 + 连击考 + 全链路排查」。本篇专做**源码级 code walk**——把分散的八股串成一条可追的端到端链路，覆盖用户点名的四大主题：**App 启动流程、View 绘制与测量、渲染合成（SurfaceFlinger）、Binder IPC**，并贯穿 AMS/WMS。

---

## 〇、当日热点锚定（为什么今天做这条链路）

联网锚定的 2026-08-06 当日信号：

1. **Android 17 QPR2**（当前稳定 Beta，build CP41.260701.006，无新增行为变更；Pixel 10 系列已纳入）。QPR2 修复清单里有多指拖拽丢触摸 #516836306、窗口模糊渲染 #527376569——本质都是「Input → WMS → SurfaceFlinger 合成」链路的现场 bug，正好用本篇的链路逻辑去溯源。
2. **A18 桌面融合 / Googlebook**：A17 桌面模式已成熟（taskbar、status bar、可调整浮窗、90:10 分屏、外显支持经 Android 16 QPR3 GA），A18 将是「手机 + PC 统一内核」的收口（通用剪贴板 + 跨设备 handoff API）。这要求 WMS / WindowOrganizer / 多 Display 的链路极其扎实——本篇 §3/§5 的 Window 与 Surface 创建正是地基。
3. **EU DMA**（2026-07-16 裁定）：要求 Google 在 A18 前开放 11 项 AI 能力给第三方助手，与 QPR2 的 **CDM 锁屏屏幕自动化重写**（PIN 门控配对）直接勾连。屏幕自动化依赖本篇 §4 的 View 树与 §3 的 Window 体系做 UI 遍历。
4. **Material 3 Expressive + 毛玻璃**：A17 QPR2 新增「Disable background blur」无障碍开关。毛玻璃 = 跨 Surface 的 Blur 效果，落点在 SurfaceFlinger 合成阶段（§5）。
5. **经典八股仍高频**（面试鸭 / CSDN 2026 信号）：Handler/Looper（链表队列、ThreadLocal、同步屏障、epoll）、Binder（mmap 一次拷贝、线程池、Transaction 码、Parcel 顺序）、AMS/WMS/PMS、性能优化（LeakCanary、Looper Printer、Choreographer 帧率）。本篇 §1/§2/§6 直接给源码佐证。

> 结论：面试官最爱问「startActivity 之后发生了什么，到界面显示经历了哪些阶段」。能把这条链路从 Java Framework 一路追到内核 Binder 驱动、再追到 SurfaceFlinger 合成上屏的人，就是分水岭。本篇就做这件事。

---

## 一、全景链路图（五段，纯 ASCII）

```
[1] AMS/ATMS 调度
App: startActivity(intent)
 -> ContextImpl.startActivity
 -> ATMS.startActivityAsUser                         (Binder 跨进程到 system_server)
 -> ActivityStarter.execute -> startActivityUnchecked
 -> RootWindowContainer.resumeFocusedTasksTopActivities
 -> ActivityStack.resumeTopActivityInnerLocked
 -> 目标进程不存在? -> ActivityStackSupervisor.realStartActivityLocked
       -> AMS.startProcessLocked (Binder 到 AMS)
            -> ProcessList.startProcessLocked
                 -> ZygoteProcess 通过 Socket 发参数给 Zygote

[2] Zygote fork + Application 启动
Zygote 端: ZygoteConnection.processCommand
 -> Zygote.forkAndSpecialize               (fork 出 app 进程)
子进程: RuntimeInit.applicationInit
 -> ActivityThread.main
 -> ActivityThread.attach
 -> AMS.attachApplication (Binder 回 system_server)
 -> ActivityThread.bindApplication
 -> handleBindApplication -> makeApplication
      -> installContentProviders (前置坑!)
      -> Instrumentation.callApplicationOnCreate

[3] Activity 生命周期 + Window 创建
ActivityStackSupervisor.realStartActivityLocked
 -> ClientTransaction: LaunchActivityItem
 -> ActivityThread.handleLaunchActivity
      -> performLaunchActivity (ClassLoader 建实例 -> attach -> onCreate)
 -> ActivityThread.handleResumeActivity
      -> performResume -> onStart/onResume
      -> WindowManagerGlobal.addView
           -> ViewRootImpl 构造 + setView

[4] View 绘制三阶段 (measure/layout/draw)
ViewRootImpl.performTraversals
 -> performMeasure  -> View.measure  -> onMeasure
 -> performLayout   -> View.layout   -> onLayout
 -> performDraw     -> View.draw     -> onDraw / dispatchDraw

[5] 首帧渲染上屏 (SurfaceFlinger 一帧的一生)
draw 进入 RenderThread -> HWUI 渲染到 GraphicBuffer
 -> BufferQueueProducer.queueBuffer
SurfaceFlinger:
 -> onMessageInvalidate (acquireBuffer, latch)
 -> 计算合成 (HWC Overlay vs GPU)
 -> onMessageRefresh -> present
 -> BufferQueueConsumer.releaseBuffer (fence)
```

> 这五段 = 本篇的目录。下面逐段 code walk，每条结论都给 AOSP 真实路径 + 方法名。

---

## 二、[1] Activity 启动调度（Java/Framework 层）

### 2.1 调用链与源码落地

| 步骤 | 关键类 / 方法 | AOSP 路径（Android 14） |
|------|--------------|------------------------|
| App 发起 | `Activity.startActivity` → `ContextImpl.startActivity` | `frameworks/base/core/java/android/app/ContextImpl.java` |
| 跨进程到 ATMS | `ActivityTaskManager.getService().startActivityAsUser` | `frameworks/base/core/java/android/app/ActivityTaskManager.java` |
| 入口分发 | `ActivityTaskManagerService.startActivityAsUser` → `startActivity` | `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java` |
| 解析 Intent | `ActivityStarter.execute` → `startActivityUnchecked` | `frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java` |
| 栈顶恢复 | `RootWindowContainer.resumeFocusedTasksTopActivities` | `frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java` |
| 真正启动 | `ActivityStack.resumeTopActivityInnerLocked`（进程不在则走 `startSpecificActivity`） | `frameworks/base/services/core/java/com/android/server/wm/ActivityStack.java` |
| 跨进程拉进程 | `ActivityStackSupervisor.realStartActivityLocked`（AMS 侧 `startProcessLocked`） | `frameworks/base/services/core/java/com/android/server/wm/ActivityStackSupervisor.java` |
| 进程管理 | `ProcessList.startProcessLocked` → `startProcess` | `frameworks/base/services/core/java/com/android/server/am/ProcessList.java` |
| 通知 Zygote | `ZygoteProcess.start` → `zygoteSendArgsAndGetResult` → `openZygoteSocketIfNeeded` | `frameworks/base/core/java/android/os/ZygoteProcess.java` |

### 2.2 关键原理点（面试必答）

- **为什么先到 ATMS 再到 AMS？** Android 10 起 `ActivityManager` 被拆成 `ActivityTaskManagerService`（管 Activity/Task/Stack/WindowContainer）与 `ActivityManagerService`（管进程/内存/广播/Service）。`startActivity` 的调度权在 ATMS，进程生死才归 AMS。这是高频追问「AMS 和 ATMS 区别」的源码级答案。
- **进程不存在时的分支**：`resumeTopActivityInnerLocked` → `startSpecificActivity` → 若 `app == null` 或 `app.thread == null` → `mService.startProcessAsync`（经 `ProcessList`），通过 **Zygote Socket** 让 Zygote fork 新进程。**注意：AMS 与 Zygote 之间不是 Binder，是 socket**——这是经典题「Zygote 为什么用 Socket 而非 Binder」的答案（fork 前不能有多线程/Binder 线程池污染，socket 单连接简单可靠）。
- **启动模式 / TaskAffinity / Intent flags** 全部在 `ActivityStarter.startActivityUnchecked` 里决定 `ActivityStarter.Request` 的 `mLaunchFlags`，影响最终落在哪个 Task、是否 `onNewIntent`。

### 2.3 易错点
- 错：认为 startActivity 直接 new Activity。正：必须由 system_server 调度，app 侧只是发请求，最终生命周期回调经 ClientTransaction 回 app 主线程。
- 错：把 ATMS 当 AMS 的一部分。正：两者是不同 Service，注册到 ServiceManager 的独立 binder 实体。

### 2.4 高频追问链
1. 冷启动 / 温启动 / 热启动的进程差异？（冷：fork 新进程 + 建 Application + 建首页；温：进程在、页面重建；热：已有实例复用栈）
2. 为什么有时 `onNewIntent` 被调用？（已有实例且启动模式允许复用，不重建只交付新 Intent）
3. 首页首帧慢就一定是 Application 的锅吗？（否，还可能卡在 ContentProvider 前置、资源加载、首页布局、同步 IO、三方 SDK 初始化——见 §3 的 ContentProvider 坑）

---

## 三、[2] Zygote fork 与 Application 启动

### 3.1 Zygote 端 fork

| 步骤 | 关键方法 | AOSP 路径 |
|------|----------|----------|
| 读 socket 参数 | `ZygoteConnection.processCommand` | `frameworks/base/core/java/com/android/internal/os/ZygoteConnection.java` |
| 真正 fork | `Zygote.forkAndSpecialize` | `frameworks/base/core/java/com/android/internal/os/Zygote.java` |
| 子进程入口 | `RuntimeInit.applicationInit` → `findStaticMain` 反射 `ActivityThread.main` | `frameworks/base/core/java/com/android/internal/os/RuntimeInit.java` |

- `Zygote.forkAndSpecialize` 内部调用 native `nativeForkAndSpecialize`，在 fork 之后子进程里做 `zygotePreload`、`setuid`、`mount`、SELinux `seapp_contexts` 等专属化；父进程返回 pid，子进程返回 0。
- 子进程不走 socket 循环，直接经 `RuntimeInit` 反射进入 `ActivityThread.main`——这就是「Zygote 孵化出的进程共用预加载的 Class/drawable/资源，省去重复加载」的源码落点。

### 3.2 Application 生命周期 code walk

`ActivityThread.main`：
```java
public static void main(String[] args) {
    Looper.prepareMainLooper();              // 主线程 Looper
    ActivityThread thread = new ActivityThread();
    thread.attach(false, startSeq);          // 通过 Binder 向 AMS 注册
    Looper.loop();                           // 主线程消息循环（见 §6 后 A17 lock-free 变化）
}
```

`attach` → `AMS.attachApplication(thread)`（Binder 跨进程）→ 回到 app 侧 `bindApplication` → `handleBindApplication`：

| 步骤 | 方法 | 说明 |
|------|------|------|
| 建 LoadedApk | `data.info = getPackageInfo` | 解析 APK 的 ApplicationInfo |
| 建 Application | `LoadedApk.makeApplication` → `newApplication` | `frameworks/base/core/java/android/app/LoadedApk.java` |
| **ContentProvider 前置坑** | `installContentProviders` | **在 `callApplicationOnCreate` 之前执行** |
| 调 onCreate | `Instrumentation.callApplicationOnCreate(app)` | 用户 `Application.onCreate` 此时才跑 |

### 3.3 关键原理点
- **ContentProvider 前置坑（冷启动必考）**：`handleBindApplication` 顺序是 `installContentProviders` → 然后才 `callApplicationOnCreate`。如果某个 ContentProvider 在 `onCreate` 里做了重 IO / 网络 / 同步 Binder 调用，它会**阻塞在 Application.onCreate 之前**，直接拉长冷启动。这是「首页首帧慢却查不到 Application 卡顿」的常见根因。
- **makeApplication 的 Context**：`ContextImpl.createAppContext` 给 Application 注入 base context，`attachBaseContext` 先于 `onCreate`——所以 `onCreate` 里才能用 `getApplicationContext()` / 资源。
- **多进程 Application 多次创建**：每个进程（含 `:remote`、Provider 独立进程）都会走一遍 `handleBindApplication`，`Application` 会被创建多次。

### 3.4 易错点
- 错：Application.onCreate 是进程最早执行点。正：ContentProvider.installProvider 更早，且同进程所有 ContentProvider 的 `onCreate` 都先于 Application.onCreate。
- 错：Application 全局单例。正：每个进程一个 Application 实例。

### 3.5 高频追问链
1. 冷启动慢怎么量化？（Peroftto trace 看 `bindApplication` 段耗时，拆 ContentProvider / attachBaseContext / onCreate 三段；基线 & 云 Profile 加速见第 19 篇）
2. 多进程下 Application 初始化重复怎么办？（用 `processName` 判断主进程，非主进程跳过重型初始化）

---

## 四、[3] Activity 生命周期回调 + Window 创建

### 4.1 Launch 与 Resume 的源码落点

`ActivityStackSupervisor.realStartActivityLocked` 向 app 主线程 post 一个 `ClientTransaction`，包含 `LaunchActivityItem` + `ResumeActivityItem`，由 `TransactionExecutor.executeCallbacks` 派发：

| 步骤 | 方法 | AOSP 路径 |
|------|------|----------|
| 建 Activity | `ActivityThread.performLaunchActivity` | `frameworks/base/core/java/android/app/ActivityThread.java` |
| 反射实例 | `ClassLoader.loadClass(className).newInstance()` | 同上 |
| attach | `activity.attach(...)` 建 PhoneWindow + WindowManager | `frameworks/base/core/java/android/app/Activity.java` |
| onCreate | `Instrumentation.callActivityOnCreate` → `activity.performCreate` | 同上 |
| onStart/onResume | `ActivityThread.handleResumeActivity` → `performResume` | `ActivityThread.java` |
| 加 Window | `WindowManagerGlobal.addView` → `ViewRootImpl` 构造 + `setView` | `frameworks/base/core/java/android/view/WindowManagerGlobal.java` |

### 4.2 DecorView 与 Window 体系
- `Activity.attach` 创建 `PhoneWindow`，`setContentView` 把布局 inflate 进 `DecorView`（含 `R.id.content` 的 `FrameLayout`）。
- `handleResumeActivity` 里 `wm.addView(decor, l)` 把 DecorView 交给 `WindowManagerGlobal`，后者为每个 Window 建一个 `ViewRootImpl` 并调用 `setView`——**ViewRootImpl 是「Window 与 View 树」的管理者，也是后续 measure/layout/draw 的发起者**。
- `ViewRootImpl.setView` 内部会 `requestLayout()`（触发首帧遍历）+ 通过 `Session.addToDisplay`（Binder 到 WMS）为这个 Window 申请 `Surface`（与 SurfaceFlinger 的 BufferQueue 对接，见 §6）。

### 4.3 易错点
- 错：setContentView 就把 View 画上屏了。正：只是构建 DecorView 视图树；真正测量/布局/绘制在 `handleResumeActivity` 后的 `performTraversals` 首帧。
- 错：一个 Activity 只有一个 ViewRootImpl。正：每个 Window（含 Dialog、PopupWindow、子 Window）各一个 ViewRootImpl。

### 4.4 高频追问链
1. Dialog / PopupWindow 为什么用 `new Window` 而不是加到 Activity 的 DecorView？（独立 Window 有独立 Surface，能浮在 Activity 之上、可跨进程显示）
2. onResume 之后 View 才可见吗？（onResume 时首帧可能还没绘制完；精确「可见且可交互」要看 `onWindowFocusChanged` / `ViewTreeObserver.OnDrawListener`）

---

## 五、[4] View 绘制三阶段（measure / layout / draw）

### 5.1 入口：performTraversals

`ViewRootImpl.performTraversals()` 是三阶段总入口，内部依次：
```java
performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
performLayout(lp, mWidth, mHeight);
performDraw();
```
路径：`frameworks/base/core/java/android/view/ViewRootImpl.java`

### 5.2 Measure 阶段

| 方法 | 说明 | AOSP 路径 |
|------|------|----------|
| `performMeasure` | 用 `MeasureSpec` 调 `mView.measure` | `ViewRootImpl.java` |
| `View.measure` | `final` 方法，先 `onMeasure` 再 `setMeasuredDimension` | `frameworks/base/core/java/android/view/View.java` |
| `onMeasure` | 子类覆写，必须调 `setMeasuredDimension` | `View.java` / 各 ViewGroup |
| `setMeasuredDimension` | 锁定宽高，后续 `getMeasuredWidth` 才有值 | `View.java` |

**MeasureSpec 三种模式（必背）**：
- `EXACTLY`：父容器给出精确尺寸（match_parent / 固定 dp）→ 子 View 必须遵守。
- `AT_MOST`：父容器给上限（wrap_content）→ 子 View 不能超过但可自行决定。
- `UNSPECIFIED`：父容器不约束（如 ScrollView 对子 View 测一次「想要多大」）。

> 经典坑：自定义 View 不处理 `AT_MOST` 时，`wrap_content` 会被当成 `EXACTLY` 父尺寸，导致尺寸铺满。必须：`if (widthSpecMode == AT_MOST) width = Math.min(desired, specSize)`。

**requestLayout vs invalidate**：
- `requestLayout()`：标记 `PFLAG_FORCE_LAYOUT`，触发**重新 measure + layout + draw**（尺寸可能变）。
- `invalidate()`：只标脏区，触发 **draw**（尺寸不变）。
- 二者都经 `ViewRootImpl` 调度，最终由 `Choreographer` 的 `TRAVERSAL` 回调合并到下一帧（与渲染帧对齐，第 19 篇详述）。

### 5.3 Layout 阶段

| 方法 | 说明 |
|------|------|
| `performLayout` | `host.layout(0,0,mView.getMeasuredWidth(), mView.getMeasuredHeight())` |
| `View.layout` | 算 `l/t/r/b`，调 `onLayout`，并触发 `onSizeChanged`（尺寸变化时） |
| `ViewGroup.onLayout` | 遍历子 View，`child.layout(...)` 递归 |

> 注意：`layout` 用 `getMeasuredWidth/Height`（measure 阶段结果）定位置；`draw` 才用最终 `getWidth/Height`（= right-left）。二者通常相等，但 measure 后 layout 前被强制改尺寸时会不等——这是「为什么 getMeasuredWidth 和 getWidth 有时不一样」的源码答案。

### 5.4 Draw 阶段

`performDraw` → `draw(fullRedrawNeeded)` → `mView.draw(canvas)`：
1. 画背景 `drawBackground`
2. 画内容 `onDraw(canvas)`
3. 画子 View `dispatchDraw`（ViewGroup 递归）
4. 画装饰 `onDrawForeground`（foreground、scrollbars）

渲染走 HWUI：`View.draw` 最终进入 `ThreadedRenderer`（硬件加速），`canvas` 实则是 `DisplayListCanvas`，把绘制命令录进 DisplayList。

### 5.5 易错点
- 错：onDraw 里 new 对象无所谓。正：onDraw 每帧调用，内部创建对象直接带来 GC 抖动 + 掉帧。**绘制对象应在构造/onSizeChanged 复用**。
- 错：measure 一次就够。正：requestLayout 会沿父链向上传递 `FORCE_LAYOUT`，可能触发整棵树的重新 measure（过度绘制/过度测量的根因）。
- 错：getWidth == getMeasuredWidth 永远成立。正：仅 layout 未改尺寸时成立（见 §5.3）。

### 5.6 高频追问链
1. 自定义 View 的 wrap_content 怎么正确处理？（覆写 onMeasure，对 AT_MOST 取 min(desired, specSize)）
2. 为什么 requestLayout 会从当前 View 一直请求到根？（forceLayout 标记沿父链冒泡，根 ViewRootImpl 统一重排）
3. 过度绘制怎么查？（开发者选项 Show overdraw / Layout Inspector / Perfetto 看 draw 时长）

---

## 六、[5] 首帧渲染上屏：SurfaceFlinger 一帧的一生

### 6.1 App 侧：从 draw 到 queueBuffer

硬件加速下，主线程 `draw` 把命令录进 DisplayList，真正的光栅化在 **RenderThread**（独立线程，避免阻塞主线程）：

| 步骤 | 类 / 方法 | AOSP 路径 |
|------|-----------|----------|
| 触发渲染 | `ThreadedRenderer.draw` → `nSyncAndDrawFrame` | `frameworks/base/core/java/android/view/ThreadedRenderer.java` |
| RenderThread 任务 | `DrawFrameTask::run` → `CanvasContext::draw` | `frameworks/base/libs/hwui/renderthread/DrawFrameTask.cpp` |
| 光栅化 | `SkiaOpenGLPipeline` / `SkiaVulkanPipeline` 渲到 GraphicBuffer | `frameworks/base/libs/hwui/pipeline/` |
| 入队 | `BufferQueueProducer::queueBuffer` | `frameworks/native/libs/gui/BufferQueueProducer.cpp` |

- App 的每个 Window 对应一个 `Surface`，底层是一对 `BufferQueue`（生产者 app / 消费者 SurfaceFlinger）。`queueBuffer` 把一个已渲染的 `GraphicBuffer` 交给 BufferQueue，并触发 SF 的无效化。

### 6.2 SurfaceFlinger 侧：合成一帧

`SurfaceQueueLayer` / `BufferQueueLayer` 持有 app 提交的 buffer。SF 主线程的调度：

```
VSync 到来 -> MessageQueue 派发
 -> onMessageInvalidate   (收集/更新 layer 状态, latch buffer)
 -> onMessageRefresh      (合成 + present)
```

| 阶段 | 方法 | 说明 | AOSP 路径 |
|------|------|------|----------|
| 无效化 | `SurfaceFlinger::onMessageInvalidate` | `handleTransaction` 更新 layer 几何；`BufferQueueLayer::latchBuffer` 取最新已提交 buffer | `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp` |
| 合成决策 | `SurfaceFlinger::composeSurfaces` / `HWComposer` | 决定哪些 layer 走 **HWC Overlay**（硬件合成，省 GPU）vs **GPU 合成**（Client 类型） | `frameworks/native/services/surfaceflinger/DisplayHardware/` |
| 刷新上屏 | `SurfaceFlinger::onMessageRefresh` → `present` | 把合成结果 present 到 Display；`BufferQueueConsumer::releaseBuffer` 用 **fence** 标记消费完成 | `SurfaceFlinger.cpp` / `frameworks/native/libs/gui/BufferQueueConsumer.cpp` |
| FrameTimeline | `frametimeline::FrameTimeline` | 记录 app 端 actuals vs SF 端 expectations，算 `JankType` | `frameworks/native/services/surfaceflinger/FrameTimeline/` |

### 6.3 关键原理点
- **HWC Overlay vs GPU 合成**：优先让硬件 composer（Display 控制器）直接叠加多个 layer（零拷贝、低功耗）；当 layer 太多 / 有变换 / 模糊 / 圆角等 GPU 专属效果时，退回 GPU 合成到一个离屏 buffer 再叠加。**毛玻璃（Material 3 Expressive）因为要采样下层并模糊，通常强制 GPU 合成**——这就是 A17 QPR2「Disable background blur」能降功耗的原因（关掉 blur 即可回到 HWC Overlay）。
- **fence（同步栅栏）**：GraphicBuffer 跨进程传递用 fd + `sync_fence`，生产者写完、消费者读完都靠 fence 标记，避免拷贝。与 Binder 传 fd（§7）配合实现「零拷贝图形内存」。
- **FrameTimeline 定责（面试高频）**：一帧卡顿到底是 app 慢还是 SF 慢？`JankType` 区分 `App Deadline Missed`（app 端 actuals 超 deadline）vs `SurfaceFlinger Deadline Missed`（SF 合成超期）vs `Unknown`——Perfetto 里直接看，见第 19 篇排查实战。

### 6.4 易错点
- 错：app draw 完界面就上屏了。正：draw 只是录命令 + RenderThread 渲到 buffer，真正上屏要等 SF 在 VSync 驱动的 `onMessageRefresh` 合成 present，中间还有 BufferQueue 跨进程传递。
- 错：掉帧一定是 app 主线程卡。正：可能是 RenderThread、可能是 SF 合成（layer 过多 / 模糊 / HWC 不支持），FrameTimeline 才能定责。

### 6.5 高频追问链
1. 一帧从 app 到屏幕经历了哪些线程/进程？（app 主线程录 DisplayList → RenderThread 光栅化 → BufferQueue 跨进程 → SF 合成 → Display 上屏）
2. 为什么毛玻璃费电？（强制 GPU 合成，无法 HWC Overlay，多一次离屏渲染）
3. 如何用 Perfetto 看掉帧根因？（FrameTimeline track + JankType，第 19 篇四步法）

---

## 七、[6] Binder 一次事务全追踪（贯穿全链路的 IPC 骨干）

本篇 §1/§2/§3 里每一次「跨进程」都是一次 Binder 事务。把一次 `ATMS.startActivityAsUser` 追到内核：

### 7.1 Native 层：BpBinder → IPCThreadState

| 步骤 | 方法 | AOSP 路径 |
|------|------|----------|
| 代理发起 | `BpBinder::transact` | `frameworks/native/libs/binder/BpBinder.cpp` |
| 线程状态机 | `IPCThreadState::transact` → `writeTransactionData` | `frameworks/native/libs/binder/IPCThreadState.cpp` |
| 写命令 | 把 `binder_transaction_data` 写进 mOut，调 `talkWithDriver` | `IPCThreadState.cpp` |
| 系统调用 | `ioctl(mProcess->mDriverFD, BINDER_WRITE_READ, &bwr)` | `IPCThreadState.cpp` |

### 7.2 内核驱动：binder.c 一次拷贝

进入 `drivers/android/binder.c`：
```
binder_ioctl(BINDER_WRITE_READ)
 -> binder_thread_write            // 解析 BC_TRANSACTION
 -> binder_transaction             // 核心：找目标进程/线程, 一次拷贝
      -> binder_alloc_copy_user    // copy_from_user 把数据从发送方用户态拷到内核 binder 缓冲区
      -> 目标进程 binder_alloc 通过 mmap 区已映射, 直接构造目标端 binder_buffer
 -> binder_thread_read             // 唤醒目标线程, 发 BR_TRANSACTION
```

**一次拷贝的真相**：发送方用户态 → 内核 binder 缓冲区是**一次** `copy_from_user`；目标进程因为提前 `mmap` 了一块内核 binder 缓冲区到自己的用户态（binder_mmap），所以**目标端不需要再 copy_to_user**，直接读 mmap 区即可。对比 socket/pipe 的「发送方拷贝到内核 + 内核拷贝到接收方」两次拷贝，Binder 省一次。

- 数据搬运：`drivers/android/binder_alloc.c` 的 `binder_alloc_copy_user` 把 parcel 数据落到 `binder_buffer`（位于 mmap 区）。
- 线程分配：`binder_transaction` 里选目标 `binder_thread`；没有空闲线程则新建（受 `max_threads` 限制，默认 **15**，满了就排队，可能拖主线程 ANR——第 19 篇已讲）。

### 7.3 目标端：BBinder → onTransact

```
目标 IPCThreadState::getAndExecuteCommand
 -> executeCommand(BR_TRANSACTION)
 -> BBinder::transact -> onTransact   // 落到具体服务 (如 ATMS / AMS 的 Bn 端)
 -> 业务逻辑执行 (ActivityTaskManagerService.startActivityAsUser)
 -> 返回时发 BC_REPLY, 同样走 binder_transaction 回传
```

### 7.4 关键原理点
- **Transaction 码 = 方法路由**：AIDL 生成的 stub 在 `onTransact` 里用 `code` 分发到具体方法（如 `START_ACTIVITY_TRANSACTION`）。Parcel 读写**顺序必须严格一致**，否则数据错位——经典坑。
- **oneway 异步**：`oneway` 接口不阻塞等 reply，写进驱动即返回；但注意**oneway 事务在驱动侧也排队**，Binder 线程池满时 oneway 也会延迟（第 19 篇 Binder 实战坑）。
- **跨 VM / 跨世界不可信**：本篇链路是普通世界 Binder；若跨 pVM（AVF）或 Trusty，getCallingUid 不可信（第 12/13 篇已详述）。

### 7.5 易错点
- 错：Binder 是「零拷贝」。正：是「一次拷贝」（对比 socket 两次），不是零拷贝；真正的零拷贝在图形内存（GraphicBuffer + fd + fence，§6.3）。
- 错：oneway 一定不卡。正：驱动侧仍排队，线程池满照样延迟。
- 错：同进程 AIDL 也要走内核。正：同进程直接 `queryLocalInterface` 拿本地 Bn 对象，**不跨进程、不走驱动**（性能优化点）。

### 7.6 高频追问链
1. Binder 一次拷贝 vs 共享内存 zero-copy 各适合什么？（Binder 适合中小控制消息，一次拷贝够用；大块数据（图形/文件）走 fd + 共享内存，避免拷贝）
2. Binder 线程池默认多少？满了会怎样？（默认 15，满了排队，同步调用卡主线程 → ANR）
3. 为什么说 getCallingUid 跨 VM 不可信？（pVM/Trusty 里调用方身份由 host 伪造，必须独立证明，第 12/13 篇）

---

## 八、易错红榜 TOP（本篇专属，20 条速记）

1. startActivity 不是直接 new Activity，必经 ATMS 调度，生命周期回调经 ClientTransaction 回主线程。
2. AMS 管进程/内存，ATMS 管 Activity/Task，是不同 Service。
3. Zygote 与 AMS 用 **socket** 而非 Binder（fork 前不能有多线程/Binder 污染）。
4. fork 后子进程经 RuntimeInit 反射进 ActivityThread.main。
5. ContentProvider.installProvider **早于** Application.onCreate。
6. makeApplication 每个进程一次，多进程 Application 被建多次。
7. setContentView 只建 DecorView 视图树，真正上屏在 performTraversals 首帧。
8. 每个 Window（Dialog/Popup/子 Window）各一个 ViewRootImpl。
9. measure 用 MeasureSpec 三模式；自定义 View 不处理 AT_MOST 则 wrap_content 失效。
10. requestLayout = measure+layout+draw；invalidate = 仅 draw。
11. getMeasuredWidth（measure 结果）vs getWidth（layout 结果）通常相等，强制改尺寸时不等。
12. onDraw 内 new 对象 → GC 抖动 + 掉帧。
13. app draw 完 ≠ 上屏；要等 SF 在 VSync 的 onMessageRefresh 合成 present。
14. 毛玻璃强制 GPU 合成，无法 HWC Overlay，故费电。
15. fence 实现图形内存跨进程零拷贝 + Binder 传 fd。
16. FrameTimeline 的 JankType 区分 app 慢 vs SF 慢。
17. Binder 是「一次拷贝」不是「零拷贝」。
18. oneway 驱动侧仍排队，线程池满照样延迟。
19. 同进程 AIDL 走本地对象，不跨进程不走驱动。
20. Parcel 读写顺序必须严格一致，否则 Transaction 数据错位。

---

## 九、跨篇追问链（把本篇接回前 19 篇）

- **链路 x 性能**：启动慢量化 → 第 19 篇冷启动全链路（bindApplication 三段、ContentProvider 坑、PinnerService、基线/云 Profile）。
- **链路 x 卡顿**：掉帧定责 → 第 19 篇卡顿掉帧（Vsync→Choreographer→三阶段→RenderThread→SF→HWC，FrameTimeline JankType 四步法）。
- **链路 x ANR**：Binder 阻塞 → 第 19 篇 ANR 回溯（四类超时、event log am_anr、Binder 阻塞链）+ 本篇 §7 线程池满排队。
- **链路 x Binder 深水区**：本篇 §7 是 native/内核层，补强见第 3 篇（Binder 驱动 TTLE/线程池）、第 4 篇（binderfs/tracepoint 调试）、第 12 篇（跨 VM binderRPC）。
- **链路 x 智能层**：UI 自动化遍历 → 第 13 篇（语义树、ANI 跨进程、Compose SemanticsNode 对 Agent 更友好）。
- **链路 x A17 新雷区**：本篇 §2 的 Looper 主循环，在 A17 已变 **Lock-free MessageQueue**（第 8 篇）；A17 分代 GC 影响 bindApplication 阶段的对象分配（第 9/10 篇）。

---

## 十、延伸阅读（AOSP 真实路径索引）

- Activity 启动：`frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java`、`RootWindowContainer.java`、`ActivityStack.java`、`ActivityStackSupervisor.java`
- 进程创建：`frameworks/base/services/core/java/com/android/server/am/ProcessList.java`、`frameworks/base/core/java/android/os/ZygoteProcess.java`、`com/android/internal/os/Zygote.java`、`ZygoteConnection.java`
- App 侧：`frameworks/base/core/java/android/app/ActivityThread.java`、`LoadedApk.java`、`Activity.java`
- View 体系：`frameworks/base/core/java/android/view/ViewRootImpl.java`、`View.java`、`WindowManagerGlobal.java`
- 渲染/合成：`frameworks/base/libs/hwui/`、`frameworks/base/core/java/android/view/ThreadedRenderer.java`、`frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp`、`BufferQueueLayer.cpp`、`frameworks/native/libs/gui/BufferQueueProducer.cpp`、`BufferQueueConsumer.cpp`
- Binder：`frameworks/native/libs/binder/BpBinder.cpp`、`IPCThreadState.cpp`、`drivers/android/binder.c`、`drivers/android/binder_alloc.c`

---

## 十一、与上 19 篇的交叉索引（系列总图）

| 篇 | 主题 | 与本篇关系 |
|----|------|-----------|
| 1 主篇 | Handler/Looper、Binder、冷启动、Zygote、AMS/ATMS、WMS/SF、View、ANR、LMKD、Compose、HAL、GKI、MTK | 本篇是其中「启动→绘制→合成→Binder」的源码级展开 |
| 2 拓展 | Input、PMS、ART、SystemUI、折叠屏 WM、SELinux、OTA、JNI、Binder 安全、Perfetto | Input 全链路是 §4/§5 的上游 |
| 3 深挖 | ART 对象头/CMC GC、Binder 驱动调试、Rust Binder、Input 多指、VSync、Camera/Audio HAL、GKI KMI、Perfetto SQL | Binder 驱动调试见 §7 |
| 4 图形多媒体通信 | HWUI/SF/Choreographer/BufferQueue/HWC/Media/Codec2/Thermal/Power/Telephony/WiFi/BT | §5 合成深水区的上游 |
| 5 系统基建 | 16KB 页、ClassLoader、权限、Keystore2、AVB、Vold、logd、可观测性、RRO、Doze、A15/16 变更 | bindApplication 阶段涉及 16KB/ClassLoader |
| 6 端侧AI/A17 | NNAPI、LiteRT、CarService、Vulkan/ANGLE、ART 产物、virtual A/B | Vulkan 是 §5 GPU 合成后端之一 |
| 7 A17新雷区 | Lock-free MessageQueue、ART 分代 GC、hiddenapi、ProfilingManager、后台音频、NFC/SE、Media3、端侧 LLM | §2 Looper 已变 Lock-free |
| 8 渲染合成/A17安全 | SF RenderEngine、Codec2 plugin、Memory Limiter、安全 DCL、Keystore 限额、CarService | §5 RenderEngine 后端 |
| 9 兼容性框架 | platform_compat、letterbox、BAL、Bubbles、Handoff、Pointer Capture、SMS OTP、ECH、SQLite 严格模式、hiddenapi 流水线 | BAL/Handoff 均经 ATMS/WMS（§1/§3） |
| 10 TEE | Trusty、Keystore2、KeyMint、Gatekeeper、Attestation、Widevine、FBE、ION→DMA-BUF | §5 图形内存走 DMA-BUF（ION 已弃） |
| 11 pKVM/AVF | pKVM、AVF、vsock、AISeal、Memory Limiter 三杀、Connectivity eBPF、Ravenwood | §7 跨 VM getCallingUid 不可信 |
| 12 智能系统 | AppFunctions、AppSearch、Compose 编译器/运行时、APK 签名、ApplicationExitInfo、系统 UI、语义树 | §4 View 树是语义树底座 |
| 13 端侧AI/AAOS | LiteRT NPU、端侧 LLM 量化、CarService 电源、StrongBox、Protected Confirmation、AVF 隔离编译 | CarService 多 Display 复用 §3 Window 体系 |
| 14 收官补遗 | 端侧 LLM 量化脚本、AAOS 电源状态机 | — |
| 15 末轮补全 | Codec2 vendor、KMP/skiko、Robolectric vs Ravenwood、A18 前瞻、14 篇索引 | A18 桌面融合依赖 §3/§5 |
| 16 速查卡 | 15 篇速答表、易错红榜 TOP25、追问链 | 本篇红榜是其补充 |
| 17 连击考 | 考官连击模拟 | 本篇链路可作连击题「从点击到上屏」 |
| 18 全链路排查 | 冷启动/卡顿/ANR/内存/发热/Binder 实战 | 本篇是排查篇的「机理底座」 |
| **20 本篇** | **源码级 code walk：startActivity→首帧** | 串 1/3/4/5/7/18，给源码佐证 |

> 系列至此 20 篇 / 约 143 专题。本篇不新增「知识点角度」，而是把已有角度用一条端到端链路**焊死**——这是面试「能把八股串成系统」的分水岭训练。
