# Android Framework 面试题 · 权限模型全景与隐私边界演进（2026-08-30，第四十七篇）

> 本篇落点：把"Android 权限"从面试八股里最常被讲浅的 `android.permission` 一层，向下凿穿到 **Runtime Permission -> AppOps -> SELinux MAC -> Binder 调用方校验** 四层权限闸门，并反向接入 **Android 17 隐私默认三连击（Contacts Picker / ACCESS_LOCAL_NETWORK / SMS OTP 3h 延迟）+ PQC + 原生 DCL 硬化** 这些 2026 真·新边界。补上全系列长期隐含、却从未独立成篇的"权限与隐私底座"真缺口（呼应 8/29 SDK Runtime 沙箱、8/21 App Lock 安全链、反复出现的 `getCallingUid` 不可信）。
>
> Baseline：**Android 14（UpsideDownCake, API 34）**，源码路径对齐 `android-14.0.0_rXX`；A17 增量标注 `API 37`。

---

## 0. 当日热点锚定（2026-08-30）

A17 (API 37) stable 已于 2026-06 落地，隐私相关默认行为发生结构性变化，面试里"权限模型演进"从冷门题一跃成高频：

| 新边界 | 形态 | 取代的旧范式 | 面试价值 |
|---|---|---|---|
| **系统级 Contacts Picker** (`ACTION_PICK_CONTACTS`) | 按字段（email/电话）临时、会话级授权，工作/私人 Profile 分离；一次性快照不跟踪后续变更 | 宽泛 `READ_CONTACTS` | 把"权限 -> 数据选择"范式翻转 |
| **`ACCESS_LOCAL_NETWORK`** 运行时权限（API 37） | 局域网发现/连接必经此权限或系统设备选择器，归入 `NEARBY_DEVICES` 组 | 此前 App 默认可静默扫描 LAN 做网络指纹 | 关掉"无网也能画像"的追踪后门 |
| **SMS OTP 3 小时延迟** | 非目标/非默认 SMS 应用的 OTP 短信读取延迟 3h；WebOTP 域名不匹配全延迟 | `RECEIVE_SMS`/`READ_SMS` 即时读 | 封堵短信验证码静默拦截 |
| **Location Button**（系统渲染） | 单次会话精确位置；位置访问常驻指示器（对齐麦克风/相机） | 永久精确位置授权 | 位置透明化 |
| **EyeDropper API** (`ACTION_OPEN_EYE_DROPPER`) | 系统级取色，免 screenshot/media projection 权限 | 截屏/投影敏感权限 | 缩小截屏滥用面 |
| **PQC 后量子加密** | Keystore 生成 `ML-DSA` 签名密钥；APK v3.2 混合签名（经典 + ML-DSA） | 仅经典签名 | 体系级安全演进 |
| **Secure DCL 扩展到原生库** | SDK 37 起 `System.load` 加载的 `.so` 必须只读，否则 `UnsatisfiedLinkError` | 仅 DEX/JAR 只读（A14 引入） | 动态代码加载硬化 |

> 联网锚定（2026-08-30）：Android 17 is Here 官方博客、Android Authority 8/17 追踪、DigitBin 隐私默认解读均确认以上；2026 面试高频区（juejin/CSDN mzlw 题库）确认 Binder/`Handler`/AMS/View/权限/SELinux 仍是资深岗分水岭，其中"权限被授予却仍被拒"是死亡陷阱题。

---

## 1. 权限模型四层闸门总览（先建立全局心智模型）

很多候选人把"权限"等同于 `AndroidManifest.xml` 里的 `<uses-permission>` 和弹窗。真实 Android 权限是 **四层递进闸门**，任意一层不通过，调用即被拒：

```
App 调用受保护 API
   |
   v
[1] 安装期权限声明 <uses-permission>  -- PackageManagerService 解析, 不解析=安装失败/找不到
   |
   v
[2] Runtime Permission (危险权限)  -- PermissionManagerService + PermissionController 弹窗授予
   |                                 (普通/签名权限安装即给, 危险权限需用户授权)
   v
[3] AppOps (操作级开关)  -- AppOpsService.noteOp/startOp, 可被后台策略/管理员动态拒绝
   |                        (注意: AppOps 与 runtime permission 是两套独立数据库!)
   v
[4] SELinux MAC (内核级)  -- 进程域(appdomain/untrusted_app) 对 目标类型 的 allow/neverallow
                            (即使上面全过, avc denied 仍直接 EPERM; 这是 Linux kernel 强制)
   |
   v
Binder 调用方 UID/PID 校验 (getCallingUid + checkPermission + enforecePermission)
   |
   v
真正执行
```

