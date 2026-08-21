# Android Framework 面试题 · A17 QPR2 Beta 3 增量特性 Framework 底层溯源（2026-08-21）

> 系列第 **37 篇** / 累计约 **236 专题**。前 36 篇已把主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧 AI + 源码 walk + Perfetto SQL + 基础八股 + 两版真题大乱斗 + Native 稳定性 + Compose 编译器逐行完整闭环。
> 本篇落点：**把你看得见的新功能翻译成面试官会问的底层机制题**——以 2026-08-14 发布的 Android 17 QPR2 Beta 3（本周期最大增量版本）为抓手，把 6 个新用户特性反向映射到 WMS / Keyguard / Telephony RIL / compat 框架 / SF 模糊 / monet 主题 等 AOSP 子系统，完成「用户态功能 -> Framework 底座」的逆向穿透。

---

## 0. 当日热点锚定

- **Android 17 QPR2 Beta 3** 于 **2026-08-14** 发布，build `CP41.260731.005`（A2/B1 两档），安全补丁 **2026-08-05**，是 QPR2 周期迄今最大版本。内部代号由 `CinnamonBun` 切到 `DEV`，`Pixel 6/6 Pro` 已 EOL 退出。stable 仍跟踪 **2026-12**。
- Beta 3 用户态增量（与 Framework 强相关的）：①原生 **App Lock**（长按图标锁应用）②**防呼叫转移诈骗**（限制 `TelephonyManager.sendUssdRequest()` 转发类 USSD + 手动拨号确认框）③折叠屏 **窗口 handle 拖拽**进入/退出分屏 + bubble 全屏快捷键 ④**Quick Settings 布局编辑器** ⑤Material You **主题扩展**（hue 滑块 + 4 种风格）⑥**扩展模糊**（锁屏快捷键/指纹/通知背景模糊）。
- **A18 / Aluminium OS 路线图**（8/21 同步确认）：Google 计划把 ChromeOS 技术栈重建在 Android 内核之上（代号 Aluminium OS），Android 18 将是「移动+桌面」融合的巩固版；通用剪贴板、桌面模式、AppFunctions/Gemini 系统级扩张为长期主线（联动 8/8、8/22）。
- 面试趋势（2026 金三银四题库 + 大厂考官）：Binder 一次拷贝 / 线程池 / 安全、Handler-Looper epoll、Compose 重组稳定性、ANR、Native crash、量化数字仍为最高频深考题；**「XX 新功能底层怎么实现」类反向题**权重上升——本篇正好覆盖。

---

## 1. 原生 App Lock：从「用户态锁应用」到 Framework 验证链路

**面试题**：Android 17 原生 App Lock 锁应用后，通知内容被隐藏、桌面 widget/shortcut 被移除，但允许的 AI agent 仍能访问该 App 数据。这套「锁」落在 Framework 哪一层？为什么数据层没被锁死？

**底层溯源（AOSP 14 路径）**：

1. **启动拦截点 = `ActivityStartInterceptor`**。App Lock 不是改 AMS，而是在 `ActivityStarter` 真正 `startActivity` 之前由 `frameworks/base/services/core/java/com/android/server/wm/ActivityStartInterceptor.java` 拦截。它判断 `AppLockManagerService.isPackageLocked(userId, packageName)`（新增系统服务，状态存 `/data/system/` 下 XML，类比 `AppOps`/`PackageManager` 持久化），若为真则把原 Intent 替换为 `KeyguardManager.createConfirmDeviceCredentialIntent()`，启动 `ConfirmDeviceCredentialActivity` 走 PIN/密码，或经 `BiometricService`（`frameworks/base/services/core/java/com/android/server/biometrics/BiometricService.java`）弹 `BiometricPrompt` 走指纹。验证成功 `RESULT_OK` 后再回放原 Intent。
   - **易错点**：App Lock 是 **launch 层拦截**，不是进程级沙箱。它不阻止 `bindService` / `ContentProvider` 查询 / Binder 调用——只要不走 `startActivity` 这条 UI 路径，数据照常可达。
