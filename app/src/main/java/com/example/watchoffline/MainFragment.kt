package com.example.watchoffline

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

class MainFragment : BrowseSupportFragment() {

    private val mHandler = Handler(Looper.getMainLooper())
    private lateinit var mBackgroundManager: BackgroundManager
    private var mDefaultBackground: Drawable? = null
    private lateinit var mMetrics: DisplayMetrics
    private var mBackgroundTimer: Timer? = null
    private var mBackgroundUri: String? = null

    private val REQUEST_CODE_IMPORT_JSON = 1001
    private val jsonDataManager = JsonDataManager()

    private val coverCache = mutableMapOf<String, ApiCover?>()


    // ✅ SMB
    private lateinit var smbGateway: SmbGateway

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        Log.i(TAG, "onCreate")

        jsonDataManager.loadData(requireContext())

        smbGateway = SmbGateway(requireContext())
        val ok = smbGateway.ensureProxyStarted(8081)
        Log.e(TAG, "SMB proxy started? $ok port=${smbGateway.getProxyPort()}")

        prepareBackgroundManager()
        resetBackgroundToDefault()
        setupUIElements()
        loadRows()
        setupEventListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        mBackgroundTimer?.cancel()
        try { smbGateway.stopDiscovery() } catch (_: Exception) {}
        try { smbGateway.stopProxy() } catch (_: Exception) {}
    }

    private fun prepareBackgroundManager() {
        mBackgroundManager = BackgroundManager.getInstance(requireActivity())
        mBackgroundManager.attach(requireActivity().window)
        mDefaultBackground = ContextCompat.getDrawable(requireContext(), R.drawable.default_background)
        mMetrics = DisplayMetrics().apply {
            requireActivity().windowManager.defaultDisplay.getMetrics(this)
        }
    }

    private fun setupUIElements() {
        title = getString(R.string.browse_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.fastlane_background)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.search_opaque)
    }

    private fun loadRows() {
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val cardPresenter = CardPresenter()

        // JSON Importados
        jsonDataManager.getImportedJsons().forEach { json ->
            val rowAdapter = ArrayObjectAdapter(cardPresenter).apply {
                json.videos.forEach { add(it.toMovie()) }
            }
            rowsAdapter.add(
                ListRow(
                    HeaderItem(json.fileName.hashCode().toLong(), json.fileName),
                    rowAdapter
                )
            )
        }

        // Preferencias
        val prefAdapter = ArrayObjectAdapter(GridItemPresenter()).apply {
            add(getString(R.string.import_json))
            add(getString(R.string.erase_json))
            add("Erase ALL JSON")
            add("Connect SMB")
            add("Importar automáticamente")
        }
        rowsAdapter.add(ListRow(HeaderItem(-1, "PREFERENCES"), prefAdapter))

        adapter = rowsAdapter
    }

    private fun VideoItem.toMovie() = Movie(
        title = title,
        videoUrl = videoSrc,
        cardImageUrl = imgSml,
        backgroundImageUrl = imgBig,
        skipToSecond = skipToSecond,
        description = "Imported from JSON"
    )

    private fun setupEventListeners() {
        setOnSearchClickedListener {
            Toast.makeText(requireContext(), "Implement your own in-app search", Toast.LENGTH_LONG).show()
        }
        onItemViewClickedListener = ItemViewClickedListener()
        onItemViewSelectedListener = ItemViewSelectedListener()
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder,
            item: Any,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            when (item) {
                is Movie -> navigateToDetails(itemViewHolder, item)
                is String -> handleStringAction(item)
            }
        }

        private fun navigateToDetails(itemViewHolder: Presenter.ViewHolder, movie: Movie) {
            Intent(requireContext(), DetailsActivity::class.java).apply {
                putExtra(DetailsActivity.MOVIE, movie)
                val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    requireActivity(),
                    (itemViewHolder.view as ImageCardView).mainImageView,
                    DetailsActivity.SHARED_ELEMENT_NAME
                )
                startActivity(this, options.toBundle())
            }
        }

        private fun handleStringAction(item: String) {
            when (item) {
                getString(R.string.import_json) -> openFilePicker()
                getString(R.string.erase_json) -> showDeleteDialog()
                "Erase ALL JSON" -> showDeleteAllDialog()
                "Connect SMB" -> openSmbConnectFlow()
                "Importar automáticamente" -> autoImportFromSmb()
                else -> Toast.makeText(requireContext(), item, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteAllDialog() {
        val count = jsonDataManager.getImportedJsons().size
        if (count == 0) {
            Toast.makeText(requireContext(), "No hay JSONs para borrar", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar TODOS los JSON")
            .setMessage("Vas a eliminar $count JSON importados. ¿Seguro?")
            .setPositiveButton("Eliminar todo") { _, _ ->
                jsonDataManager.removeAll(requireContext())
                refreshUI()
                resetBackgroundToDefault()
                Toast.makeText(requireContext(), "JSONs eliminados", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // =========================
    // ✅ SMB CONNECT FLOW
    // =========================
    private fun openSmbConnectFlow() {
        Toast.makeText(requireContext(), "Buscando SMBs en la red...", Toast.LENGTH_SHORT).show()

        val found = LinkedHashMap<String, SmbGateway.SmbServer>()

        smbGateway.discover(
            onFound = { server -> found[server.id] = server },
            onError = { err ->
                Log.e(TAG, err)
                Toast.makeText(requireContext(), "No se pudo escanear SMB: $err", Toast.LENGTH_LONG).show()
                showManualSmbDialog()
            }
        )

        Handler(Looper.getMainLooper()).postDelayed({
            smbGateway.stopDiscovery()

            if (found.isEmpty()) {
                showManualSmbDialog()
                return@postDelayed
            }

            val servers = found.values.toList()
            val labels = servers.map { "${it.name} (${it.host}:${it.port})" }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle("SMB encontrados")
                .setItems(labels) { _, which ->
                    showCredentialsDialog(servers[which])
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }, 2000)
    }

    private fun showManualSmbDialog() {
        val hostInput = EditText(requireContext()).apply {
            hint = "IP o hostname (ej: 192.168.1.33)"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Agregar SMB manual")
            .setView(hostInput)
            .setPositiveButton("Continuar") { _, _ ->
                val host = hostInput.text.toString().trim()
                if (host.isBlank()) return@setPositiveButton

                val server = SmbGateway.SmbServer(
                    id = UUID.nameUUIDFromBytes("$host:445".toByteArray()).toString(),
                    name = host,
                    host = host,
                    port = 445
                )
                showCredentialsDialog(server)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showCredentialsDialog(server: SmbGateway.SmbServer) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 0)
        }

        val userInput = EditText(requireContext()).apply { hint = "Usuario" }
        val passInput = EditText(requireContext()).apply {
            hint = "Contraseña"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val domainInput = EditText(requireContext()).apply { hint = "Dominio (opcional)" }
        val shareInput = EditText(requireContext()).apply { hint = "Share (ej: pelis)" }

        layout.addView(userInput)
        layout.addView(passInput)
        layout.addView(domainInput)
        layout.addView(shareInput)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Login SMB: ${server.host}")
            .setView(layout)
            .setPositiveButton("Conectar", null) // no autoclose
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val user = userInput.text.toString().trim()
                val pass = passInput.text.toString()
                val domain = domainInput.text.toString().trim().ifBlank { null }
                val share = shareInput.text.toString().trim()

                if (user.isBlank()) {
                    Toast.makeText(requireContext(), "Usuario requerido", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (share.isBlank()) {
                    Toast.makeText(requireContext(), "Share requerido (ej: pelis)", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val creds = SmbGateway.SmbCreds(user, pass, domain)

                Thread {
                    try {
                        smbGateway.testLogin(server.host, creds)
                        smbGateway.testShareAccess(server.host, creds, share)

                        smbGateway.saveCreds(server.id, server.host, creds)
                        smbGateway.saveLastShare(server.id, share)

                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "SMB conectado ✅", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "SMB connect FAILED", e)
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Error SMB: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }

        dialog.show()
    }

    private fun parseSeasonFromFolderOrName(name: String): Int? {
        val n = name.lowercase().replace("_", " ").trim()

        // temporada 1 / temporada 01
        Regex("""temporada\s*(\d{1,2})""").find(n)?.let {
            return it.groupValues[1].toInt()
        }

        // temp 1 / temp1
        Regex("""temp\s*(\d{1,2})""").find(n)?.let {
            return it.groupValues[1].toInt()
        }

        // season 1 / season1
        Regex("""season\s*(\d{1,2})""").find(n)?.let {
            return it.groupValues[1].toInt()
        }

        // s01 / s1
        Regex("""\bs(\d{1,2})\b""").find(n)?.let {
            return it.groupValues[1].toInt()
        }

        // carpeta "1", "2", "3" (Rick & Morty)
        if (n.matches(Regex("""\d{1,2}"""))) {
            return n.toInt()
        }

        return null
    }

    private fun fetchCoverFromApiCached(q: String): ApiCover? {
        if (coverCache.containsKey(q)) {
            return coverCache[q]
        }

        val cover = fetchCoverFromApi(q)
        coverCache[q] = cover
        return cover
    }

    private fun filterAlreadyImported(previews: List<PreviewJson>): List<PreviewJson> {
        val existing = jsonDataManager.getImportedJsons()
            .map { it.fileName }
            .toSet()

        return previews.filter { it.fileName !in existing }
    }

    // =========================
    // ✅ AUTO IMPORT (PREVIEW SMB)
    // =========================

    private data class ApiCover(
        val type: String? = null,
        val id: String? = null,
        val dir: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val skipToSecond: Int? = null,
        val file: String? = null,
        val url: String? = null
    )

    private data class PreviewJson(
        val fileName: String,
        val videos: List<VideoItem>,
        val debug: String
    )

    private data class SeriesSeasonKey(
        val seriesId: String,
        val season: Int
    )

    private data class MovieSagaKey(
        val sagaName: String
    )

    private data class RootFolderInfo(
        val root: String,
        val seasonDirs: Map<Int, String>
    )

    private fun autoImportFromSmb() {
        Toast.makeText(requireContext(), "Importando desde SMB…", Toast.LENGTH_LONG).show()

        Thread {
            try {
                val serverId = smbGateway.getLastServerId()
                    ?: return@Thread uiToast("No hay SMB configurado")

                val share = smbGateway.getLastShare(serverId)
                    ?: return@Thread uiToast("No hay share configurado")

                val cached = smbGateway.loadCreds(serverId)
                    ?: return@Thread uiToast("No hay credenciales")

                val host = cached.first
                val creds = cached.second

                // 🔥 SIN LÍMITE
                val files = smbListVideos(host, creds, share, max = Int.MAX_VALUE)
                if (files.isEmpty()) return@Thread uiToast("No se encontraron videos")

                data class RawItem(
                    val path: String,
                    val title: String,
                    val img: String,
                    val skip: Int,
                    val videoSrc: String
                )

                val rawItems = mutableListOf<RawItem>()

                files.forEachIndexed { idx, path ->

                    val q = buildQueryForPathSmart(path)
                    val cover = fetchCoverFromApiCached(q)

                    rawItems.add(
                        RawItem(
                            path = path,
                            title = cover?.id ?: path.substringAfterLast("/").substringBeforeLast("."),
                            img = cover?.url.orEmpty(),
                            skip = cover?.skipToSecond ?: 0,
                            videoSrc = "http://127.0.0.1:8081/smb/$serverId/$share/${smbGateway.encodePath(path)}"
                        )
                    )
                }

                // ================= SERIES =================
                val seriesMap = linkedMapOf<Pair<String, Int>, MutableList<RawItem>>()
                val movies = mutableListOf<RawItem>()

                for (it in rawItems) {
                    val parts = it.path.replace("\\", "/").split("/")
                    if (parts.size >= 3) {
                        val series = parts[parts.size - 3]
                        val season = parseSeasonFromFolderOrName(parts[parts.size - 2])
                        if (season != null) {
                            seriesMap.getOrPut(series to season) { mutableListOf() }.add(it)
                            continue
                        }
                    }
                    movies.add(it)
                }

                val previews = mutableListOf<PreviewJson>()

                // Series → 1 JSON por temporada
                for ((key, items) in seriesMap.toList()
                    .sortedWith(compareBy({ normalizeName(it.first.first) }, { it.first.second }))) {

                    val (series, season) = key
                    previews.add(
                        PreviewJson(
                            fileName = "${normalizeName(series)}_s${pad2(season)}.json",
                            videos = items.sortedWith(
                                compareBy<RawItem>(
                                    { parseSeasonEpisode(it.path)?.second ?: Int.MAX_VALUE },
                                    { it.path.lowercase() }
                                )
                            ).map {
                                VideoItem(it.title, it.skip, it.img, it.img, it.videoSrc)
                            },
                            debug = "SERIES $series S$season"
                        )
                    )
                }


                // ================= MOVIES =================
                val sagaMap = linkedMapOf<String, MutableList<RawItem>>()
                for (m in movies) {
                    sagaMap.getOrPut(inferSagaNameFromPath(m.path)) { mutableListOf() }.add(m)
                }

                for ((saga, items) in sagaMap.toList()
                    .sortedWith(compareBy({ normalizeName(it.first) }))) {

                    val videosSorted = items.sortedWith(
                        compareBy<RawItem>(
                            { extractMovieSortKey(it.path, it.title) }, // ASC por número si existe
                            { normalizeName(it.title) },                // ASC por título
                            { it.path.lowercase() }                     // fallback
                        )
                    ).map {
                        VideoItem(it.title, it.skip, it.img, it.img, it.videoSrc)
                    }

                    previews.add(
                        PreviewJson(
                            fileName = if (items.size > 1)
                                "saga_${normalizeName(saga).replace(" ", "_")}.json"
                            else
                                "${normalizeName(items.first().title).replace(" ", "_")}.json",
                            videos = videosSorted,
                            debug = "AUTO IMPORT"
                        )
                    )
                }


                // 🚫 Evitar duplicados
                val toImport = filterAlreadyImported(previews)

                if (toImport.isEmpty()) {
                    uiToast("Todo ya estaba importado")
                    return@Thread
                }

                importAllPreviews(toImport)

                requireActivity().runOnUiThread {
                    refreshUI()
                    Toast.makeText(
                        requireContext(),
                        "Importados ${toImport.size} JSON",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Auto import failed", e)
                uiToast("Error: ${e.message}")
            }
        }.start()
    }



    // =========================
    // ✅ STRUCTURE-FIRST SERIES DETECTION
    // =========================

    /** Si el nombre parece una temporada, devuelve el número (1..99). Si no, null. */
    private fun isSeasonFolder(name: String): Int? {
        val n = normalizeName(name)

        return when {
            n.matches(Regex("""temporada[_\s-]*(\d{1,2})""")) ->
                Regex("""(\d{1,2})""").find(n)?.groupValues?.get(1)?.toInt()

            n.matches(Regex("""season[_\s-]*(\d{1,2})""")) ->
                Regex("""(\d{1,2})""").find(n)?.groupValues?.get(1)?.toInt()

            n.matches(Regex("""\b(s|t)(\d{1,2})\b""")) ->
                Regex("""(\d{1,2})""").find(n)?.groupValues?.get(1)?.toInt()

            n.matches(Regex("""^\d{1,2}$""")) ->
                n.toInt()

            else -> null
        }
    }

    /** Detecta roots que tienen 2+ subcarpetas que parecen temporadas. */
    private fun detectSeriesRoots(paths: List<String>): Map<String, RootFolderInfo> {
        val byRoot = paths.groupBy { p ->
            p.replace("\\", "/").trim('/').split("/").firstOrNull().orEmpty()
        }

        val result = mutableMapOf<String, RootFolderInfo>()

        for ((root, files) in byRoot) {
            if (root.isBlank()) continue

            val subdirs = files.mapNotNull { p ->
                val parts = p.replace("\\", "/").trim('/').split("/")
                parts.getOrNull(1)
            }.distinct()

            val seasons = mutableMapOf<Int, String>()
            for (d in subdirs) {
                val s = isSeasonFolder(d)
                if (s != null) seasons[s] = d
            }

            if (seasons.size >= 2) {
                result[root] = RootFolderInfo(root, seasons)
            }
        }

        return result
    }

    // =========================
    // ✅ HELPERS (GROUPING / SORT)
    // =========================

    private fun pad2(n: Int): String = n.toString().padStart(2, '0')

    private fun normalizeName(s: String): String =
        s.trim()
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .lowercase()

    /** Devuelve (season, episode) si puede inferirlo desde path o filename */

    private fun extractMovieSortKey(path: String, title: String): Int {
        val name = path.substringAfterLast("/").substringBeforeLast(".")
        // Ej: [01] algo.mp4  -> 1
        Regex("""\[(\d{1,3})]""").find(name)?.let { return it.groupValues[1].toInt() }
        // Ej: "1-..." o "01 ..." o "1...."
        Regex("""^(\d{1,3})\D""").find(name)?.let { return it.groupValues[1].toInt() }
        // Ej: "... part 2" / "parte 2"
        Regex("""(?i)\b(part|parte)\s*(\d{1,3})\b""").find(name)?.let { return it.groupValues[2].toInt() }

        // si no hay número, que quede al final pero ordenado por nombre
        return Int.MAX_VALUE
    }

    private fun parseSeasonEpisode(path: String): Pair<Int, Int>? {
        val clean = path.replace("\\", "/")
        val file = clean.substringAfterLast("/")
        val fileNoExt = file.substringBeforeLast(".", file)

        // s01e02 / S1E2
        Regex("""(?i)s(\d{1,2})\s*e(\d{1,2})""").find(fileNoExt)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }

        // 2x01 / 10x25
        Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b""").find(fileNoExt)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }

        // s01-02 / s01 02 / s01_02
        Regex("""(?i)s(\d{1,2})[^\d]{0,3}(\d{1,2})""").find(fileNoExt)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }

        // ".../t1/...cap 01..." (fallback)
        val parts = clean.trim('/').split("/").filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val seasonDir = normalizeName(parts[parts.size - 2])
            val seasonNum =
                Regex("""(?i)\b(t|temp|season)\s*(\d{1,2})\b""").find(seasonDir)?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: Regex("""(?i)\bs(\d{1,2})\b""").find(seasonDir)?.groupValues?.getOrNull(1)?.toIntOrNull()

            if (seasonNum != null) {
                val epNum =
                    Regex("""(?i)\b(ep|e|cap|c)\s*(\d{1,3})\b""").find(fileNoExt)?.groupValues?.getOrNull(2)?.toIntOrNull()
                        ?: Regex("""(?i)\b(\d{1,3})\b""").find(fileNoExt)?.groupValues?.getOrNull(1)?.toIntOrNull()

                if (epNum != null) return seasonNum to epNum
            }
        }

        return null
    }

    /** Heurística para saga:
     *  - si el parent parece "Harry Potter 1" y existe grandparent -> saga = grandparent
     *  - sino saga = parent directo
     */
    private fun inferSagaNameFromPath(path: String): String {
        val clean = path.replace("\\", "/").trim('/')
        val parts = clean.split("/").filter { it.isNotBlank() }
        if (parts.size < 2) return "Películas"

        val parent = parts[parts.size - 2]
        val grand = parts.getOrNull(parts.size - 3)

        val parentNorm = normalizeName(parent)
        val looksLikePart =
            parentNorm.matches(Regex(""".*\b(\d{1,2}|i{1,6}|iv|v|vi)\b.*""")) ||
                    parentNorm.contains("part") ||
                    parentNorm.contains("parte")

        return if (looksLikePart && !grand.isNullOrBlank()) grand else parent
    }

    /** Query más “inteligente”:
     * - si puedo inferir season+episode: "<serie> s01 02"
     * - sino: filename sin ext (para movies)
     */
    private fun buildQueryForPathSmart(path: String): String {
        val clean = path.replace("\\", "/").trim('/')
        val parts = clean.split("/").filter { it.isNotBlank() }
        val fileNoExt = parts.last().substringBeforeLast(".")

        val se = parseSeasonEpisode(path)
        if (se != null && parts.size >= 3) {
            val seriesName = parts[parts.size - 3] // .../<serie>/<temporada>/<archivo>
            val (season, ep) = se
            return "${normalizeName(seriesName)} s${pad2(season)} ${pad2(ep)}"
        }

        return normalizeName(fileNoExt)
    }

    /** Comparator para ordenar episodios por número si el API lo trajo, sino por parse, sino por path */
    private fun episodeSortKey(path: String, cover: ApiCover?): Int {
        val apiEp = cover?.episode
        if (apiEp != null) return apiEp
        return parseSeasonEpisode(path)?.second ?: Int.MAX_VALUE
    }

    // =========================
    // SMB LIST + API
    // =========================

    private fun smbListVideos(
        host: String,
        creds: SmbGateway.SmbCreds,
        shareName: String,
        max: Int = 100
    ): List<String> {
        val out = mutableListOf<String>()

        val client = com.hierynomus.smbj.SMBClient()
        val conn = client.connect(host)
        val auth = com.hierynomus.smbj.auth.AuthenticationContext(
            creds.username,
            creds.password.toCharArray(),
            creds.domain
        )
        val sess = conn.authenticate(auth)
        val share = sess.connectShare(shareName) as com.hierynomus.smbj.share.DiskShare

        try {
            fun isDir(attrs: Any?): Boolean {
                val maskLong = com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value.toLong()
                val maskInt = maskLong.toInt()

                return when (attrs) {
                    is Set<*> -> attrs.contains(com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                    is java.util.EnumSet<*> -> attrs.contains(com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                    is Long -> (attrs and maskLong) != 0L
                    is Int -> (attrs and maskInt) != 0
                    else -> false
                }
            }

            fun walk(dir: String) {
                if (out.size >= max) return

                val listing = share.list(dir)
                for (f in listing) {
                    if (out.size >= max) return

                    val name = f.fileName ?: continue
                    if (name == "." || name == "..") continue

                    val childPath = if (dir.isBlank()) name else "$dir/$name"
                    val directory = isDir(f.fileAttributes)

                    if (directory) {
                        walk(childPath)
                    } else {
                        val lower = name.lowercase()
                        val isVideo = lower.endsWith(".mp4")
                                || lower.endsWith(".mkv")
                                || lower.endsWith(".avi")
                                || lower.endsWith(".webm")
                                || lower.endsWith(".mov")
                                || lower.endsWith(".flv")
                                || lower.endsWith(".mpg")
                                || lower.endsWith(".mpeg")
                                || lower.endsWith(".m4v")
                                || lower.endsWith(".ts")
                                || lower.endsWith(".3gp")
                                || lower.endsWith(".wmv")

                        if (isVideo) {
                            out.add(childPath)
                        }

                    }
                }
            }

            walk("")
            return out
        } finally {
            try { share.close() } catch (_: Exception) {}
            try { sess.close() } catch (_: Exception) {}
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun fetchCoverFromApi(q: String): ApiCover? {
        val base = "https://api-watchoffline.luzardo-thomas.workers.dev/cover?q="
        val full = base + java.net.URLEncoder.encode(q.replace("+", " "), "UTF-8")

        val conn = (URL(full).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }

        return try {
            val code = conn.responseCode
            if (code != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Gson().fromJson(body, ApiCover::class.java)
        } finally {
            conn.disconnect()
        }
    }

    // =========================
    // IMPORT ACTIONS
    // =========================

    private fun uiToast(msg: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun uniqueJsonName(base: String): String {
        val existing = jsonDataManager.getImportedJsons().map { it.fileName }.toSet()
        if (!existing.contains(base)) return base

        val dot = base.lastIndexOf(".json")
        val prefix = if (dot >= 0) base.substring(0, dot) else base
        var i = 2
        while (true) {
            val candidate = "${prefix}_$i.json"
            if (!existing.contains(candidate)) return candidate
            i++
        }
    }

    private fun importPreview(preview: PreviewJson) {
        val safeName = uniqueJsonName(preview.fileName)
        jsonDataManager.addJson(requireContext(), safeName, preview.videos)
    }

    private fun importAllPreviews(previews: List<PreviewJson>) {
        previews.forEach { importPreview(it) }
    }

    // =========================
    // JSON IMPORT (MANUAL)
    // =========================

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }

        try {
            startActivityForResult(
                Intent.createChooser(intent, "Seleccionar JSON"),
                REQUEST_CODE_IMPORT_JSON
            )
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Esta función no está disponible en Android TV",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_IMPORT_JSON && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                    val jsonString = stream.bufferedReader().use { it.readText() }
                    val videos = Gson().fromJson(jsonString, Array<VideoItem>::class.java).toList()

                    val fileName = JsonUtils.getFileNameFromUri(requireContext(), uri)
                        ?: "imported_${System.currentTimeMillis()}"

                    jsonDataManager.addJson(requireContext(), fileName, videos)
                    refreshUI()
                }
            }
        }
    }

    private fun showDeleteDialog() {
        val items = jsonDataManager.getImportedJsons().map { it.fileName }.toTypedArray()
        AlertDialog.Builder(requireContext()).apply {
            setTitle("Eliminar JSON")
            setItems(items) { _, which ->
                jsonDataManager.removeJson(requireContext(), items[which])
                refreshUI()
            }
            setNegativeButton("Cancelar", null)
            show()
        }
    }

    // =========================
    // BACKGROUND / UI
    // =========================

    private fun resetBackgroundToDefault() {
        mBackgroundTimer?.cancel()
        mBackgroundTimer = null
        mBackgroundUri = null
        mBackgroundManager.drawable = mDefaultBackground
    }

    private fun refreshUI() {
        jsonDataManager.loadData(requireContext())
        loadRows()

        val hasAnyMovie = jsonDataManager.getImportedJsons().any { it.videos.isNotEmpty() }
        if (!hasAnyMovie) resetBackgroundToDefault() else startBackgroundTimer()
    }

    private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
        override fun onItemSelected(
            itemViewHolder: Presenter.ViewHolder?,
            item: Any?,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            when (item) {
                is Movie -> {
                    mBackgroundUri = item.backgroundImageUrl
                    startBackgroundTimer()
                }
                else -> resetBackgroundToDefault()
            }
        }
    }

    private fun updateBackground(uri: String?) {
        Glide.with(requireActivity())
            .load(uri)
            .centerCrop()
            .error(mDefaultBackground)
            .into(object : SimpleTarget<Drawable>(mMetrics.widthPixels, mMetrics.heightPixels) {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    mBackgroundManager.drawable = resource
                }
            })
        mBackgroundTimer?.cancel()
    }

    private fun startBackgroundTimer() {
        mBackgroundTimer?.cancel()
        mBackgroundTimer = Timer().apply {
            schedule(UpdateBackgroundTask(), BACKGROUND_UPDATE_DELAY.toLong())
        }
    }

    private inner class UpdateBackgroundTask : TimerTask() {
        override fun run() {
            mHandler.post { updateBackground(mBackgroundUri) }
        }
    }

    private inner class GridItemPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            return TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(GRID_ITEM_WIDTH, GRID_ITEM_HEIGHT)
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(ContextCompat.getColor(context, R.color.default_background))
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }.let { ViewHolder(it) }
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            (viewHolder.view as TextView).text = item as String
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
    }

    companion object {
        private const val TAG = "MainFragment"
        private const val BACKGROUND_UPDATE_DELAY = 300
        private const val GRID_ITEM_WIDTH = 200
        private const val GRID_ITEM_HEIGHT = 200
    }
}

// =========================
// DATA CLASSES + MANAGER
// (Si ya existen en otro archivo, NO los dupliques)
// =========================
data class VideoItem(
    val title: String,
    val skipToSecond: Int,
    val imgBig: String,
    val imgSml: String,
    val videoSrc: String
)

data class ImportedJson(
    val fileName: String,
    val videos: List<VideoItem>
)

class JsonDataManager {
    private val importedJsons = mutableListOf<ImportedJson>()

    fun addJson(context: Context, fileName: String, videos: List<VideoItem>) {
        if (importedJsons.none { it.fileName == fileName }) {
            importedJsons.add(ImportedJson(fileName, videos))
            saveData(context)
        }
    }

    fun removeJson(context: Context, fileName: String) {
        importedJsons.removeAll { it.fileName == fileName }
        saveData(context)
    }

    fun removeAll(context: Context) {
        importedJsons.clear()
        saveData(context)
    }

    fun getImportedJsons(): List<ImportedJson> = importedJsons.toList()

    fun loadData(context: Context) {
        try {
            val json = context.getSharedPreferences("json_data", Context.MODE_PRIVATE)
                .getString("imported", null) ?: return

            val type = object : TypeToken<List<ImportedJson>>() {}.type
            importedJsons.clear()
            importedJsons.addAll(Gson().fromJson(json, type))
        } catch (e: Exception) {
            Log.e("JsonDataManager", "Error loading JSON data", e)
        }
    }

    fun saveData(context: Context) {
        try {
            val json = Gson().toJson(importedJsons)
            context.getSharedPreferences("json_data", Context.MODE_PRIVATE).edit()
                .putString("imported", json)
                .apply()
        } catch (e: Exception) {
            Log.e("JsonDataManager", "Error saving JSON data", e)
        }
    }
}

