package com.example.watchoffline

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
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
import java.util.EnumSet
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

    /** ✅ serverId determinístico a partir de host:port */
    fun makeServerId(host: String, port: Int = 445): String =
        UUID.nameUUIDFromBytes("$host:$port".toByteArray()).toString()

    private val KEY_SERVER_IDS = "server_ids_index"
    private val KEY_LAST_SERVER_ID = "last_server_id"

    fun listCachedServerIds(): List<String> {
        val set = prefs.getStringSet(KEY_SERVER_IDS, emptySet()) ?: emptySet()
        return set.toList().sorted()
    }

    fun saveCreds(serverId: String, host: String, creds: SmbCreds) {
        val blob = "${creds.username}\u0000${creds.password}\u0000${creds.domain.orEmpty()}\u0000$host"
        val enc = Base64.encodeToString(blob.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

        // 1) creds
        prefs.edit().putString("creds_$serverId", enc).commit()

        // 2) índice de ids
        val current = prefs.getStringSet(KEY_SERVER_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(serverId)
        prefs.edit().putStringSet(KEY_SERVER_IDS, current).commit()

        // 3) último id (útil para debug)
        prefs.edit().putString(KEY_LAST_SERVER_ID, serverId).commit()

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
            // si authenticate() pasó, el login ya está OK
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
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
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

    // --- Proxy HTTP con Range ---
    inner class HttpProxyServer(port: Int = 8081) : NanoHTTPD("0.0.0.0", port) {

        override fun serve(session: IHTTPSession): Response {
            return try {
                val uri = session.uri ?: return badRequest("No URI")

                // Diagnóstico
                if (uri == "/ping") {
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
                }
                if (uri == "/debug") {
                    val ids = listCachedServerIds()
                    val last = prefs.getString(KEY_LAST_SERVER_ID, "") ?: ""
                    val keys = prefs.all.keys.sorted().joinToString(",")
                    val sb = StringBuilder()
                    sb.append("cachedServerIds=").append(ids.joinToString(",")).append("\n")
                    sb.append("lastServerId=").append(last).append("\n")
                    sb.append("prefKeys=").append(keys).append("\n")
                    ids.forEach { id ->
                        val loaded = loadCreds(id)
                        sb.append(" - ").append(id).append(" -> ").append(loaded?.first ?: "NO_HOST").append("\n")
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

                val rangeHeader = session.headers["range"]
                Log.e(tag, "HTTP ${session.method} $uri range=$rangeHeader")

                return streamSmbFile(host, shareName, smbPath, creds, rangeHeader)
            } catch (e: Exception) {
                Log.e(tag, "serve error", e)
                internalError(e.message ?: "Unknown error")
            }
        }

        private fun streamSmbFile(
            host: String,
            shareName: String,
            smbPath: String,
            creds: SmbCreds,
            rangeHeader: String?
        ): Response {

            val (conn, sess) = connectSession(host, creds)
            var share: DiskShare? = null
            var file: File? = null

            return try {
                share = sess.connectShare(shareName) as DiskShare

                // ✅ NO emptySet(): evita "Collection is empty"
                file = share.openFile(
                    smbPath,
                    setOf(AccessMask.GENERIC_READ),
                    EnumSet.noneOf(FileAttributes::class.java),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.noneOf(SMB2CreateOptions::class.java)
                )

                val size = file.fileInformation.standardInformation.endOfFile
                val (start, end) = parseRange(rangeHeader, size)
                val contentLength = (end - start) + 1

                // ✅ Stream estable: InputStream real del archivo + skip + limit
                val base = file.getInputStream()
                val inStream = SmbSkipLimitInputStream(base, start, contentLength)

                val mime = guessMime(smbPath)

                val resp =
                    if (rangeHeader != null) {
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
                Log.e(tag, "serve error ctx=host=$host share=$shareName path=$smbPath", e)
                internalError("SMB error: ${e.message}")
            }
        }

        private fun parseRange(range: String?, total: Long): Pair<Long, Long> {
            if (range.isNullOrBlank()) return 0L to (total - 1)
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

    /**
     * InputStream que:
     * 1) hace skip exacto hasta "start"
     * 2) solo deja leer "limit" bytes (para Range y para evitar cortar antes/after)
     */
    private class SmbSkipLimitInputStream(
        private val inner: InputStream,
        start: Long,
        limit: Long
    ) : InputStream() {

        var onClose: (() -> Unit)? = null
        private var remaining: Long = limit
        private var skipped = false
        private val startPos: Long = start

        private fun ensureSkipped() {
            if (skipped) return
            var toSkip = startPos
            while (toSkip > 0) {
                val s = inner.skip(toSkip)
                if (s <= 0) {
                    // Si skip falla, intentamos leer y descartar
                    val b = ByteArray(min(64 * 1024L, toSkip).toInt())
                    val n = inner.read(b)
                    if (n <= 0) throw java.io.EOFException("EOF while skipping to start=$startPos")
                    toSkip -= n.toLong()
                } else {
                    toSkip -= s
                }
            }
            skipped = true
        }

        override fun read(): Int {
            ensureSkipped()
            if (remaining <= 0) return -1
            val v = inner.read()
            if (v >= 0) remaining--
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            ensureSkipped()
            if (remaining <= 0) return -1
            val toRead = min(len.toLong(), remaining).toInt()
            val n = inner.read(b, off, toRead)
            if (n > 0) remaining -= n.toLong()
            return n
        }

        override fun close() {
            try {
                try { inner.close() } catch (_: Exception) {}
                onClose?.invoke()
            } finally {
                super.close()
            }
        }
    }
}
