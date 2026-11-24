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
        private var yuvDecoder: YuvVideoDecoder? = null
        private var activeYuvPath: String? = null
        private var nextTexId = 100
        private var lastToastMs = 0L

        private var slotIndex = 0
        private val bufferSlots = arrayOfNulls<DirectYuvBuffers>(2)

        private fun showToastOnce(message: String) {
            val now = System.currentTimeMillis()
            if (now - lastToastMs < 4000L) return
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

        private fun resolveReadableVideo(packageName: String): String? {
            val candidates = listOf(
                "/storage/emulated/0/Android/data/$packageName/files/vcam_feed.mp4",
                "/storage/emulated/0/DCIM/Camera1/vcam_feed.mp4",
                "/data/local/tmp/vcam_feed.mp4"
            )
            for (path in candidates) {
                try {
                    val f = File(path)
                    if (f.exists() && f.canRead() && f.length() > 0L) {
                        return path
                    }
                } catch (_: Throwable) {
                }
            }
            return null
        }

        private fun isStateEnabled(packageName: String): Boolean {
            val candidates = listOf(
                "/storage/emulated/0/Android/data/$packageName/files/vcam_enabled",
                "/storage/emulated/0/DCIM/Camera1/vcam_enabled",
                "/data/local/tmp/vcam_enabled"
            )
            for (path in candidates) {
                try {
                    val f = File(path)
                    if (f.exists() && f.canRead()) {
                        return f.readText().trim() == "1"
                    }
                } catch (_: Throwable) {
                }
            }
            return false
        }

        fun getRotationDegrees(packageName: String): Int {
            val candidates = listOf(
                "/storage/emulated/0/Android/data/$packageName/files/vcam_rotation",
                "/storage/emulated/0/DCIM/Camera1/vcam_rotation",
                "/data/local/tmp/vcam_rotation"
            )
            for (path in candidates) {
                try {
                    val f = File(path)
                    if (f.exists() && f.canRead()) {
                        return f.readText().trim().toIntOrNull() ?: 0
                    }
                } catch (_: Throwable) {
                }
            }
            return 0
        }

        fun getActiveVideoPath(packageName: String): String? {
            if (!isStateEnabled(packageName)) return null
            val path = resolveReadableVideo(packageName)
            if (path == null) {
                XposedBridge.log("$TAG: Enabled for $packageName but vcam_feed.mp4 not readable!")
                showToastOnce("ShizukuVCam: Video missing — tap Sync for this app")
            }
            return path
        }

        private fun isDummySurface(surface: Surface): Boolean {
            synchronized(lock) {
                return dummySurfaces.contains(surface)
            }
        }

        private fun isImageReaderSurface(surface: Surface): Boolean {
            synchronized(lock) {
                return imageReaderFormats.containsKey(surface)
            }
        }

        private fun isJpegSurface(surface: Surface): Boolean {
            synchronized(lock) {
                return imageReaderFormats[surface] == ImageFormat.JPEG
            }
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

        fun ensureYuvDecoderRunning(videoPath: String) {
            synchronized(lock) {
                if (yuvDecoder != null && activeYuvPath == videoPath && yuvDecoder!!.isRunning()) {
                    return
                }
                yuvDecoder?.stop()
                val decoder = YuvVideoDecoder(videoPath)
                yuvDecoder = decoder
                activeYuvPath = videoPath
                decoder.start()
                XposedBridge.log("$TAG: Started ByteBuffer YUV decoder for $videoPath")
            }
        }

        fun stopAllFeeds() {
            synchronized(lock) {
                try {
                    activePlayer?.stop()
                } catch (_: Throwable) {
                }
                try {
                    activePlayer?.release()
                } catch (_: Throwable) {
                }
                activePlayer = null

                try {
                    yuvDecoder?.stop()
                } catch (_: Throwable) {
                }
                yuvDecoder = null
                activeYuvPath = null
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
                        setOnErrorListener { _, what, extra ->
                            XposedBridge.log("$TAG: MediaPlayer error $what / $extra")
                            true
                        }
                        prepareAsync()
                    }
                    showToastOnce("ShizukuVCam: Active (Preview Stream)")
                    XposedBridge.log("$TAG: Playing $videoPath onto preview Surface")
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: Failed to bind MediaPlayer: ${t.message}")
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
                XposedBridge.log("$TAG: Hooked Image.Plane class ${planeClass.name}")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: Failed to hook Image.Plane class ${planeClass.name}: ${t.message}")
            }
        }

        fun applyVirtualPlanes(
            realPlanes: Array<Image.Plane>,
            dstW: Int,
            dstH: Int,
            packageName: String
        ) {
            if (realPlanes.size < 3 || dstW <= 0 || dstH <= 0) return
            val videoPath = getActiveVideoPath(packageName)
            if (videoPath == null) {
                planeOverrides.remove(realPlanes[0])
                planeOverrides.remove(realPlanes[1])
                planeOverrides.remove(realPlanes[2])
                return
            }

            ensureYuvDecoderRunning(videoPath)
            showToastOnce("ShizukuVCam: Active (YUV Frame Injection)")

            val rotation = getRotationDegrees(packageName)
            val frame = yuvDecoder?.latestFrame

            val yOv: PlaneOverride
            val uOv: PlaneOverride
            val vOv: PlaneOverride

            synchronized(lock) {
                val idx = slotIndex
                slotIndex = (slotIndex + 1) and 1
                var slot = bufferSlots[idx]
                if (slot == null || slot.w != dstW || slot.h != dstH) {
                    slot = DirectYuvBuffers(dstW, dstH)
                    bufferSlots[idx] = slot
                }

                if (frame == null) {
                    slot.fillBlack()
                } else {
                    slot.fillFromFrame(frame, rotation)
                }

                yOv = PlaneOverride(slot.yBuf.duplicate().apply { rewind() }, dstW, 1)
                uOv = PlaneOverride(slot.uBuf.duplicate().apply { rewind() }, slot.cw, 1)
                vOv = PlaneOverride(slot.vBuf.duplicate().apply { rewind() }, slot.cw, 1)
            }

            ensurePlaneClassHooked(realPlanes[0].javaClass)
            ensurePlaneClassHooked(realPlanes[1].javaClass)
            ensurePlaneClassHooked(realPlanes[2].javaClass)
            planeOverrides[realPlanes[0]] = yOv
            planeOverrides[realPlanes[1]] = uOv
            planeOverrides[realPlanes[2]] = vOv
        }
    }

    private class PlaneOverride(
        val buffer: ByteBuffer,
        val rowStride: Int,
        val pixelStride: Int
    )

    private class YuvFrame(
        val width: Int,
        val height: Int,
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray
    )

    private class DirectYuvBuffers(val w: Int, val h: Int) {
        val cw: Int = (w + 1) / 2
        val ch: Int = (h + 1) / 2

        private val yArr = ByteArray(w * h)
        private val uArr = ByteArray(cw * ch)
        private val vArr = ByteArray(cw * ch)

        val yBuf: ByteBuffer = ByteBuffer.allocateDirect(w * h)
        val uBuf: ByteBuffer = ByteBuffer.allocateDirect(cw * ch)
        val vBuf: ByteBuffer = ByteBuffer.allocateDirect(cw * ch)

        fun fillBlack() {
            yArr.fill(16.toByte())
            uArr.fill(128.toByte())
            vArr.fill(128.toByte())
            commit()
        }

        fun fillFromFrame(src: YuvFrame, rotation: Int) {
            scalePlane(src.y, src.width, src.height, yArr, w, h, rotation)
            val srcCw = (src.width + 1) / 2
            val srcCh = (src.height + 1) / 2
            scalePlane(src.u, srcCw, srcCh, uArr, cw, ch, rotation)
            scalePlane(src.v, srcCw, srcCh, vArr, cw, ch, rotation)
            commit()
        }

        private fun commit() {
            yBuf.clear()
            yBuf.put(yArr)
            yBuf.flip()

            uBuf.clear()
            uBuf.put(uArr)
            uBuf.flip()

            vBuf.clear()
            vBuf.put(vArr)
            vBuf.flip()
        }

        private fun scalePlane(
            src: ByteArray,
            srcW: Int,
            srcH: Int,
            dst: ByteArray,
            dstW: Int,
            dstH: Int,
            rotation: Int
        ) {
            if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return
            val maxSx = srcW - 1
            val maxSy = srcH - 1
            var dstPos = 0

            when (rotation) {
                90 -> {
                    for (y in 0 until dstH) {
                        val sx = (y * srcW / dstH).coerceIn(0, maxSx)
                        for (x in 0 until dstW) {
                            val sy = ((dstW - 1 - x) * srcH / dstW).coerceIn(0, maxSy)
                            dst[dstPos++] = src[sy * srcW + sx]
                        }
                    }
                }
                180 -> {
                    for (y in 0 until dstH) {
                        val sy = ((dstH - 1 - y) * srcH / dstH).coerceIn(0, maxSy)
                        val rowOff = sy * srcW
                        for (x in 0 until dstW) {
                            val sx = ((dstW - 1 - x) * srcW / dstW).coerceIn(0, maxSx)
                            dst[dstPos++] = src[rowOff + sx]
                        }
                    }
                }
                270 -> {
                    for (y in 0 until dstH) {
                        val sx = ((dstH - 1 - y) * srcW / dstH).coerceIn(0, maxSx)
                        for (x in 0 until dstW) {
                            val sy = (x * srcH / dstW).coerceIn(0, maxSy)
                            dst[dstPos++] = src[sy * srcW + sx]
                        }
                    }
                }
                else -> {
                    for (y in 0 until dstH) {
                        val sy = (y * srcH / dstH).coerceIn(0, maxSy)
                        val rowOff = sy * srcW
                        for (x in 0 until dstW) {
                            val sx = (x * srcW / dstW).coerceIn(0, maxSx)
                            dst[dstPos++] = src[rowOff + sx]
                        }
                    }
                }
            }
        }
    }

    private class YuvVideoDecoder(private val videoPath: String) {
        private val running = AtomicBoolean(false)
        private var worker: Thread? = null

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
                    if (videoTrack < 0 || videoFormat == null) {
                        throw IllegalStateException("No video track in $videoPath")
                    }

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
                                    val pts = extractor.sampleTime
                                    codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
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
                                val targetElapsedMs = (bufferInfo.presentationTimeUs - firstPtsUs) / 1000L
                                val actualElapsedMs = System.currentTimeMillis() - startWallMs
                                val sleepMs = targetElapsedMs - actualElapsedMs
                                if (sleepMs in 1L..200L) {
                                    try {
                                        Thread.sleep(sleepMs)
                                    } catch (_: InterruptedException) {
                                        break
                                    }
                                }
                            }

                            try {
                                val outImg = codec.getOutputImage(outIndex)
                                if (outImg != null) {
                                    try {
                                        extractI420Frame(outImg)?.let { frame ->
                                            latestFrame = frame
                                        }
                                    } finally {
                                        outImg.close()
                                    }
                                }
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG: getOutputImage error: ${t.message}")
                            }

                            codec.releaseOutputBuffer(outIndex, false)
                        }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: YuvVideoDecoder stopped: ${t.message}")
                } finally {
                    running.set(false)
                    try {
                        codec?.stop()
                    } catch (_: Throwable) {
                    }
                    try {
                        codec?.release()
                    } catch (_: Throwable) {
                    }
                    try {
                        extractor?.release()
                    } catch (_: Throwable) {
                    }
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

            return YuvFrame(w, h, yOut, uOut, vOut)
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
            XposedBridge.log("$TAG: Hooked ${imageClass.name}.getPlanes() in $packageName")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook ${imageClass.name}.getPlanes(): ${t.message}")
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
                            val surface = reader.surface
                            synchronized(lock) {
                                imageReaderFormats[surface] = format
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Could not hook ImageReader.newInstance: ${t.message}")
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
                val videoPath = getActiveVideoPath(pkg) ?: return
                ensureYuvDecoderRunning(videoPath)
                ensureImageClassHooked(img.javaClass, pkg)
            }
        }

        try {
            XposedHelpers.findAndHookMethod(ImageReader::class.java, "acquireLatestImage", acquireHook)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(ImageReader::class.java, "acquireNextImage", acquireHook)
        } catch (_: Throwable) {
        }
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
                        if (getActiveVideoPath(pkg) == null) return
                        val surface = param.args[0] as? Surface ?: return
                        if (isDummySurface(surface) || isImageReaderSurface(surface)) return
                        param.args[0] = getOrCreateDummySurface(surface)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                CaptureRequest.Builder::class.java,
                "removeTarget",
                Surface::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val surface = param.args[0] as? Surface ?: return
                        synchronized(lock) {
                            surfaceToDummy[surface]?.let { dummy ->
                                param.args[0] = dummy
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Could not hook CaptureRequest.Builder: ${t.message}")
        }

        val deviceImplClass = try {
            XposedHelpers.findClass(
                "android.hardware.camera2.impl.CameraDeviceImpl",
                lpparam.classLoader
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: CameraDeviceImpl not found: ${t.message}")
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
                        val videoPath = getActiveVideoPath(pkg) ?: return
                        @Suppress("UNCHECKED_CAST")
                        val outputs = param.args[0] as? List<Surface> ?: return
                        if (outputs.isEmpty()) return
                        if (outputs.any { isDummySurface(it) }) return

                        var hasYuvReader = false
                        var previewSurface: Surface? = null
                        val newOutputs = ArrayList<Surface>(outputs.size)

                        for (surf in outputs) {
                            if (isImageReaderSurface(surf)) {
                                if (!isJpegSurface(surf)) hasYuvReader = true
                                newOutputs.add(surf)
                            } else {
                                if (previewSurface == null) previewSurface = surf
                                newOutputs.add(getOrCreateDummySurface(surf))
                            }
                        }

                        if (hasYuvReader) {
                            ensureYuvDecoderRunning(videoPath)
                        }
                        val targetPreview = previewSurface
                        if (targetPreview != null) {
                            param.args[0] = newOutputs
                            startPreviewMediaPlayer(targetPreview, videoPath)
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        try {
            XposedHelpers.findAndHookMethod(
                deviceImplClass,
                "createCaptureSessionByOutputConfigurations",
                List::class.java,
                CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val videoPath = getActiveVideoPath(pkg) ?: return
                        @Suppress("UNCHECKED_CAST")
                        val configs = param.args[0] as? List<OutputConfiguration> ?: return
                        if (configs.isEmpty()) return
                        if (configs.any { it.surface?.let { s -> isDummySurface(s) } == true }) return

                        var hasYuvReader = false
                        var previewSurface: Surface? = null
                        val newConfigs = ArrayList<OutputConfiguration>(configs.size)

                        for (cfg in configs) {
                            val surf = cfg.surface
                            if (surf == null || isImageReaderSurface(surf)) {
                                if (surf != null && !isJpegSurface(surf)) hasYuvReader = true
                                newConfigs.add(cfg)
                            } else {
                                if (previewSurface == null) previewSurface = surf
                                newConfigs.add(OutputConfiguration(getOrCreateDummySurface(surf)))
                            }
                        }

                        if (hasYuvReader) {
                            ensureYuvDecoderRunning(videoPath)
                        }
                        val targetPreview = previewSurface
                        if (targetPreview != null) {
                            param.args[0] = newConfigs
                            startPreviewMediaPlayer(targetPreview, videoPath)
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
                            val videoPath = getActiveVideoPath(pkg) ?: return
                            val sessionConfig = param.args[0] as? SessionConfiguration ?: return
                            val configs = sessionConfig.outputConfigurations ?: return
                            if (configs.isEmpty()) return
                            if (configs.any { it.surface?.let { s -> isDummySurface(s) } == true }) return

                            var hasYuvReader = false
                            var previewSurface: Surface? = null
                            val newConfigs = ArrayList<OutputConfiguration>(configs.size)

                            for (cfg in configs) {
                                val surf = cfg.surface
                                if (surf == null || isImageReaderSurface(surf)) {
                                    if (surf != null && !isJpegSurface(surf)) hasYuvReader = true
                                    newConfigs.add(cfg)
                                } else {
                                    if (previewSurface == null) previewSurface = surf
                                    newConfigs.add(OutputConfiguration(getOrCreateDummySurface(surf)))
                                }
                            }

                            if (hasYuvReader) {
                                ensureYuvDecoderRunning(videoPath)
                            }
                            val targetPreview = previewSurface
                            if (targetPreview != null) {
                                val replacementSession = SessionConfiguration(
                                    sessionConfig.sessionType,
                                    newConfigs,
                                    sessionConfig.executor,
                                    sessionConfig.stateCallback
                                )
                                sessionConfig.sessionParameters?.let {
                                    replacementSession.sessionParameters = it
                                }
                                param.args[0] = replacementSession
                                startPreviewMediaPlayer(targetPreview, videoPath)
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
                        val videoPath = getActiveVideoPath(pkg) ?: return
                        val realTex = param.args[0] as? SurfaceTexture ?: return
                        try {
                            val dummyTex = SurfaceTexture(nextTexId++).apply {
                                setDefaultBufferSize(1280, 720)
                            }
                            synchronized(lock) { dummyTextures.add(dummyTex) }
                            param.args[0] = dummyTex

                            val targetSurf = Surface(realTex)
                            startPreviewMediaPlayer(targetSurf, videoPath)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: Camera1 hook error: ${t.message}")
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }
}
