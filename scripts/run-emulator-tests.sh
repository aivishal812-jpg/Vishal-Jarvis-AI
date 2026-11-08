#!/usr/bin/env bash
# Runs the instrumentation suite on the emulator started by CI.
#
# Every phase is time-boxed: a hung WebView, Activity or instrumentation process
# must fail loudly with the last lines of its log instead of burning the whole
# job allowance. Failures are reported as GitHub error annotations because the
# workflow log itself is not always retrievable.
set -uo pipefail

apks=${1:-test-apks}
LOG_DIR=${EMULATOR_LOG_DIR:-/tmp}
TEST_TIMEOUT=${EMULATOR_TEST_TIMEOUT:-420}
BOOT_TIMEOUT=${EMULATOR_BOOT_TIMEOUT:-420}
BUDGET=${EMULATOR_TEST_BUDGET:-1500}
APP=com.myraa.mobile
WORKER="$LOG_DIR/worker.txt"

mkdir -p "$LOG_DIR"
started=$(date +%s)
failures=0

say() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*"; }
elapsed() { echo $(( $(date +%s) - started )); }
remaining() { echo $(( BUDGET - $(elapsed) )); }
escape() {
  local text=${1//'%'/'%25'}
  text=${text//$'\r'/'%0D'}
  text=${text//$'\n'/'%0A'}
  printf '%s' "$text"
}
# Surfaces the tail of a log as an annotation, so the cause is visible without
# downloading artifacts.
annotate() {
  local title=$1 file=$2 tail_text
  tail_text=$(tail -c 4000 "$file" 2>/dev/null)
  [[ -n $tail_text ]] || tail_text="(no output captured)"
  echo "::error title=$(escape "$title")::$(escape "$tail_text")"
  say "FAILED: $title"
}
note() { echo "::notice title=$(escape "$1")::$(escape "$2")"; }
dump_logcat() {
  say 'Dumping logcat for the failing run'
  timeout 120 adb logcat -d -v threadtime >"$LOG_DIR/logcat.txt" 2>&1 || true
  timeout 60 adb shell dumpsys activity processes >"$LOG_DIR/processes.txt" 2>&1 || true
}

say "Emulator runner started: budget ${BUDGET}s, per-test timeout ${TEST_TIMEOUT}s"

# ---- device availability -------------------------------------------------
if ! timeout 180 adb wait-for-device >"$WORKER" 2>&1; then
  annotate 'Emulator never appeared over adb' "$WORKER"
  exit 1
fi
boot_deadline=$(( $(date +%s) + BOOT_TIMEOUT ))
while :; do
  if [[ $(timeout 30 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n') == 1 ]]; then break; fi
  if (( $(date +%s) > boot_deadline )); then
    timeout 30 adb shell getprop >"$LOG_DIR/getprop.txt" 2>&1 || true
    annotate 'Emulator did not finish booting' "$LOG_DIR/getprop.txt"
    dump_logcat
    exit 1
  fi
  sleep 5
done
say "Emulator booted after $(elapsed)s"

# ---- install -------------------------------------------------------------
install_apk() {
  local apk=$1 label=$2
  say "Installing $label"
  if ! timeout 300 adb install -r "$apk" >"$WORKER" 2>&1; then
    annotate "Could not install $label" "$WORKER"
    dump_logcat
    exit 1
  fi
  grep -qi 'Success' "$WORKER" || say "$(cat "$WORKER")"
}

# The staged APK name carries the app version; never hard-code it here again.
app_apk=$(find "$apks" -maxdepth 1 -name 'Vishal-Jarvis-*-debug.apk' | sort | head -n 1)
if [[ -z $app_apk ]]; then
  annotate 'Application APK missing from the downloaded artifact' /dev/null
  exit 1
fi
install_apk "$app_apk" 'the application APK'
install_apk "$apks/Vishal-Jarvis-tests.apk" 'the instrumentation APK'

# Grant only permissions needed by the fixture tests. CALL_PHONE is intercepted
# by the test launcher, so no real call is placed; READ_PHONE_STATE stays
# ungranted until a test explicitly checks the existing-grant compatibility path.
grant_fixture_permissions() {
  timeout 60 adb shell pm grant $APP android.permission.RECORD_AUDIO || true
  timeout 60 adb shell pm grant $APP android.permission.CALL_PHONE || true
  timeout 60 adb shell pm grant $APP android.permission.POST_NOTIFICATIONS || true
}
grant_fixture_permissions

# Each instrumentation must start from the state of a freshly installed app.
# Otherwise a saved key, task queue or history from a previous suite changes the
# result of the next one.
reset_app() {
  say "Resetting $APP to a freshly installed state"
  timeout 90 adb shell am force-stop $APP >/dev/null 2>&1 || true
  timeout 120 adb shell pm clear $APP >/dev/null 2>&1 || true
  grant_fixture_permissions
}

timeout 60 adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
timeout 60 adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
timeout 60 adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true

# ---- tests ---------------------------------------------------------------
run_test() {
  local name=$1 marker=$2 log="$LOG_DIR/$1.log" limit code
  limit=$TEST_TIMEOUT
  if (( $(remaining) < 180 )); then
    annotate "$name skipped" /dev/null
    echo "Emulator test budget of ${BUDGET}s is exhausted before $name" >"$log"
    failures=$((failures + 1))
    return
  fi
  (( limit > $(remaining) )) && limit=$(remaining)
  say "--- Running $name (limit ${limit}s) ---"
  reset_app
  local began; began=$(date +%s)
  timeout --signal=KILL "$limit" adb shell am instrument -w -r \
    "com.myraa.mobile.tests/com.myraa.mobile.tests.$name" 2>&1 | tee "$log"
  code=${PIPESTATUS[0]}
  say "$name finished in $(( $(date +%s) - began ))s (exit $code)"
  if (( code == 124 || code == 137 )); then
    echo "TIMEOUT after ${limit}s" >>"$log"
    annotate "$name timed out after ${limit}s" "$log"
    failures=$((failures + 1)); dump_logcat; return
  fi
  if (( code != 0 )); then
    annotate "$name failed (instrumentation exit $code)" "$log"
    failures=$((failures + 1)); dump_logcat; return
  fi
  if ! grep -q 'INSTRUMENTATION_CODE: -1' "$log"; then
    annotate "$name did not report a completed instrumentation run" "$log"
    failures=$((failures + 1)); dump_logcat; return
  fi
  if grep -qE '(^|[[:space:]])FAIL:|BACKGROUND_TESTS_FAILED' "$log"; then
    annotate "$name reported a failed check" "$log"
    failures=$((failures + 1)); dump_logcat; return
  fi
  if [[ -n $marker ]] && ! grep -q "$marker" "$log"; then
    annotate "$name did not reach its success marker ($marker)" "$log"
    failures=$((failures + 1)); dump_logcat; return
  fi
  say "PASS: $name"
}

# The background suite is the longest one and exercises an installed-but-idle
# app, so it runs last.
run_test SmokeInstrumentation 'native Activity and WebView started'
run_test ActionsInstrumentation 'Checks:'
run_test AuthInstrumentation 'no live Google traffic'
run_test BackgroundInstrumentation BACKGROUND_TESTS_PASSED

say "Emulator suite finished in $(elapsed)s with $failures failure(s)"
if (( failures > 0 )); then
  note 'Emulator suite failed' "$failures of 4 instrumentation tests failed; see the error annotations and the Vishal-Jarvis-Emulator-Results artifact."
  exit 1
fi
note 'Emulator suite passed' 'All four instrumentation tests passed: background recovery, device actions, native smoke and isolated authentication.'
