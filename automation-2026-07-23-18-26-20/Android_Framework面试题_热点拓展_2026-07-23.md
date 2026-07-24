# Android Framework 热点面试题·查缺补漏深度解析（2026-07-23 拓展篇）

> 基准版本：**Android 14 (UpsideDownCake, API 34)**，AOSP 分支 android-14.0.0_rXX，内核 GKI android14-6.1。
> 本篇为「主线路」面试题（同日已产出 `Android_Framework面试题_2026-07-23.md`）的**互补专题**，覆盖面试常漏、但高频追问的 10 个硬核方向：Input 系统、PMS 安装、ART/JIT/AOT、SystemUI、折叠屏 WM、SELinux、OTA/AB、JNI/hook、Binder 安全、Perfetto。
> 建议配合 cs.android.com 按路径对照阅读。

---

## 目录

1. Input 系统全链路：从触摸屏到 onTouchEvent
2. PackageManagerService：应用安装与扫描全流程
3. ART 类加载 / JIT / AOT 与热启动优化
4. SystemUI 与锁屏架构（system 进程）
5. 多窗口 / 折叠屏 WindowManager（WindowOrganizer / Configuration）
6. SELinux 在 Android：机制与定制 ROM 调试
7. OTA / A/B 无缝升级与动态分区
8. JNI 机制与 Android Runtime hook 基础
9. Binder 安全：鉴权、SELinux context 与权限传递
10. Perfetto / Systrace 实战：卡顿与 Binder 阻塞分析
11. 知识网络串联 & 延伸阅读

---

## 1. Input 系统全链路：从触摸屏到 onTouchEvent

**面试题：手指触摸屏幕到 App 收到 onTouchEvent，中间经历了哪些进程、哪些线程？**

### 答案解析

整条链路横跨 **内核驱动 → system_server(InputFlinger) → 应用进程**，全部基于事件 + 共享内存 + socketpair：

```
[内核] 触摸屏驱动 → /dev/input/eventX (evdev)
 │  read() 读取 input_event（type/code/value 三元组）
 ▼
EventHub (native, epoll + inotify)          // frameworks/native/services/inputflinger/reader/EventHub.cpp
 │  设备热插拔监听 /dev/input；扫描设备能力（keyboard? touch?）
 ▼
InputReader (单线程)                         // reader/InputReader.cpp
 │  processEventsLocked → 按设备类型选 InputMapper（TouchInputMapper/SwitchInputMapper...）
 │  → 归一化为 NotifyMotionArgs，入 mQueuedListener
 ▼
InputDispatcher (单线程)                     // dispatcher/InputDispatcher.cpp
 │  dispatchOnce()：从 mInboundQueue 取事件 → 命中 Window（findTouchedWindow）
 │  → 入对应 Connection 的 outboundQueue → 经 InputChannel 发送
 │  InputChannel = socketpair + 共享内存(ashmem) 装 MotionEvent 样本
 │  ★ waitQueue 记录"已发未确认"，超时即 Input ANR 的源头（见主篇第 11 题）
 ▼
[应用] ViewRootImpl.mInputChannel            // frameworks/base/core/java/android/view/ViewRootImpl.java
 │  NativeInputEventReceiver.onInputEvent() → enqueueInputEvent()
 │  → ViewRootImpl.deliverInputEvent() 进入 InputStage 责任链
 │      NativePreImeInputStage → ViewPreIme → Ime → EarlyPostIme
 │      → ViewPostImeInputStage（关键：DecorView.dispatchTouchEvent）
 ▼
DecorView.dispatchTouchEvent() → Activity.dispatchTouchEvent()
 → PhoneWindow.superDispatchTouchEvent() → DecorView ViewGroup 分发 → View.onTouchEvent()
```

**关键设计点：**
- InputReader 与 InputDispatcher 是**两个线程**，中间用 `QueuedInputListener` 解耦；Dispatcher 里还跑 `InputDispatcherPolicyInterface`（即 Java 侧 `InputManagerService`）做"是否拦截（如系统手势、下拉状态栏）"裁决。
- 事件在进程间**不走 Binder**，而是 socketpair（`InputChannel::openInputChannelPair`）——避免 Input 事件被 Binder 线程池/优先级继承机制影响实时性。
- 多指/历史样本用**共享内存**（`android_view_InputChannel.cpp` 的 `android_view_MotionEvent` 指向 ashmem），样本多时也不阻塞。

### 易错点

- ❌ "触摸事件经过 Binder 传给 App"：错，是 socketpair + 共享内存。Binder 只用于 WMS 把 InputChannel 句柄、焦点窗口信息传给 InputDispatcher（`android_view_InputChannel` 跨进程传的是 fd）。
- InputDispatcher 的 `mWindowHandles` 来自 WMS 通过 `InputManagerService.setInputWindows()` 同步的窗口列表——所以"点了没反应"先查 WMS 焦点窗口对不对（`dumpsys input`）。
- `onInterceptTouchEvent` 只在 ViewGroup 有意义；重写它返回 true 会**吞掉整个事件序列（含后续 MOVE/UP）**。

### 高频追问

1. **事件序列（DOWN/MOVE/UP）如何保证落到同一 View？** InputDispatcher 首次命中后把 target 锁定到该 connection，后续事件直接发同一窗口；App 侧由 `ViewGroup.addTouchTarget` 在 MOVE/UP 时复用 target。
2. **Input ANR 的 waitQueue 阈值？** 默认 5s（`DEFAULT_INPUT_DISPATCHING_TIMEOUT`），由 `InputDispatcher::handleTargetsNotReadyLocked` 计时。
3. **系统手势（返回/Home）如何抢事件？** `InputManagerService` / `systemui` 注册 `WindowManagerPolicy` 拦截，在 `InputStage` 的 `SyntheticInputStage` 阶段消费。

