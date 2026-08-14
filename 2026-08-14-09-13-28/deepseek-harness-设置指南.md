# DeepSeek Harness（`dsh`）设置指南

> 基准：`packages/settings/README.md`、`packages/client/ui-settings*/README.md`、`packages/client/ui-settings-models/README.md`、`packages/client/ui-agent-preset/README.md`
> 衔接：本篇讲「设置体系」，[本地模型配置](deepseek-harness-本地模型配置.md) 是其中 `llm-pi-ai` 命名空间的具体用法。

---

## 1. 设置的两种入口

| 入口 | 形态 | 落盘位置 | 适合 |
|---|---|---|---|
| **Web UI Settings 面板** | 图形化、按分区（section） | 自动写入 `settings.yaml` / `.credentials.yaml` | 日常交互配置 |
| **直接编辑 `settings.yaml`** | 声明式 YAML、热生效 | `$DSH_HOME/settings.yaml` | 精细/批量/脚本化配置 |

两者操作的是**同一份持久数据**：Web UI 的每一次改动本质都是对 `settings.yaml`（或 `.credentials.yaml`）的 path 写操作（增/改 `set`、清除 `unset`）。

`DSH_HOME` 默认 `~/.dsh`（**Windows：`%USERPROFILE%\.dsh`**）。相关文件：
- `settings.yaml` —— 所有非密钥设置（模型路由、语言、主题、通用项…）。**明文只读，密钥永不进这里**。
- `.credentials.yaml` —— API Key 等密钥（write-only，页面只持有脱敏描述符）。
- `profiles/` —— 各 profile 的 `package.json` 与 `cordis.patch.yml`。
- `AGENTS.md` —— 用户全局指令（`$DSH_HOME/AGENTS.md`，无 overlay）。

---

## 2. 设置的分层模型（理解「为什么改了就生效」）

`dsh` 的设置走 `ctx.settings` seam，核心规则：

- **组合层（composition base）**：来自 cordis.yml / 内置 bundle 的 entry `config`（例如 `dsh-llm-pi-ai` 插件自带的 `providers` 段）。
- **用户层（user layer）**：就是 `$DSH_HOME/settings.yaml` 里 `llm-pi-ai:` 这类命名空间段落。
- **合并**：按 **namespace（命名空间）** 粒度合并——用户层覆盖组合层，**并且按 provider key（字典键）递归合并**。所以：
  - 你只需写想覆盖/新增的字段，不用把整个默认配置抄一遍。
  - 某字段只要**出现在**用户层（present）就算 override，不看值是否等于默认值；想退回组合层默认值用 `unset`/`deletePath`。
- **热提交**：`settings-file` 包存本地文件并**观察外部编辑**——你手改 `settings.yaml` 后，`dsh` 通过 `settings/document-updated` 事件实时收敛，**下次请求即生效，无需重启**（远程非 loopback 浏览器除外，它只有进程内 memory 模式，不跨线持久）。

> 限制（官方已知）：用户层只能**新增/覆盖**组合层的 route，不能「删除」组合层提供的 provider——删组合层 provider 属于组合变更（改 cordis.yml）。`replace` 整个 namespace 也只是清空用户层。

---

## 3. Web UI Settings 面板有哪些分区

设置面板由多个 feature 包各自贡献一个 `settings.section`（每项功能一页），外壳（`sidebar.settings` + 导航）由 `ui-settings-general` 提供。已知分区：

| 分区 / 行 | 贡献包 | 能配什么 |
|---|---|---|
| **Models（模型）** | `ui-settings-models` | DeepSeek、目录供应商（OpenAI/Anthropic…）、自定义供应商（本地模型在此）；首次启动有官方 DeepSeek 凭据引导 |
| **General（通用）** | `ui-settings-general` | 通用项 `settings.general.*`、本地配置文件操作入口（直接打开 `settings.yaml`） |
| **权限 Permission** | 各功能包 | 活动权限策略（agent 改文件/执行命令等需审批操作是否弹窗询问） |
| **语言 Language** | `locale` | `locale.preference`（zh/en），存 `settings.yaml` |
| **外观 Appearance** | `ui-theme` | `theme.preference`（light/dark/system），存 `settings.yaml` |
| **Plugins（插件）** | `ui-settings-plugins` | 插件分区 + Host Loader 只读清单页、可配置 host 平面插件卡片 |
| **Agent Presets（智能体预设）** | `ui-agent-preset` | 管理 preset 名单（复制/删除/设为默认、进入 preset 自身文件）；排在 Models 之后 |

