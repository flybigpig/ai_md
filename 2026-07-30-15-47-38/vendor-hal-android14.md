# Vendor 分区 HAL 全链路（Android 14 / API 34）

> 承接 `hal-selinux-policy-android14.md`。SELinux 只是 HAL 落地的一环；本文覆盖 **vendor 分区 HAL 的其余全部链路**：VINTF 设备清单声明、Soong 构建、分区与路径、Treble 跨分区坑（尤其 `/dev/binder` vs `/dev/vndbinder`）、`check-vintf-all` 校验、lazy HAL、以及 GKI 边界。所有结论以 `android-14.0.0_rXX` 为准。

---

## 一、结论前置

1. **HAL 放进 vendor 分区是 Treble 隔离的硬性要求**：HAL 实现必须与 `/system` 解耦，OTA 时 system 可独立升级而 vendor HAL 不变。AIDL HAL 通过 VINTF 设备清单声明，运行在 `/vendor` 的独立进程里。
2. 一份 vendor HAL 涉及 **4 类产物**：`aidl_interface` 模块（接口+ stub）、`cc_binary`（服务进程）、`vintf_fragments`（XML 清单片段）、`init_rc`（启动脚本）。前一篇的 sepolicy 是第五类。
3. **最容易踩的跨分区坑**：AIDL HAL 注册到 **framework 的 `servicemanager`（`/dev/binder`）**，不是 `/dev/vndbinder`。vendor 进程默认用 vndbinder，做 AIDL HAL 必须显式走 binder——否则 servicemanager 取不到、`isDeclared()` 返回 false。
4. `check-vintf-all` 在构建时强制校验「声明了的 HAL 必须有实现 + 版本匹配」，是 vendor HAL 能否编过的第一道闸。

---

## 二、为什么必须在 vendor 分区（Treble 本质）

```
        OTA 可独立升级            OTA 可独立升级
/system  ───────────────         /vendor ───────────────
  framework (system_server)  ⇄ Binder ⇄  HAL 进程 (/vendor/bin/hw/...)
  (android.* 接口调用)            (android.hardware.* AIDL 实现)
        │                              │
        └──── VINTF 契约（device manifest）────┘
              定义：哪些 HAL、什么版本、实例名
```

- **system 与 vendor 通过 VINTF 契约解耦**：framework 只依赖清单里声明的 HAL 接口/版本，不依赖具体实现。
- HAL 实现在 `/vendor`，由芯片/ODM 提供；framework 在 `/system`，由 Google/OEM 提供。两边各自 OTA。
- 跨分区访问（文件、binder）受 **Treble neverallow** 约束：vendor 域不能随便读 system 文件、不能写 `/data` 根。

---

## 三、VINTF 设备清单声明（AIDL 格式）

HAL 必须在设备清单里声明，servicemanager 的 `isDeclared()`、按需拉起 `tryStartService()`、以及 `check-vintf-all` 都依赖它。

**文件位置（三选一，按设备习惯）**：
- `device/<oem>/<device>/manifest.xml`（整设备一个）
- `device/<oem>/<device>/manifest/<hal>.xml`（片段，推荐，模块化）
- `vendor/<oem>/<device>/etc/vintf/manifest/<hal>.xml`（运行时实际落点，由 `vintf_fragments` 自动安装）

**AIDL HAL 片段（`android.hardware.hello.xml`）**：
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
- **`format="aidl"`**（不是 `hidl`），且 `<version>` 必须与 `aidl_interface` 的 `versions: ["1"]` **完全一致**。
- `version="8.0"` 是 FCM（Framework Compatibility Matrix）level。**Android 14 的 Shipping FCM 为 level 8**（部分 tree 用 9，以 `check-vintf-all` 报错为准）。`target-level` 同理。
- `<instance>default</instance>` 对应你服务注册名 `android.hardware.hello.IHello/default` —— **必须与 `service_contexts`、代码里注册的实例名一字不差**，否则 `isDeclared()` 返回 false、`getService` 永远拿不到（这是新增 HAL「服务起来了却取不到」的头号根因）。
- 多实例就写多个 `<instance>`。

> 与上一篇衔接：本 XML 的 `name`+`instance` 拼出的 `android.hardware.hello.IHello/default`，正是 `service_contexts` 里打标、`Access::canAdd` 校验的服务名。三者必须对齐。

