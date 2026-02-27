package com.example.watchoffline

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

class SmbGateway(private val context: Context) {

    data class SmbServer(val id: String, val name: String, val host: String, val port: Int = 445)
    data class SmbCreds(val username: String, val password: String, val domain: String? = null, val isLocal: Boolean = true)



    private val tag = "SmbGateway"

    // ✅ LOCK: Objeto de bloqueo estricto para operaciones de escritura en SharedPreferences
    // Esto evita que dos hilos escriban al mismo tiempo y corrompan la lista.
    private val prefsLock = Any()


    private fun keySharesSetFor(serverId: String) = "shares_set_$serverId"

    // =========================
    // ✅ LIMPIEZA DE CREDENCIALES SMB (Transaccional)
    // =========================

    /**
     * Borra un serverId específico de forma atómica.
     */
    fun clearServer(serverId: String) {
        synchronized(prefsLock) {
            // 1. Leer estado actual (Creando nueva instancia del Set para evitar referencias viejas)
            val currentIds = HashSet(prefs.getStringSet(KEY_SERVER_IDS, emptySet()) ?: emptySet())

            val wasRemoved = currentIds.remove(serverId)

            // 2. Preparar editor único
            val editor = prefs.edit()

            // 3. Borrar datos específicos
            editor.remove("creds_$serverId")
            editor.remove(keyNameFor(serverId))
            editor.remove(keyPortFor(serverId))
            editor.remove(keyShareFor(serverId))

            // 4. Actualizar lista maestra si hubo cambios
            if (wasRemoved) {
                editor.putStringSet(KEY_SERVER_IDS, currentIds)
            }

            // 5. Verificar si era el último usado
            val last = prefs.getString(KEY_LAST_SERVER_ID, null)
            if (last == serverId) {
                editor.remove(KEY_LAST_SERVER_ID)
            }

            // 6. COMMIT ÚNICO (Bloqueante para asegurar consistencia antes de soltar el lock)
            editor.commit()

            Log.i(tag, "Cleared SMB server: $serverId. Remaining: ${currentIds.size}")
        }
    }

    /** Borra absolutamente todo lo relacionado a SMB (recomendado para “reset”) */
    fun clearAllSmbData() {
        synchronized(prefsLock) {
            val ids = prefs.getStringSet(KEY_SERVER_IDS, emptySet()) ?: emptySet()
            val editor = prefs.edit()

            ids.forEach { id ->
                editor.remove("creds_$id")
                editor.remove(keyNameFor(id))
                editor.remove(keyPortFor(id))
                editor.remove(keyShareFor(id))
                editor.remove(keySharesSetFor(id))
            }

            editor.remove(KEY_SERVER_IDS)
            editor.remove(KEY_LAST_SERVER_ID)
            editor.remove(KEY_LAST_SHARE)

            editor.commit()
            Log.i(tag, "Cleared ALL SMB data")
        }
    }

    /**
     * Borra solo UN share específico de la lista.
     * - Si al borrarlo la lista queda vacía -> Borra todo el servidor.
     * - Si al borrarlo era el "share actual" -> Asigna otro share de la lista como actual.
     */
    fun removeShare(serverId: String, shareToRemove: String) {
        synchronized(prefsLock) {
            // Obtenemos el set actual (Mutable para poder editar)
            val currentSet = HashSet(getSavedShares(serverId))

            // Intentamos borrar
            if (currentSet.remove(shareToRemove)) {

                // CASO 1: Si ya no quedan shares, borramos el servidor entero
                if (currentSet.isEmpty()) {
                    Log.i(tag, "Al borrar share '$shareToRemove', el server quedó vacío. Borrando server completo.")
                    deleteSpecificSmbData(serverId)
                    return
                }

                // CASO 2: Quedan shares. Guardamos el nuevo set actualizado.
                val editor = prefs.edit()
                editor.putStringSet(keySharesSetFor(serverId), currentSet)

                // Verificar si el share que borramos era el que estaba marcado como "último usado"
                val lastUsedShare = prefs.getString(keyShareFor(serverId), null)
                if (lastUsedShare == shareToRemove) {
                    // Si borramos el activo, promovemos al primero que encontremos en la lista sobrante
                    val newDefault = currentSet.first()
                    editor.putString(keyShareFor(serverId), newDefault)

                    // Si además este server es el activo globalmente, actualizamos global
                    val globalLastId = prefs.getString(KEY_LAST_SERVER_ID, null)
                    if (globalLastId == serverId) {
                        editor.putString(KEY_LAST_SHARE, newDefault)
                    }
                }

                editor.apply()
                Log.i(tag, "Share eliminado: $shareToRemove. Quedan: ${currentSet.size}")
            }
        }
    }

