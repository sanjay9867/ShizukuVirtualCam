package com.sanjay.shizukuvcam.service

import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.sanjay.shizukuvcam.R
import com.sanjay.shizukuvcam.ShizukuBridge
import com.sanjay.shizukuvcam.ui.FaceDpadView
import kotlin.math.roundToInt

class FloatingControlService : Service(), SensorEventListener {

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var isCollapsed = false
    private var dpadTranslateMode = false // false = Rotate (Yaw/Pitch), true = Move (X/Y)

    private var btnAutoMoveRef: Button? = null
    private var btnGyroRef: Button? = null
    private var btnAxisModeRef: Button? = null

    private var sensorManager: SensorManager? = null
    private var accelSensor: Sensor? = null
    private var gyroCalibrated = false
    private var baseGx = 0f
    private var baseGy = 0f
    private var baseGz = 0f
    private var lastSensorPushMs = 0L

    private val stateObserver: () -> Unit = {
        btnAutoMoveRef?.post { updateToggleButtonsUi() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        showFloatingPanel()
        ShizukuBridge.addStateListener(stateObserver)
        if (ShizukuBridge.gyro6DEnabled) {
            startGyroSensor()
        }
    }

    private fun startGyroSensor() {
        val sensor = accelSensor ?: return
        gyroCalibrated = false
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopGyroSensor() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!ShizukuBridge.gyro6DEnabled) return
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]
        if (!gyroCalibrated) {
            baseGx = gx
            baseGy = gy
            baseGz = gz
            gyroCalibrated = true
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastSensorPushMs < 55L) return
        lastSensorPushMs = now

        val dx = gx - baseGx
        val dy = gy - baseGy
        val dz = gz - baseGz

        ShizukuBridge.state.autoSweep = false
        ShizukuBridge.state.yaw = (-dx / 4.2f).coerceIn(-1f, 1f)
        ShizukuBridge.state.pitch = (-dy / 4.2f).coerceIn(-1f, 1f)
        ShizukuBridge.state.roll = (dx / 6.5f).coerceIn(-1f, 1f)
        ShizukuBridge.state.tx = (-dx / 8.0f).coerceIn(-0.6f, 0.6f)
        ShizukuBridge.state.ty = (-dy / 8.0f).coerceIn(-0.6f, 0.6f)
        ShizukuBridge.state.tz = (dz / 5.5f).coerceIn(-0.7f, 0.7f)
        ShizukuBridge.pushControlState()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun styleToggle(btn: Button?, active: Boolean, onText: String, offText: String) {
        if (btn == null) return
        btn.text = if (active) onText else offText
        btn.backgroundTintList = ColorStateList.valueOf(
            if (active) Color.parseColor("#1DB954") else Color.parseColor("#082418")
        )
        btn.setTextColor(
            if (active) Color.parseColor("#041F13") else Color.parseColor("#2EE87A")
        )
    }

    private fun updateToggleButtonsUi() {
        styleToggle(btnAutoMoveRef, ShizukuBridge.state.autoSweep, "6D Auto: ON", "6D Auto")
        styleToggle(btnGyroRef, ShizukuBridge.gyro6DEnabled, "Gyro: ON", "Gyro 6D")
        styleToggle(btnAxisModeRef, dpadTranslateMode, "Pad: Move XY", "Pad: Rot 3D")
    }