---

## 四、Soong 构建（关键：`vendor: true` + `stability: "vintf"`）

**`Android.bp`（HAL 接口 + 服务）**：
```bp
// 1) AIDL 接口定义
aidl_interface {
    name: "android.hardware.hello",
    vendor_available: true,                 // 允许 vendor 进程使用
    stability: "vintf",                     // HAL 必需：保证接口稳定性/版本化
    srcs: ["aidl/android/hardware/hello/IHello.aidl"],
    versions: ["1"],                        // 必须与 VINTF 清单 version 一致
    gen_trace: true,                        // 生成 trace 代码（atrace/perfetto）
    backend: {
        cpp:  { enabled: true },
        ndk:  { enabled: true },            // HAL server 用 NDK 后端
        java: { enabled: true },            // framework/system_app 侧用 java 后端
    },
}

// 2) HAL 服务进程（落地到 vendor）
cc_binary {
    name: "android.hardware.hello-service",
    vendor: true,                           // ★ 关键：编进 /vendor 分区
    init_rc: ["android.hardware.hello-service.rc"],
    vintf_fragments: ["android.hardware.hello.xml"],  // ★ 自动安装清单到 /vendor/etc/vintf/manifest/
    relative_install_path: "hw",            // 装到 /vendor/bin/hw/
    shared_libs: [
        "libbinder_ndk",                    // AIDL HAL server 用 NDK binder
        "android.hardware.hello-ndk",       // 生成的 NDK 桩
        "liblog",
        "libutils",
    ],
    srcs: ["service.cpp"],
}
```

要点：
- **`stability: "vintf"`** 是 HAL 的硬要求，缺了 `check-vintf` 会报接口不稳定。普通 app AIDL 不需要。
- **`vendor: true`** 决定产物进 `/vendor`；若同时要 system 也能用，用 `vendor_available: true` + 在 cc_binary 上区分。车载 HAL 一般用 `vendor: true` 即可。
- **`vintf_fragments`** 让 Soong 把 XML 自动装到 `/vendor/etc/vintf/manifest/android.hardware.hello.xml`，无需手写 `PRODUCT_COPY_FILES`。
- **`relative_install_path: "hw"`** → `/vendor/bin/hw/android.hardware.hello-service`，与 `file_contexts` 路径、`init_rc` 的 `service` 路径三者一致。
- HAL server 用 **NDK 后端 + `libbinder_ndk`**（不是 framework 的 `libbinder` C++），进程跑在 vendor，符合 Treble。

**`PRODUCT_PACKAGES`（设备 mk）**：
```mk
PRODUCT_PACKAGES += android.hardware.hello-service
```
> 只需加 `cc_binary`！`aidl_interface` 桩和 `vintf_fragments` 由依赖自动带入（这也是《AOSP14 添加 HAL 文档》那轮强调的 gotcha：`PRODUCT_PACKAGES` 只加 cc_binary，vintf fragment 自动打包）。

---

## 五、分区与运行时路径总览

| 产物 | 构建属性 | 运行时路径 | 说明 |
|---|---|---|---|
| HAL 可执行体 | `vendor:true` + `relative_install_path:"hw"` | `/vendor/bin/hw/android.hardware.hello-service` | init 拉起 |
| init rc | `init_rc` | `/vendor/etc/init/android.hardware.hello-service.rc` | `class hal` |
| VINTF 清单 | `vintf_fragments` | `/vendor/etc/vintf/manifest/android.hardware.hello.xml` | `check-vintf-all` 读取 |
| AIDL 桩(.so) | NDK 后端 | `/vendor/lib[64]/android.hardware.hello.so` 或 binder 懒加载 | —— |
| sepolicy exec 标签 | `file_contexts` | 同上可执行体路径 | 上一篇已讲 |
| 数据目录 | —— | `/data/vendor/hello/` | 上一篇已讲，禁写 `/data` |

---

## 六、跨分区头号坑：`/dev/binder` vs `/dev/vndbinder`

这是 vendor HAL 最容易翻车的地方，必须讲清：

```
vendor 进程默认 binder 上下文：
  /dev/vndbinder  →  vndservicemanager（vendor 域服务，如 camera/audio 老 HAL）
  /dev/binder     →  servicemanager（framework 域，AIDL HAL 注册这里！）
  /dev/hwbinder   →  hwservicemanager（HIDL 遗留）
```

