# 《AOSP14 添加 HAL 与系统服务文档》分析与审查（Android 14）

> 被分析文档：`C:\Users\YTO-02231406\Downloads\AOSP14_添加HAL与系统服务文档.md`
> 基线：Android 14 (UpsideDownCake, API 34, FCM 8/9)。审查目标是「核对 Android 14 技术准确性 + 找出生产化前必须补的点」。

## 一、结论前置（TL;DR）

- **文档质量：高。** 覆盖一条真实可编的调用链 `App → HelloService(system_server) → IHello HAL(vendor)`，且明确声明 `sepolicy / check-vintf-all / treble_sepolicy_tests / compat_test / fuzzer_bindings_test / sepolicy_freeze_test` 全部通过——这是它**可信度远高于网文**的关键。
- **架构正确：** HAL 用 AIDL（`@VintfStability`）+ VINTF 声明 + `default` 进程；系统服务走 `Context 常量 → SystemServiceRegistry → SystemServer.addService` 三段式；App 用 `platform_apis: true` + `certificate: "platform"` 引用 `@hide` Manager。这条链路在 14 上是对的。
- **必须改的 1 个真问题：** `HelloService` 构造里直接 `ServiceManager.waitForService(HAL)` **会阻塞 system_server 启动**。其余 5 个是收紧 / 容错 / 车载适配项（见 §三）。

---

## 二、架构链路（文档主线）

```mermaid
flowchart TB
    APP[MySystemApp<br/>packages/apps/MySystemApp<br/>platform_apis + platform签名]
      -->|getSystemService HelloManager| MGR[HelloManager @hide<br/>frameworks/base/core]
    MGR -->|IHelloService.Stub.asInterface| SS[HelloService<br/>system_server 进程<br/>com.android.server.hello]
    SS -->|waitForService IHello/default| SM[servicemanager /dev/binder]
    SM --> HAL[IHello HAL 实现<br/>vendor 进程<br/>hal_hello_default 域]
    SS -.注册.-> SM2[servicemanager<br/>addService "hello"]
    APP -.find.-> SM2
```

三个接入点（系统服务标准做法，文档 §2.4 正确）：
1. `Context.HELLO_SERVICE = "hello"`（常量）
2. `SystemServiceRegistry.registerService(...)`（Manager 工厂）
3. `SystemServer.startOtherServices()` → `addService("hello", new HelloService())`

---

## 三、关键正确性审查（逐条核对 Android 14）

| 文档断言 | Android 14 实际 | 判定 |
|---|---|---|
| HAL 用 `aidl_interface` + `backend.{java,cpp,ndk}` + `gen_trace` | 14 上 AIDL HAL 标准写法，正确 | ✅ |
| `versions: ["1"]` + `aidl_api/.../1/.hash` 由 `hash_gen.sh` 计算 | 算法描述准确（逐个 sha1sum → 拼 `latest-version:N` → 再 sha1sum），占位符 `0000...` 必失败 | ✅ |
| VINTF 声明写 `compatibility_matrix.8.xml` + `.9.xml` | 14 设备 FCM 是 **8**，`.9` 为开发期前向矩阵；两者都加无害且 `check-vintf-all` 只校验设备矩阵 | ✅ |
| `system/sepolicy` 改完要同步 `prebuilts/api/34.0/` | `sepolicy_freeze_test` 强制比对，漏同步必挂，文档点到了 | ✅ |
| 新 public 类型加 `*.ignore.cil` 的 `new_objects`，**不**进 `.cil` 的 `expandtypeattribute` | 正确——进 `.cil` 会触发 `33.0_compat_test Failed to resolve expandtypeattribute` | ✅ |
| `service_fuzzer_bindings.go` 加 `EXCEPTION_NO_FUZZER` | AOSP 强制每个 servicemanager 服务有 fuzzer 绑定，正确 | ✅ |
| `PRODUCT_PACKAGES` 只加 `cc_binary`，vintf fragment 随二进制自动打包 | **这是关键 gotcha**，文档正确捕获：`android.hardware.hello-default` 不是合法 ninja 目标，单独 `m` 报 `unknown target` | ✅ |
| HAL 域加裸 `udp_socket` 被 `hal_neverallows.te` 拦截 | 正确，HAL 间通信统一 `binder_call` | ✅ |
| 验证用 `service list | grep hello` | `service list` 即遍历 servicemanager，确认 `checkService` 成功，正确 | ✅ |

**结论：技术主干无误，可直接照编。**

---

## 四、生产化前必须补 / 修的 6 个点（按重要性）

