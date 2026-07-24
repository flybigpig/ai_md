# 修改 AMS 实战技术文档

> 基于 AOSP **Android 14 (UpsideDownCake, API 34)**。全程贴真实文件路径 + 方法名 + 可套用 patch + 最短编译验证链路。

---

## 0 一个必须先点明的关键认知

**从 Android 10 起,Activity 的启动/任务栈逻辑已经从 AMS 拆到了 `ActivityTaskManagerService`(ATMS)**,位于 `frameworks/base/services/core/java/com/android/server/wm/`。

- **AMS**(`.../server/am/ActivityManagerService.java`)现在只管:进程管理、广播、Service、ContentProvider、内存/OOM。
- **ATMS**(`.../server/wm/ActivityTaskManagerService.java` + `ActivityStarter` / `ActivityStack` / `Task` / `RootWindowContainer`)负责:Activity 栈、Task、生命周期状态机、Resume 流转。

> ⚠️ 结论:你要改的如果是「**启动行为 / 栈调度 / 生命周期**」,八成动的是 **ATMS**,不是 AMS。只有「进程、广播、Service、OOM」才在 AMS。别改错文件。

---

## 1 代码定位速查表

| 你想改的行为 | 目标文件(`frameworks/base/services/core/java/com/android/server/`) | 关键方法 |
|---|---|---|
| Activity 启动入口 / 权限校验 | `am/ActivityManagerService.java` | `startActivity` / `startActivityAsUser` |
| Activity 栈 / Task / Resume 流转 | `wm/ActivityTaskManagerService.java`、`wm/ActivityStarter.java`、`wm/ActivityStack.java`、`wm/Task.java` | `startActivityAsUser` → `ActivityStarter.execute()` → `startActivityInner()` |
| 进程孵化 / 管理 | `am/ProcessList.java`、`am/ProcessRecord.java` | `startProcessLocked` |
| Service 生命周期 | `am/ActiveServices.java` | `startServiceLocked` / `bindServiceLocked` |
| 广播分发 | `am/BroadcastQueue.java`、`am/BroadcastQueueModernImpl.java` | `enqueueBroadcastLocked` / `processNextBroadcast` |
| OOM adj 计算 | `am/OomAdjuster.java` | `computeOomAdjLSP` |
| shell 命令 `am ...` | `am/ActivityManagerShellCommand.java` | `onCommand` |

**对外 Binder 接口(AIDL)**:
- `frameworks/base/core/java/android/app/IActivityManager.aidl` —— AMS 的远程接口。
- `frameworks/base/core/java/android/app/IActivityTaskManager.aidl` —— ATMS 的远程接口。
- 客户端壳:`ActivityManager.java` / `ActivityTaskManager.java`(`core/java/android/app/`)。

---

## 2 实战示例 A:在启动路径插日志(最简单的切入)

在 `ActivityManagerService.startActivityAsUser` 开头插一行日志,观察谁在拉起 Activity:

```java
// frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java
public int startActivityAsUser(IApplicationThread caller, String callingPackage,
        Intent intent, String resolvedType, IBinder resultTo, String resultWho,
        int requestCode, int startFlags, ProfilerInfo profilerInfo,
        Bundle bOptions, int userId) {
    // ↓↓↓ 插入 ↓↓↓
    android.util.Slog.d("MyAMS", "startActivity caller=" + callingPackage
            + " uid=" + Binder.getCallingUid()
            + " intent=" + (intent != null ? intent.getComponent() : null));
    // ↑↑↑ 插入 ↑↑↑
    return mActivityTaskManager.startActivityAsUser(caller, callingPackage,
            /* ... 原参数 ... */);
}
```

验证:`adb logcat -b all | grep MyAMS`,随便点个 app 就能看到调用方。

> 注意上面这行 `mActivityTaskManager.startActivityAsUser(...)` —— 正说明启动实际转交给了 ATMS。真要拦截「能不能启动」,在下面示例 B 的位置动手更彻底。

---

## 3 实战示例 B:新增一个隐藏 API(改 AIDL + AMS 实现 + 客户端壳)

给 AMS 加一个自定义方法 `myCustomCheck`,是「扩展 framework 能力」的标准三步。**顺序不能错,否则 `system_server` 起不来。**

**Step 1 — 改 AIDL(远程接口声明)**
```aidl
// frameworks/base/core/java/android/app/IActivityManager.aidl
+ boolean myCustomCheck(String pkg);
```

**Step 2 — 在 AMS 实现(不实现就 `AbstractMethodError` → system_server 崩溃循环)**
```java
// .../server/am/ActivityManagerService.java
@Override
public boolean myCustomCheck(String pkg) {
    enforceCallingPermission(android.Manifest.permission.DUMP, "myCustomCheck");
    Slog.d("MyAMS", "myCustomCheck pkg=" + pkg);
    return "com.example.allowed".equals(pkg);
}
```

