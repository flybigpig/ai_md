# HAL SELinux 策略（Android 14 / API 34）深度解析

> 目标：厘清 AIDL HAL 与 HIDL HAL 在 SELinux 类型体系上的根本差异，给出一套**可直接照编**的 AIDL HAL sepolicy 文件集，并把上一轮《Android System Development Cookbook》§6.1 的 HIDL 错误写法逐行改写成正确写法。
> 所有结论以 `android-14.0.0_rXX` 真树为准。关键宏定义位于 `system/sepolicy/public/te_macros`，类型属性位于 `system/sepolicy/public/attributes`。

---

## 一、结论前置（先看这段）

1. **AIDL HAL 走 `/dev/binder` + `servicemanager`，SELinux 用 `service_manager_type` 类型体系；HIDL HAL 走 `/dev/hwbinder` + `hwservicemanager`，SELinux 用 `hwservice_manager_type` 类型体系。** 这是两套完全不同的域/类型，不能混用。
2. Cookbook §6.1 给一个 AIDL HAL（`android.hardware.vehiclebody.IVehicleBody`）配了 **HIDL 的 sepolicy**（`hwservice_manager_type` + `::` + `hwbinder_use` + `add_hwservice`）——这是错的，会导致 HAL 在 servicemanager 注册名对不上、`check-vintf-all` 也管不到它，且 `addService` 被 SELinux 拒绝。
3. 一份**完整的 AIDL HAL sepolicy** 至少涉及 4 个文件：`hal_<name>.te`、`file_contexts`、`service_contexts`（+ `service.te` 声明类型）、以及客户端侧的 `find` 授权。车载 HAL 还要补**进程降权（rc 文件）**和 **`/data/vendor` 数据目录标注**。
4. 平台 HAL（改 `system/sepolicy/`）必须同步 `prebuilts/api/34.0/`；设备/厂商 HAL（改 `device/<oem>/<device>/sepolicy/`）用 `*.ignore.cil` 的 `new_objects` 豁免新增类型。任一漏了，`sepolicy_freeze_test` 直接挂。

---

## 二、AIDL HAL vs HIDL HAL 的 SELinux 类型体系对照

| 维度 | AIDL HAL（Android 12+ 推荐，车载新 HAL 一律用） | HIDL HAL（遗留，14 已进入弃用期） |
|---|---|---|
| Binder 设备 | `/dev/binder` | `/dev/hwbinder` |
| 服务注册中心 | `servicemanager`（handle 0，C++ 版 `ServiceManager`） | `hwservicemanager`（`system/hwservicemanager`） |
| 服务名标注文件 | `service_contexts` | `hwservice_contexts` |
| 服务类型属性 | `service_manager_type` | `hwservice_manager_type` |
| 包名/接口记法 | 单点 `android.hardware.hello.IHello/default` | 双冒号 `android.hardware.hello::IHello/default` |
| 进程使用 binder 宏 | `binder_use(domain)` | `hwbinder_use(domain)` |
| 注册服务宏/allow | `binder_service()` + `allow dom svc:service_manager { add }` | `add_hwservice(dom, hwsvc)` |
| 跨进程调用宏 | `binder_call(client, server)` | `hwbinder_call(client, server)`（或 `binder_call` 适用 vndbinder） |
| VINTF 声明 | `android.hardware.hello` 在 device manifest 的 `<interface>`（AIDL 形式） | `<hal format="hidl">` 形式 |
| 进程域切换宏 | `init_daemon_domain` / `hal_server_domain` | 同左 |

**一句话**：给 AIDL HAL 写 `hwservice_manager_type`、`::`、`hwbinder_use`、`add_hwservice` 中任意一个，都是把两套体系搞混了。

---

## 三、AIDL HAL 完整 sepolicy 文件集（以 `android.hardware.hello` / 实例 `default` 为例）

假设这是一个 **vendor 分区 HAL**（车载定制最常见），代码与策略放在：
```
device/<oem>/<device>/sepolicy/vendor/   ← 策略文件
vendor/<oem>/<device>/bin/hw/android.hardware.hello-service   ← 可执行体（file_contexts 指向此）
vendor/etc/init/android.hardware.hello-service.rc            ← init 脚本
```

### 3.1 `hal_hello.te` —— 域定义与授权（最核心）

