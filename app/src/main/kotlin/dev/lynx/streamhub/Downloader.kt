package dev.lynx.streamhub

import android.app.DownloadManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.lynx.streamhub.api.Episode
import dev.lynx.streamhub.api.MediaItem
import dev.lynx.streamhub.api.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** In-app downloader: direct files (any headers) and HLS (.m3u8, incl. AES-128). */
object Downloader {
    const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val OWN = 1_000_000_000_000L

    private class Prog(@Volatile var st: Int = 2, @Volatile var pct: Int = 0, @Volatile var bytes: Long = 0L)
    private class Seg(val url: String, val key: ByteArray?, val iv: ByteArray?, val seq: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val progs = ConcurrentHashMap<Long, Prog>()
    private var lastId = 0L

    @Synchronized
    private fun newId(): Long {
        val t = maxOf(System.currentTimeMillis(), lastId + 1)
        lastId = t
        return t
    }

    private fun setState(c: Context, id: Long, st: Int) {
        c.getSharedPreferences("dl_state", Context.MODE_PRIVATE).edit().putInt(id.toString(), st).apply()
    }

    /** Returns false if this episode was already downloaded. */
    fun enqueue(context: Context, item: MediaItem, ep: Episode, s: Stream, label: String = "", sd: Boolean = false): Boolean {
        if (Store.hasDownload(ep.id)) return false
        val app = context.applicationContext
        val hls = s.url.contains("m3u8", ignoreCase = true)
        val ext = if (hls) "ts" else s.url.substringBefore('?').substringAfterLast('.', "mp4").lowercase()
            .let { if (it.length in 2..4 && it.all { ch -> ch.isLetterOrDigit() }) it else "mp4" }
        val name = "${item.id.hashCode()}_${ep.id.hashCode()}.$ext".replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dirs = app.getExternalFilesDirs("downloads")
        val dir = (if (sd) dirs.getOrNull(1) else null) ?: app.getExternalFilesDir("downloads") ?: File(app.filesDir, "downloads")
        dir.mkdirs()
        val file = File(dir, name)
        val id = newId()
        app.getSharedPreferences("dl_meta", Context.MODE_PRIVATE).edit().putString(id.toString(), label).apply()
        setState(app, id, 2)
        Store.addDownload(DownloadEntry(item, ep.id, ep.number, ep.title ?: "", file.absolutePath, id))
        val p = Prog()
        progs[id] = p
        jobs[id] = scope.launch {
            try {
                if (hls) hls(p, s.url, s.headers, file) else direct(p, s.url, s.headers, file)
                p.pct = 100
                p.st = 8
                setState(app, id, 8)
            } catch (t: CancellationException) {
                runCatching { file.delete() }
                throw t
            } catch (t: Throwable) {
                p.st = 16
                setState(app, id, 16)
                runCatching { file.delete() }
                val msg = t.message ?: t.javaClass.simpleName
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, "Download failed: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
        return true
    }

    // ---------- network helpers ----------
    private fun open(url0: String, headers: Map<String, String>): HttpURLConnection {
        var url = url0
        for (hop in 0 until 6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: UA
            c.setRequestProperty("User-Agent", ua)
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) c.setRequestProperty(k, v) }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw Exception("Bad redirect")
                url = URL(URL(url), loc).toString()
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw Exception("Server said HTTP $code")
            }
            return c
        }
        throw Exception("Too many redirects")
    }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val c = open(url, headers)
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun fetchBytes(url: String, headers: Map<String, String>, tries: Int = 3): ByteArray {
        var last: Throwable? = null
        for (i in 0 until tries) {
            try {
                val c = open(url, headers)
                try { return c.inputStream.readBytes() } finally { c.disconnect() }
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: Exception("Download failed")
    }

    private fun resolveUrl(base: String, ref: String): String = URL(URL(base), ref).toString()

    // ---------- direct file ----------
    private suspend fun direct(p: Prog, url: String, headers: Map<String, String>, file: File) {
        val c = open(url, headers)
        try {
            val type = c.contentType ?: ""
            if (type.contains("text/html", true)) throw Exception("Link is a web page, not a video")
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { ins ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        p.bytes = done
                        if (total > 0) p.pct = (done * 100 / total).toInt().coerceAtMost(99)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    // ---------- HLS ----------
    private fun segment(sg: Seg, headers: Map<String, String>): ByteArray {
        val raw = fetchBytes(sg.url, headers)
        val k = sg.key ?: return raw
        val iv = sg.iv ?: ByteArray(16).also {
            var s = sg.seq
            for (i in 15 downTo 8) {
                it[i] = (s and 0xff).toByte()
                s = s shr 8
            }
        }
        val ci = Cipher.getInstance("AES/CBC/PKCS5Padding")
        ci.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(iv))
        return ci.doFinal(raw)
    }

    private suspend fun hls(p: Prog, url: String, headers: Map<String, String>, file: File) {
        var pl = url
        var body = fetchText(pl, headers)
        if (body.contains("#EXT-X-STREAM-INF")) {
            val lines = body.lines()
            var best = -1L
            var bestUri: String? = null
            for (i in lines.indices) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size && bw > best) {
                    best = bw
                    bestUri = lines[j].trim()
                }
            }
            pl = resolveUrl(pl, bestUri ?: throw Exception("Empty playlist"))
            body = fetchText(pl, headers)
        }
        var seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        var key: ByteArray? = null
        var iv: ByteArray? = null
        var initUrl: String? = null
        val segs = ArrayList<Seg>()
        for (raw in body.lines()) {
            val l = raw.trim()
            if (l.startsWith("#EXT-X-KEY")) {
                if (l.contains("METHOD=NONE")) {
                    key = null
                    iv = null
                } else {
                    if (!l.contains("METHOD=AES-128")) throw Exception("Unsupported stream encryption")
                    val ku = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1) ?: throw Exception("Missing key")
                    key = fetchBytes(resolveUrl(pl, ku), headers)
                    iv = Regex("IV=0[xX]([0-9A-Fa-f]+)").find(l)?.groupValues?.get(1)?.let { hex ->
                        val padded = hex.padStart(32, '0')
                        ByteArray(16) { i -> padded.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                    }
                }
            } else if (l.startsWith("#EXT-X-MAP")) {
                initUrl = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { resolveUrl(pl, it) }
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                segs.add(Seg(resolveUrl(pl, l), key, iv, seq))
                seq++
            }
        }
        if (segs.isEmpty()) throw Exception("Playlist has no segments")
        file.outputStream().use { out ->
            initUrl?.let { out.write(fetchBytes(it, headers)) }
            var done = 0
            var bytes = 0L
            for (batch in segs.chunked(6)) {
                currentCoroutineContext().ensureActive()
                val parts = coroutineScope {
                    batch.map { sg -> async(Dispatchers.IO) { segment(sg, headers) } }.awaitAll()
                }
                for (b in parts) {
                    out.write(b)
                    bytes += b.size
                }
                done += batch.size
                p.bytes = bytes
                p.pct = (done * 100 / segs.size).coerceAtMost(99)
            }
        }
    }

    // ---------- state used by the Downloads screen ----------
    /** Returns (status, percent): 8 = done, 2 = running, 16 = failed, -1 = unknown. */
    fun status(context: Context, id: Long): Pair<Int, Int> {
        if (id >= OWN) {
            val p = progs[id]
            if (p != null) return p.st to p.pct
            val st = context.getSharedPreferences("dl_state", Context.MODE_PRIVATE).getInt(id.toString(), -1)
            return if (st == 8) 8 to 100 else 16 to 0
        }
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

    fun bytes(id: Long): Long = progs[id]?.bytes ?: 0L

    fun remove(context: Context, d: DownloadEntry) {
        jobs.remove(d.downloadId)?.cancel()
        progs.remove(d.downloadId)
        if (d.downloadId < OWN) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            runCatching { dm.remove(d.downloadId) }
        }
        runCatching { File(d.path).delete() }
        context.getSharedPreferences("dl_state", Context.MODE_PRIVATE).edit().remove(d.downloadId.toString()).apply()
        Store.removeDownload(d)
    }
}
