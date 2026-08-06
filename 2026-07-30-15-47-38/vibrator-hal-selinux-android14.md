# Vibrator HAL SELinux 策略（Android 14 / API 34）

> 与前面自定义的 `hello` HAL 不同，**Vibrator 是 AOSP 内置标准 HAL**，其 `hal_vibrator` 属性、`hal_vibrator_service` 类型、`hal_vibrator_default` 域早就定义在 `system/sepolicy` 里。本文讲：AOSP 既有的 vibrator sepolicy 长什么样、Vibrator 特有的设备节点访问、以及**厂商如何在不破坏既有属性/客户端关系的前提下替换或扩展 vibrator HAL**。承接 `hal-selinux-policy-android14.md`（通用 AIDL HAL 范式）。以 `android-14.0.0_rXX` 为准。

---

## 一、结论前置

1. **不用重新声明 `hal_vibrator` 属性**：它已在 `system/sepolicy/public/attributes` 预定义（`hal_vibrator` / `hal_vibrator_client` / `hal_vibrator_server`）。厂商只**追加自己的域**并复用该属性。
2. **服务名是 `android.hardware.vibrator.IVibratorManager/default`**（AIDL，Android 12+ 从 `IVibrator` 升级到 `IVibratorManager`）。`IVibratorManager` 是 VINTF 注册实体；`IVibrator`（单个振动器）由 manager 的 `getVibrator(id)` 在事务内返回，**不需要自己的 `service_contexts` 条目**。
3. **Vibrator 特有的设备访问**：`vibrator_device`（`/dev/vibrator*`）、`sysfs_vibrator`（`/sys/class/vibrator*`）、`input_device`（`/dev/input/event*` 用于振动与音频同步的 force-feedback），以及校准数据目录（persist 或 `/data/vendor/vibrator`）。
4. **厂商替换默认实现**：把 AOSP 的 `android.hardware.vibrator-default` 从 `PRODUCT_PACKAGES` 移除，改挂自己的域到**同一个服务名**，framework 的 `VibratorManagerService`（已是 `hal_client_domain(system_server, vibrator)`）无需改动即可连上。

---

## 二、AOSP 标准 `hal_vibrator` sepolicy（真实出处）

这些在 AOSP 里已存在，先认识它们，厂商策略是在此之上**追加**而非重写：

**`system/sepolicy/public/attributes`**（节选）
```te
hal_attribute(vibrator)   # 展开出 hal_vibrator / hal_vibrator_client / hal_vibrator_server
```

**`system/sepolicy/public/service.te`**
```te
type hal_vibrator_service, service_manager_type;
```

**`system/sepolicy/private/service_contexts`**
```text
android.hardware.vibrator.IVibratorManager/default    u:object_r:hal_vibrator_service:s0
```

**`system/sepolicy/private/hal_vibrator.te`**（AOSP 默认实现）
```te
type hal_vibrator_default, domain;
hal_server_domain(hal_vibrator, hal_vibrator_default)

type hal_vibrator_default_exec, exec_type, file_type, system_file_type;
init_daemon_domain(hal_vibrator_default)

# AIDL HAL 走 /dev/binder
binder_use(hal_vibrator_default)
allow hal_vibrator_default hal_vibrator_service:service_manager { add find };
binder_call(hal_vibrator_default, system_server)
binder_call(system_server, hal_vibrator_default)

# ---- Vibrator 特有设备访问（AOSP 默认实现即包含）----
allow hal_vibrator_default vibrator_device:chr_file rw_file_perms;   # /dev/vibrator*
allow hal_vibrator_default sysfs_vibrator:file rw_file_perms;        # /sys/class/vibrator*
allow hal_vibrator_default input_device:chr_file { read write open ioctl };  # FF 同步
```

**`system/sepolicy/private/file_contexts`**
```text
/system/bin/hw/android\.hardware\.vibrator-default    u:object_r:hal_vibrator_default_exec:s0
```

> 关键认知：上面这套是 **AOSP 默认实现（`hal_vibrator_default` 域，可执行体在 `/system/bin/hw/`）**。你的厂商 HAL 如果要替换它，就不该再定义 `hal_vibrator_default`——它会和 AOSP 冲突。正确做法是 **新的域 + 复用 `vibrator` 属性 + 同一个 `hal_vibrator_service` 服务名**（见第四节）。

---

## 三、服务名与 VINTF 声明