2. **凭证校验 = `LockSettingsService`**。`frameworks/base/services/core/java/com/android/server/locksettings/LockSettingsService.java` 比对锁屏凭据（与系统锁屏共用同一套 `LockPatternUtils` + Gatekeeper/Weaver，联动 8/1 Keystore2/Weaver）。因此 App Lock 的 PIN 与系统锁屏 PIN 是同一把钥匙。
3. **通知内容隐藏 = `NotificationManagerService` 的锁屏可见性策略**。`frameworks/base/services/core/java/com/android/server/notification/NotificationManagerService.java` 对「已锁 App」的通知应用 `VISIBILITY_SECRET` 等价策略：`NotificationRecord.calculateLockscreenVisibility()` 把可见性压到 secret，`Notification.getRedacted()` 剥离敏感字段（标题/文本/大图），锁屏只显示「内容已隐藏」。这不是 App 自己声明，是系统按 `AppLockManager` 状态在 enqueue 时强制 redaction（联动 8/7 通知可见性 / 8/13 系统托管 UI）。
4. **widget/shortcut 移除 = `AppWidgetServiceImpl` + `ShortcutService`**。锁定时 `AppWidgetServiceImpl`（`frameworks/base/services/core/java/com/android/server/appwidget/AppWidgetServiceImpl.java`）通知 host 移除该包 widget；`ShortcutService`（`frameworks/base/services/core/java/com/android/server/pm/ShortcutService.java`）撤销其动态/固定 shortcut。二者都是「宿主侧收口」，源 App 无需改动。
5. **为什么 AI agent 仍能访问数据 —— 呼应 8/13、8/12 真缺口**：
   - AppFunctions Provider 的数据访问走 **Binder `getCallingUid()`**，但正如 8/13 指出：Provider 侧拿到的 `callingUid` 是 **`SYSTEM_UID`**（中间经 `AppsIndexerManagerService` / AppSearch 转交），**不可信**，App Lock 的 launch 拦截对这条路径完全不生效。
   - 同理 ANI/A11y（`AccessibilityService`）读语义树、系统托管 UI 取数据，都是 **系统身份跨进程调用**，不走被锁 App 的 `Activity`。这是「UI 锁 ≠ 数据锁」的经典安全边界题。

**高频追问链**：
- Q：App Lock 和「应用多开 / 工作资料」的隔离有什么本质区别？A：工作资料（8/1/8/31 跨资料环回阻断）是 **user 级沙箱**（独立 UID 区间 + SELinux 双域），数据层真隔离；App Lock 仅是 **同一 user 内的 UI 启动门禁**，数据层共享。
- Q：用户能不能绕过 App Lock 直接 `am start` 调起被锁 Activity？A：不能——`ActivityStartInterceptor` 在 system_server 内拦截，shell `am start` 也走同一 `ActivityStarter`，仍需过凭据。
- Q：App Lock 的锁状态存在哪、会不会被 root 篡改？A：持久化在 `LockSettingsService` 关联的 `/data/system/` 加密分区（FBE，联动 8/1 vold/fscrypt），root 可读但篡改后下次启动 `LockSettingsService` 校验失败会回退。

---

## 2. 防呼叫转移诈骗：`TelephonyManager.sendUssdRequest()` 加固的 IPC 边界

**面试题**：Beta 3 说「仅持有 `CALL_PHONE` 权限的 App 不能再后台触发呼叫转移类 USSD，否则回调 `USSD_ERROR_NOT_ALLOWED`；手动拨号也要系统确认框」。这套加固在 Framework 哪一层落地？为什么移动钱包/余额查询类 USSD 不受影响？

**底层溯源（AOSP 14 路径）**：

1. **入口 = `TelephonyManager.sendUssdRequest()`**。`frameworks/base/telephony/java/android/telephony/TelephonyManager.java` 的该 API 旧实现把 USSD 请求直接转 `PhoneInterfaceManager`。Beta 3 在 **`PhoneInterfaceManager`（`packages/services/Telephony/src/com/android/phone/PhoneInterfaceManager.java`）的 `sendUssdRequest` 实现里新增前置校验**：若调用方仅持 `CALL_PHONE` 且目标 MMI 码命中「呼叫转移」前缀集合（`*21*`/`**21*`/`#21#`/`*67*`/`*61*`/`**61*` 等），直接 callback `onError(TelephonyManager.USSD_ERROR_NOT_ALLOWED)`，**不向 RIL 下发**。
   - **易错点**：这层校验在 **telephony 进程（privileged）** 而非 App 进程，App 无法通过自己改写绕过；它属于 platform 侧权限收紧（联动 8/31 compat 框架——很可能用一个 `@ChangeId` 在 `CompatConfig` 里 gate，老 targetSdk 可豁免，体现行为变更引擎）。
