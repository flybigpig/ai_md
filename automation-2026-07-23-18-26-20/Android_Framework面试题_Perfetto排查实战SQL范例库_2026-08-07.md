# Android Framework 面试题 · Perfetto 排查实战 SQL 范例库（2026-08-07）

> 系列第 **21** 篇。前 20 篇（约 143 专题）已闭环主线 + 盲区 + 深水区 + 智能层 + 安全世界（EL3 Trusty）+ 机密计算（EL2 pKVM/AVF）+ 座舱（AAOS）+ 端侧 AI + 速查卡 + 连击考 + 全链路排查 + 源码级 code walk。
>
> 本篇落点：**Perfetto 已成为 Android 性能/问题排查的事实标准**（tracing + SQL 可查询），但前 19 篇只在"全链路排查实战"里把它当作结论工具，从未给出可复用的 `trace_processor` SQL 范例库。本篇一次性补齐"现象 -> Perfetto 抓 trace -> SQL 定界 -> AOSP 源码落点 -> 根因"的闭环，并以**面试问答**形态呈现，每题配底层原理 + AOSP 源码路径佐证 + 易错点。

---

## 0. 当日热点锚定（2026-08-07）

- **A17 QPR2 Beta 2** 已于 8/3 推送（build `CP41.260701.006`，安全补丁 2026-07-05，无 changelog，内部代号由 `CinnamonBun` 切到 `DEV`，重绘设置图标；QPR2 stable 预计 2026-12 随 Pixel Feature Drop 落地）。这意味着：A17 平台行为已稳定，**Framework 排查方法论不变，但行为基线请以 A17 QPR 为准**。
- **经典八股仍高频**：Handler/Looper、Binder 一次拷贝、AMS/ATMS 调度、WMS/View 三阶段、冷启动、内存/卡顿/ANR、Compose 重组、HAL/Treble、GKI 内核仍是面试主菜；但**能否用 Perfetto 现场证明**已成为分水岭——"背结论" vs "给 trace 证据" 是高级岗的硬门槛。
- **本篇定位**：不重复讲原理（前 20 篇已讲），只讲"如何用 Perfetto + SQL + AOSP 源码路径把原理坐实"。所有 SQL 均为 `trace_processor` 语法（在 `ui.perfetto.dev` 的 Query 面板或 `perfetto trace_processor` CLI 执行）。

---

## 一、当日热点面试题速递（10 题 · 轻量索引）

对应任务要求"搜集/归纳近期热点面试题"。以下为今日高频题，深度解法见后文对应章节。

| # | 热点面试题 | 本篇落点 | 关联前篇 |
|---|-----------|---------|---------|
| 1 | 怎么证明一次冷启动慢在 `bindApplication` 还是 `onCreate`？ | 三、§SQL-3/4 | 19 篇 §1、20 篇 §2 |
| 2 | 掉帧了，怎么定责到 App / RenderThread / SF / HWC？ | 四、§SQL-5/6 | 19 篇 §2、20 篇 §5 |
| 3 | 主线程卡顿，是 GC、锁竞争还是 Binder 阻塞？ | 五、§SQL-7/8 | 19 篇 §3/6 |
| 4 | 内存只涨不跌，是 Java 泄漏还是 native 泄漏？ | 六、§SQL-9/10 | 19 篇 §4 |
| 5 | Binder 调用耗时高，是 kernel 拷贝还是对端执行慢？ | 七、§SQL-11/12 | 19 篇 §6、20 篇 §6 |
| 6 | 发热掉速，是 Thermal 降频还是唤醒风暴？ | 八、§SQL-13/14 | 19 篇 §5 |
| 7 | ANR 了，怎么从 trace 反推主线程卡在哪个锁？ | 五 + 十九篇 §3 | 19 篇 §3 |
| 8 | systrace 和 Perfetto 本质区别？为什么后者是标准？ | 二、§原理 | 全系列 |
| 9 | `monitor contention` 是什么？Perfetto 怎么量化它对启动的影响？ | 五、§SQL-8 | 13 篇 Compose |
| 10 | 怎么抓"偶发"卡顿（非必现）的 trace？ | 二、§Ring Buffer | 19 篇 §2 |

---

## 二、Perfetto 基础与抓取（面试高频）

### Q2.1 systrace 和 Perfetto 本质区别？为什么 Perfetto 是事实标准？

**答：**

