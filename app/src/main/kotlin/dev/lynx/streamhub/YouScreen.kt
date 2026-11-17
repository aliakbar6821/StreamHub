package dev.lynx.streamhub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ExtensionsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var repoInput by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<Map<String, List<RepoExt>>>(emptyMap()) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(Store.repos.toList(), refresh, AppPrefs.extraExtensions) {
        if (!AppPrefs.extraExtensions) {
            available = emptyMap()
            errors = emptyMap()
            return@LaunchedEffect
        }
        val ok = mutableMapOf<String, List<RepoExt>>()
        val bad = mutableMapOf<String, String>()
        for (r in Store.repos.toList()) {
            try {
                ok[r] = Repos.fetch(r)
            } catch (t: Throwable) {
                bad[r] = t.message ?: "Failed to load repository"
            }
        }
        available = ok
        errors = bad
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Sources & extensions", style = MaterialTheme.typography.headlineMedium)
        Text("Default sources", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Text("MovieBox · movies and series\nAnilight · anime",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("On first use, StreamHub downloads executable plugins from the configured Phisher repository. " +
            "Internet is required. Plugins are third-party code; availability and playback are not guaranteed.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Enable default sources", modifier = Modifier.weight(1f))
            Switch(AppPrefs.defaultsEnabled, { enabled ->
                AppPrefs.defaultsEnabled = enabled; AppPrefs.save()
                scope.launch { Session.reload(context) }
            })
        }
        AppPrefs.setupStatus.takeIf { it.isNotBlank() }?.let { Text(it) }
        TextButton(onClick = { scope.launch { Session.reload(context) } }) {
            Text(if (Session.loading) "Working…" else "Set up / reload default sources")
        }
        Text("Additional CloudStream extensions", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Enable repositories and extra extensions", modifier = Modifier.weight(1f))
            Switch(AppPrefs.extraExtensions, { enabled ->
                AppPrefs.extraExtensions = enabled; AppPrefs.save()
                scope.launch { Session.reload(context) }
            })
        }
        Spacer(Modifier.height(16.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .clickable { scope.launch { Session.reload(context) } }
                .padding(vertical = 12.dp),
        ) {
            Text("Reload extensions", style = MaterialTheme.typography.titleMedium)
            Text(
                if (Session.loading) "Reloading..." else "${Session.extensions.size} installed",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Hide adult-titled content", style = MaterialTheme.typography.titleMedium)
                Text("Filters Home and Search by title", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = Safety.enabled,
                onCheckedChange = {
                    Safety.enabled = it
                    Store.saveSafe()
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("Installed extensions", style = MaterialTheme.typography.titleSmall)
        if (Session.extensions.isEmpty()) {
            Text("None", modifier = Modifier.padding(vertical = 8.dp))
        }
        Session.extensions.forEach { e ->
            Text("${e.name} (v${e.version})", modifier = Modifier.padding(vertical = 6.dp))
        }

        if (AppPrefs.extraExtensions) {
        Spacer(Modifier.height(24.dp))
        Text("Repositories", style = MaterialTheme.typography.titleMedium)
        Text(
            "Paste a repository URL (a JSON index) to browse and install extensions.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = repoInput,
            onValueChange = { repoInput = it },
            label = { Text("Repository URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val u = repoInput.trim()
                if (u.startsWith("http")) {
                    Store.addRepo(u)
                    repoInput = ""
                }
            }) { Text("Add repository") }
            TextButton(onClick = { refresh++ }) { Text("Refresh") }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        }

        Store.repos.toList().forEach { r ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { Store.removeRepo(r) }) { Text("Remove") }
            }
            errors[r]?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            available[r]?.forEach { e ->
                val installed = remember(refresh, busy) { if (e.pkg.startsWith("cs3:")) CsHost.installedVersion(context, e.pkg) else Repos.installedVersion(context, e.pkg) }
                val label = when {
                    installed == null -> "Install"
                    installed < e.version -> "Update"
                    else -> "Installed"
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.titleSmall)
                        Text(if (e.pkg.startsWith("cs3:")) "CloudStream plugin \u00b7 v${e.version}" else "v${e.version}", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        enabled = busy == null && label != "Installed" && label != "Unsupported",
                        onClick = {
                            scope.launch {
                                busy = e.pkg
                                status = null
                                try {
                                    if (e.pkg.startsWith("cs3:")) {
                                        CsHost.install(context, e) { phase ->
                                            scope.launch { status = phase }
                                        }
                                        Session.refreshRegistry(context)
                                    } else {
                                        val f = Repos.download(context, e)
                                        Repos.launchInstall(context, f)
                                        status = "Confirm the install prompt, then tap Refresh and Reload extensions."
                                    }
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (t: Exception) {
                                    status = "Install failed: ${t.message}"
                                } finally {
                                    busy = null
                                }
                            }
                        },
                    ) { Text(if (busy == e.pkg) "..." else label) }
                }
            }
        }
        }
        Spacer(Modifier.height(32.dp))
    }
}
