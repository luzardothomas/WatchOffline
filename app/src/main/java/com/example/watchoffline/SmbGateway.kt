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
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.min

class SmbGateway(private val context: Context) {

    data class SmbServer(val id: String, val name: String, val host: String, val port: Int = 445)
    data class SmbCreds(val username: String, val password: String, val domain: String? = null)

    private val tag = "SmbGateway"

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

    /** ✅ serverId determinístico a partir de host:port (ej "192.168.1.33:445") */
    fun makeServerId(host: String, port: Int = 445): String {
        return UUID.nameUUIDFromBytes("$host:$port".toByteArray()).toString()
    }

    fun listCachedServerIds(): List<String> {
        val set = prefs.getStringSet(KEY_SERVER_IDS, emptySet()) ?: emptySet()
        return set.toList().sorted()
    }

    fun saveLastServerId(serverId: String) {
        prefs.edit().putString(KEY_LAST_SERVER_ID, serverId).commit()
    }

    fun getLastServerId(): String? = prefs.getString(KEY_LAST_SERVER_ID, null)

    fun saveLastShare(serverId: String, share: String) {
        prefs.edit()
            .putString(KEY_LAST_SHARE, share)
            .putString(keyShareFor(serverId), share)
            .commit()
    }

    fun getLastShare(serverId: String? = null): String? {
        if (serverId != null) {
            val s = prefs.getString(keyShareFor(serverId), null)
            if (!s.isNullOrBlank()) return s
        }
        return prefs.getString(KEY_LAST_SHARE, null)
    }

    fun saveCreds(serverId: String, host: String, creds: SmbCreds) {
        val blob = "${creds.username}\u0000${creds.password}\u0000${creds.domain.orEmpty()}\u0000$host"
        val enc = Base64.encodeToString(blob.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

        // ✅ 1) guardar credencial (commit inmediato)
        prefs.edit()
            .putString("creds_$serverId", enc)
            .commit()

        // ✅ 2) actualizar índice de serverIds (commit inmediato)
        val current = prefs.getStringSet(KEY_SERVER_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(serverId)
        prefs.edit()
            .putStringSet(KEY_SERVER_IDS, current)
            .commit()

        // ✅ 3) recordar último serverId
        saveLastServerId(serverId)

        Log.e(tag, "Saved SMB creds: serverId=$serverId host=$host user=${creds.username}")
    }

    fun loadCreds(serverId: String): Pair<String /*host*/, SmbCreds>? {
        val enc = prefs.getString("creds_$serverId", null) ?: return null
        val blob = String(Base64.decode(enc, Base64.NO_WRAP), StandardCharsets.UTF_8)
        val parts = blob.split('\u0000')
        if (parts.size < 4) return null
        val user = parts[0]
        val pass = parts[1]
        val domain = parts[2].ifBlank { null }
        val host = parts[3]
        return host to SmbCreds(user, pass, domain)
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
                        val host = serviceInfo.host?.hostAddress ?: return
                        val port = if (serviceInfo.port > 0) serviceInfo.port else 445
                        val id = makeServerId(host, port)

                        Log.e(tag, "SMB found: name=${serviceInfo.serviceName ?: host} host=$host port=$port serverId=$id")
                        onFound(SmbServer(id, serviceInfo.serviceName ?: host, host, port))
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
    private val smbClient = SMBClient()

    private fun connectSession(host: String, creds: SmbCreds): Pair<Connection, Session> {
        val conn = smbClient.connect(host)
        val auth = AuthenticationContext(creds.username, creds.password.toCharArray(), creds.domain)
        val sess = conn.authenticate(auth)
        return conn to sess
    }

    fun testLogin(host: String, creds: SmbCreds) {
        val (conn, sess) = connectSession(host, creds)
        try {
            // si authenticate() pasó, login OK
        } finally {
            try { sess.close() } catch (_: Exception) {}
            try { conn.close() } catch (_: Exception) {}
        }
    }

    fun testShareAccess(host: String, creds: SmbCreds, shareName: String) {
        val (conn, sess) = connectSession(host, creds)
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

    fun ensureProxyStarted(port: Int = 8081): Boolean {
        proxyPort = port
        if (proxy != null) return true
        return try {
            proxy = HttpProxyServer(proxyPort).apply {
                start(60_000, false)
            }
            Log.e(tag, "SMB proxy STARTED on 0.0.0.0:$proxyPort")
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
        Log.e(tag, "SMB proxy STOPPED")
    }

    fun getProxyPort(): Int = proxyPort

    // --- Proxy HTTP local con Range ---
    inner class HttpProxyServer(port: Int = 8081) : NanoHTTPD("0.0.0.0", port) {

        override fun serve(session: IHTTPSession): Response {
            return try {
                val uri = session.uri ?: return badRequest("No URI")
                val range = session.headers["range"]
                Log.e(tag, "HTTP ${session.method} $uri range=$range")

                // ✅ endpoints de diagnóstico
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
                        sb.append(" - ").append(id)
                            .append(" host=").append(loaded?.first ?: "NO_HOST")
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

                // ✅ IMPORTANTE: no pasar sets vacíos que rompen en algunas versiones
                file = share.openFile(
                    smbPath,
                    setOf(AccessMask.GENERIC_READ),
                    null, // <- en algunas builds emptySet() rompe, null es aceptado
                    setOf(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    null  // <- idem
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
            // ✅ Saltar hasta el offset inicial (Long)
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

                // Algunos streams devuelven skip=0 aunque no estén en EOF.
                // Entonces consumimos leyendo.
                val toRead = minOf(buf.size.toLong(), left).toInt()
                val n = input.read(buf, 0, toRead)
                if (n < 0) throw java.io.EOFException("EOF while skipping $bytes bytes")
                left -= n.toLong()
            }
        }
    }

}

