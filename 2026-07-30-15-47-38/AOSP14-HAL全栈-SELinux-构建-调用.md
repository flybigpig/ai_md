# AOSP14 HAL 全栈：SELinux + 构建 + 调用（总文档）

> 合并自《HAL SELinux 策略》《Vendor 分区 HAL 全链路》《App 调用 HAL》《Vibrator HAL SELinux 策略》四篇，并以 `android-14.0.0_rXX`（API 34）为准统一校订。
> 贯穿示例：`android.hardware.hello.IHello`（vendor AIDL HAL）；车载段引用 `android.hardware.vehiclebody` / `android.hardware.vibrator` 作对照。
> 所有结论均可照编；凡与 AOSP10 时代片段冲突处，以本文为准。

---

## 第一部分：架构总览

一次「App 调到 vendor HAL」的完整链路分四段，跨三个进程、两个 binder 设备、三道 SELinux 闸：

```
App 进程 ──getSystemService──▶ framework HelloManager(@hide)
        │
        │ Binder (/dev/binder, service=hello)
        ▼
system_server 进程 ──HelloService(SystemService)──▶ IHelloService.Stub
        │
        │ Binder (/dev/binder, 服务名 android.hardware.hello.IHello/default)
        ▼
vendor HAL 进程 ──BnHello(NDK)──▶ 真正实现
```

| 段 | 进程 | binder 设备 | 服务名 | SELinux 闸 |
|---|---|---|---|---|
| App→system_server | app | `/dev/binder` | `hello` | 第一道：`system_api_service` + `find` |
| system_server→HAL | system_server | `/dev/binder` | `android.hardware.hello.IHello/default` | 第二道：`binder_call` + `find hal_hello_service` |
| HAL 自身注册 | vendor HAL | `/dev/binder`（非 vndbinder） | `android.hardware.hello.IHello/default` | 注册闸：`binder_use` + `add hal_hello_service` |

**两个 AIDL 接口别混**：
- HAL AIDL `android.hardware.hello.IHello` —— NDK 后端，vendor 侧。
- Service AIDL `android.os.IHelloService` —— java 后端，framework 侧（App/Manager 与 system_server 的契约）。

**Treble 隔离本质**：HAL 实现在 `/vendor`，与 `/system` 经 VINTF 契约解耦，两边各自 OTA。vendor 域默认禁止访问 system 文件、`/data` 根目录。

---

## 第二部分：VINTF 声明 + Soong 构建（vendor HAL）

### 2.1 VINTF 设备清单（AIDL 格式）

HAL 必须在设备清单里声明，servicemanager 的 `isDeclared()`、按需 `tryStartService()`、`check-vintf-all` 都依赖它。

落点三选一（按设备习惯）：
- `device/<oem>/<device>/manifest.xml`
- `device/<oem>/<device>/manifest/<hal>.xml`（片段，推荐）
- `vendor/<oem>/<device>/etc/vintf/manifest/<hal>.xml`（运行时实际落点，由 `vintf_fragments` 自动安装）

```xml
<manifest version="8.0" type="device">
    <hal format="aidl">
        <name>android.hardware.hello</name>
        <version>1</version>
        <interface>
            <name>IHello</name>
            <instance>default</instance>
        </interface>
    </hal>
</manifest>
```

要点：
- `format="aidl"`（非 hidl），`<version>` 必须与 `aidl_interface.versions:["1"]` **完全一致**。
- `version="8.0"` 是 FCM level；**Android 14 的 Shipping FCM 为 level 8**（部分 tree 用 9，以 `check-vintf-all` 报错为准）。
- `<instance>default</instance>` 必须与服务注册名 `android.hardware.hello.IHello/default`、以及 `service_contexts` 三者**一字不差**——否则 `isDeclared()` 返回 false、`getService` 永远拿不到（新增 HAL「服务起来了却取不到」的头号根因）。

### 2.2 Soong 构建

