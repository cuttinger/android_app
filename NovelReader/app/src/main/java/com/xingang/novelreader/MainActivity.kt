package com.xingang.novelreader

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Entry screen: paste a URL, or resume one of the recently read books. */
class MainActivity : AppCompatActivity() {

    private lateinit var library: Library
    private lateinit var urlBox: EditText
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        library = Library(this)

        urlBox = findViewById(R.id.urlBox)
        list = findViewById(R.id.bookList)
        empty = findViewById(R.id.empty)
        list.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.openButton).setOnClickListener { openTyped() }
        findViewById<ImageButton>(R.id.pasteButton).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (text.isNullOrEmpty()) {
                Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            } else {
                urlBox.setText(text)
                urlBox.setSelection(text.length)
            }
        }

        findViewById<Button>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        if (!handleShare(intent) && savedInstanceState == null && intent.action == Intent.ACTION_MAIN) {
            // Cold start from the launcher: go straight back to where the reader left off.
            library.books().firstOrNull()?.let { ReaderActivity.start(this, it.lastUrl, it.key) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** A URL shared from a browser lands here. Returns true when one was opened. */
    private fun handleShare(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return false
        val url = Regex("https?://\\S+").find(text)?.value ?: return false
        ReaderActivity.start(this, url)
        return true
    }

    private fun openTyped() {
        var url = urlBox.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        ReaderActivity.start(this, url)
    }

    private fun refresh() {
        val books = library.books()
        empty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        list.adapter = BookAdapter(books,
            onClick = { ReaderActivity.start(this, it.lastUrl, it.key) },
            onLongClick = { book ->
                AlertDialog.Builder(this)
                    .setTitle(book.title)
                    .setItems(arrayOf(getString(R.string.open_catalog), getString(R.string.remove))) { _, which ->
                        when (which) {
                            0 -> (book.catalogUrl ?: book.lastUrl).let { ReaderActivity.start(this, it, book.key) }
                            1 -> { library.remove(book.key); refresh() }
                        }
                    }
                    .show()
            })
    }

    private class BookAdapter(
        private val books: List<Book>,
        private val onClick: (Book) -> Unit,
        private val onLongClick: (Book) -> Unit,
    ) : RecyclerView.Adapter<BookAdapter.Holder>() {

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.bookTitle)
            val chapter: TextView = v.findViewById(R.id.bookChapter)
            val time: TextView = v.findViewById(R.id.bookTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false))

        override fun getItemCount() = books.size

        override fun onBindViewHolder(h: Holder, i: Int) {
            val b = books[i]
            h.title.text = b.title
            h.chapter.text = b.lastChapter.ifEmpty { b.lastUrl }
            h.time.text = DateUtils.getRelativeTimeSpanString(
                b.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            h.itemView.setOnClickListener { onClick(b) }
            h.itemView.setOnLongClickListener { onLongClick(b); true }
        }
    }
}
