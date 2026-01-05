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
    // Configuration & Nitros
    // =========================
    private val gson = Gson()
    private val coverCache = ConcurrentHashMap<String, ApiCover?>()
    private val coverPool = Executors.newFixedThreadPool(96)
    private val scanPool = Executors.newFixedThreadPool(8)
    private val COVER_TIMEOUT_MS = 3500
    private val API_HOST = "api-watchoffline.luzardo-thomas.workers.dev"
    private val videoExt = hashSetOf("mp4", "mkv", "avi", "webm", "mov", "flv", "mpg", "mpeg", "m4v", "ts", "3gp", "wmv")

    // Regex
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

    init {
        System.setProperty("http.keepAlive", "true")
        System.setProperty("http.maxConnections", "100")
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
    // Core Logic
    // =========================

    fun run(toast: (String) -> Unit, onDone: (Int) -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                val startTime = System.nanoTime()
                toast("Importando de SMBs")
                val proxyOk = smbGateway.ensureProxyStarted(proxyPort)
                if (!proxyOk) { onError("Error Proxy SMB."); return@Thread }

                val serverIds = smbGateway.listCachedServerIds()
                if (serverIds.isEmpty()) { onError("Sin servidores."); return@Thread }

                val allRawItems = CopyOnWriteArrayList<RawItem>()
                val scanFutures = ArrayList<java.util.concurrent.Future<*>>()

                for (serverId in serverIds) {
                    scanFutures.add(scanPool.submit {
                        val share = smbGateway.getLastShare(serverId)
                        if (!share.isNullOrBlank()) processServer(serverId, share, allRawItems)
                    })
                }
                for (f in scanFutures) try { f.get() } catch (_: Exception) {}

                if (allRawItems.isEmpty()) { onDone(0); return@Thread }

                // Procesamiento de Playlist
                val uniqueItems = allRawItems.distinctBy { it.videoSrc }
                val seriesMap = HashMap<Pair<String, Int>, ArrayList<RawItem>>()
                val movies = ArrayList<RawItem>()

                for (ri in uniqueItems) {
                    val parts = splitPathParts(ri.smbPath)
                    var addedToSeries = false
                    if (parts.size >= 3) {
                        val series = parts[parts.size - 3]
                        val season = parseSeasonFromFolderOrName(parts[parts.size - 2])
                        if (season != null) {
                            seriesMap.getOrPut(series to season) { ArrayList() }.add(ri)
                            addedToSeries = true
                        }
                    }
                    if (!addedToSeries) movies.add(ri)
                }

                val previews = ArrayList<PreviewJson>()
                for ((key, list) in seriesMap) {
                    list.sortWith(compareBy({ parseEpisodeForSort(it.smbPath) ?: Int.MAX_VALUE }, { it.smbPath }))
                    previews.add(PreviewJson("${normalizeName(key.first)}_s${pad2(key.second)}.json",
                        list.map { VideoItem(it.title, it.skip, it.delay, it.img, it.img, it.videoSrc) }, "SERIES"))
                }

                val sagaMap = HashMap<String, ArrayList<RawItem>>()
                for (m in movies) sagaMap.getOrPut(inferSagaNameFromPath(m.smbPath)) { ArrayList() }.add(m)
                for ((saga, list) in sagaMap) {
                    list.sortWith(compareBy({ extractMovieSortKey(it.smbPath, it.title) }, { it.title }))
                    val fName = if (list.size > 1) "saga_${normalizeName(saga).replace(" ", "_")}.json" else "${normalizeName(list.first().title).replace(" ", "_")}.json"
                    previews.add(PreviewJson(fName, list.map { VideoItem(it.title, it.skip, it.delay, it.img, it.img, it.videoSrc) }, "MOVIES"))
                }

                val toImport = filterAlreadyImported(previews)
                val existing = jsonDataManager.getImportedJsons().map { it.fileName }.toHashSet()
                var count = 0
                for (p in toImport) {
                    val safeName = uniqueJsonNameFast(p.fileName, existing)
                    jsonDataManager.addJson(context, safeName, p.videos)
                    existing.add(safeName)
                    count++
                }

                val ms = (System.nanoTime() - startTime) / 1_000_000
                toast("Importado en ${ms/1000.0}s")
                onDone(count)
            } catch (e: Exception) { onError(e.message ?: "Error desconocido") }
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

            // 🔍 LOG DE DEPURACIÓN CRÍTICO
            if (cover != null) {
                Log.d("DEBUG_DATA", "--- Analizando Cover ---")
                Log.d("DEBUG_DATA", "Path: $p")
                Log.d("DEBUG_DATA", "Query usada: $q")
                Log.d("DEBUG_DATA", "API skipSeconds: ${cover.skipToSecond}")
                Log.d("DEBUG_DATA", "API delaySeconds: ${cover.delaySkip}")
                Log.d("DEBUG_DATA", "Tipo detectado por API: ${cover.type}")
            } else {
                Log.w("DEBUG_DATA", "⚠️ Sin respuesta de API para query: $q")
            }

            // Aquí es donde se asignan los valores a RawItem
            // Verifica esta condición: si 'isSeries' es false, el skip/delay se fuerza a 0
            val finalSkip = if (isSeries) (cover?.skipToSecond ?: 0) else 0
            val finalDelay = if (isSeries) (cover?.delaySkip ?: 0) else 0

            Log.d("DEBUG_DATA", "Valores Finales -> isSeries: $isSeries | Skip: $finalSkip | Delay: $finalDelay")

            destList.add(RawItem(serverId, share, p, title, img,
                finalSkip,
                finalDelay,
                videoUrl))
        }
    }

    private fun buildQueryForPathSmart(path: String): String {
        val clean = path.replace("\\", "/").trim('/')
        val parts = clean.split("/").filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""

        val fileName = parts.last()
        val fileNoExt = fileName.substringBeforeLast(".")
        val se = parseSeasonEpisodeFromFilename(path)
        val epOnly = parseEpisodeOnlyFromFilename(path)

        // 1. REGLA ANIME / EPISODIOS CONTINUOS
        // Solo usamos la carpeta abuela si la ruta es profunda (mínimo 3 niveles)
        // Ejemplo: /Animes/Evangelion/01.mkv -> parts.size es 3
        if (parts.size >= 3 && epOnly != null) {
            val seriesFolderName = parts[parts.size - 3]

            // Evitamos que use "0" o "emulated" chequeando que no sea solo un número
            if (seriesFolderName.length > 1) {
                val seriesKey = normalizeName(seriesFolderName).replace(" ", "_")
                return "${seriesKey}_$epOnly"
            }
        }

        // 2. REGLA SERIES (4x06, S01E01)
        if (se != null) {
            // Si no hay profundidad, usamos la primera parte de la ruta o el nombre del archivo
            val seriesName = if (parts.size >= 3) parts[parts.size - 3]
            else if (parts.size >= 2) parts[parts.size - 2]
            else fileNoExt.replace(Regex("""\d+.*"""), "").trim()

            return "${normalizeName(seriesName)} ${se.first}x${pad2(se.second)}"
        }

        // 3. FALLBACK PELÍCULAS
        // Aquí es donde unimos palabras si quieres evitar espacios en la búsqueda final
        return normalizeName(fileNoExt)
    }

    private fun fetchCoversParallel(queries: List<String>): Map<String, ApiCover?> {
        val results = ConcurrentHashMap<String, ApiCover?>()
        val toAsk = queries.filter { !coverCache.containsKey(it) }
        val futures = ArrayList<java.util.concurrent.Future<*>>()

        for (q in queries) { if (coverCache.containsKey(q)) results[q] = coverCache[q] }

        for (q in toAsk) {
            futures.add(coverPool.submit {
                val cover = fetchCoverFromApi(q)
                coverCache[q] = cover
                results[q] = cover
            })
        }
        for (f in futures) try { f.get() } catch (_: Exception) {}
        return results
    }

    private fun fetchCoverFromApi(q: String): ApiCover? {
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val conn = openConnectionForUrl("https://$API_HOST/cover?q=$encoded")
        return try {
            conn.connectTimeout = COVER_TIMEOUT_MS
            conn.readTimeout = COVER_TIMEOUT_MS
            if (conn.responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line)
                reader.close()
                gson.fromJson(sb.toString(), ApiCover::class.java)
            } else null
        } catch (e: Exception) { null }
    }

    // Helpers de soporte
    private fun listSmbVideos(serverId: String, shareName: String): List<String> {
        val out = ArrayList<String>()
        smbGateway.withDiskShare(serverId, shareName) { share ->
            val stack = ArrayDeque<String>(); stack.addLast("")
            while (stack.isNotEmpty()) {
                val cleanDir = stack.removeLast().trim('\\', '/')
                if (cleanDir.lowercase().contains("system volume")) continue
                try {
                    val list = if (cleanDir.isBlank()) share.list("") else share.list(cleanDir)
                    for (f in list) {
                        val name = f.fileName
                        if (name == "." || name == "..") continue
                        if ((f.fileAttributes.toLong() and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value.toLong()) != 0L) {
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
        val shareEnc = URLEncoder.encode(share, "UTF-8").replace("+", "%20")
        val pathEnc = smbPath.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "http://127.0.0.1:$proxyPort/smb/$serverId/$shareEnc/$pathEnc"
    }

    private fun pad2(n: Int) = n.toString().padStart(2, '0')
    private fun pad3(n: Int) = n.toString().padStart(3, '0')
    private fun normalizeName(s: String) = s.trim().replace('_', ' ').replace(Regex("""\s+"""), " ").lowercase()
    private fun splitPathParts(path: String) = path.replace("\\", "/").trim('/').split("/").filter { it.isNotBlank() }
    private fun fileBaseName(path: String) = path.substringAfterLast("/").substringBeforeLast(".")
    private fun resolveCoverUrl(apiUrl: String?) = apiUrl?.trim() ?: ""

    private fun parseSeasonFromFolderOrName(name: String): Int? {
        val n = name.lowercase().replace("_", " ").trim()
        reSeason1.find(n)?.let { return it.groupValues[1].toInt() }
        reSeason2.find(n)?.let { return it.groupValues[1].toInt() }
        reSeason3.find(n)?.let { return it.groupValues[1].toInt() }
        reSeason4.find(n)?.let { return it.groupValues[1].toInt() }
        return if (n.matches(Regex("""\d{1,2}"""))) n.toInt() else null
    }

    private fun parseSeasonEpisodeFromFilename(path: String): Pair<Int, Int>? {
        val name = fileBaseName(path)
        reSE1.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        reSE2.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        return null
    }

    private fun parseEpisodeOnlyFromFilename(path: String): Int? {
        val name = fileBaseName(path)
        reEpWords.findAll(name).lastOrNull()?.let { return it.groupValues[1].toInt() }
        reEpTail.find(name)?.let { return it.groupValues[1].toInt() }
        return null
    }

    private fun parseEpisodeForSort(path: String) = parseSeasonEpisodeFromFilename(path)?.second ?: parseEpisodeOnlyFromFilename(path)

    private fun inferSagaNameFromPath(path: String): String {
        val parts = splitPathParts(path)
        if (parts.size < 2) return "Películas"
        val parent = parts[parts.size - 2]
        val looksLikePart = reSagaLooksLikePart.matches(normalizeName(parent)) || parent.lowercase().contains("part")
        return if (looksLikePart && parts.size >= 3) parts[parts.size - 3] else parent
    }

    private fun extractMovieSortKey(path: String, title: String): Int {
        val name = fileBaseName(path)
        reMovieKey1.find(name)?.let { return it.groupValues[1].toInt() }
        reMovieKey2.find(name)?.let { return it.groupValues[1].toInt() }
        reMovieKey3.find(name)?.let { return it.groupValues[2].toInt() }
        return Int.MAX_VALUE
    }

    private fun prettyCap(ep: Int?): String? =
        if (ep == null || ep <= 0) null else "Cap ${pad2(ep)}"

    private fun prettyTemp(season: Int?): String? =
        if (season == null || season <= 0) null else "T${pad2(season)}"

    private fun buildDisplayTitleForItem(absPath: String, cover: ApiCover?): String {
        // 1. Nombre base del archivo como último recurso
        val fallbackName = fileBaseName(absPath)

        // 2. Prioridad al ID de la API (si existe), sino el nombre del archivo
        val seriesName = cover?.id ?: fallbackName

        // 3. Extracción de metadatos del archivo
        val seFile = parseSeasonEpisodeFromFilename(absPath)
        val epFileOnly = parseEpisodeOnlyFromFilename(absPath)

        // 4. Consolidación de datos (Archivo > API)
        val seasonFromFile = seFile?.first
        val epFromFile = seFile?.second ?: epFileOnly
        val epFinal = epFromFile ?: cover?.episode

        // 5. Verificación de si es una serie (Flag API o existencia de caps/temporadas)
        val isSeries = cover?.type.equals("series", ignoreCase = true) ||
                (seasonFromFile != null) || (epFromFile != null) ||
                (cover?.season != null) || (cover?.episode != null)

        // Si no es serie (es película o especial único), devolvemos solo el nombre
        if (!isSeries) return seriesName

        // 6. Formateo de las etiquetas
        val cap = prettyCap(epFinal)
        val temp = prettyTemp(seasonFromFile)

        // 7. Construcción de la cadena final (Uso de StringBuilder interno por eficiencia)
        return when {
            temp != null && cap != null -> "$temp $cap - $seriesName"
            cap != null -> "$cap - $seriesName"
            else -> seriesName
        }
    }

    private fun filterAlreadyImported(previews: List<PreviewJson>) =
        previews.filter { it.fileName !in jsonDataManager.getImportedJsons().map { j -> j.fileName }.toHashSet() }

    private fun uniqueJsonNameFast(base: String, existing: MutableSet<String>): String {
        if (!existing.contains(base)) return base
        val prefix = base.removeSuffix(".json")
        var i = 2
        while (true) {
            val cand = "${prefix}_$i.json"; if (!existing.contains(cand)) return cand; i++
        }
    }
}

