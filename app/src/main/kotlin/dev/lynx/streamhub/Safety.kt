package dev.lynx.streamhub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.lynx.streamhub.api.MediaItem

object Safety {
    var enabled by mutableStateOf(true)
    private val blocked = Regex(
        "\\b(sex|sexy|porn|porno|xxx|erotic|nude|nudist|hentai|nsfw|fetish|playboy)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun allowed(item: MediaItem): Boolean = !enabled || !blocked.containsMatchIn(item.title)
}
