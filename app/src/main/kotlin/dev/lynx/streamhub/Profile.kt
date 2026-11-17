package dev.lynx.streamhub
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
fun YouScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("profile", Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(prefs.getString("name", "StreamHub User") ?: "StreamHub User") }
    var page by remember { mutableStateOf(0) }
    var edit by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }

    if (page == 2) {
        BackHandler { page = 0 }
        PlayerSettingsScreen(onBack = { page = 0 })
        return
    }

    if (page == 1) {
        BackHandler { page = 0 }
        Column(Modifier.fillMaxSize()) {
            IconButton(onClick = { page = 0 }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            ExtensionsScreen()
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Local profile", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { edit = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Edit Profile") }
        Spacer(Modifier.height(24.dp))
        Text("App", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            MenuRow(Icons.Filled.Settings, "Extensions & repositories") { page = 1 }
            HorizontalDivider(color = MaterialTheme.colorScheme.background, thickness = 2.dp)
            MenuRow(Icons.Filled.PlayArrow, "Player settings") { page = 2 }
            HorizontalDivider(color = MaterialTheme.colorScheme.background, thickness = 2.dp)
            MenuRow(Icons.Filled.Info, "About") { about = true }
        }
        Spacer(Modifier.height(20.dp))
        Text("Interface size", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillChip("Small", UiScale.value == 0.72f) { UiScale.value = 0.72f; PlayerPrefs.uiScale = 0.72f }
            PillChip("Medium", UiScale.value == 0.82f) { UiScale.value = 0.82f; PlayerPrefs.uiScale = 0.82f }
            PillChip("Large", UiScale.value == 1f) { UiScale.value = 1f; PlayerPrefs.uiScale = 1f }
        }
    }

    if (edit) {
        var t by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text("Edit profile") },
            text = { OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (t.isNotBlank()) { name = t.trim(); prefs.edit().putString("name", name).apply() }
                    edit = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } },
        )
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("StreamHub") },
            text = { Text("Open-source streaming app. Content comes only from the extensions you install.") },
            confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
        )
    }
}
