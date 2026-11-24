package com.sanjay.shizukuvcam.service

import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.annotation.Keep
import com.sanjay.shizukuvcam.IShellService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.system.exitProcess

@Keep
class ShellUserService : IShellService.Stub() {

    companion object {
        const val TMP_VIDEO_PATH = "/data/local/tmp/vcam_feed.mp4"
        const val TMP_STATE_PATH = "/data/local/tmp/vcam_enabled"
        const val PUBLIC_DIR_PATH = "/storage/emulated/0/DCIM/Camera1"
        const val PUBLIC_VIDEO_PATH = "$PUBLIC_DIR_PATH/vcam_feed.mp4"
        const val PUBLIC_STATE_PATH = "$PUBLIC_DIR_PATH/vcam_enabled"
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun runCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (out.isNotBlank()) out.trim() else err.trim()
        } catch (e: Exception) {
            "ERR: ${e.message}"
        }
    }

    override fun deployVideoFd(pfd: ParcelFileDescriptor?): String {
        if (pfd == null) return "ERR: Could not open selected video"
        return try {
            val tmpDst = File(TMP_VIDEO_PATH)
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                FileOutputStream(tmpDst).use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            }
            if (!tmpDst.exists() || tmpDst.length() == 0L) {
                return "ERR: Copied file is empty"
            }
            try {
                Os.chmod(TMP_VIDEO_PATH, 0b110_100_100) // 0644
            } catch (_: Throwable) {
            }

            try {
                val pubDir = File(PUBLIC_DIR_PATH)
                if (!pubDir.exists()) pubDir.mkdirs()
                val pubDst = File(PUBLIC_VIDEO_PATH)
                FileInputStream(tmpDst).use { input ->
                    FileOutputStream(pubDst).use { output ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                }
            } catch (_: Throwable) {
            }

            "OK"
        } catch (e: Exception) {
            "ERR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    override fun setVirtualCamEnabled(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        return try {
            val tmpState = File(TMP_STATE_PATH)
            tmpState.writeText(value)
            try {
                Os.chmod(TMP_STATE_PATH, 0b110_100_100) // 0644
            } catch (_: Throwable) {
            }

            try {
                val pubDir = File(PUBLIC_DIR_PATH)
                if (!pubDir.exists()) pubDir.mkdirs()
                File(PUBLIC_STATE_PATH).writeText(value)
            } catch (_: Throwable) {
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun isVirtualCamEnabled(): Boolean {
        return try {
            val tmpState = File(TMP_STATE_PATH)
            val pubState = File(PUBLIC_STATE_PATH)
            (tmpState.exists() && tmpState.readText().trim() == "1") ||
                (pubState.exists() && pubState.readText().trim() == "1")
        } catch (_: Exception) {
            false
        }
    }

    override fun hasDeployedVideo(): Boolean {
        return try {
            val tmpFile = File(TMP_VIDEO_PATH)
            val pubFile = File(PUBLIC_VIDEO_PATH)
            (tmpFile.exists() && tmpFile.length() > 0L) ||
                (pubFile.exists() && pubFile.length() > 0L)
        } catch (_: Exception) {
            false
        }
    }

    override fun prepareAndRestartPackage(packageName: String): String {
        if (!packageName.matches(Regex("^[a-zA-Z0-9._]+$"))) {
            return "ERR: Invalid package name"
        }
        return try {
            val targetDir = File("/storage/emulated/0/Android/data/$packageName/files")
            if (!targetDir.exists()) targetDir.mkdirs()

            val srcVideo = File(TMP_VIDEO_PATH)
            if (srcVideo.exists() && srcVideo.length() > 0L) {
                FileInputStream(srcVideo).use { input ->
                    FileOutputStream(File(targetDir, "vcam_feed.mp4")).use { output ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                }
            }

            val enabledText = if (isVirtualCamEnabled()) "1" else "0"
            File(targetDir, "vcam_enabled").writeText(enabledText)

            runCommand("pm grant $packageName android.permission.READ_MEDIA_VIDEO")
            runCommand("pm grant $packageName android.permission.READ_EXTERNAL_STORAGE")
            runCommand("am force-stop $packageName")
            "OK"
        } catch (e: Exception) {
            runCommand("am force-stop $packageName")
            "ERR: ${e.message}"
        }
    }
}
