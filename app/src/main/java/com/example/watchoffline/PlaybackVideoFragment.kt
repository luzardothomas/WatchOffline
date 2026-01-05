package com.example.watchoffline.vid

import android.app.AlertDialog
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
import org.videolan.libvlc.MediaPlayer.TrackDescription
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer

class PlaybackVideoFragment : Fragment() {

    companion object {

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
    private lateinit var btnSubtitles: ImageButton
    private lateinit var skipIntroButton: Button

    private var lastSkipVisible = false
    private var introSkipDoneForCurrent = false

    private var skipHideUntilMs: Long = 0L

    private lateinit var txtPos: TextView
    private lateinit var txtDur: TextView

    private lateinit var btnPrevVideo: ImageButton
    private lateinit var btnNextVideo: ImageButton

    // ✅ Playlist REAL (solo desde arguments)
    private var playlist: List<Movie> = emptyList()
    private var currentIndex: Int = 0
    private var currentMovie: Movie? = null

    // ✅ SOLO para playlists RANDOM (viene desde MainFragment/DetailsActivity)
    private var loopPlaylist: Boolean = false
    private var disableLastPlayed: Boolean = false

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

    private val SKIP_WINDOW_MS = 15_000L

    private var previewSeekMs: Long? = null
    private val CLEAR_PREVIEW_DELAY = 650L

    // Evita doble ejecución de EndReached / finish en algunos dispositivos
    private var endHandled = false

    private val clearPreviewRunnable = Runnable {
        isUserSeeking = false
        previewSeekMs = null
    }

    // ===== Subtitles (SPU) =====
    private var spuTracks: Array<TrackDescription> = emptyArray()
    private var currentSpuTrackId: Int = -1 // VLC Disable = -1
    private var spuRefreshTries = 0
    private val MAX_SPU_REFRESH_TRIES = 8

    private val spuRefreshRunnable = object : Runnable {
        override fun run() {
            refreshSpuTracks()
            if (spuTracks.isNotEmpty()) return

            spuRefreshTries++
            if (spuRefreshTries >= MAX_SPU_REFRESH_TRIES) return

            ui.postDelayed(this, 600L) // ~4.8s total
        }
    }

    private fun scheduleSpuRefreshLoop() {
        ui.removeCallbacks(spuRefreshRunnable)
        spuRefreshTries = 0
        ui.post(spuRefreshRunnable)
    }

    // ===== Mobile exit: show/hide por alpha =====
    private val hideExitRunnable = Runnable {
        btnExitMobile?.animate()
            ?.alpha(0f)
            ?.setDuration(150)
            ?.start()
    }

    private fun showExitTemporarily(timeoutMs: Long = AUTO_HIDE_MS) {
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
        controlsOverlay.alpha = 0f
        controlsOverlay.visibility = View.GONE

        if (isTvDevice() && skipIntroButton.visibility == View.VISIBLE) {
            skipIntroButton.requestFocus()
        } else {
            root.requestFocus()
        }
    }




    // =========================
    // ✅ Persistencia “último reproducido”
    // =========================
    private fun persistLastPlayed(reason: String = "") {
        if (disableLastPlayed) {
            Log.e(TAG, "PERSIST skipped (disableLastPlayed=true) reason=$reason idx=$currentIndex size=${playlist.size}")
            return
        }

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
        btnSubtitles = root.findViewById(R.id.btn_subtitles)
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
        btnSubtitles.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        setupControls()
        setupPrevNextHandlers()
        setupRemoteHandlers()
        setupSubtitles()

        if (isTvDevice()) {
            addTvFocusScale(btnPrevVideo)
            addTvFocusScale(btnSeekBack)
            addTvFocusScale(btnPlayPause, 1.18f)
            addTvFocusScale(btnSeekFwd)
            addTvFocusScale(btnSubtitles)
            addTvFocusScale(btnNextVideo)
            addTvFocusScale(seekBar, 1.06f)
            addTvFocusScale(skipIntroButton, 1.10f)
        }

        videoLayout.setOnClickListener {
            toggleControls()

            // ✅ Mobile: solo mostrar exit por touch, sin “hover”
            showExitTemporarily()

            // ✅ TV: si querés mantener prioridad de foco
            if (isTvDevice() && controlsVisible) {
                if (skipIntroButton.visibility == View.VISIBLE) {
                    skipIntroButton.requestFocus()
                } else {
                    btnPlayPause.requestFocus()
                }
            }
        }

        controlsOverlay.bringToFront()
        controlsOverlay.translationZ = 50f

        // ✅ solo mobile
        setupMobileExitButton()

        // ✅ MOBILE: mostrar el botón al entrar por primera vez
        if (mobileExitEnabled) {
            ui.post { showExitTemporarily() }
        }

        if (isTvDevice()) btnPlayPause.requestFocus()

        scheduleAutoHide()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        root = view

        val dbgMovie = arguments?.getSerializable("movie") as? Movie
        val dbgList = arguments?.getSerializable("playlist") as? ArrayList<Movie>
        val dbgIndex = arguments?.getInt("index", -1)
        Log.e(TAG, "ARGS movie=${dbgMovie?.videoUrl} playlistSize=${dbgList?.size} index=$dbgIndex")

        resolvePlaylistAndIndex()
        introSkipDoneForCurrent = false
        lastSkipVisible = false

        Log.e(
            TAG,
            "RESOLVED playlistSize=${playlist.size} currentIndex=$currentIndex movie=${currentMovie?.videoUrl}"
        )

        val url = currentMovie?.videoUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(requireContext(), "No se pudo cargar el video", Toast.LENGTH_SHORT).show()
            requireActivity().finish()
            return
        }

        // ✅ flags (solo afectan a RANDOM si el intent los trae)
        loopPlaylist = requireActivity().intent?.getBooleanExtra("EXTRA_LOOP_PLAYLIST", false) == true
        disableLastPlayed = requireActivity().intent?.getBooleanExtra("EXTRA_DISABLE_LAST_PLAYED", false) == true

        Log.e(TAG, "FLAGS loopPlaylist=$loopPlaylist disableLastPlayed=$disableLastPlayed")

        persistLastPlayed("enter")
        endHandled = false
        updatePrevNextState()

        initVlc(url)
        startTicker()

        // Mantener pantalla encendida
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
            val prev = prevIndex() ?: return@setOnClickListener
            playIndex(prev)
            bumpControlsTimeout()
            showExitTemporarily()
        }

