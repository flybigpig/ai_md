# Input 事件分发 深读笔记（AOSP 14）

## 1. 位置
InputManagerService（IMS，Java）在 `system_server`；真正的读取与分发在 native `inputflinger`。IMS 与 WMS 互相持有引用：`WMS` 构造后 `inputManager.setWindowManagerCallbacks(wm.getInputManagerCallback())`，`inputManager.start()` 启动 native 线程。

## 2. 分层与关键类
- Java：`frameworks/base/services/core/java/com/android/server/input/InputManagerService.java`
- native：`frameworks/native/services/inputflinger/`
  - `InputManager.cpp` — JNI 桥 + 起 `InputReader`/`InputDispatcher` 线程
  - `EventHub.cpp` — 枚举 `/dev/input/event*`，`epoll` 读原始事件
  - `InputReader.cpp` — 读事件、经 Mapper（Keyboard/Touch/...）翻译，输出给 Dispatcher
  - `InputDispatcher.cpp` — 策略（经 JNI 回调 `PhoneWindowManager`）、定焦点、派发到目标 `InputChannel`
- 客户端：`InputChannel` + `InputEventReceiver`（APP 侧）→ `ViewRootImpl` → DecorView 派发

## 3. 完整链路
```
/dev/input/eventN → EventHub → InputReader(KeyboardInputMapper...)
  → InputDispatcher(应用 policy: PhoneWindowManager.interceptKeyBeforeQueueing /
                     interceptKeyBeforeDispatching)
  → 焦点窗口 InputChannel → app InputEventReceiver → ViewRootImpl → View 树
```

## 4. 关键拦截点（Java）
- `PhoneWindowManager.interceptKeyBeforeQueueing(KeyEvent event, int policyFlags)`：事件入队前，可消费/改写（音量、电源、多任务键）
- `interceptKeyBeforeDispatching()`：分发到 app 前
- `dispatchUnhandledKey()`：app 没消费时的兜底

## 5. 外设适配（自定义按键板）
- `frameworks/base/data/keyboards/`：`Generic.kl`（scancode→keycode）、`Generic.kcm`（keycode→字符）
- 设备专属：放 `/system/usr/keylayout/Vendor_XXXX_Product_XXXX.kl`，按 `getevent` 看到的 vendor/product 命名
- 改完 `adb push` 到 `/system/usr/keylayout/`，`adb reboot` 或重载

## 6. 验证
```bash
adb shell dumpsys input                 # 设备列表/配置/焦点
adb shell getevent -l                   # 原始事件(scancode/keycode)
adb shell input keyevent KEYCODE_VOLUME_UP
adb shell input tap 500 500 / input text hello
```

## 7. 实战小项目
1. 用 `interceptKeyBeforeQueueing` 把 `KEYCODE_APP_SWITCH` 短按改成 `launchHome()`（参考指南多任务键需求）。
2. 给一块自定义按键板写 `.kl`，把某 scancode 映射成 `KEYCODE_BOOKMARK`，`getevent` 验证。
