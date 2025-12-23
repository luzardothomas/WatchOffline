package com.example.watchoffline.vid

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

    // Controles (inflados por include)
    private lateinit var overlayRoot: View
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSeekBack: ImageButton
    private lateinit var btnSeekFwd: ImageButton
    private lateinit var btnRew: ImageButton
    private lateinit var btnFf: ImageButton
    private lateinit var seekBar: SeekBar

    // Skip Intro (está en fragment_playback_video.xml, NO dentro del overlay)
    private lateinit var btnSkipIntro: Button

    private var libVlc: LibVLC? = null
    private var vlcPlayer: VlcMediaPlayer? = null

    private val ui = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private var isUserSeeking = false
    private var controlsVisible = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = inflater.inflate(R.layout.fragment_playback_video, container, false)

        videoLayout = root.findViewById(R.id.player_view)

        // El include tiene id controls_include, agarramos su root
        val controls = root.findViewById<View>(R.id.controls_include)

        // ⚠️ Ojo: estos IDs están en custom_player_control_view.xml
        overlayRoot = controls.findViewById(R.id.player_controls_root)
        btnRew = controls.findViewById(R.id.btn_rew)
        btnSeekBack = controls.findViewById(R.id.btn_seek_back)
        btnPlayPause = controls.findViewById(R.id.btn_play_pause)
        btnSeekFwd = controls.findViewById(R.id.btn_seek_fwd)
        btnFf = controls.findViewById(R.id.btn_ff)
        seekBar = controls.findViewById(R.id.seek_bar)

        // ⚠️ Este está en fragment_playback_video.xml
        btnSkipIntro = root.findViewById(R.id.skip_intro_button)

        overlayRoot.bringToFront()
        overlayRoot.translationZ = 50f

        setupControls()
        setupRemoteControl()

        // Focus/hover estilo TV
        addTvFocusScale(btnRew)
        addTvFocusScale(btnSeekBack)
        addTvFocusScale(btnPlayPause, 1.18f)
        addTvFocusScale(btnSeekFwd)
        addTvFocusScale(btnFf)
        addTvFocusScale(btnSkipIntro, 1.10f)
        addTvFocusScale(seekBar, 1.06f)

        // Click sobre video: toggle overlay
        videoLayout.setOnClickListener { showControls(!controlsVisible) }

        // Arranca con foco en Play/Pause
        btnPlayPause.requestFocus()

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

    private fun setupRemoteControl() {
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()

        root.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            // Controles ocultos: DPAD controla rápido (sin foco)
            if (!controlsVisible) {
                return@setOnKeyListener when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekByMs(-10_000); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekByMs(10_000); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { showControls(true); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { togglePlayPause(); true }
                    else -> false
                }
            }

            // Controles visibles: dejamos DPAD mover el foco (no consumir flechas)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val focused = root.findFocus()
                    if (focused != null && focused.isClickable) {
                        focused.performClick()
                        true
                    } else {
                        togglePlayPause()
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(false); true }
                else -> false
            }
        }
    }

    private fun showControls(show: Boolean) {
        controlsVisible = show
        overlayRoot.visibility = if (show) View.VISIBLE else View.GONE
        if (show) btnPlayPause.requestFocus()
    }

    private fun addTvFocusScale(view: View, scale: Float = 1.12f) {
        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) scale else 1f)
                .scaleY(if (hasFocus) scale else 1f)
                .setDuration(120)
                .start()
        }
    }

    private fun setupControls() {
        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnSeekBack.setOnClickListener { seekByMs(-10_000) }
        btnSeekFwd.setOnClickListener { seekByMs(10_000) }
        btnRew.setOnClickListener { seekByMs(-30_000) }
        btnFf.setOnClickListener { seekByMs(30_000) }

        // SeekBar 0..1000
        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val p = vlcPlayer ?: run { isUserSeeking = false; return }
                val dur = p.length
                if (dur > 0) {
                    val target = (dur * (seekBar.progress / 1000.0)).toLong()
                    p.time = target
                }
                isUserSeeking = false
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
        })

        btnSkipIntro.setOnClickListener {
            val movie = arguments?.getSerializable("movie") as? Movie
            val skip = movie?.skipToSecond ?: 0
            if (skip > 0) vlcPlayer?.time = skip * 1000L
        }
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

    // ✅ initVlc actualizado + robusto
    private fun initVlc(videoUrl: String) {

        val fixedUrl = when {
            videoUrl.startsWith("http://") || videoUrl.startsWith("https://") -> videoUrl
            videoUrl.startsWith("file://") -> videoUrl
            videoUrl.startsWith("/") -> "file://$videoUrl"
            else -> videoUrl
        }

        Log.d("PlaybackVideoFragment", "VLC url=$fixedUrl (orig=$videoUrl)")

        if (libVlc == null) {
            libVlc = LibVLC(
                requireContext(),
                arrayListOf(
                    "--audio-time-stretch",
                    "--no-drop-late-frames",
                    "--no-skip-frames",
                    "--network-caching=1500",
                    "--file-caching=800",
                    "--live-caching=800",
                    "-vvv"
                )
            )
        }

        if (vlcPlayer == null) {
            vlcPlayer = VlcMediaPlayer(libVlc).apply {
                attachViews(videoLayout, null, false, false)

                setEventListener { event ->
                    when (event.type) {
                        VlcMediaPlayer.Event.Playing -> {
                            Log.d("PlaybackVideoFragment", "VLC: Playing")
                            ui.post { btnPlayPause.setImageResource(android.R.drawable.ic_media_pause) }
                        }
                        VlcMediaPlayer.Event.Paused -> {
                            Log.d("PlaybackVideoFragment", "VLC: Paused")
                            ui.post { btnPlayPause.setImageResource(android.R.drawable.ic_media_play) }
                        }
                        VlcMediaPlayer.Event.EndReached -> {
                            Log.d("PlaybackVideoFragment", "VLC: EndReached")
                            ui.post { btnPlayPause.setImageResource(android.R.drawable.ic_media_play) }
                        }
                        VlcMediaPlayer.Event.EncounteredError -> {
                            Log.e("PlaybackVideoFragment", "VLC: EncounteredError (url=$fixedUrl)")
                            ui.post {
                                Toast.makeText(requireContext(), "VLC no pudo reproducir este video.", Toast.LENGTH_LONG).show()
                                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                            }
                        }
                    }
                }
            }
        } else {
            try { vlcPlayer?.attachViews(videoLayout, null, false, false) } catch (_: Exception) {}
        }

        val media = Media(libVlc, android.net.Uri.parse(fixedUrl)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":http-reconnect=true")
            addOption(":http-user-agent=WatchOffline")
        }

        vlcPlayer?.media = media
        media.release()

        try {
            vlcPlayer?.play()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        } catch (e: Exception) {
            Log.e("PlaybackVideoFragment", "VLC play() failed", e)
            Toast.makeText(requireContext(), "No se pudo iniciar VLC: ${e.message}", Toast.LENGTH_LONG).show()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun startTicker() {
        stopTicker()

        val r = object : Runnable {
            override fun run() {
                val p = vlcPlayer
                if (p != null) {
                    val pos = p.time
                    val dur = p.length

                    if (!isUserSeeking && dur > 0) {
                        val prog = ((pos.toDouble() / dur.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
                        seekBar.progress = prog
                    }

                    // Skip Intro visible sólo primeros 15s
                    val movie = arguments?.getSerializable("movie") as? Movie
                    val hasSkip = (movie?.skipToSecond ?: 0) > 0
                    btnSkipIntro.visibility = if (hasSkip && pos < 15_000) View.VISIBLE else View.GONE
                }

                ui.postDelayed(this, 350)
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

        try { vlcPlayer?.stop() } catch (_: Exception) {}
        try { vlcPlayer?.detachViews() } catch (_: Exception) {}
        try { vlcPlayer?.release() } catch (_: Exception) {}
        vlcPlayer = null

        try { libVlc?.release() } catch (_: Exception) {}
        libVlc = null
    }
}