### 延伸阅读

```
frameworks/native/services/inputflinger/
  reader/EventHub.cpp  reader/InputReader.cpp
  dispatcher/InputDispatcher.cpp  dispatcher/InputState.cpp
frameworks/base/core/java/android/view/InputEventReceiver.java
frameworks/base/core/java/android/view/ViewRootImpl.java  (InputStage / WindowInputEventReceiver)
frameworks/base/services/core/java/com/android/server/input/InputManagerService.java
```

---

## 2. PackageManagerService：应用安装与扫描全流程

**面试题：在 Android 14 上点击一个 APK（或 adb install），从文件到桌面图标可点，PMS 做了什么？**

### 答案解析

PMS（`services/core/java/com/android/server/pm/PackageManagerService.java`）是 system_server 里最复杂的服务之一。一次安装的骨架：

```
PackageInstallerSession.commit()                       // pm/PackageInstallerSession.java
 └─ PMS.installStage() → 多阶段 Handler 消息（INIT_COPY → MCS_BOUND）
     └─ InstallParams.startCopy() → handleReturnCode()
         ├─ 拷贝 apk 到 /data/app/<pkg>/  (installd: 经 binder 调 installd)
         ├─ 校验签名 (SignatureSchemeV2/V3/V4，含旋转密钥 / 溯源 V4)
         └─ PMS.scanPackageTracedLI()
              ├─ ParsingPackageUtils.parsePackage()      // pm/parsing/ — 解析 AndroidManifest
              │    → 生成 ParsingPackage（<activity>/<service>/<provider>/权限/组件)
              ├─ PackageParser2 生成 AndroidPackage（含 PackageSetting 占位）
              ├─ 收集 providers / 注册组件（mActivities/mServices/mProviders 的 Intent 解析表)
              ├─ 重新解析已安装依赖者（共享 UID / 权限继承）
              └─ PMS.commitPackageSettings()             // 写 /data/system/packages.xml + packages.list
         └─ dex 优化：Installd 调 dex2oat
              └─ Installer.dexopt() → installd (native) → dex2oat 产出 .odex/.vdex/.art
 └─ 广播 ACTION_PACKAGE_ADDED → Launcher 收到刷新图标
```

**三个必须讲清的子概念：**
- **解析与扫描分离**：`ParsingPackage`（临时解析结果）→ `AndroidPackage`（轻量只读，供运行时查询）。Android 12+ 把老 `PackageParser` 拆成 `parsing/` 包，明显更内聚。
- **installd 是真正干活的 native 守护进程**：PMS（Java）通过 `Installer`（binder 接口 `installd`）让 installd 做 chown、dexopt、mkuserdata 等需要 root 权限的活——**PMS 自己没 root 能力**。
- **四大组件注册**：`<activity>` 进入 `mActivities.mActivities`（ComponentResolver），隐式 Intent 解析靠 `IntentResolver` 的 `mFilters`；这也是 `startActivity` 时 `resolveActivity` 的数据来源（见主篇第 6 题）。

### 易错点

- ❌ "安装 = 解压 + 注册"：忽略了 **dex2oat 编译**与**签名校验**（V3 支持多证书轮转、V4 支持 APK 签名溯源配合 `fs-verity`）。
- 首次安装触发 dexopt 是**冷启动慢**的元凶之一；OTA 后大量 app 需重新 dexopt（bg-dexopt job）。
- `packages.xml` 是 PMS 的持久化真相源（权限授予、签名、UID 映射）；损坏会导致"应用消失但还在"。

### 高频追问

1. **secondary dex（插件化 / MultiDex）怎么加载？** `BaseDexClassLoader` + `DexPathList`（`makeDexElements` 遍历 dex/jar），Android 5+ 主 dex 之外走 `PathClassLoader` 新增 `dexElements`；插件化热修复本质是改 `dexElements` 顺序或插桩。
2. **sharedUserId 现状？** 已废弃（Android 10 target 起禁止新建，14 上强烈不推荐），改用 `android:process` + 同签名/权限共享；跨 app 数据共享走 `ContentProvider` 或 `android:sharedUserMaxSdkVersion`。
3. **快点安装怎么优化？** `cmd package install` 的 `--instant`（ephemeral）、AB 设备的 `dexopt` 延迟到 idle、基线 profile（见第 3 题）。

### 延伸阅读

```
frameworks/base/services/core/java/com/android/server/pm/{PackageManagerService,PackageInstallerSession,Installer}.java
frameworks/base/services/core/java/com/android/server/pm/parsing/
frameworks/base/core/java/android/content/pm/PackageParser.java (compat shim)
frameworks/native/cmds/installd/
```

---

## 3. ART 类加载 / JIT / AOT 与热启动优化

**面试题：ART 的类是怎么加载的？JIT 和 AOT 是什么关系？基线 Profile 为什么能加速启动？**

### 答案解析

**类加载（双亲委派 + Android 专属 ClassLoader）：**

```
BootClassLoader (preloaded framework classes，Zygote preload，见主篇第 7 题)
 └─ PathClassLoader (应用默认，parent = BootClassLoader)
      └─ DexPathList.findClass() → 遍历 dexElements 调 defineClass
           └─ native → art/runtime/class_linker.cc: ClassLinker::FindClass / DefineClass
```

