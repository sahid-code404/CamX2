# Development OTA Architecture

CamX development OTA is a deliberately non-production convenience channel:

- package `com.sahidcode404.camx`;
- debuggable `devOta` build;
- permanent distinct CamX signer pinned as SHA-256
  `f6b8a3f492d4fb9d2dbf58937d3995f8f1e0f79433c4f3e25b9930218e694d8c`;
- rolling GitHub prerelease/tag `dev-latest`;
- assets `CamX-dev.apk` and `dev-manifest.json`.

The private dev key is committed so fresh clones and small changes can retain update continuity. This
means anyone with repository contents can forge the development channel; it is not an authenticity
or production-security boundary. A stable channel requires protected signing and a separate ADR.

CI assigns a monotonic workflow-run-based versionCode, builds and verifies all required jobs, and
only then invokes the reusable publisher for the same SHA/artifact. Publishers are globally serialized
without cancellation and reject an older or equal-but-different source version. Publication uploads
APK before manifest and moves the rolling tag only after both assets complete, making any replacement
window fail closed by digest mismatch. The manifest binds schema, channel, application ID, forward version, minimum SDK, fixed
asset name, APK hash, signer, source SHA, build timestamp, changelog, and mandatory flag.

The client is lazy. An automatic check must pass `FirstPreviewGate`; manual Updates UI can trigger it.
CAMX-111 must download to an app-private bounded `.part`, validate basename/size, and inspect
package/version/SHA/signer before atomic promotion. The implemented verification boundary creates a
non-copyable `VerifiedApk` proof only after real Android package inspection; the installer revalidates
its canonical path beneath the app's own `cacheDir/updates/verified` root, identity, size, and hash
immediately before using FileProvider and visible Android package UI. Callers cannot nominate another
"private" directory. APK parsing and hashing are forced onto `Dispatchers.IO`, independent of caller
context. Expected hostile candidates return stable rejection codes for inspection, size, path, name,
hash, package, version, and signer failures; filesystem I/O and fatal process failures still throw.
Network download and `.part` orchestration remain deferred to CAMX-111.
No Service, worker, silent PackageInstaller session, or network-before-first-frame is allowed.
