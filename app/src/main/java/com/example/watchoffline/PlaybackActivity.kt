package com.example.watchoffline.vid

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.example.watchoffline.Movie
import com.example.watchoffline.R
import com.google.android.exoplayer2.ui.PlayerView

class PlaybackVideoFragment : Fragment() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_playback_video, container, false)
        playerView = view.findViewById(R.id.player_view) // Asegúrate de tener este ID en tu XML
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
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
    }
}