- **数据格式**：systrace 输出 HTML（已废弃、不再维护）；Perfetto 输出 `.perfetto-trace`（二进制 protobuf），支持 **SQL 查询**、长时间录制、多数据源（内核 ftrace + atrace + 进程内 track + heap + logcat）。
- **查询能力**：Perfetto 的 `trace_processor` 把 trace 解析成关系表（`slice` / `thread_state` / `sched` / `process` …），可用 SQL 聚合，这是 systrace 完全做不到的。
- **数据源架构**：Perfetto 由 `traced`（设备端 daemon，收集数据）、`traced_probes`（ftrace/atrace probe）、`perfetto`（命令行，下发 config）组成；配置用 protobuf（`-c config.pbtx --txt`）。

**AOSP 源码落点：**
- 命令行/守护进程：`platform/perfetto/src/tracing/`、`platform/perfetto/src/perfetto_cmd/`
- 配置解析：`platform/perfetto/src/config/`
- UI：`ui.perfetto.dev`（WebAssembly build）

### Q2.2 怎么抓一份"够用"的 trace？命令行和 ring buffer 怎么用？

**答：**

轻量录制（类似旧 systrace，固定 10s）：

```bash
adb shell perfetto \
  -o /data/misc/perfetto-traces/trace.perfetto-trace \
  -t 10s -b 256mb \
  sched freq idle am wm gfx view binder_driver media audio memory
adb pull /data/misc/perfetto-traces/trace.perfetto-trace ./
```

**Ring Buffer（抓偶发卡顿的关键）**：去掉 `-t 10s`，并设置 `fill_policy: RING_BUFFER`，Perfetto 循环覆盖旧数据，你复现问题后手动 Ctrl+C 停止，保留最近 N 秒。长时间/车机全量建议 `buffer.size_kb: 524288`（512MB）。

**精细化 protobuf 配置（排查掉帧必开 `frametimeline`）**：

```protobuf
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_blocked_reason"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "wm"
      atrace_categories: "am"
      atrace_categories: "binder_driver"
      atrace_categories: "dalvik"
      atrace_categories: "monitor_contention"
    }
  }
}
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "android.heapprofd" } }   # native 内存采样
```

**易错点：**
- `-t` 和 `RING_BUFFER` 互斥——要 ring buffer 就不要给 `-t`，否则变固定时长。
- `sched_blocked_reason` 必须开，否则 `thread_state.blocked_function` 为空，无法定位"不可中断睡眠"的根因。
- 抓 native 内存必须开 `android.heapprofd` 且目标进程需 `wrap.sh` 或 `enable_heapprofd`；否则 `heap_profile` 表为空。

---

## 三、慢启动 / 冷启动定位 SQL

### Q3.1 怎么用 Perfetto 证明"冷启动慢"卡在哪个阶段？

**原理**：冷启动从 `ActivityThread.handleBindApplication` 开始，到首帧 `Choreographer.doFrame` 结束。Perfetto 用 `android.startup` module 暴露 `android_startup` 表（含 `startup_type` = cold/warm/hot、`ts`、`dur`）。更细粒度可查 `slice` 表里的 `bindApplication` / `ActivityThreadMain` / `ActivityStart` 等 name。

**AOSP 源码落点：**
- `frameworks/base/core/java/android/app/ActivityThread.java` -> `handleBindApplication()`、`attach()`
- `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` -> `startProcessLocked()`
- `frameworks/base/core/java/android/app/ActivityThread.java` -> `performLaunchActivity()` / `handleResumeActivity()`
- 启动计时埋点：`frameworks/base/core/java/android/app/ActivityThread.java` 的 `mH` 消息、`reportFullyDrawn()`

**SQL-3（查启动总耗时与类型）：**

```sql
INCLUDE PERFETTO MODULE android.startup;
SELECT
  package,
  startup_type,
  ROUND(dur / 1e6, 1) AS total_ms
FROM android_startup
ORDER BY dur DESC
LIMIT 10;
```

**SQL-4（拆解 bindApplication 各子阶段耗时，定位 ContentProvider 前置坑）：**

```sql
SELECT
  s.name,
  ROUND(s.dur / 1e6, 1) AS ms,
  p.name AS process
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE s.name IN ('bindApplication', 'ActivityThreadMain',
                 'installContentProviders', 'ActivityStart',
                 'Choreographer#doFrame')
  AND p.name LIKE '%YOUR_APP%'
ORDER BY s.ts;
```

