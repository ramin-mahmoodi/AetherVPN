package com.cluvexstudio.aether

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

class ConnectButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    var state: State = State.DISCONNECTED
        set(value) {
            field = value
            if (value == State.CONNECTING) {
                startConnectingAnimation()
            } else {
                stopConnectingAnimation()
            }
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()
    
    private var progress = 0f
    private var pulse = 0f
    private var connectingAnimator: ValueAnimator? = null

    private val colorDisconnected = Color.parseColor("#A1A1AA")
    private val colorConnecting = Color.parseColor("#FF6D00")
    private val colorConnected = Color.parseColor("#00E676")
    private val colorBg = Color.parseColor("#1A1A1C")

    init {
        // Removed clickable/focusable so parent FrameLayout can intercept clicks
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        
        // Base radius
        val radius = size / 2f - dp(10) + if (state == State.CONNECTING) dp(4) * pulse else 0f

        val accentColor = when (state) {
            State.DISCONNECTED -> colorDisconnected
            State.CONNECTING -> colorConnecting
            State.CONNECTED -> colorConnected
        }

        // Draw outer ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(if (state == State.DISCONNECTED) 2 else 3).toFloat()
        paint.color = accentColor
        canvas.drawCircle(centerX, centerY, radius, paint)

        // Draw Connecting Arc
        if (state == State.CONNECTING) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3).toFloat()
            
            val arcStart = progress * 360f
            val arcSweep = 90f + (pulse * 90f)
            
            val outerRadius = radius + dp(6)
            arcRect.set(centerX - outerRadius, centerY - outerRadius, centerX + outerRadius, centerY + outerRadius)
            
            canvas.drawArc(arcRect, arcStart, arcSweep, false, paint)
        }
    }

    override fun onDetachedFromWindow() {
        stopConnectingAnimation()
        super.onDetachedFromWindow()
    }

    private fun startConnectingAnimation() {
        if (connectingAnimator != null) return
        connectingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                progress = v
                pulse = Math.abs(Math.sin(v * Math.PI * 2)).toFloat()
                invalidate()
            }
            start()
        }
    }

    private fun stopConnectingAnimation() {
        connectingAnimator?.cancel()
        connectingAnimator = null
        progress = 0f
        pulse = 0f
        invalidate()
    }
}
