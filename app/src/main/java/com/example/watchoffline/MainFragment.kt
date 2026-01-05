package com.example.watchoffline

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import com.bumptech.glide.Glide
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

class MainFragment : BrowseSupportFragment() {

    private val mHandler = Handler(Looper.getMainLooper())

    private val jsonDataManager = JsonDataManager()

    // ✅ RANDOM playlists
    private lateinit var randomizeImporter: RandomizeImporter


    // ✅ SMB
    private lateinit var smbGateway: SmbGateway

    // ✅ Preload cache
    private val preloadedPosterUrls = HashSet<String>()

    private var pendingFocusLastPlayed = false



    private fun writeLastPlayedUrl(url: String) {
        val u = url.trim()
        if (u.isEmpty()) return
        requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PLAYED, u)
            .apply()
    }

    private fun readLastPlayedUrl(): String? {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val u = prefs.getString(KEY_LAST_PLAYED, null)?.trim()
        Log.e(TAG, "READ lastPlayedUrl=$u")
        return u?.takeIf { it.isNotEmpty() }
    }



    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        hideHeadersDockCompletely(view)
        disableSearchOrbFocus(view)

        // ✅ Fondo fijo (no se carga background grande)
        view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.default_background))
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        jsonDataManager.loadData(requireContext())

        randomizeImporter = RandomizeImporter(requireContext(), jsonDataManager)

        smbGateway = SmbGateway(requireContext())
        smbGateway.ensureProxyStarted(8081)

        preloadPostersForImportedJsons()

        setupUIElements()
        loadRows()
        setupEventListeners()

        // ✅ AL INICIAR HOME: como antes, arrancar en ACCIONES
        mHandler.post { focusFirstItemReal() }
    }


    override fun onDestroy() {
        super.onDestroy()
        try { smbGateway.stopDiscovery() } catch (_: Exception) {}
        try { smbGateway.stopProxy() } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "FOCUSDBG onResume() CALLED adapter=${adapter?.javaClass?.simpleName}")

        mHandler.post {
            focusLastPlayedIfAny()
        }
    }


    private fun setupUIElements() {
        title = getString(R.string.browse_title)

        // ✅ tu fix
        headersState = HEADERS_HIDDEN
        isHeadersTransitionOnBackEnabled = false

        val bg = ContextCompat.getColor(requireContext(), R.color.default_background)
        brandColor = bg
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.search_opaque)
    }

    // ✅ elimina columna fantasma de headers sin romper Leanback
    private fun hideHeadersDockCompletely(root: View) {
        root.post {
            val bg = ContextCompat.getColor(requireContext(), R.color.default_background)

            root.findViewById<ViewGroup>(androidx.leanback.R.id.browse_headers_dock)?.apply {
                setBackgroundColor(bg)
                layoutParams = layoutParams.apply { width = 1 }
                alpha = 0f
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }

            root.findViewById<ViewGroup>(androidx.leanback.R.id.browse_headers)?.apply {
                alpha = 0f
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }

            root.findViewById<View>(androidx.leanback.R.id.browse_frame)?.apply {
                setPadding(0, paddingTop, paddingRight, paddingBottom)
            }
        }
    }

    // ✅ evita que el Search Orb se quede con el foco inicial
    private fun disableSearchOrbFocus(root: View) {
        root.post {
            root.findViewById<View>(androidx.leanback.R.id.search_orb)?.apply {
                isFocusable = false
                isFocusableInTouchMode = false
                clearFocus()
            }
        }
    }

    // ✅ foco inicial al primer item real (simple)
    private fun focusFirstItemReal() {
        val root = view ?: return
        root.post {
            setSelectedPosition(0, false)

            val vgrid = root.findViewById<VerticalGridView>(androidx.leanback.R.id.browse_grid)
                ?: return@post

            vgrid.post {
                val firstRowView = vgrid.getChildAt(0) ?: return@post
                val rowContent = firstRowView.findViewById<HorizontalGridView>(androidx.leanback.R.id.row_content)
                    ?: return@post

                rowContent.post {
                    rowContent.getChildAt(0)?.requestFocus() ?: rowContent.requestFocus()
                }
            }
        }
    }



    /**
     * ✅ Enfoca la card del último video reproducido si existe.
     * Devuelve true si pudo (o dejó el intento en marcha), false si no encontró.
     *
     * Implementación estable:
     * - setSelectedPosition(row)
     * - buscar VerticalGridView y el ViewHolder real
     * - luego enfocar HorizontalGridView y el item
     */
    private fun focusLastPlayedIfAny(): Boolean {
        Log.e(TAG, "FOCUSDBG ENTER focusLastPlayedIfAny")

        val lastUrl = readLastPlayedUrl()?.trim()
        if (lastUrl.isNullOrEmpty()) {
            Log.e(TAG, "FOCUSDBG no lastUrl")
            return false
        }

        val rows = adapter as? ArrayObjectAdapter ?: run {
            Log.e(TAG, "FOCUSDBG adapter not ArrayObjectAdapter")
            return false
        }

        var targetRowIndex = -1
        var targetColIndex = -1

        for (r in 0 until rows.size()) {
            val row = rows.get(r) as? ListRow ?: continue
            val rowAdapter = row.adapter ?: continue

            for (c in 0 until rowAdapter.size()) {
                val m = rowAdapter.get(c) as? Movie ?: continue
                if (m.videoUrl == lastUrl) {
                    targetRowIndex = r
                    targetColIndex = c
                    break
                }
            }
            if (targetRowIndex >= 0) break
        }

        if (targetRowIndex < 0 || targetColIndex < 0) {
            Log.e(TAG, "FOCUSDBG NOT FOUND url=$lastUrl")
            return false
        }

        Log.e(TAG, "FOCUSDBG FOUND row=$targetRowIndex col=$targetColIndex url=$lastUrl")

        // ✅ Esto es lo único que Leanback “garantiza”: te da el holder cuando exista
        setSelectedPosition(targetRowIndex, false, object : Presenter.ViewHolderTask() {

            private var tries = 0

            override fun run(holder: Presenter.ViewHolder?) {
                tries++

                if (holder == null) {
                    Log.e(TAG, "FOCUSDBG holder==null tries=$tries")
                    if (tries < 30) {
                        mHandler.postDelayed({ setSelectedPosition(targetRowIndex, false, this) }, 16)
                    }
                    return
                }

                val rowView = holder.view
                val rowContent = rowView.findViewById<HorizontalGridView>(androidx.leanback.R.id.row_content)

                if (rowContent == null) {
                    Log.e(TAG, "FOCUSDBG row_content==null tries=$tries")
                    if (tries < 30) {
                        mHandler.postDelayed({ setSelectedPosition(targetRowIndex, false, this) }, 16)
                    }
                    return
                }

                // ✅ Scroll + selección horizontal REAL
                rowContent.scrollToPosition(targetColIndex)

                rowContent.post {
                    rowContent.setSelectedPosition(targetColIndex)
                    rowContent.requestFocus()

                    Log.e(
                        TAG,
                        "FOCUSDBG APPLIED row=$targetRowIndex col=$targetColIndex selected=${rowContent.selectedPosition}"
                    )
                }
            }
        })

        return true
    }

    // =========================

    private fun preloadPostersForImportedJsons() {
        val ctx = requireContext()
        val sizePx = (POSTER_PRELOAD_SIZE_DP * ctx.resources.displayMetrics.density).toInt()

        val all = jsonDataManager.getImportedJsons().flatMap { it.videos }
        for (v in all) {
            val url = v.cardImageUrl?.trim().orEmpty()
            if (url.isNotEmpty() && preloadedPosterUrls.add(url)) {
                Glide.with(ctx)
                    .load(url)
                    .override(sizePx, sizePx)
                    .centerCrop()
                    .preload()
            }
        }

    }

    private fun loadRows() {
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val cardPresenter = CardPresenter()

        // =========================
        // ✅ ACCIONES PARA VIDEOS
        // =========================
        val actionsAdapter = ArrayObjectAdapter(GridItemPresenter()).apply {
            add("Importar de DISPOSITIVO")
            add(getString(R.string.erase_json))
            add("Borrar todos los JSON")
        }
        rowsAdapter.add(ListRow(HeaderItem(0L, "ACCIONES PRINCIPALES"), actionsAdapter))

        // =========================
        // ✅ ARMADO DE REPRODUCCIÓN
        // =========================
        val playbackBuildAdapter = ArrayObjectAdapter(GridItemPresenter()).apply {
            add("Generar playlist RANDOM")
            add("Actualizar playlist RANDOM")
            add("Borrar playlists RANDOM")
            add("Borrar TODAS las playlists RANDOM")
        }
        rowsAdapter.add(ListRow(HeaderItem(1L, "ARMADO DE REPRODUCCIÓN"), playbackBuildAdapter))


        // =========================
        // ✅ OPCIONES AVANZADAS
        // =========================
        val advancedActions = ArrayObjectAdapter(GridItemPresenter()).apply {
            add("Importar de SMB")
            add("Conectarse a un SMB")
            add("Limpiar credenciales especificas")
            add("Limpiar credenciales")
        }
        rowsAdapter.add(ListRow(HeaderItem(2L, "ACCIONES AVANZADAS"), advancedActions))


        // =========================
        // ✅ CATALOGO
        // =========================
        val importedAll = jsonDataManager.getImportedJsons()

        fun isRandomImported(imported: ImportedJson): Boolean =
            imported.fileName.uppercase(Locale.ROOT).startsWith("RANDOM")

        val importedSorted = importedAll.sortedWith(
            compareByDescending<ImportedJson> { isRandomImported(it) }
                .thenBy { prettyTitle(it.fileName).lowercase(Locale.ROOT) }
        )

        val randomCover = "android.resource://${requireContext().packageName}/drawable/dados"

        importedSorted.forEachIndexed { idx, imported ->
            val rowAdapter = ArrayObjectAdapter(cardPresenter)

            if (isRandomImported(imported)) {
                // ✅ UNA SOLA CARD (launcher de playlist RANDOM)
                rowAdapter.add(
                    Movie(
                        title = prettyTitle(imported.fileName),
                        videoUrl = "playlist://${imported.fileName}",
                        cardImageUrl = randomCover,
                        backgroundImageUrl = null,
                        skipToSecond = 0,
                        delaySkip = 0,
                        description = "Playlist RANDOM"
                    )
                )
            } else {
                imported.videos.forEach { v -> rowAdapter.add(v.toMovie()) }
            }

            val headerId = 1000L + idx
            rowsAdapter.add(
                ListRow(
                    HeaderItem(headerId, prettyTitle(imported.fileName)),
                    rowAdapter
                )
            )
        }

        adapter = rowsAdapter
    }


    private fun VideoItem.toMovie() = Movie(
        title = title,
        videoUrl = videoUrl,
        cardImageUrl = cardImageUrl,
        backgroundImageUrl = backgroundImageUrl,
        skipToSecond = skip,
        delaySkip = delaySkip,
        description = "Importado desde un JSON"
    )


    private fun setupEventListeners() {
        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }
        onItemViewClickedListener = ItemViewClickedListener()

        // ✅ DEBUG: ver qué card queda realmente seleccionada (esto te dice la verdad)
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, row ->
            val rowTitle = (row as? ListRow)?.headerItem?.name
            val m = item as? Movie

            Log.d(
                TAG,
                "FOCUSDBG SELECTED rowTitle=$rowTitle item=${item?.javaClass?.simpleName} url=${m?.videoUrl}"
            )
        }
    }


    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder,
            item: Any,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            Log.d(TAG, "CLICK Search/TV item=${item::class.java.name} row=${row::class.java.name} view=${itemViewHolder.view::class.java.name}")

            when (item) {
                is Movie -> navigateToDetails(itemViewHolder, item, row)
                is String -> handleStringAction(item)
                else -> Log.w(TAG, "CLICK unhandled type=${item::class.java.name} item=$item")
            }
        }

        private fun navigateToDetails(
            itemViewHolder: Presenter.ViewHolder,
            movie: Movie,
            row: Row
        ) {

            val clickedUrl = movie.videoUrl?.trim().orEmpty()
            if (clickedUrl.isNotEmpty()) {
                writeLastPlayedUrl(clickedUrl)   // ✅ SIEMPRE la última card clickeada (incluye playlist://)
            }


            // =========================
            // ✅ PLAYLIST RANDOM
            // =========================
            if (movie.videoUrl?.startsWith("playlist://") == true) {
                // ✅ PLAYLIST RANDOM (launcher)
                val url = movie.videoUrl ?: return

                if (url.startsWith("playlist://")) {
                    val playlistName = url.removePrefix("playlist://").trim()

                    val imported = jsonDataManager.getImportedJsons()
                        .firstOrNull { it.fileName == playlistName }

                    if (imported == null || imported.videos.isEmpty()) {
                        Toast.makeText(requireContext(), "Playlist no encontrada o vacía", Toast.LENGTH_LONG).show()
                        return
                    }

                    val playlist = ArrayList<Movie>().apply {
                        imported.videos.forEach { v -> add(v.toMovie()) }
                    }

                    val intent = Intent(requireContext(), DetailsActivity::class.java).apply {
                        putExtra(DetailsActivity.MOVIE, playlist[0])
                        putExtra(DetailsActivity.EXTRA_PLAYLIST, playlist)
                        putExtra(DetailsActivity.EXTRA_INDEX, 0)

                        // 🔁 SOLO RANDOM
                        putExtra("EXTRA_LOOP_PLAYLIST", true)
                        putExtra("EXTRA_DISABLE_LAST_PLAYED", true)
                    }

                    pendingFocusLastPlayed = false

                    val cardView = itemViewHolder.view as? ImageCardView
                    val shared = cardView?.mainImageView

                    if (shared != null) {
                        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                            requireActivity(),
                            shared,
                            DetailsActivity.SHARED_ELEMENT_NAME
                        )
                        startActivity(intent, options.toBundle())
                    } else {
                        startActivity(intent)
                    }
                    return
                }

            }

            // =========================
            // ✅ FLUJO NORMAL
            // =========================
            val listRow = row as? ListRow
            val adapter = listRow?.adapter

            val playlist = ArrayList<Movie>()
            if (adapter != null) {
                for (i in 0 until adapter.size()) {
                    val obj = adapter.get(i)
                    if (obj is Movie) playlist.add(obj)
                }
            }

            val index = playlist.indexOfFirst { it.videoUrl == movie.videoUrl }
                .let { if (it >= 0) it else 0 }

            val intent = Intent(requireContext(), DetailsActivity::class.java).apply {
                putExtra(DetailsActivity.MOVIE, movie)
                if (playlist.size > 1) {
                    putExtra(DetailsActivity.EXTRA_PLAYLIST, playlist)
                    putExtra(DetailsActivity.EXTRA_INDEX, index)
                }
            }

            pendingFocusLastPlayed = true

            val cardView = itemViewHolder.view as? ImageCardView
            val shared = cardView?.mainImageView

            if (shared != null) {
                val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    requireActivity(),
                    shared,
                    DetailsActivity.SHARED_ELEMENT_NAME
                )
                startActivity(intent, options.toBundle())
            } else {
                startActivity(intent)
            }
        }

        private fun handleStringAction(item: String) {
            when (item) {

                // ACCIONES AVANZADAS
                "Conectarse a un SMB" -> openSmbConnectFlow()
                "Limpiar credenciales especificas" -> showDeleteSpecificSmbDialog()
                "Limpiar credenciales" -> showClearSmbDialog()
                "Importar de SMB" -> runAutoImport()

                // ✅ ARMADO DE REPRODUCCIÓN
                "Generar playlist RANDOM" -> runRandomGenerate()
                "Actualizar playlist RANDOM" -> runRandomUpdate()
                "Borrar playlists RANDOM" -> runRandomDeleteSelected()
                "Borrar TODAS las playlists RANDOM" -> runRandomDeleteAll()

                // ✅ ACCIONES PRINCIPALES
                getString(R.string.erase_json) -> showDeleteDialog()
                "Borrar todos los JSON" -> showDeleteAllDialog()
                "Importar de DISPOSITIVO" -> requestLocalImportWithPermission()
                else -> Toast.makeText(requireContext(), item, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun runRandomGenerate() {
        randomizeImporter.actionGenerateRandom(
            toast = { msg -> activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show() } },
            onDone = { activity?.runOnUiThread { refreshUI() } },
            onError = { err -> activity?.runOnUiThread { Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show() } }
        )
    }

    private fun runRandomUpdate() {
        randomizeImporter.actionUpdateRandom(
            toast = { msg -> activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show() } },
            onDone = { activity?.runOnUiThread { refreshUI() } },
            onError = { err -> activity?.runOnUiThread { Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show() } }
        )
    }

    private fun runRandomDeleteSelected() {
        randomizeImporter.actionDeleteRandomPlaylists(
            toast = { msg -> activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show() } },
            onDone = { activity?.runOnUiThread { refreshUI() } },
            onError = { err -> activity?.runOnUiThread { Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show() } }
        )
    }

    private fun runRandomDeleteAll() {
        randomizeImporter.actionDeleteAllRandomPlaylists(
            toast = { msg -> activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show() } },
            onDone = { activity?.runOnUiThread { refreshUI() } },
            onError = { err -> activity?.runOnUiThread { Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show() } }
        )
    }


    private fun showClearSmbDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset SMB")
            .setMessage("Esto borra credenciales y shares guardados. Vas a tener que reconectar el SMB. ¿Continuar?")
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

    private fun requestLocalImportWithPermission() {
        if (ensureAllFilesAccessTv()) {
            runLocalAutoImport()
        } else {
            Toast.makeText(
                requireContext(),
                "Habilitá 'Acceso a todos los archivos' y volvé a intentar",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun runLocalAutoImport() {
        LocalAutoImporter(
            context = requireContext(),
            jsonDataManager = jsonDataManager,
            serverPort = 8080
        ).run(
            toast = { msg -> activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show() } },
            onDone = { activity?.runOnUiThread { refreshUI() } },
            onError = { err -> activity?.runOnUiThread { Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show() } }
        )
    }

    private fun runAutoImport() {
        AutoImporter(
            context = requireContext(),
            smbGateway = smbGateway,
            jsonDataManager = jsonDataManager,
            proxyPort = 8081
        ).run(
            toast = { msg -> activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show() } },
            onDone = { activity?.runOnUiThread { refreshUI() } },
            onError = { err -> activity?.runOnUiThread { Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show() } }
        )
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
                preloadedPosterUrls.clear()
                refreshUI()
                Toast.makeText(requireContext(), "JSONs eliminados", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openSmbConnectFlow() {
        Toast.makeText(requireContext(), "Buscando SMBs en la red...", Toast.LENGTH_SHORT).show()

        val found = LinkedHashMap<String, SmbGateway.SmbServer>()

        smbGateway.discoverAll(
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
                .setItems(labels) { _, which -> showCredentialsDialog(servers[which]) }
                .setNegativeButton("Cancelar", null)
                .show()
        }, 2000)
    }

    private fun showManualSmbDialog() {
        val hostInput = EditText(requireContext()).apply { hint = "IP o hostname (ej: 192.168.1.33)" }

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

    private fun showDeleteDialog() {
        val imported = jsonDataManager.getImportedJsons()
        if (imported.isEmpty()) {
            Toast.makeText(requireContext(), "No hay JSONs para borrar", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = imported.map { prettyTitle(it.fileName) }.toTypedArray()

        AlertDialog.Builder(requireContext()).apply {
            setTitle("Eliminar JSON")
            setItems(labels) { _, which ->
                val realName = imported[which].fileName
                jsonDataManager.removeJson(requireContext(), realName)
                preloadedPosterUrls.clear()
                refreshUI()
            }
            setNegativeButton("Cancelar", null)
            show()
        }
    }

    private fun refreshUI() {
        jsonDataManager.loadData(requireContext())
        preloadPostersForImportedJsons()
        loadRows()

        // ✅ en refresh normal, volver a acciones
        mHandler.post { focusFirstItemReal() }
    }


    private inner class GridItemPresenter : Presenter() {

        private fun dp(v: Int): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                requireContext().resources.displayMetrics
            ).toInt()

        private fun makeBg(focused: Boolean): Drawable {
            return android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()

                val fill = if (focused) 0xFF0E8A9A.toInt() else 0xFF2C2C2C.toInt()
                val stroke = if (focused) 0xFFFFFFFF.toInt() else 0x66FFFFFF.toInt()

                setColor(fill)
                setStroke(dp(2), stroke)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val ctx = parent.context

            val container = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(dp(180), dp(180))
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                background = makeBg(false)
                isFocusable = true
                isFocusableInTouchMode = true
            }

            val tv = TextView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                includeFontPadding = false
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.05f)
            }

            container.addView(tv)

            container.setOnFocusChangeListener { v, hasFocus ->
                v.background = makeBg(hasFocus)
                v.animate()
                    .scaleX(if (hasFocus) 1.04f else 1f)
                    .scaleY(if (hasFocus) 1.04f else 1f)
                    .setDuration(120)
                    .start()
            }

            return ViewHolder(container)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            val container = viewHolder.view as FrameLayout
            val tv = container.getChildAt(0) as TextView
            tv.text = (item as String).uppercase(Locale.getDefault())
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
    }

    companion object {
        private const val TAG = "MainFragment"
        private const val POSTER_PRELOAD_SIZE_DP = 180
        private const val DEBUG_LOGS = false

        private const val PREFS_NAME = "watchoffline_prefs"
        private const val KEY_LAST_PLAYED = "LAST_PLAYED_VIDEO_URL"
    }

}
