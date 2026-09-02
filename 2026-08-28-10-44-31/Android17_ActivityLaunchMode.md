# Android 17（android_frameworks_base_seventeen）Activity 启动模式源码分析

> 基于 `android_frameworks_base_seventeen` 真实源码，所有路径/行号均可在本仓库复现。
> 关键结论：Android 17 共 **5 种 launchMode**（经典四模式 + `singleInstancePerTask`），
> 且任务分派逻辑已归属于 `com.android.server.wm`（WindowManager 包），不再在 `am` 包。

---

## 0. 一个必须先说清的架构变迁

在 A17 这套源码里：

```
services/core/java/com/android/server/wm/ActivityStarter.java   ← 启动分派核心
services/core/java/com/android/server/wm/ActivityRecord.java    ← Activity 运行时记录
services/core/java/com/android/server/wm/Task.java              ← 任务容器
core/java/android/content/pm/ActivityInfo.java                  ← launchMode 常量定义
```

`ActivityStarter` / `ActivityRecord` 物理上已在 `wm/` 包内（Android 10 起 ATMS 成为任务管理真正归属，
到 A17 源文件也跟着搬过来了）。面试/排查时别再去 `com.android.server.am` 找 `ActivityStarter` 了。

---

## 1. 常量定义层：`ActivityInfo.java`

文件：`core/java/android/content/pm/ActivityInfo.java`

| 常量 | 值 | 对应 Manifest 值 |
|---|---|---|
| `LAUNCH_MULTIPLE` | 0 | `standard` |
| `LAUNCH_SINGLE_TOP` | 1 | `singleTop` |
| `LAUNCH_SINGLE_TASK` | 2 | `singleTask` |
| `LAUNCH_SINGLE_INSTANCE` | 3 | `singleInstance` |
| `LAUNCH_SINGLE_INSTANCE_PER_TASK` | 4 | `singleInstancePerTask` |

定义位置（L82–L102）：

```java
public static final int LAUNCH_MULTIPLE = 0;              // L82 standard
public static final int LAUNCH_SINGLE_TOP = 1;            // L87
public static final int LAUNCH_SINGLE_TASK = 2;           // L92
public static final int LAUNCH_SINGLE_INSTANCE = 3;       // L97
public static final int LAUNCH_SINGLE_INSTANCE_PER_TASK = 4; // L102 singleInstancePerTask
```

`@LaunchMode` 注解（L104–L114）用 `@IntDef` 约束取值，并把第 5 种也收进枚举：

```java
@IntDef(prefix = "LAUNCH_", value = {
        LAUNCH_MULTIPLE, LAUNCH_SINGLE_TOP, LAUNCH_SINGLE_TASK,
        LAUNCH_SINGLE_INSTANCE, LAUNCH_SINGLE_INSTANCE_PER_TASK })
@Retention(RetentionPolicy.SOURCE)
public @interface LaunchMode {}
```

运行时字段 `public int launchMode;`（L139）即从 `AndroidManifest` 的 `android:launchMode` 解析而来。

---

## 2. 分派核心：`ActivityStarter.java`

`ActivityStarter` 是 `startActivity()` 链路上真正"读懂 launchMode 并决定复用/新建 Task"的地方。

### 2.1 模式取值与文档模式修正（L2863–L2876）

```java
mLaunchMode = r.launchMode;                                        // L2863 取当前 Activity 的模式

mLaunchFlags = adjustLaunchFlagsToDocumentMode(
        r, LAUNCH_SINGLE_INSTANCE == mLaunchMode,                  // L2866 singleInstance → 文档模式
        LAUNCH_SINGLE_TASK == mLaunchMode, mIntent.getFlags());    // L2867 singleTask   → 文档模式

if (mLaunchMode == LAUNCH_SINGLE_INSTANCE_PER_TASK) {             // L2872 第 5 种专属处理
    // singleInstancePerTask 强制加 NEW_TASK，保证不进 source task
    mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;                       // L2875
}
```

> 要点：`singleInstance` / `singleTask` 会经 `adjustLaunchFlagsToDocumentMode` 强制进入文档模式；
> `singleInstancePerTask` 在这里被**自动补上 `FLAG_ACTIVITY_NEW_TASK`**，所以开发者不必再手写 NEW_TASK。

### 2.2 singleTop 的复用判定（L2608–L2651）

在 `startActivityUnchecked` 的 `deliverToCurrentTop` 分支里：

```java
final ActivityRecord top = topRootTask.topRunningActivity(mNotTop);
final boolean dontStart = top != null
        && top.mActivityComponent.equals(mStartActivity.mActivityComponent)
        && top.mUserId == mStartActivity.mUserId
        && top.attachedToProcess()
        && ((mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0
        || LAUNCH_SINGLE_TOP == mLaunchMode)                      // L2614 模式或 flag 命中任一即可
        && (!top.isActivityTypeHome()
            || top.getDisplayArea() == mPreferredTaskDisplayArea);
if (!dontStart) return START_SUCCESS;                            // 不复用 → 真正新建
...
deliverNewIntent(top, intentGrants);                            // L2642 复用 → 走 onNewIntent
return START_DELIVERED_TO_TOP;                                  // L2650
```

> 语义：栈顶已是同组件实例 + 当前模式为 `singleTop`（或带了 `FLAG_ACTIVITY_SINGLE_TOP`）→ 不新建，
> 调 `onNewIntent()`，返回 `START_DELIVERED_TO_TOP`。

### 2.3 singleTask / singleInstance / singleInstancePerTask 的清栈（L2678–L2681）

`complyActivityFlags()` 中：

