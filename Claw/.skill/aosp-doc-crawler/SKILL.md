---
name: aosp-doc-crawler
description: AOSP/Framework/HAL 技术文档合规定时爬虫技能。当用户需要每日自动抓取掘金/微信公众号的底层技术干货、离线归档为 Markdown、建立检索索引、配置 crontab 定时任务与告警清理时触发。即使用户只说"定时抓掘金文章"、"公众号文章归档"、"每日 AOSP 文档"、"离线知识库"、"crontab 爬虫"，也应触发。必须遵循合规抓取：掘金走公开 API，公众号走合规 RSS/订阅源，禁止违规批量爬取。
agent_created: true
---

# aosp-doc-crawler — 合规定时文档爬虫

目标：每日抓取 **AOSP / Framework / HAL / 内核驱动** 相关技术文章，转 Markdown 离线归档，生成索引，定时运行。

## 合规约束（硬性）

- **掘金**：使用其**公开内容 API**（分类/推荐列表 + 文章详情），遵守 `robots.txt`，带 `User-Agent`、限速（≥2s/请求）、失败退避。**不**绕过鉴权、不批量爆破接口。
- **微信公众号**：**不得**直接爬 `mp.weixin.qq.com`（违反其服务条款且含反爬）。合规方式：
  1. 经**第三方合规 RSS 订阅源**（如 WeRSS 等，用户自行订阅并授权）拿到 feed URL；
  2. 或经**搜狗微信搜索**公开结果页（限检索目的，不做全量归档）；
  3. 或用户导出的公众号文章。脚本 `crawl_wechat.py` 默认消费 **RSS/Atom feed URL**，不直接打 mp 站点。
- 任何来源都需**限速 + 去重 + 本地存储**，不上传、不转售。

## 何时使用

- 搭建每日技术文档归档流水线。
- 生成可检索的离线 MD 知识库。
- 配置/排障 crontab 定时任务。

## 工作流

1. 运行 `scripts/crawl_juejin.py`（掘金）与 `scripts/crawl_wechat.py --feed <RSS_URL>`（公众号合规源）。
2. 文章写入 `<ARCHIVE>/<date>/<id>.md`，含 YAML frontmatter（标题/来源/日期/原文链接/标签）。
3. 生成/更新 `<ARCHIVE>/index.md`（按日期+标签索引）。
4. crontab 每日定时；超期（默认 180 天）清理旧文；抓取失败发告警（邮件/钉钉 webhook，见 `references/crawler_readme.md`）。

## 目录结构

```
<ARCHIVE>/
  2026-07-24/<id>.md
  index.md
  failed.log
```

## 调试

```bash
python3 scripts/crawl_juejin.py --dry-run --limit 3   # 试抓不落盘
python3 scripts/crawl_wechat.py --feed https://your-rss.example/feed --once
# 查索引
grep -l "Binder" <ARCHIVE>/index.md
```

## 关联

- 文档解析成 AOSP 深度拆解 → `aosp-navigator` / `aosp-binder` 等
- 完整说明与 crontab/告警/清理 → `references/crawler_readme.md`
