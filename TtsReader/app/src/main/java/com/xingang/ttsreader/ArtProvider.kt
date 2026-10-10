package com.xingang.ttsreader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Serves the rendered artwork as `content://` images. Car media UIs (Android
 * Auto, the Automotive media centre) load artwork through URIs and ignore
 * in-line bitmaps, so this is what makes the text "covers" and number badges
 * actually show up there.
 *
 * URIs: `content://<authority>/badge/<n>` and `content://<authority>/art/<passageId>/<index>`.
 */
class ArtProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val segs = uri.pathSegments
        val bitmap: Bitmap = when {
            segs.size == 2 && segs[0] == "badge" -> SentenceArt.badge(segs[1].toIntOrNull() ?: 0)
            segs.size == 3 && segs[0] == "art" -> {
                val passage = Library(ctx).find(segs[1]) ?: return null
                val sentences = Sentences.split(passage.body)
                val i = segs[2].toIntOrNull() ?: return null
                val sentence = sentences.getOrNull(i) ?: return null
                SentenceArt.nowPlaying(sentence, i, sentences.size)
            }
            else -> return null
        }
        val dir = File(ctx.cacheDir, "art").apply { mkdirs() }
        val file = File(dir, (uri.encodedPath + "?" + uri.encodedQuery).hashCode().toString() + ".png")
        if (!file.exists()) {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.xingang.ttsreader.art"

        /** Bump when the rendering changes so car UIs do not reuse cached images. */
        private const val RENDER_VERSION = "v4"

        fun badgeUri(n: Int): Uri = Uri.parse("content://$AUTHORITY/badge/$n?$RENDER_VERSION")
        fun artUri(passageId: String, index: Int): Uri = Uri.parse("content://$AUTHORITY/art/$passageId/$index?$RENDER_VERSION")
    }
}
