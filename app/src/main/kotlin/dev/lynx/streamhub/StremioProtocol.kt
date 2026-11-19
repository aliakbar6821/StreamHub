package dev.lynx.streamhub

import dev.lynx.streamhub.api.*
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * HTTP addon protocol only. No torrents, executable plugins, tracking events or default servers.
 */
object StremioProtocol {
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun manifestUrl(input: String): String {
        val text =
            input.trim().let {
                if (it.startsWith("stremio://")) "https://" + it.removePrefix("stremio://") else it
            }
        val u = URI(text)
        require(
            u.scheme == "https" &&
                !u.host.isNullOrBlank() &&
                u.rawUserInfo == null &&
                u.rawQuery == null &&
                u.rawFragment == null
        ) {
            "Use an HTTPS manifest URL (configuration tokens in the path are supported)"
        }
        require(u.rawPath.endsWith("/manifest.json")) {
            "Paste the addon's complete /manifest.json URL"
        }
        return u.toASCIIString()
    }

    fun key(url: String): String =
        "stremio:" +
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }

    fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList()
        else (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.takeIf(String::isNotBlank) }

    fun text(o: JSONObject, k: String): String? = (o.opt(k) as? String)?.takeIf { it.isNotBlank() }

    fun http(value: String?): String? =
        value?.takeIf {
            runCatching {
                    val u = URI(it)
                    u.scheme in listOf("https", "http") &&
                        !u.host.isNullOrBlank() &&
                        u.rawUserInfo == null
                }
                .getOrDefault(false)
        }

    fun supports(m: JSONObject, resource: String, type: String, id: String): Boolean {
        val a = m.optJSONArray("resources") ?: return false
        return (0 until a.length()).any { i ->
            val v = a.opt(i)
            val o = v as? JSONObject
            val name = if (v is String) v else o?.optString("name")
            val types = strings(if (o != null) o.optJSONArray("types") else m.optJSONArray("types"))
            val prefixes =
                strings(
                    if (o != null) o.optJSONArray("idPrefixes") else m.optJSONArray("idPrefixes")
                )
            name == resource &&
                type in types &&
                (resource == "catalog" || prefixes.isEmpty() || prefixes.any(id::startsWith))
        }
    }

    fun endpoint(
        base: String,
        resource: String,
        type: String,
        id: String,
        extra: Map<String, String> = emptyMap(),
    ): String =
        base.removeSuffix("/manifest.json") +
            "/$resource/${encode(type)}/${encode(id)}" +
            (if (extra.isEmpty()) ""
            else
                "/" + extra.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }) +
            ".json"

    fun token(owner: String, type: String, id: String): String =
        JSONObject().put("addon", owner).put("type", type).put("id", id).toString()

    fun item(owner: String, o: JSONObject): MediaItem? {
        val raw = text(o, "id") ?: return null
        val type = text(o, "type") ?: return null
        val title = text(o, "name") ?: return null
        return MediaItem(
            token(owner, type, raw),
            title,
            http(text(o, "poster")),
            if (type == "movie") MediaType.MOVIE else MediaType.SERIES,
            strings(o.optJSONArray("genres")),
            text(o, "imdbRating")?.toFloatOrNull(),
            text(o, "releaseInfo")?.take(4)?.toIntOrNull(),
            description = text(o, "description"),
        )
    }

    fun streams(o: JSONObject): List<Stream> =
        objects(o.optJSONArray("streams")).mapNotNull { s ->
            // externalUrl is a webpage; infoHash and archives need a separate streaming service.
            val url = http(text(s, "url")) ?: return@mapNotNull null
            val request =
                s.optJSONObject("behaviorHints")
                    ?.optJSONObject("proxyHeaders")
                    ?.optJSONObject("request")
            val headers = mutableMapOf<String, String>()
            request?.keys()?.forEach { k ->
                val v = request.opt(k) as? String
                if (
                    k.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) &&
                        v != null &&
                        '\r' !in v &&
                        '\n' !in v &&
                        k.lowercase() !in
                            setOf("host", "connection", "content-length", "transfer-encoding")
                )
                    headers[k] = v
            }
            // Do not infer spoken language from free-form addon release names.
            val description = listOfNotNull(text(s, "name"), text(s, "title")).joinToString(" ")
            val label =
                Regex("(?i)(2160|1440|1080|720|576|480|360|240)p\\b").find(description)?.value
            Stream(url, label, headers)
        }
}

