#!/bin/bash
# Test a disposable ARM AVD. Every adb operation explicitly targets this AVD.
set -euo pipefail
cd "$(dirname "$0")/../.."
[ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] || { echo 'Requires an Apple Silicon Mac' >&2; exit 1; }
: "${ANDROID_HOME:?Configure the Mac runner Android SDK}"
: "${JAVA_HOME:?Configure JDK 17 on the Mac runner}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
port=5586
serial="emulator-$port"
if lsof -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "CI emulator port $port is already in use; refusing to touch it" >&2; exit 1
fi
mkdir -p build/ci/android-37
(cd android && ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest)
export ANDROID_AVD_HOME
ANDROID_AVD_HOME=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ondesk-avd.XXXXXX")
pid=''
cleanup() {
    if [ -n "$pid" ]; then
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
    rm -rf "$ANDROID_AVD_HOME"
}
trap cleanup EXIT
avd=ondesk_ci_api37
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
    --force --name "$avd" --device pixel_7 \
    --package 'system-images;android-37.0;google_apis_ps16k;arm64-v8a'
adb start-server
"$ANDROID_HOME/emulator/emulator" -avd "$avd" -port "$port" -no-window \
    -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot -no-metrics \
    -camera-back none -memory 2048 > build/ci/android-37/emulator.log 2>&1 &
pid=$!
booted=false
for ((attempt=0; attempt<150; attempt++)); do
    kill -0 "$pid" 2>/dev/null || { echo 'CI emulator exited during boot' >&2; exit 1; }
    if [ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then booted=true; break; fi
    sleep 2
done
[ "$booted" = true ] || { echo 'CI ARM emulator boot timed out' >&2; exit 1; }
adb -s "$serial" shell input keyevent 82
adb -s "$serial" shell settings put global window_animation_scale 0
adb -s "$serial" shell settings put global transition_animation_scale 0
adb -s "$serial" shell settings put global animator_duration_scale 0
adb -s "$serial" install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$serial" shell am instrument -w -r \
    -e class app.ondesk.ShareCompletionTest,app.ondesk.LinkShareTest,app.ondesk.RemoteCloseTest \
    app.ondesk.test/androidx.test.runner.AndroidJUnitRunner | tee build/ci/android-37/instrumentation.txt
python3 - <<'PY'
from pathlib import Path
import re
text = Path('build/ci/android-37/instrumentation.txt').read_text()
if not re.search(r'^OK \(10 tests\)', text, re.MULTILINE) or any(s in text for s in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed']):
    raise SystemExit('Expected all 10 Android 17 instrumentation tests to pass')
print('Android 17 ARM instrumentation: 10/10 PASS')
PY