    private fun showFloatingPanel() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_manual_controls, null)
        rootView = view

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 140
        }

        val headerDrag = view.findViewById<LinearLayout>(R.id.overlayHeader)
        val bodyContainer = view.findViewById<LinearLayout>(R.id.overlayBody)
        val btnCollapse = view.findViewById<TextView>(R.id.btnOverlayCollapse)
        val btnClose = view.findViewById<TextView>(R.id.btnOverlayClose)
        val dpad = view.findViewById<FaceDpadView>(R.id.overlayDpad)
        val btnRollLeft = view.findViewById<Button>(R.id.btnOverlayRollLeft)
        val btnRollRight = view.findViewById<Button>(R.id.btnOverlayRollRight)
        val btnDepthOut = view.findViewById<Button>(R.id.btnOverlayDepthOut)
        val btnDepthIn = view.findViewById<Button>(R.id.btnOverlayDepthIn)
        val btnAxisMode = view.findViewById<Button>(R.id.btnOverlayAxisMode)
        val btnFlip = view.findViewById<Button>(R.id.btnOverlayFlip)
        val btnBlink = view.findViewById<Button>(R.id.btnOverlayBlink)
        val btnAutoMove = view.findViewById<Button>(R.id.btnOverlayAutoMove)
        val btnGyro = view.findViewById<Button>(R.id.btnOverlayGyro)

        btnAutoMoveRef = btnAutoMove
        btnGyroRef = btnGyro
        btnAxisModeRef = btnAxisMode
        updateToggleButtonsUi()

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        headerDrag.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX - (event.rawX - touchX).roundToInt()
                    params.y = startY + (event.rawY - touchY).roundToInt()
                    wm.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        btnCollapse.setOnClickListener {
            isCollapsed = !isCollapsed
            bodyContainer.visibility = if (isCollapsed) View.GONE else View.VISIBLE
            btnCollapse.text = if (isCollapsed) "+" else "−"
        }

        btnClose.setOnClickListener {
            stopSelf()
        }

        dpad.onDirectionCommand = { zone, ax, ay ->
            ShizukuBridge.state.autoSweep = false
            if (ShizukuBridge.gyro6DEnabled) {
                ShizukuBridge.gyro6DEnabled = false
                stopGyroSensor()
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
            updateToggleButtonsUi()
            ShizukuBridge.pushControlState()
        }

        btnAxisMode.setOnClickListener {
            dpadTranslateMode = !dpadTranslateMode
            updateToggleButtonsUi()
        }

        btnRollLeft.setOnClickListener {
            ShizukuBridge.state.autoSweep = false
            ShizukuBridge.state.roll = (ShizukuBridge.state.roll - 0.25f).coerceIn(-1f, 1f)
            ShizukuBridge.pushControlState()
        }

        btnRollRight.setOnClickListener {
            ShizukuBridge.state.autoSweep = false
            ShizukuBridge.state.roll = (ShizukuBridge.state.roll + 0.25f).coerceIn(-1f, 1f)
            ShizukuBridge.pushControlState()
        }

        btnDepthOut.setOnClickListener {
            ShizukuBridge.state.tz = (ShizukuBridge.state.tz - 0.2f).coerceIn(-1f, 1f)
            ShizukuBridge.pushControlState()
        }

        btnDepthIn.setOnClickListener {
            ShizukuBridge.state.tz = (ShizukuBridge.state.tz + 0.2f).coerceIn(-1f, 1f)
            ShizukuBridge.pushControlState()
        }

        btnFlip.setOnClickListener {
            ShizukuBridge.state.flipH = !ShizukuBridge.state.flipH
            ShizukuBridge.pushControlState()
        }

        btnBlink.setOnClickListener {
            ShizukuBridge.state.blinkSeq = System.currentTimeMillis()
            ShizukuBridge.pushControlState()
        }

        btnAutoMove.setOnClickListener {
            val next = !ShizukuBridge.state.autoSweep
            if (next && ShizukuBridge.gyro6DEnabled) {
                ShizukuBridge.gyro6DEnabled = false
                stopGyroSensor()
            }
            ShizukuBridge.state.autoSweep = next
            if (!next) ShizukuBridge.state.reset6D()
            updateToggleButtonsUi()
            ShizukuBridge.pushControlState()
        }

        btnGyro.setOnClickListener {
            val next = !ShizukuBridge.gyro6DEnabled
            ShizukuBridge.gyro6DEnabled = next
            if (next) {
                ShizukuBridge.state.autoSweep = false
                startGyroSensor()
            } else {
                stopGyroSensor()
                ShizukuBridge.state.reset6D()
            }
            updateToggleButtonsUi()
            ShizukuBridge.pushControlState()
        }

        wm.addView(view, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopGyroSensor()
        ShizukuBridge.removeStateListener(stateObserver)
        btnAutoMoveRef = null
        btnGyroRef = null
        btnAxisModeRef = null
        rootView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: Throwable) {
            }
        }
        rootView = null
    }
}
