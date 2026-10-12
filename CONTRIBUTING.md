# Contributing

Issues and pull requests are welcome. Describe the problem, OS versions and
steps to reproduce. Do not attach pairing QR codes, credentials, received files,
device identifiers or unredacted runtime logs.

Use Xcode 27, JDK 17 and Android SDK 37. See README for builds. Before a PR run:

```sh
swift test --package-path macos
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :quick-launcher:assembleDebug
```

Add regression coverage for behavior changes. Preserve protocol compatibility,
TLS pinning, durable checkpoints, queue migration and the no-overwrite guarantee.
Document changes to permissions, storage, protocol or installation. Hardware tests
must be identified separately from emulator tests; report skipped checks honestly.
See docs/VALIDATION.md for current evidence and remaining gaps.