2. **RIL 下发链路**（被拦截后根本到不了这步）。正常非转发类 USSD：`PhoneInterfaceManager` -> `CommandsInterface.sendUssd(String, Message)` -> `frameworks/opt/telephony/src/java/com/android/internal/telephony/RIL.java` `RIL_REQUEST_SEND_USSD` -> Radio HAL AIDL `IRadio.sendUssd()`（联动 8/24 HIDL vs AIDL for HAL / 8/17 VINTF）。modem 回 `RIL_UNSOL_ON_USSD` 经 `UssdResponse` 回传。
3. **「手动拨号需确认框」= `TelecomManager` / Dialer 的 OS 级确认**。`Telecom`（`packages/services/Telecomm`）或 `packages/apps/Dialer` 在检测到用户拨出转发类 MMI 时，弹出 **系统级 `ConfirmCallForwardDialog` / `UserPromptDialog`**（非 App 自建），用户点确认才真正发往 modem。这是把「用户显式意图」与「App 静默滥用」区分开——经典的 confused-deputy 防护（呼应 8/31 BAL `callingUid vs realCallingUid`）。
4. **为什么移动钱包/余额查询不受影响**：拦截按 **MMI 码前缀白/黑名单** 匹配，仅命中呼叫转移语义集合；`*123#` 余额、`*234#` 流量等走 `sendUssdRequest` 的**非转发分支**，依旧放行。可见这是「语义识别 + 权限升级」而非「一刀切禁 USSD」。

**高频追问链**：
- Q：`CALL_PHONE` 和真正能设呼叫转移的权限差在哪？A：设呼叫转移历史上只需 `CALL_PHONE`（设计缺陷），Beta 3 把「转发类 USSD 触发」从 `CALL_PHONE` 中剥离，等价于要求更高特权（系统/运营商签名或新增受限权限），是**最小权限原则回补**。
- Q：这和 Binder `getCallingUid` 校验有什么关系？A：telephony 是 privileged UID，`PhoneInterfaceManager` 校验的是 **App 侧 `callingUid` 的权限集**（`PermissionManagerService`/`AppOpsManager` 路径校验，联动 8/7 权限全链路），仍是内核 Binder 驱动校验 UID/PID 的延续。
- Q：如果恶意 App 改用 ` ACTION_CALL ` + `tel:*21*...#` Intent 拨号能绕过吗？A：不能——走拨号器会触发第 3 步的 **系统确认框**，用户可见；且 `Telecom` 在 `CallIntentProcessor` 同样做 MMI 语义拦截。

---

## 3. 折叠屏窗口 handle：WindowOrganizer 手势引擎与多窗口状态机

**面试题**：Beta 3 折叠屏「活动窗口出现拖拽 handle，下拉进分屏、上推回全屏」，还有 bubble 全屏快捷键。这套窗口手势归谁管？和 Android 的 freeform/分屏是什么关系？

**底层溯源（AOSP 14 路径）**：

1. **窗口手势归属 = WMShell 的 `DesktopTasksController` + `TaskbarController`**。`frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/desktopmode/DesktopTasksController.java` 管理桌面/freeform 窗口装饰（caption bar / 拖拽 handle）；`frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/taskbar/TaskbarController.java` 负责任务栏与窗口拖拽手势的命中。handle 拖拽产生的「进入/退出分屏」事件由 `SplitScreenController` / `StageCoordinator`（`frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/splitscreen/`）消费，最终通过 **`WindowOrganizer`（`frameworks/base/core/java/android/window/WindowOrganizer.java`）+ `WindowOrganizerController`（`frameworks/base/services/core/java/com/android/server/wm/WindowOrganizerController.java`）** 下发 `WindowContainerTransaction` 改窗口层级，而不是老的 `ActivityManager` 直接改。
   - **易错点**：现代多窗口**全部走 WindowOrganizer 事务**，不再经 `ActivityManager.moveTaskToFront` 那种旧 API；WMS（`com.android.server.wm`）只认 `WCT`，这是 8/22 Desktop Mode 2.0 已铺开的三维辨析（freeform 窗口属性 vs WM Shell 策略 vs 应用侧 ActivityEmbedding）的延续。
