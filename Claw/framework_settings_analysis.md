# Android Framework Settings 子系统分析（Android 14 / AOSP）

> 版本基准：**Android 14 (UpsideDownCake, API 34)**，源码路径以 `android-14.0.0_rXX` 为准。
> 本文是 `settings_modify_practice.md` 的**互补篇**：实战篇讲「怎么改」，本篇讲「为什么这么工作」。

---

## 0 一句话定位

Settings 是 Android 的**系统级键值配置中心**：对外通过 `android.provider.Settings` 暴露 API，对内由独立系统进程 `SettingsProvider` 托管，底层落地为**每用户 × 每命名空间**的 XML 文件。它不依赖 SQLite 运行时存储，写入是同步进内存 + 异步落盘 XML。

---

## 1 四层架构

| 层 | 模块 / 进程 | 关键产物 | 职责 |
|----|-------------|----------|------|
| 应用层 | `packages/apps/Settings` + 各系统 App | `Settings.apk` | 提供 UI、读写配置 |
| 公共 API 层 | `frameworks/base/core/java/android/provider/Settings.java` | `framework.jar` | 暴露 `Global/SSystem/Secure` 常量与 `get/put`，维护本地缓存 |
| Provider 层 | `frameworks/base/packages/SettingsProvider` | `SettingsProvider.apk` | 真正的读写执行者，权限校验、落盘、通知 |
| 存储层 | `SettingsState` + `AtomicFile` | XML 文件 | 内存态 + 原子落盘 |
| 观察者层 | `ContentService` + `ContentObserver` | system_server | 变更广播 |

---

## 2 三个命名空间对比

| 维度 | `Global` | `Secure` | `System` |
|------|----------|----------|----------|
| 作用域 | 设备级（所有用户共享） | 用户级（受限，系统/特权可写） | 用户级（legacy，部分已迁移到 Secure） |
| 可读 | 一般 App 可读（部分键受限） | 仅 `PUBLISHED_SECURE_SETTINGS` 公开可读 | 一般可读 |
| 可写 | 需 `WRITE_SECURE_SETTINGS` | 需 `WRITE_SECURE_SETTINGS`（signature\|privileged） | 需 `WRITE_SETTINGS`；部分键受 `PROTECTED_SETTINGS` 保护 |
| 存储文件 | `settings_global.xml` | `settings_secure.xml` | `settings_system.xml` |
| 典型键 | `DEMO_SWITCH`、`AIRPLANE_MODE_ON` | `ANDROID_ID`、`ENABLED_INPUT_METHODS` | `SCREEN_BRIGHTNESS`（已废弃迁移） |

---

## 3 关键源码文件清单（AOSP android-14.0.0_rXX）

| 路径 | 关键类 / 方法 | 职责 |
|------|---------------|------|
| `frameworks/base/core/java/android/provider/Settings.java` | `Global`/`Secure`/`System`、`NameValueCache`、`CALL_METHOD_*` | 公共 API、缓存、`call` 方法名常量 |
| `frameworks/base/core/java/android/content/ContentResolver.java` | `acquireProvider`、`call`、`registerContentObserver` | 跨进程调用与 observer 注册入口 |
| `frameworks/base/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java` | `call()`、`setGlobalSetting`、`getGlobalSetting`、`loadGlobalSettings`、`notifyForSettingsChange` | Provider 实现、默认值加载、变更通知 |
| `frameworks/base/packages/SettingsProvider/src/com/android/providers/settings/SettingsState.java` | `insertSettingLocked`、`getSetting`、`persistSettingsLocked`、`writeStateLocked`、`Setting` 内部类 | 内存态、原子落盘、source 机制 |
| `frameworks/base/packages/SettingsProvider/src/com/android/providers/settings/SettingsRegistry.java` | `getSettingsStateLocked(userId, type)` | 按 (user, namespace) 管理 `SettingsState` 实例 |
| `frameworks/base/packages/SettingsProvider/res/values/defaults.xml` | `<integer name="def_*">` | 默认值资源 |
| `frameworks/base/services/core/java/com/android/server/content/ContentService.java` | `registerContentObserver`、`notifyChange` | observer 注册表与广播 |
| `frameworks/base/cmds/settings/src/com/android/commands/settings/Settings.java` | `get`/`put`/`list` | `adb shell settings` 命令实现 |

