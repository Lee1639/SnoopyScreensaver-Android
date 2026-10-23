package com.snoopysaver.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

object Placement {
    fun fit(bounds: RectF): RectF {
        val scale = min(bounds.width() / 1920f, bounds.height() / 1080f)
        val width = 1920f * scale
        val height = 1080f * scale
        val left = bounds.centerX() - width / 2f
        val top = bounds.centerY() - height / 2f
        return RectF(left, top, left + width, top + height)
    }

    /**
     * Idle scenes keep the 16:9 artwork, but wide phones would otherwise leave
     * the sides empty. Scale until the canvas covers the screen and keep its
     * bottom on the screen, so a full-width wall reaches both edges.
     */
    fun stage(bounds: RectF): RectF {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return RectF(bounds)
        val scale = max(bounds.width() / 1920f, bounds.height() / 1080f)
        val width = 1920f * scale
        val height = 1080f * scale
        val left = bounds.centerX() - width / 2f
        val top = bounds.bottom - height
        return RectF(left, top, left + width, top + height)
    }

    /** Active scenes cover the whole screen. Wider phones crop a little from the top and bottom. */
    fun active(bounds: RectF): RectF {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return RectF(bounds)
        val scale = max(bounds.width() / 1920f, bounds.height() / 1080f)
        val width = 1920f * scale
        val height = 1080f * scale
        val left = bounds.centerX() - width / 2f
        val top = bounds.centerY() - height / 2f
        return RectF(left, top, left + width, top + height)
    }

    fun sprite(
        sprite: Sprite?,
        bounds: RectF,
        offsetX: Double,
        offsetY: Double,
        ignoreOffset: Boolean,
    ): RectF {
        val viewport = stage(bounds)
        if (sprite == null || sprite.width <= 0.0 || sprite.height <= 0.0) return viewport
        val scale = viewport.width() / 1920f
        val width = (sprite.width * scale).toFloat()
        val height = (sprite.height * scale).toFloat()
        val top = when (sprite.alignment) {
            "top" -> viewport.top
            "bottom" -> viewport.bottom - height
            else -> viewport.centerY() - height / 2f
        }
        val left = viewport.centerX() - width / 2f
        val dx = if (ignoreOffset) 0f else (offsetX * scale).toFloat()
        // Metadata Y grows downward, the same way Android does.
        val dy = if (ignoreOffset) 0f else (offsetY * scale).toFloat()
        return RectF(left + dx, top + dy, left + dx + width, top + dy + height)
    }
}

class DrawState {
    var mode: Int = MODE_IDLE
    var palette: Int = DEFAULT_PALETTE
    var overlay: Int = Color.TRANSPARENT
    var background: Bitmap? = null
    var backgroundRect = RectF()
    var character: Bitmap? = null
    var characterRect = RectF()
    var visitor: Bitmap? = null
    var visitorRect = RectF()
    var visitorBehind = false
    var pose: Bitmap? = null
    var poseRect = RectF()
    var mask: Bitmap? = null
    var outline: Bitmap? = null
    var activeViewport = RectF()

    companion object {
        const val MODE_IDLE = 0
        const val MODE_ACTIVE = 1
        const val MODE_TRANSITION = 2
    }
}

class StageView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var role: Int = ROLE_BACK
    var drawState: DrawState? = null
    var halftone: Bitmap? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val halftonePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 26 }
    private val punch = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val state = drawState ?: return
        if (state.mode == DrawState.MODE_ACTIVE) return
        if (role == ROLE_BACK) {
            if (state.mode == DrawState.MODE_TRANSITION) return
            drawRoom(canvas, state, includeSprites = false)
            return
        }
        if (state.mode == DrawState.MODE_TRANSITION) {
            // A missing mask would stamp the whole idle scene over the video for a frame.
            val mask = state.mask ?: return
            val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            drawRoom(canvas, state, includeSprites = true)
            if (!state.activeViewport.isEmpty) {
                canvas.drawBitmap(mask, null, state.activeViewport, punch)
            }
            canvas.restoreToCount(checkpoint)
            val outline = state.outline
            if (outline != null && !state.activeViewport.isEmpty) {
                canvas.drawBitmap(outline, null, state.activeViewport, paint)
            }
            return
        }
        drawSprites(canvas, state)
    }

    private fun drawRoom(canvas: Canvas, state: DrawState, includeSprites: Boolean) {
        canvas.drawColor(state.palette)
        if (Color.alpha(state.overlay) > 0) canvas.drawColor(state.overlay)
        val tone = halftone
        if (tone != null) canvas.drawBitmap(tone, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), halftonePaint)
        val background = state.background
        if (background != null && !state.backgroundRect.isEmpty) {
            canvas.drawBitmap(background, null, state.backgroundRect, paint)
        }
        if (includeSprites) drawSprites(canvas, state)
    }

    private fun drawSprites(canvas: Canvas, state: DrawState) {
        if (state.visitorBehind) drawVisitor(canvas, state)
        val pose = state.pose
        if (pose != null && !state.poseRect.isEmpty) {
            canvas.drawBitmap(pose, null, state.poseRect, paint)
        } else {
            val character = state.character
            if (character != null && !state.characterRect.isEmpty) {
                canvas.drawBitmap(character, null, state.characterRect, paint)
            }
        }
        if (!state.visitorBehind) drawVisitor(canvas, state)
    }

    private fun drawVisitor(canvas: Canvas, state: DrawState) {
        val visitor = state.visitor ?: return
        if (state.visitorRect.isEmpty) return
        canvas.drawBitmap(visitor, null, state.visitorRect, paint)
    }

    companion object {
        const val ROLE_BACK = 0
        const val ROLE_FRONT = 1
    }
}