2. **窗口状态机 = `DesktopModeTaskRepository` + `WindowingMode`**。`WindowConfiguration.WINDOWING_MODE_FREE_FORM` / `SPLIT_SCREEN_PRIMARY` / `FULLSCREEN` 的切换由 WMShell 在事务里原子提交；handle 的下拉手势识别在 `TaskbarDragLayer` 的 pointer 事件里（联动 8/16 Input 全链路——拖拽 handle 是 `View`-级 `onTouchEvent`，与系统 `InputDispatcher` 的 split/Pointer Capture 正交）。
3. **bubble 全屏快捷键 = Bubbles + TaskView**。bubble 展开全屏走 `Bubbles`（`frameworks/base/packages/SystemUI/.../bubbles/`）+ `TaskView`（`android.app.ActivityView` / `TaskOrg` 包装），本质仍是一个 `WindowContainerTransaction` 把它从 bubble 容器搬到全屏 `DisplayArea`（呼应 8/31 Bubbles 的 `TaskOrganizer`/`TaskView`/`WindowContainerTransaction` 新窗口模式）。

**高频追问链**：
- Q：handle 拖拽和「系统手势导航」冲突吗？A：不冲突——系统返回/主屏手势在 `SystemGestures`/`Quickstep` 层，窗口内部 handle 手势在 WMShell 的 `TaskbarDragLayer` 消费并 `requestDisallowIntercept`（呼应 8/12 requestDisallowIntercept 对 DOWN 无效那个坑），二者分层。
- Q：分屏两 app 生命周期怎么调度？A：同一 `StageCoordinator` 管理两个 `Stage`（`SplitScreenStage`/`MainStage`），各自 `ActivityStack` 独立 `onPause/onResume`，`RootWindowContainer` 统一 resumeTopActivity（联动 8/20 AMS 生命周期）。
- Q：折叠屏旋转 + 分屏 + 多指会黑屏吗？A：历史上有竞态（呼应 8/12 真题大乱斗 vol.2 场景① / QPR2 #516836306 多指丢触摸），handle 拖拽触发的 `Surface` 销毁/重建时序若与旋转叠加，需 `RelayoutWindow` 正确 reorder——Perfetto 看 `wm` 切片定界。

---

## 4. Quick Settings 布局编辑器：SysUI `TileService` 与持久化

**面试题**：Beta 3 的 QS 布局编辑器能拖动重排 tile（亮度条、媒体播放器位置都可调）。这套「用户自定义布局」在 Framework 怎么存、怎么读、怎么保证跨重启不丢？

**底层溯源（AOSP 14 路径）**：

1. **tile 定义 = `TileService`**。`frameworks/base/core/java/android/service/quicksettings/TileService.java` 是各 QS 瓦片的 Service 基类（`REQUEST_LISTENING`/`onStartListening`）。系统 tile 由 `QSFactoryImpl`（`frameworks/base/packages/SystemUI/src/com/android/systemui/qs/QSFactoryImpl.java`）实例化。
2. **布局持久化 = `Settings.Secure.QS_TILES`**。`frameworks/base/packages/SettingsProvider/...` 把用户拖拽后的 tile 顺序（组件名逗号分隔）写进 `Settings.Secure.QS_TILES`；`QSPanel`/`QSFragment`（`frameworks/base/packages/SystemUI/src/com/android/systemui/qs/`）启动时从 `Secure` 读取重建。重排交互在 `QSCustomizer` / `TileAdapter`（`frameworks/base/packages/SystemUI/src/com/android/systemui/qs/customize/`）里完成 `ItemTouchHelper` 拖拽 + 写回。
   - **易错点**：`Settings.Secure` 是**按 user 隔离**的（`MultiUser` 场景各 user 一套），且写回走 `SettingsProvider` 的 `binder` 事务（普通 App 无 `WRITE_SECURE_SETTINGS` 改不了，需特权/系统签名）——这解释了为什么第三方 launcher 改不了系统 QS 顺序。
