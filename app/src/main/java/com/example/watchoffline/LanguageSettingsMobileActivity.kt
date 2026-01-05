package com.example.watchoffline

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import java.util.Locale

class LanguageSettingsMobileActivity : FragmentActivity() {

    companion object {
        private const val PREFS_NAME = "watchoffline_prefs"
        private const val LANG_PREFIX = "LANGCFG::"
    }

    private val TAG = "LangSettingsMobile"

    // ✅ DEBUG: Logcat SIEMPRE + stdout
    private fun dbg(msg: String) {
        val line = "[$TAG] $msg"
        // ASSERT: suele verse incluso con filtros altos
        Log.wtf(TAG, line)
        // fallback: aparece como I/System.out
        System.out.println(line)
    }

    data class Opt(val key: String, val label: String)

    private val audioOptions = listOf(
        Opt("es_lat", "ESPAÑOL"),
        Opt("en", "INGLÉS"),
        Opt("ja", "JAPONÉS"),
    )

    private val subsOptions = listOf(
        Opt("disable", "DESHABILITADO"),
        Opt("es_lat", "ESPAÑOL"),
        Opt("en", "INGLÉS"),
    )

    data class Row(
        val displayTitle: String,
        val scopeKey: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dbg("onCreate() ✅ activity=${this::class.java.name} taskId=$taskId")

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        scroll.addView(root)

        root.addView(buildTopBar())
        root.addView(buildHeaderRow())

        val rows = buildRowsFromImportedJsons()
        dbg("rows.size=${rows.size}")

        rows.forEach { r ->
            root.addView(buildDataRow(r))
            root.addView(divider())
        }

        setContentView(scroll)
    }

    // -------------------------
    // UI
    // -------------------------

