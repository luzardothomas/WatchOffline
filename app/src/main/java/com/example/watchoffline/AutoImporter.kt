package com.example.watchoffline

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.view.Gravity
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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

    // =========================
    // Models
    // =========================
    private data class RawItem(
        val serverId: String, val share: String, val smbPath: String,
        val title: String, val img: String, val skip: Int, val delay: Int, val videoSrc: String,
        val groupSeries: String? = null,
        val groupSeason: Int? = null
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
    private val scanPool = Executors.newFixedThreadPool(24)
    private val COVER_TIMEOUT_MS = 3500
    private val API_HOST = "api-watchoffline.luzardo-thomas.workers.dev"
    private val videoExt = hashSetOf("mp4", "mkv", "avi", "webm", "mov", "flv", "mpg", "mpeg", "m4v", "ts", "3gp", "wmv")

    // Regex
    private val reSeason1 = Regex("""(?i)temporada\s*(\d{1,2})""")
    private val reSeason2 = Regex("""(?i)\btemp\s*(\d{1,2})""")
    private val reSeason3 = Regex("""(?i)\bseason\s*(\d{1,2})""")
    private val reSeason4 = Regex("""(?i)\b[st][._\- ]*(\d{1,2})\b""")
    private val reSE1 = Regex("""(?i)\b[st](\d{1,2})\s*[._\- ]*\s*e(\d{1,3})\b""")
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

    private fun isNetworkAvailable(context: Context?): Boolean {
        val connectivityManager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivityManager?.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false

        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            else -> false
        }
    }

    // =========================
    // Core Logic
    // =========================

    fun run(toast: (String) -> Unit, onDone: (Int) -> Unit, onError: (String) -> Unit) {

        val activity = context as? Activity ?: return // Si no hay activity, salimos

        if (!isNetworkAvailable(context)) {
            // Ejecutamos el toast en el hilo principal por seguridad
            activity.runOnUiThread {
                toast("No hay conexión para importar de SMB")
            }
            // Cancelamos todo el proceso aquí mismo
            return
        }

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
                text = "Importando de SMBs..."
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

                val proxyOk = smbGateway.ensureProxyStarted(proxyPort)
                if (!proxyOk) { onError("Error Proxy SMB."); return@Thread }

                val serverIds = smbGateway.listCachedServerIds()
                if (serverIds.isEmpty()) { onError("Sin servidores."); return@Thread }

                val allRawItems = CopyOnWriteArrayList<RawItem>()
                val scanFutures = ArrayList<java.util.concurrent.Future<*>>()

                // === BUCLE CORREGIDO MULTI-SHARE ===
                for (serverId in serverIds) {
                    // 1. Obtenemos TODOS los shares registrados para esa IP
                    val shares = smbGateway.getSavedShares(serverId)

                    if (shares.isEmpty()) {
                        Log.w("AutoImporter", "Server $serverId no tiene shares guardados.")
                        continue
                    }

                    // 2. Lanzamos un hilo de escaneo por CADA share
                    for (share in shares) {
                        scanFutures.add(scanPool.submit {
                            try {
                                Log.d("AutoImporter", "Escaneando Server: $serverId | Share: $share")
                                processServer(serverId, share, allRawItems)
                            } catch (e: Exception) {
                                Log.e("AutoImporter", "Error escaneando $serverId/$share", e)
                            }
                        })
                    }
                }
                // ===================================

                // Esperar a que terminen todos los escaneos
                for (f in scanFutures) try { f.get() } catch (_: Exception) {}

                if (allRawItems.isEmpty()) { onDone(0); return@Thread }

                // Procesamiento de Playlist
                val uniqueItems = allRawItems.distinctBy { it.videoSrc }
                val seriesMap = HashMap<Pair<String, Int>, ArrayList<RawItem>>()
                val movies = ArrayList<RawItem>()

                for (ri in uniqueItems) {
                    // Ahora usamos los datos calculados en processServer, no adivinamos de nuevo
                    if (ri.groupSeries != null && ri.groupSeason != null) {
                        seriesMap.getOrPut(ri.groupSeries to ri.groupSeason) { ArrayList() }.add(ri)
                    } else {
                        movies.add(ri)
                    }
                }

                val previews = ArrayList<PreviewJson>()
                for ((key, list) in seriesMap) {
                    list.sortWith(compareBy({ parseEpisodeForSort(it.smbPath) ?: Int.MAX_VALUE }, { it.smbPath }))
                    previews.add(PreviewJson("${normalizeName(key.first)}_s${pad2(key.second)}_servidor.json",
                        list.map { VideoItem(it.title, it.skip, it.delay, it.img, it.img, it.videoSrc) }, "SERIES"))
                }

                val sagaMap = HashMap<String, ArrayList<RawItem>>()
                for (m in movies) sagaMap.getOrPut(inferSagaNameFromPath(m.smbPath)) { ArrayList() }.add(m)
                for ((saga, list) in sagaMap) {
                    list.sortWith(compareBy({ extractMovieSortKey(it.smbPath, it.title) }, { it.title }))
                    val fName = if (list.size > 1) "saga_${normalizeName(saga).replace(" ", "_")}_servidor.json" else "${normalizeName(list.first().title).replace(" ", "_")}_servidor.json"
                    previews.add(PreviewJson(fName, list.map { VideoItem(it.title, it.skip, it.delay, it.img, it.img, it.videoSrc) }, "MOVIES"))
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
                    // Usamos uniqueJsonName pasándole el Set que ya tenemos en RAM.
                    // Esto protege contra colisiones dentro del mismo lote nuevo.
                    val safeName = uniqueJsonName(p.fileName, existingNames)

                    jsonDataManager.addJson(context, safeName, p.videos)

                    // Actualizamos el Set en memoria para el siguiente del bucle
                    existingNames.add(safeName)
                    count++
                }

                if (count != 0) {
                    val ms = (System.nanoTime() - startTime) / 1_000_000
                    toast("JSONs: $count\nVIDEOS: ${uniqueItems.size}")
                    toast("Importado en ${ms/1000.0}s")
                }
                else {
                    toast("No se encontraron nuevos videos")
                }

                onDone(count)
            } catch (e: Exception) {
                onError(e.message ?: "Error desconocido")
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

            // --- INICIO CÁLCULO DE GRUPO (FIX PARA SERIES/PELIS) ---
            val parts = splitPathParts(p)

            // 1. Detectar Temporada (Prioridad: Carpeta > Archivo > API)
            val seasonFromFolder = if (parts.size >= 2) parseSeasonFromFolderOrName(parts[parts.size - 2]) else null
            val seFile = parseSeasonEpisodeFromFilename(p)
            val finalSeason = seasonFromFolder ?: seFile?.first ?: cover?.season

            // 2. Decidir si es Serie (Tiene temporada explicita o flag API)
            val isSeries = cover?.type.equals("series", ignoreCase = true) || finalSeason != null

            // 3. Determinar el Nombre de la Serie para el grupo
            val seriesNameForGroup = if (isSeries) {
                if (seasonFromFolder != null && parts.size >= 3) {
                    parts[parts.size - 3] // Nombre de la carpeta "Malcolm"
                } else {
                    // Si es archivo suelto o no hay carpeta de temporada, usamos API ID o nombre de archivo limpio
                    cover?.id ?: fileBaseName(p).replace(Regex("""\d+.*"""), "").trim()
                }
            } else null

            // 4. Temporada final (Si es serie pero no tiene numero, forzamos 1)
            val seasonForGroup = if (isSeries) (finalSeason ?: 1) else null
            // --- FIN CÁLCULO ---

            val finalSkip = if (isSeries) (cover?.skipToSecond ?: 0) else 0
            val finalDelay = if (isSeries) (cover?.delaySkip ?: 0) else 0

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

            destList.add(RawItem(serverId, share, p, title, img,
                finalSkip, finalDelay, videoUrl,
                // Pasamos los datos calculados
                seriesNameForGroup, seasonForGroup
            ))
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

        // Si tenemos Serie/Temporada X/Archivo
        if (parts.size >= 3) {
            val seasonFolder = parts[parts.size - 2]
            val seasonNum = parseSeasonFromFolderOrName(seasonFolder) // Usamos tu parser existente

            // A veces el archivo es solo un numero "9.mp4", el regex epOnly puede fallar si espera "ep 9"
            // Así que chequeamos si el nombre sin extensión es puramente numérico
            val epNum = epOnly ?: fileNoExt.toIntOrNull()

            if (seasonNum != null && epNum != null) {
                val seriesFolderName = parts[parts.size - 3]
                // Generamos una query explicita: "malcolm s07e09"
                // La API entenderá esto perfectamente y buscará en la carpeta s07
                return "${normalizeName(seriesFolderName)} s${pad2(seasonNum)}e${pad2(epNum)}"
            }
        }

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
        val temp = prettyTemp(finalSeason)

        // 7. Construcción de la cadena final
        return when {
            temp != null && cap != null -> "$temp $cap - $seriesName"
            cap != null -> "$cap - $seriesName"
            else -> seriesName
        }
    }

    private fun uniqueJsonName(base: String, existing: MutableSet<String>): String {
        if (!existing.contains(base)) return base
        val prefix = base.removeSuffix(".json")
        var i = 2
        while (true) {
            val cand = "${prefix}_$i.json"; if (!existing.contains(cand)) return cand; i++
        }
    }
}

