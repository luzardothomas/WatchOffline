package com.example.watchoffline.vid

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.example.watchoffline.Movie
import com.example.watchoffline.R
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ui.PlayerView


class PlaybackVideoFragment : Fragment() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var skipIntroButton: Button  // Agregado desde la rama master

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_playback_video, container, false)
        playerView = view.findViewById(R.id.player_view)
        skipIntroButton = view.findViewById(R.id.skip_intro_button)

        val movie = arguments?.getSerializable("movie") as? Movie
        val skip = movie?.skipToSecond

        if(skip != 0) {
            skipIntroButton.visibility = View.VISIBLE
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializePlayer()
    }

    private fun initializePlayer() {

        // Configurar RenderersFactory para usar extensiones como FFmpeg
        val renderersFactory = DefaultRenderersFactory(requireContext())
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // Crear instancia del reproductor
        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(renderersFactory)
            .build()
            .apply {
                // Recuperar la película desde los argumentos
                val movie = arguments?.getSerializable("movie") as? Movie
                val videoUrl = movie?.videoUrl

                if (videoUrl.isNullOrEmpty()) {
                    Log.e("PlaybackVideoFragment", "URL del video no encontrada")
                    Toast.makeText(requireContext(), "No se pudo cargar el video", Toast.LENGTH_SHORT).show()
                    return
                }

                Log.d("PlaybackVideoFragment", "Cargando video desde URL: $videoUrl")

                // Crear y configurar el MediaItem
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


                // Acción del botón Skip Intro
                skipIntroButton.setOnClickListener {
                    player?.let { exoPlayer ->
                        val currentPosition = exoPlayer.currentPosition
                        val movie = arguments?.getSerializable("movie") as? Movie
                        val skip = movie?.skipToSecond
                        if(skip != null) {
                            val newPosition = (skip * 1000L)
                            exoPlayer.seekTo(newPosition)
                        }
                    }
                }
            }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
    }
}

