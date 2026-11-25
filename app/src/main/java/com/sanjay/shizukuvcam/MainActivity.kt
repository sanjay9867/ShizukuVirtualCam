package com.sanjay.shizukuvcam

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.sanjay.shizukuvcam.service.FloatingControlService
import com.sanjay.shizukuvcam.ui.FaceDpadView
import com.sanjay.shizukuvcam.xposed.FacePhotoAsset
import com.sanjay.shizukuvcam.xposed.FaceWarpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var tvShizukuBadge: TextView
    private lateinit var ivLivePreview: ImageView
    private lateinit var tvPreviewOverlayHint: TextView
    private lateinit var tv6DReadout: TextView
    private lateinit var spinnerFlip: Spinner
    private lateinit var btnZoomMinus: Button
    private lateinit var btnZoomPlus: Button
    private lateinit var tvZoomValue: TextView
    private lateinit var faceDpad: FaceDpadView
    private lateinit var btnPadMode: Button
    private lateinit var btnAutoMove: Button
    private lateinit var btnGyro6D: Button
    private lateinit var btnBlink: Button
    private lateinit var btnSmile: Button
    private lateinit var btnIdleToggle: Button
    private lateinit var seekRoll: SeekBar
    private lateinit var seekDepthZ: SeekBar
    private lateinit var btnReset6D: Button
    private lateinit var btnFloatOverlay: Button
    private lateinit var btnUploadPhoto: Button
    private lateinit var btnUploadVideo: Button
    private lateinit var switchEnableVCam: MaterialSwitch
    private lateinit var tvFeedStatus: TextView
    private lateinit var spinnerApps: Spinner
    private lateinit var btnSyncTarget: Button
    private lateinit var btnOpenLSPatch: Button
    private lateinit var btnReconnectShizuku: Button

    private var isSyncingUi = false
    private var dpadTranslateMode = false
    private var targetApps: List<Pair<String, String>> = emptyList()

    private var sensorManager: SensorManager? = null
    private var accelSensor: Sensor? = null
    private var gyroCalibrated = false
    private var baseGx = 0f
    private var baseGy = 0f
    private var baseGz = 0f
    private var lastSensorPushMs = 0L

    private val flipPresets = listOf(
        "270° Front Cam" to Triple(270, false, false),
        "270° + Mirror" to Triple(270, true, false),
        "0° Normal" to Triple(0, false, false),
        "0° Mirror H" to Triple(0, true, false),
        "90° Clockwise" to Triple(90, false, false),
        "180° Flip V" to Triple(180, false, true)
    )

    private var previewAsset: FacePhotoAsset? = null
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
            if (connected) syncUiFromState()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        bindViews()
        setupSpinners()
        setupManual6DControls()
        setupSourceAndSyncControls()

        ShizukuBridge.addStatusListener(shizukuStatusListener)
        ShizukuBridge.addStateListener(stateChangedListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        reloadLocalPreviewAsset()
        syncUiFromState()
        checkAndBindShizuku()
    }

    private fun bindViews() {
        tvShizukuBadge = findViewById(R.id.tvShizukuBadge)
        ivLivePreview = findViewById(R.id.ivLivePreview)
        tvPreviewOverlayHint = findViewById(R.id.tvPreviewOverlayHint)
        tv6DReadout = findViewById(R.id.tv6DReadout)
        spinnerFlip = findViewById(R.id.spinnerFlip)
        btnZoomMinus = findViewById(R.id.btnZoomMinus)
        btnZoomPlus = findViewById(R.id.btnZoomPlus)
        tvZoomValue = findViewById(R.id.tvZoomValue)
        faceDpad = findViewById(R.id.faceDpad)
        btnPadMode = findViewById(R.id.btnPadMode)
        btnAutoMove = findViewById(R.id.btnAutoMove)
        btnGyro6D = findViewById(R.id.btnGyro6D)
        btnBlink = findViewById(R.id.btnBlink)
        btnSmile = findViewById(R.id.btnSmile)
        btnIdleToggle = findViewById(R.id.btnIdleToggle)
        seekRoll = findViewById(R.id.seekRoll)
        seekDepthZ = findViewById(R.id.seekDepthZ)
        btnReset6D = findViewById(R.id.btnReset6D)
        btnFloatOverlay = findViewById(R.id.btnFloatOverlay)
        btnUploadPhoto = findViewById(R.id.btnUploadPhoto)
        btnUploadVideo = findViewById(R.id.btnUploadVideo)
        switchEnableVCam = findViewById(R.id.switchEnableVCam)
        tvFeedStatus = findViewById(R.id.tvFeedStatus)
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

    private fun setupManual6DControls() {
        btnZoomMinus.setOnClickListener {
            ShizukuBridge.state.zoom = (ShizukuBridge.state.zoom - 0.1f).coerceIn(0.6f, 2.2f)
            ShizukuBridge.pushControlState()
        }

        btnZoomPlus.setOnClickListener {
            ShizukuBridge.state.zoom = (ShizukuBridge.state.zoom + 0.1f).coerceIn(0.6f, 2.2f)
            ShizukuBridge.pushControlState()
        }

        btnPadMode.setOnClickListener {
            dpadTranslateMode = !dpadTranslateMode
            syncUiFromState()
        }

        faceDpad.onDirectionCommand = { zone, ax, ay ->
            ShizukuBridge.state.autoSweep = false
            if (ShizukuBridge.gyro6DEnabled) {
                ShizukuBridge.gyro6DEnabled = false
                sensorManager?.unregisterListener(this)
            }
            when (zone) {
                FaceDpadView.Zone.CENTER -> ShizukuBridge.state.reset6D()
                else -> {
                    if (dpadTranslateMode) {
                        ShizukuBridge.state.tx = ax
                        ShizukuBridge.state.ty = ay
                    } else {
                        ShizukuBridge.state.yaw = ax
                        ShizukuBridge.state.pitch = ay
                    }
                }
            }
            ShizukuBridge.pushControlState()
        }

        seekRoll.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isSyncingUi) return
                ShizukuBridge.state.autoSweep = false
                ShizukuBridge.state.roll = (progress - 100) / 100f
                ShizukuBridge.pushControlState()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekDepthZ.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isSyncingUi) return
                ShizukuBridge.state.autoSweep = false
                ShizukuBridge.state.tz = (progress - 100) / 100f
                ShizukuBridge.pushControlState()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnReset6D.setOnClickListener {
            ShizukuBridge.state.autoSweep = false
            ShizukuBridge.gyro6DEnabled = false
            sensorManager?.unregisterListener(this)
            ShizukuBridge.state.reset6D()
            ShizukuBridge.pushControlState()
        }

        btnAutoMove.setOnClickListener {
            val next = !ShizukuBridge.state.autoSweep
            if (next && ShizukuBridge.gyro6DEnabled) {
                ShizukuBridge.gyro6DEnabled = false
                sensorManager?.unregisterListener(this)
            }
            ShizukuBridge.state.autoSweep = next
            if (!next) ShizukuBridge.state.reset6D()
            ShizukuBridge.pushControlState()
        }

        btnGyro6D.setOnClickListener {
            val next = !ShizukuBridge.gyro6DEnabled
            ShizukuBridge.gyro6DEnabled = next
            if (next) {
                ShizukuBridge.state.autoSweep = false
                gyroCalibrated = false
                accelSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                Toast.makeText(this, "Tilt phone gently to steer 6D head pose!", Toast.LENGTH_SHORT).show()
            } else {
                sensorManager?.unregisterListener(this)
                ShizukuBridge.state.reset6D()
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
                Toast.makeText(this, "6D Floating Menu active! Switch to Brave now.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSourceAndSyncControls() {
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
                if (isChecked) "Offline 6D Virtual Camera ENABLED" else "Virtual Camera DISABLED",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnSyncTarget.setOnClickListener {
            val svc = ShizukuBridge.service
            if (svc == null) {
                Toast.makeText(this, "Connect Shizuku first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pkg = ShizukuBridge.activeTargetPackage
            tvFeedStatus.text = "Syncing photo & 6D controls to $pkg..."
            lifecycleScope.launch(Dispatchers.IO) {
                svc.writeControlState(ShizukuBridge.state.serialize(), pkg)
                val res = svc.prepareAndRestartPackage(pkg)
                withContext(Dispatchers.Main) {
                    tvFeedStatus.text = if (res == "OK") {
                        "Synced to $pkg! Launch 6D Float Menu, then open $pkg."
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

    override fun onSensorChanged(event: SensorEvent) {
        if (!ShizukuBridge.gyro6DEnabled) return
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]
        if (!gyroCalibrated) {
            baseGx = gx; baseGy = gy; baseGz = gz
            gyroCalibrated = true
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSensorPushMs < 50L) return
        lastSensorPushMs = now

        val dx = gx - baseGx
        val dy = gy - baseGy
        val dz = gz - baseGz

        ShizukuBridge.state.yaw = (-dx / 4.2f).coerceIn(-1f, 1f)
        ShizukuBridge.state.pitch = (-dy / 4.2f).coerceIn(-1f, 1f)
        ShizukuBridge.state.roll = (dx / 6.5f).coerceIn(-1f, 1f)
        ShizukuBridge.state.tx = (-dx / 8.0f).coerceIn(-0.6f, 0.6f)
        ShizukuBridge.state.ty = (-dy / 8.0f).coerceIn(-0.6f, 0.6f)
        ShizukuBridge.state.tz = (dz / 5.5f).coerceIn(-0.7f, 0.7f)
        ShizukuBridge.pushControlState()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun handlePickedPhoto(uri: Uri) {
        tvFeedStatus.text = "Building offline 3D face depth relief..."
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

                val res = ShizukuBridge.deployLocalFileSync(localPhoto, "vcam_photo.jpg")
                if (res == "OK") {
                    ShizukuBridge.state.mode = "photo"
                    ShizukuBridge.state.enabled = true
                    ShizukuBridge.state.reset6D()
                    ShizukuBridge.state.assetVersion = System.currentTimeMillis()
                    ShizukuBridge.pushControlState()
                }

                withContext(Dispatchers.Main) {
                    if (res == "OK") {
                        reloadLocalPreviewAsset()
                        syncUiFromState()
                        tvFeedStatus.text = "Offline 6D Face Ready! Test D-Pad/Gyro or tap Sync for Brave."
                        Toast.makeText(this@MainActivity, "Offline 6D Face Ready!", Toast.LENGTH_SHORT).show()
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

    private fun reloadLocalPreviewAsset() {
        val localPhoto = File(filesDir, "vcam_photo.jpg")
        if (!localPhoto.exists()) {
            previewAsset = null
            tvPreviewOverlayHint.visibility = View.VISIBLE
            return
        }
        val asset = FaceWarpEngine.loadPhotoAsset(
            basePath = localPhoto.absolutePath,
            maxDim = 240,
            includeArgb = true
        )
        previewAsset = asset
        if (asset != null) {
            previewBitmap = Bitmap.createBitmap(asset.width, asset.height, Bitmap.Config.ARGB_8888)
            previewPixels = IntArray(asset.width * asset.height)
            tvPreviewOverlayHint.visibility = View.GONE
        }
    }

    private fun renderPreviewFrame() {
        val asset = previewAsset ?: return
        val bmp = previewBitmap ?: return
        val px = previewPixels ?: return
        previewAnimator.renderPreviewPixels(asset, ShizukuBridge.state, System.currentTimeMillis(), px)
        bmp.setPixels(px, 0, asset.width, 0, 0, asset.width, asset.height)
        ivLivePreview.setImageBitmap(bmp)
    }

    private fun styleToggle(btn: Button, active: Boolean, onText: String, offText: String) {
        btn.text = if (active) onText else offText
        btn.backgroundTintList = ColorStateList.valueOf(
            if (active) Color.parseColor("#1DB954") else Color.parseColor("#082418")
        )
        btn.setTextColor(
            if (active) Color.parseColor("#041F13") else Color.parseColor("#2EE87A")
        )
    }

    private fun syncUiFromState() {
        val st = ShizukuBridge.state
        isSyncingUi = true
        switchEnableVCam.isChecked = st.enabled
        tvZoomValue.text = String.format("%.1fx", st.zoom)
        tv6DReadout.text = String.format(
            "6D  Y:%.0f°  P:%.0f°  R:%.0f°  |  X:%.2f  Y:%.2f  Z:%.2f",
            st.yaw * 19.5f, st.pitch * 16f, st.roll * 17f, st.tx, st.ty, st.tz
        )
        seekRoll.progress = ((st.roll + 1f) * 100f).toInt().coerceIn(0, 200)
        seekDepthZ.progress = ((st.tz + 1f) * 100f).toInt().coerceIn(0, 200)

        styleToggle(btnPadMode, dpadTranslateMode, "Pad: Move X/Y", "Pad: Rotate 3D")
        styleToggle(btnAutoMove, st.autoSweep, "6D Auto: ON", "6D Auto: OFF")
        styleToggle(btnGyro6D, ShizukuBridge.gyro6DEnabled, "Gyro 6D: ON", "Gyro 6D: OFF")
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
        if (ShizukuBridge.gyro6DEnabled) {
            accelSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        if (ShizukuBridge.isBound) {
            tvShizukuBadge.text = "● Shizuku Connected"
        }
    }

    override fun onPause() {
        super.onPause()
        isPreviewTicking = false
        mainHandler.removeCallbacks(previewTicker)
        sensorManager?.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        ShizukuBridge.removeStatusListener(shizukuStatusListener)
        ShizukuBridge.removeStateListener(stateChangedListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }
}
