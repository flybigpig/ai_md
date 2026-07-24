# 《AOSP 整机源码 Harness 工程探索》核心内容总结

> 原文：Ahao's Technical Blog — *AOSP 整机源码 Harness 工程探索*
> 整理目的：提炼核心论断、方案框架、关键设计洞见与可迁移经验
> 背景：在纯 AOSP 17（`android-17.0.0_r1`）整机源码树上，用 Claude Code 搭建四层 harness，目标设备 Cuttlefish 虚拟机（`aosp_cf_x86_64_phone`）

---

## 一、核心论断（一句话）

> **模型不是瓶颈，环境才是。** coding agent 在 AOSP 整机树上"开箱不可用"，能做成靠的是围绕 agent 搭的 **harness engineering**——"harness 诚实，产出就诚实"。

这套 harness 的本质，是把一位 AOSP 老工程师带新人时会做的四件事翻译成 agent 基础设施：
**给地图（上下文）+ 教编译（流程）+ 划红线（护栏）+ 要求验证（闭环）**。

---

## 二、问题：为什么 agent 在 AOSP 整机树"开箱不可用"

### 2.1 agent 的工作机制：agentic search
Claude Code 之类 **不建索引、不做 RAG**，而是像新来的工程师一样现场导航（grep、读文件、跟引用）。
- **好处：永不过期**——永远读 live 代码，不会返回"两周前已删的函数"还不告诉你。
- **代价：上下文窗口是唯一稀缺资源**——导航每一步都在吃上下文，质量取决于"代码库被布置得多好"。

### 2.2 三个放大器（把矛盾推到极端）
| 放大器 | 表现 |
|--------|------|
| **规模** | 上千 git project、千万行代码；仅 `external/` 就数百子目录 |
| **多语言** | C++/Java/Kotlin/AIDL/Rust 混编；同名符号成海（无数个 `onTransact`） |
| **编译重** | Soong+Kati+Ninja，单编十几分钟起步；没有 `npm test` 式快速反馈 |

### 2.3 七类真实痛点
导航失效（全树 grep 吞光上下文、跳到错误同名符号）｜上下文盲区（不知在哪个 feature、哪些仓能动）｜流程知识丢失（每次重教编译/产物/push）｜"编过=改对"幻觉（编译成功就心满意足结束，设备一跑就崩）｜危险操作无门禁（adb push/reboot、repo sync 只靠模型自觉）｜知识污染（上下文文档混进 gerrit 提交）｜隐性经验反复付学费（"改这个类必须连某个 so 一起重编"每次重踩）。

---

## 三、理论底座：Anthropic 官方博客要点

博客把 harness 拆成 **五个扩展点 + 两项能力**，且**叠加有顺序，每层建立在前一层上**：

| 扩展点 | 最适合 | 常见误用 |
|--------|--------|----------|
| **CLAUDE.md** | 项目约定、代码库知识（根=全局、子目录=局部） | 把该进 skill 的经验塞进来 |
| **hooks** | 自动化一致行为、**捕获会话经验（自我改进）** | 用 prompt 做本应自动跑的事 |
| **skills** | 跨会话/项目的可复用专长（**可按 path scope**） | 全塞进 CLAUDE.md |
| **plugins** | 把可用配置分发到全组织 | 好做法停留在部落知识 |
| **MCP servers** | 够到本来够不到的内部工具 | 基础没跑通就先建 MCP |
| **LSP** | 符号级导航、类型语言自动查错 | 以为它自动就有 |
| **subagents** | **探索与编辑分离**、并行 | 同一会话既探索又编辑 |

特别强调：**LSP 是多语言大库最高杠杆投资**；**skills 可 path-scoped**；**subagents 典型用法是只读子代理测绘子系统、写进文件，主代理再带全貌编辑**；**hooks 最有价值的是让配置自我改进**（stop hook 反思→提议改 CLAUDE.md）。

---

## 四、同类方案盘点（4 个，各占生态位）

