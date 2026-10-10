package com.xingang.ttsreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and the TTS engine) alive while
 * reading, and shows a media-style notification with transport controls.
 */
class ReaderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReaderEngine.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> ReaderEngine.toggle()
            ACTION_NEXT -> ReaderEngine.next()
            ACTION_PREV -> ReaderEngine.previous()
            ACTION_STOP -> { ReaderEngine.pause(); stopSelf(); return START_NOT_STICKY }
        }
        goForeground(ReaderEngine.state.value)
        if (job == null) {
            job = scope.launch {
                ReaderEngine.state.collect { s ->
                    if (s.playing) goForeground(s) else stopForeground(STOP_FOREGROUND_DETACH).also { notify(s) }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun goForeground(s: ReaderEngine.State) {
        val n = notification(s)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notify(s: ReaderEngine.State) {
        if (!s.hasContent) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(s))
    }

    private fun notification(s: ReaderEngine.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0, ReaderEngine.openIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(code: Int, act: String, icon: Int, label: String) = NotificationCompat.Action(
            icon, label,
            PendingIntent.getService(
                this, code, Intent(this, ReaderService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.title.ifEmpty { getString(R.string.app_name) })
            .setContentText(s.current?.text ?: "")
            .setSubText("${s.index + 1}/${s.sentences.size}")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(s.playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action(1, ACTION_PREV, R.drawable.ic_prev, getString(R.string.previous)))
            .addAction(
                if (s.playing) action(2, ACTION_TOGGLE, R.drawable.ic_pause, getString(R.string.pause))
                else action(2, ACTION_TOGGLE, R.drawable.ic_play, getString(R.string.play))
            )
            .addAction(action(3, ACTION_NEXT, R.drawable.ic_next, getString(R.string.next)))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(ReaderMediaService.token)
            )
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL = "reading"
        const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_STOP = "stop"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReaderService::class.java))
        }

        /** Called when playback stops; the service drops its foreground state via the state collector. */
        fun stopForegroundIfIdle(context: Context) { /* handled by the state collector */ }
    }
}
