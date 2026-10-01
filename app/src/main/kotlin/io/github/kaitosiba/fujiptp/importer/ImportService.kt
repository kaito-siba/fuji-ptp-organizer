package io.github.kaitosiba.fujiptp.importer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.kaitosiba.fujiptp.FujiPtpApp
import io.github.kaitosiba.fujiptp.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 取り込み中にプロセスを前面に保ち、進捗を通知に出す。実際の処理は [ImportManager]。 */
class ImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as FujiPtpApp).container.importManager
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            progressNotification(manager.progress.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (collectJob == null) {
            collectJob = scope.launch {
                // StateFlow なので、通知の更新中に来た途中の値は捨てられて最新値だけが届く
                manager.progress.collect { progress ->
                    val notifications = getSystemService(NotificationManager::class.java)
                    if (progress.running) {
                        notifications.notify(NOTIFICATION_ID, progressNotification(progress))
                        delay(UPDATE_INTERVAL_MILLIS) // 通知の更新頻度を抑える
                    } else {
                        ServiceCompat.stopForeground(this@ImportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        progress.message?.let { notifications.notify(DONE_NOTIFICATION_ID, doneNotification(it)) }
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "取り込み", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(progress: ImportProgress) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("取り込み中 ${progress.completedFiles + progress.failedFiles + 1} / ${progress.totalFiles}")
            .setContentText(progress.currentFile ?: "")
            .setProgress(PROGRESS_MAX, (progress.fraction * PROGRESS_MAX).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun doneNotification(message: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("取り込み完了")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    private companion object {
        const val CHANNEL_ID = "import"
        const val NOTIFICATION_ID = 1
        const val DONE_NOTIFICATION_ID = 2
        const val PROGRESS_MAX = 1000
        const val UPDATE_INTERVAL_MILLIS = 500L
    }
}
