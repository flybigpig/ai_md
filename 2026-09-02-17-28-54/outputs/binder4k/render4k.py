import re, subprocess, sys, os, struct

import time

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
LONG_EDGE = 3840
WORK = os.path.dirname(os.path.abspath(__file__))


def png_size(p):
    with open(p, "rb") as f:
        d = f.read(33)
    return struct.unpack(">II", d[16:24])


def render(svg_path, out_png):
    raw = open(svg_path, "rb").read().decode("utf-8-sig")
    w = float(re.search(r'\bwidth=[\'"]([0-9.]+)', raw).group(1))
    h = float(re.search(r'\bheight=[\'"]([0-9.]+)', raw).group(1))
    s = LONG_EDGE / max(w, h)
    W, H = round(w * s), round(h * s)

    svg = re.sub(r'(<svg[^>]*?)\bwidth=[\'"][0-9.]+', r'\1width="%d"' % W, raw, count=1)
    svg = re.sub(r'(<svg[^>]*?)\bheight=[\'"][0-9.]+', r'\1height="%d"' % H, svg, count=1)

    html = os.path.join(WORK, "_tmp.html")
    open(html, "w", encoding="utf-8").write(
        "<!DOCTYPE html><html><head><meta charset='utf-8'>"
        "<style>html,body{margin:0;padding:0;background:#fff}"
        "svg{display:block}</style></head><body>%s</body></html>" % svg
    )

    out_png = os.path.abspath(out_png)
    if os.path.exists(out_png):
        os.remove(out_png)
    subprocess.run([
        CHROME, "--headless", "--disable-gpu", "--hide-scrollbars",
        "--force-device-scale-factor=1", "--no-first-run", "--no-default-browser-check",
        "--user-data-dir=" + os.path.join(WORK, "_profile"),
        "--window-size=%d,%d" % (W, H),
        "--screenshot=" + out_png,
        "file:///" + html.replace("\\", "/"),
    ], check=True, capture_output=True, timeout=300)

    for _ in range(120):
        if os.path.exists(out_png) and os.path.getsize(out_png) > 0:
            time.sleep(0.5)
            break
        time.sleep(0.5)

    print("%s -> %s  %dx%d  %.2f MB" % (
        os.path.basename(svg_path), os.path.basename(out_png),
        *png_size(out_png), os.path.getsize(out_png) / 1048576))


for src, dst in [(sys.argv[1], sys.argv[2]), (sys.argv[3], sys.argv[4])]:
    render(src, dst)
