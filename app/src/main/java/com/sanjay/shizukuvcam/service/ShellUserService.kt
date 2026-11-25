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
        const val TMP_DIR = "/data/local/tmp"
        const val PUBLIC_DIR = "/storage/emulated/0/DCIM/Camera1"

        private val ALLOWED_FILES = setOf(
            "vcam_feed.mp4",
            "vcam_photo.jpg",
            "vcam_ctrl.txt"
        )
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

    private fun isValidPackage(pkg: String?): Boolean {
        return !pkg.isNullOrBlank() && pkg.matches(Regex("^[a-zA-Z0-9._]+$"))
    }

    private fun copyFileSafe(src: File, dst: File) {
        if (!src.exists() || src.length() == 0L) return
        dst.parentFile?.let { if (!it.exists()) it.mkdirs() }
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        }
    }

    override fun deployNamedFileFd(
        pfd: ParcelFileDescriptor?,
        fileName: String?,
        activePackage: String?
    ): String {
        if (pfd == null) return "ERR: Null file descriptor"
        if (fileName == null || !ALLOWED_FILES.contains(fileName)) {
            return "ERR: Unsupported file name"
        }

        return try {
            val tmpDst = File(TMP_DIR, fileName)
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                FileOutputStream(tmpDst).use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            }
            if (!tmpDst.exists() || tmpDst.length() == 0L) {
                return "ERR: Empty file"
            }
            try {
                Os.chmod(tmpDst.absolutePath, 0b110_100_100)
            } catch (_: Throwable) {
            }

            try {
                copyFileSafe(tmpDst, File(PUBLIC_DIR, fileName))
            } catch (_: Throwable) {
            }

            if (isValidPackage(activePackage)) {
                try {
                    val pkgDir = File("/storage/emulated/0/Android/data/$activePackage/files")
                    copyFileSafe(tmpDst, File(pkgDir, fileName))
                } catch (_: Throwable) {
                }
            }

            "OK"
        } catch (e: Exception) {
            "ERR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    override fun writeControlState(controlText: String?, activePackage: String?): Boolean {
        val text = controlText ?: return false
        return try {
            val tmpFile = File(TMP_DIR, "vcam_ctrl.txt")
            tmpFile.writeText(text)
            try {
                Os.chmod(tmpFile.absolutePath, 0b110_100_100)
            } catch (_: Throwable) {
            }

            try {
                val pubDir = File(PUBLIC_DIR)
                if (!pubDir.exists()) pubDir.mkdirs()
                File(pubDir, "vcam_ctrl.txt").writeText(text)
            } catch (_: Throwable) {
            }

            if (isValidPackage(activePackage)) {
                try {
                    val pkgDir = File("/storage/emulated/0/Android/data/$activePackage/files")
                    if (!pkgDir.exists()) pkgDir.mkdirs()
                    File(pkgDir, "vcam_ctrl.txt").writeText(text)
                } catch (_: Throwable) {
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun readControlState(): String {
        val candidates = listOf(
            File(TMP_DIR, "vcam_ctrl.txt"),
            File(PUBLIC_DIR, "vcam_ctrl.txt")
        )
        for (f in candidates) {
            try {
                if (f.exists() && f.length() > 0L) return f.readText()
            } catch (_: Exception) {
            }
        }
        return ""
    }

    override fun hasDeployedFile(fileName: String?): Boolean {
        if (fileName == null || !ALLOWED_FILES.contains(fileName)) return false
        return try {
            val tmpFile = File(TMP_DIR, fileName)
            val pubFile = File(PUBLIC_DIR, fileName)
            (tmpFile.exists() && tmpFile.length() > 0L) ||
                (pubFile.exists() && pubFile.length() > 0L)
        } catch (_: Exception) {
            false
        }
    }

    override fun prepareAndRestartPackage(packageName: String?): String {
        if (!isValidPackage(packageName)) {
            return "ERR: Invalid package name"
        }
        val pkg = packageName!!
        return try {
            val targetDir = File("/storage/emulated/0/Android/data/$pkg/files")
            if (!targetDir.exists()) targetDir.mkdirs()

            for (fileName in ALLOWED_FILES) {
                val src = File(TMP_DIR, fileName).takeIf { it.exists() && it.length() > 0L }
                    ?: File(PUBLIC_DIR, fileName).takeIf { it.exists() && it.length() > 0L }
                if (src != null) {
                    copyFileSafe(src, File(targetDir, fileName))
                }
            }

            runCommand("pm grant $pkg android.permission.CAMERA")
            runCommand("pm grant $pkg android.permission.READ_MEDIA_IMAGES")
            runCommand("pm grant $pkg android.permission.READ_MEDIA_VIDEO")
            runCommand("pm grant $pkg android.permission.READ_EXTERNAL_STORAGE")
            runCommand("am force-stop $pkg")
            "OK"
        } catch (e: Exception) {
            runCommand("am force-stop $pkg")
            "ERR: ${e.message}"
        }
    }
}
