# Android Framework 面试题 · Perfetto SQL 实战扩充（input 延迟 / GPU 计数器 / battery 耗电细分）

> 日期：2026-08-11 ｜ 系列第 25 篇 ｜ 累计约 170 专题
> 主线 baseline：Android 14 (UpsideDownCake, API 34, android-14.0.0_rXX，内核 GKI android14-6.1)
> 热点锚定：Android 17 stable 已于 2026-06-16 发布（代号 CinnamonBun）；A17 QPR2 Beta 2 于 2026-08-03 推送（build CP41.260701.006，代号 DEV，仅图标微调+稳定性修复，无行为变更，stable 预计 2026-12）；A18 桌面融合 / 跨设备 Handoff / EU DMA 开放 11 项 AI 能力仍处路线图中。Perfetto 已成为 Android 性能/排查的事实标准，其 **trace_processor stdlib**（`android_input_events` / `android_gpu_*` / `android.power` 等内置表）让"一条 SQL 定责"成为 2026 面试高频考点。

---

## 0. 为什么要有"Perfetto SQL 扩充"这篇

第 21 篇（2026-08-07）已经把 Perfetto 落成了一份 SQL 范例库，覆盖了 **冷启动 / 掉帧定责 / 主线程阻塞 / 内存泄漏 / Binder 阻塞 / 电源唤醒** 六类。但当时有意识留了三块没写，正是今天要补的：

```
第 21 篇已覆盖           本篇新增（扩充）
---------------          -------------------------------
冷启动(startup)     -->  Input 延迟定界（input 系统 vs App）
掉帧(frame)         -->  GPU 计数器 / 渲染负载（GPU bound vs CPU bound）
主线程阻塞(sched)        Battery 耗电细分（电量/功耗/唤醒源归因）
内存(heapprofd)
Binder(binder_txn)
电源(cpu_freq/wakelock)
```

这三块的共同特点是：**它们都依赖 Perfetto 的特定数据源 + stdlib 表，抓不到就查不了，抓错了表名就跑不起来**。面试里考官常问"input 点了没反应是谁的锅""GPU 到底有没有成为瓶颈""这个 App 为什么这么费电"——本篇给的就是可直接复用的 SQL + 数据源配置 + AOSP 落点。

> 约定：文中 `.java/.cpp/.proto` 路径默认是 **Android 14 AOSP (android-14.0.0_rXX)**；Perfetto 表名均来自 trace_processor 内置 stdlib（已对照源码核对）。涉及 A17 新增项显式标注 `[A17]`。

---

## 专题一：Input 延迟定界 —— 一句话区分"系统派发慢"还是"App 处理慢"

**现象 / 考官提问**
> 用户反映"点了按钮要等半秒才有反应"，但滑列表又不卡。用 Perfetto 你怎么一眼判断：是 Input 系统把事件堵在路上，还是 App 主线程太忙没接住？有没有一条 SQL 直接给出定责？

**定界**
关键认知：**一次触摸从手指到 App 处理，天然分成两段**，Perfetto 的 `android_input_events` 表把这两条延迟精确拆开了：

```
手指 -> InputReader(读设备) -> InputDispatcher(派发) --socket--> App 输入管道 --> App 主线程处理 --> ACK
   |<- dispatch_latency_dur ->|                    |<- handling_latency_dur ->|        |<- ack ->|
                             系统侧(InputDispatcher 线程)           App 侧(主线程)
```

- `dispatch_latency_dur` 大 -> **系统侧**堵（InputDispatcher 队列积压 / 系统手势/窗口装饰先消费 / 多指拆分慢）。
- `handling_latency_dur` 大 -> **App 侧**堵（主线程在做别的，没及时走 `InputStage` 处理）。
- `end_to_end_latency_dur` 大且 `frame_id` 非空 -> 事件最终上屏也慢（可能叠加掉帧，见专题二）。

