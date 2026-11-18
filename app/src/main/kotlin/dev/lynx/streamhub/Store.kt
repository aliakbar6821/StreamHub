package dev.lynx.streamhub

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import dev.lynx.streamhub.api.Episode
import dev.lynx.streamhub.api.Extension
import dev.lynx.streamhub.api.MediaItem
import dev.lynx.streamhub.api.MediaStatus
import dev.lynx.streamhub.api.MediaType
import org.json.JSONArray
import org.json.JSONObject

data class SavedItem(val item: MediaItem, val extId: String)

data class HistoryEntry(
    val item: MediaItem,
    val extId: String,
    val episodeId: String,
    val episodeNumber: Float,
    val episodeTitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val updated: Long,
)

data class DownloadEntry(
    val item: MediaItem,
    val episodeId: String,
    val episodeNumber: Float,
    val episodeTitle: String,
    val path: String,
    val downloadId: Long,
)

data class PlayRequest(
    val ext: Extension?,
    val item: MediaItem,
    val episode: Episode,
    val startMs: Long,
    val localPath: String?,
)

object Store {
    private var prefs: SharedPreferences? = null
    val myList = mutableStateListOf<SavedItem>()
    val history = mutableStateListOf<HistoryEntry>()
    val downloads = mutableStateListOf<DownloadEntry>()
    val watched = mutableStateListOf<String>()
    val repos = mutableStateListOf<String>()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("streamhub", Context.MODE_PRIVATE)
        prefs = p
        Safety.enabled = p.getBoolean("safe", true)
        runCatching { Session.recent.addAll(strings("recent")) }
        runCatching { repos.addAll(strings("repos")) }
        runCatching { watched.addAll(strings("watched")) }
        runCatching {
            objs("mylist").forEach {
                myList.add(SavedItem(itemFrom(it.getJSONObject("item")), it.optString("ext")))
            }
        }
        runCatching {
            objs("history").forEach {
                history.add(
                    HistoryEntry(
                        itemFrom(it.getJSONObject("item")),
                        it.optString("ext"),
                        it.getString("epId"),
                        it.optDouble("epNum", 1.0).toFloat(),
                        it.optString("epTitle"),
                        it.optLong("pos"),
                        it.optLong("dur"),
                        it.optLong("ts"),
                    )
                )
            }
        }
        runCatching {
            objs("downloads").forEach {
                downloads.add(
                    DownloadEntry(
                        itemFrom(it.getJSONObject("item")),
                        it.getString("epId"),
                        it.optDouble("epNum", 1.0).toFloat(),
                        it.optString("epTitle"),
                        it.getString("path"),
                        it.optLong("dlId", -1L),
                    )
                )
            }
        }
    }

    // ---- low level ----
    private fun arr(key: String): JSONArray = try {
        JSONArray(prefs?.getString(key, "[]") ?: "[]")
    } catch (t: Throwable) {
        JSONArray()
    }

    private fun put(key: String, a: JSONArray) {
        prefs?.edit()?.putString(key, a.toString())?.apply()
    }

    private fun objs(key: String): List<JSONObject> {
        val a = arr(key)
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun strings(key: String): List<String> {
        val a = arr(key)
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun putStrings(key: String, l: List<String>) {
        val a = JSONArray()
        l.forEach { a.put(it) }
        put(key, a)
    }

    private fun itemJson(i: MediaItem): JSONObject = JSONObject()
        .put("id", i.id)
        .put("title", i.title)
        .put("poster", i.poster.orEmpty())
        .put("type", i.type.toString())
        .put("year", i.year ?: JSONObject.NULL)
        .put("status", i.status.toString())

    private fun itemFrom(o: JSONObject): MediaItem {
        val type = runCatching { MediaType.valueOf(o.optString("type")) }.getOrDefault(MediaType.MOVIE)
        val status = runCatching { MediaStatus.valueOf(o.optString("status")) }.getOrDefault(MediaStatus.COMPLETED)
        return MediaItem(
            id = o.getString("id"),
            title = o.optString("title"),
            poster = o.optString("poster"),
            type = type,
            year = if (o.isNull("year")) null else o.optInt("year"),
            status = status,
        )
    }

    // ---- recents / safe mode / repos ----
    fun saveRecent() = putStrings("recent", Session.recent.toList())
    fun saveSafe() {
        prefs?.edit()?.putBoolean("safe", Safety.enabled)?.apply()
    }

    fun addRepo(url: String) {
        if (url !in repos) repos.add(url)
        putStrings("repos", repos.toList())
    }

    fun removeRepo(url: String) {
        repos.remove(url)
        putStrings("repos", repos.toList())
    }

    // ---- My List ----
    fun inList(id: String) = myList.any { it.item.id == id }

    fun toggleList(item: MediaItem, extId: String) {
        val idx = myList.indexOfFirst { it.item.id == item.id }
        if (idx >= 0) myList.removeAt(idx) else myList.add(0, SavedItem(item, extId))
        val a = JSONArray()
        myList.forEach { a.put(JSONObject().put("item", itemJson(it.item)).put("ext", it.extId)) }
        put("mylist", a)
    }

    // ---- History ----
    fun historyFor(itemId: String): HistoryEntry? = history.firstOrNull { it.item.id == itemId }

    fun record(item: MediaItem, extId: String, ep: Episode, pos: Long, dur: Long) {
        val e = HistoryEntry(item, extId, ep.id, ep.number, ep.title ?: "", pos, dur, System.currentTimeMillis())
        val idx = history.indexOfFirst { it.item.id == item.id }
        if (idx >= 0) history.removeAt(idx)
        history.add(0, e)
        while (history.size > 30) history.removeAt(history.size - 1)
        saveHistory()
        if (dur > 0 && pos > dur * 0.9) markWatched(ep.id)
    }

    private fun saveHistory() {
        val a = JSONArray()
        history.forEach {
            a.put(
                JSONObject()
                    .put("item", itemJson(it.item))
                    .put("ext", it.extId)
                    .put("epId", it.episodeId)
                    .put("epNum", it.episodeNumber.toDouble())
                    .put("epTitle", it.episodeTitle)
                    .put("pos", it.positionMs)
                    .put("dur", it.durationMs)
                    .put("ts", it.updated)
            )
        }
        put("history", a)
    }

    fun clearHistory() {
        history.clear()
        saveHistory()
    }

    fun isWatched(epId: String) = watched.contains(epId)

    fun unmarkWatched(epId: String) {
        if (watched.remove(epId)) putStrings("watched", watched.toList())
    }

    fun markWatched(epId: String) {
        if (!watched.contains(epId)) {
            watched.add(epId)
            while (watched.size > 2000) watched.removeAt(0)
            putStrings("watched", watched.toList())
        }
    }

    // ---- Downloads ----
    fun hasDownload(epId: String) = downloads.any { it.episodeId == epId }

    fun addDownload(d: DownloadEntry) {
        downloads.add(0, d)
        saveDownloads()
    }

    fun removeDownload(d: DownloadEntry) {
        downloads.remove(d)
        saveDownloads()
    }

    private fun saveDownloads() {
        val a = JSONArray()
        downloads.forEach {
            a.put(
                JSONObject()
                    .put("item", itemJson(it.item))
                    .put("epId", it.episodeId)
                    .put("epNum", it.episodeNumber.toDouble())
                    .put("epTitle", it.episodeTitle)
                    .put("path", it.path)
                    .put("dlId", it.downloadId)
            )
        }
        put("downloads", a)
    }

    // ---- extension lookup for items opened from My List / History ----
    fun extIdFor(itemId: String): String? =
        myList.firstOrNull { it.item.id == itemId }?.extId?.takeIf { it.isNotEmpty() }
            ?: history.firstOrNull { it.item.id == itemId }?.extId?.takeIf { it.isNotEmpty() }

    fun ownerOf(itemId: String): Extension? {
        val id = extIdFor(itemId) ?: itemId.takeIf { it.startsWith("cs3|") }
            ?.split("|", limit = 3)?.getOrNull(1)?.let { "cs3:$it" }
        val active = Session.extensions.filter { ExtensionLoader.isEnabled(it) }
        return if (id != null) active.firstOrNull { it.id == id } else active.singleOrNull()
    }
}