    /** * Borra DE RAÍZ todo lo asociado a un Server ID (Credenciales, Shares, Puertos, Metadatos).
     */
    fun deleteSpecificSmbData(serverId: String) {
        synchronized(prefsLock) {
            val ids = prefs.getStringSet(KEY_SERVER_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()

            if (ids.contains(serverId)) {
                val editor = prefs.edit()

                // 1. Eliminamos los datos básicos
                editor.remove("creds_$serverId")
                editor.remove(keyNameFor(serverId))
                editor.remove(keyPortFor(serverId))

                // 2. Eliminamos los datos de Shares (IMPORTANTE: Borrar ambas keys)
                editor.remove(keyShareFor(serverId))      // El último share visitado
                editor.remove(keySharesSetFor(serverId))  // El Set de todos los shares <--- NUEVO

                // 3. Lo quitamos del índice global de IDs
                ids.remove(serverId)
                editor.putStringSet(KEY_SERVER_IDS, ids)

                // 4. Limpieza de punteros globales si era el server activo
                val lastId = prefs.getString(KEY_LAST_SERVER_ID, null)
                if (lastId == serverId) {
                    editor.remove(KEY_LAST_SERVER_ID)
                    editor.remove(KEY_LAST_SHARE)
                }

                editor.commit() // Usamos commit para asegurar que se borre antes de actualizar UI
                Log.i(tag, "Deleted COMPLETELY SMB data for ID: $serverId")
            }
        }
    }

    fun removeMultipleShares(serverId: String, sharesToRemove: List<String>) {
        synchronized(prefsLock) {
            val currentSet = HashSet(getSavedShares(serverId))
            var changed = false

            sharesToRemove.forEach { share ->
                if (currentSet.remove(share)) {
                    changed = true
                }
            }

            if (changed) {
                if (currentSet.isEmpty()) {
                    deleteSpecificSmbData(serverId)
                } else {
                    val editor = prefs.edit()
                    editor.putStringSet(keySharesSetFor(serverId), currentSet)

                    // Si el share activo estaba entre los borrados, ponemos el primero que quede
                    val lastUsed = prefs.getString(keyShareFor(serverId), null)
                    if (sharesToRemove.contains(lastUsed)) {
                        val newDefault = currentSet.first()
                        editor.putString(keyShareFor(serverId), newDefault)
                    }
                    editor.apply()
                }
            }
        }
    }

    /** Helper: detecta si un host es IPv6 (tiene ':') */
    fun isIpv6Host(host: String): Boolean = host.contains(":")

    /** Helper: devuelve servidores guardados pero priorizando IPv4 */
    fun listCachedServersPreferIpv4(): List<SmbServer> {
        val all = listCachedServers()
        val (ipv4, ipv6) = all.partition { !isIpv6Host(it.host) }
        return ipv4 + ipv6
    }


    // --- cache cifrada ---
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "smb_cache",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ===== Keys =====
    private val KEY_SERVER_IDS = "server_ids_index"
    private val KEY_LAST_SERVER_ID = "last_server_id"
    private val KEY_LAST_SHARE = "last_share"
    private fun keyShareFor(serverId: String) = "share_$serverId"

    // ✅ datos por serverId para UI multi-SMB
    private fun keyNameFor(serverId: String) = "name_$serverId"
    private fun keyPortFor(serverId: String) = "port_$serverId"

    // =========================================================
    // ✅ NORMALIZACIÓN DE HOST (arregla "/ip" y "name/ip")
    // =========================================================
    private fun normalizeHost(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val s = raw.trim()

        // Si viene como "hostname/1.2.3.4" o "/1.2.3.4" -> tomar lo último
        val last = s.substringAfterLast("/").trim()

        // Si viene con brackets "[2001:...]" sacarlos (SMBJ no los quiere)
        val unbracket = last.removePrefix("[").removeSuffix("]")

        // Quitar zone id IPv6 tipo "%wlan0"
        val noZone = unbracket.substringBefore("%")

        return noZone.trim()
    }

    /** ✅ serverId determinístico a partir de host:port */
    fun makeServerId(host: String, port: Int = 445): String {
        val h = normalizeHost(host)
        return UUID.nameUUIDFromBytes("$h:$port".toByteArray()).toString()
    }

    fun listCachedServerIds(): List<String> {
        synchronized(prefsLock) {
            val set = prefs.getStringSet(KEY_SERVER_IDS, emptySet()) ?: emptySet()
            return set.toList().sorted()
        }
    }

    /** ✅ Lista completa de SMB guardados (id, name, host, port) */
    fun listCachedServers(): List<SmbServer> {
        val ids = listCachedServerIds()
        val out = mutableListOf<SmbServer>()

        for (id in ids) {
            val loaded = loadCreds(id) ?: continue
            val host = loaded.first
            val name = prefs.getString(keyNameFor(id), host) ?: host
            val port = prefs.getInt(keyPortFor(id), 445)
            out.add(SmbServer(id = id, name = name, host = host, port = port))
        }

        return out.sortedWith(compareBy({ it.name.lowercase() }, { it.host }, { it.port }))
    }

    fun saveLastServerId(serverId: String) {
        prefs.edit().putString(KEY_LAST_SERVER_ID, serverId).apply()
    }

    fun getLastServerId(): String? = prefs.getString(KEY_LAST_SERVER_ID, null)

    fun saveLastShare(serverId: String, share: String) {
        if (share.isBlank()) return

        synchronized(prefsLock) {
            // 1. Obtener el Set actual de shares para este server
            val currentSet = HashSet(prefs.getStringSet(keySharesSetFor(serverId), emptySet()) ?: emptySet())

            // 2. Agregar el nuevo share
            currentSet.add(share)

            // 3. Guardar todo atómicamente
            val editor = prefs.edit()

            // Punteros globales y de "última vez visto" (Mantienen comportamiento actual de UI)
            editor.putString(KEY_LAST_SHARE, share)
            editor.putString(keyShareFor(serverId), share)

            // NUEVO: Guardamos el set acumulativo
            editor.putStringSet(keySharesSetFor(serverId), currentSet)

            editor.apply()
        }
    }

    /**
     * NUEVO MÉTODO: Obtiene TODOS los shares guardados para un servidor.
     * Incluye lógica de migración para no perder el dato si venías del sistema viejo.
     */
    fun getSavedShares(serverId: String): Set<String> {
        synchronized(prefsLock) {
            val set = prefs.getStringSet(keySharesSetFor(serverId), emptySet()) ?: emptySet()

            // Migración "On-the-fly":
            // Si el set está vacío, pero existe un "último share" del sistema viejo, lo devolvemos
            if (set.isEmpty()) {
                val oldSingleShare = prefs.getString(keyShareFor(serverId), null)
                if (!oldSingleShare.isNullOrBlank()) {
                    return setOf(oldSingleShare)
                }
            }
            return set
        }
    }

    fun getLastShare(serverId: String? = null): String? {
        if (serverId != null) {
            val s = prefs.getString(keyShareFor(serverId), null)
            if (!s.isNullOrBlank()) return s
        }
        return prefs.getString(KEY_LAST_SHARE, null)
    }

    /**
     * ✅ SAVE CREDS TRANSACCIONAL (ATÓMICO):
     * Realiza todas las escrituras (creds + metadata + index update) en un SOLO commit
     * bajo un bloqueo sincronizado. Esto evita que la segunda importación lea una lista "vieja"
     * antes de que la primera termine de guardarse.
     */
    fun saveCreds(
        serverId: String,
        host: String,
        creds: SmbCreds,
        port: Int = 445,
        serverName: String? = null
    ) {
        val cleanHost = normalizeHost(host)
        val cleanId = makeServerId(cleanHost, port)

        val localFlag = if (creds.isLocal) "1" else "0"
        val blob = "${creds.username}\u0000${creds.password}\u0000${creds.domain.orEmpty()}\u0000$cleanHost\u0000$localFlag"
        val enc = Base64.encodeToString(blob.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

        synchronized(prefsLock) {
            // 1. Obtener lista actual asegurando una NUEVA instancia del Set (HashSet)
            // Esto es vital porque Android a veces cachea el Set devuelto y no detecta cambios
            val currentIds = HashSet(prefs.getStringSet(KEY_SERVER_IDS, emptySet()) ?: emptySet())

            // 2. Agregar nuevo ID
            currentIds.add(cleanId)

            // 3. Preparar UN solo editor para todo
            val editor = prefs.edit()

            // 4. Poner credenciales y metadatos
            editor.putString("creds_$cleanId", enc)
            editor.putString(keyNameFor(cleanId), (serverName ?: cleanHost))
            editor.putInt(keyPortFor(cleanId), port)

            // 5. Poner lista actualizada de IDs
            editor.putStringSet(KEY_SERVER_IDS, currentIds)

            // 6. Actualizar último server usado
            editor.putString(KEY_LAST_SERVER_ID, cleanId)

            // 7. COMMIT SÍNCRONO (Crucial para importaciones en bucle o rápidas)
            // Usamos commit() en vez de apply() para asegurar que se escribió en disco antes de liberar el lock
            val success = editor.commit()

            if (success) {
                Log.i(tag, "✅ Saved SMB creds ATOMICALLY: id=$cleanId host=$cleanHost setSize=${currentIds.size}")
            } else {
                Log.e(tag, "❌ Failed to commit SMB creds to SharedPreferences")
            }
        }
    }

    /**
     * Retorna host + creds.
     * blob = user \0 pass \0 domain \0 host
     */
    fun loadCreds(serverId: String): Pair<String /*host*/, SmbCreds>? {
        val enc = prefs.getString("creds_$serverId", null) ?: return null
        val blob = String(Base64.decode(enc, Base64.NO_WRAP), StandardCharsets.UTF_8)
        val parts = blob.split('\u0000')
        if (parts.size < 4) return null

        val user = parts[0]
        val pass = parts[1]
        val domain = parts[2].ifBlank { null }
        val host = normalizeHost(parts[3])
        val isLocal = if (parts.size >= 5) parts[4] == "1" else true

        return host to SmbCreds(user, pass, domain, isLocal)
    }

    fun isServerLocal(serverId: String): Boolean {
        val credsPair = loadCreds(serverId)
        return credsPair?.second?.isLocal ?: false
    }

    fun encodePath(path: String): String =
        path.split("/").joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }

    // --- Descubrimiento SMB (NSD/mDNS) ---
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val nsd by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }

