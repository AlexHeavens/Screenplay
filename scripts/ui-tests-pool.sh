#!/usr/bin/env bash
# Runs the always-run (@Smoke) device tests, plus any extra classes, across a pool of already booted devices.
# Usage: POOL_SERIALS="emulator-5582 emulator-5584" scripts/ui-tests-pool.sh [Class ...]
# Each worker claims the next unclaimed class, runs it on its own device, and reports one line per class.
# Classes run longest first (BookUiTest). Does not take run.lock: the pool devices belong to this run alone.
# Experimental; see docs/parallel-android-testing.md. Does not replace scripts/ui-tests.sh.
set -o pipefail
cd "$(dirname "$0")/.." || exit 1
adb=${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb
serials=${POOL_SERIALS:?set POOL_SERIALS to the booted devices, e.g. "emulator-5582 emulator-5584"}
start=$(date +%s)
./gradlew -q -Pabi=x86_64 assembleDebug assembleDebugAndroidTest || { echo "EXIT=1 WALL=$(( $(date +%s) - start ))s"; exit 1; }
built=$(date +%s)
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT; mkdir "$work/claimed"

# Smoke classes by their @Smoke annotation, with the longest known class first.
classes=$(grep -l '@Smoke' app/src/androidTest/java/com/screenplay/*.kt | xargs -n1 basename | sed 's/\.kt$//' | grep -vx 'Smoke' | sort)
# Classes named as arguments also run whole (every test, not only @Smoke) as extra entries "Class+full".
# Longest first: whole BookUiTest, then the other whole classes, then the smoke classes.
{ for c in BookUiTest "$@"; do [[ $c == BookUiTest && " $* " != *" BookUiTest "* ]] || echo "$c+full"; done | awk '!seen[$0]++'
  # A smoke entry for a class that also runs whole would only repeat tests the whole run covers, so it is left out.
  for c in $classes; do [[ " $* " == *" $c "* || $c == BookUiTest ]] || echo "$c"; done; } | grep -v '^$' > "$work/queue"

for s in $serials; do
  ( $adb -s "$s" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null &&
    $adb -s "$s" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null ) || { echo "install failed on $s"; exit 1; } &
done
wait
installed=$(date +%s)

instrument() { local s=$1; shift; $adb -s "$s" shell am instrument -w --no-window-animation "$@" com.screenplay.test/com.screenplay.TestRunner; }

# ForceStopTest kills the app between two runs, which no test can do to its own process: kill it from here.
force_stop() {
  local s=$1
  local log=$work/force-stop-play.$s rc=0
  $adb -s "$s" shell run-as com.screenplay rm -f files/force-stop-ready
  instrument "$s" -e class com.screenplay.ForceStopTest -e phase play > "$log" 2>&1 &
  local play_pid=$!
  for _ in $(seq 1 240); do
    $adb -s "$s" shell run-as com.screenplay ls files/force-stop-ready >/dev/null 2>&1 && break
    sleep 1
  done
  $adb -s "$s" shell run-as com.screenplay ls files/force-stop-ready >/dev/null 2>&1 || { cat "$log"; rc=1; }
  $adb -s "$s" shell am force-stop com.screenplay
  wait $play_pid
  instrument "$s" -e class com.screenplay.ForceStopTest -e phase check | grep -q '^OK (' || rc=1
  return $rc
}

worker() {
  local s=$1 c t0 out rc
  while :; do
    c=$(while read -r name; do mkdir "$work/claimed/$name" 2>/dev/null && { echo "$name"; break; }; done < "$work/queue")
    [ -n "$c" ] || return 0
    t0=$(date +%s)
    if [[ $c == *+full ]]; then
      out=$(instrument "$s" -e class "com.screenplay.${c%+full}"); grep -q '^OK (' <<<"$out"; rc=$?
    elif [ "$c" = ForceStopTest ]; then
      force_stop "$s"; rc=$?
      out="forcestop"
    else
      out=$(instrument "$s" -e annotation com.screenplay.Smoke -e class "com.screenplay.$c"); grep -q '^OK (' <<<"$out"; rc=$?
    fi
    printf '%s\t%s\t%s\t%ss\trc=%s\n' "$c" "$s" "$(grep -o '^OK ([0-9]* tests\?)\|Tests run: [0-9]*,  Failures: [0-9]*' <<<"$out" | head -1)" "$(( $(date +%s) - t0 ))" "$rc" >> "$work/results.tsv"
    [ $rc -eq 0 ] || printf '%s\n' "$out" > "$work/fail.$c.log"
  done
}

for s in $serials; do worker "$s" & done
wait
sort "$work/results.tsv"
fails=$(grep -vc 'rc=0$' "$work/results.tsv")
for f in "$work"/fail.*.log; do [ -e "$f" ] && { echo "--- $(basename "$f")"; grep -E 'FAILURES|Failures|Time:|^[0-9]+\) |Exception|Error|^\s+at com\.screenplay' "$f" | head -16; }; done
echo "EXIT=$([ "$fails" -eq 0 ] && echo 0 || echo 1) devices=$(wc -w <<<"$serials") classes=$(wc -l < "$work/results.tsv") failed=$fails BUILD=$(( built - start ))s INSTALL=$(( installed - built ))s RUN=$(( $(date +%s) - installed ))s WALL=$(( $(date +%s) - start ))s"
[ "$fails" -eq 0 ]
