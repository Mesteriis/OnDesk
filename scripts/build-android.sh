#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
(cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :quick-launcher:assembleDebug)
version=$(python3 scripts/version.py)
mkdir -p build
cp android/app/build/outputs/apk/debug/app-debug.apk "build/OnDesk-$version-debug.apk"
printf 'Built %s/build/OnDesk-%s-debug.apk\n' "$PWD" "$version"
for action in screenshot camera; do
    cp "android/quick-launcher/build/outputs/apk/$action/debug/quick-launcher-$action-debug.apk" "build/OnDesk-$action-launcher-$version-debug.apk"
done