data class StremioAddon(val url: String, val manifest: JSONObject) {
    val key: String
        get() = StremioProtocol.key(url)

    val name: String
        get() = StremioProtocol.text(manifest, "name") ?: "Stremio addon"
}

/** Pure protocol adapter; storage/network injected for deterministic tests. */
class StremioExtension(
    private val addon: StremioAddon,
    private val peers: () -> List<StremioAddon>,
    private val fetch: suspend (String) -> JSONObject,
    private val enabled: (String) -> Boolean,
) : Extension {
    override val id = addon.key
    override val name = addon.name
    override val version = 1

    private val previews = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    fun streamCapabilityNote(itemId: String): String? {
        val token = runCatching { JSONObject(itemId) }.getOrNull() ?: return null
        val type = token.optString("type")
        val raw = token.optString("id")
        return if (
            (listOf(addon) + peers()).any {
                enabled(it.key) && p.supports(it.manifest, "stream", type, raw)
            }
        )
            null
        else
            "This addon lists titles. No enabled addon declares streams for this title ID. Add and enable a compatible direct-stream addon in Sources & extensions to watch or download."
    }

    fun rememberItem(item: MediaItem) {
        val t = runCatching { JSONObject(item.id) }.getOrNull() ?: return
        if (t.optString("addon") != id || previews.containsKey(item.id)) return
        previews[item.id] =
            JSONObject()
                .put("id", t.getString("id"))
                .put("type", t.getString("type"))
                .put("name", item.title)
                .put("poster", item.poster)
                .put("description", item.description)
    }

    private fun checkEnabled() {
        check(enabled(id)) { "Enable this Stremio addon in Sources & extensions" }
    }

    private val p
        get() = StremioProtocol

    private suspend fun catalogs(query: String?, page: Int): List<HomeSection> {
        checkEnabled()
        val sections = mutableListOf<HomeSection>()
        for (c in p.objects(addon.manifest.optJSONArray("catalogs")).take(12)) {
            val type = p.text(c, "type") ?: continue
            val cid = p.text(c, "id") ?: continue
            val extras = p.objects(c.optJSONArray("extra"))
            val names =
                extras.mapNotNull { p.text(it, "name") } +
                    p.strings(c.optJSONArray("extraSupported"))
            val required =
                extras.filter { it.optBoolean("isRequired") }.mapNotNull { p.text(it, "name") } +
                    p.strings(c.optJSONArray("extraRequired"))
            val args = mutableMapOf<String, String>()
            if (query != null) {
                if ("search" !in names) continue
                args["search"] = query
            }
            if (page > 1) {
                if ("skip" !in names) continue
                args["skip"] = ((page - 1) * 100).toString()
            }
            if (required.any { it !in args }) continue
            if (!p.supports(addon.manifest, "catalog", type, cid)) continue
            val rows =
                p.objects(
                        fetch(p.endpoint(addon.url, "catalog", type, cid, args))
                            .optJSONArray("metas")
                    )
                    .mapNotNull { o ->
                        if (!o.has("type")) o.put("type", type)
                        val raw = p.text(o, "id")
                        if (raw != null) {
                            if (previews.size >= 600)
                                previews.keys.firstOrNull()?.let { previews.remove(it) }
                            previews[p.token(id, type, raw)] = JSONObject(o.toString())
                        }
                        p.item(id, o)
                    }
                    .distinctBy { it.id }
            if (rows.isNotEmpty())
                sections.add(HomeSection("$name · ${p.text(c,"name") ?: cid}", rows))
        }
        return sections
    }

    override suspend fun getHome() = catalogs(null, 1)

    override suspend fun search(query: String, page: Int) =
        catalogs(query, page).flatMap { it.items }.distinctBy { it.id }

    override suspend fun getDetails(id: String): MediaDetails {
        checkEnabled()
        val t = JSONObject(id)
        require(t.getString("addon") == this.id) { "Wrong addon item" }
        val type = t.getString("type")
        val raw = t.getString("id")
        // Metadata-only and stream-only addons can cooperate, without automatically installing
        // Cinemeta.
        val candidates =
            (listOf(addon) + peers())
                .distinctBy { it.key }
                .filter { enabled(it.key) && p.supports(it.manifest, "meta", type, raw) }
        var meta: JSONObject? = null
        for (a in candidates) {
            try {
                meta = fetch(p.endpoint(a.url, "meta", type, raw)).optJSONObject("meta")
                if (meta != null) break
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (_: Exception) {}
        }
        // A catalog-only addon is valid. Movies already have their protocol video ID;
        // series still require real videos from metadata, never guessed season/episode IDs.
        val preview = previews[id]
        val m =
            meta
                ?: preview?.let { JSONObject(it.toString()) }
                ?: throw AddonCapabilityException(
                    "This addon supplies catalogs but no title metadata. Add and enable a compatible metadata addon in Sources & extensions. No metadata service is installed automatically."
                )
        if (
            type !in listOf("movie", "tv", "channel") &&
                p.objects(m.optJSONArray("videos")).isEmpty()
        )
            throw AddonCapabilityException(
                "This catalog does not supply episode IDs. Add and enable a compatible metadata addon to load the real season and episode list."
            )
        m.put("id", raw).put("type", type)
        val item = p.item(this.id, m) ?: error("Addon returned invalid metadata")
        val videos = p.objects(m.optJSONArray("videos"))
        val episodes =
            if (type == "movie" || type == "tv" || type == "channel")
                listOf(Episode(p.token(this.id, type, raw), 1f, item.title, item.poster))
            else
                videos.mapIndexedNotNull { i, v ->
                    val vid = p.text(v, "id") ?: return@mapIndexedNotNull null
                    val season = v.optInt("season", 0)
                    val number = v.optInt("episode", i + 1)
                    val title = p.text(v, "title") ?: p.text(v, "name") ?: "Episode $number"
                    Episode(
                        p.token(this.id, type, vid),
                        (i + 1).toFloat(),
                        if (season > 0) "S$season E$number · $title" else title,
                        p.http(p.text(v, "thumbnail")),
                    )
                }
        return MediaDetails(item, episodes)
    }

    override suspend fun getStreams(episodeId: String): List<Stream> {
        checkEnabled()
        val t = JSONObject(episodeId)
        require(t.getString("addon") == id) { "Wrong addon episode" }
        val type = t.getString("type")
        val raw = t.getString("id")
        val out = mutableListOf<Stream>()
        var unsupported = false
        for (a in
            (listOf(addon) + peers())
                .distinctBy { it.key }
                .filter { enabled(it.key) && p.supports(it.manifest, "stream", type, raw) }) {
            try {
                val response = fetch(p.endpoint(a.url, "stream", type, raw))
                val links = p.streams(response)
                if (p.objects(response.optJSONArray("streams")).size > links.size)
                    unsupported = true
                if (enabled(a.key)) out.addAll(links)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (_: Exception) {}
        }
        checkEnabled()
        if (out.isEmpty())
            throw AddonCapabilityException(
                if (unsupported)
                    "Addon returned only unsupported stream types. Direct HTTP(S) media is supported; torrents, archives, YouTube IDs and external webpages are not."
                else
                    "No enabled addon returned a direct media URL. Catalog addons list titles but may not supply video. Add and enable a compatible direct-stream addon in Sources & extensions."
            )
        return out.distinctBy { it.url to it.headers }
    }
}
