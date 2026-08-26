# CamX2 accepted baseline

CamX2 is based on the accepted CAMX-108 working frontier used by the computational RAW Architecture Revision 2.

- Source repository: `sahid-code404/CamX`
- Source branch: `phase/camx-108-one-shot-raw`
- Source commit: `75f56063cd34f802fe1e404574b496412ba3955c`
- Migration rule: preserve the working camera, AUX-lens, preview, one-shot RAW, OTA, diagnostics, cache, topology, and resource-ownership behavior while layering the EvidenceConstrainedImagingEngine architecture on top.
- Sole Camera2 authority remains `CameraSessionController`.