3. **亮度条/媒体独立拖拽** = 把这两个从「tile 列表」里抽成**固定 slot**（`QSContainerImpl` 的 `mQSPanel` 与独立 `BrightnessSliceView`/`MediaHost`），编辑器只调整它们在容器内的 z/位置，不参与 `QS_TILES` 序列，所以能「移到更易触达位置」而不破坏 tile 语义。

**高频追问链**：
- Q：用户自定义 QS 和 OEM 预置 QS 怎么共存？A：OEM 在 `config.xml`（`quick_settings_tiles_default`/`stock`）预置默认，`QS_TILES` 只覆盖用户改动部分，未改动 tile 回退默认（典型的「默认配置 + 用户覆盖层」模式，呼应 8/7 RRO/Overlay 思想）。
- Q：tile 的点击怎么跨进程到真正的服务？A：`TileService` 的 `onClick` 经 `IQSService` Binder 回 SystemUI，`TileService` 本身跑在**提供方进程**（如 `WifiTile` 在 SystemUI 内，`CustomTile` 在 App 进程），跨进程经 `TileLifecycleManager` 的 Binder 连接（联动 8/26 Binder 线程池）。

---

## 5. Material You 主题扩展：monet / `WallpaperColors` / `DynamicColors`

**面试题**：Beta 3 主题新增 hue 滑块 + 4 种风格（Neutral/Soft/Bright/Bold）。Android 的「根据壁纸取色并套到全系统」这套动态主题在 Framework 怎么实现的？hue 滑块改的是哪一步？

**底层溯源（AOSP 14 路径）**：

1. **取色源 = `WallpaperColors`**。`frameworks/base/core/java/android/app/WallpaperColors.java` 从壁纸提取主色/次要色/基础色，是 monet 算法的输入。
2. **套用入口 = `ThemeOverlayController`**。`frameworks/base/packages/SystemUI/src/com/android/systemui/theme/ThemeOverlayController.java` 监听壁纸/主题变化，把生成的 `ThemeOverlay` 经 `OverlayManagerService`（`frameworks/base/services/core/java/com/android/server/om/OverlayManagerService.java`）应用到 `android`/`sysui` 等 target package（联动 8/7 RRO/Overlay `idmap`）。
3. **色彩算法 = `com.android.internal.util.color` + `packages/apps/ThemePicker`**。monet 把 `WallpaperColors` 映射到 Material `ColorScheme`（CAM16/HCT 色空间），生成各角色色（primary/secondary/tertiary/neutral）。**hue 滑块 = 在 `ColorScheme` 生成阶段叠加用户 hue 偏移**（旋转色相环），4 种风格 = 不同的 `Style` 枚举（控制 chroma/tone 曲线）。
4. **App 侧适配 = `DynamicColors`**（`androidx.core/content`）。App 调 `DynamicColors.applyToActivitiesIfAvailable()` 即可让自己的 theme 跟随系统 monet；本质上让 App 的 `?attr/colorPrimary` 指向系统 overlay 暴露的 `android:colorSystem*` 资源。

**高频追问链**：
- Q：monet 取色是实时还是缓存？A：`WallpaperColors` 在壁纸变更时计算并缓存，`ThemeOverlayController` 订阅 `WallpaperManager` 的 `OnColorsChangedListener`，变更才重算——避免每次 inflate 重算。
- Q：hue 滑块会不会让品牌色失真？A：会，这是用户主权 vs 品牌一致的权衡；App 若用**硬编码色**而非 `?attr` 则不受 monet 影响（呼应 8/13 系统托管 UI / 主题一致性）。

---

## 6. 扩展模糊与 Material 3 Expressive：`RenderEffect` / `CrossWindowBlur` / `BlurUtils`

**面试题**：Beta 3 把模糊扩展到锁屏快捷键、指纹按钮、通知背景。Android 的窗口模糊（背景虚化）是 GPU 合成的还是 HWC 的？App 怎么给自己的 View 加模糊？

**底层溯源（AOSP 14 路径）**：

