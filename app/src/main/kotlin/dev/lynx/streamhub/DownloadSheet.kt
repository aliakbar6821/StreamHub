package dev.lynx.streamhub

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lynx.streamhub.api.Episode
import dev.lynx.streamhub.api.Extension
import dev.lynx.streamhub.api.MediaItem
import dev.lynx.streamhub.api.MediaType
import dev.lynx.streamhub.api.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun epLabel(item: MediaItem, n: Float): String =
    if (item.type == MediaType.MOVIE) "Movie" else "Episode ${n.toInt()}"

fun isHls(s: Stream): Boolean = s.url.contains("m3u8", ignoreCase = true) ||
    s.quality?.contains("[HLS]", ignoreCase = true) == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(
    media: MediaItem,
    ep: Episode,
    ext: Extension?,
    initialStreams: List<Stream> = emptyList(),
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var options by remember(ep.id) { mutableStateOf<List<Stream>>(emptyList()) }
    var searching by remember(ep.id) { mutableStateOf(true) }
    var preparing by remember(ep.id) { mutableIntStateOf(0) }
    var warnings by remember(ep.id) { mutableStateOf<List<String>>(emptyList()) }
    var language by remember(ep.id) { mutableStateOf(audioLanguageCode(AppPrefs.audio(media.id)).orEmpty()) }
    var selectedHeight by remember(ep.id) { mutableStateOf<Int?>(null) }
    var manualLanguage by remember(ep.id) { mutableStateOf(false) }
    var sd by remember { mutableStateOf(false) }
    val hasSd = remember { context.getExternalFilesDirs("downloads").let { it.size > 1 && it[1] != null } }
    val busy = searching || preparing > 0

    LaunchedEffect(ep.id, ext?.id, initialStreams.map { it.url }) {
        searching = true
        val seen = HashSet<String>()
        val discovered = mutableListOf<Stream>()
        fun accept(stream: Stream) {
            if (!seen.add(stream.url)) return
            discovered.add(stream)
            ext?.id?.let { StreamChoiceCache.put(it, ep.id, discovered) }
            if (!isHls(stream)) {
                options = (options + stream).distinctBy { it.url }
            } else {
                preparing++
                launch {
                    try {
                        val (variants, warning) = downloadVariants(stream)
                        options = (options + variants).distinctBy { it.url }
                        if (warning != null) warnings = (warnings + warning).distinct()
                    } finally { preparing-- }
                }
            }
        }
        try {
            val cached = initialStreams.ifEmpty {
                ext?.id?.let { StreamChoiceCache.get(it, ep.id) }.orEmpty()
            }
            if (cached.isNotEmpty()) {
                cached.forEach { accept(it) }
            } else if (ext is CsExtension) {
                val channel = Channel<Stream>(Channel.UNLIMITED)
                launch {
                    try { ext.streamsLive(ep.id) { channel.trySend(it) } }
                    finally { channel.close() }
                }
                for (stream in channel) accept(stream)
                if (seen.isEmpty()) ext.lastError?.let { warnings = warnings + it }
            } else {
                val found = withContext(Dispatchers.IO) { ext?.getStreams(ep.id).orEmpty() }
                found.forEach { accept(it) }
            }
        } catch (cancel: CancellationException) { throw cancel } catch (t: Exception) { warnings = warnings + (t.message ?: "Source lookup failed") } finally { searching = false }
    }

    val languages = options.map { streamChoice(it) }.distinctBy { it.audioKey }
    LaunchedEffect(options.map { it.url }) {
        val wanted = audioLanguageCode(AppPrefs.audio(media.id))
        if (!manualLanguage && wanted != null && languages.any { it.audioKey == wanted }) {
            language = wanted
        } else if (languages.none { it.audioKey == language }) {
            language = languages.firstOrNull()?.audioKey.orEmpty()
        }
    }
    val candidates = options.filter { streamChoice(it).audioKey == language }
    val heights = candidates.mapNotNull { streamChoice(it).height }.distinct().sortedDescending()
    val pickedIndex = bestStreamIndex(options, language, selectedHeight)
    val picked = pickedIndex?.let { options[it] }
    val pickedHeight = picked?.let { streamChoice(it).height }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Download " + epLabel(media, ep.number),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("Source", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(providerDisplayName(ext?.name), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp))
            if (busy) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(if (preparing > 0) "Reading available download qualities…" else "Loading audio and quality options…")
                }
            }
            if (options.isNotEmpty()) {
                Text("Audio", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    languages.forEach { choice ->
                        PillChip(choice.audioLabel, language == choice.audioKey) {
                            manualLanguage = true
                            language = choice.audioKey
                            selectedHeight = null
                        }
                    }
                }
                Text("Quality", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (heights.isEmpty()) {
                        PillChip("Original file", true) {}
                    } else heights.forEach { height ->
                        PillChip("${height}p", pickedHeight == height) { selectedHeight = height }
                    }
                }
                Text("Downloads keep the selected file's embedded tracks. External subtitle files are not bundled. " +
                    "Multi-track audio is not remuxed into a single language.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp))
            } else if (!busy) {
                Text("No supported download option returned for this episode.",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp))
            }
            warnings.forEach { warning ->
                Text(warning, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            if (hasSd) {
                Text("Storage", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillChip("Internal", !sd) { sd = false }
                    PillChip("SD card", sd) { sd = true }
                }
            }
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(50.dp)) { Text("Cancel") }
                Button(enabled = picked != null, modifier = Modifier.weight(1f).height(50.dp), onClick = {
                    picked?.let { stream ->
                        val choice = streamChoice(stream)
                        val label = listOfNotNull(choice.audioLabel, choice.height?.let { "${it}p" }).joinToString(" · ")
                        val ok = Downloader.enqueue(context, media, ep, stream, label, sd)
                        Toast.makeText(context, if (ok) "Download started" else "Already downloaded", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                }) { Text("Download") }
            }
        }
    }
}
