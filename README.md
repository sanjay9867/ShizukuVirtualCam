# ShizukuVirtualCam v2.2 (Human-Motion Spring Physics + LivePortrait AI)

## What Changed in v2.2
- **LivePortrait AI Motion Transfer (`fofr/live-portrait`)**: Upload a face photo + pick a short 4-8s real selfie driving video -> generates a photorealistic 3D animated `.mp4` with genuine human head turns, blinks, occlusion, and lighting, and automatically deploys it via Shizuku.
- **Upgraded On-Device Human Motion Engine (`FaceWarpEngine.kt`)**:
  - Critically-damped spring physics for natural weighted head acceleration and deceleration.
  - Neck-anchored pivot + coupled natural head roll when turning left/right.
  - Organic human waypoint Auto-Move with randomized dwell pauses and micro-sway (replaces mechanical sine loop).
  - Asymmetric real blink envelope (65ms snap close -> 25ms hold -> 175ms soft open) + subtle eye-gaze lead.
