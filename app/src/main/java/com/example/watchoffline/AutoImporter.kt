package com.example.watchoffline

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.hierynomus.msfscc.FileAttributes
import org.videolan.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

class AutoImporter(
    private val context: Context,
    private val smbGateway: SmbGateway,
    private val jsonDataManager: JsonDataManager,
    private val proxyPort: Int = 8081
) {
    private val tag = "AutoImporter"

    // =========================
    // Models
    // =========================

    private data class RawItem(
        val serverId: String,
        val share: String,
        val smbPath: String,
        val title: String,
        val img: String,
        val skip: Int,
        val delay: Int,          // ✅ NUEVO
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
        @SerializedName("skipSeconds") val skipToSecond: Int? = null,

        // ✅ NUEVO: viene del Worker (out.delaySeconds)
        @SerializedName("delaySeconds") val delaySkip: Int? = null,

        @SerializedName("file") val file: String? = null,
        @SerializedName("url") val url: String? = null
    )

    // =========================
    // Constants / fast helpers
    // =========================

    private val gson = Gson()

    private val coverCache = Collections.synchronizedMap(object : LinkedHashMap<String, ApiCover?>(256, 0.75f, true) {
        private val MAX = 600
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ApiCover?>?): Boolean = size > MAX
    })

    private val coverPool = Executors.newFixedThreadPool(8)
    private val COVER_TIMEOUT_MS = 8000

    private val videoExt = hashSetOf(
        "mp4","mkv","avi","webm","mov","flv","mpg","mpeg","m4v","ts","3gp","wmv"
    )

    // =========================
    // Regex precompiladas
    // =========================

    private val reSeason1 = Regex("""(?i)temporada\s*(\d{1,2})""")
    private val reSeason2 = Regex("""(?i)\btemp\s*(\d{1,2})""")
    private val reSeason3 = Regex("""(?i)\bseason\s*(\d{1,2})""")
    private val reSeason4 = Regex("""(?i)\bs(\d{1,2})\b""")

    private val reSE1 = Regex("""(?i)\bs(\d{1,2})\s*[._\- ]*\s*e(\d{1,3})\b""")
    private val reSE2 = Regex("""(?i)\b(\d{1,2})\s*x\s*(\d{1,3})\b""")

    private val reEpWords = Regex("""(?i)\b(?:ep|e|cap|c|episode)\s*0*(\d{1,3})\b""")
    private val reEpTail = Regex("""(?i)(?:[_\-\s])0*(\d{1,3})\s*$""")

    private val reSagaLooksLikePart = Regex(""".*\b(\d{1,2}|i{1,6}|iv|v|vi)\b.*""", RegexOption.IGNORE_CASE)

    private val reMovieKey1 = Regex("""\[(\d{1,3})]""")
    private val reMovieKey2 = Regex("""^(\d{1,3})\D""")
    private val reMovieKey3 = Regex("""(?i)\b(part|parte)\s*(\d{1,3})\b""")

    private val API_HOST = "api-watchoffline.luzardo-thomas.workers.dev"

    private val trustAllSslSocketFactory by lazy {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        )

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        ctx.socketFactory
    }

    private val trustAllHostnameVerifier = HostnameVerifier { hostname, _ ->
        hostname.equals(API_HOST, ignoreCase = true)
    }

    private fun openConnectionForUrl(fullUrl: String): HttpURLConnection {
        val url = URL(fullUrl)
        val conn = url.openConnection() as HttpURLConnection

        if (conn is HttpsURLConnection && url.host.equals(API_HOST, ignoreCase = true) && BuildConfig.DEBUG) {
            conn.sslSocketFactory = trustAllSslSocketFactory
            conn.hostnameVerifier = trustAllHostnameVerifier
        }

        return conn
    }

    // =========================
    // Public API
    // =========================