**考官视角一句话**：`android.permission` 只是第一、二层；**AppOps 是系统侧的"操作开关"，SELinux 是内核侧的"强制边界"，`getCallingUid` 是 Binder 侧的"调用方身份"。** 四层任一失守/命中拒绝，App 都会拿到 `SecurityException` 或静默失败。

---

## 2. 专题一：Runtime Permission 演进与源码级授予链路

### 2.1 问题
Android 危险权限的授予流程是怎样的？从 `Activity.requestPermissions()` 到 `checkPermission()` 返回 `PERMISSION_GRANTED`，中间经过了哪些系统服务？A14 把权限管理从 PMS 拆成了什么？A17 又新增了哪些隐私边界？

### 2.2 答案解析（带 AOSP 14 源码路径/方法名）

**申请侧入口**
- `android.app.Activity#requestPermissions(String[], int)` (`frameworks/base/core/java/android/app/Activity.java`) -> 转 `ActivityThread` -> 经 `IActivityTaskManager` Binder 到 `ActivityManagerService`（ATMS 实际处理请求弹出，`ActivityTaskManagerService#requestPermissions`）。注意：早期直接走 AMS，A14 起启动/权限相关 UI 协调更多落在 ATMS + `PermissionController` app。

**授予与存储（A14 关键拆分）**
- A14 把权限从 `PackageManagerService` 拆出独立服务：**`PermissionManagerServiceImpl`**（`frameworks/base/services/core/java/com/android/server/pm/permission/PermissionManagerServiceImpl.java`）。
  - 核心方法：`checkPermission(String, String, int)`、`grantRuntimePermission()`、`revokeRuntimePermission()`、`getPermissionFlags()`。
  - 危险权限用户决策 UI 在独立 app **PermissionController**（`packages/apps/PermissionController/`，`GrantPermissionsActivity` / `AppPermissionActivity`），以 `system` 身份运行、独立 UID，避免被普通 App 伪造授权结果。
- 安装期权限解析：`AndroidPackage` / `ParsingPackage` 解析 `AndroidManifest` 的 `<uses-permission>`，普通/签名权限安装即固化为 granted；危险权限进入"待用户授权"状态，存于 `PermissionManagerService` 的运行时授权表（底层落 `runtime-permissions.xml`，开机由 `PermissionManagerService` 恢复）。
- A14 新增 **`PermissionPolicyService`**：在 `ACTION_PACKAGE_ADDED` / 权限状态变化时，按 `PermissionPolicy` 自动 grant/deny（如 `ROLE` 关联权限）、处理 `setPermissionReviewRequired` 这类合规策略。

**演进时间线（面试高频追问素材）**
```
Android 6.0 (API 23): 引入 Runtime Permission, 危险权限必须运行时弹窗
Android 9  (API 28): 后台位置限制 (ACCESS_BACKGROUND_LOCATION 单独一类)
Android 10 (API 29): 作用域存储(Scoped Storage) 初版; 只读外部存储权限
Android 11 (API 30): 一次性授权(only this time); 自动重置闲置App权限
Android 12 (API 31): 大致位置(approximate) 二分; 麦克风/相机/位置使用指示器
Android 13 (API 33): 细分媒体权限 READ_MEDIA_IMAGES/VIDEO/AUDIO; 通知权限 POST_NOTIFICATIONS
Android 14 (API 34): 精确闹钟权限治理(SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM); 私密空间 Private Space
Android 17 (API 37): Contacts Picker / Location Button / EyeDropper / ACCESS_LOCAL_NETWORK / SMS OTP 延迟
```

**A17 隐私默认三连击（落到源码语义）**
- `ACTION_PICK_CONTACTS`：App 不再申请 `READ_CONTACTS`，改用系统 Picker 选"具体字段 + 具体联系人"，授权是会话级、不持久、不跟踪后续变更。对应 `ContactsProvider` 增加按字段授权校验，传统 `READ_CONTACTS` 授予路径对 Picker 场景不再必要。
- `ACCESS_LOCAL_NETWORK`：新增 `PROTECTION_DANGEROUS` 普通权限，归入 `NEARBY_DEVICES` 权限组（`frameworks/base/core/res/AndroidManifest` 中定义 `android.permission.ACCESS_LOCAL_NETWORK`）。`NetworkStack` / `WifiManager` 相关 LAN 发现路径增加权限检查；未授权且未走系统设备选择器的 App 的 mDNS/ARP 扫描被拒。
- SMS OTP 延迟：`SmsProvider` / `SmsRetriever` 路径对 OTP 短信增加 3h 读取延迟（非目标 App / 非默认 SMS 应用）。

