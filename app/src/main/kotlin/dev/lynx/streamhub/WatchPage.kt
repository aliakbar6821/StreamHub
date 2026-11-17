package dev.lynx.streamhub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.lynx.streamhub.api.Extension
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.lynx.streamhub.api.Episode
import dev.lynx.streamhub.api.MediaItem
import dev.lynx.streamhub.api.MediaType

/** The part of the watch page under the video: title, series row, Audio/Server chips, episode list. */
@Composable
fun WatchInfo(
    modifier: Modifier,
    media: MediaItem,
    extId: String,
    ext: Extension?,
    ep: Episode,
    episodes: List<Episode>,
    durMs: Long,
    audioLabel: String,
    serverLabel: String,
    subsOn: Boolean,
    speedLabel: String,
    onPage: (String) -> Unit,
    onEpisode: (Episode) -> Unit,
) {
    val sv = MaterialTheme.colorScheme.surfaceVariant
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val isMovie = media.type == MediaType.MOVIE
    val listState = rememberLazyListState()
    val current = episodes.indexOfFirst { it.id == ep.id }

    LaunchedEffect(episodes.size) {
        if (current > 2) listState.scrollToItem(3 + current)
    }

    var sheetEp by remember { mutableStateOf<Episode?>(null) }
    sheetEp?.let { DownloadSheet(media, it, ext) { sheetEp = null } }
    val title = ep.title?.takeIf { it.isNotBlank() } ?: media.title
    val meta = buildList {
        if (!isMovie) add("Episode ${ep.number.toInt()}")
        if (durMs > 0L) add("${(durMs / 60000L).coerceAtLeast(1L)} min")
        if (Store.isWatched(ep.id)) add("Watched")
    }.joinToString(" \u2022 ")
    val kind = when (media.type) {
        MediaType.MOVIE -> "Movie"
        MediaType.ANIME -> "Anime"
        MediaType.SERIES -> "TV"
    }

    LazyColumn(modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    title,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) {
                    Text(meta, fontSize = 14.sp, color = dim, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = media.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(sv),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        media.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(kind, media.year?.toString()).joinToString(" \u2022 "),
                        fontSize = 13.sp,
                        color = dim,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { Store.toggleList(media, extId) }) {
                    Text(if (Store.inList(media.id)) "In list" else "Add to list", fontSize = 14.sp)
                }
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillChip("Audio: $audioLabel", false) { onPage("audio") }
                PillChip(serverLabel, false) { onPage("source") }
                PillChip(if (subsOn) "Subtitles \u2713" else "Subtitles", false) { onPage("subs") }
                PillChip(speedLabel, false) { onPage("speed") }
                PillChip("Settings", false) { onPage("main") }
            }
        }
        if (episodes.size > 1) {
            item {
                Text(
                    "Episodes (${episodes.size})",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(episodes) { e ->
                WatchEpisodeRow(media, e, e.id == ep.id, onDownload = { sheetEp = e }) { onEpisode(e) }
            }
        }
    }
}

@Composable
private fun WatchEpisodeRow(item: MediaItem, e: Episode, current: Boolean, onDownload: () -> Unit, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val sv = MaterialTheme.colorScheme.surfaceVariant
    val status = when {
        current -> "Now playing"
        Store.isWatched(e.id) -> "Watched"
        Store.hasDownload(e.id) -> "Downloaded"
        else -> ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (current) sv else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(124.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(sv)) {
            AsyncImage(
                model = e.thumbnail ?: item.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                "EP ${e.number.toInt()}",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xAA000000))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.title?.takeIf { it.isNotBlank() } ?: "Episode ${e.number.toInt()}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (status.isNotEmpty()) {
                Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val done = Store.isWatched(e.id)
                DropdownMenuItem(
                    text = { Text(if (done) "Mark as unwatched" else "Mark as watched") },
                    onClick = {
                        menu = false
                        if (done) Store.unmarkWatched(e.id) else Store.markWatched(e.id)
                    },
                )
                if (!Store.hasDownload(e.id)) {
                    DropdownMenuItem(
                        text = { Text("Download") },
                        onClick = {
                            menu = false
                            onDownload()
                        },
                    )
                }
            }
        }
    }
}

/** Fade in/out wrapper that is not a scope extension, so it works inside any Box/Column. */
@Composable
fun FadeVis(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) { content() }
}