**易错点 / 高频追问：**
- **ContentProvider 前置坑**：`installContentProviders` 在 `Application.onCreate` 之前同步执行，多 CP 会显著拖慢冷启动（前 19 篇 §1、20 篇 §2 讲过）。SQL-4 里若 `installContentProviders` 的 `dur` 远大于 `onCreate`，就是典型证据。
- **追问**："怎么优化？" -> 合并/懒加载 ContentProvider、`exported=false` 收敛、用 `android:initOrder` 谨慎、`AppStartup` 库替代 manifest 声明的 initializer。
- **基线对比**：A14 起有 Cloud Profile（`PinnerService` 预加载热点方法/类），对比 `verifyClass` / `dex2oat` 的 `slice` 耗时可判断是否 profile 生效。

---

## 四、卡顿掉帧定位 SQL

### Q4.1 掉帧了，怎么定责到 App / RenderThread / SF / HWC？

**原理**：Perfetto 的 Frame Timeline 有两张表：
- `expected_frame_timeline_slice`：期望帧（按 vsync 节奏，dur ≈ 16.6ms@60fps）
- `actual_frame_timeline_slice`：实际帧，带 `jank_type` 与 `present_type`

`jank_type` 取值：`App Deadline Missed`（App/UI 线程超期）、`SurfaceFlinger Deadline Missed`（SF 合成超期）、`OnTime`（达标）等。`present_type` 区分 `Sf Composition`（GPU 合成）vs `Hwc Composition`（Overlay 直显）。

**AOSP 源码落点：**
- Frame Timeline 实现：`frameworks/native/services/surfaceflinger/FrameTimeline/`（`FrameTimeline.cpp`、`TokenManager.cpp`）
- 实际帧 vs 期望帧：SF 在 `onMessageRefresh` 阶段向 Perfetto 写 `actual_frame_timeline_slice`
- App 侧：`android/view/Choreographer.java` `doFrame()` 触发 `FrameInfo` 标记；`android/view/ViewRootImpl.java` `doTraversal()`
- 合成决策：`frameworks/native/services/surfaceflinger/BufferLayer.cpp` `getCompositionType()`（HWC_OVERLAY vs HWC_CLIENT）

**SQL-5（查所有掉帧及其定责类型）：**

```sql
SELECT
  p.name AS process,
  a.frame_number,
  ROUND(a.dur / 1e6, 1) AS actual_ms,
  ROUND(e.dur / 1e6, 1) AS expected_ms,
  a.jank_type,
  a.present_type
FROM actual_frame_timeline_slice a
JOIN expected_frame_timeline_slice e
  ON a.frame_number = e.frame_number AND a.upid = e.upid
JOIN process p ON a.upid = p.upid
WHERE a.jank_type != 'None' AND a.jank_type != 'OnTime'
ORDER BY a.dur DESC
LIMIT 20;
```

**SQL-6（定责到 App UI 线程长任务：找超 16.6ms 的 doFrame 子 slice）：**

```sql
SELECT
  s.name,
  ROUND(s.dur / 1e6, 1) AS ms,
  t.thread_name
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE p.name LIKE '%YOUR_APP%'
  AND s.name LIKE '%doFrame%'
  AND s.dur > 16.6e6
ORDER BY s.dur DESC
LIMIT 20;
```

**易错点 / 高频追问：**
- **JankType 是"定责"不是"根因"**：`App Deadline Missed` 只说明 UI 线程那帧超期；要往下钻 `doFrame` 子 slice（measure/layout/draw/Remeasure）找具体函数。
- **追问**："RenderThread 耗时怎么看？" -> 查 `RenderThread` 线程的 `DrawFrame` / `flush` / `eglSwapBuffers` slice，或 `FrameTimeline` 之外的 `thread_slice` 表按 `thread_name = 'RenderThread'` 过滤。
- **追问（结合 A17 QPR2）**：QPR2 Beta1 修复过"多指拖拽丢触摸 #516836306"和"窗口模糊渲染 #527376569"，这类 bug 在 FrameTimeline 上表现为 `present_type` 异常或输入事件 slice 断裂——可作为"真题现场溯源"案例。

---

## 五、主线程长任务 / 锁竞争 SQL

### Q5.1 主线程被卡住，是 GC、锁竞争还是 Binder 阻塞？

