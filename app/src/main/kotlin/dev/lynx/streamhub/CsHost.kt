package dev.lynx.streamhub

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import dalvik.system.PathClassLoader
import dev.lynx.streamhub.api.Extension
import dev.lynx.streamhub.api.HomeSection
import dev.lynx.streamhub.api.MediaDetails
import dev.lynx.streamhub.api.MediaItem
import dev.lynx.streamhub.api.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import dev.lynx.streamhub.api.Episode as ShEpisode
import dev.lynx.streamhub.api.Stream as ShStream

object CsHost {
    private const val TAG = "CsHost"
    private val loaded = HashSet<String>()
    @Volatile private var lastLoadError: String? = null
    @Volatile private var fired = false
    private val adapters = java.util.IdentityHashMap<MainAPI, CsExtension>()
    private val installLock = kotlinx.coroutines.sync.Mutex()

    private fun dir(c: Context) = File(c.filesDir, "cs3").apply { mkdirs() }
    private fun prefs(c: Context) = c.getSharedPreferences("cs3_installed", Context.MODE_PRIVATE)
    private fun key(pkg: String) = pkg.removePrefix("cs3:").replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun installedVersion(c: Context, pkg: String): Long? {
        val k = key(pkg)
        return if (prefs(c).contains(k) && File(dir(c), "$k.cs3").exists()) prefs(c).getLong(k, 1L) else null
    }

    suspend fun install(
        c: Context,
        e: RepoExt,
        progress: (String) -> Unit = {},
    ) = installLock.withLock {
        withContext(Dispatchers.IO) {
            val started = android.os.SystemClock.elapsedRealtime()
            val app = c.applicationContext
            CloudStreamApp.setContext(app)
            val k = key(e.pkg)
            val target = File(dir(app), "$k.cs3")
            val temporary = File.createTempFile("install-", ".part", dir(app))
            val previous = File(dir(app), "$k.previous")
            val alreadyLoaded = synchronized(this@CsHost) {
                loaded.contains(target.absolutePath)
            }
            try {
                progress("Downloading ${e.name}…")
                val con = URL(e.apk).openConnection() as HttpURLConnection
                con.connectTimeout = 15000
                con.readTimeout = 30000
                con.instanceFollowRedirects = true
                con.setRequestProperty("User-Agent", "StreamHub/0.1")
                try {
                    require(con.responseCode in 200..299) {
                        "Download failed: HTTP ${con.responseCode}"
                    }
                    var total = 0L
                    con.inputStream.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                require(total <= 64L * 1024L * 1024L) {
                                    "Plugin exceeds 64 MB limit"
                                }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    require(total > 0) { "Empty plugin download" }
                    val expected = con.contentLengthLong
                    require(expected < 0 || total == expected) { "Incomplete plugin download" }
                } finally { con.disconnect() }
                progress("Checking ${e.name}…")
                e.sha256?.let { expected ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    temporary.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                    val actual = digest.digest().joinToString("") {
                        "%02x".format(it.toInt() and 255)
                    }
                    require(actual.equals(expected, true)) { "Plugin checksum mismatch; download rejected" }
                }
                java.util.zip.ZipFile(temporary).use { zip ->
                    val manifest = zip.getEntry("manifest.json")
                        ?: error("Not a CloudStream plugin: missing manifest")
                    val metadata = JSONObject(zip.getInputStream(manifest).bufferedReader().use { it.readText() })
                    require(metadata.optString("pluginClassName").isNotBlank()) {
                        "Plugin manifest has no entry class"
                    }
                    require(zip.getEntry("classes.dex") != null) { "Plugin has no classes.dex" }
                }
                if (previous.exists()) require(previous.delete()) { "Cannot clear previous backup" }
                if (target.exists()) require(target.renameTo(previous)) { "Cannot back up installed plugin" }
                try {
                    require(temporary.renameTo(target)) { "Cannot publish downloaded plugin" }
                    require(target.setReadOnly()) { "Cannot protect plugin code" }
                    if (!alreadyLoaded && AppPrefs.extensionEnabled(e.pkg)) {
                        progress("Loading ${e.name}…")
                        require(loadPlugin(app, target)) { "Plugin could not be loaded: ${lastLoadError ?: "check device logs"}" }
                    }
                    prefs(app).edit().putLong(k, e.version).apply()
                    app.getSharedPreferences("cs3_labels", Context.MODE_PRIVATE).edit().putString(k, e.name).apply()
                    previous.delete()
                } catch (t: Throwable) {
                    target.delete()
                    if (previous.exists()) previous.renameTo(target)
                    throw t
                }
                progress(if (alreadyLoaded) "Updated. Restart StreamHub to activate this version."
                    else "Installed ${e.name}. Home will refresh when opened.")
                Log.i(TAG, "Install ${e.name}: ${android.os.SystemClock.elapsedRealtime() - started} ms")
            } finally { temporary.delete() }
        }
    }

