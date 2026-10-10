package com.xingang.ttsreader.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.xingang.ttsreader.Library
import com.xingang.ttsreader.R
import com.xingang.ttsreader.ReaderEngine

/** Saved passages; tapping one starts reading it and opens the reader screen. */
class PassageListScreen(carContext: CarContext) : Screen(carContext) {

    init {
        // Passages may have been added on the phone side since this screen was built.
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) = invalidate()
        })
    }

    override fun onGetTemplate(): Template {
        val passages = Library(carContext).passages()
        if (passages.isEmpty()) {
            return MessageTemplate.Builder(carContext.getString(R.string.car_no_passages))
                .setTitle(carContext.getString(R.string.app_name))
                .setHeaderAction(Action.APP_ICON)
                .build()
        }
        val list = ItemList.Builder()
        for (p in passages.take(carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
            .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST))) {
            list.addItem(
                Row.Builder()
                    .setTitle(p.title)
                    .addText(Library.previewOf(p.body))
                    .setOnClickListener {
                        ReaderEngine.load(p)
                        screenManager.push(ReaderScreen(carContext))
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .build()
    }
}
