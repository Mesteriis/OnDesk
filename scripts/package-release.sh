#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${ONDESK_KEYSTORE_FILE:?Set private signing keystore path}"
: "${ONDESK_KEYSTORE_PASSWORD:?Set keystore password}"
export ONDESK_KEYSTORE_PASSWORD
export ONDESK_KEY_PASSWORD=${ONDESK_KEY_PASSWORD:-$ONDESK_KEYSTORE_PASSWORD}
alias_name=${ONDESK_KEY_ALIAS:-ondesk}
build_tools=${ONDESK_BUILD_TOOLS:-36.0.0}
version=$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' macos/Info.plist)
arch=$(uname -m)
out="build/releases/v$version"
mkdir -p "$out"
swift test --package-path macos
./scripts/build-macos.sh release build/release/OnDesk.app
mkdir -p build/release/OnDesk.app/Contents/Resources/Licenses
cp LICENSE THIRD_PARTY_NOTICES.md build/release/OnDesk.app/Contents/Resources/Licenses/
cp LICENSES/*.txt build/release/OnDesk.app/Contents/Resources/Licenses/
codesign --force --sign - --identifier app.ondesk.mac build/release/OnDesk.app
codesign --verify --strict build/release/OnDesk.app
ditto -c -k --sequesterRsrc --keepParent build/release/OnDesk.app "$out/OnDesk-$version-macos-$arch.zip"
(cd android && ./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :quick-launcher:assembleRelease)
sign_apk() {
    local source=$1 target=$2
    "$ANDROID_HOME/build-tools/$build_tools/zipalign" -p -f 4 "$source" build/release/aligned.apk
    "$ANDROID_HOME/build-tools/$build_tools/apksigner" sign \
        --v4-signing-enabled false --ks "$ONDESK_KEYSTORE_FILE" --ks-key-alias "$alias_name" \
        --ks-pass env:ONDESK_KEYSTORE_PASSWORD --key-pass env:ONDESK_KEY_PASSWORD \
        --out "$target" build/release/aligned.apk
    "$ANDROID_HOME/build-tools/$build_tools/apksigner" verify "$target"
    rm build/release/aligned.apk
}
sign_apk android/app/build/outputs/apk/release/app-release-unsigned.apk "$out/OnDesk-$version-android.apk"
for action in screenshot camera; do
    sign_apk "android/quick-launcher/build/outputs/apk/$action/release/quick-launcher-$action-release-unsigned.apk" "$out/OnDesk-$version-$action-launcher.apk"
done
cp LICENSE THIRD_PARTY_NOTICES.md "$out/"
cp LICENSES/*.txt "$out/"
(cd "$out" && shasum -a 256 OnDesk-*.zip OnDesk-*.apk LICENSE THIRD_PARTY_NOTICES.md Apache-2.0.txt ZXing-Android-Embedded.txt ZXing-NOTICE.txt > SHA256SUMS.txt)
echo "Release files: $out"
