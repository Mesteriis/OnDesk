# Security policy

Security fixes target the latest release. OnDesk is a personal LAN tool; it has
not undergone an independent security audit. Only pair phones you trust. Shared
HTTP/HTTPS links intentionally open in the Mac default browser.

Report vulnerabilities privately using GitHub's **Security → Report a vulnerability**
for this repository. Do not include real pairing secrets, keys or private files.
Provide affected versions, a synthetic reproduction and the expected impact.
Please avoid public issues with exploit details before a fix is available.

TLS certificates are pinned during QR pairing. Mac private keys and phone secrets
use Keychain; Android pairing is protected by Android Keystore. Never disable pin
validation to fix connectivity. Revoke a lost phone in Mac settings and pair again
if a trusted certificate changes.
