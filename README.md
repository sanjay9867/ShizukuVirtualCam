# ShizukuVirtualCam v1.3 (Rootless Android Virtual Camera)

## What Changed in v1.3
- Fixed the Brave/Chromium Camera2 session abort (`Give browser permission to use your camera`) caused by double-hooking `CameraDeviceImpl.createCaptureSession` and pushing `MediaCodec` directly onto a CPU-readable `YUV_420_888` `ImageReader` surface.
- Implemented native-compatible **YUV Frame Injection** (`MediaCodec` ByteBuffer `COLOR_FormatYUV420Flexible` -> `ImageReader.SurfaceImage.getPlanes()` DirectByteBuffer I420 planes) so Brave's Camera2 session configures cleanly with zero errors.
- Added a **Rotation selector** (`0°`, `90°`, `180°`, `270°`) in Step 2 so sideways front/back camera feeds can be fixed in one tap.
