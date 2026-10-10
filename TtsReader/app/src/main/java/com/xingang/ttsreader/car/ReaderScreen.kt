package com.xingang.ttsreader.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.coroutineScope
import com.xingang.ttsreader.R
import com.xingang.ttsreader.ReaderEngine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Read-along screen for the car: a window of sentences around the one being
 * spoken (marked with ▶ and its number), refreshed as TTS advances, plus
 * play/pause and previous/next in the action strip. Tapping a row jumps to it.
 */
class ReaderScreen(carContext: CarContext) : Screen(carContext) {

    private var lastIndex = -1
    private var lastPlaying: Boolean? = null

    init {
        lifecycle.coroutineScope.launch {
            ReaderEngine.state.collect { s ->
                if (s.index != lastIndex || s.playing != lastPlaying || s.error != null) {
                    lastIndex = s.index
                    lastPlaying = s.playing
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val s = ReaderEngine.state.value
        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        val list = ItemList.Builder()

        if (!s.hasContent) {
            list.addItem(Row.Builder().setTitle(carContext.getString(R.string.car_nothing_loaded)).build())
        } else {
            s.error?.let { list.addItem(Row.Builder().setTitle("⚠ $it").build()) }
            // Show the previous sentence for context, then the current one and what follows.
            val start = (s.index - 1).coerceAtLeast(0)
            val end = (start + limit).coerceAtMost(s.sentences.size)
            for (i in start until end) {
                val isCurrent = i == s.index
                val row = Row.Builder()
                    .setTitle(if (isCurrent) "▶ ${i + 1} / ${s.sentences.size}" else "${i + 1}")
                    .addText(s.sentences[i].text)
                    .setOnClickListener { ReaderEngine.seek(i) }
                s.sentences[i].note?.let { row.addText(it) }
                list.addItem(row.build())
            }
        }

        // The host allows two action-strip buttons on a list: play/pause and next.
        // "Previous" is the row above the current sentence (tap it).
        val actions = ActionStrip.Builder()
            .addAction(
                if (s.playing) action(R.drawable.ic_pause, R.string.pause) { ReaderEngine.pause() }
                else action(R.drawable.ic_play, R.string.play) { ReaderEngine.play() }
            )
            .addAction(action(R.drawable.ic_next, R.string.next) { ReaderEngine.next() })
            .build()

        return ListTemplate.Builder()
            .setTitle(s.title.ifEmpty { carContext.getString(R.string.app_name) })
            .setHeaderAction(Action.BACK)
            .setActionStrip(actions)
            .setSingleList(list.build())
            .build()
    }

    /** Icon-only: the host allows at most one action-strip button with a text title. */
    @Suppress("UNUSED_PARAMETER")
    private fun action(icon: Int, label: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener { onClick() }
            .build()
}
