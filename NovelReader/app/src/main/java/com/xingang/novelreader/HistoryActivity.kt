package com.xingang.novelreader

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** The last [Library.HISTORY_LIMIT] pages visited, newest first. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var library: Library
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var count: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        library = Library(this)
        list = findViewById(R.id.historyList)
        empty = findViewById(R.id.empty)
        count = findViewById(R.id.count)
        list.layoutManager = LinearLayoutManager(this)

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<Button>(R.id.clearButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.history_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm) { _, _ -> library.clearHistory(); refresh() }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val entries = library.history()
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        count.text = getString(R.string.history_count, entries.size, Library.HISTORY_LIMIT)
        list.adapter = HistoryAdapter(entries,
            onClick = { ReaderActivity.start(this, it.url) },
            onLongClick = { e ->
                AlertDialog.Builder(this)
                    .setTitle(e.title)
                    .setItems(arrayOf(getString(R.string.remove))) { _, _ -> library.removeHistory(e.url); refresh() }
                    .show()
            })
    }

    private class HistoryAdapter(
        private val entries: List<HistoryEntry>,
        private val onClick: (HistoryEntry) -> Unit,
        private val onLongClick: (HistoryEntry) -> Unit,
    ) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.bookTitle)
            val url: TextView = v.findViewById(R.id.bookChapter)
            val time: TextView = v.findViewById(R.id.bookTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false))

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(h: Holder, i: Int) {
            val e = entries[i]
            h.title.text = e.title.ifEmpty { e.url }
            h.url.text = e.url
            h.time.text = DateUtils.getRelativeTimeSpanString(
                e.visitedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            h.itemView.setOnClickListener { onClick(e) }
            h.itemView.setOnLongClickListener { onLongClick(e); true }
        }
    }
}
