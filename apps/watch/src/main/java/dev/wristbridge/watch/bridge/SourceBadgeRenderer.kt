package dev.wristbridge.watch.bridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import dev.wristbridge.notification.SourceProfile

/** Original letter badges generated locally; never use notification text or downloaded artwork. */
class SourceBadgeRenderer {
    private val cache = LruCache<String, Bitmap>(16)

    fun icon(profile: SourceProfile): Bitmap {
        val key = "${profile.badge}:${profile.accentArgb}"
        cache.get(key)?.let { return it }
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = profile.accentArgb }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.WHITE
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = when {
            profile.badge.length > 3 -> 24f
            profile.badge.length > 2 -> 29f
            else -> 38f
        }
        val baseline = (size - paint.fontMetrics.ascent - paint.fontMetrics.descent) / 2f
        canvas.drawText(profile.badge, size / 2f, baseline, paint)
        cache.put(key, bitmap)
        return bitmap
    }
}
