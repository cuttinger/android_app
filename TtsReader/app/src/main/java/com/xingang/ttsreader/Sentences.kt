package com.xingang.ttsreader

import java.util.Locale

/** One chunk of speech together with the language it should be spoken in. */
data class SpokenPart(val text: String, val locale: Locale)

/**
 * What gets read aloud for each entry. Each mode carries its own icon: a car
 * player is the one place this is chosen without any text beside it, so the
 * icon has to say which mode is on. A bar stands for English, a pair of
 * blocks for the Chinese gloss.
 */
enum class SpeakMode(val labelRes: Int, val iconRes: Int) {
    /** 单词 → 中文释义 → 例句 */
    FULL(R.string.mode_full, R.drawable.ic_mode_full),
    /** 只读单词或短语 */
    WORD_ONLY(R.string.mode_word, R.drawable.ic_mode_word),
    /** 单词 → 中文释义 */
    WORD_THEN_CN(R.string.mode_word_cn, R.drawable.ic_mode_word_cn),
    /** 中文释义 → 单词 */
    CN_THEN_WORD(R.string.mode_cn_word, R.drawable.ic_mode_cn_word);

    companion object {
        fun of(name: String?): SpeakMode = entries.firstOrNull { it.name == name } ?: FULL
    }
}

/**
 * One unit of reading.
 *
 * [text] is displayed (for the phrase lists: the example sentence).
 * [note] is displayed but never spoken, in the form `word：中文释义`.
 * [lead] is the word or phrase itself.
 * [raw] is the source line, kept so an entry can be moved between corpora intact.
 */
data class Sentence(
    val text: String,
    val note: String? = null,
    val lead: String? = null,
    val raw: String = text,
) {
    /** What the car artwork shows: the word alone when there is one. */
    val headline: String get() = lead ?: text

    /** The Chinese gloss without its part-of-speech tags, or null when there is none. */
    val meaning: String?
        get() = note?.substringAfter('：', "")?.let { stripPartsOfSpeech(it) }?.takeIf { it.isNotEmpty() }

    /** A stable identifier for this entry within its corpus. */
    val key: String get() = (lead ?: text).lowercase()

    /** The utterances to speak, in order, each with its own language. */
    fun parts(mode: SpeakMode = SpeakMode.FULL): List<SpokenPart> {
        val word = lead?.takeIf { it.isNotBlank() }
        val cn = meaning
        // Plain pasted text has neither a word nor a gloss: always read it as-is.
        if (word == null && cn == null) return listOf(SpokenPart(text, Locale.US))
        val en = { t: String -> SpokenPart(t, Locale.US) }
        val zh = { t: String -> SpokenPart(t, Locale.SIMPLIFIED_CHINESE) }
        return when (mode) {
            SpeakMode.FULL -> listOfNotNull(word?.let(en), cn?.let(zh), en(text))
            SpeakMode.WORD_ONLY -> listOf(en(word ?: text))
            SpeakMode.WORD_THEN_CN -> listOfNotNull(word?.let(en), cn?.let(zh))
            SpeakMode.CN_THEN_WORD -> listOfNotNull(cn?.let(zh), en(word ?: text))
        }
    }
}

/** `n.` / `adj.` / `n./v.` and friends; shown on screen, not worth speaking. */
private val PART_OF_SPEECH = Regex(
    "(?:\\b(?:n|v|vt|vi|adj|adv|prep|conj|pron|num|int|interj|art|aux|abbr|pl)\\.\\s*/?\\s*)+",
    RegexOption.IGNORE_CASE,
)

/**
 * Drops every part-of-speech tag so TTS reads only the meanings. A tag also
 * sits between senses that are written without a separator ("adj. 每年的 n. 年刊");
 * those become separate senses so they are not run together when spoken.
 */
internal fun stripPartsOfSpeech(meaning: String): String =
    PART_OF_SPEECH.replace(meaning) { if (it.range.first == 0) "" else "；" }
        .split('；', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("；")

/**
 * Splits multi-line English text into sentences, one per TTS turn.
 * A line of the form `display || 中文注释 || lead word` is kept whole;
 * the second and third fields are optional.
 */
object Sentences {

    const val NOTE_SEPARATOR = " || "
    private val BOUNDARY = Regex("(?<=[.!?][\"')\\]]?)\\s+(?=[\"'(\\[]?[A-Z0-9])")
    private val ABBREVIATIONS = listOf("Mr.", "Mrs.", "Ms.", "Dr.", "St.", "Jr.", "Sr.", "vs.", "e.g.", "i.e.", "U.S.")

    fun split(text: String): List<Sentence> {
        val out = ArrayList<Sentence>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim().replace(Regex("\\s+"), " ")
            if (line.isEmpty()) continue
            if (line.contains(NOTE_SEPARATOR)) {
                val parts = line.split(NOTE_SEPARATOR).map { it.trim() }
                val display = parts[0]
                val note = parts.getOrNull(1)?.ifEmpty { null }
                val lead = parts.getOrNull(2)?.ifEmpty { null }
                if (display.isNotEmpty()) out += Sentence(display, note, lead, line)
                continue
            }
            var pieces = BOUNDARY.split(line)
            // Re-join splits made right after a common abbreviation ("Dr. Smith").
            val merged = ArrayList<String>()
            for (p in pieces) {
                if (merged.isNotEmpty() && ABBREVIATIONS.any { merged.last().endsWith(it) }) {
                    merged[merged.size - 1] = merged.last() + " " + p
                } else {
                    merged += p
                }
            }
            pieces = merged
            out += pieces.map { it.trim() }.filter { it.isNotEmpty() }.map { Sentence(it, raw = it) }
        }
        return out
    }
}