**VINTF 设备清单片段（`android.hardware.vibrator.xml`）**：
```xml
<manifest version="8.0" type="device">
    <hal format="aidl">
        <name>android.hardware.vibrator</name>
        <version>2</version>   <!-- 以你 tree 的 hardware/interfaces/vibrator/aidl 当前 versions 为准（通常 2，部分 3） -->
        <interface>
            <name>IVibratorManager</name>
            <instance>default</instance>
        </interface>
    </hal>
</manifest>
```
- `format="aidl"` + `IVibratorManager` + 实例 `default` —— 与 `service_contexts` 完全对应。
- `<version>` 必须匹配 `hardware/interfaces/vibrator/aidl` 里 `aidl_interface` 的 `versions`（AOSP 随版本演进，Android 14 多为 2；**改前 `cat` 一下真树确认**）。
- 若只替换默认实现，AOSP 已自带该 VINTF 片段（`hardware/interfaces/vibrator/aidl` 的 `vintf_fragments`），你无需重复加，只要保证你的实现注册同名服务即可。

---

## 四、厂商自定义 Vibrator HAL 的 SELinux（推荐写法）

假设你做一个 vendor 分区实现，替换 AOSP 默认、但让 framework 无感连接：

**`device/<oem>/<device>/sepolicy/vendor/hal_vibrator_oem.te`**
```te
# ★ 复用 AOSP 已定义的 hal_vibrator 属性，不重定义
type hal_vibrator_oem_default, domain;
type hal_vibrator_oem_default_exec, exec_type, vendor_file_type, file_type;
init_daemon_domain(hal_vibrator_oem_default)

# 复用 vibrator 属性，把本域标记为服务端
hal_server_domain(vibrator, hal_vibrator_oem_default)

# AIDL binder（/dev/binder）
binder_use(hal_vibrator_oem_default)
# 注册到同一个 AOSP 已声明的 hal_vibrator_service（服务名不变）
allow hal_vibrator_oem_default hal_vibrator_service:service_manager { add find };
binder_call(hal_vibrator_oem_default, system_server)
binder_call(system_server, hal_vibrator_oem_default)

# ---- Vibrator 设备访问（按需取用）----
allow hal_vibrator_oem_default vibrator_device:chr_file rw_file_perms;
allow hal_vibrator_oem_default sysfs_vibrator:file rw_file_perms;
allow hal_vibrator_oem_default input_device:chr_file { read write open ioctl };

# ---- 校准数据目录（persist 或 /data/vendor）----
type hal_vibrator_oem_data_file, file_type, data_file_type;
allow hal_vibrator_oem_default hal_vibrator_oem_data_file:dir create_dir_perms;
allow hal_vibrator_oem_default hal_vibrator_oem_data_file:file create_file_perms;
```

**`device/<oem>/<device>/sepolicy/vendor/file_contexts`**
```text
/vendor/bin/hw/android\.hardware\.vibrator-oem    u:object_r:hal_vibrator_oem_default_exec:s0
/data/vendor/vibrator(/.*)?                      u:object_r:hal_vibrator_oem_data_file:s0
```

**关键点**：
- **不重定义 `hal_vibrator` / `hal_vibrator_service`**——它们来自 AOSP public，重定义会编译冲突。你只新增 `hal_vibrator_oem_default` 域 + `hal_server_domain(vibrator, ...)` 复用属性。
- **注册同名服务** `android.hardware.vibrator.IVibratorManager/default`（经 `hal_vibrator_service` 类型）。framework 的 `VibratorManagerService` 通过 `hal_client_domain(system_server, vibrator)` 已经是客户端，`binder_call(system_server, hal_vibrator_oem_default)` 让连接成立，system_server 代码零改动。
- **移除 AOSP 默认实现**：在设备 mk 里 `PRODUCT_PACKAGES` 去掉 `android.hardware.vibrator-default`（否则两个进程抢同一服务名，先注册者胜、后注册 `add` 被拒）。

---

## 五、客户端侧：system_server 已是 client

framework 的 `VibratorManagerService`（在 `system_server`）通过 AOSP 既有规则 `hal_client_domain(system_server, vibrator)` 获得对 `hal_vibrator` 服务端的 `binder_call` 与 `find`。**你一般无需为客户端加任何 sepolicy**——这是 Vibrator 相比自定义 HAL 省事的地方（自定义 HAL 要手动 `allow system_server hal_hello_service:service_manager find`）。

如果你的 App 想**直连** vibrator HAL（极少见，且绕过 framework 管控不推荐），才需要：
```te
allow <app_domain> hal_vibrator_service:service_manager find;
binder_call(<app_domain>, hal_vibrator_oem_default);
```
且 App 必须是 system/privileged 域（普通 app 被 `neverallow` 拦）。

