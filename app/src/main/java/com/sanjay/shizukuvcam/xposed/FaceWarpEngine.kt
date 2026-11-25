package com.sanjay.shizukuvcam.xposed

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Full 6DoF (6-Axis) Offline State:
 *  - Rotation: yaw (-1..+1), pitch (-1..+1), roll (-1..+1)
 *  - Translation: tx (-1..+1), ty (-1..+1), tz (-1..+1 depth closer/farther)
 */
class ControlState {
    @Volatile var enabled: Boolean = false
    @Volatile var mode: String = "photo" // "photo" or "video"

    // 3 Rotational axes (3DoF)
    @Volatile var yaw: Float = 0f        // Left (-) .. Right (+)
    @Volatile var pitch: Float = 0f      // Down (-) .. Up (+)
    @Volatile var roll: Float = 0f       // Tilt Left (-) .. Tilt Right (+)

    // 3 Translational axes (3DoF -> total 6DoF / 6D)
    @Volatile var tx: Float = 0f         // Shift Left (-) .. Right (+)
    @Volatile var ty: Float = 0f         // Shift Down (-) .. Up (+)
    @Volatile var tz: Float = 0f         // Pull Back (-) .. Push Closer (+) with 3D perspective

    @Volatile var zoom: Float = 1.0f
    @Volatile var flipH: Boolean = false
    @Volatile var flipV: Boolean = false
    @Volatile var rotation: Int = 270
    @Volatile var blinkSeq: Long = 0L
    @Volatile var smile: Float = 0f
    @Volatile var autoIdle: Boolean = true
    @Volatile var autoSweep: Boolean = false

    // Detected facial geometry anchors
    @Volatile var faceCx: Float = 0.50f
    @Volatile var faceCy: Float = 0.48f
    @Volatile var faceR: Float = 0.22f
    @Volatile var eyeLx: Float = 0.41f
    @Volatile var eyeLy: Float = 0.43f
    @Volatile var eyeRx: Float = 0.59f
    @Volatile var eyeRy: Float = 0.43f
    @Volatile var assetVersion: Long = 1L

    fun reset6D() {
        yaw = 0f
        pitch = 0f
        roll = 0f
        tx = 0f
        ty = 0f
        tz = 0f
        smile = 0f
    }

    fun serialize(): String {
        val sb = StringBuilder(384)
        sb.append("enabled=").append(if (enabled) "1" else "0").append('\n')
        sb.append("mode=").append(mode).append('\n')
        sb.append("yaw=").append(yaw).append('\n')
        sb.append("pitch=").append(pitch).append('\n')
        sb.append("roll=").append(roll).append('\n')
        sb.append("tx=").append(tx).append('\n')
        sb.append("ty=").append(ty).append('\n')
        sb.append("tz=").append(tz).append('\n')
        sb.append("zoom=").append(zoom).append('\n')
        sb.append("flip_h=").append(if (flipH) "1" else "0").append('\n')
        sb.append("flip_v=").append(if (flipV) "1" else "0").append('\n')
        sb.append("rotation=").append(rotation).append('\n')
        sb.append("blink_seq=").append(blinkSeq).append('\n')
        sb.append("smile=").append(smile).append('\n')
        sb.append("auto_idle=").append(if (autoIdle) "1" else "0").append('\n')
        sb.append("auto_sweep=").append(if (autoSweep) "1" else "0").append('\n')
        sb.append("face_cx=").append(faceCx).append('\n')
        sb.append("face_cy=").append(faceCy).append('\n')
        sb.append("face_r=").append(faceR).append('\n')
        sb.append("eye_lx=").append(eyeLx).append('\n')
        sb.append("eye_ly=").append(eyeLy).append('\n')
        sb.append("eye_rx=").append(eyeRx).append('\n')
        sb.append("eye_ry=").append(eyeRy).append('\n')
        sb.append("ver=").append(assetVersion).append('\n')
        return sb.toString()
    }

    fun copyFrom(other: ControlState) {
        enabled = other.enabled
        mode = other.mode
        yaw = other.yaw
        pitch = other.pitch
        roll = other.roll
        tx = other.tx
        ty = other.ty
        tz = other.tz
        zoom = other.zoom
        flipH = other.flipH
        flipV = other.flipV
        rotation = other.rotation
        blinkSeq = other.blinkSeq
        smile = other.smile
        autoIdle = other.autoIdle
        autoSweep = other.autoSweep
        faceCx = other.faceCx
        faceCy = other.faceCy
        faceR = other.faceR
        eyeLx = other.eyeLx
        eyeLy = other.eyeLy
        eyeRx = other.eyeRx
        eyeRy = other.eyeRy
        assetVersion = other.assetVersion
    }

