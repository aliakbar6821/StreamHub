package dev.lynx.streamhub
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LANGS = listOf(
    "Default" to "", "Japanese" to "ja", "English" to "en", "Spanish (Spain)" to "es",
    "Spanish (Latin America)" to "es-419", "French" to "fr", "Hindi" to "hi",
    "Kannada" to "kn", "Telugu" to "te", "Tamil" to "ta",
)

@Composable
private fun LangPicker(title: String, desc: String, value: String, extra: List<Pair<String, String>> = emptyList(), onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val all = LANGS + extra
    val shown = all.firstOrNull { it.second == value }?.first ?: value
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Box {
            Text(shown, modifier = Modifier.clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp))
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                all.forEach { (n, c) -> DropdownMenuItem(text = { Text(n) }, onClick = { onPick(c); open = false }) }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PlayerSettingsScreen(onBack: () -> Unit) {
    PlayerPrefs.init(LocalContext.current)
    var audio by remember { mutableStateOf(PlayerPrefs.audioLang) }
    var sub by remember { mutableStateOf(PlayerPrefs.subLang) }
    var auto by remember { mutableStateOf(PlayerPrefs.autoNext) }
    var scale by remember { mutableStateOf(PlayerPrefs.subScale) }
    var autoSkip by remember { mutableStateOf(PlayerPrefs.autoSkip) }
    var font by remember { mutableStateOf(PlayerPrefs.subFont) }
    var bold by remember { mutableStateOf(PlayerPrefs.subBold) }
    var pos by remember { mutableStateOf(PlayerPrefs.subPos) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Text("Player settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Text("Language", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LangPicker("Default audio language", "Used when a stream has several audio tracks.", audio) { audio = it; PlayerPrefs.audioLang = it }
        LangPicker("Default subtitle language", "Used when a stream has subtitle tracks.", sub,
            listOf("Off" to "off")) { sub = it; PlayerPrefs.subLang = it }
        Spacer(Modifier.height(12.dp))
        Text("Subtitle style", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        SubtitleStyleEditor(
            dark = false,
            font = font,
            bold = bold,
            scale = scale,
            pos = pos,
            onFont = { font = it; PlayerPrefs.subFont = it },
            onBold = { bold = it; PlayerPrefs.subBold = it },
            onScale = { scale = it; PlayerPrefs.subScale = it },
            onPos = { pos = it; PlayerPrefs.subPos = it },
        )
        Spacer(Modifier.height(12.dp))
        Text("Playback", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        SwitchRow("Auto-play next episode", "Shows an Up next countdown at the end.", auto) { auto = it; PlayerPrefs.autoNext = it }
        SwitchRow("Auto skip intro", "Jumps ahead about 85 seconds at the start of an episode.", autoSkip) { autoSkip = it; PlayerPrefs.autoSkip = it }
    }
}
