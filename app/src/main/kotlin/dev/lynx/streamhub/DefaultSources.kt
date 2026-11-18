package dev.lynx.streamhub

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opt-in provider bootstrap: downloads from the explicitly configured repo.
 * Plugin binaries are not redistributed inside the StreamHub APK.
 */
object DefaultSources {
    const val REPOSITORY = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json"
    val ids = setOf("MovieBoxProvider", "Anilight")
    suspend fun ensure(context: Context): String? {
        val missing = withContext(Dispatchers.IO) {
            ids.filter { AppPrefs.extensionEnabled("cs3:$it") && CsHost.installedVersion(context, "cs3:$it") == null }
        }
        if (missing.isEmpty()) return null
        AppPrefs.setupStatus = "Setting up selected sources…"
        val failures = mutableListOf<String>()
        try {
            val available = Repos.fetch(REPOSITORY)
            for (id in missing) {
                if (!AppPrefs.extensionEnabled("cs3:$id")) continue
                val entry = available.firstOrNull { it.pkg == "cs3:$id" }
                if (entry == null) {
                    failures.add("$id is absent from the repository")
                    continue
                }
                try {
                    AppPrefs.setupStatus = "Installing ${entry.name}…"
                    require(entry.sha256 != null) { "Repository supplied no valid SHA-256 checksum" }
                    require(entry.apk.startsWith("https://")) { "Default plugin URL must use HTTPS" }
                    CsHost.install(context, entry)
                } catch (cancel: CancellationException) { throw cancel } catch (t: Exception) { failures.add("${entry.name}: ${t.message}") }
            }
        } catch (cancel: CancellationException) { throw cancel } catch (t: Exception) { failures.add("Default source setup: ${t.message}") } finally { AppPrefs.setupStatus = "" }
        return failures.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
