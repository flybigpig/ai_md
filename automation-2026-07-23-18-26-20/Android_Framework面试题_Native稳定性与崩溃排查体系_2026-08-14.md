# Android Framework 面试题 · Native 稳定性与崩溃排查体系（2026-08-14）

> 当日热点锚定：A17 QPR2 Beta 2（2026-08-03，build CP41.260701.006，stable 预计 2026-12，代号 DEV）当前主流；**2026 年 8 月安全补丁**明确修复「游戏场景 app crashes / GPU 性能 / 触控响应」，native 崩溃与系统稳定性成为本月最实操的热点。A17 QPR2 Beta1 还修过「Gemini 触发系统崩溃 / 蓝牙重配对失败 / 锁屏媒体控件」等稳定性问题。
> 本篇定位：前 28 篇（约 183 专题）闭环了 Binder/AMS/WMS/SF/ART/HAL/内核/TEE/pKVM/智能层/座舱/端侧AI/Perfetto/基础八股/两版真题大乱斗，**但「native 崩溃 → debuggerd → tombstone → linker 隔离 → 16KB 页对齐 → SIGSEGV 根因」这条纯 C/C++ 运行时链路从未独立成篇**（仅第 13 篇讲过 Java 侧的 ApplicationExitInfo 死因）。本篇一次补齐，正好落在用户要求的「linux kernel / drivers / 内存优化」赛道。
> Baseline：Android 14（UpsideDownCake, AOSP android-14.0.0_rXX）。所有路径以 AOSP 为准。

---

## 专题一：Native 崩溃全景 —— 从 SIGSEGV 到 tombstone 的完整链路

**面试题**：App native 崩溃后，系统到底做了什么？`/data/tombstones/tombstone_xx` 是谁、怎么写出来的？

### 链路拆解（AOSP 真实组件）

```
App 进程触发致命信号(SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGTRAP/SIGFPE)
  -> libc 内建的 debuggerd signal handler (debuggerd_handler.cc 注册的 sigaction)
  -> fork/exec /system/bin/crash_dump64 <tid> <pipe>
  -> crash_dump 通过 /dev/socket/tombstoned_interceptor 连接 tombstoned 守护进程
  -> tombstoned (system/core/debuggerd/tombstoned) 分配一个空闲 slot，
     把 tombstone 写到 /data/tombstones/tombstone_XX (权限 0640, system:log)
  -> crash_dump 用 libunwindstack 回溯栈、读寄存器/内存邻近区/打开的 maps
  -> 序列化进 Tombstone proto (system/core/debuggerd/proto/tombstone.proto)
  -> 原始文本 tombstone 落盘；同时 crash_dump 退出码让 app 进程真正死掉
  -> logcat 打印 "Fatal signal N (SIGxxx)" + "Tombstone written to ..."
```

### 关键源码落点

- `system/core/debuggerd/handler/debuggerd_handler.cc` —— `SetSignalHandlers()` 为 SIGABRT/SIGBUS/SIGFPE/SIGILL/SIGSEGV/SIGSTKFLT/SIGTRAP 注册处理函数；`handle_signal()` 里通过 `fork()` 出 `crash_dump`（注意：handler 在**出错线程**上下文执行，但真正干活的是 crash_dump 子进程，避免自己栈已损坏）。
- `system/core/debuggerd/crash_dump/crash_dump.cc` —— `main()` 解析参数（目标 pid/tid、是否 interactive），连接 tombstoned、调用 `engrave_tombstone()`。
- `system/core/debuggerd/tombstone.cpp` —— `engrave_tombstone()` 是生成主体，依次写：signal / abi / registers / backtrace / stack / memory near registers / memory map / neighboring frames / logcat 缓冲。
- `system/core/debuggerd/tombstoned/tombstoned.cpp` —— 守护进程，监听 `tombstoned_interceptor` 与 `tombstoned_receiver` 两个 socket，管理 `/data/tombstones/` 下最多 10 个 tombstone（环形覆盖最旧的）。
- proto 定义：`system/core/debuggerd/proto/tombstone.proto`（`Tombstone` message，含 `signal`, `registers`, `frames`, `memory_mappings` 等）。

