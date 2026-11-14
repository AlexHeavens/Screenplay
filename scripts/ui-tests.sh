#!/usr/bin/env bash
# Runs the device tests through adb rather than `gradle connectedDebugAndroidTest`, skipping
# Gradle's per-run test-platform setup. No JUnit report; CI keeps the Gradle task.
# Uses the warm emulator from scripts/emulator.sh (started if down, stopped after 30 idle minutes), or ANDROID_SERIAL if set.
# Runs are serialised, so several sessions can share the emulator. Optional arg: `smoke` (the always-run tests), Class or Class#method.
set -o pipefail
cd "$(dirname "$0")/.."
adb=${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb
start=$(date +%s)
# Build only the target's CPU type (the warm emulator is x86_64); another ANDROID_SERIAL device is asked for its own.
abi=x86_64
[ -n "$ANDROID_SERIAL" ] && abi=$($adb shell getprop ro.product.cpu.abi | tr -d '\r')
./gradlew -q ${abi:+-Pabi=$abi} assembleDebug assembleDebugAndroidTest || { echo "EXIT=1 WALL=$(( $(date +%s) - start ))s"; exit 1; }
state=${XDG_CACHE_HOME:-$HOME/.cache}/pdfaudiobook-emulator
mkdir -p "$state"; exec 9>"$state/run.lock"; flock 9
export ANDROID_SERIAL=${ANDROID_SERIAL:-$(scripts/emulator.sh ensure)}
trap 'touch "$state/last-used"' EXIT
$adb install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null &&
$adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null || exit 1
instrument() { $adb shell am instrument -w --no-window-animation "$@" com.pdfaudiobook.test/com.pdfaudiobook.TestRunner; }
out=$(instrument $( [ "$1" = smoke ] && echo "-e annotation com.pdfaudiobook.Smoke" || echo "${1:+-e class com.pdfaudiobook.$1}" ))
echo "$out"
# am instrument exits 0 even when tests fail, so judge by the runner's summary line.
grep -q '^OK (' <<<"$out"; rc=$?
# ForceStopTest needs the app killed between two runs, which no test can do to its own process: kill it from here once the first run says it is playing.
if [ "$1" = smoke ] || [ "$1" = ForceStopTest ]; then
    $adb shell run-as com.pdfaudiobook rm -f files/force-stop-ready
    instrument -e class com.pdfaudiobook.ForceStopTest -e phase play > /tmp/force-stop-play.$$.log 2>&1 &
    play_pid=$!
    for _ in $(seq 1 240); do
        $adb shell run-as com.pdfaudiobook ls files/force-stop-ready >/dev/null 2>&1 && break
        sleep 1
    done
    $adb shell run-as com.pdfaudiobook ls files/force-stop-ready >/dev/null 2>&1 || { echo "ForceStopTest never reached the point to kill at:"; cat /tmp/force-stop-play.$$.log; rc=1; }
    $adb shell am force-stop com.pdfaudiobook
    wait $play_pid
    check=$(instrument -e class com.pdfaudiobook.ForceStopTest -e phase check)
    echo "ForceStopTest after force-stop:"; echo "$check"
    grep -q '^OK (' <<<"$check" || rc=1
    rm -f /tmp/force-stop-play.$$.log
fi
echo "EXIT=$rc WALL=$(( $(date +%s) - start ))s"
exit $rc