**原理**：主线程状态在 `thread_state` 表（`state` 字段：`Running` / `R` -> 运行中；`Runnable` / `R` -> 就绪但没被调度；`Sleeping` -> 休眠；`Uninterruptible Sleep` / `D` -> 内核不可中断睡眠，常因 I/O 或锁；`Monitor` -> Java 锁竞争）。`blocked_function` 在开了 `sched_blocked_reason` 后可见具体内核函数。

**AOSP 源码落点：**
- 线程状态埋点：`kernel/sched/` 的 `sched_switch` tracepoint；`thread_state` 来自 ftrace `sched/sched_switch` + `sched/sched_blocked_reason`
- monitor contention：ART `runtime/` 的 `Monitor::Lock` / `Monitor::BlockingLock`，atrace `monitor_contention` category 由 ART 在锁等待时发射
- Binder 阻塞：`frameworks/native/libs/binder/IPCThreadState.cpp` `waitForResponse()`

**SQL-7（查主线程各状态耗时分布，定位"是不是没在跑"）：**

```sql
SELECT
  ts.state,
  COUNT(*) AS cnt,
  ROUND(SUM(ts.dur) / 1e6, 1) AS total_ms
FROM thread_state ts
JOIN thread t ON ts.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE p.name LIKE '%YOUR_APP%' AND t.thread_name = 'main'
GROUP BY ts.state
ORDER BY total_ms DESC;
```

**SQL-8（量化 monitor contention 对启动/主线程的影响，需 android.monitor_contention module）：**

```sql
INCLUDE PERFETTO MODULE android.monitor_contention;
SELECT
  mc.blocked_thread_name,
  mc.owner_thread_name,
  mc.method_name,
  ROUND(mc.dur / 1e6, 1) AS wait_ms,
  COUNT(*) AS cnt
FROM android_monitor_contention mc
JOIN thread t ON mc.blocked_utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE p.name LIKE '%YOUR_APP%'
GROUP BY mc.method_name
ORDER BY wait_ms DESC
LIMIT 20;
```

**易错点 / 高频追问：**
- **`Runnable` 高 ≠ 你代码慢**：说明主线程就绪但拿不到 CPU（被别的进程/线程抢，或 thermal 降频），此时要去查 `sched` 表看谁在占 CPU，而非优化自己的函数。
- **`Uninterruptible Sleep` 高**：多半是 Binder 对端慢 / 磁盘 I/O / 跨进程 fd 传输，配合 `blocked_function` 看具体内核函数（如 `futex_wait`、`binder_thread_read`）。
- **追问（ANR 反推）**：ANR 时 `/data/anr/traces.txt`（A14+ 在 `/data/anr/` + `am_anr` event log）主线程栈若停在 `monitor contention`，SQL-8 能直接给出"等哪个锁、被哪个线程持有、等了多久"，比看栈更有说服力。

---

## 六、内存 / 内存泄漏 SQL

### Q6.1 内存只涨不跌，是 Java 泄漏还是 native 泄漏？

**原理**：分两类排查：
- **Java 层**：抓 heap dump（Android Studio / `am dumpheap`），Perfetto 解析为 `heap_graph_object` / `heap_graph_class` / `heap_graph_reference`（需 `heap_dump` 数据源）。或用 `Runtime.maxMemory` 曲线（`counter` 表）。
- **Native 层**：`android.heapprofd` 采样 malloc/free，写 `heap_profile` / `heap_slice`，可按调用栈聚合"已分配未释放"的内存。

**AOSP 源码落点：**
- heapprofd：`platform/perfetto/src/profiling/memory/`（hook `malloc`/`free` via `ld.preload` 或 `heapprofd_client`）-> 远低于 `malloc_debug` 开销
- Java 堆：ART `runtime/gc/`（`heap.cc`、`collector/`），A14 CMC（Concurrent Mark-Compact）+ A17 分代 GC
- LMKD：`system/core/lmkd/` + `drivers/staging/android/lowmemorykiller.c`（旧）/ 新 `psi` 监控；A17 新增 Memory Limiter
- 进程内存：`/proc/<pid>/status` 的 VmRSS，Perfetto `process_memory` / `memory` counter

**SQL-9（native 内存：查分配最多且未释放的调用栈，定位泄漏点）：**

```sql
SELECT
  hp.name AS symbol,
  ROUND(SUM(hs.size) / 1024.0, 1) AS alive_kb,
  COUNT(*) AS allocs
FROM heap_slice hs
JOIN heap_profile hp ON hs.profile_id = hp.id
JOIN process p ON hp.upid = p.upid
WHERE p.name LIKE '%YOUR_APP%'
  AND hs.size > 0          -- 正值 = 分配；heap_slice 正负配对表示 alloc/free
GROUP BY hp.name
ORDER BY alive_kb DESC
LIMIT 20;
```