        btnNextVideo.setOnClickListener {
            val next = nextIndex() ?: return@setOnClickListener
            playIndex(next)
            bumpControlsTimeout()
            showExitTemporarily()
        }
    }

    private fun updatePrevNextState() {
        val hasPrev = hasPrev()
        val hasNext = hasNext()

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

    private fun nextIndex(): Int? {
        if (playlist.size <= 1) return null
        return if (currentIndex < playlist.lastIndex) currentIndex + 1
        else if (loopPlaylist) 0
        else null
    }

    private fun prevIndex(): Int? {
        if (playlist.size <= 1) return null
        return if (currentIndex > 0) currentIndex - 1
        else if (loopPlaylist) playlist.lastIndex
        else null
    }

    private fun hasNext(): Boolean = nextIndex() != null
    private fun hasPrev(): Boolean = prevIndex() != null

    private fun handleVideoEnded() {
        if (endHandled) return
        endHandled = true

        val next = nextIndex()
        if (next != null) {
            endHandled = false
            playIndex(next) // playIndex persiste el nuevo (si no está deshabilitado)
        } else {
            // ✅ caso normal: termina y sale
            requireActivity().finish()
        }
    }

    private fun playIndex(newIndex: Int) {
        if (playlist.size <= 1) return

        val idx = if (loopPlaylist) {
            val size = playlist.size
            ((newIndex % size) + size) % size
        } else {
            newIndex.coerceIn(0, playlist.lastIndex)
        }

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
        introSkipDoneForCurrent = false
        lastSkipVisible = false
        updatePrevNextState()

        // Subs: por defecto desactivados
        currentSpuTrackId = -1
        spuTracks = emptyArray()
        btnSubtitles.isEnabled = false
        btnSubtitles.alpha = 0.35f

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

    private fun setupSubtitles() {
        btnSubtitles.isEnabled = false
        btnSubtitles.alpha = 0.35f

        btnSubtitles.setOnClickListener {
            bumpControlsTimeout()
            showExitTemporarily()
            openSubtitlesDialog()
        }
    }

    private fun refreshSpuTracks() {
        val p = vlcPlayer ?: run {
            spuTracks = emptyArray()
            btnSubtitles.isEnabled = false
            btnSubtitles.alpha = 0.35f
            return
        }

        val tracks = try { p.spuTracks } catch (_: Exception) { emptyArray() }
        spuTracks = tracks ?: emptyArray()

        val has = spuTracks.isNotEmpty()
        btnSubtitles.isEnabled = has
        btnSubtitles.alpha = if (has) 1f else 0.35f

        if (isTvDevice()) {
            btnSubtitles.isFocusable = has
            btnSubtitles.isFocusableInTouchMode = has
        }
    }

    private fun setSubtitleTrack(trackId: Int) {
        val p = vlcPlayer ?: return
        currentSpuTrackId = trackId
        try { p.spuTrack = trackId } catch (_: Exception) {}
    }

    private fun openSubtitlesDialog() {
        refreshSpuTracks()

        if (spuTracks.isEmpty()) {
            Toast.makeText(requireContext(), "Este video no trae subtítulos.", Toast.LENGTH_SHORT).show()
            return
        }

        // ✅ No agregamos "OFF" manual: VLC ya trae "Disable" como track (normalmente id = -1)
        val names = spuTracks.map { t ->
            val n = (t.name ?: "").trim()
            if (n.isNotEmpty()) n else "Subtítulo ${t.id}"
        }.toTypedArray()

        val ids = spuTracks.map { it.id }.toIntArray()

        val checked = ids.indexOf(currentSpuTrackId).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(requireContext())
            .setTitle("Subtítulos")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                setSubtitleTrack(ids[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun isTvDevice(): Boolean {
        val ctx = requireContext()

        // 1) UI mode (lo "correcto")
        val uiMode = ctx.resources.configuration.uiMode
        val isTelevision =
            (uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
                    android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        // 2) Features (lo "correcto" para Android TV / Leanback)
        val pm = ctx.packageManager
        val hasLeanback =
            pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
                    pm.hasSystemFeature("android.software.leanback")

        // 3) Feature TV (algunos devices sí lo reportan aunque no leanback)
        val hasTelevisionFeature =
            pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION)

        // 4) Heurística para TV boxes que reportan mal:
        //    sin touch + navegación por DPAD ⇒ tratalo como TV
        val cfg = ctx.resources.configuration
        val noTouch = cfg.touchscreen == android.content.res.Configuration.TOUCHSCREEN_NOTOUCH
        val dpad = cfg.navigation == android.content.res.Configuration.NAVIGATION_DPAD

        return isTelevision || hasLeanback || hasTelevisionFeature || (noTouch && dpad)
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

        // ✅ CLAVE: Exit nunca debe robar foco (ni en mobile/no-TV)
        btn.isFocusable = false
        btn.isFocusableInTouchMode = false

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
        // ✅ MOBILE: anular por completo “modo control remoto”
        if (!isTvDevice()) {
            root.setOnKeyListener(null)
            videoLayout.setOnKeyListener(null)
            skipIntroButton.setOnKeyListener(null)
            seekBar.setOnKeyListener(null)

            // Mobile táctil: no queremos foco/hover ni navegación DPAD
            root.isFocusable = false
            root.isFocusableInTouchMode = false

            videoLayout.isFocusable = false
            videoLayout.isFocusableInTouchMode = false

            seekBar.isFocusable = false
            seekBar.isFocusableInTouchMode = false

            skipIntroButton.isFocusable = false
            skipIntroButton.isFocusableInTouchMode = false

            btnPrevVideo.isFocusable = false
            btnPrevVideo.isFocusableInTouchMode = false
            btnSeekBack.isFocusable = false
            btnSeekBack.isFocusableInTouchMode = false
            btnPlayPause.isFocusable = false
            btnPlayPause.isFocusableInTouchMode = false
            btnSeekFwd.isFocusable = false
            btnSeekFwd.isFocusableInTouchMode = false
            btnNextVideo.isFocusable = false
            btnNextVideo.isFocusableInTouchMode = false
            btnSubtitles.isFocusable = false
            btnSubtitles.isFocusableInTouchMode = false

            return
        }

        // =========================
        // ✅ TV: queda igual que lo tenías
        // =========================
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()

        val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            if (controlsVisible) bumpControlsTimeout()

            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    val next = nextIndex()
                    if (next != null) {
                        playIndex(next)
                        bumpControlsTimeout()
                        showExitTemporarily()
                    }
                    return@OnKeyListener true
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    val prev = prevIndex()
                    if (prev != null) {
                        playIndex(prev)
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

        // ✅ SOLO en TV real dejamos que el seekbar bloquee el autohide
        // En Mobile (aunque reciba ENTER/DPAD) queremos que se oculte para no “grisear” el video.
        if (isTvDevice() && focusIsSeek) return

        // (opcional) si el usuario está arrastrando el seekbar, no ocultar
        if (isUserSeeking) return

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
        val skipSec = (currentMovie?.skipToSecond ?: 0)
        val delaySec = (currentMovie?.delaySkip ?: 0)

        val targetSec = delaySec + skipSec
        if (targetSec <= 0) return

        val p = vlcPlayer ?: return

        p.time = targetSec * 1000L

        // evita rebote inmediato
        skipHideUntilMs = System.currentTimeMillis() + 2000L
        skipIntroButton.visibility = View.GONE

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

                        // ✅ Subs OFF por defecto (VLC Disable = -1) + buscar tracks (pueden tardar)
                        setSubtitleTrack(-1)
                        scheduleSpuRefreshLoop()

                        scheduleAutoHide()
                    }

                    // ✅ Cuando VLC detecta/actualiza streams, suelen aparecer subs
                    VlcMediaPlayer.Event.ESAdded,
                    VlcMediaPlayer.Event.ESSelected,
                    VlcMediaPlayer.Event.ESDeleted -> ui.post {
                        refreshSpuTracks()
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

                    val nowMs = System.currentTimeMillis()

                    val skipSec = (currentMovie?.skipToSecond ?: 0)
                    val delaySec = (currentMovie?.delaySkip ?: 0)

                    val hasSkip = skipSec > 0
                    val delayMs = delaySec * 1000L
                    val startMs = delayMs
                    val endMs = delayMs + SKIP_WINDOW_MS

                    val allowShow = nowMs >= skipHideUntilMs
                    val shouldShowSkip = allowShow && hasSkip && (posToShow in startMs..endMs)

                    skipIntroButton.visibility = if (shouldShowSkip) View.VISIBLE else View.GONE

                    // ✅ Regla absoluta: si controles ocultos + skip visible => hover en Skip (TV)
                    if (isTvDevice() && !controlsVisible && skipIntroButton.visibility == View.VISIBLE) {
                        if (!skipIntroButton.isFocused) {
                            ui.post {
                                if (!controlsVisible && skipIntroButton.visibility == View.VISIBLE) {
                                    skipIntroButton.requestFocus()
                                }
                            }
                        }
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
        ui.removeCallbacks(spuRefreshRunnable)

        try { vlcPlayer?.stop() } catch (_: Exception) {}
        try { vlcPlayer?.detachViews() } catch (_: Exception) {}
        try { vlcPlayer?.release() } catch (_: Exception) {}
        vlcPlayer = null

        try { libVlc?.release() } catch (_: Exception) {}
        libVlc = null

        view?.keepScreenOn = false
    }
}
