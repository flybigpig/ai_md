# Android Framework 岗经典面试题深度剖析(Input / Binder / WMS / SF / 启动)

> 面向学员的面经讲解稿。覆盖 21 道真题,按模块深讲经典题 + 速答其余题。
> 代码路径以 **Android 14 (API 34, UpsideDownCake)** 为准。
> 讲解思路:**先链路 → 再原理 → 给讨论/陷阱**(面试官常连环追问)。

---

## 一、Input 事件调用链路与 iq / oq / wq(题1)

### 全链路(从硬件到 App)

```
EventHub(epoll 读 /dev/input)            frameworks/native/services/inputflinger/EventHub.cpp
   ↓ 原始事件 (RawEvent)
InputReader::loopOnce()                   reader/InputReader.cpp
   ↓ 加工成 KeyEvent/MotionEvent,经 QueuedInputListener
InputDispatcher::notifyKey/notifyMotion()  dispatcher/InputDispatcher.cpp
   ↓ 入 inBoundQueue (mInBoundQueue)
InputDispatcher::dispatchOnceInnerLocked()
   ↓ 找焦点窗口 / 目标 connection,入 outBoundQueue (connection->mOutBoundQueue)
Connection::startDispatchCycleLocked()
   ↓ 通过 InputChannel(socketpair) sendMessage
App 端 NativeInputEventReceiver::handleEvent()   frameworks/base/core/jni/android_view_InputEventReceiver.cpp
   ↓ InputEventReceiver.onInputEvent() → ViewRootImpl 分发
App 处理完 → finishInputEvent() → InputChannel 回传
   ↓ InputDispatcher 收到 finish,从 waitQueue 移除 (connection->mWaitQueue)
```

### 三个队列的精确含义

| 队列 | 位置 | 含义 | 入队时机 | 出队时机 |
|---|---|---|---|---|
| **inBoundQueue (iq)** | `InputDispatcher::mInBoundQueue` | InputReader 加工后、尚未派发的事件 | `notifyKey/Motion` | 被 `dispatchOnceInnerLocked` 取出派发 |
| **outBoundQueue (oq)** | 每个 `Connection::mOutBoundQueue` | 已确定目标窗口、待写入 InputChannel 的事件 | `dispatchEventLockedInner` / `enqueueDispatchEntryLocked` | `startDispatchCycleLocked` 真正发送 |
| **waitQueue (wq)** | 每个 `Connection::mWaitQueue` | 已发给 App、等 App `finishInputEvent` 确认的事件 | 发送成功后 `startDispatchCycleLocked` 入队 | App finish 回传后移除 |

> 代码核心:`frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp`
> - `mInBoundQueue`、`Connection::mOutBoundQueue`、`Connection::mWaitQueue` 三个字段即 iq/oq/wq。

### 讨论点(面试官追问)
- **为什么需要三个队列,单队列不行?** iq 解耦"读"与"派发"(InputReader 生产、Dispatcher 消费);oq 是"已决策目标但还没真正写 socket"的缓冲,支持一个事件广播给多个 target(如系统手势 + 应用);wq 是"已发出待确认"的监视器,**ANR 超时检测就靠 wq**(`handleTargetsNotReadyLocked` / `waitQueue 非空且超 5s`)。
- **oq 和 wq 的关系是 1:1 还是 1:N?** 每个 `Connection`(一个窗口一条 binder 连接)各有一对 oq/wq。一个 iq 事件可能 fan-out 到多个 Connection,于是拆成多份 oq/wq。
- **事件丢了从哪查?** `dumpsys input` 直接打印 iq/oq/wq 长度与队首事件,定位是"没读到 / 没派发 / App 不确认"。

---

## 二、Perfetto 看 Input 延时与 App 处理延时(题2)

### 抓 trace 的类别
```
perfetto -o /data/local/tmp/t.pftrace -a <pkg> \
  -c - <<'CFG'
buffers: { size_kb: 65536 }
data_sources: { config { name: "linux.ftrace" ftrace_config {
  atrace_categories: "input" atrace_categories: "view"
  atrace_categories: "am" atrace_categories: "wm"
  atrace_apps: "<pkg>"
}}}
CFG
```
关键 atrace slice(在 `android.input` / `input` 类别):
- `InputDispatcher` 的 `dispatchKey` / `dispatchMotion` —— 系统派发时刻
- `deliverInputEvent` —— 事件送到 App 边界
- `ViewRootImpl` / `InputStage` 的 `doProcessInputEvents` / `ViewPostImeInputStage`
- `Choreographer` 的 `doFrame`(事件处理常被 vsync 调度)

