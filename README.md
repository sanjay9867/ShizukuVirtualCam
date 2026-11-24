# ShizukuVirtualCam (Rootless Android Virtual Camera)

## How It Works
1. **Shizuku Privileged UserService (`UID 2000`)**: Copies your selected `.mp4` video into `/data/local/tmp/vcam_feed.mp4` (`chmod 644`) and writes `/data/local/tmp/vcam_enabled`. This solves Android 11–16 Scoped Storage permission errors so any target app can read your video stream without extra storage permissions.
2. **LSPatch / Xposed Camera Hook**: Intercepts `Camera2` (`SessionConfiguration`) and `Camera1` (`setPreviewTexture`) inside patched target apps, feeding real hardware camera frames to an invisible dummy `SurfaceTexture` while streaming your MP4 video onto the app's preview/recording `Surface`.
3. **Zero-PC APK Build**: Push this folder to a GitHub repository and download the compiled `app-debug.apk` from the **Actions** tab.