- **AIDL HAL 注册到 framework 的 `servicemanager`（`/dev/binder`）**，因为 VINTF 声明的 HAL 不属于 vendor 域私有服务。
- 但 HAL 进程是 vendor 进程，默认只打开 `/dev/vndbinder`。**必须显式切换到 `/dev/binder`**，否则 `defaultServiceManager()` 拿到的是 vndservicemanager，注册/查找全错位。
- NDK 侧做法：`ABinderProcess_setThreadPoolMaxThreadCount` 之前，用 `ProcessState::initWithDriver("/dev/binder")` 或 NDK 的 `binder` 默认即 `/dev/binder`（NDK `libbinder_ndk` 默认连 framework servicemanager）。**推荐用 NDK 后端，默认就走 `/dev/binder`，少踩坑。**
- 若用 C++ framework `libbinder`，需在进程里 `ProcessState::initWithDriver("/dev/binder")` 并确认 sepolicy 有 `binder_use`（上一篇 §3.1 已授）。
- 对应的 sepolicy：vendor HAL 域访问 `/dev/binder` 需要 `binder_use`（不是 `vndbinder_use`）。

> 一句话：**AIDL HAL server 用 NDK 后端，默认连 `/dev/binder` 的 servicemanager；别让它落到 vndbinder。**

---

## 七、init rc（vendor HAL 启动）

**`android.hardware.hello-service.rc`**：
```rc
service vendorhello-hal /vendor/bin/hw/android.hardware.hello-service
    class hal
    user system
    group system
    seclabel u:object_r:hal_hello_default_exec:s0
    # 车载省电：lazy HAL（见第九节）放开下面三行
    # interface aidl android.hardware.hello.IHello/default
    # disabled
    # oneshot
```

- `class hal`：HAL 类，init 在 `hal` 阶段按依赖启动。
- `user system`/`group system`：降权（**root 进程会触发 neverallow + 安全隐患**，车载必须降）。
- `seclabel`：显式指定 exec 标签，确保域切换正确（与上篇 `file_contexts` 一致）。
- rc 文件名/路径落在 `/vendor/etc/init/`，由 `init_rc` 属性自动安装。

---

## 八、`check-vintf-all` 校验

构建时（或显式 `m check-vintf-all`）会：
1. 汇总所有 `vintf_fragments` + 设备 `manifest.xml` 得到**设备清单**；
2. 与 framework 的 **FCM（level 8）** 比对，检查声明的 HAL 版本是否被 framework 要求且存在；
3. 检查每个 `<hal>` 声明都有对应实现（so 存在 + 实例可注册）。

**常见失败与修复**：
| 报错特征 | 根因 | 修复 |
|---|---|---|
| `HAL ... does not exist` / `Cannot find ...` | `vintf_fragments` 没装进 /vendor 或 `PRODUCT_PACKAGES` 漏加 | 确认 cc_binary 进了 `PRODUCT_PACKAGES`，fragment 路径对 |
| `version mismatch` | XML `<version>` 与 `aidl_interface.versions` 不一致 | 两边对齐到同一个数字 |
| `Instance ... not declared` | 实例名/接口名拼错，或 `service_contexts` 不匹配 | 清单、`service_contexts`、代码注册名三者一致 |
| `target-level too high` | 设备清单 `version` 高于 framework FCM | 降到 8（或你 tree 支持的 level） |

---

## 九、lazy vendor HAL（车载省电必做）

普通 HAL 进程一直常驻占内存。车载多 HAL 场景，用 lazy HAL 让 servicemanager 在引用归零时自动关停：

**rc 改造**：
```rc
service vendorhello-hal /vendor/bin/hw/android.hardware.hello-service
    class hal
    interface aidl android.hardware.hello.IHello/default   # ★ 声明由 servicemanager 托管
    disabled                                                    # ★ 不随 class hal 自动起
    oneshot                                                     # ★ 退出不重启
    user system
    group system
    seclabel u:object_r:hal_hello_default_exec:s0
```

**代码侧（`service.cpp`）**：
```cpp
#include <binder/LazyServiceRegistrar.h>
// 注册完服务后：
auto registrar = android::binder::LazyServiceRegistrar::getInstance();
registrar.registerService(service, "android.hardware.hello.IHello/default");
// 默认：所有 client 引用归零 → servicemanager 回调 onClients(false) → 进程退出
// 若需常驻（调试/有后台任务）：registrar.setNoShutdown(); 或 rc 去掉 disabled/oneshot
```

