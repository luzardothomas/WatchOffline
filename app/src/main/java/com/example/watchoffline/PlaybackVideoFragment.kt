package com.example.watchoffline.vid

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.watchoffline.Movie
import com.example.watchoffline.R
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer

class PlaybackVideoFragment : Fragment() {

    private lateinit var root: View
    private lateinit var videoLayout: VLCVideoLayout

    private lateinit var controlsOverlay: View
    private lateinit var seekBar: SeekBar
    private lateinit var btnSeekBack: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSeekFwd: ImageButton
    private lateinit var skipIntroButton: Button
    private lateinit var txtPos: TextView
    private lateinit var txtDur: TextView

    private var libVlc: LibVLC? = null
    private var vlcPlayer: VlcMediaPlayer? = null

    private val ui = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private var isUserSeeking = false
    private var controlsVisible = true

    private val SEEK_STEP_MS = 10_000L      // < >
    private val SEEK_STEP_BAR = 25          // 0..1000 => 2.5% por toque
    private val AUTO_HIDE_MS = 2500L

    private var pendingSeekMs: Long? = null
    private var lastSeekAppliedAt = 0L
    private val SEEK_APPLY_DEBOUNCE_MS = 90L   // evita spamear VLC demasiado rápido

    private var previewSeekMs: Long? = null
    private val CLEAR_PREVIEW_DELAY = 650L

    private val clearPreviewRunnable = Runnable {
        isUserSeeking = false
        previewSeekMs = null
    }



    private val hideControlsRunnable = Runnable {
        // Solo ocultar overlay, NO el skip (lo maneja ticker)
        controlsVisible = false
        controlsOverlay.visibility = View.GONE

        // ✅ Si el overlay se va, aseguramos que el root quede con foco para capturar DPAD
        root.requestFocus()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = inflater.inflate(R.layout.fragment_playback_video, container, false)

        videoLayout = root.findViewById(R.id.player_view)

        controlsOverlay = root.findViewById(R.id.controls_overlay)
        seekBar = root.findViewById(R.id.seek_bar)
        btnSeekBack = root.findViewById(R.id.btn_seek_back)
        btnPlayPause = root.findViewById(R.id.btn_play_pause)
        btnSeekFwd = root.findViewById(R.id.btn_seek_fwd)
        skipIntroButton = root.findViewById(R.id.skip_intro_button)
        txtPos = root.findViewById(R.id.txt_pos)
        txtDur = root.findViewById(R.id.txt_dur)

        // ✅ Evita “filtro gris molesto” de backgrounds default (sin tocar XML)
        btnSeekBack.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        btnPlayPause.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        btnSeekFwd.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // el skip intro lo dejás con tu drawable

        setupControls()

        // ✅ Un solo handler de teclado, aplicado a root + video + skip
        setupRemoteHandlers()

        // Hover/Focus visual para control remoto
        addTvFocusScale(btnSeekBack)
        addTvFocusScale(btnPlayPause, 1.18f)
        addTvFocusScale(btnSeekFwd)
        addTvFocusScale(seekBar, 1.06f)
        addTvFocusScale(skipIntroButton, 1.10f)

        // Tap/click sobre video -> toggle overlay
        videoLayout.setOnClickListener { toggleControls() }

        // overlay arriba siempre
        controlsOverlay.bringToFront()
        controlsOverlay.translationZ = 50f

        // Foco inicial (TV)
        btnPlayPause.requestFocus()

        // arranca el auto-hide cuando se ven
        scheduleAutoHide()

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val movie = arguments?.getSerializable("movie") as? Movie
        val url = movie?.videoUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(requireContext(), "No se pudo cargar el video", Toast.LENGTH_SHORT).show()
            requireActivity().finish()
            return
        }

