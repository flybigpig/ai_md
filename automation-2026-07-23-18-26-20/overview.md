# 第三十八篇 Android Framework 面试复习 · 概览

## 完成内容
- 产出 `Android_Framework面试题_Jetpack架构组件底层深挖与查缺补漏_2026-08-22.md`，填补系列此前从未独立成篇的 **Jetpack 非 Compose 架构组件底层**真缺口（命中用户列出的 "Jetpack/Compose 底层机制" 中 Compose 之外那一半）。
- 5 大专题全部带 AOSP 14 / AndroidX 源码路径佐证：
  1. **Lifecycle 状态机**：`LifecycleRegistry.sync()` 正反 pass、`ReportFragment`(<29)/`ActivityLifecycleCallbacks`(≥29) 桥接、晚注册 observer 的「事件回放」。
  2. **LiveData 粘性**：`mVersion`/`mLastVersion` 补发原理、`postValue` 时序陷阱、`observeForever` 泄漏。
  3. **ViewModel / SavedState**：`ViewModelStore` 在 Activity 级、旋转不死靠 `onRetainNonConfigurationInstance`、`onCleared` 仅 finish 触发、`SavedStateHandle` 跨进程恢复。
  4. **协程调度**：`Dispatchers.Main = HandlerContext(Looper.main)` 封装、`Main.immediate` 同步执行、IO/Default 共用 `CoroutineScheduler`。
  5. **RecyclerView 四级缓存**：scrap / cachedViews(2) / RecyclerPool(5) / ViewCacheExtension、`GapWorker` 借 Choreographer idle 预取、`getAdapterPosition` vs `getLayoutPosition`。
- 附：查缺补漏 ASCII 连接图、易错红榜 TOP18、三条高频追问链、AOSP/AndroidX 路径清单、与 37 篇的交叉索引。累计约 **241 专题**闭环。

## 关键修改 / 决策
- 修正两处笔误：`Saved10StateHandle` → `SavedStateHandle`、`onIn,active()` → `onInactive()`（已校验 U+FFFD=0）。
- 推送改用 bash 直接传绝对 Windows 路径，规避 Git Bash 双写盘符 `MODULE_NOT_FOUND`。

## 推送结果
- 飞书云空间（AOSP 文件夹）：file_token `VpEsbjU4joaCkExVjAFcdBqAnSd`，链接 https://my.feishu.cn/file/VpEsbjU4joaCkExVjAFcdBqAnSd
- 飞书私聊（bot）：message_id `om_x100b67bb4500f4a0b26f708c984896a`，已发送链接。

## 后续可选项
- 真题大乱斗 vol.3（更刁钻多子系统叠加）
- KMP/Swift Export 实战坑下钻
- A18 Aluminium OS 桌面融合对 WMS/CDM 的源码重构走读
