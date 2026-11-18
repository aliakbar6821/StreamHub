package dev.lynx.streamhub

import dev.lynx.streamhub.api.MediaItem
import dev.lynx.streamhub.api.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Community timestamps, not video/audio fingerprinting. Only exact, unique title matches qualify. */
object SkipRepository {
    data class Result(val segments: List<SkipSegment>, val message: String)
    private val cache = object : LinkedHashMap<String, Result>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?): Boolean = size > 64
    }
    private fun norm(value: String) = value.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
    fun matchMal(response: JSONObject, item: MediaItem): Int? {
        val nodes = response.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return null
        val matches = mutableSetOf<Int>()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            if (item.year != null && node.optInt("seasonYear") != item.year) continue
            val names = mutableListOf<String>()
            node.optJSONObject("title")?.let { t ->
                listOf("romaji", "english", "native").forEach { names.add(t.optString(it)) }
            }
            node.optJSONArray("synonyms")?.let { a -> for (j in 0 until a.length()) names.add(a.optString(j)) }
            if (names.any { it.isNotBlank() && norm(it) == norm(item.title) }) {
                node.optInt("idMal").takeIf { it > 0 }?.let { matches.add(it) }
            }
        }
        return matches.singleOrNull()
    }
    private fun request(url: String, body: String? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 6000; connection.readTimeout = 6000
            connection.setRequestProperty("User-Agent", "streamHub/0.2.13")
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == 404) return JSONObject()
            check(status in 200..299) { "Timestamp service unavailable" }
            return connection.inputStream.bufferedReader().use { input ->
                // Bound metadata responses, including unexpectedly large service errors.
                val text = StringBuilder(); val buffer = CharArray(4096)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    check(text.length + n <= 1_000_000) { "Timestamp response too large" }
                    text.append(buffer, 0, n)
                }
                JSONObject(text.toString())
            }
        } finally { connection.disconnect() }
    }
    suspend fun fetch(item: MediaItem, episode: Float, durationMs: Long, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        if (item.type != MediaType.ANIME) return@withContext Result(emptyList(),
            "Automatic matching currently supports anime. Set exact markers below for this video.")
        if (!episode.isFinite() || episode <= 0 || durationMs <= 0) return@withContext Result(emptyList(), "Episode timing unavailable.")
        val key = "${item.title}|${item.year}|$episode|$durationMs"
        if (force) synchronized(cache) { cache.remove(key) }
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        try {
            val query = "query(\$s:String){Page(perPage:10){media(search:\$s,type:ANIME){idMal seasonYear title{romaji english native} synonyms}}}"
            val body = JSONObject().put("query", query).put("variables", JSONObject().put("s", item.title)).toString()
            val mal = matchMal(request("https://graphql.anilist.co", body), item)
                ?: return@withContext Result(emptyList(), "No unambiguous title match. No guessed skip times.")
            currentCoroutineContext().ensureActive()
            val number = java.math.BigDecimal(episode.toString()).stripTrailingZeros().toPlainString()
            val response = request("https://api.aniskip.com/v2/skip-times/$mal/$number?types=op&types=ed&episodeLength=${durationMs / 1000.0}")
            currentCoroutineContext().ensureActive()
            val segments = SkipSegments.parse(response, durationMs)
            Result(segments, if (segments.isEmpty()) "No compatible intro/outro timestamps for this episode/cut."
                else "AniSkip matched ${segments.size} segment(s). You choose whether to skip.")
                .also { synchronized(cache) { cache[key] = it } }
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) { Result(emptyList(), "Timestamp lookup unavailable. Playback is unaffected; tap Retry.") }
    }
}
