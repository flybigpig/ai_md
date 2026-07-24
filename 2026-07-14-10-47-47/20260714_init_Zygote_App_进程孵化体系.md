# init → Zygote → App 进程孵化体系

> 基于 AOSP `system/core/init`、`frameworks/base/cmds/app_process`、`frameworks/base/core/java/com/android/internal/os/ZygoteInit.java`。
> 本文聚焦「一个 App 进程到底是怎么被生出来的」，以及 Zygote 为什么是 Android 进程模型的灵魂。

---

## 目录

1. 进程模型的本质：只有两条出生通道
2. init（PID 1）：用户态第一个进程
3. Zygote：所有 Java 进程的母体
4. Zygote socket 协议与 forkAndSpecialize
5. system_server 与 Launcher 的孵化
6. 点图标启动 App：闭环回到 Zygote
7. forkAndSpecialize 内部做了什么
8. 关键类与文件索引

---

## 1. 进程模型的本质：只有两条出生通道

记住一句话：**init 用 `fork()+execve()` 生出 Zygote；之后所有 Java 进程（system_server 和每个 App）全是 Zygote 用 `fork()` 生出来的。**

```mermaid
graph TD
    Kernel[Linux 内核] --> Init[init PID 1]
    Init -->|fork + execve app_process| Zygote[Zygote]
    Zygote -->|forkSystemServer| SS[system_server]
    SS -->|startActivity HOME| Launcher[Launcher App]
    Zygote -->|fork (按需)| App[用户 App]
    Launcher -->|startActivity| App
```

fork 后子进程**继承 Zygote 预加载的类、资源和 ART 运行时**，再进入各自的 `main()`——这就是 Android 进程启动飞快的根本原因。

---

## 2. init（PID 1）：用户态第一个进程

内核完成启动后 exec 出 `init`，现代 AOSP 把 `main()` 拆成阶段函数：

```cpp
// system/core/init/main.cpp
int main(int argc, char** argv) {
    if (argc > 1 && !strcmp(argv[1], "selinux_setup"))
        return SelinuxSetupKernelLogging(argv);   // 加载 SELinux 策略
    if (argc > 1 && !strcmp(argv[1], "second_stage"))
        return SecondStageMain(argc, argv);        // 第二阶段重头戏
    return FirstStageMain(argc, argv);             // 挂 /sys /dev /proc
}
```

```cpp
// system/core/init/second_stage.cpp SecondStageMain()
void SecondStageMain(...) {
    PropertyInit(); SignalHandlerInit();
    LoadBootScripts(am);          // 解析 /init.rc + /system/etc/init + /vendor/etc/init
    StartPropertyService(&fd);
    am.ExecuteOneCommand();       // 按 trigger 执行命令（如 on boot 启动 service）
}
```

当 init 解析到 zygote 的 service 声明，会 `fork()+execve()`：

```cpp
// system/core/init/service.cpp
Result<void> Service::Start() {
    pid_t pid = fork();
    if (pid == 0) execve(args_[0].c_str(), args_, env);  // 子进程执行 app_process
    // 父进程(init)记录 pid，继续事件循环
}
```

zygote 的 service 定义在 rc 里：

```text
# system/core/rootdir/init.zygote64.rc
service zygote /system/bin/app_process64 -Xzygote /system/bin --zygote \
        --start-system-server --socket-name=zygote
    class main
    socket zygote stream 660 root system      # 这就是 /dev/socket/zygote
```

---

## 3. Zygote：所有 Java 进程的母体

init 执行的 `/system/bin/app_process64` 即 `app_process`：

```cpp
// frameworks/base/cmds/app_process/app_main.cpp
int main(int argc, char* const argv[]) {
    AppRuntime runtime(argv[0], computeArgBlockSize(argc, argv));
    if (zygote) { niceName = "zygote64"; startClass = "com.android.internal.os.ZygoteInit"; }
    runtime.start(startClass, args, /*zygote=*/true);
}
// frameworks/base/core/jni/AndroidRuntime.cpp
void AndroidRuntime::start(...) {
    startVm(&mJavaVM, &env, abort);   // 创建 ART 虚拟机
    startReg(env);                    // 注册 JNI
    env->CallStaticVoidMethod(startClass, mainMethod, ...);  // 进入 ZygoteInit.main
}
```

```java
// frameworks/base/core/java/com/android/internal/os/ZygoteInit.java
public static void main(String[] argv[]) {
    ZygoteServer zygoteServer = new ZygoteServer();   // 创建 /dev/socket/zygote 服务端
    preload();                          // 预加载类/资源/so，全员共享，fork 后直接继承
    gcAndFinalize();
    if (startSystemServer) {
        Runnable r = forkSystemServer(...);
        if (r != null) { r.run(); return; }    // 子进程走 system_server 分支
    }
    zygoteServer.runSelectLoop(abiList);        // 真·Zygote 在此等 fork 请求
}
```

`runSelectLoop()` 是 Zygote 核心：阻塞在 socket 上，谁要新进程就 `fork()` 谁。

---

## 4. Zygote socket 协议与 forkAndSpecialize

App 侧 `Process.start()` 把参数写进 Zygote socket：

```java
// frameworks/base/core/java/android/os/ZygoteProcess.java
Process.ProcessStartResult startViaZygote(...) {
    openZygoteSocketIfNeeded(abi);                 // 连 /dev/socket/zygote
    return zygoteSendArgsAndGetResult(...);        // 写参数，读回 pid
}
```

socket 上传输的是一串**以 `\n` 分隔的参数行**（简化）：

```text
--runtime-args
--setuid=10089
--setgid=10089
--runtime-flags=...
--target-sdk-version=34
--nice-name=com.example.app
android.app.ActivityThread   ← 子进程入口类名
```

