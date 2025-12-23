package com.example.watchoffline

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import java.util.LinkedHashMap
import java.util.UUID

class MobileMainFragment : Fragment(R.layout.fragment_mobile_main) {

    private val jsonDataManager = JsonDataManager()

    // ✅ SMB
    private lateinit var smbGateway: SmbGateway

    private val REQUEST_CODE_IMPORT_JSON = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        jsonDataManager.loadData(requireContext())

        smbGateway = SmbGateway(requireContext())
        val ok = smbGateway.ensureProxyStarted(8081)
        Log.d("MobileMainFragment", "SMB proxy started? $ok port=${smbGateway.getProxyPort()}")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { smbGateway.stopDiscovery() } catch (_: Exception) {}
        try { smbGateway.stopProxy() } catch (_: Exception) {}
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        render(view)
    }

    private fun render(rootView: View) {
        val rv = rootView.findViewById<RecyclerView>(R.id.sectionsRecycler)
        rv.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)

        val sections = buildSections()

        rv.adapter = MobileSectionsAdapter(
            sections = sections,
            onMovieClick = { item ->
                when (item.videoUrl) {
                    "__action_import_json__" -> openFilePicker()
                    "__action_erase_json__" -> showDeleteDialog()
                    "__action_erase_all_json__" -> showDeleteAllDialog()
                    "__action_connect_smb__" -> openSmbConnectFlow()

                    // ✅ ahora usa helper compartido
                    "__action_auto_import__" -> runAutoImport()

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

    private fun buildSections(): List<MobileSection> {
        // ✅ fila de acciones (TODOS los botones)
        val actionsSection = MobileSection(
            title = "ACCIONES",
            items = listOf(
                actionCard("Importar JSON", "__action_import_json__"),
                actionCard("Borrar JSON", "__action_erase_json__"),
                actionCard("Borrar TODOS", "__action_erase_all_json__"),
                actionCard("Conectar SMB", "__action_connect_smb__"),
                actionCard("Importar automáticamente", "__action_auto_import__")
            )
        )

        val contentSections = jsonDataManager.getImportedJsons().map { imported ->
            MobileSection(
                title = imported.fileName,
                items = imported.videos.map { it.toMovie() }
            )
        }

        return listOf(actionsSection) + contentSections
    }

    private fun actionCard(title: String, actionId: String) = Movie(
        title = title,
        videoUrl = actionId,           // 🔥 usamos videoUrl como "id de acción"
        cardImageUrl = "",             // vacío => se dibuja “caja” en el adapter (sin logos)
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
        description = "Imported from JSON"
    )

    // =========================
    // ✅ AUTO IMPORT (Shared helper)
    // =========================

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

    // =========================
    // ✅ JSON IMPORT (MANUAL)
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
            Toast.makeText(requireContext(), "No se pudo abrir selector: ${e.message}", Toast.LENGTH_LONG).show()
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
        if (items.isEmpty()) {
            Toast.makeText(requireContext(), "No hay JSONs para borrar", Toast.LENGTH_SHORT).show()
            return
        }

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
                Toast.makeText(requireContext(), "JSONs eliminados", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // =========================
    // ✅ SMB CONNECT FLOW (igual a TV)
    // =========================

    private fun openSmbConnectFlow() {
        Toast.makeText(requireContext(), "Buscando SMBs en la red...", Toast.LENGTH_SHORT).show()

        val found = LinkedHashMap<String, SmbGateway.SmbServer>()

        smbGateway.discover(
            onFound = { server -> found[server.id] = server },
            onError = { err ->
                Log.e("MobileMainFragment", err)
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
                        Log.e("MobileMainFragment", "SMB connect FAILED", e)
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
