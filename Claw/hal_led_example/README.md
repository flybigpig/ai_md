# android.hardware.led AIDL HAL 示例（可直接落 AOSP）

本目录是 `hal_example_android14.md` 的**可编译骨架**，整体对应 AOSP 树内路径
`hardware/interfaces/led/aidl/`。整目录拷进 AOSP 即可编译、部署、调试。

## 目录布局（本目录 = AOSP 的 hardware/interfaces/led/aidl/）

```
Android.bp                                  接口 Soong 模块（aidl_interface）
android/hardware/led/ILed.aidl              主接口（@VintfStability）
android/hardware/led/ILedCallback.aidl      回调接口
default/Android.bp                          服务端二进制模块
default/Led.h / Led.cpp                      BnLed 实现
default/service.cpp                         main()，AServiceManager_addService
default/android.hardware.led-service.example.rc  init 启动脚本
default/manifest.xml                        VINTF 声明
sepolicy/hal_led_default.te                 SELinux 类型/域
sepolicy/file_contexts                       SELinux 文件上下文
```

## 接入 AOSP 步骤

1. **复制**：把本目录内容放到 `hardware/interfaces/led/aidl/`
   （注意 `Android.bp` 的 `name` 与 AIDL 包名 `android.hardware.led` 保持一致）。
2. **SELinux**：把 `sepolicy/hal_led_default.te` 与 `sepolicy/file_contexts`
   并入设备 sepolicy（`device/<oem>/<device>/sepolicy/`），并确认 `hal_attribute(led)`
   已声明（通常在 `system/sepolicy/public/attributes` 或设备 sepolicy 宏里）。
3. **VINTF**：将 `default/manifest.xml` 内容追加进
   `device/<oem>/<device>/manifest.xml`，或随 default 一起安装到 `/vendor/etc/vintf/`。
4. **编译**：
   ```bash
   m android.hardware.led
   m android.hardware.led-service.example
   m vendorimage
   ```
5. **部署**：
   ```bash
   adb push out/.../vendor/bin/hw/android.hardware.led-service.example /vendor/bin/hw/
   adb push out/.../vendor/etc/vintf/manifest.xml /vendor/etc/vintf/
   adb reboot
   ```
6. **验证**：
   ```bash
   adb shell service check android.hardware.led.ILed/default
   # 期望：service is running
   ```

## 客户端调用（framework / 有 BIND 权限的进程）

```java
IBinder b = ServiceManager.waitForDeclaredService(
        "android.hardware.led.ILed/default");   // 新 API，替代 getService()
ILed led = ILed.Stub.asInterface(b);
led.setBrightness(128);
```

## 关键注意（踩坑清单）

1. `@VintfStability` 必须有，否则接口进不了 VINTF manifest。
2. 服务名 = `android.hardware.led.ILed/default`，`<instance>` 必须一致，否则 servicemanager 拒注册 → 进程退出。
3. AIDL HAL 走 `servicemanager`（`/dev/binder`），不是 `hwservicemanager`。
4. 客户端用 `waitForDeclaredService`，不是老的 `getService`（Android 11+ 规范）。
5. SELinux 是最大拦路虎：`hal_attribute(led)` / `file_contexts` / 客户端 `hal_led_client` 权限缺一不可，失败看 `logcat | grep avc`。
6. AIDL HAL 全部 binderized，无 HIDL 那种 passthrough 直通模式。
7. 版本化：包名体现大版本（如 `led2`），向后兼容变更原地做；冻结用 `m android.hardware.led-update-api`。
