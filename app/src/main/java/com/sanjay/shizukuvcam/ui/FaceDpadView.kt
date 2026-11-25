package com.sanjay.shizukuvcam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

class FaceDpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Zone { NONE, CENTER, UP, DOWN, LEFT, RIGHT }

    var onDirectionCommand: ((Zone, Float, Float) -> Unit)? = null

    private var activeZone: Zone = Zone.NONE

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B2A1E")
        style = Paint.Style.FILL
    }
    private val activeSlicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#165A3C")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#358962")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#358962")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val centerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1DB954")
        style = Paint.Style.FILL
    }
    private val centerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A5F3C4")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2EE87A")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#062115")
        style = Paint.Style.FILL
    }
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#062115")
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val outerR = min(width, height) / 2f - 6f
        val innerR = outerR * 0.30f

        // Base dark circle
        canvas.drawCircle(cx, cy, outerR, bgPaint)

        // Highlight pressed directional wedge
        if (activeZone != Zone.NONE && activeZone != Zone.CENTER) {
            val startAngle = when (activeZone) {
                Zone.RIGHT -> -45f
                Zone.DOWN -> 45f
                Zone.LEFT -> 135f
                Zone.UP -> 225f
                else -> 0f
            }
            canvas.drawArc(
                cx - outerR,
                cy - outerR,
                cx + outerR,
                cy + outerR,
                startAngle,
                90f,
                true,
                activeSlicePaint
            )
        }

        // Diagonal quadrant lines (matching LiteCam D-pad)
        val d = outerR * 0.7071f
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, dividerPaint)
        canvas.drawLine(cx + d, cy - d, cx - d, cy + d, dividerPaint)
        canvas.drawCircle(cx, cy, outerR, ringPaint)

        // Directional chevron arrows
        val arrowDist = outerR * 0.66f
        val arrowSize = outerR * 0.12f
        drawChevron(canvas, cx, cy - arrowDist, arrowSize, Zone.UP)
        drawChevron(canvas, cx, cy + arrowDist, arrowSize, Zone.DOWN)
        drawChevron(canvas, cx - arrowDist, cy, arrowSize, Zone.LEFT)
        drawChevron(canvas, cx + arrowDist, cy, arrowSize, Zone.RIGHT)

        // Center emerald reset / joystick circle
        centerCirclePaint.color = if (activeZone == Zone.CENTER) {
            Color.parseColor("#2EE87A")
        } else {
            Color.parseColor("#19A957")
        }
        canvas.drawCircle(cx, cy, innerR, centerCirclePaint)
        canvas.drawCircle(cx, cy, innerR, centerRingPaint)

        // Mini joystick glyph in center
        val jR = innerR * 0.22f
        canvas.drawLine(cx - innerR * 0.34f, cy + innerR * 0.28f, cx + innerR * 0.34f, cy + innerR * 0.28f, iconStrokePaint)
        canvas.drawLine(cx, cy + innerR * 0.28f, cx, cy - innerR * 0.14f, iconStrokePaint)
        canvas.drawCircle(cx, cy - innerR * 0.22f, jR, iconPaint)
    }

    private fun drawChevron(canvas: Canvas, x: Float, y: Float, s: Float, zone: Zone) {
        val path = Path()
        when (zone) {
            Zone.UP -> {
                path.moveTo(x - s, y + s * 0.5f)
                path.lineTo(x, y - s * 0.5f)
                path.lineTo(x + s, y + s * 0.5f)
            }
            Zone.DOWN -> {
                path.moveTo(x - s, y - s * 0.5f)
                path.lineTo(x, y + s * 0.5f)
                path.lineTo(x + s, y - s * 0.5f)
            }
            Zone.LEFT -> {
                path.moveTo(x + s * 0.5f, y - s)
                path.lineTo(x - s * 0.5f, y)
                path.lineTo(x + s * 0.5f, y + s)
            }
            Zone.RIGHT -> {
                path.moveTo(x - s * 0.5f, y - s)
                path.lineTo(x + s * 0.5f, y)
                path.lineTo(x - s * 0.5f, y + s)
            }
            else -> return
        }
        canvas.drawPath(path, arrowPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val outerR = min(width, height) / 2f - 6f
        val innerR = outerR * 0.32f
        val dx = event.x - cx
        val dy = event.y - cy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val zone = when {
                    dist <= innerR -> Zone.CENTER
                    else -> {
                        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                        when {
                            angle >= -45.0 && angle < 45.0 -> Zone.RIGHT
                            angle >= 45.0 && angle < 135.0 -> Zone.DOWN
                            angle >= -135.0 && angle < -45.0 -> Zone.UP
                            else -> Zone.LEFT
                        }
                    }
                }
                activeZone = zone
                invalidate()

                val normX = (dx / outerR).coerceIn(-1f, 1f)
                val normY = (-dy / outerR).coerceIn(-1f, 1f)
                when (zone) {
                    Zone.CENTER -> onDirectionCommand?.invoke(Zone.CENTER, 0f, 0f)
                    Zone.LEFT -> onDirectionCommand?.invoke(Zone.LEFT, normX.coerceAtMost(-0.65f), 0f)
                    Zone.RIGHT -> onDirectionCommand?.invoke(Zone.RIGHT, normX.coerceAtLeast(0.65f), 0f)
                    Zone.UP -> onDirectionCommand?.invoke(Zone.UP, 0f, normY.coerceAtLeast(0.65f))
                    Zone.DOWN -> onDirectionCommand?.invoke(Zone.DOWN, 0f, normY.coerceAtMost(-0.65f))
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeZone = Zone.NONE
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
