package com.example.watchoffline

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import android.content.Context

class MobileMainFragment : Fragment(R.layout.fragment_mobile_main) {

    private lateinit var btnToggleSearchRef: ImageButton
    private lateinit var searchInputRef: EditText


    companion object {
        private const val TAG = "MobileMainFragment"
    }

    private val jsonDataManager = JsonDataManager()

    // ✅ SMB
    private lateinit var smbGateway: SmbGateway

    // ✅ Search (inline)
    private var currentQuery: String = ""
    private val handler = Handler(Looper.getMainLooper())

    // ✅ Back button (auto-hide)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var btnBack: ImageButton
    private lateinit var rootViewRef: View
    private lateinit var sectionsRecyclerRef: RecyclerView
    private var backVisible = false

    // ✅ LAST PLAYED (SharedPreferences)
    private val PREFS_NAME = "watchoffline_prefs"
    private val KEY_LAST_PLAYED = "LAST_PLAYED_VIDEO_URL"

    private fun writeLastPlayed(key: String) {
        if (key.isBlank()) return
        requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PLAYED, key.trim())
            .apply()
    }

    private fun readLastPlayed(): String? {
        return requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PLAYED, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }


    private data class FocusTarget(
        val sectionIndex: Int,  // índice en el RecyclerView vertical (secciones)
        val itemIndex: Int      // índice dentro de la sección (horizontal)
    )

    private var lastRenderedSections: List<MobileSection> = emptyList()

    private fun findLastPlayedTarget(
        sections: List<MobileSection>,
        key: String
    ): FocusTarget? {
        for (sIndex in sections.indices) {
            val section = sections[sIndex]
            if (section.title == "ACCIONES AVANZADAS") continue
            if (section.title == "ARMADO DE REPRODUCCIÓN") continue
            if (section.title == "ACCIONES PRINCIPALES") continue


            for (i in section.items.indices) {
                val m = section.items[i]
                if ((m.videoUrl ?: "") == key) {
                    return FocusTarget(sIndex, i)
                }
            }
        }
        return null
    }



    /**
     * ✅ En Mobile: scrollea hasta la sección del último reproducido
     * y le pide al adapter que lo “marque” visualmente.
     */
    private fun focusLastPlayedIfAnyMobile() {
        val key = readLastPlayed()?.trim().orEmpty()
        if (key.isBlank()) return

        val rv = if (this::sectionsRecyclerRef.isInitialized) sectionsRecyclerRef
        else view?.findViewById(R.id.sectionsRecycler) ?: return

        val sections = lastRenderedSections
        if (sections.isEmpty()) return

        val target = findLastPlayedTarget(sections, key) ?: return

        try {
            btnToggleSearchRef.clearFocus()
            searchInputRef.clearFocus()
        } catch (_: Throwable) {}

        rv.post {
            rv.scrollToPosition(target.sectionIndex)

            rv.postDelayed({
                val vh = rv.findViewHolderForAdapterPosition(target.sectionIndex)
                val rowRv = vh?.itemView?.findViewById<RecyclerView>(R.id.sectionRowRecycler)
                rowRv?.scrollToPosition(target.itemIndex)
            }, 120)
        }
    }


    override fun onResume() {
        super.onResume()

        // ✅ Mobile: solo scroll/mark, sin requestFocus
        view?.post {
            focusLastPlayedIfAnyMobile()
        }
    }




    // =========================
    // ✅ Next/Prev context
    // =========================

    private data class PlaylistCtx(
        val playlist: ArrayList<Movie>,
        val index: Int
    )

    private var playlistCtxByKey: HashMap<String, PlaylistCtx> = HashMap()

    private fun movieKey(m: Movie): String {
        // estable y con baja chance de colisión
        return "${m.videoUrl}||${m.title}"
    }

    private val hideBackRunnable = Runnable {
        backVisible = false
        if (!this::btnBack.isInitialized) return@Runnable
        btnBack.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction { btnBack.visibility = View.GONE }
            .start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        jsonDataManager.loadData(requireContext())

        smbGateway = SmbGateway(requireContext())
        val ok = smbGateway.ensureProxyStarted(8081)
        Log.d(TAG, "SMB proxy started? $ok port=${smbGateway.getProxyPort()}")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { smbGateway.stopDiscovery() } catch (_: Exception) {}
        try { smbGateway.stopProxy() } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        // ✅ cleanup back-button timers/listeners
        ui.removeCallbacks(hideBackRunnable)
        if (this::sectionsRecyclerRef.isInitialized) {
            try { sectionsRecyclerRef.clearOnScrollListeners() } catch (_: Exception) {}
        }
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // refs
        rootViewRef = view.findViewById(R.id.root)
        sectionsRecyclerRef = view.findViewById(R.id.sectionsRecycler)

        setupSearchUi(view)
        render(view)
    }

    // =========================
    // ✅ Search UI (inline, no SearchView)
    // =========================

    private fun setupSearchUi(root: View) {
        btnToggleSearchRef = root.findViewById(R.id.btnToggleSearch)
        searchInputRef = root.findViewById(R.id.searchInput)

        val btn = btnToggleSearchRef
        val input = searchInputRef


        fun dp(v: Int): Int =
            (v * root.resources.displayMetrics.density).toInt()

        fun bringSearchToFront() {
            input.bringToFront()
            input.elevation = 40f
            input.translationZ = 40f

            btn.bringToFront()
            btn.isClickable = true
            btn.isFocusable = true
            btn.isFocusableInTouchMode = true
            btn.elevation = 60f
            btn.translationZ = 60f
        }

        fun reserveSpaceForButton() {
            btn.post {
                val space = btn.width.takeIf { it > 0 } ?: dp(48)
                val extra = dp(12)
                input.setPadding(
                    input.paddingLeft,
                    input.paddingTop,
                    space + extra,
                    input.paddingBottom
                )
            }
        }

        fun clearSearch() {
            input.setText("")
            input.clearFocus()
            currentQuery = ""
            refreshUI()
        }

        fun setSearchVisible(visible: Boolean) {
            input.visibility = if (visible) View.VISIBLE else View.GONE

            btn.setImageResource(
                if (visible) android.R.drawable.ic_menu_close_clear_cancel
                else android.R.drawable.ic_menu_search
            )

            if (visible) {
                reserveSpaceForButton()
                input.requestFocus()
            } else {
                clearSearch()
            }

            bringSearchToFront()
        }

        setSearchVisible(false)

        btn.setOnClickListener {
            val visible = input.visibility == View.VISIBLE
            setSearchVisible(!visible)
        }

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    currentQuery = q
                    view?.let { render(it) }
                }, 120)
            }
        })
    }

    // =========================
    // UI
    // =========================

    private fun render(rootView: View) {
        val rv = rootView.findViewById<RecyclerView>(R.id.sectionsRecycler)
        rv.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)

        val sections = buildSectionsFiltered(currentQuery)
        lastRenderedSections = sections

        // ✅ Arma contexto playlist+index para items reales:
        // - NO acciones
        // - NO "ARMADO DE REPRODUCCIÓN"
        // - NO launcher playlist:// (RANDOM)
        playlistCtxByKey = HashMap()
        sections.forEach { section ->
            if (section.title == "ACCIONES AVANZADAS") return@forEach
            if (section.title == "ARMADO DE REPRODUCCIÓN") return@forEach
            if (section.title == "ACCIONES PRINCIPALES") return@forEach

            val onlyReal = section.items.filter { it.videoUrl?.startsWith("playlist://") != true }
            val list = ArrayList(onlyReal)

            onlyReal.forEachIndexed { idx, movie ->
                playlistCtxByKey[movieKey(movie)] = PlaylistCtx(
                    playlist = list,
                    index = idx
                )
            }
        }

        rv.adapter = MobileSectionsAdapter(
            sections = sections,
            onMovieClick = { item ->

                val url = item.videoUrl ?: ""

                // ✅ Guardar SIEMPRE la última card clickeada
                // (incluye playlist://RANDOM...)
                if (!url.startsWith("__action_")) {
                    writeLastPlayed(url)
                }


                // =========================
                // ✅ ARMADO DE REPRODUCCIÓN (acciones RANDOM)
                // =========================
                when (url) {
                    "__action_random_generate__" -> { runRandomGenerate(); return@MobileSectionsAdapter }
                    "__action_random_update__" -> { runRandomUpdate(); return@MobileSectionsAdapter }
                    "__action_random_delete__" -> { runRandomDeleteOne(); return@MobileSectionsAdapter }
                    "__action_random_delete_all__" -> { runRandomDeleteAll(); return@MobileSectionsAdapter }
                }

                // =========================
                // ✅ Launcher playlist RANDOM (1 sola card)
                // =========================
                if (url.startsWith("playlist://")) {
                    val playlistName = url.removePrefix("playlist://").trim()

                    val imported = jsonDataManager.getImportedJsons()
                        .firstOrNull { it.fileName == playlistName }

                    if (imported == null || imported.videos.isEmpty()) {
                        Toast.makeText(requireContext(), "Playlist no encontrada o vacía", Toast.LENGTH_LONG).show()
                        return@MobileSectionsAdapter
                    }

                    val playlist = ArrayList<Movie>().apply {
                        imported.videos.forEach { v -> add(v.toMovie(imported.fileName)) }
                    }

                    startActivity(
                        Intent(requireContext(), DetailsActivity::class.java).apply {
                            putExtra(DetailsActivity.MOVIE, playlist[0])
                            putExtra(DetailsActivity.EXTRA_PLAYLIST, playlist)
                            putExtra(DetailsActivity.EXTRA_INDEX, 0)

                            // 🔁 SOLO RANDOM
                            putExtra("EXTRA_LOOP_PLAYLIST", true)
                            putExtra("EXTRA_DISABLE_LAST_PLAYED", true)
                        }
                    )
                    return@MobileSectionsAdapter
                }

                // =========================
                // ACCIONES
                // =========================
                when (url) {
                    "__action_erase_json__" -> showDeleteDialog()
                    "__action_erase_all_json__" -> showDeleteAllDialog()
                    "__action_connect_smb__" -> openSmbConnectFlow()
                    "__action_clear_specific_smb__" -> showDeleteSpecificSmbDialog()
                    "__action_clear_smb__" -> showClearSmbDialog()
                    "__action_web_search__" -> {
                        // 1. Obtener los archivos y ordenarlos A-Z
                        val importedJsons = jsonDataManager.getImportedJsons()
                        val listForIntent = ArrayList(importedJsons.map { it.fileName }.sortedBy { it.lowercase() })

                        if (listForIntent.isEmpty()) {
                            Toast.makeText(requireContext(), "No hay archivos JSON para editar", Toast.LENGTH_SHORT).show()
                        } else {
                            // 2. Crear el Intent con los datos necesarios
                            val intent = Intent(requireContext(), ImageSearchActivity::class.java).apply {
                                putStringArrayListExtra("TARGET_JSONS", listForIntent)
                                // Opcional: Si tienes acceso al objeto 'movie' actual, puedes pasar el título
                                // putExtra("QUERY", movie.title)
                            }

                            // 3. Lanzar con el código 100 para que onActivityResult refresque la pantalla al volver
                            startActivityForResult(intent, 100)
                        }
                    }
                    "__action_auto_import__" -> runAutoImport()
                    "__action_auto_import_local_folder__" -> showLocalFolderImportDialog()
                    else -> {
                        val ctx = playlistCtxByKey[movieKey(item)]

                        startActivity(
                            Intent(requireContext(), DetailsActivity::class.java).apply {
                                putExtra(DetailsActivity.MOVIE, item)

                                if (ctx != null && ctx.playlist.isNotEmpty()) {
                                    putExtra(DetailsActivity.EXTRA_PLAYLIST, ctx.playlist)
                                    putExtra(
                                        DetailsActivity.EXTRA_INDEX,
                                        ctx.index.coerceIn(0, ctx.playlist.lastIndex)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        )

        rootView.post { focusLastPlayedIfAnyMobile() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100 && resultCode == android.app.Activity.RESULT_OK && data != null) {
            val imageUrl = data.getStringExtra("SELECTED_IMAGE_URL")
            val selectedJsons = data.getStringArrayListExtra("TARGET_JSONS")

            if (imageUrl != null && selectedJsons != null) {
                // 1. Obtenemos la data actual
                val currentData = jsonDataManager.getImportedJsons()

                selectedJsons.forEach { fileName ->
                    val originalImport = currentData.firstOrNull { it.fileName == fileName }
                    if (originalImport != null) {
                        // 2. Mapeamos y copiamos con la nueva URL
                        val updatedVideos = originalImport.videos.map { video ->
                            video.copy(cardImageUrl = imageUrl)
                        }
                        // 3. Guardamos en SharedPreferences
                        jsonDataManager.upsertJson(requireContext(), fileName, updatedVideos)
                    }
                }

                // 4. Refrescamos la lista de Mobile
                refreshUI() // O la función que uses en Mobile para recargar la lista

                Toast.makeText(requireContext(), "Portadas actualizadas", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun refreshUI() {
        jsonDataManager.loadData(requireContext())
        view?.let { render(it) }
    }

    // =========================
    // ✅ Sections + Filter
    // =========================

    private fun buildSectionsFiltered(queryRaw: String): List<MobileSection> {

        val advancedActions = MobileSection(
            title = "ACCIONES AVANZADAS",
            items = listOf(
                actionCard("Importar de SMB", "__action_auto_import__"),
                actionCard("Conectarse a un SMB", "__action_connect_smb__"),
                actionCard("Limpiar credenciales especificas", "__action_clear_specific_smb__"),
                actionCard("Limpiar credenciales", "__action_clear_smb__"),
            )
        )

        val playbackBuildSection = MobileSection(
            title = "ARMADO DE REPRODUCCIÓN",
            items = listOf(
                actionCard("Generar playlist RANDOM", "__action_random_generate__"),
                actionCard("Actualizar playlist RANDOM", "__action_random_update__"),
                actionCard("Borrar playlists RANDOM", "__action_random_delete__"),
                actionCard("Borrar TODAS las playlists RANDOM", "__action_random_delete_all__"),
            )
        )

        val actionsSection = MobileSection(
            title = "ACCIONES PRINCIPALES",
            items = listOf(
                actionCard("Importar de una RUTA del DISPOSITIVO", "__action_auto_import_local_folder__"),
                actionCard("Actualizar portadas de JSONS", "__action_web_search__"),
                actionCard("Borrar JSON", "__action_erase_json__"),
                actionCard("Borrar todos los JSON", "__action_erase_all_json__"),
            )
        )

        fun isRandomName(name: String): Boolean =
            name.trim().uppercase(Locale.ROOT).startsWith("RANDOM")

        val q = normalize(queryRaw)
        val importedAll = jsonDataManager.getImportedJsons()

        val importedSorted = importedAll.sortedWith(
            compareByDescending<ImportedJson> { isRandomName(it.fileName) }
                .thenBy { prettyTitle(it.fileName).lowercase(Locale.ROOT) }
        )

        val randomCover = "android.resource://${requireContext().packageName}/drawable/dados"

        val contentSections = if (q.isBlank()) {
            importedSorted.map { one ->
                val title = prettyTitle(one.fileName)

                val items: List<Movie> =
                    if (isRandomName(one.fileName)) {
                        // ✅ 1 sola card launcher
                        listOf(
                            Movie(
                                title = title,
                                videoUrl = "playlist://${one.fileName}",
                                cardImageUrl = randomCover,
                                backgroundImageUrl = "",
                                skipToSecond = 0,
                                delaySkip = 0,
                                description = "Playlist RANDOM"
                            )
                        )
                    } else {
                        one.videos.map { it.toMovie(one.fileName) }
                    }

                MobileSection(title = title, items = items)
            }
        } else {
            importedSorted.mapNotNull { one ->
                val jsonTitle = prettyTitle(one.fileName)
                val jsonMatch = normalize(jsonTitle).contains(q)

                if (isRandomName(one.fileName)) {
                    // ✅ en búsqueda: mostrar launcher SOLO si matchea el nombre del JSON RANDOM
                    if (!jsonMatch) return@mapNotNull null

                    val launcher = Movie(
                        title = jsonTitle,
                        videoUrl = "playlist://${one.fileName}",
                        cardImageUrl = randomCover,
                        backgroundImageUrl = "",
                        skipToSecond = 0,
                        delaySkip = 0,
                        description = "Playlist RANDOM"
                    )
                    return@mapNotNull MobileSection(title = jsonTitle, items = listOf(launcher))
                }

                val filtered = one.videos.filter { v ->
                    jsonMatch || normalize(v.title).contains(q)
                }

                if (filtered.isEmpty()) null
                else MobileSection(
                    title = jsonTitle,
                    items = filtered.map { it.toMovie(one.fileName) }
                )
            }
        }

        return listOf(actionsSection, playbackBuildSection, advancedActions) + contentSections
    }

    private fun runRandomGenerate() {
        RandomizeImporter(
            context = requireContext(),
            jsonDataManager = jsonDataManager
        ).actionGenerateRandom(
            toast = { msg -> activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show() } },
            onDone = { activity?.runOnUiThread { refreshUI() } },
            onError = { err -> activity?.runOnUiThread { Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show() } }
        )
    }

    private fun runRandomUpdate() {
        RandomizeImporter(
            context = requireContext(),
            jsonDataManager = jsonDataManager
        ).actionUpdateRandom(
            toast = { msg -> activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show() } },
            onDone = { activity?.runOnUiThread { refreshUI() } },
            onError = { err -> activity?.runOnUiThread { Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show() } }
        )
    }

    // ✅ este es el nuevo UX: lista simple, tap y borra esa playlist
    private fun runRandomDeleteOne() {
        RandomizeImporter(
            context = requireContext(),
            jsonDataManager = jsonDataManager
        ).actionDeleteRandomPlaylists(
            toast = { msg -> activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show() } },
            onDone = { activity?.runOnUiThread { refreshUI() } },
            onError = { err -> activity?.runOnUiThread { Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show() } }
        )
    }

    private fun runRandomDeleteAll() {
        RandomizeImporter(
            context = requireContext(),
            jsonDataManager = jsonDataManager
        ).actionDeleteAllRandomPlaylists(
            toast = { msg -> activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show() } },
            onDone = { activity?.runOnUiThread { refreshUI() } },
            onError = { err -> activity?.runOnUiThread { Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show() } }
        )
    }



    private fun normalize(s: String): String =
        s.trim()
            .lowercase(Locale.getDefault())
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")


    // =========================
    // Cards
    // =========================

    private fun actionCard(title: String, actionId: String) = Movie(
        title = title,
        videoUrl = actionId,
        cardImageUrl = "",
        backgroundImageUrl = "",
        skipToSecond = 0,
        description = ""
    )

    private fun VideoItem.toMovie(sourceFileName: String) = Movie(
        title = title,
        videoUrl = videoUrl,
        cardImageUrl = cardImageUrl,
        backgroundImageUrl = backgroundImageUrl,
        skipToSecond = skip,
        delaySkip = delaySkip,
        description = "Importado desde un JSON",
        studio = sourceFileName
    )


    // =========================
    // SMB / Local actions
    // =========================

    private fun showClearSmbDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset SMB")
            .setMessage("¿Borrar todas las credenciales y shares?")
            .setPositiveButton("Borrar") { _, _ ->
                smbGateway.clearAllSmbData()
                Toast.makeText(requireContext(), "Credenciales eliminadas ✅", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDeleteSpecificSmbDialog() {
        // 1. Obtener la lista de servidores cacheados
        val servers = smbGateway.listCachedServers()

        if (servers.isEmpty()) {
            Toast.makeText(requireContext(), "No hay servidores guardados", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. Preparar los nombres legibles evitando duplicados como "IP (IP)"
        val labels = servers.map { server ->
            if (server.name == server.host || server.name.isBlank()) {
                server.host // Solo la IP si no hay nombre distinto
            } else {
                "${server.name} (${server.host})" // Nombre (IP) si son diferentes
            }
        }.toTypedArray()

        // 3. Mostrar diálogo de SELECCIÓN
        AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar SMB para eliminar")
            .setItems(labels) { _, which ->
                val selectedServer = servers[which]
                val serverLabel = labels[which]

                // 4. Diálogo de CONFIRMACIÓN (Anidado)
                AlertDialog.Builder(requireContext())
                    .setTitle("Confirmar eliminación")
                    .setMessage("¿Estás seguro de borrar la credencial y configuración de:\n$serverLabel?")
                    .setPositiveButton("Borrar") { _, _ ->
                        smbGateway.deleteSpecificSmbData(selectedServer.id)
                        refreshUI()
                        Toast.makeText(requireContext(), "Credencial eliminada ✅", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }



    private fun showLocalFolderImportDialog() {
        val options = arrayOf(
            "Downloads",
            "Movies",
            "DCIM (Cámara)",
            "Elegir ruta manual…"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Importar desde carpeta")
            .setItems(options) { _, which ->
                val base = listOf(
                    File("/storage/self/primary"),
                    File("/storage/emulated/0"),
                    File("/sdcard")
                ).firstOrNull { it.exists() && it.isDirectory } ?: File("/storage/emulated/0")

                val target = when (which) {
                    0 -> File(base, "Download")
                    1 -> File(base, "Movies")
                    2 -> File(base, "DCIM")
                    else -> null
                }

                if (which == 3) {
                    val input = EditText(requireContext()).apply {
                        hint = "/storage/emulated/0/Download"
                        setText("/storage/emulated/0/Download")
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle("Ruta a importar")
                        .setView(input)
                        .setPositiveButton("Importar") { _, _ ->
                            val path = input.text.toString().trim()
                            if (path.isBlank()) return@setPositiveButton
                            runLocalAutoImportForDirs(listOf(File(path)))
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                    return@setItems
                }

                runLocalAutoImportForDirs(listOf(target!!))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun runLocalAutoImportForDirs(dirs: List<File>) {
        if (!ensureAllFilesAccessTv()) {
            Toast.makeText(requireContext(), "Habilitá 'Acceso a todos los archivos' y reintentá", Toast.LENGTH_LONG).show()
            return
        }

        LocalAutoImporter(
            context = requireContext(),
            jsonDataManager = jsonDataManager,
            serverPort = 8080,
            rootDirs = dirs
        ).run(
            toast = { msg ->
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            },
            onDone = { count ->
                activity?.runOnUiThread {
                    refreshUI()
                    Toast.makeText(requireContext(), "Importados $count JSON", Toast.LENGTH_LONG).show()
                }
            },
            onError = { err ->
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun ensureAllFilesAccessTv(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")
                )
                startActivity(intent)
                return false
            }
        }
        return true
    }

    private fun runAutoImport() {
        AutoImporter(
            context = requireContext(),
            smbGateway = smbGateway,
            jsonDataManager = jsonDataManager,
            proxyPort = 8081
        ).run(
            toast = { msg ->
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            },
            onDone = { _ ->
                activity?.runOnUiThread {
                    refreshUI()
                }
            },
            onError = { err ->
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // =========================
    // Delete dialogs
    // =========================

    private fun showDeleteDialog() {
        // 1. Obtener y ordenar la lista A-Z por nombre de archivo (Ignorando mayúsculas)
        val imported = jsonDataManager.getImportedJsons()
            .sortedBy { it.fileName.lowercase(java.util.Locale.ROOT) }

        if (imported.isEmpty()) {
            Toast.makeText(requireContext(), "No hay JSONs para borrar", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. Generar los nombres "limpios" para la lista
        val labels = imported.map { prettyTitle(it.fileName) }.toTypedArray()

        AlertDialog.Builder(requireContext()).apply {
            setTitle("Borrar JSON")
            setItems(labels) { _, which ->
                // Recuperamos el nombre real del archivo basado en el orden de la lista
                val realName = imported[which].fileName

                // 3. Diálogo de confirmación para Mobile
                AlertDialog.Builder(requireContext()).apply {
                    setTitle("¿Confirmar borrado?")
                    setMessage("¿Estás seguro de que quieres eliminar \"${prettyTitle(realName)}\"?")
                    setNegativeButton("CANCELAR", null)
                    setPositiveButton("BORRAR") { _, _ ->
                        jsonDataManager.removeJson(requireContext(), realName)
                        Toast.makeText(requireContext(), "Archivo eliminado", Toast.LENGTH_SHORT).show()
                        refreshUI()
                    }
                    show()
                }
            }
            setNegativeButton("Cerrar", null)
            show()
        }
    }

    private fun showDeleteAllDialog() {
        val count = jsonDataManager.getImportedJsons().size
        if (count == 0) {
            Toast.makeText(requireContext(), "No hay JSONs para borrar", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Borrar TODOS los JSON")
            .setMessage("Vas a borrar $count JSON importados. ¿Seguro?")
            .setPositiveButton("Borrar todo") { _, _ ->
                jsonDataManager.removeAll(requireContext())
                refreshUI()
                Toast.makeText(requireContext(), "JSONs borrados", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // =========================
    // SMB Connect flow
    // =========================

    private fun openSmbConnectFlow() {
        Toast.makeText(requireContext(), "Buscando SMBs en la red...", Toast.LENGTH_SHORT).show()

        val found = LinkedHashMap<String, SmbGateway.SmbServer>()

        smbGateway.discoverAll(
            onFound = { server -> found[server.id] = server },
            onError = { err -> Toast.makeText(requireContext(), "No se pudo escanear SMB: $err", Toast.LENGTH_LONG).show() }
        )

        Handler(Looper.getMainLooper()).postDelayed({
            smbGateway.stopDiscovery()

            if (found.isEmpty()) {
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
        val shareInput = EditText(requireContext()).apply { hint = "Share (ej: pelis)" }

        layout.addView(userInput)
        layout.addView(passInput)
        layout.addView(shareInput)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Login SMB: ${server.host}")
            .setView(layout)
            .setPositiveButton("Conectar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val user = userInput.text.toString().trim()
                val pass = passInput.text.toString()
                val domain = null
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

                        smbGateway.saveCreds(
                            serverId = server.id,
                            host = server.host,
                            creds = creds,
                            port = server.port,
                            serverName = server.name
                        )
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
}

data class MobileSection(
    val title: String,
    val items: List<Movie>
)


