package com.example.watchoffline

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import java.util.Locale

class LanguageSettingsTvActivity : FragmentActivity() {

    private val TV_TITLE_SP = 26f
    private val TV_HEADER_SP = 17f
    private val TV_ROW_TITLE_SP = 16f
    private val TV_OPTION_SP = 13f
    private val TV_BUTTON_SP = 16f

    companion object {
        private const val PREFS_NAME = "watchoffline_prefs"
        private const val LANG_PREFIX = "LANGCFG::"
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

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(14), dp(10), dp(14))
        }
        scroll.addView(root)

        root.addView(buildTopBar())
        root.addView(buildHeaderRow())

        val rows = buildRowsFromImportedJsons()
        rows.forEach { r ->
            root.addView(buildDataRow(r))
            root.addView(divider())
        }

        setContentView(scroll)
    }

    // -------------------------
    // UI
    // -------------------------

    private fun buildTopBar(): View {
        val bar = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, dp(14))
        }

        val title = TextView(this).apply {
            text = "CONFIGURACIÓN DE IDIOMAS"
            textSize = TV_TITLE_SP
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(20), 0, dp(20), 0)
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

        fun header(text: String, weight: Float): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = TV_HEADER_SP
                alpha = 0.9f
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }.also {
                it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            }
        }

        row.addView(header("TÍTULO", 1.15f))
        row.addView(header("AUDIO", 1.10f))
        row.addView(header("SUBTÍTULOS", 1.25f))

        row.addView(
            TextView(this).apply {
                text = "CAMBIOS"
                textSize = TV_HEADER_SP
                alpha = 0.9f
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            },
            LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )


        return row
    }

    private fun buildDataRow(r: Row): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(16))
        }

        row.addView(
            TextView(this).apply {
                text = r.displayTitle
                textSize = TV_ROW_TITLE_SP
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                maxLines = 2
                ellipsize = null
                setLineSpacing(0f, 1.3f)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )

        row.addView(vDivider())

        row.addView(
            buildRadioGrid(scopeKey = r.scopeKey, kind = "audio", options = audioOptions),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.10f)
        )

        row.addView(vDivider())

        row.addView(
            buildRadioGrid(scopeKey = r.scopeKey, kind = "subs", options = subsOptions),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f)
        )

        row.addView(vDivider())

        val validated = isValidated(r.scopeKey)

        val btnScan = TextView(this).apply {
            text = if (validated) "VALIDADO" else "VALIDAR"
            textSize = TV_BUTTON_SP
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = getDrawable(R.drawable.bg_action_button)
            minWidth = dp(110)

            isClickable = !validated
            isFocusable = !validated
            isFocusableInTouchMode = !validated
            alpha = if (validated) 0.45f else 1f

            setOnClickListener { if (!validated) runScanForRow(r) }
            setOnKeyListener { _, keyCode, event ->
                if (validated) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    runScanForRow(r)
                    true
                } else false
            }

            setOnFocusChangeListener { v, hasFocus ->
                if (validated) return@setOnFocusChangeListener
                v.alpha = if (hasFocus) 1f else 0.9f
                v.scaleX = if (hasFocus) 1.06f else 1f
                v.scaleY = if (hasFocus) 1.06f else 1f
            }
        }

        row.addView(
            btnScan,
            LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(4)
            }
        )

        return row
    }

    private fun runScanForRow(r: Row) {
        val videoUrl = pickRepresentativeUrlForScope(r.scopeKey)
        if (videoUrl.isNullOrBlank()) return

        Toast.makeText(this, "Validando idiomas…", Toast.LENGTH_SHORT).show()

        scanTracksWithVlc(
            videoUrl,
            onDone = { audioTracks, subsTracks ->
                val audioPrefs = inferAudioPrefsFromTrackNames(audioTracks.map { it.name })
                val subsPrefs = inferSubsPrefsFromTrackNames(subsTracks.map { it.name })

                saveAvailablePrefs(r.scopeKey, "audio", audioPrefs)
                saveAvailablePrefs(r.scopeKey, "subs", subsPrefs)

                enforceCurrentSelection(r.scopeKey, "audio")
                enforceCurrentSelection(r.scopeKey, "subs")

                recreate()
            },
            onError = { _ -> }
        )
    }

    // -------------------------
    // RADIO GRID (TV)
    // -------------------------

    private fun buildRadioGrid(scopeKey: String, kind: String, options: List<Opt>): View {

        // ✅ CONTENEDOR PASIVO (NO SCROLLA, NO ARRASTRA)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun prefKey() = "$LANG_PREFIX$scopeKey::$kind"
        val k = prefKey()

        if (loadPref(k) == null) savePref(k, defaultValueFor(kind))

        var selected = loadPref(k)?.trim().orEmpty()
        if (selected.isBlank()) selected = defaultValueFor(kind)

        val available: Set<String>? = loadAvailablePrefs(scopeKey, kind)

        fun isAllowed(key: String): Boolean {
            if (available == null) return true
            if (kind == "subs" && key == "disable") return true
            return available.contains(key)
        }

        if (available != null && !isAllowed(selected)) {
            selected = defaultValueFor(kind)
            savePref(k, selected)
        }

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

                val a = if (allowed) 1f else 0.25f
                dots[idx].alpha = a
                labels[idx].alpha = a
            }
        }

        fun setSelected(requestedKey: String) {
            val fallback = defaultValueFor(kind)
            val finalKey = when {
                available == null -> requestedKey
                kind == "subs" && requestedKey == "disable" -> "disable"
                isAllowed(requestedKey) -> requestedKey
                else -> fallback
            }

            if (selected == finalKey) return
            selected = finalKey
            savePref(k, selected)
            applyState()
        }

        val selectKeyListener = View.OnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) return@OnKeyListener false

            val key = v.tag as? String ?: return@OnKeyListener false
            if (!isAllowed(key) && !(kind == "subs" && key == "disable")) return@OnKeyListener true

            setSelected(key)
            true
        }

        // ✅ ANCHOS FINALES (los que ya comprobaste que funcionan)
        val baseCellWidth = dp(88)
        val disableCellWidth = dp(116)

        options.forEach { opt ->
            val cellW =
                if (kind == "subs" && opt.key == "disable") disableCellWidth
                else baseCellWidth

            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(cellW, LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(dp(2), dp(6), dp(2), dp(6))
            }

            val lbl = TextView(this).apply {
                text = opt.label
                textSize = TV_OPTION_SP
                gravity = Gravity.CENTER
                maxLines = 1
                isSingleLine = true
                ellipsize = null
                isFocusable = false
            }

            val dot = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                    topMargin = dp(10)
                }
                setImageResource(android.R.drawable.radiobutton_off_background)
                isFocusable = true
                isClickable = true
                tag = opt.key
                setOnKeyListener(selectKeyListener)
                setOnClickListener { setSelected(opt.key) }

                setOnFocusChangeListener { v, hasFocus ->
                    v.scaleX = if (hasFocus) 1.12f else 1f
                    v.scaleY = if (hasFocus) 1.12f else 1f
                }
            }

            labels.add(lbl)
            dots.add(dot)

            cell.addView(lbl)
            cell.addView(dot)
            container.addView(cell)
        }

        applyState()
        return container
    }



    // -------------------------
    // Validated
    // -------------------------

    private fun isValidated(scopeKey: String): Boolean {
        return loadAvailablePrefs(scopeKey, "audio") != null
    }

    // -------------------------
    // Dividers
    // -------------------------

    private fun divider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply { topMargin = dp(8) }
            alpha = 0.18f
            setBackgroundColor(0xFF888888.toInt())
        }
    }

    private fun vDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(54)).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            alpha = 0.18f
            setBackgroundColor(0xFF888888.toInt())
        }
    }

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    // -------------------------
    // Prefs
    // -------------------------

    private fun defaultValueFor(kind: String): String = if (kind == "audio") "es_lat" else "disable"

    private fun loadPref(k: String): String? {
        val p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (p.contains(k)) p.getString(k, null) else null
    }

    private fun savePref(k: String, v: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(k, v)
            .apply()
    }

    // -------------------------
    // Data rows (sin cambios)
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
            reSxxExx.find(t)?.let { m -> out.add(m.groupValues[1].toIntOrNull() ?: return@let) }
            reX.find(t)?.let { m -> out.add(m.groupValues[1].toIntOrNull() ?: return@let) }
            reTemporada.find(t)?.let { m -> out.add(m.groupValues[2].toIntOrNull() ?: return@let) }
        }

        return out
    }

    // -------------------------
    // Track availability (sin cambios)
    // -------------------------

    private fun trackListKey(scopeKey: String, kind: String) = "TRACKLIST::$scopeKey::$kind"

    private fun saveAvailablePrefs(scopeKey: String, kind: String, prefs: Set<String>) {
        val k = trackListKey(scopeKey, kind)
        val joined = prefs.joinToString("|")
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
        return out.ifEmpty { null }
    }

    private fun enforceCurrentSelection(scopeKey: String, kind: String) {
        val k = "$LANG_PREFIX$scopeKey::$kind"
        val current = loadPref(k) ?: return
        val available = loadAvailablePrefs(scopeKey, kind) ?: return
        val fallback = defaultValueFor(kind)

        val finalKey = when {
            kind == "subs" && current == "disable" -> "disable"
            available.contains(current) -> current
            else -> fallback
        }

        if (finalKey != current) savePref(k, finalKey)
    }

    // -------------------------
    // VLC scan + infer (sin cambios)
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

                val media = org.videolan.libvlc.Media(lib, android.net.Uri.parse(fixedUrl)).apply {
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

        return out
    }

    private fun inferSubsPrefsFromTrackNames(names: List<String>): Set<String> {
        val out = linkedSetOf<String>()
        out.add("disable")

        val n = names.map { it.lowercase(Locale.ROOT) }

        fun anyContains(vararg keys: String) = n.any { s -> keys.any { k -> s.contains(k) } }

        if (anyContains("español", "espanol", "spanish", "castellano", "spa")) out.add("es_lat")
        if (anyContains("english", "ingles", "inglés", "eng")) out.add("en")

        return out
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
            val seasonTag = parts[1]

            val pack = imported.firstOrNull { it.fileName.trim() == raw.trim() } ?: return null
            val match = pack.videos.firstOrNull { v ->
                detectSeasonFromTitle(v.title)?.equals(seasonTag, ignoreCase = true) == true
            }

            return match?.videoUrl ?: pack.videos.firstOrNull()?.videoUrl
        }

        return null
    }

    private fun detectSeasonFromTitle(title: String): String? {
        val t = title.trim()
        val reSxx = Regex("""\bS(\d{1,2})\b""", RegexOption.IGNORE_CASE)
        val reSxxExx = Regex("""\bS(\d{1,2})\s*E(\d{1,2})\b""", RegexOption.IGNORE_CASE)
        val reX = Regex("""\b(\d{1,2})x(\d{1,2})\b""", RegexOption.IGNORE_CASE)
        val reTemporada = Regex("""\b(temporada|season)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)

        reSxxExx.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return "S" + it.toString().padStart(2, '0') }
        reX.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return "S" + it.toString().padStart(2, '0') }
        reTemporada.find(t)?.groupValues?.getOrNull(2)?.toIntOrNull()?.let { return "S" + it.toString().padStart(2, '0') }
        reSxx.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return "S" + it.toString().padStart(2, '0') }

        return null
    }
}
