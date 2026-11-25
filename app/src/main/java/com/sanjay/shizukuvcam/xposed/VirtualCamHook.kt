package com.sanjay.shizukuvcam.xposed

import android.app.AndroidAppHelper
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class VirtualCamHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "ShizukuVCam"

        private val lock = Any()
        private val surfaceToDummy = WeakHashMap<Surface, Surface>()
        private val dummySurfaces: MutableSet<Surface> =
            Collections.newSetFromMap(WeakHashMap<Surface, Boolean>())
        private val dummyTextures = ArrayList<SurfaceTexture>()
        private val imageReaderFormats = WeakHashMap<Surface, Int>()
        private val hookedImageClasses = ConcurrentHashMap<Class<*>, Boolean>()
        private val hookedPlaneClasses = ConcurrentHashMap<Class<*>, Boolean>()
        private val planeOverrides: MutableMap<Any, PlaneOverride> =
            Collections.synchronizedMap(WeakHashMap<Any, PlaneOverride>())

        private var activePlayer: MediaPlayer? = null
        private var yuvVideoDecoder: YuvVideoDecoder? = null
        private var activeVideoPath: String? = null
        private var activeVideoVer: Long = -1L
        private var photoWorker: PhotoAnimWorker? = null

        private var cachedControl: ControlState = ControlState()
        private var cachedCtrlFile: File? = null
        private var lastControlReadMs = 0L
        private var nextTexId = 100
        private var lastToastMs = 0L

        private var slotIndex = 0
        private val bufferSlots = arrayOfNulls<DirectYuvBuffers>(2)
        private var lastRenderedSeq = -1L
        private var lastRenderedSrc: YuvFrame? = null
        private var lastTransformSig = 0
        private var lastSlot: DirectYuvBuffers? = null

        private fun showToastOnce(message: String) {
            val now = System.currentTimeMillis()
            if (now - lastToastMs < 4500L) return
            lastToastMs = now
            try {
                val app = AndroidAppHelper.currentApplication() ?: return
                Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }

        private fun candidateDirs(packageName: String): List<File> {
            return listOf(
                File("/storage/emulated/0/Android/data/$packageName/files"),
                File("/storage/emulated/0/DCIM/Camera1"),
                File("/data/local/tmp")
            )
        }

        fun resolveReadableFile(packageName: String, fileName: String): File? {
            for (dir in candidateDirs(packageName)) {
                try {
                    val f = File(dir, fileName)
                    if (f.exists() && f.canRead() && f.length() > 0L) {
                        return f
                    }
                } catch (_: Throwable) {
                }
            }
            return null
        }

        fun getControlState(packageName: String): ControlState {
            val now = System.currentTimeMillis()
            if (now - lastControlReadMs < 100L) {
                return cachedControl
            }
            lastControlReadMs = now
            var ctrlFile = cachedCtrlFile
            if (ctrlFile == null || !ctrlFile.exists() || !ctrlFile.canRead()) {
                ctrlFile = resolveReadableFile(packageName, "vcam_ctrl.txt")
                cachedCtrlFile = ctrlFile
            }
            if (ctrlFile != null) {
                try {
                    cachedControl = ControlState.parse(ctrlFile.readText())
                    return cachedControl
                } catch (_: Throwable) {
                    cachedCtrlFile = null
                }
            }
            return cachedControl
        }

        private fun isDummySurface(surface: Surface): Boolean {
            synchronized(lock) { return dummySurfaces.contains(surface) }
        }

        private fun isImageReaderSurface(surface: Surface): Boolean {
            synchronized(lock) { return imageReaderFormats.containsKey(surface) }
        }

        private fun isJpegSurface(surface: Surface): Boolean {
            synchronized(lock) { return imageReaderFormats[surface] == ImageFormat.JPEG }
        }

        fun getOrCreateDummySurface(realSurface: Surface): Surface {
            synchronized(lock) {
                if (dummySurfaces.contains(realSurface)) return realSurface
                surfaceToDummy[realSurface]?.let { existing ->
                    if (existing.isValid) return existing
                }
                val tex = SurfaceTexture(nextTexId++).apply {
                    setDefaultBufferSize(1280, 720)
                }
                dummyTextures.add(tex)
                val dummy = Surface(tex)
                surfaceToDummy[realSurface] = dummy
                dummySurfaces.add(dummy)
                return dummy
            }
        }

        fun ensureActiveWorkerRunning(packageName: String, ctrl: ControlState) {
            synchronized(lock) {
                if (ctrl.mode == "photo") {
                    if (yuvVideoDecoder != null) {
                        yuvVideoDecoder?.stop()
                        yuvVideoDecoder = null
                        activeVideoPath = null
                    }
                    if (photoWorker == null || !photoWorker!!.isRunning()) {
                        photoWorker = PhotoAnimWorker(packageName).also { it.start() }
                    }
                } else {
                    if (photoWorker != null) {
                        photoWorker?.stop()
                        photoWorker = null
                    }
                    val vidFile = resolveReadableFile(packageName, "vcam_feed.mp4") ?: return
                    val vidPath = vidFile.absolutePath
                    if (yuvVideoDecoder == null ||
                        activeVideoPath != vidPath ||
                        activeVideoVer != ctrl.assetVersion ||
                        !yuvVideoDecoder!!.isRunning()
                    ) {
                        yuvVideoDecoder?.stop()
                        yuvVideoDecoder = YuvVideoDecoder(vidPath).also { it.start() }
                        activeVideoPath = vidPath
                        activeVideoVer = ctrl.assetVersion
                    }
                }
            }
        }

        fun stopAllFeeds() {
            synchronized(lock) {
                try { activePlayer?.stop() } catch (_: Throwable) {}
                try { activePlayer?.release() } catch (_: Throwable) {}
                activePlayer = null

                try { yuvVideoDecoder?.stop() } catch (_: Throwable) {}
                yuvVideoDecoder = null
                activeVideoPath = null

                try { photoWorker?.stop() } catch (_: Throwable) {}
                photoWorker = null
                lastRenderedSrc = null
                lastRenderedSeq = -1L
            }
        }

        fun startPreviewMediaPlayer(targetSurface: Surface, videoPath: String) {
            synchronized(lock) {
                try {
                    activePlayer?.release()
                    activePlayer = MediaPlayer().apply {
                        setDataSource(videoPath)
                        setSurface(targetSurface)
                        isLooping = true
                        setVolume(0f, 0f)
                        setOnPreparedListener { mp -> mp.start() }
                        prepareAsync()
                    }
                    showToastOnce("ShizukuVCam v2.2: Active (Preview Stream)")
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: MediaPlayer bind error: ${t.message}")
                }
            }
        }

        fun ensurePlaneClassHooked(planeClass: Class<*>) {
            if (hookedPlaneClasses.putIfAbsent(planeClass, true) != null) return
            try {
                XposedBridge.hookMethod(
                    planeClass.getMethod("getBuffer"),
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val target = param.thisObject ?: return
                            val ov = planeOverrides[target] ?: return
                            param.result = ov.buffer.duplicate().apply { rewind() }
                        }
                    }
                )
                XposedBridge.hookMethod(
                    planeClass.getMethod("getRowStride"),
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val target = param.thisObject ?: return
                            val ov = planeOverrides[target] ?: return
                            param.result = ov.rowStride
                        }
                    }
                )
                XposedBridge.hookMethod(
                    planeClass.getMethod("getPixelStride"),
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val target = param.thisObject ?: return
                            val ov = planeOverrides[target] ?: return
                            param.result = ov.pixelStride
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: Plane hook error: ${t.message}")
            }
        }

        fun applyVirtualPlanes(
            realPlanes: Array<Image.Plane>,
            dstW: Int,
            dstH: Int,
            packageName: String
        ) {
            if (realPlanes.size < 3 || dstW <= 0 || dstH <= 0) return
            val ctrl = getControlState(packageName)
            if (!ctrl.enabled) {
                planeOverrides.remove(realPlanes[0])
                planeOverrides.remove(realPlanes[1])
                planeOverrides.remove(realPlanes[2])
                return
            }

            ensureActiveWorkerRunning(packageName, ctrl)
            val frame = if (ctrl.mode == "photo") {
                showToastOnce("ShizukuVCam v2.2: Human-Motion Photo Mode")
                photoWorker?.latestFrame
            } else {
                showToastOnce("ShizukuVCam v2.2: LivePortrait / Video Mode")
                yuvVideoDecoder?.latestFrame
            }

            val panX = if (ctrl.mode == "video") ctrl.yaw * 0.12f else 0f
            val panY = if (ctrl.mode == "video") -ctrl.pitch * 0.12f else 0f
            val transformSig = computeTransformSig(
                srcW = frame?.width ?: 0,
                srcH = frame?.height ?: 0,
                dstW = dstW,
                dstH = dstH,
                rotation = ctrl.rotation,
                zoom = ctrl.zoom,
                flipH = ctrl.flipH,
                flipV = ctrl.flipV,
                panX = panX,
                panY = panY
            )

            val yOv: PlaneOverride
            val uOv: PlaneOverride
            val vOv: PlaneOverride

            synchronized(lock) {
                val cachedSlot = lastSlot
                if (frame != null &&
                    cachedSlot != null &&
                    cachedSlot.w == dstW &&
                    cachedSlot.h == dstH &&
                    frame === lastRenderedSrc &&
                    frame.seq == lastRenderedSeq &&
                    transformSig == lastTransformSig
                ) {
                    yOv = PlaneOverride(cachedSlot.yBuf.duplicate().apply { rewind() }, dstW, 1)
                    uOv = PlaneOverride(cachedSlot.uBuf.duplicate().apply { rewind() }, cachedSlot.cw, 1)
                    vOv = PlaneOverride(cachedSlot.vBuf.duplicate().apply { rewind() }, cachedSlot.cw, 1)
                } else {
                    val idx = slotIndex
                    slotIndex = (slotIndex + 1) and 1
                    var slot = bufferSlots[idx]
                    if (slot == null || slot.w != dstW || slot.h != dstH) {
                        slot = DirectYuvBuffers(dstW, dstH)
                        bufferSlots[idx] = slot
                    }

                    if (frame == null) {
                        slot.fillBlack()
                        lastRenderedSrc = null
                        lastRenderedSeq = -1L
                    } else {
                        slot.fillFromFrameFast(
                            src = frame,
                            sig = transformSig,
                            rotation = ctrl.rotation,
                            zoom = ctrl.zoom,
                            flipH = ctrl.flipH,
                            flipV = ctrl.flipV,
                            panX = panX,
                            panY = panY
                        )
                        lastRenderedSrc = frame
                        lastRenderedSeq = frame.seq
                        lastTransformSig = transformSig
                        lastSlot = slot
                    }

                    yOv = PlaneOverride(slot.yBuf.duplicate().apply { rewind() }, dstW, 1)
                    uOv = PlaneOverride(slot.uBuf.duplicate().apply { rewind() }, slot.cw, 1)
                    vOv = PlaneOverride(slot.vBuf.duplicate().apply { rewind() }, slot.cw, 1)
                }
            }

            ensurePlaneClassHooked(realPlanes[0].javaClass)
            ensurePlaneClassHooked(realPlanes[1].javaClass)
            ensurePlaneClassHooked(realPlanes[2].javaClass)
            planeOverrides[realPlanes[0]] = yOv
            planeOverrides[realPlanes[1]] = uOv
            planeOverrides[realPlanes[2]] = vOv
        }

        private fun computeTransformSig(
            srcW: Int,
            srcH: Int,
            dstW: Int,
            dstH: Int,
            rotation: Int,
            zoom: Float,
            flipH: Boolean,
            flipV: Boolean,
            panX: Float,
            panY: Float
        ): Int {
            var h = srcW
            h = 31 * h + srcH
            h = 31 * h + dstW
            h = 31 * h + dstH
            h = 31 * h + rotation
            h = 31 * h + (zoom * 1000f).toInt()
            h = 31 * h + (if (flipH) 1 else 0)
            h = 31 * h + (if (flipV) 2 else 0)
            h = 31 * h + (panX * 1000f).toInt()
            h = 31 * h + (panY * 1000f).toInt()
            return h
        }
    }

    private class PlaneOverride(
        val buffer: ByteBuffer,
        val rowStride: Int,
        val pixelStride: Int
    )

    private class PhotoAnimWorker(private val packageName: String) {
        private val running = AtomicBoolean(false)
        private var worker: Thread? = null
        private val animator = FaceWarpEngine.MotionAnimator()

        @Volatile
        var latestFrame: YuvFrame? = null
            private set

        fun isRunning(): Boolean = running.get()

        fun start() {
            if (!running.compareAndSet(false, true)) return
            worker = Thread({
                var bank: FacePoseBank? = null
                var loadedVer = -1L
                try {
                    while (running.get()) {
                        val ctrl = getControlState(packageName)
                        if (bank == null || ctrl.assetVersion != loadedVer) {
                            val photoFile = resolveReadableFile(packageName, "vcam_photo.jpg")
                            if (photoFile != null) {
                                bank = FaceWarpEngine.loadPoseBank(
                                    basePath = photoFile.absolutePath,
                                    poseDir = photoFile.parentFile,
                                    maxDim = 320,
                                    includeArgb = false
                                )
                                loadedVer = ctrl.assetVersion
                            }
                        }

                        val activeBank = bank
                        if (activeBank != null) {
                            latestFrame = animator.renderYuvFrame(
                                bank = activeBank,
                                ctrl = ctrl,
                                nowMs = System.currentTimeMillis()
                            )
                        }

                        try {
                            Thread.sleep(32L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: PhotoAnimWorker error: ${t.message}")
                } finally {
                    running.set(false)
                }
            }, "ShizukuVCam-PhotoAnim").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
        }

        fun stop() {
            running.set(false)
            worker?.interrupt()
            worker = null
        }
    }

    private class DirectYuvBuffers(val w: Int, val h: Int) {
        val cw: Int = (w + 1) / 2
        val ch: Int = (h + 1) / 2

        private val yArr = ByteArray(w * h)
        private val uArr = ByteArray(cw * ch)
        private val vArr = ByteArray(cw * ch)

        val yBuf: ByteBuffer = ByteBuffer.allocateDirect(w * h)
        val uBuf: ByteBuffer = ByteBuffer.allocateDirect(cw * ch)
        val vBuf: ByteBuffer = ByteBuffer.allocateDirect(cw * ch)

        private var cachedSig = 0
        private var yLut = IntArray(w * h)
        private var uvLut = IntArray(cw * ch)

        fun fillBlack() {
            yArr.fill(16.toByte())
            uArr.fill(128.toByte())
            vArr.fill(128.toByte())
            commit()
        }

        fun fillFromFrameFast(
            src: YuvFrame,
            sig: Int,
            rotation: Int,
            zoom: Float,
            flipH: Boolean,
            flipV: Boolean,
            panX: Float,
            panY: Float
        ) {
            if (sig != cachedSig) {
                rebuildLuts(src.width, src.height, rotation, zoom, flipH, flipV, panX, panY)
                cachedSig = sig
            }

            val srcY = src.y
            val lutY = yLut
            val totalY = w * h
            for (i in 0 until totalY) {
                yArr[i] = srcY[lutY[i]]
            }

            val srcU = src.u
            val srcV = src.v
            val lutUv = uvLut
            val totalUv = cw * ch
            for (i in 0 until totalUv) {
                val idx = lutUv[i]
                uArr[i] = srcU[idx]
                vArr[i] = srcV[idx]
            }

            commit()
        }

        private fun rebuildLuts(
            srcW: Int,
            srcH: Int,
            rotation: Int,
            zoom: Float,
            flipH: Boolean,
            flipV: Boolean,
            panX: Float,
            panY: Float
        ) {
            if (srcW <= 0 || srcH <= 0) return
            val maxSx = srcW - 1
            val maxSy = srcH - 1
            val invZoom = 1f / zoom.coerceIn(0.6f, 2.2f)
            val invDstW = 1f / w
            val invDstH = 1f / h

            var pos = 0
            for (y in 0 until h) {
                val dv = y * invDstH
                for (x in 0 until w) {
                    val du = x * invDstW
                    var su: Float
                    var sv: Float
                    when (rotation) {
                        90 -> { su = dv; sv = 1f - du }
                        180 -> { su = 1f - du; sv = 1f - dv }
                        270 -> { su = 1f - dv; sv = du }
                        else -> { su = du; sv = dv }
                    }
                    if (flipH) su = 1f - su
                    if (flipV) sv = 1f - sv
                    su = (su - 0.5f) * invZoom + 0.5f - panX
                    sv = (sv - 0.5f) * invZoom + 0.5f - panY

                    val sx = (su * srcW).toInt().coerceIn(0, maxSx)
                    val sy = (sv * srcH).toInt().coerceIn(0, maxSy)
                    yLut[pos++] = sy * srcW + sx
                }
            }

            val srcCw = (srcW + 1) / 2
            val srcCh = (srcH + 1) / 2
            val maxScx = srcCw - 1
            val maxScy = srcCh - 1
            val invCw = 1f / cw
            val invCh = 1f / ch

            var cPos = 0
            for (y in 0 until ch) {
                val dv = y * invCh
                for (x in 0 until cw) {
                    val du = x * invCw
                    var su: Float
                    var sv: Float
                    when (rotation) {
                        90 -> { su = dv; sv = 1f - du }
                        180 -> { su = 1f - du; sv = 1f - dv }
                        270 -> { su = 1f - dv; sv = du }
                        else -> { su = du; sv = dv }
                    }
                    if (flipH) su = 1f - su
                    if (flipV) sv = 1f - sv
                    su = (su - 0.5f) * invZoom + 0.5f - panX
                    sv = (sv - 0.5f) * invZoom + 0.5f - panY

                    val sx = (su * srcCw).toInt().coerceIn(0, maxScx)
                    val sy = (sv * srcCh).toInt().coerceIn(0, maxScy)
                    uvLut[cPos++] = sy * srcCw + sx
                }
            }
        }

        private fun commit() {
            yBuf.clear(); yBuf.put(yArr); yBuf.flip()
            uBuf.clear(); uBuf.put(uArr); uBuf.flip()
            vBuf.clear(); vBuf.put(vArr); vBuf.flip()
        }
    }

    private class YuvVideoDecoder(private val videoPath: String) {
        private val running = AtomicBoolean(false)
        private var worker: Thread? = null
        private var frameCounter = 0L

        @Volatile
        var latestFrame: YuvFrame? = null
            private set

        fun isRunning(): Boolean = running.get()

        fun start() {
            if (!running.compareAndSet(false, true)) return
            worker = Thread({
                var extractor: MediaExtractor? = null
                var codec: MediaCodec? = null
                try {
                    extractor = MediaExtractor().apply { setDataSource(videoPath) }
                    var videoTrack = -1
                    var videoFormat: MediaFormat? = null
                    for (i in 0 until extractor.trackCount) {
                        val fmt = extractor.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("video/")) {
                            videoTrack = i
                            videoFormat = fmt
                            break
                        }
                    }
                    if (videoTrack < 0 || videoFormat == null) return@Thread

                    extractor.selectTrack(videoTrack)
                    videoFormat.setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )

                    val mime = videoFormat.getString(MediaFormat.KEY_MIME)!!
                    codec = MediaCodec.createDecoderByType(mime)
                    codec.configure(videoFormat, null, null, 0)
                    codec.start()

                    val bufferInfo = MediaCodec.BufferInfo()
                    var startWallMs = System.currentTimeMillis()
                    var firstPtsUs = -1L

                    while (running.get()) {
                        val inIndex = codec.dequeueInputBuffer(10_000L)
                        if (inIndex >= 0) {
                            val inputBuf = codec.getInputBuffer(inIndex)
                            if (inputBuf != null) {
                                val sampleSize = extractor.readSampleData(inputBuf, 0)
                                if (sampleSize < 0) {
                                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                    codec.flush()
                                    startWallMs = System.currentTimeMillis()
                                    firstPtsUs = -1L
                                    continue
                                } else {
                                    codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }

                        val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                        if (outIndex >= 0) {
                            if (firstPtsUs < 0L) {
                                firstPtsUs = bufferInfo.presentationTimeUs
                                startWallMs = System.currentTimeMillis()
                            } else {
                                val sleepMs = ((bufferInfo.presentationTimeUs - firstPtsUs) / 1000L) -
                                    (System.currentTimeMillis() - startWallMs)
                                if (sleepMs in 1L..200L) {
                                    try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { break }
                                }
                            }
                            try {
                                val outImg = codec.getOutputImage(outIndex)
                                if (outImg != null) {
                                    try {
                                        extractI420Frame(outImg)?.let { f ->
                                            frameCounter++
                                            f.seq = frameCounter
                                            latestFrame = f
                                        }
                                    } finally {
                                        outImg.close()
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    running.set(false)
                    try { codec?.stop() } catch (_: Throwable) {}
                    try { codec?.release() } catch (_: Throwable) {}
                    try { extractor?.release() } catch (_: Throwable) {}
                }
            }, "ShizukuVCam-YuvDecoder").apply {
                isDaemon = true
                start()
            }
        }

        fun stop() {
            running.set(false)
            worker?.interrupt()
            worker = null
        }

        private fun extractI420Frame(image: Image): YuvFrame? {
            val planes = image.planes
            if (planes.size < 3) return null
            val crop = image.cropRect
            val w = if (crop.width() > 0) crop.width() else image.width
            val h = if (crop.height() > 0) crop.height() else image.height
            val left = crop.left.coerceAtLeast(0)
            val top = crop.top.coerceAtLeast(0)
            if (w <= 0 || h <= 0) return null

            val cw = (w + 1) / 2
            val ch = (h + 1) / 2
            val yOut = ByteArray(w * h)
            val uOut = ByteArray(cw * ch)
            val vOut = ByteArray(cw * ch)

            copyPlane(planes[0], left, top, w, h, yOut)
            copyPlane(planes[1], left / 2, top / 2, cw, ch, uOut)
            copyPlane(planes[2], left / 2, top / 2, cw, ch, vOut)
            return YuvFrame(w, h, yOut, uOut, vOut, 0L)
        }

        private fun copyPlane(
            plane: Image.Plane,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            dst: ByteArray
        ) {
            val buf = plane.buffer.duplicate()
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val limit = buf.limit()
            if (pixelStride == 1) {
                for (row in 0 until height) {
                    val srcOffset = (top + row) * rowStride + left
                    if (srcOffset + width > limit) break
                    buf.position(srcOffset)
                    buf.get(dst, row * width, width)
                }
            } else {
                var dstIdx = 0
                for (row in 0 until height) {
                    val rowStart = (top + row) * rowStride + left * pixelStride
                    for (col in 0 until width) {
                        val pos = rowStart + col * pixelStride
                        if (pos >= limit) break
                        dst[dstIdx++] = buf.get(pos)
                    }
                }
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.sanjay.shizukuvcam" ||
            lpparam.packageName == "android" ||
            lpparam.packageName == "com.android.systemui"
        ) {
            return
        }

        hookImageReader(lpparam)
        hookCamera2(lpparam)
        hookCamera1(lpparam)
    }

    private fun ensureImageClassHooked(imageClass: Class<*>, packageName: String) {
        if (hookedImageClasses.putIfAbsent(imageClass, true) != null) return
        try {
            XposedBridge.hookMethod(
                imageClass.getMethod("getPlanes"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val image = param.thisObject as? Image ?: return
                        if (image.format != ImageFormat.YUV_420_888) return
                        @Suppress("UNCHECKED_CAST")
                        val realPlanes = param.result as? Array<Image.Plane> ?: return
                        applyVirtualPlanes(realPlanes, image.width, image.height, packageName)
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun hookImageReader(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        try {
            XposedHelpers.findAndHookMethod(
                ImageReader::class.java,
                "newInstance",
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val format = param.args[2] as? Int ?: return
                        val reader = param.result as? ImageReader ?: return
                        try {
                            synchronized(lock) { imageReaderFormats[reader.surface] = format }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        try {
            val surfaceImageClass = XposedHelpers.findClass(
                "android.media.ImageReader\$SurfaceImage",
                lpparam.classLoader
            )
            ensureImageClassHooked(surfaceImageClass, pkg)
        } catch (_: Throwable) {
        }

        val acquireHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val img = param.result as? Image ?: return
                if (img.format != ImageFormat.YUV_420_888) return
                val ctrl = getControlState(pkg)
                if (!ctrl.enabled) return
                ensureActiveWorkerRunning(pkg, ctrl)
                ensureImageClassHooked(img.javaClass, pkg)
            }
        }
        try { XposedHelpers.findAndHookMethod(ImageReader::class.java, "acquireLatestImage", acquireHook) } catch (_: Throwable) {}
        try { XposedHelpers.findAndHookMethod(ImageReader::class.java, "acquireNextImage", acquireHook) } catch (_: Throwable) {}
    }

    private fun hookCamera2(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        try {
            XposedHelpers.findAndHookMethod(
                CaptureRequest.Builder::class.java,
                "addTarget",
                Surface::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ctrl = getControlState(pkg)
                        if (!ctrl.enabled || ctrl.mode != "video") return
                        val surface = param.args[0] as? Surface ?: return
                        if (isDummySurface(surface) || isImageReaderSurface(surface)) return
                        param.args[0] = getOrCreateDummySurface(surface)
                    }
                }
            )
        } catch (_: Throwable) {
        }

        val deviceImplClass = try {
            XposedHelpers.findClass("android.hardware.camera2.impl.CameraDeviceImpl", lpparam.classLoader)
        } catch (_: Throwable) {
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                deviceImplClass,
                "createCaptureSession",
                List::class.java,
                CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ctrl = getControlState(pkg)
                        if (!ctrl.enabled) return
                        @Suppress("UNCHECKED_CAST")
                        val outputs = param.args[0] as? List<Surface> ?: return
                        if (outputs.isEmpty() || outputs.any { isDummySurface(it) }) return

                        var hasYuvReader = false
                        var previewSurface: Surface? = null
                        val newOutputs = ArrayList<Surface>(outputs.size)
                        for (surf in outputs) {
                            if (isImageReaderSurface(surf)) {
                                if (!isJpegSurface(surf)) hasYuvReader = true
                                newOutputs.add(surf)
                            } else if (ctrl.mode == "video") {
                                if (previewSurface == null) previewSurface = surf
                                newOutputs.add(getOrCreateDummySurface(surf))
                            } else {
                                newOutputs.add(surf)
                            }
                        }

                        if (hasYuvReader) ensureActiveWorkerRunning(pkg, ctrl)
                        val targetPreview = previewSurface
                        if (targetPreview != null && ctrl.mode == "video") {
                            resolveReadableFile(pkg, "vcam_feed.mp4")?.let { vid ->
                                param.args[0] = newOutputs
                                startPreviewMediaPlayer(targetPreview, vid.absolutePath)
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                XposedHelpers.findAndHookMethod(
                    deviceImplClass,
                    "createCaptureSession",
                    SessionConfiguration::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val ctrl = getControlState(pkg)
                            if (!ctrl.enabled) return
                            val sessionConfig = param.args[0] as? SessionConfiguration ?: return
                            val configs = sessionConfig.outputConfigurations ?: return
                            if (configs.isEmpty() || configs.any { it.surface?.let { s -> isDummySurface(s) } == true }) return

                            var hasYuvReader = false
                            var previewSurface: Surface? = null
                            val newConfigs = ArrayList<OutputConfiguration>(configs.size)
                            for (cfg in configs) {
                                val surf = cfg.surface
                                if (surf == null || isImageReaderSurface(surf)) {
                                    if (surf != null && !isJpegSurface(surf)) hasYuvReader = true
                                    newConfigs.add(cfg)
                                } else if (ctrl.mode == "video") {
                                    if (previewSurface == null) previewSurface = surf
                                    newConfigs.add(OutputConfiguration(getOrCreateDummySurface(surf)))
                                } else {
                                    newConfigs.add(cfg)
                                }
                            }

                            if (hasYuvReader) ensureActiveWorkerRunning(pkg, ctrl)
                            val targetPreview = previewSurface
                            if (targetPreview != null && ctrl.mode == "video") {
                                resolveReadableFile(pkg, "vcam_feed.mp4")?.let { vid ->
                                    val replacement = SessionConfiguration(
                                        sessionConfig.sessionType,
                                        newConfigs,
                                        sessionConfig.executor,
                                        sessionConfig.stateCallback
                                    )
                                    sessionConfig.sessionParameters?.let { replacement.sessionParameters = it }
                                    param.args[0] = replacement
                                    startPreviewMediaPlayer(targetPreview, vid.absolutePath)
                                }
                            }
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                deviceImplClass,
                "close",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        stopAllFeeds()
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun hookCamera1(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        try {
            XposedHelpers.findAndHookMethod(
                Camera::class.java,
                "setPreviewTexture",
                SurfaceTexture::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ctrl = getControlState(pkg)
                        if (!ctrl.enabled || ctrl.mode != "video") return
                        val vid = resolveReadableFile(pkg, "vcam_feed.mp4") ?: return
                        val realTex = param.args[0] as? SurfaceTexture ?: return
                        try {
                            val dummyTex = SurfaceTexture(nextTexId++).apply {
                                setDefaultBufferSize(1280, 720)
                            }
                            synchronized(lock) { dummyTextures.add(dummyTex) }
                            param.args[0] = dummyTex
                            startPreviewMediaPlayer(Surface(realTex), vid.absolutePath)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }
}
