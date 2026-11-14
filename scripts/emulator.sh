#!/usr/bin/env bash
# Keeps one warm emulator for the device tests and stops it after 30 minutes without a test run.
# Usage: scripts/emulator.sh [ensure|stop|status]; `ensure` (the default) prints the serial on stdout.
# The emulator boots from its quick-boot snapshot, which it saves when it stops.
set -euo pipefail
sdk=$(sed -n 's/^sdk.dir=//p' "$(dirname "$0")/../local.properties")
sdk=${ANDROID_HOME:-${sdk:-$HOME/Android/sdk}}
adb=$sdk/platform-tools/adb
avd=pdfaudiobook-ui
port=5580
serial=emulator-$port
idle_limit_seconds=$((30 * 60))
state=${XDG_CACHE_HOME:-$HOME/.cache}/pdfaudiobook-emulator
mkdir -p "$state"

online() { [ "$($adb -s $serial get-state 2>/dev/null)" = device ]; }
booted() { [ "$($adb -s $serial shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }

# Stops the emulator once no test run has held the run lock for the idle limit.
watch_idle() {
  while online; do
    sleep 60
    if ! flock --nonblock "$state/run.lock" true 2>/dev/null; then touch "$state/last-used"; continue; fi
    last_used=$(stat --format %Y "$state/last-used" 2>/dev/null || echo 0)
    if [ $(( $(date +%s) - last_used )) -ge $idle_limit_seconds ]; then $adb -s $serial emu kill >/dev/null 2>&1; fi
  done
}

case "${1:-ensure}" in
  ensure)
    exec 8>"$state/start.lock"; flock 8
    if ! online; then
      if ! "$sdk/emulator/emulator" -list-avds | grep -qx "$avd"; then
        echo no | "$sdk"/cmdline-tools/latest/bin/avdmanager create avd --name $avd --device pixel_6 \
          --package "system-images;android-34;aosp_atd;x86_64" >&2
      fi
      setsid nohup "$sdk/emulator/emulator" -avd $avd -port $port -no-window -no-audio -no-boot-anim \
        -gpu swiftshader_indirect >"$state/emulator.log" 2>&1 8>&- 9>&- &
      $adb -s $serial wait-for-device
      until booted; do sleep 1; done
      touch "$state/last-used"
      setsid nohup "$0" watch >/dev/null 2>&1 8>&- 9>&- &
    fi
    touch "$state/last-used"
    echo $serial ;;
  watch) watch_idle ;;
  stop) $adb -s $serial emu kill; while pgrep -f "emulator.*-port $port" >/dev/null; do sleep 1; done ;;
  status) online && echo "$serial up, last used $(stat --format %y "$state/last-used")" || echo "$serial down" ;;
  *) echo "usage: $0 [ensure|stop|status]" >&2; exit 2 ;;
esac