要点：
- `interface aidl <name>` 让 servicemanager 的 `tryStartService()` 通过 `ctl.interface_start` 属性按需拉起（上篇 servicemanager C++ 版讲过）；客户端 `getService` 时若服务没起，servicemanager 自动 `SetProperty("ctl.interface_start", "aidl/<name>")` 拉起。
- **lazy HAL 被"误杀"的常见原因**：client 没释放 binder 引用（忘了 `unlinkToDeath`/持引用不释放），或 `forcePersist` 没设但有常驻需求。排查看 servicemanager 日志 + `dumpsys <hal>`。
- sepolicy 与普通 HAL 一致（上篇 §3.1），lazy 不额外要权限；唯 rc 用 `disabled`+`oneshot`+`interface aidl`。

---

## 十、GKI 边界：HAL ≠ 内核驱动

容易把「HAL」和「内核驱动」混为一谈。车载常见混淆点：

- **CAN 控制器驱动（`vehiclebody` 要读的 CAN）是内核驱动**，不是 HAL。它在 `drivers/net/can/` + DTS，GKI 下**必须编成可加载模块 `.ko`**，不能直接改 GKI 内置（cookbook §3 正确强调了这点）。
- HAL（userspace `/vendor/bin/hw`）通过某种方式与内核交互：字符设备（`/dev/canX`）+ `ioctl`、或内核暴露的 `netlink`/`socketcan`。HAL 的 sepolicy 需要 `allow hal_xxx_default can_device:chr_file {...}`（上篇 §3.1 注掉那段）。
- 所以一次「车载 CAN 整车信号上抛」完整链路是：**kernel CAN 驱动(.ko) → /dev/canX → vendor HAL(AIDL) → VINTF → framework Manager → App**。HAL 层和驱动层在两套完全不同的构建/签名/OEM 责任里。

---

## 十一、速查表（vendor AIDL HAL 最小正确集）

| 层 | 产物 | 关键属性/路径 |
|---|---|---|
| 接口 | `aidl_interface` | `stability:"vintf"`、`versions:["1"]`、`vendor_available:true`、三 backend |
| 进程 | `cc_binary` | `vendor:true`、`relative_install_path:"hw"`、`vintf_fragments`、`init_rc` |
| 清单 | `*.xml` | `format="aidl"`、`<version>1</version>`、实例名与代码一致 |
| 启动 | `*.rc` | `class hal`、`user/group system`、`seclabel`、`/vendor/etc/init/` |
| SELinux | `hal_<name>.te` 等 | 上篇全部（`service_manager_type`、`binder_use`、降权） |
| 打包 | 设备 mk | `PRODUCT_PACKAGES += <cc_binary>`（只加这个） |
| 校验 | `check-vintf-all` | version 对齐、实例名一致、fragment 装进 /vendor |
| 省电 | lazy | rc `interface aidl`+`disabled`+`oneshot` + `LazyServiceRegistrar` |
| 数据 | `/data/vendor/<hal>/` | 禁写 `/data`，自定 `vendor_data_file` 类型 |

---

## 十二、与全系列文档衔接

- **`hal-selinux-policy-android14.md`**：本文的「SELinux 层」全部在那里（类型体系、4 文件集、§6.1 改写、排障）。
- **《AOSP14 添加 HAL 与系统服务文档》**：其 `hello` HAL 的 `aidl_interface`+`vintf_fragments`+`PRODUCT_PACKAGES` 写法与本文一致，是正确的 vendor HAL 范式；其「构造里 waitForService 阻塞 boot」🔴 问题仍待修（改成上篇建议的懒连接/有界超时）。
- **《Android System Development Cookbook》§6.1**：本文 VINTF 声明 + rc 是正解，对照其 HIDL 错误 sepolicy，两者需一起改；§3 CAN 是第十节讲的「内核驱动」层，不在 HAL 范畴。
- **servicemanager C++ 版（第二轮）**：`isDeclared()` 读本文的 VINTF 清单，`tryStartService()` 拉起本文的 lazy HAL，`Access::selinux_check_access` 守上篇的 `service_manager_type` —— 三者共同构成「HAL 起不来/取不到」的闭环排查面。