    private fun vDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(44)).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            alpha = 0.25f
            setBackgroundColor(0xFF888888.toInt())
        }
    }

    private fun buildTopBar(): View {
        val bar = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, dp(14))
        }

        val btnExit = TextView(this).apply {
            text = "SALIR"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = getDrawable(R.drawable.bg_action_button)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dbg("CLICK SALIR")
                finish()
            }
        }

        bar.addView(
            btnExit,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        )

        val title = TextView(this).apply {
            text = "CONFIGURACIÓN DE IDIOMAS"
            textSize = 20f
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(70), 0, dp(70), 0)
        }

        bar.addView(
            title,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )

        return bar
    }

    private fun buildHeaderRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        fun header(text: String, weight: Float) =
            TextView(this).apply {
                this.text = text
                textSize = 13f
                alpha = 0.9f
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }.also {
                it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            }

        row.addView(header("TÍTULO", 1.15f))
        row.addView(header("AUDIO", 1.15f))
        row.addView(header("SUBTÍTULOS", 1.45f))
        row.addView(header("CAMBIOS", 0f).apply {
            layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        return row
    }

    private fun buildDataRow(r: Row): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        row.addView(TextView(this).apply {
            text = r.displayTitle
            textSize = 14f
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 2
            ellipsize = null
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f))

        row.addView(vDivider())

        row.addView(
            buildRadioGrid(r.scopeKey, "audio", audioOptions),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f)
        )

        row.addView(vDivider())

        row.addView(
            buildRadioGrid(r.scopeKey, "subs", subsOptions),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.45f)
        )

        row.addView(vDivider())

        val validated = isValidated(r.scopeKey)
        dbg("buildDataRow scope=${r.scopeKey} validated=$validated")

        val btnScan = TextView(this).apply {
            text = if (validated) "OK" else "VALIDAR"
            textSize = 12f
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = getDrawable(R.drawable.bg_action_button)

            isClickable = !validated
            isFocusable = !validated
            alpha = if (validated) 0.45f else 1f

            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END

            setOnClickListener {
                dbg("CLICK VALIDAR scope=${r.scopeKey} validated=$validated")
                if (!validated) runScanForRow(r)
            }
        }

        row.addView(
            btnScan,
            LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(8)
            }
        )

        return row
    }

    private fun runScanForRow(r: Row) {
        val videoUrl = pickRepresentativeUrlForScope(r.scopeKey)
        dbg("runScanForRow scope=${r.scopeKey} url=${videoUrl ?: "null"}")
        if (videoUrl.isNullOrBlank()) return

        Toast.makeText(this, "Validando idiomas…", Toast.LENGTH_SHORT).show()

        scanTracksWithVlc(
            videoUrl,
            onDone = { audioTracks, subsTracks ->
                dbg("SCAN DONE scope=${r.scopeKey} audioTracks=${audioTracks.size} subsTracks=${subsTracks.size}")

                val audioPrefs = inferAudioPrefsFromTrackNames(audioTracks.map { it.name })
                val subsPrefs = inferSubsPrefsFromTrackNames(subsTracks.map { it.name })

                dbg("INFER scope=${r.scopeKey} audioPrefs=$audioPrefs subsPrefs=$subsPrefs")

                saveAvailablePrefs(r.scopeKey, "audio", audioPrefs)
                saveAvailablePrefs(r.scopeKey, "subs", subsPrefs)

                enforceCurrentSelection(r.scopeKey, "audio")
                enforceCurrentSelection(r.scopeKey, "subs")

                recreate()
            },
            onError = { err ->
                dbg("SCAN ERROR scope=${r.scopeKey} err=$err")
            }
        )
    }

    private fun enforceCurrentSelection(scopeKey: String, kind: String) {
        val k = "$LANG_PREFIX$scopeKey::$kind"
        val current = loadPref(k) ?: return
        val available = loadAvailablePrefs(scopeKey, kind) ?: return

        fun fallbackKey(): String {
            return if (kind == "subs") "disable" else (available.firstOrNull() ?: current)
        }

        val finalKey = when {
            kind == "subs" && current == "disable" -> "disable"
            available.contains(current) -> current
            else -> fallbackKey()
        }

        dbg("enforce scope=$scopeKey kind=$kind current=$current available=$available -> final=$finalKey")
        if (finalKey != current) savePref(k, finalKey)
    }

    private fun buildRadioGrid(scopeKey: String, kind: String, options: List<Opt>): View {
        val hsv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        hsv.addView(wrap)

        val k = "$LANG_PREFIX$scopeKey::$kind"

        if (loadPref(k) == null) savePref(k, defaultValueFor(kind))

        var selected = loadPref(k)?.trim().orEmpty()
        if (selected.isBlank()) selected = defaultValueFor(kind)

        val available: Set<String>? = loadAvailablePrefs(scopeKey, kind)
        dbg("buildRadioGrid scope=$scopeKey kind=$kind selected=$selected available=${available ?: "NULL"}")

        fun isAllowed(key: String): Boolean {
            // ✅ Si NO está validado: bloquear todo EXCEPTO subs=disable
            if (available == null) return (kind == "subs" && key == "disable")
            if (kind == "subs" && key == "disable") return true
            return available.contains(key)
        }

        if (available != null && !isAllowed(selected)) {
            val newSelected = when {
                kind == "subs" -> "disable"
                else -> available.firstOrNull() ?: selected
            }
            dbg("selected not allowed -> $selected => $newSelected")
            selected = newSelected
            savePref(k, selected)
        }

        val measureTv = TextView(this).apply { textSize = 11f }
        val maxTextPx = options.maxOfOrNull { opt ->
            measureTv.paint.measureText(opt.label)
        } ?: 0f
        val cellWidthPx = (maxTextPx + dp(18)).toInt().coerceAtLeast(dp(78))

        val dots = ArrayList<android.widget.ImageView>(options.size)
        val labels = ArrayList<TextView>(options.size)

        fun applyState() {
            options.forEachIndexed { idx, opt ->
                val allowed = isAllowed(opt.key)
                val on = (opt.key == selected)

                dots[idx].setImageResource(
                    if (on) android.R.drawable.radiobutton_on_background
                    else android.R.drawable.radiobutton_off_background
                )

                val a = if (allowed) 1.0f else 0.25f
                dots[idx].alpha = a
                labels[idx].alpha = a

                dots[idx].isEnabled = allowed
                dots[idx].isClickable = allowed
                labels[idx].isEnabled = allowed
                labels[idx].isClickable = allowed
            }
        }

        fun setSelected(requestedKey: String) {
            dbg("CLICK RADIO scope=$scopeKey kind=$kind requested=$requestedKey")

            if (available == null) {
                val finalKey =
                    if (kind == "subs" && requestedKey == "disable") "disable"
                    else selected

                if (selected == finalKey) {
                    applyState()
                    return
                }

                selected = finalKey
                savePref(k, selected)

                // 🔴 INVALIDAR TRACKS POR URL
                bumpLangToken(scopeKey)

                // 🔁 propagar a toda la serie
                broadcastSelectionToWholeJson(scopeKey, kind, selected)

                recreate()
                return
            }

            val finalKey = when {
                kind == "subs" && requestedKey == "disable" -> "disable"
                isAllowed(requestedKey) -> requestedKey
                else -> selected
            }

            if (selected == finalKey) {
                applyState()
                return
            }

            selected = finalKey
            savePref(k, selected)

            // 🔴 INVALIDAR TRACKS POR URL (CLAVE)
            bumpLangToken(scopeKey)

            // 🔁 aplicar a toda la serie / temporadas
            broadcastSelectionToWholeJson(scopeKey, kind, selected)

            recreate()
        }


        options.forEach { opt ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(4), dp(6), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    cellWidthPx,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val lbl = TextView(this).apply {
                text = opt.label
                textSize = 11f
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                ellipsize = null
                setSingleLine(false)
                maxLines = 2
                setHorizontallyScrolling(false)
                breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val dot = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    topMargin = dp(3)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                setImageResource(android.R.drawable.radiobutton_off_background)
                isClickable = true
                isFocusable = true
                setOnClickListener { setSelected(opt.key) }
            }

            lbl.setOnClickListener { setSelected(opt.key) }

            labels.add(lbl)
            dots.add(dot)

            cell.addView(lbl)
            cell.addView(dot)
            wrap.addView(cell)
        }

        applyState()
        return hsv
    }

    private fun bumpLangToken(scopeKey: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString("LANGCFG_TOKEN::$scopeKey", System.currentTimeMillis().toString()).apply()
    }


    private fun isValidated(scopeKey: String): Boolean {
        val v = loadAvailablePrefs(scopeKey, "audio") != null
        dbg("isValidated scope=$scopeKey -> $v")
        return v
    }

    private fun divider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply { topMargin = dp(6) }
            alpha = 0.18f
            setBackgroundColor(0xFF888888.toInt())
        }
    }

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    // -------------------------
    // Prefs
    // -------------------------

    private fun defaultValueFor(kind: String): String {
        return if (kind == "audio") "es_lat" else "disable"
    }

    private fun loadPref(k: String): String? {
        val p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val v = if (p.contains(k)) p.getString(k, null) else null
        dbg("loadPref key=$k -> ${v ?: "null"}")
        return v
    }

    private fun savePref(k: String, v: String) {
        dbg("savePref key=$k = $v")
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(k, v)
            .apply()
    }

    // -------------------------
    // Propagación a JSON
    // -------------------------

    private fun extractRawFileName(scopeKey: String): String? {
        return when {
            scopeKey.startsWith("GROUP::") -> scopeKey.removePrefix("GROUP::").trim().ifBlank { null }
            scopeKey.startsWith("SEASON::") -> {
                val rest = scopeKey.removePrefix("SEASON::")
                rest.split("::").getOrNull(0)?.trim()?.ifBlank { null }
            }
            else -> null
        }
    }

    private fun buildAllScopeKeysForFile(rawFileName: String): List<String> {
        val dm = JsonDataManager()
        dm.loadData(this)
        val imported = dm.getImportedJsons()
        val pack = imported.firstOrNull { it.fileName.trim() == rawFileName.trim() }
            ?: return listOf("GROUP::$rawFileName")

        val seasons = detectSeasons(pack.videos.map { it.title }).sorted()
        return buildList {
            add("GROUP::$rawFileName")
            seasons.forEach { s ->
                val ss = s.toString().padStart(2, '0')
                add("SEASON::$rawFileName::S$ss")
            }
        }
    }

    private fun pickRepresentativeUrlForScope(scopeKey: String): String? {
        val dm = JsonDataManager()
        dm.loadData(this)
        val imported = dm.getImportedJsons()

        if (scopeKey.startsWith("GROUP::")) {
            val raw = scopeKey.removePrefix("GROUP::")
            val pack = imported.firstOrNull { it.fileName.trim() == raw.trim() } ?: return null
            return pack.videos.firstOrNull { it.videoUrl.isNotBlank() }?.videoUrl
        }

        if (scopeKey.startsWith("SEASON::")) {
            val rest = scopeKey.removePrefix("SEASON::")
            val parts = rest.split("::")
            if (parts.size < 2) return null

            val raw = parts[0]
            val seasonTag = parts[1] // ej "S01"

            val pack = imported.firstOrNull { it.fileName.trim() == raw.trim() } ?: return null
            val match = pack.videos.firstOrNull { v ->
                detectSeasonFromTitle(v.title)?.equals(seasonTag, ignoreCase = true) == true
            }

            return match?.videoUrl ?: pack.videos.firstOrNull { it.videoUrl.isNotBlank() }?.videoUrl
        }

        return null
    }

    private fun detectSeasonFromTitle(title: String): String? {
        val t = title.trim()
        val reSxx = Regex("""\bS(\d{1,2})\b""", RegexOption.IGNORE_CASE)
        val reSxxExx = Regex("""\bS(\d{1,2})\s*E(\d{1,2})\b""", RegexOption.IGNORE_CASE)
        val reX = Regex("""\b(\d{1,2})x(\d{1,2})\b""", RegexOption.IGNORE_CASE)
        val reTemporada = Regex("""\b(temporada|season)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)

        reSxxExx.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return "S" + it.toString().padStart(2, '0') }
        reX.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return "S" + it.toString().padStart(2, '0') }
        reTemporada.find(t)?.groupValues?.getOrNull(2)?.toIntOrNull()
            ?.let { return "S" + it.toString().padStart(2, '0') }
        reSxx.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return "S" + it.toString().padStart(2, '0') }

        return null
    }


    private fun broadcastSelectionToWholeJson(scopeKey: String, kind: String, value: String) {
        val raw = extractRawFileName(scopeKey)
        dbg("broadcast scope=$scopeKey raw=${raw ?: "null"} kind=$kind value=$value")
        if (raw == null) return

        val allScopes = buildAllScopeKeysForFile(raw)
        dbg("broadcast targets: ${allScopes.joinToString(" | ")}")

        allScopes.forEach { sk ->
            val kk = "$LANG_PREFIX$sk::$kind"
            savePref(kk, value)
        }
    }

    // -------------------------
    // Data
    // -------------------------

    private fun buildRowsFromImportedJsons(): List<Row> {
        val dm = JsonDataManager()
        dm.loadData(this)
        val imported = dm.getImportedJsons()

        val rows = mutableListOf<Row>()
        imported.forEach { pack ->
            val rawFileName = pack.fileName.trim()
            val displayBase = prettyTitle(rawFileName)

            rows.add(Row(displayBase, "GROUP::$rawFileName"))

            val seasons = detectSeasons(pack.videos.map { it.title })
            seasons.sorted().forEach { s ->
                val ss = s.toString().padStart(2, '0')
                rows.add(Row("$displayBase S$ss", "SEASON::$rawFileName::S$ss"))
            }
        }

        return rows.sortedBy { it.displayTitle.lowercase(Locale.ROOT) }
    }

    private fun prettyTitle(name: String): String {
        val base = name.trim()
            .replace(Regex("""\.(json|txt|txt\.json|data)$""", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)

        return base.split(' ').joinToString(" ") { w ->
            if (w.isBlank()) "" else w.replaceFirstChar { it.uppercase(Locale.ROOT) }
        }.trim()
    }

    private fun detectSeasons(titles: List<String>): Set<Int> {
        val out = linkedSetOf<Int>()

        val reSxxExx = Regex("""\bS(\d{1,2})\s*E(\d{1,2})\b""", RegexOption.IGNORE_CASE)
        val reX = Regex("""\b(\d{1,2})x(\d{1,2})\b""", RegexOption.IGNORE_CASE)
        val reTemporada = Regex("""\b(temporada|season)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)

        for (t in titles) {
            reSxxExx.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { out.add(it) }
            reX.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { out.add(it) }
            reTemporada.find(t)?.groupValues?.getOrNull(2)?.toIntOrNull()?.let { out.add(it) }
        }

        return out
    }

    // -------------------------
    // Track list prefs
    // -------------------------

    private fun trackListKey(scopeKey: String, kind: String) = "TRACKLIST::$scopeKey::$kind"

    private fun saveAvailablePrefs(scopeKey: String, kind: String, prefs: Set<String>) {
        val k = trackListKey(scopeKey, kind)
        val joined = prefs.joinToString("|")
        dbg("saveAvailablePrefs key=$k joined='$joined'")
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(k, joined)
            .apply()
    }

    private fun loadAvailablePrefs(scopeKey: String, kind: String): Set<String>? {
        val k = trackListKey(scopeKey, kind)
        val p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = p.getString(k, null) ?: return null
        val out = raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val res = out.ifEmpty { null }
        dbg("loadAvailablePrefs key=$k -> ${res ?: "null"} (raw='$raw')")
        return res
    }

    // -------------------------
    // VLC Scan
    // -------------------------

    data class TrackOpt(val id: Int, val name: String)

    private fun scanTracksWithVlc(
        videoUrl: String,
        onDone: (List<TrackOpt>, List<TrackOpt>) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val fixedUrl = when {
                    videoUrl.startsWith("http://") || videoUrl.startsWith("https://") -> videoUrl
                    videoUrl.startsWith("file://") -> videoUrl
                    videoUrl.startsWith("/") -> "file://$videoUrl"
                    else -> videoUrl
                }
                dbg("scanTracksWithVlc fixedUrl=$fixedUrl")

                val lib = org.videolan.libvlc.LibVLC(
                    this,
                    arrayListOf(
                        "--audio-time-stretch",
                        "--no-drop-late-frames",
                        "--no-skip-frames",
                        "--network-caching=1200",
                        "--file-caching=800",
                    )
                )

                val player = org.videolan.libvlc.MediaPlayer(lib)

                val media = org.videolan.libvlc.Media(lib, Uri.parse(fixedUrl)).apply {
                    setHWDecoderEnabled(false, false)
                    addOption(":http-reconnect=true")
                    addOption(":http-user-agent=WatchOffline")
                }
                player.media = media
                media.release()

                player.play()

                var tries = 0
                var audio = emptyArray<org.videolan.libvlc.MediaPlayer.TrackDescription>()
                var subs = emptyArray<org.videolan.libvlc.MediaPlayer.TrackDescription>()

                while (tries < 10) {
                    tries++
                    try { audio = player.audioTracks ?: emptyArray() } catch (_: Exception) {}
                    try { subs = player.spuTracks ?: emptyArray() } catch (_: Exception) {}

                    val hasAudio = audio.any { it.id != -1 }
                    val hasSubs = subs.isNotEmpty()

                    dbg("scan try=$tries hasAudio=$hasAudio audioCount=${audio.size} hasSubs=$hasSubs subsCount=${subs.size}")

                    if (hasAudio || hasSubs) break
                    Thread.sleep(500)
                }

                val audioOpts = audio
                    .filter { it.id != -1 }
                    .map { TrackOpt(it.id, (it.name ?: "Audio ${it.id}").trim()) }

                val subsOpts = buildList {
                    add(TrackOpt(-1, "DISABLE"))
                    subs.filter { it.id != -1 }.forEach {
                        add(TrackOpt(it.id, (it.name ?: "Sub ${it.id}").trim()))
                    }
                }

                try { player.stop() } catch (_: Exception) {}
                try { player.release() } catch (_: Exception) {}
                try { lib.release() } catch (_: Exception) {}

                runOnUiThread { onDone(audioOpts, subsOpts) }
            } catch (e: Exception) {
                runOnUiThread { onError(e.message ?: "Error escaneando tracks") }
            }
        }.start()
    }

    private fun inferAudioPrefsFromTrackNames(names: List<String>): Set<String> {
        val out = linkedSetOf<String>()
        val n = names.map { it.lowercase(Locale.ROOT) }
        fun anyContains(vararg keys: String) = n.any { s -> keys.any { k -> s.contains(k) } }

        if (anyContains("español", "espanol", "spanish", "castellano", "lat")) out.add("es_lat")
        if (anyContains("english", "ingles", "inglés", "eng")) out.add("en")
        if (anyContains("japanese", "japones", "japonés", "jpn")) out.add("ja")
        dbg("inferAudio from=$names -> $out")
        return out
    }

    private fun inferSubsPrefsFromTrackNames(names: List<String>): Set<String> {
        val out = linkedSetOf<String>()
        out.add("disable")

        val n = names.map { it.lowercase(Locale.ROOT) }
        fun anyContains(vararg keys: String) = n.any { s -> keys.any { k -> s.contains(k) } }

        if (anyContains("español", "espanol", "spanish", "castellano", "spa")) out.add("es_lat")
        if (anyContains("english", "ingles", "inglés", "eng")) out.add("en")
        dbg("inferSubs from=$names -> $out")
        return out
    }
}

