# Android 14 SELinux 策略配置（驱动 / HAL 开发实战）

> **适用：** Android 14 (API 34, android14-6.1)
> **场景：** 内核驱动创建了 `/dev/xxx` / `sysfs` 节点，HAL / Framework 需要访问它
> **核心结论：** 驱动代码写得再对，**SELinux 策略不对，HAL 一样 `open()` 失败、App 拿不到数据**。Android 14 默认 `enforcing` 模式，必须配齐标签与权限。

---

## 一、核心结论前置

```
内核驱动 insmod 成功
   │ /dev/hello 节点出现
   ▼
HAL 进程 open("/dev/hello")
   │
   ▼
SELinux 检查：HAL 的 domain 是否允许访问 /dev/hello 的 type？
   │
   ├─ 策略允许 → 成功
   └─ 策略缺失/拒绝 → EACCES，dmesg 出现 avc: denied
```

**没有 SELinux 策略 = 驱动白写一半。** 本文把"内核驱动 → SELinux 标签 → HAL 访问"这条线彻底打通。

---

## 二、Android SELinux 架构速览

| 概念 | 说明 |
|------|------|
| **enforcing** | Android 14 默认强制模式，拒绝会真正拦截（非仅日志） |
| **domain** | 进程的标签（如 `hal_hello_default`、`system_server`） |
| **type** | 客体（文件/设备/节点）的标签（如 `hello_device`、`sysfs_hello`） |
| **label** | 形如 `u:object_r:hello_device:s0`，三段式 user:role:type:level |
| **policy** | 编译进 `vendor/etc/selinux/` 的 `.cil` / 二进制策略 |
| **Treble 隔离** | system 分区与 vendor 分区策略分离，vendor 策略受限 |

### 策略文件位置

```
device/<oem>/<device>/sepolicy/
├── vendor/                 ← 厂商驱动/HAL 策略放这里
│   ├── file_contexts       ← /dev 节点、可执行文件标签
│   ├── genfs_contexts      ← sysfs/procfs 标签（无 xattr 的文件系统）
│   ├── hello.te            ← 自定义类型与权限
│   └── ...
├── private/  / public/     ← 与 system 共享的规则（需谨慎）
└── ...
```

---

## 三、标签体系：节点怎么被贴上标签

### 3.1 `/dev/xxx` 字符设备节点

内核驱动 `device_create()` 后在 `/dev` 下出现节点，**由 `ueventd` 依据 `file_contexts` 打标签**（不是内核自动打的）。

```bash
# 节点创建后查看当前标签
adb shell ls -Z /dev/hello
# 未配置前通常是默认标签，如 u:object_r:device:s0
```

### 3.2 `sysfs` 属性节点

`sysfs` 不支持 xattr，用 **`genfscon`** 按路径打标签（必须用真实路径，不能用 `/sys/class/...` 这种 symlink）。

```bash
# 查看真实路径
adb shell ls -l /sys/class/misc/hello/val
# lrwxrwxrwx ... /sys/class/misc/hello/val -> ../../devices/virtual/misc/hello/val
# 真实路径是 /sys/devices/virtual/misc/hello/val
```

### 3.3 可执行文件（HAL 二进制）

HAL service 的二进制由 `file_contexts` 打标签，使其以正确的 `exec_type` 启动。

---

## 四、完整实例：hello 驱动的 SELinux 配置

延续前文 `hello` 驱动（创建 `/dev/hello` + `/sys/class/misc/hello/val`），分两种 HAL 场景。

### 场景 A：AIDL HAL（独立进程，`hal_hello_default`）

#### 4.1 设备节点标签 — `file_contexts`

```contexts
# device/oem/device/sepolicy/vendor/file_contexts

# /dev/hello 字符设备节点
/dev/hello    u:object_r:hello_device:s0

# HAL service 可执行文件
/vendor/bin/hw/android\.hardware\.hello-service-default  u:object_r:hal_hello_default_exec:s0
```

#### 4.2 sysfs 标签 — `genfs_contexts`

```contexts
# device/oem/device/sepolicy/vendor/genfs_contexts

# 注意用真实路径（devices/virtual/...），不是 /sys/class 软链
genfscon sysfs /devices/virtual/misc/hello  u:object_r:sysfs_hello:s0
```

#### 4.3 类型与权限 — `hello.te`