```java
} else if ((mLaunchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0
        || isDocumentLaunchesIntoExisting(mLaunchFlags)
        || isLaunchModeOneOf(LAUNCH_SINGLE_INSTANCE, LAUNCH_SINGLE_TASK,
                LAUNCH_SINGLE_INSTANCE_PER_TASK)) {              // L2680 三种模式共用清栈逻辑
    int[] finishCount = new int[1];
    final ActivityRecord clearTop = targetTask.performClearTop(mStartActivity, mLaunchFlags, finishCount); // L2686
    ...
    deliverNewIntent(clearTop, intentGrants);                    // L2699 目标任务内找到 → 复用+清栈
}
```

> 语义：这三种模式只要目标任务里**已存在该实例**，就会把实例之上的 Activity 全部 `finish`，
> 把实例顶上来并走 `onNewIntent`。`performClearTop()` 是真正的清栈执行点。
> 注意 `singleTop` 不在此分支——它只关心"栈顶"，不清栈。

### 2.4 singleInstancePerTask 的排他约束（L3179–L3185）

```java
if (intentActivity != null && mLaunchMode == LAUNCH_SINGLE_INSTANCE_PER_TASK
        && !intentActivity.getTask().getRootActivity().mActivityComponent.equals(
                mStartActivity.mActivityComponent)) {
    // 该 task 是因为 affinity 命中而被选中的，但它不是 task root
    // → singleInstancePerTask 不能复用这种 task，置空让它新建自己的 task
    intentActivity = null;                                       // L3184
}
```

> 这是 `singleInstancePerTask` 区别于 `singleTask` 的核心：**它只在"作为 task root"时复用**；
> 若因 `taskAffinity` 命中了一个已有 task 但自己不是 root，就拒绝复用、强制新建专属 task。
> 因此同一个 task 里 `singleInstancePerTask` Activity 永远是 root 且唯一。

### 2.5 辅助方法 `isLaunchModeOneOf`（L3534 / L3538）

```java
private boolean isLaunchModeOneOf(int mode1, int mode2) { ... }              // L3534
private boolean isLaunchModeOneOf(int mode1, int mode2, int mode3) { ... }   // L3538
```

集中收口"模式匹配"判断，避免散落 `==` 比较。

---

## 3. 五种模式语义对照表

| 模式 | Task 归属 | 复用条件 | 清栈行为 | 典型场景 |
|---|---|---|---|---|
| `standard` (0) | 每次启动新建实例，进调用方 task | 永不复用 | 无 | 普通页面 |
| `singleTop` (1) | 进调用方 task | **栈顶**同组件才复用 | 无 | 通知点击页、搜索页 |
| `singleTask` (2) | 独占一个 task（root） | task 内已存在即复用 | 清掉实例之上的全部 | 浏览器、App 主页 |
| `singleInstance` (3) | 独占**整个** task，且 task 内只有它一个 | task 内已存在即复用 | 清掉实例之上的全部 | 来电界面、闹钟 |
| `singleInstancePerTask` (4) | 每个 task 里作为 root 独占一份 | **同 task 内**已存在即复用；跨 task 新建 | 清掉实例之上的全部 | 多窗口/多实例工具页 |

> 与 `singleTask` 的关键差异：`singleTask` 一个应用进程内**全进程共享一个**该 Activity 实例；
> `singleInstancePerTask` 是**每个 task 一份**（多任务/分屏下可同时存在多个实例，但各自独占自己的 task）。

---

## 4. 与 FLAG 的交互规则（源码实证）

| FLAG | 与 launchMode 的关系 |
|---|---|
| `FLAG_ACTIVITY_NEW_TASK` | `singleTask`/`singleInstance`/`singleInstancePerTask` 隐式具备；`singleInstancePerTask` 在 L2875 被强制补上 |
| `FLAG_ACTIVITY_SINGLE_TOP` | 与 `singleTop` 模式**等价**（L2614 取或） |
| `FLAG_ACTIVITY_CLEAR_TOP` | 触发与 singleTask 同款 `performClearTop`（L2678） |
| `FLAG_ACTIVITY_NEW_DOCUMENT` | 被 `singleInstance`/`singleTask` 抑制（日志见 L3487："Ignoring FLAG_ACTIVITY_NEW_DOCUMENT, launchMode is singleInstance or singleTask"） |

---

## 5. 调用链速查（从 App 到 WM）

```
Context.startActivity(Intent)
  → ActivityTaskManagerService.startActivity()           [wm]
    → ActivityStarter.execute()                           [wm/ActivityStarter.java]
      → startActivityUnchecked()                          ← 这里读 mLaunchMode
        ├─ singleTop 分支 : deliverToCurrentTop()         L2608
        ├─ singleTask/Instance/PerTask : complyActivityFlags() → performClearTop()  L2678
        └─ 新建 Task : getOrCreateRootTask() / Task.startActivityLocked()
```

---

## 6. 面试高频追问速答

1. **singleTask 和 singleInstancePerTask 区别？**
   前者进程级单例（一个 task）；后者 task 级单例（每个 task 各一份，多窗口可并存多实例）。

2. **为什么 singleInstancePerTask 要强制 NEW_TASK（L2875）？**
   保证它绝不进调用方 task，永远拥有自己的 task，从而能作为该 task 的 root 独占。

3. **singleTop 和 FLAG_ACTIVITY_SINGLE_TOP 完全一样吗？**
   对 WM 而言等价（L2614 取或），但 `singleTop` 写在 Manifest 对**所有**启动方生效，
   flag 只对**这一次** intent 生效。

4. **Android 17 的 ActivityStarter 为什么在 wm 包？**
   Android 10 起任务/回退栈管理移交给 ATMS（WindowManager 体系），A17 源文件物理归属 `wm/`。