```te
# ===== 进程域 =====
type hal_hello_default, domain;
type hal_hello_default_exec, exec_type, vendor_file_type, file_type;

# init 拉起 rc 时，把进程域切换到 hal_hello_default
init_daemon_domain(hal_hello_default)

# ===== AIDL HAL 属性声明（必须先于 hal_server_domain）=====
hal_attribute(hello)

# 标记本域是该 HAL 的“服务端”
hal_server_domain(hello, hal_hello_default)

# ===== Binder 基础能力（AIDL 走 /dev/binder）=====
binder_use(hal_hello_default)          # 允许 open/read/write /dev/binder

# 服务类型已在 service.te 声明为 service_manager_type；
# 允许本域把自己的 binder 服务注册进 servicemanager（perm=add）
allow hal_hello_default hal_hello_service:service_manager { add find };

# ===== 与客户端（system_server / framework）的 binder IPC =====
binder_call(hal_hello_default, system_server)
binder_call(system_server, hal_hello_default)

# ===== 若 HAL 需要访问自身设备节点（如 CAN 控制器）=====
# allow hal_hello_default can_device:chr_file { read write ioctl open };
# （设备节点类型需在 device.te / file_contexts 中定义）

# ===== 若 HAL 需要持久化配置，必须走 /data/vendor，不能直接碰 /data =====
# 见 §3.5
```

> **注意**：`hal_attribute(hello)` 必须写在 `hal_server_domain(hello, hal_hello_default)` **之前**。Cookbook §6.1 直接调 `hal_server_domain` 却没先 `hal_attribute`，宏展开会因 `$1_server` 属性不存在而失败。

### 3.2 `file_contexts` —— 可执行体打标

```text
# 路径用正则转义，结尾无斜杠表示文件
/vendor/bin/hw/android\.hardware\.hello-service    u:object_r:hal_hello_default_exec:s0
```

如果 HAL 还带 native 库：
```text
/vendor/lib(64)?/hw/android\.hardware\.hello\.so    u:object_r:hal_hello_default_exec:s0
```

### 3.3 `service_contexts` + `service.te` —— 服务名打标

`service.te`（声明类型）：
```te
type hal_hello_service, service_manager_type;
```

`service_contexts`（把 AIDL 接口名映射到该类型，**单点记法**）：
```text
android.hardware.hello.IHello/default    u:object_r:hal_hello_service:s0
```

> 这就是 servicemanager C++ 版 `Access::canAdd/canFind` 校验时比对的对象。注册时 `selinux_check_access(scon, tcon, "service_manager", "add")` 中的 `tcon` 就是这里的 `hal_hello_service`，`scon` 是 HAL 进程域 `hal_hello_default`。

### 3.4 客户端侧（system_server / framework）授权

若你的 Manager 跑在 `system_server` 里通过 `getService`/`waitForService` 拿 HAL，需让 `system_server` 能 `find` 该服务并收发 binder：

```te
# 放在 system_server 相关 te，或直接在 hal_hello.te 里
allow system_server hal_hello_service:service_manager find;
# binder_call 已在 3.1 双向授予
```

若客户端是**另一个 HAL / native 进程**（跨 HAL 调用），用 `hal_client_domain` 更干净：
```te
hal_client_domain(hal_other_default, hello)
```
该宏会自动授予对 `hal_hello` 服务端属性的 `binder_call` 与 `find`。

### 3.5 HAL 私有数据目录（车载常需）

HAL 绝不能写 `/data`（触发 `neverallow` 跨分区写），必须落到 `/data/vendor/<hal>/`，并自定一个 `vendor_data_file` 类型：

```te
# device/<oem>/<device>/sepolicy/vendor/file_contexts
/data/vendor/hello(/.*)?    u:object_r:hal_hello_data_file:s0

# device/<oem>/<device>/sepolicy/vendor/hal_hello.te
type hal_hello_data_file, file_type, data_file_type;
allow hal_hello_default hal_hello_data_file:dir create_dir_perms;
allow hal_hello_default hal_hello_data_file:file create_file_perms;
```

---

## 四、关键宏的真身（`system/sepolicy/public/te_macros`）

| 宏 | 展开要点 | 易错点 |
|---|---|---|
| `hal_attribute(name)` | 声明 `name`、`name_client`、`name_server` 三个属性 + 把 `name` 加入 `hal_type` | 必须先于 `hal_server_domain`/`hal_client_domain` 调用 |
| `hal_server_domain(name, domain)` | `typeattribute domain name_server;` + `typeattribute domain halserverdomain;` | 第一个参数是 `hal_attribute` 用的名字，第二个是进程域；Cookbook 误写成 `halserver_domain`（少下划线） |
| `hal_client_domain(name, domain)` | `typeattribute domain name_client;` + `typeattribute domain halclientdomain;` | —— |
| `binder_use(domain)` | `allow domain binder_device:chr_file {...};` + `binderfs` 访问 | AIDL 对应物，替代 HIDL 的 `hwbinder_use` |
| `binder_call(client, server)` | `allow client server:binder call;` + `allow server client:binder transfer;` + fd use | 双向调用要写两条（client→server 与 server→client） |
| `init_daemon_domain(domain)` | 允许 `init` 域 `transition` 到该域 + `entrypoint` 到 `*_exec` | rc 文件里进程名必须匹配 `file_contexts` 的 exec 标签，否则域切换失败、进程以 `init` 域跑 |