### 2.3 易错点
- **误区**："用户点了允许，`checkSelfPermission` 就一定返回 `GRANTED`。" —— 错。危险权限 granted 只过第 1、2 层；第 3 层 AppOps 可能被后台策略关掉（如 `AppOpsManager.MODE_IGNORED`），第 4 层 SELinux 仍可能 `avc denied`。
- **误区**："`requestPermissions` 直接调 `PackageManager`。" —— 实际授权决策 UI 在 `PermissionController` 独立进程，ATMS 协调弹窗，PMS 只存结果。
- **误区**："`POST_NOTIFICATIONS` 在 A13 之前就有。" —— 通知权限是 A13 (API 33) 才成危险权限；之前 `INTERNET`/`ACCESS_NETWORK_STATE` 等是普通权限。
- **误区**："一次性授权 = 临时 `android.permission`。" —— 一次性是 AppOps/session 层的 TTL，不是改写 `runtime-permissions.xml`。

### 2.4 考官高频连环追问（标准答案）
- **Q：权限 granted 了，调用相机还是 `SecurityException`，怎么排查？**
  A：先分四层——① `checkSelfPermission` 是否真 `GRANTED`；② AppOps `OP_CAMERA` 的 mode 是不是 `ALLOWED`（后台/省电策略可能改 `MODE_IGNORED/ERRORED`）；③ SELinux `untrusted_app` 域对 `camera_device` 类型是否 `allow`（系统 App 若有 `camera` 域例外）；④ 调用方 UID 与 `getCallingUid` 是否对得上（跨 Binder 时最容易踩）。
- **Q：危险权限和普通权限的区别？**
  A：普通/签名权限安装即授予，不弹窗；危险权限（如位置/通讯录/相机）必须运行时用户显式授权，且可被撤销；`signature` 权限还要求签名一致（如系统签名权限 `android.permission.READ_FRAME_BUFFER`）。

---

## 3. 专题二：Scoped Storage 与媒体权限演进

### 3.1 问题
Android 10 起的作用域存储到底改了什么？`READ_EXTERNAL_STORAGE` 为什么逐步废弃？Photo Picker 和 `MediaStore` 各自承担什么角色？

### 3.2 答案解析（AOSP 14 路径）
- **Scoped Storage 核心**：App 默认只能访问自身 `getExternalFilesDir()`、媒体集合（`MediaStore`）中自己创建的项，以及通过 `Storage Access Framework`（SAF）用户显式选的文件。无法直接 `new File("/sdcard/...")` 遍历全局。
- **媒体权限细分（A13）**：`READ_EXTERNAL_STORAGE` 在 API 33+ 废弃，拆为 `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO`。`MediaStore` (`frameworks/base/media/java/android/media/MediaStore.java`) 是访问媒体集合的统一接口，provider 实现在 `packages/providers/MediaProvider/`（`MediaProvider.java`，override `query/insert` 时按调用方权限 + owner 过滤）。
- **Photo Picker**（A13 起，A17 可定制）：`Intent.ACTION_PICK_IMAGES` 启动系统 Picker，返回 `content://` URI，**完全不需要任何存储权限**。A17 新增 `PhotoPickerUiCustomizationParams`（竖向缩略图，适配视频社交 App）。底层走 `MediaStore` + 临时授权 URI 的 `UriPermission`。
- **SAF / DocumentsProvider**：`android.externalstorage` / `com.android.externalstorage` 提供 DocumentsProvider 接入，用户经 `ACTION_OPEN_DOCUMENT` 授权单个树/文件，落 `UriPermission`（可在 `Activity.onActivityResult` 后 `takePersistableUriPermission` 持久化）。

### 3.3 易错点
- **误区**："用 `MediaStore` 就能读别人 App 的所有图片。" —— 只能读其他 App 创建的**媒体**（图片/视频/音频），且受媒体权限约束；非媒体文档必须走 SAF。
- **误区**："`MANAGE_EXTERNAL_STORAGE` 是 `READ_EXTERNAL_STORAGE` 的升级版随便申请。" —— 这是 `signature|appop|role` 级别权限（all files access），Google Play 强审核，仅文件管理类 App 可豁免，滥用直接拒审。
- **`content://` URI 权限会过期**：跨进程传 URI 必须带 `FLAG_GRANT_READ_URI_PERMISSION`，否则接收方 `Permission Denial`。

### 3.4 高频追问
- **Q：`File` API 在 Android 11 后还能直接读 `/sdcard/DCIM` 吗？**
  A：不能（除非持有 `MANAGE_EXTERNAL_STORAGE` 或被 SAF 授权该路径）。`MediaStore` + `openFileDescriptor` 才是合规路径。
