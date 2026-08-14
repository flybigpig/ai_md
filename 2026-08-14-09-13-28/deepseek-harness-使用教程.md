# DeepSeek Harness（`dsh`）使用教程

> 来源：`github.com/deepseek-ai/deepseek-harness`（已克隆到本工作区 `./deepseek-harness`）
> 文档基准：仓库 `README.md`、`docs/user/guide/*`、`docs/architecture.md`、`apps/cli/README.md`、`docs/development.md`
> 状态：项目处于 **开发者预览** 阶段，API/配置会有破坏性变更。

---

## 0. 这东西是什么

DeepSeek Harness（`dsh`）是 DeepSeek AI 开源的一个 **agent harness（智能体运行框架）**。
核心设计哲学是 **「一切皆插件」**，底层由 [Cordis](https://github.com/cordiverse/cordis) 驱动。

- 模型适配器、工具注册表、会话日志、agent 循环本身，全都是插件。
- 没有「特权核心」需要 patch，你通过挂载插件来扩展它；插件卸载时注册自动回滚。
- 一个运行的 `dsh` 是一棵「插件树」，在启动时按分层顺序组合：profile → bundle 层 → 用户 patch 层。

一句话：**它是类似 Claude Code / Codex 那种「能读改文件、跑命令、做计划」的 coding agent，但是以插件化、可组合的方式交付，并提供了 Web UI、CLI、Python SDK 三种入口。**

---

## 1. 环境要求

| 项 | 要求 |
|---|---|
| Node.js | **22.19+ 或 24+**（CI 覆盖 22.19 / 24 / 26） |
| 包管理器 | 启用 Corepack 的 **pnpm**（仓库 pin `pnpm@11.7.0`，`corepack enable` 即可） |
| Git | 2.26+ |
| API Key | 可选；用 Web / headless / Python SDK 跑真实任务时需要 DeepSeek（或兼容）API key |
| 系统 | Web/CLI 全平台；Python SDK 的 `jsonrpc-agent` 示例用持久 PTY，**不支持 Windows agent**（需 Linux/macOS 终端底层） |

> 仅想用 Web UI 跑任务：**装好 Node 即可**，不需要 pnpm，因为可以用 `npx` 直接拉。

---

## 2. 三种使用方式速览

| 方式 | 适用场景 | 命令入口 | 是否需要 pnpm |
|---|---|---|---|
| **Web UI**（推荐试用） | 交互式对话、可视化、点选配置模型 | `npx @deepseek-ai/dsh web` | 否 |
| **从源码运行** | 要改代码、开发插件、本地调试 | `pnpm dsh web` | 是 |
| **headless CLI** | 一次性跑一个任务、CI/脚本调用、无服务器 | `dsh --profile headless "任务"` | 是（源码）或装包后 |
| **Python SDK** | 把 agent 嵌进你自己的 Python 程序 | `from deepseek_harness import DeepSeekHarness` | 否（装 SDK 即可） |

---

## 3. 方式一：npx 启动 Web UI（最快上手）

装好 Node 后，直接一行：

```sh
npx @deepseek-ai/dsh web
```

- 首次会下载 `@deepseek-ai/dsh` 包并启动 Web 服务。
- 命令行会打印访问地址，默认 **`http://127.0.0.1:3080`**。
- 启动后按下面「Web UI 操作」三步走即可。

优点：零安装、不改本地环境。适合先体验。

---

## 4. 方式二：从源码运行（开发 / 定制）

适合你要读源码、写插件、改行为。已克隆到 `./deepseek-harness`：

```sh
cd deepseek-harness
pnpm install        # 装依赖 + 配置 lefthook hooks
pnpm run build      # tsc 发 lib/types + tsdown 打包运行时
pnpm dsh web        # 启动 Web UI（走 tsx ESM 源码启动）
```

常用校验（首次 clone 后跑一次确认环境 OK）：

```sh
pnpm run typecheck
```

> 注意：源码启动通过 `node --import tsx/esm` 走 ESM，所有被它 import 的模块必须保持 ESM（不能有 CJS-only 导出）。

---

## 5. Web UI 操作三步

Web UI 服务起来后：

### 5.1 配置模型
打开 **Settings → Models**，在 DeepSeek 卡片里填入你的 API Key 并保存。
- Key 是 **write-only**：保存后页面只持有脱敏描述符，不会回显明文。
- 明文保存在 **`$DSH_HOME/.credentials.yaml`**，设置里只保留凭据引用。
- 改模型配置 **无需重启服务器**，下一次请求即生效。

### 5.2 选择 workspace
点 **Choose workspace**，把启动 `dsh` 时的项目目录加进去并选中。
- 没选 workspace 前，会话输入框不可用。
- `dsh` 进程以「启动它的目录」作为默认文件系统根。

### 5.3 跑任务
开一个 session，发一句话，比如：

> Summarize this repository and identify its main packages.

agent 能：读/改 workspace 文件、跑命令、委派子任务、维护计划。
- 在「活动权限策略」下，需要审批的操作（如改文件、执行命令）Web UI 会弹窗询问。

---

## 6. 配置模型供应商（不止 DeepSeek）

打开 **Settings → Models** 页面：

- **加目录供应商**：点 `Add provider`，选 Anthropic / OpenAI 等，填 key 保存。目录自带 endpoint、协议、模型列表。
- **原生鉴权供应商**：Bedrock / Vertex / Azure / Codex 需要各自原生凭据（AWS 凭据+region、ADC 项目、`api-version`、OAuth），只填 API-key 字段配不起来。
- **自定义供应商**（公司网关 / 自托管 / 不在目录里的）：点 `Add a custom provider`，填：
  - 小写的 **Provider ID**（永久，被请求/会话/凭据引用使用；改名只能新增+删旧的）
  - display name、base URL、API protocol、credential、至少一个 model。

### 自定义供应商里支持图像输入
手填的模型默认按「纯文本」处理（端点不会告诉你能不能传图）。若你的网关支持视觉，需在 `$DSH_HOME/settings.yaml` 给该模型加 `input`：

```yaml
llm-pi-ai:
  providers:
    my-gateway:
      apiKeyEnv: GATEWAY_API_KEY
      api: openai-completions
      baseURL: https://gateway.example/v1
      models:
        - id: legacy-chat
        - id: vision-preview
          input: [text, image]   # 该模型单独声明支持图像
```

- 若所有手填模型都支持图像，省事写法是在 route 上加 `defaultInput: [text, image]`（fallback，不覆盖 catalog 已有声明）。
- `input` 取值：`text` / `image`；空列表等价于省略。

### 常见报错
- `MISSING_CREDENTIAL`：通过 Models 页存 key，或提供被引用的环境变量。
- `UNKNOWN_MODEL`：选一个已配置的模型，或把缺失模型加进自定义供应商。
- `Fetching available models returns 401`：检查 key；发现走 OpenAI 兼容 `GET /models`，不支持的端点就手动填模型。
- 图像被拒：该模型没声明 image 模态。DeepSeek 自家的 chat-completions 路由是纯文本，无法改。

---

## 7. 方式三：headless CLI 单次任务

适合脚本 / CI / 无头跑一个任务：

```sh
# 源码模式（已 pnpm install + build）
pnpm dsh --profile headless "Inspect the repository and fix the failing tests."

# 装包后通用形态
dsh --profile headless "run the tests"
```

行为：开一个全新的持久化 session，打印最终答案，然后退出。
- 需要 `DEEPSEEK_API_KEY`（源码/真实 API 模式下）。
- 调用目录即默认 workspace 根。
- `--profile web` 是 `dsh web` 的别名；`web` / `headless` 首次使用会从内置模板自动初始化。
- 查看实际启动的插件树（不真正启动）：`dsh --profile web --dump-config`。

查看启动树的其他开关：

```sh
dsh --profile web --dump-default-config   # 默认配置树
dsh --profile web --port 8080             # 把 web 端口改到 8080
dsh --profile web --help                  # web app 自身参数（不是 launcher 的）
dsh --help                                # launcher 自身帮助
```

---

## 8. 方式四：Python SDK（嵌进你自己的程序）

### 8.1 前置
- Python 3.10+
- Linux x64 / arm64，或 macOS 14+ arm64（示例用的持久 PTY 不支持 Windows agent）
- 一个 DeepSeek 兼容的 API endpoint + 凭据
- 一个隔离的、agent 可改的 workspace

### 8.2 安装 SDK
```sh
git clone https://github.com/deepseek-ai/deepseek-harness.git
cd deepseek-harness
python -m venv .venv
. .venv/bin/activate
python -m pip install deepseek-harness-sdk
```
> 装好的运行时 **不需要系统 Node.js**。从源码构建 wheel 见 `python/development.md`。

### 8.3 跑内置示例
```sh
export DEEPSEEK_API_KEY=sk-your-key-here
# export DEEPSEEK_BASE_URL=http://127.0.0.1:8000/v1   # 走 OpenAI 兼容代理时
# export DSH_MODEL=deepseek-v4-flash
# export DSH_SYSTEM_PROMPT='You are a helpful software engineer assistant.'

python examples/jsonrpc-agent/minimal.py \
  --workspace /absolute/path/to/workspace \
  --session-root /absolute/path/to/sessions \
  --session-id example-001 \
  "Inspect the repository and fix the failing tests."
```
脚本打印最终 assistant 回复；session 目录里落一份 JSONL 日志（含组装后的模型请求与工具调用）。

### 8.4 在自己的程序里调用
```python
from pathlib import Path
from deepseek_harness import DeepSeekHarness

config = Path("examples/jsonrpc-agent/minimal.cordis.yml").resolve()
workspace = Path("/absolute/path/to/workspace").resolve()
sessions = Path("/absolute/path/to/sessions").resolve()

with DeepSeekHarness(
    provider="deepseek-official",
    model="deepseek-v4-flash",
    max_tokens=49_152,
    cwd=str(workspace),
    session_root=str(sessions),
    cordis=str(config),
) as harness:
    result = harness.run(
        "Inspect the repository and fix the failing tests.",
        session_id="example-001",
    )

print(result.final_response)
```

要点：
- `DeepSeekHarness` 惰性启动并复用 bundled runtime，直到上下文管理器退出。
- 复用同一个 harness + 同一个 session_id，会保留该 session 拥有的持久 Bash 进程（含 cwd、导出变量、shell 函数）。
- 独立任务用新的 session_id；只有「要继续同一段对话 / 持久 shell 状态」才复用 id。

> ⚠️ 内置 `minimal` 组合用的文件系统权限是 `danger-full-access`，只能在一次性容器 / 可丢弃 checkout 里跑——Bash 和编辑器能改运行时进程可见的任意路径。

---

## 9. 核心概念速查（理解架构用）

| 概念 | 含义 |
|---|---|
| **Cordis** | 底层框架：插件向共享 `ctx` 贡献 service、类型化事件、可逆 effect。 |
| **Plugin（插件）** | 一切都是插件。贡献通过 `ctx.effect()` / `ctx.on()`，注册即副作用，卸载即回滚。 |
| **Profile（配置档）** | 命名组合，存于 `$DSH_HOME/profiles/<name>`；列出它堆叠的 bundle、安装的外挂插件、用户自己的 `cordis.patch.yml`。`web` / `headless` 是内置模板。 |
| **Bundle（束）** | Cordis 配置行 + 挂载代码的发布格式，可被打其上的层 patch。 |
| **Capability seam（能力缝）** | 可替换能力，三件套：Service Definition（接口）/ Service Provider（实现）/ Consumer（使用，常是面向模型的工具）。只写一个角色不算缝。 |
| **Session event** | 追加到日志的 durable 事实（如 `user/message`、`assistant/*`、`tool/*`）。模型所见 = 可从日志重建。 |
| **Agent event** | `agent/*` 携带活着的 `Agent`：inbox、step、status、request 等，用于观察/拦截在途工作。 |
| **Waterfall 事件** | 监听器必须调 `next()` 才能向下委托；不调会短路整条链（`agent/pre-step`、`agent/request`、`llm/stream`、`tools/*`）。 |
| **Turn / Step** | 一个 **step** = 一次模型请求 + 它调用的工具；一个 **turn** = 0..n 个 step。 |
| **`$DSH_HOME`** | Harness 主目录，存 profile、凭据（`.credentials.yaml`）、设置（`settings.yaml`）、patch（`cordis.patch.yml`）。 |

分层组合顺序（从空根开始）：profile 列出的每个 bundle（按序）→ profile 的 `cordis.patch.yml` → home 级的 `$DSH_HOME/cordis.patch.yml` → `--patch` 覆盖层。

`dsh-base` 是每个 profile 的第一层（模型适配器、工具、持久化、沙箱、审批策略、设置、凭据、遥测）；`dsh-web-app` 加浏览器应用；`dsh-headless` 加零服务器的单次运行器。

---

## 10. 常用命令与排查

```sh
# 启动
npx @deepseek-ai/dsh web          # 最快用 Web UI
pnpm dsh web                      # 源码 Web UI
dsh --profile headless "task"     # 无头单次任务

# 查看配置树（不启动）
dsh --profile web --dump-config
dsh --profile web --dump-default-config

# 源码开发常用（AGENTS 面向仓库贡献者）
pnpm install / build / typecheck / lint
pnpm run test / test:coverage / test:e2e / test:snapshot
pnpm run doc-sync / website:build
```

排查清单：
- Web UI 发不出请求 → 先看 Models 页是否配了 key / 选了 model（默认若指向已删供应商会卡在 `Select model`）。
- headless 报错缺凭据 → 设 `DEEPSEEK_API_KEY`；走代理设 `DEEPSEEK_BASE_URL`。
- 图像被拒 → 见第 6 节 `input: [text, image]`。
- 自定义网关 `GET /models` 401 → 手动填模型列表。

---

## 11. 安全与权限

- **API Key 绝不提交**：CI 的 e2e 在没 key 时自动 skip。
- 凭据明文落在 `$DSH_HOME/.credentials.yaml`，设置里只留引用。
- headless / Python `minimal` 示例默认 `danger-full-access`：Bash 与编辑器可改运行时可见的任意路径，**只在容器 / 可丢弃环境跑**。
- Web UI 在活动权限策略下，对需审批的操作会先询问，不会悄悄执行。

---

## 12. 延伸阅读（仓库内）

- 中文 Web UI 指南：`docs/user/guide/index.zh.md`
- 模型配置：`docs/user/guide/providers.zh.md`
- Python SDK：`docs/user/guide/python-sdk.zh.md`
- 架构：`docs/architecture.zh.md`
- 开发指南：`docs/development.zh.md`
- CLI 参考：`apps/cli/README.zh.md`
- 插件开发 cookbook：`docs/cookbook/`（加工具 / 加 LLM 适配器 / 加 Chat 节点 / 加 package）

> 提示：所有 `*.zh.md` 是官方中英双语文档的中文版，和英文版一一对应。