1. **App 侧模糊 = `RenderEffect` + `View.setRenderEffect()`**。`frameworks/base/graphics/java/android/graphics/RenderEffect.java`（含 `BlurEffect`、`Shader` 链）经 `View.setRenderEffect()` 在 **RenderThread 的 RenderNode** 上挂载模糊节点，最终由 HWUI 的 `RenderEffect` 在 GPU 上做（Skia/Ganesh 或 Vulkan 后端，联动 8/7 HWUI/RenderThread、8/30 SF RenderEngine）。
2. **窗口级背景模糊 = `WindowManager.LayoutParams.setBlurBehindRadius()` + `BlurUtils`**。`frameworks/base/services/core/java/com/android/server/wm/BlurUtils.java` 把窗口模糊半径经 `SurfaceFlinger` 的 `setBackgroundBlurRadius` 下发；是否跨窗口（背后所有窗口一起虚化）由 `CrossWindowBlurEnabledListener` 控制（系统可全局开关，避免隐私泄漏——虚化背后内容也是信息泄露面）。
3. **合成决策 = SF + HWC**。模糊层若面积大，SF 可能用 GPU 合成（blur pass）而非 HWC Overlay（Overlay 硬件一般不擅长逐像素模糊）；Perfetto 看 `sf` 切片 + `gpu` 计数（联动 8/7、8/25 GPU 计数器 / Overlay vs GPU 合成判定）。
   - **易错点**：`RenderEffect` 模糊是**逐 View 在 RenderThread** 做的，和「窗口背后模糊」是两套机制；Material 3 Expressive 的锁屏模糊用的是**窗口级 `setBlurBehindRadius`**，所以能虚化「锁屏后面的壁纸/通知」而非单个控件。

**高频追问链**：
- Q：模糊会不会掉帧？A：大面积跨窗口模糊走 GPU，叠加 8/7 讲的「RenderThread -> SF -> HWC」时序，若单帧 blur pass 超 budget 会丢帧（FrameTimeline `JankType=SurfaceFlinger`）；可看 `android_gpu_memory` / `gpu_counter`（联动 8/25）。
- Q：`CrossWindowBlur` 为什么能关？A：模糊会泄露背后窗口内容，是隐私面；`BlurUtils` 的全局开关在 `Settings.Secure`/系统属性，企业设备管理（DevicePolicy）可禁用（呼应 8/1 安全/隐私边界）。

---

## 7. A18 前瞻：Aluminium OS 与桌面融合对 Framework 的重构（延伸阅读）

- **Aluminium OS**：Google 计划把 ChromeOS 技术栈重建在 Android 内核之上（Sameer Samat 原话「rebuild ChromeOS underlying tech on top of Android」）。对 Framework 的影响：①`WMS`/`WindowOrganizer` 要原生支撑**桌面级多窗口 + 外接显示器 + 键鼠高级配置**（Pointer speed/scroll direction/multi-finger trackpad，联动 8/16 Input Touchpad + 8/22 Desktop Mode 2.0）②通用剪贴板（8/22 `ClipboardService`+`ClipData` 跨设备，AES-GCM 传输中加密）③AppFunctions/Gemini 系统级扩张（8/13）。Android 18 是这套融合的**巩固版**，不是一次性大版本。
- **面试提示**：A18 面试题大概率围绕「Android 如何既当手机 OS 又当桌面 OS」——答案骨架 = WindowOrganizer 多窗口 + 外接 display（`DisplayManager`/`ActivityView`）+ 跨设备协同（CDM/Handoff，8/22）+ 端侧 AI（8/4/8/13）。本系列 8/8、8/22 已铺好底座。

---

## 8. 易错红榜 TOP18（本篇新增 + 跨篇呼应）

