package com.example.watchoffline.vid

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.VLCVideoLayout

object VideoPlayerHolder {
    var libVlc: LibVLC? = null
    var mediaPlayer: MediaPlayer? = null
    var currentUrl: String? = null

    private var currentCallback: IVLCVout.Callback? = null

    fun reconnect(layout: VLCVideoLayout) {
        val mp = mediaPlayer ?: return
        Log.d("DEBUG_VLC", ">>> Holder: RECONNECT solicitado.")

        // Limpiamos listener anterior
        currentCallback?.let { mp.vlcVout.removeCallback(it) }
        currentCallback = null

        // Desvinculamos surface anterior
        mp.detachViews()

        // Creamos nuevo callback
        currentCallback = object : IVLCVout.Callback {
            override fun onSurfacesCreated(vlcVout: IVLCVout?) {
                Log.d("DEBUG_VLC", "✅ Surface Creada.")

                // Forzamos 'micro-play' para asegurar audio
                val currentVol = mp.volume
                val safeVol = if (currentVol > 0) currentVol else 100

                mp.volume = 0
                mp.play()

                Handler(Looper.getMainLooper()).postDelayed({
                    // Restauramos volumen antes de pausar
                    mp.volume = safeVol
                    Log.d("DEBUG_VLC", "Volumen restaurado a $safeVol")

                    if (mp.isPlaying) {
                        mp.pause()
                        Log.d("DEBUG_VLC", "Micro-play completado. Video repausado.")
                    }
                }, 50)
            }

            override fun onSurfacesDestroyed(vlcVout: IVLCVout?) {
                Log.d("DEBUG_VLC", "❌ Surface Destruida.")
            }
        }

        // Registramos callback y adjuntamos la vista
        mp.vlcVout.addCallback(currentCallback)
        mp.attachViews(layout, null, false, false)
        mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        Log.d("DEBUG_VLC", ">>> AttachViews completado.")
    }

    fun pause() {
        mediaPlayer?.let { mp ->
            try {
                mp.pause()
                mp.volume = 0
                Log.d("DEBUG_VLC", ">>> pause FORZADO")
            } catch (_: Exception) {}
        }
    }

    fun detach() {
        mediaPlayer?.let { mp ->
            Log.d("DEBUG_VLC", ">>> DETACH")
            try { mp.pause(); mp.volume = 0 } catch (_: Exception) {}
            currentCallback?.let { mp.vlcVout.removeCallback(it) }
            currentCallback = null
            try { mp.detachViews() } catch (_: Exception) {}
        }
    }

    fun release() {
        detach()
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        try { libVlc?.release() } catch (_: Exception) {}
        libVlc = null
        currentUrl = null
    }
}