| 方案 | 形态 | 定位 | 局限 |
|------|------|------|------|
| **Lightrion AOSP RAG** | 托管 MCP（SaaS） | 对公开 AOSP 各版本语义检索、跨版本 diff | 只索引**公开发布版**，不含你的本地改动（RAG 死穴）；只覆盖"读与查" |
| **utzcoz 十模式** | 方法论博客 | 4 个已交付 AOSP 级项目沉淀的 harness 工程模式 | 方法论非可安装工件；单仓 fork+模拟器，未处理 repo 千仓问题 |
| **hyperdroid-skill** | Claude Code 插件 | Android 通用技能包 + crash 分析 agent | 内容是通用命令速查，不绑任何具体源码树 |
| **Android-Software** | 分层 skill 知识包 | L1 路由 → L2 子系统专家（防幻觉路径） | 静态知识包，与"你这棵树"零绑定，版本会漂移 |

**结论**：四个方案分别解决了检索、方法论、通用知识、知识路由，但**没有一个解决"这一棵树"的问题**——本地 fork 的 symbol 级实时导航、repo/gerrit 布局下不污染上游的上下文组织、随 feature 分支自动切换、绑定本树目标设备的确定性验证环。这块空白就是本文四层 harness 的主体。

---

## 五、四层 Harness 方案（核心）

### 5.1 业务前提与术语
- **业务前提**：一个专项 = 一个 feature = 一个 repo 本地分支（`repo start <feature> --all`）= **3–8 个单仓**。正是"涉及仓有限且随分支固定"这个事实，让所有"随 feature 组织、随分支切换、按仓精简"机制得以成立。
- **关键术语**：`LSP/clangd/compdb`（符号级导航）、`SessionStart hook`（按分支重指软链）、`path-scoped skill`（按路径激活）、`permissions.ask`（硬门禁）、`verify-*.sh`（确定性验证脚本）。

### 5.2 四层总览
| 层 | 解决什么 | 落地物 |
|----|----------|--------|
| **① 代码智能** | 按 symbol 而非文本导航 | 树根 `.clangd` → feature 精简 compdb（C++）；Java/Kotlin 明确不配 LSP |
| **② 上下文** | 每会话自动知道"在哪、做什么、什么不能碰" | 根 `CLAUDE.md` 软链 → `features/<分支>/CLAUDE.md`（单文件全上下文）+ SessionStart hook 按分支重指 |
| **③ 流程** | 动到哪片代码就知道怎么编译/push/验证 | 树根 `.claude/skills/` 若干 **path-scoped** skill |
| **④ 护栏与验证** | 防误操作设备、斩断"编过=改对" | `permissions.ask` 硬门禁 + `features/<分支>/verify-*.sh` 确定性脚本 |

> **与官方扩展点的对应**：① 代码智能 = LSP；② 上下文 = CLAUDE.md + hooks（SessionStart 动态注入）；③ 流程 = skills（path-scoped）；④ 护栏 = hooks（permissions/校验）+ verify 脚本。博客的叠加顺序在探索历程中如实复现：先 CLAUDE.md 定契约 → hook 注入上下文 → skill 承载流程 → 补护栏验证。

### 5.3 目录结构（树根游离文件，不进任何 gerrit 仓）
```bash
<AOSP_ROOT>/                          # repo 工程根（非 git 仓）
├── CLAUDE.md                         # ② 软链 → features/<分支>/CLAUDE.md（hook 按分支重指）
├── .clangd                           # ① 指向 feature 精简 compdb（绝对路径）
├── compile_commands.json             # ① 根符号链
├── gen-compdb-clangd.sh              # ① compdb 两段式刷新脚本
├── .claude/
│   ├── settings.json                 # ② hooks 注册 + ④ permissions 门禁
│   ├── hooks/load-feature.sh         # ② 按分支把树根 CLAUDE.md 软链重指
│   ├── hooks/check-branch-drift.sh   # ② 会话中途切分支告警
│   ├── hooks/compdb-stale-nudge.sh   # ① compdb 时效补漏（PostToolUse）
│   ├── rules/compdb-freshness.md     # ① compdb 时效提醒（path-scoped rule）
│   └── skills/build-*/SKILL.md       # ③ path-scoped 编译/验证 skill
└── features/                         # ② 独立 git 仓（不在 manifest，repo/gerrit/soong 全不可见）
    └── dev-sidebar/                  # 目录名 = repo 分支名 = feature 名
        ├── CLAUDE.md                 # ② 单文件全部上下文
        ├── repos.tsv                 # ① compdb 仓集单一事实源
        ├── check-branch.sh
        └── verify-sidebar.sh         # ④ 确定性验证脚本
```

**关键地基**：源码树根不是 git 仓（只有 `.repo/`），所以放在树根的文件不被任何 gerrit project 跟踪，soong/kati 也不当模块编译——整套方案"不污染上游"的地基。

