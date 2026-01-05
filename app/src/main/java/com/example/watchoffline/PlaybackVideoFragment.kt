package com.example.watchoffline.vid

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
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

    companion object {
        // Para que la Home pueda enfocar el último reproducido (opcional)
        const val EXTRA_LAST_PLAYED_VIDEO_URL = "LAST_PLAYED_VIDEO_URL"

        // SharedPreferences
        private const val PREFS_NAME = "watchoffline_prefs"
        private const val PREF_LAST_URL = "LAST_PLAYED_VIDEO_URL"
    }

    private val TAG = "PlaybackVideoFragment"

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

    private lateinit var btnPrevVideo: ImageButton
    private lateinit var btnNextVideo: ImageButton

    // ✅ Playlist REAL (solo desde arguments)
    private var playlist: List<Movie> = emptyList()
    private var currentIndex: Int = 0
    private var currentMovie: Movie? = null

    // ✅ MOBILE ONLY: botón salir arriba-izquierda
    private var btnExitMobile: View? = null
    private var mobileExitEnabled = false

    private val ui = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private var libVlc: LibVLC? = null
    private var vlcPlayer: VlcMediaPlayer? = null

    private var isUserSeeking = false
    private var controlsVisible = true

    private val SEEK_STEP_MS = 10_000L
    private val SEEK_STEP_BAR = 25
    private val AUTO_HIDE_MS = 10000L

    private var previewSeekMs: Long? = null
    private val CLEAR_PREVIEW_DELAY = 650L

    // Evita doble ejecución de EndReached / finish en algunos dispositivos
    private var endHandled = false

    private val clearPreviewRunnable = Runnable {
        isUserSeeking = false
        previewSeekMs = null
    }

    // ===== Mobile exit: show/hide por alpha =====
    private val hideExitRunnable = Runnable {
        val b = btnExitMobile ?: return@Runnable
        b.animate().alpha(0f).setDuration(150).start()
    }

    private fun showExitTemporarily(timeoutMs: Long = 2500L) {
        if (!mobileExitEnabled) return
        val b = btnExitMobile ?: return

        ui.removeCallbacks(hideExitRunnable)

        if (b.visibility != View.VISIBLE) b.visibility = View.VISIBLE
        b.animate().alpha(1f).setDuration(120).start()

        ui.postDelayed(hideExitRunnable, timeoutMs)
    }

    private fun enterImmersiveMode() {
        requireActivity().window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    override fun onStart() {
        super.onStart()
        view?.setOnSystemUiVisibilityChangeListener { enterImmersiveMode() }
    }

    private val hideControlsRunnable = Runnable {
        controlsVisible = false
        controlsOverlay.visibility = View.GONE
        root.requestFocus()
    }

    // =========================
    // ✅ Persistencia “último reproducido”
    // =========================
    private fun persistLastPlayed(reason: String = "") {
        val url = currentMovie?.videoUrl?.trim().orEmpty()
        if (url.isEmpty()) return

        requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_URL, url)
            .apply()

        Log.e(TAG, "PERSIST lastUrl=$url reason=$reason idx=$currentIndex size=${playlist.size}")
    }

    // =========================

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

        // ✅ mobile exit (si no está en XML, queda null y no pasa nada)
        btnExitMobile = root.findViewById(R.id.btn_exit_mobile)

        // Prev / Next
        btnPrevVideo = root.findViewById(R.id.btn_prev_video)
        btnNextVideo = root.findViewById(R.id.btn_next_video)

        // Apariencia fastbackward / fastforward (sólo icono)
        btnPrevVideo.setImageResource(android.R.drawable.ic_media_rew)
        btnNextVideo.setImageResource(android.R.drawable.ic_media_ff)

        // Evitar “filtro gris molesto”
        btnPrevVideo.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        btnNextVideo.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        btnSeekBack.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        btnPlayPause.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        btnSeekFwd.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        setupControls()
        setupPrevNextHandlers()
        setupRemoteHandlers()

        addTvFocusScale(btnPrevVideo)
        addTvFocusScale(btnSeekBack)
        addTvFocusScale(btnPlayPause, 1.18f)
        addTvFocusScale(btnSeekFwd)
        addTvFocusScale(btnNextVideo)
        addTvFocusScale(seekBar, 1.06f)
        addTvFocusScale(skipIntroButton, 1.10f)

        videoLayout.setOnClickListener {
            toggleControls()
            showExitTemporarily()
        }

        controlsOverlay.bringToFront()
        controlsOverlay.translationZ = 50f

        // ✅ solo mobile
        setupMobileExitButton()

        // ✅ MOBILE: mostrar el botón al entrar por primera vez
        if (mobileExitEnabled) {
            ui.post { showExitTemporarily(2200L) }
        }

        if (isTvDevice()) btnPlayPause.requestFocus()

        scheduleAutoHide()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val dbgMovie = arguments?.getSerializable("movie") as? Movie
        val dbgList = arguments?.getSerializable("playlist") as? ArrayList<Movie>
        val dbgIndex = arguments?.getInt("index", -1)
        Log.e(TAG, "ARGS movie=${dbgMovie?.videoUrl} playlistSize=${dbgList?.size} index=$dbgIndex")

        resolvePlaylistAndIndex()

        Log.e(TAG, "RESOLVED playlistSize=${playlist.size} currentIndex=$currentIndex movie=${currentMovie?.videoUrl}")

        val url = currentMovie?.videoUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(requireContext(), "No se pudo cargar el video", Toast.LENGTH_SHORT).show()
            requireActivity().finish()
            return
        }

        // ✅ al entrar: este es el video actual
        persistLastPlayed("enter")

        endHandled = false
        updatePrevNextState()
        initVlc(url)
        startTicker()

        // Mantener pantalla encendida mientras este fragment esté visible
        view.keepScreenOn = true
    }

    /**
     * ✅ ÚNICA FUENTE DE VERDAD: arguments
     */
    private fun resolvePlaylistAndIndex() {
        val argMovie = arguments?.getSerializable("movie") as? Movie ?: run {
            playlist = emptyList()
            currentIndex = 0
            currentMovie = null
            return
        }

        val argList = arguments?.getSerializable("playlist") as? ArrayList<Movie>
        val argIndex = arguments?.getInt("index", 0) ?: 0

        playlist = if (!argList.isNullOrEmpty()) argList else listOf(argMovie)
        currentIndex = argIndex.coerceIn(0, playlist.lastIndex)
        currentMovie = playlist[currentIndex]
    }

    private fun setupPrevNextHandlers() {
        btnPrevVideo.setOnClickListener {
            if (!btnPrevVideo.isEnabled) return@setOnClickListener
            playIndex(currentIndex - 1)
            bumpControlsTimeout()
            showExitTemporarily()
        }

        btnNextVideo.setOnClickListener {
            if (!btnNextVideo.isEnabled) return@setOnClickListener
            playIndex(currentIndex + 1)
            bumpControlsTimeout()
            showExitTemporarily()
        }
    }

    private fun updatePrevNextState() {
        val hasPrev = playlist.size > 1 && currentIndex > 0
        val hasNext = playlist.size > 1 && currentIndex < playlist.lastIndex

        btnPrevVideo.isEnabled = hasPrev
        btnNextVideo.isEnabled = hasNext
        btnPrevVideo.alpha = if (hasPrev) 1f else 0.35f
        btnNextVideo.alpha = if (hasNext) 1f else 0.35f

        if (isTvDevice()) {
            btnPrevVideo.isFocusable = hasPrev
            btnPrevVideo.isFocusableInTouchMode = hasPrev
            btnNextVideo.isFocusable = hasNext
            btnNextVideo.isFocusableInTouchMode = hasNext
        }
    }

    private fun hasNext(): Boolean = playlist.size > 1 && currentIndex < playlist.lastIndex
    private fun hasPrev(): Boolean = playlist.size > 1 && currentIndex > 0

    private fun handleVideoEnded() {
        if (endHandled) return
        endHandled = true

        if (hasNext()) {
            endHandled = false
            playIndex(currentIndex + 1) // playIndex persiste el nuevo
        } else {
            requireActivity().finish()
        }
    }

    private fun playIndex(newIndex: Int) {
        if (playlist.size <= 1) return
        val idx = newIndex.coerceIn(0, playlist.lastIndex)
        if (idx == currentIndex) return

        val newMovie = playlist[idx]
        val url = newMovie.videoUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(requireContext(), "No se pudo cargar el video", Toast.LENGTH_SHORT).show()
            return
        }

        previewSeekMs = null
        isUserSeeking = false
        seekBar.progress = 0
        txtPos.text = "0:00"
        txtDur.text = "0:00"

        try { vlcPlayer?.stop() } catch (_: Exception) {}
        try { vlcPlayer?.detachViews() } catch (_: Exception) {}
        try { vlcPlayer?.release() } catch (_: Exception) {}
        vlcPlayer = null

        currentIndex = idx
        currentMovie = newMovie
        updatePrevNextState()

        // ✅ ESTE es el “último visto” al volver
        persistLastPlayed("playIndex")

        endHandled = false
        initVlc(url)
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)

        if (isTvDevice()) btnPlayPause.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (!controlsVisible) root.requestFocus()
        enterImmersiveMode()
    }

    private fun setupControls() {
        seekBar.max = 1000

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = true
                ui.removeCallbacks(hideControlsRunnable)
                showExitTemporarily()
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
                showExitTemporarily()
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
        })

        btnPlayPause.setOnClickListener {
            togglePlayPause()
            bumpControlsTimeout()
            showExitTemporarily()
        }

        btnSeekBack.setOnClickListener {
            seekByMs(-SEEK_STEP_MS)
            bumpControlsTimeout()
            showExitTemporarily()
        }

        btnSeekFwd.setOnClickListener {
            seekByMs(SEEK_STEP_MS)
            bumpControlsTimeout()
            showExitTemporarily()
        }

        skipIntroButton.setOnClickListener {
            performSkipIntro()
            hideOverlayOnly()
            showExitTemporarily()
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

    private fun setupMobileExitButton() {
        val btn = btnExitMobile ?: return

        mobileExitEnabled = !isTvDevice()

        if (!mobileExitEnabled) {
            btn.visibility = View.GONE
            btn.alpha = 0f
            btn.isFocusable = false
            btn.isFocusableInTouchMode = false
            btn.setOnClickListener(null)
            ui.removeCallbacks(hideExitRunnable)
            return
        }

        btn.visibility = View.VISIBLE
        btn.alpha = 0f

        btn.setOnClickListener {
            persistLastPlayed()
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        root.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP -> showExitTemporarily()
            }
            false
        }
    }

    private fun setupRemoteHandlers() {
        if (!isTvDevice()) {
            root.setOnKeyListener(null)
            videoLayout.setOnKeyListener(null)
            skipIntroButton.setOnKeyListener(null)
            seekBar.setOnKeyListener(null)

            root.isFocusable = false
            root.isFocusableInTouchMode = false

            videoLayout.isFocusable = false
            videoLayout.isFocusableInTouchMode = false

            skipIntroButton.isFocusable = false
            skipIntroButton.isFocusableInTouchMode = false

            seekBar.isFocusable = false
            seekBar.isFocusableInTouchMode = false
            return
        }

        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()

        val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            if (controlsVisible) bumpControlsTimeout()

            // ✅ Capturar TODOS los típicos del control
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (hasNext()) {
                        playIndex(currentIndex + 1)
                        bumpControlsTimeout()
                        showExitTemporarily()
                    }
                    return@OnKeyListener true
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (hasPrev()) {
                        playIndex(currentIndex - 1)
                        bumpControlsTimeout()
                        showExitTemporarily()
                    }
                    return@OnKeyListener true
                }
            }

            val focused = root.findFocus()

            if (focused === seekBar) {
                return@OnKeyListener when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekBarStep(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBarStep(+1); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
                    KeyEvent.KEYCODE_DPAD_DOWN -> { hideOverlayOnly(); true }
                    else -> false
                }
            }

            if (!controlsVisible) {
                return@OnKeyListener when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { showOverlayAndFocusPlay(); true }
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

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { hideOverlayOnly(); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val f = root.findFocus()
                    if (f != null && f.isClickable) {
                        f.performClick(); true
                    } else {
                        togglePlayPause(); true
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    persistLastPlayed()
                    false
                }
                else -> false
            }
        }

        root.setOnKeyListener(keyListener)

        videoLayout.isFocusable = true
        videoLayout.isFocusableInTouchMode = true
        videoLayout.setOnKeyListener(keyListener)

        skipIntroButton.isFocusable = true
        skipIntroButton.isFocusableInTouchMode = true
        skipIntroButton.setOnKeyListener(keyListener)

        seekBar.isFocusable = true
        seekBar.isFocusableInTouchMode = true
        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (controlsVisible) bumpControlsTimeout()

            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    persistLastPlayed()
                    false
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekBarStep(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBarStep(+1); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
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
        val focusIsSeek = (root.findFocus() === seekBar)
        if (focusIsSeek) return
        ui.postDelayed(hideControlsRunnable, AUTO_HIDE_MS)
    }

    private fun bumpControlsTimeout() {
        if (controlsVisible) scheduleAutoHide()
    }

    private fun addTvFocusScale(view: View, scale: Float = 1.12f) {
        view.setOnFocusChangeListener { v, hasFocus ->
            if (v === seekBar) {
                if (hasFocus) ui.removeCallbacks(hideControlsRunnable)
                else if (controlsVisible) scheduleAutoHide()
            }
            v.animate()
                .scaleX(if (hasFocus) scale else 1f)
                .scaleY(if (hasFocus) scale else 1f)
                .setDuration(120)
                .start()
        }
    }

    private fun performSkipIntro() {
        val skip = currentMovie?.skipToSecond ?: 0
        if (skip <= 0) return

        val p = vlcPlayer ?: return
        p.time = skip * 1000L

        if (!p.isPlaying) p.play()
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)

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

        val newProg = (seekBar.progress + dir * SEEK_STEP_BAR).coerceIn(0, 1000)
        seekBar.progress = newProg

        val target = (dur * (newProg / 1000.0)).toLong().coerceIn(0L, dur)
        try { p.time = target } catch (_: Exception) {}

        previewSeekMs = target
        txtPos.text = formatMs(target)

        isUserSeeking = true
        ui.removeCallbacks(clearPreviewRunnable)
        ui.postDelayed(clearPreviewRunnable, CLEAR_PREVIEW_DELAY)
    }

    private fun initVlc(videoUrl: String) {
        val fixedUrl = when {
            videoUrl.startsWith("http://") || videoUrl.startsWith("https://") -> videoUrl
            videoUrl.startsWith("file://") -> videoUrl
            videoUrl.startsWith("/") -> "file://$videoUrl"
            else -> videoUrl
        }

        Log.d(TAG, "VLC url=$fixedUrl")

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

                    VlcMediaPlayer.Event.Paused -> ui.post {
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    }

                    VlcMediaPlayer.Event.EndReached -> ui.post {
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                        handleVideoEnded()
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
                    val posToShow = previewSeekMs ?: p.time

                    txtPos.text = formatMs(posToShow)
                    txtDur.text = if (dur > 0) formatMs(dur) else "0:00"

                    if (dur > 0) {
                        val prog = ((posToShow.toDouble() / dur.toDouble()) * 1000.0)
                            .toInt()
                            .coerceIn(0, 1000)

                        val focusIsSeek = (root.findFocus() === seekBar)
                        if (!focusIsSeek && !isUserSeeking) {
                            seekBar.progress = prog
                        }
                    }

                    val hasSkip = (currentMovie?.skipToSecond ?: 0) > 0
                    skipIntroButton.visibility =
                        if (hasSkip && posToShow < 15_000) View.VISIBLE else View.GONE

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
        persistLastPlayed("OnPause")
        try { vlcPlayer?.pause() } catch (_: Exception) {}
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
    }

    override fun onDestroyView() {
        super.onDestroyView()

        persistLastPlayed()

        ui.removeCallbacks(hideExitRunnable)
        if (mobileExitEnabled) root.setOnTouchListener(null)
        btnExitMobile = null

        stopTicker()
        ui.removeCallbacks(hideControlsRunnable)
        ui.removeCallbacks(clearPreviewRunnable)

        try { vlcPlayer?.stop() } catch (_: Exception) {}
        try { vlcPlayer?.detachViews() } catch (_: Exception) {}
        try { vlcPlayer?.release() } catch (_: Exception) {}
        vlcPlayer = null

        try { libVlc?.release() } catch (_: Exception) {}
        libVlc = null

        view?.keepScreenOn = false
    }
}