### 怎么读延时
- **系统侧派发延时**:`iq 入队时刻 → oq 发送时刻`(InputDispatcher 内部排队)。
- **App 处理延时(最常被问)**:`oq 发送(InputChannel.sendMessage) → App finishInputEvent 回传` 这段时间,对应 **wq 在队列里停留的时长**。Perfetto 里看 `deliverInputEvent` 起点到 `finishInputEvent` 终点;或用 `dumpsys input` 看 wq 当前滞留。
- **端到端**:硬件中断(`input` 类别的 `EventHub` slice)→ 上面全链路之和。

### 讨论点
- **input 事件和掉帧的关系**:input 事件处理本身不走 vsync,但 App 的 `onTouchEvent` 若触发 `requestLayout`/重绘,会等到下一帧 `Choreographer.doFrame`,于是"点了没反应"往往是 **wq 里事件处理慢 + 后续渲染掉帧** 叠加。Perfetto 里 input slice 和 `Choreographer`/`RenderThread` slice 对照看。
- **为什么有时 finish 很快但画面还卡?** finish 只代表 Java 侧 `onInputEvent` 返回,不代表渲染完成。真正"看得见"要等 SF 合成一帧。

---

## 三、no focus ANR(题3、题4)

### 普通 ANR vs no focus ANR
- 普通 input ANR:`waitQueue` 中某事件 > 5s 未 finish(`INPUT_DISPATCH_TIMEOUT = 5s`),且焦点窗口存在。
- **no focus ANR**:`InputDispatcher::findFocusedWindowTargetsLocked` 找不到焦点窗口(`mFocusedWindow == null`),但存在 `mFocusedApplication`(认为应用马上会加窗口),于是把事件暂存并**等待窗口获得焦点**;若等待超时(同样约 5s,错误串 `input dispatching timed out (Waiting because no window with focus ...)`)→ 报 ANR,类型标记为 **no focused window**。

### 产生原理(核心代码路径)
```
InputDispatcher::findFocusedWindowTargetsLocked()
  → 若 mFocusedWindow == null 且 mFocusedApplication != null
     返回 INPUT_EVENT_INJECTION_NO_FOCUS_WINDOW / 进入 waitForWindowFocus 分支
  → handleTargetsNotReadyLocked() 启动超时计时
  → 超时未获得焦点 → 触发 ANR(标记 no focus)
```
**典型触发场景**:
1. 冷启动早期:点击屏幕,但 App 的 `WindowManagerGlobal.addView` 还没完成 / Activity `onResume` 未执行,焦点窗口为空。
2. 转屏 / 窗口 Token 失效:`WindowToken` 已移除但新 Token 未建立,焦点短暂真空。
3. 多窗口 focus 竞争:`ActivityStack` 焦点切换与 `WMS` 派发竞争,焦点窗口短暂不可达。
4. `addWindow` 被异常拦截(如 `TYPE_APPLICATION` 但 token 不匹配 `BadTokenException`),窗口永远加不上。

### 一个印象深刻的案例 & 分析流程(题4 套路)
**案例**:某 App 冷启动时用户连点,偶发 no focus ANR。
**分析流程**:
1. `bugreport` 搜 `anr` + `no focused window`,确认 ANR 类型与发生时刻。
2. `dumpsys input` 看当时 `mFocusedWindow`(空)、`mFocusedApplication`(目标 uid)、`waitQueue` 长度 —— 印证"等焦点"。
3. `dumpsys window` 看目标 App 的 `WindowState`:是否 `hasSurface`、是否 `visible`、是否 `focus` 状态。发现 `WindowState` 已 `added` 但 `mViewVisibility` 还没 `VISIBLE`,焦点未授予。
4. 看 `am` / `dumpsys activity` 的 App 进程启动与各 Activity 生命周期:卡在 `onResume` 之前的某个阶段(如 `ActivityThread.handleResumeActivity` 之前主线程被 Binder 调用阻塞)。
5. 拉 `perfetto` / `traces.txt` 看主线程:发现 `onCreate` 里同步读了 `SharedPreferences` 全量 + 一个跨进程 `Binder` 获取配置,耗时 > 5s,导致 `addView`/`onResume` 延后,焦点窗口一直空。
6. **结论**:主线程启动重活阻塞 → 窗口未及时 focus → no focus ANR。
7. **修复**:`onCreate` 异步化 I/O 与配置拉取,`windowBackground` 主题占位,提前 `addView`。