```te
# device/oem/device/sepolicy/vendor/hello.te

# ===== 类型声明 =====
# /dev/hello 设备节点类型
type hello_device, dev_type;
# sysfs 属性类型
type sysfs_hello, sysfs_type, fs_type;
# HAL 进程 domain
type hal_hello_default, domain;
# HAL 可执行文件类型
type hal_hello_default_exec, exec_type, file_type;

# ===== 进程 domain 初始化 =====
# 让 init 启动的 hal_hello_default_exec 进入 hal_hello_default domain
init_daemon_domain(hal_hello_default)
# 声明本 HAL 实现 android.hardware.hello AIDL 接口（会自动授予 hwservicemanager 交互权限）
hal_server_domain(hal_hello_default, hal_hello)

# ===== HAL 访问 /dev/hello =====
allow hal_hello_default hello_device:chr_file {
    open read write ioctl getattr
};

# ===== HAL 访问 sysfs =====
allow hal_hello_default sysfs_hello:dir search;
allow hal_hello_default sysfs_hello:file {
    open read write getattr
};

# ===== Framework (system_server) 获取 HAL 服务 =====
# 允许 system_server 作为 HAL 客户端连到 hal_hello
hal_client_domain(system_server, hal_hello)
```

#### 4.4 VINTF + service 上下文（HAL 侧配套）

`hello-service.rc` 里声明 `interface aidl`，结合 `hal_server_domain` 宏，hwservicemanager 才会放行：

```rc
service vendor.hello-default /vendor/bin/hw/android.hardware.hello-service.default
    interface aidl android.hardware.hello.IHello/default
    class hal
    user nobody
    group nobody
```

### 场景 B：C-ABI HAL（在 `system_server` 进程内，经典四层）

C-ABI HAL 通过 `dlopen` 跑在 `system_server` 进程里，所以**不用新建 HAL domain**，直接给 `system_server` 授权访问设备节点：

```te
# device/oem/device/sepolicy/vendor/hello.te

type hello_device, dev_type;

# system_server 域访问 /dev/hello
allow system_server hello_device:chr_file rw_file_perms;

# 若也暴露 sysfs
type sysfs_hello, sysfs_type, fs_type;
allow system_server sysfs_hello:file rw_file_perms;
```

> 注意：C-ABI HAL 在 Android 14 不推荐，但老代码常见。权限直接落到 `system_server` domain，风险面更大。

---

## 五、SELinux 宏速查

| 宏 | 作用 |
|----|------|
| `type xxx, dev_type;` | 声明设备节点类型 |
| `type xxx, fs_type, sysfs_type;` | 声明 sysfs/procfs 类型 |
| `type xxx, domain;` | 声明进程 domain |
| `init_daemon_domain(xxx)` | init 启动的 `xxx_exec` 进入 `xxx` domain |
| `hal_server_domain(dom, hal_xxx)` | 进程作为某 HAL 的服务端，授予 hwservicemanager 权限 |
| `hal_client_domain(dom, hal_xxx)` | 进程作为某 HAL 的客户端 |
| `rw_file_perms` | `{ open read write getattr }` 宏集合 |
| `r_file_perms` | `{ open read getattr }` 宏集合 |

---

## 六、读取与解决 AVC Denial（实战）

### 6.1 触发并抓取拒绝日志

```bash
# 确保 enforcing（默认）
adb shell getenforce   # Enforcing

# 触发访问（HAL 尝试 open /dev/hello）
adb shell setprop ctl.restart vendor.hello-default   # 重启 HAL

# 抓取 avc 拒绝
adb shell dmesg | grep avc
```

典型拒绝日志：
```
avc: denied { read write } for pid=1234 comm="android.hardware.hello" \
  name="hello" dev="tmpfs" ino=5678 scontext=u:r:hal_hello_default:s0 \
  tcontext=u:object_r:hello_device:s0 tclass=chr_file permissive=0
```

字段解读：
- `scontext` = **源**（进程 domain）：`hal_hello_default`
- `tcontext` = **目标**（客体 type）：`hello_device`
- `tclass` = 客体类别：`chr_file`
- `{ read write }` = 被拒绝的权限

### 6.2 用 audit2allow 生成规则

```bash
# 抓取并转换（需编译环境或预装工具）
adb shell dmesg | grep avc > avc.log
audit2allow -i avc.log
# 输出：
#   allow hal_hello_default hello_device:chr_file { read write };
```

> ⚠️ **audit2allow 的输出只作参考，不要无脑粘贴**。要确认：源 domain 合理吗？目标 type 对吗？权限是否最小集？避免 `audit2allow -R` 生成过于宽松的规则。

