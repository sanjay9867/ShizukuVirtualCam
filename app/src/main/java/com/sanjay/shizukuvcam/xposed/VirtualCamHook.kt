package com.sanjay.shizukuvcam.xposed

import android.app.AndroidAppHelper
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodec
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
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class VirtualCamHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "ShizukuVCam"

        private val lock = Any()
        private val surfaceToDummy = WeakHashMap<Surface, Surface>()
        private val dummyTextures = ArrayList<SurfaceTexture>()
        private val imageReaderFormats = WeakHashMap<Surface, Int>()

        private var activePlayer: MediaPlayer? = null
        private var codecFeeder: CodecSurfaceFeeder? = null
        private var nextTexId = 100

        private fun showToast(message: String) {
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

        fun getActiveVideoPath(packageName: String): String? {
            if (!isStateEnabled(packageName)) return null
            val path = resolveReadableVideo(packageName)
            if (path == null) {
                XposedBridge.log("$TAG: Enabled for $packageName but vcam_feed.mp4 not readable! Tap Sync in Shizuku VirtualCam.")
                showToast("ShizukuVCam: Video missing — tap Sync for this app")
            }
            return path
        }

        private fun isJpegSurface(surface: Surface): Boolean {
            synchronized(lock) {
                return imageReaderFormats[surface] == ImageFormat.JPEG
            }
        }

        private fun isImageReaderSurface(surface: Surface): Boolean {
            synchronized(lock) {
                return imageReaderFormats.containsKey(surface)
            }
        }

        fun getOrCreateDummySurface(realSurface: Surface): Surface {
            synchronized(lock) {
                surfaceToDummy[realSurface]?.let { existing ->
                    if (existing.isValid) return existing
                }
                val tex = SurfaceTexture(nextTexId++).apply {
                    setDefaultBufferSize(1280, 720)
                }
                dummyTextures.add(tex)
                val dummy = Surface(tex)
                surfaceToDummy[realSurface] = dummy
                return dummy
            }
        }

        fun stopActiveFeed() {
            synchronized(lock) {
                try {
                    codecFeeder?.stop()
                } catch (_: Throwable) {
                }
                codecFeeder = null

                try {
                    activePlayer?.stop()
                } catch (_: Throwable) {
                }
                try {
                    activePlayer?.release()
                } catch (_: Throwable) {
                }
                activePlayer = null
            }
        }

        fun startVideoOnSurface(targetSurface: Surface, videoPath: String, useCodecFirst: Boolean) {
            stopActiveFeed()
            synchronized(lock) {
                if (useCodecFirst) {
                    val feeder = CodecSurfaceFeeder(videoPath, targetSurface) {
                        // Fallback to MediaPlayer if MediaCodec fails on this device
                        startMediaPlayer(targetSurface, videoPath)
                    }
                    codecFeeder = feeder
                    feeder.start()
                    showToast("ShizukuVCam: Active (Browser/YUV Stream)")
                    XposedBridge.log("$TAG: Started MediaCodec surface feeder for $videoPath")
                } else {
                    startMediaPlayer(targetSurface, videoPath)
                    showToast("ShizukuVCam: Active (Preview Stream)")
                }
            }
        }

        private fun startMediaPlayer(targetSurface: Surface, videoPath: String) {
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
                    XposedBridge.log("$TAG: Playing $videoPath onto target Surface via MediaPlayer")
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: Failed to bind MediaPlayer: ${t.message}")
                }
            }
        }
    }

    private class CodecSurfaceFeeder(
        private val videoPath: String,
        private val targetSurface: Surface,
        private val onFailureFallback: () -> Unit
    ) {
        private val running = AtomicBoolean(false)
        private var worker: Thread? = null

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
                    val mime = videoFormat.getString(MediaFormat.KEY_MIME)!!
                    codec = MediaCodec.createDecoderByType(mime)
                    codec.configure(videoFormat, targetSurface, null, 0)
                    codec.start()

                    val bufferInfo = MediaCodec.BufferInfo()
                    var startWallMs = System.currentTimeMillis()
                    var firstPtsUs = -1L

                    while (running.get() && targetSurface.isValid) {
                        val inIndex = codec.dequeueInputBuffer(10_000L)
                        if (inIndex >= 0) {
                            val inputBuf = codec.getInputBuffer(inIndex)
                            if (inputBuf != null) {
                                val sampleSize = extractor.readSampleData(inputBuf, 0)
                                if (sampleSize < 0) {
                                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
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
                                    Thread.sleep(sleepMs)
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, true)
                        }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: CodecSurfaceFeeder error (${t.message}), falling back to MediaPlayer")
                    if (running.get() && targetSurface.isValid) {
                        onFailureFallback()
                    }
                } finally {
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
            }, "ShizukuVCam-Decoder").apply {
                isDaemon = true
                start()
            }
        }

        fun stop() {
            running.set(false)
            worker?.interrupt()
            worker = null
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.sanjay.shizukuvcam" ||
            lpparam.packageName == "android" ||
            lpparam.packageName == "com.android.systemui"
        ) {
            return
        }

        hookImageReader()
        hookCamera2(lpparam)
        hookCamera1(lpparam)
    }

    private fun hookImageReader() {
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
    }

    private fun hookCamera2(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. Hook CaptureRequest.Builder.addTarget / removeTarget so Camera2 stays consistent with dummy surfaces
        try {
            XposedHelpers.findAndHookMethod(
                CaptureRequest.Builder::class.java,
                "addTarget",
                Surface::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (getActiveVideoPath(lpparam.packageName) == null) return
                        val surface = param.args[0] as? Surface ?: return
                        if (isJpegSurface(surface)) return
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

        // 2. Hook CameraDeviceImpl.createCaptureSession(List<Surface>, StateCallback, Handler)
        // THIS is the exact overload used by Brave, Chrome, and Chromium-based browsers!
        try {
            XposedHelpers.findAndHookMethod(
                deviceImplClass,
                "createCaptureSession",
                List::class.java,
                CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val videoPath = getActiveVideoPath(lpparam.packageName) ?: return
                        @Suppress("UNCHECKED_CAST")
                        val outputs = param.args[0] as? List<Surface> ?: return
                        if (outputs.isEmpty()) return

                        var primarySurface: Surface? = null
                        val newOutputs = ArrayList<Surface>(outputs.size)
                        for (surf in outputs) {
                            if (isJpegSurface(surf)) {
                                newOutputs.add(surf)
                            } else {
                                if (primarySurface == null) primarySurface = surf
                                newOutputs.add(getOrCreateDummySurface(surf))
                            }
                        }

                        if (primarySurface != null) {
                            param.args[0] = newOutputs
                            startVideoOnSurface(
                                primarySurface,
                                videoPath,
                                useCodecFirst = isImageReaderSurface(primarySurface)
                            )
                            XposedBridge.log("$TAG: Hooked Camera2 List<Surface> in ${lpparam.packageName}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Could not hook createCaptureSession(List): ${t.message}")
        }

        // 3. Hook CameraDeviceImpl.createCaptureSessionByOutputConfigurations(List, StateCallback, Handler)
        try {
            XposedHelpers.findAndHookMethod(
                deviceImplClass,
                "createCaptureSessionByOutputConfigurations",
                List::class.java,
                CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val videoPath = getActiveVideoPath(lpparam.packageName) ?: return
                        @Suppress("UNCHECKED_CAST")
                        val configs = param.args[0] as? List<OutputConfiguration> ?: return
                        if (configs.isEmpty()) return

                        var primarySurface: Surface? = null
                        val newConfigs = ArrayList<OutputConfiguration>(configs.size)
                        for (cfg in configs) {
                            val surf = cfg.surface
                            if (surf == null || isJpegSurface(surf)) {
                                newConfigs.add(cfg)
                            } else {
                                if (primarySurface == null) primarySurface = surf
                                newConfigs.add(OutputConfiguration(getOrCreateDummySurface(surf)))
                            }
                        }

                        if (primarySurface != null) {
                            param.args[0] = newConfigs
                            startVideoOnSurface(
                                primarySurface,
                                videoPath,
                                useCodecFirst = isImageReaderSurface(primarySurface)
                            )
                            XposedBridge.log("$TAG: Hooked Camera2 OutputConfigurations in ${lpparam.packageName}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Could not hook createCaptureSessionByOutputConfigurations: ${t.message}")
        }

        // 4. Hook CameraDeviceImpl.createCaptureSession(SessionConfiguration) (Android 9+ modern apps)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                XposedHelpers.findAndHookMethod(
                    deviceImplClass,
                    "createCaptureSession",
                    SessionConfiguration::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val videoPath = getActiveVideoPath(lpparam.packageName) ?: return
                            val sessionConfig = param.args[0] as? SessionConfiguration ?: return
                            val configs = sessionConfig.outputConfigurations ?: return
                            if (configs.isEmpty()) return

                            var primarySurface: Surface? = null
                            val newConfigs = ArrayList<OutputConfiguration>(configs.size)
                            for (cfg in configs) {
                                val surf = cfg.surface
                                if (surf == null || isJpegSurface(surf)) {
                                    newConfigs.add(cfg)
                                } else {
                                    if (primarySurface == null) primarySurface = surf
                                    newConfigs.add(OutputConfiguration(getOrCreateDummySurface(surf)))
                                }
                            }

                            if (primarySurface != null) {
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
                                startVideoOnSurface(
                                    primarySurface,
                                    videoPath,
                                    useCodecFirst = isImageReaderSurface(primarySurface)
                                )
                                XposedBridge.log("$TAG: Hooked Camera2 SessionConfiguration in ${lpparam.packageName}")
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: Could not hook createCaptureSession(SessionConfiguration): ${t.message}")
            }
        }

        // 5. Release player cleanly when CameraDevice closes
        try {
            XposedHelpers.findAndHookMethod(
                deviceImplClass,
                "close",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        stopActiveFeed()
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun hookCamera1(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Camera::class.java,
                "setPreviewTexture",
                SurfaceTexture::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val videoPath = getActiveVideoPath(lpparam.packageName) ?: return
                        val realTex = param.args[0] as? SurfaceTexture ?: return
                        try {
                            val dummyTex = SurfaceTexture(nextTexId++).apply {
                                setDefaultBufferSize(1280, 720)
                            }
                            synchronized(lock) { dummyTextures.add(dummyTex) }
                            param.args[0] = dummyTex

                            val targetSurf = Surface(realTex)
                            startVideoOnSurface(targetSurf, videoPath, useCodecFirst = false)
                            XposedBridge.log("$TAG: Hooked Camera1 setPreviewTexture in ${lpparam.packageName}")
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: Camera1 hook error: ${t.message}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Could not hook Camera1 in ${lpparam.packageName}: ${t.message}")
        }
    }
}