### 讨论点
- **no focus 和 "focused window has not finished processing" 的区别**:前者是窗口根本没焦点(等窗口出现),后者是窗口有了但它在 wq 里上一个事件还没 finish(等处理完)。
- **为什么 5s?** `INPUT_DISPATCH_TIMEOUT = 5s`(KEY/MOTION 均为 5s);`INJECTION` 注入类也有 5s。部分系统/广播/Service 超时不同(广播 10s、前台 Service 20s)。

---

## 四、Binder 一次内存拷贝在哪里(题5)

### 核心结论
Binder 跨进程数据传递**只有一次拷贝**:发送方用户空间 → 内核 `binder_buffer`。
接收方**不拷贝**,直接读它 `mmap` 的那块内核共享内存。

### 流程(以一次同步 transact 为例)
```
发送进程(用户态)
  IPCThreadState::transact()                     frameworks/native/libs/binder/IPCThreadState.cpp
   → writeTransactionData()  把数据打包进 binder_write_read
   → ioctl(BINDER_WRITE_READ) 陷入内核
内核 drivers/android/binder.c
  binder_ioctl_write_read()
   → binder_thread_write()  解析 BC_TRANSACTION
   → binder_transaction()
       ★ copy_from_user() 把发送方数据拷到 t->buffer(内核 binder_buffer)
         —— 这就是唯一的一次拷贝
       ★ t->buffer 位于"接收方进程 mmap 的内核缓冲区"上
接收进程(用户态,其 mmap 已映射该内核区)
  binder_thread_read()  读到 BR_TRANSACTION
   → executeCommand() → BBinder::transact() → 子类 onTransact()
   ★ 直接读 mmap 区数据,无第二次拷贝
   → 返回 BR_REPLY(同样一次拷贝回传)
```
**关键点**:接收方进程在 `ProcessState::self()` 时已 `mmap` 一块内核缓冲区(`binder_mmap`),`binder_transaction` 把数据放到这块共享区,接收方用户态指针直接解引用。对比 **socket/pipe** 需要 `copy_from_user` + `copy_to_user` 两次拷贝。

### 讨论点
- **为什么大事务(默认 1MB,框架限制)仍然要拷贝而非共享?** 共享内存要求收发双方约定地址、生命周期管理复杂,且大数据常是一次性的;内核 buffer 由 binder 驱动统一管理、事务结束即回收,简单安全。所以"一次拷贝"是工程权衡,不是零拷贝。
- **异步事务(async)的 buffer 上限更小**(默认约为同步的一半),超限返回 `FAILED_TRANSACTION`,client 可能自动重试加剧堆积。

---

## 五、同步 / 异步(oneway)Binder 调用差别与 oneway 缺点(题6)

| 维度 | 同步(FLAG 0) | oneway(FLAG_ONEWAY) |
|---|---|---|
| 调用方线程 | 阻塞等 `BR_REPLY` | 立即返回,fire-and-forget |
| 返回值 / 异常 | 有 | 无(调用方不知成败) |
| 顺序保证 | 同一 binder 的同步调用按发出顺序到达 | 同一 binder 的 oneway **不保证顺序** |
| 背压 | server 在忙时 client 阻塞,天然限流 | 无背压,可瞬间淹没 server |
| 线程占用 | 占 client 一个 binder 线程直到返回 | 仅占瞬时,不占用等待 |

### oneway 的缺点(重点)
1. **无返回、无异常**:调用方无法知道 server 是否成功处理,错误被吞。
2. **无序**:oneway 可能由 server 线程池里不同线程处理,到达顺序 ≠ 发出顺序;需要顺序的场景(如"先设属性再启用")用 oneway 会出乱子。
3. **无背压**:server 被大量 oneway 淹没时无反压机制(同步调用至少让 client 排队等待);可能导致 server 线程池打满、后续同步调用也被拖死。
4. **异步事务 buffer 上限更小**:超限 `FAILED_TRANSACTION`,部分框架层会重试,反而放大压力。
5. **调试困难**:无 reply 堆栈,调用链断点难追。

