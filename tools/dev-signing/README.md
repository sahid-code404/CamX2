# CamX development signer

`camx-dev.jks.b64` is the permanent signer for the debuggable CamX development OTA channel. Its
certificate SHA-256 is pinned in `EXPECTED_CERT_SHA256`, runtime verification, CI, and the OTA
manifest. Do not regenerate, replace, or reuse it for another app/channel.

The keystore password (`camx-dev-only-2026`) and private material are intentionally public to make
development builds reproducible from a fresh clone. Therefore this signer provides update continuity
only—not authenticity or production security. Anyone with the repository can sign an accepted
development APK. Production/stable distribution needs a protected private key and a separate trust
policy.
