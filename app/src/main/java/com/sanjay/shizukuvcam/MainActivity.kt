package com.sanjay.shizukuvcam

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
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

class MainActivity : AppCompatActivity() {

    private var shellService: IShellService? = null
    private var isServiceBound = false
    private var isSyncingUi = false

    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvVideoStatus: TextView
    private lateinit var btnGrantShizuku: Button
    private lateinit var btnPickVideo: Button
    private lateinit var switchEnableVCam: MaterialSwitch
    private lateinit var spinnerApps: Spinner
    private lateinit var btnForceStopApp: Button
    private lateinit var btnOpenLSPatch: Button

    private var targetApps: List<Pair<String, String>> = emptyList()

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

        loadInstalledTargetApps()

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
            if (isSyncingUi) return@setOnCheckedChangeListener
            val svc = shellService
            if (svc == null) {
                isSyncingUi = true
                switchEnableVCam.isChecked = false
                isSyncingUi = false
                Toast.makeText(this, "Connect Shizuku first!", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                if (isChecked && !svc.hasDeployedVideo()) {
                    withContext(Dispatchers.Main) {
                        isSyncingUi = true
                        switchEnableVCam.isChecked = false
                        isSyncingUi = false
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
            val pos = spinnerApps.selectedItemPosition
            if (pos < 0 || pos >= targetApps.size) return@setOnClickListener
            val selectedPkg = targetApps[pos].second
            lifecycleScope.launch(Dispatchers.IO) {
                val res = svc.prepareAndRestartPackage(selectedPkg)
                withContext(Dispatchers.Main) {
                    val msg = if (res == "OK") {
                        "Synced video & force-stopped $selectedPkg"
                    } else {
                        "Restarted $selectedPkg ($res)"
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
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
                    "LSPatch not installed. Install JingMatrix LSPatch to patch target apps.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadInstalledTargetApps() {
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
                "WhatsApp (com.whatsapp)" to "com.whatsapp",
                "Instagram (com.instagram.android)" to "com.instagram.android",
                "Telegram (org.telegram.messenger)" to "org.telegram.messenger",
                "Google Chrome (com.android.chrome)" to "com.android.chrome"
            )
        }

        spinnerApps.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            targetApps.map { it.first }
        )
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
                    "Feed Status: Ready (Video deployed via Shizuku)"
                } else {
                    "Feed Status: No video deployed yet"
                }
                isSyncingUi = true
                switchEnableVCam.isChecked = enabled
                isSyncingUi = false
            }
        }
    }

    private fun deploySelectedVideo(uri: Uri) {
        val svc = shellService ?: return
        tvVideoStatus.text = "Feed Status: Streaming video to Shizuku..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                if (pfd == null) {
                    withContext(Dispatchers.Main) {
                        tvVideoStatus.text = "Feed Status: Failed (Cannot open video URI)"
                    }
                    return@launch
                }
                val result = pfd.use { descriptor ->
                    svc.deployVideoFd(descriptor)
                }

                withContext(Dispatchers.Main) {
                    if (result == "OK") {
                        tvVideoStatus.text = "Feed Status: Deployed! Now pick target app & tap Sync/Force-Stop."
                        Toast.makeText(
                            this@MainActivity,
                            "Video deployed via Shizuku!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        tvVideoStatus.text = "Feed Status: Failed ($result)"
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
