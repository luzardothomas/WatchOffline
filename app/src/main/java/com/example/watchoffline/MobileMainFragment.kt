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

class MobileMainFragment : Fragment(R.layout.fragment_mobile_main) {

    companion object {
        private const val TAG = "MobileMainFragment"
    }

    private val jsonDataManager = JsonDataManager()

    // ✅ SMB
    private lateinit var smbGateway: SmbGateway

    // ✅ Search (inline)
    private var currentQuery: String = ""
    private val handler = Handler(Looper.getMainLooper())

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSearchUi(view)
        render(view)
    }

    // =========================
    // ✅ Search UI (inline, no SearchView)
    // =========================

    private fun setupSearchUi(root: View) {
        val btn = root.findViewById<ImageButton>(R.id.btnToggleSearch)
        val input = root.findViewById<EditText>(R.id.searchInput)

        fun setSearchVisible(visible: Boolean) {
            input.visibility = if (visible) View.VISIBLE else View.GONE
            btn.setImageResource(
                if (visible) android.R.drawable.ic_menu_close_clear_cancel
                else android.R.drawable.ic_menu_search
            )

            if (!visible) {
                input.setText("")
                input.clearFocus()
                currentQuery = ""
                refreshUI()
            } else {
                input.requestFocus()
            }
        }

        btn.setOnClickListener {
            setSearchVisible(input.visibility != View.VISIBLE)
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

        rv.adapter = MobileSectionsAdapter(
            sections = sections,
            onMovieClick = { item ->
                when (item.videoUrl) {
                    "__action_erase_json__" -> showDeleteDialog()
                    "__action_erase_all_json__" -> showDeleteAllDialog()
                    "__action_connect_smb__" -> openSmbConnectFlow()
                    "__action_clear_smb__" -> showClearSmbDialog()
                    "__action_auto_import__" -> runAutoImport()
                    "__action_auto_import_local_folder__" -> showLocalFolderImportDialog()
                    else -> {
                        startActivity(
                            Intent(requireContext(), DetailsActivity::class.java).apply {
                                putExtra(DetailsActivity.MOVIE, item)
                            }
                        )
                    }
                }
            }
        )
    }

    private fun refreshUI() {
        jsonDataManager.loadData(requireContext())
        view?.let { render(it) }
    }

    // =========================
    // ✅ Sections + Filter
    // =========================

    private fun buildSectionsFiltered(queryRaw: String): List<MobileSection> {
        // ✅ respeta el orden EXACTO que pusiste
        val actionsSection = MobileSection(
            title = "ACCIONES",
            items = listOf(
                actionCard("Borrar JSON", "__action_erase_json__"),
                actionCard("Borrar todos los JSON", "__action_erase_all_json__"),
                actionCard("Credenciales SMB", "__action_connect_smb__"),
                actionCard("Limpiar credenciales", "__action_clear_smb__"),
                actionCard("Importar de SMB", "__action_auto_import__"),
                actionCard("Importar de RUTA", "__action_auto_import_local_folder__"),
            )
        )

        val q = normalize(queryRaw)
        val imported = jsonDataManager.getImportedJsons()

        val contentSections = if (q.isBlank()) {
            imported.map { one ->
                MobileSection(
                    title = prettyTitle(one.fileName),
                    items = one.videos.map { it.toMovie() }
                )
            }
        } else {
            imported.mapNotNull { one ->
                val jsonTitle = prettyTitle(one.fileName)
                val jsonMatch = normalize(jsonTitle).contains(q)

                val filtered = one.videos.filter { v ->
                    jsonMatch || normalize(v.title).contains(q)
                }

                if (filtered.isEmpty()) null
                else MobileSection(
                    title = jsonTitle,
                    items = filtered.map { it.toMovie() }
                )
            }
        }

        return listOf(actionsSection) + contentSections
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

    private fun VideoItem.toMovie() = Movie(
        title = title,
        videoUrl = videoSrc,
        cardImageUrl = imgSml,
        backgroundImageUrl = imgBig,
        skipToSecond = skipToSecond,
        description = "Importado desde un JSON"
    )

    // =========================
    // SMB / Local actions
    // =========================

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

    // =========================
    // Delete dialogs
    // =========================

    private fun showDeleteDialog() {
        val imported = jsonDataManager.getImportedJsons()
        if (imported.isEmpty()) {
            Toast.makeText(requireContext(), "No hay JSONs para borrar", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = imported.map { prettyTitle(it.fileName) }.toTypedArray()

        AlertDialog.Builder(requireContext()).apply {
            setTitle("Borrar JSON")
            setItems(labels) { _, which ->
                val realName = imported[which].fileName
                jsonDataManager.removeJson(requireContext(), realName)
                refreshUI()
            }
            setNegativeButton("Cancelar", null)
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
}

data class MobileSection(
    val title: String,
    val items: List<Movie>
)



