package dev.lynx.streamhub

import org.json.JSONObject

/** Sidecar metadata preserves the binary-compatible three-argument extension Stream API. */
object ProviderPlayback {
    data class Audio(val url: String, val headers: Map<String, String>)
    data class Video(val mime: String? = null, val audio: List<Audio> = emptyList(), val segments: List<SkipSegment> = emptyList(), val audioVariant: String? = null)
    data class Episode(val season: Int?, val number: Float, val malId: Int?, val segments: List<SkipSegment>)
    private val videos = linkedMapOf<String, Video>()
    private val episodes = linkedMapOf<String, Episode>()
    private fun property(value: Any, name: String): Any? = runCatching {
        value.javaClass.methods.firstOrNull { it.name == "get" + name.replaceFirstChar { c -> c.uppercase() } && it.parameterCount == 0 }?.invoke(value)
    }.getOrNull()
    private fun web(url: String) = url.startsWith("https://", true) || url.startsWith("http://", true)
    fun parseSegments(raw: String?): List<SkipSegment> = runCatching {
        if (raw.isNullOrBlank() || raw.length > 256_000) return@runCatching emptyList()
        val root = JSONObject(raw)
        val objects = listOfNotNull(root, root.optJSONObject("skip"), root.optJSONObject("skipTimes"))
        val output = mutableListOf<SkipSegment>()
        for (obj in objects) for ((name, kind) in listOf("intro" to "op", "outro" to "ed")) {
            val range = obj.optJSONObject(name) ?: continue
            val ms = range.has("startMs") && range.has("endMs")
            val start = range.optDouble(if (ms) "startMs" else "start", Double.NaN)
            val end = range.optDouble(if (ms) "endMs" else "end", Double.NaN)
            if (start.isFinite() && end.isFinite() && start >= 0 && end > start) {
                val factor = if (ms) 1 else 1000
                output.add(SkipSegment(kind, (start * factor).toLong(), (end * factor).toLong()))
            }
        }
        output.distinct().groupBy { it.kind }.values.mapNotNull { it.singleOrNull() }
    }.getOrDefault(emptyList())
    @Synchronized fun capture(link: Any, url: String, baseHeaders: Map<String, String>) {
        val type = property(link, "type")?.toString()?.uppercase()
        val mime = when(type) { "M3U8" -> "application/x-mpegURL"; "DASH" -> "application/dash+xml"; else -> null }
        val tracks = (property(link, "audioTracks") as? List<*>)?.mapNotNull { raw ->
            raw ?: return@mapNotNull null
            val audioUrl = property(raw, "url") as? String ?: return@mapNotNull null
            if (!web(audioUrl)) return@mapNotNull null
            val own = (property(raw, "headers") as? Map<*, *>)?.entries?.mapNotNull { e ->
                val k=e.key as? String; val v=e.value as? String
                if (k!=null && v!=null) k to v else null
            }?.toMap().orEmpty()
            // Do not forward a video's auth headers to another host. Provider audio headers win.
            val sameHost = runCatching { java.net.URI(url).host.equals(java.net.URI(audioUrl).host, true) }.getOrDefault(false)
            Audio(audioUrl, if (sameHost) baseHeaders + own else own)
        }.orEmpty().distinctBy { it.url }.take(12)
        videos[url] = Video(mime, tracks, parseSegments(property(link, "extractorData") as? String))
        while(videos.size>256) videos.remove(videos.keys.first())
    }
    @Synchronized fun rememberAudioVariant(url:String,variant:String) { videos[url]=video(url).copy(audioVariant=variant) }
    @Synchronized fun video(url: String): Video = videos[url] ?: Video()
    @Synchronized fun rememberEpisode(provider: String, data: String, season: Int?, number: Float, sync: Map<String,String>) {
        val mal = listOf("mal", "myanimelist", "myanimelist.net").mapNotNull { sync[it]?.toIntOrNull() }.distinct().singleOrNull()?.takeIf { it>0 }
        episodes["$provider\u0000$data"] = Episode(season, number, mal, parseSegments(data))
        while(episodes.size>2048) episodes.remove(episodes.keys.first())
    }
    @Synchronized fun episode(provider: String, data: String): Episode? = episodes["$provider\u0000$data"]
}
