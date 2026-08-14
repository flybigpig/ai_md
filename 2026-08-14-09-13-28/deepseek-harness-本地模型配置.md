# DeepSeek Harness 配置本地模型教程

> 适用：Ollama / vLLM / LM Studio / llama.cpp server / 任何 OpenAI 兼容端点
> 基准：`packages/llm/llm-pi-ai/README.md`、`docs/user/guide/providers.md`
> 结论先行：**本地模型 = 一个「自定义 OpenAI 兼容供应商」**，通过 `llm-pi-ai` 适配器的 hand-declared route 接入，协议固定 `openai-completions`。

---

## 0. 工作原理（先看这个，少踩坑）

- `dsh` 的 LLM 层由 `@deepseek-ai/dsh-llm-pi-ai` 驱动（底层 `@earendil-works/pi-ai`）。
- 一个「route（路由/供应商）」= 一个端点 + 协议 + 模型列表。`openai-completions` 协议就是 OpenAI Chat Completions 兼容形态，本地服务（Ollama、vLLM…）全都是这个。
- **关键坑（官方文档明写）**：pi-ai 的 OpenAI 兼容实现仍然要求一个 API key 或 `Authorization` 头。所以**本地无鉴权的服务也必须给一个占位凭据**——否则请求直接 `MISSING_CREDENTIAL`。解决方式二选一：
  1. `apiKeyEnv` 指向一个环境变量（值随便填，如 `ollama`），运行时 `export OLLAMA_API_KEY=ollama`；或
  2. 在 `headers` 里写 `Authorization: Bearer <任意非空串>`。
- `DSH_HOME` 默认 `~/.dsh`（Windows：`%USERPROFILE%\.dsh`）。`settings.yaml`、`.credentials.yaml`、`profiles/` 都在这里。

---

## 1. 方法 A：Web UI 添加（最直观）

1. 启动 Web UI：`npx @deepseek-ai/dsh web`（默认 `http://127.0.0.1:3080`）。
2. **Settings → Models → Add a custom provider**，填：
   - **Provider ID**：小写永久 id，例如 `ollama`、`vllm`（改名只能删了重建）。
   - **Display name**：展示名，随意。
   - **Base URL**：本地端点，例如 `http://localhost:11434/v1`（Ollama）、`http://localhost:8000/v1`（vLLM）、`http://localhost:1234/v1`（LM Studio）。
   - **API protocol**：选 **`openai-completions`**。
   - **API key**：本地无 key 也要填一个占位值（如 `ollama`）。它会被当 `Authorization` 用。
   - **Models**：至少填一个本地已拉取的模型 id，例如 `qwen2.5-coder:32b`、`llama3.1:70b`。
3. 保存。模型出现在选择器里；下一次请求即生效，**无需重启**。

> Web UI 填的 key 走凭据服务，明文落在 `$DSH_HOME/.credentials.yaml`，设置里只保留引用。占位值无所谓，本地服务不校验。

---

## 2. 方法 B：直接编辑 `settings.yaml`（推荐，最可控）

不想用 UI 或要批量/精细配置时，直接写 `$DSH_HOME/settings.yaml` 的 `llm-pi-ai:` 段。用户层会与组合层 per-provider 合并，**下次请求即生效，无需重启**。

### 2.1 标准示例（Ollama）

```yaml
llm-pi-ai:
  providers:
    ollama:                                   # Provider ID（即 route key）
      displayName: Ollama (local)
      api: openai-completions                 # 本地服务固定这个协议
      baseURL: http://localhost:11434/v1
      apiKeyEnv: OLLAMA_API_KEY               # 占位凭据引用；本地无 key 也必须有
      # 不想用环境变量，也可直接写死头：
      # headers:
      #   Authorization: Bearer ollama
      models:
        - id: qwen2.5-coder:32b               # 必须是本地实际拉取的模型名
          name: Qwen2.5-Coder 32B
        - id: llama3.1:70b
          name: Llama 3.1 70B
      defaultInput: [text]                    # 纯文本；视觉模型见 2.3
```

然后设好占位环境变量（或改用上面的 `headers` 写法）：

```sh
export OLLAMA_API_KEY=ollama    # 值随意，本地服务不校验
# 之后正常启动 dsh web / dsh --profile headless ...
```

### 2.2 常用本地服务端点速查

| 服务 | Base URL | 备注 |
|---|---|---|
| Ollama | `http://localhost:11434/v1` | 需 `ollama pull <model>`，v0.1.28+ 自带 `/v1` |
| vLLM | `http://localhost:8000/v1` | 启动加 `--served-model-name` 对应 id |
| LM Studio | `http://localhost:1234/v1` | 本地 server 选项卡开启 |
| llama.cpp server | `http://localhost:8080/v1` | `./server -m <gguf> --api-key 可选` |
| Text Generation WebUI / oobabooga | `http://localhost:5000/v1` | 需开启 OpenAI 扩展 |

