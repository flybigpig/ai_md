import subprocess, sys, json, os

MD_PATH = r"C:\Users\YTO-02231406\WorkBuddy\2026-07-15-15-28-31\android-framework-articles.md"
NODE = r"C:\Users\YTO-02231406\.workbuddy\binaries\node\versions\22.22.2\node.exe"
RUN_JS = r"C:\Users\YTO-02231406\.workbuddy\binaries\node\cli-connector-packages\node_modules\@larksuite\cli\scripts\run.js"
TITLE = "Android Framework 开发实战文章清单"

with open(MD_PATH, "r", encoding="utf-8") as f:
    content = f.read()

cmd = [
    NODE, RUN_JS, "docs", "+create",
    "--doc-format", "markdown",
    "--title", TITLE,
    "--content", content,
]
print("Calling lark-cli (via node) docs +create ...", file=sys.stderr)
try:
    res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", timeout=240)
except Exception as e:
    print("SUBPROCESS_ERROR:", e, file=sys.stderr)
    sys.exit(2)

print("=== returncode:", res.returncode, "===", file=sys.stderr)
print(res.stdout, file=sys.stderr)
print("=== STDERR ===", file=sys.stderr)
print(res.stderr, file=sys.stderr)

try:
    data = json.loads(res.stdout)
    if data.get("ok"):
        doc = data.get("data", {}).get("document", {})
        print("DOC_URL=" + doc.get("url", ""))
        print("DOC_ID=" + doc.get("document_id", ""))
    else:
        print("CREATE_NOT_OK", file=sys.stderr)
except Exception as e:
    print("PARSE_ERROR:", e, file=sys.stderr)
