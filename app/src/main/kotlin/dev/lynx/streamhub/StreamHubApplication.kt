package dev.lynx.streamhub

import android.app.Application

class StreamHubApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(this)
        Diagnostics.event("app.process.start","version=0.2.22 sdk=${android.os.Build.VERSION.SDK_INT}")
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread,error ->
            runCatching { Diagnostics.crash(error) }
            if(previous!=null)previous.uncaughtException(thread,error)
            else { android.os.Process.killProcess(android.os.Process.myPid());kotlin.system.exitProcess(10) }
        }
    }
}