    private fun buildResources(c: Context, f: File): Resources {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, f.absolutePath)
        return Resources(assets, c.resources.displayMetrics, c.resources.configuration)
    }

    @Synchronized
    private fun loadPlugin(c: Context, f: File): Boolean {
        if (loaded.contains(f.absolutePath)) return true
        lastLoadError = null
        return try {
            if (f.canWrite()) f.setReadOnly()
            val loader = PathClassLoader(f.absolutePath, c.classLoader)
            val text = loader.getResourceAsStream("manifest.json")?.bufferedReader()?.use { it.readText() }
                ?: return false
            val manifest = JSONObject(text)
            val cls = manifest.optString("pluginClassName")
            if (cls.isEmpty()) return false
            val inst = loader.loadClass(cls).getDeclaredConstructor().newInstance() as BasePlugin
            val id = f.nameWithoutExtension
            inst.filename = id
            if (inst is Plugin && manifest.optBoolean("requiresResources")) {
                inst.resources = buildResources(c, f)
            }
            val err = runCatching { if (inst is Plugin) inst.load(c) else inst.load() }.exceptionOrNull()
            val registered = APIHolder.allProviders.any { it.sourcePlugin == id }
            if (err != null && !registered) throw err
            loaded.add(f.absolutePath)
            true
        } catch (t: Throwable) {
            lastLoadError = (t.javaClass.simpleName + ": " + (t.message ?: "unknown error")).take(400)
            Log.w(TAG, "load failed ${f.name}: ${t.message}", t)
            false
        }
    }

    fun installed(c: Context): List<InstalledExtension> = dir(c).listFiles().orEmpty()
        .filter { it.extension == "cs3" }.map { f ->
            val key = f.nameWithoutExtension
            val label = c.getSharedPreferences("cs3_labels", Context.MODE_PRIVATE).getString(key, null)
                ?: providerDisplayName(key)
            InstalledExtension("cs3:$key", providerDisplayName(label), prefs(c).getLong(key, 1L))
        }

    @Synchronized
    fun extensions(c: Context): List<Extension> {
        val app = c.applicationContext
        CloudStreamApp.setContext(app)
        val before = loaded.size
        val activeFiles = dir(app).listFiles().orEmpty().filter {
            it.extension == "cs3" && AppPrefs.extensionEnabled("cs3:${it.nameWithoutExtension}")
        }
        activeFiles.forEach { loadPlugin(app, it) }
        if (activeFiles.isNotEmpty() && (!fired || loaded.size != before)) {
            fired = true
            runCatching {
                com.lagradost.cloudstream3.MainActivity.mainPluginsLoadedEvent(true)
                com.lagradost.cloudstream3.MainActivity.afterPluginsLoadedEvent(true)
                com.lagradost.cloudstream3.MainActivity.afterRepositoryLoadedEvent(true)
            }
        }
        return APIHolder.allProviders
            .filter { provider ->
                val source = provider.sourcePlugin
                source != null && !provider.supportedTypes.contains(TvType.NSFW) &&
                    AppPrefs.extensionEnabled("cs3:$source") && installedVersion(app, "cs3:$source") != null
            }
            .map { api -> adapters.getOrPut(api) { CsExtension(api,
                (installedVersion(app, "cs3:${api.sourcePlugin}") ?: 1L).toInt()) } }
    }
}

private fun TvType?.toMediaType(): MediaType = when (this) {
    TvType.Movie, TvType.AnimeMovie, TvType.Documentary -> MediaType.MOVIE
    TvType.Anime, TvType.OVA -> MediaType.ANIME
    else -> MediaType.SERIES
}

