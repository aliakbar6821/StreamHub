package dev.lynx.streamhub

import dev.lynx.streamhub.api.Stream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared bounded playlist reads, single-flight inspection, and origin-level 429 backoff. */
object HlsInspection {
    class RateLimited(val waitMs:Long): java.io.IOException("Provider rate limit (HTTP 429). Wait ${(waitMs+999)/1000} seconds before refreshing.")
    private data class Entry(val at:Long,val value:Pair<Stream,String>)
    private val cache=linkedMapOf<String,Entry>()
    private val cooldown=linkedMapOf<String,Long>()
    private val gate=Mutex()
    private fun key(s:Stream)=s.url+"\u0000"+s.headers.toSortedMap().toString()
    private fun origin(url:String):String?=runCatching { val u=URL(url);"${u.protocol.lowercase()}://${u.host.lowercase()}:${if(u.port<0)u.defaultPort else u.port}" }.getOrNull()
    fun retryAfterMs(value:String?,now:Long=System.currentTimeMillis()):Long {
        val seconds=value?.trim()?.toLongOrNull()
        if(seconds!=null && seconds>=0)return seconds.coerceAtMost(Long.MAX_VALUE/1000).times(1000).coerceAtLeast(1000)
        val date=runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",java.util.Locale.US)
            .apply { isLenient=false }.parse(value.orEmpty())?.time }.getOrNull()
        return date?.minus(now)?.coerceAtLeast(1000) ?: 60_000L
    }
    @Synchronized fun markRateLimit(url:String,retryAfter:String?):Long {
        val wait=retryAfterMs(retryAfter);val host=origin(url) ?: return wait
        val now=System.currentTimeMillis()
        val until=if(wait>Long.MAX_VALUE-now)Long.MAX_VALUE else now+wait
        cooldown[host]=maxOf(cooldown[host] ?: 0L,until)
        while(cooldown.size>64)cooldown.remove(cooldown.keys.first())
        return wait
    }
    @Synchronized fun remaining(url:String):Long {
        val host=origin(url) ?: return 0L
        val until=cooldown[host] ?: return 0L
        val left=until-System.currentTimeMillis()
        if(left<=0){cooldown.remove(host);return 0L}
        return left
    }
    @Synchronized fun invalidate(stream:Stream){cache.remove(key(stream))}
    @Synchronized private fun cached(stream:Stream):Pair<Stream,String>? = cache[key(stream)]
        ?.takeIf { System.currentTimeMillis()-it.at in 0..30_000L }?.value
    @Synchronized private fun save(stream:Stream,value:Pair<Stream,String>){
        cache[key(stream)]=Entry(System.currentTimeMillis(),value)
        while(cache.size>64)cache.remove(cache.keys.first())
    }
    suspend fun read(stream:Stream):Pair<Stream,String> = gate.withLock {
        cached(stream)?.let { return@withLock it }
        withContext(Dispatchers.IO){
            var current=stream
            repeat(6){
                val left=remaining(current.url);if(left>0)throw RateLimited(left)
                val c=URL(current.url).openConnection() as HttpURLConnection
                try{
                    c.instanceFollowRedirects=false;c.connectTimeout=8000;c.readTimeout=12000
                    c.setRequestProperty("User-Agent",Downloader.UA)
                    current.headers.forEach{(k,v)->c.setRequestProperty(k,v)}
                    val code=c.responseCode
                    if(code==429){
                        val wait=markRateLimit(current.url,c.getHeaderField("Retry-After"))
                        // Also back off the entry origin if it redirects to this rate-limited origin.
                        val seconds=(wait/1000).coerceAtLeast(1).toString()
                        markRateLimit(stream.url,seconds)
                        throw RateLimited(wait)
                    }
                    if(code in listOf(301,302,303,307,308)){
                        val next=URL(URL(current.url),c.getHeaderField("Location") ?: error("Missing playlist redirect target")).toString()
                        current=current.copy(url=next,headers=DownloadSafety.redirectedHeaders(current.url,next,current.headers))
                    }else{
                        check(code in 200..299){"Playlist inspection failed: HTTP $code"}
                        val text=c.inputStream.bufferedReader().use{reader->
                            val out=StringBuilder();val buffer=CharArray(8192)
                            while(true){val n=reader.read(buffer);if(n<0)break;check(out.length+n<=1_000_000){"Playlist too large"};out.append(buffer,0,n)}
                            out.toString().also { check(it.trimStart().startsWith("#EXTM3U")){"Source did not return an HLS playlist"} }
                        }
                        val result=current to text
                        save(stream,result);return@withContext result
                    }
                }finally{c.disconnect()}
            }
            error("Too many playlist redirects")
        }
    }
}
