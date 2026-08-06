# Windows 系统日志分析报告

- **分析窗口**:2026-08-04 07:13:07 ~ 08:13:07(最近一小时)
- **数据来源**:Windows 事件日志 `System` + `Application` 通道
- **分析结论**:**系统健康,无错误 / 警告 / 严重事件**。事件少是因为系统在大部分时间处于现代待机(Modern Standby / S0ix)低功耗状态,08:08 才被输入设备唤醒。

---

## 1. 总体统计

| 指标 | 数值 |
|---|---|
| 事件总数 | 14 |
| 错误(Error) | 0 |
| 警告(Warning) | 0 |
| 严重(Critical) | 0 |
| 信息(Information) | 14 |
| System 通道 | 7 |
| Application 通道 | 7 |

## 2. 来源分布

| 来源(ProviderName) | 条数 | 通道 | 性质 |
|---|---|---|---|
| Microsoft-Windows-Security-SPP | 6 | Application | Windows 软件保护/激活服务心跳 |
| Service Control Manager | 4 | System | 服务启动类型变更(BITS) |
| Microsoft-Windows-IsolatedUserMode | 2 | System | VBS 隔离用户模式 trustlet 启停 |
| Edge | 1 | Application | Edge 浏览器扩展 GC |
| Microsoft-Windows-Kernel-Power | 1 | System | 系统会话电源状态转换(唤醒) |

---

## 3. 事件明细与逐项解读

### 3.1 Microsoft-Windows-Security-SPP(6 条,Application)
Windows 软件保护平台(Software Protection Platform),负责激活状态与许可证维护,其事件均为后台例行维护:

- **EventID 16394 — "脱机下级迁移成功"**(07:16 / 07:18 / 08:08 各一次)
  许可证数据的脱机迁移/同步成功,正常后台维护。
- **EventID 16384 — "安排软件保护服务在 … 重新启动成功。原因: RulesEngine"**(07:16 / 07:18 / 08:08)
  SPP 按 RulesEngine 调度下一次自检。**注意消息里的日期是 `2126-07-10` 这种"远未来"**——这是 SPP 内部用于占位调度的机制,**不是系统时钟错乱**,不要误报。

> 解读:SPP 在 08:08 集中出现,正好对应下面 3.5 的系统唤醒,属唤醒后服务恢复心跳,正常。

### 3.2 Service Control Manager(4 条,System)
- **EventID 7040 — BITS 服务启动类型反复切换**

  | 时间 | 变更 |
  |---|---|
  | 07:25:11 | 按需启动 → 自动启动 |
  | 07:27:27 | 自动启动 → 按需启动 |
  | 07:42:28 | 按需启动 → 自动启动 |
  | 07:44:49 | 自动启动 → 按需启动 |

  BITS(后台智能传输服务,Windows Update 等下载依赖)的启动类型在"自动"与"按需"之间**来回横跳 4 次**。
  通常是某个程序在临时需要 BITS 时置为自动、用完改回按需。单次看无害,但这种"反复横跳"值得留意——若当时你并未手动触发 Windows Update,建议排查是哪家软件/驱动安装器在调度 BITS(可用 Sysinternals Autoruns / Process Monitor 在该时间段抓注册表写入 `HKLM\SYSTEM\CurrentControlSet\Services\BITS\Start`)。

### 3.3 Microsoft-Windows-IsolatedUserMode(2 条,System)
基于虚拟化的安全(VBS)相关的安全 trustlet 生命周期:
- **EventID 2** — `Secure Trustlet Id 0 and Pid 0 stopped with status STATUS_SUCCESS`(07:22:41)
- **EventID 5** — `Secure Trustlet NULL Id 0 and Pid 0 started with status STATUS_SUCCESS`(07:22:41)

  `STATUS_SUCCESS` 表示正常启停。一般出现在系统从低功耗恢复后,安全子系统(如 Credential Guard / 安全内核)重新初始化。正常。

### 3.4 Edge(1 条,Application)
- **EventID 256** — `Garbage collection for extensions on file thread is complete.`(07:23:11)
  来自 Edge 浏览器 chrome 内部线程的扩展垃圾回收完成日志。纯正常后台行为。

### 3.5 Microsoft-Windows-Kernel-Power(1 条,System)
- **EventID 566** — `系统会话已从22转换为 24. 原因 InputHid  BootId:45`(08:08:03)

  系统会话电源状态转换,**原因 `InputHid`(输入人机接口设备)**,即用户通过键盘 / 鼠标 / 触摸唤醒了系统。这解释了:
  1. 07:13~08:08 之间事件极少——系统处于现代待机(S0ix / Connected Standby)低功耗,不产生活跃日志。
  2. 08:08 之后 SPP / 各类服务恢复活动(见 3.1、3.2)。

---

## 4. 值得关注的点(非异常,但可留意)

1. **BITS 启动类型在 07:25–07:44 反复横跳 4 次**(见 3.2)。若非你主动触发更新,建议追查来源进程。
2. **系统 08:08 才被输入设备唤醒**(见 3.5),此前一小时大部分时间处于现代待机。日志"很空"是待机所致,不是日志系统故障。
3. **SPP 消息里的 2126 年日期是占位调度值,非时钟异常**(见 3.1),勿误报。

---

## 5. 复现命令(PowerShell)

```powershell
$now=Get-Date; $start=$now.AddHours(-1)
$all=@()
foreach($l in @('System','Application')){
  try { $all += Get-WinEvent -FilterHashtable @{LogName=$l; StartTime=$start} -ErrorAction Stop } catch {}
}
$all | Sort-Object TimeCreated | Format-Table TimeCreated, ProviderName, Id, LevelDisplayName, @{n='Msg';e={($_.Message -split "`n")[0]}}
# 仅看错误/警告:
$all | Where-Object { $_.LevelDisplayName -in 'Error','Critical','Warning' }
```

如需涵盖安全审计 / 驱动 / 特定应用,把通道扩为 `@('System','Application','Security')` 或指定应用日志名(如 `Microsoft-Windows-WindowsUpdateClient/Operational`)。

---

## 6. 延伸建议

- 想看**驱动 / 内核**层面问题:加 `System` 已覆盖大部分;更细看 `Microsoft-Windows-Kernel-PnP`、`Microsoft-Windows-NDIS`。
- 想看**应用崩溃**:Application 通道的 `Application Error`(来源 `Application Error`, EventID 1000)才是崩溃信号——本窗口内为 0,说明无进程崩溃。
- 想看**登录 / 安全审计**:需管理员权限查 `Security` 通道。

> 本分析仅覆盖 System + Application。如需我把 Security 通道、或你指定的某个应用/驱动日志也纳入,告诉我范围即可重跑。
