package dev.lynx.streamhub

import android.content.Context
import androidx.media3.common.util.UnstableApi
import dev.lynx.streamhub.api.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Separate HLS VOD renditions are fetched independently, then remuxed without re-encoding. */
@UnstableApi
object HlsDownloadPlans {
    data class Plan(val video:Stream,val audio:Stream,val height:Int,val language:String)
    private val plans=linkedMapOf<String,Plan>()
    private fun key(s:Stream)=s.url+"\u0000"+s.quality.orEmpty()+"\u0000"+s.headers.toSortedMap().toString()
    @Synchronized fun get(s:Stream):Plan?=plans[key(s)]
    @Synchronized private fun remember(s:Stream,p:Plan){plans[key(s)]=p;while(plans.size>128)plans.remove(plans.keys.first())}
    private val attributes=Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
    private fun attrs(line:String)=attributes.findAll(line.substringAfter(':')).associate{it.groupValues[1] to it.groupValues[2].removeSurrounding("\"")}
    private fun headers(base:String,target:String,input:Map<String,String>) =
        DownloadSafety.redirectedHeaders(base,target,input)
    fun parse(stream:Stream,text:String):List<Plan>? {
        val lines=text.lines().map{it.trim()}
        if(lines.none{it.startsWith("#EXT-X-STREAM-INF:")})return null
        val audio=lines.filter{it.startsWith("#EXT-X-MEDIA:")}.map{attrs(it)}
            .filter{it["TYPE"]=="AUDIO"&&!it["URI"].isNullOrBlank()}
        if(audio.isEmpty())return null
        val output=mutableListOf<Plan>()
        for(i in lines.indices){
            if(!lines[i].startsWith("#EXT-X-STREAM-INF:"))continue
            val v=attrs(lines[i]);val group=v["AUDIO"] ?: continue
            val next=lines.drop(i+1).firstOrNull{it.isNotBlank()&&!it.startsWith('#')} ?: continue
            val height=v["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: continue
            if(height<=0)continue
            val videoUrl=URL(URL(stream.url),next).toString()
            if(!videoUrl.startsWith("https://",true)&&!videoUrl.startsWith("http://",true))continue
            for(a in audio.filter{it["GROUP-ID"]==group}){
                val audioUrl=URL(URL(stream.url),a["URI"]).toString()
                if(!audioUrl.startsWith("https://",true)&&!audioUrl.startsWith("http://",true))continue
                val lang=audioLanguageCode(a["LANGUAGE"])?:audioLanguageCode(a["NAME"])?:"original"
                output.add(Plan(Stream(videoUrl,"${height}p [HLS]",headers(stream.url,videoUrl,stream.headers)),
                    Stream(audioUrl,"[HLS]",headers(stream.url,audioUrl,stream.headers)),height,lang))
            }
        }
        return output.distinctBy{listOf(it.video.url,it.audio.url,it.language)}
    }
    private suspend fun master(stream:Stream)=HlsInspection.read(stream)
    suspend fun options(context:Context,source:Stream):Pair<List<Stream>,String?>? {
        if(!isHls(source))return null
        return try { prepareOptions(context, source) } catch (cancel:kotlinx.coroutines.CancellationException) { throw cancel } catch (rate:HlsInspection.RateLimited) { emptyList<Stream>() to rate.message } catch (failure:Exception) { emptyList<Stream>() to "Could not inspect this HLS source. Other verified options may still be available." }
    }
    private suspend fun prepareOptions(context:Context,source:Stream):Pair<List<Stream>,String?>? {
        val (resolved,text)=master(source)
        val parsed=parse(resolved,text) ?: return null
        val output=mutableListOf<Stream>();var warning:String?=null
        for(p in parsed){
            val video=VerifiedStreams.inspect(context,p.video)
            if(!video.videoSupported||video.heights.size != 1||p.height !in video.heights){warning="A rendition did not report its advertised video resolution.";continue}
            val audio=VerifiedStreams.inspect(context,p.audio)
            if(audio.audio.none{it.supported&&it.codec=="audio/mp4a-latm"}){
                warning="MP4 combining currently requires supported AAC audio.";continue
            }
            val label=if(p.language=="original")"Original" else audioLanguageName(p.language)?:"Original"
            val option=p.video.copy(quality="${p.height}p ($label Audio) [HLS] [MUX]")
            remember(option,p);output.add(option)
        }
        return output.distinctBy{it.url to streamChoice(it).audioKey} to if(output.isEmpty())warning?:"No compatible AAC/video rendition pair found." else warning
    }
    private fun json(s:Stream)=JSONObject().put("url",s.url).put("quality",s.quality.orEmpty()).put("headers",JSONObject(s.headers))
    private fun stream(j:JSONObject):Stream {val h=j.getJSONObject("headers");return Stream(j.getString("url"),j.optString("quality"),h.keys().asSequence().associateWith{h.getString(it)})}
    fun persist(c:Context,id:Long,p:Plan){check(c.getSharedPreferences("dl_mux",Context.MODE_PRIVATE).edit().putString(id.toString(),
        JSONObject().put("video",json(p.video)).put("audio",json(p.audio)).put("height",p.height).put("language",p.language).toString()).commit()){"Could not save audio/video download plan"}}
    fun restore(c:Context,id:Long):Plan?=runCatching{
        val raw=c.getSharedPreferences("dl_mux",Context.MODE_PRIVATE).getString(id.toString(),null)?:return@runCatching null
        val j=JSONObject(raw);Plan(stream(j.getJSONObject("video")),stream(j.getJSONObject("audio")),j.getInt("height"),j.getString("language"))
    }.getOrNull()
}
