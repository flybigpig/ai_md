# WMS 窗口管理 深读笔记（AOSP 14）

## 1. 在系统里的位置
WindowManagerService（WMS）管理**所有窗口**（Activity、Dialog、StatusBar、Toast、输入法、壁纸），运行在 `system_server`。与 AMS、InputManagerService 强耦合：AMS 管 Activity 生命周期，WMS 管这些 Activity 的"窗口表面"、层级、焦点、动画；InputManager 把输入事件派发给 WMS 指定的"当前焦点窗口"。

启动：`SystemServer.startOtherServices()`
```java
wm = WindowManagerService.main(context, inputManager, !firstBoot, ...);
ServiceManager.addService(Context.WINDOW_SERVICE, wm, ...);
ServiceManager.addService("window", wm);
```
`WMS.main()` 在独立 looper（"android.display" 线程）上 new 实例；构造里建 `RootWindowContainer`、`mWindowMap`、`Session` 表、`mPolicy = new PhoneWindowManager()`（经 PolicyThread 初始化）、初始化 SurfaceControl native。

## 2. 核心数据结构（从顶到底）
- `RootWindowContainer` — 所有 Display 的根
- `DisplayContent` — 一块物理/虚拟屏
- `Task`（Android 11 前叫 `ActivityStack`）— 一组相关窗口，含 `ActivityRecord`
- `ActivityRecord` — 一个 Activity 实例（AMS 同对象）
- `WindowToken` — 同一 token 下的窗口集合
- `WindowState` — 单个窗口的服务器侧状态
- `Session` — 每个客户端进程一个（`IWindowSession`）
- `SurfaceControl` — 指向 native Surface 的句柄（像素在 SurfaceFlinger）

## 3. 关键流程
### addWindow（APP 加窗口）
`ViewRootImpl` → `IWindowSession.add()` → `Session.add()` → `WMS.addWindow(Session, IWindow, LayoutParams, ...)`：
1. 按 `LayoutParams.type` 校验权限（`TYPE_APPLICATION_OVERLAY` 需 `SYSTEM_ALERT_WINDOW`；`TYPE_SYSTEM_ERROR`/`TYPE_SYSTEM_DIALOG` 需系统权限，由 `PhoneWindowManager.checkAddPermission()` 判定）
2. 找/建 `WindowToken`，new `WindowState`，加入 `mWindowMap`
3. `mPolicy.adjustWindowParamsLw()` 等微调
4. 返回 `addResult`（如 `ADD_FLAG_FIRST_WINDOW`），并 `openInputChannel()` 给客户端建 `InputChannel`

### relayoutWindow（布局/出图）
`WMS.relayoutWindow()` 计算窗口帧、可见性，经 `SurfaceControl` 创建/更新 surface，把 frame 回给客户端去 draw。

## 4. 常见定制点（hook 位置）
| 想改什么 | 改哪 | 关键方法 |
|---|---|---|
| 禁止某类系统对话框 | `WMS.addWindow()` 或 `PhoneWindowManager.checkAddPermission()` | 按 `attrs.type` 拦截/返回错误 |
| 窗口转场动画 | `AppTransition` / `RemoteAnimationAdapter` / `WindowStateAnimator` | `overridePendingAppTransition()` |
| 焦点/置顶逻辑 | `RootWindowContainer` / `DisplayContent` | `getTopFocusedDisplayContent().mCurrentFocus` |
| 默认分辨率/密度 | `WMS` + `DisplayManager` | `ro.sf.lcd_density` |
| 状态栏/导航栏高度 | `PhoneWindowManager` + `WMS` | `getSystemDecorLayer()` |

## 5. 验证
```bash
adb shell dumpsys window windows        # WindowState 列表、焦点、层级
adb shell dumpsys window displays        # 各屏信息
adb shell dumpsys SurfaceFlinger         # Layer/合成
adb shell wm size / wm density           # 分辨率/密度
```
加 log：`Slog.d("WM_DBG", ...)` 放 `WMS.addWindow/relayoutWindow`，`logcat -s WindowManager:* WM_DBG:*`。

## 6. 实战小项目
1. 在 `WMS.addWindow()` 里对 `TYPE_SYSTEM_ALERT` 且特定包名直接返回错误/抛 `WindowManager.BadTokenException`，`make services` 推 `services.jar` 验证。
2. 给某个 Activity 加自定义进场动画（`overridePendingAppTransition`）。
