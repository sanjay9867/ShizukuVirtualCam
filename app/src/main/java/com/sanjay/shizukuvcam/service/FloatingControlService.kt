package com.sanjay.shizukuvcam.service

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.content.res.ColorStateList
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

class FloatingControlService : Service() {

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var isCollapsed = false
    private var btnAutoMoveRef: Button? = null

    private val stateObserver: () -> Unit = {
        btnAutoMoveRef?.post { updateAutoMoveButtonUi() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        showFloatingPanel()
        ShizukuBridge.addStateListener(stateObserver)
    }

    private fun updateAutoMoveButtonUi() {
        val btn = btnAutoMoveRef ?: return
        val active = ShizukuBridge.state.autoSweep
        btn.text = if (active) "Auto: ON" else "Auto: OFF"
        btn.backgroundTintList = ColorStateList.valueOf(
            if (active) Color.parseColor("#1DB954") else Color.parseColor("#082418")
        )
        btn.setTextColor(
            if (active) Color.parseColor("#041F13") else Color.parseColor("#2EE87A")
        )
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
            x = 24
            y = 180
        }

        val headerDrag = view.findViewById<LinearLayout>(R.id.overlayHeader)
        val bodyContainer = view.findViewById<LinearLayout>(R.id.overlayBody)
        val btnCollapse = view.findViewById<TextView>(R.id.btnOverlayCollapse)
        val btnClose = view.findViewById<TextView>(R.id.btnOverlayClose)
        val dpad = view.findViewById<FaceDpadView>(R.id.overlayDpad)
        val btnZoomOut = view.findViewById<Button>(R.id.btnOverlayZoomOut)
        val btnZoomIn = view.findViewById<Button>(R.id.btnOverlayZoomIn)
        val btnFlip = view.findViewById<Button>(R.id.btnOverlayFlip)
        val btnBlink = view.findViewById<Button>(R.id.btnOverlayBlink)
        val btnAutoMove = view.findViewById<Button>(R.id.btnOverlayAutoMove)
        btnAutoMoveRef = btnAutoMove
        updateAutoMoveButtonUi()

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

        dpad.onDirectionCommand = { zone, yaw, pitch ->
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
            updateAutoMoveButtonUi()
            ShizukuBridge.pushControlState()
        }

        btnZoomOut.setOnClickListener {
            ShizukuBridge.state.zoom = (ShizukuBridge.state.zoom - 0.1f).coerceIn(0.6f, 2.2f)
            ShizukuBridge.pushControlState()
        }

        btnZoomIn.setOnClickListener {
            ShizukuBridge.state.zoom = (ShizukuBridge.state.zoom + 0.1f).coerceIn(0.6f, 2.2f)
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
            ShizukuBridge.state.autoSweep = next
            if (!next) {
                ShizukuBridge.state.yaw = 0f
                ShizukuBridge.state.pitch = 0f
            }
            updateAutoMoveButtonUi()
            ShizukuBridge.pushControlState()
        }

        wm.addView(view, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        ShizukuBridge.removeStateListener(stateObserver)
        btnAutoMoveRef = null
        rootView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: Throwable) {
            }
        }
        rootView = null
    }
}