---

## 4 读写全链路（以 `Settings.Global.putInt` 为例）

```
1. Settings.Global.putInt(cr, name, value)
2.   └─ Settings.Global.putStringForUser(cr, name, String.valueOf(value), cr.getUserId())
3.     └─ NameValueCache.putStringForUser(...)            // 本地缓存层
4.       └─ mProviderHolder.getProvider(cr)
5.         └─ cr.acquireProvider(Settings.AUTHORITY)      // AUTHORITY = "settings"
6.           └─ IContentProvider.call(pkg, CALL_METHOD_PUT_GLOBAL, name, args)   // Binder 调用
7.             └─ [跨进程] SettingsProvider.call(method, name, args)
8.               └─ SettingsProvider.setGlobalSetting(name, value, ...)
9.                 └─ mSettingsRegistry.getSettingsStateLocked(userId, SETTINGS_TYPE_GLOBAL)
10.                  └─ SettingsState.insertSettingLocked(name, value, pkg, tag, makeDefault, source)
11.                    └─ SettingsState.persistSettingsLocked()
12.                      └─ writeStateLocked(...) → AtomicFile → /data/system/users/0/settings_global.xml
13.  SettingsProvider.notifyForSettingsChange(...) → ContentResolver.notifyChange(uri)
14.    └─ [跨进程] ContentService.notifyChange → 注册的 ContentObserver 回调
```

> `getInt` 走 `NameValueCache.getStringForUser`：命中缓存（带 generation 版本号）则直接返回，未命中才走 `IContentProvider.call(CALL_METHOD_GET_GLOBAL)`，并把结果按 generation 写入本地缓存。`SettingsProvider` 改值后通过 `ContentService` 通知，触发缓存失效。

---

## 5 存储机制详解

- **内存态**：`SettingsState` 持有 `ArrayMap<String, Setting>`，`Setting` 字段 = `name / value / packageName / tag / defaultValue / source`。
- **落盘**：`AtomicFile`（先写临时文件再 `rename`，保证崩溃不损文件）。写入经 `Handler` **异步**执行，避免阻塞调用方。
- **路径**：`Environment.getUserSystemDirectory(userId)` = `/data/system/users/<id>/`，文件名 `settings_global.xml` / `settings_secure.xml` / `settings_system.xml` / `settings_ssaid.xml`。
- **缓存一致性**：`SettingsState` 维护 `mCurrentGeneration` 单调递增的 generation 计数器，`SettingsProvider` 通过 `ContentService` 把 namespace URI 的变化通知出去，`NameValueCache` 据此 invalidate。

> ⚠️ **修正实战篇的一处简化**：`settings_modify_practice.md` §0 写「底层是 data/system/users/.../settings_global.xml 对应的 SQLite」。Android 14 的**运行时存储就是 XML**（经 `SettingsState`+`AtomicFile`），SQLite 仅用于旧版 `settings.db` 的迁移与默认值引导，日常读写不碰 SQLite。

---

## 6 默认值来源与加载

- 资源默认：`frameworks/base/packages/SettingsProvider/res/values/defaults.xml`，如 `<integer name="def_demo_switch">0</integer>`。
- 加载入口：`SettingsProvider` 的 `loadGlobalSettings(...) / loadSystemSettings(...) / loadSecureSettings(...)`，内部 `loadSetting(state, name, value)` 把默认值 `insertSettingLocked(name, value, ..., makeDefault=true, source=SETTINGS_SOURCE_DEFAULT)` 写入 `SettingsState`。
- `makeDefault=true` 的默认值可被用户/系统写入覆盖；`source=DEFAULT` 在 source 优先级中最低。

---

## 7 变更通知机制

- 写入方：`SettingsProvider` 改完调用 `notifyForSettingsChange(userId, type, name)`，对 `content://settings/global/<name>`（或 namespace 级 URI）发 `notifyChange`。
- 注册方：App/服务 `ContentResolver.registerContentObserver(Settings.Global.getUriFor(name), false, observer)` → `ContentService.registerContentObserver`。
- 回调：`ContentObserver.onChange(selfChange)` 在主线程/指定 Handler 触发。
- URI 构造：`Settings.Global.getUriFor(name)` → `content://settings/global/<name>`。

---

## 8 权限模型