- Android 没有标准 `URLClassLoader`；应用代码统一走 `PathClassLoader`（在 `libcore/dalvik/src/main/java/dalvik/system/PathClassLoader.java`），其 `dexElements` 由 `DexPathList` 构造时解析。
- **双亲委派**保证 framework 类不会被 app 替换；插件化/热修复的突破点正是"插到 dexElements 前面"或替换 `parent`。

**编译三态（Android 14）：**

| 模式 | 触发 | 产物 | 说明 |
|---|---|---|---|
| 解释执行 | 首次执行 | — | 走解释器，记录热点 |
| JIT | 运行时热点方法 | 内存中机器码 + profile 采样 | `art/runtime/jit/`；`JitCodeCache` |
| AOT | 安装/充电空闲 (`dexopt`) | `/data/app/.../oat/.../*.odex` + `.vdex` | `dex2oat` 编译，启动直接走机器码 |

**基线 Profile（Baseline Profile）机制**——这是近年启动优化的核心考点：
```
应用构建期：生成 baseline-prof.txt（含启动关键类/方法/布局的"热点清单"）
安装时：installd 用该 profile 调 dex2oat --profile-file=... 预编译
 → 首启即 AOT 路径，跳过解释/JIT 预热 → 冷启动显著下降（Google 实测 30%~40%）
运行时 JIT 还会持续采样写入 /data/misc/profile/cur/<pkg>/primary.prof
 → 下次充电空闲 bg-dexopt 合并进 .odex（ProfileSaver 触发）
```
源码落在 `art/runtime/jit/profile_saver.cc`、`frameworks/base/services/core/java/com/android/server/pm/dex/`（`DexManager`/`ArtManager`）。

### 易错点

- ❌ "AOT 一定比 JIT 快"：AOT 编译耗时、占存储，且无法做运行时专精优化（如基于真实输入的去虚拟化）；JIT 在"运行足够久"后可能更优。现代 ART 是 **AOT + JIT + 解释** 混合。
- `MultiDex.install` 在 Android 5+ 基本不需要（默认 multidex 支持），残留是历史包袱。
- 类加载 **不是线程安全自由**：并发 defineClass 会触发 `ClassCircularityError`/锁竞争，插件化框架要管好加载时序。

### 高频追问

1. **ART 对象内存模型 & GC？** （见主篇第 12 题）Android 14 默认 **CMC（Concurrent Mark-Compact, userfaultfd）**；对象头（klass + lock word + 标记位）在 `mirror::Object`。
2. **怎么看某方法有没有被编译？** `adb shell cmd package compile` / `oatdump --oat-file=...` 看 `.odex`，或 `art::jit::Jit::GetCodeCache` 运行时 dump。
3. **JIT zygote？** Android 12+ `boot-image-profile` 让 zygote 预编译 framework 热点，所有 app 共享 boot odex。

### 延伸阅读

```
art/runtime/class_linker.cc  art/runtime/jit/  art/runtime/gc/
libcore/dalvik/src/main/java/dalvik/system/{PathClassLoader,BaseDexClassLoader,DexPathList}.java
frameworks/base/services/core/java/com/android/server/pm/dex/DexManager.java
external/dex2oat (实际上是 art 工具，源码在 art/dex2oat/)
```

---

## 4. SystemUI 与锁屏架构（system 进程）

**面试题：SystemUI 是独立进程吗？状态栏、通知、锁屏分别是怎么组织和与 system_server 交互的？**

### 答案解析

SystemUI 跑在**自己的进程 `com.android.systemui`**（不是 system_server），由 Zygote fork 后 `SystemUIService` 拉起。整体是 **Dagger 依赖注入 + 组件自注册** 架构：

```
SystemUIService.onCreate()
 └─ SystemUIInitializer / SysUiModule (Dagger) 注入 Dependency
 └─ 遍历 startable 组件：StatusBar、NavigationBar、NotificationShade、Keyguard
StatusBar (frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/)
 ├─ 持有 StatusBarWindowView（自己的 Window，TYPE_STATUS_BAR）
 ├─ 通过 StatusBarManager (binder) 与 system_server 的 StatusBarManagerService 通信
 └─ NotificationPanelViewController 控制下拉/锁屏
KeyguardViewMediator                       // 锁屏核心，监听 PMS/AMS 状态
 ├─ 与 PowerManagerService（息屏/亮屏）、ActivityManager（用户切换）联动
 └─ KeyguardBouncer / KeyguardSecurityContainer 渲染密码/指纹 UI
```

**与 system_server 的关键接口：**
- `StatusBarManagerService`（`services/core/java/com/android/server/statusbar/StatusBarManagerService.java`）：system_server 通过它让 SystemUI 显示/隐藏状态栏、展开通知、setSystemUiVisibility。
- `WindowManagerService` 给 SystemUI 的窗口分配层级（状态栏 `TYPE_STATUS_BAR`、导航栏 `TYPE_NAVIGATION_BAR`、锁屏 `TYPE_STATUS_BAR_PANEL`）。
- 通知来自 `NotificationManagerService`（`services/core/java/com/android/server/notification/`），SystemUI 的 `NotificationListener` 跨进程拉取。

**Android 12+ 的大变化：Shell（WMShell）**：窗口动画、最近任务（Recents）、分屏/自由窗口的逻辑从 SystemUI 抽到 `frameworks/base/libs/WindowManager/Shell/`，经 `WindowOrganizer`（`IWindowOrganizerController`）与 WMS 协作——这也是主篇第 9 题提到的"窗口动画不再由老 WindowAnimator 独揽"。

### 易错点

