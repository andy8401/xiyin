#!/usr/bin/env bash
# Launch an AVD in the foreground. Run in one terminal, then run
# verify-emulator.sh from another terminal.
#
# Default: fast x86_64 AVD with WHPX.
# Software fallback (no BIOS virtualization needed):
#   AVD=mygo_car_arm64_avd ACCEL=off bash start-emulator.sh
set -euo pipefail
WS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="$WS/.toolchain/android-sdk"
AVD="${AVD:-mygo_car_avd}"
ACCEL="${ACCEL:-on}"

if [ "$ACCEL" = "on" ] && [ "$(powershell.exe -NoProfile -Command '(Get-CimInstance Win32_ComputerSystem).HypervisorPresent' 2>/dev/null | tr -d '\r')" != "True" ]; then
  echo "WARNING: HypervisorPresent is False. Enable Intel VT-x in BIOS and reboot with WHPX, or use ACCEL=off with the ARM64 AVD." >&2
fi

export ANDROID_HOME="$SDK"
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy
exec "$SDK/emulator/emulator.exe" -avd "$AVD" -accel "$ACCEL" -no-boot-anim -no-snapshot -gpu swiftshader_indirect
