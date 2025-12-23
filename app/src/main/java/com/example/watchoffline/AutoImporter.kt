package com.example.watchoffline

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.net.URLEncoder
import com.hierynomus.msfscc.FileAttributes

class AutoImporter(
    private val context: Context,
    private val smbGateway: SmbGateway,
    private val jsonDataManager: JsonDataManager,
    private val proxyPort: Int = 8081
) {
    private val tag = "AutoImporter"

    private data class RawItem(
        val serverId: String,
        val share: String,
        val smbPath: String,   // path dentro del share (ej "Movies/Matrix.mkv")
        val title: String,
        val img: String,
        val skip: Int,
        val videoSrc: String
    )

    private data class PreviewJson(
        val fileName: String,
        val videos: List<VideoItem>,
        val debug: String
    )

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

    private val coverCache = mutableMapOf<String, ApiCover?>()

    // ✅ fallback seguro para loaders (Glide / Coil / Picasso)
    private fun defaultPosterUrl(): String =
        "file:///android_res/drawable/movie.png"

    private fun resolveCoverUrl(apiUrl: String?): String {
        val u = apiUrl?.trim()
        return if (!u.isNullOrEmpty()) u else defaultPosterUrl()
    }


    fun run(
        toast: (String) -> Unit,
        onDone: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                toast("Importando desde SMB…")

                // ✅ Proxy debe estar prendido
                smbGateway.ensureProxyStarted(proxyPort)

                // ✅ Multi SMB: agarramos TODOS los servers guardados
                val serverIds = smbGateway.listCachedServerIds()
                if (serverIds.isEmpty()) {
                    onError("No hay SMB guardados. Usá 'Conectarse al SMB' y guardá al menos uno.")
                    return@Thread
                }

                val allRawItems = mutableListOf<RawItem>()
                var scannedServers = 0

                for (serverId in serverIds) {
                    val share = smbGateway.getLastShare(serverId)
                    if (share.isNullOrBlank()) {
                        Log.w(tag, "No share saved for serverId=$serverId (skip)")
                        continue
                    }

                    scannedServers++
                    toast("Escaneando SMB ($scannedServers/${serverIds.size})… $share")

                    val smbFiles = listSmbVideos(serverId, share)

                    for (smbPath in smbFiles) {
                        val q = buildQueryForPathSmart(smbPath)
                        val cover = fetchCoverFromApiCached(q)

                        val url = buildProxyUrl(serverId, share, smbPath)

                        // ✅ ÚNICO FIX: si cover.url viene vacía -> usar drawable movie.png
                        val finalImg = resolveCoverUrl(cover?.url)

                        allRawItems.add(
                            RawItem(
                                serverId = serverId,
                                share = share,
                                smbPath = smbPath,
                                title = cover?.id
                                    ?: smbPath.substringAfterLast("/").substringBeforeLast("."),
                                img = finalImg,
                                skip = cover?.skipToSecond ?: 0,
                                videoSrc = url
                            )
                        )

                    }
                }

                if (allRawItems.isEmpty()) {
                    onError("No se encontraron videos en los SMB guardados.")
                    return@Thread
                }

                // ================= SERIES vs MOVIES =================
                val seriesMap = linkedMapOf<Pair<String, Int>, MutableList<RawItem>>()
                val movies = mutableListOf<RawItem>()

                for (ri in allRawItems) {
                    val parts = ri.smbPath.replace("\\", "/")
                        .split("/")
                        .filter { it.isNotBlank() }

                    // Esperado: .../<serie>/<temporada>/<archivo>
                    if (parts.size >= 3) {
                        val series = parts[parts.size - 3]
                        val season = parseSeasonFromFolderOrName(parts[parts.size - 2])
                        if (season != null) {
                            seriesMap.getOrPut(series to season) { mutableListOf() }.add(ri)
                            continue
                        }
                    }
                    movies.add(ri)
                }

                val previews = mutableListOf<PreviewJson>()

                // Series → 1 JSON por temporada
                for ((key, items) in seriesMap.toList()
                    .sortedWith(compareBy({ normalizeName(it.first.first) }, { it.first.second }))) {

                    val (series, season) = key

                    val videos = items.sortedWith(
                        compareBy<RawItem>(
                            { parseSeasonEpisode(it.smbPath)?.second ?: Int.MAX_VALUE },
                            { it.smbPath.lowercase() }
                        )
                    ).map { r ->
                        // r.img ya viene sanitizado con fallback
                        VideoItem(r.title, r.skip, r.img, r.img, r.videoSrc)
                    }

                    previews.add(
                        PreviewJson(
                            fileName = "${normalizeName(series)}_s${pad2(season)}.json",
                            videos = videos,
                            debug = "SMB SERIES $series S$season"
                        )
                    )
                }

                // Movies → agrupar por saga (carpeta padre)
                val sagaMap = linkedMapOf<String, MutableList<RawItem>>()
                for (m in movies) {
                    sagaMap.getOrPut(inferSagaNameFromPath(m.smbPath)) { mutableListOf() }.add(m)
                }

                for ((saga, items) in sagaMap.toList()
                    .sortedWith(compareBy({ normalizeName(it.first) }))) {

                    val videosSorted = items.sortedWith(
                        compareBy<RawItem>(
                            { extractMovieSortKey(it.smbPath, it.title) },
                            { normalizeName(it.title) },
                            { it.smbPath.lowercase() }
                        )
                    ).map { r ->
                        // r.img ya viene sanitizado con fallback
                        VideoItem(r.title, r.skip, r.img, r.img, r.videoSrc)
                    }

                    val fileName =
                        if (items.size > 1) {
                            "saga_${normalizeName(saga).replace(" ", "_")}.json"
                        } else {
                            "${normalizeName(items.first().title).replace(" ", "_")}.json"
                        }

                    previews.add(
                        PreviewJson(
                            fileName = fileName,
                            videos = videosSorted,
                            debug = "SMB MOVIES"
                        )
                    )
                }

                val toImport = filterAlreadyImported(previews)
                if (toImport.isEmpty()) {
                    toast("Todo ya estaba importado")
                    onDone(0)
                    return@Thread
                }

                for (p in toImport) {
                    val safeName = uniqueJsonName(p.fileName)
                    jsonDataManager.addJson(context, safeName, p.videos)
                }

                onDone(toImport.size)

            } catch (e: Exception) {
                Log.e(tag, "FAILED", e)
                onError("Error: ${e.message}")
            }
        }.start()
    }

    // =========================
    // ✅ LISTAR VIDEOS SMB (recursivo)
    // =========================

    private fun listSmbVideos(serverId: String, shareName: String): List<String> {
        val out = mutableListOf<String>()

        val videoExt = setOf(
            "mp4","mkv","avi","webm","mov","flv","mpg","mpeg","m4v","ts","3gp","wmv"
        )

        fun isVideo(name: String): Boolean {
            val ext = name.substringAfterLast(".", "").lowercase()
            return ext in videoExt
        }

        fun shouldSkipFolder(path: String): Boolean {
            val p = path.lowercase()
            return p.contains("system volume information") ||
                    p.contains("\$recycle.bin") ||
                    (p.contains("windows") && p.contains("installer"))
        }

        smbGateway.withDiskShare(serverId, shareName) { share ->
            fun walk(dir: String) {
                val cleanDir = dir.trim('\\', '/')
                if (cleanDir.isNotEmpty() && shouldSkipFolder(cleanDir)) return

                val list = try {
                    if (cleanDir.isBlank()) share.list("") else share.list(cleanDir)
                } catch (_: Exception) {
                    return
                }

                for (f in list) {
                    val name = f.fileName ?: continue
                    if (name == "." || name == "..") continue

                    val full = if (cleanDir.isBlank()) name else "$cleanDir/$name"

                    val attrs = f.fileAttributes.toLong()
                    val isDir = (attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value.toLong()) != 0L

                    if (isDir) {
                        walk(full)
                    } else {
                        if (isVideo(name)) out.add(full.replace("\\", "/"))
                    }
                }
            }
            walk("")
        }

        return out
    }

    private fun buildProxyUrl(serverId: String, share: String, smbPath: String): String {
        val encodedPath = smbPath.split("/").joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        val shareEnc = URLEncoder.encode(share, "UTF-8").replace("+", "%20")
        return "http://127.0.0.1:$proxyPort/smb/$serverId/$shareEnc/$encodedPath"
    }

    // =========================
    // Cover API + cache
    // =========================

    private fun fetchCoverFromApiCached(q: String): ApiCover? {
        if (coverCache.containsKey(q)) return coverCache[q]
        val cover = try { fetchCoverFromApi(q) } catch (_: Exception) { null }
        coverCache[q] = cover
        return cover
    }

    private fun fetchCoverFromApi(q: String): ApiCover? {
        val base = "https://api-watchoffline.luzardo-thomas.workers.dev/cover?q="
        val full = base + URLEncoder.encode(q.replace("+", " "), "UTF-8")
        val conn = (java.net.URL(full).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Gson().fromJson(body, ApiCover::class.java)
        } finally {
            conn.disconnect()
        }
    }

    // =========================
    // HELPERS
    // =========================

    private fun pad2(n: Int): String = n.toString().padStart(2, '0')

    private fun normalizeName(s: String): String =
        s.trim().replace('_', ' ')
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

    private fun filterAlreadyImported(previews: List<PreviewJson>): List<PreviewJson> {
        val existing = jsonDataManager.getImportedJsons().map { it.fileName }.toSet()
        return previews.filter { it.fileName !in existing }
    }
}
