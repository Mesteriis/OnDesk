#!/usr/bin/env python3
"""Read the shared release version and reject inconsistent platform metadata."""
import plistlib
import re
from pathlib import Path

root = Path(__file__).resolve().parent.parent
info = plistlib.loads((root / "macos/Info.plist").read_bytes())
version = info["CFBundleShortVersionString"]
if not re.fullmatch(r"\d+\.\d+\.\d+", version):
    raise SystemExit("Expected a numeric major.minor.patch version")
for path in ["android/app/build.gradle.kts", "android/quick-launcher/build.gradle.kts"]:
    text = (root / path).read_text()
    if re.search(r'versionName\s*=\s*"([^"]+)"', text).group(1) != version:
        raise SystemExit(f"Version mismatch: {path}")
    if re.search(r'versionCode\s*=\s*(\d+)', text).group(1) != info["CFBundleVersion"]:
        raise SystemExit(f"Build number mismatch: {path}")
print(version)
