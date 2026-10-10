package com.xingang.ttsreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Renders text into bitmaps used where car media UIs insist on artwork:
 * the Now Playing "album art" square shows the sentence and its note in
 * large type instead of a placeholder icon, and browse rows get a number badge.
 */
object SentenceArt {

    private const val ART_SIZE = 480
    private const val BADGE_SIZE = 96
    private const val BG = 0xFF1F3A5F.toInt()

    /** Square artwork: the word or phrase alone, as large as fits. */
    fun nowPlaying(sentence: Sentence, index: Int, total: Int): Bitmap {
        val bmp = Bitmap.createBitmap(ART_SIZE, ART_SIZE, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val pad = 28f
        val width = (ART_SIZE - 2 * pad).toInt()

        val counter = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); textSize = 20f }
        c.drawText("${index + 1} / $total", pad, pad + 20f, counter)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
        }
        val top = pad + 44f
        val available = (ART_SIZE - top - pad).toInt()
        val textLayout = fit(sentence.headline, textPaint, width, Int.MAX_VALUE, 96f, 24f, available)
        // Centre the block vertically in the remaining space.
        val y = top + ((available - textLayout.height) / 2f).coerceAtLeast(0f)
        c.save(); c.translate(pad, y); textLayout.draw(c); c.restore()
        return bmp
    }

    /** Small square with the item number, for browse rows. */
    fun badge(number: Int): Bitmap {
        val bmp = Bitmap.createBitmap(BADGE_SIZE, BADGE_SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = if (number >= 100) 34f else 42f
        }
        val y = BADGE_SIZE / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(number.toString(), BADGE_SIZE / 2f, y, p)
        return bmp
    }

    /** Lay text out at the largest size (from [maxSize] down to [minSize]) that fits the height. */
    private fun fit(
        text: String, paint: TextPaint, width: Int, maxLines: Int, maxSize: Float, minSize: Float,
        maxHeight: Int = Int.MAX_VALUE,
    ): StaticLayout {
        var size = maxSize
        while (true) {
            paint.textSize = size
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setLineSpacing(0f, 1.15f)
                .setMaxLines(maxLines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            val truncated = layout.lineCount == maxLines && layout.getEllipsisCount(maxLines - 1) > 0
            if ((layout.height <= maxHeight && !truncated) || size <= minSize) return layout
            size -= 2f
        }
    }
}
