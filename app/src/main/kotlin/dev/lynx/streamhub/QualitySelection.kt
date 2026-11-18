package dev.lynx.streamhub

import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi

@UnstableApi
object QualitySelection {
    /** Local files must not inherit a saved streaming video override or size limit. */
    fun forLocal(current: TrackSelectionParameters): TrackSelectionParameters = current.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_VIDEO).clearVideoSizeConstraints().build()
    fun select(current:TrackSelectionParameters,tracks:Tracks,height:Int,locked:Boolean):TrackSelectionParameters {
        var builder=current.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).clearVideoSizeConstraints()
        if(height<0) return builder.build()
        if(!locked) return builder.setMaxVideoSize(Int.MAX_VALUE,height).build()
        val match=tracks.groups.filter { it.type==C.TRACK_TYPE_VIDEO }.firstNotNullOfOrNull { group ->
            (0 until group.length).firstOrNull { group.isTrackSupported(it) && group.getTrackFormat(it).height==height }
                ?.let { group to it }
        } ?: throw IllegalArgumentException("Locked ${height}p is unavailable in this source. Choose Auto or another quality.")
        builder=builder.setMinVideoSize(0,height).setMaxVideoSize(Int.MAX_VALUE,height)
            .setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup,match.second))
        return builder.build()
    }
}