```bp
// hardware/interfaces/hello/Android.bp
aidl_interface {
    name: "android.hardware.hello",
    vendor_available: true,
    stability: "vintf",                       // HAL 硬要求
    srcs: ["aidl/android/hardware/hello/IHello.aidl"],
    versions: ["1"],
    gen_trace: true,
    backend: {
        cpp:  { enabled: true },
        ndk:  { enabled: true },              // HAL server 用 NDK 后端
        java: { enabled: true },              // framework/system_app 侧用 java 后端
    },
}

// vendor/<oem>/<device>/hello/Android.bp
cc_binary {
    name: "android.hardware.hello-service",
    vendor: true,                             // ★ 进 /vendor
    init_rc: ["android.hardware.hello-service.rc"],
    vintf_fragments: ["android.hardware.hello.xml"],
    relative_install_path: "hw",              // → /vendor/bin/hw/
    srcs: ["service.cpp"],
    shared_libs: [
        "libbinder_ndk",
        "android.hardware.hello-ndk",
        "liblog",
        "libutils",
    ],
}
```

- `stability:"vintf"` 是 HAL 硬要求，缺了 `check-vintf` 报接口不稳定。
- `vendor:true` 决定进 `/vendor`；车载 HAL 一般用它即可。
- `vintf_fragments` 让 Soong 自动把 XML 装到 `/vendor/etc/vintf/manifest/`，无需手写 `PRODUCT_COPY_FILES`。
- `relative_install_path:"hw"` → `/vendor/bin/hw/android.hardware.hello-service`，与 `file_contexts`、`init_rc` 三者一致。
- HAL server 用 **NDK 后端 + `libbinder_ndk`**（不是 framework 的 `libbinder` C++），进程跑在 vendor，符合 Treble。

设备 mk 只需加 `cc_binary`：
```mk
PRODUCT_PACKAGES += android.hardware.hello-service
```
> 只需加 `cc_binary`！桩和 `vintf_fragments` 由依赖自动带入。

### 2.3 跨分区头号坑：`/dev/binder` vs `/dev/vndbinder`

```
vendor 进程默认：
  /dev/vndbinder  → vndservicemanager（vendor 域私有服务）
  /dev/binder     → servicemanager（framework 域，AIDL HAL 注册这里！）
  /dev/hwbinder   → hwservicemanager（HIDL 遗留）
```

AIDL HAL 注册到 framework 的 `servicemanager`（`/dev/binder`）。但 HAL 进程是 vendor 进程，默认只开 `/dev/vndbinder`，**必须显式走 `/dev/binder`**。NDK 后端默认即连 `/dev/binder`，**推荐 NDK 后端，少踩坑**；若用 C++ framework `libbinder`，需 `ProcessState::initWithDriver("/dev/binder")` 并确认 sepolicy 有 `binder_use`。

### 2.4 运行时路径总览

| 产物 | 构建属性 | 运行时路径 |
|---|---|---|
| HAL 可执行体 | `vendor:true` + `relative_install_path:"hw"` | `/vendor/bin/hw/android.hardware.hello-service` |
| init rc | `init_rc` | `/vendor/etc/init/android.hardware.hello-service.rc` |
| VINTF 清单 | `vintf_fragments` | `/vendor/etc/vintf/manifest/android.hardware.hello.xml` |
| AIDL 桩(.so) | NDK 后端 | `/vendor/lib[64]/android.hardware.hello.so` |
| sepolicy exec 标签 | `file_contexts` | 同上可执行体路径 |
| 数据目录 | —— | `/data/vendor/hello/`（禁写 `/data`） |

### 2.5 `check-vintf-all` 校验

| 报错特征 | 根因 | 修复 |
|---|---|---|
| `HAL ... does not exist` | fragment 没装进 /vendor 或 `PRODUCT_PACKAGES` 漏加 | 确认 cc_binary 进 `PRODUCT_PACKAGES` |
| `version mismatch` | XML `<version>` 与 `aidl_interface.versions` 不一致 | 两边对齐 |
| `Instance ... not declared` | 实例名/接口名拼错 | 清单、`service_contexts`、代码注册名三者一致 |
| `target-level too high` | 设备清单 version 高于 FCM | 降到 8（或 tree 支持 level） |

### 2.6 lazy vendor HAL（车载省电）