### 讨论点
- **oneway ≠ 异步线程**:server 端仍由 binder 线程池处理,只是 client 不等。不要把 oneway 当成"server 开子线程异步"。
- **何时用 oneway**:高频、可丢失、无需结果的通知类(如 `INotificationManager.enroll`、状态广播、埋点),且调用方在应用进程(非 system_server,避免放大 system_server 负担)。

---

## 六、WMS 层级树 vs SF 层级树(题8/9/10/11)

### WMS 层级树(逻辑窗口树)
```
RootWindowContainer                  wm/RootWindowContainer.java
 └ DisplayContent                    wm/DisplayContent.java
    ├ TaskDisplayArea (含 Feature 分层)
    │   └ Task → ActivityRecord → WindowToken → WindowState
    └ WallpaperDisplayArea / 状态栏 / 导航栏 (各自 Feature 层级)
```
- 命令:`adb shell dumpsys window`(看 `mRoot` 整棵树、每个 `WindowState` 的 `mLayer` / `mToken` / `mAttrs.type` / `animating`);`dumpsys window visible` 看可见窗口;`dumpsys window windows` 列出全部 WindowState。
- **作用**:WMS 树是"窗口状态与 z 序决策树",负责算出每个窗口应该在哪一层、能不能接收输入、焦点给谁。

### SF 层级树(图形合成树)
```
SurfaceFlinger
 └ Layer 树(由 SurfaceControl 的 setParent / setZ 决定)
    └ 每个 Layer 对应一个可合成图层(buffer / color / effect)
```
- 命令:`adb shell dumpsys SurfaceFlinger`(看各 `Layer` 的 `z`、`position`、`size`、`alpha`、`activeBuffer`、`visible`、`occluded`)。

### 两者差别
| | WMS 树 | SF 树 |
|---|---|---|
| 本质 | 逻辑窗口状态 | 图形图层 |
| 节点 | WindowState / WindowToken | Layer(由 SurfaceControl 引用) |
| 关注 | 焦点、可见性、z 决策、输入命中 | 像素合成、裁剪、透明度、混合 |
| 更新频率 | 窗口增删/属性变化时 | 每帧 Transaction 提交 |

### 同步机制(题9 核心)
WMS 算好 z/position/alpha 后,通过 `SurfaceControl.Transaction` 下发:
```
WindowStateAnimator::applySurfaceChanges()        wm/WindowStateAnimator.java
 → SurfaceControl.setLayer / setPosition / setAlpha / setRelativeLayer
 → native SurfaceComposerClient::Transaction::apply()   frameworks/native/libs/gui/SurfaceComposerClient.cpp
 → SurfaceFlinger::setTransactionState() → 应用 Layer 属性
```
- 同步节拍:WMS 在 `performSurfacePlacement`(`RootWindowContainer.performSurfacePlacement`)中,每帧(或窗口变化时)提交 Transaction;SF 在 `present` 阶段按 Layer 的 z 合成。
- **保证一致**:WMS 的 `WindowState.mLayer` 与 SF 的 `Layer.z` 通过同一个 `SurfaceControl` 句柄绑定,所以"WMS 树怎么排,z 就怎么合成"。

### Feature 分层(题10)
Android 12+ 用 `DisplayArea` + `Feature` 把窗口类型归类到固定层级:
- `DisplayArea.Tokens` 按 `WindowManager.LayoutParams.type` 归组,如 `FEATURE_WINDOW_TAKES_OVER_SCREEN`、`FEATURE_HIDE_NON_SYSTEM_OVERLAYS`、状态栏/导航栏/壁纸各自的 DisplayArea。
- **作用**:把"窗口类型 → z 序"从硬编码改为配置化,解耦类型与层级,方便分屏、多窗、系统 UI 独立层级、One-handed 等特性灵活插入。

