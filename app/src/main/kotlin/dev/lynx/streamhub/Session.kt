package dev.lynx.streamhub

import dev.lynx.streamhub.api.Extension
import java.util.concurrent.ConcurrentHashMap

object Session {
    // item id -> extension that provided it
    val owner = ConcurrentHashMap<String, Extension>()
}