### 易错点
- **debuggerd handler 不救活进程**：它只负责"体面地留下遗书"，写完 tombstone 后目标进程必然死亡（缺页/段错误无法恢复）。
- `debuggerd -p <pid>` / `debuggerd -b <pid>` 是**主动抓取**（`-b` 只 dump 不杀），走的是同样的 tombstoned 通道，常用于 ANR/cpu 高时手动抓 native 栈。
- 有些信号（如 SIGSEGV 由 JIT/ART 故意触发的" guard page fault "）会被 ART 自己先拦截处理，**不会**生成 tombstone —— 这正是 ART 用 segfault 实现某些写屏障/GC 技巧的手段（见专题六）。

---

## 专题二：tombstone 文件解剖 —— 怎么读、哪里定界

**面试题**：给你一个 tombstone，第一步看什么？`backtrace` 里的 `??` 和 `#00 pc 00000000004a1c2e` 怎么还原成符号？

### 文件结构（按顺序）
1. `Build fingerprint` / `ABI` —— 确认是 arm64 还是 arm32，符号必须匹配对应 ABI 与 build。
2. `Timestamp` / `Process` / `Thread` —— 崩溃进程 pid/tid，区分主线程 vs Binder/RT 线程。
3. `Signal` —— `SIGSEGV (signal 11), code 1 (SEGV_MAPERR), fault addr 0x0`：
   - `SEGV_MAPERR` = 访问了**未映射**地址（典型空指针解引用 / 野指针）。
   - `SEGV_ACCERR` = 地址映射了但**权限不对**（写只读页 / 执行不可执行页，典型 DEP 违反、或 16KB 对齐踩到不可执行段）。
4. `Registers` —— `x0..x30, pc, sp, lr`；arm64 下 pc 指向崩溃指令，lr 是返回地址。
5. `Backtrace` —— 每帧 `pc` + 相对 so 的偏移；`#00` 是崩溃点，`#01` 是调用者。看不到符号名（`??`）是因为 so 被 strip 了。
6. `Stack` —— sp 附近的原始内存 dump，配合回溯人工核对。
7. `Memory near registers` —— 每个寄存器指向地址 ±128B 的内存，常用于看"坏对象"的内容。
8. `Memory map` —— `/proc/<pid>/maps` 快照，**定界 so 版本/加载基址**的关键。

### 符号还原三件套
- `llvm-symbolizer` / `ndk-stack`：`ndk-stack -sym <带符号的 obj/local/arm64-v8a> -dump tombstone.txt` 自动把 `pc offset` 翻译成 `函数名 + 行号`（前提：保留 `unstripped` 的 `.so`，CI 一般归档 `symbols/` 目录）。
- `addr2line`：`aarch64-linux-android-addr2line -e libxxx.so.debug 0x0004a1c2e`（注意用**带符号**文件、`-fC` 看函数与行）。
- ART 方法帧：`libunwindstack/DexFiles.cpp` 能解析 `libart.so` 里的 oat 方法，把 `art::...` 帧符号化；tombstone 里看到的 `art::interpreter::...` 帧即源于此。

### 易错点
- **strip 后的 .so 只能给地址，不能给符号**：发布包必须额外留存 `symbols/<abi>/libxxx.so`（未 strip），否则线上 tombstone 全是 `??`。
- `pc` 偏移是相对 **so 加载基址**，不是绝对地址；同一 so 每次加载基址因 ASLR 不同，但**偏移固定**，所以符号化用偏移而非绝对地址。

---

## 专题三：linker 与 linker namespace 隔离

**面试题**：为什么 app 的 `dlopen("libc.so")` 能成功但三方 SDK 的 `dlopen("libvndk.so")` 可能失败？vendor / system / default namespace 是什么关系？A17 对 `.so` 加载有什么新加固？

### 动态链接器与命名空间
- 链接器本体：`/system/bin/linker64`（arm64）/`linker`（arm32），源码在 `bionic/linker/`（`linker_main.cpp` 的 `__linker_init` 是进程启动第一条指令；`linker.cpp` 的 `soinfo::prelink_image` / `link_image` 负责重定位）。
- **namespace 隔离**：Android 8（Treble）起引入 linker namespace，把 `/system/lib`、`/vendor/lib`、`/product/lib` 的库可见性切分，防止 vendor 旧 lib 意外被 system 进程加载造成 ABI 混乱。源码 `bionic/linker/linker_namespace.cpp`（`android_namespace_t`、链接许可 `allowed_libs`）。
- 配置由 `system/linkerconfig/` 在启动时生成 `/linkerconfig/ld.config.txt`（以及各 APEX 的 per-namespace 配置）。关键 namespace：`default`（app 与 system 进程）、`system`、`vendor`、`product`、`apex`。
- **跨 namespace 共享白名单**：`/etc/public.libraries.txt`（及 `/*/etc/public.libraries-*.txt`）列出允许被其他 namespace `dlopen` 的系统库；不在表里、又跨 namespace 的 `dlopen` 会被拒（报 `dlopen failed: library "libxxx.so" ... is not accessible`）。