> 填 `models` 列表会**整体替换**该 route 的目录；如果只改某个 catalog 模型的容量/推理级别，用 `modelOverrides`（见下文 2.4）。不列 `models` 则该 route 走 pi-ai 内置目录——但本地 hand-declared route 没有内置目录，所以**本地必须显式列 `models`**，否则解析失败。

### 2.3 本地视觉模型（多模态）

手填的模型默认按纯文本处理。若本地模型支持图像（如 `llava`、`qwen2.5-vl`）：

```yaml
llm-pi-ai:
  providers:
    ollama-vl:
      api: openai-completions
      baseURL: http://localhost:11434/v1
      apiKeyEnv: OLLAMA_API_KEY
      models:
        - id: qwen2.5-vl:72b
          input: [text, image]     # 该模型单独声明支持图像
```

若所有模型都支持图像，省事写法是在 route 上加 `defaultInput: [text, image]`（fallback，不会覆盖 catalog 已声明）。

### 2.4 本地推理模型（QwQ / DeepSeek-R1 等）

本地跑 R1 / QwQ 这类带 thinking 的模型，可在 route/model 上声明推理级别（`reasoningEfforts`）和方言（`compat.thinkingFormat`）：

```yaml
llm-pi-ai:
  providers:
    ollama-r1:
      api: openai-completions
      baseURL: http://localhost:11434/v1
      apiKeyEnv: OLLAMA_API_KEY
      compat:
        thinkingFormat: deepseek          # 端点 URL 猜不到方言时显式指定
      models:
        - id: deepseek-r1:70b
          reasoningEfforts:               # 可选思考级别（off/high/max 等）
            off:
            high: high
            max: ultra
```

- `reasoningEfforts` 的 key 来自 pi-ai 级别集（`off`/`minimal`/`low`/`medium`/`high`/`xhigh`/`max`）；不声明则模型不暴露推理选项。
- `compat.thinkingFormat` 只在 `openai-completions` 协议上可配；用于修正私有网关/本地服务的思考协议方言。

---

## 3. 在 headless / Python SDK 里用本地模型

组合层（cordis.yml / 内置示例）也能直接写 `llm-pi-ai` providers 段。例如把 `examples/jsonrpc-agent/minimal.cordis.yml` 里的 `llm-deepseek` 块替换为：

```yaml
- id: llm
  name: '@deepseek-ai/dsh-llm-pi-ai'
  config:
    providers:
      ollama:
        api: openai-completions
        baseURL: http://localhost:11434/v1
        apiKeyEnv: OLLAMA_API_KEY
        models:
          - id: qwen2.5-coder:32b
        defaultInput: [text]
```

然后 `export OLLAMA_API_KEY=ollama` 再跑 Python SDK / `dsh --profile headless ...` 即可。

> 组合层（cordis.yml）是「base」，用户层（settings.yaml `llm-pi-ai:`）会按 provider key **合并覆盖**它。所以也可以只在组合层写好本地 route，用 Web UI / settings 再调整，不必改源码。

---

## 4. 验证与排查

**验证**：启动后，Web UI 模型选择器出现你的本地模型 → 开 session 发一句话（如 "ping，只回 ok"）→ 看是否返回。命令行可用 `dsh --profile web --dump-config` 确认 route 已加载。

**常见报错**：
- `MISSING_CREDENTIAL`：本地服务也必须给占位凭据。检查 `apiKeyEnv` 指向的变量是否已 `export`，或在 `headers` 写了 `Authorization`。
- `UNKNOWN_MODEL`：模型 id 与本地实际名称不符（Ollama 常带 `:tag`，如 `qwen2.5-coder:32b`），或该 route 的 `models` 列表里没列这个 id。
- `Fetching available models returns 401`：本地若不支持 OpenAI 的 `GET /models` 自动发现，就**手动填 models**，别点 "Fetch available models"。
- 图像被拒（"image refused before sending"）：该模型没声明 `input: [text, image]`，见 2.3。
- 连不上（`DISCOVERY_FAILED` / 超时）：检查本地服务是否已启动、`baseURL` 端口是否正确、是否跨容器（Web UI 在容器里则 `localhost` 指向容器自身，需用宿主 IP 或 `host.docker.internal`）。

---

## 5. 一句话总结

本地模型 = **Add custom provider → 协议 `openai-completions` → baseURL 指向本地 `/v1` → 必填一个占位 API key（环境变量或 `headers`）→ 手动列 `models`**。改完下次请求即生效，不用重启 `dsh`。
