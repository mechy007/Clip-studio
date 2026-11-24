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
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var lock: PowerManager.WakeLock? = null
    private var foregroundType: Int? = null
    private var lastUpdate: ProgressUpdate? = null
    private var activeQueue: String? = null

    private data class ProgressUpdate(
        val jobs: Map<Work, String>, val download: GuidedDownloadProgress?, val remaining: Int
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Video processing", NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            StudioSession.state.collect { state ->
                if (foregroundType != null) updateWork(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE_DOWNLOAD -> {
                if (StudioSession.state.value.activeDownloadQueueId != null) StudioSession.cancel(Work.IMPORT)
                if (foregroundType == null) stopSelf(startId)
            }
            CANCEL -> {
                StudioSession.cancelAll()
                if (foregroundType == null) stopSelf(startId)
            }
            else -> updateWork(StudioSession.state.value, starting = true)
        }
        return START_NOT_STICKY
    }

    private fun updateWork(state: StudioState, starting: Boolean = false) {
        val queue = state.activeDownloadQueueId
        if (activeQueue != null && queue == null) showQueueOutcome(state, requireNotNull(activeQueue))
        if (queue != null && queue != activeQueue) notifications.cancel(QUEUE_NOTIFICATION_ID)
        activeQueue = queue
        if (state.jobs.isEmpty() && !starting) {
            stopSelf()
            return
        }

        val remaining = if (queue != null) state.project?.downloadQueue?.size ?: 0 else 0
        val update = ProgressUpdate(state.jobs, state.guidedDownload, remaining)
        val type = workType(state.jobs.keys)
        if (type != foregroundType) {
            try {
                startForeground(NOTIFICATION_ID, runningNotification(update), type)
                foregroundType = type
                if (lock?.isHeld != true) {
                    lock = getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClipStudio:Processing")
                        .apply { acquire(6L * 60 * 60 * 1_000) }
                }
            } catch (_: IllegalStateException) {
                StudioSession.stopBackgroundWork("Android paused background processing. Open Clip Studio to resume.")
                stopSelf()
                return
            }
        } else if (lastUpdate != update) {
            post(NOTIFICATION_ID, runningNotification(update))
        }
        lastUpdate = update
        if (state.jobs.isEmpty()) stopSelf()
    }

    private fun workType(jobs: Set<Work>): Int {
        if (Build.VERSION.SDK_INT < 35) return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        var type = 0
        if (jobs.isEmpty() || jobs.any { it == Work.IMPORT || it == Work.STORAGE }) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (jobs.any { it != Work.IMPORT && it != Work.STORAGE }) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        }
        return type
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val message = "Android paused background processing. Open Clip Studio to resume."
        activeQueue?.let { showQueueOutcome(StudioSession.state.value, it, message) }
        activeQueue = null
        foregroundType = null
        StudioSession.stopBackgroundWork(message)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        lock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun runningNotification(update: ProgressUpdate): Notification {
        val lines = update.jobs.values.toList().ifEmpty { listOf("Preparing…") }
        val text = if (update.remaining > 0) lines + "${update.remaining} downloads remaining" else lines
        val progress = update.download?.takeUnless { it.assembling || it.percent == 0 }?.percent
        val builder = baseNotification("Clip Studio", text.joinToString("\n"))
            .setProgress(100, progress ?: 0, progress == null)
            .setOngoing(true)
        if (update.remaining > 0) builder.addAction(action("Pause downloads", PAUSE_DOWNLOAD, 2))
        else builder.addAction(action("Cancel jobs", CANCEL, 1))
        return builder.build()
    }

    private fun showQueueOutcome(state: StudioState, projectId: String, message: String? = null) {
        val project = state.library.projects.firstOrNull { it.id == projectId } ?: return
        val remaining = project.downloadQueue.size
        val status = if (remaining == 0) "Download queue complete" else
            message ?: state.status.ifBlank { "$remaining downloads waiting to resume" }
        val builder = baseNotification(if (remaining == 0) "Downloads complete" else "Download queue paused", status)
            .setAutoCancel(true)
        if (remaining > 0) {
            val resume = PendingIntent.getActivity(this, 3, openIntent()
                .setAction(RESUME_DOWNLOADS).putExtra(PROJECT_ID, projectId), pendingFlags)
            builder.addAction(Notification.Action.Builder(null, "Resume downloads", resume).build())
        }
        post(QUEUE_NOTIFICATION_ID, builder.build())
    }

    private fun baseNotification(title: String, text: String): Notification.Builder =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(this, 0, openIntent(), pendingFlags))
            .setOnlyAlertOnce(true)

    private fun action(label: String, action: String, request: Int): Notification.Action =
        Notification.Action.Builder(null, label, PendingIntent.getService(this, request,
            Intent(this, StudioWorkService::class.java).setAction(action), pendingFlags)).build()

    private fun openIntent() = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun post(id: Int, notification: Notification) {
        if (notifications.areNotificationsEnabled()) notifications.notify(id, notification)
    }

    companion object {
        internal const val RESUME_DOWNLOADS = "com.clipstudio.app.RESUME_DOWNLOADS"
        internal const val PROJECT_ID = "com.clipstudio.app.PROJECT_ID"
        private const val CHANNEL = "studio-processing"
        private const val NOTIFICATION_ID = 601
        private const val QUEUE_NOTIFICATION_ID = 602
        private const val CANCEL = "com.clipstudio.app.CANCEL_WORK"
        private const val PAUSE_DOWNLOAD = "com.clipstudio.app.PAUSE_DOWNLOAD"
        private const val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