### A17 安全原生 DCL 加固（新雷区）
- A17 起，**运行时 `dlopen` 的 `.so` 必须来自只读、经签名校验的分区**（system/vendor/apex），SELinux 对可写分区上的可执行映射会 `execmod` 拒绝；这是与「16KB 页」「hiddenapi」并列的"三连击"之一（呼应第 9 篇）。
- 对面试的意义：把 so 下载到 `data/` 再 `dlopen`（热修复/插件化经典做法）在 A17  targets 上会被拦；要么走 `android:extractNativeLibs` + 系统分区，要么用 WebView/ART 之外的受控机制。

### 易错点
- namespace 不是 chroot，是**链接器层面的可见性表**；`/proc/<pid>/maps` 能看到所有已映射的 so，但不代表能从任意 namespace `dlopen` 它。
- `LD_LIBRARY_PATH` 在 Android 上**基本被忽略**（出于安全），不要指望用它绕 namespace。

---

## 专题四：16KB 页面大小与 ELF 加载对齐

**面试题**：Android 15/16 推的 16KB 页到底是什么？为什么老 so 在新内核上起不来？怎么修？

### 背景
- 传统内核 `PAGE_SIZE=4KB`。Android 15 开始支持 **16KB 页内核**，Android 16 起新设备**必须**支持 16KB 页（性能/安全/I/O 收益）。难点：二进制必须同时兼容 4KB 与 16KB 内核。
- 解决思路：编译期用 `-Wl,-z,max-page-size=16384` 把所有 `LOAD` 段对齐到 **16KB**（16KB 对齐天然也是 4KB 对齐的超集），这样同一个 so 在两种内核上都能 mmap。
- 链接器校验：`bionic/linker/linker.cpp` 在 `prelink_image()` 中检查程序头 `p_align` 能被当前内核 `PAGE_SIZE` 整除、且各段不重叠；若 so 是按 4KB（`max-page-size=4096`）编的，在 16KB 内核上 linker 会因对齐不满足而拒绝加载，报 `page size of ... does not match`。
- ART 镜像同样受影响：`boot.oat` / `boot.art` 的布局需页大小感知（`art/runtime/image.cc` 头部记录 `image_begin` 等按页对齐校验），`dex2oat` 产出的镜像在 16KB 内核上需重编（呼应第 24 篇 dex2oat 重编坑）。

### 排查与修复
- 确认内核页大小：`getconf PAGE_SIZE` 或 `Pagesize` 字段。
- 确认 so 对齐：`readelf -l libxxx.so` 看 `LOAD` 段的 `Align` 是否为 `0x4000`（16384）。
- 修复：在 `Android.bp` 的 `cc_library` 加 `product_variables { page_size: { 16k: { ldflags: ["-Wl,-z,max-page-size=16384"] } } }`（Soong 已内置支持），或全局开启 `__ANDROID_MAX_PAGE_SIZE=16384`。
- 注意：**并非只有 so**：任何自己做 `mmap` 且假设 4KB 的代码（某些音视频/图形厂商库、手写内存池）都会在 16KB 内核上崩，需逐一排查对齐假设。

### 易错点
- "App 在 Pixel 15/16 新内核启动即 SIGSEGV / linker 报错" 十有八九是 16KB 对齐缺失，而不是代码逻辑 bug——这是 2026 年最典型的"编译期没改、运行时突然崩"案例。
- arm32 与 arm64 都要处理；很多老 prebuilt 只有 4KB 对齐的 `.so`。

---

## 专题五：SIGSEGV 根因分类与内存破坏检测工具

**面试题**：native 崩溃根因一般分几类？怎么在上线前抓到 UAF（use-after-free）这类"偶发、难复现"的崩溃？

