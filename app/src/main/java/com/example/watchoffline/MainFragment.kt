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
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector


class MainFragment : BrowseSupportFragment() {

    private val mHandler = Handler(Looper.getMainLooper())
    private lateinit var mBackgroundManager: BackgroundManager
    private var mDefaultBackground: Drawable? = null
    private lateinit var mMetrics: DisplayMetrics
    private var mBackgroundTimer: Timer? = null
    private var mBackgroundUri: String? = null

    private val REQUEST_CODE_IMPORT_JSON = 1001
    private val jsonDataManager = JsonDataManager()

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

            // ✅ NUEVO
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

                // ✅ NUEVO
                "Importar automáticamente" -> autoImportPreviewFromSmb()

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
                Toast.makeText(requireContext(), "JSONs eliminados ✅", Toast.LENGTH_SHORT).show()
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

                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "SMB conectado ✅", Toast.LENGTH_LONG).show()
                            dialog.dismiss()

                            AlertDialog.Builder(requireContext())
                                .setTitle("SMB conectado ✅")
                                .setMessage(
                                    "Probá debug:\nhttp://127.0.0.1:8081/debug\n\n" +
                                            "lastServerId=${smbGateway.getLastServerId()}\n" +
                                            "lastShare=${smbGateway.getLastShare(server.id)}"
                                )
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "SMB connect FAILED", e)
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "Error SMB: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }

        dialog.show()
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

    private fun autoImportPreviewFromSmb() {
        Toast.makeText(requireContext(), "Analizando SMB... (máx 10 videos)", Toast.LENGTH_LONG).show()

        Thread {
            try {
                val serverId = smbGateway.getLastServerId()
                if (serverId.isNullOrBlank()) {
                    uiToast("No hay SMB guardado (lastServerId vacío). Conectá SMB primero.")
                    return@Thread
                }

                val share = smbGateway.getLastShare(serverId)
                if (share.isNullOrBlank()) {
                    uiToast("No hay share guardado (lastShare vacío). Re-conectá SMB.")
                    return@Thread
                }

                val cached = smbGateway.loadCreds(serverId)
                if (cached == null) {
                    uiToast("No hay credenciales cacheadas para serverId=$serverId")
                    return@Thread
                }

                val host = cached.first
                val creds = cached.second

                val mp4s = smbListMp4(host, creds, share, max = 10)

                val results = mutableListOf<String>()
                for (p in mp4s) {
                    val q = buildQueryForPath(p)
                    val cover = fetchCoverFromApi(q)

                    val title = cover?.id?.takeIf { it.isNotBlank() } ?: p.substringAfterLast("/").substringBeforeLast(".")
                    val img = cover?.url.orEmpty()
                    val skip = cover?.skipToSecond ?: 0

                    val videoSrc = "http://127.0.0.1:8081/smb/$serverId/$share/${smbGateway.encodePath(p)}"

                    val json = """
[
  {
    "title": ${jsonQuote(title)},
    "skipToSecond": $skip,
    "imgBig": ${jsonQuote(img)},
    "imgSml": ${jsonQuote(img)},
    "videoSrc": ${jsonQuote(videoSrc)}
  }
]
""".trim()

                    results.add("q=$q\nmp4=$p\n\n$json")
                }

                requireActivity().runOnUiThread {
                    if (results.isEmpty()) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Importar automáticamente")
                            .setMessage("No encontré .mp4 en el share '$share'.")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        val labels = results.mapIndexed { idx, txt ->
                            val first = txt.lineSequence().firstOrNull().orEmpty()
                            "${idx + 1}) $first"
                        }.toTypedArray()

                        AlertDialog.Builder(requireContext())
                            .setTitle("Preview JSONs (SMB) - ${results.size} items")
                            .setItems(labels) { _, which ->
                                AlertDialog.Builder(requireContext())
                                    .setTitle("JSON a importar (preview)")
                                    .setMessage(results[which])
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                            .setPositiveButton("Cerrar", null)
                            .show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "autoImportPreviewFromSmb failed", e)
                uiToast("Error: ${e.message}")
            }
        }.start()
    }

    /** Heurística simple:
     *  - si hay ".../<serie>/<temporada>/archivo.mp4" y temporada parece t1/s01/season1 -> q="serie temporada"
     *  - si no -> q=filename sin extensión
     */
    private fun buildQueryForPath(path: String): String {
        val clean = path.replace("\\", "/").trim('/')
        val parts = clean.split("/").filter { it.isNotBlank() }

        fun norm(s: String): String =
            s.lowercase().replace(Regex("""\s+"""), " ").trim()

        if (parts.size >= 3) {
            val parent2 = norm(parts[parts.size - 3])
            val parent1 = norm(parts[parts.size - 2])

            val looksSeason =
                parent1.matches(Regex("""(t|temp|season)\s*\d+""")) ||
                        parent1.matches(Regex("""s\d{1,2}"""))

            return if (looksSeason) {
                "${parent2} ${parent1}".replace(" ", "+")
            } else {
                norm(parts.last().substringBeforeLast(".")).replace(" ", "+")
            }
        }

        return norm(parts.last().substringBeforeLast(".")).replace(" ", "+")
    }

    private fun smbListMp4(
        host: String,
        creds: SmbGateway.SmbCreds,
        shareName: String,
        max: Int = 10
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
                        if (name.lowercase().endsWith(".mp4")) {
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

    private fun jsonQuote(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return "\"$escaped\""
    }

    private fun uiToast(msg: String) {
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }

    // =========================
    // JSON IMPORT
    // =========================
    private fun openFilePicker() {
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(this, REQUEST_CODE_IMPORT_JSON)
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

    private fun resetBackgroundToDefault() {
        mBackgroundTimer?.cancel()
        mBackgroundTimer = null
        mBackgroundUri = null

        // ✅ fuerza tu imagen default real (evita el degradado de Leanback)
        mBackgroundManager.drawable = mDefaultBackground
    }



    private fun refreshUI() {
        jsonDataManager.loadData(requireContext())
        loadRows()

        val hasAnyMovie = jsonDataManager.getImportedJsons().any { it.videos.isNotEmpty() }
        if (!hasAnyMovie) {
            resetBackgroundToDefault()
        } else {
            startBackgroundTimer()
        }
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
                else -> {
                    // ✅ cualquier cosa que NO sea Movie (ej: Preferences) vuelve al fondo default
                    resetBackgroundToDefault()
                }
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
