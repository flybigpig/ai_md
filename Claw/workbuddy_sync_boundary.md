# WorkBuddy 跨设备同步:云同步 vs 迁移包

> 适用场景:想把 WorkBuddy 的项目空间 / 任务 / 记忆搬到另一台电脑时。
> **核心结论:云同步只管"记忆",迁移包管"任务 / 文件 / 配置",两者互补,完整搬家要一起做。**

## 1. 云同步(Memory 云端同步)

- 服务端自动维护两层:
  - **云端用户画像**(每次会话注入系统提示的 `<memory>` 块,服务端生成、AI 只读)
  - **历史对话检索**(`conversation_search`,全量历史对话的语义搜索)
- 特性:**同账号自动跨设备**,无需导任何包。
- 开启:客户端设置里"记忆 / 同步"相关开关 + 新机用同一账号登录(入口以客户端版本为准)。
- 范围:仅记忆层。

## 2. 迁移包(export.py / import.py)

脚本:`~/.workbuddy/skills/workbuddy-asset-migration/scripts/`
Python:managed python(如 `C:/Users/<用户>/.workbuddy/binaries/python/versions/3.13.12/python.exe`)

### 导出(源机)

```bash
PY="<managed python>"; SKILL="<scripts 路径>"

# 主包:任务(sessions)+ 自动化(automations)+ skills + 配置 + 身份 + connectors(凭证已剔除)
$PY $SKILL/export.py --source auto --no-credentials --output ~/Desktop/wb-assets.zip

# 加 --with-workspaces 额外产出工作区文件包(实体 .md / .patch / 代码)
$PY $SKILL/export.py --source auto --with-workspaces --no-credentials --output ~/Desktop/wb-assets.zip

# 预览加 --dry-run
```

产出:`wb-assets.zip`(主包)+ `wb-assets-workspaces.zip`(工作区文件包,仅当加 `--with-workspaces`)。

### 导入(目标机,先退出 WorkBuddy 客户端)

```bash
$PY $SKILL/import.py --package ~/Desktop/wb-assets.zip \
    --workspaces-package ~/Desktop/wb-assets-workspaces.zip \
    --target auto

# 两机 Windows 用户名不同,加路径重写:
#   --path-map "C:/Users/OLDNAME=C:/Users/NEWNAME" --target-os win32
```

导入后重启 WorkBuddy,到 connector 面板重新授权。

注意:
- 当前正在运行的对话会被自动排除(避免幽灵会话)。
- connectors 的 OAuth token 跨机失效,`--no-credentials` 已剔除,需重授权。
- 本地 `MEMORY.md` / `SOUL.md` 等不靠包导入,记忆走云同步。

## 3. 范围对照

| 数据 | 云同步 | 迁移包 |
|---|---|---|
| 云端画像 / 对话检索 | ✅ | — |
| 对话任务 / 自动化 | ❌ | ✅ |
| skills | ❌ | ✅ |
| 配置(settings / mcp / models) | ❌ | ✅ |
| connectors(凭证) | ❌ | ✅(需重授权) |
| 工作区实体文件 | ❌ | ✅(`--with-workspaces`) |
| 本地 SOUL / IDENTITY / USER / MEMORY.md | ❌ | ✅(身份 / 配置类) |
| 项目级 `.workbuddy/memory/` | ❌ | ✅(随 workspaces 包) |

## 4. 推荐做法

1. 记忆层 → **云同步**(同账号登录即可)。
2. 任务 / skills / 配置 / 文件 → **官方迁移包**(export / import)。
3. 社区"OneDrive + 符号链接同步整个 `~/.workbuddy/`"方案可用但有坑:
   `workbuddy.db` 含本地路径、SQLite WAL 运行时被云盘锁住导致同步冲突、skills 目录易冲突。官方更推荐迁移包。

## 5. 本次产物(2026-07-31)

- `~/Desktop/wb-assets.zip` 8.6 MB(主包)
- `~/Desktop/wb-assets-workspaces.zip` 1.2 MB(工作区文件包)
- 目标机按 §2 导入命令执行即可。