---

## 六、设备节点类型来源（别自己造类型）

| 节点 | sepolicy 类型 | 声明位置 |
|---|---|---|
| `/dev/vibrator[0-9]*` | `vibrator_device` | `system/sepolicy/public/device.te`（`type vibrator_device, dev_type;`） |
| `/sys/class/vibrator*` `/sys/devices/.../vibrator` | `sysfs_vibrator` | `system/sepolicy/public/file.te` + `file_contexts`（具体路径按 kernel 暴露） |
| `/dev/input/event*` | `input_device` | `system/sepolicy/public/device.te` |

> 若你的硬件把振动器挂在非标准 sysfs 路径（如 `/sys/devices/platform/.../vib`），需要**新增 `sysfs_vibrator`-风格的自定义类型**并在 `file_contexts` 标注该路径，再 `allow` 你的域访问——不要直接给 `sysfs` 通配类型加 `rw`（会触发 `neverallow`）。

---

## 七、排障（Vibrator 场景特有）

```bash
# 确认服务注册
adb shell service list | grep vibrator
# 应为 android.hardware.vibrator.IVibratorManager/default

# 抓 vibrator 相关 avc
adb shell dmesg | grep avc | grep -i vibrator

# 典型拒绝与含义
# avc: denied { add } tcontext=u:object_r:hal_vibrator_service ... tclass=service_manager
#   → 两个实现抢同名服务（AOSP default 没移除），或你的域没 allow add
# avc: denied { read write } tcontext=u:object_r:vibrator_device ... tclass=chr_file
#   → 漏 allow hal_vibrator_oem_default vibrator_device:chr_file rw_file_perms;
# avc: denied { read } tcontext=u:object_r:sysfs_vibrator ... tclass=file
#   → 漏 sysfs 访问，或路径没标到 sysfs_vibrator
# avc: denied { call } tclass=binder （scontext=system_server, tcontext=hal_vibrator_oem_default）
#   → 漏 binder_call(system_server, hal_vibrator_oem_default)
```

排查流程：先用 `dmesg | grep avc` 拿 `scontext`/`tcontext`/`tclass`/`perm`，对应上面三类（服务名、设备节点、binder），补对应 `allow`。再用 `audit2allow -p <built sepolicy>` 出草稿，但**类型名必须按本文（如 `hal_vibrator_service` 而非 `hal_vibrator_hwservice`）**——再次印证 AIDL/HIDL 类型体系不能混。

---

## 八、速查表（Vibrator HAL SELinux）

| 项 | 值 |
|---|---|
| AOSP 属性（已存在，复用） | `hal_vibrator` / `hal_vibrator_service`（`service_manager_type`） |
| 服务名 | `android.hardware.vibrator.IVibratorManager/default` |
| VINTF 接口 | `IVibratorManager`，实例 `default`，`format="aidl"`，version 与 tree 对齐 |
| 厂商域命名 | `hal_vibrator_oem_default`（不碰 `hal_vibrator_default`） |
| 复用属性 | `hal_server_domain(vibrator, hal_vibrator_oem_default)` |
| 设备类型 | `vibrator_device` / `sysfs_vibrator` / `input_device`（均来自 AOSP public） |
| 客户端 | system_server 已 `hal_client_domain(system_server, vibrator)`，无需改 |
| 替换默认 | 移除 `android.hardware.vibrator-default` 防抢名 |
| 校验 | `make sepolicy` + `check-vintf-all` |

---

## 九、与全系列衔接

- **`hal-selinux-policy-android14.md`**：通用 AIDL HAL sepolicy 范式（四文件集、`binder_use`/`binder_call`/`service_manager_type`、宏真身）在此。Vibrator 是其实例化之一，差异只在：**属性/服务类型已由 AOSP 预定义，厂商只追加域**。
- **`vendor-hal-android14.md`**：VINTF 声明、`vendor:true` 构建、lazy HAL、`/dev/binder` vs `/dev/vndbinder` 坑，全部适用；Vibrator 默认实现在 `/system`，厂商实现改放 `/vendor` 即套用该篇。
- **`app-call-hal-android14.md`**：App 不走直连，经 framework `VibratorManager`（公开 API `VibratorManager`/`Vibrator`）→ system_server `VibratorManagerService` → HAL。标准路径，无需自定义 Manager。
- **servicemanager C++ 版（第二轮）**：`service list` 看到的 `android.hardware.vibrator.IVibratorManager/default` 即由 `Access::canAdd`（selinux `add` on `hal_vibrator_service`）守门；`canFind` 守 `system_server` 的查找。
