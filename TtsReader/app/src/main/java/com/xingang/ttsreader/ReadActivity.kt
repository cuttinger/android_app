package com.xingang.ttsreader

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Phone reading screen: the sentences, the one being spoken highlighted, and transport controls. */
class ReadActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var playButton: ImageButton
    private lateinit var rateView: TextView
    private lateinit var orderButton: TextView
    private lateinit var modeButton: TextView
    private lateinit var editButton: TextView
    private lateinit var listHost: View
    private val adapter = SentenceAdapter { ReaderEngine.seek(it) }
    private var lastIndex = -1
    /** Swiping entries into 已掌握 / 未掌握 is only enabled in edit mode. */
    private var editing = false
    private lateinit var swipeHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_read)
        list = findViewById(R.id.sentenceList)
        title = findViewById(R.id.title)
        status = findViewById(R.id.status)
        playButton = findViewById(R.id.playButton)
        rateView = findViewById(R.id.rate)
        orderButton = findViewById(R.id.orderButton)
        modeButton = findViewById(R.id.modeButton)
        editButton = findViewById(R.id.editButton)
        listHost = findViewById(R.id.listHost)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        playButton.setOnClickListener { ReaderEngine.toggle() }
        findViewById<ImageButton>(R.id.prevButton).setOnClickListener { ReaderEngine.previous() }
        findViewById<ImageButton>(R.id.nextButton).setOnClickListener { ReaderEngine.next() }
        findViewById<View>(R.id.rateSlower).setOnClickListener { ReaderEngine.setRate(ReaderEngine.state.value.rate - 0.1f) }
        findViewById<View>(R.id.rateFaster).setOnClickListener { ReaderEngine.setRate(ReaderEngine.state.value.rate + 0.1f) }
        findViewById<View>(R.id.voiceButton).setOnClickListener { pickEngine() }
        orderButton.setOnClickListener {
            ReaderEngine.toggleShuffle()
            Toast.makeText(
                this,
                if (ReaderEngine.state.value.shuffle) R.string.shuffle_on else R.string.shuffle_off,
                Toast.LENGTH_SHORT,
            ).show()
        }

        modeButton.setOnClickListener { pickSpeakMode() }
        editButton.setOnClickListener { setEditing(!editing) }
        swipeHelper = ItemTouchHelper(swipeCallback).also { it.attachToRecyclerView(list) }
        setEditing(false)

        loadFromIntent(intent)
        lifecycleScope.launch { ReaderEngine.state.collect { render(it) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        loadFromIntent(intent)
    }

    private fun loadFromIntent(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_ID) ?: return
        if (ReaderEngine.state.value.passageId == id) return
        Library(this).find(id)?.let { ReaderEngine.load(it) }
    }

    private fun render(s: ReaderEngine.State) {
        title.text = s.title
        status.text = when {
            s.error != null -> s.error
            !s.ready -> getString(R.string.tts_loading)
            s.hasContent -> getString(R.string.progress, s.index + 1, s.sentences.size)
            else -> ""
        }
        playButton.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        rateView.text = String.format("%.1fx", s.rate)
        orderButton.setText(if (s.shuffle) R.string.order_shuffle else R.string.order_sequential)
        modeButton.setText(
            when (s.speakMode) {
                SpeakMode.FULL -> R.string.mode_tag_full
                SpeakMode.WORD_ONLY -> R.string.mode_tag_word
                SpeakMode.WORD_THEN_CN -> R.string.mode_tag_word_cn
                SpeakMode.CN_THEN_WORD -> R.string.mode_tag_cn_word
            }
        )
        adapter.update(s.sentences, s.index)
        if (s.index != lastIndex) {
            lastIndex = s.index
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(s.index, list.height / 3)
        }
    }

    private fun pickSpeakMode() {
        val modes = SpeakMode.entries
        val current = ReaderEngine.state.value.speakMode
        val labels = modes.map { getString(it.labelRes) + if (it == current) "  ✓" else "" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.choose_mode)
            .setItems(labels) { _, which -> ReaderEngine.setSpeakMode(modes[which]) }
            .show()
    }

    private fun setEditing(on: Boolean) {
        editing = on
        editButton.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        editButton.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.current_text else R.color.text)
        )
        if (on) Toast.makeText(this, R.string.edit_mode_on, Toast.LENGTH_LONG).show()
    }

    /** Left = 已掌握, right = 未掌握; both take the entry out of the passage being read. */
    private val swipeCallback = object : ItemTouchHelper.SimpleCallback(
        0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    ) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun getSwipeDirs(rv: RecyclerView, holder: RecyclerView.ViewHolder) =
            if (editing) super.getSwipeDirs(rv, holder) else 0

        override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            val bucket = if (direction == ItemTouchHelper.LEFT) Bucket.KNOWN else Bucket.UNKNOWN
            val filed = ReaderEngine.fileEntry(pos, bucket) ?: return
            val name = getString(if (bucket == Bucket.KNOWN) R.string.known else R.string.unknown)
            Snackbar.make(listHost, getString(R.string.filed_to, name, filed.headline), Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) { ReaderEngine.unfile(filed) }
                .show()
        }

        override fun onChildDraw(
            c: Canvas, rv: RecyclerView, holder: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isActive: Boolean,
        ) {
            val v = holder.itemView
            if (dX != 0f) {
                val toKnown = dX < 0
                paint.color = ContextCompat.getColor(this@ReadActivity, if (toKnown) R.color.known_bg else R.color.unknown_bg)
                if (toKnown) {
                    c.drawRect(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), paint)
                } else {
                    c.drawRect(v.left.toFloat(), v.top.toFloat(), v.left + dX, v.bottom.toFloat(), paint)
                }
                paint.color = Color.WHITE
                val label = getString(if (toKnown) R.string.known else R.string.unknown)
                val y = v.top + (v.height + paint.textSize) / 2f
                if (toKnown) {
                    c.drawText(label, v.right - paint.measureText(label) - 40f, y, paint)
                } else {
                    c.drawText(label, v.left + 40f, y, paint)
                }
            }
            super.onChildDraw(c, rv, holder, dX, dY, actionState, isActive)
        }
    }

    /** Step 1: choose a TTS engine among those installed; step 2: a voice of that engine. */
    private fun pickEngine() {
        val engines = ReaderEngine.engines()
        if (engines.isEmpty()) return
        val current = ReaderEngine.currentEngine()
        val labels = engines.map { it.label + if (it.name == current) "  ✓" else "" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.choose_engine)
            .setItems(labels) { _, which ->
                val chosen = engines[which].name
                if (chosen != current) {
                    ReaderEngine.setEngine(chosen)
                    list.postDelayed({ pickVoice() }, 1500)
                } else {
                    pickVoice()
                }
            }
            .show()
    }

    private fun pickVoice() {
        val voices = ReaderEngine.voices()
        if (voices.isEmpty()) return
        val current = ReaderEngine.currentVoice()
        val labels = voices.map { v ->
            val quality = when {
                v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "★★★"
                v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "★★"
                else -> "★"
            }
            val net = if (v.isNetworkConnectionRequired) " · 需联网" else ""
            "${v.name}  $quality$net" + if (v.name == current) "  ✓" else ""
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.choose_voice)
            .setItems(labels) { _, which -> ReaderEngine.setVoice(voices[which].name) }
            .show()
    }

    private class SentenceAdapter(private val onClick: (Int) -> Unit) : RecyclerView.Adapter<SentenceAdapter.Holder>() {
        private var items: List<Sentence> = emptyList()
        private var current = -1

        fun update(list: List<Sentence>, index: Int) {
            val old = current
            if (items !== list) { items = list; notifyDataSetChanged() }
            current = index
            if (old != index) { if (old >= 0) notifyItemChanged(old); notifyItemChanged(index) }
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val text: TextView = v.findViewById(R.id.sentence)
            val note: TextView = v.findViewById(R.id.note)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_sentence, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, i: Int) {
            val ctx = h.itemView.context
            val isCurrent = i == current
            h.text.text = items[i].text
            h.note.text = items[i].note ?: ""
            h.note.visibility = if (items[i].note != null) View.VISIBLE else View.GONE
            h.text.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            h.text.setTextColor(ContextCompat.getColor(ctx, if (isCurrent) R.color.current_text else R.color.text))
            h.itemView.setBackgroundColor(ContextCompat.getColor(ctx, if (isCurrent) R.color.current_bg else android.R.color.transparent))
            h.itemView.setOnClickListener { onClick(i) }
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        fun start(context: Context, id: String) {
            context.startActivity(Intent(context, ReadActivity::class.java).putExtra(EXTRA_ID, id))
        }
    }
}
