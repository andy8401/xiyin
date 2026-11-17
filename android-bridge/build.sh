#!/usr/bin/env bash
# One-shot AAR build. Usage: ./build.sh
set -euo pipefail
source "$(dirname "$0")/env.sh"

check_toolchain

echo "== Toolchain =="
echo "  go:      $($GOROOT/bin/go.exe version)"
echo "  javac:   $($JAVA_HOME/bin/javac.exe -version 2>&1)"
echo "  sdk:     $ANDROID_HOME"
echo "  target:  $TARGET (api $ANDROID_API)"
echo "  output:  $AAR_OUT"

mkdir -p "$(dirname "$AAR_OUT")"
rm -f "$AAR_OUT"

cd "$BRIDGE_DIR"

# Go 1.27 requires the gobind tool dependency be declared in go.mod.
# gomobile also needs gobind discoverable via PATH (set in env.sh).
echo "== gomobile bind =="
gomobile bind \
  -target="$TARGET" \
  -androidapi="$ANDROID_API" \
  -o="$AAR_OUT" \
  ./facade

echo "== Result =="
ls -lh "$AAR_OUT"
echo "BUILD_OK"
