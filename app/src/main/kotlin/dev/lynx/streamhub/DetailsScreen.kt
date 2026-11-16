package dev.lynx.streamhub

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("< Back") }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Box(
                    Modifier
                        .padding(horizontal = 16.dp)
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
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        details?.item?.title ?: media.title,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    val year = details?.item?.year ?: media.year
                    if (year != null) Text(year.toString(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                val first = details?.episodes?.firstOrNull()
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (last != null) {
                        Button(onClick = {
                            val ep = Episode(
                                id = last.episodeId,
                                number = last.episodeNumber,
                                title = last.episodeTitle,
                            )
                            onPlay(ext, ep, last.positionMs)
                        }) { Text("Resume E${last.episodeNumber.toInt()}") }
                    } else if (first != null) {
                        Button(onClick = { onPlay(ext, first, 0L) }) { Text("Play") }
                    }
                    OutlinedButton(onClick = { Store.toggleList(media, ext?.id ?: "") }) {
                        Text(if (Store.inList(media.id)) "In My List \u2713" else "+ My List")
                    }
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
            details?.let { d ->
                item {
                    Text(
                        "Episodes (${d.episodes.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(d.episodes) { ep ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(ext, ep, 0L) }
                            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${ep.number.toInt()}. ", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            ep.title ?: "Episode ${ep.number.toInt()}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (Store.isWatched(ep.id)) Text("\u2713 ")
                        TextButton(onClick = { download(ep) }) {
                            Text(if (Store.hasDownload(ep.id)) "Saved" else "\u2193")
                        }
                    }
                }
            }
        }
    }
}
