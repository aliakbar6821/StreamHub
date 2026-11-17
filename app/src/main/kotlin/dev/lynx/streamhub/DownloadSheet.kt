package dev.lynx.streamhub
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lynx.streamhub.api.Episode
import dev.lynx.streamhub.api.Extension
import dev.lynx.streamhub.api.MediaItem
import dev.lynx.streamhub.api.MediaType
import dev.lynx.streamhub.api.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun epLabel(item: MediaItem, n: Float): String =
    if (item.type == MediaType.MOVIE) "Movie" else "Episode ${n.toInt()}"

fun isHls(s: Stream): Boolean = s.url.contains(".m3u8", ignoreCase = true)

@Composable
private fun ServerRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(media: MediaItem, ep: Episode, ext: Extension?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var streams by remember { mutableStateOf<List<Stream>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(-1) }
    var sd by remember { mutableStateOf(false) }
    var secs by remember { mutableStateOf(0) }
    val hasSd = remember {
        val d = context.getExternalFilesDirs("downloads")
        d.size > 1 && d[1] != null
    }

    LaunchedEffect(ep.id) {
        launch { while (streams == null) { delay(1000); secs++ } }
        try {
            val s = withContext(Dispatchers.IO) { ext?.getStreams(ep.id) ?: emptyList() }
            pick = s.indexOfFirst { !isHls(it) }
            streams = s
        } catch (t: Throwable) {
            err = t.message ?: "Failed to load sources"
            streams = emptyList()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        val list = streams
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                "Download " + epLabel(media, ep.number),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text("Server", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            when {
                list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Finding sources\u2026 ${secs}s")
                }
                list.isEmpty() -> Text(err ?: "No sources found for this one.", color = MaterialTheme.colorScheme.error)
                else -> Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    list.forEachIndexed { i, s ->
                        val hls = isHls(s)
                        val name = s.quality?.takeIf { it.isNotBlank() } ?: "Server ${i + 1}"
                        ServerRow(name + if (hls) " \u00b7 HLS (not downloadable yet)" else "", pick == i, !hls) { pick = i }
                    }
                }
            }
            if (hasSd) {
                Spacer(Modifier.height(16.dp))
                Text("Storage", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillChip("Internal", !sd) { sd = false }
                    PillChip("SD card", sd) { sd = true }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(52.dp)) { Text("Cancel") }
                Button(
                    modifier = Modifier.weight(1f).height(52.dp),
                    enabled = pick >= 0,
                    onClick = {
                        val s = list?.getOrNull(pick)
                        if (s != null) {
                            val ok = Downloader.enqueue(context, media, ep, s, s.quality ?: "", sd)
                            Toast.makeText(context, if (ok) "Download started" else "Already downloaded", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                ) { Text("Download") }
            }
        }
    }
}