- SystemUI **崩溃会让手机"假死"（状态栏/导航栏没了）**，但 system_server 还在，AMS 会重启 SystemUI（`ActivityManagerService` 的 `SystemUI` 重启逻辑）。
- 锁屏不是独立 Activity：是 SystemUI 内 `KeyguardViewMediator` 管理的 View，叠加在 Launcher 之上；所以"锁屏界面"和"桌面"同属一个显示层级栈。
- 系统定制的"下拉状态栏快捷开关"本质是 `QSPanel` 里一堆 `QSTile` 实现，新增开关要注册到 SystemUI 的 `TileService`。

### 高频追问

1. **沉浸式/状态栏透明怎么实现？** `View.setSystemUiVisibility` / 新 API `WindowInsetsController`；WMS 按 `layoutParams` 的 `systemUiVisibility` 位给 SystemUI 发 `setSystemUiVisibility`。
2. **指纹/人脸解锁怎么打通？** BiometricService（`system_server`）→ SystemUI 的 `AuthController` 显示生物识别弹窗，结果回传 BiometricPrompt。
3. **多用户/访客模式？** `UserController` + SystemUI 的 `UserSwitcher`，切换用户时 SystemUI 重建。

### 延伸阅读

```
frameworks/base/packages/SystemUI/src/com/android/systemui/
  statusbar/phone/StatusBar.java  keyguard/KeyguardViewMediator.java
  notification/NotificationListener.java  qs/  wm/ActivityHostWm
frameworks/base/services/core/java/com/android/server/statusbar/StatusBarManagerService.java
frameworks/base/services/core/java/com/android/server/notification/NotificationManagerService.java
frameworks/base/libs/WindowManager/Shell/
```

---

## 5. 多窗口 / 折叠屏 WindowManager（WindowOrganizer / Configuration）

**面试题：折叠屏展开/合上为什么会重建 Activity？分屏、自由窗口、Activity Embedding 底层分别靠什么？**

### 答案解析

Android 10 起桌面/窗口管理统一到 **WindowContainer 树**（`wm/WindowContainer.java`），一切"容器"都是它的子类：`RootWindowContainer → DisplayContent → DisplayArea → TaskDisplayArea → Task → TaskFragment → ActivityRecord`。窗口操作统一收口到 **WindowOrganizer**：

```
WindowOrganizerController                 // services/core/java/com/android/server/wm/WindowOrganizerController.java
 ├─ applyTransaction(): 执行 SurfaceControl.Transaction + 容器层级/大小变更
 ├─ createTaskFragment / deleteTaskFragment   // 分屏/嵌入式
TaskFragment / TaskFragmentOrganizer       // 把同一 Task 切成多个可独立 resized 的 Fragment
ActivityEmbedding (Jetpack): 经 TaskFragmentOrganizer 把 Activity 嵌入同一 Task 左右/上下
```

**折叠屏 Configuration 变化链路：**
```
硬件 hinge sensor / 显示状态变化（DisplayManager）
 └─ DisplayContent.onConfigurationChanged()
     ├─ 计算新的 Configuration（screenSize, smallestScreenWidth, density, orientation）
     ├─ WindowContainer.propagateConfigurationToActivity()
     └─ ActivityRecord.onConfigurationChanged()
          └─ 若 configChanges 未在 manifest 声明 → AMS 触发 Activity 重建 (relaunch)
              // 注意：折叠角度变化常带 new screen size → 触发 recreate
折叠专属：HingeAngle 经 android.hardware.Sensor.TYPE_HINGE_ANGLE 上报到 app 的 SensorManager
```

**分屏 / 自由窗口：**
- 分屏（Split-Screen）：WMShell 的 `SplitScreen` controller 创建两个 `Task` 各占半屏，经 `WindowOrganizer` 设置 `bounds`。
- 自由窗口（Freeform）：桌面模式 `wm/DesktopMode` / Taskbar，Activity 以 `windowIsTranslucent` + 可拖拽 bounds 运行。
- **Activity Embedding**：不新建 Task，在同一 Task 内用 `TaskFragment` 把多个 Activity 摆成主从布局（大屏"列表+详情"），天然规避重建。

### 易错点

- ❌ "折叠屏展开一定重建"：只有**未声明对应 configChanges 且配置确实变了**才重建；正确做法是 `android:configChanges="screenSize|smallestScreenWidthDp|orientation"` + 自行 `onConfigurationChanged` 适配（但资源切换会失效，要权衡）。
- 分屏下两个 Activity 仍各有 `TaskFragment`，但属于同一个 `TaskDisplayArea`；不只是"两个窗口"。
- `onConfigurationChanged` 与 `onNewIntent` 不互斥：分屏 resize 走 config，deep link 走 intent。

### 高频追问

1. **折叠屏如何避免视频/游戏被打断？** `CameraCompat`/App 兼容性策略 + `ActivityEmbedding` 主从；系统侧 `wm/TaskFragment` 的 `minWidth` 限制。
2. **多显示器（HDMI/投屏）？** `DisplayManager` + `ActivityTaskManager` 的 `DisplayContent` 多实例；启动到副屏用 `Intent.FLAG_ACTIVITY_MULTIPLE_TASK` + `ActivityOptions.setLaunchDisplayId`。
3. **兼容模式（CompatMode）？** `CompatModePackages` 给老 app 强制缩放/固定方向（折叠屏上常见）。

### 延伸阅读

```
frameworks/base/services/core/java/com/android/server/wm/
  WindowOrganizerController.java  TaskFragment.java  ActivityRecord.java
  DisplayContent.java  RootWindowContainer.java  WindowContainer.java
frameworks/base/core/java/android/window/  (WindowOrganizer, TaskFragmentOrganizer API)
frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/splitscreen/
```

---

