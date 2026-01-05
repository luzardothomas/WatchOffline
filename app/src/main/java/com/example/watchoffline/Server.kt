package com.example.watchoffline

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.min

class BackgroundServer(
    port: Int = 8080
) : NanoHTTPD("127.0.0.1", port) {   // ✅ SOLO localhost

    private val tag = "BackgroundServer"

    // Candidatos típicos (celular + TV box)
    private val rootCandidates = listOf(
        File("/storage/self/primary"),
        File("/storage/emulated/0"),
        File("/sdcard"),
        File("/storage") // último fallback
    )

    // Root elegido automáticamente
    private val rootDir: File = pickRootDir()

    override fun start() {
        super.start(SOCKET_READ_TIMEOUT, false)
        Log.d(tag, "Started localhost-only on http://127.0.0.1:$listeningPort (root=${rootDir.path})")
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            val decodedUri = decodeUri(session.uri ?: "/")

            val requested = if (decodedUri == "/" || decodedUri.isBlank()) {
                rootDir
            } else {
                val absLike = decodedUri.startsWith("/storage/") || decodedUri.startsWith("/sdcard")
                if (absLike) File(decodedUri) else File(rootDir, decodedUri.trimStart('/'))
            }

            if (!isAllowedPath(requested)) {
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    "text/plain; charset=utf-8",
                    "Forbidden"
                )
            }

            when {
                !requested.exists() -> notFound("No existe: ${requested.path}")
                requested.isDirectory -> listFilesHtml(requested)
                else -> serveFileWithRange(session, requested)
            }
        } catch (e: Exception) {
            Log.e(tag, "serve FAILED", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "Internal Server Error: ${e.message}"
            )
        }
    }

    // =========================
    // Root selection
    // =========================

    private fun pickRootDir(): File {
        val chosen = rootCandidates.firstOrNull { it.exists() && it.isDirectory && it.canRead() }
        Log.d(tag, "pickRootDir -> ${chosen?.path ?: "/storage"}")
        return chosen ?: File("/storage")
    }

    private fun isAllowedPath(f: File): Boolean {
        val canon = try { f.canonicalPath } catch (_: Exception) { return false }

        for (root in rootCandidates) {
            val rCanon = try { root.canonicalPath } catch (_: Exception) { continue }
            if (canon.startsWith(rCanon)) return true
        }

        val rootCanon = try { rootDir.canonicalPath } catch (_: Exception) { return false }
        return canon.startsWith(rootCanon)
    }

    // =========================
    // HTML listing
    // =========================

    private fun listFilesHtml(dir: File): Response {
        val children = dir.listFiles()

        if (children == null) {
            val diag = """
                <p><b>No se pudo listar este directorio.</b></p>
                <p>Esto suele pasar por permisos/scoped storage (Android 11+), o porque el fabricante bloquea el listado.</p>
                <p><b>Dir:</b> ${escapeHtml(dir.path)} | <b>exists</b>=${dir.exists()} <b>canRead</b>=${dir.canRead()}</p>
                <h3>Probá abrir estos roots:</h3>
                <ul>
                    ${rootCandidates.joinToString("\n") { r ->
                val href = encodeHrefAbsolute(r.path, isDir = true)
                "<li><a href=\"$href\">${escapeHtml(r.path)}/</a></li>"
            }}
                </ul>
            """.trimIndent()

            return newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                wrapHtml("Directorio: ${escapeHtml(dir.path)}", diag)
            )
        }

        val files = children.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))

        val listItems = buildString {
            for (f in files) {
                val href = encodeHrefAbsolute(f.path, isDir = f.isDirectory)
                val icon = if (f.isDirectory) "📁" else "📄"
                val label = escapeHtml(f.name + if (f.isDirectory) "/" else "")
                appendLine("<li><a href=\"$href\">$icon $label</a></li>")
            }
        }

        val header = """
            <p><b>Servidor:</b> localhost-only (127.0.0.1)</p>
            <p><b>Root elegido:</b> ${escapeHtml(rootDir.path)}</p>
            <p><b>Directorio:</b> ${escapeHtml(dir.path)}</p>
        """.trimIndent()

        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            wrapHtml("Directorio: ${escapeHtml(dir.path)}", header + "<ul>$listItems</ul>")
        )
    }

    private fun wrapHtml(title: String, body: String): String {
        return """
            <html>
              <head>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1"/>
                <title>${escapeHtml(title)}</title>
              </head>
              <body style="font-family: sans-serif; padding: 12px;">
                <h2>${escapeHtml(title)}</h2>
                $body
              </body>
            </html>
        """.trimIndent()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun encodeHrefAbsolute(absPath: String, isDir: Boolean): String {
        val clean = absPath.replace("\\", "/")
        val parts = clean.split("/").map { seg ->
            if (seg.isBlank()) "" else URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        val rebuilt = parts.joinToString("/")
        val base = if (rebuilt.startsWith("/")) rebuilt else "/$rebuilt"
        return base + if (isDir) "/" else ""
    }

    private fun decodeUri(uri: String): String {
        val decoded = URLDecoder.decode(uri, "UTF-8")
        return decoded.replace("\\", "/")
    }

    private fun notFound(msg: String) = newFixedLengthResponse(
        Response.Status.NOT_FOUND,
        "text/plain; charset=utf-8",
        msg
    )

    // =========================
    // File serving with Range (NO chunked) + ignore Broken pipe
    // =========================

    private fun serveFileWithRange(session: IHTTPSession, file: File): Response {
        val mime = guessMime(file)
        val fileLen = file.length()

        // Range: bytes=start-end
        val rangeHeader = session.headers["range"] ?: session.headers["Range"]

        return try {
            if (rangeHeader != null && rangeHeader.startsWith("bytes=") && fileLen > 0) {
                val range = rangeHeader.removePrefix("bytes=").trim()
                val (start, end) = parseRange(range, fileLen)
                val len = (end - start + 1).coerceAtLeast(0)

                val fis = FileInputStream(file)
                skipFully(fis, start)

                // Stream acotado + tolerante a desconexión del cliente
                val stream: InputStream = SafeBoundedInputStream(fis, len, tag)

                newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, len).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Content-Range", "bytes $start-$end/$fileLen")
                    addHeader("Content-Length", len.toString())
                }
            } else {
                val stream: InputStream = SafeFileInputStream(FileInputStream(file), tag)

                newFixedLengthResponse(Response.Status.OK, mime, stream, fileLen).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Content-Length", fileLen.toString())
                }
            }
        } catch (e: Exception) {
            // Si el cliente cortó justo al armar, no lo tratamos como error fatal
            if (isClientDisconnect(e)) {
                Log.d(tag, "Client disconnected while serving '${file.path}' (${e::class.java.simpleName}: ${e.message})")
                // NanoHTTPD requiere devolver algo: una respuesta vacía OK (no se llegará a leer).
                newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "")
            } else {
                Log.e(tag, "serveFile failed: ${file.path}", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain; charset=utf-8",
                    "Error del servidor: ${e.message ?: "Desconocido"}"
                )
            }
        }
    }

    private fun parseRange(range: String, fileLen: Long): Pair<Long, Long> {
        val lenMinus1 = fileLen - 1
        return when {
            range.startsWith("-") -> {
                val suffix = range.removePrefix("-").toLongOrNull() ?: 0L
                val start = (fileLen - suffix).coerceAtLeast(0L)
                start to lenMinus1
            }
            range.contains("-") -> {
                val parts = range.split("-", limit = 2)
                val start = parts[0].toLongOrNull() ?: 0L
                val end = parts[1].toLongOrNull() ?: lenMinus1
                val safeStart = start.coerceIn(0L, lenMinus1)
                val safeEnd = min(end, lenMinus1).coerceAtLeast(safeStart)
                safeStart to safeEnd
            }
            else -> 0L to lenMinus1
        }
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private fun guessMime(file: File): String {
        return when (file.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "m4v" -> "video/x-m4v"
            "ts"  -> "video/mp2t"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "srt" -> "application/x-subrip"
            else -> "application/octet-stream"
        }
    }

    private fun isClientDisconnect(e: Throwable): Boolean {
        val msg = e.message?.lowercase().orEmpty()
        return (e is SocketException && (msg.contains("broken pipe") || msg.contains("connection reset"))) ||
                (e is IOException && (msg.contains("broken pipe") || msg.contains("connection reset")))
    }

    /**
     * InputStream wrapper que “normaliza” desconexiones del cliente:
     * - si al leer/escribir se corta, cerramos y devolvemos EOF.
     *
     * NanoHTTPD escribe al socket desde el stream; si el socket se cortó,
     * terminará apareciendo como IOException al intentar seguir.
     * Este wrapper minimiza el ruido y asegura close limpio del file descriptor.
     */
    private class SafeFileInputStream(
        private val inner: InputStream,
        private val tag: String
    ) : InputStream() {

        private var closed = false

        override fun read(): Int {
            if (closed) return -1
            return try {
                inner.read()
            } catch (e: Exception) {
                if (isDisconnect(e)) {
                    safeClose()
                    -1
                } else {
                    throw e
                }
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            return try {
                inner.read(b, off, len)
            } catch (e: Exception) {
                if (isDisconnect(e)) {
                    safeClose()
                    -1
                } else {
                    throw e
                }
            }
        }

        override fun close() {
            safeClose()
        }

        private fun safeClose() {
            if (closed) return
            closed = true
            try { inner.close() } catch (_: Exception) {}
        }

        private fun isDisconnect(e: Throwable): Boolean {
            val msg = e.message?.lowercase().orEmpty()
            val isDisc = (e is SocketException || e is IOException) &&
                    (msg.contains("broken pipe") || msg.contains("connection reset"))
            if (isDisc) {
                Log.d(tag, "Client disconnected (stream): ${e::class.java.simpleName}: ${e.message}")
            }
            return isDisc
        }
    }

    // InputStream acotado para Range, pero tolerante a desconexión
    private class SafeBoundedInputStream(
        inner: InputStream,
        remaining: Long,
        private val tag: String
    ) : InputStream() {

        private val base = SafeFileInputStream(inner, tag)
        private var left: Long = remaining

        override fun read(): Int {
            if (left <= 0) return -1
            val b = base.read()
            if (b >= 0) left--
            if (left <= 0) close()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val toRead = min(len.toLong(), left).toInt()
            val r = base.read(b, off, toRead)
            if (r > 0) {
                left -= r.toLong()
                if (left <= 0) close()
            }
            return r
        }

        override fun close() {
            base.close()
        }
    }
}

