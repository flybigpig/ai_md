# Automation: framework（每日 Android Framework 面试题整理）

## 执行记录

### 2026-07-23（首次运行）
- 搜集近期热点：Binder 驱动层（一次拷贝/mmap/线程池/TTLE）、启动链路（ATMS）、HAL（Treble/HIDL→AIDL/FMQ）、ANR 机制、Compose 重组（SlotTable/Snapshot）、MTK 平台（DuraSpeed/AEE）。
- 产出：`Android_Framework面试题_2026-07-23.md`（16 个专题 + 查缺补漏清单，含 AOSP 源码路径）。
- 已覆盖主题（后续避免重复、可轮换深挖）：Handler/Looper+同步屏障、Binder(3篇)、冷启动、Zygote socket、AMS/ATMS+oom_adj、WMS/SF、View 三部曲、ANR、LMKD/PSI、Compose、HAL、GKI/内核、MTK。
- 下次可换角度：Input 系统全链路、PMS 安装流程、ART 类加载/JIT/AOT、SystemUI/锁屏、多屏/折叠屏 WM、SELinux、OTA/AB 分区、JNI/art hook、Binder 安全、Perfetto 分析实战。

### 2026-07-23（第二次运行·查缺补漏专题）
- 主篇已覆盖 16 章主线路，本次按"轮换角度"补 10 个查缺补漏方向：Input 全链路、PMS 安装扫描、ART/JIT/AOT 与基线 Profile、SystemUI/锁屏、折叠屏/多窗口 WM(WindowOrganizer/TaskFragment)、SELinux 排错、OTA/AB+动态分区+snapuserd、JNI/hook、Binder 安全(clearCallingIdentity/SELinux ctx)、Perfetto 实战。
- 产出：`Android_Framework面试题_热点拓展_2026-07-23.md`（与同日主篇互为补充，含 AOSP 源码路径 + 交叉索引表）。
- 推送飞书：feishu 连接器当前为 disconnected，无法自动推送；已生成文件并提示用户连接飞书或改用 DingTalk（自动化配置的 connector 为 dingtalk，响应文本会被推送到钉钉）。
- 后续可轮换的真·未覆盖角度：ART 内存布局/对象头、Binder 驱动 TTLE 全链路 dump 实战、Input 多指/手势、Vsync/Display 时序精算、Camera/音频 HAL、Car/Automotive、ART deopt/verify、Rust Binder 代码走读、GKI KMI 模块开发、perfetto SQL 实战范例。

### 2026-07-23（第三次运行·推送飞书成功）
- 用户要求重新推送到飞书。feishu 连接器已 connected；lark-cli 技能自带 app（cli_aaeb44244db89bc9）首次设备流仅拿到 bot 身份，云空间上传需要 user 身份 + drive scope。
- 重新发起 drive 域 user 设备流授权（用户「王凯」授权），获得 drive:file:upload、drive:drive.metadata:readonly。
- 用 `lark-cli markdown +create --as user --file <相对路径>` 将两份 Markdown 推送到飞书云空间根目录，均成功（主篇 file_token Q5jgbT1PkoBBtXxIOwBczW7unNd；拓展篇 Ui2RbKCaHo5qysxlIrocT6btnTd）。
- 踩坑记录：lark-cli 的 --file 必须是「当前目录内相对路径」（需先 cd 到 workspace）；qrcode 子命令 --url 含 `&` 会被内层 cmd 误解析，自动二维码生成失败，改用「链接+设备码」手动授权更稳。

### 2026-07-23（第四次运行·深挖篇）
- 当日第三份产出：`Android_Framework面试题_深挖篇_2026-07-23.md`（11 章），覆盖此前规划的"真·未覆盖角度"：ART 对象头/LockWord、Android 14 CMC GC(userfaultfd)、verify/deopt、Binder 驱动调试实战(binderfs/binder_logs/tracepoint)、Rust Binder(libbinder_rs)、Input 多指拆分(split touch)、VSync 时序(VsyncSchedule/FrameTimeline/JankType)、Camera HAL(Camera3Device/AIDL)、Audio 全链路(AudioFlinger/FastMixer/AAudio MMAP)、GKI KMI/DDK/vendor hook、Perfetto SQL 实战；末尾附三篇交叉索引表。
- 推送飞书成功：云空间 file_token `Kx78bw2YEoPkf9xuNMtcaSFOncb`；另用 bot 身份 `im +messages-send --user-id <open_id> --text` 把链接直发用户私聊（chat_id oc_0cdb87ca7048b320a26c5e5fed7ca7af）——"推送到对话"比只传云空间更贴题，后续沿用此双动作。
- 踩坑：`im +messages-send` 传纯文本要用 `--text`，`--content` 只接受 JSON（如 '{"text":"..."}')。
- 至此当日三篇：主篇(16章)/拓展篇(10章)/深挖篇(11章)，主线+盲区+深水区已闭环。明日起建议轮换新角度：ART deopt 已覆盖，可选 Camera/Audio 更深(Codec2/MediaCodec)、Vulkan/ANGLE/HWUI 渲染、Thermal/PowerHAL、CarService、NFC/SE、Wi-Fi/BT 协议栈、Telephony(RIL) 等。

