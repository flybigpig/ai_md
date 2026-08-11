# AOSP 官方 AIDL HAL SELinux 策略配置实例（drivers）

> 本文是一份**可直接照抄**的 AIDL HAL sepolicy 模板，按 AOSP 官方 AIDL HAL 写法整理，仅把 HAL 名改成 `drivers`。
> 适用于 Android 10+（Treble/HAL 隔离），也是 Android 14 GKI 2.0 下 vendor HAL 的标准做法。
> 配合内核驱动使用：HAL 进程（`hal_drivers_default` 域）→ `/dev/drivers0` 或 sysfs → 内核驱动（`.ko` 模块）。

---

## 1. 目录结构（建议）

假设你的设备策略目录是 `device/<厂商>/<设备>/sepolicy`，建议结构如下：

```text
sepolicy/
├── public/
│   ├── attributes
│   ├── service.te
│   └── hal_drivers.te
├── private/
│   ├── service_contexts
│   └── system_server.te
└── vendor/
    ├── hal_drivers_default.te
    ├── file.te
    └── file_contexts
```

构建里加上：

```makefile
BOARD_VENDOR_SEPOLICY_DIRS += device/<厂商>/<设备>/sepolicy
```

---

## 2. public/attributes：定义 HAL 属性

```te
// public/attributes
// 定义 drivers HAL 属性：生成 hal_drivers_client / hal_drivers_server
hal_attribute(drivers)
```

这会定义：
- `hal_drivers_client`：所有能"获取" drivers HAL 的客户端进程属性
- `hal_drivers_server`：所有能"注册" drivers HAL 的服务端进程属性

---

## 3. public/service.te：定义 drivers HAL 服务类型

```te
// public/service.te
// AIDL HAL 服务类型，必须带 hal_service_type 和 service_manager_type
type hal_drivers_service, service_manager_type, hal_service_type;
```

AOSP 要求：对 vendor 可见的 AIDL HAL 服务类型必须有 `hal_service_type`。

---

## 4. public/hal_drivers.te：Binder 通信 + 服务绑定

```te
// public/hal_drivers.te
// 1. 允许 client/server 使用 binder
binder_use(hal_drivers_client)
binder_use(hal_drivers_server)
// 2. 允许 client 与 server 互相 binder 调用
binder_call(hal_drivers_client, hal_drivers_server)
binder_call(hal_drivers_server, hal_drivers_client)
// 3. 把 drivers HAL 属性和服务类型绑定
//    hal_drivers_client 可以 find，hal_drivers_server 可以 add
hal_attribute_service(hal_drivers, hal_drivers_service)
```

这一步是官方推荐写法，`hal_attribute_service` 用来绑定 HAL 属性和 AIDL 服务类型。

---

## 5. private/service_contexts：服务名 → 服务类型

```te
// private/service_contexts
// 假设你的 AIDL 包名是 android.hardware.drivers，接口 IDrivers，实例名 default
android.hardware.drivers.IDrivers/default  u:object_r:hal_drivers_service:s0
```

如果你有多个实例：

```te
android.hardware.drivers.IDrivers/custom   u:object_r:hal_drivers_service:s0
```

---

## 6. private/system_server.te：让 system_server 成为 drivers HAL 客户端（示例）

```te
// private/system_server.te
// 让 system_server 成为 hal_drivers 的客户端
hal_client_domain(system_server, hal_drivers)
```

这样 `system_server` 就自动获得 `hal_drivers_client` 属性，可以 find / 调用 drivers HAL 服务。

如果你有别的系统进程要作为客户端，也类似：

```te
hal_client_domain(some_system_domain, hal_drivers)
binder_use(some_system_domain)
```

---

## 7. vendor/hal_drivers_default.te：drivers HAL 服务端进程域

```te
// vendor/hal_drivers_default.te
// 1. 声明为 drivers HAL 的服务端域
hal_server_domain(hal_drivers_default, hal_drivers)
// 2. 典型：作为 init 启动的守护进程
init_daemon_domain(hal_drivers_default)
// 3. (可选) 访问内核驱动或设备节点的权限示例
//    假设你在内核里有一个字符设备 /dev/drivers0，类型为 drivers_device
// allow hal_drivers_default drivers_device:chr_file { read write open ioctl };
// 4. (可选) 访问驱动相关 sysfs 节点
// allow hal_drivers_default sysfs_drivers:file { read open };
```

