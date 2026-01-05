package com.example.watchoffline

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
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
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import java.util.LinkedHashMap
import java.util.Locale
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

    private val jsonDataManager = JsonDataManager()

    // ✅ SMB
    private lateinit var smbGateway: SmbGateway

    // ✅ Preload cache (para no golpear URLs al navegar)
    private val preloadedPosterUrls = HashSet<String>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        Log.i(TAG, "onCreate")

        jsonDataManager.loadData(requireContext())

        smbGateway = SmbGateway(requireContext())
        val ok = smbGateway.ensureProxyStarted(8081)
        Log.i(TAG, "SMB proxy started? $ok port=${smbGateway.getProxyPort()}")

        // ✅ Preload inicial (una vez)
        preloadPostersForImportedJsons()

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
            @Suppress("DEPRECATION")
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

    /**
     * ✅ Preload posters (imgSml) para que al navegar no esté pegándole a la red.
     * - Se ejecuta 1 sola vez por URL (HashSet)
     * - Cachea al tamaño real que usa CardPresenter (180dp)
     */
    private fun preloadPostersForImportedJsons() {
        val ctx = requireContext()
        val sizePx = (POSTER_PRELOAD_SIZE_DP * ctx.resources.displayMetrics.density).toInt()

        // IMPORTANTE: aplanamos todos los videos de todos los JSON importados
        val all = jsonDataManager.getImportedJsons().flatMap { it.videos }

        for (v in all) {
            val url = v.imgSml?.trim().orEmpty()
            if (url.isNotEmpty() && preloadedPosterUrls.add(url)) {
                Glide.with(ctx)
                    .load(url)
                    .override(sizePx, sizePx)
                    .centerCrop()
                    .preload()
            }
        }

        if (DEBUG_LOGS) {
            Log.i(TAG, "preload posters: added=${preloadedPosterUrls.size}")
        }

    }

    private fun loadRows() {
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val cardPresenter = CardPresenter()

        val actionsAdapter = ArrayObjectAdapter(GridItemPresenter()).apply {
            add(getString(R.string.erase_json))
            add("Eliminar todos los JSON")
            add("Credenciales SMB")
            add("Limpiar credenciales")
            add("Importar de SMB")
            add("Importar de DISPOSITIVO")
        }

        rowsAdapter.add(
            ListRow(
                HeaderItem(0L, "ACCIONES"),
                actionsAdapter
            )
        )

        jsonDataManager.getImportedJsons().forEach { imported ->
            val rowAdapter = ArrayObjectAdapter(cardPresenter).apply {
                imported.videos.forEach { add(it.toMovie()) }
            }

            rowsAdapter.add(
                ListRow(
                    HeaderItem(imported.fileName.hashCode().toLong(), prettyTitle(imported.fileName)),
                    rowAdapter
                )
            )
        }

        adapter = rowsAdapter
    }

    private fun VideoItem.toMovie() = Movie(
        title = title,
        videoUrl = videoSrc,
        cardImageUrl = imgSml,
        backgroundImageUrl = imgBig,
        skipToSecond = skipToSecond,
        description = "Importado desde un JSON"
    )

    private fun setupEventListeners() {
        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
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
            val intent = Intent(requireContext(), DetailsActivity::class.java).apply {
                putExtra(DetailsActivity.MOVIE, movie)
            }
            startActivity(intent)
        }

        private fun handleStringAction(item: String) {
            when (item) {
                getString(R.string.erase_json) -> showDeleteDialog()
                "Eliminar todos los JSON" -> showDeleteAllDialog()
                "Credenciales SMB" -> openSmbConnectFlow()
                "Limpiar credenciales" -> showClearSmbDialog()
                "Importar de SMB" -> runAutoImport()
                "Importar de DISPOSITIVO" -> requestLocalImportWithPermission()
                else -> Toast.makeText(requireContext(), item, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showClearSmbDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset SMB")
            .setMessage("Esto borra credenciales y shares guardados. Vas a tener que reconectar el SMB. ¿Continuar?")
            .setPositiveButton("Borrar") { _, _ ->
                smbGateway.clearAllSmbData()
                Toast.makeText(requireContext(), "SMB reseteado ✅", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null)
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
            toast = { msg ->
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            },
            onDone = { count ->
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
            onDone = { count ->
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
                preloadedPosterUrls.clear() // ✅ reset preload cache
                refreshUI()
                resetBackgroundToDefault()
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
            .setPositiveButton("Conectar", null)
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
                // ✅ al borrar, limpiamos cache de preload y recargamos (evita quedarse con URLs “muertas”)
                preloadedPosterUrls.clear()
                refreshUI()
            }
            setNegativeButton("Cancelar", null)
            show()
        }
    }

    private fun resetBackgroundToDefault() {
        mBackgroundTimer?.cancel()
        mBackgroundTimer = null
        mBackgroundUri = null
        mBackgroundManager.drawable = mDefaultBackground
    }

    private fun refreshUI() {
        jsonDataManager.loadData(requireContext())
        debugDumpFirstItem("after refreshUI() loadData()")

        // ✅ Preload para el nuevo dataset (solo 1 vez por URL)
        preloadPostersForImportedJsons()

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
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>?,
                    isFirstResource: Boolean
                ): Boolean {
                    Log.e(TAG, "BG Glide FAILED model=$model err=${e?.rootCauses?.firstOrNull()?.message}", e)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable?,
                    model: Any?,
                    target: Target<Drawable>?,
                    dataSource: DataSource?,
                    isFirstResource: Boolean
                ): Boolean = false
            })
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
        private fun dp(v: Int): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                requireContext().resources.displayMetrics
            ).toInt()

        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(dp(130), dp(100))
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundResource(R.drawable.action_tile_bg)

                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER

                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }

            tv.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.08f else 1f)
                    .scaleY(if (hasFocus) 1.08f else 1f)
                    .setDuration(120)
                    .start()
                v.alpha = if (hasFocus) 1f else 0.92f
            }

            return ViewHolder(tv)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            (viewHolder.view as TextView).text = (item as String).uppercase(Locale.getDefault())
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
    }

    // =========================
    // ✅ DEBUG HELPERS
    // =========================

    private fun debugDumpFirstItem(where: String) {
        val imported = jsonDataManager.getImportedJsons()
        val first = imported.firstOrNull()?.videos?.firstOrNull()
        Log.i(TAG, "DEBUG [$where] importedCount=${imported.size}")
        if (first == null) {
            Log.i(TAG, "DEBUG [$where] no videos")
            return
        }
        Log.i(TAG, "DEBUG [$where] firstVideo title='${first.title}' skip=${first.skipToSecond}")
        Log.i(TAG, "DEBUG [$where] firstVideo imgSml='${first.imgSml}' imgBig='${first.imgBig}'")
    }

    companion object {
        private const val TAG = "MainFragment"
        private const val BACKGROUND_UPDATE_DELAY = 300
        private const val POSTER_PRELOAD_SIZE_DP = 180

        // ✅ evita depender de BuildConfig
        private const val DEBUG_LOGS = false
    }

}
