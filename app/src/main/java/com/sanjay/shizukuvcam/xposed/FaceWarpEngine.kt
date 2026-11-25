package com.sanjay.shizukuvcam.xposed

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

class ControlState {
    @Volatile var enabled: Boolean = false
    @Volatile var mode: String = "photo" // "photo" or "video"
    @Volatile var yaw: Float = 0f        // -1f (left) .. +1f (right)
    @Volatile var pitch: Float = 0f      // -1f (down) .. +1f (up)
    @Volatile var zoom: Float = 1.0f     // 0.6f .. 2.2f
    @Volatile var flipH: Boolean = false
    @Volatile var flipV: Boolean = false
    @Volatile var rotation: Int = 270    // 0, 90, 180, 270
    @Volatile var blinkSeq: Long = 0L
    @Volatile var smile: Float = 0f      // 0f .. 1f
    @Volatile var autoIdle: Boolean = true
    @Volatile var autoSweep: Boolean = false
    @Volatile var faceCx: Float = 0.50f
    @Volatile var faceCy: Float = 0.48f
    @Volatile var faceR: Float = 0.22f
    @Volatile var eyeLx: Float = 0.41f
    @Volatile var eyeLy: Float = 0.43f
    @Volatile var eyeRx: Float = 0.59f
    @Volatile var eyeRy: Float = 0.43f
    @Volatile var assetVersion: Long = 1L

    fun serialize(): String {
        return buildString {
            append("enabled=").append(if (enabled) "1" else "0").append('\n')
            append("mode=").append(mode).append('\n')
            append("yaw=").append(yaw).append('\n')
            append("pitch=").append(pitch).append('\n')
            append("zoom=").append(zoom).append('\n')
            append("flip_h=").append(if (flipH) "1" else "0").append('\n')
            append("flip_v=").append(if (flipV) "1" else "0").append('\n')
            append("rotation=").append(rotation).append('\n')
            append("blink_seq=").append(blinkSeq).append('\n')
            append("smile=").append(smile).append('\n')
            append("auto_idle=").append(if (autoIdle) "1" else "0").append('\n')
            append("auto_sweep=").append(if (autoSweep) "1" else "0").append('\n')
            append("face_cx=").append(faceCx).append('\n')
            append("face_cy=").append(faceCy).append('\n')
            append("face_r=").append(faceR).append('\n')
            append("eye_lx=").append(eyeLx).append('\n')
            append("eye_ly=").append(eyeLy).append('\n')
            append("eye_rx=").append(eyeRx).append('\n')
            append("eye_ry=").append(eyeRy).append('\n')
            append("ver=").append(assetVersion).append('\n')
        }
    }

