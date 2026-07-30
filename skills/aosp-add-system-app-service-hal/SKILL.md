---
name: aosp-add-system-app-service-hal
description: AOSP 14 端到端新增「系统 App + Java 系统服务 + HAL」脚手架技能。当用户要"新增系统服务/新增系统 app/加一个 java 系统服务让 app 调用/系统服务调 HAL/app 调系统服务再调 HAL"时触发。即使用户只说"加个 demo 服务"、"写个调用 HAL 的 system app"、"system_server 里挂一个服务给 app 用"、"AIDL HAL 怎么接进 framework",也应触发。默认 Android 14,采用 AIDL 稳定接口(Treble 合规)。
agent_created: true
---

# aosp-add-system-app-service-hal — 端到端新增「App + Java 系统服务 + HAL」

目标:在 Android 14 AOSP 里新增一条完整调用链
**App(getSystemService) → DemoManager(Binder 客户端) → DemoManagerService(system_server) → IDemo(Java 后端) → HAL 进程**,
并实现 HAL 经 `IDemoCallback` 主动上报、再经 `IDemoManagerCallback` 转发回 App 的双向通道。

选型默认(可按需调整):
- HAL = **AIDL 稳定接口**(`stability: "vintf"`),vendor 分区,Treble 合规;不用 HIDL。
- 系统服务运行在 `system_server`,通过 `ServiceManager.waitForService("android.hardware.<x>.I<X>/default")` 连接 HAL。
- App = platform 签名 `android_app`(`certificate: "platform"`,`platform_apis: true`),用 `signature` 级权限控制访问,不进 priv-app。

## 一、文件清单(按 AOSP 目录)

HAL(`hardware/interfaces/<x>/aidl/`):
- `Android.bp` — `aidl_interface{ name:"android.hardware.<x>", vendor:true, stability:"vintf", frozen:false, backend:{java,cpp,ndk} }`
- `android/hardware/<x>/I<X>.aidl`(主接口)、`I<X>Callback.aidl`(上报)、`<Type>.aidl`(parcelable)
- `default/`:`Demo.cpp/.h`(`Bn<X>` 实现)、`service.cpp`(`AServiceManager_addService(<X>::descriptor+"/default")` + `ABinderProcess_joinThreadPool`)、`<x>-default.rc`(`service ... /vendor/bin/hw/...` `class hal`)、`<x>-default.xml`(VINTF fragment `format="aidl"` `instance="default"`)、`Android.bp`(`cc_binary` 依赖 `android.hardware.<x>-ndk`)
- `default/sepolicy/`:`demo.te`(`type hal_<x>,domain` + `init_daemon_domain` + `add_service(hal_<x>, hal_<x>_service)` + `binder_call`)、`file_contexts`(可执行打 `hal_<x>_exec`)

Framework(`frameworks/base/`):
- `core/java/android/os/<x>/I<X>Manager.aidl` + `I<X>ManagerCallback.aidl`(APP 侧 Binder 接口,放 `core/java` 自动进 framework)
- `core/java/android/os/<x>/<X>Manager.java`(公开客户端,`@SystemService(Context.<X>_SERVICE)`,`getSystemService` 可用)
- `services/core/java/com/android/server/<x>/<X>ManagerService.java`(继承 `SystemService`,`onStart()` 里 `publishBinderService(Context.<X>_SERVICE, mBinder)` + `connectHal()`;实现 `I<X>Manager.Stub`,`enforceCallingPermission` 自定义权限;持有 `IDemo` 并 `setCallback` 接收 HAL 上报)
- 修改已有文件:`Context.java`(加 `<X>_SERVICE` 常量)、`SystemServer.java`(`startOtherServices` 启动服务)、`SystemServiceRegistry.java`(绑定 `<X>_SERVICE ↔ <X>Manager`)、`services/core/Android.bp`(`static_libs += android.hardware.<x>-java`)、`core/res/AndroidManifest.xml`(声明 `signature` 级权限)

App(`packages/apps/<X>App/`):
- `Android.bp`(`android_app`,`platform_apis:true`,`certificate:"platform"`,`privileged:false`)、`AndroidManifest.xml`(`sharedUserId:android.uid.system` + `<uses-permission>` 自定义权限)、`MainActivity.java`(`getSystemService(<X>Manager.class)` 调用 + 注册回调)

SELinux(platform,`system/sepolicy/private/`):
- `service_contexts`:`<svc> → <svc>_service`、`android.hardware.<x>.I<X>/default → hal_<x>_service`
- `service.te`:`type <svc>_service, system_api_service, system_server_service, service_manager_type;` + `type hal_<x>_service, hal_service_manager_type, service_manager_type;`
- `system_server.te`:`allow system_server <svc>_service:service_manager { add find };`、`allow system_server hal_<x>_service:service_manager find;` + `binder_call(system_server, hal_<x>)`

产品 mk:`PRODUCT_PACKAGES += <X>App android.hardware.<x>-default`

## 二、关键坑(必看)

1. **metalava API 校验**:新增公开类(`<X>Manager`)后全编前必须 `m update-api`,否则 `api/current.txt` 不一致直接失败。
2. **aidl 库名随 frozen 变化**:`frozen:false` 库名 `android.hardware.<x>-java/-ndk`;一旦 `frozen:true`+`versions`,变 `-Vn-java/-ndk`,须同步改 `services/core/Android.bp`。
3. **HAL 没起来 system_server 卡死**:`waitForService` 阻塞等待 HAL;rc 未编进 vendor 或 SELinux 拦 init 启动会一直等。先 `ps`/`dmesg` 排查。
4. **SELinux 必查**:`hal_<x>_service` 是跨分区 service 类型,首次刷机 `dmesg | grep avc` + `audit2allow` 补全。
5. **权限 SecurityException**:App 必须 platform 签名且 manifest 声明权限,且 `core/res/AndroidManifest.xml` 已加该 `<permission>`。
6. **`IDemo.DESCRIPTOR`** = AIDL 全限定名 `android.hardware.<x>.I<X>`,实例名拼 `/default`。

## 三、编译验证

```bash
source build/envsetup.sh && lunch <target>-eng
m update-api
m <X>App android.hardware.<x>-default services
# 验证:
adb shell service list | grep -E "<x>|android.hardware.<x>"
adb shell ps -A | grep android.hardware.<x>-default
```

## 关联
- 系统服务启动/注册机制 → `aosp-systemserver`
- Binder 驱动与 IPCThreadState → `aosp-binder`
- HAL/Treble/SELinux 域 → `aosp-hal-treble`
- 编译/刷机/模拟器验证 → `aosp-build-flash`
- 版本与源码路径坐标 → `aosp-navigator`
