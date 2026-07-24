import json, html, datetime

SRC = "aihot_today.json"
with open(SRC, encoding="utf-8") as f:
    d = json.load(f)

date_str = d["date"]
gen_iso = d["generatedAt"]
canonical = d["attribution"]["canonical"]
source_name = d["attribution"]["source"]

# ---- Beijing time conversion (generatedAt) ----
gt = datetime.datetime.fromisoformat(gen_iso.replace("Z", "+00:00"))
gt_bj = gt.astimezone(datetime.timezone(datetime.timedelta(hours=8)))
date_bj = gt_bj.date()
weekday_cn = ["星期一","星期二","星期三","星期四","星期五","星期六","星期日"][date_bj.weekday()]
date_cn = f"{date_bj.year}年{date_bj.month}月{date_bj.day}日 {weekday_cn}"
gen_human = f"{date_bj.year}年{date_bj.month}月{date_bj.day}日 {gt_bj.hour:02d}:{gt_bj.minute:02d}（北京时间）"

# ---- fixed 5 sections (required order) ----
FIXED = [
    ("models",   "模型发布/更新"),
    ("products", "产品发布/更新"),
    ("industry", "行业动态"),
    ("paper",    "论文研究"),
    ("tip",      "技巧与观点"),
]
by_label = {s["label"]: s["items"] for s in d["sections"]}

def trunc(s, n=60):
    s = (s or "").strip()
    if len(s) <= n:
        return s
    return s[:n-1] + "…"

sections_out = []
global_num = 0
total = 0
for key, label in FIXED:
    items = by_label.get(label, [])
    block = {"key": key, "label": label, "items": []}
    for it in items:
        global_num += 1
        total += 1
        url = it.get("sourceUrl") or it.get("permalink") or "#"
        block["items"].append({
            "num": global_num,
            "title": it.get("title", "").strip(),
            "source": it.get("sourceName", "").strip(),
            "summary": trunc(it.get("summary", "")),
            "url": url,
        })
    sections_out.append(block)

assert total == sum(len(s["items"]) for s in sections_out)

esc = lambda s: html.escape(str(s), quote=True)

# ---- build sections html ----
all_sections = []
for s in sections_out:
    anchor = f"sec-{s['key']}"
    cnt = len(s["items"])
    if cnt == 0:
        cards_block = '      <p class="empty">今日暂无该版块内容。</p>'
    else:
        lines = []
        for it in s["items"]:
            num = f"{it['num']:02d}"
            lines.append(
                '      <article class="card" id="item-' + str(it['num']) + '">\n'
                '        <div class="card-num">' + num + '</div>\n'
                '        <div class="card-body">\n'
                '          <h3 class="card-title">' + esc(it['title']) + '</h3>\n'
                '          <div class="card-meta"><span class="chip">' + esc(it['source']) + '</span></div>\n'
                '          <p class="card-summary">' + esc(it['summary']) + '</p>\n'
                '          <a class="card-link" href="' + esc(it['url']) + '" target="_blank" rel="noopener noreferrer">阅读原文 →</a>\n'
                '        </div>\n'
                '      </article>'
            )
        cards_block = "\n".join(lines)
    all_sections.append(
        '  <section class="section" id="' + anchor + '">\n'
        '    <header class="section-head">\n'
        '      <h2>' + esc(s['label']) + '</h2>\n'
        '      <span class="count-badge">' + str(cnt) + '</span>\n'
        '    </header>\n'
        '    <div class="grid">\n' + cards_block + '\n    </div>\n'
        '  </section>'
    )
sections_html = "\n".join(all_sections)

nav_html = "\n".join(
    '      <a class="nav-chip" href="#sec-' + s["key"] + '">' + esc(s["label"]) + '<b>' + str(len(s["items"])) + '</b></a>'
    for s in sections_out
)

stats_html = "\n".join(
    '        <div class="stat"><span class="stat-num">' + str(len(s["items"])) + '</span><span class="stat-label">' + esc(s["label"]) + '</span></div>'
    for s in sections_out
)

