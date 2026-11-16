package dev.lynx.streamhub
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.lynx.streamhub.api.Episode
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun DownloadsScreen(onPlay: (PlayRequest) -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    Column(Modifier.fillMaxSize()) {
        Text("Downloads", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp))
        if (Store.downloads.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No downloads yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Store.downloads.toList()) { d ->
                val st = remember(tick, d.downloadId) { Downloader.status(context, d.downloadId) }
                val f = File(d.path)
                val exists = f.exists() && f.length() > 0
                val done = st.first == 8 || (st.first == -1 && exists)
                val failed = st.first == 16
                val label = when {
                    done -> "Downloaded \u2022 ${f.length() / 1048576} MB"
                    failed -> "Failed"
                    st.first == 4 || st.first == 2 && st.second == 0 -> "Waiting\u2026"
                    st.first == -1 -> "Unavailable"
                    else -> "Downloading ${st.second}%"
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = done && exists) {
                        onPlay(PlayRequest(null, d.item, Episode(d.episodeId, d.episodeNumber, d.episodeTitle), 0L, d.path))
                    }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = d.item.poster, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(110.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text("E${d.episodeNumber.toInt()} \u2022 $label", style = MaterialTheme.typography.bodySmall,
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!done && !failed) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { st.second / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    IconButton(onClick = { Downloader.remove(context, d) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
            }
        }
    }
}
