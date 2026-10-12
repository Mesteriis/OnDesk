#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
source_icon=assets/source/OnDesk.png
mkdir -p assets/macos/OnDesk.iconset assets/macos/AppIcon.appiconset
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$source_icon" --out "assets/macos/OnDesk.iconset/icon_${size}x${size}.png" >/dev/null
  retina=$((size * 2))
  sips -z "$retina" "$retina" "$source_icon" --out "assets/macos/OnDesk.iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns assets/macos/OnDesk.iconset -o assets/macos/OnDesk.icns
python3 - <<'PY'
import json, shutil
from pathlib import Path
images=[]
for size in (16,32,128,256,512):
    for scale in (1,2):
        name=f'icon_{size}x{size}'+('@2x' if scale==2 else '')+'.png'
        shutil.copyfile(Path('assets/macos/OnDesk.iconset')/name,Path('assets/macos/AppIcon.appiconset')/name)
        images.append(dict(filename=name,idiom='mac',scale=f'{scale}x',size=f'{size}x{size}'))
Path('assets/macos/AppIcon.appiconset/Contents.json').write_text(json.dumps(dict(images=images,info=dict(author='xcode',version=1)),indent=2)+'\n')
PY
for pair in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  density=${pair%:*}; size=${pair#*:}; dest="android/app/src/main/res/mipmap-${density}"
  mkdir -p "$dest"
  sips -z "$size" "$size" "$source_icon" --out "$dest/ic_launcher.png" >/dev/null
done
sips -z 512 512 "$source_icon" --out assets/android-play-store-512.png >/dev/null
sips -z 1024 1024 "$source_icon" --out android/app/src/main/res/drawable-nodpi/ondesk_artwork.png >/dev/null

swift scripts/round-icon.swift "$source_icon"
