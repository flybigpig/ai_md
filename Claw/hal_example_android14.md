# Android 14 AIDL HAL 完整示例：`android.hardware.led`

> 目标：从零实现一个**自定义 AIDL HAL**，覆盖接口定义 → Soong → 服务端实现 → init.rc → VINTF → SELinux → 客户端调用 → 编译部署调试。
> 基于 Android 14（API 34）。本示例注册的 HAL 走**标准 `servicemanager`（域 `/dev/binder`）**——这是 Android 11+ 新 AIDL HAL 的规范路径（区别于遗留 HIDL 走 `hwservicemanager`）。

---

## 1. 目录结构（落在 AOSP 树内）

```
hardware/interfaces/led/aidl/
├── Android.bp                          # 接口 Soong 模块（aidl_interface）
├── android/hardware/led/
│   ├── ILed.aidl                       # 主接口
│   └── ILedCallback.aidl               # 回调接口
└── default/
    ├── Android.bp                      # 服务端二进制 Soong 模块
    ├── Led.h                           # 实现头
    ├── Led.cpp                         # 实现体
    ├── service.cpp                     # main()，注册到 servicemanager
    ├── android.hardware.led-service.example.rc   # init 启动脚本
    └── manifest.xml                    # VINTF 声明（也可放 device 下）
```

---

## 2. 接口定义（AIDL）

### `android/hardware/led/ILed.aidl`

```aidl
package android.hardware.led;

import android.hardware.led.ILedCallback;

@VintfStability
interface ILed {
    /** 设置亮度 0..255 */
    void setBrightness(int brightness) = 1;

    /** 读取当前亮度 */
    int getBrightness() = 2;

    /** 注册亮度变化回调 */
    void registerCallback(ILedCallback callback) = 3;
}
```

### `android/hardware/led/ILedCallback.aidl`

```aidl
package android.hardware.led;

@VintfStability
interface ILedCallback {
    void onBrightnessChanged(int brightness) = 1;
}
```

> **`@VintfStability` 是 HAL AIDL 的硬性要求**：它让接口成为跨进程/跨版本的"稳定契约"，并允许注册为 VINTF HAL。没有它只是普通 binder 接口，无法进 VINTF manifest。

---

## 3. 接口 Soong 模块

### `Android.bp`（接口层）

```bp
aidl_interface {
    name: "android.hardware.led",
    vendor_available: true,
    srcs: ["android/hardware/led/*.aidl"],
    stability: "vintf",

    backend: {
        cpp: { enabled: true },
        java: { enabled: true },
        ndk: { enabled: true },
    },

    # 冻结 API 后（m android.hardware.led-update-api）再补：
    # versions_with_info: [{ version: "1", imports: [] }],
}
```

生成产物：
- C++ 头：`aidl/android/hardware/led/BnLed.h`、`ILed.h`（库 `android.hardware.led-cpp`）
- Java 包：`android.hardware.led`（库 `android.hardware.led-java`）
- NDK 库：`android.hardware.led-ndk`
- 命名空间：`aidl::android::hardware::led`

---

## 4. 服务端实现

### `default/Led.h`

```cpp
#pragma once
#include <aidl/android/hardware/led/BnLed.h>
#include <android/binder_interface_utils.h>

namespace aidl::android::hardware::led {

class Led : public BnLed {
  public:
    ndk::ScopedAStatus setBrightness(int brightness) override;
    ndk::ScopedAStatus getBrightness(int* _aidl_return) override;
    ndk::ScopedAStatus registerCallback(
        const std::shared_ptr<ILedCallback>& callback) override;

  private:
    int mBrightness = 0;
    std::shared_ptr<ILedCallback> mCallback;
};

}  // namespace aidl::android::hardware::led
```

### `default/Led.cpp`

```cpp
#include "Led.h"
#include <android/log.h>

#define LOG_TAG "LedHal"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace aidl::android::hardware::led {

ndk::ScopedAStatus Led::setBrightness(int brightness) {
    if (brightness < 0 || brightness > 255)
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);

    mBrightness = brightness;
    ALOGI("setBrightness -> %d", mBrightness);

    // 真实设备：这里 ioctl(/dev/led, ...) 或写 sysfs
    if (mCallback) mCallback->onBrightnessChanged(mBrightness);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Led::getBrightness(int* _aidl_return) {
    *_aidl_return = mBrightness;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Led::registerCallback(
        const std::shared_ptr<ILedCallback>& callback) {
    mCallback = callback;
    return ndk::ScopedAStatus::ok();
}

}  // namespace aidl::android::hardware::led
```

### `default/service.cpp`（main，注册到 servicemanager）

```cpp
#include "Led.h"
#include <android/binder_process.h>
#include <android/binder_manager.h>

using aidl::android::hardware::led::Led;
using aidl::android::hardware::led::ILed;

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(1);

    auto led = ndk::SharedRefBase::make<Led>();
    std::string name = std::string(ILed::descriptor) + "/default";
    // AIDL HAL 注册到标准 servicemanager（域 /dev/binder），非 hwservicemanager
    AIBinder* binder = led->asBinder().get();
    AServiceManager_addService(binder, name.c_str());

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;  // 不会走到这
}
```

> 服务实例名规范：**`<package>.<Iface>/<instance>`**，即 `android.hardware.led.ILed/default`。`ILed::descriptor` 就是 `android.hardware.led.ILed`。

