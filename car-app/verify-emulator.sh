#!/usr/bin/env bash
# Stage F emulator smoke verification.
# Prerequisite: the machine has been rebooted with WHPX active
# (hypervisorlaunchtype=Auto + HypervisorPlatform/VirtualMachinePlatform enabled).
#
# Usage:
#   Terminal 1: bash start-emulator.sh        # foreground, keep running
#   Terminal 2: bash verify-emulator.sh       # this script
set -uo pipefail

WS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="$WS/.toolchain/android-sdk"
ADB="$SDK/platform-tools/adb.exe"
# Default: x86_64 emulator APK. For the software ARM64 AVD use:
#   APK="$WS/build/mygocar-arm64.apk" bash verify-emulator.sh
APK="${APK:-$WS/build/mygocar-emulator.apk}"

echo "== 0. wait for emulator device =="
DEV=""
for i in $(seq 1 60); do
  DEV=$("$ADB" devices 2>/dev/null | awk '/emulator-/ {print $1; exit}')
  if [ -n "$DEV" ]; then break; fi
  sleep 2
done
if [ -z "$DEV" ]; then
  echo "No emulator device. Start it first with: bash start-emulator.sh" >&2
  exit 1
fi
"$ADB" devices

echo "== 1. wait for boot =="
for i in $(seq 1 180); do
  boot=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  if [ "$boot" = "1" ]; then echo "boot_completed=1 after $((i*4))s"; break; fi
  sleep 4
done
boot=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
[ "$boot" = "1" ] || { echo "BOOT_TIMEOUT"; exit 1; }

echo "== 1b. force landscape for car UI =="
# settings put user_rotation alone is ignored while auto-rotation is on.
"$ADB" shell wm user-rotation lock 1 2>/dev/null || true
"$ADB" shell settings put system accelerometer_rotation 0 2>/dev/null || true

echo "== 2. install APK =="
echo "APK=$APK"
"$ADB" install -r "$APK" || { echo "INSTALL_FAILED"; exit 1; }

echo "== 3. clear logcat + launch app =="
"$ADB" logcat -c
"$ADB" shell monkey -p com.andy8401.mygo.car -c android.intent.category.LAUNCHER 1 2>&1 | tail -3
sleep 12

echo "== 4. capture evidence =="
echo "-- process --"
"$ADB" shell ps -A 2>/dev/null | grep -i mygo || echo "process not found"
echo "-- Go bridge / plugin / JNI log lines --"
"$ADB" logcat -d 2>/dev/null | grep -iE 'GoBridge|GoMediaOutput|GoHostBridge|GoListener|GoTransport|moejs|dlopen.*gojni|facade|AndroidRuntime|FATAL' | tail -80

echo "== 5. screenshot =="
# Git Bash converts /sdcard/... into a Windows path unless path conversion is off.
MSYS_NO_PATHCONV=1 "$ADB" shell screencap -p /sdcard/mygo_car.png
MSYS_NO_PATHCONV=1 "$ADB" pull /sdcard/mygo_car.png "$WS/build/mygo_car_emulator.png" 2>/dev/null && echo "screenshot -> build/mygo_car_emulator.png"

echo "VERIFY_DONE"
