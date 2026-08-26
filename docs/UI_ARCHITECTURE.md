# UI and Design System Architecture

CamX uses a near-black/graphite camera surface with cool violet and electric-blue optical accents.
The geometric aperture/X launcher asset is original, mask-safe, adaptive, round, and supplies a
themed monochrome layer. The system splash reuses the optical mark without text. Settings can use a
modern light or dark Material scheme; camera mode remains dark for viewfinder continuity.

Compose owns overlays, navigation, settings presentation, and low-frequency state only. The
SurfaceView owns frame presentation. `CameraUiSnapshot` contains selected canonical identity, visible
lens count, capture availability/progress, one high-level error label, and update availability—never
CaptureResult or frame-by-frame metrics.

Fresh install launches directly into camera UI and immediately invokes the Android permission
contract. Denial leaves the viewfinder surface/controls in place. Permanent denial exposes a small
nonmodal App Settings action; there is no custom full-screen permission landing or operational modal.

Feature code emits typed intent. It cannot construct settings persistence, open Camera2 resources,
read global registries, or retain a Surface. Controls that lack real backing behavior remain disabled
or absent, preventing placeholder stream/gallery/capture features from becoming false product claims.