### 根因分类
1. **空指针/野指针解引用** —— `SEGV_MAPERR`, fault addr 接近 0 或明显随机；最常见（忘了判空、对象已销毁）。
2. **Use-After-Free (UAF)** —— 对象 free 后还被访问；崩溃点随机、难以稳定复现；最危险的一类。
3. **堆溢出（heap buffer overflow）** —— 写穿了分配块，破坏相邻对象或 allocator 元数据，常表现为"在毫不相干的代码处崩"。
4. **栈溢出（stack overflow）** —— 无限递归 / 巨大栈上数组；SIGSEGV 在 sp 接近栈边界时。
5. **ABI / 调用约定不匹配** —— C++ name mangling / struct 布局 / `std::` 版本不一致（如 NDK 与 host 混用 libc++）；表现为参数错乱后崩。
6. **JNI 误用** —— 跨 JNI 边界持有的 `jobject` 未 `NewGlobalRef` 被 GC 回收后当指针用（`Deleted_Reference`）、`GetStringUTFChars` 不 `Release`、在错误线程 Attach。
7. **写只读页 / 执行不可执行页** —— `SEGV_ACCERR`，常与 16KB 对齐、W^X 策略、或 A17 `.so` 只读加固相关。

### 检测工具矩阵（面试必背）
- **ASan (AddressSanitizer)**：编译期插桩（`SANITIZE=address`），能精确报 UAF/溢出位置；代价是 2-3x 内存与明显变慢，多用于 debug 构建与 CTS。
- **HWAddressSanitizer (HWASan)**：基于 arm64 顶层字节忽略（Top Byte Ignore, TBI）+ 内存标签，开销远低于 ASan，可在接近真实性能下抓 UAF；`SANITIZE=hwaddress`，是 Android 主力 native 模糊测试手段。
- **GWP-ASan**：**采样型** UAF/溢出检测，几乎零开销，随机让极少数分配走"防护分配"，命中即报详细栈；系统进程默认开启，app 可通过 `android:allowNativeHeapPointerTagging` / `wrap.sh` 启用（无需重编）。
- **MTE (Memory Tagging Extension)**：arm64 v8.5 硬件特性，给每 16B 打 4-bit 标签，硬件在访存时校验，可同步（sync，精确但慢）或异步（async，性能友好）模式；Android 13+ 在部分设备提供 `heap_tagging` level 配置，是 UAF 检测的"终极硬件方案"。

### 易错点
- **Release 包崩、Debug 包不崩** 几乎总是 UAF/未初始化/对齐问题（Debug 的内存布局/填充不同掩盖了 bug）——不要因为 Debug 不复现就搁置。
- GWP-ASan 是采样，**没抓到不等于没有**；要稳定复现还得上 HWASan/ASan。

---

## 专题六：Java↔Native 崩溃边界与 ART 信号处理

**面试题**：native 崩溃会抛 Java 异常吗？`kill -3` 为什么能 dump 出 Java 栈？ART 怎么做到既自己用信号又不影响 debuggerd？

### 边界事实
- **native 崩溃不会变成 Java 异常**：信号在 C 层被 debuggerd 接管，进程直接死，Java 层 `try/catch` 永远抓不到（这是与 `NullPointerException` 类 Java 崩溃最本质的区别）。
- **`kill -3` (SIGQUIT) 不杀进程**：它被 ART 的 `SignalCatcher` 专门处理，用来 dump 当前所有线程的 Java + native 栈（ANR 时 system_server 也会发 SIGQUIT 给目标进程抓栈）。源码 `art/runtime/signal_catcher.cc` 的 `HandleSigQuit()` → `DumpJavaStack` / `DumpNativeStack`（`DumpNativeStack` 在 `runtime/thread.cc`，底层同样用 libunwindstack）。
- **ART 的 sigchain 机制**：`art/runtime/sigchain.cc` 实现了一套"信号链"——ART 需要自己处理某些信号（如 GC 的 `SIGSEGV` 做 card table / GC 写屏障技巧、空指针读保护），又不能霸占掉 debuggerd 的致命信号处理。sigchain 让多个 handler 链式共存：ART 先判断"这信号是不是我关心的（如故意触发的 fault）"，不是则转交 chain 上的下一个（最终到 debuggerd）。这就是为何 ART 能"故意 segfault"而进程不崩。

### crash_dump 进程视角
- `crash_dump` 是**独立进程**（fork 出来），以 `debuggerd` 权限运行，能 `ptrace` 目标进程读寄存器/内存/栈。目标进程此时被挂起，dump 完才真正死亡——所以 tombstone 里的栈是"崩溃瞬间"的。
- 进程死亡后，`ActivityManager` 收到 `app_process` 退出，结合 `ApplicationExitInfo`（第 13 篇）记录 `REASON_CRASH_NATIVE`，并在 logcat 标记 `FATAL EXCEPTION`/`Native crash`。

