# OnDesk v1 implementation ledger

Initial implementation plan, 2026-10-12.

Goal: personal Android → Mac LAN file delivery with QR trust, durable queue,
resumable 4 MiB blocks, type folders, no overwrite, idempotent durable receipt.
macOS 27; Android 16–17. Swift/SwiftUI/Network, Kotlin/Compose/Room/WorkManager.

1. Core + protocol: file validation, routing, checkpointing and crash-safe completion.
2. Mac receiver: TLS identity, Keychain phone credentials, QR/Bonjour, menu + settings.
3. Android: pinned TLS + NSD, share ingestion, Room queue, foreground worker + UI.
4. Integration: actual TLS transfer, interruption/restart, large file, checksums.
5. Packaging: .app + APK, documented build/install, actual checks and limitations.

Pre-flight: Android and Mac share framed protocol, IDs, SHA256 and committed offsets.
All offsets and sizes are 64-bit. A success receipt means fully committed file.

Ruling: Empty project uses a new implementation branch in the current directory;
no existing checkout or unrelated changes require another worktree.
Ruling: Execute directly, with one final independent review under executing-plans.
Ruling: Protocol uses length-prefixed UTF-8 JSON headers followed by raw block bytes;
JSON max 16 KiB, binary max 4 MiB. Native TLS on both platforms; no HTTP dependency.
Ruling: Persistent receiver records use atomic JSON + fsync checkpoints, rather than
SQLite; serial actor enforces one mutator. Android uses Room as specified.
Ruling: SDK already installed under Homebrew. Do not replace it or modify global config.
Ruling: APK may be built and tested in a disposable emulator; real phone installation
requires explicit device selection and the approved command batch. Physical-device testing used a Samsung Z Fold7.

## Progress
- Initial RED tests created for routing, validation, checkpoint resume, idempotency,
  collision protection, owner isolation and checksum integrity.
- Core GREEN: 10 Swift tests, including actual NWConnection TLS pair/transfer/reconnect/revoke.
- Android GREEN: 7 JVM tests; APK debug assembled with AGP 9.3.1, Gradle 9.7.1.
- Icons: supplied original archived; 10 macOS PNG sizes + ICNS/AppIcon; Android
  five density launcher/round sets, adaptive and monochrome resources, notification.
- Ruling: Gradle 9.4.1 cannot load AGP 9.3.1 (ProjectTypeBinding unavailable);
  use already installed compatible Gradle 9.7.1, pinned wrapper.
- Mac bundle packaged/ad-hoc signed; native UI tool timed out twice on menu-only
  app. Added normal first-launch settings and --settings launch option for QA.
- New disposable Android 17 AVD ondesk_v1_qa_37, emulator port 5580, booted.
- Share instrumentation GREEN on clean Android 16 and 17: ACTION_SEND_MULTIPLE
  imported text + PNG from a separate content provider into persistent Room/spool.
- Integration found DER serial INTEGER bug: Apple accepted a redundant leading
  zero; Android Conscrypt rejected INVALID_INTEGER. Regression with fixed P256
  keys reproduced 3 failures; canonical positive INTEGER fix passes (14 Swift tests).
- Ruling: Repair pre-release noncanonical certificates using the same private key;
  certificate pin changes require a new QR pairing. Never weaken certificate checks.
- Android 17 cross-platform GREEN: wrong pin rejected; real TLS QR credentials,
  Android Keystore persistence, force-stop-surviving queue delivered by actual Worker.

- Android 16 cross-platform GREEN, same durable queue/pin/Worker checks as Android 17.
- Android 17 >4 GiB synthetic transport GREEN (280.59 s), resume at 4 MiB, SHA and
  idempotent receipt; not a playable-video or physical-Wi-Fi acceptance claim.
- Android 16 import-while-sending regression RED then GREEN after separate import
  mutex; last acknowledged offset retained on failure. Explicit backup exclusions.
- Final independent read-only review dispatched under executing-plans requirement.

- Independent review: four P2 cases corrected (waiting deletion vs sender,
  stale TLS endpoint before NSD, orphan spool reconciliation, manual backoff retry).
  Focused re-review confirmed first three and caught KEEP on manual retry;
  changed manual retries to independent one-shot enqueue, serialized by sender mutex.