    companion object {
        fun parse(text: String): ControlState {
            val state = ControlState()
            if (text.isBlank()) return state
            for (rawLine in text.lineSequence()) {
                val eq = rawLine.indexOf('=')
                if (eq <= 0) continue
                val key = rawLine.substring(0, eq).trim()
                val value = rawLine.substring(eq + 1).trim()
                when (key) {
                    "enabled" -> state.enabled = value == "1"
                    "mode" -> state.mode = if (value == "video") "video" else "photo"
                    "yaw" -> state.yaw = (value.toFloatOrNull() ?: 0f).coerceIn(-1f, 1f)
                    "pitch" -> state.pitch = (value.toFloatOrNull() ?: 0f).coerceIn(-1f, 1f)
                    "roll" -> state.roll = (value.toFloatOrNull() ?: 0f).coerceIn(-1f, 1f)
                    "tx" -> state.tx = (value.toFloatOrNull() ?: 0f).coerceIn(-1f, 1f)
                    "ty" -> state.ty = (value.toFloatOrNull() ?: 0f).coerceIn(-1f, 1f)
                    "tz" -> state.tz = (value.toFloatOrNull() ?: 0f).coerceIn(-1f, 1f)
                    "zoom" -> state.zoom = (value.toFloatOrNull() ?: 1f).coerceIn(0.6f, 2.2f)
                    "flip_h" -> state.flipH = value == "1"
                    "flip_v" -> state.flipV = value == "1"
                    "rotation" -> state.rotation = when (value.toIntOrNull()) {
                        0, 90, 180, 270 -> value.toInt()
                        else -> 270
                    }
                    "blink_seq" -> state.blinkSeq = value.toLongOrNull() ?: 0L
                    "smile" -> state.smile = (value.toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
                    "auto_idle" -> state.autoIdle = value == "1"
                    "auto_sweep" -> state.autoSweep = value == "1"
                    "face_cx" -> state.faceCx = (value.toFloatOrNull() ?: 0.50f).coerceIn(0.15f, 0.85f)
                    "face_cy" -> state.faceCy = (value.toFloatOrNull() ?: 0.48f).coerceIn(0.15f, 0.85f)
                    "face_r" -> state.faceR = (value.toFloatOrNull() ?: 0.22f).coerceIn(0.08f, 0.45f)
                    "eye_lx" -> state.eyeLx = (value.toFloatOrNull() ?: 0.41f).coerceIn(0.10f, 0.90f)
                    "eye_ly" -> state.eyeLy = (value.toFloatOrNull() ?: 0.43f).coerceIn(0.10f, 0.90f)
                    "eye_rx" -> state.eyeRx = (value.toFloatOrNull() ?: 0.59f).coerceIn(0.10f, 0.90f)
                    "eye_ry" -> state.eyeRy = (value.toFloatOrNull() ?: 0.43f).coerceIn(0.10f, 0.90f)
                    "ver" -> state.assetVersion = value.toLongOrNull() ?: 1L
                }
            }
            return state
        }
    }
}

class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    @Volatile var seq: Long = 0L
)

class FacePhotoAsset(
    val width: Int,
    val height: Int,
    val baseYuv: YuvFrame,
    val baseArgb: IntArray
)

object FaceWarpEngine {

    private const val GRID_X = 24
    private const val GRID_Y = 24

    fun loadPhotoAsset(
        basePath: String,
        maxDim: Int,
        includeArgb: Boolean
    ): FacePhotoAsset? {
        val baseBmp = decodeScaledBitmap(basePath, maxDim) ?: return null
        val w = baseBmp.width
        val h = baseBmp.height
        val baseArgb = IntArray(w * h)
        baseBmp.getPixels(baseArgb, 0, w, 0, 0, w, h)
        baseBmp.recycle()

        val baseYuv = argbToYuv(baseArgb, w, h)
        return FacePhotoAsset(
            width = w,
            height = h,
            baseYuv = baseYuv,
            baseArgb = if (includeArgb) baseArgb else IntArray(0)
        )
    }