### 🔴 1. `waitForService` 在构造里阻塞 system_server 启动（最该改）
`HelloService` 构造函数：
```java
public HelloService(Context context) { connectHal(); }  // waitForService 同步阻塞
```
`ServiceManager.waitForService(name)` 在 `maxWaitTimeMs=0` 时**无限轮询等待**。若 `vendor.hello-default` 因 init 未起 / 崩溃 / SELinux 拒绝而没注册，system_server 在 `startOtherServices()` 阶段直接卡死，整机起不来。
**改法（三选一，推荐组合）：**
- 构造里**不要连 HAL**，改为首次调用 `getGreeting()` 时懒连接 + 带超时的 `waitForService(name, 3000)`；
- 或 `mHal` 连上后 `linkToDeath()`，HAL 死后自动置空并重连；
- 至少把 `waitForService` 换成**有界超时**，避免 boot 挂死。

### 🟠 2. `hello-default.rc` 内容缺失（车载 HAL 必填）
文档只提了 service 名和二进制路径，**没展示 rc 实际内容**。vendor HAL 至少要有：
```
service vendor.hello-default /vendor/bin/hw/android.hardware.hello-service.example
    class hal
    user system
    group system
    seclabel u:object_r:hal_hello_default_exec:s0   # 或 init 用 domain 自动 transition
    on boot                 # 或 interface aidl（见下）
```
缺 `user/group` 会以 `root` 起进程，SELinux `init_daemon_domain` 仍会 trans 到 `hal_hello_default`，但权限面过大；车载量产一律要降权。

### 🟠 3. 完全没提 Lazy HAL（车载省电必选项）
文档用的是常驻 `vendor.hello-default`。座舱场景应改为 **lazy AIDL HAL**：
- rc 里 `interface aidl android.hardware.hello.IHello/default` + `disabled` + `oneshot`；
- HAL 实现里用 `LazyServiceRegistrar::registerService` 而非裸 `addService`；
- 无客户端时由 servicemanager 的 `registerClientCallback` 引用计数降到 0 自动退出（对应我之前讲的 servicemanager C++ 版 `handleClientCallbacks`）。
这能直接省掉一个常驻 vendor 进程，是车载定制高频动作。

### 🟡 4. `hello_service` 标了 `app_api_service`，暴露面偏大
`app_api_service` 让**任意 app**（不止平台 app）都能 `ServiceManager.getService("hello")` 甚至 `service call hello` 直连。既然 `HelloManager` 是 `@hide`、只给平台 app 用，建议：
- 改成只挂 `system_server_service`（仅 system_server 能 add/find 的常规服务域），或
- 保留 `app_api_service` 但给 `IHelloService` 方法加 `<permission>` 校验。
否则任意三方 app 经反射就能调你的系统服务。

### 🟡 5. 缺 `hash_gen.sh` 实际命令 + 版本演进说明
文档讲了对 `.hash` 的算法，但没给命令。补一句：
```bash
development/tools/aidl/gen_trace  # 不对
# 实际：m android.hardware.hello 后若未 freeze，直接跑
AIDL_API_FREEZE=true m android.hardware.hello   # 让 soong 算并写入真实 hash
```
另外提醒：**后续给 `IHello` 加方法必须 `versions` bump 到 `["1","2"]` 并重新 freeze**，否则 `aidl` 编译报 `API currently not frozen`。文档只覆盖了初次冻结。

### 🟡 6. HAL 死后 `mHal` 不重连
`mHal` 在构造（或首次调用）时拿一次后永久持有。HAL 进程崩溃重启后，`mHal.getGreeting()` 会抛 `DeadObjectException` / 返回 unavailable，service 侧不会自动恢复。
**改法：** `mHal` 包一层，`linkToDeath` 触发置空，下次调用重连；方法里对 `RemoteException` 做重连兜底（文档现在只是 `if (mHal != null)`，DeadObject 时 mHal 非 null 但已死）。

---

## 五、与前面几轮分析的衔接

1. **servicemanager C++ 版（前轮已讲）**：本文档 HAL 注册到 `servicemanager`，`service list` 验证正是 `checkService`；`waitForService` 内部依赖 VINTF `isDeclared` + `tryStartService` 按需拉起——这正是我之前强调「AIDL HAL 没在 VINTF 声明就 `waitForService` 拿不到」的落地印证。
2. **dumpsys（前两轮）**：新增的 `hello` 系统服务、`android.hardware.hello.IHello/default` HAL，都能用 `dumpsys <svc>`（若实现了 `dump()`）或 `service list` 观察，是验证这套链路的标准手段。
3. **车载视角**：本文档是「新增 AIDL HAL 全链路」的最小正确基线。要上车，把 §四的 **2（rc 降权）+ 3（lazy HAL）+ 1（不阻塞 boot）** 补齐，再叠加 SELinux vendor 域收紧，就是一份可直接量产的交付。

---

## 六、一句话总结

> 文档主干（AIDL HAL + VINTF + 三段式系统服务 + platform App）在 Android 14 上**技术正确且已构建验证**，可放心照编；上线前只需补齐 **「构造不阻塞 boot 的懒连接 / HAL 重连」「rc 降权」「lazy HAL」「app_api_service 收紧」「hash 演进」** 五处，即可从 demo 升级为车载量产级交付。