## 6. SELinux 在 Android：机制与定制 ROM 调试

**面试题：定制 ROM 加了个服务总报 avc: denied，怎么定位与解决？SELinux 在 Android 是怎么组织的？**

### 答案解析

SELinux 是 **MAC（强制访问控制）**，规则编译进内核（`security/selinux/`），Android 把策略拆成多块在构建期拼合：

```
system/sepolicy/
 ├─ public/    (AOSP 通用类型/属性，OEM 不能改语义)
 ├─ private/   (AOSP 内部实现细节)
 ├─ vendor/    (vendor 分区策略，对应 /vendor 下的进程/文件)
 ├─ product/   (product 分区)
 └─ mapping/   (版本间类型兼容映射，保证 OTA 平滑)
设备侧：device/<oem>/<device>/sepolicy/  追加厂商 te 文件与 file_contexts
构建：checkpolicy/checkmodule 编译为 sepolicy 二进制，按分区刷入 /system/etc/selinux、/vendor/etc/selinux
运行时：kernel 加载，状态 enforcing/permissive 由 /sys/fs/selinux/enforce 或 boot 参数决定
```

**典型排错流程（面试必会）：**
```
1) dmesg | grep avc  或  logcat | grep avc        # 看 denied 的 scontext/tcontext/tclass/perm
2) 用 audit2allow 从 avc 生成 te 规则（仅排错用！）
   audit2allow -i avc.log  →  生成 allow <scontext> <tcontext>:<tclass> { perm };
3) 把规则补到对应 te（system 还是 vendor？看 scontext 属于哪个分区）
4) 重编 sepolicy 烧写，确认不再 denied
```

**关键概念：**
- **类型强制（TE）**：每个进程/文件有 type，规则是 `allow source_type target_type:tclass { perms }`。
- **neverallow**：AOSP 用 `neverallow` 锁死危险规则，定制时加了冲突规则会让 `checkpolicy` 构建失败（高频定制 ROM 翻车点）。
- **Binder 与 SELinux**：servicemanager 在转发 Binder 调用时（HIDL 经 `hwservicemanager`、AIDL 经 `servicemanager`）会拿调用方 **SELinux context**（`FLAT_BINDER_FLAG_TXN_SECURITY_CTX`，见主篇第 4/9 题）做 `selinux_check_access`——所以"为什么我的 app 访问某服务被拒"可能就是 SELinux，不是 UID 权限。
- **属性（attribute）** 用于分组：`appdomain`、`system_server_service`、`mlstrustedsubject` 等，写规则时引用属性而非具体 type 更通用。

### 易错点

- ❌ 生产环境直接 `permissive` 或 `audit2allow` 无脑加 `allow`：会引入安全漏洞且可能违反 neverallow。正确做法是**最小权限 + 新建合理 type**。
- 分不清 `enforcing`（拦截并记日志）与 `permissive`（只记不拦）——排错临时开 permissive，定位后必须回 enforcing。
- vendor 进程（如 HAL）的规则必须放 **vendor sepolicy**，放 system 分区不会生效（Treble 隔离，见主篇第 14 题）。

### 高频追问

1. **mac_permissions.xml 是干嘛的？** 映射 app 签名/包名 → seinfo 标签，决定 app 的 domain（如 `platform_app`/`untrusted_app`），在 `system/sepolicy/private/mac_permissions.xml`。
2. **怎么看某个文件当前的 context？** `ls -Z` / `ps -Z`，改 context 用 `chcon`（临时）、`restorecon`（按 file_contexts 恢复）。
3. **平台 App 签名与 domain 的关系？** 用 platform key 签名的 app 配 `seinfo=platform` → 进 `platform_app` domain，拥有比 `untrusted_app` 多得多的权限。

### 延伸阅读

```
system/sepolicy/ (public/private/vendor/product/mapping)
external/selinux/ (checkpolicy, sepolicy-analyze, audit2allow)
security/selinux/  (内核实现)
device/<oem>/<device>/sepolicy/  (厂商增量)
```

---

## 7. OTA / A/B 无缝升级与动态分区

**面试题：Android 的 A/B 升级怎么做到"不中断使用"？动态分区（super）和快照（snapuserd）做什么？**

### 答案解析

**A/B 无缝升级（Android 7 引入，现在主流）：** 设备上有两套 slot（`slot A` / `slot B`），当前启动在一个，升级把新系统写入**另一个空闲 slot**，写完后切 `active` 标记，下次重启即用新系统；失败则回退旧 slot。全程用户可正常使用。

```
update_engine (system/update_engine/)            // 下载 payload、按操作清单写 block
 ├─ 读 OTA payload（含 manifest：REPLACE/MOVE/SOURCE_COPY/ZERO/ERASE 操作）
 ├─ CowWriter / SnapshotManager 写新分区（见下）
 └─ 写入后 mark slot B active（bootctl / misc 分区）
重启 → bootloader 选 active slot → 新系统
```

**动态分区（Android 10+，/super）：** 把 `system/vendor/product/odm` 合进一个 **super 分区**，用 `dm-linear`（device-mapper）动态"切"出逻辑分区。好处：OTA 时可以在 super 内重新分配各分区大小，不必固定预留空间。

**快照（snapshot/COW，snapuserd，Android 11+）：**
- A/B 之外，为支持**虚拟 A/B**（小 storage 设备只有一份物理分区、用快照伪装两份），引入 `snapuserd`（`system/core/fs_mgr/libsnapshot/`）。
- 升级时把旧分区的写操作**重定向到 COW（copy-on-write）设备**，旧 slot 逻辑上仍可读；若需回退，丢弃 COW 即可还原。OOM/断电时靠 COW 一致性恢复。
- 内核侧 `dm-user` 模块配合 snapuserd 在用户态处理 COW 映射。