        initVlc(url)
        startTicker()
    }

    override fun onResume() {
        super.onResume()
        // ✅ Si el overlay está oculto, que el root tenga foco para capturar DPAD
        if (!controlsVisible) root.requestFocus()
    }

    private fun setupControls() {
        seekBar.max = 1000

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = true
                // mientras el usuario usa la barra, no auto-hide
                ui.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val p = vlcPlayer ?: run { isUserSeeking = false; return }
                val dur = p.length
                if (dur > 0) {
                    val target = (dur * (seekBar.progress / 1000.0)).toLong()
                    p.time = target
                }
                isUserSeeking = false
                scheduleAutoHide()
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
        })

        btnPlayPause.setOnClickListener {
            togglePlayPause()
            bumpControlsTimeout()
        }

        btnSeekBack.setOnClickListener {
            seekByMs(-SEEK_STEP_MS)
            bumpControlsTimeout()
        }

        btnSeekFwd.setOnClickListener {
            seekByMs(SEEK_STEP_MS)
            bumpControlsTimeout()
        }

        skipIntroButton.setOnClickListener {
            performSkipIntro()
            // ✅ Se ocultan controles, pero dejamos el sistema listo para volver a abrirlos
            hideOverlayOnly()
        }
    }

    private fun isTvDevice(): Boolean {
        val uiMode = requireContext().resources.configuration.uiMode
        val isTelevision =
            (uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
                    android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        val pm = requireContext().packageManager
        val hasLeanback = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

        return isTelevision || hasLeanback
    }

    private fun setupRemoteHandlers() {

        // ✅ En celular: no forzar foco / no capturar DPAD -> scroll y gestos libres
        if (!isTvDevice()) {
            // importante: liberar cualquier captura previa
            root.setOnKeyListener(null)
            videoLayout.setOnKeyListener(null)
            skipIntroButton.setOnKeyListener(null)
            seekBar.setOnKeyListener(null)

            // no fuerces foco en touch
            root.isFocusable = false
            root.isFocusableInTouchMode = false

            videoLayout.isFocusable = false
            videoLayout.isFocusableInTouchMode = false

            skipIntroButton.isFocusable = false
            skipIntroButton.isFocusableInTouchMode = false

            // seekbar puede quedar focusable o no; en celular conviene NO forzarlo
            seekBar.isFocusable = false
            seekBar.isFocusableInTouchMode = false

            return
        }

        // ✅ Android TV: mantenemos tu comportamiento actual (DPAD + foco)
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()

        val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false

            if (controlsVisible) bumpControlsTimeout()

            val focused = root.findFocus()

            // ==========================
            // 1) Si estoy en la SeekBar
            // ==========================
            if (focused === seekBar) {
                return@OnKeyListener when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekBarStep(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBarStep(+1); true }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> true
                    KeyEvent.KEYCODE_DPAD_DOWN -> { hideOverlayOnly(); true }
                    else -> false
                }
            }

            // ==========================
            // 2) Overlay oculto
            // ==========================
            if (!controlsVisible) {
                return@OnKeyListener when (keyCode) {

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        showOverlayAndFocusPlay()
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (skipIntroButton.visibility == View.VISIBLE) {
                            performSkipIntro()
                            hideOverlayOnly()
                            true
                        } else {
                            val p = vlcPlayer
                            if (p != null && p.isPlaying) {
                                p.pause()
                                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                            }
                            showOverlayAndFocusPlay()
                            true
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekByMs(-SEEK_STEP_MS); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekByMs(SEEK_STEP_MS); true }

                    else -> false
                }
            }

            // ==========================
            // 3) Overlay visible
            // ==========================
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    hideOverlayOnly()
                    true
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val f = root.findFocus()
                    if (f != null && f.isClickable) {
                        f.performClick()
                        true
                    } else {
                        togglePlayPause()
                        true
                    }
                }

                else -> false
            }
        }

        // ✅ Aplicar listener a los lugares de foco
        root.setOnKeyListener(keyListener)

        videoLayout.isFocusable = true
        videoLayout.isFocusableInTouchMode = true
        videoLayout.setOnKeyListener(keyListener)

        skipIntroButton.isFocusable = true
        skipIntroButton.isFocusableInTouchMode = true
        skipIntroButton.setOnKeyListener(keyListener)

        // ✅ SeekBar con DPAD
        seekBar.isFocusable = true
        seekBar.isFocusableInTouchMode = true
        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            if (controlsVisible) bumpControlsTimeout()

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekBarStep(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBarStep(+1); true }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> true
                KeyEvent.KEYCODE_DPAD_DOWN -> { hideOverlayOnly(); true }
                else -> false
            }
        }
    }


    private fun toggleControls() {
        if (controlsVisible) hideOverlayOnly() else showOverlayAndFocusPlay()
    }

    private fun showOverlayAndFocusPlay() {
        controlsVisible = true
        controlsOverlay.visibility = View.VISIBLE
        controlsOverlay.alpha = 1f
        controlsOverlay.bringToFront()
        controlsOverlay.translationZ = 50f
        controlsOverlay.requestLayout()
        controlsOverlay.invalidate()

        btnPlayPause.requestFocus()
        scheduleAutoHide()
    }

    private fun hideOverlayOnly() {
        ui.removeCallbacks(hideControlsRunnable)
        hideControlsRunnable.run()
    }

    private fun scheduleAutoHide() {
        ui.removeCallbacks(hideControlsRunnable)

        // ✅ si estoy parado en la pelotita, NO auto-hide
        val focusIsSeek = (root.findFocus() === seekBar)
        if (focusIsSeek) return

        ui.postDelayed(hideControlsRunnable, AUTO_HIDE_MS)
    }


    private fun bumpControlsTimeout() {
        // si están visibles, extender vida
        if (controlsVisible) scheduleAutoHide()
    }

    private fun addTvFocusScale(view: View, scale: Float = 1.12f) {
        view.setOnFocusChangeListener { v, hasFocus ->

            // ✅ regla de oro para la pelotita
            if (v === seekBar) {
                if (hasFocus) {
                    // mientras el usuario está en la pelotita, nunca ocultar
                    ui.removeCallbacks(hideControlsRunnable)
                } else {
                    // cuando sale de la pelotita, recién ahí volvemos al auto-hide
                    if (controlsVisible) scheduleAutoHide()
                }
            }

            v.animate()
                .scaleX(if (hasFocus) scale else 1f)
                .scaleY(if (hasFocus) scale else 1f)
                .setDuration(120)
                .start()
        }
    }


    private fun performSkipIntro() {
        val movie = arguments?.getSerializable("movie") as? Movie
        val skip = movie?.skipToSecond ?: 0
        if (skip <= 0) return

        val p = vlcPlayer ?: return

        // ✅ hacer skip sin pausar
        val target = skip * 1000L
        p.time = target

        // opcional: si por alguna razón estaba pausado, seguir
        if (!p.isPlaying) p.play()
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)

        // ✅ importantísimo: devolver foco a root para que DPAD vuelva a funcionar
        root.requestFocus()
    }

    private fun togglePlayPause() {
        val p = vlcPlayer ?: return
        if (p.isPlaying) {
            p.pause()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        } else {
            p.play()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    private fun seekByMs(delta: Long) {
        val p = vlcPlayer ?: return
        val dur = p.length
        if (dur <= 0) return
        p.time = (p.time + delta).coerceIn(0L, dur)
    }

    private fun seekBarStep(dir: Int) {
        val p = vlcPlayer ?: return
        val dur = p.length
        if (dur <= 0) return

        // 0..1000
        val newProg = (seekBar.progress + dir * SEEK_STEP_BAR).coerceIn(0, 1000)
        seekBar.progress = newProg

        val target = (dur * (newProg / 1000.0)).toLong().coerceIn(0L, dur)

        // ✅ Seek real (video/imagen)
        try {
            p.time = target
        } catch (_: Exception) {}

        // ✅ Preview inmediato para el contador mientras tocás
        previewSeekMs = target
        txtPos.text = formatMs(target)

        isUserSeeking = true
        ui.removeCallbacks(clearPreviewRunnable)
        ui.postDelayed(clearPreviewRunnable, CLEAR_PREVIEW_DELAY)
    }


    private fun applySeekRobust(targetMs: Long, durMs: Long) {
        val p = vlcPlayer ?: return

        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSeekAppliedAt < SEEK_APPLY_DEBOUNCE_MS) {
            // si llegan flechas muy rápido, igual dejamos el pendingSeekMs actualizado y listo
            return
        }
        lastSeekAppliedAt = now

        try {
            // 1) Intento principal: time
            p.time = targetMs

            // 2) Verificación + fallback con position (más confiable en algunos streams)
            ui.postDelayed({
                val pp = vlcPlayer ?: return@postDelayed
                val cur = pp.time
                if (kotlin.math.abs(cur - targetMs) > 1200) {
                    val pos = (targetMs.toDouble() / durMs.toDouble()).toFloat().coerceIn(0f, 1f)
                    try { pp.position = pos } catch (_: Exception) {}
                }
            }, 80)
        } catch (_: Exception) {
            // fallback directo
            val pos = (targetMs.toDouble() / durMs.toDouble()).toFloat().coerceIn(0f, 1f)
            try { p.position = pos } catch (_: Exception) {}
        }
    }




    private val resetSeekingFlagRunnable = Runnable {
        isUserSeeking = false
        scheduleAutoHide()
    }

    private fun initVlc(videoUrl: String) {
        val fixedUrl = when {
            videoUrl.startsWith("http://") || videoUrl.startsWith("https://") -> videoUrl
            videoUrl.startsWith("file://") -> videoUrl
            videoUrl.startsWith("/") -> "file://$videoUrl"
            else -> videoUrl
        }

        Log.d("PlaybackVideoFragment", "VLC url=$fixedUrl")

        libVlc = LibVLC(
            requireContext(),
            arrayListOf(
                "--audio-time-stretch",
                "--no-drop-late-frames",
                "--no-skip-frames",
                "--network-caching=1500",
                "--file-caching=800"
            )
        )

        vlcPlayer = VlcMediaPlayer(libVlc).apply {
            attachViews(videoLayout, null, false, false)
            setEventListener { ev ->
                when (ev.type) {
                    VlcMediaPlayer.Event.Playing -> ui.post {
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                    }
                    VlcMediaPlayer.Event.Paused,
                    VlcMediaPlayer.Event.EndReached -> ui.post {
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    }
                    VlcMediaPlayer.Event.EncounteredError -> ui.post {
                        Toast.makeText(requireContext(), "VLC no pudo reproducir este video.", Toast.LENGTH_LONG).show()
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
            }
        }

        val media = Media(libVlc, Uri.parse(fixedUrl)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":http-reconnect=true")
            addOption(":http-user-agent=WatchOffline")
        }

        vlcPlayer?.media = media
        media.release()

        vlcPlayer?.play()
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun startTicker() {
        stopTicker()

        val r = object : Runnable {
            override fun run() {
                val p = vlcPlayer
                if (p != null) {
                    val dur = p.length

                    // ✅ si el usuario está seekeando con control, mostramos preview
                    val posToShow = previewSeekMs ?: p.time

                    txtPos.text = formatMs(posToShow)
                    txtDur.text = if (dur > 0) formatMs(dur) else "0:00"

                    if (dur > 0) {
                        val prog = ((posToShow.toDouble() / dur.toDouble()) * 1000.0)
                            .toInt()
                            .coerceIn(0, 1000)

                        val focusIsSeek = (root.findFocus() === seekBar)

                        // ✅ no pelear contra el control remoto
                        if (!focusIsSeek && !isUserSeeking) {
                            seekBar.progress = prog
                        }
                    }

                    // Skip Intro visible sólo primeros 15s si hay skip
                    val movie = arguments?.getSerializable("movie") as? Movie
                    val hasSkip = (movie?.skipToSecond ?: 0) > 0
                    skipIntroButton.visibility =
                        if (hasSkip && posToShow < 15_000) View.VISIBLE else View.GONE

                    // si overlay está oculto y skip visible: que el focus vaya al skip
                    if (!controlsVisible && skipIntroButton.visibility == View.VISIBLE) {
                        if (!skipIntroButton.isFocused) skipIntroButton.requestFocus()
                    }
                }

                ui.postDelayed(this, 300)
            }
        }

        ticker = r
        ui.post(r)
    }




    private fun stopTicker() {
        ticker?.let { ui.removeCallbacks(it) }
        ticker = null
    }

    override fun onPause() {
        super.onPause()
        try { vlcPlayer?.pause() } catch (_: Exception) {}
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTicker()
        ui.removeCallbacks(hideControlsRunnable)
        ui.removeCallbacks(resetSeekingFlagRunnable)

        try { vlcPlayer?.stop() } catch (_: Exception) {}
        try { vlcPlayer?.detachViews() } catch (_: Exception) {}
        try { vlcPlayer?.release() } catch (_: Exception) {}
        vlcPlayer = null

        try { libVlc?.release() } catch (_: Exception) {}
        libVlc = null
    }
}
