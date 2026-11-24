# ShizukuVirtualCam v1.2 (Rootless Android Virtual Camera)

## What Changed in v1.2
- Added `CameraDeviceImpl.createCaptureSession(List<Surface>, StateCallback, Handler)` and `CaptureRequest.Builder.addTarget(Surface)` hooks required by **Brave, Chrome, and Chromium-based browsers**.
- Added `ImageReader` (`YUV_420_888`) tracking and a `MediaCodec` surface video decoder with automatic `MediaPlayer` fallback so browser WebRTC video feeds work reliably.
- Added on-screen diagnostic Toasts (`ShizukuVCam: Active ...` or `Video missing`) so you can immediately verify whether LSPatch loaded the module inside Brave.
