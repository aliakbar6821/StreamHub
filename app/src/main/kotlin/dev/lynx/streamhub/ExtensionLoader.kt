package dev.lynx.streamhub

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dalvik.system.PathClassLoader
import dev.lynx.streamhub.api.Extension

object ExtensionLoader {
    const val META_CLASS = "streamhub.extension.class"

    fun loadAll(context: Context): List<Extension> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        }
        return packages.mapNotNull { load(context, it) }
    }

    private fun load(context: Context, pkg: PackageInfo): Extension? {
        val app = pkg.applicationInfo ?: return null
        val cls = app.metaData?.getString(META_CLASS) ?: return null
        val fullName = if (cls.startsWith(".")) pkg.packageName + cls else cls
        return try {
            val loader = PathClassLoader(app.sourceDir, context.classLoader)
            Class.forName(fullName, false, loader)
                .getDeclaredConstructor()
                .newInstance() as Extension
        } catch (t: Throwable) {
            null
        }
    }
}
