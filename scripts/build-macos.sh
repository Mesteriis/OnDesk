#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
configuration=${1:-debug}
case "$configuration" in debug|release) ;; *) echo 'Usage: build-macos.sh [debug|release] [output.app]' >&2; exit 2;; esac
swift build --package-path macos -c "$configuration"
bin_dir=$(swift build --package-path macos -c "$configuration" --show-bin-path)
app=${2:-build/OnDesk.app}
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$bin_dir/OnDesk" "$app/Contents/MacOS/OnDesk"
cp assets/macos/OnDesk.icns "$app/Contents/Resources/OnDesk.icns"
cp macos/Info.plist "$app/Contents/Info.plist"
if [ "$configuration" = release ]; then
    strip -S "$app/Contents/MacOS/OnDesk"
    xattr -cr "$app"
fi
codesign --force --sign - --identifier app.ondesk.mac "$app"
codesign --verify --strict "$app"
echo "Built $app"
