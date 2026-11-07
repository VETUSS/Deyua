#!/usr/bin/env bash
# 用法：GH_TOKEN=xxx bash publish_release.sh [tag] [apk] [notes] [name]
#
#   tag   默认 v0.8.93-r32
#   apk   默认 out/Deyva-<tag>.apk
#   notes 默认 out/release-notes-<tag 去掉 v>.md
#   name  默认 "Deyva <tag>"
#
# 只在本地跑，token 从环境变量读，不落盘。重复执行是安全的：
# release 已存在就复用，同名资产已上传就跳过。
set -euo pipefail

OWNER="VETUSS"
REPO="Deyua"

TAG="${1:-v0.8.93-r32}"
APK="${2:-D:/xm/out/Deyva-$TAG.apk}"
NOTES="${3:-D:/xm/out/release-notes-${TAG#v}.md}"
NAME="${4:-Deyva $TAG}"

for f in "$APK" "$NOTES"; do
    [ -f "$f" ] || { echo "找不到文件：$f" >&2; exit 1; }
done
echo "tag=$TAG"
echo "apk=$APK ($(stat -c%s "$APK") 字节)"
echo "notes=$NOTES"

: "${GH_TOKEN:?需要 GH_TOKEN 环境变量}"

python - "$OWNER" "$REPO" "$TAG" "$NAME" "$APK" "$NOTES" <<'PY'
import json, os, sys, urllib.request, urllib.error, mimetypes

owner, repo, tag, name, apk, notes = sys.argv[1:7]
token = os.environ["GH_TOKEN"]
body = open(notes, encoding="utf-8").read()

def call(url, data=None, method=None, raw=False, ctype=None):
    if data is not None and not raw:
        data = json.dumps(data).encode()
    req = urllib.request.Request(url, data=data, method=method or ("POST" if data else "GET"))
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data is not None:
        req.add_header("Content-Type", ctype or "application/json")
    if raw:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        print("HTTP", e.code, e.read().decode()[:800]); sys.exit(1)

# 已存在就复用，避免重复创建
existing = None
try:
    r = call(f"https://api.github.com/repos/{owner}/{repo}/releases/tags/{tag}")
    existing = r
    print("已有 release：", r["html_url"])
except SystemExit:
    pass

if existing:
    rel = existing
else:
    rel = call(f"https://api.github.com/repos/{owner}/{repo}/releases", {
        "tag_name": tag, "name": name, "body": body,
        "draft": False, "prerelease": False,
    })
    print("release 已创建：", rel["html_url"])

assets = {a["name"]: a for a in rel.get("assets", [])}
fname = os.path.basename(apk)
if fname in assets:
    print("资产已存在，跳过上传：", fname)
else:
    url = rel["upload_url"].split("{")[0]
    data = open(apk, "rb").read()
    print(f"上传 {fname}  {len(data)} 字节 …")
    up = call(f"{url}?name={fname}", data=data, method="POST",
              raw=True, ctype="application/vnd.android.package-archive")
    print("资产已上传，", up["size"], "字节，下载地址：", up["browser_download_url"])

print("\n发布页：", rel["html_url"])
print("直链：  https://github.com/%s/%s/releases/download/%s/%s" % (owner, repo, tag, fname))
PY