### 2026-07-24（第五篇·图形/多媒体/电源热控/通信篇）
- 按轮换规划覆盖此前完全未涉及的角度，产出 `Android_Framework面试题_图形多媒体通信篇_2026-07-24.md`（12 大专题+查缺补漏）：HWUI/DisplayList/RenderThread 同步点、Choreographer/VSync offset、SurfaceFlinger(BufferQueue/HWC Overlay vs GPU 合成)、图形内存(Gralloc/DMA-BUF/GraphicBuffer/fence 零拷贝、Binder 传 fd)、多刷新率(DisplayModeDirector 投票/LTPO)、MediaCodec 状态机+同步异步+Surface 零拷贝、Codec2(CCodec) vs OMX(ACodec)/C2Work/C2Buffer、Thermal HAL 降频链路、Power HAL+ADPF/PerformanceHint、Telephony/RIL(RILJ↔Radio HAL AIDL)、Wi-Fi(Mainline/ClientModeImpl/supplicant)、Bluetooth(Fluoride→Gabeldorsche)。均带 Android14 源码路径。
- 推送飞书成功：云空间 file_token `Rwn9bqI5KoGWHxxLVNTc6DGRnth`；bot 私聊(chat_id oc_0cdb87ca7048b320a26c5e5fed7ca7af)发链接成功。
- 踩坑：user 身份无 `im:message.send_as_user` scope 无法私聊发消息，须用 `--as bot`；上传仍走 user 身份(drive:file:upload)。此为稳定组合，后续沿用「user 上传 + bot 发消息」。
- 明日可轮换：Vulkan/ANGLE/HWUI Skia 后端深挖、Codec2 vendor plugin 开发、CarService/Automotive、NFC/SE、NNAPI/TFLite delegate、virtual A/B snapuserd 深水区。

