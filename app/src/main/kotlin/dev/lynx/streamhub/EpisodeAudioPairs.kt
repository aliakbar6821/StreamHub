package dev.lynx.streamhub

/** Never pair by list index: providers may omit episodes or interleave seasons/specials. */
object EpisodeAudioPairs {
    data class Entry(val data:String,val season:Int?,val number:Int?)
    fun match(primary:List<Entry>,alternate:List<Entry>):Map<String,String> {
        fun key(e:Entry)=e.number?.takeIf { it>0 }?.let { (e.season ?: 1) to it }
        val a=primary.filter { key(it)!=null }.groupBy { key(it) }
        val b=alternate.filter { key(it)!=null }.groupBy { key(it) }
        val primaryCounts=primary.groupingBy { it.data }.eachCount()
        val alternateCounts=alternate.groupingBy { it.data }.eachCount()
        val output=linkedMapOf<String,String>()
        for((coordinate,rows) in a){
            val left=rows.singleOrNull() ?: continue
            val right=b[coordinate]?.singleOrNull() ?: continue
            if(primaryCounts[left.data]==1 && alternateCounts[right.data]==1 && left.data.isNotBlank()&&right.data.isNotBlank()&&left.data!=right.data){output[left.data]=right.data;output[right.data]=left.data}
        }
        return output
    }
    fun identity(data:String)="alt:"+java.security.MessageDigest.getInstance("SHA-256")
        .digest(data.toByteArray()).take(8).joinToString(""){"%02x".format(it.toInt() and 255)}
}
