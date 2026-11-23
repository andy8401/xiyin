#!/usr/bin/env bash
# Verify the built AAR: structure + generated Java API signatures.
set -euo pipefail
source "$(dirname "$0")/env.sh"

AAR="${1:-$AAR_OUT}"
if [ ! -f "$AAR" ]; then
  echo "AAR not found: $AAR" >&2
  exit 1
fi

echo "== AAR: $(basename "$AAR") =="
ls -lh "$AAR"
echo
echo "== Contents =="
unzip -l "$AAR" | head -20

TMP="$WORKSPACE/build/.aarcheck"
rm -rf "$TMP"
mkdir -p "$TMP"
cd "$TMP"
unzip -q -o "$AAR" classes.jar
unzip -q -o classes.jar

echo
echo "== Exported Java API =="
for cls in facade.Facade facade.HostBridge facade.MediaOutput facade.Listener; do
  echo "--- $cls ---"
  "$JAVA_HOME/bin/javap.exe" "$cls" 2>/dev/null
done

echo
echo "== Required API presence =="
api="$("$JAVA_HOME/bin/javap.exe" facade.Facade 2>/dev/null)"
missing=0
for fn in setHostBridge setMediaOutput setListener init loadPlugin listPluginsJSON browseJSON searchJSON openDetailJSON lyricsJSON playQueueJSON playStationJSON playAt queueJSON togglePlay next prev seekMs setVolume setShuffle cycleRepeat saveSession resumeSession reloadCurrent refreshContent ended stateJSON resolveStreamJSON settingsJSON setSettingJSON hasCapability version appSettingsJSON setSettingsJSON saveSessionJSON restoreSessionJSON clearSession addLocalFolders localFoldersJSON rescanLibrary localTracksJSON localSearchJSON installPluginFromPath installPluginFromRepo uninstallPlugin pluginOriginsJSON checkPluginUpdatesJSON signInJSON signOutJSON accountJSON; do
  if echo "$api" | grep -q " $fn("; then
    printf "  ok   %s\n" "$fn"
  else
    printf "  MISS %s\n" "$fn"
    missing=1
  fi
done
if [ "$missing" -ne 0 ]; then
  echo "CHECK_FAIL: missing exported API" >&2
  exit 1
fi

echo
echo "CHECK_OK"
