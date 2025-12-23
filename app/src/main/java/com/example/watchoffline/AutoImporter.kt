package com.example.watchoffline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap

class AutoImporter(
    private val context: Context,
    private val smbGateway: SmbGateway,
    private val jsonDataManager: JsonDataManager,
    private val proxyPort: Int = 8081, // tu proxy SMB local
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val coverCache = mutableMapOf<String, ApiCover?>()

    data class ApiCover(
        val type: String? = null,
        val id: String? = null,
        val dir: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val skipToSecond: Int? = null,
        val file: String? = null,
        val url: String? = null
    )

    data class PreviewJson(
        val fileName: String,
        val videos: List<VideoItem>,
        val debug: String
    )

    data class RawItem(
        val path: String,
        val title: String,
        val img: String,
        val skip: Int,
        val videoSrc: String
    )

    /**
     * Ejecuta el auto import en un thread.
     */
    fun run(
        toast: (String) -> Unit,
        onDone: (importedCount: Int) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        toast("Importando desde SMB…")

        Thread {
            try {
                val serverId = smbGateway.getLastServerId()
                    ?: return@Thread postError("No hay SMB configurado", onError)

                val share = smbGateway.getLastShare(serverId)
                    ?: return@Thread postError("No hay share configurado", onError)

                val cached = smbGateway.loadCreds(serverId)
                    ?: return@Thread postError("No hay credenciales", onError)

                val host = cached.first
                val creds = cached.second

                // 🔥 SIN LÍMITE
                val files = smbListVideos(host, creds, share, max = Int.MAX_VALUE)
                if (files.isEmpty()) return@Thread postError("No se encontraron videos", onError)

                val rawItems = mutableListOf<RawItem>()

                files.forEach { path ->
                    val q = buildQueryForPathSmart(path)
                    val cover = fetchCoverFromApiCached(q)

                    rawItems.add(
                        RawItem(
                            path = path,
                            title = cover?.id ?: path.substringAfterLast("/").substringBeforeLast("."),
                            img = cover?.url.orEmpty(),
                            skip = cover?.skipToSecond ?: 0,
                            videoSrc = "http://127.0.0.1:$proxyPort/smb/$serverId/$share/${smbGateway.encodePath(path)}"
                        )
                    )
                }

                // ================= SERIES =================
                val seriesMap = linkedMapOf<Pair<String, Int>, MutableList<RawItem>>()
                val movies = mutableListOf<RawItem>()

                for (it in rawItems) {
                    val parts = it.path.replace("\\", "/").split("/")
                    if (parts.size >= 3) {
                        val series = parts[parts.size - 3]
                        val season = parseSeasonFromFolderOrName(parts[parts.size - 2])
                        if (season != null) {
                            seriesMap.getOrPut(series to season) { mutableListOf() }.add(it)
                            continue
                        }
                    }
                    movies.add(it)
                }

                val previews = mutableListOf<PreviewJson>()

                // Series → 1 JSON por temporada
                for ((key, items) in seriesMap.toList()
                    .sortedWith(compareBy({ normalizeName(it.first.first) }, { it.first.second }))) {

                    val (series, season) = key
                    previews.add(
                        PreviewJson(
                            fileName = "${normalizeName(series)}_s${pad2(season)}.json",
                            videos = items.sortedWith(
                                compareBy<RawItem>(
                                    { parseSeasonEpisode(it.path)?.second ?: Int.MAX_VALUE },
                                    { it.path.lowercase() }
                                )
                            ).map {
                                VideoItem(it.title, it.skip, it.img, it.img, it.videoSrc)
                            },
                            debug = "SERIES $series S$season"
                        )
                    )
                }

                // ================= MOVIES =================
                val sagaMap = linkedMapOf<String, MutableList<RawItem>>()
                for (m in movies) {
                    sagaMap.getOrPut(inferSagaNameFromPath(m.path)) { mutableListOf() }.add(m)
                }

                for ((saga, items) in sagaMap.toList()
                    .sortedWith(compareBy({ normalizeName(it.first) }))) {

                    val videosSorted = items.sortedWith(
                        compareBy<RawItem>(
                            { extractMovieSortKey(it.path, it.title) },
                            { normalizeName(it.title) },
                            { it.path.lowercase() }
                        )
                    ).map {
                        VideoItem(it.title, it.skip, it.img, it.img, it.videoSrc)
                    }

                    previews.add(
                        PreviewJson(
                            fileName = if (items.size > 1)
                                "saga_${normalizeName(saga).replace(" ", "_")}.json"
                            else
                                "${normalizeName(items.first().title).replace(" ", "_")}.json",
                            videos = videosSorted,
                            debug = "AUTO IMPORT"
                        )
                    )
                }

                // 🚫 Evitar duplicados
                val toImport = filterAlreadyImported(previews)

                if (toImport.isEmpty()) {
                    postToast("Todo ya estaba importado", toast)
                    return@Thread postDone(0, onDone)
                }

                importAllPreviews(toImport)

                postDone(toImport.size, onDone)

            } catch (e: Exception) {
                Log.e("AutoImporter", "Auto import failed", e)
                postError("Error: ${e.message}", onError)
            }
        }.start()
    }

    // -------------------------
    // UI helpers
    // -------------------------
    private fun postToast(msg: String, toast: (String) -> Unit) {
        mainHandler.post { toast(msg) }
    }

    private fun postDone(count: Int, onDone: (Int) -> Unit) {
        mainHandler.post { onDone(count) }
    }

    private fun postError(msg: String, onError: (String) -> Unit) {
        mainHandler.post { onError(msg) }
    }

    // -------------------------
    // Helpers (idénticos a los tuyos)
    // -------------------------

    private fun pad2(n: Int): String = n.toString().padStart(2, '0')

    private fun normalizeName(s: String): String =
        s.trim()
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .lowercase()

    private fun parseSeasonFromFolderOrName(name: String): Int? {
        val n = name.lowercase().replace("_", " ").trim()
        Regex("""temporada\s*(\d{1,2})""").find(n)?.let { return it.groupValues[1].toInt() }
        Regex("""temp\s*(\d{1,2})""").find(n)?.let { return it.groupValues[1].toInt() }
        Regex("""season\s*(\d{1,2})""").find(n)?.let { return it.groupValues[1].toInt() }
        Regex("""\bs(\d{1,2})\b""").find(n)?.let { return it.groupValues[1].toInt() }
        if (n.matches(Regex("""\d{1,2}"""))) return n.toInt()
        return null
    }

    private fun parseSeasonEpisode(path: String): Pair<Int, Int>? {
        val clean = path.replace("\\", "/")
        val file = clean.substringAfterLast("/")
        val fileNoExt = file.substringBeforeLast(".", file)

        Regex("""(?i)s(\d{1,2})\s*e(\d{1,2})""").find(fileNoExt)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b""").find(fileNoExt)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        Regex("""(?i)s(\d{1,2})[^\d]{0,3}(\d{1,2})""").find(fileNoExt)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }

        val parts = clean.trim('/').split("/").filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val seasonDir = normalizeName(parts[parts.size - 2])
            val seasonNum =
                Regex("""(?i)\b(t|temp|season)\s*(\d{1,2})\b""")
                    .find(seasonDir)?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: Regex("""(?i)\bs(\d{1,2})\b""")
                        .find(seasonDir)?.groupValues?.getOrNull(1)?.toIntOrNull()

            if (seasonNum != null) {
                val epNum =
                    Regex("""(?i)\b(ep|e|cap|c)\s*(\d{1,3})\b""")
                        .find(fileNoExt)?.groupValues?.getOrNull(2)?.toIntOrNull()
                        ?: Regex("""(?i)\b(\d{1,3})\b""")
                            .find(fileNoExt)?.groupValues?.getOrNull(1)?.toIntOrNull()

                if (epNum != null) return seasonNum to epNum
            }
        }
        return null
    }

    private fun inferSagaNameFromPath(path: String): String {
        val clean = path.replace("\\", "/").trim('/')
        val parts = clean.split("/").filter { it.isNotBlank() }
        if (parts.size < 2) return "Películas"

        val parent = parts[parts.size - 2]
        val grand = parts.getOrNull(parts.size - 3)

        val parentNorm = normalizeName(parent)
        val looksLikePart =
            parentNorm.matches(Regex(""".*\b(\d{1,2}|i{1,6}|iv|v|vi)\b.*""")) ||
                    parentNorm.contains("part") ||
                    parentNorm.contains("parte")

        return if (looksLikePart && !grand.isNullOrBlank()) grand else parent
    }

    private fun extractMovieSortKey(path: String, title: String): Int {
        val name = path.substringAfterLast("/").substringBeforeLast(".")
        Regex("""\[(\d{1,3})]""").find(name)?.let { return it.groupValues[1].toInt() }
        Regex("""^(\d{1,3})\D""").find(name)?.let { return it.groupValues[1].toInt() }
        Regex("""(?i)\b(part|parte)\s*(\d{1,3})\b""").find(name)?.let { return it.groupValues[2].toInt() }
        return Int.MAX_VALUE
    }

    private fun buildQueryForPathSmart(path: String): String {
        val clean = path.replace("\\", "/").trim('/')
        val parts = clean.split("/").filter { it.isNotBlank() }
        val fileNoExt = parts.last().substringBeforeLast(".")

        val se = parseSeasonEpisode(path)
        if (se != null && parts.size >= 3) {
            val seriesName = parts[parts.size - 3]
            val (season, ep) = se
            return "${normalizeName(seriesName)} s${pad2(season)} ${pad2(ep)}"
        }
        return normalizeName(fileNoExt)
    }

    private fun fetchCoverFromApiCached(q: String): ApiCover? {
        if (coverCache.containsKey(q)) return coverCache[q]
        val cover = fetchCoverFromApi(q)
        coverCache[q] = cover
        return cover
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

    private fun filterAlreadyImported(previews: List<PreviewJson>): List<PreviewJson> {
        val existing = jsonDataManager.getImportedJsons().map { it.fileName }.toSet()
        return previews.filter { it.fileName !in existing }
    }

    private fun uniqueJsonName(base: String): String {
        val existing = jsonDataManager.getImportedJsons().map { it.fileName }.toSet()
        if (!existing.contains(base)) return base

        val dot = base.lastIndexOf(".json")
        val prefix = if (dot >= 0) base.substring(0, dot) else base
        var i = 2
        while (true) {
            val candidate = "${prefix}_$i.json"
            if (!existing.contains(candidate)) return candidate
            i++
        }
    }

    private fun importPreview(preview: PreviewJson) {
        val safeName = uniqueJsonName(preview.fileName)
        jsonDataManager.addJson(context, safeName, preview.videos)
    }

    private fun importAllPreviews(previews: List<PreviewJson>) {
        previews.forEach { importPreview(it) }
    }

    // -------------------------
    // SMB list (igual a tu MainFragment)
    // -------------------------
    private fun smbListVideos(
        host: String,
        creds: SmbGateway.SmbCreds,
        shareName: String,
        max: Int = 100
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
                        val lower = name.lowercase()
                        val isVideo = lower.endsWith(".mp4")
                                || lower.endsWith(".mkv")
                                || lower.endsWith(".avi")
                                || lower.endsWith(".webm")
                                || lower.endsWith(".mov")
                                || lower.endsWith(".flv")
                                || lower.endsWith(".mpg")
                                || lower.endsWith(".mpeg")
                                || lower.endsWith(".m4v")
                                || lower.endsWith(".ts")
                                || lower.endsWith(".3gp")
                                || lower.endsWith(".wmv")

                        if (isVideo) out.add(childPath)
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
}