**底层原理 + 源码落点**
- 输入读取与派发都在 `system_server` 进程：`InputReader::loopOnce()` 从 `/dev/input` 读事件（`frameworks/native/services/inputflinger/reader/InputReader.cpp`）-> 经 `InputDispatcher::dispatchOnce()`（`frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp`）派发。`dispatchMotionLocked()` -> `findTouchedWindowTargetsLocked()` 决定事件落到哪个窗口，**这一步会被系统策略拦截**（导航栏 / freeform resize handle / 手势排除区，详见第 22/23 篇）。
- App 侧：`ViewRootImpl.deliverInputEvent()`（`frameworks/base/core/java/android/view/ViewRootImpl.java`）进入 `InputStage` 责任链（`ViewPostImeInputStage` 最终 dispatch 到 DecorView -> View 树）。**主线程被其他消息占住时，这条链就排不上**，于是 `handling_latency_dur` 飙升。
- 数据来源：要在抓 trace 时开 `android.input.inputevent`（详见末尾配置）。Perfetto 在 trace_processor 内置 `android_input_events` 表（由 `sendMessage(`/`receiveMessage(` 切片按 `event_channel`/`event_seq` 配对算出四段延迟），另有 `android_key_events` 视图。

**可直接复用的 SQL**

```sql
-- 一键列出"响应慢"的触摸事件，并按系统/App 两段定责
SELECT
  ts,
  dispatch_latency_dur / 1e6  AS dispatch_ms,   -- 系统侧：派发到 App 收到
  handling_latency_dur / 1e6  AS handling_ms,   -- App 侧：收到到 ACK
  total_latency_dur   / 1e6  AS total_ms,       -- 往返总延迟
  end_to_end_latency_dur / 1e6 AS e2e_ms,       -- 输入到上屏（含掉帧）
  frame_id
FROM android_input_events
WHERE total_latency_dur > 50e6          -- 超过 50ms 才算异常
ORDER BY handling_latency_dur DESC      -- 先看是不是 App 主线程的锅
LIMIT 30;
```

```sql
-- 聚合：这段 trace 里，系统侧 vs App 侧各占多少
SELECT
  AVG(dispatch_latency_dur)/1e6 AS avg_dispatch_ms,
  AVG(handling_latency_dur)/1e6 AS avg_handling_ms,
  MAX(handling_latency_dur)/1e6  AS worst_handling_ms
FROM android_input_events;
```

**易错点（红榜）**
- 把"点了没反应"一律归咎于 App 卡顿。错：先看 `dispatch_latency_dur`，系统手势/窗口装饰抢事件会显著拉长这段（`dispatch` 段大、`handling` 段正常）。
- 以为 input 延迟 = 掉帧。错：两者独立。`handling_latency_dur` 只到 App 处理完发 ACK；是否上屏掉帧要看 `end_to_end_latency_dur` + 第 21 篇的 `actual_frame_timeline_slice`。
- 抓不到数据就怪 SQL。错：必须在抓 trace 时显式开 `android.input.inputevent` 数据源，否则 `android_input_events` 为空。

**高频追问链**
1. 怎么证明是 InputDispatcher 队列积压而不是 App？-> 对照 `dispatch_latency_dur` 与 system_server 里 `InputDispatcher` 线程的 `thread_state`；若 dispatch 段在系统侧就大，证明是系统派发堵（常与多指/手势/窗口装饰竞争有关）。
2. App 主线程为什么接不住？-> 看同一时间窗 `thread_state` 主线程是不是 `Running`/`Runnable` 被别的消息占了，`monitor_contention` 是否严重（第 21 篇主线程阻塞 SQL）。
3. 系统手势抢事件怎么破？-> `View.setSystemGestureExclusionRects()`（Compose `Modifier.systemGestureExclusion()`），第 22/23 篇已展开。

**延伸阅读**：第 21 篇（Perfetto SQL 基础）、第 22/23 篇（手势路由 / freeform resize handle）、第 2 篇（Input 全链路）、第 20 篇（code walk·Input 派发）。

---

## 专题二：GPU 计数器与渲染负载 —— 怎么证明"到底是不是 GPU 瓶颈"

**现象 / 考官提问**
> 滑动掉帧，Perfetto 里 GPU 频率从 2.8G 掉到 1.2G，overdraw 也高。你怎么用 Perfetto 证明：是 GPU 真的算不过来（GPU bound），还是主线程/RenderThread 喂不及（CPU bound）？GPU 计数器要怎么抓？

