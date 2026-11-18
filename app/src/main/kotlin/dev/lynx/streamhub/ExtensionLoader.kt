package dev.lynx.streamhub

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dalvik.system.PathClassLoader
import dev.lynx.streamhub.api.*

data class InstalledExtension(val key: String, val name: String, val version: Long)

object ExtensionLoader {
    const val META_CLASS = "streamhub.extension.class"
    private fun packages(context: Context): List<PackageInfo> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(
            PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        }
    }
    fun installed(context: Context): List<InstalledExtension> = CsHost.installed(context) +
        packages(context).filter { it.applicationInfo?.metaData?.getString(META_CLASS) != null }.map { pkg ->
            val app = pkg.applicationInfo!!
            @Suppress("DEPRECATION")
            val version = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()
            InstalledExtension(pkg.packageName, context.packageManager.getApplicationLabel(app).toString(), version)
        }
    fun loadAll(context: Context): List<Extension> = packages(context)
        .filter { AppPrefs.extensionEnabled(it.packageName) }
        .mapNotNull { load(context, it) } + CsHost.extensions(context)

    fun activationId(extension: Extension): String = when (extension) {
        is CsExtension -> extension.activationId
        is GatedExtension -> extension.activationId
        else -> extension.id
    }
    fun isEnabled(extension: Extension): Boolean = AppPrefs.extensionEnabled(activationId(extension))
    private fun load(context: Context, pkg: PackageInfo): Extension? {
        val app = pkg.applicationInfo ?: return null
        val cls = app.metaData?.getString(META_CLASS) ?: return null
        val name = if (cls.startsWith(".")) pkg.packageName + cls else cls
        return try {
            val loader = PathClassLoader(app.sourceDir, context.classLoader)
            val extension = Class.forName(name, false, loader).getDeclaredConstructor().newInstance() as Extension
            GatedExtension(extension, pkg.packageName)
        } catch (_: Exception) { null } catch (_: LinkageError) { null }
    }
}

private class GatedExtension(private val delegate: Extension, val activationId: String) : Extension by delegate {
    private fun checkEnabled() { check(AppPrefs.extensionEnabled(activationId)) { "Enable $name in Sources & extensions" } }
    override suspend fun getHome(): List<HomeSection> { checkEnabled(); return delegate.getHome() }
    override suspend fun getGenres(): List<String> { checkEnabled(); return delegate.getGenres() }
    override suspend fun search(query: String, page: Int): List<MediaItem> { checkEnabled(); return delegate.search(query, page) }
    override suspend fun getDetails(id: String): MediaDetails { checkEnabled(); return delegate.getDetails(id) }
    override suspend fun getStreams(episodeId: String): List<Stream> { checkEnabled(); return delegate.getStreams(episodeId) }
    override suspend fun getSchedule(): List<MediaItem>? { checkEnabled(); return delegate.getSchedule() }
}