- **Q：Photo Picker 返回的 URI 怎么持久化？**
  A：`takePersistableUriPermission(uri, FLAG_GRANT_READ)` 后存 URI，重启后仍可用（受 `UriPermission` 生命周期管理）。

---

## 4. 专题三：位置权限细粒度与 A17 位置新边界

### 4.1 问题
`ACCESS_FINE_LOCATION` 和 `ACCESS_COARSE_LOCATION` 的区别？Android 12 的"大致位置"二分是怎么实现的？A17 又改了什么？

### 4.2 答案解析（AOSP 14 路径）
- **二分权限**：`FINE`（GPS/WiFi/BT 融合，米级）、`COARSE`（小区/WiFi 粗定位，~2km）。A12 起即使申请了 `FINE`，用户可在弹窗选"大致位置"，系统把 `FINE` 解析降级为 `COARSE` 效果。
- **大致位置算法**：`LocationManagerService`（`frameworks/base/services/core/java/com/android/server/location/LocationManagerService.java`）结合 `Gnss` / `NetworkLocationProvider`，在 `getLastLocation` / `requestLocationUpdates` 时按调用方被授予的权限档位 + AppOps 决定返回精度。`coarse` 是通过对 `fine` 结果做空间离散化（网格量化）实现的。
- **A17 新增（8/30 锚定）**：
  1. **密度自适应大致位置（density-based coarse location）**：旧版固定 2km 网格，低密度区隐私不足；A17 改为按人口密度动态缩放网格（人少处放大），保证跨城乡一致隐私。
  2. **位置访问常驻指示器**：对齐麦克风/相机，非系统 App 访问位置即显示状态栏指示器，点按进"最近使用"管理。
  3. **Location Button（系统渲染）**：App 可嵌入系统渲染的位置按钮，仅本次会话授权精确位置，关 App 即失效，免去反复弹窗。
  4. **位置权限弹窗重设计**：`Precise`/`Approximate` 视觉区分强化，引导用户选最小够用档位。

### 4.3 易错点
- **误区**："申请 `FINE` 就一定能拿到 GPS 精度。" —— 用户选了大致位置 + AppOps `MODE_IGNORED` 都会降级；后台位置还要单独的 `ACCESS_BACKGROUND_LOCATION` 且 A10+ 后台受限。
- **误区**："后台位置只要 `FINE` 就行。" —— 后台必须显式 `ACCESS_BACKGROUND_LOCATION`，且 A10 起需分两次授权、开发者选项可强制后台限制。

### 4.4 高频追问
- **Q：为什么 `getLastKnownLocation` 有时返回 null 即使有权限？**
  A：位置提供器未就绪 / 省电模式关闭 GPS / AppOps 拒绝 / 后台节流（A10+ 后台限频）都可能；应改用 `requestLocationUpdates` + 回调，并区分前台/后台权限。

---

## 5. 专题四：AppOpsManager —— Runtime Permission 之上的"操作级开关"

### 5.1 问题
`AppOpsManager` 和 `android.permission` 有什么区别？为什么权限 granted 仍可能被拒？

### 5.2 答案解析（AOSP 14 路径）
- **两套独立数据库**：`android.permission` 是"能力是否授予"（用户级）；`AppOps`（`frameworks/base/services/core/java/com/android/server/AppOpsService.java`，`frameworks/base/core/java/android/app/AppOpsManager.java`）是"该操作当前是否被允许执行"，受系统策略/后台/省电/管理员动态控制。
- **关键 API**：`AppOpsManager.noteOp(int op, int uid, String pkg)` / `checkOp()` / `startOp()` / `finishOp()`。服务侧（如 `CameraService`、`AudioService`、`LocationManagerService`）在真正执行前调 `noteOp`；返回 `MODE_ALLOWED` 才继续，`MODE_IGNORED`/`MODE_ERRORED` 则拒绝或静默丢弃。
- **Op 示例**：`OP_CAMERA`(0)、`OP_RECORD_AUDIO`(27)、`OP_FINE_LOCATION`、`OP_COARSE_LOCATION`。每个 op 有 `allow/ignore/deny/foreground` 等 mode。
- **权限与 AppOps 的映射**：危险权限授予后，系统一般把对应 op 设为 `allow`；但 AppOps 可被独立改写（如后台摄像头限制把 `OP_CAMERA` 置 `foreground` mode，后台调用即拒）。

