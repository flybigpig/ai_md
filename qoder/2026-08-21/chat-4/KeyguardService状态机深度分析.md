# KeyguardService 状态机深度分析

## 一、KeyguardService 的定位

`KeyguardService` 本身**不是一个状态机**——它是 SystemUI 进程内实现 `IKeyguardService.aidl` 的 Binder 服务，职责是 Binder 转发层。真正的状态机在它所持有的 **`KeyguardViewMediator`（KVM）** 中。

```
system_server (PhoneWindowManager/PMS)
    │ Binder 调用
    ▼
KeyguardService (Binder Stub 实现)
    │ 方法委托
    ▼
KeyguardViewMediator (真正的状态机)
    │ Handler 串行化
    ▼
StatusBarKeyguardViewManager / KeyguardViewControllerImpl
```

`KeyguardService` 的 `mBinder` 收到所有框架回调后，直接转发给 KVM：

| KeyguardService 方法 | 转发目标 |
|---------------------|---------|
| `onSystemReady()` | KVM.onSystemReady() |
| `onStartedGoingToSleep()` | KVM.onStartedGoingToSleep() |
| `onFinishedGoingToSleep()` | KVM.onFinishedGoingToSleep() |
| `onStartedWakingUp()` | KVM.onStartedWakingUp() |
| `onFinishedWakingUp()` | KVM.onFinishedWakingUp() |
| `onScreenTurningOn()` | KVM.onScreenTurningOn() |
| `setKeyguardEnabled()` | KVM.setKeyguardEnabled() |
| `doKeyguardTimeout()` | KVM.doKeyguardTimeout() |
| `setOccluded()` | KVM.setOccluded() |
| `keyguardDone()` | KVM.keyguardDone() |
| `dismiss()` | KVM.dismiss() |

---

## 二、有限状态机：状态变量

KVM 内部使用一组 boolean 标志位构成状态向量：

```
S = { mSystemReady, mShowing, mOccluded, mScreenOn,
      mInputRestricted, mDeviceLocked, mSwitchingUser, mDeviceInteractive }
```

每个状态位的语义：

| 状态位 | 类型 | 初始值 | 说明 |
|--------|------|--------|------|
| `mSystemReady` | boolean | false | system_server 就绪后置 true，永不回退 |
| `mShowing` | boolean | true | 锁屏当前是否正在显示 |
| `mOccluded` | boolean | false | 被全屏 Activity 遮挡（如来电、导航） |
| `mScreenOn` | boolean | false | 屏幕是否亮着 |
| `mInputRestricted` | boolean | true | 系统输入（按键/触摸）是否受限 |
| `mDeviceLocked` | boolean | true | 设备是否处于锁定状态 |
| `mSwitchingUser` | boolean | false | 用户切换进行中 |
| `mDeviceInteractive` | boolean | false | (AOSP 13+) 设备是否交互中 |

这些状态位**不是正交的**——它们之间存在约束关系：

```
约束 1:  mSystemReady == false ⇒ 所有状态机操作跳过
约束 2:  mShowing == false ⇒ mOccluded 无意义
约束 3:  mInputRestricted == true ⇒ 屏蔽所有系统手势
约束 4:  mDeviceLocked == false && mShowing == true ⇒ 滑动即解锁
约束 5:  mSwitchingUser == true ⇒ 锁屏冻结，不响应事件
```

---

## 三、事件驱动：Handler 串行消息

KVM 不直接在任何调用线程中执行状态变更。所有外部事件先投递到 **内部 Handler 的消息队列**，串行出队执行。这是消除竞态的核心设计。

消息类型（按优先级排序）：

```
KEYGUARD_SHOW          (1) 显示锁屏
KEYGUARD_HIDE          (1) 隐藏锁屏
KEYGUARD_DONE          (1) 解锁完成
KEYGUARD_GOING_AWAY    (1) 解锁动画开始
SET_OCCLUDED           (1) 遮挡状态变化
KEYGUARD_TIMEOUT       (2) 锁屏超时（可延迟）
DOZE_CHANGED           (1) Doze 模式变化
USER_SWITCHING         (1) 用户切换
```

**为什么必须串行化？**

KVM 的接口由多个完全独立的线程同时调用：

