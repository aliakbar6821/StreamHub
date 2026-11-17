package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

abstract class Plugin : BasePlugin() {
    companion object {
        const val `$stable`: Int = 8
    }

    open fun load(context: Context) {
        load()
    }

    var resources: Resources? = null
    var needsResources: Boolean = false
    var openSettings: ((context: Context) -> Unit)? = null
}