- Filesystem constraint documented: durable exclusive publication requires hard links.

- Physical Samsung Z Fold7 / Android 17 GREEN: user scanned QR and shared image;
  Mac completed receipt, category/size/SHA256 verified; phone spool empty afterward.
- Scanner forced landscape reproduced by manifest instrumentation RED, override
  unspecified GREEN; updated APK preserves pairing. Physical recheck of rotation pending.
- Native Mac settings CUA now available: QR, peer and saved-file status observed.
- Final: 14 Swift, 10 JVM, Android 16/17 queue concurrency regressions PASS; lint no errors.

## Android quick actions 0.2.0

- User selected a manually enabled accessibility service for screenshot capture;
  no recurring MediaProjection prompt. Service requests screenshot capability only,
  subscribes to no accessibility events and exposes no public capture receiver.
- Static launcher shortcuts and explicit activity entry points support gesture apps.
  Screenshot activity closes before capture; native camera output uses a narrowly
  scoped private FileProvider, then joins the existing durable sender queue.
- 7 distinct Android 17 instrumentation checks PASS; 10 JVM tests PASS; lint no errors.
  Camera result/cancel instrumentation is synthetic, not a camera hardware claim.
- Physical Z Fold7: user confirmed screenshot and camera actions inside OnDesk;
  Mac PNG/JPEG receipts, sizes, SHA-256 and image category verified, captures empty.
- Physical Touch the Notch exposes package launch only. Optional quick-launcher
  APKs provide distinct package entries without duplicating capture/queue logic.
  UI-configured double tap delivered a checksum-verified PNG; right-to-left swipe
  opened Samsung IMAGE_CAPTURE via OnDesk. Camera cancelled, no capture retained.
  Gesture input injected through ADB; finger ergonomics and process-death remain open.

## Shared links 0.3.0

- Approved in chat: normal Android Share → OnDesk → default Mac browser, durable
  offline queue, HTTP/HTTPS only, no duplicate launch on request replay.
- Room 2 adds nullable link via generated 1→2 migration; existing file entries and
  paths preserved. Link commands have no file copy; cleanup/deletion handles both.
- Protocol 1 additive open_link accepts a UUID and URL from an authenticated peer.
  Separate private LinkStore binds owner/URL and durably checkpoints dispatching
  before NSWorkspace.open. Crash ambiguity blocks reopening and requires checking
  the browser, then fresh re-share if needed. Opened receipt means OS acceptance.
- 19 Swift, 12 JVM and 2 Android 17 instrumentation tests PASS; lint no errors.
  Force-stop-surviving offline link observed on emulator. Physical Fold ACTION_SEND
  produced a durable Mac receipt and matching Chrome active-tab URL via Apple Events.
- Updated .app and APK installed; pairing retained; screenshot service setting retained.

## Mac camera hotkey 0.4.0

- User-selected global chord: Cmd+Shift+1, Carbon RegisterEventHotKey; menu fallback,
  conflict disclosure and phone selector when multiple phones are paired.
- Authenticated polling over existing pinned TLS from the bound Android accessibility
  service. Optional remote-camera switch, non-exported activity, unlocked launch,
  monotonic launch budget, one pending Mac request, finite capture intent lifetime.
- Camera confirmation reuses private capture + persistent Room sender; request UUID
  binds the resulting transfer to one Mac clipboard intent. No new database fields.
- Completed, checksum-verified JPEG is saved and copied as TIFF image pasteboard data.
  At-most-once ephemeral clipboard intent; cancel/replay/foreign files leave it alone.
- Android main APK and Mac bundle are 0.4.0; optional gesture launchers remain 0.2.0.

## Configurable image retention 0.4.0

- Mac Folders settings: opt-in image cleanup, 1–365 days, default 3; persisted defaults.
- Sweep at launch, every hour, on settings/folder change, or manual check.
- Only top-level regular known-image filenames older than cutoff by modification time;
  skip hidden files, directories, symlinks and Finder aliases. Recheck fresh attributes
  immediately before FileManager trashItem. Cancel ongoing sweep on configuration change.
