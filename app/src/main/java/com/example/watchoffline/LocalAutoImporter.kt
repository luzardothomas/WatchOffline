package com.example.watchoffline

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

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

class LocalAutoImporter(
    private val context: Context,
    private val jsonDataManager: JsonDataManager,
    private val serverPort: Int = 8080,
    rootDirs: List<File>? = null
) {
    private val tag = "LocalAutoImporter"

    private val rootDirs: List<File> = (rootDirs ?: pickReadableRoots()).distinctBy { it.path }

    private data class RawItem(
        val path: String, val title: String, val img: String,
        val skip: Int, val delay: Int, val videoSrc: String
    )

    private data class PreviewJson(val fileName: String, val videos: List<VideoItem>, val debug: String)

    private val gson = Gson()
    private val coverCache = ConcurrentHashMap<String, ApiCover?>()

    private val apiPool = Executors.newFixedThreadPool(96)
    private val diskPool = Executors.newFixedThreadPool(4)

    private val COVER_TIMEOUT_MS = 3500
    private val videoExt = hashSetOf("mp4", "mkv", "avi", "webm", "mov", "flv", "mpg", "mpeg", "m4v", "ts", "3gp", "wmv")

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

    fun run(toast: (String) -> Unit, onDone: (Int) -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                val startTime = System.nanoTime()
                toast("Importando de forma LOCAL")

                val foundFiles = CopyOnWriteArrayList<String>()
                val scanFutures = ArrayList<java.util.concurrent.Future<*>>()

                for (dir in rootDirs) {
                    scanFutures.add(diskPool.submit {
                        val files = listLocalVideos(dir)
                        if (files.isNotEmpty()) foundFiles.addAll(files)
                    })
                }
                for (f in scanFutures) try { f.get() } catch (_: Exception) {}

                val uniquePaths = foundFiles.map { normalizeAbsPath(it) }.toHashSet().toList()

                if (uniquePaths.isEmpty()) {
                    onError("No se encontraron videos.")
                    return@Thread
                }

                val queriesToFetch = HashSet<String>()
                val pathMap = HashMap<String, String>(uniquePaths.size)

                for (path in uniquePaths) {
                    val q = buildQueryForPathSmart(path)
                    pathMap[path] = q
                    queriesToFetch.add(q)
                }

                val coverResults = fetchCoversParallel(queriesToFetch.toList())
                val placeholder = fallbackImageUri()
                val rawItems = ArrayList<RawItem>(uniquePaths.size)

                for (absPath in uniquePaths) {
                    val q = pathMap[absPath] ?: ""
                    val cover = coverResults[q]

                    val imgUrl = cover?.url?.trim().orEmpty().ifBlank { placeholder }
                    val displayTitle = buildDisplayTitleForItem(absPath, cover)

                    // ✅ Se eliminó la restricción 'isSeries'. Si la API devuelve skip, lo usamos.
                    val skipFinal = cover?.skipToSecond ?: 0
                    val delayFinal = cover?.delaySkip ?: 0

                    rawItems.add(RawItem(
                        path = absPath,
                        title = displayTitle,
                        img = imgUrl,
                        skip = skipFinal,
                        delay = delayFinal,
                        videoSrc = "http://127.0.0.1:$serverPort${encodePathForUrl(absPath)}"
                    ))
                }

                val uniqueBySrc = LinkedHashMap<String, RawItem>(rawItems.size)
                for (ri in rawItems) uniqueBySrc.putIfAbsent(ri.videoSrc, ri)
                val rawItemsUnique = uniqueBySrc.values.toList()

                val seriesMap = HashMap<Pair<String, Int>, ArrayList<RawItem>>()
                val movies = ArrayList<RawItem>()

                for (ri in rawItemsUnique) {
                    val parts = ri.path.replace("\\", "/").split("/").filter { it.isNotBlank() }
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
                val seriesEntries = seriesMap.entries.toList().sortedWith(compareBy({ normalizeName(it.key.first) }, { it.key.second }))
                for ((key, items) in seriesEntries) {
                    items.sortWith(compareBy({ parseEpisodeForSort(it.path) ?: Int.MAX_VALUE }, { it.path.lowercase() }))
                    val videos = items.map { VideoItem(it.title, it.skip, it.delay, it.img, it.img, it.videoSrc) }
                    previews.add(PreviewJson("${normalizeName(key.first)}_s${pad2(key.second)}.json", videos, "LOCAL SERIES"))
                }

                val sagaMap = HashMap<String, ArrayList<RawItem>>()
                for (m in movies) sagaMap.getOrPut(inferSagaNameFromPath(m.path)) { ArrayList() }.add(m)

                val sagaEntries = sagaMap.entries.toList().sortedWith(compareBy { normalizeName(it.key) })
                for ((saga, items) in sagaEntries) {
                    items.sortWith(compareBy({ extractMovieSortKey(it.path, it.title) }, { normalizeName(it.title) }))
                    val videos = items.map { VideoItem(it.title, it.skip, it.delay, it.img, it.img, it.videoSrc) }
                    val fName = if (items.size > 1) "saga_${normalizeName(saga).replace(" ", "_")}.json"
                    else "${normalizeName(items.first().title).replace(" ", "_")}.json"
                    previews.add(PreviewJson(fName, videos, "LOCAL MOVIES"))
                }

                val toImport = filterAlreadyImported(previews)
                val existingNames = jsonDataManager.getImportedJsons().map { it.fileName }.toHashSet()
                var imported = 0
                for (p in toImport) {
                    val safeName = uniqueJsonName(p.fileName, existingNames)
                    jsonDataManager.addJson(context, safeName, p.videos)
                    existingNames.add(safeName)
                    imported++
                }

                val ms = (System.nanoTime() - startTime) / 1_000_000
                toast("JSONs: $imported\nVIDEOS: ${uniquePaths.size}")
                onDone(imported)

            } catch (e: Exception) {
                onError("Error: ${e.message}")
            }
        }.start()
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
            futures.add(apiPool.submit {
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
        val full = "https://api-watchoffline.luzardo-thomas.workers.dev/cover?q=$encoded"
        return try {
            val conn = URL(full).openConnection() as HttpURLConnection
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

    private fun pickReadableRoots(): List<File> {
        val candidates = mutableListOf(File("/storage/self/primary"), File("/storage/emulated/0"), File("/sdcard"))
        try { File("/storage").listFiles()?.filter { it.isDirectory }?.let { candidates.addAll(it) } } catch (_: Exception) {}
        return candidates.distinctBy { it.path }.filter { it.exists() && it.canRead() }.ifEmpty { listOf(File("/storage")) }
    }

    private fun listLocalVideos(root: File): List<String> {
        val out = ArrayList<String>()
        if (!root.exists() || !root.canRead()) return out
        val stack = java.util.ArrayDeque<File>()
        stack.push(root)
        while (stack.isNotEmpty()) {
            val dir = stack.pop()
            val p = dir.path
            if (p.contains("/Android/data") || p.contains("/Android/obb") || p.contains("/.") || p.contains("cache")) continue
            val children = try { dir.listFiles() } catch (_: Exception) { null } ?: continue
            for (f in children) {
                if (f.isDirectory) stack.push(f)
                else {
                    val dot = f.name.lastIndexOf('.')
                    if (dot > 0 && videoExt.contains(f.name.substring(dot + 1).lowercase())) out.add(f.absolutePath)
                }
            }
        }
        return out
    }

    private fun encodePathForUrl(absPath: String): String {
        val sb = StringBuilder()
        val parts = absPath.replace("\\", "/").split("/")
        for (seg in parts) {
            if (seg.isNotBlank()) sb.append("/").append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"))
        }
        return sb.toString()
    }

    private fun fallbackImageUri() = "android.resource://${context.packageName}/${R.drawable.movie}"
    private fun normalizeAbsPath(p: String): String = try { File(p).canonicalPath.replace("\\", "/") } catch (_: Exception) { p.replace("\\", "/") }
    private fun pad2(n: Int) = n.toString().padStart(2, '0')
    private fun pad3(n: Int) = n.toString().padStart(3, '0')
    private fun normalizeName(s: String) = s.trim().replace('_', ' ').replace(Regex("""\s+"""), " ").lowercase()

    private fun buildQueryForPathSmart(path: String): String {
        val clean = path.replace("\\", "/").trim('/')
        val parts = clean.split("/").filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""

        val fileNoExt = parts.last().substringBeforeLast(".")
        val se = parseSeasonEpisodeFromFilename(path) // Detecta 1x02, S01E02
        val epOnly = parseEpisodeOnlyFromFilename(path) // Detecta "01" al final o "Cap 01"

        // 1. REGLA DE ORO PARA ANIMES (Carpeta Abuela + Episodio)
        // Si tenemos al menos 3 partes (Serie/Temporada/Archivo) y detectamos un episodio
        if (parts.size >= 3) {
            val seriesName = parts[parts.size - 3]
            val ep = se?.second ?: epOnly

            if (ep != null) {
                val seriesKey = normalizeName(seriesName).replace(" ", "_")
                // Retorna nombreserie_001
                return "${seriesKey}_${pad3(ep)}"
            }
        }

        // 2. REGLA PARA SERIES OCCIDENTALES (Si tiene Temporada explícita)
        if (se != null && parts.size >= 2) {
            val seriesName = if (parts.size >= 3) parts[parts.size - 3] else parts[0]
            return "${normalizeName(seriesName)} s${pad2(se.first)} ${pad2(se.second)}"
        }

        // 3. FALLBACK: Nombre del archivo normalizado
        return normalizeName(fileNoExt)
    }

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
        val clean = path.replace("\\", "/").trim('/')
        val parts = clean.split("/").filter { it.isNotBlank() }
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

    private fun uniqueJsonName(base: String, existing: MutableSet<String>): String {
        if (!existing.contains(base)) return base
        val prefix = base.removeSuffix(".json")
        var i = 2
        while (true) {
            val cand = "${prefix}_$i.json"
            if (!existing.contains(cand)) return cand
            i++
        }
    }

    private fun filterAlreadyImported(previews: List<PreviewJson>) =
        previews.filter { it.fileName !in jsonDataManager.getImportedJsons().map { it.fileName }.toSet() }

    private fun fileBaseName(path: String) = path.substringAfterLast("/").substringBeforeLast(".")

    private fun buildDisplayTitleForItem(absPath: String, cover: ApiCover?): String {
        val fallback = fileBaseName(absPath)
        val se = parseSeasonEpisodeFromFilename(absPath)
        val ep = se?.second ?: parseEpisodeOnlyFromFilename(absPath) ?: cover?.episode
        val season = se?.first ?: cover?.season
        val isSeries = season != null || ep != null || cover?.type?.lowercase() == "series"
        if (!isSeries) return cover?.id ?: fallback
        val capStr = if (ep != null) "Cap ${pad2(ep)}" else ""
        val tempStr = if (season != null) "T${pad2(season)}" else ""
        return "$tempStr $capStr - ${cover?.id ?: fallback}".trim()
    }
}