---

## 5. 服务端 Soong 模块 + init.rc

### `default/Android.bp`

```bp
cc_binary {
    name: "android.hardware.led-service.example",
    vendor: true,
    relative_install_path: "hw",
    init_rc: ["android.hardware.led-service.example.rc"],

    srcs: ["Led.cpp", "service.cpp"],

    shared_libs: [
        "libbinder_ndk",
        "liblog",
        "android.hardware.led-cpp",   # 接口生成的 C++ 后端
    ],
}
```

### `default/android.hardware.led-service.example.rc`

```
service vendor.led-aidl /vendor/bin/hw/android.hardware.led-service.example
    class hal
    user system
    group system
    capabilities WAKE_ALARM
```

---

## 6. VINTF 声明（manifest）

### `default/manifest.xml`（或在 `device/<oem>/<device>/manifest.xml` 追加）

```xml
<manifest version="2.0" type="device">
    <hal format="aidl">
        <name>android.hardware.led</name>
        <version>1</version>
        <interface>
            <name>ILed</name>
            <instance>default</instance>
        </interface>
    </hal>
</manifest>
```

> 启动时会与 framework 的 `compatibility_matrix.xml` 匹配；实例名 `default` 必须与 `service.cpp` 里注册的名字一致，否则 `servicemanager` 拒绝注册 → 进程退出。

---

## 7. SELinux 策略（device sepolicy）

### `led.te`

```te
type hal_led_default, domain;
type hal_led_default_exec, exec_type, vendor_file_type, file_type;

hal_server_domain(hal_led_default, hal_led)
init_daemon_domain(hal_led_default)

# 允许访问字符设备（真实设备按需放开）
allow hal_led_default led_device:chr_file rw_file_perms;
```

### `file_contexts`

```
/vendor/bin/hw/android\.hardware\.led-service\.example  u:object_r:hal_led_default_exec:s0
```

> 还需在 `system/sepolicy/public/attributes` 声明 `hal_attribute(led)`（或在 device sepolicy 用 `hal_attribute(led)` 宏），并给 framework 客户端类型 `hal_led_client` 权限。具体宏/类型名随设备策略略异，这是最容易卡住的一步。

---

## 8. 客户端调用

### Java（framework 侧 / 有 BIND 权限的进程）

```java
import android.hardware.led.ILed;
import android.os.IBinder;
import android.os.ServiceManager;

IBinder binder = ServiceManager.waitForDeclaredService(
        "android.hardware.led.ILed/default");           // 新 API，替代 getService()
ILed led = ILed.Stub.asInterface(binder);
led.setBrightness(128);
int b = led.getBrightness();
```

### C++（NDK，vendor 进程内）

```cpp
#include <android/binder_manager.h>
#include <aidl/android/hardware/led/ILed.h>
using aidl::android::hardware::led::ILed;

ndk::SpAIBinder binder =
    AServiceManager_waitForDeclaredService("android.hardware.led.ILed/default");
std::shared_ptr<ILed> led = ILed::fromBinder(binder);
led->setBrightness(128);
```

> **注意**：客户端用 `waitForDeclaredService` / `AServiceManager_waitForDeclaredService`（声明式查找），这是 Android 11+ 的新规范，替代老的 `getService`。

---

## 9. 编译与部署

```bash
# 1. 编译接口 + 服务端
m android.hardware.led
m android.hardware.led-service.example

# 2. 冻结 API（仅当要发版/锁版本时）
m android.hardware.led-update-api

# 3. 刷 vendor / 或整编
m vendorimage
adb push out/target/product/xxx/vendor/bin/hw/android.hardware.led-service.example /vendor/bin/hw/
adb push out/.../vendor/etc/vintf/manifest.xml /vendor/etc/vintf/
adb reboot
```

---

## 10. 调试命令

```bash
# 看 HAL 是否注册（AIDL HAL 用 lshal --aidl 或 service list）
adb shell lshal | grep led
adb shell service list | grep led

# 看 servicemanager 里的名字
adb shell service check android.hardware.led.ILed/default

# 看 SELinux 拒绝
adb shell logcat | grep avc

# 看进程是否起来
adb shell ps -A | grep led-service
```

---

## 11. 关键注意点（踩坑清单）

1. **`@VintfStability` 必须有**，否则接口进不了 VINTF。
2. **服务名 = `android.hardware.led.ILed/default`**，manifest 的 `<instance>` 必须一致。
3. **AIDL HAL 走 `servicemanager`（`/dev/binder`），不是 `hwservicemanager`**——这是 Android 11+ 新 HAL 的规范（遗留 HIDL 才走 hwbinder）。
4. **客户端用 `waitForDeclaredService`**，不是老 `getService`。
5. **SELinux 是最大拦路虎**：`hal_attribute(led)`、`file_contexts`、客户端 `hal_led_client` 权限缺一不可，失败看 `avc` 日志。
6. **`No passthrough`**：AIDL HAL 全部 binderized，没有 HIDL 那种 passthrough 直通模式。
7. **版本化**：包名体现大版本（如 `led2`），向后兼容变更原地做，冻结用 `m <iface>-update-api`。

---

*配套文档：`hal_android14.md`（架构）、`hal_version_history.md`（演进史）。本示例为最小可运行骨架，真实硬件需替换 `setBrightness` 内的 ioctl/sysfs 逻辑，并补全设备 SELinux 策略。*