**校验链（安全）：** `vbmeta` 分区存哈希树与签名（AVB, `external/avb/`），bootloader 在加载前验证 system/vendor 的 `hashtree_disabled` 与 `vbmeta` 签名——**校验失败直接拒绝启动**（verified boot）。`avbtool` 是构建/调试工具。

### 易错点

- ❌ "A/B 就是双份完整系统"：物理 A/B 才是；虚拟 A/B（`Virtual A/B`）靠 snapuserd 的 COW 复用一份物理空间，省存储但有快照回滚成本。
- OTA 失败常见原因：storage 不足（快照写不下）、`vbmeta` 校验不过（改了 system 没重签）、动态分区映射错乱（`fs_mgr` 挂不上 super）。
- `fastboot` 与 `recovery` 模式：A/B 大多用 `update_engine` 走正常系统升级，少进 recovery；但 `adb sideload` 仍走 recovery。

### 高频追问

1. **如何调试 OTA 失败？** `logcat -b all | grep update_engine`；`snapshotctl` / `dmctl` 看快照状态；`avbtool verify_image` 校验 vbmeta。
2. **GKI 与 OTA 的关系？**（见主篇第 15 题）GKI 让内核与 vendor 解耦，内核可单独 OTA 而不破坏板级驱动（.ko 按 KMI 约束）。
3. **增量 OTA？** payload 里是 block 级 diff（bsdiff），只下发变化块，体积小但要求源版本严格匹配。

### 延伸阅读

```
system/update_engine/  system/core/fs_mgr/libsnapshot/ (snapuserd)
system/core/fs_mgr/  bootable/recovery/  (recovery + install)
external/avb/  build/tools/releasetools/  (OTA 包生成)
```

---

## 8. JNI 机制与 Android Runtime hook 基础

**面试题：Java 的 native 方法怎么调到 C++？ART 上 hook 一个 Java 方法有哪些思路？**

### 答案解析

**JNI 注册两种方式：**
```
① 动态查找（默认，性能差）：首次调用 native 方法
   → art/runtime/jni/jni_internal.cc: FindNativeMethod()
   → 按 "Java_<包名>_<类>_<方法>" 拼名 dlsym 查找 so 中的符号
② 静态注册（推荐）：JNI_OnLoad 里主动 RegisterNatives
   → art/runtime/jni/jni_internal.cc: RegisterNatives()
   → 把 Java 方法 (jmethodID) 直接绑定到 C 函数指针，省去查表
```
关键结构：`JNIEnv`（每线程一份，指向 `JNIInvokeInterface`）、`JavaVM`（进程级，可 `GetEnv` 拿 `JNIEnv`）。ART 在 `art/runtime/jni/` 实现了整套 JNI 桥，含引用管理（`NewGlobalRef`/`DeleteLocalRef` 防泄漏）。

**ART hook 的常见思路（系统/逆向岗考点）：**
1. **替换 ArtMethod 入口（Inline hook / 入口替换）**：`art::ArtMethod` 里 `entry_point_from_jni_` 或 `data_`/`entry_point_from_quick_compiled_code_` 存真正执行地址。把目标方法的 entry 改成跳板函数即可拦截——SandHook、YAHFA、Epic 都走这条。难点：ART 版本差异大（ArtMethod 字段布局随版本变），且需处理"已编译 + 未编译"两种状态。
2. **dex 插桩（字节码层面）**：在 dex 里插入调用代理的指令（Tinker/主流热修复），不改 ART。
3. **Native 层 inline hook**：对 so 里的 C 函数改指令（基于指令集的 inline hook，如 Android-Inline-Hook、whale 的 native 部分）——用于 hook JNI 函数本身或 native 库。
4. **Java 层 proxy**：仅对接口/可继承类有效（动态代理），不触及 final/static。

**Android 限制（14 上收紧）：**
- **隐藏 API（hidden API）**：非 SDK 接口（`@hide`）默认被 `hiddenapi` 拦，应用直接反射抛 `NoSuchMethodError`/崩溃；绕过要改 `restriction`（`meta` 标记）或用 `Runtime` 豁免（已被逐步堵）。
- **W^X（写执行不可同时）**：ART 方法区 + SELinux + `seccomp` 限制，inline hook 需要 `mprotect` 改页权限，且部分段为只读映射。

### 易错点

- ❌ "native 方法必须按 JNI 命名"：只有动态查找才需要；用 `RegisterNatives` 可任意命名。
- JNI 局部引用（LocalRef）在 JNI 调用结束后**自动失效**，跨 JNI 调用保存要升级成 GlobalRef，否则野指针/泄漏。
- hook 系统服务方法要格外小心：ART 方法可能已被 AOT 内联进调用方，单纯改 entry 拦不到——这是 hook 框架稳定性难点。

### 高频追问

1. **JNI 引用泄漏怎么查？** `CheckJNI`（`-Xcheck:jni`）、`libnativehelper` 的 `ScopedLocalRef`、AddressSanitizer for JNI。
2. **为什么 ART 比 Dalvik 难 hook？** ART 有 AOT 编译 + 内联 + 方法抢占（GC 安全点），Dalvik 纯解释器改 entry 即可。
3. **ART 的 dex 加载流程？**（见第 3 题）`ClassLinker::LoadClass` → `LinkCode` 决定走解释/interpreter-to-JIT/AOT stub。

### 延伸阅读