    fun discover(onFound: (SmbServer) -> Unit, onError: (String) -> Unit) {
        stopDiscovery()

        val serviceType = "_smb._tcp."
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(tag, "NSD started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(tag, "Resolve failed $errorCode for ${serviceInfo.serviceName}")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val addr = serviceInfo.host ?: return
                        val port = if (serviceInfo.port > 0) serviceInfo.port else 445

                        // ✅ preferir IPv4 si es posible
                        val host = when (addr) {
                            is Inet4Address -> addr.hostAddress
                            is Inet6Address -> addr.hostAddress // queda como "2001:..."
                            else -> addr.hostAddress
                        }

                        val cleanHost = normalizeHost(host)
                        if (cleanHost.isBlank()) return

                        val id = makeServerId(cleanHost, port)
                        val name = serviceInfo.serviceName ?: cleanHost

                        Log.i(tag, "SMB found (NSD): name=$name host=$cleanHost port=$port serverId=$id")
                        onFound(SmbServer(id, name, cleanHost, port))
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onError("Start discovery failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                onError("Stop discovery failed: $errorCode")
                stopDiscovery()
            }
        }

        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        val l = discoveryListener ?: return
        try { nsd.stopServiceDiscovery(l) } catch (_: Exception) {}
        discoveryListener = null
    }

