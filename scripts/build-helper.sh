#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [ -n "${HILIGHT_API_JAR:-}" ]; then
  API_JAR="$HILIGHT_API_JAR"
else
  API_JAR="$(find "$SDK/platforms" -maxdepth 2 -path '*/android-37*/android.jar' -print \
    | sort -V | tail -1)"
fi
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
JAVAC="${JAVAC:-$(command -v javac || echo "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javac")}"

[ -n "$API_JAR" ] && [ -f "$API_JAR" ] \
  || { echo "missing an Android 17 / API 37 android.jar"; exit 1; }
case "$API_JAR" in
  */platforms/android-37*/android.jar) ;;
  *) [ -n "${HILIGHT_API_JAR:-}" ] \
       || { echo "selected platform is not Android 37: $API_JAR"; exit 1; } ;;
esac
echo "using $API_JAR"

OUT="$ROOT/helper/build"
rm -rf "$OUT" && mkdir -p "$OUT/classes"

"$JAVAC" --release 17 -nowarn -classpath "$API_JAR" -d "$OUT/classes" \
  "$ROOT"/core/src/com/hilight/core/*.java

"$BT/d8" --lib "$API_JAR" --output "$OUT" "$OUT"/classes/com/hilight/core/*.class 2>&1 \
  | grep -v "API level" || true

mv "$OUT/classes.dex" "$OUT/hilight-helper.dex"
echo "built $OUT/hilight-helper.dex"
