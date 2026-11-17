package dev.lynx.streamhub

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import dev.lynx.streamhub.api.Episode
import dev.lynx.streamhub.api.MediaItem
import dev.lynx.streamhub.api.Stream
import java.io.File

object Downloader {
    /** Returns false if this episode was already downloaded. */
    fun enqueue(context: Context, item: MediaItem, ep: Episode, s: Stream, label: String = "", sd: Boolean = false): Boolean {
        if (Store.hasDownload(ep.id)) return false
        val ext = s.url.substringBefore('?').substringAfterLast('.', "mp4").take(5)
        val name = "${item.id.hashCode()}_${ep.id.hashCode()}.$ext".replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dirs = context.getExternalFilesDirs("downloads")
        val dir = (if (sd) dirs.getOrNull(1) else null) ?: context.getExternalFilesDir("downloads") ?: File(context.filesDir, "downloads")
        dir.mkdirs()
        val file = File(dir, name)
        val req = DownloadManager.Request(Uri.parse(s.url))
            .setTitle(item.title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
        s.headers.forEach { (k, v) -> req.addRequestHeader(k, v) }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)
        context.getSharedPreferences("dl_meta", Context.MODE_PRIVATE).edit().putString(id.toString(), label).apply()
        Store.addDownload(
            DownloadEntry(item, ep.id, ep.number, ep.title ?: "", file.absolutePath, id)
        )
        return true
    }

    /** Returns (DownloadManager status, percent). Status -1 means unknown. */
    fun status(context: Context, id: Long): Pair<Int, Int> {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val c = dm.query(DownloadManager.Query().setFilterById(id))
        c.use {
            if (!it.moveToFirst()) return -1 to 0
            val st = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return st to (if (total > 0) (done * 100 / total).toInt() else 0)
        }
    }

    fun remove(context: Context, d: DownloadEntry) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { dm.remove(d.downloadId) }
        runCatching { File(d.path).delete() }
        Store.removeDownload(d)
    }
}
