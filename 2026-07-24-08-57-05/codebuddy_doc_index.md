# WorkBuddy 工作区文档全局索引

> 生成时间：2026-07-24
> 范围：`WorkBuddy/` 下全部用户产出文档（`*.md`，不含系统 skills 与内部 `.workbuddy` 工作记忆）
> 统计：共 **59** 篇工作区内容文档（不含本索引文件），约 **19,500** 行
> 用途：按主题分类归档，便于检索；末尾附「重复/分散提示」与「合并建议」

---

## 目录（按主题）

- [A. 总览 / 体系索引 / 综述](#a-总览--体系索引--综述)
- [B. 启动 / 进程 / AMS](#b-启动--进程--ams)
- [C. Binder / IPC / AIDL](#c-binder--ipc--aidl)
- [D. WMS / 窗口 / Input / SystemUI](#d-wms--窗口--input--systemui)
- [E. HAL / Treble](#e-hal--treble)
- [F. 图形 / 音频](#f-图形--音频)
- [G. Settings / SELinux / 安全](#g-settings--selinux--安全)
- [H. 构建 / 编译 / 调试 / 工具](#h-构建--编译--调试--工具)
- [I. 车机 / BSP / 高通 8295](#i-车机--bsp--高通-8295)
- [J. Android Agent / AI 工具链](#j-android-agent--ai-工具链)
- [K. C++ 语言](#k-c-语言)
- [L. Rust 驱动](#l-rust-驱动)
- [M. 面试题 / 练习题（自动化产出）](#m-面试题--练习题自动化产出)
- [附：重复 / 分散提示与合并建议](#附重复--分散提示与合并建议)

> 路径均为相对 `WorkBuddy/` 的相对路径；行数为 `wc -l` 实测。

---

## A. 总览 / 体系索引 / 综述

| 文档 | 路径 | 行数 | 说明 |
|------|------|------|------|
| AOSP14 Framework 专家总览 | `2026-07-10-09-16-20/aosp14-framework-expert.md` | 179 | Framework 整体能力地图 |
| AOSP 系统模块分析 | `2026-07-10-09-16-20/aosp-system-modules.md` | 183 | 系统模块拆分 |
| AOSP AM/WM 核心分析 | `2026-07-10-09-16-20/aosp-am-wm-core-analysis.md` | 226 | ActivityManager / WindowManager 核心 |
| AOSP repo 分析 | `2026-07-10-09-16-20/aosp-repo-analysis.md` | 312 | 代码仓库 / repo 管理 |
| AOSP Harness 博客摘要 | `2026-07-10-09-16-20/aosp-harness-blog-summary.md` | 196 | Agent Harness 相关博文整理 |
| Android Framework 体系索引 | `2026-07-14-10-47-47/20260714_Android_Framework_体系索引.md` | 111 | 体系化索引页 |
| Framework 笔记总导出 | `Claw/framework_notes_export.md` | **4280** | 巨型笔记汇总（最大单文件） |
| AOSP14 Framework 索引 | `Claw/framework_index_aosp14.md` | 171 | Claw 工作区索引 |
| Android Framework 论文/综述 | `Claw/android_framework_paper.md` | 221 | 综述性长文 |

## B. 启动 / 进程 / AMS

| 文档 | 路径 | 行数 | 说明 |
|------|------|------|------|
| AMS 深入解析 | `Claw/ams_deep_dive.md` | 329 | ActivityManagerService 内部机制 |
| AMS 修改实践 | `Claw/ams_modify_practice.md` | 162 | 改 AMS 的实操记录 |
| AMS 进程调度与 LMK | `2026-07-14-10-47-47/20260714_AMS_进程调度与_LMK_深度解析.md` | 217 | 进程调度 / LowMemoryKiller |
| init/Zygote/App 进程孵化 | `2026-07-14-10-47-47/20260714_init_Zygote_App_进程孵化体系.md` | 261 | 进程孵化全链路 |
| startActivity 全链路 | `2026-07-14-10-47-47/20260714_startActivity_全链路深度解析.md` | 362 | 启动 Activity 完整调用链 |

## C. Binder / IPC / AIDL

| 文档 | 路径 | 行数 | 说明 |
|------|------|------|------|
| Binder / AIDL 基础 | `Claw/binder_aidl.md` | 252 | Binder 与 AIDL 入门 |
| 系统服务 AIDL | `Claw/system_service_aidl.md` | 65 | 系统服务侧 AIDL |
| Binder IPC 与驱动层 | `2026-07-14-10-47-47/20260714_Binder_IPC_与_驱动层深度解析.md` | 236 | IPC + 驱动层 |
| Binder 驱动全链路详解 | `2026-07-23-18-42-42/Binder驱动全链路详解.md` | 395 | 驱动层完整链路（新版） |
| Binder/AIDL/HAL ONEWAY FREEZE | `2026-07-23-18-42-42/Binder_AIDL_HAL_ONEWAY_FREEZE.md` | 293 | oneway 与冻结问题分析 |

## D. WMS / 窗口 / Input / SystemUI

| 文档 | 路径 | 行数 | 说明 |
|------|------|------|------|
| WMS 深入解析 | `Claw/wms_deep_dive.md` | 55 | WindowManagerService |
| Input 深入解析 | `Claw/input_deep_dive.md` | 43 | 输入系统 |
| SystemUI 定制 | `Claw/systemui_customization.md` | 38 | SystemUI 客制化 |
| WMS 窗口管理机制 | `2026-07-14-10-47-47/20260714_WindowManagerService_窗口管理机制深度剖析.md` | 238 | 窗口管理深度 |
| IMS 输入事件分发 | `2026-07-14-10-47-47/20260714_IMS_输入事件分发链路深度解析.md` | 261 | 输入事件分发链 |

## E. HAL / Treble

| 文档 | 路径 | 行数 | 说明 |
|------|------|------|------|
| HAL Android14 | `Claw/hal_android14.md` | 158 | HAL 基础 |
| HAL 示例 Android14 | `Claw/hal_example_android14.md` | 347 | HAL 实现示例 |
| HAL 学习路线 | `Claw/hal_learning_roadmap.md` | 104 | 学习路线图 |
| HAL 版本历史 | `Claw/hal_version_history.md` | 137 | HAL 版本演进 |
| HAL LED 示例 | `Claw/hal_led_example/README.md` | 65 | LED HAL 示例工程 |
| HAL 与 Treble | `2026-07-14-10-47-47/20260714_HAL_与_Treble_硬件抽象层.md` | 185 | Treble 架构 |

## F. 图形 / 音频

| 文档 | 路径 | 行数 | 说明 |
|------|------|------|------|
| SurfaceFlinger 图形合成 | `2026-07-14-10-47-47/20260714_SurfaceFlinger_图形合成流程全解析.md` | 338 | 图形合成全链路 |
| AudioFlinger / AudioPolicy | `2026-07-14-10-47-47/20260714_AudioFlinger_与_AudioPolicyService_音频框架解读.md` | 258 | 音频框架 |

## G. Settings / SELinux / 安全

| 文档 | 路径 | 行数 | 说明 |
|------|------|------|------|
| Framework Settings 分析 | `Claw/framework_settings_analysis.md` | 186 | Settings  Provider 分析 |
| Settings 修改实践 | `Claw/settings_modify_practice.md` | 268 | 改 Settings 实操 |
| SELinux 策略 | `Claw/selinux_policy.md` | 64 | SELinux policy |

## H. 构建 / 编译 / 调试 / 工具

| 文档 | 路径 | 行数 | 说明 |
|------|------|------|------|
| Android14 构建 | `Claw/android14_build.md` | 581 | Android14 编译 |
| AOSP 构建指南 | `Claw/aosp-build-guide.md` | 321 | 构建总指南 |
| App→Framework 改造指南 | `Claw/app_to_framework_guide.md` | 485 | 应用层改 Framework |
| Perfetto / ANR 排查 | `Claw/perfetto_anr_troubleshooting.md` | 41 | 性能/ANR 排查 |
| Git 命令合集 | `Claw/git-命令合集.md` | 211 | Git 速查 |
| Framework 调试实战 | `2026-07-14-10-47-47/20260714_Framework_调试实战.md` | 361 | 调试方法论 |
| Framework 开发实战 | `2026-07-14-10-47-47/20260715_Framework_开发实战.md` | 464 | 开发实战 |

## I. 车机 / BSP / 高通 8295

| 文档 | 路径 | 行数 | 说明 |
|------|------|------|------|
| Android BSP 开发详解 | `2026-07-23-18-42-42/Android_BSP开发详解.md` | 346 | BSP 开发 |
| 高通 8295 车机开发指南 | `2026-07-14-16-31-46/qcom-8295-auto-dev-guide.md` | 280 | QCOM 8295 车机 |
| 高通 8295 证书 | `2026-07-14-16-31-46/qcom-8295-certificates.md` | 132 | 证书相关 |
| 8295 Car Demo | `2026-07-14-16-31-46/8295-car-demo/README.md` | 88 | Demo 工程说明 |

## J. Android Agent / AI 工具链

| 文档 | 路径 | 行数 | 说明 |
|------|------|------|------|
| Android AOSP AI Agent | `2026-07-15-15-28-31/android-aosp-ai-agent.md` | 214 | AI Agent 方案 |
| Agent Harness 说明 | `2026-07-15-15-28-31/android-agent-harness/README.md` | 88 | Harness 工程 |
| In-App Agent 说明 | `2026-07-15-15-28-31/android-inapp-agent/README.md` | 105 | 应用内 Agent 工程 |

## K. C++ 语言

| 文档 | 路径 | 行数 | 说明 |
|------|------|------|------|
| C++20 详解 | `2026-07-21-16-54-48/C++20详解.md` | 445 | C++20 特性 |
| C++ 各版本详解 | `2026-07-21-16-54-48/C++各版本详解.md` | 280 | 版本演进 |
| C++ 四阶能力图谱 | `2026-07-21-16-54-48/C++四阶能力图谱.md` | 211 | 能力分级 |

## L. Rust 驱动

| 文档 | 路径 | 行数 | 说明 |
|------|------|------|------|
| Rust 驱动分析 | `2026-07-23-18-18-23/rust_drivers_analysis.md` | 732 | Rust for Linux 驱动 |

## M. 面试题 / 练习题（自动化产出）

| 文档 | 路径 | 行数 | 说明 |
|------|------|------|------|
| Framework 面试题（基础） | `automation-2026-07-23-18-26-20/Android_Framework面试题_2026-07-23.md` | 775 | 自动化生成 |
| Framework 面试题（深挖） | `automation-2026-07-23-18-26-20/Android_Framework面试题_深挖篇_2026-07-23.md` | 523 | 自动化生成 |
| Framework 面试题（热点拓展） | `automation-2026-07-23-18-26-20/Android_Framework面试题_热点拓展_2026-07-23.md` | 630 | 自动化生成 |
| Framework 面试题（图形多媒体通信） | `automation-2026-07-23-18-26-20/Android_Framework面试题_图形多媒体通信篇_2026-07-24.md` | 367 | 自动化生成 |
| 公务员事业编练习题（日更） | `automation-2026-07-23-18-27-25/公务员事业编练习题_2026-07-24.md` | 314 | 自动化生成 |
| 公务员事业编练习题（20道） | `automation-2026-07-23-18-27-25/公务员事业编练习题_20道.md` | 310 | 自动化生成 |

---

## 附：重复 / 分散提示与合并建议

### 1. 同主题跨多工作区分散
- **Binder/IPC**：`Claw/binder_aidl.md`、`Claw/system_service_aidl.md`、`2026-07-14/20260714_Binder_IPC_与_驱动层深度解析.md`、`2026-07-23/Binder驱动全链路详解.md`、`2026-07-23/Binder_AIDL_HAL_ONEWAY_FREEZE.md` —— 5 篇，建议以 `2026-07-23/Binder驱动全链路详解.md`（最全，395 行）为主版本，其余并入或标注「已合并」。
- **HAL/Treble**：`Claw/hal_*.md`（5 篇）+ `2026-07-14/HAL_与_Treble`。Claw 侧偏示例/路线，07-14 偏架构，可保留但需在索引注明关系。
- **体系索引**：`Claw/framework_index_aosp14.md`、`2026-07-14/体系索引`、`Claw/framework_notes_export.md` 三套索引并存，建议统一指向 `framework_notes_export.md` 作为唯一总入口。
- **WMS**：`Claw/wms_deep_dive.md`（仅 55 行）与 `2026-07-14/WMS`（238 行）内容互补，可合并。

### 2. 大文件风险提示
- `Claw/framework_notes_export.md`（4280 行）是历史笔记一次性导出，建议拆分为按子系统的小文件，或仅作为归档只读副本。

### 3. 自动化产出归类
- `automation-2026-07-23-18-26-20/`（Framework 面试题 ×4）与 `automation-2026-07-23-18-27-25/`（公务员练习题 ×2）为定时任务产物，与 AOSP 技术主线无关，建议单独归档到 `面试/公考` 类目录，避免污染技术检索。

### 4. 建议的物理整理动作（待你确认后执行）
1. 新建 `Claw/notes/` 统一收纳分散的早期 workspace 文档（07-10、07-14 同主题篇目）。
2. 上述重复主题合并为单篇「主文档 + 变更记录」。
3. 自动化产物迁至独立顶层目录（如 `WorkBuddy/auto-output/`）。
4. 本索引文件 `codebuddy_doc_index.md` 置于 `Claw/`，作为常驻检索入口，文档变动后同步更新。

---

*说明：本索引仅做分类与检索，未移动/删除任何文件。如需执行「物理整理」，请确认上节建议后再操作。*