---

## 五、HIDL HAL sepolicy 对照（以及 §6.1 错在哪）

为说明差异，下面是**如果这真的是 HIDL HAL** 该有的写法（仅作对照，不要用在 AIDL HAL 上）：

```te
# ===== HIDL 版（仅对照，AIDL HAL 不要用）=====
type hal_vehiclebody_default, domain;
type hal_vehiclebody_default_exec, exec_type, vendor_file_type, file_type;
init_daemon_domain(hal_vehiclebody_default)

hal_attribute(vehiclebody)
hal_server_domain(vehiclebody, hal_vehiclebody_default)

hwbinder_use(hal_vehiclebody_default)              # ← HIDL 专用
type hal_vehiclebody_hwservice, hwservice_manager_type;
add_hwservice(hal_vehiclebody_default, hal_vehiclebody_hwservice)
allow hal_vehiclebody_default hal_vehiclebody_hwservice:hwservice_manager { add find };

# hwservice_contexts（双冒号记法）：
# android.hardware.vehiclebody::IVehicleBody/default  u:object_r:hal_vehiclebody_hwservice:s0
```

### §6.1 的具体错误（逐条）

| §6.1 原文 | 问题 | 正确写法（AIDL） |
|---|---|---|
| `hal_attribute(vehicle)` | 与 §1 的接口包名 `vehiclebody` 不一致（自相矛盾） | `hal_attribute(vehiclebody)` |
| `halserver_domain(...)` | 宏名错，正确是 `hal_server_domain` | `hal_server_domain(vehiclebody, hal_vehiclebody_default)` |
| `type hal_vehiclebody_hwservice, hwservice_manager_type;` | AIDL HAL 不应是 `hwservice_manager_type` | `type hal_vehiclebody_service, service_manager_type;` |
| `hwbinder_use(...)` | AIDL 走 `/dev/binder` | `binder_use(hal_vehiclebody_default)` |
| `add_hwservice(...)` | HIDL 注册宏 | `binder_service(...)` + `allow ...:service_manager { add }` |
| `android.hardware.vehicle::IVehicle/default`（hwservice_contexts） | `::` 是 HIDL 记法，且包名/接口与 §1 不符 | `android.hardware.vehiclebody.IVehicleBody/default`（service_contexts，单点） |

**为什么这会编过但跑不起来**：`hwservice_manager_type` 类型、`hwbinder_use`、`add_hwservice` 在 14 上依然存在（HIDL 尚未完全移除），所以 `make sepolicy` 能通过；但 servicemanager（C++ 版）的 `Access::canAdd` 只认 `service_manager_type` 的服务名，且 AIDL HAL 注册用的是 `service_manager`，于是 `addService` 时 `selinux_check_access(scon, tcon, "service_manager", "add")` 的 `tcon` 找不到对应 `service_manager_type` 类型 → **avc denied（permission=add）**，HAL 注册失败，`getService` 永远拿不到。

---

## 六、把 §6.1 改写成正确的 AIDL HAL sepolicy（完整可 apply 片段）

下面这套直接替换 §6.1 的 sepolicy 段落即可（以 `android.hardware.vehiclebody` 为例）：

**`device/<oem>/<device>/sepolicy/vendor/hal_vehiclebody.te`**
```te
type hal_vehiclebody_default, domain;
type hal_vehiclebody_default_exec, exec_type, vendor_file_type, file_type;
init_daemon_domain(hal_vehiclebody_default)

hal_attribute(vehiclebody)
hal_server_domain(vehiclebody, hal_vehiclebody_default)

binder_use(hal_vehiclebody_default)
binder_service(hal_vehiclebody_default)
allow hal_vehiclebody_default hal_vehiclebody_service:service_manager { add find };

binder_call(hal_vehiclebody_default, system_server)
binder_call(system_server, hal_vehiclebody_default)

# 数据目录（如需持久化）
type hal_vehiclebody_data_file, file_type, data_file_type;
allow hal_vehiclebody_default hal_vehiclebody_data_file:dir create_dir_perms;
allow hal_vehiclebody_default hal_vehiclebody_data_file:file create_file_perms;
```

