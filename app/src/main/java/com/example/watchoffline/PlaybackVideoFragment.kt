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
import android.widget.LinearLayout
import android.widget.ListView
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

    // Tracks

    private var tracksAppliedForThisMovie = false

    private var spuTracks: Array<TrackDescription> = emptyArray()
    private var audioTracks: Array<TrackDescription> = emptyArray()
    private var currentAudioTrackId: Int = -1 // VLC suele usar -1 como "default" en audio (depende stream)
    private var currentSpuTrackId: Int = -1   // VLC Disable = -1
    private var spuRefreshTries = 0
    private var tracksRefreshTries = 0
    private val MAX_TRACKS_REFRESH_TRIES = 18
    private val TRACKS_REFRESH_DELAY_MS = 650L

    // Cache en RAM (rápido) + persistencia en prefs (sobrevive app)
    private val trackCache = HashMap<String, Pair<Int, Int>>() // key -> (audioId, spuId)


    private val tracksRefreshRunnable = object : Runnable {
        override fun run() {
            refreshTracks()

            val hasRealSubs = spuTracks.any { it.id != -1 }
            val hasRealAudio = audioTracks.any { it.id != -1 }

            // ✅ apenas hay tracks reales, aplico saved/default UNA vez
            if (!tracksAppliedForThisMovie && (hasRealSubs || hasRealAudio)) {
                tracksAppliedForThisMovie = true
                applySavedOrDefaultTracksForCurrentMovie()
                // opcional: persistir lo que quedó aplicado
                persistCurrentTracks("afterApply")
            }

            tracksRefreshTries++
            if ((hasRealSubs || hasRealAudio) || tracksRefreshTries >= MAX_TRACKS_REFRESH_TRIES) return

            ui.postDelayed(this, TRACKS_REFRESH_DELAY_MS)
        }
    }


    private fun scheduleTracksRefreshLoop() {
        ui.removeCallbacks(tracksRefreshRunnable)
        tracksRefreshTries = 0
        ui.post(tracksRefreshRunnable)
    }

    private fun refreshTracks() {
        val p = vlcPlayer ?: run {
            spuTracks = emptyArray()
            audioTracks = emptyArray()
            btnSubtitles.isEnabled = false
            btnSubtitles.alpha = 0.35f
            return
        }

        spuTracks = try { p.spuTracks ?: emptyArray() } catch (_: Exception) { emptyArray() }
        audioTracks = try { p.audioTracks ?: emptyArray() } catch (_: Exception) { emptyArray() }

        // Audio sin Disable
        val audioRealCount = audioTracks.count { it.id != -1 }
        // Subs puede ser solo Disable al principio, igual sirve mostrarlo (OFF)
        val hasAny = (audioRealCount > 0) || spuTracks.isNotEmpty()

        btnSubtitles.isEnabled = hasAny
        btnSubtitles.alpha = if (hasAny) 1f else 0.35f

        if (isTvDevice()) {
            btnSubtitles.isFocusable = hasAny
            btnSubtitles.isFocusableInTouchMode = hasAny
        }
    }


    private fun setSubtitleTrack(trackId: Int) {
        val p = vlcPlayer ?: return
        currentSpuTrackId = trackId
        try { p.spuTrack = trackId } catch (_: Exception) {}
    }

    private fun setAudioTrack(trackId: Int) {
        val p = vlcPlayer ?: return
        currentAudioTrackId = trackId
        try { p.audioTrack = trackId } catch (_: Exception) {}
    }



    private fun trackPrefsKey(url: String) = "TRACKS::" + url.trim()

    private fun loadSavedTracks(url: String): Pair<Int, Int>? {
        val k = trackPrefsKey(url)
        trackCache[k]?.let { return it }

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains("$k::audio") && !prefs.contains("$k::spu")) return null

        val a = prefs.getInt("$k::audio", Int.MIN_VALUE)
        val s = prefs.getInt("$k::spu", Int.MIN_VALUE)
        if (a == Int.MIN_VALUE && s == Int.MIN_VALUE) return null

        val pair = Pair(
            if (a == Int.MIN_VALUE) -1 else a,
            if (s == Int.MIN_VALUE) -1 else s
        )
        trackCache[k] = pair
        return pair
    }

    private fun saveTracks(url: String, audioId: Int, spuId: Int) {
        val k = trackPrefsKey(url)
        trackCache[k] = Pair(audioId, spuId)

        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("$k::audio", audioId)
            .putInt("$k::spu", spuId)
            .apply()
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

    private fun persistCurrentTracks(reason: String = "") {
        val url = currentMovie?.videoUrl?.trim().orEmpty()
        val p = vlcPlayer ?: return
        if (url.isBlank()) return

        val a = try { p.audioTrack } catch (_: Exception) { currentAudioTrackId }
        val s = try { p.spuTrack } catch (_: Exception) { currentSpuTrackId }

        saveTracks(url, a, s)
        Log.d(TAG, "TRACKS persisted reason=$reason url=$url audio=$a spu=$s")
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
        tracksAppliedForThisMovie = false
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
        persistCurrentTracks("beforePlayIndex")
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
        tracksAppliedForThisMovie = false
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

    private fun looksSpanish(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.contains("español") ||
                n.contains("espanol") ||
                n.contains("spanish") ||
                n.contains("castellano") ||
                n.contains("spa") ||          // a veces viene como "spa"
                n.contains("es-") || n.contains("[es]") || n == "es"
    }

    private fun pickSpanishTrackId(tracks: Array<TrackDescription>): Int? {
        // 1) match por nombre
        tracks.firstOrNull { t -> looksSpanish(t.name ?: "") }?.let { return it.id }

        // 2) fallback: si hay algo que diga "default"
        tracks.firstOrNull { t -> (t.name ?: "").lowercase().contains("default") }?.let { return it.id }

        return null
    }

    private fun langPref(scopeKey: String, kind: String): String? {
        val k = "LANGCFG::$scopeKey::$kind"
        val p = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (p.contains(k)) p.getString(k, null) else null
    }

    private fun detectSeasonFromTitle(title: String?): String? {
        if (title.isNullOrBlank()) return null

        Regex("""\bS(\d{1,2})\s*E(\d{1,2})\b""", RegexOption.IGNORE_CASE)
            .find(title)?.let { m ->
                val s = m.groupValues[1].toIntOrNull() ?: return null
                return "S" + s.toString().padStart(2, '0')
            }

        Regex("""\b(\d{1,2})x(\d{1,2})\b""", RegexOption.IGNORE_CASE)
            .find(title)?.let { m ->
                val s = m.groupValues[1].toIntOrNull() ?: return null
                return "S" + s.toString().padStart(2, '0')
            }

        Regex("""\b(temporada|season)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
            .find(title)?.let { m ->
                val s = m.groupValues[2].toIntOrNull() ?: return null
                return "S" + s.toString().padStart(2, '0')
            }

        return null
    }

    /**
     * Llamar cuando arranca el playback:
     * - si hay guardado por URL => aplicar
     * - si no, elegir español si existe (audio y subs)
     * - y guardar
     */
    private fun applySavedOrDefaultTracksForCurrentMovie() {
        val url = currentMovie?.videoUrl?.trim().orEmpty()
        if (url.isBlank()) return

        refreshTracks()

        // 1️⃣ Si hay algo guardado por URL → respetarlo (máxima prioridad)
        val saved = loadSavedTracks(url)
        if (saved != null) {
            val (a, s) = saved

            // Audio: solo si existe
            if (audioTracks.any { it.id == a }) {
                setAudioTrack(a)
                currentAudioTrackId = a
            }

            // Subs: puede ser -1 (OFF)
            if (s == -1 || spuTracks.any { it.id == s }) {
                setSubtitleTrack(s)
                currentSpuTrackId = s
            }
            return
        }

        // 2️⃣ LanguageSettings scopes (SEASON / GROUP)
        val group = currentMovie?.studio?.trim().orEmpty()   // viene de Movie.studio (fileName)
        val season = detectSeasonFromTitle(currentMovie?.title)

        fun langPref(scopeKey: String, kind: String): String? {
            val k = "LANGCFG::$scopeKey::$kind"
            val p = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return if (p.contains(k)) p.getString(k, null) else null
        }

        // ✅ limpiar valores viejos por compat (si quedó "default" de builds anteriores)
        fun sanitizePref(kind: String, v: String?): String? {
            if (v == null) return null
            if (v == "default") return if (kind == "audio") "es_lat" else "disable"
            return v
        }

        fun pickAudioIdByPref(pref: String): Int? {
            fun hasAny(words: List<String>, name: String) = words.any { name.contains(it) }

            val list = audioTracks.filter { it.id != -1 }
            if (list.isEmpty()) return null

            val want = when (pref) {
                "es_lat" -> listOf("español", "espanol", "spanish", "castellano", "lat", "spa", "es")
                "en" -> listOf("english", "ingles", "inglés", "eng", "en")
                "ja" -> listOf("japanese", "japones", "japonés", "jpn", "ja")
                else -> emptyList()
            }

            if (want.isEmpty()) return null

            return list.firstOrNull { t ->
                hasAny(want, (t.name ?: "").lowercase())
            }?.id
        }

        fun pickSubIdByPref(pref: String): Int? {
            fun hasAny(words: List<String>, name: String) = words.any { name.contains(it) }

            if (pref == "disable") return -1

            val list = spuTracks.filter { it.id != -1 }
            if (list.isEmpty()) return null

            val want = when (pref) {
                "es_lat" -> listOf("español", "espanol", "spanish", "castellano", "spa", "es")
                "en" -> listOf("english", "ingles", "inglés", "eng", "en")
                else -> emptyList()
            }

            if (want.isEmpty()) return null

            return list.firstOrNull { t ->
                hasAny(want, (t.name ?: "").lowercase())
            }?.id
        }

        // leer prefs (prioridad: SEASON > GROUP)
        var audioPref: String? = null
        var subsPref: String? = null

        if (group.isNotBlank()) {
            if (season != null) {
                audioPref = sanitizePref("audio", langPref("SEASON::$group::$season", "audio"))
                subsPref = sanitizePref("subs", langPref("SEASON::$group::$season", "subs"))
            }
            if (audioPref == null) audioPref = sanitizePref("audio", langPref("GROUP::$group", "audio"))
            if (subsPref == null) subsPref = sanitizePref("subs", langPref("GROUP::$group", "subs"))
        }

        var appliedSomething = false

        // ✅ AUDIO:
        // pref elegido -> si no existe -> Español Latino -> primer audio real (fallback final)
        audioPref?.let { pref ->
            val wantId = pickAudioIdByPref(pref)

            val finalId =
                if (wantId != null && audioTracks.any { it.id == wantId }) {
                    wantId
                } else {
                    // ✅ fallback si NO existe lo pedido (ej japonés)
                    pickAudioIdByPref("es_lat")
                        ?: audioTracks.firstOrNull { it.id != -1 }?.id
                }

            if (finalId != null && audioTracks.any { it.id == finalId }) {
                setAudioTrack(finalId)
                currentAudioTrackId = finalId
                appliedSomething = true
            }
        }

        // ✅ SUBS:
        // disable siempre -1
        // si idioma no existe -> -1
        subsPref?.let { pref ->
            val id =
                if (pref == "disable") -1
                else pickSubIdByPref(pref) ?: -1

            if (id == -1 || spuTracks.any { it.id == id }) {
                setSubtitleTrack(id)
                currentSpuTrackId = id
                appliedSomething = true
            }
        }

        // si aplicó algo por scopes, guardarlo por URL (sticky por episodio) y salir
        if (appliedSomething) {
            saveTracks(url, currentAudioTrackId, currentSpuTrackId)
            return
        }

        // 3️⃣ Defaults reales:
        // Audio: Español Latino si existe, sino primer audio real
        // Subs: DISABLE
        val defaultAudio =
            pickSpanishTrackId(audioTracks)
                ?: audioTracks.firstOrNull { it.id != -1 }?.id
                ?: -1

        val defaultSpu = -1 // DISABLE

        if (defaultAudio != -1) {
            setAudioTrack(defaultAudio)
            currentAudioTrackId = defaultAudio
        }

        setSubtitleTrack(defaultSpu)
        currentSpuTrackId = defaultSpu

        saveTracks(url, currentAudioTrackId, currentSpuTrackId)
    }



    private fun setupSubtitles() {
        btnSubtitles.isEnabled = false
        btnSubtitles.alpha = 0.35f

        btnSubtitles.setOnClickListener {
            bumpControlsTimeout()
            showExitTemporarily()
            openTracksDialog()
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

    private fun openTracksDialog() {
        refreshTracks()

        val p = vlcPlayer
        if (p == null) return

        // Audio: filtramos Disable (-1)
        val audioList = audioTracks.filter { it.id != -1 }
        // Subs: dejamos todo (incluye Disable -1)
        val subsList = spuTracks.toList()

        if (audioList.isEmpty() && subsList.isEmpty()) {
            // sin toast como pediste: simplemente no hacemos nada
            return
        }

        fun trackName(t: TrackDescription, fallback: String): String {
            val n = (t.name ?: "").trim()
            return if (n.isNotEmpty()) n else fallback
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 10)
        }

        // ---- AUDIO ----
        if (audioList.isNotEmpty()) {
            val title = TextView(requireContext()).apply {
                text = "Audio"
                textSize = 16f
                setPadding(0, 0, 0, 10)
            }
            container.addView(title)

            val lvAudio = ListView(requireContext()).apply {
                choiceMode = ListView.CHOICE_MODE_SINGLE
            }

            val audioNames = audioList.mapIndexed { idx, t ->
                trackName(t, "Audio ${idx + 1}")
            }.toTypedArray()

            lvAudio.adapter = android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_single_choice,
                audioNames
            )

            // selección actual (si VLC da -1, elegimos 0)
            val currentAudio = try { p.audioTrack } catch (_: Exception) { currentAudioTrackId }
            val checkedAudio = audioList.indexOfFirst { it.id == currentAudio }.let { if (it >= 0) it else 0 }
            lvAudio.setItemChecked(checkedAudio, true)

            lvAudio.setOnItemClickListener { _, _, which, _ ->
                val id = audioList[which].id
                setAudioTrack(id)
                currentAudioTrackId = id

                val url = currentMovie?.videoUrl?.trim().orEmpty()
                saveTracks(url, id, currentSpuTrackId)
            }


            container.addView(lvAudio)
        }

        // ---- SUBTÍTULOS ----
        if (subsList.isNotEmpty()) {
            val title = TextView(requireContext()).apply {
                text = "Subtítulos"
                textSize = 16f
                setPadding(0, 25, 0, 10)
            }
            container.addView(title)

            val lvSubs = ListView(requireContext()).apply {
                choiceMode = ListView.CHOICE_MODE_SINGLE
            }

            val subsNames = subsList.map { t ->
                // VLC ya trae "Disable" normalmente
                trackName(t, "Subtítulo ${t.id}")
            }.toTypedArray()

            lvSubs.adapter = android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_single_choice,
                subsNames
            )

            val currentSpu = try { p.spuTrack } catch (_: Exception) { currentSpuTrackId }
            val checkedSubs = subsList.indexOfFirst { it.id == currentSpu }.let { if (it >= 0) it else 0 }
            lvSubs.setItemChecked(checkedSubs, true)

            lvSubs.setOnItemClickListener { _, _, which, _ ->
                val id = subsList[which].id
                setSubtitleTrack(id)
                currentSpuTrackId = id

                val url = currentMovie?.videoUrl?.trim().orEmpty()
                saveTracks(url, currentAudioTrackId, id)
            }


            container.addView(lvSubs)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Tracks")
            .setView(container)
            .setPositiveButton("Cerrar", null)  // ✅ único modo de cerrar
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
                        scheduleTracksRefreshLoop()
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
        persistCurrentTracks("onPause")
        persistLastPlayed("OnPause")
        try { vlcPlayer?.pause() } catch (_: Exception) {}
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        persistCurrentTracks("onDestroyView")
        persistLastPlayed()

        ui.removeCallbacks(hideExitRunnable)
        if (mobileExitEnabled) root.setOnTouchListener(null)
        btnExitMobile = null

        stopTicker()
        ui.removeCallbacks(hideControlsRunnable)
        ui.removeCallbacks(clearPreviewRunnable)
        ui.removeCallbacks(tracksRefreshRunnable)

        try { vlcPlayer?.stop() } catch (_: Exception) {}
        try { vlcPlayer?.detachViews() } catch (_: Exception) {}
        try { vlcPlayer?.release() } catch (_: Exception) {}
        vlcPlayer = null

        try { libVlc?.release() } catch (_: Exception) {}
        libVlc = null

        view?.keepScreenOn = false
    }
}
