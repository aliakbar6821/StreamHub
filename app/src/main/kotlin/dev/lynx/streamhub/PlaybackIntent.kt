package dev.lynx.streamhub

/** Async readiness never grants permission to start background audio. */
internal class PlaybackIntent(var foreground: Boolean = false) {
    var wantsPlay: Boolean = true
    var pictureInPicture: Boolean = false
    val shouldPlay: Boolean get() = wantsPlay && (foreground || pictureInPicture)
}