html_doc = (
'<!DOCTYPE html>\n'
'<html lang="zh-CN">\n'
'<head>\n'
'<meta charset="UTF-8">\n'
'<meta name="viewport" content="width=device-width, initial-scale=1.0">\n'
'<title>AI HOT 日报 · ' + esc(date_cn) + '</title>\n'
'<style>\n'
'  :root {\n'
'    --bg: #f5f7fb; --card: #ffffff; --ink: #1c2430; --sub: #5b6675;\n'
'    --line: #e6eaf0; --brand: #4f46e5; --brand2: #7c3aed; --accent: #0ea5e9;\n'
'    --chip: #eef1fb; --shadow: 0 1px 3px rgba(16,24,40,.06), 0 8px 24px rgba(16,24,40,.06);\n'
'  }\n'
'  * { box-sizing: border-box; }\n'
'  body { margin:0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;\n'
'         background: var(--bg); color: var(--ink); line-height:1.6; -webkit-font-smoothing:antialiased; }\n'
'  a { color: inherit; }\n'
'  .hero { background: linear-gradient(135deg, var(--brand) 0%, var(--brand2) 55%, var(--accent) 120%);\n'
'           color:#fff; padding: 38px 20px 30px; }\n'
'  .hero-inner { max-width: 1120px; margin: 0 auto; }\n'
'  .kicker { font-size: 13px; letter-spacing: .14em; text-transform: uppercase; opacity:.85; margin:0 0 6px; }\n'
'  .hero h1 { margin:0; font-size: clamp(26px, 4.5vw, 40px); font-weight: 800; }\n'
'  .hero .date { font-size: 17px; opacity:.95; margin: 8px 0 2px; }\n'
'  .hero .gen { font-size: 13px; opacity:.82; margin: 0; }\n'
'  .stats { display:flex; flex-wrap:wrap; gap:12px; margin-top:22px; }\n'
'  .stat { background: rgba(255,255,255,.14); border:1px solid rgba(255,255,255,.25);\n'
'           border-radius:14px; padding: 12px 18px; min-width: 110px; backdrop-filter: blur(4px); }\n'
'  .stat-num { display:block; font-size: 26px; font-weight: 800; line-height:1.1; }\n'
'  .stat-label { font-size: 13px; opacity:.92; }\n'
'  .nav { position: sticky; top:0; z-index: 20; background: rgba(255,255,255,.92);\n'
'          backdrop-filter: blur(8px); border-bottom:1px solid var(--line); }\n'
'  .nav-inner { max-width:1120px; margin:0 auto; padding:10px 20px; display:flex; flex-wrap:wrap; gap:8px; }\n'
'  .nav-chip { text-decoration:none; font-size:14px; color:var(--sub); background:var(--chip);\n'
'              border:1px solid var(--line); border-radius:999px; padding:6px 14px; display:inline-flex; gap:7px; align-items:center; }\n'
'  .nav-chip b { color: var(--brand); font-size:13px; }\n'
'  .nav-chip:hover { border-color: var(--brand); color: var(--brand); }\n'
'  main { max-width:1120px; margin:0 auto; padding: 26px 20px 40px; }\n'
'  .section { margin-bottom: 34px; scroll-margin-top: 64px; }\n'
'  .section-head { display:flex; align-items:center; gap:12px; margin: 0 0 16px;\n'
'                   padding-bottom:10px; border-bottom:2px solid var(--line); }\n'
'  .section-head h2 { margin:0; font-size: 21px; font-weight:800; }\n'
'  .count-badge { background: var(--brand); color:#fff; font-size:13px; font-weight:700;\n'
'                  border-radius:999px; padding:2px 11px; }\n'
'  .grid { display:grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap:16px; }\n'
'  .card { background: var(--card); border:1px solid var(--line); border-radius:16px; padding:18px;\n'
'           box-shadow: var(--shadow); display:flex; gap:14px; transition: transform .15s ease, box-shadow .15s ease; }\n'
'  .card:hover { transform: translateY(-3px); box-shadow: 0 6px 18px rgba(16,24,40,.12); }\n'
'  .card-num { flex:0 0 auto; width:38px; height:38px; border-radius:11px; font-weight:800; font-size:15px;\n'
'              display:flex; align-items:center; justify-content:center; color:#fff;\n'
'              background: linear-gradient(135deg, var(--brand), var(--brand2)); }\n'
'  .card-body { flex:1 1 auto; min-width:0; }\n'
'  .card-title { margin:0 0 8px; font-size:16px; font-weight:700; line-height:1.45; }\n'
'  .card-meta { margin-bottom:8px; }\n'
'  .chip { display:inline-block; font-size:12px; color:var(--brand); background:var(--chip);\n'
'          border:1px solid var(--line); border-radius:999px; padding:2px 10px; }\n'
'  .card-summary { margin:0 0 12px; font-size:14px; color:var(--sub); }\n'
'  .card-link { font-size:14px; font-weight:600; color:var(--brand); text-decoration:none; }\n'
'  .card-link:hover { text-decoration:underline; }\n'
'  .empty { color: var(--sub); font-size:14px; background:var(--card); border:1px dashed var(--line);\n'
'            border-radius:14px; padding:18px; }\n'
'  footer { max-width:1120px; margin:0 auto; padding: 22px 20px 50px; color:var(--sub); font-size:13px;\n'
'            border-top:1px solid var(--line); }\n'
'  footer a { color: var(--brand); text-decoration:none; }\n'
'  footer a:hover { text-decoration:underline; }\n'
'  .totop { position:fixed; right:18px; bottom:18px; width:44px; height:44px; border-radius:50%;\n'
'            background:var(--brand); color:#fff; border:none; font-size:20px; cursor:pointer;\n'
'            box-shadow: var(--shadow); display:none; }\n'
'  @media (max-width:520px) {\n'
'    .stat { min-width: calc(50% - 6px); }\n'
'    .card-num { width:32px; height:32px; font-size:14px; }\n'
'  }\n'
'</style>\n'
'</head>\n'
'<body>\n'
'  <header class="hero">\n'
'    <div class="hero-inner">\n'
'      <p class="kicker">AI HOT · 每日 AI 晨报</p>\n'
'      <h1>AI 日报仪表盘</h1>\n'
'      <p class="date">' + esc(date_cn) + '</p>\n'
'      <p class="gen">数据生成于 ' + esc(gen_human) + ' · 共 ' + str(total) + ' 条</p>\n'
'      <div class="stats">\n' + stats_html + '\n      </div>\n'
'    </div>\n'
'  </header>\n'
'  <nav class="nav">\n'
'    <div class="nav-inner">\n' + nav_html + '\n    </div>\n'
'  </nav>\n'
'  <main>\n' + sections_html + '\n  </main>\n'
'  <footer>\n'
'    <p>本日报共收录 <b>' + str(total) + '</b> 条 AI 资讯，按「模型发布/更新 · 产品发布/更新 · 行业动态 · 论文研究 · 技巧与观点」五个版块分组，全局连续编号。</p>\n'
'    <p>数据来源：<a href="' + esc(canonical) + '" target="_blank" rel="noopener noreferrer">' + esc(source_name) + '（' + esc(canonical) + '）</a> · 由 aihot skill 日报接口拉取并生成。</p>\n'
'  </footer>\n'
'  <button class="totop" id="totop" aria-label="返回顶部">↑</button>\n'
'  <script>\n'
'    (function() {\n'
'      var btn = document.getElementById(\'totop\');\n'
'      window.addEventListener(\'scroll\', function() {\n'
'        btn.style.display = window.scrollY > 480 ? \'block\' : \'none\';\n'
'      });\n'
'      btn.addEventListener(\'click\', function() {\n'
'        window.scrollTo({ top: 0, behavior: \'smooth\' });\n'
'      });\n'
'      document.querySelectorAll(\'.nav-chip\').forEach(function(a){\n'
'        a.addEventListener(\'click\', function(e){\n'
'          var id = a.getAttribute(\'href\');\n'
'          var el = document.querySelector(id);\n'
'          if (el) { e.preventDefault(); el.scrollIntoView({ behavior:\'smooth\', block:\'start\' }); }\n'
'        });\n'
'      });\n'
'    })();\n'
'  </script>\n'
'</body>\n'
'</html>\n'
)

out = "ai_daily_dashboard.html"
with open(out, "w", encoding="utf-8") as f:
    f.write(html_doc)

print("WROTE", out)
print("total items:", total)
print("sections:", [(s["label"], len(s["items"])) for s in sections_out])
print("date_cn:", date_cn, "| gen_human:", gen_human)