1. App Lock 是 **launch 层拦截**（ActivityStartInterceptor），**不是进程沙箱**——`bindService`/CP 查询不受阻。
2. App Lock 通知隐藏是 **NMS 强制 redaction**，不是 App 自己声明 `VISIBILITY_SECRET`。
3. AI agent 仍能读被锁 App 数据 = Binder `getCallingUid()` 拿到 `SYSTEM_UID` 不可信（呼应 8/13）。
4. `sendUssdRequest` 加固在 **telephony 特权进程**（`PhoneInterfaceManager`），App 无法本地绕过。
5. 转发类 USSD 拦截按 **MMI 前缀匹配**，移动钱包/余额 USSD 走非转发分支照常放行。
6. 手动拨号确认框是 **Telecom/Dialer 系统级弹窗**，不是 App 自建——confused-deputy 防护。
7. 现代多窗口**一律走 `WindowOrganizer` 的 `WindowContainerTransaction`**，别答老 `ActivityManager.moveTaskToFront`。
8. 折叠屏 handle 手势在 **WMShell TaskbarDragLayer**，与系统导航手势分层不冲突。
9. bubble 全屏 = Bubbles + `TaskView`，本质还是一次 `WCT` 改层级（呼应 8/31 Bubbles）。
10. QS 布局持久化在 **`Settings.Secure.QS_TILES`**，按 user 隔离，普通 App 无 `WRITE_SECURE_SETTINGS` 改不了。
11. 亮度条/媒体是**独立 slot**，不参与 `QS_TILES` 序列，所以能单独拖位置。
12. monet hue 滑块改的是 **ColorScheme 生成阶段的 hue 偏移**，不是重取壁纸色。
13. App 硬编码色不受 monet 影响——要用 `?attr/colorSystem*` 才跟随（呼应 8/13）。
14. `RenderEffect` 模糊（逐 View/RenderThread）≠ 窗口 `setBlurBehindRadius`（跨窗口/SF）两套机制。
15. `CrossWindowBlur` 能全局关 = 模糊是隐私泄露面（呼应 8/1）。
16. 大面积跨窗口模糊多走 **GPU 合成**而非 HWC Overlay，可能掉帧（联动 8/7/8/25）。
17. App Lock 锁状态存 **FBE 加密分区**，root 可读但篡改会被 `LockSettingsService` 校验回退。
18. A18 Aluminium OS = ChromeOS 重建于 Android 内核，**不是新内核**，融合在 WM/display/CDM 层。

---

## 9. 三条高频追问链（可直接当模拟考）

- **链 A（安全边界）**：App Lock 锁了 App，AI agent 为什么还能读数据？-> 因为锁在 launch 层，数据层靠 Binder `getCallingUid` 鉴权，而 agent 调用拿到 SYSTEM_UID 不可信（8/13）。-> 那工作资料能防住吗？-> 能，user 级沙箱 + SELinux 双域（8/1/8/31）。-> 怎么从 Framework 证明某调用方可信？-> 内核 Binder 驱动校验 UID/PID + `clearCallingIdentity`/`restoreCallingIdentity` 边界（8/26/8/36）。
- **链 B（Telephony IPC）**：`sendUssdRequest` 怎么被限制的？-> `PhoneInterfaceManager` 前置校验 MMI 前缀 + 权限集（8/31 compat 可能 gate）。-> 正常 USSD 怎么到 modem？-> `RIL_REQUEST_SEND_USSD` -> Radio HAL AIDL（8/24/8/17）。-> 手动拨号确认框防什么？-> 防 confused-deputy / 社会工程（8/31 BAL 同理）。
- **链 C（多窗口/渲染）**：折叠屏 handle 拖拽归谁管？-> WMShell DesktopTasksController + WindowOrganizer WCT（8/22）。-> 拖拽和导航手势冲突吗？-> 分层 + requestDisallowIntercept（8/12/8/16）。-> 分屏旋转黑屏怎么查？-> Perfetto `wm`/`sf` 切片 + RelayoutWindow 时序（8/25/8/12 vol.2）。

---

## 10. AOSP 14 源码路径清单（本篇）