```
art/runtime/jni/jni_internal.cc  art/runtime/ reflection.cc
art/runtime/art_method.h  (ArtMethod 布局)
libcore/luni/src/main/native/  (AOSP 自带的 RegisterNatives 范例)
```

---

## 9. Binder 安全：鉴权、SELinux context 与权限传递

**面试题：服务端怎么知道"是谁"在调我？Binder 调用如何防止伪造身份？oneway/protected 有什么区别？**

### 答案解析

Binder 的"身份"由**驱动在事务建立时填充**，调用方无法伪造：

```
binder_transaction()                       // drivers/android/binder.c
 └─ 填充 binder_transaction_data 的 sender_pid / sender_euid
     // 注意：是**驱动**填的，用户态改不了
IPCThreadState::executeCommand(BR_TRANSACTION)
 └─ 调 BBinder::transact 前设置 IPCThreadState 的 mCallingPid/mCallingUid
     // frameworks/native/libs/binder/IPCThreadState.cpp
Java 层 Binder.execTransact()
 └─ Binder.getCallingUid() / getCallingPid()  (android.os.Binder)
```

**权限校验三件套（服务端常用）：**
```java
// frameworks/base/core/java/android/os/Binder.java
int uid = Binder.getCallingUid();          // 调用方真实 UID（驱动填，不可伪造）
int pid = Binder.getCallingPid();
// 校验某权限：经 ActivityManager 检查调用方是否持有
context.checkPermission(permission, pid, uid);   // 或 mContext.enforcePermission(...)
```

**跨身份调用（关键技巧）：** 服务端有时要"以自己身份"去调别的服务（否则会带着客户端的 UID 越权）：
```java
final long token = Binder.clearCallingIdentity();  // 暂存并清掉调用方身份，恢复为本进程身份
try { /* 以自己(系统)身份做事 */ } finally { Binder.restoreCallingIdentity(token); }
```
典型场景：AMS 替某 app 去 PMS 查信息、WMS 内部操作——否则会把客户端的 uid 透传下去导致权限错乱。

**SELinux context 传递（Android 8+）：** AIDL/HIDL 事务带 `FLAT_BINDER_FLAG_TXN_SECURITY_CTX`，驱动把调用方 SELinux context 一并传给 servicemanager，由其在 `selinux_check_access` 做服务级鉴权（见第 6、主篇第 4 题）。

**oneway 与安全：** oneway 是**单向异步**（客户端不阻塞等回复），但**不意味着绕过权限**——权限/UID 校验照常；它只是控制流语义。真正的"protect"靠 `android:permission` 声明在 manifest 的服务上。

### 易错点

- 拿到 `getCallingUid()` 后做权限判断**必须在 `onTransact` 内、未 clearCallingIdentity 前**——否则身份已被清掉变成自己。
- ❌ "oneway 更快所以随便用"：oneway 挤占 async binder buffer（主篇第 5 题），且失败静默、无 reply，调试困难。
- 跨进程传 `PendingIntent`/`IBinder` 时，权限可随对象传递（`PendingIntent` 自带 creatorUid），这是"为什么 PendingIntent 回调以发起方身份执行"的根。

### 高频追问

1. **protected broadcast 是什么？** `frameworks/base/core/res/AndroidManifest.xml` 里 `<protected-broadcast>` 声明的 action 只有系统能发，防 app 伪造系统广播（如 `BOOT_COMPLETED`）。
2. **Binder 调用方进程被杀，如何通知我？** `linkToDeath`（主篇第 3 题）+ DeathRecipient；也可结合 `Process` 监听。
3. **如何通过 UID 限制只让特定 app 调我的服务？** `android:exported` + `android:permission` + `onTransact` 里校验 `getCallingUid()` 白名单（同签名/特定 UID）。

### 延伸阅读

```
drivers/android/binder.c  (binder_transaction 填充 pid/euid / security ctx)
frameworks/native/libs/binder/IPCThreadState.cpp  (mCallingPid/mCallingUid)
frameworks/base/core/java/android/os/Binder.java  (getCallingUid/clearCallingIdentity)
frameworks/native/cmds/servicemanager/  (selinux_check_access)
```

---

## 10. Perfetto / Systrace 实战：卡顿与 Binder 阻塞分析

**面试题：线上/线下怎么定位"主线程卡顿"和"Binder 调用阻塞"？Perfetto 比 systrace 强在哪？**

### 答案解析

**工具演进：** 老的 `systrace`（`frameworks/native/cmds/atrace/`）基于 ftrace 类别选择；新的是 **Perfetto**（`external/perfetto/`），用 `trace_config` 精确选数据源（ftrace、atrace 类别、process 的 `TrackEvent`、binder 内核 track、CPU 调度、memory），可长时间、可 SQL 查询。

**一次典型卡顿分析流程：**
```
1) 抓 trace：
   adb shell perfetto -o /data/misc/perfetto-traces/trace_file.pftrace \
     -t 10s sched freq idle am wm gfx view binder_driver \
     --app=<your.pkg>           # process track 包含 Choreographer/doFrame
2) 打开 ui.perfetto.dev 导入，或 python 端 sql
3) 找主线程 (Thread track: <pkg> main)
   - 看 Choreographer.doFrame 间隔是否 > 16.6ms（丢帧）
   - 看 frame 内 measure/layout/draw 各阶段耗时（查 View 层级/过度绘制）
   - 看主线程是不是卡在 binder 调用（binder_transaction / binder_reply 长耗时）
4) 若卡在 binder：展开 binder_driver 的 tx 行，找对端 (to binderX)，
   切到对端进程线程，定位是 AMS/WMS/Provider 慢 → 回到各篇对应机制
```

