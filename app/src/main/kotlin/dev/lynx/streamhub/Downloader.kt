package dev.lynx.streamhub

import android.app.DownloadManager
import android.content.Context
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.IOException
import java.io.FileOutputStream
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

    private class Prog(@Volatile var st: Int = 2, @Volatile var pct: Int = 0,
        @Volatile var bytes: Long = 0L, @Volatile var total: Long = -1L,
        @Volatile var paused: Boolean = false, var validator: String = "",
        var segments: Int = 0, var fingerprint: String = "", var readyToPublish: Boolean = false, var parent: Prog? = null)
    private class PauseSignal : IOException()
    private class HttpFailure(val code: Int) : IOException("Server said HTTP $code")
    private val slots = Semaphore(2)
    private val requests = ConcurrentHashMap<Long, Stream>()
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()
    private fun checkpoint(p: Prog) { if (p.paused || p.parent?.paused == true) throw PauseSignal() }
    private fun save(c: Context, id: Long, p: Prog) {
        if (id in cancelled) return
        c.getSharedPreferences("dl_progress", Context.MODE_PRIVATE).edit()
            .putLong("$id.bytes", p.bytes).putLong("$id.total", p.total)
            .putInt("$id.pct", p.pct).putString("$id.validator", p.validator)
            .putInt("$id.segments", p.segments).putString("$id.fingerprint", p.fingerprint)
            .putInt("$id.ready", if (p.readyToPublish) 1 else 0).apply()
    }
    private fun restored(c: Context, id: Long): Prog {
        val pref = c.getSharedPreferences("dl_progress", Context.MODE_PRIVATE)
        return Prog(st = 4, pct = pref.getInt("$id.pct", 0), bytes = pref.getLong("$id.bytes", 0L),
            total = pref.getLong("$id.total", -1L), paused = true,
            validator = pref.getString("$id.validator", "").orEmpty(),
            segments = pref.getInt("$id.segments", 0), fingerprint = pref.getString("$id.fingerprint", "").orEmpty(),
            readyToPublish = pref.getInt("$id.ready", 0) == 1)
    }
    private fun request(c: Context, id: Long): Stream? = requests[id] ?: runCatching {
        val raw = c.getSharedPreferences("dl_requests", Context.MODE_PRIVATE).getString(id.toString(), null)
            ?: return@runCatching null
        val json = JSONObject(raw)
        val headers = json.getJSONObject("headers")
        Stream(json.getString("url"), json.optString("quality").takeIf { it.isNotEmpty() },
            headers.keys().asSequence().associateWith { headers.getString(it) })
    }.getOrNull()?.also { requests[id] = it }

    private fun start(app: Context, d: DownloadEntry, stream: Stream, p: Prog) {
        val id = d.downloadId
        val file = File(d.path)
        jobs[id] = scope.launch {
            Diagnostics.event("download.start","id=$id host=${runCatching { URL(stream.url).host }.getOrDefault("unknown")}")
            try {
                var failures = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (p.paused) { p.st = 4; delay(200L); continue }
                    p.st = 1 // queued, until a transfer slot is free
                    try {
                        slots.withPermit {
                            checkpoint(p)
                            p.st = 2
                            if (!p.readyToPublish || !file.exists() || file.length() != p.bytes) {
                                val mux = HlsDownloadPlans.restore(app, id)
                                DownloadSafety.requirePlan(stream.quality, mux != null)
                                if (mux != null) paired(app, id, p, mux, file)
                                else if (isHls(stream)) hls(p, stream.url, stream.headers, file) { save(app, id, p) }
                                else direct(p, stream.url, stream.headers, file) { save(app, id, p) }
                                p.readyToPublish = true; p.bytes = file.length(); p.total = p.bytes
                                save(app, id, p)
                            }
                            p.pct = 99
                            val names = app.getSharedPreferences("dl_names", Context.MODE_PRIVATE)
                            val folder = names.getString("$id.folder", null) ?: safeDownloadName(d.item.title, spaces = true)
                            val name = names.getString("$id.name", null) ?: readableDownloadName(d.item,
                                Episode(d.episodeId, d.episodeNumber, d.episodeTitle), stream)
                            val published = DownloadFiles.publish(app, id, file, folder, name) { checkpoint(p) }
                            currentCoroutineContext().ensureActive()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                if (id in cancelled) throw CancellationException("Download removed")
                                Store.publishDownloadPath(id, published)
                                if (id in cancelled) throw CancellationException("Download removed")
                                DownloadFiles.committed(app, id)
                                file.delete()
                                removeStaging(app, id)
                                requests.remove(id)
                                listOf("dl_requests", "dl_mux").forEach { name ->
                                    app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().remove(id.toString()).apply()
                                }
                                p.pct = 100; p.st = 8; p.total = p.bytes
                                save(app, id, p); setState(app, id, 8)
                            }
                        }
                        p.pct = 100; p.st = 8; p.total = p.bytes
                        save(app, id, p); setState(app, id, 8)
                        break
                    } catch (pause: PauseSignal) {
                        p.st = 4; save(app, id, p); setState(app, id, 4)
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (io: IOException) {
                        Diagnostics.failure("download.network.error",io)
                        save(app, id, p)
                        if (p.paused) continue
                        val waitMs=downloadRetryDelay(io,failures,(io as? HttpFailure)?.code) ?: throw io
                        failures++
                        p.st = 1
                        Diagnostics.event("download.retry","id=$id attempt=$failures waitMs=$waitMs savedBytes=${p.bytes}")
                        delay(waitMs)
                    }
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (t: Exception) {
                Diagnostics.event("download.failed.context","id=$id savedBytes=${p.bytes} pct=${p.pct}")
                Diagnostics.failure("download.failed",t)
                p.st = 16; save(app, id, p); setState(app, id, 16)
                app.getSharedPreferences("dl_errors", Context.MODE_PRIVATE).edit()
                    .putString(id.toString(), if(t is java.net.UnknownHostException)
                        "Source host could not be resolved after retries. Check internet/private DNS or retry later; saved progress is kept."
                        else (t.message ?: "Download failed").take(180)).apply()
            }
        }
    }

    fun canResume(c: Context, id: Long): Boolean = id >= OWN && request(c, id) != null
    fun pause(c: Context, id: Long) {
        val p = progs[id] ?: return
        if (p.st == 8 || p.st == 16) return
        p.paused = true; p.st = 4; save(c, id, p); setState(c, id, 4)
    }
    fun resume(c: Context, d: DownloadEntry, serviceAlreadyStarted: Boolean = false): Boolean {
        if (status(c, d.downloadId).first == 8 || !DownloadAccess.canSavePublic(c)) return false
        if (!serviceAlreadyStarted && !DownloadService.ensureRunning(c, d.downloadId)) return false
        val stream = request(c, d.downloadId) ?: return false
        val p = progs.getOrPut(d.downloadId) { restored(c, d.downloadId) }
        p.paused = false; p.st = 1; setState(c, d.downloadId, 1)
        if (jobs[d.downloadId]?.isActive != true) start(c.applicationContext, d, stream, p)
        return true
    }
    fun total(c: Context, id: Long): Long = progs[id]?.total
        ?: c.getSharedPreferences("dl_progress", Context.MODE_PRIVATE).getLong("$id.total", -1L)
    fun savedBytes(c: Context, id: Long): Long = progs[id]?.bytes
        ?: c.getSharedPreferences("dl_progress", Context.MODE_PRIVATE).getLong("$id.bytes", 0L)
    fun failure(c: Context, id: Long): String = c.getSharedPreferences("dl_errors", Context.MODE_PRIVATE)
        .getString(id.toString(), "Transfer failed. Retry; expired links may need a new download.").orEmpty()
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
        Diagnostics.event("download.state","id=$id state=$st")
        if (id in cancelled) return
        c.getSharedPreferences("dl_state", Context.MODE_PRIVATE).edit().putInt(id.toString(), st).apply()
    }

    /** Only verified self-contained sources or prepared audio/video plans can be queued. */
    fun enqueue(context: Context, item: MediaItem, ep: Episode, s: Stream, label: String = "", sourceName: String? = null): Boolean {
        Diagnostics.event("download.enqueue.request")
        val mux = HlsDownloadPlans.get(s)
        if (mux == null && s.quality.orEmpty().contains("[MUX]", true)) return false
        if (Store.hasDownload(ep.id) || (mux == null && (ProviderPlayback.video(s.url).audio.isNotEmpty() ||
            VerifiedStreams.cached(s)?.complete != true))) return false
        val app = context.applicationContext
        if (!DownloadAccess.canSavePublic(app)) return false
        val id = newId()
        if (mux != null && runCatching { HlsDownloadPlans.persist(app, id, mux) }.isFailure) return false
        val name = readableDownloadName(item, ep, s)
        val folder = safeDownloadName(item.title, spaces = true)
        val root = app.getExternalFilesDir("downloads") ?: File(app.filesDir, "downloads")
        val dir = File(root, ".partial/$id")
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create staging folder" }
        val file = File(dir, name)
        app.getSharedPreferences("dl_names", Context.MODE_PRIVATE).edit()
            .putString("$id.name", name).putString("$id.folder", folder).apply()
        app.getSharedPreferences("dl_meta", Context.MODE_PRIVATE).edit().putString(id.toString(), label).apply()
        setState(app, id, 2)
        Store.addDownload(DownloadEntry(item, ep.id, ep.number, ep.title ?: "", file.absolutePath, id))
        val p = Prog()
        progs[id] = p
        requests[id] = s
        val requestJson = JSONObject().put("url", s.url).put("quality", s.quality.orEmpty())
            .put("headers", JSONObject(s.headers))
        app.getSharedPreferences("dl_requests", Context.MODE_PRIVATE).edit()
            .putString(id.toString(), requestJson.toString()).apply()
        val provider = Session.owner[item.id] ?: Store.ownerOf(item.id)
        app.getSharedPreferences("dl_source", Context.MODE_PRIVATE).edit()
            .putString(id.toString(), providerDisplayName(sourceName ?: provider?.name)).apply()
        val entry = Store.downloads.first { it.downloadId == id }
        if (!DownloadService.ensureRunning(app, id)) { remove(app, entry); return false }
        start(app, entry, s, p)
        return true
    }

    private fun leg(c: Context, id: Long, key: String, owner: Prog): Prog {
        val raw = c.getSharedPreferences("dl_mux_progress", Context.MODE_PRIVATE).getString("$id.$key", null)
        val j = runCatching { JSONObject(raw ?: "{}") }.getOrDefault(JSONObject())
        return Prog(bytes = j.optLong("bytes", 0), total = j.optLong("total", -1), pct = j.optInt("pct", 0),
            validator = j.optString("validator"), segments = j.optInt("segments", 0),
            fingerprint = j.optString("fingerprint"), readyToPublish = j.optBoolean("ready", false), parent = owner)
    }
    private suspend fun paired(c: Context, id: Long, owner: Prog, plan: HlsDownloadPlans.Plan, output: File) {
        val video = File(output.parentFile, "video.component")
        val audio = File(output.parentFile, "audio.component")
        val work = listOf(Triple("v", plan.video, video), Triple("a", plan.audio, audio))
        for ((index, part) in work.withIndex()) {
            checkpoint(owner)
            val (key, stream, file) = part
            Diagnostics.event("download.component","id=$id leg=$key")
            val state = leg(c, id, key, owner)
            fun persist() {
                if (id in cancelled) return
                val j = JSONObject().put("bytes", state.bytes).put("total", state.total).put("pct", state.pct)
                    .put("validator", state.validator).put("segments", state.segments)
                    .put("fingerprint", state.fingerprint).put("ready", state.readyToPublish)
                c.getSharedPreferences("dl_mux_progress", Context.MODE_PRIVATE).edit().putString("$id.$key", j.toString()).apply()
                owner.bytes = video.length() + audio.length(); owner.total = -1
                owner.pct = index * 45 + state.pct.coerceIn(0,100) * 45 / 100
                save(c, id, owner)
            }
            if (!state.readyToPublish || !file.exists() || state.bytes != file.length()) {
                if (isHls(stream)) hls(state, stream.url, stream.headers, file) { persist() }
                else direct(state, stream.url, stream.headers, file) { persist() }
                state.readyToPublish = true; state.bytes = file.length(); state.pct = 100; persist()
            }
        }
        checkpoint(owner)
        Diagnostics.event("download.mux.start","id=$id height=${plan.height}")
        OfflineMuxer.combine(video, audio, output, plan.height, { checkpoint(owner) }) { progress ->
            owner.pct = 90 + progress.coerceIn(0,100) * 8 / 100
            save(c, id, owner)
        }
    }
    private fun removeStaging(c: Context, id: Long) {
        Diagnostics.event("download.staging.cleanup","id=$id")
        val roots = listOfNotNull(c.getExternalFilesDir("downloads"), File(c.filesDir, "downloads")).distinctBy { it.path }
        roots.forEach { File(it, ".partial/$id").deleteRecursively() }
        c.getSharedPreferences("dl_mux_progress", Context.MODE_PRIVATE).edit().remove("$id.v").remove("$id.a").apply()
    }

    // ---------- network helpers ----------
    private fun openConnection(url0: String, headers: Map<String, String>): HttpURLConnection {
        var url = url0
        var safeHeaders = headers
        for (hop in 0 until 6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            val ua = safeHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: UA
            c.setRequestProperty("User-Agent", ua)
            safeHeaders.forEach { (k, v) -> if (!k.equals("User-Agent", true)) c.setRequestProperty(k, v) }
            val code = try { c.responseCode } catch (failure: Exception) { c.disconnect(); throw failure }
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw Exception("Bad redirect")
                val next = URL(URL(url), loc).toString()
                safeHeaders = DownloadSafety.redirectedHeaders(url, next, safeHeaders)
                url = next
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw HttpFailure(code)
            }
            return c
        }
        throw Exception("Too many redirects")
    }

    private fun fetchText(url: String, headers: Map<String, String>): Pair<String, String> {
        val c = openConnection(url, headers)
        return try { c.inputStream.bufferedReader().use { reader ->
            val text=StringBuilder();val buffer=CharArray(8192)
            while(true){
                val n=reader.read(buffer);if(n<0)break
                check(text.length+n<=2_000_000){"Download playlist exceeds safe size"}
                text.append(buffer,0,n)
            }
            c.url.toString() to text.toString()
        } } finally { c.disconnect() }
    }

    private fun fetchBytes(url: String, headers: Map<String, String>, tries: Int = 3): ByteArray {
        var last: Throwable? = null
        for (i in 0 until tries) {
            try {
                val c = openConnection(url, headers)
                try { return c.inputStream.readBytes() } finally { c.disconnect() }
            } catch (t: IOException) {
                if (t is HttpFailure && !retryableHttp(t.code)) throw t
                last = t
                if (i < tries - 1) Thread.sleep(500L * (1L shl i))
            }
        }
        throw last ?: Exception("Download failed")
    }

    private fun resolveUrl(base: String, ref: String): String = URL(URL(base), ref).toString()

    // ---------- direct file ----------
    private suspend fun direct(p: Prog, url: String, headers: Map<String, String>, file: File, persist: () -> Unit) {
        checkpoint(p)
        // Only resume entities for which the server supplied an identity validator.
        var offset = if (p.validator.isNotEmpty()) file.length() else 0L
        var extra = headers.filterKeys { !it.equals("Range", true) && !it.equals("If-Range", true) }
        if (offset > 0L) extra = extra + mapOf("Range" to "bytes=$offset-", "If-Range" to p.validator)
        val c = try { openConnection(url, extra + ("Accept-Encoding" to "identity")) } catch (e: HttpFailure) {
            if (e.code != 416 || offset == 0L) throw e
            offset = 0L
            openConnection(url, headers.filterKeys { !it.equals("Range", true) && !it.equals("If-Range", true) } + ("Accept-Encoding" to "identity"))
        }
        try {
            val code = c.responseCode
            if (offset > 0L && code == 200) offset = 0L // Range ignored / entity changed: truncate.
            if (!validResumeRange(offset, code, c.getHeaderField("Content-Range")))
                throw IOException("Server returned an invalid byte range")
            val type = c.contentType.orEmpty()
            if (type.contains("text/html", true)) throw Exception("Link is a web page, not a video")
            p.validator = c.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                ?: c.getHeaderField("Last-Modified").orEmpty()
            val length = c.contentLengthLong
            p.total = if (length >= 0) offset + length else -1L
            var done = offset
            p.bytes = done
            p.pct = if (p.total > 0) (done * 100 / p.total).toInt().coerceIn(0, 99) else 0
            var savedAt = System.nanoTime()
            persist()
            c.inputStream.use { ins ->
                FileOutputStream(file, offset > 0L).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        checkpoint(p)
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n; p.bytes = done
                        if (System.nanoTime() - savedAt >= 1_000_000_000L) {
                            persist(); savedAt = System.nanoTime()
                        }
                        if (p.total > 0) p.pct = (done * 100 / p.total).toInt().coerceIn(0, 99)
                    }
                    if (p.total >= 0 && done != p.total) throw IOException("Transfer ended early")
                }
            }
        } finally { c.disconnect() }
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

    private suspend fun hls(p: Prog, url: String, headers: Map<String, String>, file: File, persist: () -> Unit) {
        checkpoint(p)
        fun scoped(target: String) = DownloadSafety.redirectedHeaders(url, target, headers)
        val initial = fetchText(url, headers)
        var pl = initial.first
        var body = initial.second
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
            val child = fetchText(pl, scoped(pl))
            pl = child.first; body = child.second
        }
        if (p.parent != null && body.lineSequence().any { it.trim() == "#EXT-X-DISCONTINUITY" })
            throw IllegalArgumentException("Discontinuous audio/video playlists cannot currently be combined safely")
        if (!body.contains("#EXT-X-ENDLIST")) throw Exception("Live playlists cannot be downloaded safely")
        if (body.contains("#EXT-X-MAP") && body.contains("#EXT-X-KEY"))
            throw Exception("Encrypted initialization segments are not supported")
        if (body.contains("#EXT-X-BYTERANGE")) throw Exception("Byte-range HLS downloads are not supported")
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        if (p.fingerprint != fingerprint || file.length() < p.bytes) {
            p.segments = 0; p.bytes = 0L; p.pct = 0; file.delete()
        } else if (file.length() > p.bytes) {
            // Discard a partially written, uncheckpointed batch after process interruption.
            java.io.RandomAccessFile(file, "rw").use { it.setLength(p.bytes) }
        }
        p.fingerprint = fingerprint
        p.total = -1L // No reliable byte total is advertised by a standard HLS playlist.
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
                    val keyUrl=resolveUrl(pl, ku)
                    key = fetchBytes(keyUrl, scoped(keyUrl))
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
        checkpoint(p)
        FileOutputStream(file, p.segments > 0).use { out ->
            if (p.segments == 0) {
                initUrl?.let { out.write(fetchBytes(it, scoped(it))) }
                p.bytes = file.length()
            }
            for (batch in segs.drop(p.segments).chunked(4)) {
                currentCoroutineContext().ensureActive()
                checkpoint(p)
                val parts = coroutineScope {
                    batch.map { sg -> async(Dispatchers.IO) { segment(sg, scoped(sg.url)) } }.awaitAll()
                }
                // A pause during fetch stops before writing this incomplete batch.
                checkpoint(p)
                for (part in parts) out.write(part)
                out.flush()
                p.bytes = file.length()
                p.segments += batch.size
                p.pct = (p.segments * 100 / segs.size).coerceAtMost(99)
                persist()
            }
        }
    }

    // ---------- state used by the Downloads screen ----------
    /** Returns (status, percent): 8 = done, 2 = running, 16 = failed, -1 = unknown. */
    fun status(context: Context, id: Long): Pair<Int, Int> {
        if (id >= OWN) {
            val p = progs[id]
            if (p != null) return p.st to p.pct
            val recorded = Store.downloads.firstOrNull { it.downloadId == id }?.path
            if (recorded != null && (recorded.startsWith("content://") ||
                    (recorded.startsWith("/") && recorded.contains("/Download/streamHub/")))) {
                DownloadFiles.committed(context, id)
                return 8 to 100
            }
            val st = context.getSharedPreferences("dl_state", Context.MODE_PRIVATE).getInt(id.toString(), -1)
            val pct = context.getSharedPreferences("dl_progress", Context.MODE_PRIVATE).getInt("$id.pct", 0)
            return when {
                st == 8 -> 8 to 100
                st == 16 -> 16 to pct
                request(context, id) != null -> 4 to pct // interrupted by process death: explicit Resume
                else -> 16 to pct
            }
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

    fun hasActiveWork(): Boolean = progs.values.any { it.st == 1 || it.st == 2 }
    fun pauseAll(context: Context) { progs.keys.toList().forEach { pause(context, it) } }

    fun remove(context: Context, d: DownloadEntry) {
        Diagnostics.event("download.remove")
        val current = Store.downloads.firstOrNull { it.downloadId == d.downloadId } ?: d
        cancelled.add(d.downloadId)
        val job = jobs.remove(d.downloadId)
        job?.cancel()
        requests.remove(d.downloadId)
        DownloadNotifications.cancel(context, d.downloadId)
        scope.launch {
            job?.cancelAndJoin()
            DownloadFiles.abortPending(context, d.downloadId)
            runCatching { DownloadFiles.delete(context, current.path) }
            removeStaging(context, d.downloadId)
            progs.remove(d.downloadId)
            val progress = context.getSharedPreferences("dl_progress", Context.MODE_PRIVATE).edit()
            listOf("bytes", "total", "pct", "validator", "segments", "fingerprint", "ready").forEach { progress.remove("${d.downloadId}.$it") }
            progress.apply()
            cancelled.remove(d.downloadId)
        }
        if (d.downloadId < OWN) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            runCatching { dm.remove(d.downloadId) }
        }
        listOf("dl_state", "dl_requests", "dl_source", "dl_meta", "dl_errors", "dl_mux").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().remove(d.downloadId.toString()).apply()
        }
        context.getSharedPreferences("dl_names", Context.MODE_PRIVATE).edit()
            .remove("${d.downloadId}.name").remove("${d.downloadId}.folder").apply()
        Store.removeDownload(current)
    }
}
