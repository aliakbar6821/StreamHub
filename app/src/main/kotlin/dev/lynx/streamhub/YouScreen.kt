package dev.lynx.streamhub

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun ExtensionPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun ExtensionToggle(
    entry: InstalledExtension,
    installed: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    ExtensionPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (!installed) "Not installed · downloads when enabled"
                    else
                        "v${entry.version} · " +
                            if (AppPrefs.extensionEnabled(entry.key)) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = AppPrefs.extensionEnabled(entry.key),
                onCheckedChange = onToggle,
                enabled = !busy,
            )
        }
    }
}

@Composable
fun ExtensionsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<List<InstalledExtension>>(emptyList()) }
    var addonInput by remember { mutableStateOf("") }
    var repoInput by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<Map<String, List<RepoExt>>>(emptyMap()) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val repositories = Store.repos.toList()

    suspend fun readInstalled() {
        installed = withContext(Dispatchers.IO) { ExtensionLoader.installed(context) }
    }
    fun toggle(key: String, enabled: Boolean) {
        if (busy != null) return
        Diagnostics.event("extension.toggle", "enabled=$enabled")
        AppPrefs.setExtensionEnabled(key, enabled)
        Session.invalidate()
        busy = key
        scope.launch {
            try {
                Session.reload(context)
                readInstalled()
                if (enabled && installed.none { it.key == key }) {
                    val reason = Session.error.orEmpty()
                    AppPrefs.setExtensionEnabled(key, false)
                    Session.invalidate()
                    status = "Could not install this extension. $reason".trim()
                } else
                    status =
                        when {
                            !enabled -> "Extension disabled; files and settings kept"
                            Session.extensions.none { ExtensionLoader.activationId(it) == key } ->
                                "Enabled, but this extension could not be loaded. Try updating it or restarting the app."
                            else -> "Extension enabled"
                        }
                refresh++
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Exception) {
                Diagnostics.failure("extensions.operation.error", t)
                status = t.message ?: "Could not update extensions"
            } finally {
                busy = null
            }
        }
    }
    LaunchedEffect(refresh) { readInstalled() }
    LaunchedEffect(repositories, refresh, AppPrefs.extraExtensions) {
        available = emptyMap()
        errors = emptyMap()
        if (!AppPrefs.extraExtensions) return@LaunchedEffect
        val ok = mutableMapOf<String, List<RepoExt>>()
        val bad = mutableMapOf<String, String>()
        for (repo in repositories) {
            try {
                ok[repo] = Repos.fetch(repo)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Exception) {
                Diagnostics.failure("extensions.repository.error", t)
                bad[repo] = t.message ?: "Could not load repository"
            }
        }
        available = ok
        errors = bad
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Sources & extensions", style = MaterialTheme.typography.headlineMedium) }
        item {
            ExtensionPanel {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system", "light", "dark").forEach { mode ->
                        PillChip(
                            mode.replaceFirstChar { it.uppercase() },
                            AppPrefs.appearance == mode,
                        ) {
                            AppPrefs.appearance = mode
                            AppPrefs.save()
                        }
                    }
                }
            }
        }
        item {
            Text("Choose your sources", style = MaterialTheme.typography.titleLarge)
            Text(
                "No sources are bundled. Install from your own repository, then enable only the extensions you trust. Extensions contain third-party executable code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (installed.isEmpty())
            item {
                Text(
                    "No extensions installed. Add a Stremio manifest or a Cloudstream-compatible repository below.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        items(installed, key = { "installed:${it.key}" }) { entry ->
            ExtensionToggle(entry, true, busy != null) { toggle(entry.key, it) }
            val cs=Session.extensions.filterIsInstance<CsExtension>().firstOrNull { it.activationId==entry.key }
            if(cs!=null && StremioProtocol.http(cs.websiteUrl)?.startsWith("https://")==true)TextButton(enabled=busy==null,onClick={
                context.startActivity(android.content.Intent(context,ProviderVerificationActivity::class.java).putExtra("website",cs.websiteUrl))
            }){Text("Verify website") }
            if (entry.key.startsWith("stremio:"))
                TextButton(
                    enabled = busy == null,
                    onClick = {
                        busy = "stremio-remove"
                        scope.launch {
                            try {
                                StremioHost.remove(context, entry.key)
                                Session.invalidate()
                                Session.reload(context)
                                readInstalled()
                            } catch (c: CancellationException) {
                                throw c
                            } catch (_: Exception) {
                                status = "Could not remove addon or refresh sources. Retry."
                            } finally {
                                busy = null
                            }
                        }
                    },
                ) {
                    Text("Remove addon")
                }
        }
        item {
            ExtensionPanel {
                Text("Stremio addons", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add your own configured HTTPS manifest. Supports catalogs, search, metadata and direct HTTP(S) streams across enabled addons. Enabled addons receive matching searches and video IDs. No default addons, torrents, archives or external-webpage playback.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    addonInput,
                    { addonInput = it },
                    label = { Text("Stremio manifest URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = busy == null && addonInput.isNotBlank(),
                    onClick = {
                        busy = "stremio-install"
                        scope.launch {
                            try {
                                val label = StremioHost.install(context, addonInput)
                                addonInput = ""
                                Session.invalidate()
                                Session.reload(context)
                                readInstalled()
                                status = "Added $label"
                                refresh++
                            } catch (c: CancellationException) {
                                throw c
                            } catch (_: Exception) {
                                status =
                                    "Could not add addon. Use a configured HTTPS /manifest.json URL and check availability."
                            } finally {
                                busy = null
                            }
                        }
                    },
                ) {
                    Text("Add / update addon")
                }
            }
        }
        item {
            if (busy != null || AppPrefs.setupStatus.isNotBlank()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    AppPrefs.setupStatus.ifBlank { "Updating sources…" },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(
                enabled = busy == null,
                onClick = {
                    busy = "reload"
                    Session.invalidate()
                    scope.launch {
                        try {
                            Session.reload(context)
                            readInstalled()
                            refresh++
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (t: Exception) {
                            Diagnostics.failure("extensions.operation.error", t)
                            status = t.message
                        } finally {
                            busy = null
                        }
                    }
                },
            ) {
                Text("Reload enabled extensions")
            }
        }
        item {
            ExtensionPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Hide adult-titled content",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Filters Home and Search by title",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        Safety.enabled,
                        {
                            Safety.enabled = it
                            Store.saveSafe()
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Browse additional repositories", modifier = Modifier.weight(1f))
                    Switch(
                        AppPrefs.extraExtensions,
                        {
                            AppPrefs.extraExtensions = it
                            AppPrefs.save()
                        },
                    )
                }
            }
        }
        if (AppPrefs.extraExtensions) {
            item {
                ExtensionPanel {
                    Text("Repositories", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        repoInput,
                        { repoInput = it },
                        label = { Text("Repository URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val url = repoInput.trim()
                                if (url.startsWith("https://")) {
                                    Store.addRepo(url)
                                    repoInput = ""
                                } else status = "Use an HTTPS repository URL"
                            }
                        ) {
                            Text("Add")
                        }
                        TextButton(onClick = { refresh++ }) { Text("Refresh") }
                    }
                }
            }
            repositories.forEach { repo ->
                item(key = "repo:$repo") {
                    ExtensionPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                repo,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { Store.removeRepo(repo) }) { Text("Remove") }
                        }
                        errors[repo]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                items(available[repo].orEmpty(), key = { "repo:$repo:${it.pkg}" }) { entry ->
                    val current = installed.firstOrNull { it.key == entry.pkg }
                    ExtensionPanel {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "v${entry.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (current != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (AppPrefs.extensionEnabled(entry.pkg)) "Enabled"
                                    else "Disabled",
                                    Modifier.weight(1f),
                                )
                                Switch(
                                    AppPrefs.extensionEnabled(entry.pkg),
                                    { toggle(entry.pkg, it) },
                                    enabled = busy == null,
                                )
                            }
                        }
                        if (current == null || current.version < entry.version) {
                            Button(
                                enabled = busy == null,
                                onClick = {
                                    busy = entry.pkg
                                    status = null
                                    scope.launch {
                                        try {
                                            if (entry.pkg.startsWith("cs3:")) {
                                                CsHost.install(context, entry)
                                                Session.refreshRegistry(context)
                                                status =
                                                    "Installed. Enable it to use it. Updates to a loaded plugin may need an app restart."
                                            } else {
                                                val file = Repos.download(context, entry)
                                                Repos.launchInstall(context, file)
                                                status =
                                                    "Complete Android's installation prompt, then refresh here."
                                            }
                                            readInstalled()
                                            refresh++
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (t: Exception) {
                                            Diagnostics.failure("extensions.operation.error", t)
                                            status = "Install failed: ${t.message}"
                                        } finally {
                                            busy = null
                                        }
                                    }
                                },
                            ) {
                                Text(if (current == null) "Install (disabled)" else "Update")
                            }
                        }
                    }
                }
            }
        }
    }
}
