# AOSP 14 新增「系统 App + Java 系统服务 + HAL」完整集成指南

> 适用版本: Android 14 (API 34, UpsideDownCake)
> 方案选型: HAL 采用 **AIDL 稳定接口**(`stability: "vintf"`,Treble 合规);系统服务为运行在 `system_server` 的 Java 服务;App 为 platform 签名的系统 App。
> 完整源码已按 AOSP 目录结构落在 `aosp-demo-integration/` 下,直接拷贝到 AOSP 根目录即可;对已有文件的修改在 `aosp-demo-integration/patches/`。

---

## 一、方案速查表

| 需求 | 改动层级 | 难度 | 涉及模块 / 文件 | 分区 |
|---|---|---|---|---|
| 新增 HAL(AIDL 稳定接口) | HAL / vendor | 中 | `hardware/interfaces/demo/aidl/**` | vendor |
| 系统服务调用 HAL | Framework / system | 中 | `DemoManagerService.java` + `services/core/Android.bp` | system |
| 新增 Java 系统服务 | Framework / system | 中 | `IDemoManager.aidl` + `DemoManager.java` + `SystemServer`/`SystemServiceRegistry` | system |
| App 调用系统服务 | App / system | 低 | `packages/apps/DemoSystemApp/**` | system |
| SELinux 放行 | sepolicy | 高 | `system/sepolicy` + HAL `sepolicy/` | system+vendor |

调用链路(自上而下):
**App(`getSystemService`) → DemoManager(Binder 客户端) → DemoManagerService(system_server) → IDemo(Java 后端) → HAL 进程(`android.hardware.demo.IDemo/default`)**

---

## 二、整体调用链路(Mermaid)

```mermaid
flowchart TD
    A[DemoSystemApp<br/>platform 签名系统 App] -->|getSystemService DemoManager| B[DemoManager<br/>Binder 客户端 / framework]
    B -->|Binder: IDemoManager| C[DemoManagerService<br/>运行于 system_server]
    C -->|ServiceManager.waitForService| D[(servicemanager)<br/>android.hardware.demo.IDemo/default]
    D -->|Binder: IDemo (Java 后端)| E[HAL 进程<br/>android.hardware.demo-default]
    E -->|IDemoCallback 主动上报| C
    C -->|IDemoManagerCallback 转发| B
    B -->|onEvent| A

    subgraph system_server[system 分区 / system_server 进程]
        C
    end
    subgraph vendor[vendor 分区]
        E
    end
```

要点:
- App 与 `DemoManagerService` 之间走**普通 framework Binder**(服务名 `demo`,注册在 `ServiceManager`)。
- `DemoManagerService` 与 HAL 之间走 **AIDL HAL Binder**(实例名 `android.hardware.demo.IDemo/default`,通过 `ServiceManager.waitForService` 获取)。
- HAL 通过 `IDemoCallback` 把事件推给 `DemoManagerService`,再经 `IDemoManagerCallback` 转发给 App——实现双向通信。

---

## 三、HAL 层(AIDL 稳定接口)

目标路径:`hardware/interfaces/demo/aidl/`

文件清单:
```
hardware/interfaces/demo/aidl/
├── Android.bp                              # aidl_interface 定义
├── android/hardware/demo/
│   ├── IDemo.aidl                          # 主接口:getCount / setValue / setCallback
│   ├── IDemoCallback.aidl                  # HAL 主动上报回调
│   └── DemoStatus.aidl                     # parcelable 返回结构
└── default/
    ├── Android.bp                          # cc_binary: android.hardware.demo-default
    ├── Demo.h / Demo.cpp                   # BnDemo 实现
    ├── service.cpp                         # main():注册到 servicemanager
    ├── android.hardware.demo-default.rc    # init 服务
    ├── android.hardware.demo-default.xml   # VINTF manifest fragment
    └── sepolicy/
        ├── demo.te                         # HAL 进程域策略
        └── file_contexts                   # 可执行文件标签
```

关键实现说明:
- `service.cpp` 用 NDK 后端把服务注册进 `servicemanager`:
  ```cpp
  std::string instance = std::string(IDemo::descriptor) + "/default";
  AServiceManager_addService(demo->asBinder().get(), instance.c_str());
  ABinderProcess_joinThreadPool();
  ```
