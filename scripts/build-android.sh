#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
(cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :quick-launcher:assembleDebug)
mkdir -p build
cp android/app/build/outputs/apk/debug/app-debug.apk build/OnDesk-0.4.2-debug.apk
printf 'Built %s/build/OnDesk-0.4.2-debug.apk\n' "$PWD"
for action in screenshot camera; do
    cp "android/quick-launcher/build/outputs/apk/$action/debug/quick-launcher-$action-debug.apk" "build/OnDesk-$action-launcher-0.4.2-debug.apk"
done