**`device/<oem>/<device>/sepolicy/vendor/service.te`**
```te
type hal_vehiclebody_service, service_manager_type;
```

**`device/<oem>/<device>/sepolicy/vendor/service_contexts`**
```text
android.hardware.vehiclebody.IVehicleBody/default    u:object_r:hal_vehiclebody_service:s0
```

**`device/<oem>/<device>/sepolicy/vendor/file_contexts`**
```text
/vendor/bin/hw/android\.hardware\.vehiclebody-service    u:object_r:hal_vehiclebody_default_exec:s0
/data/vendor/vehiclebody(/.*)?                          u:object_r:hal_vehiclebody_data_file:s0
```

**客户端（system_server）侧补充**
```te
allow system_server hal_vehiclebody_service:service_manager find;
```

---

## 七、进程降权（rc 文件 + init 域切换 + neverallow）

SELinux 策略定义了域，但**进程是否真的以该域运行**取决于 rc 文件。车载 HAL 绝不能以 root 跑：

**`vendor/etc/init/android.hardware.vehiclebody-service.rc`**
```rc
service vendorbody-hal /vendor/bin/hw/android.hardware.vehiclebody-service
    class hal
    user system
    group system
    seclabel u:object_r:hal_vehiclebody_default_exec:s0
    # 车载省电：lazy HAL 写法（见第八轮讨论）
    # interface aidl android.hardware.vehiclebody.IVehicleBody/default
    # disabled
    # oneshot
```

要点：
- `user system` / `group system` —— 降权，避免 root 进程触发 `neverallow` 与安全隐患。
- `seclabel` 显式指定 exec 标签，确保 init 域切换正确（即使不写，只要 `file_contexts` 标了 exec 类型也会切，但显式写更稳）。
- 若漏掉 `seclabel` 且 `file_contexts` 错标，进程会以 `init` 域运行 → `binder_call` 规则不匹配 → binder 调用全部 avc denied。
- `class hal` 让 HAL 在 `hal` 类里按依赖顺序启动。

---

## 八、Treble 跨分区隔离要点

1. **vendor HAL 域（如 `hal_vehiclebody_default`）默认禁止访问 `system_file`、`/data` 根目录、framework 私有目录**——触发 `neverallow`。任何跨分区访问都要显式 `allow` 且通常仍被 `neverallow` 拦，正确做法是只用 vendor 允许的接口（binder call、vndk、/data/vendor）。
2. **system↔vendor 的 binder 调用**：用 `binder_call` 双向授权即可， binder 本身跨分区允许。
3. **不要试图让 vendor HAL 读 `system_server` 的 `/proc` 或 `system_data_file`**——这是 Treble 红线，会被 `neverallow` 编译拒绝。
4. 若 HAL 必须共享内存给 surface（如 EVS/相机类），走 `gralloc`/`allocator` 的 `hal_graphics_allocator` 域，不要自行 `mmap` system 内存。

---

## 九、构建校验

### 9.1 平台 HAL（改 `system/sepolicy/`）
必须把新类型/规则同时写进 `private/` 与 `prebuilts/api/34.0/private/`，否则：
```
sepolicy_freeze_test: FAIL (private 与 prebuilts/api/34.0 不一致)
```
`prebuilts/api/34.0/public/` 里的属性（`hal_attribute` 生成的）也要同步。

### 9.2 设备/厂商 HAL（改 `device/<oem>/<device>/sepolicy/`）
受 `BOARD_SEPOLICY_FREEZE_TEST`（默认开）约束。新增类型若不想同步 prebuilt，用 **`*.ignore.cil`** 豁免：

```cil
; device/<oem>/<device>/sepolicy/vendor/<device>.ignore.cil
(new_objects
  (type hal_vehiclebody_default)
  (type hal_vehiclebody_default_exec)
  (type hal_vehiclebody_service)
  (type hal_vehiclebody_data_file)
)
```
注意：是 `new_objects` 宏，写在 `.ignore.cil`，不是普通 `.cil` 的 `expandtypeattribute`。这也是 HAL 文档那轮我强调的 gotcha。

### 9.3 触发校验
```bash
source build/envsetup.sh && lunch <device>-eng
make sepolicy        # 单独编 sepolicy，最快验证语法
make sepolicy_freeze_test   # 单独跑 freeze 测试
```

---

