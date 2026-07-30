# Android Framework 开发实战文章清单

> 整理时间:2026-07-30
> 面向目标:Android 14 (API 34, UpsideDownCake) AOSP 定制 / Framework 系统服务 / Binder 驱动 / 编译刷机
> 用途:书签库 + 可套用路径对照,配合 `android-aosp-ai-agent.md` 与 `android-hal-history.md` 使用

---

## 一、自定义系统服务(Framework 层添加 Service)

最贴合「在 AOSP 里加 `AIAgentManagerService`」的 MVP 第 1–5 步模板,路径几乎能直接套。

### 1. 《Framework层自定义系统服务》
- 链接:https://blog.csdn.net/u012739527/article/details/156002629
- 要点:完整 5 步 —— 定义 AIDL → 实现 `extends IXxx.Stub` → 在 `SystemServer` 里 `ServiceManager.addService` → 扩展 `Context.java` 常量 → `adb shell service list | grep xxx` 验证。
- 关键命令:`make frameworks-base` / `make services` / `make systemimage`。
- 可套用:`frameworks/base/core/java/android/content/Context.java` 加常量、`getSystemService()` 分支。

### 2. 《Android 13 AOSP 自定义 Service 创建与开机调用实现指南》
- 链接:https://gitcode.csdn.net/69f1d9060a2f6a37c5a6d113.html
- 要点:带完整 patch 对照。
  - `frameworks/base/services/core/Android.bp` 加 `filegroup` 把 AIDL 编进 `services.core.unboosted`。
  - `WindowManagerService.performEnableScreen()` 里跨进程回调自定义服务。
  - SELinux:`system_server_service, service_manager_type` + `add_service(system_server, myservice)`。
- 关键文件:`system/sepolicy/private/service.te`、`service_contexts`、`system_server.te`。

### 3. 《Android 12 (AOSP) 添加自定义系统服务》
- 链接:https://juejin.cn/post/7632621929040035866
- 要点:强调完整闭环 —— AIDL(`@hide`)+ `IXayeManager.Stub` + `SystemServiceRegistry` 注册 + ART 白名单 / `@hide` 清理 / 缓存清除这些易漏坑。
- 编译目标示例:`aosp_android12_r27`,`sdk_car_x86_64-userdebug`。

---

## 二、WMS / AMS / ATMS 源码调用链

### 1. 《AOSP15 WMS addWindow 详解》(掘金版)
- 链接:https://juejin.cn/post/7630730075692531775
- 全链路:`WindowManagerImpl.addView()` → `WindowManagerGlobal` → `ViewRootImpl.setView()` → `IWindowSession.addToDisplayAsUser()`(Binder IPC)→ `Session` → `WMS.addWindow()`。
- 拆解点:`DisplayPolicy.checkAddPermission()` 权限校验、`WindowToken` 查找/创建、`WindowState` 构造、`InputChannel` 注册。
- 真实路径:`frameworks/base/core/java/android/view/WindowManagerImpl.java`、`WindowManagerGlobal.java`、`ViewRootImpl.java`。

### 2. 《AOSP15 WMS addWindow 详解》(manbohub 版,含时序图)
- 链接:https://manbohub.com/archives/4313
- 同主题补充版,带更完整架构图与执行时序,适合对照看。

### 3. 《WMS relayout 调用流程详解》
- 链接:https://juejin.cn/post/7631950703318761478
- 全链路:`performTraversals → relayoutWindow → WMS.relayoutWindow → computeFrames`。
- 含 `relayoutAsync` 优化机制、`windowShouldResize` 判定。

### 4. 《AOSP15 Activity 启动流程源码分析(上)》
- 链接:https://blog.csdn.net/wxz1179503422/article/details/158042350
- 组件图:`ActivityStarter / ActivityTaskSupervisor / RootWindowContainer / TaskDisplayArea / Task`。
- 关键位置:`frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java`。
- ATMS 拆分说明:Android 10 起 Activity/Task 管理从 AMS 拆到 ATMS。

### 5. 《Android framework》(AMS/WMS/PMS/Binder 速查笔记)
- 链接:https://blog.csdn.net/weixin_45754428/article/details/156893099
- 速查内容:`ProcessRecord / ActivityRecord / TaskRecord`、`Zygote fork`、ANR 埋雷机制、Binder 1MB 限制、`ServiceManager` 句柄 0、Binder 线程池上限 16。
- 适合当面试/排查速查口诀。

