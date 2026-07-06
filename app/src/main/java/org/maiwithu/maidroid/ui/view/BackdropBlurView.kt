package org.maiwithu.maidroid.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

/**
 * Backdrop blur view that captures content behind it and renders a blurred overlay.
 *
 * API 31+   uses [RenderEffect.createBlurEffect] (GPU-accelerated).
 * API 2630  uses android.renderscript.ScriptIntrinsicBlur (GPU-accelerated).
 */
class BackdropBlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Blur amount applied to the backdrop. Clamped to [0..25] on API < S. */
    var blurRadius: Float = 22f
        set(value) {
            val clamped = value.coerceIn(0f, 25f)
            if (field != clamped) {
                field = clamped
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRenderEffect(RenderEffect.createBlurEffect(
                        clamped, clamped, Shader.TileMode.CLAMP
                    ))
                }
                invalidate()
            }
        }

    /** Down-sample scale for the captured bitmap. Higher = lower memory, lower quality. */
    var downsampleFactor: Int = 5
        set(value) {
            field = value.coerceAtLeast(2)
            clearBitmaps()
            invalidate()
        }

    /** Tint color drawn on top of the blurred content. */
    var overlayColor: Int = 0x662B2B2B.toInt()
        set(value) {
            field = value
            invalidate()
        }

    /** Corner radius in pixels applied to the clipping path. */
    var cornerRadiusPx: Float = 0f
        set(value) {
            field = value.coerceAtLeast(0f)
            invalidate()
        }

    /** Interval between captures when content may be scrolling / animating. */
    var refreshIntervalMillis: Long = 200L

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val rect = RectF()
    private val selfLocation = IntArray(2)
    private val rootLocation = IntArray(2)
    private var captureBitmap: Bitmap? = null
    private var captureScheduled = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(
                blurRadius, blurRadius, Shader.TileMode.CLAMP
            ))
        }
    }

    override fun onDetachedFromWindow() {
        captureScheduled = false
        clearBitmaps()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clearBitmaps()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onDraw(canvas: Canvas) {
        if (isCapturing || width <= 0 || height <= 0) return

        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(rect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)

        val count = canvas.save()
        canvas.clipPath(clipPath)
        captureBitmap?.let { canvas.drawBitmap(it, null, rect, bitmapPaint) }
        overlayPaint.color = overlayColor
        canvas.drawRect(rect, overlayPaint)
        canvas.restoreToCount(count)

        if (refreshIntervalMillis > 0L) {
            scheduleCapture()
        }
    }

    private fun scheduleCapture() {
        if (captureScheduled || !isAttachedToWindow) return
        captureScheduled = true
        postDelayed(
            {
                captureScheduled = false
                if (!isAttachedToWindow || width <= 0 || height <= 0) return@postDelayed
                captureAndBlur()
                invalidate()
            },
            refreshIntervalMillis
        )
    }

    private fun captureAndBlur() {
        val root = rootView ?: return
        if (root.width <= 0 || root.height <= 0) return

        val scale = downsampleFactor
        val bitmapWidth = max(1, ceil(width / scale.toFloat()).toInt())
        val bitmapHeight = max(1, ceil(height / scale.toFloat()).toInt())
        val bitmap = captureBitmap
            ?.takeIf { it.width == bitmapWidth && it.height == bitmapHeight && !it.isRecycled }
            ?: Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                .also { captureBitmap = it }

        getLocationOnScreen(selfLocation)
        root.getLocationOnScreen(rootLocation)

        val captureCanvas = Canvas(bitmap)
        captureCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        captureCanvas.scale(1f / scale, 1f / scale)
        captureCanvas.translate(
            (rootLocation[0] - selfLocation[0]).toFloat(),
            (rootLocation[1] - selfLocation[1]).toFloat()
        )

        isCapturing = true
        try {
            root.draw(captureCanvas)
        } finally {
            isCapturing = false
        }

        // On API 31+ RenderEffect handles the blur in onDraw via the GPU.
        // On API < 31 we apply RenderScript (GPU-accelerated) to the bitmap in-place.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            applyRenderScriptBlur(bitmap, max(1, (blurRadius / scale).toInt()).coerceIn(1, 25))
        }
    }

    private fun applyRenderScriptBlur(bitmap: Bitmap, radius: Int) {
        try {
            val rsClass = Class.forName("android.renderscript.RenderScript")
            val elementClass = Class.forName("android.renderscript.Element")
            val allocClass = Class.forName("android.renderscript.Allocation")
            val blurClass = Class.forName("android.renderscript.ScriptIntrinsicBlur")

            val rs = rsClass.getMethod("create", Context::class.java).invoke(null, context)
            val element = elementClass.getMethod("U8_4", rsClass).invoke(null, rs)
            val script = blurClass.getMethod("create", rsClass, elementClass)
                .invoke(null, rs, element)
            val inputAlloc = allocClass.getMethod("createFromBitmap", rsClass, Bitmap::class.java)
                .invoke(null, rs, bitmap)
            val type = inputAlloc.javaClass.getMethod("getType").invoke(inputAlloc)
            val outputAlloc = allocClass.getMethod("createTyped", rsClass, type.javaClass)
                .invoke(null, rs, type)

            script.javaClass.getMethod("setRadius", Float::class.java)
                .invoke(script, radius.toFloat())
            script.javaClass.getMethod("setInput", allocClass).invoke(script, inputAlloc)
            script.javaClass.getMethod("forEach", allocClass).invoke(script, outputAlloc)
            outputAlloc.javaClass.getMethod("copyTo", Bitmap::class.java)
                .invoke(outputAlloc, bitmap)

            inputAlloc.javaClass.getMethod("destroy").invoke(inputAlloc)
            outputAlloc.javaClass.getMethod("destroy").invoke(outputAlloc)
            rs.javaClass.getMethod("destroy").invoke(rs)
        } catch (_: Exception) {
            // RenderScript is unavailable; bitmap stays unblurred but is still drawn.
        }
    }

    private fun clearBitmaps() {
        captureBitmap?.recycle()
        captureBitmap = null
    }

    companion object {
        var isCapturing = false
    }
}