**注意：**
- 不要在 `.rc` 里写 `seclabel ...`，让 SELinux 自动根据文件标签切换到 `hal_drivers_default` 域。
- 如果你确实需要自定义域，只要再写一个 `hal_drivers_custom.te`，同样用 `hal_server_domain(..., hal_drivers)` 即可。

---

## 8. vendor/file.te：定义可执行文件类型

```te
// vendor/file.te
type hal_drivers_default_exec, exec_type, file_type, vendor_file_type;
```

---

## 9. vendor/file_contexts：给 HAL 服务二进制打标签

```te
// vendor/file_contexts
// 假设你的 HAL 服务安装在 /vendor/bin/hw/
/vendor/bin/hw/android\.hardware\.drivers-service  u:object_r:hal_drivers_default_exec:s0
```

要点：
- 路径要和实际安装路径完全一致（注意 `.` 要转义成 `\.`）。
- 文件标签必须是 `<domain>_exec`，否则进程不会从 `init` 切换到 `hal_drivers_default`。

---

## 10. 如何验证生效

1. **文件标签：**

```bash
ls -Z /vendor/bin/hw/android.hardware.drivers-service
# 应该看到：u:object_r:hal_drivers_default_exec:s0
```

2. **进程域：**

```bash
ps -Z | grep drivers
# 应该看到：u:r:hal_drivers_default:s0
```

3. **服务注册：**

```bash
dumpsys android.hardware.drivers.IDrivers
# 或
service list | grep android.hardware.drivers
```

4. **SELinux 拒绝：**

```bash
dmesg | grep avc
# 或
logcat -b events -d | grep avc
```

如果有 AVC 拒绝，按报文补充 `allow` 规则到 `vendor/hal_drivers_default.te`。

---

## 11. 和 HIDL 的差异（如果你用的是 HIDL）

- 服务类型改为带 `hwservice_manager_type`：

```te
type hal_drivers_service, hwservice_manager_type, hal_service_type;
```

- 使用 `hwservice_contexts` 代替 `service_contexts`。
- 用 `hal_attribute_hwservice(hal_drivers, hal_drivers_service)` 代替 `hal_attribute_service`。

---

# 技术校验与注意事项（照抄前务必看）

下面是基于 AOSP 实际构建机制，对上面模板需重点确认的 4 个点，避免"编译过了但策略不生效"。

### 校验 1：public / private / vendor 目录在 `BOARD_VENDOR_SEPOLICY_DIRS` 下的真实处理

标准 AOSP 中，`public/` 和 `private/` 是 **`system/sepolicy`** 的目录层级概念：
- `public/`：跨 partition 可见的接口（HAL 属性、服务类型声明），供 vendor 引用
- `private/`：仅 system 分区可见的策略（如 `system_server.te`、`service_contexts` 的 system 部分）
- `vendor/`：vendor 分区专属策略

`device/<oem>/<device>/sepolicy` 经 `BOARD_VENDOR_SEPOLICY_DIRS` 引入时，**整个目录被当作 vendor 策略统一处理**，并不会自动区分 public/private 语义。因此：
- ✅ 把 `hal_attribute` / 服务类型 / `system_server` 客户端授权也写在 device sepolicy 目录里**能编译通过**——这是很多设备定制的简化做法。
- ⚠️ 严格合规分层时，HAL 属性与服务类型声明应进 `system/sepolicy/public`，`system_server.te` / 系统侧 `service_contexts` 应进 `system/sepolicy/private`，device 目录只保留 `vendor/*`。
- 💡 如果你的目标是"快速落地、能跑"，直接照抄上面的目录结构即可；若要严格 Treble 合规、做 CTS/VTS，`hal_attribute(drivers)` 和 `type hal_drivers_service` 应挪到 `system/sepolicy/public`，`system_server.te` 挪到 `system/sepolicy/private`。

