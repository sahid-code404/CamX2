# CameX → CamX AUX optical parity matrix

Behavioral oracle: `sahid-code404/CameX@cc165176f6fa9373ea30ef806b6cbab7da479556`.

| Behavior | CameX oracle | CamX hardware-failed behavior | Corrected CamX behavior |
| --- | --- | --- | --- |
| Exact profile merge | Same routing identity merges provider evidence into one profile. | Provider metadata conflicts could split one transport identity. | Direct identity is open transport; physical identity is logical parent + member; provider/source observations merge first. |
| Cross-route optical aliases | Strong independent optical evidence can group different transport IDs. | Required exact `strongOpticalKey` plus relationship anchor; otherwise route identity became lens identity. | Route/source/ID equality is not optical evidence; distinct routes group only on `STRONG_MATCH`. |
| Physical-member authority | Same parent/member is authoritative identity; different members of same parent conflict. | Relationship was mainly an anchor for otherwise exact optical keys. | Preserve the authoritative same-member/different-member rule independently of transport aliases. |
| Complete-link grouping | Candidate must strongly match every member. | Canonical key grouping had no pairwise complete-link matcher. | No transitive chain collapse; every group insertion is all-members `STRONG_MATCH`. |
| Optical fingerprint | Stable canonical optics, independent of preferred profile/transport. | Route identity frequently leaked into canonical fingerprint. | PARITY-2 derives fingerprint from merged canonical optical metadata; route-specific identity remains profile-only. |
| Profile selection | Verified profile wins; credible usable routes follow; structural rejection loses; transient failure is recoverable. | D2 already has trust-first, source-aware bounded same-lens selection/failover. | Preserve D2 architecture and feed it corrected sibling profiles; PARITY-2 aligns remaining confidence inputs. |
| Canonical metadata | Representative/voted optical metadata belongs to the lens. | UI derives optics from whichever profile currently wins. | PARITY-2 builds stable canonical optical metadata, independent of profile switching. |
| One button per lens | Profiles never become normal lens buttons. | Bad canonicalization caused route aliases to become multiple buttons. | UI continues projecting canonical lenses only; corrected topology removes alias duplicates structurally. |
| Stable 1× reference | Persisted compatible reference, otherwise deterministic primary rear lens. | Current active verified lens becomes `referenceMetric`, rebasing labels after every switch. | PARITY-3 selects a stable rear reference independent of active selection. |
| PreviewVerified truth | Exact verified profile reconnects to its canonical optical lens after first frame. | First-frame ownership is strong, but wrong topology can reconnect aliases to different lenses. | Keep CamX first-frame/generation ownership and reconnect exact route/profile through corrected canonical topology. |