### 5.3 易错点
- **核心死亡陷阱**：`ContextCompat.checkSelfPermission == GRANTED` **不等于** 操作能成功。必须再查 AppOps。例如前台服务摄像头、后台录音、画中画摄像头都被 AppOps 模式限制。面试里"权限被授予却仍被拒"的标准答法就是"过了 permission 但没过 AppOps / SELinux"。
- **误区**："AppOps 是 App 自己管的。" —— AppOps 由系统 `AppOpsService` 持有，App 只能查自己的 op，不能改（改需 `android.permission.UPDATE_APP_OPS_STATS`，系统级）。

### 5.4 高频追问
- **Q：怎么在框架层排查"权限有了但相机打不开"？**
  A：`adb shell appops get <pkg>` 看 `CAMERA` 的 mode；`dumpsys appops` 看最近 op 记录；再看 `OP_CAMERA` 是否被背景策略置 `foreground`；最后看 SELinux `avc`。

---

## 6. 专题五：SELinux —— Framework 权限的最后一道内核级 MAC

### 6.1 问题
为什么 App 声明并授予了 `android.permission.CAMERA`，访问 `/dev/video*` 仍 `avc denied`？SELinux 在权限模型里处在哪一层？

### 6.2 答案解析（AOSP 14 路径）
- **SELinux 是强制访问控制（MAC）**，运行在 Linux kernel，任何用户态权限（包括 root，受限域）都无法绕过。Android 用 SELinux 把每个进程打进**域（domain）**，每个资源打**类型（type）**，策略文件用 `allow domain type:class { perms }` 描述。
- **App 域**：普通第三方 App 属 `untrusted_app`（A14 拆分为 `untrusted_app` / `untrusted_app_25` / `untrusted_app_27` 等按 target SDK 版本分域，体现"对新版本收紧"策略）；系统 App 属 `platform_app` / `system_app`。
- **策略源**：`system/sepolicy/`（A14 起 `system/sepolicy/private/*.te` + `prebuilts/api`），如 `untrusted_app.te` 定义 `untrusted_app` 域能访问什么。`neverallow` 规则禁止危险的域交叉（如 `untrusted_app` 永远不能 `write` 系统分区）。
- **设备节点标签**：`/dev/video*` 被标 `video_device` 类型，策略通常只允许 `camera` 域（cameraserver）`read/write`，`untrusted_app` 无此 allow，故 `avc denied` -> `EPERM`。App 通过 `Camera2` API 走 cameraserver（正确域）才合法，而不是直接 open `/dev/video*`。
- **Binder 的 SELinux 校验**：ServiceManager 查找 handle 时，`service_manager` 域对 `service_manager_type` 做 `add`/`find` 检查；Binder 调用本身有 `binder_call(src_domain, target_domain)` 规则（如 `untrusted_app` 能否 call `system_server` 的某服务由策略决定）。

### 6.3 易错点
- **误区**："root 就能干任何事。" —— SELinux enforcing 模式下，即使是 root（`su` 进 `su` 域），只要策略 `neverallow` 仍被拒。
- **误区**："`android.permission` 是权限的全部。" —— permission 是 framework 层软约束；SELinux 是 kernel 层硬约束，后者优先级更高、不可被 App 规避。
- **误区**："SELinux 只在开机初始化起作用。" —— 运行时每次访问（open/connect/ioctl/binder_call）都做 `avc` 检查，拒绝会写 `/proc/.../avc` 并 `dmesg`/`logcat` 可见。

### 6.4 高频追问
- **Q：开发一个需要访问自定义 `/dev/xxx` 的系统 App，要改几处？**
  A：① `device/` 下 `file_contexts` 给 `/dev/xxx` 打类型；② `sepolicy` 新增 `type xxx_device, dev_type;` + `allow my_appdomain xxx_device:chr_file { open read write ioctl };`；③ `neverallow` 冲突检查（不能让 `untrusted_app` 拿到）；④ 可能还要 `service_contexts` / `hwservice_contexts`（HAL 场景）。这正是 MTK/厂商定制常改的文件（呼应 8/17 HAL/驱动篇）。

---

## 7. 专题六：Binder 调用方校验与 `getCallingUid` 不可信

### 7.1 问题
系统服务怎么确认"是谁在调我"？为什么 `getCallingUid()` 在某些场景不可信？`clearCallingIdentity` 是做什么的？

