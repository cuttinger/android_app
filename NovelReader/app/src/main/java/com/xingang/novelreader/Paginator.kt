package com.xingang.novelreader

import android.view.View
import android.widget.TextView

/**
 * Splits chapter text into screen-sized pages. The text is laid out in a
 * TextView configured exactly like the page views (same style, padding,
 * font size, line height), so the cut points match what the pages render.
 */
object Paginator {

    /**
     * @param probe a TextView inflated from the page layout, not attached to a window.
     * @param totalWidth full width the page view will get.
     * @param totalHeight full height the page view will get.
     */
    fun paginate(text: CharSequence, probe: TextView, totalWidth: Int, totalHeight: Int): List<CharSequence> {
        if (text.isEmpty() || totalWidth <= 0 || totalHeight <= 0) return listOf(text)
        probe.text = text
        probe.measure(
            View.MeasureSpec.makeMeasureSpec(totalWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        probe.layout(0, 0, probe.measuredWidth, probe.measuredHeight)
        val layout = probe.layout ?: return listOf(text)
        val height = totalHeight - probe.paddingTop - probe.paddingBottom

        val pages = ArrayList<CharSequence>()
        var first = 0
        while (first < layout.lineCount) {
            val top = layout.getLineTop(first)
            var last = first
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= height) last++
            val slice = text.subSequence(layout.getLineStart(first), layout.getLineEnd(last))
            // Skip pages that would hold only whitespace (e.g. a trailing newline).
            if (slice.isNotBlank()) pages += slice
            first = last + 1
        }
        return pages.ifEmpty { listOf(text) }
    }
}
