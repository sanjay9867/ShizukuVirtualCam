package com.sanjay.shizukuvcam

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.sanjay.shizukuvcam.service.ShellUserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private var shellService: IShellService? = null
    private var isServiceBound = false

    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvVideoStatus: TextView
    private lateinit var btnGrantShizuku: Button
    private lateinit var btnPickVideo: Button
    private lateinit var switchEnableVCam: MaterialSwitch
    private lateinit var spinnerApps: Spinner
    private lateinit var btnForceStopApp: Button
    private lateinit var btnOpenLSPatch: Button

    private val targetApps = listOf(
        "WhatsApp (com.whatsapp)" to "com.whatsapp",
        "Instagram (com.instagram.android)" to "com.instagram.android",
        "Telegram (org.telegram.messenger)" to "org.telegram.messenger",
        "Google Chrome (com.android.chrome)" to "com.android.chrome",
        "Snapchat (com.snapchat.android)" to "com.snapchat.android",
        "Google Meet (com.google.android.apps.tachyon)" to "com.google.android.apps.tachyon"
    )

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShellUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                shellService = IShellService.Stub.asInterface(binder)
                isServiceBound = true
                runOnUiThread {
                    tvShizukuStatus.text = "Shizuku Status: Connected (UID 2000 Privileged Shell)"
                    syncStateFromShizuku()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            isServiceBound = false
            runOnUiThread {
                tvShizukuStatus.text = "Shizuku Status: Disconnected"
            }
        }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 1001) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindShizukuShellService()
                } else {
                    tvShizukuStatus.text = "Shizuku Status: Permission Denied"
                }
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkAndBindShizuku()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellService = null
        isServiceBound = false
        tvShizukuStatus.text = "Shizuku Status: Service Stopped"
    }

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                deploySelectedVideo(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        tvVideoStatus = findViewById(R.id.tvVideoStatus)
        btnGrantShizuku = findViewById(R.id.btnGrantShizuku)
        btnPickVideo = findViewById(R.id.btnPickVideo)
        switchEnableVCam = findViewById(R.id.switchEnableVCam)
        spinnerApps = findViewById(R.id.spinnerApps)
        btnForceStopApp = findViewById(R.id.btnForceStopApp)
        btnOpenLSPatch = findViewById(R.id.btnOpenLSPatch)

        spinnerApps.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            targetApps.map { it.first }
        )

        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        btnGrantShizuku.setOnClickListener {
            checkAndBindShizuku()
        }

        btnPickVideo.setOnClickListener {
            if (shellService == null) {
                Toast.makeText(this, "Connect Shizuku first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickVideoLauncher.launch("video/*")
        }

        switchEnableVCam.setOnCheckedChangeListener { _, isChecked ->
            val svc = shellService
            if (svc == null) {
                switchEnableVCam.isChecked = false
                Toast.makeText(this, "Connect Shizuku first!", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                if (isChecked && !svc.hasDeployedVideo()) {
                    withContext(Dispatchers.Main) {
                        switchEnableVCam.isChecked = false
                        Toast.makeText(
                            this@MainActivity,
                            "Select an MP4 video first!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                svc.setVirtualCamEnabled(isChecked)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        if (isChecked) "Virtual Camera ENABLED" else "Virtual Camera DISABLED",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        btnForceStopApp.setOnClickListener {
            val svc = shellService
            if (svc == null) {
                Toast.makeText(this, "Connect Shizuku first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedPkg = targetApps[spinnerApps.selectedItemPosition].second
            lifecycleScope.launch(Dispatchers.IO) {
                svc.forceStopPackage(selectedPkg)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Force-stopped $selectedPkg via Shizuku",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        btnOpenLSPatch.setOnClickListener {
            val launchIntent = packageManager.getLaunchIntentForPackage("org.lsposed.lspatch")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(
                    this,
                    "LSPatch not installed. Install LSPatch to embed this module into target apps.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkAndBindShizuku() {
        if (!Shizuku.pingBinder()) {
            tvShizukuStatus.text = "Shizuku Status: Not Running (Open Shizuku app & Start)"
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindShizukuShellService()
        } else {
            tvShizukuStatus.text = "Shizuku Status: Requesting Permission..."
            Shizuku.requestPermission(1001)
        }
    }

    private fun bindShizukuShellService() {
        if (!isServiceBound) {
            try {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            } catch (e: Exception) {
                tvShizukuStatus.text = "Shizuku Bind Error: ${e.message}"
            }
        }
    }

    private fun syncStateFromShizuku() {
        val svc = shellService ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val hasVideo = svc.hasDeployedVideo()
            val enabled = svc.isVirtualCamEnabled()
            withContext(Dispatchers.Main) {
                tvVideoStatus.text = if (hasVideo) {
                    "Feed Status: Ready (/data/local/tmp/vcam_feed.mp4)"
                } else {
                    "Feed Status: No video deployed yet"
                }
                switchEnableVCam.isChecked = enabled
            }
        }
    }

    private fun deploySelectedVideo(uri: Uri) {
        val svc = shellService ?: return
        tvVideoStatus.text = "Feed Status: Copying video via Shizuku..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempCacheFile = File(cacheDir, "upload_temp.mp4")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempCacheFile).use { output ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                }
                val success = svc.deployVideoFeed(tempCacheFile.absolutePath)
                tempCacheFile.delete()

                withContext(Dispatchers.Main) {
                    if (success) {
                        tvVideoStatus.text = "Feed Status: Deployed & Readable by All Apps!"
                        Toast.makeText(
                            this@MainActivity,
                            "Video deployed via Shizuku!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        tvVideoStatus.text = "Feed Status: Failed to deploy video"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvVideoStatus.text = "Feed Status: Error - ${e.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        if (isServiceBound) {
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            } catch (_: Exception) {
            }
        }
    }
}
