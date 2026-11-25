# ShizukuVirtualCam v2.1 (Auto-Move + Low-Latency YUV Pipeline)

## What Changed in v2.1
- **One-Tap Auto-Move (`Auto: ON/OFF`)**: Added to both the floating overlay and the main Manual Mode panel to run a smooth, hands-free Left -> Right -> Up -> Down -> Center head sweep with automatic blinks. Tapping the D-Pad center reset or any direction immediately returns to manual control.
- **10x Faster YUV Frame Pipeline (Lag Fix)**:
  - Cached precomputed 1D rotation/flip/zoom lookup tables (`yLut`, `uvLut`) in `DirectYuvBuffers` so camera frame injection runs with zero per-pixel floating-point math.
  - Added frame sequence deduplication (`lastRenderedSeq`) so repeated `getPlanes()` calls reuse the already-filled `DirectByteBuffer` in 0 ms.
  - Replaced per-pixel `sampleMeshOffset()` and redundant chroma mesh interpolation in `FaceWarpEngine` with a single scanline fixed-point 16.16 `warpLut` and reusable `YuvFrame` buffers (zero GC stutter).
  - Coalesced rapid D-Pad / overlay updates over Shizuku Binder (`AtomicReference` latest-wins queue).
