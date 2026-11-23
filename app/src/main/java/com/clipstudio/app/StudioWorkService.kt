package com.clipstudio.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class StudioWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Video processing", NotificationManager.IMPORTANCE_LOW))
        lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClipStudio:Processing").apply { acquire(60 * 60 * 1_000L) }
        val serviceType = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(NOTIFICATION_ID, notification("Preparing…"), serviceType)
        scope.launch {
            StudioSession.state.collect { current ->
                if (current.jobs.isEmpty()) stopSelf()
                else getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(current.jobs.values.joinToString("\n")))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) StudioSession.cancelAll()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        StudioSession.cancelAll()
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        lock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, StudioWorkService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Clip Studio")
            .setContentText(text.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Cancel jobs", cancel).build())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "studio-processing"
        private const val NOTIFICATION_ID = 601
        private const val CANCEL = "com.clipstudio.app.CANCEL_WORK"
    }
}
