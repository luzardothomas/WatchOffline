package com.example.watchoffline

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Auto-import desde el contenido LOCAL del dispositivo,
 * usando el BackgroundServer (http://127.0.0.1:<serverPort>) para reproducir.
 *
 * Reutiliza la misma lógica "series vs movies" + cover API.
 */
class LocalAutoImporter(
    private val context: Context,
    private val jsonDataManager: JsonDataManager,
    private val serverPort: Int = 8080,
    rootDirs: List<File>? = null
) {

    private val tag = "LocalAutoImporter"

    // ✅ Roots reales, dinámicos (celular + TV box)
    private val rootDirs: List<File> = (rootDirs ?: pickReadableRoots()).distinctBy { it.path }

    // =========================
    // MODELOS INTERNOS
    // =========================

    private data class RawItem(
        val path: String,     // path ABSOLUTO (ej /storage/emulated/0/Movies/a.mp4)
        val title: String,
        val img: String,
        val skip: Int,
        val videoSrc: String  // URL a tu server local
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

    // =========================
    // API
    // =========================

    fun run(
        toast: (String) -> Unit,
        onDone: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                toast("Importando desde DISPOSITIVO…")

                // ✅ diagnóstico: roots usados
                val rootsMsg = rootDirs.joinToString { r ->
                    "${r.path}(read=${r.canRead()})"
                }
                Log.d(tag, "Roots: $rootsMsg")
                toast("Roots: " + rootDirs.joinToString { it.path })

                // 1) Buscar videos (dedupe por path)
                val filesSet = linkedSetOf<String>()
                val perRootCounts = mutableListOf<String>()

                for (dir in rootDirs) {
                    val found = listLocalVideos(dir)
                    perRootCounts.add("${dir.path}: ${found.size}")
                    found.forEach { filesSet.add(it) }
                }

                Log.d(tag, "Scan counts: ${perRootCounts.joinToString(" | ")}")
                toast("Encontrados: ${filesSet.size} videos")

                if (filesSet.isEmpty()) {
                    onError("No se encontraron videos en el dispositivo (roots: ${rootDirs.joinToString { it.path }})")
                    return@Thread
                }

                // ✅ placeholder local SIEMPRE (evita cards/Details bugueados si cover.url viene vacío)
                val placeholder = fallbackImageUri()

                // 2) Items crudos
                val rawItems = mutableListOf<RawItem>()
                for (absPath in filesSet) {
                    val q = buildQueryForPathSmart(absPath)
                    val cover = fetchCoverFromApiCached(q)

                    val imgUrl = cover?.url?.trim().orEmpty().ifBlank { placeholder }

                    rawItems.add(
                        RawItem(
                            path = absPath,
                            title = cover?.id ?: absPath.substringAfterLast("/").substringBeforeLast("."),
                            img = imgUrl,
                            skip = cover?.skipToSecond ?: 0,
                            // ✅ BackgroundServer actual decodifica session.uri y acepta paths absolutos
                            videoSrc = "http://127.0.0.1:$serverPort${encodePathForUrl(absPath)}"
                        )
                    )
                }

                // ================= SERIES vs MOVIES =================
                val seriesMap = linkedMapOf<Pair<String, Int>, MutableList<RawItem>>()
                val movies = mutableListOf<RawItem>()

                for (ri in rawItems) {
                    val parts = ri.path.replace("\\", "/")
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
                            { parseSeasonEpisode(it.path)?.second ?: Int.MAX_VALUE },
                            { it.path.lowercase() }
                        )
                    ).map { r ->
                        // ✅ imgBig/imgSml NO vacíos (siempre placeholder si falta)
                        VideoItem(r.title, r.skip, r.img, r.img, r.videoSrc)
                    }

                    previews.add(
                        PreviewJson(
                            fileName = "${normalizeName(series)}_s${pad2(season)}.json",
                            videos = videos,
                            debug = "LOCAL SERIES $series S$season"
                        )
                    )
                }

                // Movies → agrupar por saga (carpeta padre)
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
                    ).map { r ->
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
                            debug = "LOCAL MOVIES"
                        )
                    )
                }

                // 🚫 Evitar duplicados
                val toImport = filterAlreadyImported(previews)
                if (toImport.isEmpty()) {
                    toast("Todo ya estaba importado")
                    onDone(0)
                    return@Thread
                }

                // Importar
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
    // PLACEHOLDER local
    // =========================
    private fun fallbackImageUri(): String {
        // ⚠️ Cambiá R.drawable.movie si tu recurso se llama distinto
        return "android.resource://${context.packageName}/${R.drawable.movie}"
    }

    // =========================
    // ROOTS (dinámico)
    // =========================

    private fun pickReadableRoots(): List<File> {
        val baseCandidates = listOf(
            File("/storage/self/primary"),
            File("/storage/emulated/0"),
            File("/sdcard")
        )

        val extra = mutableListOf<File>()

        // Intentar descubrir montajes USB/SD
        try {
            File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory) extra.add(f)
            }
        } catch (_: Exception) { /* ignore */ }

        try {
            File("/mnt/media_rw").listFiles()?.forEach { f ->
                if (f.isDirectory) extra.add(f)
            }
        } catch (_: Exception) { /* ignore */ }

        val all = (baseCandidates + extra).distinctBy { it.path }
        val good = all.filter { it.exists() && it.isDirectory && it.canRead() }

        return if (good.isNotEmpty()) good else listOf(File("/storage"))
    }

    // =========================
    // LISTAR VIDEOS LOCALES
    // =========================

    private fun listLocalVideos(root: File): List<String> {
        val out = mutableListOf<String>()
        if (!root.exists() || !root.isDirectory || !root.canRead()) {
            Log.w(tag, "ROOT SKIP: ${root.path} exists=${root.exists()} dir=${root.isDirectory} canRead=${root.canRead()}")
            return out
        }

        val videoExt = setOf("mp4", "mkv", "avi", "webm", "mov", "flv", "mpg", "mpeg", "m4v", "ts", "3gp", "wmv")

        fun shouldSkipDir(dir: File): Boolean {
            val p = dir.absolutePath.replace("\\", "/")
            return p.contains("/Android/data/") || p.contains("/Android/obb/")
        }

        fun walk(dir: File) {
            if (shouldSkipDir(dir)) return

            val children = try { dir.listFiles() } catch (_: Exception) { null }
            if (children == null) {
                Log.w(tag, "CANNOT LIST: ${dir.absolutePath}")
                return
            }

            for (f in children) {
                if (f.isDirectory) {
                    walk(f)
                } else {
                    val ext = f.name.substringAfterLast(".", "").lowercase()
                    if (ext in videoExt) {
                        out.add(f.absolutePath.replace("\\", "/"))
                    }
                }
            }
        }

        Log.d(tag, "SCAN ROOT: ${root.absolutePath}")
        walk(root)
        Log.d(tag, "FOUND IN ROOT ${root.path}: ${out.size}")
        return out
    }

    /**
     * Convierte "/storage/emulated/0/Movies/a b.mp4"
     * -> "/storage/emulated/0/Movies/a%20b.mp4"
     */
    private fun encodePathForUrl(absPath: String): String {
        val clean = absPath.replace("\\", "/")
        val parts = clean.split("/").map { seg ->
            if (seg.isBlank()) "" else URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        val rebuilt = parts.joinToString("/")
        return if (rebuilt.startsWith("/")) rebuilt else "/$rebuilt"
    }

    // =========================
    // Cover API + cache
    // =========================

    private val coverCache = mutableMapOf<String, ApiCover?>()

    private fun fetchCoverFromApiCached(q: String): ApiCover? {
        if (coverCache.containsKey(q)) return coverCache[q]
        val cover = try { fetchCoverFromApi(q) } catch (_: Exception) { null }
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

    // =========================
    // HELPERS (igual que SMB)
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