**定界**
先分清三类"卡"：
1. **App 主线程 bound**：measure/layout/draw 在 TRAVERSAL 回调超时（看 `Choreographer` + 主线程 `thread_state`）。
2. **RenderThread bound**：Record/HWUI 指令录制慢，或等 GPU fence（RenderThread 卡在 `eglClientWaitSync` / fence）。
3. **GPU bound**：GPU 实际渲染/合成时间超过一帧预算，GPU 计数器（指令数、texel、overdraw、GPU busy%）爆高。

只有第 3 类才是"GPU 瓶颈"，其余都该从 CPU 侧优化。

**底层原理 + 源码落点**
- 帧渲染：主线程 `Choreographer` TRAVERSAL 回调做 measure/layout/draw -> `RenderThread` 经 HWUI/Skia 录制绘制指令 -> `RenderEngine`（`frameworks/native/libs/renderengine/`）提交 GPU -> `SurfaceFlinger` 调 `HWC` 决定 Overlay 合成 vs GPU 合成（`frameworks/native/services/surfaceflinger/DisplayHardware/`）。overdraw 多 -> 被迫 GPU 合成 -> GPU 负载高 -> 更易掉频（第 19/23 篇场景四）。
- GPU 计数器来源：vendor 专用数据源 `gpu.counters.<vendor>`（如 `gpu.counters.adreno` / `gpu.counters.mali`），需按厂商 counter id 配置；GPU 频率走 `linux.ftrace` 的 `power/gpu_frequency`；GPU 显存走 `gpu_mem/gpu_mem_total`。trace_processor 内置 `android_gpu_frequency` / `android_gpu_memory` stdlib 表，原始采样在 `gpu_counter` / `gpu_counter_track`（含 `gpu_id`/`ugpu`）。

**可直接复用的 SQL**

```sql
-- GPU 频率掉频的时间窗（配合掉帧时间点看是不是"降频导致卡"）
SELECT ts, value AS gpu_freq_khz, gpu_id
FROM android_gpu_frequency
WHERE gpu_id = 0
ORDER BY ts;
```

```sql
-- 采样某个 GPU 计数器（例如 GPU busy / 指令数），看是否触顶
SELECT
  c.ts, c.value, gct.name AS counter_name
FROM gpu_counter c
JOIN gpu_counter_track gct ON c.track_id = gct.id
WHERE gct.name GLOB '*busy*' OR gct.name GLOB '*GPU active*'
ORDER BY c.ts;
```

```sql
-- GPU bound 判定启发式：同一帧窗口内，GPU 相关切片耗时占比 vs 主线程绘制耗时
-- 思路：RenderThread 等待 GPU fence 的时长 / 主线程 draw 时长，比值高 => GPU bound
SELECT
  f.frame_number,
  f.jank_type,
  f.dur / 1e6 AS frame_dur_ms
FROM actual_frame_timeline_slice f
WHERE f.jank_type != 'None'
ORDER BY frame_dur_ms DESC
LIMIT 20;
-- （GPU busy 计数器需与上面 frame 时间窗 JOIN 才能精确定责到"GPU 算不过来"）
```

**易错点（红榜）**
- 看到 GPU 频率掉就说"是降频导致卡"。错：降频是发热的果（第 23 篇场景四）；要先证明降频前这帧 GPU 已经满载。
- 以为开了 `gpu.counters` 就行。错：必须带厂商后缀（`gpu.counters.adreno` 等）并按 `counter_ids`/`counter_names` 指定，**否则表里没数据**。
- 把"RenderThread 等 fence 久"当成 GPU bound。不一定：等 fence 久也可能是前面 CPU 提交晚，要拿 GPU busy 计数器佐证。

**高频追问链**
1. Overlay vs GPU 合成怎么用 Perfetto 看？-> 看 `actual_frame_timeline_slice.present_type`（如 `Presented`/`SCHEDULED`）+ SF 的 HWC 合成决策切片；能从 GPU 合成降成 Overlay（减少层数/overdraw）即省电（第 19/23 篇）。
2. GPU 显存泄漏怎么查？-> `android_gpu_memory` + `gpu_mem/gpu_mem_total`（ftrace），配合 `vulkan.memory_tracker` 数据源看 Vulkan 分配。
3. Android 14+ 还有什么 GPU 维度？-> `gpu.renderstages`（渲染管线时间线）+ Android 14 新增的 **GPU work period**（应用级 GPU 统计），可定位具体提交批次。

