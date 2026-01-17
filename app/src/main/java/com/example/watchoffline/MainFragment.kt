package com.example.watchoffline

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.StateSet
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
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.util.LinkedHashMap
import java.util.Locale

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Optimización visual básica
        hideHeadersDockCompletely(view)
        disableSearchOrbFocus(view)
        view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.default_background))

        // ✅ OPTIMIZACIÓN CRÍTICA DE SCROLL (La magia para que vuele)
        setupSmoothScrolling(view)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        jsonDataManager.loadData(requireContext())
        randomizeImporter = RandomizeImporter(requireContext(), jsonDataManager)
        smbGateway = SmbGateway(requireContext())
        smbGateway.ensureProxyStarted(8081)

        // Preload ligero (solo primeras imágenes)
        preloadPostersForImportedJsons()

        setupUIElements()
        loadRows()
        setupEventListeners()

        mHandler.post { focusFirstItemReal() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { smbGateway.stopDiscovery() } catch (_: Exception) {}
        try { smbGateway.stopProxy() } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        mHandler.post { focusLastPlayedIfAny() }
        // Aseguramos que Glide vuelva a cargar si quedó pausado
        Glide.with(requireContext()).resumeRequests()
    }


    // ==========================================
    // ✅ OPTIMIZACIÓN DE SCROLL / GLIDE
    // ==========================================
    private fun setupSmoothScrolling(root: View) {
        // Buscamos la lista vertical interna de Leanback
        val verticalGrid = root.findViewById<VerticalGridView>(androidx.leanback.R.id.browse_grid) ?: return

        verticalGrid.apply {
            // 1. Aumentar caché: Guarda 20 filas en memoria.
            setItemViewCacheSize(20)

            // 2. Optimización de layout
            setHasFixedSize(true)

            // 3. PAUSAR IMÁGENES AL SCROLLEAR
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        // Si frenó, cargamos imágenes
                        if (isAdded) Glide.with(requireContext()).resumeRequests()
                    } else {
                        // Si se mueve, PAUSA total. Prioridad a la animación.
                        if (isAdded) Glide.with(requireContext()).pauseRequests()
                    }
                }
            })
        }
    }

    private fun setupUIElements() {
        title = getString(R.string.browse_title)
        headersState = HEADERS_HIDDEN
        isHeadersTransitionOnBackEnabled = false
        val bg = ContextCompat.getColor(requireContext(), R.color.default_background)
        brandColor = bg
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.search_opaque)
    }

    // ✅ OPTIMIZACIÓN LIST ROW CORREGIDA: Sin sombras, sin efectos, PERO CON TÍTULOS
    private fun createOptimizedListRowPresenter(): ListRowPresenter {
        return ListRowPresenter().apply {
            shadowEnabled = false        // Sin sombras (GPU heavy)
            selectEffectEnabled = false  // Sin efecto zoom/dim automático

            // ❌ headerPresenter = null  <-- ESTO BORRABA LOS TÍTULOS. LO QUITAMOS.
        }
    }

    private fun loadRows() {
        // Usamos el presenter ULTRA optimizado
        val rowsAdapter = ArrayObjectAdapter(createOptimizedListRowPresenter())
        val cardPresenter = CardPresenter()

        // =========================
        // ACCIONES PRINCIPALES
        // =========================
        val actionsAdapter = ArrayObjectAdapter(GridItemPresenter()).apply {
            add("Importar de DISPOSITIVO")
            add("Actualizar portadas de JSONS")
            add(getString(R.string.erase_json))
            add("Borrar todos los JSON")
        }
        rowsAdapter.add(ListRow(HeaderItem(0L, "ACCIONES PRINCIPALES"), actionsAdapter))

        // =========================
        // ARMADO DE REPRODUCCIÓN
        // =========================
        val playbackBuildAdapter = ArrayObjectAdapter(GridItemPresenter()).apply {
            add("Generar playlist RANDOM")
            add("Actualizar playlist RANDOM")
            add("Borrar playlists RANDOM")
            add("Borrar TODAS las playlists RANDOM")
        }
        rowsAdapter.add(ListRow(HeaderItem(1L, "ARMADO DE REPRODUCCIÓN"), playbackBuildAdapter))

        // =========================
        // ACCIONES AVANZADAS
        // =========================
        val advancedActions = ArrayObjectAdapter(GridItemPresenter()).apply {
            add("Importar de SMB")
            add("Conectarse a un SMB")
            add("Limpiar credenciales especificas")
            add("Limpiar credenciales")
        }
        rowsAdapter.add(ListRow(HeaderItem(2L, "ACCIONES AVANZADAS"), advancedActions))

        // =========================
        // CATALOGO
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

    // ==========================================
    // GRID ITEM PRESENTER (Zero Allocation)
    // ==========================================
    private inner class GridItemPresenter : Presenter() {

        private var density: Float = 0f
        private var initialized = false

        private val colorFocused = 0xFF0E8A9A.toInt()
        private val colorDefault = 0xFF2C2C2C.toInt()
        private val strokeFocused = 0xFFFFFFFF.toInt()
        private val strokeDefault = 0x66FFFFFF.toInt()

        private fun initMetrics(ctx: Context) {
            if (!initialized) {
                density = ctx.resources.displayMetrics.density
                initialized = true
            }
        }

        private fun dp(v: Int): Int = (v * density).toInt()

        private fun createStateListDrawable(): StateListDrawable {
            val res = StateListDrawable()

            val focusedDr = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(colorFocused)
                setStroke(dp(2), strokeFocused)
            }

            val defaultDr = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(colorDefault)
                setStroke(dp(2), strokeDefault)
            }

            res.addState(intArrayOf(android.R.attr.state_focused), focusedDr)
            res.addState(StateSet.WILD_CARD, defaultDr)

            // Eliminamos fade duration para respuesta instantánea en scroll rápido
            res.setEnterFadeDuration(0)
            res.setExitFadeDuration(0)

            return res
        }

        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val ctx = parent.context
            initMetrics(ctx)

            val container = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(dp(180), dp(180))
                // Clip simple, menos costoso
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                background = createStateListDrawable()
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
                // Animación muy corta y simple
                v.animate()
                    .scaleX(if (hasFocus) 1.05f else 1f)
                    .scaleY(if (hasFocus) 1.05f else 1f)
                    .setDuration(100)
                    .start()
            }

            return ViewHolder(container)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            val container = viewHolder.view as FrameLayout
            val tv = container.getChildAt(0) as TextView
            tv.text = (item as String).uppercase(Locale.getDefault())
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
    }

    // ==========================================
    // Helpers
    // ==========================================

    private fun hideHeadersDockCompletely(root: View) {
        root.post {
            val bg = ContextCompat.getColor(requireContext(), R.color.default_background)
            root.findViewById<ViewGroup>(androidx.leanback.R.id.browse_headers_dock)?.apply {
                setBackgroundColor(bg)
                layoutParams = layoutParams.apply { width = 1 }
                alpha = 0f
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            root.findViewById<ViewGroup>(androidx.leanback.R.id.browse_headers)?.apply {
                alpha = 0f
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            root.findViewById<View>(androidx.leanback.R.id.browse_frame)?.apply {
                setPadding(0, paddingTop, paddingRight, paddingBottom)
            }
        }
    }

    private fun disableSearchOrbFocus(root: View) {
        root.post {
            root.findViewById<View>(androidx.leanback.R.id.search_orb)?.apply {
                isFocusable = false
                clearFocus()
            }
        }
    }

    private fun focusFirstItemReal() {
        val root = view ?: return
        root.post {
            setSelectedPosition(0, false)
            val vgrid = root.findViewById<VerticalGridView>(androidx.leanback.R.id.browse_grid) ?: return@post
            vgrid.post {
                val firstRowView = vgrid.getChildAt(0) ?: return@post
                val rowContent = firstRowView.findViewById<HorizontalGridView>(androidx.leanback.R.id.row_content) ?: return@post
                rowContent.post { rowContent.getChildAt(0)?.requestFocus() ?: rowContent.requestFocus() }
            }
        }
    }

    private fun writeLastPlayedUrl(url: String) {
        val u = url.trim()
        if (u.isEmpty()) return
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LAST_PLAYED, u).apply()
    }

    private fun readLastPlayedUrl(): String? {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_PLAYED, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun focusLastPlayedIfAny(): Boolean {
        val lastUrl = readLastPlayedUrl() ?: return false
        val rows = adapter as? ArrayObjectAdapter ?: return false

        var targetRowIndex = -1
        var targetColIndex = -1

        for (r in 0 until rows.size()) {
            val row = rows.get(r) as? ListRow ?: continue
            val rowAdapter = row.adapter ?: continue
            for (c in 0 until rowAdapter.size()) {
                val m = rowAdapter.get(c) as? Movie ?: continue
                if (m.videoUrl == lastUrl) {
                    targetRowIndex = r; targetColIndex = c; break
                }
            }
            if (targetRowIndex >= 0) break
        }

        if (targetRowIndex < 0) return false

        setSelectedPosition(targetRowIndex, false, object : Presenter.ViewHolderTask() {
            private var tries = 0
            override fun run(holder: Presenter.ViewHolder?) {
                tries++
                if (holder == null || holder.view == null) {
                    if (tries < 20) mHandler.postDelayed({ setSelectedPosition(targetRowIndex, false, this) }, 50)
                    return
                }
                val rowContent = holder.view.findViewById<HorizontalGridView>(androidx.leanback.R.id.row_content)
                if (rowContent == null) {
                    if (tries < 20) mHandler.postDelayed({ setSelectedPosition(targetRowIndex, false, this) }, 50)
                    return
                }
                rowContent.scrollToPosition(targetColIndex)
                rowContent.post {
                    rowContent.setSelectedPosition(targetColIndex)
                    rowContent.requestFocus()
                }
            }
        })
        return true
    }

    private fun preloadPostersForImportedJsons() {
        val ctx = requireContext()
        val sizePx = (POSTER_PRELOAD_SIZE_DP * ctx.resources.displayMetrics.density).toInt()
        val all = jsonDataManager.getImportedJsons().flatMap { it.videos }.take(20)
        for (v in all) {
            val url = v.cardImageUrl?.trim().orEmpty()
            if (url.isNotEmpty() && preloadedPosterUrls.add(url)) {
                Glide.with(ctx).load(url).override(sizePx, sizePx).centerCrop().preload()
            }
        }
    }

    private fun prettyTitle(fileName: String): String {
        return fileName.removeSuffix(".json")
            .replace("_", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString() } }
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
        setOnSearchClickedListener { startActivity(Intent(requireContext(), SearchActivity::class.java)) }
        onItemViewClickedListener = ItemViewClickedListener()
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(itemViewHolder: Presenter.ViewHolder, item: Any, rowViewHolder: RowPresenter.ViewHolder, row: Row) {
            when (item) {
                is Movie -> navigateToDetails(itemViewHolder, item, row)
                is String -> handleStringAction(item)
            }
        }

        private fun navigateToDetails(itemViewHolder: Presenter.ViewHolder, movie: Movie, row: Row) {
            val clickedUrl = movie.videoUrl?.trim().orEmpty()
            if (clickedUrl.isNotEmpty()) writeLastPlayedUrl(clickedUrl)

            if (movie.videoUrl?.startsWith("playlist://") == true) {
                val url = movie.videoUrl ?: return
                val playlistName = url.removePrefix("playlist://").trim()
                val imported = jsonDataManager.getImportedJsons().firstOrNull { it.fileName == playlistName }
                if (imported == null || imported.videos.isEmpty()) {
                    Toast.makeText(requireContext(), "Playlist vacía", Toast.LENGTH_LONG).show()
                    return
                }
                val playlist = ArrayList<Movie>().apply { imported.videos.forEach { v -> add(v.toMovie()) } }
                val intent = Intent(requireContext(), DetailsActivity::class.java).apply {
                    putExtra(DetailsActivity.MOVIE, playlist[0])
                    putExtra(DetailsActivity.EXTRA_PLAYLIST, playlist)
                    putExtra(DetailsActivity.EXTRA_INDEX, 0)
                    putExtra("EXTRA_LOOP_PLAYLIST", true)
                    putExtra("EXTRA_DISABLE_LAST_PLAYED", true)
                }
                pendingFocusLastPlayed = false
                startActivityWithAnim(itemViewHolder, intent)
                return
            }

            val listRow = row as? ListRow
            val adapter = listRow?.adapter
            val playlist = ArrayList<Movie>()
            if (adapter != null) {
                for (i in 0 until adapter.size()) {
                    val obj = adapter.get(i)
                    if (obj is Movie) playlist.add(obj)
                }
            }
            val index = playlist.indexOfFirst { it.videoUrl == movie.videoUrl }.let { if (it >= 0) it else 0 }
            val intent = Intent(requireContext(), DetailsActivity::class.java).apply {
                putExtra(DetailsActivity.MOVIE, movie)
                if (playlist.size > 1) {
                    putExtra(DetailsActivity.EXTRA_PLAYLIST, playlist)
                    putExtra(DetailsActivity.EXTRA_INDEX, index)
                }
            }
            pendingFocusLastPlayed = true
            startActivityWithAnim(itemViewHolder, intent)
        }

        private fun startActivityWithAnim(vh: Presenter.ViewHolder, intent: Intent) {
            val cardView = vh.view as? ImageCardView
            val shared = cardView?.mainImageView
            if (shared != null) {
                val opts = ActivityOptionsCompat.makeSceneTransitionAnimation(requireActivity(), shared, DetailsActivity.SHARED_ELEMENT_NAME)
                startActivity(intent, opts.toBundle())
            } else {
                startActivity(intent)
            }
        }

        private fun handleStringAction(item: String) {
            when (item) {
                "Conectarse a un SMB" -> openSmbConnectFlow()
                "Limpiar credenciales especificas" -> showSelectServerToDeleteDialog()
                "Limpiar credenciales" -> showClearSmbDialog()
                "Importar de SMB" -> runAutoImport()
                "Generar playlist RANDOM" -> runRandomGenerate()
                "Actualizar playlist RANDOM" -> runRandomUpdate()
                "Borrar playlists RANDOM" -> runRandomDeleteSelected()
                "Borrar TODAS las playlists RANDOM" -> runRandomDeleteAll()
                getString(R.string.erase_json) -> showDeleteDialog()
                "Borrar todos los JSON" -> showDeleteAllDialog()
                "Importar de DISPOSITIVO" -> requestLocalImportWithPermission()
                "Actualizar portadas de JSONS" -> {
                    val importedJsons = jsonDataManager.getImportedJsons()
                    // Obtenemos nombres, filtramos y ordenamos A-Z
                    val listForIntent = ArrayList(importedJsons.map { it.fileName }.sorted())

                    if (listForIntent.isEmpty()) {
                        Toast.makeText(requireContext(), "No hay archivos JSON", Toast.LENGTH_SHORT).show()
                    } else {
                        val intent = Intent(requireContext(), ImageSearchActivity::class.java).apply {
                            putStringArrayListExtra("TARGET_JSONS", listForIntent)
                        }
                        startActivityForResult(intent, 100)
                    }
                }
                else -> Toast.makeText(requireContext(), item, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100 && resultCode == android.app.Activity.RESULT_OK && data != null) {
            val imageUrl = data.getStringExtra("SELECTED_IMAGE_URL")
            val selectedJsonNames = data.getStringArrayListExtra("TARGET_JSONS")

            if (imageUrl != null && selectedJsonNames != null) {

                // 1. Obtenemos la lista actual de lo que ya tenemos cargado
                val currentData = jsonDataManager.getImportedJsons()

                selectedJsonNames.forEach { fileName ->
                    // 2. Buscamos el JSON original en la memoria
                    val originalImport = currentData.firstOrNull { it.fileName == fileName }

                    if (originalImport != null) {
                        // 3. Creamos una NUEVA lista de videos con la URL de imagen cambiada
                        // Usamos .copy() para cambiar solo la imagen en cada video
                        val updatedVideos = originalImport.videos.map { video ->
                            video.copy(cardImageUrl = imageUrl)
                        }

                        // 4. Usamos tu función UPSERT que ya borra el viejo y guarda el nuevo
                        jsonDataManager.upsertJson(requireContext(), fileName, updatedVideos)
                    }
                }

                // 5. Refrescamos la interfaz para ver los cambios
                refreshUI()

                Toast.makeText(requireContext(), "Portadas actualizadas", Toast.LENGTH_SHORT).show()
            }
        }
    }


    // ==========================================
    // Acciones Lógicas (Import, SMB, Dialogs)
    // ==========================================
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
            .setMessage("¿Borrar todas las credenciales y shares?")
            .setPositiveButton("Borrar") { _, _ ->
                smbGateway.clearAllSmbData()
                Toast.makeText(requireContext(), "Credenciales eliminadas ✅", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showSelectServerToDeleteDialog() {
        val savedServers = smbGateway.listCachedServers()

        if (savedServers.isEmpty()) {
            Toast.makeText(requireContext(), "No hay credenciales guardadas", Toast.LENGTH_SHORT).show()
            return
        }

        val options = savedServers.map { "${it.host} (${it.name})" }.toTypedArray()

        // CAMBIO: Añadimos R.style.Theme_AppCompat_Dialog_Alert explícitamente
        androidx.appcompat.app.AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Selecciona el servidor")
            .setItems(options) { _, which ->
                val selectedServer = savedServers[which]
                onDeleteServerClicked(selectedServer.id, selectedServer.name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun onDeleteServerClicked(serverId: String, serverName: String) {
        val savedShares = smbGateway.getSavedShares(serverId).toList()

        if (savedShares.isEmpty()) {
            smbGateway.deleteSpecificSmbData(serverId)
            refreshUI()
            return
        }

        val options = savedShares.toTypedArray()
        val selectedIndices = mutableSetOf<Int>()

        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Administrar shares de $serverName")
            .setMultiChoiceItems(options, null) { _, which, isChecked ->
                if (isChecked) selectedIndices.add(which) else selectedIndices.remove(which)
            }
            // Asignamos los roles, pero los reubicaremos programáticamente
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Eliminar seleccionados") { _, _ ->
                if (selectedIndices.isNotEmpty()) {
                    val sharesToDelete = selectedIndices.map { options[it] }
                    showConfirmationDialog("Borrar shares", "¿Eliminar ${sharesToDelete.size} share(s)?") {
                        smbGateway.removeMultipleShares(serverId, sharesToDelete)
                        refreshUI()
                    }
                }
            }
            .setPositiveButton("BORRAR TODO EL SMB") { _, _ ->
                showConfirmationDialog("Borrar todo", "¿Eliminar todo el SMB?") {
                    smbGateway.deleteSpecificSmbData(serverId)
                    refreshUI()
                }
            }

        val dialog = builder.create()
        dialog.show()

        // --- REORDENAMIENTO FORZADO POST-SHOW ---
        val btnPositive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
        val btnNeutral = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
        val btnNegative = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)

        // 1. Obtenemos el contenedor de los botones (el parent)
        val buttonParent = btnPositive.parent as? android.view.ViewGroup
        if (buttonParent != null) {
            // 2. Limpiamos el orden actual
            buttonParent.removeAllViews()

            // 3. Los agregamos en tu orden exacto: Izquierda -> Medio -> Derecha
            buttonParent.addView(btnNegative) // Cancelar
            buttonParent.addView(btnNeutral)  // Eliminar Seleccionados
            buttonParent.addView(btnPositive) // Borrar Todo el Server
        }
    }

    // También actualizamos esta para que use el mismo estilo y no de error
    private fun showConfirmationDialog(title: String, msg: String, onConfirm: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(title)
            .setMessage(msg)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("Eliminar") { dialog, _ ->
                onConfirm()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun ensureAllFilesAccessTv(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${requireContext().packageName}")))
                return false
            }
        }
        return true
    }

    private fun requestLocalImportWithPermission() {
        if (ensureAllFilesAccessTv()) runLocalAutoImport()
        else Toast.makeText(requireContext(), "Habilitá permisos de almacenamiento", Toast.LENGTH_LONG).show()
    }

    private fun runLocalAutoImport() {
        LocalAutoImporter(requireContext(), jsonDataManager, 8080).run(
            toast = { msg -> activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show() } },
            onDone = { activity?.runOnUiThread { refreshUI() } },
            onError = { err -> activity?.runOnUiThread { Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show() } }
        )
    }

    private fun runAutoImport() {
        AutoImporter(requireContext(), smbGateway, jsonDataManager, 8081).run(
            toast = { msg -> activity?.runOnUiThread { Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show() } },
            onDone = { activity?.runOnUiThread { refreshUI() } },
            onError = { err -> activity?.runOnUiThread { Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show() } }
        )
    }

    private fun showDeleteAllDialog() {
        val count = jsonDataManager.getImportedJsons().size
        if (count == 0) return
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar TODOS")
            .setMessage("Se borrarán $count JSONs. ¿Seguro?")
            .setPositiveButton("Eliminar") { _, _ ->
                jsonDataManager.removeAll(requireContext())
                preloadedPosterUrls.clear()
                refreshUI()
                Toast.makeText(requireContext(), "JSONs eliminados", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun openSmbConnectFlow() {
        Toast.makeText(requireContext(), "Buscando SMBs en la red...", Toast.LENGTH_SHORT).show()
        val found = LinkedHashMap<String, SmbGateway.SmbServer>()
        smbGateway.discoverAll(
            onFound = { server -> found[server.id] = server },
            onError = { err -> Toast.makeText(requireContext(), "No se pudo escanear SMB: $err", Toast.LENGTH_LONG).show() }
        )
        Handler(Looper.getMainLooper()).postDelayed({
            smbGateway.stopDiscovery()
            if (found.isEmpty()) {  return@postDelayed }
            val servers = found.values.toList()
            val labels = servers.map { "${it.name} (${it.host}:${it.port})" }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("SMB encontrados")
                .setItems(labels) { _, which -> showCredentialsDialog(servers[which]) }
                .setNegativeButton("Cancelar", null).show()
        }, 2000)
    }

    private fun showCredentialsDialog(server: SmbGateway.SmbServer) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(50, 20, 50, 0)
        }

        // Campo Usuario
        val userInput = EditText(requireContext()).apply {
            hint = "Usuario"
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        // Campo Contraseña
        val passInput = EditText(requireContext()).apply {
            hint = "Contraseña"
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        // Campo Share
        val shareInput = EditText(requireContext()).apply {
            hint = "Share (ej: pelis)"
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        layout.addView(userInput)
        layout.addView(passInput)
        layout.addView(shareInput)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Login: ${server.host}")
            .setView(layout)
            .setPositiveButton("Conectar") { _, _ ->
                val user = userInput.text.toString().trim()
                val pass = passInput.text.toString()
                val share = shareInput.text.toString().trim()
                if (user.isBlank() || share.isBlank()) return@setPositiveButton

                Thread {
                    try {
                        val creds = SmbGateway.SmbCreds(user, pass, null)
                        smbGateway.testLogin(server.host, creds)
                        smbGateway.testShareAccess(server.host, creds, share)
                        smbGateway.saveCreds(server.id, server.host, creds, server.port, server.name)
                        smbGateway.saveLastShare(server.id, share)
                        activity?.runOnUiThread { Toast.makeText(requireContext(), "SMB conectado ✅", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) {
                        activity?.runOnUiThread { Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .create()

        // Opcional: Para que al darle a "Done" en el último campo se ejecute el botón positivo
        shareInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }

        dialog.show()
    }

    private fun showDeleteDialog() {
        // 1. Obtener y ordenar la lista A-Z por nombre de archivo
        val imported = jsonDataManager.getImportedJsons()
            .sortedBy { it.fileName.lowercase(java.util.Locale.ROOT) }

        if (imported.isEmpty()) {
            Toast.makeText(requireContext(), "No hay archivos para eliminar", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. Generar los títulos "lindos" para mostrar en la lista
        val labels = imported.map { prettyTitle(it.fileName) }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar JSON")
            .setItems(labels) { _, which ->
                val targetFile = imported[which].fileName

                // 3. Diálogo de confirmación (Seguridad)
                AlertDialog.Builder(requireContext())
                    .setTitle("¿Confirmar eliminación?")
                    .setMessage("Vas a borrar: ${prettyTitle(targetFile)}")
                    .setNegativeButton("CANCELAR", null)
                    .setPositiveButton("ELIMINAR") { _, _ ->
                        // Ejecutar el borrado real
                        jsonDataManager.removeJson(requireContext(), targetFile)
                        preloadedPosterUrls.clear()
                        refreshUI()
                        Toast.makeText(requireContext(), "JSON eliminado", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .setNegativeButton("CERRAR", null)
            .show()
    }

    private fun refreshUI() {
        jsonDataManager.loadData(requireContext())
        preloadPostersForImportedJsons()
        loadRows()
        mHandler.post { focusFirstItemReal() }
    }

    companion object {
        private const val TAG = "MainFragment"
        private const val POSTER_PRELOAD_SIZE_DP = 180
        private const val PREFS_NAME = "watchoffline_prefs"
        private const val KEY_LAST_PLAYED = "LAST_PLAYED_VIDEO_URL"
    }
}