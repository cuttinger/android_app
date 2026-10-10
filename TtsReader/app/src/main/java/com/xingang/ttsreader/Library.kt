package com.xingang.ttsreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved piece of text to read aloud. */
data class Passage(val id: String, val title: String, val body: String, val updatedAt: Long) {
    /** Bundled corpora come from assets and cannot be edited. */
    val isBuiltin: Boolean get() = id.startsWith(Library.BUILTIN_PREFIX)

    /** The two collections the user fills by swiping entries out of other corpora. */
    val isCollection: Boolean get() = id == Library.KNOWN_ID || id == Library.UNKNOWN_ID
}

/** Where an entry was filed: 已掌握 or 未掌握. */
enum class Bucket(val id: String) { KNOWN(Library.KNOWN_ID), UNKNOWN(Library.UNKNOWN_ID) }

/**
 * The reading material.
 *
 * Bundled corpora live in `assets` and are parsed on demand — they are hundreds
 * of kilobytes in total, far too much for SharedPreferences, which silently
 * drops entries once the file grows too large. Entries the user files away are
 * appended to a file for the same reason. SharedPreferences holds only the
 * small things: pasted passages, reading positions and settings.
 */
class Library(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)

    // ---- passages ---------------------------------------------------------

    /** User passages first (newest first), then 已掌握 / 未掌握, then the bundled corpora. */
    fun passages(): List<Passage> {
        val hidden = hiddenIds()
        val moved = moves()
        val now = System.currentTimeMillis()
        val collections = Bucket.entries.map { bucket ->
            val lines = moved.filter { it.bucket == bucket }.map { it.line }
            Passage(bucket.id, "${titleOf(bucket)} ${lines.size} 条", lines.joinToString("\n"), now)
        }
        val removed = moved.groupBy({ it.corpusId }, { it.key })
        val corpora = builtins(app)
            .filterNot { it.id in hidden }
            .map { p ->
                val gone = removed[p.id]?.toSet().orEmpty()
                if (gone.isEmpty()) p else p.copy(body = filterOut(p.body, gone)).withCount(titleOf(p.id))
            }
        return userPassages().sortedByDescending { it.updatedAt } +
            collections.filterNot { it.id in hidden } + corpora
    }

    fun find(id: String): Passage? = passages().firstOrNull { it.id == id }

    /** Saves a user passage; bundled corpora and the two collections are managed elsewhere. */
    fun save(passage: Passage) {
        if (passage.isBuiltin || passage.isCollection) return
        write(userPassages().filter { it.id != passage.id } + passage)
    }

    /**
     * Removes a passage. Bundled corpora and collections cannot be deleted from
     * storage, so their ids are remembered as hidden; [restoreHidden] brings them back.
     */
    fun remove(id: String) {
        if (id.startsWith(BUILTIN_PREFIX) || id == KNOWN_ID || id == UNKNOWN_ID) {
            val arr = JSONArray()
            (hiddenIds() + id).distinct().forEach { arr.put(it) }
            prefs.edit().putString("hidden", arr.toString()).apply()
        } else {
            write(userPassages().filter { it.id != id })
        }
    }

    private fun userPassages(): List<Passage> {
        val arr = JSONArray(prefs.getString("passages", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Passage(it.getString("id"), it.getString("title"), it.getString("body"), it.getLong("t")) }
            .filter { !it.isBuiltin && !it.isCollection }
    }

    private fun write(list: List<Passage>) {
        val arr = JSONArray()
        for (p in list.sortedByDescending { it.updatedAt }) {
            arr.put(JSONObject().put("id", p.id).put("title", p.title).put("body", p.body).put("t", p.updatedAt))
        }
        prefs.edit().putString("passages", arr.toString()).apply()
    }

    private fun hiddenIds(): Set<String> {
        val arr = JSONArray(prefs.getString("hidden", "[]"))
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    fun hiddenCount(): Int = hiddenIds().size

    fun restoreHidden() = prefs.edit().remove("hidden").apply()

    // ---- 已掌握 / 未掌握 ---------------------------------------------------

    /** One filed entry: which corpus it came from, and where it went. */
    data class Moved(val corpusId: String, val key: String, val bucket: Bucket, val line: String)

    private val movesFile: File get() = File(app.filesDir, "moves.tsv")

    /** All filed entries, oldest first. Tab-separated so the " | " inside a line survives. */
    fun moves(): List<Moved> {
        movesCache?.let { return it }
        val list = try {
            if (!movesFile.exists()) emptyList()
            else movesFile.readLines().mapNotNull { ln ->
                val f = ln.split('\t')
                if (f.size != 4) return@mapNotNull null
                val bucket = Bucket.entries.firstOrNull { it.name == f[2] } ?: return@mapNotNull null
                Moved(f[0], f[1], bucket, f[3])
            }
        } catch (e: Exception) {
            emptyList()
        }
        movesCache = list
        return list
    }

    /**
     * File [sentence] from [corpusId] into [bucket]. Re-filing an entry already
     * on the list moves it to the other bucket instead of duplicating it.
     */
    fun moveEntry(corpusId: String, sentence: Sentence, bucket: Bucket) {
        val kept = moves().filterNot { it.key == sentence.key && it.bucket == bucket }
            .filterNot { it.key == sentence.key }
        val list = kept + Moved(corpusId, sentence.key, bucket, sentence.raw)
        writeMoves(list)
    }

    /** Put an entry back where it came from. */
    fun unfile(key: String) = writeMoves(moves().filterNot { it.key == key })

    private fun writeMoves(list: List<Moved>) {
        try {
            movesFile.writeText(list.joinToString("\n") { "${it.corpusId}\t${it.key}\t${it.bucket.name}\t${it.line}" })
            movesCache = list
        } catch (e: Exception) {
            // Filing is a convenience; a full disk should not crash the reader.
        }
    }

    /** Drop every line whose entry key is in [keys]. */
    private fun filterOut(body: String, keys: Set<String>): String =
        body.lineSequence().filter { ln ->
            val lead = ln.split(Sentences.NOTE_SEPARATOR).getOrNull(2)?.trim()
            val key = (lead ?: ln.substringBefore(Sentences.NOTE_SEPARATOR).trim()).lowercase()
            key !in keys
        }.joinToString("\n")

    private fun Passage.withCount(base: String) =
        copy(title = "$base ${body.lineSequence().count { it.isNotBlank() }} 条")

    // ---- settings ---------------------------------------------------------

    /** Last sentence index per passage, so reading resumes where it stopped. */
    fun position(id: String): Int = prefs.getInt("pos_$id", 0)
    fun savePosition(id: String, index: Int) = prefs.edit().putInt("pos_$id", index).apply()

    /** Package name of the chosen TTS engine; null = system default. */
    var ttsEngine: String?
        get() = prefs.getString("ttsEngine", null)
        set(v) = prefs.edit().putString("ttsEngine", v).apply()

    /** Voice name within the engine; null = engine default for en-US. */
    var ttsVoice: String?
        get() = prefs.getString("ttsVoice", null)
        set(v) = prefs.edit().putString("ttsVoice", v).apply()

    /** True = 随机顺序朗读；false = 按原文顺序。 */
    var shuffle: Boolean
        get() = prefs.getBoolean("shuffle", false)
        set(v) = prefs.edit().putBoolean("shuffle", v).apply()

    var speakMode: SpeakMode
        get() = SpeakMode.of(prefs.getString("speakMode", null))
        set(v) = prefs.edit().putString("speakMode", v.name).apply()

    var speechRate: Float
        get() = prefs.getFloat("rate", 1.0f)
        set(v) = prefs.edit().putFloat("rate", v).apply()

    /**
     * Carry reading positions over from the versioned ids used by earlier builds,
     * and drop corpora that older versions copied into the preferences.
     */
    fun migrate() {
        if (prefs.getInt("migrated", 0) >= MIGRATION) return
        val e = prefs.edit()
        // Reading position only grows, so the furthest of the old ids is the right one to keep.
        for ((old, new) in LEGACY_IDS) {
            val from = prefs.getInt("pos_$old", 0)
            if (from > prefs.getInt("pos_$new", 0)) e.putInt("pos_$new", from)
            e.remove("pos_$old")
        }
        e.putInt("migrated", MIGRATION).apply()
        val stored = JSONArray(prefs.getString("passages", "[]"))
        if ((0 until stored.length()).any { stored.getJSONObject(it).getString("id").startsWith(BUILTIN_PREFIX) }) {
            write(userPassages())
        }
    }

    companion object {
        /** Every bundled corpus id starts with this. */
        const val BUILTIN_PREFIX = "builtin-"
        const val KNOWN_ID = "builtin-known"
        const val UNKNOWN_ID = "builtin-unknown"

        /** (asset file, passage id, title). */
        private val BUILTINS = listOf(
            Triple("phrases.txt", "builtin-phrases", "日常英语短语"),
            Triple("phrases2.txt", "builtin-spoken", "美国日常口语与职场用语"),
            Triple("vocab.txt", "builtin-vocab", "生词本"),
            Triple("daily.txt", "builtin-daily", "每日地道英语短语"),
            Triple("cet6.txt", "builtin-cet6", "六级核心词汇"),
        )

        /** Ids used by earlier builds -> the stable id now in use. */
        private val LEGACY_IDS = listOf(
            "builtin-phrases-v3" to "builtin-phrases",
            "builtin-phrases-v2" to "builtin-phrases",
            "builtin-phrases-2-v3" to "builtin-spoken",
            "builtin-phrases-2-v2" to "builtin-spoken",
            "builtin-phrases-2" to "builtin-spoken",
            "builtin-vocab-v3" to "builtin-vocab",
            "builtin-daily-v2" to "builtin-daily",
        )

        /** Bump to re-run [migrate]. */
        private const val MIGRATION = 2

        @Volatile private var cache: List<Passage>? = null
        @Volatile private var movesCache: List<Moved>? = null

        private fun titleOf(bucket: Bucket) = if (bucket == Bucket.KNOWN) "已掌握" else "未掌握"

        private fun titleOf(id: String) = BUILTINS.firstOrNull { it.second == id }?.third ?: ""

        /**
         * Parse the bundled corpora. Each asset line is `phrase | example | 中文`
         * and becomes "Example || phrase：中文 || phrase": the example is displayed,
         * the note explains it, and TTS reads phrase → 中文 → example.
         */
        fun builtins(context: Context): List<Passage> {
            cache?.let { return it }
            synchronized(this) {
                cache?.let { return it }
                val now = System.currentTimeMillis()
                val list = BUILTINS.mapNotNull { (asset, id, title) ->
                    val lines = try {
                        context.assets.open(asset).bufferedReader().readLines()
                    } catch (e: Exception) {
                        return@mapNotNull null
                    }.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    val body = lines.mapNotNull { line ->
                        val parts = line.split(" | ")
                        if (parts.size != 3) return@mapNotNull null
                        val (phrase, example, cn) = parts.map { it.trim() }
                        "$example${Sentences.NOTE_SEPARATOR}$phrase：$cn${Sentences.NOTE_SEPARATOR}$phrase"
                    }
                    if (body.isEmpty()) null
                    else Passage(id, "$title ${body.size} 条", body.joinToString("\n"), now)
                }
                cache = list
                return list
            }
        }

        /** One-line preview of a passage body: spoken text only, notes stripped. */
        fun previewOf(body: String, max: Int = 80): String =
            Sentences.split(body).joinToString(" ") { it.text }.take(max)

        /** Title for a passage: its first non-empty line, shortened. */
        fun titleOfBody(body: String): String =
            body.lineSequence().map { it.substringBefore(Sentences.NOTE_SEPARATOR).trim() }
                .firstOrNull { it.isNotEmpty() }?.take(60) ?: "Untitled"

        fun newId(): String = System.currentTimeMillis().toString(36)
    }
}
