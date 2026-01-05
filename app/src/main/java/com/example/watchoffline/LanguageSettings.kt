package com.example.watchoffline

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import java.util.Locale

class LanguageSettings : FragmentActivity() {

    companion object {
        private const val PREFS_NAME = "watchoffline_prefs"
        private const val LANG_PREFIX = "LANGCFG::"
    }

    data class Opt(val key: String, val label: String)

    // Opciones (podés sumar más)
    private val audioOptions = listOf(
        Opt("es_lat", "ESPAÑOL LATINO"),
        Opt("en", "INGLÉS"),
        Opt("ja", "JAPONÉS"),
    )

    private val subsOptions = listOf(
        Opt("disable", "DISABLE"),
        Opt("es_lat", "ESPAÑOL LATINO"),
        Opt("en", "INGLÉS"),
    )


    data class Row(
        val displayTitle: String, // lo que se ve (pretty)
        val scopeKey: String      // lo que se guarda (estable)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        scroll.addView(root)

        // ===== Top bar: Volver + título =====
        root.addView(buildTopBar())

        // ===== Header (tabla) =====
        root.addView(buildHeaderRow())

        // ===== Contenido =====
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

        // SALIR (izquierda)
        val btnExit = TextView(this).apply {
            text = "SALIR"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = getDrawable(R.drawable.bg_action_button)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }


        bar.addView(
            btnExit,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        )

        // Título (centro real)
        val title = TextView(this).apply {
            text = "CONFIGURACIÓN DE IDIOMAS"
            textSize = 20f
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            // Evita que el botón “SALIR” lo tape visualmente si el título es largo
            setPadding(dp(70), 0, dp(70), 0)
        }

        bar.addView(
            title,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        )

        return bar
    }



    private fun buildHeaderRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        // TÍTULO
        row.addView(TextView(this).apply {
            text = "TÍTULO"
            textSize = 13f
            alpha = 0.9f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(160), LinearLayout.LayoutParams.WRAP_CONTENT))