### layer / WindowState / bbq / SurfaceControl 各自含义(题11)
- **WindowState**:WMS 中一个窗口的逻辑表示(对应一个 `ViewRootImpl` + 一个 `LayoutParams`),持有 `mSurfaceControl`、`mAttrs`、`mLayer`。
- **SurfaceControl**(Java 层 `android.view.SurfaceControl`):通往 SF `Layer` 的**句柄**,用来设置 z/alpha/position/crop/parent,本身不含像素。
- **Layer**:SF 侧真正参与合成的单位,由 SurfaceControl 的 native 对象驱动;一个 WindowState 对应一个 SurfaceControl,最终对应一个 Layer。
- **BBQ(BufferQueue)**:`Surface` 底层的图形缓冲队列。`Surface`(生产者,app 端 `View`/GL 绘制)→ `BufferQueue` → SF 的 `BufferLayerConsumer`(消费者)。即 WindowState 的 SurfaceControl 关联 Layer,Layer 通过 BBQ 拿到 app 绘制的 buffer 去合成。
- **一句话**:`WindowState`(WMS 逻辑)→ `SurfaceControl`(设置层级属性的句柄)→ `Layer`(SF 合成单元)→ `BBQ`(app 往 Layer 塞像素的队列)。

---

## 七、壁纸显示原理与闪黑(题12/13/14)

### 壁纸窗口原理
- 壁纸是 `TYPE_WALLPAPER` 的特殊 `WindowState`,由 `WallpaperManagerService`(`system_server`)通过 `WallpaperConnection` 管理,渲染端是 `ImageWallpaper` / 动态壁纸 `WallpaperService.Engine`。
- 流程:`WallpaperService.Engine` 创建 `Surface` → 通过 `WindowManager.addWindow(TYPE_WALLPAPER)` → WMS 树中位于**壁纸 DisplayArea**(在应用 TaskDisplayArea **之下** → z 低于应用窗口)→ `WindowStateAnimator` 经 `SurfaceControl` 把 Layer 放到低层 → SF 合成时应用窗口盖在壁纸之上。

### 与普通 WindowState / ViewRootImpl 的差别
- 普通应用窗口由 app 进程自己的 `ViewRootImpl` 创建并驱动;**壁纸窗口由 system_server 的 WallpaperManagerService 创建**,不是 app 的 ViewRootImpl。
- 但底层都走同一套 WMS `relayoutWindow` + `SurfaceControl` 机制,无本质差别,只是 owner 进程和层级不同。

### 闪黑原理(题12)
闪黑 = 壁纸 surface **销毁重建间隙无内容**,SF 合成出黑:
1. 切换壁纸 / 转屏 / 配置变更 → 旧 `Surface` `surfaceDestroyed`,但新 `Surface` 还没 `onSurfaceRedrawNeeded` 完成首帧绘制。
2. SF 在中间帧看到该 Layer 无 `activeBuffer` 或 `visible=false` → 合成黑色。
3. 动态壁纸 Engine 生命周期(`onCreate`/`onSurfaceCreated`/`onSurfaceRedrawNeeded`)若首帧慢,黑屏窗口更长。

### 解决方法(题13)+ 双壁纸 token(题14)
- **双壁纸 token 交替**:维护两个 `WallpaperWindowToken`,旧的**不立即 destroy**,新的先 `draw` 完成,确认有内容后再切焦点/移除旧的,做到"新盖旧、无黑缝"。
- **crossfade / 保证顺序**:WMS 提交 Transaction 时,先让新壁纸 Layer `setRelativeLayer` 在旧之上(题14 的"保证一个在上面"做法),且旧的不早于新首帧完成就 destroy。
- **改进点(题13 追问"没考虑的问题")**:
  - 双 token 内存翻倍(两块壁纸 surface 同时存在),低内存机需回收策略。
  - 动态壁纸 Engine 重建代价高(重新初始化动画/视频解码),应复用 Engine。
  - 多用户 / 多屏场景:每屏每用户一套壁纸 token,双 token 逻辑要按 display 隔离。
  - 切换动画连续性:单纯"等首帧"仍可能有 1 帧跳变,需 `BLASTSyncEngine` 同步两屏 Transaction。

---

## 八、ShellTransition 动画执行流程(题17)

> Android 12+ 引入,WMS 与 Shell(Launcher/WmShell)协同管理过渡动画。