**延伸阅读**：第 21 篇（掉帧定责 SQL）、第 19 篇（卡顿掉帧全链路）、第 4/8 篇（SF/HWC/RenderEngine 合成决策）、第 20 篇（code walk·SF 一帧的一生）。

---

## 专题三：Battery 耗电细分 —— 电量曲线 + 唤醒源归因 + 发热链路

**现象 / 考官提问**
> 待测机待机一晚上掉 30% 电，logcat 看不出谁在耗电。Perfetto 怎么把"电量下降速率""谁在持有 WakeLock""谁在频繁唤醒"一次性拉出来？按 UID 归因又该用什么？

**定界**
耗电分析分两层，Perfetto 和 `dumpsys` 各管一段：
- **Perfetto**：给"时间线相关性"——电量什么时候掉的、掉的时候 CPU/GPU/唤醒在干什么（定性"是不是它"）。
- **`dumpsys batterystats` + BatteryHistorian**：给"按 UID 的精确归因"——哪个 App 吃了多少 mAh（定量"吃了多少"）。面试要结合两者。

**底层原理 + 源码落点**
- 电量数据：`android.power` 数据源（`battery_counters`: `BATTERY_COUNTER_CAPACITY_PERCENT`/`CHARGE`/`CURRENT`，`collect_power_rails: true`）-> trace_processor 落到 `counter` 表（track name 类似 `battery.charge_uah`/`battery.current_ua`/`battery.voltage_uv`）。`linux.ftrace` 的 `power/suspend_resume`、`regulator/*`、`power/clock_*` 给电源状态机/电压频率事件。
- 唤醒源：WakeLock 持有来自 `PowerManager`（`frameworks/base/core/java/android/os/PowerManager.java`）；聚合统计在 `BatteryStatsImpl`（`frameworks/base/core/java/com/android/internal/os/BatteryStatsImpl.java`），对外由 `PowerStatsService`（`frameworks/base/services/core/java/com/android/server/power/stats/PowerStatsService.java`，A14 新位置）+ `BatteryStatsHelper` 汇总，`dumpsys batterystats` 输出每 UID 的 Wakelock/Alarm/Sensor/GPS 耗时与估电。
- 发热链路：耗电高 -> 温度升 -> `ThermalManagerService`（`frameworks/base/services/core/java/com/android/server/thermal/ThermalManagerService.java`）经 Thermal HAL 上报 -> `Power HAL` 降频（第 19/23 篇场景四）。所以"耗电归因"和"发热降频"是同源问题。

**可直接复用的 SQL**

```sql
-- 电量随时间下降（用 counter 表，按 battery charge/current 画曲线）
SELECT ts, value, ct.name
FROM counter c
JOIN counter_track ct ON c.track_id = ct.id
WHERE ct.name LIKE 'battery.%'
ORDER BY ts;
```

```sql
-- 掉电速率：相邻采样电量差 / 时间差，挑掉得最快的窗口
SELECT
  ts,
  (LEAD(value) OVER (ORDER BY ts) - value) /
  ((LEAD(ts) OVER (ORDER BY ts) - ts) / 1e9) AS drain_per_sec
FROM counter c
JOIN counter_track ct ON c.track_id = ct.id
WHERE ct.name LIKE '%charge%' OR ct.name LIKE '%current%'
ORDER BY drain_per_sec ASC               -- 负数越大 = 掉得越快
LIMIT 20;
```

```sql
-- 唤醒源时间线：WakeLock 持有 / 频繁 alarm（需要 android.wakelock 或 atrace power 类别）
SELECT ts, dur / 1e6 AS hold_ms, name
FROM slice
WHERE name GLOB '*wakelock*' OR name GLOB '*WakeLock*'
ORDER BY dur DESC
LIMIT 30;
```

