#!/usr/bin/env bash
# Starts or stops a pool of redroid containers (Android 14 x86_64 in Docker, shared host kernel) for UI tests.
# Usage: scripts/redroid-pool.sh start N | stop | serials   (needs binder_linux loaded and binderfs mounted)
# `start` prints the pool's adb serials, ready for POOL_SERIALS in scripts/ui-tests-pool.sh. Experimental.
set -euo pipefail
adb=${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb
image=redroid/redroid:14.0.0-latest
base_port=5601
case "${1:-}" in
  start)
    n=${2:?usage: start N}
    [ -e /dev/binderfs/binder ] || { echo "binderfs missing: sudo modprobe binder_linux devices=binder,hwbinder,vndbinder; sudo mount -t binder binder /dev/binderfs" >&2; exit 1; }
    for i in $(seq 1 "$n"); do
      docker container inspect "rd$i" >/dev/null 2>&1 || docker run --detach --rm --privileged --name "rd$i" --publish "127.0.0.1:$((base_port + i - 1)):5555" "$image" \
        androidboot.redroid_gpu_mode=guest androidboot.redroid_width=1080 androidboot.redroid_height=2400 androidboot.redroid_dpi=420 >/dev/null
    done
    sleep 3
    for i in $(seq 1 "$n"); do
      s=127.0.0.1:$((base_port + i - 1)); $adb connect "$s" >/dev/null
      until [ "$($adb -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 1; done
      # Gestural navigation, as on the emulator: the three-button bar would take screen height and push the options chips off the bottom.
      $adb -s "$s" shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural >/dev/null 2>&1
      echo "$s"
    done | paste -sd' ' ;;
  stop) docker ps --format '{{.Names}}' | grep -E '^rd[0-9]+$' | xargs -r docker rm --force >/dev/null ;;
  serials) docker ps --format '{{.Names}}' | grep -E '^rd[0-9]+$' | sort | sed -E 's/rd([0-9]+)/echo 127.0.0.1:$((5600+\1))/e' | paste -sd' ' ;;
  *) echo "usage: $0 start N | stop | serials" >&2; exit 2 ;;
esac
