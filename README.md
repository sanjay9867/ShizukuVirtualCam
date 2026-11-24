# ShizukuVirtualCam v1.1 (Rootless Android Virtual Camera)

## What Changed in v1.1
- Fixed `Failed to deploy video` by streaming the chosen `.mp4` over Shizuku AIDL using `ParcelFileDescriptor` instead of trying to read private `/data/user/0/.../cache/`.
- Added automatic app list detection in Step 3.
- Copies the deployed feed directly into `/storage/emulated/0/Android/data/<target_pkg>/files/vcam_feed.mp4` when you tap **Sync Video to App & Force-Stop**, eliminating Android 11-16 Scoped Storage & SELinux read failures.
