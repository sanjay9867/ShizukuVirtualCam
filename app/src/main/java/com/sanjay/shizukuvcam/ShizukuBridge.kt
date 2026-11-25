package com.sanjay.shizukuvcam

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.sanjay.shizukuvcam.service.ShellUserService
import com.sanjay.shizukuvcam.xposed.ControlState
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object ShizukuBridge {
    @Volatile
    var service: IShellService? = null
        private set

    @Volatile
    var isBound: Boolean = false
        private set

    @Volatile
    var activeTargetPackage: String = "com.brave.browser"

    @Volatile
    var gyro6DEnabled: Boolean = false

    val state: ControlState = ControlState()

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val pendingPayload = AtomicReference<String?>(null)
    private val isFlushing = AtomicBoolean(false)
    private val statusListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<() -> Unit>()

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShellUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IShellService.Stub.asInterface(binder)
                isBound = true
                loadStateFromShizuku()
                notifyStatus(true)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            notifyStatus(false)
        }
    }

    fun addStatusListener(listener: (Boolean) -> Unit) {
        statusListeners.addIfAbsent(listener)
    }

    fun removeStatusListener(listener: (Boolean) -> Unit) {
        statusListeners.remove(listener)
    }

    fun addStateListener(listener: () -> Unit) {
        stateListeners.addIfAbsent(listener)
    }

    fun removeStateListener(listener: () -> Unit) {
        stateListeners.remove(listener)
    }

    private fun notifyStatus(connected: Boolean) {
        for (l in statusListeners) {
            try { l(connected) } catch (_: Throwable) {}
        }
    }

    fun notifyStateChanged() {
        for (l in stateListeners) {
            try { l() } catch (_: Throwable) {}
        }
    }

    fun bind() {
        if (!isBound) {
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (_: Throwable) {
            }
        }
    }

    fun markDisconnected() {
        service = null
        isBound = false
        notifyStatus(false)
    }

    private fun loadStateFromShizuku() {
        ioExecutor.execute {
            try {
                val raw = service?.readControlState().orEmpty()
                if (raw.isNotBlank()) {
                    state.copyFrom(ControlState.parse(raw))
                    notifyStateChanged()
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun pushControlState() {
        pendingPayload.set(state.serialize())
        notifyStateChanged()
        if (isFlushing.compareAndSet(false, true)) {
            ioExecutor.execute {
                try {
                    while (true) {
                        val payload = pendingPayload.getAndSet(null) ?: break
                        try {
                            service?.writeControlState(payload, activeTargetPackage)
                        } catch (_: Throwable) {
                        }
                    }
                } finally {
                    isFlushing.set(false)
                    if (pendingPayload.get() != null) {
                        pushControlState()
                    }
                }
            }
        }
    }

    fun deployLocalFileSync(localFile: File, targetName: String): String {
        val svc = service ?: return "ERR: Shizuku not connected"
        return try {
            val pfd = ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use { svc.deployNamedFileFd(it, targetName, activeTargetPackage) }
        } catch (e: Exception) {
            "ERR: ${e.message}"
        }
    }
}