- `setValue()` 写入后通过已注册的 `IDemoCallback` 主动上报事件,演示 HAL→Framework 反向通道。
- `Android.bp` 中 `frozen: false` 时,生成的 Java/NDK 库名为 `android.hardware.demo-java` / `android.hardware.demo-ndk`。一旦 `frozen: true` + `versions`,库名变为 `-Vn-java` / `-Vn-ndk`,**记得同步修改 `services/core/Android.bp` 的依赖名**。

---

## 四、Framework 层(Java 系统服务)

### 4.1 新增文件(直接拷入 AOSP)

```
frameworks/base/core/java/android/os/demo/
├── IDemoManager.aidl          # APP 侧 Binder 接口
├── IDemoManagerCallback.aidl  # APP 侧回调
└── DemoManager.java           # 公开客户端,@SystemService(Context.DEMO_SERVICE)

frameworks/base/services/core/java/com/android/server/demo/
└── DemoManagerService.java    # system_server 中的服务实现,连接并调用 HAL
```

- `DemoManagerService` 在 `onStart()` 里 `publishBinderService(Context.DEMO_SERVICE, mBinder)` 把自己的 Binder 注册为 `demo`,随后 `connectHal()` 用 `ServiceManager.waitForService("android.hardware.demo.IDemo/default")` 阻塞等待 HAL 就绪,并注册 `mHalCallback` 接收 HAL 上报。
- App 的每个调用(`getCount`/`setCount`/`registerCallback`)均 `enforceCallingPermission("android.permission.ACCESS_DEMO_SERVICE")`,仅 platform 签名应用可过。

### 4.2 修改已有文件(`aosp-demo-integration/patches/`)

| 文件 | 修改点 | patch 文件 |
|---|---|---|
| `frameworks/base/core/java/android/content/Context.java` | 新增 `DEMO_SERVICE = "demo"` 常量 | `Context.java.diff` |
| `frameworks/base/services/java/com/android/server/SystemServer.java` | `startOtherServices()` 启动 `DemoManagerService` | `SystemServer.java.diff` |
| `frameworks/base/services/core/java/com/android/server/SystemServiceRegistry.java` | 绑定 `DEMO_SERVICE ↔ DemoManager` | `SystemServiceRegistry.java.diff` |
| `frameworks/base/services/core/Android.bp` | `static_libs` 增加 `android.hardware.demo-java` | `services_core_Android.bp.diff` |
| `frameworks/base/core/res/AndroidManifest.xml` | 声明 `android.permission.ACCESS_DEMO_SERVICE`(signature) | `core_res_AndroidManifest.xml.diff` |

> 这些 diff 以 Android 14 稳定锚点(`DISPLAY_HASH_SERVICE`、`TelephonyRegistry.class` 启动等)编写。若 `git apply` 因行号偏移失败,按 `patches/` 文件里的注释**手工插入**即可(代码块已给出完整新增内容)。
> 新增了公开 API(`DemoManager` 等),全编前请执行 **`m update-api`** 重新生成 `frameworks/base/api/current.txt` / `system-current.txt`,否则 metalava 校验会失败。

---

## 五、系统 App 层

目标路径:`packages/apps/DemoSystemApp/`

文件清单:
```
packages/apps/DemoSystemApp/
├── Android.bp              # android_app,platform_apis + certificate:platform
├── AndroidManifest.xml     # 声明 ACCESS_DEMO_SERVICE 权限,sharedUserId system
└── src/com/android/demoapp/
    └── MainActivity.java   # getSystemService(DemoManager.class) 调用 + 注册回调
```

- `Android.bp` 用 `platform_apis: true` + `certificate: "platform"`,与系统同签名即可拿到 `signature` 级 `ACCESS_DEMO_SERVICE` 权限,**无需放进 priv-app**,避免 `privapp-permissions` 约束。
- `MainActivity` 在 `onCreate` 中读取/写入计数并注册 `IDemoManagerCallback`,UI 实时显示 HAL 上报事件。

编译进系统:把 `patches/PRODUCT_PACKAGES.snippet` 的两行加入产品 mk:
```
PRODUCT_PACKAGES += DemoSystemApp
PRODUCT_PACKAGES += android.hardware.demo-default
```

---

## 六、SELinux 策略

### 6.1 HAL 进程域(vendor)→ `hardware/interfaces/demo/aidl/default/sepolicy/`
- `demo.te`:定义 `hal_demo` 域、`hal_demo_exec`,`init_daemon_domain` + `add_service(hal_demo, hal_demo_service)` + 与 `system_server` 的 `binder_call`。
- `file_contexts`:给 `/vendor/bin/hw/android.hardware.demo-default` 打 `hal_demo_exec` 标签。

