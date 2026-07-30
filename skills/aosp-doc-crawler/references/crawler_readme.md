# AOSP 文档定时爬虫 — 部署 / crontab / 告警 / 清理

## 1. 部署前置

```bash
pip install feedparser            # 仅公众号 RSS 需要
mkdir -p ~/aosp-docs/{docs,log}
```

## 2. crontab（每日 09:00 抓取）

```cron
# 编辑: crontab -e
0 9 * * *  cd /path/to/skill/scripts && python3 crawl_juejin.py --archive ~/aosp-docs/docs >> ~/aosp-docs/log/juejin.log 2>&1
0 9 * * *  cd /path/to/skill/scripts && python3 crawl_wechat.py --feed https://your-rss.example/feed --archive ~/aosp-docs/docs >> ~/aosp-docs/log/wechat.log 2>&1
# 每日 23:50 生成索引 + 清理 >180 天旧文
50 23 * * * /path/to/build_index.sh
```

`build_index.sh` 示例：

```bash
#!/usr/bin/env bash
ARCHIVE=~/aosp-docs/docs
# 索引: 汇总所有 md 的 frontmatter 标题+链接
: > "$ARCHIVE/index.md"
echo "# AOSP 文档索引 ($(date +%F))" >> "$ARCHIVE/index.md"
for f in $(find "$ARCHIVE" -name '*.md' ! -name index.md | sort -r); do
  title=$(grep -m1 '^title:' "$f" | sed 's/title: //')
  link=$(grep -m1 '^link:' "$f" | sed 's/link: //')
  echo "- [$title]($f)  $link" >> "$ARCHIVE/index.md"
done
# 清理: 超过 180 天的目录
find "$ARCHIVE" -maxdepth 1 -type d -mtime +180 -exec rm -rf {} \;
```

## 3. 资源控制（性能约束）

- 限速：掘金 ≥2s/请求，公众号 ≥1s/条目；`--limit` 控制单次量（默认 10）。
- IO 限速：归档落本地盘，避免峰值写；索引用增量。
- 反爬策略：固定 UA、失败指数退避（脚本内 2s，生产可加 `backoff`）。

## 4. 告警

抓取失败写入 `failed.log`，可对接钉钉 webhook：

```bash
if grep -q '\[!\]' ~/aosp-docs/log/juejin.log; then
  curl -s -X POST $DING_WEBHOOK -H 'Content-Type: application/json' \
    -d '{"msgtype":"text","text":{"content":"AOSP 爬虫异常，请查看 failed.log"}}'
fi
```

## 5. 合规红线

- 掘金：仅公开 API，不绕鉴权、不批量爆破。
- 公众号：**禁止**直爬 `mp.weixin.qq.com`；只用用户订阅的合规 RSS/Atom。
- 文档仅本地归档学习，**不**上传/转售/再分发。
- 尊重原文版权，归档注明来源与原文链接，遵守 robots.txt。