> 注意：以上 `counter` 部分来自 `android.power` 数据源，**必须抓 trace 时开**；`slice` 唤醒部分来自 atrace `power` 类别或 `android.wakelock`。**按 UID 精确 eats 多少电，请用 `adb shell dumpsys batterystats <pkg>`**，Perfetto 负责在上面 SQL 的时间窗里做相关性佐证。

**易错点（红榜）**
- 以为 Perfetto 能直接给"App X 吃了 5% 电"。错：Perfetto 给时间线，按 UID 归因靠 `dumpsys batterystats` / BatteryHistorian。
- 只看 `battery.current_ua` 瞬时值。错：要用 LEAD 窗口算"掉电速率"，瞬时值在采样间隔内抖动无意义。
- 把"唤醒次数多"等同于"耗电多"。错：还要看每次唤醒持有时长 + 是否把 CPU 拉到高频；短暂唤醒比长持 Wakelock 危害小得多。

**高频追问链**
1. 待机偷跑怎么定位？-> `dumpsys batterystats` 看 `Wakeup reasons` / `Alarm` 排行 + Perfetto `power/suspend_resume` 看是否频繁退出 suspend（曾被 Doze/AppStandby 限制，第 5/19 篇）。
2. 发热和耗电同源怎么一起查？-> 把 `thermal` 事件 / `cpu_frequency` 与上面电量曲线 JOIN，看掉电窗口是否正好 coincide 降频窗口（呼应第 19/23 篇场景四）。
3. A16 JobScheduler 配额对耗电影响？-> 后台 Job 配额收紧减少唤醒，直接降待机耗电（第 5 篇）。

**延伸阅读**：第 21 篇（电源/唤醒 SQL）、第 19 篇（发热掉速/后台受限）、第 5 篇（Doze/AppStandby/JobScheduler/WakeLock）、第 4 篇（Power HAL/ADPF/Thermal）。

---

## 4. 综合实战：三条跨表混合 SQL（压轴）

**SQL-A：input 延迟 × 掉帧 联合定责**（系统卡 vs App 卡 vs 上屏卡，一锅出）

```sql
SELECT
  e.ts,
  e.dispatch_latency_dur/1e6  AS sys_ms,
  e.handling_latency_dur/1e6  AS app_ms,
  e.end_to_end_latency_dur/1e6 AS e2e_ms,
  f.frame_number, f.jank_type
FROM android_input_events e
LEFT JOIN actual_frame_timeline_slice f
  ON f.frame_number = e.frame_id
WHERE e.total_latency_dur > 50e6
ORDER BY e.handling_latency_dur DESC;
```

**SQL-B：GPU 频率掉频 × 掉帧 联合**（证明"降频是不是卡顿元凶"）

```sql
SELECT
  f.frame_number, f.jank_type,
  g.value AS gpu_freq_khz, g.ts
FROM actual_frame_timeline_slice f
JOIN android_gpu_frequency g
  ON g.ts BETWEEN f.ts AND f.ts + f.dur
WHERE f.jank_type != 'None'
ORDER BY f.ts;
```

**SQL-C：电量掉速 × 降频 × 卡顿 三窗关联**（发热耗电掉帧同源论证）

```sql
SELECT
  c.ts,
  (LEAD(c.value) OVER (ORDER BY c.ts) - c.value) /
    ((LEAD(c.ts) OVER (ORDER BY c.ts) - c.ts)/1e9) AS drain_per_sec,
  cf.value AS cpu_freq_khz
FROM counter c
JOIN counter_track ct ON c.track_id = ct.id
LEFT JOIN cpu_frequency cf ON cf.ts BETWEEN c.ts AND c.ts + 1000000000
WHERE ct.name LIKE '%charge%' OR ct.name LIKE '%current%'
ORDER BY drain_per_sec ASC
LIMIT 20;
```

---

## 5. 抓 trace 的数据源配置（本篇三专题必需）