Zygote 收到后 `forkAndSpecialize()`：

```java
// frameworks/base/core/java/com/android/internal/os/ZygoteConnection.java
Runnable processCommand(ZygoteServer zygoteServer, ...) {
    // 解析参数 → Zygote.forkAndSpecialize(uid, gid, gids, runtimeFlags, ...)
    // fork 返回后：
    //   父进程：返回 null，继续 runSelectLoop
    //   子进程：执行 handleChildProc() → 进入目标 App 的 main（ActivityThread）
}
```

---

## 5. system_server 与 Launcher 的孵化

`forkSystemServer()` 在子进程里走 `handleSystemServerProcess()` → `RuntimeInit.zygoteInit()` → `SystemServer.main()`：

```java
// frameworks/base/services/java/com/android/server/SystemServer.java
public static void main(String[] args) { new SystemServer().run(); }
void run() {
    startBootstrapServices();   // AMS / ATMS / PowerManager / DisplayManager
    startCoreServices();
    startOtherServices();       // WMS / IMS / PMS 收尾
    mActivityManagerService.systemReady(() -> { ... });  // 系统就绪后启动 Home
}
```

`systemReady()` 里通过 ATMS 解析 `CATEGORY_HOME` 启动桌面：

```java
// ActivityManagerService.systemReady() → RootWindowContainer.startHomeOnTaskDisplayArea()
Intent homeIntent = new Intent(Intent.ACTION_MAIN, null);
homeIntent.addCategory(Intent.CATEGORY_HOME);
startActivityLocked(homeIntent, ...);   // 走 ATMS 启动链
```

因为 Launcher 进程此时不存在，这条链走到 `startSpecificActivityLocked()` → `startProcessLocked()` → **再次请求 Zygote fork**。所以 **Launcher 的出生流程和你的 App 完全一样**。

---

## 6. 点图标启动 App：闭环回到 Zygote

Launcher 是个普通 App，点图标只是发 `startActivity`：

```java
// packages/apps/Launcher3 点击图标
Intent intent = new Intent(Intent.ACTION_MAIN)
        .setComponent(new ComponentName(pkg, activity));
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
startActivity(intent);                  // → Binder → ATMS
```

ATMS 一路到 `ActivityStack.startSpecificActivityLocked()`，发现目标进程不存在 → `startProcessLocked()` → `Process.start()` → Zygote socket → `forkAndSpecialize()` 出新进程 → `ActivityThread.main()`：

```java
// frameworks/base/core/java/android/app/ActivityThread.java
public static void main(String[] args) {
    Looper.prepareMainLooper();
    ActivityThread thread = new ActivityThread();
    thread.attach(false, startSeq);     // false = 非系统进程
    Looper.loop();                      // 主线程消息循环
}
// attach(false): 反连 AMS，把自己注册上去
final IActivityManager mgr = ActivityManager.getService();
mgr.attachApplication(mAppThread, startSeq);    // Binder 回 system_server
```

AMS `attachApplication()` 做两件事：① `bindApplication` 让 App 创建 `Application`；② `attachApplication` 把 pending 的 Activity 真正 launch。

---

## 7. forkAndSpecialize 内部做了什么

fork 之后、进入 App `main()` 之前，子进程要做一系列「个性化」：

```java
// frameworks/base/core/java/com/android/internal/os/Zygote.java
static int forkAndSpecialize(...) {
    int pid = nativeForkAndSpecialize(uid, gid, gids, runtimeFlags,
            rlimits, mountExternal, seInfo, niceName, ...);
    // native 侧（native 层）做：
    //   - setgroups / setgid / setuid：切换成 App 的 uid/gid（沙箱隔离）
    //   - setns：加入对应 mount namespace（隔离存储）
    //   - selinux_android_setcontext：设置 SELinux context
    //   - capabilities 裁剪：丢掉多余 capability
    //   - 关闭 Zygote 持有的其他 socket（只保留自己的）
    return pid;
}
```

这就是为什么每个 App 跑在**独立 uid + 独立 SELinux context** 的沙箱里——隔离在 fork 之后、specialize 阶段就完成了。

---

## 8. 关键类与文件索引

| 类 / 函数 | 文件 | 职责 |
|-----------|------|------|
| `main` / `SecondStageMain` | `system/core/init/{main,second_stage}.cpp` | init 两阶段启动 |
| `Service::Start` | `system/core/init/service.cpp` | fork+execve 生 Zygote |
| `app_main.cpp` | `frameworks/base/cmds/app_process/app_main.cpp` | app_process 入口 |
| `AndroidRuntime::start` | `frameworks/base/core/jni/AndroidRuntime.cpp` | 起 ART、进 ZygoteInit |
| `ZygoteInit` | `core/java/com/android/internal/os/ZygoteInit.java` | preload、runSelectLoop |
| `ZygoteServer` / `ZygoteConnection` | `core/java/com/android/internal/os/` | socket 监听与 fork 命令 |
| `ZygoteProcess` | `core/java/android/os/ZygoteProcess.java` | App 侧连 Zygote socket |
| `ActivityThread.main` | `core/java/android/app/ActivityThread.java` | App 进程真正入口 |

---

## 一句话总结

> Android 进程模型的全部奥秘就一句话：**init 用 fork+execve 生 Zygote，之后所有 Java 进程（system_server 和每个 App）全是 Zygote fork 出来的**。fork 后子进程继承预加载的类和 ART，再在 `forkAndSpecialize` 里换上自己的 uid/SELinux context 成为沙箱；App 启动链最终都绕回 Zygote 的 socket——这就是「点一下图标，进程就起来了」背后那条唯一的孵化通道。
