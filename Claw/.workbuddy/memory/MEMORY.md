# Claw 工作区长期记忆

## 项目方向
AOSP / Android Framework 内核级代码研究,目标版本 Android 14(API 34, UpsideDownCake),内核 GKI android14-6.1。
编译宿 Linux,模拟器 sdk_phone_x86_64 验证,国内走清华镜像。

## 用户偏好(已多次验证)
- 要深度,不要泛泛;给真实 AOSP 文件路径 + 方法名,必要时配图(SVG / Mermaid)。
- 最终以落盘 md 为准(早期说不用写文档,后改为明确要 md)。
- 喜欢可直接套的产物:编译指南、patch 文件、可 apply 的 diff、系统 app 模板。
- 中文交流,硬核技术向。

## 已交付文档
- android14_build.md — AOSP 14 编译指南
- binder_aidl.md — Binder IPC + AIDL
- ams_modify_practice.md + ams_patches/ — AMS/ATMS 修改实战 + patch
- android_framework_paper.md — Framework 综述
- settings_modify_practice.md — Settings 修改实战
- framework_settings_analysis.md — Settings 子系统架构分析(实战篇互补)
  - 修正:运行时存储是 XML(SettingsState+AtomicFile),非 SQLite;SQLite 仅迁移用。
  - Source 优先级:DEVICE_OVERRIDE > CONFIG > DEVICE > SYSTEM > DEFAULT。

## HAL 知识梳理(2026-07-20 对话)
- Android 14 HAL = AIDL HAL(hardware/interfaces/*/aidl/),HIDL 冻结退场。
- hwservicemanager 在 Android 14 仍存在(/vendor/bin/hwservicemanager,域 /dev/hwbinder),仅服务遗留 HIDL;新 AIDL HAL 注册到标准 servicemanager(域 /dev/binder)。servicemanager 与 vndservicemanager 在 Android 12 起合并为同一二进制(system/bin/servicemanager,参数区分)。三个 binder 域: binder(system+AIDL HAL)/hwbinder(HIDL)/vndbinder(vendor↔vendor)。
- 关键版本:Android 8.0 Treble+HIDL+hwbinder+VINTF;10 HIDL 弃用并入 AIDL;11 Stable AIDL 正式支持 HAL(aidl_interface+stability:vintf,hidl2aidl);12 servicemanager/vndservicemanager 二进制合并;13 HIDL 冻结;14 AIDL HAL 为现行标准。
- VINTF(system/libvintf/)做 Treble 契约校验:vendor manifest vs framework compatibility matrix。
- HAL server 由 init 从 vendor/*.rc 拉起 → AIBinder_registerService → servicemanager::do_add_service(VINTF+SELinux 双检查)。
- binder 三节点现状:Android 14 实际仍三个 /dev 节点(binder/hwbinder/vndbinder),同驱动实例。
- 架构图已内联绘制(四层 + Treble 边界 + vibrator 调用链示例)。

## Android 17 关注点(2026-07-27 起)
- 用户开始关注 Android 17(API 37);当前工作区仍基于 AOSP 14。深扒 17 需另 checkout `android-17.0.0_rXX` 分支(内核 GKI 对应 android17-xx)。
- 关键变更落地点(frameworks/base):ActivityManager/ActivityTaskManager(大屏方向限制移除、config change 默认不重启 Activity + 新 `recreateOnConfigChanges`)、AppFunctionsManager 系统服务(on-device MCP)、ProfilingManager(异常检测)、keystore2(ML-DSA 后量子签名)。
