package com.example.watchoffline.vid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.example.watchoffline.Movie
import com.example.watchoffline.R
import com.google.android.exoplayer2.ui.PlayerView

class PlaybackVideoFragment : Fragment() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var skipIntroButton: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_playback_video, container, false)
        playerView = view.findViewById(R.id.player_view)
        skipIntroButton = view.findViewById(R.id.skip_intro_button)

        // Asegurarse de que el botón esté visible inicialmente
        skipIntroButton.visibility = View.VISIBLE

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializePlayer()
    }

    private fun initializePlayer() {
        // Crear instancia del reproductor
        player = ExoPlayer.Builder(requireContext()).build().apply {
            // Recuperar la película desde los argumentos
            val movie = arguments?.getSerializable("movie") as? Movie
            val videoUrl = movie?.videoUrl

            if (videoUrl.isNullOrEmpty()) {
                return
            }

            val mediaItem = MediaItem.fromUri(videoUrl)
            setMediaItem(mediaItem)

            // Asociar el reproductor a la vista
            playerView.player = this

            // Preparar y reproducir
            prepare()
            playWhenReady = true

            // Escuchar la posición del video
            addListener(object : Player.Listener {
                override fun onPositionDiscontinuity(reason: Int) {
                    super.onPositionDiscontinuity(reason)
                    val currentPosition = currentPosition

                    // Mostrar el botón Skip Intro solo durante los primeros 15 segundos
                    if (currentPosition < 15000) { // 15000 ms = 15 segundos
                        skipIntroButton.visibility = View.VISIBLE
                    } else {
                        skipIntroButton.visibility = View.GONE
                    }
                }
            })
        }

        // Acción del botón Skip Intro
        skipIntroButton.setOnClickListener {
            player?.let { exoPlayer ->
                val currentPosition = exoPlayer.currentPosition
                val newPosition = currentPosition + 60000 // Salta 1 minuto
                exoPlayer.seekTo(newPosition)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
    }
}







