#!/usr/bin/env python3
"""crawl_juejin.py — 掘金 AOSP/Framework 技术文章合规抓取(公开 API)
合规要点: 仅用公开内容接口; 带 UA; 限速; 失败退避; 不绕鉴权; 不批量爆破。
用法:
  python3 crawl_juejin.py --archive ./docs --limit 10
  python3 crawl_juejin.py --dry-run --limit 3
"""
import argparse, json, os, sys, time, html
import urllib.request

API = "https://api.juejin.cn/recommend_api/v1/article/recommend_cate_feed"
UA = "Mozilla/5.0 (compatible; aosp-doc-crawler/1.0; +local)"
CATS = {  # 分类 id(可按需调整)
    "654": "前端", "6809637767543259144": "后端", "6809637769959177741": "安卓",
    "6809635626879549454": "架构", "6809637776263217160": "Linux",
}
KEYWORDS = ("aosp", "framework", "binder", "zygote", "systemserver",
            "hal", "treble", "selinux", "socketcan", "内核", "驱动", "android")


def http_post(url, payload, timeout=15):
    data = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, headers={
        "User-Agent": UA, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def fetch_list(cat_id, limit):
    # 掘金推荐流: cursor 分页
    out, cursor = [], "0"
    while len(out) < limit:
        body = {"id_type": 2, "sort_type": 200, "cursor": cursor,
                "limit": min(20, limit - len(out)), "category_id": cat_id}
        try:
            resp = http_post(API, body)
        except Exception as e:
            print(f"[!] list err: {e}", file=sys.stderr); break
        items = resp.get("data", [])
        if not items:
            break
        for it in items:
            a = it.get("article_info", {})
            out.append({"id": a.get("article_id"), "title": a.get("title"),
                        "link": a.get("link") or f"https://juejin.cn/post/{a.get('article_id')}",
                        "view": a.get("view_count", 0)})
        cursor = resp.get("cursor", "0")
        time.sleep(2)  # 限速
    return out


def is_relevant(title):
    t = (title or "").lower()
    return any(k in t for k in KEYWORDS)


def save(archive, art):
    d = os.path.join(archive, time.strftime("%Y-%m-%d"))
    os.makedirs(d, exist_ok=True)
    fn = os.path.join(d, f"{art['id']}.md")
    if os.path.exists(fn):
        return False
    md = (f"---\ntitle: {art['title']}\nsource: 掘金\n"
          f"link: {art['link']}\ndate: {time.strftime('%Y-%m-%d')}\n"
          f"tags: [aosp]\n---\n\n# {art['title']}\n\n> 原文: {art['link']}\n")
    with open(fn, "w", encoding="utf-8") as f:
        f.write(md)
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--archive", default="./docs")
    ap.add_argument("--limit", type=int, default=10)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    seen, saved = 0, 0
    for cid, name in CATS.items():
        for art in fetch_list(cid, a.limit):
            if not art.get("id") or not is_relevant(art["title"]):
                continue
            seen += 1
            if a.dry_run:
                print(f"[dry] {name}: {art['title']} -> {art['link']}")
                continue
            if save(a.archive, art):
                saved += 1
    print(f"[+] relevant={seen} saved={saved} archive={a.archive}")


if __name__ == "__main__":
    main()
