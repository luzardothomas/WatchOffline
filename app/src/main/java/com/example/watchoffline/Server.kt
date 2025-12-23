package com.example.watchoffline

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder

class BackgroundServer(
    port: Int = 8080
) : NanoHTTPD("0.0.0.0", port) {

    private val rootDir = File("/storage")

    override fun start() = super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

    override fun stop() {
        super.stop()
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            // ✅ 1) decodificar URL (/..../Neon%20Genesis.. -> Neon Genesis..)
            val decodedPath = URLDecoder.decode(session.uri, "UTF-8")

            // ✅ 2) normalizar y proteger
            val requestedFile = File(decodedPath).canonicalFile
            val root = rootDir.canonicalFile  // rootDir = File("/storage")

            // 🔒 seguridad: solo permitir archivos dentro de /storage
            if (!requestedFile.path.startsWith(root.path)) {
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    "text/plain",
                    "Forbidden"
                )
            }

            when {
                !requestedFile.exists() -> notFound()
                requestedFile.isDirectory -> listFiles(requestedFile)
                else -> serveFile(requestedFile)
            }
        } catch (e: Exception) {
            Log.e("BackgroundServer", "serve() error", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Server error: ${e.message}"
            )
        }
    }

    private fun notFound() = newFixedLengthResponse(
        Response.Status.NOT_FOUND,
        "text/html",
        "<h1>404 Recurso no encontrado</h1>"
    )

    private fun listFiles(dir: File): Response {
        val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()

        val items = buildString {
            files.forEach { file ->
                val relPath = file.relativeTo(rootDir).path.replace(File.separatorChar, '/')
                val href = "/$relPath" + if (file.isDirectory) "/" else ""
                appendLine(
                    """
                    <li>
                        <a href="$href">
                            ${if (file.isDirectory) "📁" else "📄"} ${file.name}${if (file.isDirectory) "/" else ""}
                        </a>
                    </li>
                    """.trimIndent()
                )
            }
        }

        return newFixedLengthResponse(
            """
            <html>
                <body>
                    <h2>Directorio: ${dir.path}</h2>
                    <ul>$items</ul>
                </body>
            </html>
            """.trimIndent()
        )
    }

    private fun serveFile(file: File): Response {
        val mime = when (file.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }

        return try {
            newChunkedResponse(
                Response.Status.OK,
                mime,
                FileInputStream(file)
            ).apply {
                addHeader("Content-Length", file.length().toString())
                addHeader("Accept-Ranges", "bytes")
            }
        } catch (e: Exception) {
            errorResponse(e)
        }
    }

    private fun errorResponse(e: Exception) =
        newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            "text/plain",
            "Error del servidor: ${e.message ?: "Desconocido"}"
        )
}
