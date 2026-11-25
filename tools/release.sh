#!/bin/bash
# 發 GW 測試版（prerelease），並把 feed release 的 update.json 指到這一版，App 內更新就讀得到。
# 用法：tools/release.sh "更新說明"
# 發之前先把 app/build.gradle 的 versionCode 調高（versionName 視需要）。
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="JJptt1980/5G-Proxy-Pro"
APK_NAME="5G-Proxy-Pro-GW.apk"
FEED_TAG="feed"
NOTES="${1:-}"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

CODE=$(sed -n 's/^ *versionCode \([0-9]*\).*/\1/p' app/build.gradle | head -1)
NAME=$(sed -n 's/^ *versionName "\(.*\)".*/\1/p' app/build.gradle | head -1)
TAG="v${NAME}-${CODE}"

PUBLISHED=$(gh release download "$FEED_TAG" --repo "$REPO" --pattern update.json --output - 2>/dev/null \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['versionCode'])" 2>/dev/null || echo 0)
if [ "$CODE" -le "$PUBLISHED" ]; then
  echo "versionCode $CODE 沒有大於已發佈的 $PUBLISHED，請先調高 app/build.gradle 的 versionCode。" >&2
  exit 1
fi

./gradlew -q testDebugUnitTest assembleDebug

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
cp app/build/outputs/apk/debug/app-debug.apk "$OUT/$APK_NAME"
SHA=$(shasum -a 256 "$OUT/$APK_NAME" | cut -d' ' -f1)
[ -n "$NOTES" ] || NOTES="測試版 ${NAME}"

python3 - "$OUT/update.json" "$CODE" "$NAME" "https://github.com/${REPO}/releases/download/${TAG}/${APK_NAME}" "$SHA" "$NOTES" <<'PY'
import json, sys
path, code, name, url, sha, notes = sys.argv[1:]
with open(path, "w", encoding="utf-8") as f:
    json.dump({"versionCode": int(code), "versionName": name, "apkUrl": url, "sha256": sha, "notes": notes},
              f, ensure_ascii=False, indent=2)
PY

# 一律 prerelease：不算「一天一次正式版」。
gh release create "$TAG" "$OUT/$APK_NAME" "$OUT/update.json" \
  --repo "$REPO" --title "${NAME} (${CODE})" --notes "$NOTES" --prerelease

if ! gh release view "$FEED_TAG" --repo "$REPO" >/dev/null 2>&1; then
  gh release create "$FEED_TAG" --repo "$REPO" --prerelease --title "更新資訊" \
    --notes "App 內更新讀這裡的 update.json，指向最新的測試版。"
fi
gh release upload "$FEED_TAG" "$OUT/update.json" --repo "$REPO" --clobber

echo "已發佈 ${TAG}：https://github.com/${REPO}/releases/tag/${TAG}"