```
startActivity(APP)
 → ActivityStarter 决定需要过渡
 → TransitionController.createTransition()         wm/TransitionController.java
 → 收集参与的 WindowContainer(旧 Activity、新 Activity、壁纸等)
 → BLASTSyncEngine 注册同步(保证多方一起变,不出现半截画面)
 → 先提交一个 Transaction 给 SF:把相关 Layer 放到起始位/隐藏
 → 请求 Shell 播放动画:WmShell Transitions.Player.play()   (libs/WindowManager/Shell/.../Transitions.java)
     · 通过 RemoteAnimationRunner / Transaction 每帧更新 SurfaceControl
 → onTransactionReady / 动画结束 → finishTransition()
 → TransitionController 通知 WMS 过渡完成
 → BLASTSyncEngine 解除同步,提交最终 Transaction → SF 合成最终画面
```
### 讨论点
- **为什么引入 ShellTransition?** 旧架构动画逻辑散落在 WMS/App/WindowManagerPolicy,跨应用、分屏、多窗难统一;新架构把"动画播放"交给 Shell,用 `BLASTSyncEngine` 做帧同步,保证多窗口一起变、无撕裂。
- **常见坑**:Transition 没 `finish`(动画回调丢失 / 异常)→ 窗口卡在过渡态、`BLASTSyncEngine` 不解除 → 界面假死,需 `dumpsys window` 看 `mTransitionController` 状态。

---

## 九、冷启动跨进程 Binder 次数统计(题20/21)

### Java 层有多少次?(题20)
从 `zygote fork`(不走 binder,是本地 socket)到首帧:
粗略链路:`ActivityManagerService.startProcessLocked`(fork)→ `ActivityThread.main` → `attach`(binder 到 AMS `attachApplication`)→ `bindApplication`(经 `IApplicationThread`,binder)→ `scheduleTransaction`(`ClientTransaction`,binder)→ `ActivityStackSupervisor.realStartActivityLocked` → `scheduleLaunchActivity`(binder)→ `onCreate/onStart/onResume`(均在 app 进程内,无 binder)→ `onResume` 后 `addWindow`/`relayoutWindow`(binder 到 WMS)→ `SurfaceFlinger` 提交(binder)。
**大约**:`attachApplication`(1) + `bindApplication`(1) + `scheduleTransaction/Launch`(1~2) + `WMS.addWindow/relayout`(1~2) + `AMS` 各种生命周期回调(若干)≈ **10~20 次量级**,具体随版本与厂商定制波动。

### 怎么调试验证(题20 追问)
1. `perfetto` 选 `binder` 类别 + `am`/`wm`/`activity` 标签,过滤 `Binder` 的 `binder_transaction` / `binder_transaction_received`,数 transaction 次数。
2. `atrace` 里看 `binder` 的 `IPCThreadState::transact` slice,按调用栈归类。
3. 代码中在 `IPCThreadState::transact` 加计数器(仅调试 build),或在 `ActivityThread`/`ActivityManager` 关键方法插桩统计。
4. `dumpsys activity` / `dumpsys window` 结合时间线推断。

### native 端也要统计(题21)
- **方法一**:`perfetto` / `atrace` 开 `binder` 数据源(ftrace `binder`/`binder_transaction`),覆盖 native 层所有 `IPCThreadState::transact`(包括 Java 经 JNI 下去的也计入),直接看 native 次数。
- **方法二**:`simpleperf` 采样 `binder_ioctl` / `IPCThreadState::transact` / `executeCommand`,统计调用频次与耗时。
- **方法三**:在 `frameworks/native/libs/binder/IPCThreadState.cpp` 的 `transact()` / `executeCommand()` 加全局原子计数(debug build),区分 `BC_TRANSACTION` / `BR_TRANSACTION`。
- **讨论**:Java 层 binder 最终都落到 native `IPCThreadState`,所以 native 统计是"全集",Java 只是其中一部分;统计时要区分"Java 发起"与"系统内部自发"(如 WMS 内部 binder 调用)。

---

## 十、冷启动优化 & 性能问题导致 ANR(题15/16)

### 冷启动优化点(题15)
- `Application.attachBaseContext` / `onCreate` 精简:移除同步 I/O、重初始化。
- **ContentProvider 合并/延迟**:CP 在 `Application.onCreate` 之前(`ActivityThread` 的 `handleMakeApplication` → `installContentProviders`)初始化,是早期 stage 大头;合并多余 CP、用 `androidx.startup` 统管并延迟非必要初始化。
- 主题占位:`windowBackground` 设品牌图,首帧前不白屏。
- 线程池预热、类预加载(基线 profile `/apex/com.android.art`、dex2oat、AppStartup)。
- 视图层级扁平化、`AsyncLayoutInflater`、I/O 全异步、`SharedPreferences` 用 `apply` 且不在主线程读全量。
- 印象最深的一点:**ContentProvider 的隐式启动**——很多三方库偷偷塞 CP,在冷启最早期同步拉起,合并且按需延迟后首帧提前数百 ms。