    fun copyFrom(other: ControlState) {
        enabled = other.enabled
        mode = other.mode
        yaw = other.yaw
        pitch = other.pitch
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

class FacePoseBank(
    val width: Int,
    val height: Int,
    val baseYuv: YuvFrame,
    val baseArgb: IntArray,
    val leftYuv: YuvFrame? = null,
    val rightYuv: YuvFrame? = null,
    val upYuv: YuvFrame? = null,
    val downYuv: YuvFrame? = null,
    val blinkYuv: YuvFrame? = null,
    val smileYuv: YuvFrame? = null,
    val leftArgb: IntArray? = null,
    val rightArgb: IntArray? = null,
    val upArgb: IntArray? = null,
    val downArgb: IntArray? = null,
    val blinkArgb: IntArray? = null,
    val smileArgb: IntArray? = null
) {
    val hasAiPoses: Boolean
        get() = leftYuv != null || rightYuv != null || upYuv != null || downYuv != null
}

object FaceWarpEngine {

    private const val GRID_X = 20
    private const val GRID_Y = 20

    fun loadPoseBank(
        basePath: String,
        poseDir: File?,
        maxDim: Int,
        includeArgb: Boolean
    ): FacePoseBank? {
        val baseBmp = decodeScaledBitmap(basePath, maxDim) ?: return null
        val w = baseBmp.width
        val h = baseBmp.height
        val baseArgb = IntArray(w * h)
        baseBmp.getPixels(baseArgb, 0, w, 0, 0, w, h)
        baseBmp.recycle()

        val baseYuv = argbToYuv(baseArgb, w, h)

        fun loadPose(name: String): Pair<YuvFrame?, IntArray?> {
            if (poseDir == null) return null to null
            val f = File(poseDir, name)
            if (!f.exists() || !f.canRead() || f.length() == 0L) return null to null
            val bmp = decodeExactBitmap(f.absolutePath, w, h) ?: return null to null
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            val yuv = argbToYuv(px, w, h)
            return yuv to (if (includeArgb) px else null)
        }

        val (lY, lA) = loadPose("vcam_pose_left.jpg")
        val (rY, rA) = loadPose("vcam_pose_right.jpg")
        val (uY, uA) = loadPose("vcam_pose_up.jpg")
        val (dY, dA) = loadPose("vcam_pose_down.jpg")
        val (bY, bA) = loadPose("vcam_pose_blink.jpg")
        val (sY, sA) = loadPose("vcam_pose_smile.jpg")

        return FacePoseBank(
            width = w,
            height = h,
            baseYuv = baseYuv,
            baseArgb = if (includeArgb) baseArgb else IntArray(0),
            leftYuv = lY,
            rightYuv = rY,
            upYuv = uY,
            downYuv = dY,
            blinkYuv = bY,
            smileYuv = sY,
            leftArgb = lA,
            rightArgb = rA,
            upArgb = uA,
            downArgb = dA,
            blinkArgb = bA,
            smileArgb = sA
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

    private fun decodeExactBitmap(path: String, w: Int, h: Int): Bitmap? {
        return try {
            val raw = BitmapFactory.decodeFile(path) ?: return null
            if (raw.width == w && raw.height == h) {
                raw
            } else {
                val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
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

    class MotionAnimator {
        var curYaw = 0f
        var curPitch = 0f
        var curSmile = 0f
        var curBlink = 0f

        private var lastBlinkSeq = 0L
        private var blinkStartMs = 0L
        private var nextAutoBlinkMs = System.currentTimeMillis() + 3000L
        private var frameCounter = 0L

        private val gridDx = FloatArray((GRID_X + 1) * (GRID_Y + 1))
        private val gridDy = FloatArray((GRID_X + 1) * (GRID_Y + 1))

        // Reusable buffers to eliminate GC churn and lag
        private var cachedW = 0
        private var cachedH = 0
        private var warpLut = IntArray(0)
        private var reusedYuv: YuvFrame? = null

        fun step(ctrl: ControlState, nowMs: Long) {
            var targetYaw = ctrl.yaw
            var targetPitch = ctrl.pitch

            // Smooth hands-free Left -> Right -> Up -> Down -> Center head sweep
            if (ctrl.autoSweep) {
                val cycleSec = ((nowMs % 6400L) / 1000f) // 6.4-second smooth cycle
                val phase = (cycleSec / 6.4f) * (2.0 * Math.PI)
                // Horizontal figure-8 sweep (Left/Right) + vertical nod (Up/Down)
                targetYaw = (sin(phase) * 0.88).toFloat()
                targetPitch = (sin(phase * 2.0) * 0.68).toFloat()
            }

            val lerp = if (ctrl.autoSweep) 0.22f else 0.32f
            curYaw += (targetYaw - curYaw) * lerp
            curPitch += (targetPitch - curPitch) * lerp
            curSmile += (ctrl.smile - curSmile) * 0.28f

            if (ctrl.blinkSeq != lastBlinkSeq) {
                lastBlinkSeq = ctrl.blinkSeq
                blinkStartMs = nowMs
            }

            if ((ctrl.autoIdle || ctrl.autoSweep) && nowMs >= nextAutoBlinkMs && (nowMs - blinkStartMs) > 650L) {
                blinkStartMs = nowMs
                nextAutoBlinkMs = nowMs + 2600L + ((nowMs * 13L) % 2200L)
            }

            val blinkElapsed = nowMs - blinkStartMs
            curBlink = if (blinkElapsed in 0L..250L) {
                val p = blinkElapsed / 250f
                sin(p * Math.PI).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
        }

        private fun computeMesh(ctrl: ControlState, nowMs: Long, useSoftWarpForAi: Boolean) {
            val t = (nowMs % 600_000L) * 0.001f
            val idleYaw = if (ctrl.autoIdle && !ctrl.autoSweep) sin(t * 0.85f) * 0.045f else 0f
            val idlePitch = if (ctrl.autoIdle && !ctrl.autoSweep) sin(t * 1.45f) * 0.035f else 0f
            val breathY = if (ctrl.autoIdle || ctrl.autoSweep) sin(t * 1.75f) * 0.006f else 0f

            val warpScale = if (useSoftWarpForAi) 0.28f else 1.0f
            val effYaw = (curYaw * warpScale + idleYaw).coerceIn(-1.2f, 1.2f)
            val effPitch = (curPitch * warpScale + idlePitch).coerceIn(-1.2f, 1.2f)
            val effBlink = if (useSoftWarpForAi) curBlink * 0.35f else curBlink
            val effSmile = if (useSoftWarpForAi) curSmile * 0.35f else curSmile

            val cx = ctrl.faceCx
            val cy = ctrl.faceCy
            val rx = (ctrl.faceR * 1.25f).coerceAtLeast(0.12f)
            val ry = (ctrl.faceR * 1.55f).coerceAtLeast(0.15f)
            val eyeR = (ctrl.faceR * 0.24f).coerceAtLeast(0.03f)
            val mouthY = (cy + ctrl.faceR * 0.56f).coerceIn(0.25f, 0.90f)
            val mouthRx = (ctrl.faceR * 0.50f).coerceAtLeast(0.06f)

            val cols = GRID_X + 1
            for (gy in 0..GRID_Y) {
                val v = gy.toFloat() / GRID_Y
                for (gx in 0..GRID_X) {
                    val u = gx.toFloat() / GRID_X
                    val nx = (u - cx) / rx
                    val ny = (v - cy) / ry
                    val r2 = nx * nx + ny * ny

                    var dx = 0f
                    var dy = breathY * (1f - (v * 0.4f))

                    if (r2 < 2.2f) {
                        val falloff = 1f - (r2 / 2.2f)
                        val w = falloff * falloff

                        dx -= effYaw * rx * 0.25f * w
                        dx += effYaw * nx * nx * rx * 0.06f * w

                        dy += effPitch * ry * 0.23f * w
                        dy -= effPitch * ny * ny * ry * 0.05f * w

                        dx += (u - cx) * (effYaw * nx * 0.14f) * w
                        dy += (v - cy) * (effPitch * ny * 0.10f) * w
                    }

                    if (effBlink > 0.02f) {
                        val lex = (u - ctrl.eyeLx) / (eyeR * 1.35f)
                        val ley = (v - ctrl.eyeLy) / (eyeR * 0.85f)
                        val le2 = lex * lex + ley * ley
                        if (le2 < 1.3f) {
                            val ew = 1f - le2 / 1.3f
                            dy -= effBlink * eyeR * 0.85f * ew * ew
                        }

                        val rex = (u - ctrl.eyeRx) / (eyeR * 1.35f)
                        val rey = (v - ctrl.eyeRy) / (eyeR * 0.85f)
                        val re2 = rex * rex + rey * rey
                        if (re2 < 1.3f) {
                            val ew = 1f - re2 / 1.3f
                            dy -= effBlink * eyeR * 0.85f * ew * ew
                        }
                    }

                    if (effSmile > 0.02f) {
                        val mx = (u - cx) / mouthRx
                        val my = (v - mouthY) / (mouthRx * 0.55f)
                        val m2 = mx * mx + my * my
                        if (m2 < 1.4f) {
                            val mw = 1f - m2 / 1.4f
                            val cornerFactor = abs(mx).coerceIn(0f, 1.2f)
                            dy += effSmile * mouthRx * 0.22f * cornerFactor * mw * mw
                            dx -= effSmile * mx * mouthRx * 0.10f * mw * mw
                        }
                    }

                    val idx = gy * cols + gx
                    gridDx[idx] = dx
                    gridDy[idx] = dy
                }
            }
        }

        // Builds a single flat w*h source index LUT using scanline-interpolated fixed-point math
        private fun buildWarpLut(w: Int, h: Int) {
            if (cachedW != w || cachedH != h || warpLut.size != w * h) {
                cachedW = w
                cachedH = h
                warpLut = IntArray(w * h)
                val cw = (w + 1) / 2
                val ch = (h + 1) / 2
                reusedYuv = YuvFrame(w, h, ByteArray(w * h), ByteArray(cw * ch), ByteArray(cw * ch), 0L)
            }

            val cols = GRID_X + 1
            val maxX = w - 1
            val maxY = h - 1
            val lut = warpLut

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

                    val dx00 = gridDx[i00] * w
                    val dx10 = gridDx[i10] * w
                    val dx01 = gridDx[i01] * w
                    val dx11 = gridDx[i11] * w

                    val dy00 = gridDy[i00] * h
                    val dy10 = gridDy[i10] * h
                    val dy01 = gridDy[i01] * h
                    val dy11 = gridDy[i11] * h

                    val invCellH = 1f / cellH
                    val invCellW = 1f / cellW

                    for (y in yStart until yEnd) {
                        val ty = (y - yStart) * invCellH
                        val leftDx = dx00 + (dx01 - dx00) * ty
                        val rightDx = dx10 + (dx11 - dx10) * ty
                        val leftDy = dy00 + (dy01 - dy00) * ty
                        val rightDy = dy10 + (dy11 - dy10) * ty

                        var curDxFp = ((xStart + leftDx) * 65536f).toInt()
                        val stepDxFp = (((rightDx - leftDx) * invCellW + 1f) * 65536f).toInt()

                        var curDyFp = ((y + leftDy) * 65536f).toInt()
                        val stepDyFp = (((rightDy - leftDy) * invCellW) * 65536f).toInt()

                        var dstIdx = y * w + xStart
                        for (x in xStart until xEnd) {
                            val sx = (curDxFp shr 16).coerceIn(0, maxX)
                            val sy = (curDyFp shr 16).coerceIn(0, maxY)
                            lut[dstIdx++] = sy * w + sx
                            curDxFp += stepDxFp
                            curDyFp += stepDyFp
                        }
                    }
                }
            }
        }

        private fun pickPoseBlendYuv(bank: FacePoseBank): Pair<YuvFrame?, Int> {
            if (curBlink > 0.25f && bank.blinkYuv != null) {
                return bank.blinkYuv to (curBlink * 256f).toInt().coerceIn(0, 256)
            }
            if (curSmile > 0.15f && bank.smileYuv != null && abs(curYaw) < 0.35f && abs(curPitch) < 0.35f) {
                return bank.smileYuv to (curSmile * 256f).toInt().coerceIn(0, 256)
            }
            if (abs(curYaw) >= abs(curPitch)) {
                if (curYaw < -0.05f && bank.leftYuv != null) {
                    return bank.leftYuv to ((-curYaw) * 256f).toInt().coerceIn(0, 256)
                }
                if (curYaw > 0.05f && bank.rightYuv != null) {
                    return bank.rightYuv to (curYaw * 256f).toInt().coerceIn(0, 256)
                }
            } else {
                if (curPitch > 0.05f && bank.upYuv != null) {
                    return bank.upYuv to (curPitch * 256f).toInt().coerceIn(0, 256)
                }
                if (curPitch < -0.05f && bank.downYuv != null) {
                    return bank.downYuv to ((-curPitch) * 256f).toInt().coerceIn(0, 256)
                }
            }
            return null to 0
        }

        private fun pickPoseBlendArgb(bank: FacePoseBank): Pair<IntArray?, Int> {
            if (curBlink > 0.25f && bank.blinkArgb != null) {
                return bank.blinkArgb to (curBlink * 256f).toInt().coerceIn(0, 256)
            }
            if (curSmile > 0.15f && bank.smileArgb != null && abs(curYaw) < 0.35f && abs(curPitch) < 0.35f) {
                return bank.smileArgb to (curSmile * 256f).toInt().coerceIn(0, 256)
            }
            if (abs(curYaw) >= abs(curPitch)) {
                if (curYaw < -0.05f && bank.leftArgb != null) {
                    return bank.leftArgb to ((-curYaw) * 256f).toInt().coerceIn(0, 256)
                }
                if (curYaw > 0.05f && bank.rightArgb != null) {
                    return bank.rightArgb to (curYaw * 256f).toInt().coerceIn(0, 256)
                }
            } else {
                if (curPitch > 0.05f && bank.upArgb != null) {
                    return bank.upArgb to (curPitch * 256f).toInt().coerceIn(0, 256)
                }
                if (curPitch < -0.05f && bank.downArgb != null) {
                    return bank.downArgb to ((-curPitch) * 256f).toInt().coerceIn(0, 256)
                }
            }
            return null to 0
        }

        fun renderYuvFrame(bank: FacePoseBank, ctrl: ControlState, nowMs: Long): YuvFrame {
            step(ctrl, nowMs)
            val (poseYuv, alpha) = pickPoseBlendYuv(bank)
            computeMesh(ctrl, nowMs, useSoftWarpForAi = (poseYuv != null && alpha > 64))

            val w = bank.width
            val h = bank.height
            buildWarpLut(w, h)

            val outFrame = reusedYuv!!
            val outY = outFrame.y
            val outU = outFrame.u
            val outV = outFrame.v
            val lut = warpLut

            val baseY = bank.baseYuv.y
            val baseU = bank.baseYuv.u
            val baseV = bank.baseYuv.v
            val poseY = poseYuv?.y
            val poseU = poseYuv?.u
            val poseV = poseYuv?.v
            val invAlpha = 256 - alpha

            // Fast 1D LUT copy for Y plane
            val totalY = w * h
            if (poseY != null && alpha > 0) {
                for (i in 0 until totalY) {
                    val sIdx = lut[i]
                    val bVal = baseY[sIdx].toInt() and 0xFF
                    val pVal = poseY[sIdx].toInt() and 0xFF
                    outY[i] = ((bVal * invAlpha + pVal * alpha) shr 8).toByte()
                }
            } else {
                for (i in 0 until totalY) {
                    outY[i] = baseY[lut[i]]
                }
            }

            // Fast 4:2:0 subsampled LUT lookup for U and V planes (reuses Y LUT!)
            val cw = (w + 1) / 2
            val ch = (h + 1) / 2
            var cIdx = 0
            if (poseU != null && poseV != null && alpha > 0) {
                for (cy in 0 until ch) {
                    val yRow = (cy shl 1) * w
                    for (cx in 0 until cw) {
                        val sIdx = lut[yRow + (cx shl 1)]
                        val scIdx = ((sIdx / w) shr 1) * cw + ((sIdx % w) shr 1)
                        val bu = baseU[scIdx].toInt() and 0xFF
                        val bv = baseV[scIdx].toInt() and 0xFF
                        val pu = poseU[scIdx].toInt() and 0xFF
                        val pv = poseV[scIdx].toInt() and 0xFF
                        outU[cIdx] = ((bu * invAlpha + pu * alpha) shr 8).toByte()
                        outV[cIdx] = ((bv * invAlpha + pv * alpha) shr 8).toByte()
                        cIdx++
                    }
                }
            } else {
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
            }

            frameCounter++
            outFrame.seq = frameCounter
            return outFrame
        }

        fun renderPreviewPixels(
            bank: FacePoseBank,
            ctrl: ControlState,
            nowMs: Long,
            outPixels: IntArray
        ) {
            step(ctrl, nowMs)
            val (poseArgb, alpha) = pickPoseBlendArgb(bank)
            computeMesh(ctrl, nowMs, useSoftWarpForAi = (poseArgb != null && alpha > 64))

            val w = bank.width
            val h = bank.height
            val base = bank.baseArgb
            if (base.size < w * h || outPixels.size < w * h) return

            buildWarpLut(w, h)
            val lut = warpLut
            val invAlpha = 256 - alpha
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

                    val sIdx = lut[vRow + ux]
                    val bp = base[sIdx]
                    outPixels[dstRow + x] = if (poseArgb != null && alpha > 0) {
                        val pp = poseArgb[sIdx]
                        val r = ((((bp shr 16) and 0xFF) * invAlpha + ((pp shr 16) and 0xFF) * alpha) shr 8)
                        val g = ((((bp shr 8) and 0xFF) * invAlpha + ((pp shr 8) and 0xFF) * alpha) shr 8)
                        val b = (((bp and 0xFF) * invAlpha + (pp and 0xFF) * alpha) shr 8)
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        bp
                    }
                }
            }
        }
    }
}