| 调用者 | 线程 | 典型事件 |
|--------|------|---------|
| PowerManagerService | Binder 线程池 | 睡眠/唤醒回调 |
| WindowManagerPolicy | Binder 线程池 | screenTurningOn |
| ActivityManagerService | AMS 主线程 | setOccluded |
| BiometricService | Binder 线程池 | keyguardDone |
| Settings | 任意线程 | doKeyguardTimeout |

如果没有串行化，以下竞态必然发生：

```
时间线 →
T1:  Thread-A (PMS 回调)  → handleShow() → 开始挂载 KEYGUARD_DIALOG
T2:  Thread-B (解锁回调)  → keyguardDone() → 开始移除 KEYGUARD_DIALOG
结果: 挂载和移除并发执行 → 窗口状态不一致，Surface 泄漏或崩溃
```

Handler 消息队列将 T1 和 T2 排队，T2 一定在 T1 完成后才执行。

---

## 四、核心状态转移

### 4.1 显示锁屏：`SHOW` 路径

```
触发条件:
  ① 系统就绪后首次显示 (handleSystemReady → doKeyguardLocked)
  ② 屏幕唤醒 (onStartedWakingUp → handleShow)
  ③ 设备锁定 (doKeyguardTimeout → handleShow)

状态转移:
  mShowing: false → true
  mOccluded: 不变
  mInputRestricted: 不变 (默认为 true)

输出动作:
  ① mStatusBarKeyguardViewManager.show(options)
     → mStatusBarWindowController.setKeyguardShowing(true)
     → mKeyguardMonitor.notifyKeyguardState(true, ...)
     → adjustStatusBarLocked() [隐藏状态栏]
  ② setShowingLocked(true) [写入 Settings]
  ③ mUpdateMonitor.sendKeyguardVisibilityChanged(true)

前置检查:
  if (!mSystemReady) return;     // 系统未就绪跳过
  if (mShowing) return;          // 已显示跳过
  if (mScreenOn == false)        // 灭屏时记录但暂不显示
     → 延迟到 onScreenTurnedOn() 时真正挂载
```

### 4.2 解锁：`DONE → GOING_AWAY → HIDE` 三阶段

解锁不是一步完成的，而是三个阶段，每个阶段由一条 Handler 消息驱动：

**第一阶段 — `handleDone()`：逻辑解锁**

```
触发条件: 生物识别/PIN/Pattern/Password 验证通过

状态转移:
  mShowing: true → true (尚未变化)
  mOccluded: true/false → false
  mInputRestricted: true → false
  mDeviceLocked: true → false (FBE CE 密钥解锁)

输出动作:
  ① adjustStatusBarLocked() [恢复状态栏]
  ② 投递 MSG_KEYGUARD_GOING_AWAY
```

**第二阶段 — `handleGoingAway()`：解锁动画**

```
触发条件: handleDone 投递的消息

状态转移:
  mShowing: true → false

输出动作:
  ① notifyKeyguardGoingAway() [通知 WMS 准备动画]
  ② WMS 开始 startKeyguardExitAnimation()
     → KEYGUARD_DIALOG 窗口淡出
     → 后续 Activity 恢复动画
  ③ WMS 动画完成 → 回调 keyguardGone()
  ④ 投递 MSG_KEYGUARD_HIDE
```

**第三阶段 — `handleHide()`：窗口移除**

```
触发条件: 动画完成回调

状态转移:
  mShowing: false (已设置，不变)

输出动作:
  ① mStatusBarKeyguardViewManager.hide() [移除 KEYGUARD_DIALOG]
  ② setShowingLocked(false) [持久化]
  ③ sendBroadcast(ACTION_USER_PRESENT) [通知所有 App]
  ④ mUpdateMonitor.clearBiometricRecognized() [重置生物识别状态]
```

**为什么必须分三阶段？**

```
动机 1: mInputRestricted 必须在动画开始前就恢复
        → 用户能在动画期间就开始操作后续 App

动机 2: mShowing 必须在动画开始时置 false
        → 防止动画期间新的 setOccluded 等请求误判锁屏状态

动机 3: KEYGUARD_DIALOG 窗口必须在动画完全结束后才移除
        → 保证动画连贯性，避免窗口提前消失导致画面闪烁
```

### 4.3 遮挡：`SET_OCCLUDED` 路径