| 操作 | 所需权限 | 保护级别 |
|------|----------|----------|
| 写 `Global` | `WRITE_SECURE_SETTINGS` | signature\|privileged |
| 写 `Secure` | `WRITE_SECURE_SETTINGS` | signature\|privileged |
| 写 `System` | `WRITE_SETTINGS`（部分键需 `WRITE_SECURE_SETTINGS`） | signature\|privileged\|appop |
| 读受限 `Secure` 键 | `READ_PRIVILEGED_*` 或 privileged | signature\|privileged |

- 白名单：`Settings.Global.PUBLIC_SETTINGS` / `PRIVATE_SETTINGS`、`Settings.Secure.PUBLISHED_SECURE_SETTINGS` 控制 App 可见性。
- 校验位置：`SettingsProvider.enforceWritePermission(...)` / `enforceReadPermission(...)`。
- 普通第三方 App 既无权限也无签名，写 `Global/Secure` 会被 `SecurityException` 拒绝——这正是系统/特权 App（如你的 `Settings.apk`）才能改的原因。

---

## 9 Source / Override 机制

`Setting` 的 `source` 字段决定「谁写的值优先级更高」：

```
SETTINGS_SOURCE_DEVICE_OVERRIDE(3)  >  SETTINGS_SOURCE_CONFIG(4)  >  SETTINGS_SOURCE_DEVICE(2)
        >  SETTINGS_SOURCE_SYSTEM(1)  >  SETTINGS_SOURCE_DEFAULT(5)  >  UNCOPECIFIED(0)
```

- 用途：OTA 预置、DeviceConfig、设备策略（DO/PO）覆盖用户值。
- `Settings.Global.putInt` 默认 `source = SYSTEM`，可被 `DEVICE_OVERRIDE`/`CONFIG` 覆盖；这正是「用户改了又被系统/策略覆盖回去」的技术根因。

---

## 10 与 DeviceConfig / ConfigStore 的区别

| 维度 | Settings | DeviceConfig |
|------|----------|--------------|
| 用途 | 用户可见的持久化配置 | 平台/feature flag（多为隐藏开关） |
| 命名空间 | Global/Secure/System | 任意 namespace（如 `activity_manager`） |
| 访问 | `Settings.Global.getX` | `DeviceConfig.getProperty` |
| 存储 | `SettingsState` + XML | `DeviceConfig` 服务 + 文件 |
| 用户可读写 | 部分 | 否 |

---

## 11 启动与进程模型

- `SettingsProvider` 是独立 APK `com.android.providers.settings`，`android.uid.system`，开机随包扫描/publish 启动（非 `system_server` 内）。
- `ContentService` 在 `SystemServer` 中启动，承载全局 observer 注册表。
- **多用户**：每个 `userId` 一份 `SettingsState` + 一份 XML；`SettingsRegistry` 以 `(userId, type)` 为 key 索引。

---

## 12 调试命令

```bash
# 读写（global/secure/system 三选一）
adb shell settings get global demo_switch
adb shell settings put global demo_switch 1
adb shell settings list global

# 直接看落盘（root）
adb shell su -c "cat /data/system/users/0/settings_global.xml" | grep demo_switch

# 监听变更
adb shell content observe --uri content://settings/global/demo_switch
```

---

## 13 与实战篇的关系（交叉索引）

| 实战篇动作 | 对应本篇机制 |
|------------|--------------|
| 场景 C：新增 `Global.DEMO_SWITCH` 常量 | §3 `Settings.java` + §4 链路 + §6 默认值 |
| 场景 C：`loadGlobalSettings` 加默认值 | §6 默认值加载（注意是写进 `SettingsState`，非直接写 SQLite） |
| 编译 `m framework` + `m SettingsProvider` | §3 两产物分属 API 层与 Provider 层，缺一则键常量/默认值不生效 |
| 场景 A/B 只动 UI | 不涉及 Provider 层，符合 §1 分层 |

---

## 14 参考文档索引

- 修改实战（UI / 存储键 / 编译坑）→ `settings_modify_practice.md`
- Binder / AIDL 机理 → `binder_aidl.md`、`android_framework_paper.md`
- AMS / ATMS 修改实战 → `ams_modify_practice.md`（含 3 份 patch）
- AOSP 编译 / 加系统 app / 改内核 → `android14_build.md`
