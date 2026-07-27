# Android Framework 面试题 · 系统基建·安全存储·可观测性与版本演进篇（2026-07-27）

> 第六篇 · 承接主篇(16章)/拓展篇(10章)/深挖篇(11章)/图形多媒体通信篇(12章)。
> 本篇轮换到此前**完全未覆盖**的「系统地基」与「可观测性/版本演进」角度：
> **16KB 页面（近期热点）、ClassLoader/插件化、权限全链路、Keystore/Keymint、Verified Boot/AVB/fscrypt、Vold/FUSE 存储、logd 日志、性能可观测性工具链、RRO/Overlay、Doze/AppStandby/JobScheduler、Android 15/16 行为变更**。
> AOSP 基线：**Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX)**，内核 GKI `android14-6.1`；涉及 15/16 的会显式标注。
> 面试定位：这些是「系统/平台/安全/性能/兼容性岗」的硬核追问区，能答出底层数据结构和跨进程契约，基本就稳了。

---

## 目录
1. [16KB 页面大小（Android 15/16 强制，近期热点）](#1)
2. [ClassLoader 与热修复/插件化底层](#2)
3. [权限系统全链路：runtime permission / AppOps / PermissionManager](#3)
4. [Keystore2 与 Keymint HAL：Android 密钥体系](#4)
5. [Verified Boot / AVB / dm-verity / fscrypt 加密](#5)
6. [存储架构：Vold / FUSE / emulated 卷（sdcardfs 退场）](#6)
7. [日志系统：logd / liblog / logcat 内核 buffer](#7)
8. [性能可观测性工具链实战（Looper Printer / Choreographer / BlockCanary / Matrix）](#8)
9. [资源系统与 RRO/Overlay 运行时资源覆盖](#9)
10. [后台治理与电源框架（Doze / AppStandby / JobScheduler / WakeLock）](#10)
11. [Android 15/16 行为变更与 Framework 演进热点串讲](#11)
12. [查缺补漏 · 易错点 · 高频追问 · 延伸阅读](#12)

---

<a id="1"></a>
## 1. 16KB 页面大小（Android 15/16 强制，近期热点）

**Q：Android 15/16 为什么要强制 16KB 内存页？这对 Framework/驱动/HAL/NDK 工程师分别意味着什么？如何检测与适配？**

**答案解析：**

传统 ARM64 设备几乎都用 **4KB 页**（`CONFIG_ARM64_4K_PAGES`）。5.x 内核起 Google 推动 **16KB 页**（`CONFIG_ARM64_16K_PAGES`），原因是大页能显著减少 TLB miss、降低页表内存、提升 PTE walk 效率，整体吞吐与续航更优。

- **Android 15 (API 35)** 首次引入 16KB 页**支持**：内核 + bionic + 运行时开始对 16KB 设备兼容；自 **2025-11-01** 起，Google Play 上目标 API 15+ 的新应用/更新**必须**对齐 16KB。
- **Android 16 (API 36)** 增加**兼容模式**：检测到 App 仍是 4KB 对齐时自动进入兼容模式并弹通知；可在 `AndroidManifest.xml` 设 `android:pageSizeCompat` 关闭弹窗（需 A16 SDK 编译）。**最佳实践仍是原生化 16KB 对齐**。

**对各类工程师的实质影响（考点）：**
- **内核/驱动**：内核本身按 16KB 编译即可；但**自写驱动**里若有硬编码页大小（如 `ALIGN(x) (((x)+4095)&~4095)`）、或假设 4KB 的 ring buffer、DMA buffer 对齐、buddy 分配粒度，必须改成 `getpagesize()` 动态获取。GKI KMI 模块尤其要注意。
- **HAL/原生**：NDK r28 默认支持 16KB；r27 需显式开 `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`。`mmap`/`ashmem(dmabuf)`/`ion` 的对齐、共享内存 ring buffer 长度都要动态化。
- **Framework/ART**：ART 堆管理、GC 卡表（card table）、img 镜像、oat 文件布局都已适配；`libc` 的 `bionic/linker` 在加载 ELF 时校验 LOAD 段对齐——**未对齐的 so 在 16KB 设备上直接加载失败**。

**检测与验证：**
```bash
# 用 zipalign 检查 APK 是否已 16KB 对齐（P=page，16 表示 16*1024 对齐基数）
zipalign -c -P 16 -v 4 app.apk
# 运行时查看设备页大小
getconf PAGESIZE   # 16384 即 16KB
```
Native 代码正确写法：
```c
#include <unistd.h>
#define ALIGN_PAGE(x) (((x) + (getpagesize()-1)) & ~(getpagesize()-1))  // 动态，兼容 4/16KB
```

**关键源码路径（Android 14/15）：**
- `bionic/linker/` —— `linker_phdr.cpp`（`ElfReader::ReadLoads()` 解析 PT_LOAD）、`linker.cpp`（`get_page_size()` 来自 `bionic/libc/private/__pagesize.cpp`，运行时 `sysconf(_SC_PAGESIZE)`）。
- `bionic/libc/arch-arm64/` —— `BIONIC_SUPPORT_16K_PAGE_SIZE` 相关宏。
- 内核：`arch/arm64/Kconfig`（`ARM64_16K_PAGES`）、`mm/` 页表；GKI `android14-6.1`。
- 官方文档：`source.android.com/docs/performance/16kb-page-size`。

**易错点：**
- 「16KB 只是改个宏」——错。它牵动 **ELF 加载对齐、所有原生 ring buffer、共享内存契约、内核 buddy 与 PTE 数量**，是跨栈工程。
- 以为只有 NDK 应用要管——**系统 ROM 里的 vendor 驱动、预装 so、甚至 init 早期 native 进程**都可能受影响。
- 兼容模式是「兜底不是方案」，长期有性能/稳定性损耗。

**高频追问：**
- 4KB 页和 16KB 页的 APK/so 能否共存于同一台 16KB 设备？（答：能，走兼容模式，但 so 必须能动态对齐加载）
- 页大小变化对 **Binder 的 mmap 内核缓冲区**（`binder_mmap` 里 `max`（通常 1M-8K））有影响吗？（答：binder 缓冲区大小与页大小无关，是协议层 `BINDER_VM_SIZE` 约定；但内核分配底层页表条目数会变）
- ART 的 **oat/vdex/art** 镜像在 16KB 下要重编吗？（答：oat 文件含页面对齐假设，跨页大小需重新 dexopt）

**延伸阅读：**
- AOSP Docs《16 KB page size》`source.android.com/docs/performance/16kb-page-size`
- `bionic/linker/linker_phdr.cpp` 的 `PhdrTable::ShouldUseRelro` 与对齐校验
- NDK r28 release notes（默认 16KB 对齐）

---

<a id="2"></a>
## 2. ClassLoader 与热修复/插件化底层

**Q：Android 的 `PathClassLoader` / `DexClassLoader` 区别？热修复「插桩」改的是哪一层？Resources 怎么合并？**

**答案解析：**

Android 用 `dalvik.system` 下的类加载器，继承链：
`ClassLoader`（抽象，双亲委派）→ `BaseDexClassLoader` → `PathClassLoader` / `DexClassLoader` / `IncrementalClassLoader`（Instant Run/增量）。

- **`PathClassLoader`**：只能加载**已安装 APK 的 `dexPath`**（即 `/data/app/.../base.apk` 与 `dexopt` 后的 oat），**不能指定 optimizedDirectory**，是 App 默认类加载器（`LoadedApk.getClassLoader()` 返回它）。
- **`DexClassLoader`**：可加载**任意路径**的 dex/jar/apk，并指定 `optimizedDirectory`（odex 输出），是插件化/热修复加载外部 dex 的主力。

**热修复插桩原理（核心考点）：**
`BaseDexClassLoader` 持有：
```java
private final DexPathList pathList;   // BaseDexClassLoader.java
```
`DexPathList` 持有：
```java
private Element[] dexElements;        // DexPathList.java  makeDexElements()
```
类查找走 `BaseDexClassLoader.findClass()` → `pathList.findClass(name, ...)` → **遍历 `dexElements` 数组**，命中即返回。

因此「热修复」本质是**反射把补丁 dex 的 Element 插入 `dexElements` 数组前端**（越靠前优先级越高，从而覆盖旧类）：
```java
// 伪代码：插入补丁 dex 到数组头部
Element[] newElements = 合并(补丁Elements, 原Elements);
反射设置 pathList.dexElements = newElements;
```
Tinker/QFix 等方案差异在于「全量替换 dex 顺序」vs「插桩（给每个方法前插一段跳板）」。**插桩**是为了规避「类已被加载无法替换」的限制——在类已加载时，通过编译期在方法入口插入 `if(needFix) goto patch` 的跳转。

**Resources 合并（插件资源冲突）：**
类能换，资源不行（`Resources.getDrawable(resId)` 是运行时按 id 查 `ResourcesTable`）。插件化需：
- 老方案：反射 `AssetManager.addAssetPath(path)`（hidden API，Android 8 前直接反射，之后需 `@hide` 桥接或 `ResourceLoader`）。
- 新方案：`android.content.res.loader`（`ResourcesProvider` / `ResourcesLoader`，Android 9+），把插件 apk 作为 `ApkAssets` 叠加。
- 资源 id 冲突靠 **aapt 编译期改 packageId**（宿主 0x7f，插件 0x7e/0x7d…）隔离。

**关键源码路径（Android 14）：**
- `libcore/dalvik/src/main/java/dalvik/system/BaseDexClassLoader.java`、`DexClassLoader.java`、`PathClassLoader.java`、`IncrementalClassLoader.java`
- `libcore/dalvik/src/main/java/dalvik/system/DexPathList.java`（`makeDexElements`、`findClass`、`loadDexFile`）
- `frameworks/base/core/java/android/app/LoadedApk.java` —— `makeClassLoader()`、`mClassLoader`、`getClassLoader()`
- `frameworks/base/core/java/android/app/ApplicationLoaders.java` —— `getClassLoader()` 缓存（同 `classLoaderName+sharedLibrary` 复用）
- `frameworks/base/core/java/android/content/res/AssetManager.java` —— `addAssetPath`(hidden)、`addAssetPathInternal`、`ApkAssets`

**易错点：**
- 「`DexClassLoader` 能热更新已运行的类」——错。**类一旦被某个 ClassLoader 加载并实例化，就无法被同一 ClassLoader 重新加载**；所以要么换 ClassLoader 实例（插件），要么用插桩在已加载类里留钩子。
- `PathClassLoader` 与 `DexClassLoader` 在 Android 8+ 实现已**趋同**（都走 `BaseDexClassLoader`），区别仅在构造参数语义。
- 多 ClassLoader 下的 **Class 不相等**：`pluginClass != hostClass` 即便全限定名相同，导致 `instanceof`/`cast` 失败——插件化通信要用接口或 `反射`/序列化。

**高频追问：**
- 双亲委派在热修复里是帮手还是障碍？（答：是帮手，补丁插到数组前，findClass 先命中补丁；但系统类由 BootClassLoader 委派在前，补丁不能改系统类）
- `IncrementalClassLoader` 和 `BaseDexClassLoader` 是什么关系？（答：Instant Run 用 `IncrementalClassLoader` 包裹一个 `DelegateClassLoader` 去加载变更 dex，支持「重启即生效」而非冷启动）
- 64K 方法数限制 `65536` 由谁报？怎么解？（答：`dex` 单文件方法索引 16 位；用 `MultiDex`（API<21 手动 `MultiDex.install`，ART 直接多 dex）或 R8/ProGuard 裁剪）

**延伸阅读：**
- `libcore/dalvik/src/main/java/dalvik/system/DexPathList.java#makeDexElements`
- 《Android 插件化技术——原理、方案与实践》
- `frameworks/base/core/java/android/content/res/ResourcesLoader.java`

---

<a id="3"></a>
## 3. 权限系统全链路：runtime permission / AppOps / PermissionManager

**Q：`Context.checkSelfPermission()` 一路走到内核/驱动了吗？runtime permission 和 AppOps 有什么区别？**

**答案解析：**

`checkSelfPermission` **完全在 framework 用户态，不进内核**。调用链：
```
Context.checkSelfPermission(name)
  → ContextImpl.checkSelfPermission()
    → ActivityManager.checkPermission(name, pid, uid)   // 静态方法
      → ActivityManagerNative.getDefault().checkPermission(name, pid, uid)
        → AMS.checkPermission(name, pid, uid)
          → PermissionManagerService.checkPermission(name, pid, uid)
            → 查 mPackageManagerInternal 里该 uid 的 grantedPermissions 集合
```
AMS 的 `checkPermission` 先快路径判断：uid==0(root)/uid==SYSTEM_UID 直接放行；否则转 `PermissionManagerService.checkPermission`（`frameworks/base/services/core/java/com/android/server/pm/PermissionManagerService.java`）查包权限授予表。

**runtime permission vs AppOps（核心区分，必考）：**
- **runtime permission**（危险权限：`READ_CONTACTS` 等）：用户安装后首次使用时弹窗授权，存于 `PackageManager` 的授予集合，粒度是「app 级」。
- **AppOps（App Ops）**：更细的运行期开关（`OP_CAMERA`、`OP_COARSE_LOCATION`…），**不一定与权限一一对应**，由 `AppOpsService`（`frameworks/base/services/core/java/com/android/server/AppOpsService.java`）管理，记录「允许/忽略/拒绝/询问」。很多权限底层同时受 AppOps 约束——例如定位权限授予后，AppOps 仍可「仅前台允许」。

典型调用：`AppOpsManager.checkOp(op, uid, pkg)` → `AppOpsService.checkOperation()`。`ActivityManager` 在 `checkPermission` 之外还会在敏感操作（如 `getRunningTasks`）里调 `AppOps` 二次校验。

**授权流程**：用户点「允许」→ `PermissionController`（`packages/apps/PermissionController/`）→ `PermissionManagerService.grantRuntimePermission()` → 更新 `PackageParser.Package.mRequestedPermissions` 与 `mGrantedPermissions` → 广播 `ACTION_PACKAGE_CHANGED` 使 `PackageParser` 缓存失效。

**关键源码路径（Android 14）：**
- `frameworks/base/core/java/android/content/Context.java` —— `checkSelfPermission()`（API 23+，委托 `checkPermission`）
- `frameworks/base/core/java/android/app/ContextImpl.java` —— `checkPermission()` / `checkSelfPermission()`
- `frameworks/base/core/java/android/app/ActivityManager.java` —— 静态 `checkPermission(name, pid, uid)`
- `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` —— `checkPermission()` / `checkCallingPermission()`（`checkCallingPermission` 取 `Binder.getCallingPid/Uid`，是 IPC 鉴权的关键）
- `frameworks/base/services/core/java/com/android/server/pm/PermissionManagerService.java` —— `checkPermission()` / `grantRuntimePermission()`
- `frameworks/base/services/core/java/com/android/server/AppOpsService.java`
- `packages/apps/PermissionController/`

**易错点：**
- 「`checkSelfPermission` == 一定能用该能力」——错。还要过 **AppOps** 与「运行期状态」（如后台定位受 `AppStandby`/后台限制）。
- `checkCallingPermission` 与 `checkSelfPermission` 区别：前者检查**Binder 调用方** uid（IPC 上下文），后者检查**当前进程** uid。Binder 服务端鉴权几乎都用前者，否则会被「伪造调用方」绕过。
- 权限判断**不查 SELinux**——那一层在 `binder` 事务层与 `service_manager` 的 `selinux_check_access` 里，是另一套机制（见拓展篇第 9 章 Binder 安全）。

**高频追问：**
- 为什么 `checkCallingPermission` 在「非 IPC 线程」上会返回 `PERMISSION_DENIED`？（答：Binder 调用上下文 `mCallingUid` 为空，退化为查自身，语义不符）
- `READ_EXTERNAL_STORAGE` 在 Android 13+ 被拆成 `READ_MEDIA_IMAGES/VIDEO/AUDIO`，底层怎么兼容旧 App？（答：`PermissionManagerService` 做「权限组→拆分权限」映射，`READ_EXTERNAL_STORAGE` 作为 legacy 权限被 group 自动授予逻辑替代）
- AppOps 的数据存在哪？（答：XML `/data/system/appops.xml`，运行时在 `AppOpsService` 内存）

**延伸阅读：**
- `frameworks/base/services/core/java/com/android/server/pm/PermissionManagerService.java#checkPermission`
- 《Android 权限机制与 AppOps 深度解析》
- 拓展篇第 9 章「Binder 安全：clearCallingIdentity / SELinux ctx」

---

<a id="4"></a>
## 4. Keystore2 与 Keymint HAL：Android 密钥体系

**Q：Android 的密钥存在哪？`AndroidKeyStore` 和 `KeyChain` 区别？`Keymint` HAL 干什么？**

**答案解析：**

Android 密钥体系分两代：
- **Keystore（旧，C++，Android ≤11）**：`/data/misc/keystore/` 下 SQLite + 文件，密钥材料可由 Keystore 守护进程托管。
- **Keystore2（新，Rust，Android 12+）**：`system/security/keystore2/`，基于 **Keystore 2.0 API + Keymint HAL**，密钥材料可以**只存在于 TEE/StrongBox（SE）中，永不离开安全硬件**。`AndroidKeyStore` Provider 只是个「句柄」——私钥字节从不出安全边界，签名/解密在安全环境内完成。

**`AndroidKeyStore` vs `KeyChain`：**
- `AndroidKeyStore`（`android.security.keystore`）：App **自己生成、自己用**的密钥，存于 Keystore2，受 `KeyGenParameterSpec`（如 `setUserAuthenticationRequired`、`setPurposes`）约束；可绑定生物/锁屏。
- `KeyChain`（`android.security.KeyChain`）：系统级**用户安装的 CA/客户端证书**（如 Wi-Fi/TLS 客户端证书），需用户显式选择授权，跨 App 共享，存于 `keystore` 的 `UID_SYSTEM` 名下。

**Keymint HAL（关键）：**
`hardware/interfaces/security/keymint/` 是 AIDL HAL，定义 `IKeyMintDevice`（密钥生成/签名/加解密）、`IKeyMintOperation`、`IRemotelyProvisionedComponent`（远程证明）。它由 **TEE 厂商实现**（如 ARM TrustZone 里的 `km/tz` 实现或 StrongBox 的 SE 实现）。Framework 侧 `android.security.keystore2.AndroidKeyStore` 调用 `keystore2` 守护，守护再通过 `IKeyMintDevice` AIDL 跨进 vendor 安全域。

演进对照：**Keymaster HAL**（HIDL，旧）→ **Keymint HAL**（AIDL，新，Android 12+，支持远程证明/弱密钥检测）。`Gatekeeper HAL` 负责锁屏口令校验并签发「auth token」给 Keymint 做「需解锁才能用密钥」的约束。

**关键源码路径（Android 14）：**
- `frameworks/base/keystore/` —— `java/android/security/KeyStore.java`、`AndroidKeyStoreProvider.java`
- `frameworks/base/core/java/android/security/keystore2/` —— `AndroidKeyStore.java`、`KeyGenParameterSpec.java`、`AndroidKeyStoreProvider`
- `system/security/keystore2/` —— keystore2 守护（Rust）
- `hardware/interfaces/security/keymint/` —— `IKeyMintDevice.aidl`、`IKeyMintOperation.aidl`、`RemoteProvisioning`
- `hardware/interfaces/security/gatekeeper/`、`hardware/interfaces/security/sharedsecret/`
- 内核/安全硬件：TEE 实现（厂商闭源）、`Gatekeeper` 驱动

**易错点：**
- 「`AndroidKeyStore` 把私钥存成了文件，我能读到」——错。在 StrongBox/TEE 模式下私钥**只存在于安全硬件**，Keystore 只持有引用 handle。
- `KeyChain` 与 `AndroidKeyStore` 的密钥**不互通**——别把系统 CA 证书和用户 App 密钥混为一谈。
- 没有 `setUserAuthenticationRequired(true)` 的密钥，设备解锁状态下任何时刻都能用——敏感密钥务必加 auth 绑定。

**高频追问：**
- 为什么说 Keymint 支持「远程证明（Remote Provisioning）」？（答：Android 12+ 弃用出厂预置根证书，改为设备上电后向 Google 证明自己是真 TEE 再动态下发证明证书，`IRemotelyProvisionedComponent`）
- `Keystore2` 用 Rust 写有什么好处？（答：内存安全，避免旧 C++ keystore 的历史内存破坏漏洞；与 Android 整体 Rust 化趋势一致）
- 指纹/人脸解锁后的「auth token」怎么流转到 Keymint？（答：`Gatekeeper`/`BiometricService` 校验通过后签发带时间窗的 `HardwareAuthToken`，传给 Keymint 验证 `authBound` 密钥）

**延伸阅读：**
- `hardware/interfaces/security/keymint/README.md`
- AOSP Docs《Implementing KeyMint》《Keystore 2.0》
- 第 5 章 Verified Boot（证明链与 KeyMint 远程证明同源）

---

<a id="5"></a>
## 5. Verified Boot / AVB / dm-verity / fscrypt 加密

**Q：Android 开机怎么保证系统分区没被篡改？`dm-verity` 和 `fscrypt` 分别保护什么？**

**答案解析：**

这是「信任链（Chain of Trust）」问题，从硬件到文件系统逐层校验：

1. **Boot ROM / 芯片熔丝**：固化公钥，校验 **bootloader**（AVB 的 `vbmeta` 根签名）。
2. **AVB（Android Verified Boot，`external/avb/`）**：bootloader 用 `avbtool` 生成的 `vbmeta` 分区（含哈希描述符）校验 `boot`/`system`/`vendor`/`vendor_boot` 等。验证通过才 `avb_slot_verify` 放行。
3. **内核启动 + `dm-verity`**：`system`/`vendor` 等**只读**分区挂载为 verity 设备。`dm-verity` 用**哈希树**（Merkle tree）：每个数据块对应一个哈希，哈希层层上卷到树根，树根 hash 存于 `vbmeta`。读块时实时算 hash 比对，**任何篡改（哪怕一个字节）都会被校验失败拦截**，内核拒绝读该块（触发 `EIO`）。源码 `drivers/md/dm-verity-target.c`（GKI 内核）。
4. **`fs_mgr`（`system/core/fs_mgr/`）**：`init` 解析 `fstab`，根据 `verify`/`forceencrypt` 标志决定挂载方式（verity / 加密）。

**`dm-verity` vs `fscrypt`（保护对象不同，必考）：**
- **`dm-verity`**：保护**完整性**（integrity）——防止系统分区被篡改（恶意刷机/持久化 rootkit）。针对**只读系统分区**。
- **`fscrypt`**（文件系统级加密，原名 `fbe`/`fde`）：保护**机密性**（confidentiality）——`/data` 用户数据加密，设备丢失不被读。分 **FBE（File-Based Encryption，Android 7+，按目录/用户加密）** 与 **metadata 加密**（整个 `/data` 元数据加密）。密钥来自 `vold` + `Gatekeeper`/`Keystore`（用户锁屏口令派生）。

**关键源码路径（Android 14 / GKI android14-6.1）：**
- `external/avb/` —— `libavb/avb_slot_verify.c`、`avbtool`、`avb_vbmeta_image.h`
- `system/core/fs_mgr/` —— `fs_mgr.cpp`、`fs_mgr_verity.cpp`、`fs_mgr_fstab.cpp`
- `system/vold/` —— `fs/`、`KeyStorage.cpp`、`VoldNativeService.cpp`（加密密钥管理）
- `frameworks/base/services/core/java/com/android/server/StorageManagerService.java`（fscrypt policy 下发）
- 内核 `drivers/md/dm-verity-target.c`、`fs/crypto/`（fscrypt）、`fs/ext4`/`f2fs`
- `bootable/recovery`、`system/core/init/`（挂载早期）

**易错点：**
- 「verified boot 就是加密」——错。verity 是**完整性校验（防篡改）**，不加密内容；机密性靠 fscrypt。二者目的正交。
- `dm-verity` 只保护**只读系统分区**，`/data` 是可读写的，靠 fscrypt + 文件权限保护。
- 解锁 bootloader（`fastboot oem unlock`）会**禁用 AVB**，这就是「root 后系统不再可信」的根因。

**高频追问：**
- verity 哈希树怎么做到「读一个块只验证一个块」？（答：Merkle 树，自底向上，读块 N 只需验证从叶到根 O(log) 个哈希，根 hash 已被 vbmeta 签名）
- `adb disable-verity` 在 userdebug 上能关 verity 吗？（答：userdebug + `adb root` 可临时关闭以便 remount system 调试，user 版本不行）
- FBE 的 `DE`（Device Encrypted）与 `CE`（Credential Encrypted）存储区区别？（答：DE 区设备启动即可用，CE 区需用户解锁后才解密——`UnlockDeviceRequired` 相关）

**延伸阅读：**
- AOSP Docs《Verified Boot》《Implementing dm-verity》《Encryption》
- `external/avb/README.md`
- 第 4 章 Keymint（远程证明与 AVB 同属「设备可信」体系）

---

<a id="6"></a>
## 6. 存储架构：Vold / FUSE / emulated 卷（sdcardfs 退场）

**Q：App 看到的 `/storage/emulated/0` 是怎么来的？为什么 Android 11 后 `sdcardfs` 退场、改用 FUSE？**

**答案解析：**

`/storage/emulated/0`（即 `Environment.getExternalStorageDirectory()` 的「内部外部存储」）**不是真实磁盘分区**，而是 `vold` + **FUSE 用户态守护** 暴露的虚拟挂载。

链路：
```
真实数据分区 /data/media/0 (F2FS/ext4)
   ↑ vold 管理 emulated volume (EmulatedVolume)
   ↑ FUSE daemon (fusemp / media) 用户态文件操作转发
   ↑ 挂载点 /mnt/runtime/{read,write,full}/emulated/0
   ↑ 绑定到 /storage/emulated/0（按 app 可见性选 read/write/full 视图）
```
- **vold**（`system/vold/`）：存储守护，管理 `VolumeManager`、`EmulatedVolume`、`PublicVolume`（SD 卡/OTG）、`StubVolume`。接收 `StorageManager` 的 `mount/unmount` 指令。
- **FUSE（Filesystem in Userspace）**：内核 `fs/fuse/` 把文件操作从内核态转到**用户态守护进程**（`media` FUSE daemon，即 `fusemp`）执行。守护按 **app UID / 权限 / 运行时可见性** 做细粒度拦截（如「分区存储（Scoped Storage）」下某 app 只能看自己 `Android/data/<pkg>`）。
- **sdcardfs 退场原因**：sdcardfs 是内核态 wrap fs，性能尚可但**难以精确实现 Android 10+ 的分区存储权限语义**（按 app 过滤目录、按 URI 授权）。FUSE 在用户态更易实现复杂策略；Android 11 起默认 `sdcardfs` 关闭、走 FUSE（`ro.sys.sdcardfs` 控制）。代价是文件操作多一次用户态上下文切换，Google 后续用 **FUSE passthrough**（`FUSE_PASSTHROUGH`）把只读/部分路径直通内核以挽回性能。

**运行时视图隔离**（考点）：`/mnt/runtime/` 下三个视图——
- `read`：仅读，无写权限（如 `INTERNET` 无存储权限 app）
- `write`：可写自己包名目录
- `full`：特权 app（如 `MediaProvider`、`shell`）可见全部

**关键源码路径（Android 14）：**
- `system/vold/` —— `VoldNativeService.cpp`、`VolumeManager.cpp`、`model/EmulatedVolume.cpp`、`model/PublicVolume.cpp`、`utils/Fuse.cpp`、`Utils.cpp`
- `frameworks/base/services/core/java/com/android/server/StorageManagerService.java` —— `mount()`/`unmount()`、fscrypt policy
- `frameworks/base/core/java/android/os/storage/StorageManager.java`、`StorageVolume.java`
- `frameworks/base/core/java/android/os/Environment.java` —— `getExternalStorageDirectory()`
- 内核 `fs/fuse/`（FUSE）、`fs/f2fs`、`fs/ext4`
- `packages/providers/MediaProvider/`（分区存储的实际权限裁决者）

**易错点：**
- 「`/storage/emulated/0` 是一个真实分区」——错，它是 FUSE 暴露的、底层是 `/data/media/0` 的**虚拟视图**。
- 「外部存储 = SD 卡」——错，现代「外部存储」默认指 **emulated 内部存储**（`/data/media`），SD 卡是 `PublicVolume`。
- 分区存储下「凭文件路径直接 `new FileInputStream`」经常失败——因为 FUSE 守护按 app 可见性拦截，必须用 `MediaStore`/`Storage Access Framework` 的 URI。

**高频追问：**
- FUSE passthrough 怎么提升性能？（答：`FUSE_PASSTHROUGH` 让 FUSE 直接把文件操作转给底层真实 fs，跳过用户态守护转发，减少上下文切换）
- `MediaProvider` 在分区存储里扮演什么角色？（答：它持有对 `/data/media` 的真实访问，所有 app 的媒体读写都经它按 URI 授权裁决）
- `StorageManager` 与 `vold` 怎么通信？（答：binder，跨 `system_server` 与 `vold` 原生守护，`IVold` AIDL）

**延伸阅读：**
- AOSP Docs《Storage》《Scoped Storage》
- `system/vold/README.md`
- 第 5 章 fscrypt（emulated 卷加密由 vold 管理密钥）

---

<a id="7"></a>
## 7. 日志系统：logd / liblog / logcat 内核 buffer

**Q：Android 的 `Log.d` 最终写到哪？`logcat -b kernel` 读的是内核 log 吗？logd 和内核 `printk` 什么关系？**

**答案解析：**

Android 有一套**独立于内核 printk 的用户态日志系统 logd**（避免把所有 app 日志灌进单一内核 ring buffer 拖垮系统）：

1. App 调 `Log.d(tag, msg)`（`frameworks/base/core/java/android/util/Log.java`）→ JNI → **`liblog`**（`system/core/liblog/`）。
2. `liblog` 通过 **`/dev/socket/logd` 或 `/dev/log/*` 设备** 把日志发给 **`logd` 守护进程**（`system/core/logd/`，由 `init` 启动）。
3. `logd` 内部维护多个 **`LogBuffer`**（按 buffer 分类），并按 `prune` 规则（如 `persist.logd.size` 控制大小）淘汰旧日志。`LogReader` 响应 `logcat` 的读请求，`LogWriter` 接收写入。
4. `logcat`（`system/core/logcat/`）连 `logd` 拉取格式化输出。

**buffer 分类（考点）：** `main`（app）、`system`（framework）、`radio`（RIL/电话）、`events`（系统事件 `logcat -b events`）、`crash`（崩溃）、`kernel`（**特殊**：`logd` 会去读内核 `/proc/kmsg` 或 `dmesg` 转存，故 `logcat -b kernel` 能看到内核 `printk` 输出，但它**不是直接从内核 buffer 读，而是 logd 转存的副本**）、`security` 等。

**与内核 printk 的关系：**
- `printk` → `/dev/kmsg` → `dmesg` 是**内核原生** ring buffer（由内核 ring 大小 `log_buf_len` 决定，易满丢日志）。
- `logd` 把内核日志**复制**到自己 buffer 一份（通过 `LogKlog` 读 `/proc/kmsg`），同时 app 日志**不进内核**，各走各的。这样用户态日志量再大也不挤占内核 buffer，内核自身日志也不被用户态淹没。

**关键源码路径（Android 14）：**
- `system/core/logd/` —— `main.cpp`、`LogBuffer.cpp`、`LogReader.cpp`、`LogWriter.cpp`、`LogStatistics.cpp`、`CommandListener.cpp`、`LogKlog.cpp`（读内核 kmsg）、`liblog/`
- `system/core/liblog/` —— `logger_write.cpp`、`logger_name.cpp`、`logprint.c`、`android_log_print`
- `system/core/logcat/` —— `logcat.cpp`
- `frameworks/base/core/java/android/util/Log.java` —— `Log.d/i/w/e/v`
- 内核 `printk`：`kernel/printk/printk.c`、`/proc/kmsg`、`/dev/kmsg`

**易错点：**
- 「`logcat -b kernel` 直接读内核 ring buffer」——不准确。它读的是 **logd 转存的副本**；内核原生 buffer 用 `dmesg` 看，且更易被 ring 覆盖。
- 「App 日志会进内核」——不会，Android 刻意分离，否则高日志量 app 会冲掉内核关键日志。
- `logd` 日志**重启即丢**（内存 buffer），要持久化得用 `logcat -f` 落盘或 `logpersist`（userdebug）。

**高频追问：**
- 为什么 logd 要分多个 buffer？（答：隔离 app/system/radio/events，便于按需抓取与按配额 prune，避免互相挤占）
- 内核 `printk` 和 `logd` 的性能/可靠性差异？（答：printk 进内核 ring，易满丢；logd 用户态、可配置大小、可持久化策略）
- dropbox（`/data/system/dropbox`）和 logd 什么关系？（答：`dropbox` 服务把崩溃/ANR/系统事件**持久化**到文件，logd 只是易失的实时 buffer）

**延伸阅读：**
- `system/core/logd/README.md`
- AOSP Docs《Logging》《Reading and writing logs》
- 第 8 章可观测性（logd 是性能/崩溃分析的数据源之一）

---

<a id="8"></a>
## 8. 性能可观测性工具链实战（Looper Printer / Choreographer / BlockCanary / Matrix）

**Q：怎么在「不埋点」的情况下检测主线程卡顿？BlockCanary 的原理是什么？它和 Choreographer 帧率监控有什么区别？**

**答案解析：**

主线程卡顿本质是「**某个 Message 在 Looper 里执行太久**」。有两套互补观测手段：

**(A) Looper Printer 法（检测卡顿原因，BlockCanary 核心）：**
`Looper.loop()` 每处理一条 `Message` 前会调 `Printer.println(">>>>> Dispatching to " + msg.target + " " + msg.callback + ":" + msg.what);`，处理完调 `<<<<< Finished to ...`。
```java
// Looper.java loop()
if (logging != null) logging.println(">>>>> Dispatching to " + msg.target + " " + msg.callback);
... dispatchMessage(msg);
if (logging != null) logging.println("<<<<< Finished to " + msg.target + " " + msg.callback);
```
`BlockCanary`/`Matrix` 的 `LooperMonitor` 通过 `Looper.getMainLooper().setMessageLogging(printer)` 注入自己的 `Printer`：
- 收到 `>>>>> Dispatching` → 记录起点 + **dump 主线程堆栈**（`Looper.getMainLooper().getThread().getStackTrace()`）到采样队列；
- 收到 `<<<<< Finished` → 算耗时，若 > 阈值（默认 1000ms/自定义）且采样到堆栈 → 上报「卡顿+堆栈」。

**(B) Choreographer 帧回调法（检测掉帧，不找原因）：**
`Choreographer.getInstance().postFrameCallback(callback)` 每帧 VSync 后回调 `doFrame(frameTimeNanos)`。两次 `doFrame` 间隔若 > 16.6ms 即掉帧；通过 `FrameMetrics`/`doFrame` 前后的 `Choreographer` token 统计丢帧数。Matrix 的 `FrameTracer` 即此法。

**区别（必考）：** Looper Printer 告诉你「**哪条消息卡了、当时在干什么（堆栈）**」——定位根因；Choreographer 告诉你「**掉了几帧、帧耗时分布**」——量化卡顿但不定位代码。生产环境通常**两者结合**：Choreographer 发现掉帧 → 结合 Looper 堆栈定位到具体 Message/方法。

**关键源码路径（Android 14）：**
- `frameworks/base/core/java/android/os/Looper.java` —— `loop()`、`setMessageLogging(Printer)`、`me.mLogging`
- `frameworks/base/core/java/android/view/Choreographer.java` —— `FrameCallback`、`doFrame()`、`postFrameCallback()`、`CALLBACK_TRAVERSAL`、`scheduleFrameLocked()`
- `frameworks/base/core/java/android/view/ViewRootImpl.java` —— `doTraversal()` 经 Choreographer 调度（掉帧归因常用）
- `BlockCanary`（开源）：`blockcanary-android` 的 `LooperMonitor`、`BlockCanaryInternals`
- `Matrix`（`matrix-trace-canary`）：`LooperMonitor`、`FrameTracer`、`AppMethodBeat`（插桩量化方法耗时）

**易错点：**
- 「Choreographer 能告诉我哪段代码卡了」——不能，它只量化帧耗时，要定位必须配合 Looper 堆栈/Hook。
- `setMessageLogging` 是**全局唯一**的，多个库同时设会互相覆盖——Matrix/BlockCanary 都做了兼容（包装上一个 printer）。
- 主线程**睡眠/锁等待（synchronized 阻塞）** 同样算进 Message 耗时，Looper 法能抓到，但堆栈可能停在 `Object.wait`/锁上。

**高频追问：**
- 为什么不直接在 `dispatchMessage` 前后插桩而是用 `Printer`？（答：`Printer` 是官方预留的无侵入钩子，无需改 framework 源码；插桩需编译期/ART hook，成本高）
- 卡顿堆栈为什么是「采样」不是「实时」？（答：频繁 getStackTrace 本身开销大，BlockCanary 用「起点 + 周期采样」平衡精度与性能）
- Matrix 的 `AppMethodBeat` 和 Looper 法什么关系？（答：AppMethodBeat 用编译期在方法入口/出口插桩记录时间戳，精度到方法级；Looper 法粗到 Message 级——前者是「精确但需插桩」，后者「无侵入但粗」）

**延伸阅读：**
- `frameworks/base/core/java/android/os/Looper.java#loop`
- 开源 `BlockCanary`、`Tencent/matrix` 文档
- 深挖篇第 11 章 Perfetto SQL（生产级卡顿归因用 Perfetto 更全）

---

<a id="9"></a>
## 9. 资源系统与 RRO/Overlay 运行时资源覆盖

**Q：主题/换肤/厂商定制怎么不改 App 代码就替换资源？RRO 和 build-time Overlay 区别？**

**答案解析：**

Android 资源查找链：`Resources.getXXX(resId)` → `ResourcesImpl` → `AssetManager` → 按 **`ApkAssets` 顺序**查找资源表（`resources.arsc`）。Overlay 的本质就是**把另一套 `resources.arsc` 插到查找链前面**，从而「同名资源先命中 overlay 的」。

两类 Overlay：
1. **Build-time Overlay（静态，OVERLAY 资源包）**：编译时通过 `PRODUCT_PACKAGE_OVERLAYS` / `DEVICE_PACKAGE_OVERLAYS` 把 overlay apk 资源合并进目标包，**固化进系统镜像**，无法运行时开关。厂商 ROM 深度定制常用。
2. **RRO（Runtime Resource Overlay，Android 5+）**：独立的 **overlay apk**（只含 `resources.arsc`，无代码），由 `OverlayManagerService`（`frameworks/base/services/core/java/com/android/server/om/`）在运行时挂载到目标包。可**动态启用/禁用**（如夜间模式切换、主题商店换肤、运营商定制）。
   - overlay apk 在 `AndroidManifest.xml` 里声明 `android:isStatic`、`<overlay android:targetPackage="..." android:targetName="..."/>`。
   - 挂载时 `OverlayManagerServiceImpl` 把 overlay 的 `ApkAssets` 加到目标 `AssetManager` 的资源路径集合（`AssetManager.addOverlayPath`/`addAssetPath`），并通知目标包 `Configuration` 变更重建 Resources。
   - **idmap**：overlay 与目标包的资源 id 通过 `idmap2`（`frameworks/base/tools/idmap2/`）做映射表，因为 overlay apk 自己编译出的 id 与目标包不同，需要映射对齐。

**关键源码路径（Android 14）：**
- `frameworks/base/core/java/android/content/res/AssetManager.java` —— `addAssetPath`(hidden)、`addOverlayPath`、`ApkAssets`、`getResourceValue`
- `frameworks/base/core/java/android/content/res/Resources.java` / `ResourcesImpl.java` —— 资源解析入口
- `frameworks/base/services/core/java/com/android/server/om/OverlayManagerService.java`、`OverlayManagerServiceImpl.java`、`OverlayManagerSettings.java`
- `frameworks/base/tools/idmap2/` —— `idmap2` 工具；`system/core/libidmap2/`
- `android.content.om` —— `OverlayInfo`、`OverlayManager`（公开 API）

**易错点：**
- 「RRO 能替换任何资源」——不行。**资源 id 必须存在且类型兼容**；且 `OverlayManagerService` 受 **`overlay` 权限与 SELinux** 约束，普通 app 无法随意 overlay 别家 app（除非同签名/系统权限/`per-user` 授权）。
- 「静态 Overlay 和 RRO 二选一」——实际**共存**：build-time 用于镜像级固化，RRO 用于运行时可变定制。
- overlay 改了资源但 UI 没变——忘了触发 `Configuration` 变更/重建；`AssetManager` 需重新 `applyStyle`/`recreate`。

**高频追问：**
- 为什么需要 `idmap`？（答：overlay apk 独立编译，资源 id 与目标包不一致，idmap2 生成「目标 id ↔ overlay id」映射，使 overlay 的 arsc 能正确覆盖目标 id）
- RRO 对 `layout`/`<style>` 支持到什么程度？（答：支持大部分值/布局/字符串/颜色；但**代码里硬编码的 id 引用、部分 `<bag>` 语义**有限制，复杂主题常需配合 `ContextThemeWrapper`）
- `OverlayManagerService` 和 SELinux 怎么联动？（答：挂载 overlay 需 `android.permission.CHANGE_OVERLAY_PACKAGES` 或系统签名，且 overlay apk 自身 `seinfo` 与 `targetPackage` 的 SELinux 域需匹配）

**延伸阅读：**
- AOSP Docs《Runtime Resource Overlay (RRO)》
- `frameworks/base/services/core/java/com/android/server/om/OverlayManagerServiceImpl.java#commitReplaceOverlay`
- 第 3 章权限（overlay 授权是权限+SELinux 双重约束）

---

<a id="10"></a>
## 10. 后台治理与电源框架（Doze / AppStandby / JobScheduler / WakeLock）

**Q：App 退到后台后为什么网络/同步突然不工作了？Android 16 对 JobScheduler 配额做了什么改动？**

**答案解析：**

Android 用「**分桶（App Standby Buckets）+ 空闲管控（Doze）+ 调度配额（JobScheduler）+ 唤醒锁（WakeLock）**」四件套限制后台行为，省电且防滥用。

1. **App Standby Buckets（应用待机分桶）**：`AppStandbyController`（`frameworks/base/services/core/java/com/android/server/usage/AppStandbyController.java`）按使用频率把 app 分到 `active / working_set / frequent / rare / restricted` 五档。越靠后，**后台网络、Job、Alarm、同步** 受限越狠（如 `rare` 每天只给几分钟后台窗口）。
2. **Doze（休眠）**：设备**长时间静止+灭屏+不充电**时进入 `DeviceIdleController`（`frameworks/base/services/core/java/com/android/server/DeviceIdleController.java`）管理的 Doze，分批延迟网络/Job/Alarm（`alarm.setAndAllowWhileIdle` 例外）。
3. **JobScheduler 配额**：`JobSchedulerService`（`frameworks/base/services/core/java/com/android/server/job/JobSchedulerService.java`）按分桶给每个 app 分配 **E/J 配额（执行分钟数/每日）**，超限 Job 排队不执行。`WorkManager` 底层就是 JobScheduler。
4. **WakeLock**：`PowerManager.WakeLock`（`frameworks/base/core/java/android/os/PowerManager.java`，实现在 `frameworks/base/services/core/java/com/android/server/power/PowerManagerService.java`）防止系统休眠；持有过久会被 `Watchdog`/电池统计报滥用。

**Android 16 配额变更（近期热点，必考）：** Developer 文档明确 JobScheduler 三处收紧——
- **变更1：active 分桶也开始受宽松运行时配额约束**（以前 active 几乎无限）。
- **变更2：Top 状态启动的 Job，app 变不可见后继续跑受配额限制**。
- **变更3：与前台服务（FGS）并发执行的 Job 同样受配额**。
影响面覆盖 `WorkManager`/`JobScheduler`/`DownloadManager`。Google 建议迁移到 `WorkManager`（已自动适配配额）。

**关键源码路径（Android 14/16）：**
- `frameworks/base/services/core/java/com/android/server/DeviceIdleController.java` —— `becomeInactiveIfAppropriate`/`onBootPhase`、`mMode`（deep/light idle）
- `frameworks/base/services/core/java/com/android/server/usage/AppStandbyController.java` —— `getAppStandbyBucket()`、bucket 计算
- `frameworks/base/services/core/java/com/android/server/job/JobSchedulerService.java` —— `maybeQueueReadyJobsForExecutionLocked`、`mConstants`（配额）
- `frameworks/base/core/java/android/app/job/JobScheduler.java`、`JobInfo.java`
- `frameworks/base/core/java/android/os/PowerManager.java` —— `newWakeLock()`、`WakeLock`
- `frameworks/base/services/core/java/com/android/server/power/PowerManagerService.java`

**易错点：**
- 「后台用 `AlarmManager.setExact` 就能准时跑」——Doze 下 `setExact` 也会被延迟（除非 `setExactAndAllowWhileIdle`/`setAlarmClock`，后者仅闹钟类）。
- 「`WorkManager` 不受配额影响」——错，它**底层就是 JobScheduler**，只是帮你自动适配；Android 16 的变更对 WorkManager 用户基本透明正是因为它做了适配。
- `WakeLock` 忘记 `release()` 是最常见耗电 bug，且**无法被 GC 自动释放**（必须对称 acquire/release）。

**高频追问：**
- `restricted` 分桶的 app 还能收 FCM 吗？（答：高优先级 FCM 仍可唤醒，但普通后台 Job/网络被严格限制——这是 Android 限制后台的一大「合法出口」）
- `setAndAllowWhileIdle` 和 `setExactAndAllowWhileIdle` 区别？（答：前者不保证精确时刻只保证「允许在 idle 时跑」，后者保证精确时刻且允许 idle 跑）
- Doze 的 light idle 和 deep idle 区别？（答：light idle 仅延迟网络与部分 Job；deep idle 还限制 GPS/传感器/Wi-Fi 扫描）

**延伸阅读：**
- AOSP Docs《Power management restrictions》《App Standby Buckets》
- `frameworks/base/services/core/java/com/android/server/job/JobSchedulerService.java#maybeQueueReadyJobsForExecutionLocked`
- 图形多媒体通信篇第 9 章 Power HAL（WakeLock 最终落到 Power HAL 调频）

---

<a id="11"></a>
## 11. Android 15/16 行为变更与 Framework 演进热点串讲

**Q：作为 Framework 工程师，近两年（15/16）最该关注的演进热点有哪些？**

**答案解析（串讲，呼应前文）：**

把前文零散热点收口成「面试官爱问的版本演进」清单：

1. **16KB 页面（第 1 章）**：Android 15 支持、16 加兼容模式、Play 强制对齐。Framework/驱动/HAL 的 ELF 对齐与硬编码页大小是最大兼容性雷区。
2. **Intent 重定向默认防护（Android 16）**：系统对「App A 把 Intent 交给 App B，B 又反射回 A 的受保护组件」这类攻击默认拦截，开发者几乎无需改动——但定制 ROM 的 `PendingIntent`/跨 app 跳转逻辑要回归。
3. **Predictive Back 三按钮导航（Android 16）**：预测式返回动画扩展到 3 按钮导航（长按返回键预览目标），需 app 完成 predictive back 迁移（`AndroidManifest`/`OnBackInvokedCallback` 替代 `onBackPressed`）。
4. **GBL 通用引导加载（Android 16）**：Generic Bootloader 标准化可更新 bootloader，简化启动链——与第 5 章 Verified Boot 强相关。
5. **CAP AIDL 音频政策（Android 16）**：可配置音频政策（Configurable Audio Policy）补齐 AIDL HAL 定义，汽车/多输出设备受益（呼应图形多媒体通信篇第 9 章 Power/Audio HAL）。
6. **Virtual Device Owner（AVF 衍生）**：可信 app 在手机本地建虚拟设备并把画面投影到车机/PC/VR，可覆盖 app 的 orientation/resizability 限制——WindowManager 新考点。
7. **AOSP 私有化（2025 行业大事件）**：Google 宣布减少 AOSP 公开同步，部分分支转内部开发——直接影响**获取 framework 源码、跟踪 upstream 变更**的方式（更多依赖厂商 drop / 镜像源）。
8. **ART 作为 Mainline 模块（持续）**：`com.android.art` APEX 让运行时**独立于系统 OTA 更新**——意味着你设备上跑的 ART 版本可能比 system image 新。理解 `art/`、`/apex/com.android.art/` 对排查运行时问题至关重要（呼应主篇/拓展篇 ART 相关内容）。
9. **Rust 持续替代 C++**：binder、libc 部分、keystore2、部分驱动移植 Rust——安全与内存正确性的长期趋势（见深挖篇第 5 章 Rust Binder）。

**关键源码/文档锚点：**
- `source.android.com/docs/whatsnew/android-16-release`（GBL/CAP/16KB/ITS）
- `developer.android.com/about/versions/16/behavior-changes-all`（Intent 重定向/兼容模式/Predictive Back）
- `external/avb/`（GBL/AVB）、`art/`（ART APEX）
- `frameworks/base/`（各行为变更落点）

**易错点：**
- 「版本变更只影响 app 工程师」——错，Framework/ROM 工程师要**实现/适配**这些变更（如 16KB 对齐、GBL、CAP AIDL）。
- 「AOSP 私有化与我无关」——对**需要跟踪 upstream、合入安全补丁、二开 framework** 的工程师影响巨大，必须建立自己的源码获取与 diff 渠道。

**高频追问：**
- 怎么验证一款设备/ROM 是否完整支持 16KB？（答：`getconf PAGESIZE`、查内核 `CONFIG_ARM64_16K_PAGES`、`zipalign -c -P 16` 校验预装 so）
- ART Mainline 化后，如何确认当前运行时版本？（答：`adb shell getprop ro.build.version.art` 或 `pm art` 相关、`/apex/com.android.art/`）

**延伸阅读：**
- `source.android.com/docs/whatsnew/android-16-release`
- AOSP Blog《ART as a Mainline module》《Rust in Android》

---

<a id="12"></a>
## 12. 查缺补漏 · 易错点 · 高频追问 · 延伸阅读

### 12.1 一句话速记（面试快答）
- **16KB 页**：ELF 加载对齐 + 原生 ring buffer 动态化；Play 强制对齐（A15+），A16 加兼容模式。
- **ClassLoader**：热修复=反射插 `DexPathList.dexElements` 数组头；类加载后不可换。
- **权限**：`checkSelfPermission` 全用户态不过内核；runtime permission(包级) ≠ AppOps(细粒度运行期)。
- **Keystore2/Keymint**：密钥可只存 TEE/StrongBox，AndroidKeyStore 只是句柄；Keymint 是 AIDL HAL。
- **AVB/dm-verity/fscrypt**：verity 保完整(防篡改/只读分区)，fscrypt 保机密(/data 加密)。
- **Vold/FUSE**：`/storage/emulated/0` 是 FUSE 虚拟视图，底 `/data/media/0`；sdcardfs 退场。
- **logd**：用户态日志守护，独立于内核 printk；`logcat -b kernel` 是其转存副本。
- **可观测性**：Looper Printer 找卡顿原因(堆栈)，Choreographer 量化掉帧。
- **RRO**：运行时插 `ApkAssets` 到资源查找链前，靠 idmap 映射 id；受权限+SELinux 约束。
- **后台治理**：分桶(Standby)+Doze+JobScheduler 配额+WakeLock；A16 收紧 active/Top/FGS 并发 Job 配额。

### 12.2 高频追问链（面试官常这样往下挖）
1. 16KB 页 → 你的 HAL 里硬编码 4096 会怎样？→ GKI 模块要重测什么？→ 怎么验证对齐？
2. ClassLoader 插桩 → 已加载类为何不能换？→ 多 ClassLoader 的 instanceof 问题？→ MultiDex 与 65536？
3. 权限 check → 只过 permission 够吗？→ AppOps 在哪层？→ Binder 调用方怎么鉴权（clearCallingIdentity）？
4. Keystore2 → 密钥真在文件里吗？→ StrongBox vs TEE？→ 远程证明怎么玩？
5. AVB → verity 哈希树怎么 O(log) 验证？→ 解锁 bootloader 为何不可信？→ fscrypt 的 DE/CE 区别？
6. Vold → emulated 卷底层是啥？→ 分区存储谁裁决？→ FUSE passthrough 为何快？
7. 可观测性 → Choreographer 能定位代码吗？→ 多个库抢 setMessageLogging 怎么办？→ Perfetto 比这强在哪？
8. RRO → 为什么需要 idmap？→ 普通 app 能 overlay 别家吗？→ 静态 overlay 与 RRO 共存？
9. 后台 → restricted 还能收 FCM 吗？→ WorkManager 不受配额？→ Doze light vs deep？

### 12.3 易错点清单（本篇全集）
- 16KB：「改宏即可」、「只有 NDK 要管」、「兼容模式是方案」——均错。
- 热修复：「DexClassLoader 能热更已运行类」——错，类加载后不可换。
- 权限：「checkSelfPermission==能用」——还要过 AppOps/后台状态。
- Keystore：「AndroidKeyStore 私钥存成文件可读」——错，TEE 模式只在安全硬件。
- AVB：「verified boot 就是加密」——错，verity 是完整性非机密性。
- Vold：「/storage/emulated/0 是真实分区」——错，FUSE 虚拟视图。
- logd：「logcat -b kernel 直读内核 ring」——是 logd 转存副本。
- 可观测性：「Choreographer 定位卡顿代码」——它只量化掉帧。
- RRO：「能替换任何资源」——受 id/SELinux/签名约束。
- 后台：「Alarm setExact 后台准时跑」——Doze 下仍延迟。

### 12.4 延伸阅读 / 动手实验
- `bionic/linker/linker_phdr.cpp` —— ELF LOAD 段与对齐校验（16KB 主线）。
- `libcore/dalvik/src/main/java/dalvik/system/DexPathList.java` —— `makeDexElements`/`findClass`（热修复主线）。
- `frameworks/base/services/core/java/com/android/server/pm/PermissionManagerService.java` —— `checkPermission`/`grantRuntimePermission`。
- `hardware/interfaces/security/keymint/IKeyMintDevice.aidl` —— 密钥 HAL 契约。
- `external/avb/libavb/avb_slot_verify.c` + `drivers/md/dm-verity-target.c` —— 信任链与哈希树。
- `system/vold/VolumeManager.cpp` + 内核 `fs/fuse/` —— 存储虚拟化。
- `system/core/logd/LogBuffer.cpp` —— 日志缓冲与 prune。
- `frameworks/base/core/java/android/os/Looper.java#loop` —— 卡顿探测钩子。
- `frameworks/base/services/core/java/com/android/server/om/OverlayManagerServiceImpl.java` —— RRO 挂载。
- `frameworks/base/services/core/java/com/android/server/job/JobSchedulerService.java` —— 配额与分桶。

### 交叉索引（本系列六篇）
| 篇 | 主题 | 文件 |
|---|---|---|
| 主篇 | Handler/Binder/AMS/WMS/View/ANR/内存/Compose/HAL/内核/MTK | `Android_Framework面试题_2026-07-23.md` |
| 拓展篇 | Input/PMS/ART-JIT/SystemUI/折叠屏/SELinux/OTA/JNI/Binder安全/Perfetto | `Android_Framework面试题_热点拓展_2026-07-23.md` |
| 深挖篇 | ART对象头/CMC GC/verify-deopt/Binder驱动/Rust Binder/Input多指/VSync时序/Camera/Audio/GKI/Perfetto SQL | `Android_Framework面试题_深挖篇_2026-07-23.md` |
| 图形多媒体通信篇 | HWUI/Choreographer/SF/图形内存/多刷新率/MediaCodec/Codec2/Thermal/Power/RIL/Wi-Fi/BT | `Android_Framework面试题_图形多媒体通信篇_2026-07-24.md` |
| 本篇 | 16KB页/ClassLoader/权限/Keystore/AVB-fscrypt/Vold-FUSE/logd/可观测性/RRO/后台治理/15-16演进 | `Android_Framework面试题_系统基建与可观测性篇_2026-07-27.md` |

> 复习建议：主篇打地基 → 拓展篇补盲区 → 深挖篇钻深水 → 图形篇通全栈渲染/多媒体/通信 → 本篇固系统地基与可观测性。六篇合起来基本覆盖 Android Framework 面试的「主链路 + 盲区 + 深水区 + 系统基建」四个维度。
