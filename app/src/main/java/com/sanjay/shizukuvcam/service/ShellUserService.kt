package com.sanjay.shizukuvcam.service

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
        const val SHARED_VIDEO_PATH = "/data/local/tmp/vcam_feed.mp4"
        const val SHARED_STATE_PATH = "/data/local/tmp/vcam_enabled"
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

    override fun deployVideoFeed(sourcePath: String): Boolean {
        return try {
            val src = File(sourcePath)
            if (!src.exists()) return false
            val dst = File(SHARED_VIDEO_PATH)
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            }
            // Make world-readable so sandboxed target apps hooked via LSPatch can read it
            Os.chmod(SHARED_VIDEO_PATH, 0b110_100_100) // 0644
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun setVirtualCamEnabled(enabled: Boolean): Boolean {
        return try {
            val stateFile = File(SHARED_STATE_PATH)
            stateFile.writeText(if (enabled) "1" else "0")
            Os.chmod(SHARED_STATE_PATH, 0b110_100_100) // 0644
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun isVirtualCamEnabled(): Boolean {
        return try {
            val stateFile = File(SHARED_STATE_PATH)
            stateFile.exists() && stateFile.readText().trim() == "1"
        } catch (_: Exception) {
            false
        }
    }

    override fun hasDeployedVideo(): Boolean {
        return try {
            val f = File(SHARED_VIDEO_PATH)
            f.exists() && f.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    override fun forceStopPackage(packageName: String) {
        if (packageName.matches(Regex("^[a-zA-Z0-9._]+$"))) {
            runCommand("am force-stop $packageName")
        }
    }
}
