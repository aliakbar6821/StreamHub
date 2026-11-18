package dev.lynx.streamhub

import dev.lynx.streamhub.api.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** These fields describe variants from one provider, not different providers. */
data class StreamChoice(val language: String?, val height: Int?, val codec: String?) {
    val audioKey: String get() = language ?: ""
    val audioLabel: String get() = if (language == "original") "Original"
        else audioLanguageName(language) ?: "Original"
}

private val audioNames = linkedMapOf(
    "en" to "English", "ja" to "Japanese", "hi" to "Hindi", "ur" to "Urdu",
    "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam", "kn" to "Kannada",
    "bn" to "Bengali", "mr" to "Marathi", "pa" to "Punjabi", "gu" to "Gujarati",
    "ar" to "Arabic", "ko" to "Korean", "zh" to "Chinese", "fr" to "French",
    "es" to "Spanish", "de" to "German", "it" to "Italian", "pt" to "Portuguese",
    "ru" to "Russian", "id" to "Indonesian", "th" to "Thai", "vi" to "Vietnamese",
)
fun audioLanguageCode(value: String?): String? {
    val text = value?.trim()?.lowercase(Locale.ROOT) ?: return null
    if (text.isBlank() || text == "und") return null
    val base = text.replace('_', '-').substringBefore('-')
    val code = mapOf("eng" to "en", "jpn" to "ja", "hin" to "hi", "urd" to "ur",
        "tam" to "ta", "tel" to "te", "mal" to "ml", "ben" to "bn")[base] ?: base
    return if (code in audioNames) code
        else audioNames.entries.firstOrNull { it.value.equals(text, true) }?.key
}
fun audioLanguageName(code: String?): String? = audioLanguageCode(code)?.let { audioNames[it] }
private val resolution = Regex("(?i)\\b([0-9]{3,4})\\s*p\\b")
fun streamChoice(stream: Stream): StreamChoice {
    val label = stream.quality.orEmpty()
    // Parse explicit audio-labelled fields only. 'Dub' alone does NOT mean English.
    val audio = Regex("(?i)\\(([^()]*)\\s+audio\\)").find(label)?.groupValues?.get(1)?.trim()
        ?: Regex("(?i)\\baudio\\s*:\\s*([A-Za-z-]+)").find(label)?.groupValues?.get(1)
    val language = if (audio.equals("original", true)) "original" else audioLanguageCode(audio)
    val height = resolution.findAll(label).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        ?: if (Regex("(?i)\\b4k\\b").containsMatchIn(label)) 2160 else null
    val codec = when {
        Regex("(?i)\\b(hevc|h[ .-]?265)\\b").containsMatchIn(label) -> "hevc"
        Regex("(?i)\\b(avc|h[ .-]?264)\\b").containsMatchIn(label) -> "avc"
        else -> null
    }
    return StreamChoice(language, height, codec)
}
fun providerDisplayName(name: String?): String = name?.replace(Regex("(?i)provider$"), "")
    ?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown source"

/** Select a file for a language/height; prefer the current codec, then AVC. */
fun bestStreamIndex(streams: List<Stream>, language: String, height: Int?, codec: String? = null): Int? =
    streams.indices.filter { streamChoice(streams[it]).audioKey == language }
        .sortedWith(compareBy<Int> {
            val h = streamChoice(streams[it]).height
            if (height != null && h == height) 0 else 1
        }.thenBy {
            val c = streamChoice(streams[it]).codec
            when { codec != null && c == codec -> 0; c == "avc" -> 1; c == null -> 2; else -> 3 }
        }.thenByDescending { streamChoice(streams[it]).height ?: 0 }).firstOrNull()

fun downloadChoicesAtHeight(streams: List<Stream>, height: Int?): List<Stream> =
    streams.filter { streamChoice(it).height == height }

/** Short-lived, in-memory reuse of already discovered signed video links. */
object StreamChoiceCache {
    private data class Entry(val time: Long, val streams: List<Stream>)
    private val values = ConcurrentHashMap<String, Entry>()
    fun put(source: String, episode: String, streams: List<Stream>) {
        if (streams.isEmpty()) return
        values["$source|$episode"] = Entry(System.currentTimeMillis(), streams.toList())
        if (values.size > 16) values.entries.minByOrNull { it.value.time }?.let { values.remove(it.key) }
    }
    fun get(source: String, episode: String): List<Stream> {
        val key = "$source|$episode"
        val entry = values[key] ?: return emptyList()
        if (System.currentTimeMillis() - entry.time > 90_000L) {
            values.remove(key); return emptyList()
        }
        return entry.streams
    }
}

