package com.example.watchoffline

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.hierynomus.msfscc.FileAttributes
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
        val smbPath: String,
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

    @Keep
    private data class ApiCover(
        @SerializedName("type") val type: String? = null,
        @SerializedName("id") val id: String? = null,
        @SerializedName("dir") val dir: String? = null,
        @SerializedName("season") val season: Int? = null,
        @SerializedName("episode") val episode: Int? = null,

        // ✅ la API manda "skipSeconds"
        @SerializedName("skipSeconds") val skipToSecond: Int? = null,

        @SerializedName("file") val file: String? = null,
        @SerializedName("url") val url: String? = null
    )

    private val coverCache = mutableMapOf<String, ApiCover?>()

    // ✅ si no hay url, guardamos "" y la UI muestra el drawable
    private fun resolveCoverUrl(apiUrl: String?): String {
        val u = apiUrl?.trim()
        return if (!u.isNullOrEmpty()) u else ""
    }


    fun run(
        toast: (String) -> Unit,
        onDone: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                toast("Importando desde SMB…")

                smbGateway.ensureProxyStarted(proxyPort)

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
                        val finalImg = resolveCoverUrl(cover?.url)
                        val displayTitle = buildDisplayTitleForItem(smbPath, cover)

                        // ✅ SKIP:
                        // - Movies: siempre 0
                        // - Series: usar lo que viene de la API (skipSeconds -> skipToSecond)
                        val skipFinal = if (cover?.type?.lowercase() == "series") {
                            cover.skipToSecond ?: 0
                        } else 0

                        allRawItems.add(
                            RawItem(
                                serverId = serverId,
                                share = share,
                                smbPath = smbPath,
                                title = displayTitle,
                                img = finalImg,
                                skip = skipFinal,
                                videoSrc = url
                            )
                        )
                    }
                }

                if (allRawItems.isEmpty()) {
                    onError("No se encontraron videos en los SMB guardados.")
                    return@Thread
                }

                val seriesMap = linkedMapOf<Pair<String, Int>, MutableList<RawItem>>()
                val movies = mutableListOf<RawItem>()

                for (ri in allRawItems) {
                    val parts = ri.smbPath.replace("\\", "/")
                        .split("/")
                        .filter { it.isNotBlank() }

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
                            { parseEpisodeForSort(it.smbPath) ?: Int.MAX_VALUE },
                            { it.smbPath.lowercase() }
                        )
                    ).map { r ->
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

                // Movies → agrupar por saga
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
                        // ✅ movies ya vienen con skip=0
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
    // SMB listing
    // =========================

    private fun listSmbVideos(serverId: String, shareName: String): List<String> {
        val out = mutableListOf<String>()

        val videoExt = setOf("mp4","mkv","avi","webm","mov","flv","mpg","mpeg","m4v","ts","3gp","wmv")

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

                    if (isDir) walk(full) else if (isVideo(name)) out.add(full.replace("\\", "/"))
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
        coverCache[q]?.let { return it }

        val cover = runCatching {
            fetchCoverFromApi(q)
        }.getOrNull()

        coverCache[q] = cover
        return cover
    }


    private fun fetchCoverFromApi(q: String): ApiCover? {
        val base = "https://api-watchoffline.luzardo-thomas.workers.dev/cover?q="
        val full = base + URLEncoder.encode(q.replace("+", " "), "UTF-8")

        val conn = (URL(full).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val code = conn.responseCode
            if (code !in 200..299) return null

            val body = conn.inputStream
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (body.isBlank()) return null

            Gson().fromJson(body, ApiCover::class.java)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }



    // =========================
    // Helpers (igual a lo que ya venías usando)
    // =========================

    private fun pad2(n: Int): String = n.toString().padStart(2, '0')
    private fun pad3(n: Int): String = n.toString().padStart(3, '0')

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

    private fun parseSeasonEpisodeFromFilename(path: String): Pair<Int, Int>? {
        val clean = path.replace("\\", "/")
        val file = clean.substringAfterLast("/")
        val name = file.substringBeforeLast(".", file)

        Regex("""(?i)\bs(\d{1,2})\s*[._\- ]*\s*e(\d{1,3})\b""")
            .find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }

        Regex("""(?i)\b(\d{1,2})\s*x\s*(\d{1,3})\b""")
            .find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }

        return null
    }

    private fun parseEpisodeOnlyFromFilename(path: String): Int? {
        val clean = path.replace("\\", "/")
        val file = clean.substringAfterLast("/")
        val name = file.substringBeforeLast(".", file)

        Regex("""(?i)\b(?:ep|e|cap|c|episode)\s*0*(\d{1,3})\b""")
            .findAll(name).toList().lastOrNull()
            ?.let { return it.groupValues[1].toInt() }

        Regex("""(?i)(?:[_\-\s])0*(\d{1,3})\s*$""")
            .find(name)?.let { return it.groupValues[1].toInt() }

        return null
    }

    private fun parseEpisodeForSort(path: String): Int? =
        parseSeasonEpisodeFromFilename(path)?.second ?: parseEpisodeOnlyFromFilename(path)

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

        val se = parseSeasonEpisodeFromFilename(path)
        if (se != null && parts.size >= 3) {
            val seriesName = parts[parts.size - 3]
            val (season, ep) = se
            return "${normalizeName(seriesName)} s${pad2(season)} ${pad2(ep)}"
        }

        if (parts.size >= 3) {
            val seriesName = parts[parts.size - 3]
            val seasonFolder = parts[parts.size - 2]
            val season = parseSeasonFromFolderOrName(seasonFolder)
            val epOnly = parseEpisodeOnlyFromFilename(path)
            if (season != null && epOnly != null) {
                val seriesKey = normalizeName(seriesName).replace(" ", "_")
                return "${seriesKey}_${pad3(epOnly)}"
            }
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

    private fun prettyCap(ep: Int?): String? =
        if (ep == null || ep <= 0) null else "Cap ${ep.toString().padStart(2, '0')}"

    private fun prettyTemp(season: Int?): String? =
        if (season == null || season <= 0) null else "T${season.toString().padStart(2, '0')}"

    private fun fileBaseName(path: String): String =
        path.replace("\\", "/").substringAfterLast("/").substringBeforeLast(".")

    private fun buildDisplayTitleForItem(smbPath: String, cover: ApiCover?): String {
        val fallbackName = fileBaseName(smbPath)
        val seriesName = cover?.id ?: fallbackName

        val seFile = parseSeasonEpisodeFromFilename(smbPath)
        val epFileOnly = parseEpisodeOnlyFromFilename(smbPath)

        val seasonFromFile = seFile?.first
        val epFromFile = seFile?.second ?: epFileOnly
        val epFinal = epFromFile ?: cover?.episode

        val isSeries = (cover?.type?.lowercase() == "series") ||
                (seasonFromFile != null) || (epFromFile != null) ||
                (cover?.season != null) || (cover?.episode != null)

        if (!isSeries) return seriesName

        val cap = prettyCap(epFinal)
        val temp = prettyTemp(seasonFromFile)

        return when {
            temp != null && cap != null -> "$temp $cap - $seriesName"
            cap != null -> "$cap - $seriesName"
            else -> seriesName
        }
    }
}
