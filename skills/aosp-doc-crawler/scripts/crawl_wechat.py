#!/usr/bin/env python3
"""crawl_wechat.py — 微信公众号文章合规归档(消费 RSS/Atom feed)
合规要点:
  * 不直接爬 mp.weixin.qq.com(违反其服务条款且含反爬)。
  * 仅消费用户自行订阅的合规 RSS/Atom 源(如 WeRSS 等第三方订阅服务)。
  * 限速、去重、本地存储; 不转发、不转售。
用法:
  python3 crawl_wechat.py --feed https://your-rss.example/feed --archive ./docs
  python3 crawl_wechat.py --feed <URL> --once
"""
import argparse, os, sys, time
try:
    import feedparser  # 轻依赖, 需 pip install feedparser
except ImportError:
    print("[!] 缺少 feedparser: pip install feedparser", file=sys.stderr); sys.exit(2)

UA = "Mozilla/5.0 (compatible; aosp-doc-crawler/1.0; +local)"
KEYWORDS = ("aosp", "framework", "binder", "zygote", "hal", "treble",
            "selinux", "socketcan", "内核", "驱动", "android", "系统")


def is_relevant(title):
    t = (title or "").lower()
    return any(k in t for k in KEYWORDS)


def save(archive, entry):
    d = os.path.join(archive, time.strftime("%Y-%m-%d"))
    os.makedirs(d, exist_ok=True)
    eid = entry.get("id") or entry.get("link") or str(int(time.time()))
    fn = os.path.join(d, f"{abs(hash(eid))}.md")
    if os.path.exists(fn):
        return False
    md = (f"---\ntitle: {entry.get('title','')}\nsource: 微信公众号(RSS)\n"
          f"link: {entry.get('link','')}\ndate: {entry.get('published','')}\n"
          f"tags: [aosp,wechat]\n---\n\n# {entry.get('title','')}\n\n"
          f"> 原文: {entry.get('link','')}\n\n{entry.get('summary','')}\n")
    with open(fn, "w", encoding="utf-8") as f:
        f.write(md)
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--feed", required=True, help="合规 RSS/Atom 订阅源 URL")
    ap.add_argument("--archive", default="./docs")
    ap.add_argument("--once", action="store_true")
    a = ap.parse_args()
    print(f"[*] fetch feed: {a.feed}")
    fp = feedparser.parse(a.feed)
    if fp.bozo:
        print(f"[!] feed parse warning: {fp.bozo_exception}", file=sys.stderr)
    saved = 0
    for e in fp.entries:
        if not is_relevant(e.get("title", "")):
            continue
        if save(a.archive, e):
            saved += 1
        time.sleep(1)  # 限速
    print(f"[+] wechat saved={saved} archive={a.archive}")


if __name__ == "__main__":
    main()