    // --- SMB client ---
    private val smbConfig = SmbConfig.builder()
        .withTimeout(120, TimeUnit.SECONDS)
        .withSoTimeout(180, TimeUnit.SECONDS)
        .build()

    private val smbClient = SMBClient(smbConfig)

    fun connectSession(host: String, creds: SmbCreds): Pair<Connection, Session> {
        val cleanHost = normalizeHost(host)
        if (cleanHost.isBlank()) throw IllegalArgumentException("Host vacío/ inválido")

        Log.d(tag, "connectSession host=$cleanHost user=${creds.username} domain=${creds.domain ?: ""}")
        val conn = smbClient.connect(cleanHost)
        val auth = AuthenticationContext(creds.username, creds.password.toCharArray(), creds.domain)
        val sess = conn.authenticate(auth)
        return conn to sess
    }

    fun discoverAll(
        onFound: (SmbServer) -> Unit,
        onError: (String) -> Unit,
        scanTimeoutMs: Int = 180,
        maxThreads: Int = 40
    ) {
        // 1) NSD
        discover(onFound, onError)

        // 2) Fallback: scan LAN por 445
        Thread {
            try {
                val prefix = getLanPrefixOrNull()
                if (prefix == null) {
                    Log.w(tag, "No pude inferir prefijo LAN, salto scan")
                    return@Thread
                }

                Log.i(tag, "SMB scan starting on $prefix.0/24 ...")

                val executor = Executors.newFixedThreadPool(max(4, maxThreads))
                val foundSet = mutableSetOf<String>()

                for (i in 1..254) {
                    val ip = "$prefix.$i"
                    executor.submit {
                        if (isPortOpen(ip, 445, scanTimeoutMs)) {
                            val id = makeServerId(ip, 445)
                            synchronized(foundSet) {
                                if (foundSet.add(id)) {
                                    Log.i(tag, "SMB found by scan: $ip:445 id=$id")
                                    onFound(SmbServer(id = id, name = ip, host = ip, port = 445))
                                }
                            }
                        }
                    }
                }

                executor.shutdown()
                executor.awaitTermination(12, TimeUnit.SECONDS)

                Log.i(tag, "SMB scan finished")
            } catch (e: Exception) {
                Log.e(tag, "SMB scan error", e)
                onError("Scan error: ${e.message}")
            }
        }.start()
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Intenta inferir prefijo tipo "192.168.1" */
    private fun getLanPrefixOrNull(): String? {
        val ip = getBestLocalIpHint() ?: return null
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return parts.take(3).joinToString(".")
    }

    fun testLogin(host: String, creds: SmbCreds) {
        val cleanHost = normalizeHost(host)
        val (conn, sess) = connectSession(cleanHost, creds)
        try {
            // si authenticate() pasó, login OK
        } finally {
            try { sess.close() } catch (_: Exception) {}
            try { conn.close() } catch (_: Exception) {}
        }
    }

    fun testShareAccess(host: String, creds: SmbCreds, shareName: String) {
        val cleanHost = normalizeHost(host)
        val (conn, sess) = connectSession(cleanHost, creds)
        try {
            val sh = sess.connectShare(shareName)
            try { sh.close() } catch (_: Exception) {}
        } finally {
            try { sess.close() } catch (_: Exception) {}
            try { conn.close() } catch (_: Exception) {}
        }
    }

    // --- IP local sugerida (LAN) ---
    fun getBestLocalIpHint(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces().toList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        if (!ip.startsWith("169.254.")) return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    // =========================
    // ✅ PROXY START/STOP
    // =========================
    private var proxy: HttpProxyServer? = null
    private var proxyPort: Int = 8081

    /**
     * Devuelve todos los serverIds guardados (cache).
     */
    fun listSavedServers(): List<SmbServer> {
        return listCachedServers()
    }

    /**
     * Abre un DiskShare para un serverId guardado, ejecuta `block`, y cierra TODO.
     */
    fun <T> withDiskShare(
        serverId: String,
        shareName: String,
        block: (DiskShare) -> T
    ): T {
        val cached = loadCreds(serverId)
            ?: throw IllegalStateException("No cached creds for serverId=$serverId")

        val host = cached.first
        val creds = cached.second

        val (conn, sess) = connectSession(host, creds)
        var share: DiskShare? = null

        return try {
            share = sess.connectShare(shareName) as DiskShare
            block(share)
        } finally {
            try { share?.close() } catch (_: Exception) {}
            try { sess.close() } catch (_: Exception) {}
            try { conn.close() } catch (_: Exception) {}
        }
    }

    fun ensureProxyStarted(port: Int = 8081): Boolean {
        proxyPort = port
        if (proxy != null) return true
        return try {
            proxy = HttpProxyServer(proxyPort).apply {
                start(60_000, false)
            }
            Log.i(tag, "SMB proxy STARTED on 0.0.0.0:$proxyPort")
            true
        } catch (e: Exception) {
            Log.e(tag, "SMB proxy FAILED to start on port=$proxyPort", e)
            proxy = null
            false
        }
    }

    fun stopProxy() {
        try { proxy?.stop() } catch (_: Exception) {}
        proxy = null
        Log.i(tag, "SMB proxy STOPPED")
    }

    fun getProxyPort(): Int = proxyPort

    // --- Proxy HTTP local con Range ---
    inner class HttpProxyServer(port: Int = 8081) : NanoHTTPD("0.0.0.0", port) {

        override fun serve(session: IHTTPSession): Response {
            return try {
                val uri = session.uri ?: return badRequest("No URI")
                val range = session.headers["range"]
                Log.d(tag, "HTTP ${session.method} $uri range=$range")

                if (uri == "/ping") {
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
                }

                if (uri == "/debug") {
                    val ids = listCachedServerIds()
                    val lastId = getLastServerId().orEmpty()
                    val lastShare = getLastShare(lastId).orEmpty()

                    val sb = StringBuilder()
                    sb.append("cachedServerIds=").append(ids.joinToString(",")).append("\n")
                    sb.append("lastServerId=").append(lastId).append("\n")
                    sb.append("lastShare=").append(lastShare).append("\n")

                    ids.forEach { id ->
                        val loaded = loadCreds(id)
                        val shareFor = getLastShare(id).orEmpty()
                        val name = prefs.getString(keyNameFor(id), loaded?.first ?: "NO_HOST") ?: "NO_NAME"
                        val port = prefs.getInt(keyPortFor(id), 445)

                        sb.append(" - ").append(id)
                            .append(" name=").append(name)
                            .append(" host=").append(loaded?.first ?: "NO_HOST")
                            .append(" port=").append(port)
                            .append(" share=").append(shareFor)
                            .append("\n")
                    }

                    return newFixedLengthResponse(Response.Status.OK, "text/plain", sb.toString())
                }

                if (!uri.startsWith("/smb/")) return notFound("Resource not found (use /smb/...)")

                val parts = uri.split("/").filter { it.isNotBlank() }
                if (parts.size < 4) return badRequest("Expected /smb/<serverId>/<share>/<path>")

                val serverId = parts[1]
                val shareName = parts[2]
                val encodedPath = parts.drop(3).joinToString("/")

                val smbPath = URLDecoder.decode(encodedPath, "UTF-8")
                    .replace("\\", "/")
                    .trimStart('/')

                val cached = loadCreds(serverId) ?: return unauthorized("No cached creds for $serverId")
                val host = cached.first
                val creds = cached.second

                streamSmbFile(host, creds, shareName, smbPath, session)
            } catch (e: Exception) {
                Log.e(tag, "serve error", e)
                internalError(e.message ?: "Unknown error")
            }
        }

        private fun streamSmbFile(
            host: String,
            creds: SmbCreds,
            shareName: String,
            smbPath: String,
            req: IHTTPSession
        ): Response {
            val rangeHeader = req.headers["range"]

            val (conn, sess) = connectSession(host, creds)

            var share: DiskShare? = null
            var file: File? = null

            return try {
                share = sess.connectShare(shareName) as DiskShare

                file = share.openFile(
                    smbPath,
                    setOf(AccessMask.GENERIC_READ),
                    null,
                    setOf(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )

                val size = file.fileInformation.standardInformation.endOfFile
                val (start, end) = parseRange(rangeHeader, size)
                val contentLength = (end - start) + 1

                val inStream = SmbRangedInputStream(file, start, end)
                val mime = guessMime(smbPath)

                val resp = if (rangeHeader != null) {
                    newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, inStream, contentLength).apply {
                        addHeader("Accept-Ranges", "bytes")
                        addHeader("Content-Range", "bytes $start-$end/$size")
                        addHeader("Content-Length", contentLength.toString())
                    }
                } else {
                    newFixedLengthResponse(Response.Status.OK, mime, inStream, size).apply {
                        addHeader("Accept-Ranges", "bytes")
                        addHeader("Content-Length", size.toString())
                    }
                }

                inStream.onClose = {
                    try { file?.close() } catch (_: Exception) {}
                    try { share?.close() } catch (_: Exception) {}
                    try { sess.close() } catch (_: Exception) {}
                    try { conn.close() } catch (_: Exception) {}
                }

                resp
            } catch (e: Exception) {
                try { file?.close() } catch (_: Exception) {}
                try { share?.close() } catch (_: Exception) {}
                try { sess.close() } catch (_: Exception) {}
                try { conn.close() } catch (_: Exception) {}
                notFound("SMB error: ${e.message}")
            }
        }

        private fun parseRange(range: String?, total: Long): Pair<Long, Long> {
            if (range == null) return 0L to (total - 1)
            val cleaned = range.trim().lowercase()
            if (!cleaned.startsWith("bytes=")) return 0L to (total - 1)
            val spec = cleaned.removePrefix("bytes=")
            val parts = spec.split("-")
            val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = parts.getOrNull(1)?.toLongOrNull() ?: (total - 1)
            val safeStart = start.coerceIn(0, total - 1)
            val safeEnd = end.coerceIn(safeStart, total - 1)
            return safeStart to safeEnd
        }

        private fun guessMime(path: String): String {
            val p = path.lowercase()
            return when {
                p.endsWith(".mp4") -> "video/mp4"
                p.endsWith(".mkv") -> "video/x-matroska"
                p.endsWith(".webm") -> "video/webm"
                p.endsWith(".mp3") -> "audio/mpeg"
                p.endsWith(".m4a") -> "audio/mp4"
                p.endsWith(".jpg") || p.endsWith(".jpeg") -> "image/jpeg"
                p.endsWith(".png") -> "image/png"
                else -> "application/octet-stream"
            }
        }

        private fun badRequest(msg: String) =
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", msg)

        private fun unauthorized(msg: String) =
            newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", msg)

        private fun notFound(msg: String) =
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", msg)

        private fun internalError(msg: String) =
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", msg)
    }

    private class SmbRangedInputStream(
        private val file: com.hierynomus.smbj.share.File,
        private val start: Long,
        private val end: Long
    ) : InputStream() {

        var onClose: (() -> Unit)? = null

        private val stream: InputStream = file.getInputStream()
        private var remaining: Long = (end - start) + 1

        init {
            skipFully(stream, start)
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            if (len == 0) return 0

            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = stream.read(b, off, toRead)
            if (n <= 0) return -1

            remaining -= n.toLong()
            return n
        }

        override fun close() {
            try {
                try { stream.close() } catch (_: Exception) {}
                onClose?.invoke()
            } finally {
                super.close()
            }
        }

        private fun skipFully(input: InputStream, bytes: Long) {
            var left = bytes
            val buf = ByteArray(64 * 1024)

            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) {
                    left -= skipped
                    continue
                }

                val toRead = minOf(buf.size.toLong(), left).toInt()
                val n = input.read(buf, 0, toRead)
                if (n < 0) throw java.io.EOFException("EOF while skipping $bytes bytes")
                left -= n.toLong()
            }
        }
    }
}