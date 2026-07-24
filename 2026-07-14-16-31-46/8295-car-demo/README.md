# 8295 车机开发 Demo（应用层 / APK）

高通 SA8295 车机绝大多数是 **Android Automotive OS (AAOS)** 或车厂定制 Android。
本 Demo 按**应用/APK 层**视角落地四块通用能力，可直接放进 Android Studio 编译运行：

1. 多屏 / 响应式（`MultiDisplayHelper` + `PassengerPresentation`）
2. 相机扫码（`scan/CameraXScanActivity`，ML Kit）
3. 车辆信号（`car/CarApiManager`，`android.car`）
4. AAOS 模拟器实操（见下文）

> 系统/BSP 层（ABL、TrustZone、内核 DTS、QNX Hypervisor）需要高通商业授权 + NDA，
> 普通开发者拿不到，不在本 Demo 范围。

---

## 一、环境准备

- **IDE**：Android Studio（最新稳定版，Hedgehog+/Iguana+）。
- **JDK**：17（与 `build.gradle.kts` 中 `jvmTarget=17` 对应）。
- **编译 target**：建议用 **AAOS automotive 系统镜像**作为编译/运行环境，
  `android.car` 由系统提供，无需额外引包。

### 拿不到 automotive 编译环境时（普通 phone target）

`android.car` 不是公开 SDK，普通 SDK 没有这个类。两种办法：

1. 用 **AAOS 模拟器**（推荐，见第二节）作为编译 + 运行目标；或
2. 从车机 / AAOS 模拟器系统镜像里提取 `android.car.jar`，放到 `app/libs/`，
   并取消 `app/build.gradle.kts` 里 `compileOnly(files("libs/android.car.jar"))` 的注释。

提取方式（连上 AAOS 模拟器/车机后）：
```bash
adb pull /system/framework/android.car.jar app/libs/android.car.jar
```

---

## 二、AAOS 模拟器实操步骤

```bash
# 1) 安装 automotive 系统镜像（API 34 为例）
sdkmanager "system-images;android-34;android-automotive;x86_64"

# 2) 创建 AVD（命令行；也可在 AVD Manager 图形界面选 automotive 硬件配置）
avdmanager create avd \
  -n car8295_avd \
  -k "system-images;android-34;android-automotive;x86_64" \
  -d "automotive" \
  -p ~/.android/avd/car8295_avd.avd

# 3) 启动（建议开多屏：--weight 控制第二屏）
emulator -avd car8295_avd -no-snapshot -cores 4
```

模拟器自带 **mock CarService**，可用 `CarPropertyManager` 读写虚拟车辆信号调试，
不受真机 `signature` 权限限制——非常适合前期开发。

> 模拟器里 `PERF_VEHICLE_SPEED` 等属性会由 mock 后端返回，能直接跑通 `CarApiManager`。

---

## 三、运行四个 Demo

- **多屏**：点「多屏 / 响应式」。`MultiDisplayHelper.showPassengerScreen()` 优先用
  `Presentation` 渲副驾屏；无副屏时 Toast 提示（单屏开发板也能验证逻辑）。
  响应式尺寸靠 `res/values-sw600dp`、`values-sw1000dp`、`values-land` 的 `dimens.xml` 断点切换。
- **扫码**：点「相机扫码」，CameraX + ML Kit 逐帧识别 QR/CODE_128/EAN_13，
  结果落在底部 `TextView`。生产环境加去重/节流。
- **车辆信号**：点「读取车辆信号」读一次车速；进入页面即订阅车速变化自动刷新。
  无 `CAR_SPEED` 权限时显示「无权限/不可用」。
- **电子凭证 / 打印替代**：本 Demo 未含，思路见 `qcom-8295-auto-dev-guide.md`
  第三节（PdfDocument / 蓝牙 ESC-POS / 云端打印）。

---

## 四、踩坑提醒（框架视角）

1. **AAOS 才有 android.car**：车厂定制 Android（非 AAOS）常没有 `CarService`，
   车辆信号走 OEM 私有 SDK / 系统广播，不能照搬 `CarPropertyManager`。
   先 `adb shell pm list packages | grep car` 确认。
2. **相机可能被 EVS 独占**：环视/倒车摄像头常被 EVS/Hypervisor 占用，
   CameraX 拿不到；扫码用舱内/DMS 摄像头或外置 USB/蓝牙摄像头。
3. **权限是 signature/system**：`CAR_SPEED`/`CAR_POWERTRAIN` 等真机需 OEM 签名或预装，
   `AndroidManifest` 声明不等于拿到。
4. **副屏限制**：部分 OEM 要求 `manifest` 声明 `com.android.car.allowed_passenger_display`，
   且副驾屏 Activity 需在车厂白名单内。
5. **回调线程**：`CarPropertyEventCallback` 在 Binder 线程，UI 更新务必 `runOnUiThread`。
6. **BLE 打印多为 GATT**：新设备常无 SPP，蓝牙打印走 GATT 而非 `BluetoothSocket` SPP。