```rc
service vendorhello-hal /vendor/bin/hw/android.hardware.hello-service
    class hal
    interface aidl android.hardware.hello.IHello/default   # servicemanager 托管
    disabled                                                    # 不随 class hal 自动起
    oneshot                                                     # 退出不重启
    user system
    group system
    seclabel u:object_r:hal_hello_default_exec:s0
```

代码侧 `LazyServiceRegistrar::getInstance().registerService(service, "android.hardware.hello.IHello/default")`，引用归零自动关停。被「误杀」常见原因：client 没释放 binder 引用（忘了 `unlinkToDeath`），或 `forcePersist` 没设却有常驻需求。sepolicy 与普通 HAL 一致，lazy 不额外要权限。

### 2.7 GKI 边界：HAL ≠ 内核驱动

CAN 控制器驱动是**内核驱动**（GKI 下必须编成 `.ko`），不是 HAL。HAL 经 `/dev/canX` 字符设备 + `ioctl` 与内核交互。一次「车载 CAN 整车信号上抛」完整链路：**kernel CAN 驱动(.ko) → /dev/canX → vendor HAL(AIDL) → VINTF → framework Manager → App**。

---

## 第三部分：SELinux 策略（AIDL HAL）

### 3.1 AIDL vs HIDL 类型体系（不能混）

| 维度 | AIDL HAL（推荐） | HIDL HAL（弃用中） |
|---|---|---|
| Binder 设备 | `/dev/binder` | `/dev/hwbinder` |
| 注册中心 | `servicemanager` | `hwservicemanager` |
| 服务名标注 | `service_contexts` | `hwservice_contexts` |
| 服务类型属性 | `service_manager_type` | `hwservice_manager_type` |
| 包名记法 | 单点 `android.hardware.hello.IHello/default` | 双冒号 `android.hardware.hello::IHello/default` |
| 进程使用 binder | `binder_use(domain)` | `hwbinder_use(domain)` |
| 注册宏/allow | `binder_service()` + `allow ...:service_manager { add }` | `add_hwservice(dom, hwsvc)` |
| 跨进程调用 | `binder_call(client, server)` | `hwbinder_call` |

**一句话**：给 AIDL HAL 写 `hwservice_manager_type` / `::` / `hwbinder_use` / `add_hwservice` 中任意一个都是把两套体系搞混。

### 3.2 AIDL HAL 完整 sepolicy 文件集（vendor 分区）

`device/<oem>/<device>/sepolicy/vendor/hal_hello.te`：
```te
type hal_hello_default, domain;
type hal_hello_default_exec, exec_type, vendor_file_type, file_type;
init_daemon_domain(hal_hello_default)

hal_attribute(hello)                              # ★ 必须先于 hal_server_domain
hal_server_domain(hello, hal_hello_default)

binder_use(hal_hello_default)                     # /dev/binder
binder_service(hal_hello_default)
allow hal_hello_default hal_hello_service:service_manager { add find };

binder_call(hal_hello_default, system_server)
binder_call(system_server, hal_hello_default)

# HAL 私有数据目录（禁写 /data，必须 /data/vendor）
type hal_hello_data_file, file_type, data_file_type;
allow hal_hello_default hal_hello_data_file:dir create_dir_perms;
allow hal_hello_default hal_hello_data_file:file create_file_perms;
```

`device/<oem>/<device>/sepolicy/vendor/service.te`：
```te
type hal_hello_service, service_manager_type;
```

`device/<oem>/<device>/sepolicy/vendor/service_contexts`（单点记法）：
```text
android.hardware.hello.IHello/default    u:object_r:hal_hello_service:s0
```

`device/<oem>/<device>/sepolicy/vendor/file_contexts`：
```text
/vendor/bin/hw/android\.hardware\.hello-service    u:object_r:hal_hello_default_exec:s0
/data/vendor/hello(/.*)?                           u:object_r:hal_hello_data_file:s0
```

> **`hal_attribute(hello)` 必须写在 `hal_server_domain` 之前**；Cookbook 把宏误写成 `halserver_domain`（少下划线）会展开失败。

### 3.3 进程降权（rc）

