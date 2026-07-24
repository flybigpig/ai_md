# android-agent-harness

主机侧 Kotlin/JVM Agent Harness:经 **ADB** 驱动 Android 真机/模拟器,构建「截图 + UI 树 → LLM 决策 → 动作」的感知-行动回环。把一台设备当作 computer-use 目标交给 LLM 操控。

## 架构

```
                         ┌─────────────────────────────┐
                         │           AgentLoop          │  感知-决策-行动回环
                         │  system+goal → LLM → tools   │
                         └───────────┬─────────────────┘
                        LlmClient    │        ToolRegistry
                 ┌───────────────────┴───┐   ┌────────┴──────────┐
                 │ MockLlmClient(回放/启发)│   │  DeviceTools       │
                 │ OpenAiLlmClient(兼容端点)│   │  get_ui/tap/swipe  │
                 └───────────────────────┘   │  input/key/open/…  │
                                             └────────┬──────────┘
                                                 Device (感知+动作)
                                                      │ AdbClient
                                                  adb exec / shell
                                                      │
                                              Android 设备(USB/TCP)
```

模块划分:

| 包 | 职责 |
|---|---|
| `adb` | `AdbClient` —— 封装 `adb` 进程调用(exec / shell / devices) |
| `device` | `Device` 感知(截图、`uiautomator dump`、屏幕信息)+ 动作(tap/swipe/input/key/openApp);`UiNode`/`UiHierarchyParser` UI 树模型与 XML 解析 |
| `tools` | `Tool`/`ToolRegistry` 工具抽象;`DeviceTools` 把设备能力封装成 LLM 可调用工具;`Schema` 生成 JSON Schema |
| `llm` | `LlmClient` 抽象;`MockLlmClient`(脚本回放 / 启发式);`OpenAiLlmClient`(OpenAI 兼容,含 function calling) |
| `agent` | `AgentLoop` 主回环;`Prompts` 系统提示词 |

## 前置条件

- JDK 21
- Android SDK platform-tools(`adb` 可用),设备已开 USB 调试
- (可选)一个 OpenAI 兼容端点,例如本地 `llama.cpp server`(`http://127.0.0.1:8081/v1`)

## 运行

先跑通回环(Mock,不接真实模型;`get_ui` 仍需连一台设备):

```bash
./gradlew run --args="--goal '打开设置' --mock --adb 'C:\D\SDK\platform-tools\adb.exe'"
```

接本地 llama.cpp(OpenAI 兼容):

```bash
./gradlew run --args="--goal '打开设置并进入关于手机' \
  --base-url http://127.0.0.1:8081/v1 --model qwen \
  --adb 'C:\D\SDK\platform-tools\adb.exe'"
```

### 命令行参数

| 参数 | 说明 | 默认 |
|---|---|---|
| `--goal <text>` | 任务目标(必填) | —— |
| `--mock` | 使用 Mock LLM | 未提供 `--base-url` 时自动启用 |
| `--adb <path>` | adb 路径 | `ADB_PATH` 环境变量或 `adb` |
| `--serial <serial>` | 指定设备 | 单设备可省 |
| `--base-url <url>` | OpenAI 兼容端点 | —— |
| `--api-key <key>` | Bearer key(本地服务可省) | `OPENAI_API_KEY` |
| `--model <name>` | 模型名 | `local-model` |
| `--max-steps <n>` | 最大步数 | 25 |
| `--out <dir>` | 截图/输出目录 | `./agent-out` |

## 测试

```bash
./gradlew test
```

`AgentLoopTest` 用内存工具验证回环逻辑(不依赖真机)。

## 已实现的工具

`get_ui`(读界面元素)、`screenshot`、`tap_index`、`tap_xy`、`swipe`(方向/坐标)、`input_text`、`press_key`(back/home/enter/delete)、`open_app`、`finish`。

## 后续可扩展

- 多模态:把 `screenshot` 的 PNG 以 image content 传给视觉模型,做基于像素的 grounding。
- 更强 grounding:给 `get_ui` 元素叠加可点区域标注(set-of-marks)。
- 无障碍事件:改用 Accessibility 服务获取更实时的界面变化。
- 记忆/反思:在回环中加入子目标规划与失败重试策略。
