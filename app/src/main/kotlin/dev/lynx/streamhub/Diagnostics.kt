package dev.lynx.streamhub

import android.content.Context
import android.content.ContentValues
import android.content.ContentUris
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

object Diagnostics {
    private val exportLock=Any()
    private var generation=0L
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback?=null
    private fun connectivity() = app?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    private fun watchNetwork() {
        if(networkCallback!=null)return
        val callback=object: android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network){event("network.available")}
            override fun onLost(network: android.net.Network){event("network.lost")}
            override fun onCapabilitiesChanged(network: android.net.Network,caps: android.net.NetworkCapabilities){
                event("network.capabilities","validated=${caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)} wifi=${caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)} mobile=${caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)}")
            }
        }
        runCatching { connectivity()?.registerDefaultNetworkCallback(callback);networkCallback=callback }
    }
    private fun unwatchNetwork(){networkCallback?.let{ callback -> runCatching{connectivity()?.unregisterNetworkCallback(callback)} };networkCallback=null}
    private var app: Context?=null
    private var journal: DiagnosticJournal?=null
    private var worker: ExecutorService?=null
    @Synchronized fun init(context: Context) {
        if(journal!=null)return
        app=context.applicationContext
        val enabled=context.getSharedPreferences("diagnostics",Context.MODE_PRIVATE).getBoolean("enabled",true)
        journal=DiagnosticJournal(File(context.filesDir,"diagnostics/log.txt"),enabled)
        if(enabled){worker=Executors.newSingleThreadExecutor();watchNetwork()}
    }
    @Synchronized fun enabled(): Boolean=journal?.enabled ?: false
    @Synchronized fun setEnabled(enabled: Boolean) {
        val c=app ?: return
        generation++
        journal?.setEnabled(enabled)
        c.getSharedPreferences("diagnostics",Context.MODE_PRIVATE).edit().putBoolean("enabled",enabled).apply()
        if(!enabled){unwatchNetwork();worker?.shutdownNow();worker=null}
        else if(worker==null){worker=Executors.newSingleThreadExecutor();watchNetwork()}
        if(enabled)event("diagnostics.enabled")
    }
    @Synchronized fun event(name: String, detail: String="") {
        val target=journal ?: return
        if(!target.enabled)return
        val token=generation
        runCatching { worker?.execute { synchronized(this) { if(token==generation && target.enabled)target.append(name,detail) } } }
    }
    fun failure(name: String, error: Throwable) {
        if(!enabled())return
        // Exception messages can contain signed URLs, headers or user input. Never collect them.
        val chain=generateSequence(error) { it.cause }.take(5).map { it.javaClass.simpleName }.joinToString(" <- ")
        val frames=error.stackTrace.take(10).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        event(name,"$chain | $frames")
    }
    @Synchronized fun crash(error: Throwable) {
        val target=journal ?: return
        if(target.enabled)target.append("uncaught",error.javaClass.name+" "+error.stackTrace.take(10).joinToString(" | "))
    }
    fun snapshot(): String {
        val (target,queue)=synchronized(this) { journal to worker }
        if(target==null)return "No diagnostic journal.\n"
        return runCatching { queue?.submit<String> { target.snapshot() }?.get(5,java.util.concurrent.TimeUnit.SECONDS) }
            .getOrNull() ?: target.snapshot()
    }
    /** Caller runs on IO after public-storage permission is available. Export still works when disabled. */
    fun export(context: Context): String = synchronized(exportLock) {
        val text=snapshot()
        if(Build.VERSION.SDK_INT>=29){
            val resolver=context.contentResolver
            val collection=MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val path=Environment.DIRECTORY_DOWNLOADS+"/streamHub/log/"
            val uri=resolver.query(collection,arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
                arrayOf("log.txt",path,context.packageName),null)?.use { cursor ->
                if(cursor.moveToFirst())ContentUris.withAppendedId(collection,cursor.getLong(0)) else null
            } ?: resolver.insert(collection,ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,"log.txt");put(MediaStore.MediaColumns.MIME_TYPE,"text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH,path)
            }) ?: error("Cannot create log.txt in Downloads")
            val output=resolver.openOutputStream(uri,"wt") ?: error("Cannot write log.txt")
            output.bufferedWriter().use { it.write(text) }
        }else{
            @Suppress("DEPRECATION")
            val folder=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"streamHub/log")
            check(folder.isDirectory||folder.mkdirs()){ "Cannot create log folder" }
            File(folder,"log.txt").writeText(text)
        }
        "Exported Downloads/streamHub/log/log.txt"
    }
}
