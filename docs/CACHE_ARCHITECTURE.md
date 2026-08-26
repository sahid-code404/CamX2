# Cache Architecture

Cache is an optimization backed by verified evidence, never a source of new hardware truth. CAMX-101
persists the existing `HotStartSnapshot` and `CameraTopologySnapshot` as two independent files; neither
codec opens a camera, reads characteristics, performs JNI/native work, or depends on the other tier.

## Binary envelopes

Both files use a deterministic big-endian binary envelope:

1. four-byte tier magic (`CMXH` hot, `CMXT` topology);
2. format version (`1`);
3. snapshot schema version;
4. signed payload length checked against the tier bound before allocation;
5. CRC32 of the payload;
6. payload in explicit stable field order, with no Java object serialization, Parcelable, reflection,
   JSON, raw maps, or implicit class metadata.

CRC32 detects accidental/corrupt storage changes; it is not an authentication protocol. Unknown format
or schema is an operational cache miss. Bad magic, checksum, UTF-8, enum/boolean encoding, bounds,
truncation, trailing data, or topology relationship is internally `Corrupt` and is never published.

## Exact decode bounds

| Value | Maximum |
|---|---:|
| hot payload | 32 KiB |
| topology payload | 1 MiB |
| environment fingerprint UTF-8 | 1,024 bytes |
| IDs/fingerprints UTF-8 | 512 bytes each |
| preview configuration signature UTF-8 | 1,024 bytes |
| routes | 128 |
| canonical lenses | 64 |
| profiles per lens | 32 |
| profiles total | 128 |
| evidence records | 256 |
| route provenance sources | 4 |
| preview streams per capability set | 128 |
| FPS ranges per capability set | 64 |
| RAW sizes per capability set | 64 |
| focal lengths per evidence record | 32 |
| apertures per evidence record | 32 |

String lengths and collection counts are read and checked before any corresponding allocation. UTF-8
uses a decoder configured to report malformed/unmappable input. Boolean bytes accept only `0` or `1`;
enums accept only known ordinals. Existing model constructors then enforce value and topology
relationships, including unique routes/profiles, profile-to-route membership, canonical ownership,
positive sizes/FPS, finite optical values, and legal orientation.

## Hot tier

The hot file contains exactly the stable values represented by `HotStartSnapshot`: environment,
selection/profile/route identities, opaque transport IDs, resolved preview configuration, orientation,
facing, route/preview trust, and verification timestamp. It contains no complete topology, evidence
list, discovery record, or diagnostics payload. `readHot()` reads only `camx-hot.cache`.

## Topology tier

The topology file contains the existing immutable `CameraTopologySnapshot` graph: routes and
capabilities, canonical lenses and profiles, generated timestamp, and metadata evidence. Profile
records refer to route IDs instead of duplicating route objects; decode resolves those references only
after the complete bounded route table is valid. Publication uses `frozenCopy()` so caller-owned mutable
collections cannot alias repository state. `readTopology()` reads only `camx-topology.cache`.

## Environment and failure semantics

The persisted `CameraEnvironmentFingerprint` is compared by exact value. The current contract is one
typed fingerprint value, so any difference invalidates the entire record; CAMX-101 invents no brand,
model, SoC, sensor, OEM, or numeric-camera-ID compatibility rule.

Persistence distinguishes hit, miss, corruption, stale request, and transient I/O failure. Writes
distinguish success, bounded-format rejection, and I/O failure. Corruption, unknown schema, absent
files, and I/O failures never alter camera/profile trust. `CameraCacheRepository` preserves an already
valid same-environment in-memory snapshot when disk is absent, corrupt, stale, or unavailable. A
request for a different environment invalidates incompatible memory before considering persisted data.

## Atomic files and ownership

Each tier owns a separate authoritative file and sibling `.tmp` file. Writes follow:

`encode -> temp create/write -> flush -> FileDescriptor.sync() -> close -> Os.rename(temp, authority)`

`android.system.Os.rename` is a public API available below the API-23 application floor and performs
same-filesystem rename semantics for sibling files. The authoritative file is never opened for
truncating write. Failure before rename leaves the previous authority untouched; the abandoned temp is
best-effort deleted. A later write refuses to proceed until an old sibling temp can be removed.

Repository memory is published independently of disk durability and is versioned by the existing
per-tier request sequence. Newer intent wins publication; stale load completion cannot replace newer
memory, and serialized per-tier writes leave the final authoritative file at the newest completed
request. No arbitrary sleeps, service/worker architecture, network, or camera hot-path I/O is added.

## Evidence boundary

CAMX-101 establishes bounded persistent cache infrastructure only. Unit and CI evidence can prove
codec determinism, corruption handling, atomic replacement behavior, immutability, API-23 compilation,
and architectural boundaries. It does not establish working preview, discovery, lens switching, RAW,
physical-device compatibility, or a startup-performance improvement; those require their later tickets
and hardware measurements.
