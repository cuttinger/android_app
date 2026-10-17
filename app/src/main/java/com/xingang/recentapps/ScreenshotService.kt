package com.xingang.recentapps

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Exists for one reason: a system screenshot is not something an ordinary app
 * can take, and [AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT] is the
 * only route open to one. The service does nothing else — it listens to no
 * events and reads no window content.
 */
class ScreenshotService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: ScreenshotService? = null

        /** False when the service is not enabled, so the caller can say so. */
        fun take(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            val service = instance ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
    }
}
