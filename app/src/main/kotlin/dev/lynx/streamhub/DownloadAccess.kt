package dev.lynx.streamhub

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object DownloadAccess {
    fun canSavePublic(context: Context): Boolean = Build.VERSION.SDK_INT >= 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    fun requestLogExport(context: Context, ready: () -> Unit) {
        var candidate: Context? = context
        while(candidate is ContextWrapper) {
            if(candidate is MainActivity){candidate.requestLogExportAccess(ready);return}
            candidate=candidate.baseContext
        }
        if(canSavePublic(context))ready()
    }
    fun request(context: Context, ready: () -> Unit) {
        var candidate: Context? = context
        while (candidate is ContextWrapper) {
            if (candidate is MainActivity) { candidate.requestDownloadAccess(ready); return }
            candidate = candidate.baseContext
        }
        if (canSavePublic(context)) ready()
    }
}