/** Expand a self-contained HLS master into actual download resolutions.
 * Separate audio/video renditions require muxing, which our downloader does not do.
 */
suspend fun downloadVariants(stream: Stream): Pair<List<Stream>, String?> = withContext(Dispatchers.IO) {
    if (!isHls(stream)) return@withContext listOf(stream) to null
    val con = URL(stream.url).openConnection() as HttpURLConnection
    con.connectTimeout = 8000
    con.readTimeout = 8000
    con.setRequestProperty("User-Agent", Downloader.UA)
    stream.headers.forEach { (k, v) -> con.setRequestProperty(k, v) }
    try {
        require(con.responseCode in 200..299) { "HTTP ${con.responseCode}" }
        val text = con.inputStream.bufferedReader().use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(result.length + count <= 1_000_000) { "Playlist is too large" }
                result.append(buffer, 0, count)
            }
            result.toString()
        }
        require(text.trimStart().startsWith("#EXTM3U")) { "Invalid HLS playlist" }
        if (!text.contains("#EXT-X-STREAM-INF")) return@withContext listOf(stream) to null
        val lines = text.lines().map { it.trim() }
        if (lines.any { it.startsWith("#EXT-X-MEDIA:") &&
                it.contains("TYPE=AUDIO") && it.contains("URI=") }) {
            return@withContext emptyList<Stream>() to
                "This HLS source uses separate audio/video. Downloading it needs a muxing downloader."
        }
        val variants = mutableListOf<Stream>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') } ?: continue
            val h = Regex("RESOLUTION=[0-9]+x([0-9]+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            val label = resolution.replace(stream.quality.orEmpty(), "").trim() +
                (h?.let { " ${it}p" } ?: "")
            variants.add(stream.copy(url = URL(URL(stream.url), next).toString(), quality = (label.trim() + " [HLS]")))
        }
        variants.distinctBy { it.url } to if (variants.isEmpty()) "No downloadable HLS variants reported." else null
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (t: Exception) { emptyList<Stream>() to "Could not inspect HLS download: ${t.message}" } finally { con.disconnect() }
}

/** Only explicit version markers; a host name such as MegaDub is NOT an audio tag. */
fun sourceAudioVersion(stream: Stream?): Boolean? {
    val label = stream?.quality.orEmpty()
    val dubbed = Regex("(?i)\\b(dub|dubbed)\\b").containsMatchIn(label)
    val subbed = Regex("(?i)\\b(sub|subbed)\\b").containsMatchIn(label)
    return when { dubbed && !subbed -> true; subbed && !dubbed -> false; else -> null }
}

fun untaggedAudioLabel(version: Boolean?, count: Int, index: Int): String =
    if (count <= 1 || index == 0) "Original" else if (index == 1) "Other" else "Other $index"

/** Anilight's English dub mapping is provider-specific, never inferred for other providers. */
fun isAnilightProvider(name: String): Boolean = providerDisplayName(name).equals("Anilight", true)
fun providerAudioCode(provider: String, stream: Stream, episodeCode: String? = null): String? {
    streamChoice(stream).language?.let { return it }
    if (!isAnilightProvider(provider)) return null
    val label = stream.quality.orEmpty()
    return when {
        sourceAudioVersion(stream) == false -> "original"
        sourceAudioVersion(stream) == true -> "en"
        Regex("(?i)\\bmegadub\\b").containsMatchIn(label) -> "en"
        else -> episodeCode
    }
}

fun preferredAudioKey(value: String?): String? =
    if (value.equals("original", true)) "original" else audioLanguageCode(value)

/** External subtitles must survive short-lived source reuse too, not just the video URLs. */
object SubtitleChoiceCache {
    private data class Entry(val at: Long, val values: List<Pair<String,String>>)
    private val entries=ConcurrentHashMap<String,Entry>()
    fun put(source:String,episode:String,subtitles:List<Pair<String,String>>) {
        entries["$source|$episode"]=Entry(System.currentTimeMillis(),subtitles.toList())
        if(entries.size>16) entries.entries.minByOrNull { it.value.at }?.let { entries.remove(it.key) }
    }
    fun get(source:String,episode:String):List<Pair<String,String>> {
        val entry=entries["$source|$episode"] ?: return emptyList()
        return entry.values.takeIf { System.currentTimeMillis()-entry.at in 0..90_000L }.orEmpty()
    }
}