交互细节（基于 `ui-settings-models`）：
- Models 页把 **LLM 可配置供应商目录** + **settings 分层脱敏值** + **凭据徽标** 三者汇聚成一张快照，一次只展开一张编辑卡。
- 编辑卡主字段就是 **API key 输入框**（页面从不问环境变量名）；填的 key 经 `credentials.set` **write-only** 落盘，文件里只留引用（`apiKeyEnv: <ROUTE>_API_KEY`）。
- 「Add a custom provider」是独立卡片：必须提供 小写 **Provider ID**、endpoint、**protocol**、至少一个模型 id，才能创建。
- 每次写入带 `revision`；若另一个标签页或外部 `settings.yaml` 编辑并发改了，会被拒为 `settings-conflict`，需重读再写。

---

## 4. `settings.yaml` 里能声明的主要命名空间

| Namespace | 作用 | 关键字段 |
|---|---|---|
| `llm-pi-ai:` | 多供应商 LLM 路由（含本地模型） | `providers.<route>`（`api`/`baseURL`/`apiKeyEnv`/`models`/`modelOverrides`/`compat`/`defaultInput`…） |
| `locale.preference` | 界面语言 | `zh` / `en` |
| `theme.preference` | 主题 | `light` / `dark` / `system` |
| `settings.general.*` | 通用产品项 | 由 `ui-settings-general` 定义 |
| `agent-presets` | 智能体预设名单 | 复制/删除/默认（多为文件操作） |

> 模型相关的完整字段与本地模型示例见 [本地模型配置](deepseek-harness-本地模型配置.md)。

### 4.1 最小 `settings.yaml` 示例（本地 Ollama + 中文 + 深色）

```yaml
locale:
  preference: zh
theme:
  preference: dark
llm-pi-ai:
  providers:
    ollama:
      api: openai-completions
      baseURL: http://localhost:11434/v1
      apiKeyEnv: OLLAMA_API_KEY      # 占位凭据，需 export OLLAMA_API_KEY=ollama
      models:
        - id: qwen2.5-coder:32b
```

保存后刷新 Web UI 即可在模型选择器看到 `ollama` 路由；无需重启。

---

## 5. 与组合层（cordis.yml）的关系

- **组合层**是 cordis.yml 里各插件的 `config`，是「出厂默认/部署形态」。
- **用户层**（`settings.yaml`）在其上叠加，**不删组合层 route**。
- 想彻底改某个组合层 provider（如删掉内置 deepseek 路由），要改 cordis.yml / bundle，或在 profile 的 `cordis.patch.yml` 里 patch 对应 row。
- 查看实际启动的插件树（含最终生效的配置）：`dsh --profile web --dump-config`。

---

## 6. 排查

- `settings-conflict`：并发写入（另一标签页 / 外部编辑）导致 revision 过期。重读设置页或 `settings.yaml` 再改。
- 改了 `settings.yaml` 没生效：确认路径是 `$DSH_HOME/settings.yaml`（不是仓库目录里的某份）；确认格式是合法 YAML；远程/容器里的 Web UI 是 memory 模式不持久——本地 loopback 访问才落盘。
- 密钥相关（`MISSING_CREDENTIAL`）：见本地模型篇——本地无鉴权服务也要给占位 `apiKeyEnv`。
- 想「重置某项回默认」：在 Web UI 对应卡片清空该字段（等于 `unset`），或直接编辑 `settings.yaml` 删除该 key。

---

## 7. 一句话总结

`dsh` 的设置 = **Web UI Settings 面板** 与 **`$DSH_HOME/settings.yaml`** 同一份数据；按 namespace 分层（用户层覆盖组合层，字典键递归合并），热提交无需重启；密钥单独存 `.credentials.yaml`（write-only）；模型在 `llm-pi-ai:` 命名空间，本地模型也是在这里配。
