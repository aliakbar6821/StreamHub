package dev.lynx.streamhub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.lynx.streamhub.api.Episode
import dev.lynx.streamhub.api.Extension
import dev.lynx.streamhub.api.MediaDetails
import dev.lynx.streamhub.api.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DetailsScreen(
    item: MediaItem,
    onBack: () -> Unit,
    onPlay: (Extension, Episode) -> Unit,
) {
    var details by remember { mutableStateOf<MediaDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val ext = Session.owner[item.id]

    LaunchedEffect(item.id) {
        if (ext == null) {
            error = "Extension not found. Go back and reload."
            return@LaunchedEffect
        }
        try {
            details = withContext(Dispatchers.IO) { ext.getDetails(item.id) }
        } catch (t: Throwable) {
            error = t.message ?: "Failed to load"
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
                        model = item.poster,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        details?.item?.title ?: item.title,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    val year = details?.item?.year ?: item.year
                    if (year != null) Text(year.toString(), style = MaterialTheme.typography.bodyMedium)
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
                            .clickable { if (ext != null) onPlay(ext, ep) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${ep.number.toInt()}.  ", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            ep.title ?: "Episode ${ep.number.toInt()}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
