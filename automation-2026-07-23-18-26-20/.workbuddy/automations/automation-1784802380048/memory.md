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

### 2026-07-30（第九篇·渲染合成深水区 + Android 17 安全/内存新雷区篇）
- 联网锚定当日热点（Google I/O 2026 / Android 17 stable 2026-06-16）：Vulkan 成为原生 GPU API + WebGPU 进 Jetpack；A17 新增 Memory Limiter（应用内存限额）、安全原生 DCL 加固（dlopen 的 .so 必须只读）、Keystore 每应用密钥限额、跨资料环回流量默认阻断、限制隐式 URI 授权。
- 产出 `Android_Framework面试题_渲染合成与A17安全内存_2026-07-30.md`（7 大专题 + 查缺补漏）：SF RenderEngine(GL/Vulkan 合成后端) + HWC 合成决策深水区、Codec2 vendor plugin(CCodec→C2Component 厂商扩展)、A17 Memory Limiter 与 LMKD/ART 分代 GC 协同、安全原生 DCL 加固(16KB/SELinux/hiddenapi 三连击)、Keystore 限额+跨资料环回阻断、CarService 多用户/多显示/整车电源、ART oat/odex/vdex/art 镜像布局深水区。均带 A14 AOSP 源码路径；九篇累计 85 专题。
- 飞书推送成功：user 身份上传云空间 file_token `Pv9sb7VtUothdcxSJGDc3KdJnAe`(url https://my.feishu.cn/file/Pv9sb7VtUothdcxSJGDc3KdJnAe)；bot 身份发链接到用户私聊(chat_id oc_0cdb87ca7048b320a26c5e5fed7ca7af, message_id om_x100b699df2d3f8a4b116ee2987a25cf)。
- 状态：user 身份本次 needs_refresh 但自动刷新成功(expiresAt 2026-07-29 已过, refreshExpiresAt 2026-08-05 仍有效); bot 身份 ready。沿用「user 上传 + bot 发消息」稳定组合，均一次成功。
- 后续真·未覆盖角度所剩：LiteRT NPU delegate 源码走读、SF RenderEngine Vulkan 后端细节、Codec2 vendor 组件调试实战、ART hiddenapi 名单生成流水线、端侧 LLM 量化工程化、CarService 电源状态机完整状态图。

### 2026-07-31（第十篇·兼容性框架主线 × A17 跨设备/窗口/输入/隐私新雷区）
- 联网锚定当日热点（A17 官方 behavior-changes-17 + 中文解读）：大屏强制 resizable 退出选项移除、BAL 加固扩展到 IntentSender、Bubbles 浮窗新窗口模式、Handoff/Continue On、触控板 Pointer Capture 归一化、SMS OTP 三小时延迟、ECH + ACCESS_LOCAL_NETWORK、CP2 PII 裁剪 + Strict SQL、Contacts Picker。
- 关键发现：前九篇 85 专题一直在讲各种 targetSdk 行为变更的"结果"，却从未拆过其"引擎"——**应用兼容性框架 platform_compat**。本篇以它为主轴（§1），后九章均为其在各子系统的实例落点，形成强主线。
- 产出 `Android_Framework面试题_兼容性框架与A17跨设备窗口隐私_2026-07-31.md`（10 大专题 + 查缺补漏 + 15 条易错点速记 + 十篇交叉索引，44.8KB）：①compat 框架全链路(@ChangeId/@EnabledSince、CompatConfig、AppCompatCallbacks→ART disabled_compat_changes_、编译期 compat_config XML、am compat 调试) ②WMS letterbox/SizeCompat(ActivityRecord/LetterboxUiController/DisplayContent.ignoreOrientationRequest、相机预览角度三叠加) ③BAL(BackgroundActivityStartController、callingUid vs realCallingUid 防 confused deputy、ALLOW_IF_VISIBLE) ④Bubbles(TaskOrganizer/TaskView/WindowContainerTransaction，区别于 A11 气泡通知) ⑤Handoff/CDM ⑥Pointer Capture(InputDispatcher::setPointerCaptureLocked、TouchpadInputMapper+libchrome-gestures) ⑦SMS OTP 双层拦截(InboundSmsHandler + SmsProvider.query) ⑧ECH(Conscrypt/BoringSSL/GREASE) + ACCESS_LOCAL_NETWORK(eBPF/netd) ⑨SQLiteQueryBuilder setStrictColumns/setStrictGrammar 防盲注 ⑩hiddenapi 生成流水线(UnsupportedAppUsageProcessor→art/tools/hiddenapi→AccessContext domain)。累计 95 专题。
- 飞书推送成功：user 身份上传云空间 file_token `Knn4bVROdowUTlx2nmNc205En6f`(url https://my.feishu.cn/file/Knn4bVROdowUTlx2nmNc205En6f)；bot 身份发链接到用户私聊(chat_id oc_0cdb87ca7048b320a26c5e5fed7ca7af, message_id om_x100b698a9f4520a4b4c17eb03618712)。
- 状态：user 身份 needs_refresh 自动刷新成功(expiresAt 2026-07-30 已过, refreshExpiresAt 2026-08-06 仍有效)；bot ready。「user 上传 + bot 发消息」组合第七次一次成功，稳定。lark-cli 有 1.0.80 更新可用（当前 1.0.79，不影响功能）。
- 后续真·未覆盖角度所剩：LiteRT NPU delegate 源码走读、CarService 电源状态机完整状态图、Codec2 vendor 组件调试实战、端侧 LLM 量化工程化、packages/modules/Connectivity eBPF 程序细读、Ravenwood host 侧单测框架、Trusty TEE / Widevine DRM。

### 2026-08-01（第十一篇·安全世界 TEE 全链路 × A17 架构级安全/内存新雷区）
- 联网锚定当日热点（A17 官方 release notes Architecture/Security 段）：ION 彻底弃用（支持内核 2025-12 EOL）→ 必迁 DMA-BUF heaps、硬件封装密钥改进、安全元件预热 IWeaver#warmUp()（省 ≤200ms）、锁屏速率限制修复（LockPatternUtils 超时缓存 bug）、音频托管 SCO 重构、AOSP 源码树只读、memfd_class 政策。
- 关键发现：前十篇 95 专题讲遍「普通世界」，从未跨过 EL3 Secure Monitor。本篇以「安全世界（Trusty TEE）」为主轴，正好落地 memory 规划已久的未覆盖真缺口「Trusty TEE / Widevine DRM」。
- 产出 `Android_Framework面试题_安全世界TEE与A17架构级安全内存_2026-08-01.md`（8 大专题 + 15 条易错点 + 高频追问链 + 十一篇交叉索引，约 30KB）：①Trusty 架构与 SMC 世界切换全链路(TrustZone NS 位/EL3/SError/key blob，drivers/trusty、system/core/trusty) ②libtrusty/trusty-ipc(TIPC≠Binder，/dev/trusty-ipc-dev0、virtio) ③Keystore2(Rust,system/security/keystore2)+KeyMint AIDL HAL→TA(Authorization Tags/auth-bound key/HAT) ④Gatekeeper(IGatekeeper)/Weaver(IWeaver,SE)+A17 warmUp()+锁屏限速+SID=USER_SECURE_ID ⑤Key Attestation(X.509 OID 1.3.6.1.4.1.11129.2.1.17/RootOfTrust)+RKP/DICE ⑥Widevine DRM(mediadrmserver/DrmHal-CryptoHal/drm AIDL HAL/OEMCrypto/L1-L2-L3/queueSecureInputBuffer/secure buffer) ⑦硬件封装密钥+FBE(vold/fscrypt/ICE，A17) ⑧ION→DMA-BUF heaps(drivers/dma-buf/heaps、libdmabufheap/BufferAllocator、每堆独立 SELinux，A17)。均带 A14 源码路径。累计 103 专题。
- 飞书推送成功：user 身份上传云空间 file_token `WWu2b23wtoYy6mxvj2jcIfPRnVd`(url https://my.feishu.cn/file/WWu2b23wtoYy6mxvj2jcIfPRnVd)；bot 身份发链接到用户私聊(chat_id oc_0cdb87ca7048b320a26c5e5fed7ca7af, message_id om_x100b69e7a5b7eca4b29e94203a41caf)。
- 状态：user 身份 needs_refresh 自动刷新成功(server verification succeeded after refresh)；bot ready。「user 上传(markdown +create) + bot 发消息(im +messages-send --text)」组合第八次一次成功，稳定。
- 后续真·未覆盖角度所剩：LiteRT NPU delegate 源码走读、CarService 电源状态机完整状态图、Codec2 vendor 组件调试实战、端侧 LLM 量化工程化、Connectivity eBPF 程序细读、Ravenwood host 单测框架、StrongBox/SE 深水区、Protected Confirmation(ConfirmationUI)、pKVM/AVF(虚拟化 EL2)。

### 2026-08-02（第十二篇·EL2 机密计算：pKVM/AVF × A17 AISeal）
- 联网锚定当日热点：Google 宣布 **pKVM 通过 SESIP Level 5 认证**（首个达此级别的大规模消费电子软件安全系统，EN-17927/AVA_VAN.5）；A17 推出 **AISeal with pKVM** —— 机密计算沙箱，把 AppSearch 个人数据库 + 端侧大模型推理 + AI Agent 全部搬进 AVF 保护型 VM（Microdroid，protected 默认开，~300MB RAM / ~16GB 加密存储，多租户 vsock，Rust host service + Java 系统服务）。
- 关键设计：承接第十一篇的 EL3 安全世界（Trusty TEE），本篇跨到 **EL2 Hypervisor**，一次性补齐 EL0/EL1/EL2/EL3 四层执行世界拼图；并顺带落地 memory 中挂了多轮的两个真缺口 **Connectivity eBPF** 与 **Ravenwood**。
- 产出 `Android_Framework面试题_pKVM机密计算与A17_AISeal_2026-08-02.md`（47.4KB，8 大专题）：①pKVM 架构（stage-2 页表所有权状态机/内存捐赠/nVHE vs VHE/host 自我降级/SMMU DMA 隔离，arch/arm64/kvm/hyp/nvhe/mem_protect.c）②AVF 五层链路 + crosvm 内存布局 + DICE/BCC per-VM secret 派生 + authfs Merkle/zipfuse（packages/modules/Virtualization/）③跨世界通信：vsock + RPC Binder（RpcSession/RpcServer，与内核 Binder 六点差异，**跨 VM getCallingUid 不可信**；binderRPC 同时服务 AVF 与 Trusty）④AISeal 全解剖 + PCC/Private AI Compute/AISeal 三层辨析 + 保护边界精确表述（采集链路在 host 不受保护）⑤pKVM vs TEE 威胁模型对比（TCB 1 万行 vs 全部 TA；pKVM 四短板：无早期启动/无安全外设/仅 ARM64/内存成本）⑥A17 Memory Limiter vs LMKD vs cgroup OOM **三条杀路径辨析** + ProfilingManager 四触发器 + binder spam 检测溯源到 BinderCallsStats/binder_transaction_log + onTrimMemory 自 A14 只剩两常量 ⑦Connectivity eBPF 全链路（NetworkPolicyManagerService→BpfNetMaps→TrafficController→bpf_progs/netd.c，cgroup_skb hook，双缓冲 stats map，xt_qtaguid 已退场）⑧Ravenwood（frameworks/base/ravenwood/，跑 AOSP 真身而非 shadow）+ CTS/VTS/GTS/MTS/CTS-V/STS 矩阵。累计 111 专题。
- 飞书推送成功：user 身份上传云空间 file_token `EveNbLot9oivkJxlPagcuhPsnAb`(url https://my.feishu.cn/file/EveNbLot9oivkJxlPagcuhPsnAb)；bot 身份发链接到用户私聊(chat_id oc_0cdb87ca7048b320a26c5e5fed7ca7af, message_id om_x100b69dd492108a0b2bb5dd375c8f7c)。
- 状态：user 身份 needs_refresh 自动刷新成功(expiresAt 2026-08-01 已过, refreshExpiresAt 2026-08-08 仍有效)；bot ready。「user 上传(markdown +create) + bot 发消息(im +messages-send --text)」组合第九次一次成功。lark-cli 1.0.79 → 1.0.81 有更新（不影响功能）。
- 后续真·未覆盖角度所剩：LiteRT NPU delegate 源码走读、CarService 电源状态机完整状态图、Codec2 vendor 组件调试实战、端侧 LLM 量化工程化、Protected Confirmation(ConfirmationUI)、StrongBox/SE 深水区、AVF 隔离编译(odrefresh in pVM)实战、Compose 编译器插件与 A17 Compose-First 演进、A17 Verified Financial Calls/Live Threat Detection 安全新特性。

### 2026-08-03（第十三篇·智能系统主线：AppFunctions × Compose-First × 后量子签名）
- 联网锚定当日热点：A17 官宣两条纲领 **Compose-First**（新 API/库/工具只面向 Compose；Fragment/RecyclerView/ViewPager/android.widget 进入 maintenance mode）与 **Intelligence System**；**AppFunctions** = Android 原生 MCP（@AppFunction 注解 + KDoc 变 LLM tool description，走 GMS/Mainline 故 A16+ 即可用，不锁 A17）；**后量子密码进平台**（安全硬件支持 ML-DSA/FIPS 204 + 新增 **APK 签名方案 v3.2** 混合签名）；`ApplicationExitInfo.getDescription()` 新死因 `MemoryLimiter:AnonSwap`；隐私范式从「运行时权限」转向「系统托管 UI + 一次性凭证」（Contacts Picker / Photo Picker / 系统渲染位置按钮 / EyeDropper）。
- 关键发现：前十二篇 111 专题讲透系统底座（Binder/AMS/WMS/SF/ART/HAL/内核/TEE/pKVM，EL0-EL3 四层世界已闭环），但**智能层是完整空白**：AppFunctions、AppSearch、Compose 编译器插件、Compose 运行时、Compose↔Framework 接缝、APK 签名、ApplicationExitInfo、URI 授权、无障碍语义树，一个都没讲过。本篇以「Android 从操作系统变成智能系统」为主轴一次补齐，同时落地 memory 中挂了多轮的「Compose 编译器插件与 A17 Compose-First 演进」缺口。
- 产出 `Android_Framework面试题_智能系统AppFunctions与ComposeFirst_2026-08-03.md`（82.9KB / 1050 行，9 大专题）：①AppFunctions 三链路（AppsIndexerManagerService 安装时索引→AppSearch AppFunctionStaticMetadata + AppFunctionRuntimeMetadata 经 JoinSpec 关联；BIND_APP_FUNCTION_SERVICE 保护模式；**Provider 侧 Binder.getCallingUid() 拿到 SYSTEM_UID 不可信**，与第十二篇跨 pVM 场景并列考点）②AppSearch/Icing（PlatformStorage 全用户共用一个 Icing 实例靠 prefix+VisibilityStore 隔离；LiteIndex+MainIndex 双索引 ≈ LSM-Tree memtable+SSTable；BM25F）③Compose 编译器插件（IR lowering 注入 $composer/$changed 位掩码；restart/replace/movable 三种 group；带返回值 Composable 不是重组边界；强跳过模式 === 判定 + 稳定性推断 + 编译器指标）④Compose 运行时（SlotTable gap buffer 平坦 IntArray；Snapshot MVCC 版本链 + readObserver「读取即订阅」；Recomposer 挂在 Choreographer **ANIMATION** 回调、View traversal 在 TRAVERSAL 回调同帧先后；强制单遍测量 + SubcomposeLayout；三阶段独立失效与延迟读取）⑤Compose↔Framework 六接缝（AndroidComposeView 在 View 树里只是一个 View / WindowRecomposer + ViewCompositionStrategy 泄漏坑 / 三段 PointerEventPass 替代 onInterceptTouchEvent / LayoutNode→RenderNode **未绕过 HWUI** / Modifier.Node / CompositionLocal static vs 非 static）⑥APK 签名 v1→v3.2（Signing Block ID-value pair 可扩展性、v2 摘要覆盖三段且替换 EOCD 中央目录偏移、ApkSignatureVerifier→ParsingPackageUtils→PMS checkCapability 链路、ML-DSA hybrid 与体积代价、为何签名先于加密迁移）⑦ApplicationExitInfo（AppExitInfoTracker 四路采集 + /data/system/procexitstore 每 UID 16 条、REASON 全表、**三条杀进程路径辨析**：内核 OOM/LMKD PSI/A17 Memory Limiter 个体超标静默杀、getImportance 区分前后台）⑧系统托管 UI + UriGrantsManagerService（临时 vs takePersistableUriPermission、urigrants.xml 上限 LRU、授权不可传递防 confused deputy、tapjacking 与可信 UI 三等级：系统渲染→TEE 渲染→Agent 显式确认）⑨无障碍语义树与 AI Agent UI 自动化（ANI 查询跨进程并跳目标 App UI 线程执行故主线程卡顿拖慢 Agent、ACTION_CLICK≠注入 MotionEvent、Compose SemanticsNode→ANI 映射、**Compose 语义树为何对 Agent 更友好**、pointerInput+detectTapGestures 无 onClick 语义经典坑）。末附 18 条易错点速记 + 三条高频追问链（Compose 性能/AI Agent/进程死因）+ 十三篇交叉索引。累计 **120 专题**。
- 飞书推送成功：user 身份上传云空间 file_token `KMqqbNbmUobjH8xdpmocWX9nnWd`(url https://my.feishu.cn/file/KMqqbNbmUobjH8xdpmocWX9nnWd)；bot 身份发链接到用户私聊(chat_id oc_0cdb87ca7048b320a26c5e5fed7ca7af, message_id om_x100b69ca61f79ca4b2606269d201b43)。
- 状态：user 身份 needs_refresh 自动刷新成功(expiresAt 2026-08-02 已过, refreshExpiresAt 2026-08-09 仍有效)；bot ready。「user 上传(markdown +create) + bot 发消息(im +messages-send --text)」组合**第十次一次成功**。lark-cli 1.0.79 → 1.0.81 有更新（不影响功能）。
- 工程踩坑（新）：用 Write 分片写超大 Markdown 时，某行 ASCII 框图出现单字节损坏（`─` 变 `�`）；`cat p1 p2 p3 > out` 字节拼接对 UTF-8 安全，但损坏行需用 Python 按行重写修复（Edit 工具匹配含损坏字符的 old_string 会失败）。建议后续分片写完后 grep 一次 `�` 做体检。
- 后续真·未覆盖角度所剩：LiteRT NPU delegate 源码走读、CarService 电源状态机完整状态图、Codec2 vendor 组件调试实战、端侧 LLM 量化工程化（INT4/KV cache/算子回退）、Protected Confirmation(ConfirmationUI)、StrongBox/SE 深水区(IOmapiService/applet)、AVF 隔离编译(odrefresh in pVM / compos)、A17 Verified Financial Calls / Live Threat Detection、Kotlin/Compose Multiplatform 在 Android 侧运行时差异、Robolectric shadow vs Ravenwood 取舍。

### 2026-08-04（第十四篇·端侧 AI 工程化 × AAOS 座舱电源 × 安全深水区）
- 产出 6 专题：LiteRT NPU delegate 全链路、端侧 LLM INT4/KV cache 量化、CarService CPMS+Garage Mode、StrongBox/SE(OMAPI)、Protected Confirmation、AVF 隔离编译(odrefresh/compos)。累计 126 专题。
- 飞书推送：user 上传云空间(file_token LKw9b0ZhMo22pVxaKbwcxUHannd) + bot 私聊成功。

### 2026-08-05（第十五篇·末轮真缺口补全 × 热点前瞻 × 体系总导航）
- 产出 4 个新深水区专题 + 体系总导航：Codec2 vendor 组件调试实战(C2ComponentStore 注册/querySupportedParams/config/C2Work 异步队列/codec2-info+dumpsys 调试)、KMP/skiko Android 侧运行时差异(androidMain=Kotlin/JVM→DEX→ART 无额外 runtime；skiko 仅非 Android target；Compose Multiplatform on Android = Jetpack Compose)、Robolectric shadow vs Ravenwood 对照(假 framework vs 真 AOSP 真身)、Android 18 前瞻(隐式 URI 授权全面收紧 detectImplicitUriPermissionGrant + 桌面融合)、14篇126专题交叉索引+7条跨篇追问链。累计约 130 专题。
- 联网锚定：Android 17(CinnamonBun) 2026-06-16 stable；A17 QPR2 预计 2026-12；A18 桌面融合 + URI 授权收紧路线图。
- 飞书推送：user 上传云空间(file_token NlCdb2kK4oNwvsxJWS0cBNsInsg, url https://my.feishu.cn/file/NlCdb2kK4oNwvsxJWS0cBNsInsg) + bot 私聊(message_id om_x100b681c0ebb84a8b2ee1503ae5eda3) 成功。「user 上传 + bot 发消息」组合**第十二次一次成功**。
- 后续真·未覆盖角度所剩：CarService 电源状态图细化(hibernation/多显示时序)、端侧 LLM 量化实操脚本。

### 2026-08-05（第十六篇·收官补遗，10:44 当日二次触发）
- 08:35 主运行已产出第十五篇（末轮缺口补全 + 体系总导航，15 篇/约 130 专题）并推送成功。本次为当日二次触发，不重复主篇，改为补齐第十五篇 TODO 中最后两块真缺口。
- 产出 `Android_Framework面试题_收官补遗_端侧LLM量化与AAOS电源状态机_2026-08-05.md`（2 专题，约 19KB）：①CarService 整车电源状态机（CPMS 状态图 ON/SHUTDOWN_PREPARE/SUSPEND/HIBERNATION/OFF/POST_SHUTDOWN + VHAL AP_POWER_STATE_* + CarPowerPolicy 组件级供电白名单 + 多显示掉电/上电时序 + GarageMode 与 hibernation 关系，packages/services/Car/...）②端侧 LLM 量化实操（weight-only vs W8A8 / INT8 vs INT4 代价 / KV cache 量化 / INT4 group-wise 可运行 Python 脚本 + llama.cpp quantize + LiteRT NPU delegate 算子回退 + 校准集）。系列正式收官 16 篇/约 132 专题。
- 飞书推送成功：user 身份上传云空间（file_token WmPUb6GmqoYWB8x4nZfcQvxrnYe，url https://my.feishu.cn/file/WmPUb6GmqoYWB8x4nZfcQvxrnYe）+ bot 私聊（message_id om_x100b681e19d860b4b4cccacd2ee76fa）成功。「user 上传 + bot 发消息」组合**第十三次一次成功**。lark-cli 1.0.82 -> 1.0.83 有更新（不影响功能）。
- 至此全系列真·未覆盖角度清零，主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱 + 端侧 AI + 收官补遗 完整闭环。

### 2026-08-05（第十七篇·考前总复习速查卡，11:00 当日三次触发）
- 当日已跑两次（08:35 第十五篇 / 10:44 第十六篇收官），系列 132 专题 + 真缺口清零。本次为第三次触发，不再写重复深水区，改为产出**跨 15 篇的考前定向复习导航**：`Android_Framework面试题_考前总复习速查卡_2026-08-05.md`（15 篇知识地图 + 11 子系统高频速答表 + 跨篇易错红榜 TOP25 + 10 条追问链 + AOSP 源码路径索引 + 复习节奏）。非新增角度，是收官后的"压制成速查"形态，对应 MEMORY 规划下一步"考前定向复习"。
- 飞书推送：首次用 `--folder-token` 把文档落到用户指定的**云文档 AOSP 文件夹**（token PJWMfGhfflNSLndN66lcix7wnOh，已存在）；user 上传成功(file_token Hex3bB6L7oAC5Qx0IhXc08dLngd, url https://my.feishu.cn/file/Hex3bB6L7oAC5Qx0IhXc08dLngd)；bot 私聊发链接(message_id om_x100b681e3a536880b16a3b3e212fbc9) 成功。
- 新稳定组合确认：「user 上传(--folder-token 指定 AOSP 文件夹) + bot 发消息」第十四次一次成功。lark-cli 1.0.82->1.0.83 有更新提示（不影响功能）。
- 工程点：本卡修正第 8 篇文件名笔误(08-30->07-30)，U+FFFD 体检=0。

### 2026-08-05（第十八篇·高频考官连击模拟考，当日第四次触发，此前未记入本文件）
- 当日 08:35/10:44/11:00 已跑第十五/十六/十七篇；本篇为当日第四次触发，产出 `Android_Framework面试题_高频考官连击模拟考_2026-08-05.md`：把前 17 篇 ~132 专题转成「考官连击」形态（知识变考场），不新增角度。系列至此 18 篇 / 约 132 专题完整闭环（主线+盲区+深水区+智能层+安全世界+座舱+端侧AI+收官补遗+速查卡+连击考）。

### 2026-08-06（第十九篇·全链路性能/问题排查实战专项）
- 联网锚定当日热点：A17 QPR2 Beta 2（8/4 发布，build CP41.260701.006，无行为变更）；QPR2 Beta1 修复清单含多指拖拽丢触摸 #516836306、ML-DSA "NONE" digest #525612735、窗口模糊渲染 #527376569、ANI.toString 窗口边界 #520428442（均可作"真题现场溯源"案例）；EU DMA 裁定（2026-07-16）强制 Google 在 A18 前开放 11 项 AI 能力给第三方助手；Perfetto 成排查事实标准(FrameTimeline/heapprofd/trace_processor SQL)。
- 角度选择：系列 132 专题已闭环，不再写重复深水区，改为**压轴实战篇**——把分散知识点串成"现象→抓trace→定界→根因→修复"的面试能力。产出 `Android_Framework面试题_全链路排查实战专项_2026-08-06.md`（6 大专题 + 易错红榜 TOP20 + 三条追问链 + 18篇交叉索引，约 30KB/444行，U+FFFD=0）：①冷启动全链路(trace 时间窗/bindApplication 30%+/ContentProvider 前置坑/基线&云 Profile/PinnerService) ②卡顿掉帧定界(Vsync→Choreographer→三阶段→RenderThread→SF→HWC；FrameTimeline JankType 定责；Perfetto 四步法；QPR2 多指/模糊 bug 呼应) ③ANR 回溯(四类超时/event log am_anr//data/anr/栈/Binder 阻塞链) ④内存三路杀(LMK/PSI vs A17 Memory Limiter vs 内核 OOM 辨析；Java/native/graphics/binder 分类排查；heapprofd；A14 CMC + A17 分代) ⑤发热掉速/后台受限(Thermal HAL→Power HAL/ADPF→降频；Doze/AppStandby/BAL/Job 配额 A16/FGS 类型 A14) ⑥Binder 实战坑(线程池默认 15/oneway 满也排队/大事务走 fd/跨 VM getCallingUid 不可信)+ A17 安全新特性收尾(Verified Financial Calls/Live Threat Detection，补最后一个真缺口)。均带 A14 AOSP 源码路径。累计约 138 专题。
- 飞书推送成功：「user 上传(--folder-token PJWMfGhfflNSLndN66lcix7wnOh，file_token EYM7bHqnFoZiAFxeK50cx0nInGc，url https://my.feishu.cn/file/EYM7bHqnFoZiAFxeK50cx0nInGc) + bot 私聊(message_id om_x100b680921a2aca8b29b972e2f939ba)」组合第十五次一次成功。lark-cli 1.0.82（1.0.83 可用，不影响功能）。
- 后续可轮换新角度（若继续日更）：专项"真题大乱斗"混合场景卷、A18 桌面融合/跨设备 handoff 前瞻深挖、EU DMA 第三方助手接入对 Framework 的影响（CDM 锁屏屏幕自动化权限重写已落地 QPR2）、Perfetto SQL 实战范例库。

### 2026-08-06（第二十篇·源码级 code walk 专项：startActivity 到首帧上屏，当日 20:54 二次触发）
- 当日上午已跑第十九篇（全链路排查实战）。本篇为当日二次触发，不重复深水区，改为落地 memory 规划已久的"真·新增角度"之一的**源码级 code walk**：把分散八股焊成一条端到端链路。
- 联网锚定当日热点：A17 QPR2 稳定 Beta(build CP41.260701.006, Pixel 10 已支持, 无行为变更)、A18 桌面融合/Googlebook(通用剪贴板+跨设备 handoff)、EU DMA 裁定开放 11 项 AI 能力(与 QPR2 CDM 锁屏屏幕自动化 PIN 门控配对勾连)、Material 3 Expressive 毛玻璃(A17 QPR2 Disable background blur 开关)、经典八股(Handler/Looper/Binder/AMS-WMS-PMS/性能优化)仍高频。
- 产出 `Android_Framework面试题_源码级codewalk启动到首帧_2026-08-06.md`（33KB / 433 行，U+FFFD=0）：五段全景链路图 + 逐段 code walk 带 A14 AOSP 真实路径——①ATMS/AMS 调度(startActivity→ActivityStarter→realStartActivityLocked→Zygote socket) ②Zygote fork + Application 启动(forkAndSpecialize→ActivityThread.main→handleBindApplication, **ContentProvider 前置坑**) ③Activity 生命周期 + Window 创建(performLaunchActivity→handleResumeActivity→ViewRootImpl.setView) ④View 绘制三阶段(performTraversals→measure/layout/draw, MeasureSpec 三模式, requestLayout vs invalidate, getMeasuredWidth≠getWidth) ⑤SurfaceFlinger 一帧的一生(app RenderThread→queueBuffer→SF onMessageInvalidate/onMessageRefresh→HWC Overlay vs GPU 合成→fence→FrameTimeline JankType) ⑥Binder 一次事务全追踪(BpBinder→IPCThreadState→ioctl→binder.c binder_transaction 一次拷贝→目标 BBinder onTransact)。均带 A14 源码路径。末附易错红榜 TOP20 + 三条跨篇追问链 + 11 节上 19 篇交叉索引。累计 20 篇 / 约 143 专题。
- 飞书推送成功：「user 上传(--folder-token PJWMfGhfflNSLndN66lcix7wnOh, file_token Dx70be2VEoziLAxz0tecTSXWnid, url https://my.feishu.cn/file/Dx70be2VEoziLAxz0tecTSXWnid) + bot 私聊(message_id om_x100b687c7008e100b308affb02b59d5)」组合第十六次一次成功。lark-cli 1.0.82（1.0.84 可用，不影响功能）。仍用 PowerShell 原生 Windows Set-Location 到工作区再上传，规避 Git Bash 双写盘符 MODULE_NOT_FOUND。
- 后续真·未覆盖角度所剩：专项"真题大乱斗"混合场景卷、A18 桌面融合/跨设备 handoff 前瞻深挖、EU DMA 第三方助手接入对 Framework 的影响、Perfetto SQL 实战范例库。

### 2026-08-07（第二十一篇·Perfetto 排查实战 SQL 范例库）
- 联网锚定：A17 QPR2 Beta 2 已于 8/3 推送(build CP41.260701.006, 安全补丁 2026-07-05, 无 changelog, 内部代号 CinnamonBun->DEV, 重绘设置图标; QPR2 stable 预计 2026-12); Perfetto 成事实标准(trace_processor SQL 可查 slice/thread_state/actual_frame_timeline_slice/binder_transaction/heap_profile/android_startup)。
- 角度选择：前 20 篇(约143专题)已闭环,本篇落点 = 把第十九篇"全链路排查"里只点到名的 Perfetto 落成可复用 trace_processor SQL 范例库,以面试 Q&A 形态呈现,每题配 AOSP 源码落点 + 易错点。
- 产出 `Android_Framework面试题_Perfetto排查实战SQL范例库_2026-08-07.md`(546行/29KB, U+FFFD=0): ①Perfetto 基础与抓取(systrace vs Perfetto / ring buffer / pbtx 配置) ②冷启动 SQL(android_startup + slice 拆解 bindApplication/installContentProviders 前置坑) ③掉帧定责 SQL(actual/expected_frame_timeline_slice JOIN frame_number+upid, jank_type/present_type 定责到 App/RenderThread/SF/HWC) ④主线程阻塞 SQL(thread_state 状态分布 / monitor_contention module 量化锁竞争) ⑤内存泄漏 SQL(heapprofd heap_slice vs heap_graph_object Java 泄漏, 三类杀手辨析) ⑥Binder 阻塞 SQL(binder_transaction 拆 kernel 拷贝 vs 对端执行, oneway 满也排队) ⑦电源/唤醒 SQL(cpu_frequency counter / wakelock slice, Thermal->Power HAL->降频) ⑧可复用 SQL snippet 库 ⑨易错红榜 TOP20 + 三条高频追问链 + 20篇交叉索引。累计约 148 专题。
- 飞书推送成功：「user 上传(--folder-token PJWMfGhfflNSLndN66lcix7wnOh, file_token LNkpbkN16o82XsxSuyBcyfkCn87, url https://my.feishu.cn/file/LNkpbkN16o82XsxSuyBcyfkCn87) + bot 私聊(message_id om_x100b686623008cacb22d7b1d14f1208)」组合第十七次一次成功。lark-cli 1.0.82(1.0.84 可用, 不影响功能)。仍用 PowerShell 原生 Windows Set-Location 到工作区再上传,规避 Git Bash 双写盘符 MODULE_NOT_FOUND。
- 后续可轮换新角度所剩：真题大乱斗混合场景卷(A17 QPR2 多子系统叠加压轴综合题)、A18 桌面融合/跨设备 handoff 前瞻深挖(EU DMA 强制开放 11 项 AI 能力对 Framework 影响, CDM 锁屏屏幕自动化权限重写已落地 QPR2)、Perfetto SQL 范例库扩充(input 延迟/GPU 计数器/battery 耗电细分)。