---

## 六、关键设计洞见（为什么这么设计）

### 6.1 features/ 必须独立 git 仓（git 语义死结）
| 需求 | 与 git 语义的冲突 |
|------|------------------|
| 上下文文件内容**随 feature 分支切换** | git 语义：文件必须被 project 跟踪才会随 checkout 变内容 |
| **不污染 gerrit** | 一旦被 frameworks/base 等 project 跟踪 → repo upload/gerrit 可见 |
| `.git/info/exclude` 只让文件不被跟踪 | —— 那它就不随分支变了，两头堵死 |

**破局**：把"随分支"的逻辑整个搬出 project git → `features/` 独立 git 仓放树根 + hook 按当前分支把树根 `CLAUDE.md` 软链重指到对应 feature。

### 6.2 v5 → v6 演进（最关键转折）
- **v5**：SessionStart hook stdout 注入概览 + 各仓物化 `CLAUDE.md` 按需加载。
- **实测发现**：这两条送达路内容进的都是**会话消息流**，长会话压缩时被**摘掉（易失）**，且**都不进子代理**（子代理只加载 CLAUDE.md 层）。
- **v6**：砍掉注入与子目录 CLAUDE.md，把 feature 全部上下文内联进**单个** `features/<分支>/CLAUDE.md`，树根 `CLAUDE.md` 软链指向它 → 启动即整份进 **Memory files 持久桶**（抗压缩、子代理也吃到）。代价只是根 CLAUDE.md 变大（3–8 仓约定，几 k token，可控）。

### 6.3 六条硬约束（写进根 CLAUDE.md，子代理也可见）
不向任何 gerrit project 提交 harness 文件｜禁配 Java LSP/禁生成 Eclipse 工程文件｜改 public/System API 后必须 `m update-api`｜新增系统服务必须同步 `system/sepolicy`｜push framework.jar 后注意 ART 缓存（dexpreopt/boot image 校验）｜不手改 `out/` 下生成物。

### 6.4 Java/Kotlin 刻意不配 LSP（论证后的止损）
完整走完 aidegen + jdtls 路线后放弃，沉淀选型判据：
> **能靠"精确文件清单"喂的 LSP（clangd 读 compdb，不遍历树）就配；要靠"遍历大树 workspace"、且工具链已弃用的就不配。**

三原因：aidegen 已官方弃用；jdtls 会从根 URI 遍历整棵树（空转吃 ~2GB、反复 internal error）；官方插件不暴露缩窄扫描范围的配置。Java/Kotlin 改用 Grep 搜符号 + JNI 注册名（`android_view_*`）作锚点。

### 6.5 compdb 两段式 + 时效"两条腿"
- 全树 compdb 实测 **113,371 条 / 1.97GB** 太重；`gen-compdb-clangd.sh` 用 soong 全树库作过滤源，按 feature 涉及仓（来自 `repos.tsv` 的 `compdb` 标签）过滤出精简库 **12,152 条 / 282MB**。
- 时效兜底：**读构建文件 → path-scoped rule** 自动提醒；**不读构建文件的 staling 动作（repo sync / 新增 .cpp / 新建 Android.bp）→ PostToolUse hook** 回注提醒。两条腿拼出完整覆盖，都不靠人记。

### 6.6 与官方博客的两处有意背离 + 一处暂缓
- **背离一**：不采纳"子目录初始化 CLAUDE.md"（cwd 必须树根；且子目录 CLAUDE.md 易失、不进子代理）→ 改用"单文件软链根"。
- **背离二**：不把 harness 做成 plugin（价值恰在"树根游离文件"这个不污染上游的地基）→ 跨机分发改由 `features/` 独立 git 仓推私有 remote。
- **暂缓**：MCP servers（基础未跑通前不建 MCP；只读侧把 Lightrion 当补充通道）。

---

## 七、踩坑精选（均有实证）