```text
# 一次性把三专题需要的源都开上（txt 配置，perfetto -c - --txt）
buffers: { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources: {
  config {
    name: "android.input.inputevent"          # 专题一：android_input_events
  }
}
data_sources: {
  config {
    name: "gpu.counters"                       # 专题二：gpu_counter（按厂商补 suffix）
    gpu_counter_config { counter_period_ns: 1000000 counter_ids: 1 counter_ids: 3 }
  }
}
data_sources: {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "power/gpu_frequency"     # 专题二：GPU 频率
      ftrace_events: "gpu_mem/gpu_mem_total"   # 专题二：GPU 显存
      ftrace_events: "power/suspend_resume"    # 专题三：suspend 唤醒
    }
  }
}
data_sources: {
  config {
    name: "android.power"                      # 专题三：电量/功耗 rail
    android_power_config {
      battery_poll_ms: 1000
      battery_counters: BATTERY_COUNTER_CAPACITY_PERCENT
      battery_counters: BATTERY_COUNTER_CHARGE
      battery_counters: BATTERY_COUNTER_CURRENT
      collect_power_rails: true
    }
  }
}
duration_ms: 10000
```

---

## 6. 跨专题易错红榜 TOP18（压轴速记）

1. input 延迟分两段：`dispatch`（系统侧）vs `handling`（App 侧），先定哪段大（专题一）。
2. 不开 `android.input.inputevent` 数据源，`android_input_events` 就空（专题一）。
3. input 延迟 ≠ 掉帧，看 `end_to_end_latency_dur` + frame 表才知上屏与否（专题一）。
4. GPU 计数器必须带厂商后缀（`gpu.counters.adreno` 等）并按 id 指定（专题二）。
5. 降频是发热的果不是卡顿的因，先用 GPU busy 计数器证明满载（专题二，呼应 23 篇）。
6. RenderThread 等 fence 久 ≠ GPU bound，要 GPU busy 佐证（专题二）。
7. Overlay vs GPU 合成看 `present_type` + HWC 决策切片（专题二）。
8. Perfetto 给耗电时间线，按 UID 归因靠 `dumpsys batterystats`（专题三）。
9. 电量要看"掉电速率"（LEAD 窗口），不看瞬时值（专题三）。
10. 频繁唤醒 ≠ 耗电多，还要看持有时长与拉频（专题三）。
11. 发热/耗电/降频同源，可三窗 JOIN 论证（专题三，呼应 19/23 篇）。
12. 三条混合 SQL 的核心都是"先按时间窗 JOIN，再按语义列定责"（§4）。
13. `gpu_counter_track` 有 `gpu_id`/`ugpu`，多 GPU 机器要 JOIN 区分（专题二）。
14. `android_gpu_frequency` / `android_gpu_memory` 是 stdlib 聚合表，原始在 `gpu_counter`（专题二）。
15. 待机偷跑先看 `Wakeup reasons` + `suspend_resume` 是否频繁退出 suspend（专题三）。
16. A16 JobScheduler 配额收紧直接降待机耗电（专题三，呼应第 5 篇）。
17. input 系统手势抢事件会拉长 `dispatch` 段，不是 App 锅（专题一，呼应 22/23 篇）。
18. 抓线上大 trace 用 RING_BUFFER + 按需开源，别全量（配置段 + 第 21 篇）。

---

## 7. 三条高频追问链（跨专题综合）

**链 A：一次"点了没反应"能挖多深？**
手指 -> InputReader -> InputDispatcher.dispatchOnce -> 系统策略拦截(手势/resize) -> socket 到 App -> ViewRootImpl.deliverInputEvent -> InputStage -> 主线程处理 -> ACK。用 `android_input_events` 把 dispatch/handling/e2e 三段拆开；dispatch 段大查系统侧队列，handling 段大查主线程 `thread_state`。

**链 B：一帧掉帧到底卡在 CPU 还是 GPU？**
主线程 TRAVERSAL(draw) -> RenderThread(HWUI/Skia 录制) -> RenderEngine 提交 -> GPU 执行 -> HWC 合成 -> 上屏。用 `actual_frame_timeline_slice.jank_type` 定掉帧，用 `gpu_counter`(busy/指令) + `android_gpu_frequency` 证 GPU bound，否则是 CPU 侧（主线程/RenderThread）。

