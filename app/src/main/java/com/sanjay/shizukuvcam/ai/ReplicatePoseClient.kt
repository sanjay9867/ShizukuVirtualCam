package com.sanjay.shizukuvcam.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
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

    /**
     * Generates a true photorealistic 3D human-motion MP4 video by transferring
     * real head turns, blinks, and micro-expressions from a driving video onto
     * your face photo using fofr/live-portrait on Replicate.
     */
    fun generateLivePortraitVideo(
        apiToken: String,
        sourcePhotoFile: File,
        drivingVideoFile: File,
        outVideoFile: File,
        onProgress: (String) -> Unit
    ): Result<File> {
        return try {
            onProgress("Uploading face photo to Replicate...")
            val photoUrl = uploadFileToReplicate(apiToken, sourcePhotoFile, "image/jpeg")

            onProgress("Uploading driving video to Replicate...")
            val videoUrl = uploadFileToReplicate(apiToken, drivingVideoFile, "video/mp4")

            onProgress("Resolving fofr/live-portrait model version...")
            val versionId = fetchLatestVersionId(apiToken, "fofr", "live-portrait")
                ?: "067dd98cc3e5cb396c4a9efb4bba3eec6c4a9d271211325c477518fc6485e146"

            onProgress("Animating face with real human motion (LivePortrait AI)...")
            val input = JSONObject().apply {
                put("face_image", photoUrl)
                put("driving_video", videoUrl)
                put("live_portrait_dsize", 512)
                put("live_portrait_scale", 2.3)
                put("live_portrait_stitching", true)
                put("live_portrait_relative", true)
                put("live_portrait_eye_retargeting", true)
                put("live_portrait_lip_retargeting", false)
                put("live_portrait_lip_zero", true)
                put("video_frame_load_cap", 160)
                put("video_select_every_n_frames", 1)
            }

            val outputUrl = runPrediction(apiToken, versionId, input, maxPollAttempts = 90) { status ->
                onProgress("LivePortrait AI status: $status...")
            }

            onProgress("Downloading photorealistic animated video...")
            downloadUrlToFile(outputUrl, outVideoFile)
            Result.success(outVideoFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
                val input = JSONObject().apply {
                    put("image", dataUri)
                    put("rotate_yaw", spec.yaw)
                    put("rotate_pitch", spec.pitch)
                    put("rotate_roll", 0)
                    put("blink", spec.blink)
                    put("smile", spec.smile)
                    put("output_format", "jpg")
                    put("output_quality", 92)
                }
                val outputUrl = runPrediction(apiToken, versionId, input, maxPollAttempts = 45) {}
                val outFile = File(outDir, spec.fileName)
                downloadUrlToFile(outputUrl, outFile)
                generated.add(outFile)
            }
            Result.success(generated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun uploadFileToReplicate(token: String, file: File, mimeType: String): String {
        val boundary = "----ShizukuVCamBoundary" + System.currentTimeMillis()
        val conn = (URL("https://api.replicate.com/v1/files").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 30_000
            readTimeout = 90_000
        }

        DataOutputStream(conn.outputStream).use { out ->
            out.writeBytes("--$boundary
")
            out.writeBytes("Content-Disposition: form-data; name="content"; filename="${file.name}"
")
            out.writeBytes("Content-Type: $mimeType

")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(32768)
                while (true) {
                    val read = fis.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
            }
            out.writeBytes("
--$boundary--
")
            out.flush()
        }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("File upload HTTP $code: $body")
        }
        val json = JSONObject(body)
        return json.optJSONObject("urls")?.optString("get")
            ?.takeIf { it.startsWith("http") }
            ?: throw IllegalStateException("Replicate file upload did not return a URL")
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

    private fun runPrediction(
        token: String,
        versionId: String,
        input: JSONObject,
        maxPollAttempts: Int,
        onStatus: (String) -> Unit
    ): String {
        val payload = JSONObject().apply {
            put("version", versionId)
            put("input", input)
        }

        val conn = (URL("https://api.replicate.com/v1/predictions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Prefer", "wait=30")
            connectTimeout = 30_000
            readTimeout = 45_000
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
        while ((status == "starting" || status == "processing") && pollUrl.isNotBlank() && attempts < maxPollAttempts) {
            onStatus("$status (${attempts * 2}s)")
            Thread.sleep(2000L)
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
            throw IllegalStateException("AI generation failed: $err")
        }

        val outObj = json.opt("output")
        return when (outObj) {
            is JSONArray -> outObj.optString(0)
            is String -> outObj
            else -> ""
        }.takeIf { it.startsWith("http") }
            ?: throw IllegalStateException("No output URL returned by Replicate")
    }

    private fun downloadUrlToFile(urlStr: String, dst: File) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        conn.inputStream.use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output, bufferSize = 32768)
            }
        }
        conn.disconnect()
    }
}