        // AUDIO
        row.addView(TextView(this).apply {
            text = "AUDIO"
            textSize = 13f
            alpha = 0.9f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // SUBTÍTULOS
        row.addView(TextView(this).apply {
            text = "SUBTÍTULOS"
            textSize = 13f
            alpha = 0.9f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(TextView(this).apply {
            text = "CAMBIOS"
            textSize = 13f
            alpha = 0.9f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT))


        return row
    }


    private fun buildDataRow(r: Row): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        // TÍTULO
        row.addView(TextView(this).apply {
            text = r.displayTitle
            textSize = 14f
            gravity = Gravity.CENTER                // ✅ centro dentro de su celda
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }, LinearLayout.LayoutParams(dp(160), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL        // ✅ centro vertical respecto a la fila
        })


        // AUDIO
        row.addView(
            buildRadioGrid(scopeKey = r.scopeKey, kind = "audio", options = audioOptions)
                .apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
        )

        // SUBTÍTULOS
        row.addView(
            buildRadioGrid(scopeKey = r.scopeKey, kind = "subs", options = subsOptions)
                .apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
        )

        // ✅ BOTÓN VALIDAR (AL FINAL)

        val validated = isValidated(r.scopeKey)

        val btnScan = TextView(this).apply {
            text = if (validated) "VALIDADO" else "VALIDAR"
            textSize = 12f
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = getDrawable(R.drawable.bg_action_button)
            isClickable = !validated
            isFocusable = !validated
            alpha = if (validated) 0.45f else 1f

            setOnClickListener {
                if (!validated) runScanForRow(r)
            }
        }

        row.addView(
            btnScan,
            LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(8)
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

                // ✅ si lo guardado ya no es válido (ej JAPONES), vuelve a ES_LAT / DISABLE
                enforceCurrentSelection(r.scopeKey, "audio")
                enforceCurrentSelection(r.scopeKey, "subs")

                recreate() // refresca la pantalla completa (simple)
            },
            onError = { _ ->
                // opcional: toast
                // Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun pickRepresentativeUrlForScope(scopeKey: String): String? {
        val dm = JsonDataManager()
        dm.loadData(this)
        val imported = dm.getImportedJsons()

        // scopeKey ejemplo:
        // GROUP::<rawFileName>
        // SEASON::<rawFileName>::S01
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

            return match?.videoUrl ?: pack.videos.firstOrNull()?.videoUrl
        }

        return null
    }

    /** Devuelve "S01", "S02"... o null */
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

    /**
     * “Grid” horizontal: cada opción es una celda vertical:
     *  LABEL
     *  (radio)
     *
     * Alinea perfecto radio debajo del label.
     * Selección única implementada manualmente (simple y confiable).
     */
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
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        hsv.addView(wrap)

        fun prefKey() = "$LANG_PREFIX$scopeKey::$kind"
        val k = prefKey()

        // ✅ si no existe pref guardado, guardamos el default una sola vez
        if (loadPref(k) == null) {
            savePref(k, defaultValueFor(kind)) // audio=es_lat, subs=disable
        }

        var selected = loadPref(k)?.trim().orEmpty()
        if (selected.isBlank()) selected = defaultValueFor(kind)

        // ✅ disponibles reales (si null => todavía no se validó, no bloqueamos nada)
        val available: Set<String>? = loadAvailablePrefs(scopeKey, kind)
        fun isAllowed(key: String): Boolean {
            if (available == null) return true
            if (kind == "subs" && key == "disable") return true
            return available.contains(key)
        }

        // Si ya hay available y lo seleccionado no está permitido → fallback inmediato
        if (available != null && !isAllowed(selected)) {
            selected = defaultValueFor(kind)
            savePref(k, selected)
        }

        // Dots (radio visual)
        val dots = ArrayList<android.widget.ImageView>(options.size)
        val labels = ArrayList<TextView>(options.size)

        fun applyState() {
            options.forEachIndexed { idx, opt ->
                val allowed = isAllowed(opt.key)
                val on = (opt.key == selected)

                // icono on/off
                dots[idx].setImageResource(
                    if (on) android.R.drawable.radiobutton_on_background
                    else android.R.drawable.radiobutton_off_background
                )

                // look "apagado" si está bloqueado
                val a = if (allowed) 1.0f else 0.25f
                dots[idx].alpha = a
                labels[idx].alpha = a

                // bloquear click si no está permitido
                dots[idx].isEnabled = allowed
                dots[idx].isClickable = allowed
                labels[idx].isEnabled = allowed
                labels[idx].isClickable = allowed
            }
        }

        fun setSelected(requestedKey: String) {
            val fallback = defaultValueFor(kind)

            val finalKey = when {
                available == null -> requestedKey                // no validado todavía, dejalo elegir
                kind == "subs" && requestedKey == "disable" -> "disable"
                isAllowed(requestedKey) -> requestedKey
                else -> fallback                                 // ✅ si no existe, vuelve al default
            }

            if (selected == finalKey) {
                applyState()
                return
            }

            selected = finalKey
            savePref(k, selected)
            applyState()
        }

        options.forEach { opt ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }

            val lbl = TextView(this).apply {
                text = opt.label
                textSize = 12f
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }

            val dot = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    topMargin = dp(6)
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

        // ✅ asegurar estado inicial (incluye bloqueo visual)
        applyState()

        return hsv
    }

    private fun isValidated(scopeKey: String): Boolean {
        return loadAvailablePrefs(scopeKey, "audio") != null
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
        return if (p.contains(k)) p.getString(k, null) else null
    }

    private fun savePref(k: String, v: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(k, v)
            .apply()
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

            // GROUP (todo el JSON)
            rows.add(Row(displayBase, "GROUP::$rawFileName"))

            // SEASONS detectadas por títulos
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

    private fun trackListKey(scopeKey: String, kind: String) = "TRACKLIST::$scopeKey::$kind"

    /**
     * Guarda qué prefs están realmente disponibles para este scope.
     * Ej: audio: ["es_lat","en"]  subs: ["disable","es_lat"]
     */
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


    data class TrackOpt(val id: Int, val name: String)

    private fun scanTracksWithVlc(videoUrl: String, onDone: (List<TrackOpt>, List<TrackOpt>) -> Unit, onError: (String) -> Unit) {
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

                // esperar tracks con reintentos (máx ~5s)
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

                runOnUiThread {
                    onDone(audioOpts, subsOpts)
                }
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
        out.add("disable") // siempre existe la opción disable

        val n = names.map { it.lowercase(Locale.ROOT) }

        fun anyContains(vararg keys: String) = n.any { s -> keys.any { k -> s.contains(k) } }

        if (anyContains("español", "espanol", "spanish", "castellano", "spa")) out.add("es_lat")
        if (anyContains("english", "ingles", "inglés", "eng")) out.add("en")

        return out
    }
}