- Trash is recoverable; no permanent deletion of user images, transfer receipt deletion,
  recursion or cleanup of other categories. Existing receipts remain idempotent.


## Share window receipt-based completion — Android 0.4.1

- ACTION_SEND / ACTION_SEND_MULTIPLE windows track their own UUID batch, preserved
  in saved instance state. UI closes only when all expected rows are delivered,
  import/pair work has finished and no import/setup error is active.
- Room 3 adds nullable shareBatchID, auto-migrations 1→2→3 preserve old queues.
  Worker retains batch receipt rows after deleting acknowledged bytes, preventing
  a fast send/cleanup race with the activity's Room observer.
- Finishing a share window releases its batch after any in-flight import finishes;
  normal worker cleanup removes released delivered rows. Manual Back preserves
  queued files and background delivery. Configuration changes do not release batches.
- A process-killed window can restore its saved batch and see durable receipts.
  If Android discards that saved window, small delivered metadata rows can remain
  in the queue until manually removed; payload copies are already deleted after ACK.
- Ordinary launcher stays open; camera/screenshot shortcut capture flows are unchanged.
  The Mac protocol and bundle remain 0.4.0. Main Android APK is 0.4.1 (versionCode 5).
- Fixed existing remote-camera teardown: close captured TLS client asynchronously
  on application IO scope, after cancelling the control job. Conscrypt may write
  TLS close_notify; closing directly in AccessibilityService.onDestroy is forbidden
  main-thread network. A regression guards thread dispatch and resource closure.

## Native Mac file notifications — 0.4.1

- AppModel owns FileNotifications for the app lifetime. It registers UserNotifications
  categories/delegate before receiver startup, requests alert/sound authorization,
  and exposes enabled/sound/status settings in a separate native settings tab.
- Receiver emits a typed receivedFile callback only after TransferStore.finish
  succeeds. Optional notification errors never change the file delivery result.
- TransferStore atomically persists an optional notificationClaimed field before
  enqueueing the notification. Old receipts decode without the field; repeated
  finish requests and restarts do not repeat banners. This is at-most-once best
  effort: a crash between claim and notification submission can lose the banner,
  but cannot lose the saved file or its receipt. Disabled notifications are not
  replayed later as a backlog.
- Notification payloads contain receipt UUID and owner, not caller-provided paths.
  Open/default/Finder actions resolve the owner-checked durable receipt and reject
  missing files, symlinks and aliases. A notification remains actionable after
  restart without waiting for TLS/Keychain initialization.
- Standard immediate local notifications use a shared thread identifier and default
  sound when enabled. macOS controls their presentation/grouping/Focus suppression.
  No cloud push, new network endpoints or Android update is required.


## Invisible Android share entry — 0.4.2

- ShareActivity replaces MainActivity as the ACTION_SEND/ACTION_SEND_MULTIPLE
  target. It uses the existing translucent no-preview theme, no content view,
  non-focusable/non-touchable window and zero activity transitions. MainActivity
  forwards explicit cached share intents for compatibility and stays the launcher
  for setup/queue. No service or permission was added.
- Shared imports still hold their URI grants/activity lifetime until internal
  copies and Room entries are durable, then finish without waiting for delivery.
  WorkManager sends and removes acknowledged payloads as before. StateFlow signals
  completion without depending on a Compose recomposer in the invisible activity.
- A retained ViewModel prevents duplicate imports on configuration changes. The
  saved batch UUID and retained receipts allow restored imports to skip the durable
  sequential prefix (also if the worker has already removed delivered payloads).
  Batch markers are released after import, rather than after final Mac receipt.
- ShareFeedback persists actionable errors and posts an error notification with
  an explicit queue action; MainActivity consumes the saved error when opened.
  No permission dialogs or main-screen navigation occur during share. Denied
  notifications still leave the error persisted and import failures use a toast.
- Source URI access during a forced process stop is not guaranteed by Android;
  already queued files remain durable, but a discarded task may need a new share
  for its unfinished files. Background large-copy/process-death soak is not claimed.