### 2026-07-27（第六篇·系统基建·安全存储·可观测性与版本演进篇）
- 按轮换规划覆盖此前完全未涉及的角度，产出 `Android_Framework面试题_系统基建与可观测性篇_2026-07-27.md`（11 大专题+查缺补漏）：16KB 页面(Android15/16 强制+兼容模式,近期热点)、ClassLoader/插件化(DexPathList.dexElements 插桩)、权限全链路(PermissionManagerService/AppOps)、Keystore2/Keymint HAL、Verified Boot/AVB/dm-verity/fscrypt、Vold/FUSE 存储(sdcardfs 退场)、logd/liblog 日志、性能可观测性(Looper Printer/Choreographer/BlockCanary/Matrix)、RRO/Overlay(idmap)、Doze/AppStandby/JobScheduler/WakeLock(含 A16 JobScheduler 配额变更)、Android15/16 行为变更串讲。均带 Android14 源码路径。
- 飞书推送成功：user 身份上传云空间 file_token `Q83YbMIBdokkTexiKJ9cb4fJn0f`(url https://my.feishu.cn/file/Q83YbMIBdokkTexiKJ9cb4fJn0f)；bot 身份发链接到用户私聊(chat_id oc_0cdb87ca7048b320a26c5e5fed7ca7af, message_id om_x100b695ef77288b0b1a4c14de56578f)。
- 状态/踩坑：本次 user 身份 token 需 refresh 但自动刷新成功(expiresAt 已过、refreshExpiresAt 2026-07-31 仍有效)；bot 身份 ready。沿用「user 上传 + bot 发消息」稳定组合，均一次成功。用户 openId ou_9bb9a536eb5ca6ec98914b4982e2bafb。
- 六篇至此闭环：主篇(16)/拓展篇(10)/深挖篇(11)/图形多媒体通信篇(12)/本篇(11)。后续仍可轮换：CarService/Automotive、NFC/SE、NNAPI/TFLite、Vulkan/ANGLE/HWUI-Skia 后端、Codec2 vendor plugin、virtual A/B snapuserd、ART 镜像 odex 布局深水区。

### 2026-07-28（第七篇·端侧 AI 与 Android 17 演进热点篇）
- 联网锚定当日热点：Android 17(API 37, CinnamonBun, 2026-06-16 stable)正式版主线 = Compose-First / Adaptive-First / 端侧 AI-NPU 化 / 隐私收紧；NNAPI 被标 deprecated，A17 要求 NPU 访问声明 FEATURE_NEURAL_PROCESSING_UNIT；端侧 AI 为 2026 最大增量热点。
- 产出 `Android_Framework面试题_端侧AI与Android17演进_2026-07-28.md`（10 大专题 + 六篇总图 + 易错点速记）：填补此前完全未覆盖的真缺口——NNAPI/NPU 全链路(IDevice 分区调度/共享内存张量/neuralnetworks AIDL HAL)、LiteRT NPU delegate 与 A17 NPU 声明、CarService/Vehicle HAL、Vulkan/ANGLE/HWUI Skia 后端、ART oat/odex/vdex/art 镜像与 profile-guided、virtual A/B + snapuserd COW 快照；并将 NNAPI/CarService/Vulkan/ART 产物/virtual A/B 与 A16/A17 行为变更(edge-to-edge/Predictive Back/WindowSizeClass/大屏 resizable)热点衔接。均带 Android 14 AOSP 源码路径。
- 飞书推送成功：user 身份上传云空间 file_token `PxVfbagJ6os06mxRTFRcrpBbnGf`(url https://my.feishu.cn/file/PxVfbagJ6os06mxRTFRcrpBbnGf)；bot 身份发链接到用户私聊(chat_id oc_0cdb87ca7048b320a26c5e5fed7ca7af, message_id om_x100b694bcf1018a4b038a9009dfac68)。
- 状态：user 身份 token 本次 needs_refresh 但自动刷新成功(expiresAt 2026-07-27 已过期,refreshExpiresAt 2026-08-03 仍有效);bot 身份 ready。沿用「user 上传 + bot 发消息」稳定组合,均一次成功。
- 七篇至此闭环：主篇(16)/拓展篇(10)/深挖篇(11)/图形多媒体通信篇(12)/系统基建篇(11)/本篇(10)。后续真·未覆盖角度所剩：Media3/ExoPlayer、Codec2 vendor plugin、LiteRT NPU delegate 源码走读、端侧 LLM(Gemini Nano)运行时、CarService 多用户/多显示、SF RenderEngine Vulkan 后端、ART hiddenapi/非 SDK 接口管制。

### 2026-07-29（第八篇·Android 17 新雷区 + 真缺口补全篇）
- 联网锚定当日热点：Android 17(API 37, CinnamonBun, 2026-06-16 stable)Framework 破坏性变更集中爆发——Lock-free MessageQueue、ART 分代 GC、static final 真不可变、ProfilingManager 新触发器(COLD_START/OOM/KILL_EXCESSIVE_CPU_USAGE)、后台音频加固+自定义通知限制。
- 产出 `Android_Framework面试题_2026-07-29.md`（8 大专题+查缺补漏+八篇交叉索引）：A17 Lock-free MessageQueue(Handler/Looper 新雷区)、ART 分代 GC(CMC 之上加 young/old gen,经 art apex Mainline 热更)、ART hiddenapi/非SDK接口管制(light/dark/black greylist + A17 final 封死)、ProfilingManager 触发器、后台音频+通知限制、NFC/Secure Element 全链路、Media3/ExoPlayer 底层(构建于 MediaCodec)、端侧 LLM(AICore 专有/ODP AOSP 开放)。均带 Android14 AOSP 源码路径。
- 补全此前七篇完全未覆盖真缺口：hiddenapi、NFC/SE、Media3/ExoPlayer、端侧LLM(AICore/ODP)。八篇至此 78 个专题闭环。
- 飞书推送成功：user 身份上传云空间 file_token `Z8zCbjvwRoc4aPxZctLc66TCnMd`(url https://my.feishu.cn/file/Z8zCbjvwRoc4aPxZctLc66TCnMd)；bot 身份发链接到用户私聊(chat_id oc_0cdb87ca7048b320a26c5e5fed7ca7af, message_id om_x100b69a0d2ab34a8b20487f339486db)。
- 状态：user 身份本次 needs_refresh 但自动刷新成功(expiresAt 2026-07-28 已过,refreshExpiresAt 2026-08-04 仍有效);bot 身份 ready。沿用「user 上传 + bot 发消息」稳定组合,均一次成功。
- 后续真·未覆盖角度所剩：Codec2 vendor plugin 开发、SF RenderEngine Vulkan 后端、LiteRT NPU delegate 源码走读、CarService 多用户/多显示、ART 镜像 odex 布局深水区。
