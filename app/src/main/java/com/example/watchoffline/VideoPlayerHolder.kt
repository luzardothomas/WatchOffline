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

    // Variable para controlar que no se acumulen listeners
    private var currentCallback: IVLCVout.Callback? = null

    fun reconnect(layout: VLCVideoLayout) {
        val mp = mediaPlayer ?: return
        Log.d("DEBUG_VLC", ">>> Holder: RECONNECT solicitado.")

        // 1. Limpiamos cualquier listener zombie anterior
        if (currentCallback != null) {
            mp.vlcVout.removeCallback(currentCallback)
            currentCallback = null
        }

        // 2. Desvinculamos vista anterior
        mp.detachViews()

        // 3. Creamos el nuevo listener
        // 3. Creamos el nuevo listener
        currentCallback = object : IVLCVout.Callback {
            override fun onSurfacesCreated(vlcVout: IVLCVout?) {
                Log.d("DEBUG_VLC", "✅ EVENTO: Surface Creada (Pantalla lista).")

                // Si el video está pausado, aplicamos la técnica "Mute & Kick"
                if (!mp.isPlaying) {
                    Log.d("DEBUG_VLC", "    -> Video pausado. Iniciando secuencia de pintado...")

                    // A. Guardamos volumen SEGURO (si es 0 por algún bug, asumimos 100)
                    val currentVol = mp.volume
                    val safeVolume = if (currentVol > 0) currentVol else 100

                    // B. Silenciamos
                    mp.volume = 0

                    // C. Play forzado
                    mp.play()

                    // D. Esperamos 50ms y RESTAURAMOS INCONDICIONALMENTE
                    Handler(Looper.getMainLooper()).postDelayed({
                        // 1. Restaurar volumen PRIMERO (pase lo que pase)
                        mp.volume = safeVolume
                        Log.d("DEBUG_VLC", "    -> Volumen restaurado a: $safeVolume")

                        // 2. Si sigue reproduciendo, pausamos
                        if (mp.isPlaying) {
                            mp.pause()
                            Log.d("DEBUG_VLC", "    -> 'Micro-Play' completado. Video repausado.")
                        }
                    }, 50)
                }
            }

            override fun onSurfacesDestroyed(vlcVout: IVLCVout?) {
                Log.d("DEBUG_VLC", "❌ EVENTO: Surface Destruida.")
            }
        }

        // 4. Registramos el listener Y LUEGO adjuntamos la vista
        mp.vlcVout.addCallback(currentCallback)
        mp.attachViews(layout, null, false, false)
        mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        Log.d("DEBUG_VLC", ">>> Holder: AttachViews completado. Esperando callback...")
    }

    fun pause() {
        try { if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause() } catch (_: Exception) {}
    }

    fun detach() {
        // Al salir, limpiamos el callback para no causar memory leaks
        val mp = mediaPlayer ?: return
        if (currentCallback != null) {
            mp.vlcVout.removeCallback(currentCallback)
            currentCallback = null
        }
        try { mp.detachViews() } catch (_: Exception) {}
    }

    fun release() {
        detach() // Reusamos la lógica de limpieza
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        try { libVlc?.release() } catch (_: Exception) {}
        libVlc = null
        currentUrl = null
    }
}