**链 C：一台机器为什么又热又费电又卡？**
耗电(`android.power`/`counter`) -> 温度升 -> ThermalManagerService -> Power HAL 降频(`cpu_frequency`) -> 帧预算超标(`actual_frame_timeline_slice`) -> 掉帧。三窗 JOIN 即可把"热/电/卡"钉成同源，再回 `dumpsys batterystats` 定位元凶 UID。

---

## 8. 数据源 / AOSP 路径清单（本篇引用）

| 子系统 | 路径（android-14.0.0_rXX） |
| --- | --- |
| Input 读取 | `frameworks/native/services/inputflinger/reader/InputReader.cpp` |
| Input 派发 | `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp` |
| App 输入入口 | `frameworks/base/core/java/android/view/ViewRootImpl.java` (deliverInputEvent / InputStage) |
| Choreographer | `frameworks/base/core/java/android/view/Choreographer.java` |
| RenderEngine | `frameworks/native/libs/renderengine/` |
| HWC 合成 | `frameworks/native/services/surfaceflinger/DisplayHardware/` |
| HWUI/Skia | `frameworks/base/libs/hwui/` |
| PowerManager | `frameworks/base/core/java/android/os/PowerManager.java` |
| BatteryStats | `frameworks/base/core/java/com/android/internal/os/BatteryStatsImpl.java` |
| PowerStats | `frameworks/base/services/core/java/com/android/server/power/stats/PowerStatsService.java` |
| Thermal | `frameworks/base/services/core/java/com/android/server/thermal/ThermalManagerService.java` |
| PerformanceHint | `frameworks/base/core/java/android/os/PerformanceHintManager.java` |
| Perfetto 表 | `android_input_events` / `android_key_events` / `gpu_counter`(+`gpu_counter_track`) / `android_gpu_frequency` / `android_gpu_memory` / `counter`(+`counter_track`) / `actual_frame_timeline_slice` / `cpu_frequency` / `thread_state` |
| Perfetto 数据源 | `android.input.inputevent` / `gpu.counters.<vendor>` / `linux.ftrace`(`power/gpu_frequency`,`gpu_mem/gpu_mem_total`,`power/suspend_resume`) / `android.power` |

---

## 9. 25 篇交叉索引（前 24 篇 -> 本篇）

| 篇 | 主题 | 本篇衔接 |
| --- | --- | --- |
| 01~14 主/拓展/深挖/图形/基建/A17/渲染/TEE/pKVM/智能/座舱/收官 | 单点专题（约 132） | 本篇三专题为其配排查 SQL |
| 15 速查卡 | 15 篇知识地图 | 全局 |
| 16 连击考 | 考官连击形态 | 本篇 SQL 实战是"连击"的量化武器 |
| 17 全链路排查 | 冷启动/卡顿/ANR/内存/发热/Binder | 本篇补 input/GPU/battery 三段缺失 |
| 18 code walk | startActivity->首帧 / Binder 事务 | 本篇 GPU 段对应 SF 一帧 |
| 19 Perfetto SQL | 启动/掉帧/主线程/Binder/电源 SQL | 本篇是其"扩充"（input/GPU/battery） |
| 20 A18 桌面融合 | freeform/WM Shell/ActivityEmbedding/CDM | 本篇 input 段呼应手势抢事件 |
| 21 系列累计 | 153 专题闭环 | — |

> 至此系列 25 篇 / 约 170 专题：单点(1~14) + 复习(15~16) + 实战(17~19) + 源码(20) + 综合(21~25)。**真·未覆盖角度所剩**：真题大乱斗 vol.2（更刁钻的多子系统叠加）、KMP/skiko 非 Android target 运行时深水区（第十五篇已部分覆盖 Android 侧差异）、ART 镜像 odex 布局优化实战（第二十四篇已部分覆盖）。

---

*本篇为每日自动化产出，落盘工作区根目录（文件名带日期），并推送飞书云文档 AOSP 文件夹 + bot 私聊链接。复习建议：先背 §6 红榜 18 条建立"哪些数据源开哪些表"的肌肉记忆，再把 §4 三条混合 SQL 在真实 trace 上跑一遍；面试按"开数据源 -> 跑 SQL -> 读三段延迟/计数器 -> 定责"四步口述，比背八股得分高得多。*
