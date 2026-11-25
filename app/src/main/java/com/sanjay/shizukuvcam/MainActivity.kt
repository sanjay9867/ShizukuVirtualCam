package com.sanjay.shizukuvcam

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import android.media.FaceDetector
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.sanjay.shizukuvcam.ai.ReplicatePoseClient
import com.sanjay.shizukuvcam.service.FloatingControlService
import com.sanjay.shizukuvcam.ui.FaceDpadView
import com.sanjay.shizukuvcam.xposed.FacePoseBank
import com.sanjay.shizukuvcam.xposed.FaceWarpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tvShizukuBadge: TextView
    private lateinit var ivLivePreview: ImageView
    private lateinit var tvPreviewOverlayHint: TextView
    private lateinit var spinnerFlip: Spinner
    private lateinit var btnZoomMinus: Button
    private lateinit var btnZoomPlus: Button
    private lateinit var tvZoomValue: TextView
    private lateinit var faceDpad: FaceDpadView
    private lateinit var btnAutoMove: Button
    private lateinit var btnBlink: Button
    private lateinit var btnSmile: Button
    private lateinit var btnIdleToggle: Button
    private lateinit var btnFloatOverlay: Button
    private lateinit var btnUploadPhoto: Button
    private lateinit var btnUploadVideo: Button
    private lateinit var switchEnableVCam: MaterialSwitch
    private lateinit var tvFeedStatus: TextView
    private lateinit var etReplicateToken: EditText
    private lateinit var btnLivePortraitAnimate: Button
    private lateinit var btnGenerateAiPoses: Button
    private lateinit var btnClearAiPoses: Button
    private lateinit var spinnerApps: Spinner
    private lateinit var btnSyncTarget: Button
    private lateinit var btnOpenLSPatch: Button
    private lateinit var btnReconnectShizuku: Button

    private var isSyncingUi = false
    private var targetApps: List<Pair<String, String>> = emptyList()

    private val flipPresets = listOf(
        "270° Front Cam" to Triple(270, false, false),
        "270° + Mirror" to Triple(270, true, false),
        "0° Normal" to Triple(0, false, false),
        "0° Mirror H" to Triple(0, true, false),
        "90° Clockwise" to Triple(90, false, false),
        "180° Flip V" to Triple(180, false, true)
    )

    private var previewBank: FacePoseBank? = null
    private var previewBitmap: Bitmap? = null
    private var previewPixels: IntArray? = null
    private val previewAnimator = FaceWarpEngine.MotionAnimator()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPreviewTicking = false

    private val previewTicker = object : Runnable {
        override fun run() {
            if (!isPreviewTicking) return
            renderPreviewFrame()
            mainHandler.postDelayed(this, 36L)
        }
    }

    private val shizukuStatusListener: (Boolean) -> Unit = { connected ->
        runOnUiThread {
            tvShizukuBadge.text = if (connected) "● Shizuku Connected" else "● Shizuku Offline"
            if (connected) {
                syncUiFromState()
            }
        }
    }

    private val stateChangedListener: () -> Unit = {
        runOnUiThread { syncUiFromState() }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 1001 && grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuBridge.bind()
            } else {
                tvShizukuBadge.text = "● Permission Denied"
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkAndBindShizuku()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        ShizukuBridge.markDisconnected()
    }

    private val pickPhotoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) handlePickedPhoto(uri)
        }

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) handlePickedVideo(uri)
        }

    private val pickDrivingVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) handleLivePortraitWithDrivingVideo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupSpinners()
        setupManualControls()
        setupSetupAndAiControls()

        val prefs = getSharedPreferences("vcam_prefs", MODE_PRIVATE)
        etReplicateToken.setText(prefs.getString("replicate_token", ""))

        ShizukuBridge.addStatusListener(shizukuStatusListener)
        ShizukuBridge.addStateListener(stateChangedListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        reloadLocalPreviewBank()
        syncUiFromState()
        checkAndBindShizuku()
    }

    private fun bindViews() {
        tvShizukuBadge = findViewById(R.id.tvShizukuBadge)
        ivLivePreview = findViewById(R.id.ivLivePreview)
        tvPreviewOverlayHint = findViewById(R.id.tvPreviewOverlayHint)
        spinnerFlip = findViewById(R.id.spinnerFlip)
        btnZoomMinus = findViewById(R.id.btnZoomMinus)
        btnZoomPlus = findViewById(R.id.btnZoomPlus)
        tvZoomValue = findViewById(R.id.tvZoomValue)
        faceDpad = findViewById(R.id.faceDpad)
        btnAutoMove = findViewById(R.id.btnAutoMove)
        btnBlink = findViewById(R.id.btnBlink)
        btnSmile = findViewById(R.id.btnSmile)
        btnIdleToggle = findViewById(R.id.btnIdleToggle)
        btnFloatOverlay = findViewById(R.id.btnFloatOverlay)
        btnUploadPhoto = findViewById(R.id.btnUploadPhoto)
        btnUploadVideo = findViewById(R.id.btnUploadVideo)
        switchEnableVCam = findViewById(R.id.switchEnableVCam)
        tvFeedStatus = findViewById(R.id.tvFeedStatus)
        etReplicateToken = findViewById(R.id.etReplicateToken)
        btnLivePortraitAnimate = findViewById(R.id.btnLivePortraitAnimate)
        btnGenerateAiPoses = findViewById(R.id.btnGenerateAiPoses)
        btnClearAiPoses = findViewById(R.id.btnClearAiPoses)
        spinnerApps = findViewById(R.id.spinnerApps)
        btnSyncTarget = findViewById(R.id.btnSyncTarget)
        btnOpenLSPatch = findViewById(R.id.btnOpenLSPatch)
        btnReconnectShizuku = findViewById(R.id.btnReconnectShizuku)
    }

    private fun setupSpinners() {
        spinnerFlip.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            flipPresets.map { it.first }
        )
        spinnerFlip.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSyncingUi) return
                val (rot, fh, fv) = flipPresets[position].second
                ShizukuBridge.state.rotation = rot
                ShizukuBridge.state.flipH = fh
                ShizukuBridge.state.flipV = fv
                ShizukuBridge.pushControlState()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val pm = packageManager
        val installed = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    app.packageName != packageName &&
                        ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                            app.packageName == "com.android.chrome")
                }
                .map { app ->
                    val label = pm.getApplicationLabel(app).toString()
                    "$label (${app.packageName})" to app.packageName
                }
                .sortedBy { it.first.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }

        targetApps = installed.ifEmpty {
            listOf(
                "Brave (com.brave.browser)" to "com.brave.browser",
                "Chrome (com.android.chrome)" to "com.android.chrome"
            )
        }

        spinnerApps.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            targetApps.map { it.first }
        )

        val braveIdx = targetApps.indexOfFirst { it.second == "com.brave.browser" }
        if (braveIdx >= 0) {
            spinnerApps.setSelection(braveIdx)
            ShizukuBridge.activeTargetPackage = "com.brave.browser"
        }

        spinnerApps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in targetApps.indices) {
                    ShizukuBridge.activeTargetPackage = targetApps[position].second
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupManualControls() {
        btnZoomMinus.setOnClickListener {
            ShizukuBridge.state.zoom = (ShizukuBridge.state.zoom - 0.1f).coerceIn(0.6f, 2.2f)
            ShizukuBridge.pushControlState()
        }

        btnZoomPlus.setOnClickListener {
            ShizukuBridge.state.zoom = (ShizukuBridge.state.zoom + 0.1f).coerceIn(0.6f, 2.2f)
            ShizukuBridge.pushControlState()
        }

        faceDpad.onDirectionCommand = { zone, yaw, pitch ->
            ShizukuBridge.state.autoSweep = false
            when (zone) {
                FaceDpadView.Zone.CENTER -> {
                    ShizukuBridge.state.yaw = 0f
                    ShizukuBridge.state.pitch = 0f
                    ShizukuBridge.state.smile = 0f
                }
                else -> {
                    ShizukuBridge.state.yaw = yaw
                    ShizukuBridge.state.pitch = pitch
                }
            }
            ShizukuBridge.pushControlState()
        }

        btnAutoMove.setOnClickListener {
            val next = !ShizukuBridge.state.autoSweep
            ShizukuBridge.state.autoSweep = next
            if (!next) {
                ShizukuBridge.state.yaw = 0f
                ShizukuBridge.state.pitch = 0f
            }
            ShizukuBridge.pushControlState()
        }

        btnBlink.setOnClickListener {
            ShizukuBridge.state.blinkSeq = System.currentTimeMillis()
            ShizukuBridge.pushControlState()
        }

        btnSmile.setOnClickListener {
            ShizukuBridge.state.smile = if (ShizukuBridge.state.smile > 0.3f) 0f else 0.85f
            ShizukuBridge.pushControlState()
        }

        btnIdleToggle.setOnClickListener {
            ShizukuBridge.state.autoIdle = !ShizukuBridge.state.autoIdle
            ShizukuBridge.pushControlState()
        }

        btnFloatOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant 'Display over other apps' permission to float over Brave", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                startService(Intent(this, FloatingControlService::class.java))
                Toast.makeText(this, "Floating Manual Menu active! Switch to Brave now.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSetupAndAiControls() {
        btnReconnectShizuku.setOnClickListener { checkAndBindShizuku() }

        btnUploadPhoto.setOnClickListener {
            if (ShizukuBridge.service == null) {
                Toast.makeText(this, "Connect Shizuku first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickPhotoLauncher.launch("image/*")
        }

        btnUploadVideo.setOnClickListener {
            if (ShizukuBridge.service == null) {
                Toast.makeText(this, "Connect Shizuku first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickVideoLauncher.launch("video/*")
        }

        switchEnableVCam.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            if (ShizukuBridge.service == null) {
                isSyncingUi = true
                switchEnableVCam.isChecked = false
                isSyncingUi = false
                Toast.makeText(this, "Connect Shizuku first!", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            ShizukuBridge.state.enabled = isChecked
            ShizukuBridge.pushControlState()
            Toast.makeText(
                this,
                if (isChecked) "Virtual Camera ENABLED (${ShizukuBridge.state.mode.uppercase()})" else "Virtual Camera DISABLED",
                Toast.LENGTH_SHORT
            ).show()
        }

        // True Photorealistic Human Motion Transfer (LivePortrait AI)
        btnLivePortraitAnimate.setOnClickListener {
            val token = etReplicateToken.text.toString().trim()
            if (token.isBlank()) {
                Toast.makeText(this, "Paste your Replicate API token (r8_...) first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val localPhoto = File(filesDir, "vcam_photo.jpg")
            if (!localPhoto.exists()) {
                Toast.makeText(this, "Upload a Face Photo first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ShizukuBridge.service == null) {
                Toast.makeText(this, "Connect Shizuku first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            getSharedPreferences("vcam_prefs", MODE_PRIVATE).edit()
                .putString("replicate_token", token)
                .apply()

            Toast.makeText(
                this,
                "Pick a short 4-8s real selfie driving video (head turns + blinks)",
                Toast.LENGTH_LONG
            ).show()
            pickDrivingVideoLauncher.launch("video/*")
        }

        btnGenerateAiPoses.setOnClickListener {
            val token = etReplicateToken.text.toString().trim()
            if (token.isBlank()) {
                Toast.makeText(this, "Paste your Replicate API token (r8_...) first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val localPhoto = File(filesDir, "vcam_photo.jpg")
            if (!localPhoto.exists()) {
                Toast.makeText(this, "Upload a face photo first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            getSharedPreferences("vcam_prefs", MODE_PRIVATE).edit()
                .putString("replicate_token", token)
                .apply()

            setAiButtonsEnabled(false)
            tvFeedStatus.text = "Starting Replicate AI 3D Pose Generator..."
            lifecycleScope.launch(Dispatchers.IO) {
                val result = ReplicatePoseClient.generateAllPoses(
                    apiToken = token,
                    sourcePhotoFile = localPhoto,
                    outDir = filesDir
                ) { progressMsg ->
                    runOnUiThread { tvFeedStatus.text = progressMsg }
                }

                if (result.isSuccess) {
                    val poseFiles = result.getOrDefault(emptyList())
                    for (pf in poseFiles) {
                        ShizukuBridge.deployLocalFileSync(pf, pf.name)
                    }
                    ShizukuBridge.state.mode = "photo"
                    ShizukuBridge.state.assetVersion = System.currentTimeMillis()
                    ShizukuBridge.pushControlState()

                    withContext(Dispatchers.Main) {
                        reloadLocalPreviewBank()
                        setAiButtonsEnabled(true)
                        tvFeedStatus.text = "AI 3D Poses Ready! D-Pad & Auto-Move now blend neural poses."
                        Toast.makeText(this@MainActivity, "All 6 AI Neural Poses Deployed!", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Unknown error"
                    withContext(Dispatchers.Main) {
                        setAiButtonsEnabled(true)
                        tvFeedStatus.text = "AI Pose Error: $err"
                    }
                }
            }
        }

        btnClearAiPoses.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                for (spec in ReplicatePoseClient.DEFAULT_POSES) {
                    File(filesDir, spec.fileName).delete()
                }
                ShizukuBridge.service?.clearAiPoses(ShizukuBridge.activeTargetPackage)
                ShizukuBridge.state.mode = "photo"
                ShizukuBridge.state.assetVersion = System.currentTimeMillis()
                ShizukuBridge.pushControlState()
                withContext(Dispatchers.Main) {
                    reloadLocalPreviewBank()
                    tvFeedStatus.text = "Reset to Human-Motion Photo Mode."
                }
            }
        }

        btnSyncTarget.setOnClickListener {
            val svc = ShizukuBridge.service
            if (svc == null) {
                Toast.makeText(this, "Connect Shizuku first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pkg = ShizukuBridge.activeTargetPackage
            tvFeedStatus.text = "Syncing assets & controls to $pkg..."
            lifecycleScope.launch(Dispatchers.IO) {
                svc.writeControlState(ShizukuBridge.state.serialize(), pkg)
                val res = svc.prepareAndRestartPackage(pkg)
                withContext(Dispatchers.Main) {
                    tvFeedStatus.text = if (res == "OK") {
                        "Synced to $pkg! Launch Float Menu, then open $pkg."
                    } else {
                        "Sync warning: $res"
                    }
                    Toast.makeText(this@MainActivity, tvFeedStatus.text, Toast.LENGTH_LONG).show()
                }
            }
        }

        btnOpenLSPatch.setOnClickListener {
            val launchIntent = packageManager.getLaunchIntentForPackage("org.lsposed.lspatch")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "LSPatch Manager not installed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setAiButtonsEnabled(enabled: Boolean) {
        btnLivePortraitAnimate.isEnabled = enabled
        btnGenerateAiPoses.isEnabled = enabled
    }

    private fun handleLivePortraitWithDrivingVideo(drivingUri: Uri) {
        val token = etReplicateToken.text.toString().trim()
        val localPhoto = File(filesDir, "vcam_photo.jpg")
        setAiButtonsEnabled(false)
        tvFeedStatus.text = "Preparing driving video for LivePortrait AI..."

        lifecycleScope.launch(Dispatchers.IO) {
            val drivingCache = File(cacheDir, "driving_input.mp4")
            val outputVideo = File(filesDir, "liveportrait_output.mp4")
            try {
                contentResolver.openInputStream(drivingUri)?.use { input ->
                    FileOutputStream(drivingCache).use { output ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                } ?: throw IllegalStateException("Cannot read driving video")

                val res = ReplicatePoseClient.generateLivePortraitVideo(
                    apiToken = token,
                    sourcePhotoFile = localPhoto,
                    drivingVideoFile = drivingCache,
                    outVideoFile = outputVideo
                ) { statusMsg ->
                    runOnUiThread { tvFeedStatus.text = statusMsg }
                }

                drivingCache.delete()

                if (res.isSuccess) {
                    runOnUiThread { tvFeedStatus.text = "Deploying photorealistic video via Shizuku..." }
                    val deployRes = ShizukuBridge.deployLocalFileSync(outputVideo, "vcam_feed.mp4")
                    if (deployRes == "OK") {
                        ShizukuBridge.state.mode = "video"
                        ShizukuBridge.state.enabled = true
                        ShizukuBridge.state.assetVersion = System.currentTimeMillis()
                        ShizukuBridge.pushControlState()
                        withContext(Dispatchers.Main) {
                            setAiButtonsEnabled(true)
                            syncUiFromState()
                            tvFeedStatus.text = "LivePortrait Video LIVE! Tap Sync to Target App & open Brave."
                            Toast.makeText(this@MainActivity, "Photorealistic Human Motion Deployed!", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            setAiButtonsEnabled(true)
                            tvFeedStatus.text = "Shizuku deploy error: $deployRes"
                        }
                    }
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Unknown error"
                    withContext(Dispatchers.Main) {
                        setAiButtonsEnabled(true)
                        tvFeedStatus.text = "LivePortrait Error: $err"
                    }
                }
            } catch (e: Exception) {
                drivingCache.delete()
                withContext(Dispatchers.Main) {
                    setAiButtonsEnabled(true)
                    tvFeedStatus.text = "LivePortrait Error: ${e.message}"
                }
            }
        }
    }

    private fun handlePickedPhoto(uri: Uri) {
        tvFeedStatus.text = "Processing face photo & detecting eyes..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open photo")
                val rawBmp = inputStream.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException("Invalid image format")

                val scale = minOf(640f / rawBmp.width, 640f / rawBmp.height, 1f)
                val tw = ((rawBmp.width * scale).toInt() and 1.inv()).coerceAtLeast(128)
                val th = ((rawBmp.height * scale).toInt() and 1.inv()).coerceAtLeast(128)
                val cleanBmp = if (tw == rawBmp.width && th == rawBmp.height) {
                    rawBmp
                } else {
                    Bitmap.createScaledBitmap(rawBmp, tw, th, true).also { rawBmp.recycle() }
                }

                detectFaceLandmarks(cleanBmp)

                val localPhoto = File(filesDir, "vcam_photo.jpg")
                FileOutputStream(localPhoto).use { out ->
                    cleanBmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                cleanBmp.recycle()

                for (spec in ReplicatePoseClient.DEFAULT_POSES) {
                    File(filesDir, spec.fileName).delete()
                }
                ShizukuBridge.service?.clearAiPoses(ShizukuBridge.activeTargetPackage)

                val res = ShizukuBridge.deployLocalFileSync(localPhoto, "vcam_photo.jpg")
                if (res == "OK") {
                    ShizukuBridge.state.mode = "photo"
                    ShizukuBridge.state.enabled = true
                    ShizukuBridge.state.yaw = 0f
                    ShizukuBridge.state.pitch = 0f
                    ShizukuBridge.state.assetVersion = System.currentTimeMillis()
                    ShizukuBridge.pushControlState()
                }

                withContext(Dispatchers.Main) {
                    if (res == "OK") {
                        reloadLocalPreviewBank()
                        syncUiFromState()
                        tvFeedStatus.text = "Face Photo Deployed! Use Auto-Move, D-Pad, or LivePortrait AI below."
                        Toast.makeText(this@MainActivity, "Face Photo Live & Ready!", Toast.LENGTH_SHORT).show()
                    } else {
                        tvFeedStatus.text = "Deploy failed: $res"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvFeedStatus.text = "Photo error: ${e.message}"
                }
            }
        }
    }

    private fun detectFaceLandmarks(bmp: Bitmap) {
        try {
            val rgb565 = bmp.copy(Bitmap.Config.RGB_565, false)
            val detector = FaceDetector(rgb565.width, rgb565.height, 1)
            val faces = arrayOfNulls<FaceDetector.Face>(1)
            val found = detector.findFaces(rgb565, faces)
            val w = rgb565.width.toFloat()
            val h = rgb565.height.toFloat()
            rgb565.recycle()

            if (found > 0 && faces[0] != null) {
                val face = faces[0]!!
                val mid = PointF()
                face.getMidPoint(mid)
                val eyeDist = face.eyesDistance()

                ShizukuBridge.state.eyeLx = ((mid.x - eyeDist * 0.5f) / w).coerceIn(0.15f, 0.85f)
                ShizukuBridge.state.eyeLy = (mid.y / h).coerceIn(0.15f, 0.85f)
                ShizukuBridge.state.eyeRx = ((mid.x + eyeDist * 0.5f) / w).coerceIn(0.15f, 0.85f)
                ShizukuBridge.state.eyeRy = (mid.y / h).coerceIn(0.15f, 0.85f)
                ShizukuBridge.state.faceCx = (mid.x / w).coerceIn(0.20f, 0.80f)
                ShizukuBridge.state.faceCy = ((mid.y + eyeDist * 0.35f) / h).coerceIn(0.20f, 0.80f)
                ShizukuBridge.state.faceR = ((eyeDist * 1.15f) / minOf(w, h)).coerceIn(0.12f, 0.40f)
            }
        } catch (_: Throwable) {
        }
    }

    private fun handlePickedVideo(uri: Uri) {
        tvFeedStatus.text = "Deploying MP4 video via Shizuku..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val svc = ShizukuBridge.service ?: throw IllegalStateException("Shizuku disconnected")
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Cannot open video URI")
                val res = pfd.use { svc.deployNamedFileFd(it, "vcam_feed.mp4", ShizukuBridge.activeTargetPackage) }
                if (res == "OK") {
                    ShizukuBridge.state.mode = "video"
                    ShizukuBridge.state.enabled = true
                    ShizukuBridge.state.assetVersion = System.currentTimeMillis()
                    ShizukuBridge.pushControlState()
                }
                withContext(Dispatchers.Main) {
                    syncUiFromState()
                    tvFeedStatus.text = if (res == "OK") {
                        "MP4 Video Deployed! Tap Sync to Target App."
                    } else {
                        "Video deploy failed: $res"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvFeedStatus.text = "Video error: ${e.message}"
                }
            }
        }
    }

    private fun reloadLocalPreviewBank() {
        val localPhoto = File(filesDir, "vcam_photo.jpg")
        if (!localPhoto.exists()) {
            previewBank = null
            tvPreviewOverlayHint.visibility = View.VISIBLE
            return
        }
        val bank = FaceWarpEngine.loadPoseBank(
            basePath = localPhoto.absolutePath,
            poseDir = filesDir,
            maxDim = 240,
            includeArgb = true
        )
        previewBank = bank
        if (bank != null) {
            previewBitmap = Bitmap.createBitmap(bank.width, bank.height, Bitmap.Config.ARGB_8888)
            previewPixels = IntArray(bank.width * bank.height)
            tvPreviewOverlayHint.visibility = View.GONE
        }
    }

    private fun renderPreviewFrame() {
        val bank = previewBank ?: return
        val bmp = previewBitmap ?: return
        val px = previewPixels ?: return
        previewAnimator.renderPreviewPixels(bank, ShizukuBridge.state, System.currentTimeMillis(), px)
        bmp.setPixels(px, 0, bank.width, 0, 0, bank.width, bank.height)
        ivLivePreview.setImageBitmap(bmp)
    }

    private fun syncUiFromState() {
        val st = ShizukuBridge.state
        isSyncingUi = true
        switchEnableVCam.isChecked = st.enabled
        tvZoomValue.text = String.format("%.1fx", st.zoom)
        btnAutoMove.text = if (st.autoSweep) "Auto-Move: ON" else "Auto-Move: OFF"
        btnAutoMove.backgroundTintList = ColorStateList.valueOf(
            if (st.autoSweep) Color.parseColor("#1DB954") else Color.parseColor("#082418")
        )
        btnAutoMove.setTextColor(
            if (st.autoSweep) Color.parseColor("#041F13") else Color.parseColor("#2EE87A")
        )
        btnIdleToggle.text = if (st.autoIdle) "Idle: ON" else "Idle: OFF"
        btnSmile.text = if (st.smile > 0.3f) "Smile: ON" else "Smile"
        val presetIdx = flipPresets.indexOfFirst { (_, triple) ->
            triple.first == st.rotation && triple.second == st.flipH && triple.third == st.flipV
        }
        if (presetIdx >= 0) spinnerFlip.setSelection(presetIdx)
        isSyncingUi = false
    }

    private fun checkAndBindShizuku() {
        if (!Shizuku.pingBinder()) {
            tvShizukuBadge.text = "● Start Shizuku App"
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuBridge.bind()
        } else {
            tvShizukuBadge.text = "● Requesting Shizuku..."
            Shizuku.requestPermission(1001)
        }
    }

    override fun onResume() {
        super.onResume()
        isPreviewTicking = true
        mainHandler.post(previewTicker)
        if (ShizukuBridge.isBound) {
            tvShizukuBadge.text = "● Shizuku Connected"
        }
    }

    override fun onPause() {
        super.onPause()
        isPreviewTicking = false
        mainHandler.removeCallbacks(previewTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        ShizukuBridge.removeStatusListener(shizukuStatusListener)
        ShizukuBridge.removeStateListener(stateChangedListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }
}
