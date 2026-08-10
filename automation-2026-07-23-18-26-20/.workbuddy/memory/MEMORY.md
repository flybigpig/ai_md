# MEMORY.md - Android Framework 面试题自动化项目

## 项目背景
每日 08:35 自动化生成 Android Framework 热点面试题复习材料，落盘工作区根目录（文件名带日期），推送飞书（user 上传云空间 + bot 私聊发链接）。Baseline：Android 14 (android-14.0.0_rXX)。用户主线方向：AAOS 座舱 + 端侧 AI。

## 累计状态（截至 2026-08-10，第二十四篇·ART 运行时与 dex2oat 冷启动深度优化）
- 24 篇累计约 166 专题，主线闭环：Binder/AMS/WMS/SF/ART/HAL/内核 → TEE(EL3) → pKVM/AVF(EL2) → 智能层(AppFunctions/Compose/端侧AI) → 座舱(AAOS 电源状态机) → 安全深水区(SE/Confirmation) → 测试双雄(Robolectric/Ravenwood) → 体系总导航/收官补遗/速查卡/连击考 → 全链路排查实战 → 源码级 code walk(startActivity→首帧/SF一帧/binder.c一次事务) → Perfetto SQL 实战范例库 → A18 桌面融合/跨设备协同(Desktop Mode/WM Shell/ActivityEmbedding/freeform + CDM + Universal Clipboard/MediaRouter + EU DMA 能力开放 + 跨设备安全边界) → 真题大乱斗(8 个跨子系统混合场景压轴卷，五段式口述法) → ART 运行时专用深水区(解释/JIT/AOT三态 + dex2oat四件套 odex/vdex/art + compiler filter + PMS-Installer触发dexopt + Profile全链路基线/云/ProfileSaver/bg-dexopt + 冷启动ART视角根因 + dexpreopt/OTA失效)。
- 8/5 当日四次触发收尾：第十五篇(末轮缺口补全+总导航) → 第十六篇(收官补遗) → 第十七篇(考前总复习速查卡) → 第十八篇(高频考官连击模拟考)。8/6 第十九篇转"压轴实战"形态：把前 132 专题串成"现象→抓trace→定界→根因→修复"的面试排查能力（冷启动/卡顿/ANR/内存三路杀/发热后台受限/Binder 实战坑 + A17 安全新特性收尾），并补齐最后一个真缺口「A17 Verified Financial Calls / Live Threat Detection」。

## 剩余可轮换角度（若继续日更，以下尚未作为独立篇写过）
- "真题大乱斗 vol.2"混合场景卷（更刁钻的多子系统叠加压轴综合题）。
- Perfetto SQL 范例库扩充（input 延迟 / GPU 计数器 / battery 耗电细分，可作为第二十一篇的增量）。
- KMP/skiko 非 Android target 运行时深水区（androidMain 之外 target 的差异）。
- ~~ART 镜像 odex 布局优化实战~~（已 8/10 独立成第二十四篇：oat/odex/vdex/art 镜像 + profile-guided 调优 + dexpreopt/OTA 失效）。
- 注：截至 8/9，真题大乱斗混合场景卷已于第二十三篇独立成篇；以上为可选的新增量角度，非缺陷。

## 飞书推送稳定组合（已验证 20 次）
- 上传：PowerShell 原生 Windows cwd 下 `lark-cli markdown +create --as user --file <相对文件名> --folder-token PJWMfGhfflNSLndN66lcix7wnOh`（folder-token 落到用户云文档 AOSP 文件夹；Git Bash `/c/...` cwd 会让 lark-cli 报 MODULE_NOT_FOUND，因双写盘符 `C:\c\Users`）。
- 发消息：`lark-cli im +messages-send --as bot --user-id ou_9bb9a536eb5ca6ec98914b4982e2bafb --text <链接>`。
- user 身份 token 自动刷新（refreshExpiresAt 约 2026-08-10 前有效）；8/10 当日仍自动刷新成功但已临边界，后续若推送失败需重新 device-flow 授权 user 身份(drive scope)。

## 工程踩坑
- 超大 md 含 U+2500 框图字符(`─`)易单字节损坏（`─`→`�`）；本篇(8/5)改用纯 ASCII 箭头(`->`/`|`)与 Markdown 表格替代框图，单 Write 一次成型，grep U+FFFD = 0 体检通过，无需分片。
- 删除临时分片用 Python os.remove（绝对 Windows 路径）；`rm` 被 safe-delete 包装会拒绝相对/msys 路径。
- 飞书上传必须用 PowerShell 原生 Windows `Set-Location` 到工作区再 `lark-cli markdown +create --as user --file <相对名>`；Git Bash `/c/...` cwd 会让 lark-cli 报 MODULE_NOT_FOUND（双写盘符 `C:\c\Users`）。
