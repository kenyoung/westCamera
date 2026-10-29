package com.example.dropboxsignal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

// Pinch-to-zoom + drag-to-pan image view. Opens fit-to-view and centered; pinch or double-tap
// zooms (clamped between fit and 8x), drag pans, and a single tap is forwarded to onSingleTap
// (used to dismiss the full-screen overlay).
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    var onSingleTap: (() -> Unit)? = null

    private val m = Matrix()
    private val values = FloatArray(9)
    private var fitScale = 1f
    private var maxScale = 1f
    private var viewW = 0f
    private var viewH = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val current = currentScale()
                var factor = d.scaleFactor
                val target = current * factor
                if (target < fitScale) factor = fitScale / current
                if (target > maxScale) factor = maxScale / current
                m.postScale(factor, factor, d.focusX, d.focusY)
                clamp()
                imageMatrix = m
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val current = currentScale()
                val target = if (current > fitScale * 1.1f) fitScale else fitScale * 3f
                m.postScale(target / current, target / current, e.x, e.y)
                clamp()
                imageMatrix = m
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                m.postTranslate(-dx, -dy)
                clamp()
                imageMatrix = m
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        fitToView()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w.toFloat()
        viewH = h.toFloat()
        fitToView()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun currentScale(): Float {
        m.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    // Reset to a centered fit whenever the image or the view size changes.
    private fun fitToView() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (viewW == 0f || viewH == 0f || dw == 0f || dh == 0f) return
        fitScale = minOf(viewW / dw, viewH / dh)
        maxScale = fitScale * 8f
        m.reset()
        m.postScale(fitScale, fitScale)
        m.postTranslate((viewW - dw * fitScale) / 2f, (viewH - dh * fitScale) / 2f)
        imageMatrix = m
    }

    // Keep the image within the view: centered when smaller than the view on an axis, edge-pinned
    // when larger, so panning can't push it off into empty space.
    private fun clamp() {
        val d = drawable ?: return
        m.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        val contentW = d.intrinsicWidth * scale
        val contentH = d.intrinsicHeight * scale
        m.postTranslate(
            fixTrans(values[Matrix.MTRANS_X], viewW, contentW),
            fixTrans(values[Matrix.MTRANS_Y], viewH, contentH)
        )
    }

    private fun fixTrans(trans: Float, viewSize: Float, contentSize: Float): Float {
        val minTrans: Float
        val maxTrans: Float
        if (contentSize <= viewSize) {
            minTrans = (viewSize - contentSize) / 2f   // center it
            maxTrans = minTrans
        } else {
            minTrans = viewSize - contentSize           // pin right/bottom edge
            maxTrans = 0f                               // pin left/top edge
        }
        return when {
            trans < minTrans -> minTrans - trans
            trans > maxTrans -> maxTrans - trans
            else -> 0f
        }
    }
}