### 7.2 答案解析（AOSP 14 路径）
- **调用方身份**：Binder 事务中，驱动把调用方 `uid`/`pid` 塞进 `binder_transaction`，native 侧 `IPCThreadState::getCallingUid()`（`frameworks/native/libs/binder/IPCThreadState.cpp`）取出；Java 侧 `android.os.Binder.getCallingUid()` / `getCallingPid()`（`frameworks/base/core/java/android/os/Binder.java`）。
- **权限校验**：服务侧用 `ActivityManagerService.checkPermission(String, int pid, int uid)` / `Context.checkPermission` / `enforcePermission` 校验"该 uid 是否持有某 `android.permission`"。例如 `ActivityManagerService` 的 `checkComponentPermission`。
- **`getCallingUid` 不可信的两大场景**（全系列反复强调）：
  1. **跨 VM / 跨沙箱 RPC**（pKVM/AVF、SDK Runtime）：AVF 里 pVM 通过 `RpcServer`/`VSock` 出来的 Binder 调用，宿主侧 `getCallingUid` 可能是 `SYSTEM_UID` 或某个代理 UID，并非真实 App UID（呼应 8/2 pKVM、8/29 SDK Runtime：sandbox 内调用映射回宿主时 UID 被改写，必须靠 `SharedLibraryInfo`/sandbox 令牌二次校验）。
  2. **被中间代理转发**：某系统服务 A 代 App B 去调服务 C，C 看到的 `getCallingUid` 是 A 的 UID 不是 B 的；若 C 据此做权限决策就会 confused-deputy（呼应 8/21 App Lock：AI agent 经系统服务转发读锁 App 数据、`getCallingUid=SYSTEM_UID` 不可直接信）。
- **`clearCallingIdentity` / `restoreCallingIdentity`**：服务内部需要"以自己身份"去做一个受权限保护的操作时，先 `clearCallingIdentity()` 临时清除调用方身份（降级为自身 UID），做完 `restoreCallingIdentity()` 恢复。典型用于 `system_server` 内部跨 Binder 调用，避免把调用方 UID 带进下一跳引发错误拒绝/越权。

### 7.3 易错点
- **误区**："`getCallingUid()` 一定等于发起 App 的 UID。" —— 上面两场景直接打脸；任何"跨进程代理/跨 VM"链路都要二次身份校验。
- **误区**："`checkPermission` 只看 permission 字符串。" —— 它结合 `getCallingUid` + `getCallingPid`，且背后还要过 AppOps + SELinux（见专题四、五）。
- **误区**："`clearCallingIdentity` 是提权。" —— 它是"清除调用方身份、以服务自身身份执行"，是临时降权/身份重置，不是提权；不匹配 `restore` 会酿成权限漏洞。

### 7.4 高频追问
- **Q：`enforcePermission` 抛 `SecurityException` 前会先查什么？**
  A：先 `getCallingUid`/`getCallingPid` -> `checkPermission` -> 若 uid 非系统还需 AppOps -> 跨进程还要 SELinux `binder_call`；全过才放行，任一不过抛异常。

---

## 8. 专题七：A17 隐私边界汇总 + 跨版本演进对照总表

### 8.1 A17 隐私安全新边界（2026 真·热点，落到面试题）
- **默认隐私三连击**：Contacts Picker（字段级/会话级，替代 `READ_CONTACTS`）、`ACCESS_LOCAL_NETWORK`（关 LAN 静默扫描）、SMS OTP 3h 延迟（关验证码拦截）。
- **位置透明化**：常驻位置指示器 + Location Button 单次会话精确 + 密度自适应大致位置。
- **EyeDropper API**：系统取色，免截屏/投影敏感权限。
- **PQC 后量子**：Keystore `ML-DSA` + APK v3.2 混合签名。
- **Secure DCL 扩展到原生库**：SDK 37 起 `System.load` 的 `.so` 必须只读，否则 `UnsatisfiedLinkError`。

### 8.2 权限/隐私跨版本演进对照表（A6 -> A17）

| 版本 | 权限/隐私里程碑 | 面试可讲点 |
|---|---|---|
| A6 (23) | Runtime Permission 引入 | 危险权限运行时弹窗；`checkSelfPermission` 时代 |
| A9 (28) | 后台位置单独权限 | `ACCESS_BACKGROUND_LOCATION` |
| A10 (29) | Scoped Storage 初版 | 全局文件访问受限；`MANAGE_EXTERNAL_STORAGE` 例外 |
| A11 (30) | 一次性授权 + 闲置重置 | session/ TTL 概念进权限模型 |
| A12 (31) | 大致位置二分 + 麦克风/相机/位置指示器 | 精度二分 + 透明化 |
| A13 (33) | 媒体权限细分 + 通知权限 | `READ_MEDIA_*` / `POST_NOTIFICATIONS` |
| A14 (34) | 精确闹钟治理 + Private Space + DCL DEX 只读 | `SCHEDULE_EXACT_ALARM`；私密空间隔离 |
| A17 (37) | Contacts Picker / ACCESS_LOCAL_NETWORK / SMS OTP 延迟 / Location Button / EyeDropper / PQC / 原生 DCL | 隐私默认三连击 + 后量子 + 原生库硬化 |