> 注：`heap_slice.size` 为正表示分配、配对负值为释放；聚合"净存活"需结合 `heap_profile` 的快照时间窗。实战中更常用 heap_dump 的 `heap_graph_object` 按类聚合 retained size。

**SQL-10（Java 堆：按类聚合占用，找泄漏嫌疑类）：**

```sql
SELECT
  hgc.name AS class_name,
  COUNT(hgo.id) AS obj_count,
  ROUND(SUM(hgo.self_size + hgo.retained_size) / 1024.0, 1) AS retained_kb
FROM heap_graph_object hgo
JOIN heap_graph_class hgc ON hgo.type_id = hgc.id
JOIN heap_graph hg ON hgo.graph_id = hg.id
JOIN process p ON hg.upid = p.upid
WHERE p.name LIKE '%YOUR_APP%'
GROUP BY hgc.name
ORDER BY retained_kb DESC
LIMIT 20;
```

**易错点 / 高频追问：**
- **三类杀手区分**（前 19 篇 §4 详述）：Java 泄漏看 `heap_graph`；native 泄漏看 `heapprofd`；graphics 内存（GL texture / gralloc / HWUI）看 `GpuMemory` / `Graphics` 分类；binder 缓冲看 `binder_transaction_alloc_buf`。四者要分开查，别混为一谈。
- **追问（三条杀路径辨析）**：A14 之后内存不足有三条路径——内核 OOM、LMKD（PSI 触发）、A17 Memory Limiter（单进程超额静默杀）。三者触发信号不同，Perfetto 里分别看 `am_low_memory` / `lmkd` 日志 / `ApplicationExitInfo` 的 `MemoryLimiter:AnonSwap`（第 13 篇讲过）。
- **追问**："heapprofd 和 malloc_debug 区别？" -> heapprofd 用采样 + 客户端 hook，开销低可长期开；malloc_debug 全量记录开销大，仅调试用。

---

## 七、Binder / IPC 阻塞 SQL

### Q7.1 Binder 调用耗时高，是 kernel 拷贝还是对端执行慢？

**原理**：Perfetto 的 `binder_transaction` 表记录每次 Binder 事务（含 `aidl_name`、`client_process` / `server_process`、`is_async`（oneway）、`txn_id`）。在 UI 里点 client 端的 binder slice 会自动画箭头连到 server 端的 reply slice，据此可拆出"内核驱动耗时 vs 对端执行耗时"。

**AOSP 源码落点：**
- 驱动：`drivers/android/binder.c` -> `binder_transaction()`（一次拷贝在 `binder_alloc_copy_user_to_buffer` / `copy_from_user`）、`binder_thread_read()`
- 用户态：`frameworks/native/libs/binder/IPCThreadState.cpp` `transact()` / `waitForResponse()`、`BpBinder::transact()`
- 线程池：`IPCThreadState` 默认 15 个 Binder 线程（`frameworks/native/libs/binder/ProcessState.cpp` `open()` 时 `mMaxThreads`，A14 起默认 15）；oneway 满也会排队（前 19 篇 §6 讲）

**SQL-11（查最长的 Binder 调用，定位对端慢服务）：**

```sql
SELECT
  bt.aidl_name,
  bt.client_process,
  bt.server_process,
  ROUND(bt.dur / 1e6, 1) AS ms,
  bt.is_async
FROM binder_transaction bt
WHERE bt.dur > 10e6          -- 超过 10ms 的事务
ORDER BY bt.dur DESC
LIMIT 20;
```

**SQL-12（查某对端服务的累计 Binder 阻塞，判断是否线程池满/对端忙）：**

```sql
SELECT
  bt.server_process,
  COUNT(*) AS txn_cnt,
  ROUND(SUM(bt.dur) / 1e6, 1) AS total_ms,
  SUM(CASE WHEN bt.is_async = 0 THEN 1 ELSE 0 END) AS sync_cnt
FROM binder_transaction bt
GROUP BY bt.server_process
ORDER BY total_ms DESC
LIMIT 20;
```

