package dev.lynx.streamhub
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AniRel(
    val title: String, val image: String, val format: String, val eps: Int?, val dur: Int?,
    val start: String, val end: String, val year: Int?, val key: Int,
)

data class AniInfo(
    val banner: String?, val cover: String?, val studio: String?, val score: Int?, val episodes: Int?,
    val status: String?, val format: String?, val year: Int?, val genres: List<String>,
    val nextEp: Int?, val nextAt: Long?, val order: List<AniRel>, val similar: List<AniRel>,
)

object AniMeta {
    @Volatile var lastError: String? = null
    private val cache = HashMap<String, AniInfo?>()
    private val MON = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private const val Q = "query(\$s:String){Media(search:\$s,type:ANIME){title{romaji english native} synonyms bannerImage coverImage{extraLarge large} averageScore episodes duration status format seasonYear genres studios(isMain:true){nodes{name}} startDate{year month day} endDate{year month day} nextAiringEpisode{episode airingAt} relations{edges{relationType node{type title{romaji english} coverImage{large extraLarge} format episodes duration seasonYear startDate{year month day} endDate{year month day}}}} recommendations(perPage:12,sort:RATING_DESC){nodes{mediaRecommendation{title{romaji english} coverImage{extraLarge large} format seasonYear}}}}}"

    private fun JSONObject.str(k: String): String? =
        if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.int(k: String): Int? = if (isNull(k)) null else optInt(k)

    private fun titleOf(t: JSONObject?): String = t?.str("english") ?: t?.str("romaji") ?: ""

    private fun dateOf(d: JSONObject?): String {
        if (d == null) return "N/A"
        val y = d.int("year") ?: return "N/A"
        val m = d.int("month")
        val dd = d.int("day")
        return when {
            m == null || m !in 1..12 -> "$y"
            dd == null -> "${MON[m - 1]} $y"
            else -> "${MON[m - 1]} $dd, $y"
        }
    }

    private fun keyOf(d: JSONObject?): Int {
        if (d == null) return 99999999
        val y = d.int("year") ?: return 99999999
        return y * 10000 + (d.int("month") ?: 0) * 100 + (d.int("day") ?: 0)
    }

    private fun fmt(f: String?): String {
        if (f == null) return "TV"
        return when (f) {
            "TV_SHORT" -> "TV Short"
            "MOVIE" -> "Movie"
            "TV", "OVA", "ONA" -> f
            else -> f.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private fun statusText(s: String?): String? = when (s) {
        "RELEASING" -> "Airing"
        "FINISHED" -> "Finished"
        "NOT_YET_RELEASED" -> "Upcoming"
        "CANCELLED" -> "Cancelled"
        "HIATUS" -> "Hiatus"
        else -> null
    }

    private fun cover(n: JSONObject): String =
        n.optJSONObject("coverImage")?.let { it.str("extraLarge") ?: it.str("large") } ?: ""

    private fun rel(n: JSONObject) = AniRel(
        titleOf(n.optJSONObject("title")), cover(n), fmt(n.str("format")), n.int("episodes"), n.int("duration"),
        dateOf(n.optJSONObject("startDate")), dateOf(n.optJSONObject("endDate")), n.int("seasonYear"),
        keyOf(n.optJSONObject("startDate")),
    )

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun matches(q: String, m: JSONObject): Boolean {
        val nq = norm(q).take(8)
        if (nq.length < 3) return false
        val names = ArrayList<String>()
        m.optJSONObject("title")?.let { t ->
            listOf("romaji", "english", "native").forEach { k -> t.str(k)?.let { names.add(it) } }
        }
        m.optJSONArray("synonyms")?.let { a -> for (i in 0 until a.length()) names.add(a.getString(i)) }
        return names.any { norm(it).contains(nq) }
    }

    private fun clean(t: String): String = t
        .replace(Regex("\\(.*?\\)"), "")
        .replace(Regex("(?i)\\b(season|part|cour)\\s*\\d+\\b"), "")
        .replace(Regex("\\s+"), " ").trim()

    suspend fun fetch(title: String): AniInfo? = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[title] }?.let { return@withContext it }
        var r: AniInfo? = null
        for (t in listOf(title, clean(title)).filter { it.isNotBlank() }.distinct()) {
            for (attempt in 0..1) {
                try {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    r = load(t)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    break
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
                } catch (e: Exception) {
                    lastError = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(120)
                }
            }
            if (r != null) break
        }
        if (r != null) {
            lastError = null
            synchronized(cache) { cache[title] = r }
        }
        r
    }

    private fun load(query: String): AniInfo? {
        val con = URL("https://graphql.anilist.co").openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.doOutput = true
        con.connectTimeout = 25000
        con.readTimeout = 60000
        con.setRequestProperty("Content-Type", "application/json")
        con.setRequestProperty("Accept", "application/json")
        con.setRequestProperty("User-Agent", "StreamHub/0.1")
        val body = JSONObject().put("query", Q).put("variables", JSONObject().put("s", query))
        val text = try {
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (con.responseCode != 200) {
                lastError = "AniList HTTP " + con.responseCode
                return null
            }
            con.inputStream.bufferedReader().use { it.readText() }
        } finally {
            con.disconnect()
        }
        val m = JSONObject(text).optJSONObject("data")?.optJSONObject("Media")
        if (m == null) {
            lastError = "AniList returned no result"
            return null
        }
        if (!matches(query, m)) {
            lastError = "No AniList match for \"" + query + "\""
            return null
        }

        val order = ArrayList<AniRel>()
        order.add(rel(m))
        val okTypes = setOf("PREQUEL", "SEQUEL", "SIDE_STORY", "SPIN_OFF", "SUMMARY", "PARENT", "ALTERNATIVE")
        val edges = m.optJSONObject("relations")?.optJSONArray("edges")
        if (edges != null) for (i in 0 until edges.length()) {
            val e = edges.getJSONObject(i)
            val n = e.optJSONObject("node") ?: continue
            if (e.optString("relationType") in okTypes && n.optString("type") == "ANIME") order.add(rel(n))
        }
        val sim = ArrayList<AniRel>()
        val rn = m.optJSONObject("recommendations")?.optJSONArray("nodes")
        if (rn != null) for (i in 0 until rn.length()) {
            val n = rn.getJSONObject(i).optJSONObject("mediaRecommendation") ?: continue
            sim.add(rel(n))
        }
        val genres = ArrayList<String>()
        val gl = m.optJSONArray("genres")
        if (gl != null) {
            for (i in 0 until gl.length()) { genres.add(gl.getString(i)) }
        }
        val next = m.optJSONObject("nextAiringEpisode")
        return AniInfo(
            banner = m.str("bannerImage"),
            cover = cover(m).ifBlank { null },
            studio = m.optJSONObject("studios")?.optJSONArray("nodes")?.optJSONObject(0)?.str("name"),
            score = m.int("averageScore"),
            episodes = m.int("episodes"),
            status = statusText(m.str("status")),
            format = fmt(m.str("format")),
            year = m.int("seasonYear"),
            genres = genres,
            nextEp = next?.int("episode"),
            nextAt = next?.let { if (it.isNull("airingAt")) null else it.optLong("airingAt") * 1000 },
            order = order.distinctBy { it.title }.sortedBy { it.key },
            similar = sim,
        )
    }
}