> 实操提示：绝大多数芯片厂商（QCOM/MTK）的设备 sepolicy 目录就是扁平的 `vendor/`，所有 `.te` 都放一起。模板里的 public/private/vendor 分层更多是"逻辑分类"，物理目录可以合并为单层 `sepolicy/vendor/`，只要 `BOARD_VENDOR_SEPOLICY_DIRS` 指向它。

### 校验 2：`hal_attribute` 宏实际展开成什么

```te
hal_attribute(drivers)
```

展开后等价于（伪码）：

```te
attribute hal_drivers_client;
attribute hal_drivers_server;
# 以及允许 client 与 server 的基础 binder 能力（部分版本）
```

所以第 4 节的 `binder_use` / `binder_call` 是**显式补充**，并非重复——`hal_attribute` 只建属性，Binder 通信权限仍需自己写（或依赖 `hal_client_domain` / `hal_server_domain` 宏内已包含的 `binder_use`）。注意：
- `hal_server_domain(hal_drivers_default, hal_drivers)` 宏**已经包含** `binder_use(hal_drivers_server)` + domain 类型声明。
- `hal_client_domain(system_server, hal_drivers)` 宏**已经包含** `binder_use(hal_drivers_client)`。

因此第 4 节对 `binder_use` / `binder_call` 的显式声明，主要作用是把 `client↔server` 之间的 `binder_call` 双向打通。如果 server 域和 client 域都用官方宏声明了，这部分可以保留作明确化，不会冲突。

### 校验 3：`service_contexts` 的真实路径与多框架

AIDL HAL 注册到 **`servicemanager`**（非 hwservicemanager），所以：
- 映射文件用 `service_contexts`（system 侧）+ vendor 侧也有自己的 `service_contexts`。
- 服务名格式严格为 `<fully.qualified.Interface>/<instance>`，即 `android.hardware.drivers.IDrivers/default`。注意接口名是 `IDrivers`（带 `I` 前缀），实例是 `default`。HAL 实现里 `AServiceManager_addService` 用的 name 必须与这里**逐字符一致**，否则 `system_server` 的 `waitForDeclaredService` 找不到，`hal_client_domain` 授权也无法命中。

### 校验 4：内核设备节点标签不在本文，需另配

本模板只管 HAL 进程与 Binder 通信。**HAL 进程要真正 open `/dev/drivers0`，还需两处（见《Android14_SELinux策略配置.md》）：**
1. `vendor/file_contexts` 追加：`/dev/drivers0  u:object_r:drivers_device:s0`
2. `vendor/file.te` 声明：`type drivers_device, dev_type;`
3. `vendor/hal_drivers_default.te` 放开：`allow hal_drivers_default drivers_device:chr_file { read write open ioctl };`

sysfs 同理（`genfs_contexts` + `sysfs_drivers` 类型）。这一步漏了，HAL 启动后 `open("/dev/drivers0")` 会在 enforcing 模式下吃 AVC deny，服务直接起不来。

---

# 与本系列其他文档的衔接

| 文档 | 角色 |
|------|------|
| `Android14_SELinux策略配置.md` | 单层 sepolicy 写法 + avc 调试 + 内核节点/sysfs 标签（本文第 7 节末尾"可选"部分的落地细节） |
| 本文 | 官方分层（public/private/vendor）AIDL HAL 模板，适合严格 Treble 合规 |
| `Android内核驱动实例.md` | 内核侧 `/dev/hello` 驱动源码（把里面的 `hello` 全局替换成 `drivers` 即可对接本模板） |

**完整落地的三段式：**
```
内核驱动(drivers.ko)
   │ /dev/drivers0 + sysfs
   ▼  SELinux: file_contexts + drivers_device 类型 + allow chr_file
HAL 进程(hal_drivers_default 域)
   │  Binder: AServiceManager_addService("...IDrivers/default")
   ▼  SELinux: hal_server_domain + file_contexts(_exec) + hal_attribute_service
system_server(Framework, hal_drivers_client 域)
```
