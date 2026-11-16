package dev.lynx.streamhub

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.lynx.streamhub.api.Episode
import dev.lynx.streamhub.api.Extension
import dev.lynx.streamhub.api.MediaDetails
import dev.lynx.streamhub.api.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DetailsScreen(
    media: MediaItem,
    onBack: () -> Unit,
    onPlay: (Extension?, Episode, Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<MediaDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val ext = Session.owner[media.id] ?: Store.ownerOf(media.id)
    val last = Store.historyFor(media.id)

    LaunchedEffect(media.id) {
        if (ext == null) {
            error = "Extension not found. Go back and reload."
            return@LaunchedEffect
        }
        try {
            details = withContext(Dispatchers.IO) { ext.getDetails(media.id) }
        } catch (t: Throwable) {
            error = t.message ?: "Failed to load"
        }
    }

    fun download(ep: Episode) {
        val e = ext ?: return
        scope.launch {
            try {
                val s = withContext(Dispatchers.IO) { e.getStreams(ep.id).firstOrNull() }
                if (s == null) {
                    Toast.makeText(context, "No stream found", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val started = Downloader.enqueue(context, media, ep, s)
                Toast.makeText(
                    context,
                    if (started) "Download started" else "Already downloaded",
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (t: Throwable) {
                Toast.makeText(context, "Download failed: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val info = details?.item
    val eps = details?.episodes ?: emptyList()
    val first = eps.firstOrNull()
    val desc = info?.description ?: media.description
    val inList = Store.inList(media.id)
    val meta = listOfNotNull(
        media.type.name,
        (info?.year ?: media.year)?.toString(),
        details?.let { "${it.episodes.size} episodes" },
    ).joinToString("  \u2022  ")

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    AsyncImage(
                        model = media.poster,
                        contentDescription = media.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                                )
                            ),
                    )
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        info?.title ?: media.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (last != null) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val ep = Episode(
                                    id = last.episodeId,
                                    number = last.episodeNumber,
                                    title = last.episodeTitle,
                                )
                                onPlay(ext, ep, last.positionMs)
                            },
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Resume E${last.episodeNumber.toInt()}")
                        }
                    } else {
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = first != null,
                            onClick = { first?.let { onPlay(ext, it, 0L) } },
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Play")
                        }
                    }
                    FilledTonalIconButton(onClick = { Store.toggleList(media, ext?.id ?: "") }) {
                        Icon(
                            if (inList) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "My List",
                            tint = if (inList) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    FilledTonalIconButton(onClick = { shareText(context, media.title) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                }
            }
            if (!desc.isNullOrBlank()) {
                item {
                    Text(
                        desc,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (details == null && error == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            error?.let {
                item {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
            }
            if (eps.isNotEmpty()) {
                item {
                    Text(
                        "Episodes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
                    )
                }
                items(eps) { ep ->
                    val resumeMs = if (last != null && last.episodeId == ep.id) last.positionMs else 0L
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPlay(ext, ep, resumeMs) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(96.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (ep.thumbnail != null) {
                                AsyncImage(
                                    model = ep.thumbnail,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text(
                                    ep.number.toInt().toString(),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Episode ${ep.number.toInt()}" + if (resumeMs > 0L) "  \u2022  Continue" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                ep.title ?: "Episode ${ep.number.toInt()}",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (Store.isWatched(ep.id)) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Watched",
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        IconButton(onClick = { download(ep) }) {
                            Icon(
                                if (Store.hasDownload(ep.id)) Icons.Filled.Check else Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Download",
                            )
                        }
                    }
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
    }
}