class CsExtension(private val api: MainAPI, override val version: Int = 1) : Extension {
    override val id = "cs3:" + api.name
    override val name = api.name
    val activationId: String get() = "cs3:" + (api.sourcePlugin ?: api.name)
    private fun requireEnabled() { check(AppPrefs.extensionEnabled(activationId)) { "Enable $name in Sources & extensions" } }

    private val dubs = java.util.concurrent.ConcurrentHashMap<String, List<ShEpisode>>()

    /** Dubbed episodes for a title, or empty when the plugin has no separate dub list. */
    fun dubEpisodes(id: String): List<ShEpisode> = dubs[id] ?: emptyList()

    private fun SearchResponse.toItem(): MediaItem {
        val t: TvType? = this.type
        return MediaItem(
            id = "cs3|" + api.name + "|" + this.url,
            title = cleanMediaText(this.name),
            poster = this.posterUrl,
            type = t.toMediaType(),
        )
    }

    override suspend fun getHome(): List<HomeSection> = coroutineScope {
        requireEnabled()
        api.mainPage.take(6).map { mp ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(12_000L) {
                    runCatching { api.getMainPage(1, MainPageRequest(mp.name, mp.data, false)) }.getOrNull()
                }
            }
        }.awaitAll()
            .flatMap { it?.items ?: emptyList() }
            .filter { it.list.isNotEmpty() }
            .map { HomeSection(it.name + " · " + api.name, it.list.map { r -> r.toItem() }) }
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        requireEnabled()
        var err: String? = null
        val res = withTimeoutOrNull(12_000L) {
            runCatching { api.search(query) }.onFailure { err = describe(it) }.getOrNull()
                ?: runCatching { api.search(query, page)?.items }.onFailure { err = describe(it) }.getOrNull()
                ?: runCatching { api.quickSearch(query) }.onFailure { err = describe(it) }.getOrNull()
        }
        val e2 = err
        lastSearchNote = when {
            !res.isNullOrEmpty() -> res.size.toString()
            res != null -> "no results"
            e2 != null && e2.startsWith("NotImplementedError") -> "no search support"
            e2 != null -> e2
            else -> "timed out"
        }
        (res ?: emptyList()).map { it.toItem() }
    }

    override suspend fun getDetails(id: String): MediaDetails = withContext(Dispatchers.IO) {
        requireEnabled()
        val url = id.split("|", limit = 3).getOrNull(2) ?: id
        val lr = withTimeoutOrNull(40_000L) { api.load(url) } ?: throw Exception("Could not load details")
        var dubRaw: List<com.lagradost.cloudstream3.Episode> = emptyList()
        var defaultAudio: String? = null
        val eps: List<com.lagradost.cloudstream3.Episode> = when (lr) {
            is TvSeriesLoadResponse -> lr.episodes
            is AnimeLoadResponse -> {
                val sub = lr.episodes[DubStatus.Subbed]
                val dub = lr.episodes[DubStatus.Dubbed]
                if (!sub.isNullOrEmpty() && !dub.isNullOrEmpty() && sub.map { it.data } != dub.map { it.data }) dubRaw = dub
                if (isAnilightProvider(name)) defaultAudio = when {
                    !sub.isNullOrEmpty() -> "original"
                    !dub.isNullOrEmpty() -> "en"
                    else -> null
                }
                sub?.takeIf { it.isNotEmpty() } ?: dub?.takeIf { it.isNotEmpty() } ?: lr.episodes.values.flatten()
            }
            else -> emptyList()
        }
        val episodes = when {
            eps.isNotEmpty() -> eps.mapIndexed { i, e ->
                val label = e.name?.takeIf { it.isNotBlank() }
                    ?: ("Episode " + (e.episode ?: (i + 1)))
                val s = e.season
                ShEpisode(
                    id = e.data,
                    number = e.episode?.toFloat() ?: (i + 1).toFloat(),
                    title = cleanMediaText(if (s != null && s > 1) "S$s · $label" else label),
                    thumbnail = e.posterUrl,
                )
            }
            lr is MovieLoadResponse -> listOf(ShEpisode(lr.dataUrl, 1f, lr.name, lr.posterUrl))
            lr is LiveStreamLoadResponse -> listOf(ShEpisode(lr.dataUrl, 1f, lr.name, lr.posterUrl))
            else -> emptyList()
        }
        defaultAudio?.let { code -> episodes.forEach { AudioMetadataStore.rememberEpisode(this@CsExtension.id, it.id, code) } }
        if (dubRaw.isNotEmpty()) {
            dubs[id] = dubRaw.mapIndexed { i, e ->
                val label = e.name?.takeIf { it.isNotBlank() } ?: ("Episode " + (e.episode ?: (i + 1)))
                ShEpisode(id = e.data, number = e.episode?.toFloat() ?: (i + 1).toFloat(), title = cleanMediaText(label), thumbnail = e.posterUrl)
            }
        } else {
            dubs.remove(id)
        }
        if (isAnilightProvider(name)) dubs[id].orEmpty().forEach {
            AudioMetadataStore.rememberEpisode(this@CsExtension.id, it.id, "en")
        }
        val t: TvType = lr.type
        MediaDetails(
            item = MediaItem(
                id = id,
                title = cleanMediaText(lr.name),
                poster = lr.posterUrl,
                type = t.toMediaType(),
                genres = lr.tags ?: emptyList(),
                year = lr.year,
                description = lr.plot,
            ),
            episodes = episodes,
        )
    }

    /** Why the last streamsLive() call produced nothing (shown in the player and the download sheet). */
    @Volatile var lastError: String? = null

    /** Result of the last search(): a count, or why it returned nothing. */
    @Volatile var lastSearchNote: String = ""

    private fun describe(t: Throwable): String {
        var r: Throwable = t
        var n = 0
        while (r.cause != null && r.cause !== r && n < 8) { r = r.cause!!; n++ }
        val msg = (r.message ?: "").take(300)
        return r.javaClass.simpleName + (if (msg.isNotBlank()) ": $msg" else "")
    }

    suspend fun streamsLive(
        episodeId: String,
        onSubtitle: (String, String) -> Unit = { _, _ -> },
        onStream: (ShStream) -> Unit,
    ) {
        requireEnabled()
        lastError = null
        var count = 0
        var finished = false
        var err: String? = null
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(90_000L) {
                try {
                    api.loadLinks(episodeId, false, { sub ->
                        if (sub.url.isNotBlank()) {
                            onSubtitle(sub.lang, sub.url)
                        }
                    }, { l ->
                        if (l.url.isNotBlank()) {
                            val ref: String? = l.referer
                            val hs: Map<String, String>? = l.headers
                            val h = HashMap<String, String>(hs.orEmpty())
                            if (!ref.isNullOrBlank() && h.keys.none { it.equals("Referer", true) }) h["Referer"] = ref
                            val q = if (l.quality > 0) l.quality.toString() + "p" else null
                            count++
                            onStream(AudioMetadataStore.enrich(id, name, episodeId,
                                ShStream(url = l.url, quality = listOfNotNull(l.name, q).joinToString(" "), headers = h)))
                        }
                    })
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.w("CsHost", "loadLinks failed for " + api.name, t)
                    err = describe(t)
                }
                finished = true
            }
        }
        lastError = err ?: when {
            !finished -> "Plugin " + api.name + " took longer than 90s and returned nothing."
            count == 0 -> "Plugin " + api.name + " finished but returned no links. Its site may be down or blocked, or it needs a feature this app lacks."
            else -> null
        }
    }

    override suspend fun getStreams(episodeId: String): List<ShStream> = withContext(Dispatchers.IO) {
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val job = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
            runCatching { api.loadLinks(episodeId, false, { }, { l -> links.add(l) }) }
        }
        val t0 = System.currentTimeMillis()
        var firstAt = 0L
        while (job.isActive) {
            delay(250)
            val t = System.currentTimeMillis()
            if (firstAt == 0L && links.isNotEmpty()) firstAt = t
            if (firstAt != 0L && t - firstAt > 5000) break
            if (t - t0 > 60000) break
        }
        job.cancel()
        links.toList()
            .filter { it.url.isNotBlank() }
            .sortedByDescending { it.quality }
            .map { l ->
                val ref: String? = l.referer
                val hs: Map<String, String>? = l.headers
                val h = HashMap<String, String>(hs.orEmpty())
                if (!ref.isNullOrBlank() && h.keys.none { it.equals("Referer", true) }) h["Referer"] = ref
                val q = if (l.quality > 0) l.quality.toString() + "p" else null
                ShStream(
                    url = l.url,
                    quality = listOfNotNull(l.name, q).joinToString(" "),
                    headers = h,
                )
            }
    }
}
