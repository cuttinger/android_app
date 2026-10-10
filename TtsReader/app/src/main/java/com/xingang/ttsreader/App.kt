package com.xingang.ttsreader

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ReaderEngine.init(this)
        Library(this).migrate()
        // The templated CarAppActivity only works where a templates host exists
        // (Android Automotive OS). It is disabled in the manifest; turn it on here for cars.
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, "androidx.car.app.activity.CarAppActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
