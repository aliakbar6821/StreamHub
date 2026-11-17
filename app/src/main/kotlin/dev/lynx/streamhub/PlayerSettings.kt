package dev.lynx.streamhub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val LANGS = listOf(
    "Default" to "", "Japanese" to "ja", "English" to "en",
    "Spanish (Spain)" to "es", "Spanish (Latin America)" to "es-419",
    "French" to "fr", "Hindi" to "hi", "Urdu" to "ur", "Arabic" to "ar",
    "German" to "de", "Italian" to "it", "Portuguese" to "pt",
    "Kannada" to "kn", "Telugu" to "te", "Tamil" to "ta", "Malayalam" to "ml",
    "Korean" to "ko", "Chinese" to "zh",
)

@Composable
private fun PreferencePicker(
    icon: ImageVector, title: String, description: String, value: String,
    options: List<Pair<String, String>>, onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 19.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.width(12.dp))
        Box {
            Row(Modifier.widthIn(min = 100.dp, max = 145.dp)
                .clip(RoundedCornerShape(28.dp)).background(colors.surfaceVariant)
                .clickable { expanded = true }.padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(options.firstOrNull { it.second == value }?.first ?: value.ifBlank { "Auto" },
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold)
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 210.dp, max = 310.dp).heightIn(max = 360.dp)) {
                options.distinctBy { it.second }.forEach { (label, key) ->
                    DropdownMenuItem(
                        text = { Text(label, maxLines = 2) },
                        trailingIcon = { if (key == value) Icon(Icons.Filled.Check, null) },
                        onClick = { expanded = false; onPick(key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackSwitch(icon: ImageVector, title: String, description: String,
    checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

@Composable
fun PlayerSettingsScreen(onBack: () -> Unit) {
    PlayerPrefs.init(LocalContext.current)
    var audio by remember { mutableStateOf(PlayerPrefs.audioLang) }
    var sub by remember { mutableStateOf(PlayerPrefs.subLang) }
    var auto by remember { mutableStateOf(PlayerPrefs.autoNext) }
    var skip by remember { mutableStateOf(PlayerPrefs.autoSkip) }
    var font by remember { mutableStateOf(PlayerPrefs.subFont) }
    var bold by remember { mutableStateOf(PlayerPrefs.subBold) }
    var scale by remember { mutableStateOf(PlayerPrefs.subScale) }
    var position by remember { mutableStateOf(PlayerPrefs.subPos) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
            Text("Player settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text("Language & Server", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 15.dp))
        PreferencePicker(Icons.Filled.Mic, "Default audio language",
            "For new titles. Choices made in the player are saved per title. Available tracks depend on the source.",
            audio, LANGS) { audio = it; PlayerPrefs.audioLang = it }
        PreferencePicker(Icons.Filled.ClosedCaption, "Default subtitle language",
            "For new titles. Player choices are saved per title.",
            sub, listOf("Off" to "off") + LANGS) { sub = it; PlayerPrefs.subLang = it }
        PreferencePicker(Icons.Filled.Storage, "Default server",
            "Auto uses an available server. This list contains server labels previously returned by your sources.",
            AppPrefs.defaultServer, listOf("Auto" to "") + AppPrefs.knownServers.map { it to it }) {
            AppPrefs.defaultServer = it; AppPrefs.save()
        }
        Text("Playback", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        PlaybackSwitch(Icons.Filled.SkipNext, "Auto Next", "Play the next available episode after the countdown.", auto) {
            auto = it; PlayerPrefs.autoNext = it
        }
        PlaybackSwitch(Icons.Filled.FastForward, "Auto Skip",
            "Approximate only: skips a fixed 85 seconds, not detected intro timestamps.", skip) {
            skip = it; PlayerPrefs.autoSkip = it
        }
        Text("Subtitle style", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 16.dp))
        SubtitleStyleEditor(
            dark = AppPrefs.appearance == "dark" ||
                (AppPrefs.appearance == "system" && androidx.compose.foundation.isSystemInDarkTheme()),
            font = font, bold = bold, scale = scale, pos = position,
            onFont = { font = it; PlayerPrefs.subFont = it },
            onBold = { bold = it; PlayerPrefs.subBold = it },
            onScale = { scale = it; PlayerPrefs.subScale = it },
            onPos = { position = it; PlayerPrefs.subPos = it },
        )
        Spacer(Modifier.height(32.dp))
    }
}