```
触发条件:
  setOccluded(true)  ← 全屏 Activity 启动（来电、地图导航）
  setOccluded(false) ← 全屏 Activity 退出

状态转移 (true):
  mOccluded: false → true
  mShowing: true (不变)
  mInputRestricted: true (不变！)

输出动作 (true):
  ① mStatusBarKeyguardViewManager.setOccluded(true)
     → 隐藏 KEYGUARD_DIALOG 窗口
     → Activity 窗口显示在锁屏之上
  ② 锁屏仍然 active（mInputRestricted 保持 true）
     → 下拉状态栏仍被禁止
     → 系统键仍被拦截

状态转移 (false):
  mOccluded: true → false
  mShowing: true (不变)

输出动作 (false):
  ① mStatusBarKeyguardViewManager.setOccluded(false)
     → 恢复 KEYGUARD_DIALOG 窗口显示
  ② 检查超时 → 如果距上次操作超过超时阈值
     → 重新锁定设备 (mDeviceLocked → true)

约束条件:
  if (!mShowing && isOccluded) return;
  // 没在显示锁屏时，不能被遮挡（防止状态矛盾）
```

### 4.4 睡眠唤醒：四阶段钩子

这是 KVM 与 PowerManagerService 协同最复杂的部分：

```
                   屏幕亮 (交互态)
                   mScreenOn=true, mDeviceInteractive=true
                   mShowing=true (锁屏时)
                        │
                        │ PMS.onStartedGoingToSleep()
                        │ (PowerManager 开始灭屏流程)
                        ▼
              ┌─────────────────────┐
              │  第一阶段: 开始灭屏   │
              │  onStartedGoingToSleep() │
              │  mDeviceLocked=true  │
              │  保存当前锁屏状态    │
              │  不操作窗口          │
              └─────────┬───────────┘
                        │ 灭屏过程完成
                        ▼
              ┌─────────────────────┐
              │  第二阶段: 灭屏完成   │
              │  onFinishedGoingToSleep() │
              │  mScreenOn=false     │
              │  mDeviceInteractive=false│
              │  隐藏 KEYGUARD_DIALOG │
              │  进入 Doze/AOD       │
              └─────────┬───────────┘
                        │ 用户按下电源键 / 抬手亮屏 / 双击唤醒
                        ▼
              ┌─────────────────────┐
              │  第三阶段: 开始亮屏   │
              │  onStartedWakingUp() │
              │  mDeviceInteractive=true │
              │  退出 Doze           │
              │  恢复 KEYGUARD_DIALOG │
              │  投递 KEYGUARD_SHOW  │
              └─────────┬───────────┘
                        │ 亮屏过程完成
                        ▼
              ┌─────────────────────┐
              │  第四阶段: 亮屏完成   │
              │  onFinishedWakingUp()│
              │  mScreenOn=true      │
              │  锁屏完全显示        │
              │  开始生物识别扫描    │
              └─────────────────────┘
                        │
                        │ 用户解锁 → 进入 DONE → GOING_AWAY → HIDE
                        ▼
                   正常使用 (解锁后)
                   mShowing=false, mInputRestricted=false
```

**关键同步点：** `onStartedWakingUp` 和 `onScreenTurningOn` 的关系：

```
时序 (按 PMS 调用顺序):
  ① onStartedWakingUp()  ← KVM 收到
  ② onScreenTurningOn()  ← KVM 收到，此时 surface 已准备好
  ③ WindowManager 显示窗口
  ④ onFinishedWakingUp() ← KVM 收到

KVM 的处理:
  ① 中: mDeviceInteractive=true，投递 KEYGUARD_SHOW
  ② 中: WindowManager.addView() 真正挂载 KEYGUARD_DIALOG
  ④ 中: 锁屏准备好接收用户输入
```

---

## 五、窗口可见性决策逻辑

KVM 的状态最终通过 WMS 的 `KeyguardController` 决定 `KEYGUARD_DIALOG` 窗口是否可见。决策树如下：

