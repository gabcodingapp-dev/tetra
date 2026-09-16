package com.tetra.bot.vision

import android.graphics.RectF

/** Normalized (0..1) region of the screen holding the 2048 board. */
data class Roi(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun on(w: Int, h: Int): RectF = RectF(left * w, top * h, right * w, bottom * h)

    fun isValid(): Boolean =
        left >= 0f && top >= 0f && right > left && bottom > top && right <= 1f && bottom <= 1f

    companion object {
        fun fromRect(rect: RectF, w: Int, h: Int): Roi {
            fun clamp(v: Float) = v.coerceIn(0f, 1f)
            return Roi(
                clamp(rect.left / w),
                clamp(rect.top / h),
                clamp(rect.right / w),
                clamp(rect.bottom / h)
            )
        }
    }
}