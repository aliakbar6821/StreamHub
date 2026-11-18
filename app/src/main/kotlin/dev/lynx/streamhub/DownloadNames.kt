package dev.lynx.streamhub

import dev.lynx.streamhub.api.Episode
import dev.lynx.streamhub.api.MediaItem
import dev.lynx.streamhub.api.MediaType
import dev.lynx.streamhub.api.Stream
import java.util.Locale

internal fun safeDownloadName(value: String, spaces: Boolean = false): String {
    val cleaned = value.map { c -> when {
        c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' -> c
        c.isWhitespace() && spaces -> ' '
        else -> '_'
    } }.joinToString("").replace(Regex("[ _]+"), if (spaces) " " else "_").trim(' ', '.', '_')
    val out = StringBuilder()
    for (c in cleaned) {
        if ((out.toString() + c).toByteArray(Charsets.UTF_8).size > 110) break
        out.append(c)
    }
    return out.toString().trim(' ', '.', '_').ifBlank { "Video" }
}

internal fun downloadExtension(stream: Stream): String {
    if (HlsDownloadPlans.get(stream) != null) return "mp4"
    if (isHls(stream)) return "ts"
    val extension = stream.url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        .substringAfterLast('.', "mp4").lowercase(Locale.ROOT)
    return extension.takeIf { it in setOf("mp4", "mkv", "webm", "mov", "m4v", "ts") } ?: "mp4"
}

internal fun readableDownloadName(item: MediaItem, episode: Episode, stream: Stream): String {
    val choice = streamChoice(stream)
    val language = when (choice.language) {
        "en" -> "eng"; "ja" -> "jpn"; "hi" -> "hin"; "ur" -> "urd"
        "ta" -> "tam"; "te" -> "tel"; "ml" -> "mal"; "bn" -> "ben"
        null, "original" -> "original"
        else -> choice.language
    }
    val number = episode.number.toString().removeSuffix(".0")
    val season = Regex("^S(\\d+)\\s*·").find(episode.title.orEmpty())?.groupValues?.get(1)?.let { "S$it" }
    val parts = listOfNotNull(safeDownloadName(item.title), season,
        if (item.type == MediaType.MOVIE) null else number, language,
        choice.height?.let { "${it}P" } ?: "source")
    return parts.joinToString("_") + "." + downloadExtension(stream)
}