### 6.2 Framework 服务与连接(platform)→ `system/sepolicy/private/`
- `service_contexts`:`demo → demo_service`、`android.hardware.demo.IDemo/default → hal_demo_service`。
- `service.te`:声明 `demo_service` 与 `hal_demo_service` 类型。
- `system_server.te`:允许 `system_server` 对 `demo_service` 做 `{add find}`、对 `hal_demo_service` 做 `find`,并 `binder_call(system_server, hal_demo)`。

> 注意:`hal_demo_service` 同时被 platform(`system_server.te`)与 vendor(`demo.te`)引用,属跨分区 service 类型。本策略为**起点模板**,首次刷机后务必用 `adb shell dmesg | grep avc` + `audit2allow` 补全被拒规则。

---

## 七、编译 & 刷入验证

```bash
source build/envsetup.sh
lunch <your-target>-eng          # 如 sdk_phone_x86_64-eng
m update-api                     # 1) 先更新 API 描述文件(新增公开类)
m DemoSystemApp android.hardware.demo-default services            # 2) 模块编译
# 或整编: make -j$(nproc)
```

验证步骤:
1. 刷机/启动后,确认 HAL 已起:
   ```bash
   adb shell service list | grep demo          # 应看到 android.hardware.demo.IDemo/default
   adb shell ps -A | grep android.hardware.demo-default
   ```
2. 确认 Framework 服务已注册:
   ```bash
   adb shell service list | grep "demo "        # 应看到 demo (在 system_server 中)
   ```
3. 启动 App `Demo System App`,UI 应显示 `getCount(前)` / `setCount` / `getCount(后)`,且 `setValue` 触发后收到 `[HAL 上报] code=1` 事件。
4. 双向验证:另开 shell 调用 HAL 看是否联动:
   ```bash
   adb shell cmd demo ...   # 若无 cmd 实现,用 App 即可验证
   ```

---

## 八、踩坑清单

1. **API 校验失败(metalava)**:新增 `DemoManager` 等公开类后未跑 `m update-api`,全编报 `api/current.txt` 不一致。→ 先 `m update-api` 再编。
2. **aidl 库名不匹配**:`frozen` 状态改变后库名从 `-java` 变成 `-Vn-java`,`services/core/Android.bp` 的 `static_libs` 要同步改,否则链接报 `undefined reference` / 找不到 `android.hardware.demo`。
3. **HAL 未启动 / 连不上**:`waitForService` 会阻塞,若 HAL 的 rc 没编进 vendor 或 SELinux 拦了 init 启动,`system_server` 会一直等。→ 先看 `ps` 是否起进程,再看 `dmesg` avc、`logcat -b all | grep DemoHal`。
4. **SELinux avc denied**:最常见。典型是 `system_server` 找不到 `hal_demo_service` 或 `binder_call` 缺失。→ `dmesg | grep avc`,`audit2allow -i avc.log` 生成补充规则,并入 `system_server.te` / `demo.te`。
5. **权限被拒 SecurityException**:App 未用 platform 签名或没声明 `ACCESS_DEMO_SERVICE`。→ 确认 `certificate: "platform"` 且 manifest 有 `<uses-permission>`,且 `core/res/AndroidManifest.xml` 已加该权限。
6. **Binder 事务过大/溢出**:本例数据量极小不会触发;若以后在 `DemoStatus` 里塞大对象,注意 oneway 与 parcel 大小限制(惯例 < 1MB)。
7. **找不到 `IDemo.DESCRIPTOR`**:Java 后端生成的常量就是 AIDL 全限定名 `android.hardware.demo.IDemo`,实例名要拼 `/default`。

---

## 九、目录对照(本仓库 → AOSP 根)

| 本仓库路径 | AOSP 目标路径 |
|---|---|
| `hardware/interfaces/demo/aidl/**` | `hardware/interfaces/demo/aidl/**` |
| `frameworks/base/core/java/android/os/demo/**` | `frameworks/base/core/java/android/os/demo/**` |
| `frameworks/base/services/core/java/com/android/server/demo/**` | `frameworks/base/services/core/java/com/android/server/demo/**` |
| `packages/apps/DemoSystemApp/**` | `packages/apps/DemoSystemApp/**` |
| `sepolicy/system_private/*` | `system/sepolicy/private/*`(合并内容) |
| `patches/*` | 手工套用到对应已有文件 |
