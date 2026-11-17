package dev.lynx.streamhub

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lynx.streamhub.api.Episode

fun cleanMediaText(value: String): String =
    if ('&' in value || '<' in value)
        androidx.core.text.HtmlCompat.fromHtml(value, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString().trim()
    else value

fun chronologicalEpisodes(episodes: List<Episode>): List<Episode> =
    episodes.sortedWith(compareBy<Episode> {
        Regex("^S([0-9]+) · ").find(it.title.orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
    }.thenBy { it.number })

/** Ranges refer to chronological positions, never reversed list indexes. */
fun episodeWindow(episodes: List<Episode>, newest: Boolean, range: Int): List<Episode> {
    val ordered = chronologicalEpisodes(episodes)
    val page = ordered.drop(range.coerceAtLeast(0) * 100).take(100)
    return if (newest) page.reversed() else page
}

@Composable
fun EpisodeControls(
    total: Int,
    newest: Boolean,
    range: Int,
    onNewest: (Boolean) -> Unit,
    onRange: (Int) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PillChip("Oldest", !newest) { onNewest(false) }
        PillChip("Newest", newest) { onNewest(true) }
        if (total > 100) {
            Box {
                PillChip("${range * 100 + 1}–${minOf((range + 1) * 100, total)} ▾", false) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    repeat((total + 99) / 100) { page ->
                        DropdownMenuItem(
                            text = { Text("${page * 100 + 1}–${minOf((page + 1) * 100, total)}") },
                            onClick = { menu = false; onRange(page) },
                        )
                    }
                }
            }
        }
    }
}