**易错点 / 高频追问：**
- **一次拷贝 ≠ 零拷贝**：Binder 只在内核态拷贝一次（发送方用户态 -> 内核 binder 缓冲 -> 接收方用户态），大对象（>1MB 或含 fd）应走 `ashmem` / `gralloc` fd 传递，别塞进 transaction（前 20 篇 §6 详述）。
- **oneway 满也排队**：很多人误以为 `oneway` 永不阻塞，其实对端线程池满时发送端 `binder_thread_read` 也会等。Perfetto 里表现为 server 端同 `txn_id` 队列堆积。
- **追问（跨 VM 不可信）**：第 12/13 篇强调——跨 pVM/Trusty 时 `getCallingUid()` 不可信，排查 Binder 安全问题时别只信 UID，要看 `binder_transaction` 的 `server_process` 与调用链。

---

## 八、电源 / 唤醒 / 后台受限 SQL

### Q8.1 发热掉速、频繁唤醒，怎么查？

**原理**：发热掉速是 Thermal HAL 降频链路（前 19 篇 §5）：`thermal` 服务读温度 -> `Power HAL` / `ADPF` 调 `cpu_frequency`；唤醒风暴看 `wakelock` / `suspend` 与 `thread_state`。CPU 频率在 `counter` 表（track 名含 `cpu_frequency`），内核态睡眠在 `thread_state.state = 'D'`。

**AOSP 源码落点：**
- Thermal：`hardware/interfaces/thermal/`（AIDL HAL）、`frameworks/base/services/core/java/com/android/server/thermal/ThermalManagerService.java`
- Power：`hardware/interfaces/power/`、ADPF `android.os.PerformanceHintManager`、`frameworks/base/core/java/android/os/PerformanceHintManager.java`
- WakeLock：`frameworks/base/services/core/java/com/android/server/power/PowerManagerService.java`（`acquireWakeLockInternal`）
- 后台受限：Doze / AppStandby（`UsageStatsManager`）、JobScheduler 配额（A16 收紧）、FGS 类型（A14）

**SQL-13（查 CPU 频率变化，定位降频区间）：**

```sql
SELECT
  ct.name AS freq_track,
  ROUND(c.value / 1000.0, 0) AS khz,
  c.ts,
  ROUND(c.dur / 1e6, 1) AS ms
FROM counter c
JOIN counter_track ct ON c.track_id = ct.id
WHERE ct.name LIKE '%cpu_frequency%'
ORDER BY c.ts
LIMIT 50;
```

**SQL-14（查唤醒锁持有，定位唤醒风暴）：**

```sql
SELECT
  s.name AS wakelock,
  p.name AS process,
  ROUND(s.dur / 1e6, 1) AS held_ms
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE s.name LIKE '%wakelock%' OR s.name LIKE '%WakeLock%'
ORDER BY s.dur DESC
LIMIT 20;
```

**易错点 / 高频追问：**
- **降频 ≠ 你代码慢**：`cpu_frequency` 掉到最低频时，任何函数都变慢，此时优化算法无效，要先解决发热源（后台密集任务 / 频繁网络 / 过度绘制）。
- **追问**："Doze 下还能被谁唤醒？" -> 高优先级 FCM、`setAlarmClock`、活动期（maintenance window）。Perfetto 里查 `alarm` / `wakelock` slice 配合 `am` 日志。
- **追问（结合 A16/A17）**：A16 JobScheduler 配额收紧、A17 后台音频加固 + 自定义通知限制，都会改变后台行为基线——排查"后台不执行"时要先确认 targetSdk 行为。

---

## 九、可复用 SQL 范例库（沉淀 snippet，直接抄）

以下为一次成型的常备查询，按场景分组，建议存为本地 `.sql` 文件随时调用（在 `ui.perfetto.dev` Query 面板粘贴执行）。

