package dev.lynx.streamhub

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun MenuRow(icon: ImageVector, label: String, value: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { Diagnostics.event("settings.action",label); onClick() }.padding(horizontal = 18.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (value != null) Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Filled.KeyboardArrowRight, null)
    }
}

@Composable
fun YouScreen(startAtExtensions: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("profile", Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(prefs.getString("name", "StreamHub User") ?: "StreamHub User") }
    var page by remember(startAtExtensions) { mutableStateOf(if (startAtExtensions) 1 else 0) }
    var edit by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var appearance by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    var filesMenu by remember { mutableStateOf(false) }
    var listOnly by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var restored by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            message = try {
                withContext(Dispatchers.IO) {
                    val text = LocalBackup.export(ctx, listOnly)
                    val output = ctx.contentResolver.openOutputStream(uri) ?: error("Cannot open output file")
                    output.bufferedWriter().use { it.write(text) }
                }
                "Export complete. Downloaded videos and plugin binaries are not included."
            } catch (t: Exception) { "Export failed: ${t.message}" }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val input = ctx.contentResolver.openInputStream(uri) ?: error("Cannot read file")
                    val text = input.bufferedReader().use { reader ->
                        val out = StringBuilder()
                        val buf = CharArray(8192)
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            require(out.length + n <= 5_000_000) { "Backup exceeds 5 MB" }
                            out.append(buf, 0, n)
                        }
                        out.toString()
                    }
                    LocalBackup.restore(ctx, text)
                }
                restored = true
            } catch (t: Exception) { message = "Restore failed: ${t.message}" }
        }
    }
    if (page == 2) {
        BackHandler { page = 0 }
        PlayerSettingsScreen { page = 0 }
        return
    }
    if (page == 1) {
        BackHandler { page = 0 }
        Column(Modifier.fillMaxSize()) {
            IconButton(onClick = { page = 0 }) { Icon(Icons.Filled.ArrowBack, "Back") }
            ExtensionsScreen()
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Text("streamHub", style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 20.dp))
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(18.dp))
            Column {
                Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Local profile", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = { edit = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(48.dp)) {
            Text("Edit Profile")
        }
        Text("App", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 14.dp))
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            MenuRow(Icons.Filled.ImportExport, "Import/Export list") { listOnly = true; filesMenu = true }
            HorizontalDivider(color = MaterialTheme.colorScheme.background, thickness = 2.dp)
            MenuRow(Icons.Filled.Backup, "Backup & Restore") { listOnly = false; filesMenu = true }
            HorizontalDivider(color = MaterialTheme.colorScheme.background, thickness = 2.dp)
            MenuRow(Icons.Filled.Extension, "Sources & extensions") { page = 1 }
            HorizontalDivider(color = MaterialTheme.colorScheme.background, thickness = 2.dp)
            MenuRow(Icons.Filled.Palette, "Appearance", AppPrefs.appearance.replaceFirstChar { it.uppercase() }) { appearance = true }
            HorizontalDivider(color = MaterialTheme.colorScheme.background, thickness = 2.dp)
            MenuRow(Icons.Filled.BugReport, "Diagnostics") { diagnostics = true }
            HorizontalDivider(color = MaterialTheme.colorScheme.background, thickness = 2.dp)
            MenuRow(Icons.Filled.Info, "About") { about = true }
        }
        Text("Preferences", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 18.dp))
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            MenuRow(Icons.Filled.SmartDisplay, "Player settings") { page = 2 }
        }
        Text("Interface size", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Small" to 0.72f, "Medium" to 0.82f, "Large" to 1f).forEach { (label, scale) ->
                PillChip(label, UiScale.value == scale) { UiScale.value = scale; PlayerPrefs.uiScale = scale }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
    if (diagnostics) DiagnosticsSettings { diagnostics = false }
    if (appearance) AlertDialog(onDismissRequest = { appearance = false },
        title = { Text("Appearance") }, text = {
            Column {
                listOf("System" to "system", "Light" to "light", "Dark" to "dark").forEach { (label, key) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        AppPrefs.appearance = key; AppPrefs.save(); appearance = false
                    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(AppPrefs.appearance == key, null)
                        Spacer(Modifier.width(10.dp)); Text(label)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { appearance = false }) { Text("Close") } })
    if (edit) {
        var text by remember { mutableStateOf(name) }
        AlertDialog(onDismissRequest = { edit = false }, title = { Text("Edit profile") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                if (text.isNotBlank()) { name = text.trim(); prefs.edit().putString("name", name).apply() }; edit = false
            }) { Text("Save") } }, dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } })
    }
    if (filesMenu) AlertDialog(onDismissRequest = { filesMenu = false },
        title = { Text(if (listOnly) "Import/Export My List" else "Backup & Restore") },
        text = { Text("Uses StreamHub JSON files. Import replaces the saved list; full restore replaces preferences and library metadata. " +
            "Downloaded videos and plugins are not backed up. Stop downloads first. After restore the app must close and be reopened.") },
        confirmButton = { TextButton(onClick = {
            filesMenu = false; save.launch(if (listOnly) "StreamHub-list.json" else "StreamHub-backup.json")
        }) { Text("Export") } },
        dismissButton = { TextButton(onClick = {
            filesMenu = false; restore.launch(arrayOf("application/json", "text/plain"))
        }) { Text("Import / Restore") } })
    if (restored) AlertDialog(onDismissRequest = {}, title = { Text("Restore complete") },
        text = { Text("Close StreamHub now, then reopen it to load restored data. This stops active in-app downloads.") },
        confirmButton = { TextButton(onClick = {
            (ctx as? android.app.Activity)?.finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        }) { Text("Close app") } })
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }) }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("streamHub") },
        text = { Text("StreamHub is inspired from anistation") },
        confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } })
}
