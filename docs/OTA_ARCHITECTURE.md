# Development OTA Architecture

CamX2 development OTA is a deliberately non-production convenience channel:

- Android application ID `com.sahidcode404.camx2`;
- migrated source namespace `com.sahidcode404.camx`;
- debuggable `devOta` build;
- permanent development signer pinned as SHA-256
  `f6b8a3f492d4fb9d2dbf58937d3995f8f1e0f79433c4f3e25b9930218e694d8c`;
- rolling GitHub prerelease/tag `dev-latest` in `sahid-code404/CamX2`;
- assets `CamX-dev.apk` and `dev-manifest.json`.

The private dev key is committed so fresh clones and small changes can retain update continuity. This
means anyone with repository contents can forge the development channel; it is not an authenticity
or production-security boundary. A stable channel requires protected signing and a separate ADR.

The distinct Android application ID is what permits CamX2 and the previous CamX APK
(`com.sahidcode404.camx`) to coexist. OTA verification is intentionally bound to the CamX2 ID so an
APK from the CamX package cannot update or replace CamX2, even though the internal source namespace
is still inherited from the migrated CamX implementation.

CI assigns a monotonic workflow-run-based versionCode, builds and verifies all required jobs, and
only then invokes the reusable publisher for the same SHA/artifact. Publishers are globally serialized
without cancellation and reject an older or equal-but-different source version. Publication uploads
APK before manifest and moves the rolling tag only after both assets complete, making any replacement
window fail closed by digest mismatch. The manifest binds schema, channel, application ID, forward
version, minimum SDK, fixed asset name, APK hash, signer, source SHA, build timestamp, changelog, and
mandatory flag.

## CAMX-111 client

The updater is enabled only when `BuildConfig.OTA_CHANNEL == "development"`. Debug and future release
variants do not activate the committed development trust channel. The `devOta` source set owns
`INTERNET` and `REQUEST_INSTALL_PACKAGES`; camera ownership and topology code remain unchanged.

Automatic checks are lazy. `MainActivity` forwards only the fact that a preview frame is verified to
`FirstPreviewUpdateTrigger`. `FirstPreviewGate` then permits exactly one automatic check for the
ViewModel lifetime. Additional verified frames and lens switches cannot trigger another automatic
check. Manual checks remain available from the Updates panel and are independent of the automatic
one-shot gate. All network, manifest parsing, APK streaming, hashing, package inspection, and final
verification run off the camera dispatcher and off the Compose main thread.

The development endpoints are fixed in code:

- `https://github.com/sahid-code404/CamX2/releases/download/dev-latest/dev-manifest.json`
- `https://github.com/sahid-code404/CamX2/releases/download/dev-latest/CamX-dev.apk`

The client does not accept an APK URL from the manifest, preferences, intents, external storage, or
UI. Redirects are followed manually, remain HTTPS, are bounded to five hops, and are restricted to
GitHub/GitHubusercontent hosts. Connect timeout is 10 seconds and read timeout is 20 seconds.

The manifest is fetched first and is bounded to 64 KiB by both declared and actual byte count.
Strict JSON parsing and manifest-only validation reject wrong schema, channel, package, asset name,
minimum SDK, signer, digest format, or unbounded version fields. A same or older version is a normal
`UpToDate` result and never starts an APK download.

APK bytes stream through a fixed 64 KiB buffer into:

`cacheDir/updates/CamX-dev.apk.part`

The stream never scales memory with APK size. Both `Content-Length` and actual bytes are bounded by
`DevOtaTrust.MAX_APK_BYTES` (256 MiB), and actual SHA-256 is computed during streaming. Progress is
coalesced rather than emitted for every packet. Cancellation disconnects the active HTTP connection,
cancels the structured operation, and removes the `.part` file. Only one check/download operation may
be active at a time.

After a complete stream is flushed and synced, same-filesystem rename is the only promotion path to:

`cacheDir/updates/verified/CamX-dev.apk`

If the atomic rename cannot complete, the update fails rather than copying into the trusted basename.
The existing `AndroidApkInspector`, `DevelopmentUpdateVerifier`, and `VerifiedApk.verifyAndPromote`
boundary then verify real package ID, versionCode, versionName, minSdk, APK SHA-256, and signer.
Mixed rolling generations therefore fail closed. `VerifiedApk` remains the only install proof.

`ApkInstaller` accepts only `VerifiedApk` and revalidates its private canonical path, file identity,
size, modification time, and hash immediately before every install attempt. FileProvider exposes only
`cacheDir/updates/verified/`. On Android 8+ a missing "Allow from this source" permission opens
CamX2's own system setting; returning with permission granted preserves the already verified APK and
restores the explicit Install action. The user taps Install again to launch the normal Android
package-update UI. The flow never uninstalls CamX or CamX2, never clears app data, never uses a
browser/file manager, and never attempts silent or privileged installation.

Update work is owned by a lifecycle `ViewModel` scope, so ordinary Activity recreation does not create
a duplicate download. No Service, ForegroundService, JobService, WorkManager, persistent daemon, or
background camera owner is introduced. A failed OTA is orthogonal to preview, topology, AUX trust,
cache, and CameraDevice ownership.