    private fun decodeScaledBitmap(path: String, maxDim: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var sample = 1
            while (opts.outWidth / sample > maxDim * 2 || opts.outHeight / sample > maxDim * 2) {
                sample *= 2
            }
            val decOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeFile(path, decOpts) ?: return null
            val scale = minOf(maxDim.toFloat() / raw.width, maxDim.toFloat() / raw.height, 1f)
            val tw = ((raw.width * scale).toInt() and 1.inv()).coerceAtLeast(64)
            val th = ((raw.height * scale).toInt() and 1.inv()).coerceAtLeast(64)
            if (tw == raw.width && th == raw.height) {
                raw
            } else {
                val scaled = Bitmap.createScaledBitmap(raw, tw, th, true)
                if (scaled !== raw) raw.recycle()
                scaled
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun argbToYuv(argb: IntArray, w: Int, h: Int): YuvFrame {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val yArr = ByteArray(w * h)
        val uArr = ByteArray(cw * ch)
        val vArr = ByteArray(cw * ch)

        for (j in 0 until h) {
            val rowOff = j * w
            val cRowOff = (j shr 1) * cw
            for (i in 0 until w) {
                val px = argb[rowOff + i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yArr[rowOff + i] = yVal.coerceIn(16, 235).toByte()
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val uVal = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vVal = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val ci = cRowOff + (i shr 1)
                    uArr[ci] = uVal.coerceIn(16, 240).toByte()
                    vArr[ci] = vVal.coerceIn(16, 240).toByte()
                }
            }
        }
        return YuvFrame(w, h, yArr, uArr, vArr, 1L)
    }

    /**
     * 100% Offline 6D (6DoF) Depth-Relief & Spring-Physics Animator:
     * - Builds an anatomical 3D facial heightfield Z(u, v) (skull ellipsoid + nose bridge + cheek contour + eye sockets)
     * - Applies true 3x3 3D Euler rotation (Yaw, Pitch, Roll) + 3D Translation (X, Y, Z) + pinhole perspective projection
     * - Computes subtle Lambertian 3D micro-shading across cheeks/nose as head turns
     * - Uses critically-damped 6D springs + asymmetric real blink curve + eye-gaze saccade lead
     */
    class MotionAnimator {
        // 6D smoothed physical state
        var curYaw = 0f
        var curPitch = 0f
        var curRoll = 0f
        var curTx = 0f
        var curTy = 0f
        var curTz = 0f

        private var velYaw = 0f
        private var velPitch = 0f
        private var velRoll = 0f
        private var velTx = 0f
        private var velTy = 0f
        private var velTz = 0f

        var curSmile = 0f
        var curBlink = 0f
        var curGazeX = 0f
        var curGazeY = 0f

        private var lastStepMs = 0L
        private var lastBlinkSeq = 0L
        private var blinkStartMs = 0L
        private var nextAutoBlinkMs = System.currentTimeMillis() + 2600L
        private var frameCounter = 0L

        // Organic 6D Auto-Move waypoint state machine
        private var sweepIdx = 0
        private var sweepNextSwitchMs = 0L
        private var swYaw = 0f
        private var swPitch = 0f
        private var swRoll = 0f
        private var swTx = 0f
        private var swTy = 0f
        private var swTz = 0f
        private var swSmile = 0f
        private var rngState = 0x5DEECE66DL xor System.nanoTime()

        private val gridCount = (GRID_X + 1) * (GRID_Y + 1)
        private val gridDx = FloatArray(gridCount)
        private val gridDy = FloatArray(gridCount)
        private val gridShade = FloatArray(gridCount) { 1f }

        // Cached anatomical 3D face depth & normals (rebuilt only if face landmarks change)
        private val depthZ = FloatArray(gridCount)
        private val headMaskArr = FloatArray(gridCount)
        private val baseNx = FloatArray(gridCount)
        private val baseNy = FloatArray(gridCount)
        private val baseNz = FloatArray(gridCount) { 1f }
        private var depthSig = 0

        // Reusable scanline LUT + YUV buffers (zero GC allocation per frame)
        private var cachedW = 0
        private var cachedH = 0
        private var warpLut = IntArray(0)
        private var shadeLut = ShortArray(0) // 8.8 fixed point luminance multiplier (256 = 1.0x)
        private var reusedYuv: YuvFrame? = null

        private fun nextUnitFloat(): Float {
            rngState = rngState * 6364136223846793005L + 1442695040888963407L
            val bits = ((rngState ushr 33) and 0x7FFFFFFFL).toInt()
            return bits.toFloat() / 0x7FFFFFFFL.toFloat()
        }

        private fun organicNoise(tSec: Float, seed: Float): Float {
            return (sin(tSec * 0.67f + seed) * 0.48f +
                sin(tSec * 1.53f + seed * 2.1f) * 0.32f +
                cos(tSec * 2.71f + seed * 0.8f) * 0.20f)
        }

        private fun ensureDepthModel(ctrl: ControlState) {
            var sig = (ctrl.faceCx * 1000).toInt()
            sig = 31 * sig + (ctrl.faceCy * 1000).toInt()
            sig = 31 * sig + (ctrl.faceR * 1000).toInt()
            sig = 31 * sig + (ctrl.eyeLx * 1000).toInt()
            sig = 31 * sig + (ctrl.eyeRx * 1000).toInt()
            if (sig == depthSig) return
            depthSig = sig

            val cx = ctrl.faceCx
            val cy = ctrl.faceCy
            val rx = (ctrl.faceR * 1.28f).coerceAtLeast(0.13f)
            val ry = (ctrl.faceR * 1.60f).coerceAtLeast(0.16f)
            val noseX = cx
            val noseY = cy + ctrl.faceR * 0.06f
            val eyeR = (ctrl.faceR * 0.24f).coerceAtLeast(0.03f)
            val cols = GRID_X + 1

            for (gy in 0..GRID_Y) {
                val v = gy.toFloat() / GRID_Y
                for (gx in 0..GRID_X) {
                    val u = gx.toFloat() / GRID_X
                    val nx = (u - cx) / rx
                    val ny = (v - cy) / ry
                    val r2 = nx * nx + ny * ny

                    // 1. Base 3D head ellipsoid height
                    val skullZ = if (r2 < 1.35f) {
                        val s = (1f - r2 / 1.35f).coerceIn(0f, 1f)
                        sqrt(s) * 0.22f
                    } else {
                        0f
                    }

                    // 2. Protruding 3D nose bridge & tip (drives unmistakable 3D parallax!)
                    val ndx = (u - noseX) / (rx * 0.26f)
                    val ndy = (v - noseY) / (ry * 0.34f)
                    val noseZ = (exp(-(ndx * ndx + ndy * ndy).toDouble()).toFloat()) * 0.095f

                    // 3. Subtle cheek volume + brow ridge
                    val lCheekX = (u - (cx - rx * 0.42f)) / (rx * 0.40f)
                    val rCheekX = (u - (cx + rx * 0.42f)) / (rx * 0.40f)
                    val cheekY = (v - (cy + ry * 0.14f)) / (ry * 0.35f)
                    val cheekZ = (exp(-(lCheekX * lCheekX + cheekY * cheekY).toDouble()).toFloat() +
                        exp(-(rCheekX * rCheekX + cheekY * cheekY).toDouble()).toFloat()) * 0.032f

                    // 4. Slight eye socket indentation
                    val lex = (u - ctrl.eyeLx) / (eyeR * 1.4f)
                    val ley = (v - ctrl.eyeLy) / (eyeR * 1.1f)
                    val rex = (u - ctrl.eyeRx) / (eyeR * 1.4f)
                    val rey = (v - ctrl.eyeRy) / (eyeR * 1.1f)
                    val socketDip = (exp(-(lex * lex + ley * ley).toDouble()).toFloat() +
                        exp(-(rex * rex + rey * rey).toDouble()).toFloat()) * 0.022f

                    // Smooth blend mask separating foreground head from background
                    val mask = if (r2 < 2.35f) {
                        val f = 1f - (r2 / 2.35f)
                        f * f * (3f - 2f * f)
                    } else {
                        0f
                    }

                    // Soft anchor at lower shoulders so head pivots naturally on neck
                    val neckY = (cy + ctrl.faceR * 1.40f).coerceIn(0.55f, 0.95f)
                    val shoulderPin = if (v > neckY - 0.12f) {
                        (1f - ((v - (neckY - 0.12f)) / 0.24f)).coerceIn(0.18f, 1f)
                    } else {
                        1f
                    }

                    val idx = gy * cols + gx
                    depthZ[idx] = ((skullZ + noseZ + cheekZ - socketDip).coerceAtLeast(0f)) * mask
                    headMaskArr[idx] = mask * shoulderPin
                }
            }

            // Estimate 3D surface normals via finite differences on depthZ
            val du = 1f / GRID_X
            val dv = 1f / GRID_Y
            for (gy in 0..GRID_Y) {
                for (gx in 0..GRID_X) {
                    val idx = gy * cols + gx
                    val gxL = (gx - 1).coerceAtLeast(0)
                    val gxR = (gx + 1).coerceAtMost(GRID_X)
                    val gyU = (gy - 1).coerceAtLeast(0)
                    val gyD = (gy + 1).coerceAtMost(GRID_Y)

                    val dzdx = (depthZ[gy * cols + gxR] - depthZ[gy * cols + gxL]) / ((gxR - gxL) * du)
                    val dzdy = (depthZ[gyD * cols + gx] - depthZ[gyU * cols + gx]) / ((gyD - gyU) * dv)

                    val nx = -dzdx * 1.6f
                    val ny = -dzdy * 1.6f
                    val nz = 1f
                    val invLen = 1f / sqrt(nx * nx + ny * ny + nz * nz)
                    baseNx[idx] = nx * invLen
                    baseNy[idx] = ny * invLen
                    baseNz[idx] = nz * invLen
                }
            }
        }

        private fun advanceHuman6DWaypoint(nowMs: Long) {
            if (nowMs < sweepNextSwitchMs) return
            val j1 = (nextUnitFloat() - 0.5f) * 0.16f
            val j2 = (nextUnitFloat() - 0.5f) * 0.14f
            val j3 = (nextUnitFloat() - 0.5f) * 0.12f

            when (sweepIdx % 8) {
                0 -> { // Centered calm posture
                    swYaw = j1 * 0.35f; swPitch = j2 * 0.35f; swRoll = j3 * 0.25f
                    swTx = 0f; swTy = 0f; swTz = 0.08f; swSmile = 0.08f
                    sweepNextSwitchMs = nowMs + 1050L + (nextUnitFloat() * 600L).toLong()
                }
                1 -> { // Turn Left + slight natural tilt + slight lean in
                    swYaw = -0.68f + j1; swPitch = 0.06f + j2 * 0.4f; swRoll = 0.22f + j3 * 0.3f
                    swTx = -0.22f; swTy = 0.04f; swTz = 0.18f; swSmile = 0f
                    sweepNextSwitchMs = nowMs + 1300L + (nextUnitFloat() * 550L).toLong()
                }
                2 -> { // Return Center + blink
                    swYaw = 0.04f + j1 * 0.3f; swPitch = -0.05f + j2 * 0.3f; swRoll = 0f
                    swTx = 0f; swTy = -0.03f; swTz = 0.05f; swSmile = 0.18f
                    blinkStartMs = nowMs + 100L
                    sweepNextSwitchMs = nowMs + 950L + (nextUnitFloat() * 450L).toLong()
                }
                3 -> { // Turn Right + opposite tilt + shift right
                    swYaw = 0.68f + j1; swPitch = 0.05f + j2 * 0.4f; swRoll = -0.22f + j3 * 0.3f
                    swTx = 0.22f; swTy = 0.03f; swTz = 0.16f; swSmile = 0f
                    sweepNextSwitchMs = nowMs + 1300L + (nextUnitFloat() * 550L).toLong()
                }
                4 -> { // Look Up + lean slightly back
                    swYaw = j1 * 0.4f; swPitch = 0.56f + j2 * 0.4f; swRoll = j3 * 0.25f
                    swTx = 0f; swTy = 0.18f; swTz = -0.18f; swSmile = 0f
                    sweepNextSwitchMs = nowMs + 1150L + (nextUnitFloat() * 500L).toLong()
                }
                5 -> { // Nod Down + subtle approach closer (Z+)
                    swYaw = j1 * 0.4f; swPitch = -0.48f + j2 * 0.4f; swRoll = -j3 * 0.25f
                    swTx = 0f; swTy = -0.15f; swTz = 0.26f; swSmile = 0.06f
                    sweepNextSwitchMs = nowMs + 1100L + (nextUnitFloat() * 450L).toLong()
                }
                6 -> { // Warm camera glance + slight head tilt + blink
                    swYaw = j1 * 0.25f; swPitch = 0.02f; swRoll = 0.18f
                    swTx = 0.05f; swTy = 0.02f; swTz = 0.20f; swSmile = 0.42f
                    blinkStartMs = nowMs + 80L
                    sweepNextSwitchMs = nowMs + 1200L + (nextUnitFloat() * 550L).toLong()
                }
                else -> { // Relax back to neutral 6D origin
                    swYaw = 0f; swPitch = 0f; swRoll = 0f
                    swTx = 0f; swTy = 0f; swTz = 0f; swSmile = 0f
                    sweepNextSwitchMs = nowMs + 900L + (nextUnitFloat() * 450L).toLong()
                }
            }
            sweepIdx++
        }

        private fun stepSpring(cur: Float, vel: Float, target: Float, k: Float, d: Float, dt: Float): Pair<Float, Float> {
            val acc = (target - cur) * k - vel * d
            val nextVel = vel + acc * dt
            val nextCur = (cur + nextVel * dt).coerceIn(-1.15f, 1.15f)
            return nextCur to nextVel
        }

        fun step(ctrl: ControlState, nowMs: Long) {
            val dt = if (lastStepMs == 0L) 0.033f else ((nowMs - lastStepMs) * 0.001f).coerceIn(0.01f, 0.075f)
            lastStepMs = nowMs
            val tSec = (nowMs % 600_000L) * 0.001f

            var tYaw = ctrl.yaw
            var tPitch = ctrl.pitch
            var tRoll = ctrl.roll
            var tTx = ctrl.tx
            var tTy = ctrl.ty
            var tTz = ctrl.tz
            var tSmile = ctrl.smile

            if (ctrl.autoSweep) {
                advanceHuman6DWaypoint(nowMs)
                tYaw = swYaw
                tPitch = swPitch
                tRoll = swRoll
                tTx = swTx
                tTy = swTy
                tTz = swTz
                if (tSmile < 0.05f) tSmile = swSmile
            } else {
                sweepNextSwitchMs = 0L
            }

            // Subtle natural coupled roll & organic 6D human micro-sway
            if (ctrl.autoIdle || ctrl.autoSweep) {
                tYaw += organicNoise(tSec, 1.3f) * 0.05f
                tPitch += organicNoise(tSec, 4.7f) * 0.04f
                tRoll += (-tYaw * 0.10f) + organicNoise(tSec, 8.2f) * 0.03f
                tTx += organicNoise(tSec, 2.9f) * 0.035f
                tTy += sin(tSec * 1.45f) * 0.025f // Natural breathing lift
                tTz += sin(tSec * 1.45f + 0.6f) * 0.035f // Subtle breathing depth shift
            }

            val kRot = if (ctrl.autoSweep) 22f else 36f
            val dRot = if (ctrl.autoSweep) 8.8f else 11.5f
            val kPos = if (ctrl.autoSweep) 18f else 32f
            val dPos = if (ctrl.autoSweep) 8.2f else 10.8f

            val rYaw = stepSpring(curYaw, velYaw, tYaw, kRot, dRot, dt)
            curYaw = rYaw.first; velYaw = rYaw.second

            val rPitch = stepSpring(curPitch, velPitch, tPitch, kRot, dRot, dt)
            curPitch = rPitch.first; velPitch = rPitch.second

            val rRoll = stepSpring(curRoll, velRoll, tRoll, kRot, dRot, dt)
            curRoll = rRoll.first; velRoll = rRoll.second

            val rTx = stepSpring(curTx, velTx, tTx, kPos, dPos, dt)
            curTx = rTx.first; velTx = rTx.second

            val rTy = stepSpring(curTy, velTy, tTy, kPos, dPos, dt)
            curTy = rTy.first; velTy = rTy.second

            val rTz = stepSpring(curTz, velTz, tTz, kPos, dPos, dt)
            curTz = rTz.first; velTz = rTz.second

            // Fast eye-gaze saccade leading head turn
            val targetGazeX = (tYaw - curYaw * 0.45f).coerceIn(-0.85f, 0.85f)
            val targetGazeY = (tPitch - curPitch * 0.45f).coerceIn(-0.65f, 0.65f)
            curGazeX += (targetGazeX - curGazeX) * (dt * 16f).coerceAtMost(1f)
            curGazeY += (targetGazeY - curGazeY) * (dt * 16f).coerceAtMost(1f)
            curSmile += (tSmile - curSmile) * (dt * 6.5f).coerceAtMost(1f)

            // Randomized natural blink + occasional double-blink
            if (ctrl.blinkSeq != lastBlinkSeq) {
                lastBlinkSeq = ctrl.blinkSeq
                blinkStartMs = nowMs
            }
            if ((ctrl.autoIdle || ctrl.autoSweep) && nowMs >= nextAutoBlinkMs && (nowMs - blinkStartMs) > 550L) {
                blinkStartMs = nowMs
                val shortDouble = nextUnitFloat() < 0.16f
                nextAutoBlinkMs = nowMs + if (shortDouble) 600L else (2300L + (nextUnitFloat() * 3400L).toLong())
            }

            // Asymmetric human blink envelope (65ms close -> 25ms hold -> 175ms open)
            val bElapsed = nowMs - blinkStartMs
            curBlink = when {
                bElapsed < 0L || bElapsed > 265L -> 0f
                bElapsed < 65L -> {
                    val p = bElapsed / 65f
                    p * p * (3f - 2f * p)
                }
                bElapsed < 90L -> 1f
                else -> {
                    val p = 1f - ((bElapsed - 90L) / 175f)
                    (p * p * (3f - 2f * p)).coerceIn(0f, 1f)
                }
            }
        }

        /**
         * True 3D 6DoF Perspective Projection Mesh:
         * Transforms every vertex on the anatomical 3D depth-relief surface using a 3x3 Euler
         * rotation matrix (Yaw, Pitch, Roll) and 3D translation (X, Y, Z) with pinhole perspective.
         */
        private fun compute6DMesh(ctrl: ControlState) {
            ensureDepthModel(ctrl)

            // Physical angles in radians (restrained to natural human range so 3D relief stays realistic)
            val yawRad = curYaw * 0.34f       // up to ~±19.5°
            val pitchRad = curPitch * 0.28f   // up to ~±16°
            val rollRad = curRoll * 0.30f     // up to ~±17°

            // 3D translation in normalized camera coordinates
            val shiftX = curTx * 0.12f
            val shiftY = -curTy * 0.11f
            val shiftZ = curTz * 0.28f        // Positive = head moves closer to camera lens

            val cyA = cos(yawRad); val syA = sin(yawRad)
            val cpA = cos(pitchRad); val spA = sin(pitchRad)
            val crA = cos(rollRad); val srA = sin(rollRad)

            // Combined 3x3 inverse rotation matrix R^T = Rx(-pitch) * Ry(-yaw) * Rz(-roll)
            val r00 = cyA * crA
            val r01 = cyA * srA
            val r02 = -syA

            val r10 = spA * syA * crA - cpA * srA
            val r11 = spA * syA * srA + cpA * crA
            val r12 = spA * cyA

            val r20 = cpA * syA * crA + spA * srA
            val r21 = cpA * syA * srA - spA * crA
            val r22 = cpA * cyA

            // Pinhole camera distance (lower = stronger 3D perspective foreshortening when moving Z)
            val camDist = 1.95f
            val pivotX = ctrl.faceCx
            val pivotY = ctrl.faceCy + ctrl.faceR * 0.18f
            val pivotZ = 0.08f

            val eyeR = (ctrl.faceR * 0.23f).coerceAtLeast(0.03f)
            val mouthY = (ctrl.faceCy + ctrl.faceR * 0.56f).coerceIn(0.25f, 0.90f)
            val mouthRx = (ctrl.faceR * 0.48f).coerceAtLeast(0.06f)

            // Soft studio light direction (slightly above and in front)
            val lightX = 0.18f
            val lightY = -0.26f
            val lightZ = 0.948f

            val cols = GRID_X + 1
            for (gy in 0..GRID_Y) {
                val v = gy.toFloat() / GRID_Y
                for (gx in 0..GRID_X) {
                    val u = gx.toFloat() / GRID_X
                    val idx = gy * cols + gx

                    val zSurf = depthZ[idx]
                    val mask = headMaskArr[idx]

                    var dx = 0f
                    var dy = 0f
                    var shade = 1.0f

                    if (mask > 0.001f) {
                        // Local 3D point on the head surface
                        val lx = u - pivotX - shiftX * mask
                        val ly = v - pivotY - shiftY * mask
                        val lz = (zSurf - pivotZ) + shiftZ * (0.45f + 0.55f * (zSurf / 0.30f).coerceIn(0f, 1f)) * mask

                        // Inverse 3D rotation to find corresponding source texture coordinate
                        val rx3 = r00 * lx + r01 * ly + r02 * lz
                        val ry3 = r10 * lx + r11 * ly + r12 * lz
                        val rz3 = r20 * lx + r21 * ly + r22 * lz

                        // Perspective depth projection (closer nose shifts/scales more than recessed ears/background)
                        val persp = camDist / (camDist + (rz3 - (zSurf - pivotZ)) * 1.35f).coerceAtLeast(0.85f)
                        val srcU = pivotX + rx3 * persp
                        val srcV = pivotY + ry3 * persp

                        dx = (srcU - u) * mask
                        dy = (srcV - v) * mask

                        // Subtle 3D Lambertian micro-shading as cheeks/nose turn toward/away from studio light
                        val bnx = baseNx[idx]
                        val bny = baseNy[idx]
                        val bnz = baseNz[idx]
                        // Forward-rotated normal for lighting
                        val fnx = r00 * bnx + r10 * bny + r20 * bnz
                        val fny = r01 * bnx + r11 * bny + r21 * bnz
                        val fnz = r02 * bnx + r12 * bny + r22 * bnz

                        val baseDot = (bnx * lightX + bny * lightY + bnz * lightZ).coerceIn(0.25f, 1f)
                        val rotDot = (fnx * lightX + fny * lightY + fnz * lightZ).coerceIn(0.20f, 1f)
                        val relLight = 1f + (rotDot - baseDot) * 0.32f * mask
                        shade = relLight.coerceIn(0.88f, 1.12f)
                    }

                    // Eyelid blink & pupil saccade gaze
                    val lex = (u - ctrl.eyeLx) / (eyeR * 1.30f)
                    val ley = (v - ctrl.eyeLy) / (eyeR * 0.85f)
                    val le2 = lex * lex + ley * ley
                    if (le2 < 1.25f) {
                        val ew = 1f - le2 / 1.25f
                        val ew2 = ew * ew
                        if (curBlink > 0.02f) {
                            dy -= curBlink * eyeR * 0.82f * ew2
                            shade *= (1f - curBlink * 0.14f * ew2)
                        } else {
                            dx -= curGazeX * eyeR * 0.18f * ew2
                            dy += curGazeY * eyeR * 0.14f * ew2
                        }
                    }

                    val rex = (u - ctrl.eyeRx) / (eyeR * 1.30f)
                    val rey = (v - ctrl.eyeRy) / (eyeR * 0.85f)
                    val re2 = rex * rex + rey * rey
                    if (re2 < 1.25f) {
                        val ew = 1f - re2 / 1.25f
                        val ew2 = ew * ew
                        if (curBlink > 0.02f) {
                            dy -= curBlink * eyeR * 0.82f * ew2
                            shade *= (1f - curBlink * 0.14f * ew2)
                        } else {
                            dx -= curGazeX * eyeR * 0.18f * ew2
                            dy += curGazeY * eyeR * 0.14f * ew2
                        }
                    }

                    // Gentle smile corner lift
                    if (curSmile > 0.02f) {
                        val mx = (u - ctrl.faceCx) / mouthRx
                        val my = (v - mouthY) / (mouthRx * 0.55f)
                        val m2 = mx * mx + my * my
                        if (m2 < 1.45f) {
                            val mw = 1f - m2 / 1.45f
                            val cornerFactor = abs(mx).coerceIn(0f, 1.2f)
                            dy += curSmile * mouthRx * 0.20f * cornerFactor * mw * mw
                            dx -= curSmile * mx * mouthRx * 0.09f * mw * mw
                        }
                    }

                    gridDx[idx] = dx
                    gridDy[idx] = dy
                    gridShade[idx] = shade
                }
            }
        }

        private fun buildWarpAndShadeLut(w: Int, h: Int) {
            if (cachedW != w || cachedH != h || warpLut.size != w * h) {
                cachedW = w
                cachedH = h
                warpLut = IntArray(w * h)
                shadeLut = ShortArray(w * h)
                val cw = (w + 1) / 2
                val ch = (h + 1) / 2
                reusedYuv = YuvFrame(w, h, ByteArray(w * h), ByteArray(cw * ch), ByteArray(cw * ch), 0L)
            }

            val cols = GRID_X + 1
            val maxX = w - 1
            val maxY = h - 1
            val lut = warpLut
            val sLut = shadeLut

            for (gy in 0 until GRID_Y) {
                val yStart = (gy * h) / GRID_Y
                val yEnd = ((gy + 1) * h) / GRID_Y
                val cellH = (yEnd - yStart).coerceAtLeast(1)
                val row0 = gy * cols
                val row1 = row0 + cols

                for (gx in 0 until GRID_X) {
                    val xStart = (gx * w) / GRID_X
                    val xEnd = ((gx + 1) * w) / GRID_X
                    val cellW = (xEnd - xStart).coerceAtLeast(1)

                    val i00 = row0 + gx
                    val i10 = i00 + 1
                    val i01 = row1 + gx
                    val i11 = i01 + 1

                    val dx00 = gridDx[i00] * w; val dx10 = gridDx[i10] * w
                    val dx01 = gridDx[i01] * w; val dx11 = gridDx[i11] * w

                    val dy00 = gridDy[i00] * h; val dy10 = gridDy[i10] * h
                    val dy01 = gridDy[i01] * h; val dy11 = gridDy[i11] * h

                    val sh00 = gridShade[i00]; val sh10 = gridShade[i10]
                    val sh01 = gridShade[i01]; val sh11 = gridShade[i11]

                    val invCellH = 1f / cellH
                    val invCellW = 1f / cellW

                    for (y in yStart until yEnd) {
                        val ty = (y - yStart) * invCellH
                        val leftDx = dx00 + (dx01 - dx00) * ty
                        val rightDx = dx10 + (dx11 - dx10) * ty
                        val leftDy = dy00 + (dy01 - dy00) * ty
                        val rightDy = dy10 + (dy11 - dy10) * ty
                        val leftSh = sh00 + (sh01 - sh00) * ty
                        val rightSh = sh10 + (sh11 - sh10) * ty

                        var curDxFp = ((xStart + leftDx) * 65536f).toInt()
                        val stepDxFp = (((rightDx - leftDx) * invCellW + 1f) * 65536f).toInt()

                        var curDyFp = ((y + leftDy) * 65536f).toInt()
                        val stepDyFp = (((rightDy - leftDy) * invCellW) * 65536f).toInt()

                        var curShFp = (leftSh * 256f * 256f).toInt()
                        val stepShFp = (((rightSh - leftSh) * invCellW) * 256f * 256f).toInt()

                        var dstIdx = y * w + xStart
                        for (x in xStart until xEnd) {
                            val sx = (curDxFp shr 16).coerceIn(0, maxX)
                            val sy = (curDyFp shr 16).coerceIn(0, maxY)
                            lut[dstIdx] = sy * w + sx
                            sLut[dstIdx] = (curShFp shr 8).coerceIn(190, 310).toShort()
                            dstIdx++
                            curDxFp += stepDxFp
                            curDyFp += stepDyFp
                            curShFp += stepShFp
                        }
                    }
                }
            }
        }

        fun renderYuvFrame(asset: FacePhotoAsset, ctrl: ControlState, nowMs: Long): YuvFrame {
            step(ctrl, nowMs)
            compute6DMesh(ctrl)

            val w = asset.width
            val h = asset.height
            buildWarpAndShadeLut(w, h)

            val outFrame = reusedYuv!!
            val outY = outFrame.y
            val outU = outFrame.u
            val outV = outFrame.v
            val lut = warpLut
            val sLut = shadeLut

            val baseY = asset.baseYuv.y
            val baseU = asset.baseYuv.u
            val baseV = asset.baseYuv.v

            // Fast 1D LUT copy + 3D Lambertian micro-shading on Y plane
            val totalY = w * h
            for (i in 0 until totalY) {
                val yRaw = baseY[lut[i]].toInt() and 0xFF
                val shaded = (yRaw * sLut[i].toInt()) shr 8
                outY[i] = shaded.coerceIn(16, 235).toByte()
            }

            // Fast 4:2:0 chroma lookup reusing the same LUT
            val cw = (w + 1) / 2
            val ch = (h + 1) / 2
            var cIdx = 0
            for (cy in 0 until ch) {
                val yRow = (cy shl 1) * w
                for (cx in 0 until cw) {
                    val sIdx = lut[yRow + (cx shl 1)]
                    val scIdx = ((sIdx / w) shr 1) * cw + ((sIdx % w) shr 1)
                    outU[cIdx] = baseU[scIdx]
                    outV[cIdx] = baseV[scIdx]
                    cIdx++
                }
            }

            frameCounter++
            outFrame.seq = frameCounter
            return outFrame
        }

        fun renderPreviewPixels(
            asset: FacePhotoAsset,
            ctrl: ControlState,
            nowMs: Long,
            outPixels: IntArray
        ) {
            step(ctrl, nowMs)
            compute6DMesh(ctrl)

            val w = asset.width
            val h = asset.height
            val base = asset.baseArgb
            if (base.size < w * h || outPixels.size < w * h) return

            buildWarpAndShadeLut(w, h)
            val lut = warpLut
            val sLut = shadeLut
            val zoom = ctrl.zoom.coerceIn(0.6f, 2.2f)
            val invZoom = 1f / zoom
            val maxX = w - 1
            val maxY = h - 1

            for (y in 0 until h) {
                var vNorm = y.toFloat() / h
                if (ctrl.flipV) vNorm = 1f - vNorm
                val vy = (((vNorm - 0.5f) * invZoom + 0.5f) * h).toInt().coerceIn(0, maxY)
                val vRow = vy * w
                val dstRow = y * w

                for (x in 0 until w) {
                    var uNorm = x.toFloat() / w
                    if (ctrl.flipH) uNorm = 1f - uNorm
                    val ux = (((uNorm - 0.5f) * invZoom + 0.5f) * w).toInt().coerceIn(0, maxX)

                    val mIdx = vRow + ux
                    val bp = base[lut[mIdx]]
                    val sh = sLut[mIdx].toInt()
                    val r = ((((bp shr 16) and 0xFF) * sh) shr 8).coerceIn(0, 255)
                    val g = ((((bp shr 8) and 0xFF) * sh) shr 8).coerceIn(0, 255)
                    val b = (((bp and 0xFF) * sh) shr 8).coerceIn(0, 255)
                    outPixels[dstRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }
}
