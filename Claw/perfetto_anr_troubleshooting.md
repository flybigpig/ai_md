# 性能 / 排障（Perfetto / ANR）深读笔记（AOSP 14）

## 1. Perfetto（首选 trace 工具）
设备上自带 `perfetto` 二进制，比老 systrace 强。
```bash
# 录 10 秒,挑选 datasource
adb shell perfetto -o /data/misc/perfetto-traces/trace.pftrace -t 10s \
  sched freq idle am wm gfx view binder
adb pull /data/misc/perfetto-traces/trace.pftrace
# 用 https://ui.perfetto.dev 打开
```
常用 datasource：`sched`(CPU 调度)、`freq`/`idle`(功耗)、`am`(ActivityManager)、`wm`(窗口)、`gfx`(图形)、`view`(View 系统)、`binder`(Binder 事务)、`memory`。
也可用 config 文件：`perfetto -c config.pbtx -o out.pftrace`。
旧 `systrace`(`frameworks/native/cmds/atrace/`)已 deprecated，底层就是 perfetto。

## 2. ANR 触发与产物
超时阈值：输入派发 5s、Broadcast 前台 10s/后台 60s、Service 20s、ContentProvider 10s。
检测到后在 `ActivityManagerService`/`ANRHelper` 写 `/data/anr/anr_<pid>_<时间戳>`，同时入 DropBox（`/data/system/dropbox`），`am` 会报告。
```bash
adb shell ls /data/anr/
adb pull /data/anr/anr_xxxx
adb bugreport                      # 打包 anr + logcat + dumpsys
adb shell kill -3 <pid>            # 触发 Java 线程栈 dump 到 logcat(SIGQUIT)
```

## 3. 分析套路
1. 打开 trace/anr 文件，找**主线程**（如 `main` of `system_server` 或 app）
2. 看是否 `waiting to lock <0x..> held by thread X`（锁等待）→ 追 thread X
3. thread X 卡在 Binder 调用？IO？计算？→ 定位具体函数
4. 系统服务 ANR 重点看 `system_server` 主线程是否被某 binder 同步调用阻塞

## 4. 内存
```bash
adb shell dumpsys meminfo <proc>          # app
adb shell dumpsys meminfo system_server   # 系统服务
# 泄漏看: Views / Activities 计数、Binder proxy 数
```

## 5. 实战小项目
1. 故意在主线程 `Thread.sleep(8000)` 触发 ANR，用 `kill -3` + perfetto 练定位。
2. 抓一次开机 trace：`perfetto -t 20s sched freq am wm boot`，找启动慢的服务。