### 易错点
- 不要以为"catch 了就能救 native 崩溃"——`signal(SIGSEGV, handler)` 想吞掉段错误是危险且不可移植的，ART/Android 也不鼓励 app 这么做（会被 sigchain 干扰）。
- 多线程崩：tombstone 标的是**触发信号的那个 tid**，但根因可能在另一线程（竞态/UAF），看 backtrace 要结合 `memory map` 与日志时间线。

---

## 专题七：实战排查工具链（线上/线下）

| 场景 | 工具 | 要点 |
|---|---|---|
| 线上 native 崩溃聚合 | Firebase Crashlytics / 自研 native 上报 | 必须上传 **unstripped symbols**（按 versionCode + ABI 归档），否则只有偏移 |
| tombstone 符号化 | `ndk-stack` / `llvm-symbolizer` / `addr2line` | 用带符号 .so，按偏移还原函数+行号 |
| 稳定复现 UAF | **HWASan** 构建（`SANITIZE=hwaddress`） | 接近真实性能，CTS/fuzz 主力 |
| 偶发采样检测 | **GWP-ASan**（无需重编，开采样） | 命中即给栈；没命中不代表无 bug |
| 硬件级 UAF | **MTE**（arm64 v8.5，`heap_tagging` sync/async） | 终极方案，部分 Pixel 支持 |
| CPU/火焰图 | **simpleperf**（`app_profiler.py`） | 抓 native CPU 热点 + 调用图，配合 perf.data |
| native 栈 trace | **Perfetto**（`data-source { name: "linux.perf"` / `android.game` / `track_event`） | 第 21/25 篇的 perfetto 也能看 native 栈与帧 |
| 手动抓运行中栈 | `debuggerd -b <pid>` | 不杀进程，常用于卡死/高 CPU |
| 内存破坏模糊测试 | **honggfuzz / libFuzzer + HWASan** | 持续喂畸形输入抓崩溃 |

### 易错点
- **符号文件版本要对齐**：用错了 build 的 symbols，还原出的行号是错的，会误导修复方向。
- `simpleperf` 抓 release 需 `wrap.sh` 或 `libsimpleperf.so` 注入，且要 `android:debuggable` 或 root；否则采不到。

---

## 易错红榜 TOP18（Native 稳定性专项）

1. debuggerd 写完 tombstone 后进程**必死**，它不是恢复机制。
2. `SIGQUIT(kill -3)` 不杀进程，只 dump 栈；SIGSEGV/SIGABRT 才致命。
3. tombstone 符号化用**偏移**而非绝对地址（ASLR 改变基址）。
4. 发布包必须留存 **unstripped .so**，否则线上全是 `??`。
5. `SEGV_MAPERR`=未映射（空/野指针）；`SEGV_ACCERR`=权限错（写只读/W^X/16KB 对齐）。
6. 16KB 页：老 so 未用 `max-page-size=16384` 会在新内核 linker 拒绝加载。
7. UAF 在 Release 崩、Debug 不崩是常态（内存布局差异掩盖 bug）。
8. GWP-ASan 是**采样**，没抓到≠没有；要稳复现上 HWASan/ASan。
9. linker namespace 是链接器可见性表，不是 chroot；`LD_LIBRARY_PATH` 基本被忽略。
10. `dlopen` 跨 namespace 受 `public.libraries.txt` 白名单约束。
11. A17 起 `dlopen` 的 .so 必须只读签名分区，data 下热修 so 会被 SELinux `execmod` 拦。
12. native 崩溃**不会**被 Java `try/catch` 捕获——与 NPE 本质不同。
13. ART sigchain 让 ART 的"故意 segfault"（GC 写屏障）与 debuggerd 致命处理共存。
14. tombstone 标的是触发信号的 tid，根因可能在别的线程（竞态/UAF）。
15. 手写 `signal(SIGSEGV,...)` 吞段错误不可移植且被 sigchain 干扰，不要做。
16. MTE 仅在支持 arm64 v8.5 的设备可用，sync 精确但慢、async 性能友好。
17. JNI 跨边界持 `jobject` 必须 `NewGlobalRef`，否则被 GC 回收后变野指针。
18. 不仅 so 要 16KB 对齐，**任何自写 mmap/内存池假设 4KB 的 native 代码**都会在新内核崩。

---

## 三条高频追问链

