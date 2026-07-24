# SystemUI 定制 深读笔记（AOSP 14）

## 1. 位置与形态
`frameworks/base/packages/SystemUI/` 编译成 `SystemUI.apk`，运行在独立进程 `com.android.systemui`（**不是 system_server**），是带系统权限的普通 app，通过 binder 调系统服务。可崩溃重启——改完 `kill` 掉 pid 即重载。

## 2. 启动
`SystemServer` 经 `ActivityManagerInternal.startSystemUi()` 拉起 `com.android.systemui.SystemUIService`（入口 Service）→ `SystemUIApplication` 启动各 `SystemUI` 组件。

## 3. 关键类（注意重命名）
- 状态栏：`CentralSurfaces`（接口）/ `CentralSurfacesImpl`（实现）——**Android 12 由 `StatusBar` 重命名**，路径 `src/com/android/systemui/statusbar/phone/CentralSurfacesImpl.java`
- 导航栏：`NavigationBarController` / `NavigationBar`（或 `NavigationBarView`），`src/com/android/systemui/navigationbar/`
- 通知：`src/com/android/systemui/statusbar/notification/`
- 快速设置：`QSPanel` / `QuickQSPanel` / `QSTileHost`，`src/com/android/systemui/qs/`
- 锁屏：`KeyguardViewMediator` / `KeyguardStatusBarViewController`
- 图标：`StatusBarIconController`
- 注入：AOSP 14 SystemUI 用 **Dagger**（依赖 `SystemUIFactory`）

## 4. 常见定制点
| 想改 | 文件 |
|---|---|
| 状态栏图标/布局 | `CentralSurfacesImpl` + `res/layout/status_bar.xml` / `StatusBarIconController` |
| 导航栏按键/布局 | `NavigationBar` + `res/layout/navigation_bar.xml` |
| 新增快捷开关(QS Tile) | 实现 `QSTileImpl` 子类，注册到 `QSTileHost`/`TileMapper` |
| 锁屏样式 | `Keyguard*`，`res/layout/keyguard_*` |

## 5. 验证
```bash
adb shell pm path com.android.systemui
m SystemUI && adb install -r out/target/product/<dev>/system/priv-app/SystemUI/SystemUI.apk
adb shell ps -A | grep systemui     # 拿 pid
adb shell kill <pid>                # SystemUI 自动重启,看改动
dumpsys activity services SystemUI  # 看组件状态
```
注意：priv-app 需平台签名；debug 用 `adb install -r` 可覆盖。

## 6. 实战小项目
1. 在 `status_bar.xml` 加一个自定义图标，并在 `CentralSurfacesImpl` 里控制显隐（如插线时显示）。
2. 写一个 `QSTile` 一键开关某个系统属性。