| 坑 | 根因 | 教训 |
|----|------|------|
| **Eclipse/jdtls 残留写坏源码树** | Java LSP 实验把 `.class` 连同 `.aconfig` 拷进 `services/*/bin/`，被 soong 的 `**/*.aconfig` glob 收成重复声明；`.gitignore` 忽略 `.project` 使 `git status` 看不见 | "禁配 Java LSP"从性能取舍升级为**硬约束**——它不只吃内存，还会污染构建 |
| **clangd 包装脚本劫持（多树共存）** | `~/.local/bin/` 旧树 clangd 包装脚本硬编码 `--compile-commands-dir`，**CLI flag 优先级压过 `.clangd`** | 包装脚本按 `$PWD` 分树分发；树根 `.clangd` 写绝对路径双保险 |
| **`--query-driver` 在 AOSP 是反作用** | clangd 裸跑驱动探测系统 glibc 头，与 bionic 头混装爆出上百个 `__GLIBC_USE is not defined` | AOSP compdb 已带全套 bionic `-isystem`，什么都不用补，删掉即清零 |
| **元教训** | 三大件搭好 ≠ 能用 | harness 自身也要工程标准对待：设计完要评审、加固、实测（自审修掉 6 个缺陷：长编译超时、/clear 后上下文静默丢失、软约束不可靠→硬门禁） |

还有一个上游坑：AOSP 曾有临时 commit（`b790b9cb8`）把 cc 模块 `compiler` 置 nil，导致 `SOONG_GEN_COMPDB=1 m nothing` 静默产出空 `[]`；基线落在该窗口需 `ninja -t compdb` 或打三行补丁绕行。

---

## 八、边界与演进

### 8.1 已知边界
- Java/Kotlin 无 LSP（跨语言追踪靠 Grep + JNI 注册名）
- 单树单分支（repo 树无 git worktree 等价物，并行两 feature 需两棵树）
- compdb 有时效（改构建文件/repo sync 后需重跑刷新脚本）

### 8.2 何时重审
每 3–6 个月、或新一代模型发布后感觉规则见顶时，删过期/矛盾规则（"为迁就某代模型缺陷写的规则，下一代上来就成束缚"）；另加一条自己的信号：**依赖工具链出现弃用声明立即重审**（aidegen 之鉴）。

### 8.3 可演进方向（按杠杆排序）
1. **五段式 handoff 交接文档**（What/How Verified/Files Modified/Blocker/Next）——跨会话冷启动最高杠杆
2. **会话末反思 hook**——提议更新 CLAUDE.md，形成持续改进闭环
3. **冷启动 review 子代理对抗审查**——作者偏 "ship it"，无包袱 reviewer 偏 "explain this"
4. **只读子代理测绘、主代理编辑**——整机树"探索"极烧上下文，先派只读 subagent 测绘子系统、把发现写进文件，主代理再带全貌下手

---

## 九、与上一轮 AOSP 仓库分析的关系 & 对 fly 的启示

### 9.1 两份文档的互补
- 上一轮分析的 `cnb.cool/flybigpig/aosp`（android-14.0.0_r1 monorepo）回答的是 **"这棵树长什么样"**（目录结构、模块分层、CI 配置）。
- 本文回答的是 **"怎么让 AI 在这棵树上高效、安全地干活"**（harness 工程）。
- 二者结合即：**先读懂 AOSP 的树（结构）+ 再为 coding agent 搭好地基（harness）**。

### 9.2 对 fly（圆通 Android/Kotlin 开发者）的可迁移经验
fly 正在做 erp-pda（Android）开发，并自建 CodeBuddy Agent / erp-list-page-builder 等 skill 体系。本文 harness 思路可直接迁移：
- **行为契约 > 文档**：把"agent 默认会犯的错"写成硬约束（如改 public API 必跑 update-api），而非泛泛文档。
- **软约束 → 硬门禁**：危险操作（push/reboot/clean）用系统级确认拦一道，不依赖模型记性。
- **verify 闭环**：没有现成测试时，把"改对了没有"编码成只输出 PASS/FAIL 的脚本，斩断"编过=改对"幻觉——对 erp-pda 这类没有 `npm test` 的移动项目尤其关键。
- **不污染上游**：把 skill/上下文放在树根游离文件或独立仓，而非塞进被跟踪的业务仓（与本文 `features/` 独立仓同构）。
- **path-scoped 加载**：按路径激活的流程知识零常驻占用，适合把 erp-pda 各模块（盘点/扫描出库/充值/上架…）的编译-部署-验证流程各写成一个 scoped skill。

---

## 十、一句话带走

> **给 agent 一张地图、教它怎么编译、划清红线、逼它验证——这四件事做好了，AOSP 整机树上的 coding agent 就从"开箱不可用"变成"每会话都站在同一套地基上"。模型会换代，地基每个会话都在复用。**
