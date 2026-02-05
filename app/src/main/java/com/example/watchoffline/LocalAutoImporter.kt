package com.example.watchoffline

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.storage.StorageManager
import android.util.Log
import android.view.Gravity
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
        val skip: Int, val delay: Int, val videoSrc: String,
        val groupSeries: String? = null,
        val groupSeason: Int? = null
    )

    private data class PreviewJson(val fileName: String, val videos: List<VideoItem>, val debug: String)

    private val gson = Gson()
    private val coverCache = ConcurrentHashMap<String, ApiCover?>()

    private val apiPool = Executors.newFixedThreadPool(96)
    private val diskPool = Executors.newFixedThreadPool(24)

    private val COVER_TIMEOUT_MS = 3500
    private val videoExt = hashSetOf("mp4", "mkv", "avi", "webm", "mov", "flv", "mpg", "mpeg", "m4v", "ts", "3gp", "wmv")

    private val reSeason1 = Regex("""(?i)temporada\s*(\d{1,2})""")
    private val reSeason2 = Regex("""(?i)\btemp\s*(\d{1,2})""")
    private val reSeason3 = Regex("""(?i)\bseason\s*(\d{1,2})""")
    private val reSeason4 = Regex("""(?i)\bs(\d{1,2})\b""")
    private val reSE1 = Regex("""(?i)\bs(\d{1,2})\s*[._\- ]*\s*e(\d{1,3})\b""")
    private val reSE2 = Regex("""(?i)\b(\d{1,2})\s*x\s*(\d{1,3})\b""")
    private val reSE_Underscore = Regex("""(?i)\b[st](\d{1,2})_e(\d{1,3})\b""")
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

        val activity = context as? Activity ?: return // Si no hay activity, salimos

        // Usamos un array de 1 elemento como contenedor seguro para la variable entre hilos
        val dialogRef = arrayOf<Dialog?>(null)

        // 1. MOSTRAR DIÁLOGO (HILO PRINCIPAL)
        activity.runOnUiThread {
            // Diseño visual (Caja gris oscura)
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(50, 50, 50, 50)
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#303030"))
            }

            layout.addView(ProgressBar(activity).apply { isIndeterminate = true })
            layout.addView(TextView(activity).apply {
                text = "Importando de forma LOCAL..."
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(40, 0, 0, 0)
            })

            // Crear y mostrar el diálogo "crudo"
            val rawDialog = Dialog(activity)
            rawDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            rawDialog.setContentView(layout)
            rawDialog.setCancelable(false) // Bloquea toques y botón atrás
            rawDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            try {
                rawDialog.show()
                // Guardamos la referencia para que el otro hilo la pueda cerrar
                dialogRef[0] = rawDialog
            } catch (e: Exception) {
                // Ignorar si falla al mostrar
            }
        }

        Thread {
            try {
                val startTime = System.nanoTime()

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

                    // ==========================================================
                    // 1. CÁLCULO CENTRALIZADO (Movido arriba para evitar duplicados)
                    // ==========================================================
                    val parts = splitPathParts(absPath)

                    // Detectar Temporada (Carpeta > Archivo > API)
                    val seasonFromFolder = if (parts.size >= 2) parseSeasonFromFolderOrName(parts[parts.size - 2]) else null
                    val seFile = parseSeasonEpisodeFromFilename(absPath)
                    val finalSeason = seasonFromFolder ?: seFile?.first ?: cover?.season

                    // Decidir si es Serie (Esta es la variable ÚNICA que usaremos)
                    val isSeries = cover?.type.equals("series", ignoreCase = true) || finalSeason != null
                    // ==========================================================

                    if (cover != null) {
                        Log.d("DEBUG_DATA_LOCAL", "--- Analizando Archivo Local ---")
                        Log.d("DEBUG_DATA_LOCAL", "Path: $absPath")
                        Log.d("DEBUG_DATA_LOCAL", "Query usada: $q")
                        Log.d("DEBUG_DATA_LOCAL", "Tipo detectado (API): ${cover.type}")
                    } else {
                        Log.w("DEBUG_DATA_LOCAL", "⚠️ Sin respuesta de API para archivo: ${absPath.substringAfterLast("/")}")
                    }

                    // Asignación de valores finales (Usando la variable isSeries ya calculada)
                    val finalSkip = if (isSeries) (cover?.skipToSecond ?: 0) else 0
                    val finalDelay = if (isSeries) (cover?.delaySkip ?: 0) else 0

                    Log.d("DEBUG_DATA_LOCAL", "Resultado Final -> isSeries: $isSeries | Skip: $finalSkip | Delay: $finalDelay")

                    // === LÓGICA PARA EL NOMBRE DEL GRUPO (JSON) ===
                    val seriesNameForGroup = if (isSeries) {
                        // Si viene de una carpeta "Temporada X", la serie es la carpeta padre
                        if (seasonFromFolder != null && parts.size >= 3) {
                            parts[parts.size - 3]
                        } else {
                            // Si no, usamos el ID de la API o el nombre limpio del archivo
                            cover?.id ?: fileBaseName(absPath).replace(Regex("""\d+.*"""), "").trim()
                        }
                    } else null

                    val seasonForGroup = if (isSeries) (finalSeason ?: 1) else null
                    // ============================================

                    rawItems.add(RawItem(
                        path = absPath,
                        title = displayTitle,
                        img = imgUrl,
                        skip = finalSkip,
                        delay = finalDelay,
                        videoSrc = "http://127.0.0.1:$serverPort${encodePathForUrl(absPath)}",
                        groupSeries = seriesNameForGroup,
                        groupSeason = seasonForGroup
                    ))
                }

                val uniqueBySrc = LinkedHashMap<String, RawItem>(rawItems.size)
                for (ri in rawItems) uniqueBySrc.putIfAbsent(ri.videoSrc, ri)
                val rawItemsUnique = uniqueBySrc.values.toList()

                val seriesMap = HashMap<Pair<String, Int>, ArrayList<RawItem>>()
                val movies = ArrayList<RawItem>()

                for (ri in rawItemsUnique) {
                    // Si tiene etiqueta de serie, VA A SERIES
                    if (ri.groupSeries != null && ri.groupSeason != null) {
                        seriesMap.getOrPut(ri.groupSeries to ri.groupSeason) { ArrayList() }.add(ri)
                    } else {
                        // Solo si no es serie, va a películas
                        movies.add(ri)
                    }
                }

                val previews = ArrayList<PreviewJson>()
                val seriesEntries = seriesMap.entries.toList().sortedWith(compareBy({ normalizeName(it.key.first) }, { it.key.second }))
                for ((key, items) in seriesEntries) {
                    items.sortWith(compareBy({ parseEpisodeForSort(it.path) ?: Int.MAX_VALUE }, { it.path.lowercase() }))
                    val videos = items.map { VideoItem(it.title, it.skip, it.delay, it.img, it.img, it.videoSrc) }
                    previews.add(PreviewJson("${normalizeName(key.first)}_s${pad2(key.second)}_local.json", videos, "LOCAL SERIES"))
                }

                val sagaMap = HashMap<String, ArrayList<RawItem>>()
                for (m in movies) sagaMap.getOrPut(inferSagaNameFromPath(m.path)) { ArrayList() }.add(m)

                val sagaEntries = sagaMap.entries.toList().sortedWith(compareBy { normalizeName(it.key) })
                for ((saga, items) in sagaEntries) {
                    items.sortWith(compareBy({ extractMovieSortKey(it.path, it.title) }, { normalizeName(it.title) }))
                    val videos = items.map { VideoItem(it.title, it.skip, it.delay, it.img, it.img, it.videoSrc) }
                    val fName = if (items.size > 1) "saga_${normalizeName(saga).replace(" ", "_")}_local.json"
                    else "${normalizeName(items.first().title).replace(" ", "_")}_local.json"
                    previews.add(PreviewJson(fName, videos, "LOCAL MOVIES"))
                }

                // 1. Leemos la "Base de Datos" UNA sola vez y la cargamos en RAM (HashSet)
                // Esto hace que buscar duplicados sea instantáneo (0ms)
                val existingNames = jsonDataManager.getImportedJsons()
                    .map { it.fileName }
                    .toHashSet()

                // 2. Filtramos en memoria: Si el nombre ya existe, lo descartamos.
                // Sin lecturas a disco adicionales.
                val toImport = previews.filter { it.fileName !in existingNames }
                var count = 0
                for (p in toImport) {
                    // Usamos uniqueJsonNameFast pasándole el Set que ya tenemos en RAM.
                    // Esto protege contra colisiones dentro del mismo lote nuevo.
                    val safeName = uniqueJsonName(p.fileName, existingNames)

                    jsonDataManager.addJson(context, safeName, p.videos)

                    // Actualizamos el Set en memoria para el siguiente del bucle
                    existingNames.add(safeName)
                    count++
                }

                if(count != 0) {
                    val ms = (System.nanoTime() - startTime) / 1_000_000
                    toast("JSONs: $count\nVIDEOS: ${uniquePaths.size}")
                    toast("Importado en ${ms/1000.0}s")
                }
                else {
                    toast("No se encontraron nuevos videos")
                }

                onDone(count)

            } catch (e: Exception) {
                onError("Error: ${e.message}")
            }
            finally {
                // 3. CERRAR EL DIÁLOGO (SIEMPRE)
                activity.runOnUiThread {
                    try {
                        // Cerramos sin preguntar, forzado
                        dialogRef[0]?.dismiss()
                    } catch (e: Exception) {
                        // Ignorar errores al cerrar (ej. activity destruida)
                    }
                }
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
        val candidates = ArrayList<File>()

        // 1. Siempre agregamos la interna oficial
        candidates.add(File("/storage/emulated/0"))

        Log.d(tag, "=== BUSCANDO VOLÚMENES (StorageManager) ===")

        // 2. Método API Android (StorageManager): La forma correcta en Android 11+
        try {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val volumes = storageManager.storageVolumes

            for (vol in volumes) {
                val path = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    vol.directory
                } else null

                if (path != null) {
                    val status = if (path.canRead()) "LEGIBLE" else "ILEGIBLE (Falta Permiso)"
                    Log.i(tag, " -> VOLUMEN SM: [${vol.mediaStoreVolumeName}] Ruta: ${path.absolutePath} | Estado: $status")

                    if (path.canRead()) {
                        candidates.add(path)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error usando StorageManager: ${e.message}")
        }

        // 3. Fallback manual: Si StorageManager falló o devolvió solo la interna
        // Intentamos listar /storage a la fuerza
        val manualRoot = File("/storage")
        if (manualRoot.exists() && manualRoot.canRead()) {
            manualRoot.listFiles()?.forEach {
                if (it.isDirectory && it.canRead() && !it.name.equals("emulated") && !it.name.equals("self")) {
                    Log.i(tag, " -> VOLUMEN MANUAL (/storage): ${it.absolutePath}")
                    candidates.add(it)
                }
            }
        }

        // 4. Buscar en /mnt/media_rw
        // A veces Android monta los USB aquí y son accesibles con MANAGE_EXTERNAL_STORAGE
        Log.d(tag, "=== INTENTANDO RUTAS ALTERNATIVAS (/mnt) ===")
        val mediaRw = File("/mnt/media_rw")
        if (mediaRw.exists() && mediaRw.canRead()) {
            mediaRw.listFiles()?.forEach {
                // Filtramos carpetas vacías o ilegibles
                if (it.isDirectory && it.canRead() && (it.listFiles()?.isNotEmpty() == true)) {
                    candidates.add(it)
                }
            }
        }

        // También probar /mnt/usb_storage
        val usbStorage = File("/mnt/usb_storage")
        if (usbStorage.exists() && usbStorage.canRead()) {
            candidates.add(usbStorage)
        }

        // 5. FILTRADO FINAL Y LOGUEO DE VERDAD
        // Usamos canonicalPath para evitar duplicados (ej: /sdcard vs /storage/emulated/0)
        val finalRoots = candidates
            .filter { it.exists() }
            .distinctBy {
                try { it.canonicalPath } catch(_:Exception) { it.absolutePath }
            }

        Log.w(tag, "=== LISTA DEFINITIVA DE ROOTS A ESCANEAR (${finalRoots.size}) ===")
        for (f in finalRoots) {
            Log.w(tag, " [CANDIDATO FINAL] -> ${f.absolutePath} (R:${f.canRead()} | W:${f.canWrite()})")
        }

        return finalRoots
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


    private fun buildQueryForPathSmart(path: String): String {
        val clean = path.replace("\\", "/").trim('/')
        val parts = clean.split("/").filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""

        val fileName = parts.last()
        val fileNoExt = fileName.substringBeforeLast(".")

        // 1. Detección de Temporada/Episodio en el nombre del archivo
        val se = parseSeasonEpisodeFromFilename(path)
        val epOnly = parseEpisodeOnlyFromFilename(path)

        // Regex para detectar si el nombre del archivo es SOLO metadata (ej: "t1_e1", "1x01", "s01e01")
        // Si coincide con esto, IGNORAMOS el nombre del archivo y miramos las carpetas.
        // Explicación: (s/t opcional) + (numeros) + (separador x/e/_) + (numeros)
        val isMetadataFile = Regex("""(?i)^(?:s|t|season|temp|temporada)?[\s_.-]*\d+[\s_.-]*(?:x|e|ep|_)[\s_.-]*\d+$""")
            .matches(fileNoExt)

        // Regex mejorado para carpetas (agrega "season", soporta guiones bajos, espacios, puntos)
        val seasonFolderRegex = Regex("""(?i)^(?:s|t|season|temp|temporada)[\s_.-]*(\d{1,4})$""")

        // ------------------------------------------------------------------------
        // PASO A: ¿El archivo tiene el nombre REAL de la serie? (ej: "Malcolm_1x01")
        // ------------------------------------------------------------------------
        if (!isMetadataFile && se != null) {
            // Quitamos la parte de S01E01 para ver qué queda
            val cleanName = fileNoExt.replace(Regex("""(?i)[\s._\-]*(?:s\d+e\d+|\d+x\d+|t\d+_e\d+).*"""), "").trim()

            // Si quedan letras (ej: "Malcolm"), usamos eso.
            if (cleanName.any { it.isLetter() }) {
                return "${normalizeName(cleanName)} ${se.first}x${pad2(se.second)}"
            }
        }

        // ------------------------------------------------------------------------
        // PASO B: ¿El archivo es tipo "nombre_global_1"? (ej: "di_maria_1")
        // ------------------------------------------------------------------------
        if (!isMetadataFile) {
            val nameWithSpaces = fileNoExt.replace(Regex("""[._\-]"""), " ").trim()
            // Si tiene letras y NO es metadata pura, asumimos que es nombre + numero global
            if (nameWithSpaces.any { it.isLetter() }) {
                return normalizeName(nameWithSpaces)
            }
        }

        // ------------------------------------------------------------------------
        // PASO C: El archivo es "Mudo" o "Metadata Pura" (01.mp4, t1_e1.mp4) -> MIRAR CARPETAS
        // ------------------------------------------------------------------------
        if (parts.size >= 2) {
            val parentFolder = parts[parts.size - 2]
            val match = seasonFolderRegex.find(normalizeName(parentFolder))

            if (match != null) {
                // ---> CASO 1: PADRE ES TEMPORADA (Malcolm/season_7/05.mp4 o Malcolm/t_1/t1_e1.mp4)
                val seasonNum = match.groupValues[1].toInt()

                // Intentamos sacar el episodio del nombre del archivo (si es t1_e1 saca 1, si es 5 saca 5)
                // Si parseSeasonEpisode falló antes, usamos el fallback numérico simple
                val finalEp = se?.second ?: epOnly ?: fileNoExt.replace(Regex("\\D+"), "").toIntOrNull() ?: 1

                if (parts.size >= 3) {
                    // La serie es el ABUELO
                    val grandParent = parts[parts.size - 3]
                    return "${normalizeName(grandParent)} s${pad2(seasonNum)}e${pad2(finalEp)}"
                }
            } else {
                // ---> CASO 2: PADRE ES LA SERIE (Di_Maria/01.mp4)
                val finalEp = epOnly ?: fileNoExt.replace(Regex("\\D+"), "").toIntOrNull() ?: 1
                return "${normalizeName(parentFolder)} $finalEp"
            }
        }

        // Fallback finalísimo
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
        reSE_Underscore.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        reSE2.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        return null
    }

    private fun parseEpisodeOnlyFromFilename(path: String): Int? {
        val name = fileBaseName(path)
        reEpWords.findAll(name).lastOrNull()?.let { return it.groupValues[1].toInt() }
        reEpTail.find(name)?.let { return it.groupValues[1].toInt() }
        return name.toIntOrNull()
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

        // ==============================================================
        // LÓGICA DE TEMPORADA MEJORADA
        // ==============================================================
        val seasonFromFile = seFile?.first

        // Buscamos en la carpeta (ej: Temporada_7)
        val parts = splitPathParts(absPath)
        val seasonFromFolder = if (parts.size >= 2) {
            parseSeasonFromFolderOrName(parts[parts.size - 2])
        } else null

        // Consolidación: Archivo > Carpeta > API
        val finalSeason = seasonFromFile ?: seasonFromFolder ?: cover?.season
        // ==============================================================

        val epFromFile = seFile?.second ?: epFileOnly
        val epFinal = epFromFile ?: cover?.episode

        // 5. Verificación de si es una serie
        // Usamos finalSeason en lugar de seasonFromFile
        val isSeries = cover?.type.equals("series", ignoreCase = true) ||
                (finalSeason != null) || (epFromFile != null) || (epFinal != null)

        // Si no es serie (es película o especial único), devolvemos solo el nombre
        if (!isSeries) return seriesName

        // 6. Formateo de las etiquetas
        val cap = prettyCap(epFinal)
        val temp = prettyTemp(finalSeason) // <--- AQUI USAMOS EL DATO CONSOLIDADO

        // 7. Construcción de la cadena final
        return when {
            temp != null && cap != null -> "$temp $cap - $seriesName"
            cap != null -> "$cap - $seriesName"
            else -> seriesName
        }
    }

    private fun fileBaseName(path: String): String {
        return path.replace("\\", "/").substringAfterLast("/").substringBeforeLast(".")
    }

    private fun pad2(n: Int): String = n.toString().padStart(2, '0')
    private fun splitPathParts(path: String) = path.replace("\\", "/").trim('/').split("/").filter { it.isNotBlank() }

    private fun normalizeName(s: String): String =
        s.trim().replace('_', ' ').replace(Regex("""\s+"""), " ").lowercase()
}