---

## 9. 易错红榜 TOP20（权限/隐私专题）

1. "权限 granted 就等于操作能成功" —— 还过 AppOps + SELinux。
2. "root 能绕过 SELinux" —— enforcing 下 `neverallow` 不可绕过。
3. "`getCallingUid` 一定等于真实 App UID" —— 跨 VM/代理场景不可信。
4. "`requestPermissions` 直接调 PackageManager" —— 实际走 ATMS + PermissionController 独立进程。
5. "`READ_EXTERNAL_STORAGE` 仍通用" —— A13+ 拆 `READ_MEDIA_*`，旧权限对媒体逐步失效。
6. "`MANAGE_EXTERNAL_STORAGE` 随便申请" —— `signature|appop|role`，强审核。
7. "用 `File` 直读 `/sdcard` 全局" —— Scoped Storage 下非法。
8. "Photo Picker URI 永久有效" —— 需 `takePersistableUriPermission` 持久化。
9. "申请 FINE 必得 GPS 精度" —— 用户选大致 + AppOps 可降级。
10. "后台位置只需 FINE" —— 还要 `ACCESS_BACKGROUND_LOCATION` 且受限频。
11. "AppOps 是 App 自己管的" —— 系统 `AppOpsService` 持有，App 不可改。
12. "SELinux 只在开机生效" —— 每次访问都 `avc` 检查。
13. "`clearCallingIdentity` 是提权" —— 是临时清除调用方身份，须配对 `restore`。
14. "普通权限也弹窗" —— 普通/签名权限安装即给。
15. "一次性授权改写了 `runtime-permissions.xml`" —— 是 session/AppOps 层 TTL。
16. "签名权限只要声明就给" —— 还需 `signature`/`signatureOrSystem` 签名一致。
17. "Binder 调用只看 permission 字符串" —— 还过 AppOps + SELinux + `getCallingUid`。
18. "跨进程传 URI 不用带 flag" —— 需 `FLAG_GRANT_READ/WRITE_URI_PERMISSION`。
19. "A17 Contacts Picker 仍需 `READ_CONTACTS`" —— 系统 Picker 替代，按需字段授权。
20. "ACCESS_LOCAL_NETWORK 属 INTERNET 组" —— 属 `NEARBY_DEVICES`，且是 API 37 新增运行时权限。

---

## 10. 三条高频追问链（跨子系统串讲）

### 链 A：权限被授予却仍被拒（Permission -> AppOps -> SELinux 全链路）
```
App 调 camera.open()
  -> Camera2 API -> cameraserver (跨进程)
  -> cameraserver 内 AppOpsService.noteOp(OP_CAMERA)
       |-- MODE_IGNORED (后台策略) => 静默失败
  -> 即使 AppOps 过, untrusted_app 域对 video_device 的 SELinux allow?
       |-- 无 allow => avc denied => EPERM
  -> 即使内核过, 服务侧 checkPermission(getCallingUid) 是否持有 CAMERA?
结论: 四层任一不过都拒; 面试标准答 = "先分四层逐层排查"
```

### 链 B：一次定位请求（Location Button -> 大致位置算法 -> 指示器）
```
App 嵌 Location Button -> 点按授权本次会话精确位置
  -> LocationManagerService 按授予档位返回 FINE/COARSE
  -> COARSE 走密度自适应网格量化 (A17 新)
  -> 状态栏位置指示器亮起 (对齐麦克风/相机)
  -> AppOps OP_FINE_LOCATION 被 session 约束, 关 App 即失效
结论: A17 把"精确位置"从永久授权变成"会话级 + 透明化"双保险
```

### 链 C：跨 VM / 跨沙箱 RPC Binder（getCallingUid 不可信 -> SELinux 域 -> 二次校验）
```
App(主系统) -> SDK Runtime 沙箱 / pKVM pVM (VSock + RpcServer)
  -> 宿主侧 Binder getCallingUid == SYSTEM_UID / 代理 UID (非真实 App)
  -> 若服务据此做权限决策 => confused-deputy 漏洞
  -> 正确做法: 沙箱令牌 / SharedLibraryInfo / 二次身份断言
  -> 再加 SELinux binder_call(src_domain, target_domain) 约束域边界
结论: 端侧 AI / 隐私沙箱场景, getCallingUid 必须配合沙箱身份断言
```

---

## 11. AOSP 14 源码路径清单（本篇引用）

