# android-inapp-agent —— 设备内 Android UI Agent

一个**运行在 Android 设备内部**的 UI 自动化 Agent（Kotlin）。它不依赖主机 ADB，
而是用 **AccessibilityService** 直接感知并操作本机，由 LLM（本地或远程，OpenAI 兼容）
驱动「感知 → 决策 → 行动」回环。

> 这是 `android-agent-harness`（主机侧 ADB 版）的“掉头”版本：把控制面从 PC 搬到设备里，
> 更贴近系统内部、可独立在手机上运行。两个工程可并存，互不依赖。

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│  Android 设备                                                   │
│                                                                │
│  MainActivity (UI: 设目标/启动/停止/日志)                       │
│        │ 写 AgentState (goal / baseUrl / model / vision)        │
│        ▼                                                        │
│  AgentService (前台服务, 跑回环)                                │
│        │                                                        │
│        ├─ LlmClient ──► OpenAiLlmClient (http://127.0.0.1:8081) │
│        │             └─ MockLlmClient (无模型调试)             │
│        │                                                        │
│        └─ AgentLoop (感知-决策-行动)                            │
│              │ 工具调用                                          │
│              ▼                                                  │
│        ToolRegistry → DeviceTools ──► AgentAccessibilityService │
│                                         ├ getRootInActiveWindow │
│                                         │   → Perception (节点树)│
│                                         ├ dispatchGesture (点击/滑动)│
│                                         ├ performGlobalAction (BACK/HOME)│
│                                         ├ 节点 ACTION_SET_TEXT (填字)│
│                                         ├ openApp (启动 App)    │
│                                         └ MediaProjection (截图)│
└──────────────────────────────────────────────────────────────┘
```

## 关键组件

| 文件 | 职责 |
|---|---|
| `AgentAccessibilityService.kt` | 核心“眼+手”：节点树感知、手势/节点动作、系统键、启动 App、MediaProjection 截图 |
| `perception/Perception.kt` + `UiNode.kt` | 把 `AccessibilityNodeInfo` 树转成确定性索引的扁平可交互节点列表（供 LLM 引用） |
| `tools/` | `Tool`/`ToolRegistry`/`Schema` + `DeviceTools`（9 个工具：get_ui/tap_index/tap_xy/swipe/input_text/press_key/open_app/screenshot/finish） |
| `llm/` | `LlmClient` 抽象 + `OpenAiLlmClient`（function calling，带文本 JSON 兜底）+ `MockLlmClient`（启发式 grounding，免模型验证回环） |
| `agent/AgentLoop.kt` | 回环主逻辑，兼容标准 tool_calling 与本地模型的文本 JSON 动作 |
| `agent/AgentService.kt` | 前台服务，承载回环生命周期 |
| `ui/MainActivity.kt` | 控制界面：目标/端点/模型、启动停止、日志流、无障碍与截屏授权引导 |

## 感知与动作的底层要点（Framework 视角）

- **感知**：`AccessibilityService.getRootInActiveWindow()` 返回当前窗口的
  `AccessibilityNodeInfo` 树。我们在 `Perception` 里按**先序确定性索引**抽取“可提及节点”
  （可点击/可编辑/可滚动/可勾选/有文本叶节点），既给 LLM 结构化上下文，又让 `tap_index`
  能在动作阶段**重新遍历对齐**到同一节点（不持有会过期的 node 引用）。
- **动作（手势）**：`dispatchGesture(GestureDescription, callback, handler)` 是
  API 24+ 跨 App 点击/滑动的可靠手段，比 `performAction(ACTION_CLICK)` 更通用。
- **动作（填字）**：对 editable 节点 `performAction(ACTION_SET_TEXT, Bundle{ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE})`。
- **动作（系统）**：`performGlobalAction(GLOBAL_ACTION_BACK/HOME/RECENTS/NOTIFICATIONS)`。
- **截图（可选 grounding）**：`MediaProjection` + `VirtualDisplay` + `ImageReader` 取帧，
  供视觉模型做像素级 grounding（set-of-marks）。

## 构建 / 安装

需要：Android SDK（platform-34 + build-tools 34）+ JDK 17+。本机已具备。

```bash
cd android-inapp-agent
./gradlew assembleDebug          # 产物: app/build/outputs/apk/debug/app-debug.apk

# 安装到已连接设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 运行

1. 打开 App → 点「开启无障碍服务」→ 在系统「无障碍」里启用 **设备内 Agent**。
2. 填目标（如“打开设置并进入关于手机”），可选勾选 Mock（免模型先验证回环）。
3. 点「启动 Agent」。若勾选“视觉 grounding”，会先弹系统截屏授权。
4. 日志区实时显示回环步骤。

### 接本地模型（如 llama.cpp / ollama）

让设备访问主机 8081（需主机先起 `/v1/chat/completions` 兼容端点）：

```bash
adb reverse tcp:8081 tcp:8081     # 设备 127.0.0.1:8081 → 主机 8081
```

App 里 base-url 填 `http://127.0.0.1:8081/v1`，模型名按你的本地模型填。
**很多量化小模型的 tool calling 不稳**：AgentLoop 会自动兜底——若返回的是纯文本，
则尝试解析其中 `{"tool":"...","args":{...}}` 形式的 JSON 动作。

## 与主机侧 harness 的关系

- `android-agent-harness`：PC 经 ADB 驱动设备，适合调试/CI/可观测。
- `android-inapp-agent`：设备内自闭环，无需 PC，更贴近系统、可离线跑。
- 二者共用同一套「Tool 抽象 + LLM 兼容协议 + 回环纪律」的设计，可互相借鉴。

## 已知边界 / 下一步

- **多模态回灌**：`screenshot` 工具已能取到 PNG 字节，但尚未把图片作为 image_url
  回灌给视觉模型（需扩展 `ChatMessage` 支持 content 数组）。这是「像素级 grounding」的下一步。
- `open_app` 按 label 反查依赖 `QUERY_ALL_PACKAGES`（开发/调试场景 OK，上架需申述）。
- `dispatchGesture` 在部分定制 ROM / 锁屏 / 安全键盘下可能受限。