---

## 三、Binder 驱动底层(drivers/android/binder.c + binder_alloc.c)

### 1. 《Binder 驱动 - 内核驱动层源码初探》
- 链接:https://blog.csdn.net/WriteBug001/article/details/163017090
- 核心:`binder_mmap` 的「一次拷贝」机制 —— mmap 阶段不立即分配物理页,只锁虚拟地址 + 建 `alloc->pages` 数组;物理页延迟到 ioctl 通信时由 `binder_alloc_new_buf_locked → binder_alloc_page` 分配。
- 真实路径:`drivers/android/binder.c`、`drivers/android/binder_alloc.c`。

### 2. 《深入内核:Binder 驱动的内存管理与事务调度》
- 链接:https://juejin.cn/post/7614747451162902563
- 基于 Android 10 内核,还原 `binder_proc / binder_thread / binder_node` 红黑树、`todo` 队列与 `wait_queue`。
- 解释 `TransactionTooLargeException` 与死锁根因。

### 3. 《Binder 驱动之内存管理》
- 链接:https://www.codeleading.com/article/95696220652/
- 聚焦 `binder_update_page_range()` 用 `allocate` 参数区分分配/回收,`free_buffers` / `allocated_buffers` 红黑树管理。

### 4. 《体验 Binder 的设计哲学 / Binder 发展历程时间线》
- 链接:https://blog.csdn.net/zhilin_tang/article/details/154423902
- Binder 内核版本变迁树(2008 → Android 14):含 `binder_alloc.c` 重构、BinderFS、零拷贝优化。
- `binder_open / binder_mmap / binder_ioctl` 函数调用树。

---

## 四、AOSP 编译 / 模块编译 / 刷机

### 1. 《AOSP 编译 handbook》
- 链接:https://rodneycheung.gitbook.io/handbook/5.-ji-shu-pian/android-ji-shu/zhun-bei/aosp_build
- 最干命令集:`mm / mma / mmm / mmma` 区别(`make` 类带依赖)。
- 刷机:`fastboot flashall -w`;改 `.so`:`adb push` + `mount -o rw,remount /system`。
- 坑:`ro.secure=0` 才能 remount。

### 2. 《Ubuntu 24.04 AOSP 下载编译(国内镜像加速)》
- 链接:http://www.jsqmd.com/news/702822/
- 保姆级:环境配置 + `lunch aosp_x86_64-eng` + 模拟器启动 + `mm / mma` 单编 + `adb shell perfetto` 性能分析。

### 3. 《mm/mma/mmm 到底怎么选》
- 链接:https://wenku.csdn.net/answer/27zabyodd6q9
- 重点:**Framework 核心模块(`services.jar`)单编后要用 `make snod` 重新打包 system.img 或 `adb push` 才生效** —— 直接对应改 `AIAgentManagerService` 后的验证方式。

### 4. 《源码编译 ubuntu20.04 + Pixel3 真机》
- 链接:https://blog.csdn.net/yxf0448/article/details/124118981
- 真机流程:`fastboot flashing unlock` + 逐分区 `flash boot/system/vendor`。
- `mm –B` 强制重编。

---

## 五、建议切入点(按与当前项目相关度排序)

1. **想先把 `AIAgentManagerService` 骨架跑通** → 看「一」的 1/2/3,直接套 AIDL + SELinux patch。
2. **想搞清「手」怎么注入输入** → 看「二」addWindow / relayout,定位 `InputManager` / `WMS` 注入点。
3. **想写 HAL 推理后端** → 看「三」binder_alloc,配合 `android-hal-history.md` 的 HAL 演进笔记。
4. **想把改动落到真机/模拟器验证** → 看「四」的模块编译与刷机命令。

---

## 附:关联本地文档

- `android-aosp-ai-agent.md` —— AOSP 集成 AI 多智能体设计文档(架构 + 集成点路径对照)。
- `android-hal-history.md` —— Android HAL 历史变化(Legacy / HIDL / AIDL 三阶段)。
- `android-inapp-agent/` —— 设备内 App agent 工程(AccessibilityService 感知+动作)。
