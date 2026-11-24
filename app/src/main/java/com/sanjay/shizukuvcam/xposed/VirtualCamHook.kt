package com.sanjay.shizukuvcam.xposed

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

class VirtualCamHook : IXposedHookLoadPackage {

    companion object {
        private const val VIDEO_PATH = "/data/local/tmp/vcam_feed.mp4"
        private const val STATE_PATH = "/data/local/tmp/vcam_enabled"
        private const val TAG = "ShizukuVCam"

        private var activePlayer: MediaPlayer? = null
        private var dummyTexture: SurfaceTexture? = null
        private var dummySurface: Surface? = null

        fun isActive(): Boolean {
            return try {
                val stateFile = File(STATE_PATH)
                val videoFile = File(VIDEO_PATH)
                stateFile.exists() &&
                    stateFile.readText().trim() == "1" &&
                    videoFile.exists() &&
                    videoFile.length() > 0L
            } catch (_: Throwable) {
                false
            }
        }

        @Synchronized
        fun startVideoOnSurface(targetSurface: Surface) {
            try {
                activePlayer?.release()
                activePlayer = MediaPlayer().apply {
                    setDataSource(VIDEO_PATH)
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
                XposedBridge.log("$TAG: Playing $VIDEO_PATH onto target Surface")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: Failed to bind MediaPlayer: ${t.message}")
            }
        }

        @Synchronized
        fun createDummySurface(): Surface {
            dummySurface?.release()
            dummyTexture?.release()
            val tex = SurfaceTexture(10).apply { setDefaultBufferSize(1280, 720) }
            dummyTexture = tex
            val surf = Surface(tex)
            dummySurface = surf
            return surf
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.sanjay.shizukuvcam" ||
            lpparam.packageName == "android" ||
            lpparam.packageName == "com.android.systemui"
        ) {
            return
        }

        hookCamera2(lpparam)
        hookCamera1(lpparam)
    }

    private fun hookCamera2(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val deviceImplClass = XposedHelpers.findClass(
                "android.hardware.camera2.impl.CameraDeviceImpl",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                deviceImplClass,
                "createCaptureSession",
                SessionConfiguration::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isActive()) return
                        val sessionConfig = param.args[0] as? SessionConfiguration ?: return
                        val configs = sessionConfig.outputConfigurations ?: return
                        if (configs.isEmpty()) return

                        // Pick the primary preview/video output surface
                        val primaryConfig = configs[0]
                        val realSurface = primaryConfig.surface ?: return

                        try {
                            val dummy = createDummySurface()
                            val dummyConfig = OutputConfiguration(dummy)
                            val newConfigs = ArrayList<OutputConfiguration>(configs.size)
                            newConfigs.add(dummyConfig)
                            for (i in 1 until configs.size) {
                                newConfigs.add(configs[i])
                            }

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
                            startVideoOnSurface(realSurface)
                            XposedBridge.log("$TAG: Hooked Camera2 SessionConfiguration in ${lpparam.packageName}")
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: Camera2 hook error: ${t.message}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Could not hook Camera2 in ${lpparam.packageName}: ${t.message}")
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
                        if (!isActive()) return
                        val realTex = param.args[0] as? SurfaceTexture ?: return
                        try {
                            val dummyTex = SurfaceTexture(11).apply {
                                setDefaultBufferSize(1280, 720)
                            }
                            dummyTexture?.release()
                            dummyTexture = dummyTex
                            param.args[0] = dummyTex

                            val targetSurf = Surface(realTex)
                            startVideoOnSurface(targetSurf)
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
