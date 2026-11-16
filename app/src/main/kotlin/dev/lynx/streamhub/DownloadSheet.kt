package dev.lynx.streamhub
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(media: MediaItem, ep: Episode, ext: Extension?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf(-1) }
    var pick by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(ep.id) {
        try { count = withContext(Dispatchers.IO) { ext?.getStreams(ep.id)?.size ?: 0 } }
        catch (t: Throwable) { count = 0; status = t.message ?: "Failed to load sources" }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Download Episode ${ep.number.toInt()}", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Text("Server", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            when {
                count < 0 -> Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
                count == 0 -> Text("No sources found", color = MaterialTheme.colorScheme.error)
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0 until count).toList()) { i -> PillChip("Server ${i + 1}", pick == i) { pick = i } }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = count > 0 && !busy,
                    onClick = {
                        busy = true; status = "Starting..."
                        scope.launch {
                            try {
                                val s = withContext(Dispatchers.IO) { ext?.getStreams(ep.id)?.getOrNull(pick) }
                                if (s == null) status = "Source unavailable" else {
                                    val ok = Downloader.enqueue(context, media, ep, s)
                                    status = if (ok) "Queued. Check the Downloads tab." else "Already downloaded"
                                }
                            } catch (t: Throwable) { status = "Failed: ${t.message}" }
                            busy = false
                        }
                    },
                ) { Text("Download") }
            }
        }
    }
}
