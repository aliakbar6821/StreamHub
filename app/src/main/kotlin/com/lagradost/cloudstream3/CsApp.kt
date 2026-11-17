package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.Event

class CloudStreamApp {
    companion object {
        @Volatile
        private var appContext: Context? = null

        fun setContext(context: Context) {
            appContext = context.applicationContext
        }

        fun getContext(): Context? = appContext

        fun setKey(path: String, value: Any?) = DataStore.setKey(path, value)

        fun <T> getKey(path: String): T? = getKey(path, null)

        @Suppress("UNCHECKED_CAST")
        fun <T> getKey(path: String, defVal: T?): T? =
            (DataStore.getKey(path, Any::class.java) as? T) ?: defVal

        fun removeKey(path: String) {
            appContext?.let { DataStore.getSharedPrefs(it).edit().remove(path).apply() }
        }
    }
}

object CommonActivity {
    @Volatile
    var activity: Activity? = null

    fun setActivityInstance(newActivity: Activity?) {
        activity = newActivity
    }

    fun showToast(message: String?, duration: Int? = null) {
        val msg = message ?: return
        val ctx = activity ?: CloudStreamApp.getContext() ?: return
        val len = if (duration == Toast.LENGTH_LONG) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(ctx, msg, len).show()
            } catch (_: Throwable) {
            }
        }
    }

    fun showToast(act: Activity?, message: String?, duration: Int? = null) =
        showToast(message, duration)
}

class AcraApplication {
    companion object {
        val context: Context? get() = CloudStreamApp.getContext()

        fun <T> setKey(path: String, value: T) = CloudStreamApp.setKey(path, value)
        fun <T> setKey(folder: String, path: String, value: T) =
            CloudStreamApp.setKey(DataStore.getFolderName(folder, path), value)

        fun <T> getKey(path: String): T? = CloudStreamApp.getKey(path)
        fun <T> getKey(path: String, defVal: T?): T? = CloudStreamApp.getKey(path, defVal)
        fun removeKey(path: String) = CloudStreamApp.removeKey(path)
        fun removeKeys(folder: String): Int {
            val ctx = CloudStreamApp.getContext() ?: return 0
            return with(DataStore) { ctx.removeKeys(folder) }
        }
    }
}

class MainActivity {
    companion object {
        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()
    }
}
