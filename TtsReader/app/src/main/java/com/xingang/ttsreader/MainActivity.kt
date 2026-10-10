package com.xingang.ttsreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Entry screen: paste or type English text, save it, and pick a passage to read. */
class MainActivity : AppCompatActivity() {

    private lateinit var library: Library
    private lateinit var input: EditText
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var restoreButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // On Android Automotive OS the launcher icon should open the templated car UI.
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) && intent?.action == Intent.ACTION_MAIN) {
            startActivity(Intent().setClassName(this, "androidx.car.app.activity.CarAppActivity"))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        library = Library(this)
        input = findViewById(R.id.input)
        list = findViewById(R.id.passageList)
        empty = findViewById(R.id.empty)
        list.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.readButton).setOnClickListener { saveAndRead() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { input.text.clear() }
        restoreButton = findViewById(R.id.restoreButton)
        restoreButton.setOnClickListener { library.restoreHidden(); refresh() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { input.setText(it) }
    }

    private fun saveAndRead() {
        val body = input.text.toString().trim()
        if (body.isEmpty()) {
            Toast.makeText(this, R.string.empty_text, Toast.LENGTH_SHORT).show()
            return
        }
        val passage = Passage(Library.newId(), Library.titleOfBody(body), body, System.currentTimeMillis())
        library.save(passage)
        input.text.clear()
        ReadActivity.start(this, passage.id)
    }

    private fun refresh() {
        val items = library.passages()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val hidden = library.hiddenCount()
        restoreButton.visibility = if (hidden > 0) View.VISIBLE else View.GONE
        restoreButton.text = getString(R.string.restore_hidden, hidden)
        list.adapter = PassageAdapter(items,
            onClick = { ReadActivity.start(this, it.id) },
            onLongClick = { p ->
                if (p.isBuiltin) {
                    // Bundled corpora cannot be edited, but they can be hidden from the list.
                    AlertDialog.Builder(this)
                        .setTitle(p.title)
                        .setItems(arrayOf(getString(R.string.remove))) { _, _ ->
                            library.remove(p.id); refresh()
                        }
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(p.title)
                        .setItems(arrayOf(getString(R.string.edit), getString(R.string.remove))) { _, which ->
                            when (which) {
                                0 -> { input.setText(p.body); library.remove(p.id); refresh() }
                                1 -> { library.remove(p.id); refresh() }
                            }
                        }
                        .show()
                }
            })
    }

    private class PassageAdapter(
        private val items: List<Passage>,
        private val onClick: (Passage) -> Unit,
        private val onLongClick: (Passage) -> Unit,
    ) : RecyclerView.Adapter<PassageAdapter.Holder>() {

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.title)
            val preview: TextView = v.findViewById(R.id.preview)
            val time: TextView = v.findViewById(R.id.time)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_passage, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, i: Int) {
            val p = items[i]
            h.title.text = p.title
            h.preview.text = Library.previewOf(p.body, 200)
            h.time.text = DateUtils.getRelativeTimeSpanString(p.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            h.itemView.setOnClickListener { onClick(p) }
            h.itemView.setOnLongClickListener { onLongClick(p); true }
        }
    }
}