```rc
service vendorhello-hal /vendor/bin/hw/android.hardware.hello-service
    class hal
    user system
    group system
    seclabel u:object_r:hal_hello_default_exec:s0
```
漏 `seclabel` 且 `file_contexts` 错标 → 进程以 `init` 域跑 → `binder_call` 不匹配 → 全部 avc denied。

### 3.4 关键宏真身

| 宏 | 展开要点 | 易错点 |
|---|---|---|
| `hal_attribute(name)` | 声明 `name`/`name_client`/`name_server` 三属性 + 入 `hal_type` | 必须先于 `hal_server_domain` |
| `hal_server_domain(name, domain)` | `typeattribute domain name_server` + `halserverdomain` | 参数1 是 `hal_attribute` 名，参数2 是进程域 |
| `binder_use(domain)` | `allow domain binder_device:chr_file {...}` | AIDL 对应物，替代 `hwbinder_use` |
| `binder_call(client, server)` | `allow client server:binder call` + `allow server client:binder transfer` + fd | 双向要写两条 |
| `init_daemon_domain(domain)` | `init` 域 transition + entrypoint | rc 进程名须匹配 exec 标签 |

### 3.5 构建校验

- **平台 HAL（改 `system/sepolicy/`）**：新类型/规则须同时写 `private/` 与 `prebuilts/api/34.0/private/`，否则 `sepolicy_freeze_test` 失败。
- **设备/厂商 HAL**：用 `*.ignore.cil` 的 `new_objects` 豁免新增类型：
```cil
; device/<oem>/<device>/sepolicy/vendor/<device>.ignore.cil
(new_objects
  (type hal_hello_default)
  (type hal_hello_default_exec)
  (type hal_hello_service)
  (type hal_hello_data_file)
)
```
> 是 `new_objects` 宏、写在 `.ignore.cil`，不是普通 `.cil` 的 `expandtypeattribute`。
```bash
source build/envsetup.sh && lunch <device>-eng
make sepolicy
make sepolicy_freeze_test
```

### 3.6 排障

```bash
adb shell dmesg | grep avc
adb shell dmesg | grep avc | audit2allow -p out/target/product/<device>/obj/ETC/sepolicy_intermediates/sepolicy
adb shell setenforce 0   # 临时确认是否 SELinux 问题（仅排障）
adb shell service list | grep hello
```
典型 avc：
- `tclass=service_manager perm=add` → 服务名类型没在 `service_contexts` 标对，或 `hal_hello.te` 缺 `allow ...:service_manager { add }`。
- `tclass=binder perm=call` → 缺 `binder_call(client, server)` 双向。
- servicemanager C++ 版 `Access::selinux_check_access(scon, tcon, "service_manager", perm)` 即对应 `service_manager` 类 avc。

---

## 第四部分：App 调用 HAL（末端）

### 4.1 推荐 Path A 四段式（见第一部分架构图）

`HelloService`（system_server，包裹 HAL）——**绝不在构造里 `waitForService(HAL)`**（会无限等、阻塞整机启动），改懒连接 + `linkToDeath`：

```java
public class HelloService extends SystemService {
    private final IHelloService.Stub mStub = new IHelloService.Stub() {
        @Override public String getHello() throws RemoteException {
            IHello hal = getHal();
            if (hal == null) throw new RemoteException("HAL not ready");
            return hal.getHello();
        }
    };
    private IHello mHal;
    private final IBinder.DeathRecipient mDeath = () -> { mHal = null; };
    @Override public void onStart() {
        publishBinderService(Context.HELLO_SERVICE, mStub);
    }
    private IHello getHal() {
        if (mHal == null) {
            IBinder b = ServiceManager.getService("android.hardware.hello.IHello/default");
            if (b != null) {
                mHal = IHello.Stub.asInterface(b);
                try { mHal.asBinder().linkToDeath(mDeath, 0); } catch (RemoteException ignored) {}
            }
        }
        return mHal;
    }
}
```
在 `SystemServer.startOtherServices()` 调 `mSystemServiceManager.startService(HelloService.class)`。

