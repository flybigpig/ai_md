# AOSP14 Vendor HAL 五件套模板（`android.hardware.hello`）

> 可直接 apply 的 HAL 全栈骨架：接口 + 构建 + 启动 + SELinux + 系统服务/Manager。
> 基准包名：`android.hardware.hello` / 接口 `IHello` / 实例 `default`。
> 所有文件已按 AOSP 真实目录结构放好，复制到你的 tree 即可（注意把 `<oem>/<device>` 换成真实值）。

## 一、文件 → 真实路径映射

| 本模板文件 | 复制到（AOSP 根下） | 类别 |
|---|---|---|
| `hardware/interfaces/hello/Android.bp` | `hardware/interfaces/hello/Android.bp` | ① bp（接口） |
| `hardware/interfaces/hello/aidl/android/hardware/hello/IHello.aidl` | 同上目录的 aidl 子路径 | ① bp 依赖 |
| `vendor/oem/device/hello/Android.bp` | `vendor/<oem>/<device>/hello/Android.bp` | ① bp（进程） |
| `vendor/oem/device/hello/service.cpp` | `vendor/<oem>/<device>/hello/service.cpp` | ⑤ HAL 实现 |
| `vendor/oem/device/hello/android.hardware.hello-service.rc` | `vendor/<oem>/<device>/hello/` | ③ rc |
| `vendor/oem/device/hello/android.hardware.hello.xml` | `vendor/<oem>/<device>/hello/` | ② vintf |
| `device/oem/device/sepolicy/vendor/hal_hello.te` | `device/<oem>/<device>/sepolicy/vendor/` | ④ sepolicy |
| `device/oem/device/sepolicy/vendor/service.te` | 同上 | ④ sepolicy |
| `device/oem/device/sepolicy/vendor/service_contexts` | 同上 | ④ sepolicy |
| `device/oem/device/sepolicy/vendor/file_contexts` | 同上 | ④ sepolicy |
| `device/oem/device/sepolicy/vendor/oem_device.ignore.cil` | 同上 | ④ sepolicy |
| `frameworks/base/core/java/android/os/IHelloService.aidl` | `frameworks/base/core/java/android/os/` | ⑤ Service 契约 |
| `frameworks/base/services/core/java/com/android/server/hello/HelloService.java` | `frameworks/base/services/core/java/com/android/server/hello/` | ⑤ 系统服务 |
| `frameworks/base/core/java/android/hello/HelloManager.java` | `frameworks/base/core/java/android/hello/` | ⑤ Manager |

## 二、还需在 framework 里补的接点（五件套之外，必做）

1. **`Context.java`**（`frameworks/base/core/java/android/content/Context.java`）加 `@hide` 常量：
   ```java
   /** @hide */
   public static final String HELLO_SERVICE = "hello";
   ```
2. **`SystemServiceRegistry.java`**（`frameworks/base/core/java/android/app/SystemServiceRegistry.java`）注册 Manager：
   ```java
   registerService(Context.HELLO_SERVICE, HelloManager.class,
       new CachedServiceFetcher<HelloManager>() {
           @Override public HelloManager createService(ContextImpl ctx) {
               IBinder b = ServiceManager.getService(Context.HELLO_SERVICE);
               return new HelloManager(IHelloService.Stub.asInterface(b));
           }
       });
   ```
3. **`SystemServer.java`**（`frameworks/base/services/java/com/android/server/SystemServer.java`）在 `startOtherServices()` 启动：
   ```java
   mSystemServiceManager.startService(com.android.server.hello.HelloService.class);
   ```
4. **权限声明**（framework 的 `framework/base/core/res/AndroidManifest.xml` 或你的权限定义处）：
   ```xml
   <permission android:name="android.permission.HELLO_ACCESS"
       android:protectionLevel="signature|privileged" />
   ```
5. **framework 侧 sepolicy**：`hello_service` 类型在 `system/sepolicy` 标 `system_api_service`（注意是 platform 层，不走 `*.ignore.cil`）。

## 三、设备 mk

```mk
# device/<oem>/<device>/device.mk
PRODUCT_PACKAGES += android.hardware.hello-service
```

## 四、改成你真实的 HAL 包名

全局替换（推荐用 sed / IDE 批量替换），保持「包名 / 接口名 / 实例名」三者同步：
- `android.hardware.hello` → 你的 HAL 包名（如 `android.hardware.vehiclebody`）
- `IHello` → 你的接口名（如 `IVehicleBody`）
- `hello` → 缩写（影响 `hal_hello_*`、`hello_service`、`HELLO_SERVICE`、`HELLO_ACCESS`、目录名）
- `default` 实例名一般不变；若改，需同步 VINTF `<instance>`、`service_contexts`、代码注册名三处。

## 五、构建与验证

```bash
source build/envsetup.sh && lunch <device>-eng
make sepolicy                 # 单独验 SELinux 语法
make sepolicy_freeze_test     # 验 freeze（厂商 HAL 靠 *.ignore.cil 的 new_objects 豁免）
m check-vintf-all             # 验 VINTF 声明/版本/实例
make android.hardware.hello-service   # 编 HAL 进程
# 全编后刷机：
adb shell service list | grep hello
# 期望看到 android.hardware.hello.IHello/default

adb shell service list | grep "hello$"      # framework 服务
# 期望看到 hello

# 排障
adb shell dmesg | grep avc
adb shell setenforce 0   # 临时确认是否 SELinux 问题（仅排障，量产不留）
```

## 六、常见坑（详见《AOSP14 HAL 全栈》总文档）

- 🔴 `HelloService` 构造里别 `waitForService(HAL)`（无限等、阻塞 boot）→ 已用懒连接 + `linkToDeath`。
- 🔴 AIDL HAL 别配 HIDL sepolicy（`hwservice_manager_type`/`::`/`hwbinder_use`）。
- 🔴 VINTF `version` / 实例名 / `service_contexts` / 代码注册名四者必须一致。
- 🟠 vendor HAL 走 `/dev/binder`（NDK 后端默认），别落到 `/dev/vndbinder`。
- 🟡 HAL 进程务必 `user system`/`group system`/`seclabel` 降权。