**Perfetto 独有优势：**
- **结构化 SQL**：`SELECT * FROM slice WHERE name LIKE '%doFrame%'`，可批量统计丢帧；
- **binder 内核 track**：直接看到每个事务的发起/应答耗时、对端 PID（`binder_transaction` 事件的 `dest_node`/`dest_proc`）；
- **长时 trace + 低开销**：可抓分钟级不掉帧；
- 支持 **app 自定义 TrackEvent**：用 `perfetto::Tracing` + `TRACE_EVENT` 在自己代码埋点。

**App 侧埋点（Java）：**
```java
// frameworks/base/core/java/android/os/Trace.java
Trace.beginSection("MySlowWork");  // 写入 atrace 缓冲区，Perfetto/systrace 可采集
try { ... } finally { Trace.endSection(); }
```
底层是 `atrace`（通过 `/sys/kernel/debug/tracing/trace_marker` 写字符）。

### 易错点

- ❌ "卡顿就是主线程忙"：先确认是 **CPU 饥饿（别的进程抢）/ IO 等待（iowait）/ 锁竞争（binder 对端慢）**，Perfetto 的 `sched` track 看主线程是否在 `Running` 还是 `Runnable`（排队）还是 `Sleeping`（等 IO/binder）。
- `systrace` 已经半废弃，新代码/新文档都用 Perfetto；但 atrace 类别名（sched/gfx/view/am/wm）在两套工具通用。
- 抓 trace 时别开太多类别（尤其 `disk`/`mm` 全开）否则 buffer 爆掉丢数据；按需选。

### 高频追问

1. **怎么定位锁竞争？** Perfetto 的 `thread_state` slice 看主线程是否长时间 `Sleeping` 且 wakeup 来自某锁；或 `lock contention` 的 systrace tag；用户态可用 `StrictMode` 的 `detectCustomSlowCalls`。
2. **ANR trace 与 perfetto 怎么配合？** `/data/anr/traces.txt`（SIGQUIT dump）看"卡在哪一帧/哪个 native"，Perfetto 看"卡之前发生了什么"。
3. **线上如何低开销采集？** `perfetto --background` + 系统 `perfetto` 守护 + 采样（非全量）；或 `debug*`（debuggable）下按需抓。

### 延伸阅读

```
external/perfetto/  (trace processor / SQL)
frameworks/native/cmds/atrace/  frameworks/base/core/java/android/os/Trace.java
frameworks/base/core/java/android/view/Choreographer.java  (doFrame 标记)
docs: perfetto.dev (查询语法/数据源表)
```

---

## 11. 知识网络串联 & 延伸阅读

### 本次补足的知识网络（对照主篇）

- [x] **Input 全链路**：把"触摸→事件→窗口焦点→分发"与 WMS 焦点窗口、Input ANR 串起来（主篇 2/9/11 的拼图）。
- [x] **PMS 安装**：解释了 `startActivity` 时 `resolveActivity`、`packages.xml` 持久化、插件化的 dexElements 根源（主篇 6 的"组件哪来的"）。
- [x] **ART/JIT/AOT**：启动优化的底层抓手，连接主篇 6（冷启动落点）与 12（内存/GC）。
- [x] **SystemUI/锁屏 & 折叠屏 WM**：补全主篇 9（WMS）在"系统窗口/多窗口"侧的应用。
- [x] **SELinux & OTA/AB & Binder 安全**：把"权限/身份/安全"从 Binder 驱动一路串到定制 ROM 与升级（主篇 3/4/14 的安全补充）。
- [x] **JNI/hook & Perfetto**：系统/逆向/性能岗的"工具箱"，是前面所有机制的观测与干预手段。

### 高频追问交叉索引

| 想深挖 | 看主篇 | 看本篇 |
|---|---|---|
| 启动为什么慢 | 6 冷启动 | 2 PMS、3 ART |
| 触摸了没反应 | 9/11 | 1 Input |
| 卡顿怎么查 | 11 ANR | 10 Perfetto |
| 新服务起不来 | 14 HAL | 6 SELinux、9 Binder安全 |
| OTA 后黑屏/起不来 | 15 内核 | 7 OTA/AB |
| 插件化/热修复 | — | 2 PMS、3 ART、8 JNI |

### 延伸阅读（源码路径速查·拓展篇）

```
Input     frameworks/native/services/inputflinger/{reader,dispatcher}/
          frameworks/base/core/java/android/view/{InputEventReceiver,ViewRootImpl}.java
PMS       frameworks/base/services/core/java/com/android/server/pm/
          frameworks/native/cmds/installd/
ART       art/runtime/{class_linker,jit,gc}/  libcore/dalvik/.../dalvik/system/
SystemUI frameworks/base/packages/SystemUI/  frameworks/base/libs/WindowManager/Shell/
WM多屏    frameworks/base/services/core/java/com/android/server/wm/{WindowOrganizerController,TaskFragment,DisplayContent}.java
SELinux   system/sepolicy/  external/selinux/  security/selinux/
OTA       system/update_engine/  system/core/fs_mgr/libsnapshot/  external/avb/
JNI/hook  art/runtime/jni/  art/runtime/art_method.h
Binder安  drivers/android/binder.c  frameworks/native/libs/binder/IPCThreadState.cpp
Perfetto  external/perfetto/  frameworks/base/core/java/android/os/Trace.java
```

---

*本篇为 2026-07-23 自动任务的「查缺补漏」专题，与同日主篇 `Android_Framework面试题_2026-07-23.md` 互为补充。建议两篇搭配复习。源码路径以 Android 14 (android-14.0.0_rXX) 为准，配合 cs.android.com 对照。*
