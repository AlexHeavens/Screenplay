#!/usr/bin/env bash
# Runs the device tests through adb rather than `gradle connectedDebugAndroidTest`, skipping
# Gradle's per-run test-platform setup. No JUnit report; CI keeps the Gradle task.
# Default: a pool of 2 redroid containers (scripts/redroid-pool.sh, started if down, stopped after 30 idle minutes).
# Without binderfs, or with USE_EMULATOR=1, it uses the warm emulator from scripts/emulator.sh instead; ANDROID_SERIAL picks one device.
# With POOL_SERIALS="serial ..." (for example from scripts/redroid-pool.sh), claims the first device of the pool that no other run holds,
# and waits when all are busy. Each device has its own lock, so runs on different devices go in parallel; runs on one device queue.
# Optional arg: `smoke` (the always-run tests), Class or Class#method.
set -o pipefail
cd "$(dirname "$0")/.." || exit 1
adb=${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb
start=$(date +%s)
# Build only the target's CPU type (the warm emulator is x86_64); another ANDROID_SERIAL device is asked for its own.
abi=x86_64
[ -n "$ANDROID_SERIAL" ] && abi=$($adb shell getprop ro.product.cpu.abi | tr -d '\r')
# Redroid is the default when the host has binderfs and no device was named.
[ -z "$USE_EMULATOR" ] && [ -z "$ANDROID_SERIAL" ] && [ -z "$POOL_SERIALS" ] && [ -e /dev/binderfs/binder ] && POOL_SERIALS=$(scripts/redroid-pool.sh ensure 2 9>&-)
[ -n "$POOL_SERIALS" ] && abi=x86_64
./gradlew -q ${abi:+-Pabi=$abi} assembleDebug assembleDebugAndroidTest || { echo "EXIT=1 WALL=$(( $(date +%s) - start ))s"; exit 1; }
state=${XDG_CACHE_HOME:-$HOME/.cache}/pdfaudiobook-emulator
mkdir -p "$state"
if [ -n "$POOL_SERIALS" ]; then
    # Fd 9 holds the claimed device's lock until this script exits.
    claimed=
    until [ -n "$claimed" ]; do
        for s in $POOL_SERIALS; do
            exec 9>"$state/run-${s//[:\/]/_}.lock"
            flock --nonblock 9 && { claimed=$s; break; }
        done
        [ -n "$claimed" ] || sleep 2
    done
    export ANDROID_SERIAL=$claimed
else
    exec 9>"$state/run.lock"; flock 9
    # Close the lock in the emulator daemon, or it would hold the lock after this script exits.
    export ANDROID_SERIAL=${ANDROID_SERIAL:-$(scripts/emulator.sh ensure 9>&-)}
fi
trap 'touch "$state/last-used"' EXIT
$adb install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null &&
$adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null || exit 1
instrument() { $adb shell am instrument -w --no-window-animation "$@" com.screenplay.test/com.screenplay.TestRunner; }
# shellcheck disable=SC2046  # the instrument arguments are split on purpose
out=$(instrument $( [ "$1" = smoke ] && echo "-e annotation com.screenplay.Smoke" || echo "${1:+-e class com.screenplay.$1}" ))
echo "$out"
# am instrument exits 0 even when tests fail, so judge by the runner's summary line.
grep -q '^OK (' <<<"$out"; rc=$?
# ForceStopTest needs the app killed between two runs, which no test can do to its own process: kill it from here once the first run says it is playing.
if [ "$1" = smoke ] || [ "$1" = ForceStopTest ]; then
    $adb shell run-as com.screenplay rm -f files/force-stop-ready
    instrument -e class com.screenplay.ForceStopTest -e phase play > /tmp/force-stop-play.$$.log 2>&1 &
    play_pid=$!
    for _ in $(seq 1 240); do
        $adb shell run-as com.screenplay ls files/force-stop-ready >/dev/null 2>&1 && break
        sleep 1
    done
    $adb shell run-as com.screenplay ls files/force-stop-ready >/dev/null 2>&1 || { echo "ForceStopTest never reached the point to kill at:"; cat /tmp/force-stop-play.$$.log; rc=1; }
    $adb shell am force-stop com.screenplay
    wait $play_pid
    check=$(instrument -e class com.screenplay.ForceStopTest -e phase check)
    echo "ForceStopTest after force-stop:"; echo "$check"
    grep -q '^OK (' <<<"$check" || rc=1
    rm -f /tmp/force-stop-play.$$.log
fi
echo "EXIT=$rc WALL=$(( $(date +%s) - start ))s"
exit $rc