**链 A：从"游戏闪退"到根因（贴合本月 8 月补丁热点）**
现象（游戏场景 app crash）→ 抓 tombstone（`SEGV_ACCERR` / fault addr 落在某 vendor 图形 so）→ `ndk-stack` 符号化定位到 `glMapBufferRange` 后写入 → 怀疑堆溢出/UAF → 本地 HWASan 构建复现拿到精确栈 → 发现是厂商预编译 so 按 4KB 对齐 + 越界写 → 修：重编 16KB 对齐 + 加边界检查。

**链 B：linker namespace × 插件化（架构/兼容性）**
app `dlopen` 三方 so 失败报 `not accessible` → 看 `ld.config.txt` 确认 default namespace 白名单 → 区分"系统库白名单缺失" vs "vendor namespace 隔离" → A17 `.so` 只读加固进一步收紧 → 结论：现代插件化不能再用 data 分区裸 dlopen。

**链 C：Java 栈怎么来的（原理深挖）**
`kill -3` 为何能 dump Java 栈？→ ART `SignalCatcher::HandleSigQuit` → `DumpJavaStack`（遍历 Thread 链表，suspend 各线程读栈帧）/ `DumpNativeStack`（libunwindstack）→ sigchain 如何把 SIGQUIT 路由到 ART 而不误伤 debuggerd → 与 native 崩溃走 debuggerd 路径的区别。

---

## AOSP 源码路径清单（Android 14）

```
system/core/debuggerd/handler/debuggerd_handler.cc      # 致命信号 handler 注册
system/core/debuggerd/crash_dump/crash_dump.cc          # crash_dump 子进程
system/core/debuggerd/tombstoned/tombstoned.cpp         # tombstone 守护进程
system/core/debuggerd/tombstone.cpp                     # engrave_tombstone() 生成主体
system/core/debuggerd/proto/tombstone.proto             # Tombstone proto 结构
system/core/libunwindstack/                            # 栈回溯(Unwind/Maps/DexFiles)
bionic/linker/linker_main.cpp                           # __linker_init 进程第一条指令
bionic/linker/linker.cpp                                # prelink_image/link_image/页对齐校验
bionic/linker/linker_namespace.cpp                      # namespace 隔离(allowed_libs)
system/linkerconfig/                                   # 生成 /linkerconfig/ld.config.txt
etc/public.libraries.txt                               # 跨 namespace 共享白名单
art/runtime/signal_catcher.cc                           # SIGQUIT -> DumpJavaStack/DumpNativeStack
art/runtime/sigchain.cc                                 # ART 信号链(与 debuggerd 共存)
art/runtime/thread.cc                                   # DumpNativeStack 实现
external/gwp_asan/                                     # GWP-ASan 采样检测
external/compiler-rt/lib/hwasan/                       # HWAddressSanitizer
```

---

## 28 -> 29 篇交叉索引

| 篇 | 主题 | 与本篇关系 |
|---|---|---|
| #7 系统基建与可观测性 | 16KB 页面 / 存储 / 日志 | 本篇专题四把 16KB 从"概念"落到 linker 校验与修复 |
| #9 渲染合成与 A17 安全内存 | A17 安全原生 DCL 加固 | 本篇专题三补 `.so` 只读加固的 linker/SELinux 落点 |
| #13 智能系统 AppFunctions | ApplicationExitInfo 死因 | 本篇专题六补 `REASON_CRASH_NATIVE` 的 native 侧全链路 |
| #19 全链路排查实战 | 内存三路杀 / 崩溃定位 | 本篇是"native 崩溃"这一路杀的专门教科书 |
| #20 源码级 code walk | 启动到首帧 | 本篇补 Zygote fork 后 linker 加载 / 信号处理初始化 |
| #21/25 Perfetto SQL | native 栈 / 帧 | 本篇专题七给出 Perfetto 看 native 栈的入口 |
| #24 ART dex2oat | 镜像对齐 | 本篇专题四补 boot.art/boot.oat 的 16KB 页感知 |
| #26 核心基础八股 | GKI 驱动 / epoll/futex | 本篇专题三/四把"驱动层内存页"与用户态 linker 接起来 |

> 全系列至此 **29 篇 / 约 189 专题**，主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧AI + 源码walk + Perfetto SQL + 基础八股 + 两版真题大乱斗 + Native 稳定性体系，完整闭环。后续若继续日更可考虑：KMP 跨平台实战坑（Swift Export / Kotlin-Native Worker 并发 / CMP iOS 无障碍 XCTest opaque）、Compose 编译器插件 IR lowering `$changed` 位掩码源码走读。
