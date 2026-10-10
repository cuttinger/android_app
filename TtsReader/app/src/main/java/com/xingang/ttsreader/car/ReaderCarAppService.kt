package com.xingang.ttsreader.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.xingang.ttsreader.ReaderEngine

/** Android Auto entry point: hands the host a session whose first screen lists the passages. */
class ReaderCarAppService : CarAppService() {

    // Sideloaded build: accept any host (Android Auto in developer mode). A Play
    // Store build should use the templates host allow-list instead.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            ReaderEngine.init(carContext)
            // Jump straight into the reader when something is already loaded.
            return if (ReaderEngine.state.value.hasContent) ReaderScreen(carContext) else PassageListScreen(carContext)
        }
    }
}
