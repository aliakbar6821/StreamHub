package dev.lynx.streamhub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    onOpen: (MediaItem) -> Unit = {},
) {
    val sv = MaterialTheme.colorScheme.surfaceVariant
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
        val isMovie = media.type == MediaType.MOVIE
    // SH_PATCH_03_RECOMMENDATIONS
    var related by remember(media.id, extId) {
        mutableStateOf<List<MediaItem>>(emptyList())
    }
    var relatedLoading by remember(media.id, extId) { mutableStateOf(false) }
    var relatedError by remember(media.id, extId) { mutableStateOf<String?>(null) }
    var relatedGenres by remember(media.id, extId) {
        mutableStateOf<Set<String>>(emptySet())
    }

    LaunchedEffect(media.id, extId) {
        related = emptyList()
        relatedError = null
        if (ext == null) {
            relatedError = "Select a source to discover more titles."
            return@LaunchedEffect
        }
        relatedLoading = true
        try {
            val result = withContext(Dispatchers.IO) {
                withTimeoutOrNull(20_000L) {
                    val genres = media.genres.map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }.toSet()
                    val cached = Session.sections.flatMap { it.items }
                        .filter { Session.owner[it.id]?.id == ext.id }
                    val feed = if (cached.isNotEmpty()) cached else ext.getHome().flatMap { it.items }
                    feed.forEach { Session.owner[it.id] = ext }
                    val candidates = feed
                        .distinctBy { it.id }
                        .filter { it.id != media.id && it.type == media.type }
                    val ranked = candidates.sortedByDescending { candidate ->
                        candidate.genres.count { it.trim().lowercase() in genres }
                    }.take(20)
                    genres to ranked
                }
            }
            if (result == null) {
                relatedError = "This source took too long to load suggestions."
            } else {
                relatedGenres = result.first
                related = result.second
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            relatedError = "Suggestions are unavailable from this source."
        } finally {
            relatedLoading = false
        }
    }
    val listState = rememberLazyListState()
    var tab by remember(media.id) { mutableStateOf(if (isMovie) 1 else 0) }
    var newest by remember(media.id) { mutableStateOf(false) }
    var range by remember(media.id) { mutableStateOf(0) }
    val visibleEpisodes = episodeWindow(episodes, newest, range)
    val current = visibleEpisodes.indexOfFirst { it.id == ep.id }

    LaunchedEffect(ep.id, episodes.size) {
        val index = chronologicalEpisodes(episodes).indexOfFirst { it.id == ep.id }
        range = if (index < 0) 0 else index / 100
    }

    LaunchedEffect(ep.id, tab, range, newest, visibleEpisodes.size) {
        if (!isMovie && tab == 0 && current > 2) {
            // Three header items, tabs, episode heading and sorting controls.
            listState.scrollToItem(6 + current)
        }
    }

    var sheetEp by remember { mutableStateOf<Episode?>(null) }
    sheetEp?.let { DownloadSheet(media, it, ext) { sheetEp = null } }
    val title = cleanMediaText(ep.title?.takeIf { it.isNotBlank() } ?: media.title)
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
                    color = MaterialTheme.colorScheme.onSurface,
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
                        color = MaterialTheme.colorScheme.onSurface,
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
                PillChip("Audio: ${audioLabel.substringBefore(" · ").take(28)}", false) { onPage("audio") }
                PillChip(serverLabel.take(30), false) { onPage("source") }
                PillChip(if (subsOn) "Subtitles \u2713" else "Subtitles", false) { onPage("subs") }
                PillChip(speedLabel, false) { onPage("speed") }
                PillChip("Settings", false) { onPage("main") }
            }
        }
        if (!isMovie && episodes.size > 1) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillChip("Episodes", tab == 0) { tab = 0 }
                    PillChip("More like this", tab == 1) { tab = 1 }
                }
            }
        }
        if (!isMovie && tab == 0 && episodes.size > 1) {
            item {
                Text(
                    "Episodes (${episodes.size})",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                EpisodeControls(episodes.size, newest, range,
                    { newest = it }, { range = it })
            }
            items(visibleEpisodes, key = { it.id }) { e ->
                WatchEpisodeRow(media, e, e.id == ep.id, onDownload = { sheetEp = e }) { onEpisode(e) }
            }
        }
        if (isMovie || tab == 1 || episodes.size <= 1) {
        item {
            Text(
                "More from this source",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        if (relatedLoading) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        } else if (related.isEmpty()) {
            item {
                Text(
                    relatedError ?: "This source returned no other titles of this type.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        } else {
            items(related, key = { "related:" + it.id }) { candidate ->
                RelatedWatchCard(
                    candidate,
                    candidate.genres.any {
                        it.trim().lowercase() in relatedGenres
                    },
                ) { onOpen(candidate) }
            }
        }
        }
    }
}

@Composable
internal fun WatchEpisodeRow(item: MediaItem, e: Episode, current: Boolean, onDownload: () -> Unit, onClick: () -> Unit) {
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
                cleanMediaText(e.title?.takeIf { it.isNotBlank() } ?: "Episode ${e.number.toInt()}"),
                color = MaterialTheme.colorScheme.onSurface,
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
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
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


/**
 * Uses extension artwork as supplied. A poster is not misrepresented
 * as an episode thumbnail, and no views or dates are fabricated.
 */
@Composable
private fun RelatedWatchCard(
    item: MediaItem,
    sharesGenres: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.poster,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(84.dp).aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = colors.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                item.year?.toString(),
                when (item.type) {
                    MediaType.MOVIE -> "Movie"
                    MediaType.ANIME -> "Anime"
                    MediaType.SERIES -> "Series"
                },
            ).joinToString(" • ")
            Text(
                meta,
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
            if (item.genres.isNotEmpty()) {
                Text(
                    item.genres.take(3).joinToString(" • "),
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            if (sharesGenres) {
                Text(
                    "Similar genres",
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
        ItemMenu(item)
    }
}
