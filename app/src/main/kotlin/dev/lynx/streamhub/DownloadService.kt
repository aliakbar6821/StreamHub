package dev.lynx.streamhub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/** User-initiated transfers only. Paused jobs do not keep a foreground service alive. */
class DownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val watched = linkedSetOf<Long>()
    private var monitor: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.createChannel(this)
        Store.init(this)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, DownloadNotifications.SUMMARY_ID,
            DownloadNotifications.summary(this), if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        val id = intent?.getLongExtra("download_id", -1L) ?: -1L
        if (id > 0) watched.add(id)
        val entry = Store.downloads.firstOrNull { it.downloadId == id }
        when (intent?.action) {
            PAUSE -> Downloader.pause(this, id)
            RESUME -> if (entry != null) Downloader.resume(this, entry, serviceAlreadyStarted = true)
            CANCEL -> {
                if (entry != null && Downloader.status(this, id).first != 8) Downloader.remove(this, entry)
                watched.remove(id); DownloadNotifications.cancel(this, id)
            }
        }
        if (monitor?.isActive != true) monitor = serviceScope.launch {
            while (isActive) {
                val entries = Store.downloads.toList().filter { it.downloadId in watched }
                val rows = withContext(Dispatchers.IO) {
                    entries.map { it to Downloader.status(this@DownloadService, it.downloadId) }
                }
                rows.forEach { (download, state) -> DownloadNotifications.update(this@DownloadService, download, state) }
                val existing = entries.map { it.downloadId }.toSet()
                watched.filter { it !in existing }.toList().forEach {
                    DownloadNotifications.cancel(this@DownloadService, it); watched.remove(it)
                }
                if (!Downloader.hasActiveWork()) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(); break
                }
                delay(1000L)
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        Downloader.pauseAll(this)
        Store.downloads.filter { it.downloadId in watched }.forEach {
            DownloadNotifications.update(this, it, Downloader.status(this, it.downloadId))
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        Downloader.pauseAll(this)
        serviceScope.cancel()
        super.onDestroy()
    }
    companion object {
        const val START = "dev.lynx.streamhub.download.START"
        const val PAUSE = "dev.lynx.streamhub.download.PAUSE"
        const val RESUME = "dev.lynx.streamhub.download.RESUME"
        const val CANCEL = "dev.lynx.streamhub.download.CANCEL"
        fun ensureRunning(context: Context, id: Long): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)
                .setAction(START).putExtra("download_id", id))
            true
        } catch (_: RuntimeException) { false }
    }
}

object DownloadNotifications {
    const val SUMMARY_ID = 9081
    private const val CHANNEL = "streamhub_downloads_v1"
    private const val GROUP = "streamhub_downloads"
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }
    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).putExtra("open_downloads", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun base(context: Context) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(dev.lynx.streamhub.R.drawable.ic_download_notification)
        .setOnlyAlertOnce(true).setSilent(true).setContentIntent(open(context)).setGroup(GROUP)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
    fun summary(context: Context): Notification = base(context).setContentTitle("streamHub downloads")
        .setContentText("Downloading files to Downloads/streamHub")
        .setGroupSummary(true).setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
    private fun action(context: Context, id: Long, action: String): PendingIntent = PendingIntent.getService(context, 0,
        Intent(context, DownloadService::class.java).setAction(action).putExtra("download_id", id)
            .setData(Uri.parse("streamhub-download://$id/${action.substringAfterLast('.')}")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun amount(bytes: Long): String = String.format(java.util.Locale.US, "%.1f MB", bytes.coerceAtLeast(0) / 1_048_576.0)
    fun update(context: Context, entry: DownloadEntry, state: Pair<Int, Int>) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val id = entry.downloadId
        val st = state.first
        val bytes = Downloader.savedBytes(context, id)
        val total = Downloader.total(context, id)
        val label = when (st) { 8 -> "Downloaded"; 4 -> "Paused"; 16 -> "Failed"; 1 -> "Waiting / retrying"; else -> "Downloading" }
        val text = "$label · ${amount(bytes)}" + if (total > 0) " / ${amount(total)}" else ""
        val name = context.getSharedPreferences("dl_names", Context.MODE_PRIVATE).getString("$id.name", null)
            ?: entry.item.title
        val builder = base(context).setContentTitle(name).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${entry.item.title}\n$text"))
            .setOngoing(st == 1 || st == 2).setAutoCancel(st == 8 || st == 16)
        if (st != 8 && st != 16) builder.setProgress(100, state.second.coerceIn(0, 100), total <= 0 && state.second == 0 && st != 4)
        if (st == 1 || st == 2) builder.addAction(android.R.drawable.ic_media_pause, "Pause", action(context, id, DownloadService.PAUSE))
        if (st == 4 || st == 16) builder.addAction(android.R.drawable.ic_media_play,
            if (st == 16) "Retry" else "Resume", action(context, id, DownloadService.RESUME))
        if (st != 8) builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", action(context, id, DownloadService.CANCEL))
        runCatching { manager.notify("download:$id", 1, builder.build()) }
    }
    fun cancel(context: Context, id: Long) { NotificationManagerCompat.from(context).cancel("download:$id", 1) }
}
