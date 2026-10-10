package com.xingang.recentapps

import com.github.promeg.pinyinhelper.Pinyin

/** Builds the searchable key set for an app and normalises user queries. */
object SearchIndex {

    /** Lower-case and strip all whitespace so "wei xin" and "weixin" behave the same. */
    fun normalize(s: CharSequence): String =
        s.toString().lowercase().filterNot { it.isWhitespace() }

    /**
     * Keys derived from every piece of text we know about the app.
     * Pinyin is produced twice: full spelling ("weixin") and initials ("wx").
     */
    fun buildKeys(vararg texts: String?): List<String> {
        val keys = LinkedHashSet<String>()
        for (t in texts) {
            if (t.isNullOrBlank()) continue
            val norm = normalize(t)
            keys += norm
            if (t.any { Pinyin.isChinese(it) }) {
                keys += fullPinyin(t)
                keys += pinyinInitials(t)
            }
        }
        return keys.filter { it.isNotEmpty() }
    }

    private fun fullPinyin(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c.isWhitespace()) continue
            sb.append(if (Pinyin.isChinese(c)) Pinyin.toPinyin(c) else c.toString())
        }
        return sb.toString().lowercase()
    }

    private fun pinyinInitials(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c.isWhitespace()) continue
            if (Pinyin.isChinese(c)) {
                val py = Pinyin.toPinyin(c)
                if (py.isNotEmpty()) sb.append(py[0])
            } else {
                sb.append(c)
            }
        }
        return sb.toString().lowercase()
    }
}