## 十、排障：从 avc 到规则

### 10.1 抓拒绝日志
```bash
adb shell dmesg | grep avc
# 或实时
adb shell cat /proc/kmsg | grep avc
adb logcat | grep -i servicemanager   # servicemanager 自身的拒绝会打 logcat
```

### 10.2 用 audit2allow 生成规则（必须指定编出的 sepolicy）
```bash
adb shell dmesg | grep avc | \
  audit2allow -p out/target/product/<device>/obj/ETC/sepolicy_intermediates/sepolicy
```
> 千万别把 audit2allow 的输出无脑贴回 te——它只告诉你“缺了哪条 allow”，要结合本文判断类型对不对（比如它建议的 `hal_vehiclebody_hwservice` 就是错的，应改 `hal_vehiclebody_service`）。

### 10.3 典型 avc 解读
```
avc: denied { add } for service=android.hardware.vehiclebody.IVehicleBody/default
  scontext=u:r:hal_vehiclebody_default:s0
  tcontext=u:object_r:hal_vehiclebody_service:s0
  tclass=service_manager
```
→ `tclass=service_manager` + `permission=add`：服务名类型没在 `service_contexts` 标对，或 `hal_vehiclebody.te` 缺 `allow ...:service_manager { add }`。

```
avc: denied { call } for ... tclass=binder
```
→ 缺 `binder_call(client, server)` 双向授权。

### 10.4 servicemanager C++ 版的校验点（衔接前几轮）
`frameworks/native/cmds/servicemanager/Access.cpp` 的 `canAdd`/`canFind` 最终调：
```cpp
selinux_check_access(scon, tcon, "service_manager", perm /* add|find|list */, NULL);
```
所以 `tclass=service_manager` 的 avc 直接对应这里。你的 `service_contexts` 标签就是 `tcon`，进程域就是 `scon`。

### 10.5 临时确认是否 SELinux 问题
```bash
adb shell setenforce 0   # userdebug/eng 才能执行
# 再试一次 HAL 注册/调用；若成功，100% 是 sepolicy 问题
adb shell setenforce 1
```
> 仅排障用，量产绝不留 `permissive`。

### 10.6 验证注册成功
```bash
adb shell service list | grep vehiclebody
# 或
adb shell cmd service list | grep vehiclebody
```

---

## 十一、与前几轮文档的衔接

- **《AOSP14 添加 HAL 与系统服务文档》**：其中 `hello` HAL 的 sepolicy 写法（`hal_attribute(hello)` + `service_manager_type` + `service_contexts` + `*.ignore.cil` 的 `new_objects`）**与本文一致，是正确的 AIDL 范式**，可作为配套参考。
- **《Android System Development Cookbook》§6.1**：本文第六章即对其的逐行修正。该 cookbook §1 通篇讲“新增 HAL 一律 AIDL”，但 §6.1 却用了 HIDL 策略，属自相矛盾，以本文为准。
- **servicemanager C++ 版（第二轮）**：`checkService`/`getService`/`waitForService` 的取服务流程依赖 VINTF `isDeclared` + 按需 `tryStartService`；而**能否注册成功**的闸门就是本文的 `service_contexts` + `service_manager_type` + `Access::selinux_check_access`。三者共同构成“HAL 起来了却取不到”的完整排查面。
- **dumpsys（第三/四轮）**：HAL 注册后可 `adb shell dumpsys <hal_service>` 观察状态；若 `dumpsys` 报 `Permission denied` 或 `Service not found`，先回到本文 §10.3 的 `service_manager` 类 avc 排查。

---

## 十二、速查表（AIDL HAL sepolicy 最小正确集）

| 文件 | 必须内容 |
|---|---|
| `hal_<name>.te` | `domain` + `exec` 类型、`init_daemon_domain`、`hal_attribute`、`hal_server_domain`、`binder_use`、`allow ...:service_manager { add }`、`binder_call` 双向 |
| `service.te` | `type hal_<name>_service, service_manager_type;` |
| `service_contexts` | `android.hardware.<name>.I<Ifc>/default  u:object_r:hal_<name>_service:s0`（**单点**） |
| `file_contexts` | `/vendor/bin/hw/...-service  u:object_r:hal_<name>_default_exec:s0` |
| rc 文件 | `user system` / `group system` / `seclabel` 降权 |
| 客户端 te | `allow <client> hal_<name>_service:service_manager find;` |
| 构建 | 厂商 HAL 用 `*.ignore.cil` 的 `new_objects` 豁免；平台 HAL 同步 `prebuilts/api/34.0/` |
