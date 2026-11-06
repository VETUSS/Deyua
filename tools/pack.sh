#!/usr/bin/env bash
# pack.sh <label> [--release] [--apk <apk>] [--require <str>] [--forbid <str>]
#
#   repack -> zipalign -p 4 -> apksigner (v1/v2/v3) -> verify + .so alignment check
#   out: <repo>/out/Deyva-<label>.apk
#
# --release 使用 DEYVA_KS / DEYVA_KS_PASS 指定的私有密钥签名，并核对证书 SHA-256
#           与应用内 BuildIntegrity.OFFICIAL_CERT_SHA256 是否一致。
#           不加则用 AOSP debug 密钥 —— 那是公开密钥，签出的包只能自测。
#
# 需要的环境：
#   DEYVA_ROOT     仓库根（不设则按本脚本位置推断）
#   ANDROID_HOME   Android SDK，需含 build-tools/34.0.0
#   DEYVA_KS       仅 --release：keystore 路径
#   DEYVA_KS_PASS  仅 --release：keystore 密码
set -euo pipefail

ROOT="${DEYVA_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
  export PATH="$ANDROID_HOME/build-tools/34.0.0:$PATH"
fi

pick() {  # pick <tool>：优先 .bat（Windows / Git Bash），其次无后缀
  if command -v "$1.bat" >/dev/null 2>&1; then echo "$1.bat"
  elif command -v "$1" >/dev/null 2>&1; then echo "$1"
  else echo "!! 找不到 $1，请确认 ANDROID_HOME 下有 build-tools/34.0.0" >&2; exit 1; fi
}
APKSIGNER="$(pick apksigner)"
ZIPALIGN="$(pick zipalign)"

LABEL="${1:?usage: pack.sh <label> [--release] [--apk path] [repack args]}"
shift || true

RELEASE=0
ARGS=()
for a in "$@"; do
  if [ "$a" = "--release" ]; then RELEASE=1; else ARGS+=("$a"); fi
done

OUT="$ROOT/out/Deyva-${LABEL}.apk"
UNSIGNED="$ROOT/out/unsigned-${LABEL}.apk"
ALIGNED="$ROOT/out/aligned-${LABEL}.apk"
mkdir -p "$ROOT/out"

if [ "$RELEASE" = "1" ]; then
  : "${DEYVA_KS:?--release 需要 DEYVA_KS 指向你的 keystore}"
  : "${DEYVA_KS_PASS:?--release 需要 DEYVA_KS_PASS}"
  KS="$DEYVA_KS"
  KS_PASS="$DEYVA_KS_PASS"
  echo "[pack] 正式签名模式"
else
  KS="${DEYVA_KS:-}"
  KS_PASS="android"
  echo "[pack] 调试签名模式 —— 此包不可对外分发（debug 密钥是公开的）"
fi

if [ ${#ARGS[@]} -gt 0 ]; then
  python "$ROOT/tools/repack.py" --out "$UNSIGNED" "${ARGS[@]}"
else
  python "$ROOT/tools/repack.py" --out "$UNSIGNED"
fi

"$ZIPALIGN" -p -f 4 "$UNSIGNED" "$ALIGNED"

if [ -n "$KS" ]; then
  "$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT" "$ALIGNED"
else
  # 无 keystore 时退回 AOSP debug 密钥（Android SDK 自带）
  "$APKSIGNER" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
    --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT" "$ALIGNED"
fi
"$APKSIGNER" verify --print-certs "$OUT" | head -4

ACTUAL="$("$APKSIGNER" verify --print-certs "$OUT" \
  | grep -i 'SHA-256 digest' | head -1 | sed 's/.*: *//' | tr -d ':\r' | tr 'A-F' 'a-f')"

ABOUT="$ROOT/android/app/src/main/java/com/spektra/android/About.kt"
EXPECTED="$(grep -o 'OFFICIAL_CERT_SHA256 = "[0-9a-f]*"' "$ABOUT" | sed 's/.*"\([0-9a-f]*\)"/\1/' || true)"
echo "[pack] 证书指纹 期望=${EXPECTED:-（未设置）}"
echo "[pack] 证书指纹 实际=$ACTUAL"
if [ "$RELEASE" = "1" ]; then
  if [ -z "$EXPECTED" ]; then
    echo "!! About.kt 里没找到 OFFICIAL_CERT_SHA256 —— 装机后会一律显示「非官方构建」"
    exit 1
  fi
  if [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "!! 指纹不一致：这个包装上后会被判成「非官方构建」"
    exit 1
  fi
  echo "[pack] 指纹一致"
fi

python - "$OUT" <<'PY'
import sys, zipfile, struct
p = sys.argv[1]; bad = 0
z = zipfile.ZipFile(p); f = open(p, 'rb')
for i in z.infolist():
    if not i.filename.endswith('.so'):
        continue
    f.seek(i.header_offset); h = f.read(30)
    nlen, elen = struct.unpack('<HH', h[26:30])
    off = i.header_offset + 30 + nlen + elen
    ok = (i.compress_type == 0 and off % 4096 == 0)
    bad += 0 if ok else 1
    print('  %-42s method=%d mod4096=%d %s' %
          (i.filename, i.compress_type, off % 4096, 'OK' if ok else 'BAD'))
print('BAD entries:', bad)
sys.exit(1 if bad else 0)
PY
ls -la "$OUT"
