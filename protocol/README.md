# OnDesk wire protocol 1

Transport: TLS 1.2 or later, server leaf DER SHA-256 pinned from QR (self-signed).
Frame: 4-byte unsigned big-endian JSON length (1..16384), UTF-8 JSON object;
then exactly `payloadLength` raw bytes (0..4194304, only chunk). One request/reply
at a time per connection; response frame has no trailing binary payload.
64-bit JSON integers represent sizes and offsets. Every request carries `version:1`.

QR: JSON `{version,serverID,host,port,fingerprint,secret}`. Secret is random 32 bytes
hex, expires after 300 seconds, one use. `host` is an address hint; Bonjour service
`_ondesk._tcp.` with instance name equal to serverID supplies changed addresses.
QR never contains long-term phone credentials.

Request fields: `version`, `op`, optional `token`, `deviceID`, `deviceName`, `secret`,
`transfer`, `id`, `offset`, `payloadLength`, `url`. Transfer: `{id,name,mime,size,sha256}`.
- pair: deviceID (UUID), deviceName, secret. Response issues token.
- begin: token, transfer. Response offset/complete/savedName. Same UUID + different
  metadata or owner is rejected. Also serves as the resume/status query.
- chunk: token, id, offset, payloadLength + raw payload. Requires exact current
  offset and not past declared size. Reply offset is durable checkpoint.
- finish: token, id. Checks size and SHA256, publishes without overwrite, then
  persists receipt. Retry yields the same savedName. Zero-byte files supported.
- open_link (OnDesk 0.3.0+): token, id (UUID), url (HTTP/HTTPS, <=4096 UTF-8
  bytes, no credentials, whitespace, controls or backslash), no binary payload.
  Authenticated owner and exact URL are bound to UUID. Reply complete:true,
  opened:true acknowledges OS browser dispatch; replay returns the same receipt.
  Different owner/URL is unauthorized/conflict. Links have separate durable records,
  never share the file begin/chunk/finish namespace or automatically open file uploads.
  Before invoking the browser, persist dispatching. After acceptance, persist opened.
  A retry of dispatching returns open_uncertain and must not invoke the browser;
  manual re-share with a fresh UUID is required after checking the browser.
  Browser refusal returns open_failed; manual retry can try the same UUID again.

Reply: `{version:1,ok:true,token?,serverID?,offset?,complete?,savedName?}` or
`{version:1,ok:false,error:CODE}`. Link receipt adds optional `opened`.
Codes: unauthorized, pairing_expired, invalid, conflict, offset, incomplete,
checksum, storage, incompatible, open_failed, open_uncertain. No paths/secrets in errors.
Clients retain queued bytes until `complete:true`. Invalid/checksum/conflict/unauthorized
are actionable blocked states; storage/network errors retry with backoff.
No unencrypted fallback, insecure trust manager, or trust-on-first-network-contact.

## Remote camera extension (0.4.0, wire version 1)

Android's user-enabled accessibility service keeps a pinned TLS connection and
polls `camera_poll` every 2 seconds while unlocked and remote camera is enabled.
The request uses the usual phone token. A reply may contain `cameraID` (UUID)
and `cameraTTLMillis` (remaining launch budget, at most 30000). Android subtracts
the whole exchange duration using elapsed realtime and rechecks the deadline
before launching the non-exported RemoteCameraActivity. NSD fallback retains the pin.
No Android inbound port, cloud or ADB is involved in product operation.

Mac requests target one paired phone with a poll in the last 10 seconds. At most
one intent is pending; delivery is at most once. A lost command reply is allowed
to lose the launch, rather than launching twice. Undelivered requests expire at
30 seconds, delivered requests at 10 minutes from creation. State is ephemeral:
receiver restart invalidates pending clipboard intent. `camera_cancel` with
`id` cancels only that owner's matching intent. Token revocation prevents polls
and photo completion side effects.

The private capture filename and normal transfer UUID equal cameraID; the queue
continues using the original paired server ID. No Room schema migration needed.
Normal `begin/chunk/finish` and durable SHA-256 receipts remain unchanged. After
successful finish, only the matching live owner/request can consume the clipboard
intent. The receiver loads the completed JPEG's actual stored destination, then
writes an image to the Mac pasteboard. Duplicate finish, unrelated files, expired
requests and Mac restarts do not replay clipboard effects. Clipboard failure never
undoes durable file delivery. This clipboard side effect is at most once, not
crash-replayed. Ordinary Android camera/screenshot shortcuts ignore remote extras.
