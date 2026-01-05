
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
        private const val PREFS_NAME = "watchoffline_prefs"
        private const val PREF_LAST_URL = "LAST_PLAYED_VIDEO_URL"
    }

    private val PREF_GLOBAL_AUDIO = "GLOBAL_AUDIO_PREF"
    private val PREF_GLOBAL_SUBS  = "GLOBAL_SUBS_PREF"

    private val TAG = "PlaybackVideoFragment"

    private val END_EPSILON_MS = 1500L // 1.5s antes del final


    private var vlcPlayer: VlcMediaPlayer? = null

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

    private var playlist: List<Movie> = emptyList()
    private var currentIndex: Int = 0
    private var currentMovie: Movie? = null

    private var loopPlaylist: Boolean = false
    private var disableLastPlayed: Boolean = false

    private var btnExitMobile: View? = null
    private var mobileExitEnabled = false

    private val ui = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    // Control de reintentos silenciosos
    private var errorRetryTimestamp: Long = 0
    private val MIN_RETRY_INTERVAL_MS = 2000L

    private var isUserSeeking = false

    private var lastSeekTime = 0L
    private val SEEK_THROTTLE_MS = 250L
    private var controlsVisible = true

    private val SEEK_STEP_MS = 10_000L
    private val SEEK_STEP_BAR = 25
    private val AUTO_HIDE_MS = 10000L
    private val SKIP_WINDOW_MS = 15_000L

    private var previewSeekMs: Long? = null
    private val CLEAR_PREVIEW_DELAY = 650L

    private var endHandled = false

    private val clearPreviewRunnable = Runnable {
        isUserSeeking = false
        previewSeekMs = null
    }

    // Runnable encargado de decirle a VLC que salte de verdad
    private val performSeekRunnable = Runnable {
        previewSeekMs?.let { target ->
            VideoPlayerHolder.mediaPlayer?.time = target
        }
    }

    // Tracks
    private var tracksAppliedForThisMovie = false
    private var spuTracks: Array<TrackDescription> = emptyArray()
    private var audioTracks: Array<TrackDescription> = emptyArray()
    private var currentAudioTrackId: Int = -1
    private var currentSpuTrackId: Int = -1
    private var tracksRefreshTries = 0
    private val MAX_TRACKS_REFRESH_TRIES = 18
    private val TRACKS_REFRESH_DELAY_MS = 650L

    // =========================
    // Helpers (Tracks, Prefs)
    // =========================
    private fun groupScopeKey(): String {
        val group = currentMovie?.studio?.trim().orEmpty()
        return if (group.isNotBlank()) "GROUP::$group" else "GROUP::(none)"
    }

    private fun langToken(scopeKey: String): String {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("LANGCFG_TOKEN::$scopeKey", "0") ?: "0"
    }

    private fun bumpLangToken(scopeKey: String) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val k = "LANGCFG_TOKEN::$scopeKey"
        val cur = prefs.getString(k, "0") ?: "0"
        val next = (cur.toLongOrNull() ?: 0L) + 1L
        prefs.edit().putString(k, next.toString()).apply()
    }

    private fun trackPrefsKey(url: String): String {
        val token = langToken(groupScopeKey())
        return "TRACKS::v=$token::" + url.trim()
    }

    private val trackCache = HashMap<String, Pair<Int, Int>>()

    private fun loadSavedTracks(url: String): Pair<Int, Int>? {
        val k = trackPrefsKey(url)
        trackCache[k]?.let { return it }
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains("$k::audio") && !prefs.contains("$k::spu")) return null
        val a = prefs.getInt("$k::audio", Int.MIN_VALUE)
        val s = prefs.getInt("$k::spu", Int.MIN_VALUE)
        if (a == Int.MIN_VALUE && s == Int.MIN_VALUE) return null
        val pair = Pair(if (a == Int.MIN_VALUE) -1 else a, if (s == Int.MIN_VALUE) -1 else s)
        trackCache[k] = pair
        return pair
    }

    private fun saveTracks(url: String, audioId: Int, spuId: Int) {
        val k = trackPrefsKey(url)
        trackCache[k] = Pair(audioId, spuId)
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("$k::audio", audioId).putInt("$k::spu", spuId).apply()
    }

    private val tracksRefreshRunnable = object : Runnable {
        override fun run() {
            refreshTracks()
            val hasRealSubs = spuTracks.any { it.id != -1 }
            val hasRealAudio = audioTracks.any { it.id != -1 }

            if (!tracksAppliedForThisMovie && (hasRealSubs || hasRealAudio)) {
                tracksAppliedForThisMovie = true
                applySavedOrDefaultTracksForCurrentMovie()
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
        val p = VideoPlayerHolder.mediaPlayer ?: run {
            spuTracks = emptyArray(); audioTracks = emptyArray()
            btnSubtitles.isEnabled = false; btnSubtitles.alpha = 0.35f
            return
        }
        spuTracks = try { p.spuTracks ?: emptyArray() } catch (_: Exception) { emptyArray() }
        audioTracks = try { p.audioTracks ?: emptyArray() } catch (_: Exception) { emptyArray() }
        val hasAny = (audioTracks.count { it.id != -1 } > 0) || spuTracks.isNotEmpty()
        btnSubtitles.isEnabled = hasAny
        btnSubtitles.alpha = if (hasAny) 1f else 0.35f
        if (isTvDevice()) {
            btnSubtitles.isFocusable = hasAny
            btnSubtitles.isFocusableInTouchMode = hasAny
        }
    }

    private fun setSubtitleTrack(trackId: Int) {
        val p = VideoPlayerHolder.mediaPlayer ?: return
        currentSpuTrackId = trackId
        try { p.spuTrack = trackId } catch (_: Exception) {}
    }

    private fun setAudioTrack(trackId: Int) {
        val p = VideoPlayerHolder.mediaPlayer ?: return
        currentAudioTrackId = trackId
        try { p.audioTrack = trackId } catch (_: Exception) {}
    }

    private fun saveGlobalPref(kind: String, value: String) {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(if (kind == "audio") PREF_GLOBAL_AUDIO else PREF_GLOBAL_SUBS, value).apply()
    }

    private fun loadGlobalPref(kind: String): String? {
        val p = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getString(if (kind == "audio") PREF_GLOBAL_AUDIO else PREF_GLOBAL_SUBS, null)
    }

    private fun norm(s: String?) = (s ?: "").trim().lowercase()

    private fun inferLangFromName(name: String): String? {
        val n = norm(name)
        if (n.isBlank()) return null
        if (n.contains("japanese") || n.contains("japon") || n.contains("jpn") || n == "ja") return "ja"
        if (n.contains("english") || n.contains("ingles") || n.contains("inglés") || n.contains("eng") || n == "en") return "en"
        if (n.contains("español") || n.contains("espanol") || n.contains("spanish") || n.contains("castellano") || n.contains("spa") || n == "es") return "es_lat"
        return null
    }

    private fun pickSubIdByPref(pref: String): Int? {
        fun hasAny(words: List<String>, name: String) = words.any { name.contains(it) }
        if (pref == "disable") return -1
        val list = spuTracks.filter { it.id != -1 }
        if (list.isEmpty()) return null
        val want = when (pref) {
            "es_lat" -> listOf("español", "espanol", "spanish", "castellano", "spa", "es")
            "en"     -> listOf("english", "ingles", "inglés", "eng", "en")
            else     -> emptyList()
        }
        if (want.isEmpty()) return null
        return list.firstOrNull { t -> hasAny(want, (t.name ?: "").lowercase()) }?.id
    }

    private fun pickAudioIdByPref(pref: String): Int? {
        fun hasAny(words: List<String>, name: String) = words.any { name.contains(it) }
        val list = audioTracks.filter { it.id != -1 }
        if (list.isEmpty()) return null
        val want = when (pref) {
            "es_lat" -> listOf("español", "espanol", "spanish", "castellano", "lat", "spa", "es")
            "en"     -> listOf("english", "ingles", "inglés", "eng", "en")
            "ja"     -> listOf("japanese", "japones", "japonés", "jpn", "ja")
            else     -> emptyList()
        }
        if (want.isEmpty()) return null
        return list.firstOrNull { t -> hasAny(want, (t.name ?: "").lowercase()) }?.id
    }

    private fun applySavedOrDefaultTracksForCurrentMovie() {
        refreshTracks()
        val audioPref = loadGlobalPref("audio") ?: "keep"
        val subsPref  = loadGlobalPref("subs")  ?: "keep"

        if (audioPref != "keep") {
            pickAudioIdByPref(audioPref)?.let { setAudioTrack(it) }
        }
        when (subsPref) {
            "disable" -> setSubtitleTrack(-1)
            "keep" -> {}
            else -> pickSubIdByPref(subsPref)?.let { setSubtitleTrack(it) } ?: setSubtitleTrack(-1)
        }
    }

    private fun persistLastPlayed(reason: String = "") {
        if (disableLastPlayed) return
        val url = currentMovie?.videoUrl?.trim().orEmpty()
        if (url.isEmpty()) return
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_LAST_URL, url).apply()
        Log.e(TAG, "PERSIST lastUrl=$url reason=$reason")
    }

    private fun persistCurrentTracks(reason: String = "") {
        val url = currentMovie?.videoUrl?.trim().orEmpty()
        val p = VideoPlayerHolder.mediaPlayer ?: return
        if (url.isBlank()) return
        val a = try { p.audioTrack } catch (_: Exception) { currentAudioTrackId }
        val s = try { p.spuTrack } catch (_: Exception) { currentSpuTrackId }
        saveTracks(url, a, s)
    }

    private val hideExitRunnable = Runnable { btnExitMobile?.animate()?.alpha(0f)?.setDuration(150)?.start() }

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
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    override fun onStart() {
        super.onStart()
        // Si ya tenemos un player vivo, reconectamos aquí, que es cuando la UI es visible
        if (VideoPlayerHolder.mediaPlayer != null) {
            Log.d("DEBUG_VLC", "Fragment onStart: Llamando a reconnect...")
            VideoPlayerHolder.reconnect(videoLayout)
        }
        view?.setOnSystemUiVisibilityChangeListener { enterImmersiveMode() }
    }

    private val hideControlsRunnable = Runnable {
        controlsVisible = false
        controlsOverlay.alpha = 0f
        controlsOverlay.visibility = View.GONE
        if (isTvDevice() && skipIntroButton.visibility == View.VISIBLE) skipIntroButton.requestFocus()
        else root.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        Log.d("DEBUG_VLC", " Fragment: onResume")

        // 1. Obtenemos el player desde el Holder (puede ser nulo si aun no cargó)
        val player = VideoPlayerHolder.mediaPlayer

        if (player != null) {
            // 2. Usamos 'player' en lugar de 'mp'
            val attached = player.vlcVout.areViewsAttached()
            Log.d("DEBUG_VLC", " Fragment: onResume check -> ¿Vistas acopladas? $attached")
        } else {
            Log.d("DEBUG_VLC", " Fragment: onResume -> El Player es NULL todavía")
        }

        // ... (el resto de tu código de onResume, si tenías el enterImmersiveMode, etc) ...
        if (!controlsVisible) root.requestFocus()
        enterImmersiveMode()
    }

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
        btnExitMobile = root.findViewById(R.id.btn_exit_mobile)
        btnPrevVideo = root.findViewById(R.id.btn_prev_video)
        btnNextVideo = root.findViewById(R.id.btn_next_video)

        btnPrevVideo.setImageResource(android.R.drawable.ic_media_previous)
        btnNextVideo.setImageResource(android.R.drawable.ic_media_next)
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
            showExitTemporarily()
            if (isTvDevice() && controlsVisible) {
                if (skipIntroButton.visibility == View.VISIBLE) skipIntroButton.requestFocus()
                else btnPlayPause.requestFocus()
            }
        }

        controlsOverlay.bringToFront()
        controlsOverlay.translationZ = 50f
        setupMobileExitButton()
        if (mobileExitEnabled) ui.post { showExitTemporarily() }
        if (isTvDevice()) btnPlayPause.requestFocus()
        scheduleAutoHide()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view
        resolvePlaylistAndIndex()
        tracksAppliedForThisMovie = false
        introSkipDoneForCurrent = false
        lastSkipVisible = false

        val url = currentMovie?.videoUrl
        if (url.isNullOrBlank()) {
            requireActivity().finish()
            return
        }

        loopPlaylist = requireActivity().intent?.getBooleanExtra("EXTRA_LOOP_PLAYLIST", false) == true
        disableLastPlayed = requireActivity().intent?.getBooleanExtra("EXTRA_DISABLE_LAST_PLAYED", false) == true

        persistLastPlayed("enter")
        endHandled = false
        updatePrevNextState()

        initVlc(url)
        startTicker()
        view.keepScreenOn = true
    }

    private fun resolvePlaylistAndIndex() {
        val argMovie = arguments?.getSerializable("movie") as? Movie ?: run {
            playlist = emptyList(); currentIndex = 0; currentMovie = null; return
        }
        val argList = arguments?.getSerializable("playlist") as? ArrayList<Movie>
        val argIndex = arguments?.getInt("index", 0) ?: 0
        playlist = if (!argList.isNullOrEmpty()) argList else listOf(argMovie)
        currentIndex = argIndex.coerceIn(0, playlist.lastIndex)
        currentMovie = playlist[currentIndex]
    }

    private fun setupPrevNextHandlers() {
        btnPrevVideo.setOnClickListener { prevIndex()?.let { playIndex(it); bumpControlsTimeout(); showExitTemporarily() } }
        btnNextVideo.setOnClickListener { nextIndex()?.let { playIndex(it); bumpControlsTimeout(); showExitTemporarily() } }
    }

    private fun updatePrevNextState() {
        val hasPrev = hasPrev(); val hasNext = hasNext()
        btnPrevVideo.isEnabled = hasPrev; btnNextVideo.isEnabled = hasNext
        btnPrevVideo.alpha = if (hasPrev) 1f else 0.35f
        btnNextVideo.alpha = if (hasNext) 1f else 0.35f
        if (isTvDevice()) {
            btnPrevVideo.isFocusable = hasPrev
            btnNextVideo.isFocusable = hasNext
        }
    }

    private fun nextIndex() = if (playlist.size <= 1) null else if (currentIndex < playlist.lastIndex) currentIndex + 1 else if (loopPlaylist) 0 else null
    private fun prevIndex() = if (playlist.size <= 1) null else if (currentIndex > 0) currentIndex - 1 else if (loopPlaylist) playlist.lastIndex else null
    private fun hasNext() = nextIndex() != null
    private fun hasPrev() = prevIndex() != null

    private fun handleVideoEnded() {
        if (endHandled) return
        endHandled = true
        nextIndex()?.let { endHandled = false; playIndex(it) } ?: requireActivity().finish()
    }

    private fun playIndex(newIndex: Int) {
        persistCurrentTracks("beforePlayIndex")
        if (playlist.size <= 1) return
        val idx = if (loopPlaylist) ((newIndex % playlist.size) + playlist.size) % playlist.size else newIndex.coerceIn(0, playlist.lastIndex)
        if (idx == currentIndex) return

        val newMovie = playlist[idx]
        val url = newMovie.videoUrl
        if (url.isNullOrBlank()) return

        previewSeekMs = null
        isUserSeeking = false
        seekBar.progress = 0
        txtPos.text = "0:00"; txtDur.text = "0:00"

        VideoPlayerHolder.release()

        currentIndex = idx
        currentMovie = newMovie
        tracksAppliedForThisMovie = false
        introSkipDoneForCurrent = false
        lastSkipVisible = false
        updatePrevNextState()

        currentSpuTrackId = -1
        spuTracks = emptyArray()
        btnSubtitles.isEnabled = false; btnSubtitles.alpha = 0.35f

        persistLastPlayed("playIndex")
        endHandled = false
        initVlc(url)
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        if (isTvDevice()) btnPlayPause.requestFocus()
    }

    // =========================================================
    // ✅ CICLO DE VIDA
    // =========================================================
    override fun onPause() {
        super.onPause()
        persistCurrentTracks("onPause")
        persistLastPlayed("OnPause")

        // Pausar al salir
        VideoPlayerHolder.pause()
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        persistCurrentTracks("onDestroyView")
        persistLastPlayed()

        ui.removeCallbacks(hideExitRunnable)
        if (mobileExitEnabled) root.setOnTouchListener(null)
        stopTicker()
        ui.removeCallbacks(hideControlsRunnable)
        ui.removeCallbacks(clearPreviewRunnable)
        ui.removeCallbacks(tracksRefreshRunnable)

        // IMPORTANTE: Solo desconectamos vista, no matamos el player
        VideoPlayerHolder.detach()

        view?.keepScreenOn = false
    }

    // =========================================================================
    // ✅ INIT VLC MODIFICADO: USA VideoPlayerHolder.reconnect()
    // =========================================================================
    private fun initVlc(videoUrl: String) {
        val fixedUrl = when {
            videoUrl.startsWith("http") || videoUrl.startsWith("file") -> videoUrl
            videoUrl.startsWith("/") -> "file://$videoUrl"
            else -> videoUrl
        }
        Log.d(TAG, "VLC url=$fixedUrl")

        // 1. REUTILIZACIÓN
        if (VideoPlayerHolder.mediaPlayer != null && VideoPlayerHolder.currentUrl == fixedUrl) {
            Log.d("DEBUG_VLC", "Fragment: Player existe. La reconexión se manejará en onStart.")

            // Solo actualizamos UI
            val isPlaying = VideoPlayerHolder.mediaPlayer?.isPlaying == true
            btnPlayPause.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            setupVlcListeners(VideoPlayerHolder.mediaPlayer!!)
            return
        }

        // 2. CREACIÓN NUEVA
        VideoPlayerHolder.release()
        VideoPlayerHolder.currentUrl = fixedUrl

        val libVlc = LibVLC(requireContext(), arrayListOf(
            "-vvv",
            "--network-caching=600",
            "--live-caching=600",
            "--file-caching=600",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--no-audio-time-stretch",
            "--codec=mediacodec_ndk,all",
            "--avcodec-hw=any", // Deja que VLC elija el método más rápido
            "--deinterlace=0",   // Desactivar post-procesado ahorra tiempo de renderizado en saltos
        ))
        VideoPlayerHolder.libVlc = libVlc

        val mp = VlcMediaPlayer(libVlc)
        VideoPlayerHolder.mediaPlayer = mp

        mp.volume = 100

        // Aquí sí hacemos attach inicial porque estamos creando de cero
        mp.attachViews(videoLayout, null, false, false)
        setupVlcListeners(mp)
        /*
        val media = Media(libVlc, Uri.parse(fixedUrl)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":http-reconnect=true")
            addOption(":http-user-agent=WatchOffline")
        }
         */
        val media = Media(libVlc, Uri.parse(fixedUrl)).apply {
            // Cambiá true, false por false, false solo para probar.
            // Si así funciona, el problema es cómo tu TV maneja el HW Acceleration en pausa.
            setHWDecoderEnabled(true, false)
            addOption(":http-reconnect=true")
        }
        mp.media = media
        media.release()

        mp.play()
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
    }

    private fun setupVlcListeners(mp: VlcMediaPlayer) {
        mp.setEventListener { ev ->
            when (ev.type) {
                VlcMediaPlayer.Event.Playing -> ui.post {
                    btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                    scheduleTracksRefreshLoop(); scheduleAutoHide()
                }
                VlcMediaPlayer.Event.ESAdded, VlcMediaPlayer.Event.ESDeleted, VlcMediaPlayer.Event.ESSelected -> ui.post { refreshSpuTracks() }
                VlcMediaPlayer.Event.Paused -> ui.post { btnPlayPause.setImageResource(android.R.drawable.ic_media_play) }
                VlcMediaPlayer.Event.EndReached -> ui.post {
                    btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    handleVideoEnded()
                }
                VlcMediaPlayer.Event.EncounteredError -> ui.post {
                    val now = System.currentTimeMillis()
                    if (now - errorRetryTimestamp > MIN_RETRY_INTERVAL_MS) {
                        errorRetryTimestamp = now
                        Log.e(TAG, "⚠️ VLC Error silencioso. Reiniciando player...")
                        VideoPlayerHolder.release()
                        currentMovie?.videoUrl?.let { initVlc(it) }
                    }
                }
            }
        }
    }

    private fun formatMs(ms: Long): String {
        val t = (ms/1000).toInt().coerceAtLeast(0)
        val h = t/3600; val m = (t/60)%60; val s = t%60
        return if(h>0) "%d:%02d:%02d".format(h,m,s) else "%d:%02d".format(m,s)
    }

    private fun startTicker() {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                val p = VideoPlayerHolder.mediaPlayer
                if (p != null) {
                    val dur = p.length
                    val pos = if (isUserSeeking) (previewSeekMs ?: p.time) else p.time
                    txtPos.text = formatMs(pos); txtDur.text = if(dur>0) formatMs(dur) else "0:00"
                    if (dur > 0 && !isUserSeeking && root.findFocus() !== seekBar) {
                        seekBar.progress = ((pos.toDouble()/dur.toDouble())*1000.0).toInt().coerceIn(0,1000)
                    }
                    val now = System.currentTimeMillis()
                    val d = (currentMovie?.delaySkip?:0)*1000L; val s = (currentMovie?.skipToSecond?:0)
                    val showSkip = (now >= skipHideUntilMs) && (s > 0) && (pos in d..(d+SKIP_WINDOW_MS))
                    skipIntroButton.visibility = if (showSkip) View.VISIBLE else View.GONE
                }
                ui.postDelayed(this, 300)
            }
        }
        ticker = r; ui.post(r)
    }

    private fun stopTicker() { ticker?.let { ui.removeCallbacks(it) }; ticker = null }

    private fun setupControls() {
        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = true; ui.removeCallbacks(hideControlsRunnable); showExitTemporarily()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val p = VideoPlayerHolder.mediaPlayer ?: run {
                    isUserSeeking = false
                    return
                }

                val dur = p.length
                if (dur <= 0) {
                    isUserSeeking = false
                    return
                }

                val target = (dur * (seekBar.progress / 1000.0)).toLong()

                // 🔴 OVERFLOW → FINAL REAL
                if (target >= dur - END_EPSILON_MS) {
                    seekBar.progress = 1000
                    txtPos.text = formatMs(dur)
                    previewSeekMs = null
                    isUserSeeking = false

                    p.time = dur
                    handleVideoEnded()
                    return
                }

                // Seek normal
                p.time = target
                isUserSeeking = false
                scheduleAutoHide()
                showExitTemporarily()
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
        })

        btnPlayPause.setOnClickListener { togglePlayPause(); bumpControlsTimeout(); showExitTemporarily() }
        btnSeekBack.setOnClickListener { seekByMs(-SEEK_STEP_MS); bumpControlsTimeout(); showExitTemporarily() }
        btnSeekFwd.setOnClickListener { seekByMs(SEEK_STEP_MS); bumpControlsTimeout(); showExitTemporarily() }
        skipIntroButton.setOnClickListener { performSkipIntro(); hideOverlayOnly(); showExitTemporarily() }
    }

    private fun setupSubtitles() {
        btnSubtitles.isEnabled = false; btnSubtitles.alpha = 0.35f
        btnSubtitles.setOnClickListener { bumpControlsTimeout(); showExitTemporarily(); openTracksDialog() }
    }

    private fun refreshSpuTracks() {
        val p = VideoPlayerHolder.mediaPlayer ?: return
        spuTracks = try { p.spuTracks ?: emptyArray() } catch (_: Exception) { emptyArray() }
        val has = spuTracks.isNotEmpty()
        btnSubtitles.isEnabled = has
        btnSubtitles.alpha = if (has) 1f else 0.35f
    }

    private fun openTracksDialog() {
        refreshTracks()
        val audioList = audioTracks.filter { it.id != -1 }
        val subsList = spuTracks.toList()
        if (audioList.isEmpty() && subsList.isEmpty()) return

        val container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 30, 40, 10) }

        if (audioList.isNotEmpty()) {
            container.addView(TextView(requireContext()).apply { text = "Audio"; textSize = 16f; setPadding(0, 0, 0, 10) })
            val lvAudio = ListView(requireContext()).apply { choiceMode = ListView.CHOICE_MODE_SINGLE }
            lvAudio.adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_list_item_single_choice, audioList.mapIndexed { idx, t -> (t.name ?: "").ifBlank { "Audio ${idx+1}" } }.toTypedArray())
            val checkedAudio = audioList.indexOfFirst { it.id == (try{VideoPlayerHolder.mediaPlayer?.audioTrack}catch(_:Exception){currentAudioTrackId}) }.coerceAtLeast(0)
            lvAudio.setItemChecked(checkedAudio, true)
            lvAudio.setOnItemClickListener { _, _, which, _ ->
                val t = audioList[which]
                setAudioTrack(t.id); currentAudioTrackId = t.id
                saveGlobalPref("audio", inferLangFromName(t.name ?: "") ?: "keep")
                bumpLangToken(groupScopeKey())
                persistCurrentTracks("audioPick")
            }
            container.addView(lvAudio)
        }

        if (subsList.isNotEmpty()) {
            container.addView(TextView(requireContext()).apply { text = "Subtítulos"; textSize = 16f; setPadding(0, 25, 0, 10) })
            val lvSubs = ListView(requireContext()).apply { choiceMode = ListView.CHOICE_MODE_SINGLE }
            lvSubs.adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_list_item_single_choice, subsList.map { t -> (t.name ?: "").ifBlank { "Subtítulo ${t.id}" } }.toTypedArray())
            val checkedSubs = subsList.indexOfFirst { it.id == (try{VideoPlayerHolder.mediaPlayer?.spuTrack}catch(_:Exception){currentSpuTrackId}) }.coerceAtLeast(0)
            lvSubs.setItemChecked(checkedSubs, true)
            lvSubs.setOnItemClickListener { _, _, which, _ ->
                val t = subsList[which]
                setSubtitleTrack(t.id); currentSpuTrackId = t.id
                saveGlobalPref("subs", if (t.id == -1) "disable" else (inferLangFromName(t.name ?: "") ?: "keep"))
                bumpLangToken(groupScopeKey())
                persistCurrentTracks("subsPick")
            }
            container.addView(lvSubs)
        }
        AlertDialog.Builder(requireContext()).setTitle("Tracks").setView(container).setPositiveButton("Cerrar", null).show()
    }

    private fun isTvDevice(): Boolean {
        val ctx = requireContext()
        val uiMode = ctx.resources.configuration.uiMode
        val isTV = (uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val pm = ctx.packageManager
        val hasLeanback = pm.hasSystemFeature("android.software.leanback") || pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        return isTV || hasLeanback
    }

    private fun setupMobileExitButton() {
        val btn = btnExitMobile ?: return
        mobileExitEnabled = !isTvDevice()
        if (!mobileExitEnabled) { btn.visibility = View.GONE; return }
        btn.visibility = View.VISIBLE; btn.alpha = 0f; btn.isFocusable = false
        btn.setOnClickListener { persistLastPlayed(); requireActivity().onBackPressedDispatcher.onBackPressed() }
        root.setOnTouchListener { _, ev ->
            if (ev.actionMasked in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE)) showExitTemporarily()
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
                    if (f != null && f.isClickable) { f.performClick(); true }
                    else { togglePlayPause(); true }
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




    private fun toggleControls() { if (controlsVisible) hideOverlayOnly() else showOverlayAndFocusPlay() }

    private fun showOverlayAndFocusPlay() {
        controlsVisible = true
        controlsOverlay.visibility = View.VISIBLE; controlsOverlay.alpha = 1f
        controlsOverlay.bringToFront()
        btnPlayPause.requestFocus()
        scheduleAutoHide()
    }

    private fun hideOverlayOnly() { ui.removeCallbacks(hideControlsRunnable); hideControlsRunnable.run() }
    private fun scheduleAutoHide() { ui.removeCallbacks(hideControlsRunnable); ui.postDelayed(hideControlsRunnable, AUTO_HIDE_MS) }
    private fun bumpControlsTimeout() { if (controlsVisible) scheduleAutoHide() }

    private fun addTvFocusScale(view: View, scale: Float = 1.12f) {
        view.setOnFocusChangeListener { v, hasFocus ->
            if (v === seekBar && hasFocus) ui.removeCallbacks(hideControlsRunnable)
            v.animate().scaleX(if(hasFocus) scale else 1f).scaleY(if(hasFocus) scale else 1f).setDuration(120).start()
        }
    }

    private fun performSkipIntro() {
        val target = ((currentMovie?.delaySkip?:0) + (currentMovie?.skipToSecond?:0)) * 1000L
        if (target <= 0) return
        VideoPlayerHolder.mediaPlayer?.time = target
        skipHideUntilMs = System.currentTimeMillis() + 2000L
        skipIntroButton.visibility = View.GONE
        VideoPlayerHolder.mediaPlayer?.play()
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        root.requestFocus()
    }

    private fun togglePlayPause() {
        val p = VideoPlayerHolder.mediaPlayer ?: return
        if (p.isPlaying) { p.pause(); btnPlayPause.setImageResource(android.R.drawable.ic_media_play) }
        else { p.play(); btnPlayPause.setImageResource(android.R.drawable.ic_media_pause) }
    }
/*
    private fun seekByMs(delta: Long) {
        val p = VideoPlayerHolder.mediaPlayer ?: return
        if (p.length > 0) p.time = (p.time + delta).coerceIn(0L, p.length)
    }
*/

    private fun seekByMs(delta: Long) {
        val p = VideoPlayerHolder.mediaPlayer ?: return
        val dur = p.length
        if (dur <= 0) return

        val target = p.time + delta

        // 🔴 OVERFLOW → FINAL REAL
        if (target >= dur - END_EPSILON_MS) {
            p.time = dur
            handleVideoEnded()
            return
        }

        p.time = target.coerceIn(0L, dur)
    }


    private fun seekBarStep(dir: Int) {
        val p = VideoPlayerHolder.mediaPlayer ?: return
        val dur = p.length
        if (dur <= 0) return

        // 1. Matemáticas de la barra
        val stepSize = 40
        val newProg = (seekBar.progress + dir * stepSize).coerceIn(0, 1000)
        val target = (dur * (newProg / 1000.0)).toLong()

        // 🔴 OVERFLOW → FINAL REAL
        if (target >= dur - END_EPSILON_MS) {
            seekBar.progress = 1000
            txtPos.text = formatMs(dur)
            previewSeekMs = dur
            isUserSeeking = false

            p.time = dur
            handleVideoEnded()
            return
        }


        // 2. ACTUALIZACIÓN UI (Siempre instantánea)
        seekBar.progress = newProg
        txtPos.text = formatMs(target)
        previewSeekMs = target
        isUserSeeking = true

        // 3. CONTROL DE TRÁFICO (Throttling)
        val now = System.currentTimeMillis()
        if (now - lastSeekTime > SEEK_THROTTLE_MS) {
            lastSeekTime = now

            // Aplicamos el salto al motor de VLC
            p.time = target

            // Si está pausado, le damos el "toque" para refrescar
            if (!p.isPlaying) {
                p.play()
                // Un delay muy corto para que no llegue a sonar pero sí a pintar
                ui.postDelayed({
                    if (isUserSeeking) p.pause()
                }, 20)
            }
        }

        // 4. Limpieza final
        ui.removeCallbacks(clearPreviewRunnable)
        ui.postDelayed(clearPreviewRunnable, 1200)
    }

}