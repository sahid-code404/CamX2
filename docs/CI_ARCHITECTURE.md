# CI and Architecture Guards

The repository-content read-only validation job triggers on `main`, `rewrite/**`, `phase/**`, pull
requests, and manual validation. Its required order is:

1. fail-closed whitespace and architecture/source guards;
2. the Gradle Android-model API-23 assertion, host C++ tests, and pure Kotlin tests;
3. `lintDevOta` and one all-ABI `devOta` assembly;
4. produced-APK package, exact minSdk, v1+v2 signer, size, alignment, ABI, and ELF inspection;
5. manifest/hash binding and artifact upload only after every prior gate passes.

Pull-request validation explicitly checks out `pull_request.head.sha` rather than treating GitHub's
synthetic merge SHA as source evidence. Push and manual validation use `github.sha`. The chosen source
SHA is checked against `git rev-parse HEAD`, propagated into build metadata and artifact naming, and
reported in the job log so final evidence binds to the exact source commit under review.

The Gradle model and every variant must resolve `minSdk=23`; textual source matching is not the model
proof. The produced APK is inspected independently and its `uses-sdk` plus development manifest must
also equal 23. The package verifier requires the permanent signer, both v1 signing for API 23 and v2
signing for newer Android, one signer, bounded size, and valid zip alignment.

Native inspection covers every `lib/<abi>/*.so` across `armeabi-v7a`, `arm64-v8a`, `x86`, and
`x86_64`. It requires ABI/library-set parity, correct ELF class/machine and SONAME, an API floor no
higher than 23 (exactly 23 for `libcamx_core.so`), API-23-resolvable `DT_NEEDED` libraries and strong
undefined symbols, preserved symbol-version matching, and the core export allowlist. It rejects
missing build notes, private/vendor dependencies, hidden native payloads, text relocations,
RPATH/RUNPATH, and later-loader-only relocation formats. Weak imports are reported and allowed only by
an explicit reviewed rule.

Publishing is a reusable workflow invoked only by a push job that `needs: validate`. It downloads the
exact current-run artifact and rechecks source/hash/package/signer binding before updating
`dev-latest`, including the exact API-23 manifest contract. Validation cancellation is job-scoped, so
an already-started publisher cannot be
cancelled halfway by a newer push. Cross-branch publishers are globally serialized and a remote
manifest/APK freshness check prevents rolling backward. Build jobs have read-only repository
permission; only the gated publisher receives contents write, and no repository secrets are inherited.

Guards reject obvious regressions: brand/model/SoC/numeric-ID routing, camera opens outside the sole
owner, Camera2 ownership imports in UI, services/workers, blocking/global coroutine patterns,
DataStore/network in camera hot boundaries, native camera control/private libraries/bare ownership,
RAW global registries, a suspending camera mutation block, generation-only callback admission,
repeated requested-configuration fallback, missing resource ownership entries, signer/floor drift,
package suffixes, and publication without a green dependency. Guards complement typed tests and
review; a regular-expression guard does not prove semantic correctness.

## Evidence boundaries

- Static CI proves source/model policy, compilation, unit tests, produced APK identity/signing, and
  all-ABI ELF compatibility for the exact SHA.
- A separately recorded API-23 emulator job may prove install, Activity start, permission flow, and
  actual `libcamx_core.so` load/JNI response for that emulator image. Assembly alone is not launch
  evidence.
- Only `HARDWARE_ACCEPTANCE.md` records physical lens, logical/physical routing, FPS, RAW, lifecycle,
  thermal, and leak behavior. Neither static CI nor an emulator permits a physical-device claim.

Final reporting names the exact commit, CI run and conclusion, version code/name, APK SHA-256, signer
SHA-256, artifact/download URL, and which of static CI, API-23 emulator, and physical-device evidence
actually exists. An ignored or stale local artifact is never accepted as evidence for a newer SHA.
