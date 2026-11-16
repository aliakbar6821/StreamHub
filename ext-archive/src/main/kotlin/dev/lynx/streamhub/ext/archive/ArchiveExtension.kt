package dev.lynx.streamhub.ext.archive

import android.net.Uri
import dev.lynx.streamhub.api.Episode
import dev.lynx.streamhub.api.Extension
import dev.lynx.streamhub.api.HomeSection
import dev.lynx.streamhub.api.MediaDetails
import dev.lynx.streamhub.api.MediaItem
import dev.lynx.streamhub.api.MediaStatus
import dev.lynx.streamhub.api.MediaType
import dev.lynx.streamhub.api.Stream
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ArchiveExtension : Extension {
    override val id = "archive.org"
    override val name = "Internet Archive"
    override val version = 1

    private val base = "https://archive.org"

    override suspend fun getHome(): List<HomeSection> = listOf(
        HomeSection(
            "Feature Films",
            query("collection:feature_films AND mediatype:movies", MediaType.MOVIE),
        ),
        HomeSection(
            "Classic TV",
            query("collection:classic_tv AND mediatype:movies", MediaType.SERIES),
        ),
        HomeSection(
            "Animation & Cartoons",
            query("collection:animationandcartoons AND mediatype:movies", MediaType.SERIES),
        ),
    ).filter { it.items.isNotEmpty() }

    override suspend fun getGenres(): List<String> =
        listOf("All", "Films", "TV", "Cartoons")

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        query("title:($query) AND mediatype:movies", MediaType.MOVIE, page)

    override suspend fun getDetails(id: String): MediaDetails {
        val root = JSONObject(get("$base/metadata/$id"))
        val meta = root.optJSONObject("metadata")
        val files = root.optJSONArray("files") ?: JSONArray()
        val episodes = mutableListOf<Episode>()
        for (i in 0 until files.length()) {
            val name = files.getJSONObject(i).optString("name")
            if (name.endsWith(".mp4", ignoreCase = true)) {
                episodes.add(
                    Episode(
                        id = "$id/$name",
                        number = (episodes.size + 1).toFloat(),
                        title = name.substringBeforeLast('.'),
                    )
                )
            }
        }
        val item = MediaItem(
            id = id,
            title = meta?.optString("title").takeUnless { it.isNullOrBlank() } ?: id,
            poster = "$base/services/img/$id",
            type = if (episodes.size > 1) MediaType.SERIES else MediaType.MOVIE,
            year = meta?.optString("year")?.take(4)?.toIntOrNull(),
            status = MediaStatus.COMPLETED,
        )
        return MediaDetails(item, episodes)
    }

    override suspend fun getStreams(episodeId: String): List<Stream> {
        val id = episodeId.substringBefore("/")
        val file = episodeId.substringAfter("/")
        return listOf(Stream("$base/download/$id/${Uri.encode(file, "/")}", "Original"))
    }

    private fun query(q: String, type: MediaType, page: Int = 1, rows: Int = 20): List<MediaItem> {
        val url = "$base/advancedsearch.php?q=${Uri.encode(q)}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year" +
            "&sort%5B%5D=downloads%20desc&rows=$rows&page=$page&output=json"
        val docs = JSONObject(get(url)).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            val id = d.getString("identifier")
            MediaItem(
                id = id,
                title = d.optString("title", id),
                poster = "$base/services/img/$id",
                type = type,
                year = d.optString("year").take(4).toIntOrNull(),
                status = MediaStatus.COMPLETED,
            )
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "StreamHub/0.1")
        return try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