| 子系统 | 路径 | 关键类/方法 |
|---|---|---|
| App Lock 启动拦截 | `frameworks/base/services/core/java/com/android/server/wm/ActivityStartInterceptor.java` | `intercept()` / `AppLockManagerService.isPackageLocked` |
| 凭证校验 | `frameworks/base/services/core/java/com/android/server/locksettings/LockSettingsService.java` | `checkCredential` / `LockPatternUtils` |
| 生物识别 | `frameworks/base/services/core/java/com/android/server/biometrics/BiometricService.java` | `BiometricPrompt` 路由 |
| 通知 redaction | `frameworks/base/services/core/java/com/android/server/notification/NotificationManagerService.java` | `NotificationRecord.calculateLockscreenVisibility` / `Notification.getRedacted` |
| widget/shortcut 收口 | `frameworks/base/services/core/java/com/android/server/appwidget/AppWidgetServiceImpl.java` / `.../pm/ShortcutService.java` | host 移除 / 撤销 shortcut |
| USSD 加固 | `packages/services/Telephony/src/com/android/phone/PhoneInterfaceManager.java` | `sendUssdRequest` 前置校验 |
| RIL | `frameworks/opt/telephony/src/java/com/android/internal/telephony/RIL.java` | `RIL_REQUEST_SEND_USSD` -> `IRadio.sendUssd` |
| Telecom 确认框 | `packages/services/Telecomm/` / `packages/apps/Dialer/` | `ConfirmCallForwardDialog` / `UserPromptDialog` |
| 窗口手势 | `frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/desktopmode/DesktopTasksController.java` | handle 拖拽 / WCT 提交 |
| 任务栏 | `frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/taskbar/TaskbarController.java` | `TaskbarDragLayer` 手势 |
| 分屏 | `frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/splitscreen/SplitScreenController.java` | `StageCoordinator` |
| WindowOrganizer | `frameworks/base/core/java/android/window/WindowOrganizer.java` + `.../wm/WindowOrganizerController.java` | `WindowContainerTransaction` |
| QS tile | `frameworks/base/core/java/android/service/quicksettings/TileService.java` | `onClick` / `onStartListening` |
| QS 持久化 | `frameworks/base/packages/SettingsProvider/` + `.../SystemUI/src/com/android/systemui/qs/` | `Settings.Secure.QS_TILES` / `QSFactoryImpl` |
| monet 主题 | `frameworks/base/core/java/android/app/WallpaperColors.java` + `.../SystemUI/src/com/android/systemui/theme/ThemeOverlayController.java` | `ColorScheme` / `OverlayManagerService` |
| 模糊 | `frameworks/base/graphics/java/android/graphics/RenderEffect.java` + `.../wm/BlurUtils.java` | `setRenderEffect` / `setBlurBehindRadius` / `CrossWindowBlurEnabledListener` |
| 兼容框架 | `frameworks/base/services/core/java/com/android/server/compat/CompatConfig.java` | `@ChangeId` gate（USSD/App Lock 行为变更可能走此） |

---

## 11. 36→37 篇交叉索引

- **8/13 智能系统 AppFunctions**：链 A 的「agent 读被锁 App 数据」直接复用其 `getCallingUid=SYSTEM_UID 不可信` 结论。
- **8/1 安全世界 TEE / Keystore2 / Weaver**：App Lock 凭证校验共用 `LockPatternUtils`+Gatekeeper/Weaver 链路；`CrossWindowBlur` 隐私面呼应安全边界。
- **8/22 A18 桌面融合 / 8/8 A18 Desktop Mode**：第 3 节窗口 handle + 第 7 节 Aluminium OS 直接延伸。
- **8/31 兼容性框架**：USSD 加固 / App Lock 行为变更很可能由 `@ChangeId` 在 `CompatConfig` gate（compat 引擎实例）。
- **8/16 Input 全链路 / 8/12 基础八股**：handle 手势分层、`requestDisallowIntercept` 对 DOWN 无效、split/Pointer Capture。
- **8/7 渲染合成 / 8/25 Perfetto GPU 计数器 / 8/30 SF RenderEngine**：模糊的合成决策与掉帧定界。
- **8/24 HAL / 8/17 VINTF**：RIL -> Radio HAL AIDL 路径。
- **8/26 Binder 线程池 / 8/36 `$changed` 逐行**：TileService `onClick` 跨进程 Binder、`getCallingUid` 鉴权。

> 全系列至此 37 篇 / 约 236 专题。本篇为「用户态新功能 -> Framework 底座」反向溯源专项，补齐了此前只讲系统底座、未把 **A17 QPR2 Beta 3 六个新特性**翻译成面试题的真缺口；主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧 AI + 源码 walk + Perfetto SQL + 基础八股 + 两版真题大乱斗 + Native 稳定性 + Compose 编译器逐行 + 新特性溯源 完整闭环。