### 6.3 正确的最小权限写法

```te
# 不要写：
allow hal_hello_default hello_device:chr_file { read write open ioctl getattr ... };

# 用宏收敛：
allow hal_hello_default hello_device:chr_file rw_file_perms;
```

---

## 七、neverallow 陷阱（vendor 分区限制）

Android 14 的 `neverallow` 规则会**阻止 vendor 域访问某些 system 类型**。常见踩坑：

```te
# 错误：vendor HAL 直接访问 system_server 私有数据 → neverallow 拒绝
allow hal_hello_default system_data_file:file { read write };  # 编译期报错

# 正确：通过标准 HAL 接口 / 专用类型，不要越界
```

处理原则：
1. **vendor 策略不能直接 allow 访问 system 分区的关键类型**（如 `system_file`、`system_server` 私有数据）。
2. 需要跨分区访问时，走**标准 Binder/HAL 接口**，不要开放文件级访问。
3. 若确有合法例外，需在 `system/sepolicy` 侧用 `neverallow` 的例外语法，但极少见，优先重构架构。

---

## 八、编译与验证流程

```bash
# 1. 修改 sepolicy 后重新编译 vendor 镜像
source build/envsetup.sh
lunch <target>-userdebug
make selinux_policy   # 或 make vendorimage

# 2. 刷入
fastboot flash vendor
fastboot reboot

# 3. 验证标签
adb shell ls -Z /dev/hello
adb shell ls -Z /sys/devices/virtual/misc/hello/
adb shell ls -Z /vendor/bin/hw/android.hardware.hello-service-default

# 4. 验证进程 domain
adb shell ps -Z | grep hello

# 5. 验证无拒绝
adb shell dmesg | grep avc | grep hello   # 应为空

# 6. 临时关闭 enforcing 仅用于定位（不要当修复）
adb shell setenforce 0
```

---

## 九、完整 SELinux 检查清单（hello 驱动）

```
□ file_contexts: /dev/hello → hello_device
□ file_contexts: HAL 二进制 → hal_hello_default_exec
□ genfs_contexts: sysfs 真实路径 → sysfs_hello
□ hello.te: type 声明 (dev_type / sysfs_type / domain / exec_type)
□ hello.te: init_daemon_domain(hal_hello_default)
□ hello.te: hal_server_domain(hal_hello_default, hal_hello)
□ hello.te: allow 访问 chr_file（最小权限）
□ hello.te: allow 访问 sysfs（dir search + file）
□ hello.te: hal_client_domain(system_server, hal_hello)
□ hello-service.rc: interface aidl 声明
□ 编译 selinux_policy 通过（neverallow 不报错）
□ 设备端 ls -Z 标签正确
□ dmesg 无 avc denied
```

---

## 十、常见错误对照

| 现象 | 根因 | 修复 |
|------|------|------|
| `open() -> EACCES` | 进程 domain 无权访问 `/dev/hello` | 加 `allow <dom> hello_device:chr_file ...` |
| `/dev/hello` 标签为 `device:s0` | `file_contexts` 未配或 ueventd 未重读 | 补 `file_contexts` + 重编 vendor |
| sysfs 访问被拒 | `genfs_contexts` 路径用了软链 | 改用 `devices/virtual/...` 真实路径 |
| HAL 启动后 domain 不对 | 漏 `init_daemon_domain` | 补宏 + 确认 `file_contexts` 的 exec 标签 |
| 编译报 neverallow | vendor 越界访问 system 类型 | 改用标准 HAL 接口，不要文件级越权 |
| hwservicemanager 拒绝 | 漏 `hal_server_domain` | 补 `hal_server_domain(dom, hal_xxx)` |

---

## 十一、一句话总结

SELinux 是 Android 14 驱动落地的**最后一公里**：内核驱动 `device_create()` 出节点后，必须在 `file_contexts` 给 `/dev/hello` 打 `hello_device` 标签，在 `hello.te` 给 HAL 的 domain 授权 `chr_file`/`sysfs` 访问，并配齐 `init_daemon_domain` + `hal_server_domain` 宏；否则 enforcing 模式下 `open()` 必被 `avc: denied` 拦死。调试靠 `dmesg | grep avc` + `audit2allow` 定位，但最终规则要收敛成最小权限，避免 neverallow 越界。

---

> **文档版本：** v1.0
> **适用：** Android 14 (android14-6.1) 厂商驱动 / HAL SELinux 策略
> **最后更新：** 2026-08-10
