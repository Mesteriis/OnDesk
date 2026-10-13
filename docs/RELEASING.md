# Installation and releases

## Downloads

Get the assets from https://github.com/Mesteriis/OnDesk/releases/latest:

- `OnDesk-0.4.3-macos-arm64.zip`: macOS 27, Apple Silicon; unpack and copy
  OnDesk.app to Applications, then open it. Intel binaries are not supplied.
- `OnDesk-0.4.3-android.apk`: Android 16–17; allow installation from the source
  you used to download it, install, open OnDesk and pair using the Mac QR code.
- `OnDesk-0.4.3-screenshot-launcher.apk` and `OnDesk-0.4.3-camera-launcher.apk`:
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
It writes assets and checksums to `build/releases/v0.4.3/`. It does not install apps,
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
3. Commit the reviewed source to main, wait for Checks, then push a matching version
   tag. The Release workflow publishes its checked artifacts; do not create a
   competing manual release. Never upload `.work` or signing files.
4. Download the published assets and compare checksums. Verify the source tag matches
   the tested commit. Mark hardware checks separately from local tests.

Published release builds have not been installed on the physical test phone: it
retains the previously verified debug build and its pairing. See VALIDATION.md for
feature checks performed on physical devices and emulators before publication.

## GitHub Actions

- **Checks** runs on pushes to main, pull requests and manual dispatch. Android JVM
  tests, release lint and debug/test APK builds use GitHub-hosted Ubuntu. A reusable
  instrumentation workflow runs ShareCompletionTest, LinkShareTest and RemoteCloseTest
  on fresh emulators (10 tests each): API 36 x86_64 on hosted Linux, API 37 ARM
  on the dedicated Mac for trusted main/tag runs only. The Android 17 Linux images
  did not boot reliably here; they are not silently treated as passing. It does not run screenshot accessibility
  or cross-platform TLS fixtures; those need their separate setup in VALIDATION.md.
- Swift tests and an optimized Mac bundle run on the `ondesk-macos` self-hosted runner
  only for main pushes/manual main runs. Pull requests never run on the personal Mac.
  Current GitHub-hosted macOS images lack the project's macOS 27 SDK.
  External PRs run Android API 36 checks; API 37 and Swift are checked after merge.
- **Release** runs for `v*` tags. The tagged commit must already be contained in main;
  the tag must exactly match every platform's version. Both packaging jobs and the
  emulator tests must succeed before publishing. Each third-party Action uses a
  pinned commit. Build jobs have read-only repository permission; only the final
  hosted publisher has contents:write and no signing secrets.
- Manual **Release** from main builds/tests/uploads artifacts without publishing.
  Download the workflow artifacts from the run page to inspect a release candidate.
- All external contributors require maintainer approval before fork PR workflows
  run. A runner-side pre-job hook also rejects PR events, foreign repositories,
  non-main/non-release refs and unknown workflows before any workflow step. Install
  a copy of `scripts/ci/guard-macos-runner.sh` outside the runner directory/workspace
  and configure `ACTIONS_RUNNER_HOOK_JOB_STARTED` in its private `.env`, then restart
  the service. The configured copy stays outside checkout and cannot be changed by
  a PR checkout. Review workflow changes before approving; a PR can modify its workflow YAML,
  so job-level conditions alone are not a sandbox for a public self-hosted runner.
- Android signing uses repository secrets `ANDROID_RELEASE_KEYSTORE_BASE64` and
  `ANDROID_RELEASE_KEYSTORE_PASSWORD`, alias `ondesk`, with the same key as 0.4.2.
  The keystore is restored only for signing in a hosted runner's temporary directory
  and removed on exit. Keep a private backup; GitHub Secrets cannot be read back.
- The Mac runner is a separate user LaunchAgent named `ondesk-macos-arm64`; it does
  not replace other CI services. It needs the Mac awake and the user session logged
  in. No release workflow accesses the personal pairing Keychain or installs apps.

To release, update all three platform versions/build numbers and CHANGELOG, push main,
wait for Checks, then push the corresponding `vMAJOR.MINOR.PATCH` tag. Inspect the
Release run and download/check its published SHA256SUMS.txt. Existing releases are
not overwritten; a failed publication can leave a draft for maintainer inspection.

The Mac runner also needs JDK 17, the Android SDK/emulator and
`system-images;android-37.0;google_apis_ps16k;arm64-v8a` already installed.
Configure `JAVA_HOME` and `ANDROID_HOME` privately on the runner, not in Git.
`scripts/ci/test-android-37-macos.sh` builds test APKs, creates a fresh AVD under
RUNNER_TEMP, uses an exclusive emulator port and explicit `adb -s emulator-5586`
for every device operation, requires `OK (10 tests)`, then stops its emulator and
removes its AVD. It never installs or instruments a connected physical phone.
The runner pre-job hook must allow the three repository workflows listed in its
script; update its installed private copy when that allowlist changes.