| 模块 | 路径 |
|---|---|
| Runtime Permission 授予/存储 | `frameworks/base/services/core/java/com/android/server/pm/permission/PermissionManagerServiceImpl.java` |
| 权限策略自动 grant | `frameworks/base/services/core/java/com/android/server/pm/permission/PermissionPolicyService.java` |
| 授权 UI（独立进程） | `packages/apps/PermissionController/src/com/android/permissioncontroller/permission/ui/GrantPermissionsActivity.java` |
| 申请入口 | `frameworks/base/core/java/android/app/Activity.java` (`requestPermissions`) |
| AppOps 服务 | `frameworks/base/services/core/java/com/android/server/AppOpsService.java` |
| AppOps 客户端 | `frameworks/base/core/java/android/app/AppOpsManager.java` |
| 媒体集合 | `frameworks/base/media/java/android/media/MediaStore.java` |
| 媒体 Provider | `packages/providers/MediaProvider/src/com/android/providers/media/MediaProvider.java` |
| 位置服务 | `frameworks/base/services/core/java/com/android/server/location/LocationManagerService.java` |
| 权限校验（AMS） | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` (`checkPermission`/`checkComponentPermission`) |
| Binder 调用方身份 | `frameworks/base/core/java/android/os/Binder.java` (`getCallingUid`/`clearCallingIdentity`/`restoreCallingIdentity`) |
| Native Binder 身份 | `frameworks/native/libs/binder/IPCThreadState.cpp` (`getCallingUid`) |
| SELinux 策略 | `system/sepolicy/private/*.te`（appdomain/untrusted_app/platform_app/system_app） |
| SELinux 设备标签 | `device/<vendor>/sepolicy/*/file_contexts`；`external/selinux/` |
| 权限常量定义 | `frameworks/base/core/res/AndroidManifest.xml`（`android.permission.*`） |

---

## 12. 第四十六篇 -> 第四十七篇 交叉索引

- **8/29 SDK Runtime 隐私沙箱**：本篇专题六"getCallingUid 不可信"的沙箱子场景，正是由 SDK Runtime 的独立 UID + 受限 Binder + `SharedLibraryInfo.TYPE_SDK_PACKAGE` 映射回宿主引出 —— 本篇把该机制升格为权限模型的通用第四层。
- **8/21 A17 QPR2 App Lock**：App Lock 的"AI agent 经系统服务转发读锁 App 数据"正是专题六场景②（confused-deputy）的实例；本篇补了 `clearCallingIdentity` 的工具性解法。
- **8/17 HAL / Linux 内核驱动**：专题五 SELinux 设备节点标签 + `neverallow` 正是 MTK/厂商新增 `/dev/xxx` 驱动必改的 sepolicy 文件，本篇给出四步改法。
- **8/2 pKVM / A17 AISeal**：专题六场景①跨 VM RPC Binder `getCallingUid` 不可信，端侧 AI 进 pKVM 必须配沙箱身份断言。
- **8/12 核心基础查缺补漏 / 8/27 跨版本演进**：本篇"权限四层闸门"补全了此前只讲 `android.permission`、未下钻 AppOps/SELinux 的缺口。

---

## 13. 延伸阅读

- AOSP：`system/sepolicy/README` + `untrusted_app.te` / `appdomain.te`（SELinux 域与 `neverallow` 全貌）。
- AOSP：`frameworks/base/services/core/java/com/android/server/pm/permission/`（Runtime Permission 全链路）。
- Android Developers Blog "Android 17 is Here" — 隐私默认三连击 + PQC + 原生 DCL 官方说明。
- 《Android 安全架构》（SELinux + Capability + Permission 三层模型）官方文档。
- `adb shell appops` / `dmesg | grep avc` / `dumpsys package <pkg>` 三件套，是权限排查真·实战手段。
- 关联专题：Binder 一次拷贝与线程池（8/12）、App Lock 安全链（8/21）、SDK Runtime 沙箱（8/29）、pKVM 机密计算（8/2）。

---

> 全系列状态：47 篇 / 约 282 专题。本篇关闭"权限模型四层闸门 + A17 隐私新边界"真缺口，与 8/29 SDK Runtime 沙箱、8/21 App Lock、8/17 SELinux/驱动形成安全底座闭环。后续可选增量：① WebView 多进程渲染沙箱（chromium sandbox / Mojo IPC）与 pKVM 对照；② Aluminium OS 落地后对照 A14 真实 diff 复盘；③ 真题大乱斗 vol.4（基于本篇链 A/B/C 扩展更刁钻混合场景）。秋招（9–11 月）建议按本篇"四层闸门"心智模型自测"权限被授予却仍被拒"死亡陷阱题。
