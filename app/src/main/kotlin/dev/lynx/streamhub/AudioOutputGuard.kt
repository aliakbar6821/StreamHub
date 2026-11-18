package dev.lynx.streamhub

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** Rebuild only our audio renderer, never change global routing, volume, mode or playback intent. */
@UnstableApi
class AudioOutputGuard(context: Context, private val player: ExoPlayer) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler=Handler(Looper.getMainLooper())
    private var lastDevices: Set<Int> = outputs()
    private var closed=false
    private var restoring=false
    private fun outputs()=manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.toSet()
    private val finish=Runnable {
        if (!closed && restoring) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).build()
        restoring=false
    }
    private val refresh=Runnable { if (!closed && player.playbackState != Player.STATE_IDLE) reset(false) }
    private val callback=object:AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { changed() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { changed() }
    }
    private fun changed() {
        val devices=outputs()
        if(devices==lastDevices) return // initial callback must not restart a prepared player
        lastDevices=devices
        handler.removeCallbacks(refresh);handler.postDelayed(refresh,400L)
    }
    fun start() { manager.registerAudioDeviceCallback(callback,handler) }
    fun reset(userInitiated: Boolean) {
        if(closed || restoring) return
        player.setPreferredAudioDevice(null) // follow Android's chosen media route
        if(userInitiated) player.volume=1f // explicit recovery only; do not alter system media volume
        val parameters=player.trackSelectionParameters
        if(!userInitiated && parameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)) return
        val enabled=parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).build()
        restoring=true
        player.trackSelectionParameters=enabled.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,true).build()
        handler.postDelayed(finish,120L)
    }
    fun report(): String {
        val outputs=manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { "type=${it.type}" }
        return "Media volume ${manager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} · player volume ${player.volume}\n" +
            "Audio mode ${manager.mode} · connected outputs: $outputs\nConnected outputs are not proof of the active route."
    }
    fun close() {
        closed=true;manager.unregisterAudioDeviceCallback(callback)
        handler.removeCallbacks(refresh);handler.removeCallbacks(finish)
        // The player is released immediately after this guard is disposed.
        restoring=false
    }
}
