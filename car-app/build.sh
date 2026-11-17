#!/usr/bin/env bash
# Stage D build entry point. All toolchain paths are resolved inside the
# workspace so the build never depends on the caller's PATH.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE="$(cd "$HERE/.." && pwd)"
TC="$WORKSPACE/.toolchain"

export JAVA_HOME="$TC/jdk17"
export PATH="$JAVA_HOME/bin:$TC/gradle-8.7/bin:$PATH"
export ANDROID_HOME="$TC/android-sdk"
export ANDROID_SDK_ROOT="$TC/android-sdk"
export GRADLE_USER_HOME="$TC/gradle-home"

cd "$HERE"

case "${1:-assemble}" in
  assemble)
    shift || true
    "$TC/gradle-8.7/bin/gradle" --no-daemon assembleDebug "$@"
    ;;
  check)
    "$TC/gradle-8.7/bin/gradle" --no-daemon :app:compileDebugKotlin :app:lintDebug
    ;;
  clean)
    "$TC/gradle-8.7/bin/gradle" --no-daemon clean
    ;;
  *)
    "$TC/gradle-8.7/bin/gradle" --no-daemon "$@"
    ;;
esac