```sql
-- [A] CPU 占用最高的线程（谁在吃 CPU）
SELECT t.thread_name, p.name AS proc,
       ROUND(SUM(s.dur) / 1e6, 1) AS total_ms
FROM thread_slice s
JOIN thread t ON s.utid = t.utid
JOIN process p ON t.upid = p.upid
GROUP BY t.utid ORDER BY total_ms DESC LIMIT 10;

-- [B] 超过 16.6ms 的 slice（潜在卡顿点）
SELECT s.name, ROUND(s.dur/1e6,1) AS ms, t.thread_name, p.name AS proc
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE s.dur > 16.6e6 ORDER BY s.dur DESC LIMIT 20;

-- [C] 不可中断睡眠（D 状态）的阻塞函数汇总
SELECT ts.blocked_function, COUNT(*) AS cnt, ROUND(SUM(ts.dur)/1e6,1) AS ms
FROM thread_state ts
JOIN thread t ON ts.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE ts.state = 'D' AND p.name LIKE '%YOUR_APP%'
GROUP BY ts.blocked_function ORDER BY ms DESC;

-- [D] 所有掉帧（jank）汇总
SELECT a.jank_type, COUNT(*) AS cnt,
       ROUND(SUM(a.dur)/1e6,1) AS total_ms
FROM actual_frame_timeline_slice a
WHERE a.jank_type NOT IN ('None','OnTime')
GROUP BY a.jank_type ORDER BY cnt DESC;

-- [E] 最长 Binder 事务 Top 20
SELECT bt.aidl_name, bt.client_process, bt.server_process,
       ROUND(bt.dur/1e6,1) AS ms, bt.is_async
FROM binder_transaction bt
ORDER BY bt.dur DESC LIMIT 20;

-- [F] 启动阶段拆解（需 android.startup module）
INCLUDE PERFETTO MODULE android.startup;
SELECT package, startup_type, ROUND(dur/1e6,1) AS ms
FROM android_startup ORDER BY dur DESC LIMIT 10;
```

**易错点：**
- `thread_slice` 是 `slice` JOIN `thread_track` 的视图，写聚合时用它省去手动 JOIN。
- `actual_frame_timeline_slice.jank_type` 取值区分大小写，过滤用 `NOT IN ('None','OnTime')` 比 `!= 'OnTime'` 更稳（避免 NULL）。
- 所有 `dur` 单位都是 **ns**，转 ms 要 `/1e6`，转 s 要 `/1e9`——最容易写错的是漏除或除错数量级。

---

## 十、易错点红榜 TOP20（面试高频踩坑）

1. Perfetto 的 `dur` 是 **ns**，SQL 里 `/1e6` 才是 ms，别写成 `/1000`。
2. Ring Buffer 必须去掉 `-t` 并用 `fill_policy: RING_BUFFER`，否则不是环形。
3. `sched_blocked_reason` 不开，`thread_state.blocked_function` 永远为空。
4. `heapprofd` 不开，`heap_profile` / `heap_slice` 表为空；native 泄漏无从查。
5. `jank_type` 是"定责"不是"根因"，要下钻 `doFrame` 子 slice 才到函数级。
6. `Runnable` 高说明拿不到 CPU（被抢/降频），不是你代码慢——去看 `sched` 表。
7. `Uninterruptible Sleep (D)` 高多半是 Binder 对端慢或 I/O，看 `blocked_function`。
8. Binder `oneway` 满也会排队，发送端仍可能阻塞在 `binder_thread_read`。
9. Frame Timeline 有 `expected` 和 `actual` 两张表，定责要 JOIN `frame_number` + `upid`。
10. `bindApplication` 前的 `installContentProviders` 是冷启动常见隐形杀手。
11. Java 泄漏看 `heap_graph_object`，native 泄漏看 `heapprofd`，两者数据源完全不同。
12. 内存三类杀手（Java/native/graphics/binder）要分开查，别混。
13. 三条杀路径（内核 OOM / LMKD-PSI / A17 Memory Limiter）触发信号不同。
14. 降频时任何函数都变慢，先治发热源再谈优化。
15. Thermal -> Power HAL -> `cpu_frequency` 是降频主线，Perfetto 里看 `counter` 表。
16. `monitor contention` 是 ART 在锁等待时发射的 atrace，需开 `monitor_contention` category。
17. 跨 pVM/Trusty 的 Binder 调用 `getCallingUid()` 不可信，排查安全别只信 UID。
18. 大对象别塞 Binder transaction，走 fd（ashmem/gralloc）传递。
19. `actual_frame_timeline_slice.present_type` 区分 HWC Overlay vs GPU 合成，是合成决策证据。
20. SQL 过滤 `jank_type` 用 `NOT IN ('None','OnTime')`，避免 NULL 被漏掉。

---

## 十一、高频追问链（考官连击）

**追问链 A · 卡顿定责（打通"现象->证据->源码"）：**
1. 掉帧了你怎么证明不是 SF 的锅？ -> FrameTimeline `jank_type` = `App Deadline Missed`
2. 怎么定位到具体函数？ -> 下钻 `doFrame` 子 slice（measure/layout/draw）
3. 如果是 RenderThread 慢呢？ -> 查 `RenderThread` 线程 `DrawFrame`/`flush`/`eglSwapBuffers`
4. 为什么 HWC 不合成？ -> `BufferLayer::getCompositionType()` 返回 `HWC_CLIENT`，看 layer 是否超 OEM 限制

