package dev.lynx.streamhub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lynx.streamhub.api.Episode
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun DownloadsScreen(onPlay: (PlayRequest) -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            tick++
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Downloads",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        )
        if (Store.downloads.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No downloads yet. Tap the arrow next to an episode.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(Store.downloads.toList()) { d ->
                    val st = remember(tick, d.downloadId) { Downloader.status(context, d.downloadId) }
                    val exists = File(d.path).let { it.exists() && it.length() > 0 }
                    val done = st.first == 8 || (st.first == -1 && exists)
                    val label = when {
                        done -> "Downloaded"
                        st.first == 16 -> "Failed"
                        st.first == 4 -> "Paused"
                        st.first == -1 -> "Unavailable"
                        else -> "Downloading ${st.second}%"
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = done && exists) {
                                val ep = Episode(
                                    id = d.episodeId,
                                    number = d.episodeNumber,
                                    title = d.episodeTitle,
                                )
                                onPlay(PlayRequest(null, d.item, ep, 0L, d.path))
                            }
                            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.item.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "E${d.episodeNumber.toInt()} \u2022 $label",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { Downloader.remove(context, d) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
