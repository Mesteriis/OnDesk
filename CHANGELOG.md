# Changelog

## 0.4.3 — 2026-10-13

- GitHub Actions checks for Swift, Android JVM tests, lint and share-target
  instrumentation on Android API 36 and 37.
- macOS builds on a dedicated self-hosted Apple Silicon runner; Android builds
  and signing run on GitHub-hosted Linux.
- Release tags require matching platform versions and a commit contained in main.
  Successful platform checks produce signed APKs, a Mac archive and SHA-256
  checksums before publication. Manual release runs verify without publishing.

## 0.4.2 — 2026-10-12

First public OSS release, under the MIT license.

- Native macOS menu-bar receiver and Android Share target with QR pairing,
  pinned TLS, persistent queue, resumable 4 MiB blocks and SHA-256 verification.
- Category folders, collision-safe filenames, peer revocation and login startup.
- Android screenshot and camera shortcuts, optional gesture launcher APKs.
- Shared web links open in the default Mac browser.
- Cmd+Shift+1 requests a phone photo and copies the received image to Mac clipboard.
- Configurable image retention using Trash, and native receipt notifications.
- Android sharing queues files without opening the main application screen.

See docs/VALIDATION.md for checks and limitations. macOS binaries are ad-hoc signed,
not notarized. Android release and development builds use different signing keys.
