#!/usr/bin/env bash
# On-demand OCR benchmark. Not part of the build or of scripts/ui-tests.sh.
#   run.sh setup                         venv and Tesseract models in $OCR_BENCH_HOME (default ~/.cache/ocr-bench)
#   run.sh emulator [widths] [modes]     ML Kit on the shared emulator, for example: run.sh emulator 1600 argb,gray
#   run.sh desktop <engine> [args...]    a desktop engine, for example: run.sh desktop tesseract-fast --width 2400
#   run.sh score <run.json>...           score runs; the first one is the baseline
# Run files go to tools/ocr-bench/results/ (emulator: $OCR_BENCH_OUT if set). Commit the ones worth keeping.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
home=${OCR_BENCH_HOME:-$HOME/.cache/ocr-bench}
py="$home/venv/bin/python -I"

case ${1:-} in
setup)
  mkdir -p "$home"
  [ -x "$home/venv/bin/python" ] || python3 -m venv "$home/venv"
  "$home/venv/bin/pip" install --quiet jiwer pymupdf pillow numpy tesserocr rapidocr onnxruntime
  # Each model set in its own directory; these are data files, read only by Tesseract.
  for set in fast best; do
    mkdir -p "$home/tessdata_$set"
    for lang in eng fra rus; do
      f="$home/tessdata_$set/$lang.traineddata"
      [ -s "$f" ] || curl --fail --location --silent --show-error --output "$f" \
        "https://github.com/tesseract-ocr/tessdata_$set/raw/main/$lang.traineddata"
    done
  done
  ;;
emulator)
  widths=${2:-1600} modes=${3:-argb}
  : "${ANDROID_HOME:?set ANDROID_HOME}" "${JAVA_HOME:?set JAVA_HOME}"
  adb=$ANDROID_HOME/platform-tools/adb
  (cd "$repo" && ./gradlew --quiet -Pabi=x86_64 assembleDebug assembleDebugAndroidTest)
  # The emulator is shared: wait for UI test runs, then hold the same lock they use.
  while pgrep -f "bash scripts/ui-tests" >/dev/null; do sleep 20; done
  state=${XDG_CACHE_HOME:-$HOME/.cache}/pdfaudiobook-emulator
  exec 9>"$state/run.lock"; flock 9
  export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5580}
  trap 'touch "$state/last-used"' EXIT
  $adb install -r -t "$repo/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
  $adb install -r -t "$repo/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
  dev=/sdcard/Android/data/com.screenplay/files
  $adb shell "rm -rf $dev/ocr-bench $dev/ocr-bench-out; mkdir -p $dev/ocr-bench"
  mapfile -t pdfs < <($py "$here/corpus.py")
  $adb push "${pdfs[@]}" $dev/ocr-bench/ >/dev/null
  $adb shell am instrument -w -e ocrBench true -e widths "$widths" -e modes "$modes" \
    -e class com.screenplay.OcrBenchmarkTest com.screenplay.test/com.screenplay.TestRunner
  out=$(mktemp -d)
  $adb pull $dev/ocr-bench-out "$out" >/dev/null
  cores=$($adb shell nproc | tr -d '\r')
  $py "$here/collect.py" "$out/ocr-bench-out" --host "emulator $ANDROID_SERIAL x86_64 $cores cores" --out "${OCR_BENCH_OUT:-$here/results}"
  ;;
desktop)
  shift
  OMP_THREAD_LIMIT=1 $py "$here/desktop.py" "$@"
  ;;
score)
  shift
  $py "$here/score.py" "$@"
  ;;
*)
  sed -n '2,7p' "$0"
  exit 2
  ;;
esac
