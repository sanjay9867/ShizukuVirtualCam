package com.sanjay.shizukuvcam.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ReplicatePoseClient {

    data class PoseSpec(
        val fileName: String,
        val label: String,
        val yaw: Int = 0,
        val pitch: Int = 0,
        val blink: Int = 0,
        val smile: Double = 0.0
    )

    val DEFAULT_POSES = listOf(
        PoseSpec("vcam_pose_left.jpg", "Look Left", yaw = -18),
        PoseSpec("vcam_pose_right.jpg", "Look Right", yaw = 18),
        PoseSpec("vcam_pose_up.jpg", "Look Up", pitch = -14),
        PoseSpec("vcam_pose_down.jpg", "Look Down", pitch = 14),
        PoseSpec("vcam_pose_blink.jpg", "Blink Eyes", blink = -16),
        PoseSpec("vcam_pose_smile.jpg", "Gentle Smile", smile = 0.75)
    )

    fun generateAllPoses(
        apiToken: String,
        sourcePhotoFile: File,
        outDir: File,
        onProgress: (String) -> Unit
    ): Result<List<File>> {
        return try {
            if (!outDir.exists()) outDir.mkdirs()
            val photoBytes = sourcePhotoFile.readBytes()
            val dataUri = "data:image/jpeg;base64," + Base64.encodeToString(photoBytes, Base64.NO_WRAP)

            onProgress("Fetching latest fofr/expression-editor model version...")
            val versionId = fetchLatestVersionId(apiToken, "fofr", "expression-editor")
                ?: return Result.failure(IllegalStateException("Could not fetch Replicate model version. Check API token."))

            val generated = ArrayList<File>()
            for ((index, spec) in DEFAULT_POSES.withIndex()) {
                onProgress("Generating AI Pose ${index + 1}/${DEFAULT_POSES.size}: ${spec.label}...")
                val outputUrl = runExpressionPrediction(apiToken, versionId, dataUri, spec)
                val outFile = File(outDir, spec.fileName)
                downloadUrlToFile(outputUrl, outFile)
                generated.add(outFile)
            }
            Result.success(generated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchLatestVersionId(token: String, owner: String, name: String): String? {
        val conn = (URL("https://api.replicate.com/v1/models/$owner/$name").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 20_000
            readTimeout = 20_000
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("Replicate HTTP $code: $body")
        }
        val root = JSONObject(body)
        return root.optJSONObject("latest_version")?.optString("id")?.takeIf { it.isNotBlank() }
    }

    private fun runExpressionPrediction(
        token: String,
        versionId: String,
        imageDataUri: String,
        spec: PoseSpec
    ): String {
        val input = JSONObject().apply {
            put("image", imageDataUri)
            put("rotate_yaw", spec.yaw)
            put("rotate_pitch", spec.pitch)
            put("rotate_roll", 0)
            put("blink", spec.blink)
            put("smile", spec.smile)
            put("output_format", "jpg")
            put("output_quality", 92)
        }
        val payload = JSONObject().apply {
            put("version", versionId)
            put("input", input)
        }

        val conn = (URL("https://api.replicate.com/v1/predictions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Prefer", "wait=60")
            connectTimeout = 30_000
            readTimeout = 65_000
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("Prediction HTTP $code: $body")
        }

        var json = JSONObject(body)
        var status = json.optString("status")
        val pollUrl = json.optJSONObject("urls")?.optString("get").orEmpty()

        var attempts = 0
        while ((status == "starting" || status == "processing") && pollUrl.isNotBlank() && attempts < 45) {
            Thread.sleep(1500L)
            attempts++
            val pConn = (URL(pollUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            val pBody = pConn.inputStream.bufferedReader().readText()
            pConn.disconnect()
            json = JSONObject(pBody)
            status = json.optString("status")
        }

        if (status != "succeeded") {
            val err = json.optString("error", "Status: $status")
            throw IllegalStateException("AI Pose failed: $err")
        }

        val outObj = json.opt("output")
        return when (outObj) {
            is JSONArray -> outObj.optString(0)
            is String -> outObj
            else -> ""
        }.takeIf { it.startsWith("http") }
            ?: throw IllegalStateException("No image URL returned by Replicate")
    }

    private fun downloadUrlToFile(urlStr: String, dst: File) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        conn.inputStream.use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output, bufferSize = 32768)
            }
        }
        conn.disconnect()
    }
}