```
mSystemReady?
  ├── No  → 跳过，不显示锁屏
  └── Yes
       ├── Dozing?
       │   ├── Yes → 隐藏 KEYGUARD_DIALOG
       │   │          → 如果 AOD 启用，显示 AOD 内容
       │   │          → 不启用 AOD，屏幕全黑
       │   └── No
       │       ├── mShowing?
       │       │   ├── No → 无锁屏窗口，正常使用
       │       │   └── Yes
       │       │       ├── mOccluded?
       │       │       │   ├── Yes → 窗口存在但不可见
       │       │       │   │         Activity 窗口可见
       │       │       │   │         mInputRestricted 保持 true
       │       │       │   └── No → 窗口全屏可见
       │       │       │            └── mDeviceLocked?
       │       │       │                ├── Yes → 需要用户解锁
       │       │       │                │         → 显示 Bouncer (有凭证)
       │       │       │                │         → 显示滑动提示 (无凭证)
       │       │       │                └── No → 滑动即解锁
       │       │       └── 注: mSwitchingUser 不影响窗口
       │       │           但阻止所有用户交互
```

最终窗口状态由 `mShowing && !mDozing && !mOccluded` 决定：

```
KEYGUARD_DIALOG 可见 ⇔ mShowing == true
                       && mDozing == false
                       && mOccluded == false
```

---

## 六、三重竞态防护

KVM 使用三层防护确保状态一致性：

**第一层：Handler 串行化**

所有重量级操作（显示/隐藏/动画）不直接执行，投递到 Handler 消息队列：

```java
// 安全：投递到 Handler，串行执行
public void onScreenTurnedOff() {
    mScreenOn = false;  // 轻量状态可以原子操作
    mHandler.sendMessage(mHandler.obtainMessage(KEYGUARD_SHOW));
}

// 不安全：直接操作会导致竞态
public void onScreenTurnedOffBAD() {
    handleShow();  // 可能在错误的线程执行
}
```

**第二层：synchronized 块**

关键方法内的共享数据访问加内置锁：

```java
private void handleSystemReady() {
    synchronized (this) {
        mSystemReady = true;
        doKeyguardLocked(null);       // 在锁内
        mUpdateMonitor.registerCallback(mUpdateCallback);
    }
}
```

**第三层：前置条件检查**

每个 Handler 处理方法开头验证状态合法性：

```java
private void handleShow() {
    if (!mSystemReady) return;    // 系统未就绪
    if (mShowing) return;         // 已显示
    if (!mScreenOn) {             // 灭屏则延迟
        mDelayedShowing = true;
        return;
    }
    // ... 实际显示逻辑
}

private void handleSetOccluded(boolean isOcculded) {
    if (!mSystemReady) return;
    if (!mShowing && isOccluded) return;  // 没锁屏不能被遮挡
    // ... 实际遮挡逻辑
}
```

---

## 七、状态转移矩阵

| 当前状态 | 事件 | 新状态 | 输出 |
|---------|------|--------|------|
| `S0: idle` | systemReady | `S0 → S0` | doKeyguardLocked() |
| `S0: idle` | timeout | `S0 → S1` | show() |
| `S1: showing` | wakeup | `S1 → S1` | (已显示，无操作) |
| `S1: showing` | done | `S1 → S2` | unlock CE，send GOING_AWAY |
| `S2: goingAway` | animation complete | `S2 → S0` | hide()，send USER_PRESENT |
| `S1: showing` | occlude=true | `S1 → S3` | hide window，keep active |
| `S3: occluded` | occlude=false | `S3 → S1` | show window |
| `S1: showing` | goingToSleep | `S1 → S4` | 准备灭屏 |
| `S4: sleeping` | dozing=true | `S4 → S5` | hide KEYGUARD_DIALOG |
| `S5: dozing` | wakingUp | `S5 → S6` | recovery KEYGUARD_DIALOG |
| `S6: waking` | screenOn | `S6 → S1` | 锁屏可交互 |
| `*` | userSwitching | `* → S7` | 冻结锁屏 |
| `S7: switching` | switchComplete | `S7 → *` | 恢复锁屏 |

---

## 八、总结

KeyguardService 本身是薄 Binder 层，真正的状态机在 KeyguardViewMediator，其核心设计是：

1. **状态向量化**：6-8 个 boolean 构成组合状态，约束关系保证一致性
2. **事件串行化**：Handler 消息队列消除多线程竞态
3. **输出去耦合**：状态变更不直接操作窗口，通过 StatusBarKeyguardViewManager 间接输出
4. **三阶段解锁**：逻辑解锁 → 动画 → 窗口移除，保证平滑过渡
5. **四阶段睡眠唤醒**：与 PowerManagerService 协同，精确控制窗口挂载时机

理解这 5 个设计要点就掌握了整个锁屏状态机的行为逻辑。