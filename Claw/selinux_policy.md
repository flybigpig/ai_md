# SELinux 策略 深读笔记（AOSP 14）

## 1. 位置与结构
策略源在 `system/sepolicy/`：
- `public/` — 跨版本稳定的 type/attribute/class（vendor 也能引用）
- `private/` — 平台私有规则
- `vendor/` — 厂商策略（对应 `/vendor/etc/selinux`）
- `prebuilts/api/<ver>/` — 各版本冻结快照（兼容性用）
- `REQUIRED` — 必须包含的模块清单

产物：`/system/etc/selinux/`(plat) 与 `/vendor/etc/selinux/`(vendor)，开机由 `init` 加载。

## 2. 关键文件类型
| 文件 | 作用 |
|---|---|
| `*.te` | type enforcement 规则（allow/neverallow/type 定义） |
| `file_contexts` | 路径 → type |
| `service_contexts` | binder 服务名 → type |
| `hwservice_contexts` | hwbinder 服务 → type |
| `property_contexts` | 系统属性 → type |
| `seapp_contexts` | app 进程 → domain |
| `genfs_contexts` | 伪文件系统(如 proc)标签 |
| `mac_permissions.xml` | 签名 → seinfo |

## 3. 给新 native 服务加策略（典型）
```te
# private/myservice.te
type myservice, domain;
type myservice_exec, exec_type, file_type;
init_daemon_domain(myservice)        # 从 init 启动的域
binder_service(myservice)            # 允许注册 binder 服务
allow myservice system_server:binder { call transfer };
```
```contexts
# file_contexts
/system/bin/myservice u:object_r:myservice_exec:s0
# service_contexts
myservice u:object_r:myservice_service:s0
```
```te
# 在 private/ 对应 type 声明
type myservice_service, service_manager_type;
```
编译：`make sepolicy`（或整编）；产物在 `out/.../obj/ETC/`.

## 4. 调试
```bash
adb shell dmesg | grep avc            # 内核态拒绝
adb logcat | grep avc                 # 用户态
# 临时确认是否 SELinux 引起:
adb shell setenforce 0                # permissive(仅 userdebug/eng)
# 用拒绝日志生成候选规则(仅调试!):
adb shell dmesg | grep avc > avc.log
audit2allow -i avc.log
```
⚠️ `audit2allow` 给的是"能过"的规则，不是"正确"的规则——最终要手工精炼，且不能破坏 `neverallow`。

## 5. 注意（AOSP 14 / Treble）
- 平台策略：vendor 不可引用 `private/` 里的 type，只能用 `public/`——新增跨域交互的 type 要放 `public/` 或走 `versioned` 接口。
- `neverallow` 很严，`make sepolicy` 会在违反时直接失败。
- 改完务必 `setenforce 1` 后实跑验证，别停留在 permissive。

## 6. 实战小项目
给你已有的 `hal_led_example` 补一份完整策略：`.te` + `file_contexts` + `service_contexts`，做到 `setenforce 1` 下不报 avc。