/*
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

                val allRawItems = ArrayList<RawItem>(512)
                var scannedServers = 0

                for (serverId in serverIds) {
                    val share = smbGateway.getLastShare(serverId)
                    if (share.isNullOrBlank()) {
                        Log.w(tag, "No share saved for serverId=$serverId (skip)")
                        continue
                    }

                    scannedServers++
                    toast("Escaneando SMB ($scannedServers/${serverIds.size}) del share: \'$share\'")

                    val smbFiles = listSmbVideos(serverId, share)
                    if (smbFiles.isEmpty()) continue

                    val jobs = ArrayList<Pair<String, String>>(smbFiles.size) // (path, query)
                    for (p in smbFiles) jobs.add(p to buildQueryForPathSmart(p))

                    val coverMap = fetchCoversBatch(jobs.map { it.second }.distinct())

                    for ((smbPath, q) in jobs) {
                        val cover = coverMap[q]

                        val videoUrl = buildProxyUrl(serverId, share, smbPath)
                        val img = resolveCoverUrl(cover?.url)
                        val title = buildDisplayTitleForItem(smbPath, cover)

                        val isSeries = cover?.type.equals("series", ignoreCase = true)

                        val skipFinal = if (isSeries) (cover?.skipToSecond ?: 0) else 0

                        val delayFinal = if (isSeries) (cover?.delaySkip ?: 0) else 0

                        allRawItems.add(
                            RawItem(
                                serverId = serverId,
                                share = share,
                                smbPath = smbPath,
                                title = title,
                                img = img,
                                skip = skipFinal,
                                delay = delayFinal,
                                videoSrc = videoUrl
                            )
                        )
                    }
                }

                if (allRawItems.isEmpty()) {
                    onError("No se encontraron videos en los SMB guardados.")
                    return@Thread
                }

                val unique = LinkedHashMap<String, RawItem>(allRawItems.size)
                for (ri in allRawItems) unique.putIfAbsent(ri.videoSrc, ri)
                val itemsUnique = unique.values.toList()

                val seriesMap = linkedMapOf<Pair<String, Int>, MutableList<RawItem>>()
                val movies = ArrayList<RawItem>(itemsUnique.size)

                for (ri in itemsUnique) {
                    val parts = splitPathParts(ri.smbPath)
                    if (parts.size >= 3) {
                        val series = parts[parts.size - 3]
                        val season = parseSeasonFromFolderOrName(parts[parts.size - 2])
                        if (season != null) {
                            seriesMap.getOrPut(series to season) { ArrayList() }.add(ri)
                            continue
                        }
                    }
                    movies.add(ri)
                }

                val previews = ArrayList<PreviewJson>(seriesMap.size + 16)

                val seriesEntries = seriesMap.entries.toList()
                    .sortedWith(compareBy({ normalizeName(it.key.first) }, { it.key.second }))

                for ((key, list) in seriesEntries) {
                    val (series, season) = key

                    list.sortWith(
                        compareBy<RawItem>(
                            { parseEpisodeForSort(it.smbPath) ?: Int.MAX_VALUE },
                            { it.smbPath.lowercase() }
                        )
                    )

                    val videos = ArrayList<VideoItem>(list.size)
                    for (r in list) {
                        // ✅ IMPORTANTE: VideoItem debe aceptar delay en el constructor.
                        videos.add(VideoItem(r.title, r.skip, r.delay, r.img, r.img, r.videoSrc))
                    }

                    previews.add(
                        PreviewJson(
                            fileName = "${normalizeName(series)}_s${pad2(season)}.json",
                            videos = videos,
                            debug = "SMB SERIES $series S$season"
                        )
                    )
                }

                val sagaMap = linkedMapOf<String, MutableList<RawItem>>()
                for (m in movies) {
                    sagaMap.getOrPut(inferSagaNameFromPath(m.smbPath)) { ArrayList() }.add(m)
                }

                val sagaEntries = sagaMap.entries.toList()
                    .sortedWith(compareBy({ normalizeName(it.key) }))

                for ((saga, list) in sagaEntries) {
                    list.sortWith(
                        compareBy<RawItem>(
                            { extractMovieSortKey(it.smbPath, it.title) },
                            { normalizeName(it.title) },
                            { it.smbPath.lowercase() }
                        )
                    )

                    val videos = ArrayList<VideoItem>(list.size)
                    for (r in list) {
                        // movies: delay=0
                        videos.add(VideoItem(r.title, r.skip, r.delay, r.img, r.img, r.videoSrc))
                    }

                    val fileName =
                        if (list.size > 1) "saga_${normalizeName(saga).replace(" ", "_")}.json"
                        else "${normalizeName(list.first().title).replace(" ", "_")}.json"

                    previews.add(
                        PreviewJson(
                            fileName = fileName,
                            videos = videos,
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

                val existing = jsonDataManager.getImportedJsons().map { it.fileName }.toHashSet()

                var imported = 0
                for (p in toImport) {
                    val safeName = uniqueJsonNameFast(p.fileName, existing)
                    jsonDataManager.addJson(context, safeName, p.videos)
                    existing.add(safeName)
                    imported++
                }

                onDone(imported)

            } catch (e: Exception) {
                Log.e(tag, "FAILED", e)
                onError("Error: ${e.message}")
            }
        }.start()
    }

 */

    fun run(
        toast: (String) -> Unit,
        onDone: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            var partialSuccess = false

            try {
                toast("Importando desde SMB…")
                val proxyOk = smbGateway.ensureProxyStarted(8081)
                if (!proxyOk) {
                    onError("No se pudo iniciar el Proxy SMB interno.")
                    return@Thread
                }

                val serverIds = smbGateway.listCachedServerIds()
                if (serverIds.isEmpty()) {
                    onError("No hay SMB guardados. Usá 'Conectarse al SMB' y guardá al menos uno.")
                    return@Thread
                }

                val allRawItems = ArrayList<RawItem>(512)
                var scannedServers = 0

                // 1. ESCANEO (Con try/catch interno para no detenerse si falla uno)
                for (serverId in serverIds) {
                    try {
                        val share = smbGateway.getLastShare(serverId)
                        if (share.isNullOrBlank()) continue

                        scannedServers++
                        toast("Escaneando SMB ($scannedServers/${serverIds.size})...")

                        val smbFiles = listSmbVideos(serverId, share)
                        if (smbFiles.isEmpty()) continue

                        val jobs = ArrayList<Pair<String, String>>(smbFiles.size)
                        for (p in smbFiles) jobs.add(p to buildQueryForPathSmart(p))

                        val coverMap = fetchCoversBatch(jobs.map { it.second }.distinct())

                        for ((smbPath, q) in jobs) {
                            val cover = coverMap[q]
                            val videoUrl = buildProxyUrl(serverId, share, smbPath)
                            val img = resolveCoverUrl(cover?.url)
                            val title = buildDisplayTitleForItem(smbPath, cover)
                            val isSeries = cover?.type.equals("series", ignoreCase = true)
                            val skipFinal = if (isSeries) (cover?.skipToSecond ?: 0) else 0
                            val delayFinal = if (isSeries) (cover?.delaySkip ?: 0) else 0

                            allRawItems.add(
                                RawItem(
                                    serverId = serverId,
                                    share = share,
                                    smbPath = smbPath,
                                    title = title,
                                    img = img,
                                    skip = skipFinal,
                                    delay = delayFinal,
                                    videoSrc = videoUrl
                                )
                            )
                        }
                        partialSuccess = true

                    } catch (e: Exception) {
                        Log.e(tag, "Error importando server $serverId", e)
                        // No mostramos toast de error individual para no interrumpir visualmente, solo log
                    }
                }

                // 2. VALIDACIÓN VACÍO
                if (allRawItems.isEmpty()) {
                    if (partialSuccess) {
                        toast("Escaneo finalizado: No se encontraron videos.")
                        onDone(0)
                    } else {
                        onError("No se pudieron conectar los servidores.")
                    }
                    return@Thread
                }

                // (Aquí quitamos el toast de "Procesando..." para poner el final que querías)

                // 3. AGRUPAMIENTO
                val unique = LinkedHashMap<String, RawItem>(allRawItems.size)
                for (ri in allRawItems) unique.putIfAbsent(ri.videoSrc, ri)
                val itemsUnique = unique.values.toList()

                val seriesMap = linkedMapOf<Pair<String, Int>, MutableList<RawItem>>()
                val movies = ArrayList<RawItem>(itemsUnique.size)

                for (ri in itemsUnique) {
                    val parts = splitPathParts(ri.smbPath)
                    if (parts.size >= 3) {
                        val series = parts[parts.size - 3]
                        val season = parseSeasonFromFolderOrName(parts[parts.size - 2])
                        if (season != null) {
                            seriesMap.getOrPut(series to season) { ArrayList() }.add(ri)
                            continue
                        }
                    }
                    movies.add(ri)
                }

                val previews = ArrayList<PreviewJson>(seriesMap.size + 16)

                // Series
                val seriesEntries = seriesMap.entries.toList()
                    .sortedWith(compareBy({ normalizeName(it.key.first) }, { it.key.second }))

                for ((key, list) in seriesEntries) {
                    val (series, season) = key
                    list.sortWith(
                        compareBy<RawItem>(
                            { parseEpisodeForSort(it.smbPath) ?: Int.MAX_VALUE },
                            { it.smbPath.lowercase() }
                        )
                    )
                    val videos = list.map { VideoItem(it.title, it.skip, it.delay, it.img, it.img, it.videoSrc) }
                    previews.add(PreviewJson("${normalizeName(series)}_s${pad2(season)}.json", videos, "SMB SERIES"))
                }

                // Películas
                val sagaMap = linkedMapOf<String, MutableList<RawItem>>()
                for (m in movies) sagaMap.getOrPut(inferSagaNameFromPath(m.smbPath)) { ArrayList() }.add(m)
                val sagaEntries = sagaMap.entries.toList().sortedWith(compareBy({ normalizeName(it.key) }))

                for ((saga, list) in sagaEntries) {
                    list.sortWith(compareBy({ extractMovieSortKey(it.smbPath, it.title) }, { it.title }))
                    val videos = list.map { VideoItem(it.title, it.skip, it.delay, it.img, it.img, it.videoSrc) }
                    val fName = if (list.size > 1) "saga_${normalizeName(saga).replace(" ", "_")}.json"
                    else "${normalizeName(list.first().title).replace(" ", "_")}.json"
                    previews.add(PreviewJson(fName, videos, "SMB MOVIES"))
                }

                // 4. GUARDADO
                val toImport = filterAlreadyImported(previews)

                // Si no hay nada nuevo, igual mostramos el total encontrado, pero avisamos que 0 se importaron
                if (toImport.isEmpty()) {
                    val msg = "Se importaron 0 JSONs y se encontraron un total de ${allRawItems.size} videos en total"
                    toast(msg)
                    onDone(0)
                    return@Thread
                }

                val existing = jsonDataManager.getImportedJsons().map { it.fileName }.toHashSet()
                var importedFilesCount = 0

                for (p in toImport) {
                    val safeName = uniqueJsonNameFast(p.fileName, existing)
                    jsonDataManager.addJson(context, safeName, p.videos)
                    existing.add(safeName)
                    importedFilesCount++
                }

                // ✅ AQUÍ ESTÁ EL MENSAJE FINAL EXACTO QUE PEDISTE
                // importedFilesCount = Cantidad de archivos JSON creados/guardados
                // allRawItems.size = Total absoluto de videos detectados en el escaneo
                val finalMsg = "JSONS IMPORTADOS: $importedFilesCount\nVIDEOS IMPORTADOS: ${allRawItems.size}"

                toast(finalMsg)
                onDone(importedFilesCount)

            } catch (e: Exception) {
                Log.e(tag, "FAILED GLOBAL", e)
                onError("Error general: ${e.message}")
            }
        }.start()
    }

    // =========================
    // Cover resolving
    // =========================

    private fun resolveCoverUrl(apiUrl: String?): String {
        val u = apiUrl?.trim()
        return if (!u.isNullOrEmpty()) u else ""
    }

    private fun fetchCoversBatch(queries: List<String>): Map<String, ApiCover?> {
        if (queries.isEmpty()) return emptyMap()

        val out = LinkedHashMap<String, ApiCover?>(queries.size)

        val missing = ArrayList<String>()
        for (q in queries) {
            val cached = coverCache[q]
            if (cached != null || coverCache.containsKey(q)) {
                out[q] = cached
            } else {
                missing.add(q)
            }
        }
        if (missing.isEmpty()) return out

        val futures = ArrayList<Future<Pair<String, ApiCover?>>>(missing.size)
        for (q in missing) {
            futures.add(coverPool.submit(Callable {
                q to fetchCoverFromApiSafe(q)
            }))
        }

        for (f in futures) {
            val (q, cover) = runCatching { f.get() }.getOrElse { ("" to null) }
            if (q.isNotBlank()) {
                coverCache[q] = cover
                out[q] = cover
            }
        }

        return out
    }

    private fun fetchCoverFromApiSafe(q: String): ApiCover? {
        return runCatching { fetchCoverFromApi(q) }.getOrNull()
    }

    private fun fetchCoverFromApi(q: String): ApiCover? {
        val base = "https://$API_HOST/cover?q="
        val full = base + URLEncoder.encode(q.replace("+", " "), "UTF-8")

        val conn = (openConnectionForUrl(full)).apply {
            requestMethod = "GET"
            connectTimeout = COVER_TIMEOUT_MS
            readTimeout = COVER_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val code = conn.responseCode
            if (code !in 200..299) return null

            val body = conn.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) return null

            gson.fromJson(body, ApiCover::class.java)
        } catch (e: Exception) {
            Log.e(tag, "COVER API EXCEPTION q='$q' -> ${e::class.java.simpleName}: ${e.message}", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    // =========================
    // SMB listing
    // =========================

    private fun listSmbVideos(serverId: String, shareName: String): List<String> {
        val out = ArrayList<String>(512)

        fun isVideo(name: String): Boolean {
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.length - 1) return false
            val ext = name.substring(dot + 1).lowercase()
            return ext in videoExt
        }

        fun shouldSkipFolder(path: String): Boolean {
            val p = path.lowercase()
            return p.contains("system volume information") ||
                    p.contains("\$recycle.bin") ||
                    (p.contains("windows") && p.contains("installer"))
        }

        smbGateway.withDiskShare(serverId, shareName) { share ->
            val stack = ArrayDeque<String>()
            stack.addLast("")

            while (stack.isNotEmpty()) {
                val dir = stack.removeLast()
                val cleanDir = dir.trim('\\', '/')
                if (cleanDir.isNotEmpty() && shouldSkipFolder(cleanDir)) continue

                val list = try {
                    if (cleanDir.isBlank()) share.list("") else share.list(cleanDir)
                } catch (_: Exception) {
                    continue
                }

                for (f in list) {
                    val name = f.fileName ?: continue
                    if (name == "." || name == "..") continue

                    val full = if (cleanDir.isBlank()) name else "$cleanDir/$name"

                    val attrs = f.fileAttributes.toLong()
                    val isDir = (attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value.toLong()) != 0L

                    if (isDir) {
                        stack.addLast(full)
                    } else if (isVideo(name)) {
                        out.add(full.replace("\\", "/"))
                    }
                }
            }
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
    // Helpers
    // =========================

    private fun pad2(n: Int): String = n.toString().padStart(2, '0')
    private fun pad3(n: Int): String = n.toString().padStart(3, '0')

    private fun normalizeName(s: String): String =
        s.trim()
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .lowercase()

    private fun splitPathParts(path: String): List<String> {
        val clean = path.replace("\\", "/").trim('/')
        if (clean.isBlank()) return emptyList()
        return clean.split("/").filter { it.isNotBlank() }
    }

    private fun parseSeasonFromFolderOrName(name: String): Int? {
        val n = name.lowercase().replace("_", " ").trim()
        reSeason1.find(n)?.let { return it.groupValues[1].toInt() }
        reSeason2.find(n)?.let { return it.groupValues[1].toInt() }
        reSeason3.find(n)?.let { return it.groupValues[1].toInt() }
        reSeason4.find(n)?.let { return it.groupValues[1].toInt() }
        if (n.matches(Regex("""\d{1,2}"""))) return n.toInt()
        return null
    }

    private fun fileBaseName(path: String): String =
        path.replace("\\", "/").substringAfterLast("/").substringBeforeLast(".")

    private fun parseSeasonEpisodeFromFilename(path: String): Pair<Int, Int>? {
        val file = path.replace("\\", "/").substringAfterLast("/")
        val name = file.substringBeforeLast(".", file)

        reSE1.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        reSE2.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }

        return null
    }

    private fun parseEpisodeOnlyFromFilename(path: String): Int? {
        val file = path.replace("\\", "/").substringAfterLast("/")
        val name = file.substringBeforeLast(".", file)

        reEpWords.findAll(name).toList().lastOrNull()?.let { return it.groupValues[1].toInt() }
        reEpTail.find(name)?.let { return it.groupValues[1].toInt() }

        return null
    }

    private fun parseEpisodeForSort(path: String): Int? =
        parseSeasonEpisodeFromFilename(path)?.second ?: parseEpisodeOnlyFromFilename(path)

    private fun inferSagaNameFromPath(path: String): String {
        val parts = splitPathParts(path)
        if (parts.size < 2) return "Películas"

        val parent = parts[parts.size - 2]
        val grand = parts.getOrNull(parts.size - 3)

        val parentNorm = normalizeName(parent)
        val looksLikePart =
            reSagaLooksLikePart.matches(parentNorm) ||
                    parentNorm.contains("part") ||
                    parentNorm.contains("parte")

        return if (looksLikePart && !grand.isNullOrBlank()) grand else parent
    }

    private fun extractMovieSortKey(path: String, title: String): Int {
        val name = path.substringAfterLast("/").substringBeforeLast(".")
        reMovieKey1.find(name)?.let { return it.groupValues[1].toInt() }
        reMovieKey2.find(name)?.let { return it.groupValues[1].toInt() }
        reMovieKey3.find(name)?.let { return it.groupValues[2].toInt() }
        return Int.MAX_VALUE
    }

    private fun buildQueryForPathSmart(path: String): String {
        val parts = splitPathParts(path)
        if (parts.isEmpty()) return ""

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

    private fun filterAlreadyImported(previews: List<PreviewJson>): List<PreviewJson> {
        val existing = jsonDataManager.getImportedJsons().map { it.fileName }.toHashSet()
        return previews.filter { it.fileName !in existing }
    }

    private fun uniqueJsonNameFast(base: String, existing: MutableSet<String>): String {
        if (!existing.contains(base)) return base

        val prefix = base.removeSuffix(".json")
        var i = 2
        while (true) {
            val candidate = "${prefix}_$i.json"
            if (!existing.contains(candidate)) return candidate
            i++
        }
    }

    private fun prettyCap(ep: Int?): String? =
        if (ep == null || ep <= 0) null else "Cap ${ep.toString().padStart(2, '0')}"

    private fun prettyTemp(season: Int?): String? =
        if (season == null || season <= 0) null else "T${season.toString().padStart(2, '0')}"

    private fun buildDisplayTitleForItem(smbPath: String, cover: ApiCover?): String {
        val fallbackName = fileBaseName(smbPath)
        val seriesName = cover?.id ?: fallbackName

        val seFile = parseSeasonEpisodeFromFilename(smbPath)
        val epFileOnly = parseEpisodeOnlyFromFilename(smbPath)

        val seasonFromFile = seFile?.first
        val epFromFile = seFile?.second ?: epFileOnly
        val epFinal = epFromFile ?: cover?.episode

        val isSeries = cover?.type.equals("series", ignoreCase = true) ||
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