**追问链 B · 冷启动优化（结合源码）：**
1. 怎么证明慢在 bindApplication？ -> `android_startup` + `slice` 表拆解
2. installContentProviders 耗时高怎么解？ -> 合并/懒加载 CP、`AppStartup` 替 initializer
3. 基线 Profile 生效了吗？ -> 查 `verifyClass`/`dex2oat` slice，`PinnerService` 预加载
4. 云 Profile 和本地 Profile 区别？ -> `BackgroundDexOptService` + `AbstractJobScheduler` 下发（前 19/20 篇）

**追问链 C · 内存三路杀（辨析触发源）：**
1. 进程被杀，是 OOM 还是 LMKD？ -> `am_low_memory` vs `lmkd` 日志 vs `ApplicationExitInfo`
2. A17 Memory Limiter 和 LMKD 什么关系？ -> 个体超标静默杀 + 全局 PSI 杀，两条独立路径
3. native 泄漏怎么定位？ -> `heapprofd` 按调用栈聚合未释放内存
4. 为什么 heapprofd 开销低？ -> 采样 + 客户端 hook，对比 `malloc_debug` 全量

---

## 十二、与前面 20 篇交叉索引

| 本篇主题 | 对应前篇 | 衔接点 |
|---------|---------|-------|
| 冷启动拆解 | 19 篇 §1、20 篇 §2 | `bindApplication` / ContentProvider 前置坑 |
| 掉帧定责 | 19 篇 §2、20 篇 §5 | FrameTimeline / Choreographer / SF 合成 |
| 主线程阻塞 / ANR | 19 篇 §3、20 篇 §4 | `thread_state` / monitor contention |
| 内存三路杀 | 19 篇 §4、13 篇 | LMKD/PSI vs A17 Memory Limiter vs OOM |
| Binder 阻塞 | 19 篇 §6、20 篇 §6 | `binder_transaction` / 一次拷贝 / oneway 排队 |
| 发热掉速 | 19 篇 §5 | Thermal HAL -> Power HAL/ADPF -> 降频 |
| 跨 VM UID 不可信 | 12 篇、13 篇 | pVM/Trusty Binder 安全 |
| 启动/Profile | 19 篇 §1、20 篇 §2 | Cloud Profile / PinnerService |
| A17 行为基线 | 7/8/9/10/11 篇 | QPR2 Beta2 / Memory Limiter / 后台加固 |
| 智能层排查 | 13 篇 | Compose 重组/语义树对 Agent 的影响也可用 Perfetto 看主线程卡顿 |

---

## 十三、今日复习建议

1. **动手 > 背诵**：在真机/模拟器上抓一份 10s 含 `gfx/view/wm/am/binder_driver/dalvik/monitor_contention` 的 trace，把"九、可复用 SQL 范例库"逐条跑一遍，替换 `YOUR_APP` 为你的包名。
2. **建立"证据->源码"反射**：每个 Perfetto 表都对应一段 AOSP 代码（本文已逐条给出路径），面试时能把"trace 现象"映射到"源码方法名"是高级岗标志。
3. **关注 A17 QPR2 基线**：QPR2 Beta2（build CP41.260701.006）已推送，行为以 A17 为准；QPR2 stable 预计 2026-12。
4. **本篇价值**：把前 20 篇的"原理结论"变成"可复用排查工具箱"，是面试现场题（"你怎么做性能优化"）的最强弹药。

---

> 系列状态：第 21 篇 / 约 148 专题（前 20 篇约 143 + 本篇 Perfetto 专项约 5 大场景）。主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧 AI + 速查卡 + 连击考 + 全链路排查 + 源码 code walk + Perfetto SQL 范例库 完整闭环。
>
> 后续可轮换新角度（memory 规划剩余）：①真题大乱斗混合场景卷（多子系统叠加压轴综合题）；②A18 桌面融合 / 跨设备 handoff 前瞻深挖（EU DMA 强制开放 11 项 AI 能力对 Framework 的影响，CDM 锁屏屏幕自动化权限重写已落地 QPR2）；③Perfetto SQL 范例库可继续扩充（如 input 延迟、GPU 计数器、battery 耗电细分）。