**Step 3 — 客户端壳(app 侧可调)**
```java
// frameworks/base/core/java/android/app/ActivityManager.java
public boolean myCustomCheck(String pkg) {
    try {
        return getService().myCustomCheck(pkg);
    } catch (RemoteException e) {
        throw e.rethrowFromSystemServer();
    }
}
```

> 若要作为 `@SystemApi` / `@hide` 给外部用,还得处理 `hiddenapi` 黑白名单(否则运行时被拦)。改了 AIDL 属于「framework 接口变更」,**必须整编 `m`**,只 `m services` 不够。

---

## 4 三份可直接套的 patch(`ams_patches/`)

| patch | 作用 | 落点 | 编译范围 |
|---|---|---|---|
| `01_intercept_pkg_launch.patch` | 在 `startActivityAsUser` 开头读 `persist.myams.enable_block` + `persist.myams.block_pkg`,命中则 `return ActivityManager.START_CANCELED` 拦截启动 | `am/ActivityManagerService.java` | `m services` |
| `02_cmd_activity_switch.patch` | 在 `ActivityManagerShellCommand.onCommand` 加 `am myams block <pkg>` / `unblock` / `status`,运行时写上面两个属性,与 01 **联动、无需重编即可开关** | `am/ActivityManagerShellCommand.java` | `m services` |
| `03_hidden_api_myCustomCheck.patch` | 示例 B 落地:AIDL + AMS 实现 + `ActivityManager.java` 客户端壳 | `IActivityManager.aidl` + 2 处 | 改 AIDL 必须 `m` |

> 每个 patch 头部都写了**「定位插入点」**;行号是 Android 14 典型 hunk 的参考值,你手上具体 tag 可能偏移,**推荐按插入点手动贴**(比 `git apply` 稳)。

**运行时开关设计(01 + 02 联动的价值)**:实战中常用 `SystemProperties` / `Settings.Global` 做功能开关,避免每次改逻辑都重编刷机。例:
```java
if (SystemProperties.getBoolean("persist.myams.enable_block", false)) { /* 拦截逻辑 */ }
```
改完刷一次机后,后续只需 `am myams block com.xxx` 即可动态开关。

---

## 5 最短编译 + 验证链路

**只改了 AMS/ATMS 的 `.java`(未动 AIDL)** —— 只重编 `services.jar`,1~3 分钟:
```bash
source build/envsetup.sh
lunch aosp_cf_x86_64_phone-userdebug   # 或你的 target
m services -j$(nproc)                   # 产物: out/.../system/framework/services.jar
```

**推送验证(必须 reboot,services.jar 不重启不生效)**:
```bash
adb root && adb remount
adb push out/target/product/<device>/system/framework/services.jar /system/framework/
adb reboot
# 起来后:
adb shell dumpsys activity activities   # 看栈
adb shell am start -n com.xxx/.MainActivity   # 触发
adb logcat -b all | grep MyAMS
# 若用了示例 A 的 patch:
adb shell am myams block com.xxx.yyy    # 运行时开关
```

**改了 AIDL / framework 客户端壳** —— 必须整编:
```bash
m framework services -j$(nproc)   # 或直接 m
```

---

## 6 六个最容易翻车的坑

1. **改错文件** —— 启动/栈逻辑在 **ATMS**(`wm/`),不在 AMS(`am/`)。改半天没效果多半是这个。
2. **AIDL 加了方法却没在 AMS 实现** —— `system_server` 启动即 `AbstractMethodError`,进 bootloop。改 AIDL 必成对实现。
3. **hiddenapi 黑名单** —— 新加的 `@hide` API 被运行时限制拦截,需处理 `hiddenapi-*` 标志。
4. **`services.jar` 不 reboot 不生效** —— push 后必须 `adb reboot`,热替换无效。
5. **只 `m services` 但改了客户端壳** —— 客户端壳在 `framework.jar` 里,需要 `m framework`。
6. **签名问题** —— `services.jar` 必须用 platform 签名;自编 userdebug 默认已对齐,别用外部签名覆盖。

---

## 7 相关文档索引

- Binder 内核机理 / 一次拷贝 / 异步空间 / deferred gc → `binder_aidl.md`、`android_framework_paper.md`
- Android 14 全量/增量/内核/模拟器编译、添加系统 App → `android14_build.md`(§1–§12)
- 本文的三份 patch → `ams_patches/01_intercept_pkg_launch.patch` / `02_cmd_activity_switch.patch` / `03_hidden_api_myCustomCheck.patch`
