package dev.lynx.streamhub

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaCodec
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Native AAC + AVC/HEVC → MP4 remux. No codecs are downloaded and no media is re-encoded. */
object OfflineMuxer {
    suspend fun combine(video:File,audio:File,output:File,height:Int,checkpoint:()->Unit,progress:(Int)->Unit){
        val v=MediaExtractor();val a=MediaExtractor();val temporary=File(output.path+".muxing.mp4")
        temporary.delete();var muxer:MediaMuxer?=null;var started=false;var complete=false
        try{
            v.setDataSource(video.path);a.setDataSource(audio.path)
            fun select(e:MediaExtractor,prefix:String):Pair<Int,MediaFormat>{
                for(i in 0 until e.trackCount){val f=e.getTrackFormat(i)
                    if(f.getString(MediaFormat.KEY_MIME)?.startsWith(prefix)==true){e.selectTrack(i);return i to f}}
                error("Downloaded component contains no $prefix track")
            }
            val vf=select(v,"video/").second;val af=select(a,"audio/").second
            check(vf.getString(MediaFormat.KEY_MIME) in setOf("video/avc","video/hevc")){"MP4 combining requires AVC or HEVC video"}
            check(af.getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm"){"MP4 combining requires AAC audio"}
            check(vf.getInteger(MediaFormat.KEY_HEIGHT)==height){"Downloaded resolution differs from the selected quality"}
            val m=MediaMuxer(temporary.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);muxer=m
            val vi=m.addTrack(vf);val ai=m.addTrack(af);m.start();started=true
            fun bufferSize(f:MediaFormat)=if(f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val capacity=maxOf(2*1024*1024,bufferSize(vf),bufferSize(af)).coerceAtMost(16*1024*1024)
            val buffer=ByteBuffer.allocateDirect(capacity);val info=MediaCodec.BufferInfo()
            // HLS renditions share a presentation timeline: retain their start offset.
            val origin=minOf(v.sampleTime.coerceAtLeast(0),a.sampleTime.coerceAtLeast(0))
            val vb=origin;val ab=origin
            var vc=0;var ac=0;var tick=0L
            val duration=if(vf.containsKey(MediaFormat.KEY_DURATION))vf.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1) else Long.MAX_VALUE
            while(v.sampleTime>=0||a.sampleTime>=0){
                currentCoroutineContext().ensureActive();checkpoint()
                val useVideo=a.sampleTime<0||(v.sampleTime>=0&&v.sampleTime-vb<=a.sampleTime-ab)
                val e=if(useVideo)v else a;val base=if(useVideo)vb else ab
                check(e.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED==0){"Encrypted samples cannot be remuxed"}
                buffer.clear();val size=e.readSampleData(buffer,0);check(size>=0){"Unexpected end of encoded media"}
                check(size<=capacity){"Encoded sample exceeds safe muxing buffer"}
                val pts=(e.sampleTime-base).coerceAtLeast(0)
                info.set(0,size,pts,if(e.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(if(useVideo)vi else ai,buffer,info)
                if(useVideo)vc++ else ac++
                e.advance()
                val now=System.currentTimeMillis()
                if(now-tick>=500){progress((pts.toDouble()/duration*100).toInt().coerceIn(0,99));tick=now}
            }
            check(vc>0&&ac>0){"Audio/video samples are missing; refusing to publish a silent file"}
            m.stop();started=false;m.release();muxer=null
            currentCoroutineContext().ensureActive();checkpoint()
            val verify=MediaExtractor()
            try{verify.setDataSource(temporary.path)
                val types=(0 until verify.trackCount).map{verify.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()}
                check(types.any{it.startsWith("video/")}&&types.any{it.startsWith("audio/")}){"Combined file failed audio/video validation"}
            }finally{verify.release()}
            output.delete();check(temporary.renameTo(output)){"Cannot finalize combined MP4"}
            complete=true;progress(100)
        }finally{
            if(started)runCatching{muxer?.stop()}
            runCatching{muxer?.release()};v.release();a.release()
            if(!complete)temporary.delete()
        }
    }
}
