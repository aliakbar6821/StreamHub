package dev.lynx.streamhub

import java.net.URL

/** Small deterministic guards shared by download preparation and transfer. */
object DownloadSafety {
    fun redirectedHeaders(from: String, to: String, headers: Map<String, String>): Map<String, String> {
        val a=URL(from);val b=URL(to)
        require(b.protocol.equals("https",true)||b.protocol.equals("http",true)) { "Unsupported download protocol" }
        fun port(u: URL)=if(u.port<0)u.defaultPort else u.port
        val same=a.protocol.equals(b.protocol,true)&&a.host.equals(b.host,true)&&port(a)==port(b)
        return if(same)headers else headers.filterKeys {
            !it.equals("Cookie",true)&&!it.equals("Authorization",true)&&!it.equals("Proxy-Authorization",true)
        }
    }
    fun requirePlan(quality: String?, hasPlan: Boolean) {
        check(hasPlan || !quality.orEmpty().contains("[MUX]",true)) {
            "Saved audio/video plan is unavailable. Remove this entry and refresh download options. No video-only fallback was used."
        }
    }
}
