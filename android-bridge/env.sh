#!/usr/bin/env bash
# Sourced by build.sh and check.sh. Resolves all toolchain paths relative to the
# workspace, so the build is reproducible and never depends on the caller's PATH.
# All paths use forward slashes (MSYS2/Git Bash convention on Windows).

BRIDGE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE="$(cd "$BRIDGE_DIR/.." && pwd)"
TC="$WORKSPACE/.toolchain"

export GOROOT="$TC/gotool/go"
export GOPATH="$TC/gopath"
export GOBIN="$TC/gotool/bin"
export PATH="$TC/jdk8/bin:$TC/gotool/bin:$GOROOT/bin:$GOBIN:$PATH"

# Module mirrors: proxy.golang.org returns 502 through the local proxy; goproxy.cn times out.
export GOPROXY="${GOPROXY:-https://mirrors.aliyun.com/goproxy,direct}"
export GOSUMDB="${GOSUMDB:-off}"
export GOFLAGS="${GOFLAGS:--mod=mod}"
export GOTOOLCHAIN=local

# Android SDK / NDK / JDK
export ANDROID_HOME="$TC/android-sdk"
export ANDROID_SDK_ROOT="$TC/android-sdk"
export JAVA_HOME="$TC/jdk8"

# Build parameters (override via environment if needed)
export TARGET="${TARGET:-android/arm64}"
export ANDROID_API="${ANDROID_API:-24}"
export AAR_OUT="${AAR_OUT:-$WORKSPACE/build/mygobridge.aar}"

check_toolchain() {
  local missing=0
  local required=(
    "$GOROOT/bin/go.exe"
    "$GOBIN/gomobile.exe"
    "$GOBIN/gobind.exe"
    "$JAVA_HOME/bin/javac.exe"
    "$ANDROID_HOME/platforms/android-35/android.jar"
  )
  local ndk_count
  ndk_count=$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l)
  for f in "${required[@]}"; do
    if [ ! -e "$f" ]; then
      echo "MISSING: $f" >&2
      missing=1
    fi
  done
  if [ "$ndk_count" -lt 1 ]; then
    echo "MISSING: no NDK under $ANDROID_HOME/ndk" >&2
    missing=1
  fi
  if [ "$missing" -ne 0 ]; then
    echo "Toolchain incomplete. See ANDROID_PORT_PLAN.md section 8." >&2
    return 1
  fi
  return 0
}
