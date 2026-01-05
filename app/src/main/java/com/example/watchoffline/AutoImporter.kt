package com.example.watchoffline

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.hierynomus.msfscc.FileAttributes
import org.videolan.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
        val serverId: String, val share: String, val smbPath: String,
        val title: String, val img: String, val skip: Int, val delay: Int, val videoSrc: String
    )

    private data class PreviewJson(val fileName: String, val videos: List<VideoItem>, val debug: String)

    @Keep
    private data class ApiCover(
        @SerializedName("type") val type: String? = null,
        @SerializedName("id") val id: String? = null,
        @SerializedName("dir") val dir: String? = null,
        @SerializedName("season") val season: Int? = null,
        @SerializedName("episode") val episode: Int? = null,
        @SerializedName("skipSeconds") val skipToSecond: Int? = null,
        @SerializedName("delaySeconds") val delaySkip: Int? = null,
        @SerializedName("file") val file: String? = null,
        @SerializedName("url") val url: String? = null
    )

    // =========================
    // Configuration & Thread Pools
    // =========================
    private val gson = Gson()
    private val coverCache = ConcurrentHashMap<String, ApiCover?>()

    // 🚀 NITROS: Aumentamos threads ligeramente para compensar Keep-Alive wait times
    // Si el servidor aguanta, 96 hilos saturarán la red del móvil al máximo.
    private val coverPool = Executors.newFixedThreadPool(96)
    private val scanPool = Executors.newFixedThreadPool(8)

    private val COVER_TIMEOUT_MS = 3500

    private val videoExt = hashSetOf("mp4", "mkv", "avi", "webm", "mov", "flv", "mpg", "mpeg", "m4v", "ts", "3gp", "wmv")

    // Regex (Precompiladas estáticas para evitar recreación)
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

    init {
        // 🚀 CRÍTICO: Permitir Connection Pooling (Keep-Alive)
        System.setProperty("http.keepAlive", "true")
        System.setProperty("http.maxConnections", "100") // Permitir muchas conexiones simultáneas
    }

    private val trustAllSslSocketFactory by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
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
    // Public API: RUN
    // =========================

    fun run(
        toast: (String) -> Unit,
        onDone: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val startTime = System.nanoTime() // NanoTime para precisión
                toast("Importando de los SMBs...")

                val proxyOk = smbGateway.ensureProxyStarted(proxyPort)
                if (!proxyOk) { onError("Error Proxy SMB."); return@Thread }

                val serverIds = smbGateway.listCachedServerIds()
                if (serverIds.isEmpty()) { onError("Sin servidores."); return@Thread }

                val allRawItems = CopyOnWriteArrayList<RawItem>()
                val scanFutures = ArrayList<java.util.concurrent.Future<*>>()

                // 1. SCAN PARALELO
                for (serverId in serverIds) {
                    scanFutures.add(scanPool.submit {
                        try {
                            val share = smbGateway.getLastShare(serverId)
                            if (!share.isNullOrBlank()) processServer(serverId, share, allRawItems)
                        } catch (e: Exception) { Log.e(tag, "Scan Err $serverId", e) }
                    })
                }
                for (f in scanFutures) try { f.get() } catch (_: Exception) {}

                if (allRawItems.isEmpty()) { toast("0 videos."); onDone(0); return@Thread }

                // 2. PROCESAMIENTO IN-MEMORY (Ultra Rápido)
                val uniqueItems = allRawItems.distinctBy { it.videoSrc }
                val seriesMap = HashMap<Pair<String, Int>, ArrayList<RawItem>>()
                val movies = ArrayList<RawItem>(uniqueItems.size)

                for (ri in uniqueItems) {
                    val parts = splitPathParts(ri.smbPath)
                    var isSeries = false
                    if (parts.size >= 3) {
                        val series = parts[parts.size - 3]
                        val season = parseSeasonFromFolderOrName(parts[parts.size - 2])
                        if (season != null) {
                            seriesMap.getOrPut(series to season) { ArrayList() }.add(ri)
                            isSeries = true
                        }
                    }
                    if (!isSeries) movies.add(ri)
                }

                val previews = ArrayList<PreviewJson>(seriesMap.size + 20)

                for ((key, list) in seriesMap) {
                    list.sortWith(compareBy({ parseEpisodeForSort(it.smbPath) ?: Int.MAX_VALUE }, { it.smbPath }))
                    previews.add(PreviewJson("${normalizeName(key.first)}_s${pad2(key.second)}.json", list.map { VideoItem(it.title, it.skip, it.delay, it.img, it.img, it.videoSrc) }, "SERIES"))
                }

                val sagaMap = HashMap<String, ArrayList<RawItem>>()
                for (m in movies) sagaMap.getOrPut(inferSagaNameFromPath(m.smbPath)) { ArrayList() }.add(m)

                for ((saga, list) in sagaMap) {
                    list.sortWith(compareBy({ extractMovieSortKey(it.smbPath, it.title) }, { it.title }))
                    val fName = if (list.size > 1) "saga_${normalizeName(saga).replace(" ", "_")}.json" else "${normalizeName(list.first().title).replace(" ", "_")}.json"
                    previews.add(PreviewJson(fName, list.map { VideoItem(it.title, it.skip, it.delay, it.img, it.img, it.videoSrc) }, "MOVIES"))
                }

                // 3. GUARDADO
                val toImport = filterAlreadyImported(previews)
                if (toImport.isEmpty()) {
                    onDone(0)
                    val ms = (System.nanoTime() - startTime) / 1_000_000
                    toast("0 nuevos (${allRawItems.size} total) en ${ms}ms")
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

                //val ms = (System.nanoTime() - startTime) / 1_000_000
                toast("JSONs: $importedFilesCount\nVIDEOS: ${allRawItems.size}")
                //toast("TIEMPO: ${ms / 1000.0}s")
                onDone(importedFilesCount)

            } catch (e: Exception) {
                Log.e(tag, "CRITICAL", e)
                onError("Error: ${e.message}")
            }
        }.start()
    }

    private fun processServer(serverId: String, share: String, destList: CopyOnWriteArrayList<RawItem>) {
        val smbFiles = listSmbVideos(serverId, share)
        if (smbFiles.isEmpty()) return

        val queriesToFetch = HashSet<String>()
        val jobMap = HashMap<String, String>(smbFiles.size)

        for (p in smbFiles) {
            val q = buildQueryForPathSmart(p)
            jobMap[p] = q
            queriesToFetch.add(q)
        }

        val coverResults = fetchCoversParallel(queriesToFetch.toList())

        for (p in smbFiles) {
            val q = jobMap[p] ?: ""
            val cover = coverResults[q]
            val videoUrl = buildProxyUrl(serverId, share, p)
            val img = resolveCoverUrl(cover?.url)
            val title = buildDisplayTitleForItem(p, cover)
            val isSeries = cover?.type.equals("series", ignoreCase = true)

            destList.add(RawItem(serverId, share, p, title, img,
                if (isSeries) (cover?.skipToSecond ?: 0) else 0,
                if (isSeries) (cover?.delaySkip ?: 0) else 0,
                videoUrl))
        }
    }

    private fun fetchCoversParallel(queries: List<String>): Map<String, ApiCover?> {
        if (queries.isEmpty()) return emptyMap()
        val results = ConcurrentHashMap<String, ApiCover?>()
        val futures = ArrayList<java.util.concurrent.Future<*>>()

        val toAsk = ArrayList<String>()
        for (q in queries) {
            val cached = coverCache[q]
            if (cached != null) results[q] = cached
            else if (!coverCache.containsKey(q)) toAsk.add(q)
        }

        if (toAsk.isEmpty()) return results

        for (q in toAsk) {
            futures.add(coverPool.submit {
                val cover = fetchCoverFromApi(q) // Sin Safe wrapper, directo para velocidad
                coverCache[q] = cover
                results[q] = cover
            })
        }
        for (f in futures) try { f.get() } catch (_: Exception) {}
        return results
    }

    // 🚀 OPTIMIZACIÓN HTTP KEEP-ALIVE
    private fun fetchCoverFromApi(q: String): ApiCover? {
        // Encode manual rápido
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val conn = openConnectionForUrl("https://$API_HOST/cover?q=$encoded")

        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = COVER_TIMEOUT_MS
            conn.readTimeout = COVER_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            // ⚠️ ELIMINADO: "Connection: close" para permitir Keep-Alive

            if (conn.responseCode in 200..299) {
                // Leemos con BufferedReader para eficiencia
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line)
                reader.close()
                gson.fromJson(sb.toString(), ApiCover::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
        // ⚠️ ELIMINADO: conn.disconnect() -> Esto permite reutilizar el socket SSL
    }

    // =========================
    // SMB & Helpers (Optimizados)
    // =========================

    private fun listSmbVideos(serverId: String, shareName: String): List<String> {
        val out = ArrayList<String>(512)
        smbGateway.withDiskShare(serverId, shareName) { share ->
            val stack = ArrayDeque<String>(); stack.addLast("")
            while (stack.isNotEmpty()) {
                val cleanDir = stack.removeLast().trim('\\', '/')
                val lowerDir = cleanDir.lowercase()
                if (lowerDir.contains("system volume") || lowerDir.contains("\$recycle")) continue

                try {
                    val list = if (cleanDir.isBlank()) share.list("") else share.list(cleanDir)
                    for (f in list) {
                        val name = f.fileName
                        if (name == "." || name == "..") continue
                        val isDir = (f.fileAttributes.toLong() and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value.toLong()) != 0L
                        if (isDir) {
                            stack.addLast(if (cleanDir.isBlank()) name else "$cleanDir/$name")
                        } else {
                            val dot = name.lastIndexOf('.')
                            if (dot > 0 && videoExt.contains(name.substring(dot + 1).lowercase())) {
                                out.add(if (cleanDir.isBlank()) name else "$cleanDir/$name".replace("\\", "/"))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return out
    }

    private fun buildProxyUrl(serverId: String, share: String, smbPath: String): String {
        val sb = StringBuilder("http://127.0.0.1:$proxyPort/smb/").append(serverId).append("/")
        sb.append(URLEncoder.encode(share, "UTF-8").replace("+", "%20")).append("/")
        val parts = smbPath.split("/")
        for (i in parts.indices) {
            sb.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"))
            if (i < parts.size - 1) sb.append("/")
        }
        return sb.toString()
    }

    // Regex y parsers (Sin cambios lógicos, solo compactados)
    private fun pad2(n: Int) = if (n < 10) "0$n" else "$n"
    private fun pad3(n: Int) = if (n < 10) "00$n" else if (n < 100) "0$n" else "$n"
    private fun normalizeName(s: String) = s.trim().replace('_', ' ').replace(Regex("""\s+"""), " ").lowercase()
    private fun splitPathParts(path: String) = path.replace("\\", "/").trim('/').split("/").filter { it.isNotBlank() }

    private fun parseSeasonFromFolderOrName(name: String): Int? {
        val n = name.lowercase().replace("_", " ").trim()
        return reSeason1.find(n)?.groupValues?.get(1)?.toInt()
            ?: reSeason2.find(n)?.groupValues?.get(1)?.toInt()
            ?: reSeason3.find(n)?.groupValues?.get(1)?.toInt()
            ?: reSeason4.find(n)?.groupValues?.get(1)?.toInt()
            ?: if (n.matches(Regex("""\d{1,2}"""))) n.toInt() else null
    }

    private fun fileBaseName(path: String) = path.replace("\\", "/").substringAfterLast("/").substringBeforeLast(".")

    private fun parseSeasonEpisodeFromFilename(path: String): Pair<Int, Int>? {
        val name = fileBaseName(path)
        return reSE1.find(name)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            ?: reSE2.find(name)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }

    private fun parseEpisodeOnlyFromFilename(path: String): Int? {
        val name = fileBaseName(path)
        return reEpWords.findAll(name).lastOrNull()?.groupValues?.get(1)?.toInt()
            ?: reEpTail.find(name)?.groupValues?.get(1)?.toInt()
    }

    private fun parseEpisodeForSort(path: String) = parseSeasonEpisodeFromFilename(path)?.second ?: parseEpisodeOnlyFromFilename(path)

    private fun inferSagaNameFromPath(path: String): String {
        val parts = splitPathParts(path)
        if (parts.size < 2) return "Películas"
        val parent = parts[parts.size - 2]
        val grand = parts.getOrNull(parts.size - 3)
        val pNorm = normalizeName(parent)
        val isPart = reSagaLooksLikePart.matches(pNorm) || pNorm.contains("part") || pNorm.contains("parte")
        return if (isPart && !grand.isNullOrBlank()) grand else parent
    }

    private fun extractMovieSortKey(path: String, title: String): Int {
        val name = fileBaseName(path)
        return reMovieKey1.find(name)?.groupValues?.get(1)?.toInt()
            ?: reMovieKey2.find(name)?.groupValues?.get(1)?.toInt()
            ?: reMovieKey3.find(name)?.groupValues?.get(2)?.toInt()
            ?: Int.MAX_VALUE
    }

    private fun buildQueryForPathSmart(path: String): String {
        val parts = splitPathParts(path)
        if (parts.isEmpty()) return ""
        val fileNoExt = parts.last().substringBeforeLast(".")
        val se = parseSeasonEpisodeFromFilename(path)
        if (se != null && parts.size >= 3) return "${normalizeName(parts[parts.size - 3])} s${pad2(se.first)} ${pad2(se.second)}"
        if (parts.size >= 3) {
            val season = parseSeasonFromFolderOrName(parts[parts.size - 2])
            val ep = parseEpisodeOnlyFromFilename(path)
            if (season != null && ep != null) return "${normalizeName(parts[parts.size - 3]).replace(" ", "_")}_${pad3(ep)}"
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
            val cand = "${prefix}_$i.json"
            if (!existing.contains(cand)) return cand
            i++
        }
    }

    private fun resolveCoverUrl(apiUrl: String?) = apiUrl?.trim() ?: ""

    private fun buildDisplayTitleForItem(smbPath: String, cover: ApiCover?): String {
        val fallback = fileBaseName(smbPath)
        val seFile = parseSeasonEpisodeFromFilename(smbPath)
        val epFileOnly = parseEpisodeOnlyFromFilename(smbPath)
        val season = seFile?.first
        val ep = seFile?.second ?: epFileOnly ?: cover?.episode
        val isSeries = cover?.type.equals("series", true) || season != null || ep != null
        if (!isSeries) return cover?.id ?: fallback
        val capStr = if (ep != null && ep > 0) "Cap ${pad2(ep)}" else null
        val tempStr = if (season != null && season > 0) "T${pad2(season)}" else null
        val sName = cover?.id ?: fallback
        return if (tempStr != null && capStr != null) "$tempStr $capStr - $sName" else if (capStr != null) "$capStr - $sName" else sName
    }
}