`HelloManager`（@hide 门面）：
```java
public class HelloManager {
    private final IHelloService mService;
    public HelloManager(IHelloService service) { mService = service; }
    @RequiresPermission(android.Manifest.permission.HELLO_ACCESS)
    public String getHello() {
        try { return mService.getHello(); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }
}
```
在 `SystemServiceRegistry` 注册；`Context.HELLO_SERVICE`（`"hello"`）定义在 `Context.java`（@hide）。

App（系统/特权，编进 tree，`platform_apis:true`）：
```java
HelloManager mgr = (HelloManager) getSystemService(Context.HELLO_SERVICE);
String s = mgr.getHello();
```

### 4.2 权限模型（收口暴露面）

| 元素 | 定义 |
|---|---|
| `HELLO_ACCESS` 权限 | `protectionLevel="signature\|privileged"` |
| `hello_service` 类型 | `system_api_service`（非 `app_api_service`，避免任意 app 直连） |
| App 声明 | `<uses-permission android:name="android.permission.HELLO_ACCESS"/>` + `privileged:true` |
| service 方法校验 | `enforceCallingPermission(HELLO_ACCESS)` 兜底 |

### 4.3 Hidden API

自定义 Manager 与 `Context.HELLO_SERVICE` 是 `@hide`，三类解法：①编进 tree 的系统/特权 App（`platform_apis:true`，最干净）②`-greylist-max-o.txt` 灰名单 ③`VMRuntime.setHiddenApiExemptions`/`@UnsupportedAppUsage`（调试用，量产有合规风险）。

### 4.4 Path B（App 直连 HAL，量产不推荐）

仅 system/privileged 域 + 明确 sepolicy；失去 framework 管控，且受 Treble `neverallow appdomain hal_<x>_default:binder` 拦。需 `allow <app_domain> hal_hello_service:service_manager find` + `binder_call(<app_domain>, hal_hello_default)`。

### 4.5 排障表

| 现象 | 根因层 | 排查 |
|---|---|---|
| `getSystemService` 返回 null | 服务没注册 / 常量错 | `service list \| grep hello`；查 `startOtherServices` |
| `SecurityException` | 权限闸门 | App 是否 `privileged` + 声明 `HELLO_ACCESS` |
| `RemoteException: HAL not ready` | `getHal()` 拿不到代理 | `service list \| grep android.hardware.hello`；查 HAL 进程/VINTF |
| `DeadObjectException` | HAL 崩溃/重启 | 查 HAL 日志 + `linkToDeath` 重连 |
| avc `tclass=binder` | 缺 `binder_call` | `dmesg \| grep avc` 定位 scontext/tcontext |
| avc `service_manager perm=find` | 类型标错/缺 find | 查 `*_service` + `service_contexts` |

---

## 第五部分：标准 AOSP HAL 特例（以 Vibrator 为例）

Vibrator 是 AOSP 内置标准 HAL，属性/服务类型/域早已定义在 `system/sepolicy`；厂商只**追加自己的域并复用属性**，不重写。

- 服务名 `android.hardware.vibrator.IVibratorManager/default`（AIDL，Android 12+ 升到 `IVibratorManager`）。`IVibrator` 由 manager 的 `getVibrator(id)` 在事务内返回，不需独立 `service_contexts` 条目。
- AOSP 预定义：`hal_attribute(vibrator)`、`type hal_vibrator_service, service_manager_type;`、`hal_server_domain(vibrator, hal_vibrator_default)`、设备类型 `vibrator_device`/`sysfs_vibrator`/`input_device`。
- **厂商替换默认实现**：新域 `hal_vibrator_oem_default`（不碰 `hal_vibrator_default`，避免冲突）+ `hal_server_domain(vibrator, hal_vibrator_oem_default)` + 注册到同一个 `hal_vibrator_service` 服务名 + 设备 mk 移除 `android.hardware.vibrator-default` 防抢名。framework 的 `VibratorManagerService` 已是 `hal_client_domain(system_server, vibrator)`，**零改动连上**，无需为客户端加 sepolicy。
- 校验：`make sepolicy` + `check-vintf-all`；`service list | grep vibrator` 应为 `IVibratorManager/default`。

---

## 第六部分：速查总表

### 6.1 vendor AIDL HAL 最小正确集

