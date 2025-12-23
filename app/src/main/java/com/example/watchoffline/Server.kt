package com.example.watchoffline

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.min

class BackgroundServer(
    port: Int = 8080
) : NanoHTTPD("0.0.0.0", port) {

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

    override fun start() = super.start(SOCKET_READ_TIMEOUT, false)

    override fun serve(session: IHTTPSession): Response {
        return try {
            val decodedUri = decodeUri(session.uri ?: "/")

            // "/" => listar rootDir
            val requested = if (decodedUri == "/" || decodedUri.isBlank()) {
                rootDir
            } else {
                // Si te pasan un path absoluto tipo "/storage/emulated/0/Movies/a.mp4",
                // lo respetamos. Si es relativo, lo resolvemos contra rootDir.
                val absLike = decodedUri.startsWith("/storage/") || decodedUri.startsWith("/sdcard")
                if (absLike) File(decodedUri) else File(rootDir, decodedUri.trimStart('/'))
            }

            // Seguridad: evitar salir del root (si el requested es relativo al root)
            // Para absolutos, permitimos sólo si están bajo alguno de los roots conocidos.
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

        // Permitimos si está bajo alguno de los roots típicos (cuando te pasan path absoluto)
        for (root in rootCandidates) {
            val rCanon = try { root.canonicalPath } catch (_: Exception) { continue }
            if (canon.startsWith(rCanon)) return true
        }

        // Si rootCandidates falla por permisos raros, al menos restringimos a rootDir
        val rootCanon = try { rootDir.canonicalPath } catch (_: Exception) { return false }
        return canon.startsWith(rootCanon)
    }

    // =========================
    // HTML listing
    // =========================

    private fun listFilesHtml(dir: File): Response {
        val children = dir.listFiles()

        // Si no se puede listar (scoped storage / permisos), children suele ser null
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

    // Href absoluto, pero URL-encoded por segmentos (no rompe '/')
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
        // uri viene URL-encoded. Decode una vez.
        val decoded = URLDecoder.decode(uri, "UTF-8")
        // normalización mínima
        return decoded.replace("\\", "/")
    }

    private fun notFound(msg: String) = newFixedLengthResponse(
        Response.Status.NOT_FOUND,
        "text/plain; charset=utf-8",
        msg
    )

    // =========================
    // File serving with Range
    // =========================

    private fun serveFileWithRange(session: IHTTPSession, file: File): Response {
        val mime = guessMime(file)

        // Range: bytes=start-end
        val rangeHeader = session.headers["range"] ?: session.headers["Range"]
        val fileLen = file.length()

        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && fileLen > 0) {
            val range = rangeHeader.removePrefix("bytes=").trim()
            val (start, end) = parseRange(range, fileLen)

            val len = (end - start + 1).coerceAtLeast(0)
            val fis = FileInputStream(file)

            // saltar al start
            skipFully(fis, start)

            val stream: InputStream = BoundedInputStream(fis, len)

            return newChunkedResponse(Response.Status.PARTIAL_CONTENT, mime, stream).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Range", "bytes $start-$end/$fileLen")
                addHeader("Content-Length", len.toString())
            }
        }

        // Sin range
        return try {
            newChunkedResponse(Response.Status.OK, mime, FileInputStream(file)).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Length", fileLen.toString())
            }
        } catch (e: Exception) {
            Log.e(tag, "serveFile failed: ${file.path}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "Error del servidor: ${e.message ?: "Desconocido"}"
            )
        }
    }

    private fun parseRange(range: String, fileLen: Long): Pair<Long, Long> {
        // formatos:
        // "500-" => 500..(len-1)
        // "500-999"
        // "-500" => últimos 500 bytes
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

    // InputStream acotado para Range
    private class BoundedInputStream(
        private val inner: InputStream,
        private var remaining: Long
    ) : InputStream() {

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = inner.read()
            if (b >= 0) remaining--
            if (remaining <= 0) inner.close()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = min(len.toLong(), remaining).toInt()
            val r = inner.read(b, off, toRead)
            if (r > 0) {
                remaining -= r.toLong()
                if (remaining <= 0) inner.close()
            }
            return r
        }

        override fun close() {
            inner.close()
        }
    }
}
