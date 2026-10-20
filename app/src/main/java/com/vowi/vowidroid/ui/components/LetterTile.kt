package com.vowi.vowidroid.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The letter-tile palette of AOSP's contacts and dialer (LetterTileDrawable),
 * shared by the in-app avatars and the bitmaps for shortcuts and widgets.
 */
object LetterTile {
    private val colors = longArrayOf(
        0xFFDB4437, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5, 0xFF4285F4, 0xFF039BE5,
        0xFF0097A7, 0xFF009688, 0xFF0F9D58, 0xFF689F38, 0xFFEF6C00, 0xFFFF5722, 0xFF757575,
    )

    /** The tile colour for a number or name; a phone number is keyed by its last ten digits, so "+1 555…" and "555…" match. */
    fun color(key: String): Int {
        val digits = key.filter(Char::isDigit)
        val colorKey = if (digits.length >= 7) digits.takeLast(10) else key
        return colors[Math.floorMod(colorKey.hashCode(), colors.size)].toInt()
    }

    fun letter(name: String?): Char? = name?.trim()?.firstOrNull { it.isLetter() }?.uppercaseChar()

    /** A round tile with the name's first letter, or a plain dot for a bare number. */
    fun bitmap(name: String?, key: String, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(key) }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        val letter = letter(name)
        if (letter != null) {
            paint.textSize = size * 0.45f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letter.toString(), size / 2f, baseline, paint)
        } else {
            // A simple person silhouette: head and shoulders.
            canvas.drawCircle(size / 2f, size * 0.38f, size * 0.16f, paint)
            canvas.drawArc(size * 0.22f, size * 0.58f, size * 0.78f, size * 1.1f, 180f, 180f, true, paint)
        }
        return bitmap
    }

    /** A square photo cropped to a circle, as the stock apps show contacts. */
    fun circle(photo: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        val side = minOf(photo.width, photo.height)
        val source = android.graphics.Rect((photo.width - side) / 2, (photo.height - side) / 2, (photo.width + side) / 2, (photo.height + side) / 2)
        canvas.drawBitmap(photo, source, android.graphics.Rect(0, 0, size, size), paint)
        return output
    }
}