| 层 | 产物 | 关键属性/路径 |
|---|---|---|
| 接口 | `aidl_interface` | `stability:"vintf"`、`versions:["1"]`、`vendor_available:true`、三 backend |
| 进程 | `cc_binary` | `vendor:true`、`relative_install_path:"hw"`、`vintf_fragments`、`init_rc` |
| 清单 | `*.xml` | `format="aidl"`、`<version>1</version>`、实例名与代码一致 |
| 启动 | `*.rc` | `class hal`、`user/group system`、`seclabel`、`interface aidl`（lazy） |
| SELinux | `hal_<name>.te` 等 | `service_manager_type`、`binder_use`、`binder_call` 双向、降权 |
| 打包 | 设备 mk | `PRODUCT_PACKAGES += <cc_binary>` |
| 校验 | `check-vintf-all` | version 对齐、实例名一致、fragment 进 /vendor |
| 数据 | `/data/vendor/<hal>/` | 禁写 `/data` |

### 6.2 AIDL HAL sepolicy 最小正确集

| 文件 | 必须内容 |
|---|---|
| `hal_<name>.te` | `domain`+`exec` 类型、`init_daemon_domain`、`hal_attribute`、`hal_server_domain`、`binder_use`、`allow ...:service_manager { add }`、`binder_call` 双向 |
| `service.te` | `type hal_<name>_service, service_manager_type;` |
| `service_contexts` | `android.hardware.<name>.I<Ifc>/default  u:object_r:hal_<name>_service:s0`（单点） |
| `file_contexts` | `/vendor/bin/hw/...-service  u:object_r:hal_<name>_default_exec:s0` |
| rc | `user system`/`group system`/`seclabel` |
| 客户端 te | `allow <client> hal_<name>_service:service_manager find;` |
| 构建 | 厂商 HAL 用 `*.ignore.cil` 的 `new_objects`；平台 HAL 同步 `prebuilts/api/34.0/` |

### 6.3 常见错误（务必避开）

1. 🔴 把 AIDL HAL 配成 HIDL sepolicy（`hwservice_manager_type`/`::`/`hwbinder_use`/`add_hwservice`）。
2. 🔴 `HelloService` 构造里 `waitForService(HAL)` 无限等，阻塞整机启动 → 改懒连接 + `linkToDeath`。
3. 🔴 VINTF `version` / 实例名 / `service_contexts` / 代码注册名四者不对齐 → `isDeclared()` 失败。
4. 🟠 `hal_server_domain` 前未 `hal_attribute`；宏名误写 `halserver_domain`。
5. 🟠 vendor HAL 走 `/dev/vndbinder` 而非 `/dev/binder` → NDK 后端规避。
6. 🟡 `hello_service` 用 `app_api_service` 暴露面过大 → 收紧 `system_api_service` + permission。
7. 🟡 漏 `*.ignore.cil` 的 `new_objects` 豁免 → `sepolicy_freeze_test` 失败。
8. 🟡 升级接口后忘 `versions` bump + 重新 `freeze`（`hash_gen` 占位符 `0000...` 必挂）。

---

## 第七部分：与全系列文档衔接

- **servicemanager C++ 版**：`checkService`/`getService`/`waitForService` 依赖 VINTF `isDeclared` + 按需 `tryStartService`；能否注册成功的闸门是本文 `service_contexts` + `service_manager_type` + `Access::selinux_check_access`。三者构成「HAL 起不来/取不到」的完整排查面。
- **《AOSP14 添加 HAL 与系统服务文档》**：其 `hello` HAL 的 `aidl_interface`+`vintf_fragments`+`PRODUCT_PACKAGES`+sepolicy 写法与本文一致（正确范式）；其「构造里 waitForService 阻塞 boot」🔴 问题见第四部分修法。
- **《Android System Development Cookbook》§6.1**：把 AIDL HAL 写成 HIDL sepolicy 属自相矛盾，以本文第三部分为准；§3 CAN 属「内核驱动」层（2.7），不在 HAL 范畴；§6.2 hiddenapi 三法见 4.3。
- **dumpsys**：HAL 注册后 `adb shell dumpsys <hal_service>` 可观察状态。
