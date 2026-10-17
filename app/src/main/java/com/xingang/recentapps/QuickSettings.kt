package com.xingang.recentapps

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast

/** What a tile does when tapped, which is also what its subtitle says. */
enum class QuickKind {
    /** Changed in place; the tile shows the current state. */
    TOGGLE,

    /** Opens the system screen that owns the setting. */
    SHORTCUT,

    /** Runs a one-off action. */
    ACTION,
}

data class QuickTile(
    val id: String,
    val labelRes: Int,
    val iconRes: Int,
    val kind: QuickKind,
)

/**
 * The quick-settings page.
 *
 * Only a little of this can actually be changed from an ordinary app: rotation
 * needs the "modify system settings" grant, the torch is open to anyone, and
 * a screenshot needs an accessibility service. Radios — hotspot, Wi-Fi,
 * Bluetooth, airplane mode — have been closed to third-party apps since
 * Android 8, so those tiles open the system screen instead of pretending to
 * flip a switch.
 */
class QuickSettings(private val context: Context) {

    private val cameras = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var torchOn = false

    init {
        runCatching {
            cameras.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchCameraId()) torchOn = enabled
                }
            }, null)
        }
    }

    fun tiles(): List<QuickTile> = TILES

    /** On/off for the two tiles that have a state; null for everything else. */
    fun stateOf(id: String): Boolean? = when (id) {
        ID_ROTATE -> Settings.System.getInt(
            context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0
        ) == 1
        ID_TORCH -> torchOn
        else -> null
    }

    fun trigger(activity: Activity, tile: QuickTile) {
        when (tile.id) {
            ID_ROTATE -> toggleRotation(activity)
            ID_TORCH -> toggleTorch(activity)
            ID_SCREENSHOT -> takeScreenshot(activity)
            ID_QR -> scanQr(activity)
            else -> SHORTCUT_INTENTS[tile.id]?.let { open(activity, it()) }
        }
    }

    // ---- the two that really toggle ----------------------------------------

    private fun toggleRotation(activity: Activity) {
        if (!Settings.System.canWrite(context)) {
            toast(R.string.needs_write_settings)
            open(
                activity,
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
            return
        }
        val now = stateOf(ID_ROTATE) == true
        Settings.System.putInt(
            context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (now) 0 else 1
        )
    }

    private fun torchCameraId(): String? = runCatching {
        cameras.cameraIdList.firstOrNull { id ->
            cameras.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    private fun toggleTorch(activity: Activity) {
        val id = torchCameraId() ?: run { toast(R.string.no_torch); return }
        runCatching { cameras.setTorchMode(id, !torchOn) }
            .onFailure { toast(R.string.no_torch) }
    }

    // ---- the ones that need help -------------------------------------------

    private fun takeScreenshot(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast(R.string.screenshot_unsupported)
            return
        }
        if (!ScreenshotService.take()) {
            toast(R.string.screenshot_needs_service)
            open(activity, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    /** No scanner of our own: hand it to whichever app on the phone has one. */
    private fun scanQr(activity: Activity) {
        val candidates = listOf(
            Intent("com.google.zxing.client.android.SCAN"),
            Intent(Intent.ACTION_VIEW).setComponent(
                ComponentName("com.google.android.googlequicksearchbox", "com.google.android.apps.search.lens.LensExportedActivity")
            ),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )
        for (intent in candidates) {
            if (runCatching { activity.startActivity(intent); true }.getOrDefault(false)) return
        }
        toast(R.string.no_scanner)
    }

    // ---- plumbing ------------------------------------------------------------

    private fun open(activity: Activity, intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.screen_not_found)
        } catch (e: SecurityException) {
            toast(R.string.screen_not_found)
        }
    }

    private fun toast(res: Int) = Toast.makeText(context, res, Toast.LENGTH_SHORT).show()

    companion object {
        const val ID_ROTATE = "rotate"
        const val ID_TORCH = "torch"
        const val ID_SCREENSHOT = "screenshot"
        const val ID_QR = "qr"

        private val SHORTCUT_INTENTS: Map<String, () -> Intent> = mapOf(
            "hotspot" to {
                Intent().setComponent(
                    ComponentName("com.android.settings", "com.android.settings.TetherSettings")
                )
            },
            "wifi" to { Intent(Settings.ACTION_WIFI_SETTINGS) },
            "bluetooth" to { Intent(Settings.ACTION_BLUETOOTH_SETTINGS) },
            "airplane" to { Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS) },
            "battery" to { Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS) },
            "developer" to { Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
        )

        private val TILES = listOf(
            QuickTile(ID_ROTATE, R.string.qs_rotate, R.drawable.qs_rotate, QuickKind.TOGGLE),
            QuickTile(ID_TORCH, R.string.qs_torch, R.drawable.qs_torch, QuickKind.TOGGLE),
            QuickTile(ID_SCREENSHOT, R.string.qs_screenshot, R.drawable.qs_screenshot, QuickKind.ACTION),
            QuickTile(ID_QR, R.string.qs_qr, R.drawable.qs_qr, QuickKind.ACTION),
            QuickTile("hotspot", R.string.qs_hotspot, R.drawable.qs_hotspot, QuickKind.SHORTCUT),
            QuickTile("wifi", R.string.qs_wifi, R.drawable.qs_wifi, QuickKind.SHORTCUT),
            QuickTile("bluetooth", R.string.qs_bluetooth, R.drawable.qs_bluetooth, QuickKind.SHORTCUT),
            QuickTile("airplane", R.string.qs_airplane, R.drawable.qs_airplane, QuickKind.SHORTCUT),
            QuickTile("battery", R.string.qs_battery, R.drawable.qs_battery, QuickKind.SHORTCUT),
            QuickTile("developer", R.string.qs_developer, R.drawable.qs_developer, QuickKind.SHORTCUT),
        )
    }
}
