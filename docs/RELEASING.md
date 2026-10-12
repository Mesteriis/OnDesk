# Installation and releases

## Downloads

Get the assets from https://github.com/Mesteriis/OnDesk/releases/latest:

- `OnDesk-0.4.2-macos-arm64.zip`: macOS 27, Apple Silicon; unpack and copy
  OnDesk.app to Applications, then open it. Intel binaries are not supplied.
- `OnDesk-0.4.2-android.apk`: Android 16–17; allow installation from the source
  you used to download it, install, open OnDesk and pair using the Mac QR code.
- `OnDesk-0.4.2-screenshot-launcher.apk` and `OnDesk-0.4.2-camera-launcher.apk`:
  optional app entries for gesture utilities that cannot select shortcuts.
- `SHA256SUMS.txt` and license/notice files.

Verify a download with `shasum -a 256 <filename>` against SHA256SUMS.txt.
Checksums detect corruption; obtain both artifacts and checksums from this repository.

Mac binaries currently have an ad-hoc signature, not Developer ID signing or Apple
notarization. Gatekeeper may block them. macOS System Settings → Privacy & Security
can offer Open Anyway after a blocked launch; only allow a build you trust. Building
from source is also supported. Do not disable Gatekeeper globally. Keychain access
may need approval after replacing an ad-hoc build. This is a distribution limitation,
not evidence of Apple notarization acceptance.

Release APKs use a dedicated persistent signing key. Debug APKs use a different key
and cannot be upgraded in place with release APKs. Finish sending the old queue
before migrating; uninstalling deletes queue data and pairing. Release-to-release
updates signed by the same key preserve data. Back up private signing material to
keep that update path. No release key is included in Git or source archives.

## Building an identical release variant

Requires Xcode 27 / Swift 6.2, macOS 27, JDK 17, Android SDK API 37 and build-tools
36.0.0. Gradle 9.7.1 is provided by the wrapper. From the repository root:

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export ONDESK_KEYSTORE_FILE=/private/path/ondesk-release.jks
export ONDESK_KEY_ALIAS=ondesk
# Set ONDESK_KEYSTORE_PASSWORD and optionally ONDESK_KEY_PASSWORD privately.
# Never commit or put passwords in command-line arguments.
./scripts/package-release.sh
```

The script runs Swift tests, Android JVM tests and release lint, creates an optimized
Mac app in `build/release/OnDesk.app`, signs APKs using apksigner and verifies signatures.
It writes assets and checksums to `build/releases/v0.4.2/`. It does not install apps,
change running applications, publish or notarize. The host architecture determines
the Mac binary architecture. A different signing key produces a different APK;
bit-for-bit reproducibility across machines has not been established.

Without a release key, source contributors can build debug APKs using README commands
or unsigned release APKs with `:app:assembleRelease`. Local release keys can be created
with JDK keytool; store them outside the checkout with restricted permissions.

## Maintainer publication

1. Update versions, changelog and validation evidence; inspect the staged diff for
   private files, logs, credentials and machine-local paths. Run the release script.
2. Check bundle metadata, executable architecture, APK package/version/debuggable
   status, signatures, embedded notices and archive contents.
3. Commit the reviewed source. Create a tag and GitHub release targeting its full
   commit SHA; upload only the staged release assets, never `.work` or signing files.
4. Download the published assets and compare checksums. Verify the source tag matches
   the tested commit. Mark hardware checks separately from local tests.

Published release builds have not been installed on the physical test phone: it
retains the previously verified debug build and its pairing. See VALIDATION.md for
feature checks performed on physical devices and emulators before publication.
