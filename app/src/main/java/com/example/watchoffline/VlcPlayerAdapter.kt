package com.example.watchoffline

import androidx.leanback.media.PlayerAdapter
import org.videolan.libvlc.MediaPlayer

class VlcPlayerAdapter(
    private val mediaPlayer: MediaPlayer
) : PlayerAdapter() {

    override fun play() {
        mediaPlayer.play()
    }

    override fun pause() {
        mediaPlayer.pause()
    }

    override fun seekTo(positionMs: Long) {
        mediaPlayer.time = positionMs
    }

    override fun getCurrentPosition(): Long =
        mediaPlayer.time

    override fun getDuration(): Long =
        mediaPlayer.length

    override fun isPlaying(): Boolean =
        mediaPlayer.isPlaying
}