### 性能问题导致 ANR 例子(题16)
- `BroadcastReceiver.onReceive` 主线程耗时 > 10s → 广播 ANR。
- `Service` `onCreate`/`onStartCommand` > 20s(前台)→ Service ANR。
- `ContentProvider.onCreate` > 10s → CP ANR。
- 主线程被**同步 Binder 调用**阻塞(等远端超时)、锁竞争(`synchronized` 大块)、频繁 GC(`Allocation` 风暴)、`SharedPreferences` 全量读阻塞主线程(8.0 后 `apply` 异步但 `get` 仍可能阻塞)、布局过深 `measure/layout` 超时。
- 案例:首帧前主线程同步读大 `SharedPreferences` + 跨进程拉配置 > 5s → 触发 input/no-focus ANR(呼应第三、十节)。

---

## 十一、其余题速答

### 题7:系统应用保活简单方案
- `foreground Service`(带通知)、绑定 `system_server` 服务、账号同步(`AccountManager` 周期性)、1px 像素 Activity、双进程守护。
- **系统 app 特权**:`android:persistent="true"`(仅系统签名)、`requestIgnoreBatteryOptimizations`、加入 `allow-in-power-save`/`force-app-standby` 白名单。
- 讨论:Android 8+ 后台限制(Doze / App Standby / 后台位置限制),非系统 app 保活基本被堵,系统设计上不鼓励。

### 题18:多屏 InputMonitor 性能
- `InputManager.registerInputMonitor` 监听会**每个事件都回调**,多屏 + 多 monitor 开销显著(主线程/输入线程压力)。
- 优化:只为需要的 `Display` 注册 monitor;用 `InputChannel` 定向而非全局 monitor;`BatchedInputEvent` 批处理;回调里只做轻量判断,重逻辑异步;避免每次 `injectInputEvent` 回注(有 IPC 开销)。
- 更优方案:`InputChannel` + 异步消费者 + 事件合并,或只在"需要拦截"的窗口(如边缘手势)注册局部 monitor,而非全局。

### 题19:显示画面异常分析(透明度 / bounds / 黑块)
- **透明度不对**:查 `SurfaceControl` 的 `alpha`、`ColorDrawable`/`WindowManager.LayoutParams.dimAmount`、主题 `windowIsTranslucent`、`Window.setDimAmount`;Perfetto/sf dump 看 Layer `alpha`。
- **bounds / 区域不对**:查 `WindowState.mFrame` / `mDisplayFrame`、Task `bounds`、`SurfaceControl` 的 `position` + `crop`(crop 决定显示区);`dumpsys window` 看 `mFrame`,`dumpsys SurfaceFlinger` 看 Layer `displayFrame`/`crop`。
- **黑块/部分黑**:查 Layer 是否 `visible`、`activeBuffer` 是否为空(没 draw)、是否被上层不透明 Layer 遮挡(看 z)、`surfaceDestroyed` 间隙、SF dump 看该 Layer 的 `occluded`/`color`。
- 工具链:`dumpsys SurfaceFlinger`(Layer 树/属性)、`dumpsys window`(WindowState/Frame)、`perfetto`(`sf`/`trace` 看合成帧)、`adb shell screencap` 对照。

---

## 总结:面试官到底在考什么
1. **链路记忆**:能否从硬件一路讲到 App(`EventHub→InputReader→Dispatcher→InputChannel→ViewRootImpl` / `Binder 驱动→IPCThreadState→AMS`)。
2. **关键数据结构**:iq/oq/wq、WindowState/Layer/BBQ、三个 binder 队列。
3. **超时与异常机制**:5s input 超时、no focus 的产生、Binder 上限。
4. **排查方法论**:bugreport / dumpsys / perfetto 怎么用,而不是背结论。
5. **权衡与讨论**:为什么这么设计(一次拷贝、三个队列、Feature 分层、ShellTransition)。
```
