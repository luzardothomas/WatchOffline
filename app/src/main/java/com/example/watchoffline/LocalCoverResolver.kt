package com.example.watchoffline

import android.util.Log
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

public class LocalCoverResolver(
    private val indexJsonStr: String,
    private val seriesMapJsonStr: String
) {

    @Keep
    data class ApiCover(
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

    private val aliasMap = HashMap<String, IndexItem>()
    private val globalCoverMap = HashMap<String, Boolean>()
    private val r2BaseUrl = "https://pub-e4a7b9dc4eaa4c3b92580000615de316.r2.dev"
    private var config: IndexConfig = IndexConfig()
    private data class IndexConfig(
        val paths: PathsConfig? = null,
        val notFound: NotFoundConfig? = null,
        val series: SeriesConfig? = null
    )
    private data class PathsConfig(val moviesBase: String?, val seriesBase: String?)
    private data class NotFoundConfig(val path: String?)
    private data class SeriesConfig(val seasonDirTemplate: String?)
    private data class IndexItem(
        val id: String, val dir: String, val type: String?,
        val aliases: List<String>?, val coverFile: String?,
        val skipSeconds: Int?, val delaySeconds: Int?,
        val episodeSkipDefault: Int?, val delaySkipDefault: Int?,
        val seasonEpisodeCounts: List<Int>?,
        val episodeSkips: Map<String, Map<String, Int>>?,
        val delaySkips: Map<String, Map<String, Int>>?
    )

    init {
        loadIndexData(indexJsonStr)
        loadSeriesMap(seriesMapJsonStr)
    }

    private fun loadIndexData(jsonStr: String) {
        try {
            val element = com.google.gson.JsonParser.parseString(jsonStr)
            if (element.isJsonObject) {
                val rootObj = element.asJsonObject

                if (rootObj.has("config")) {
                    config = Gson().fromJson(rootObj.get("config"), IndexConfig::class.java)
                } else {
                    config = IndexConfig()
                }

                if (rootObj.has("items") && rootObj.get("items").isJsonArray) {
                    val itemsArray = rootObj.get("items").asJsonArray
                    var aliasCount = 0

                    itemsArray.forEach { itemElement ->
                        try {
                            val item = Gson().fromJson(itemElement, IndexItem::class.java)
                            val allAliases = (item.aliases ?: emptyList()) + item.id
                            allAliases.forEach { alias ->
                                val norm = normalizeQuery(alias)
                                if (norm.isNotEmpty()) {
                                    aliasMap[norm] = item
                                    aliasCount++
                                }
                            }
                        } catch (itemError: Exception) {
                            Log.w("LocalResolver", "Ignorando un item mal formado en index_json: ${itemError.message}")
                        }
                    }
                    Log.d("LocalResolver", "✅ INIT: Parsed ${aliasMap.size} alias únicos de items válidos. Total aliases procesados: $aliasCount")
                }
            }
        } catch (e: Exception) {
            Log.e("LocalResolver", "❌ INIT ERROR: Fallo crítico leyendo index_json", e)
        }
    }

    private fun loadSeriesMap(jsonStr: String) {
        try {
            val type = object : com.google.gson.reflect.TypeToken<HashMap<String, Boolean>>() {}.type
            val map: HashMap<String, Boolean> = Gson().fromJson(jsonStr, type)
            globalCoverMap.putAll(map)
            Log.d("LocalResolver", "✅ Mapa de series cargado: ${globalCoverMap.size} series")
        } catch (e: Exception) {
            Log.e("LocalResolver", "❌ Error cargando seriesMap", e)
        }
    }

    private fun normalizeQuery(q: String?): String {
        if (q.isNullOrBlank()) return ""
        return q.trim().lowercase()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("-+"), "_")
            .replace(Regex("__+"), "_")
    }

    private fun parseSeasonEpisodeFromQuery(qNorm: String): Pair<Int, Int>? {
        val m1 = Regex("""(?:^|_)(s|t)(\d{1,2})(?:_?e)(\d{1,3})(?:_|$)""", RegexOption.IGNORE_CASE).find(qNorm)
        if (m1 != null) return Pair(m1.groupValues[2].toInt(), m1.groupValues[3].toInt())

        val m2 = Regex("""(?:^|_)(\d{1,2})x(\d{1,3})(?:_|$)""", RegexOption.IGNORE_CASE).find(qNorm)
        if (m2 != null) return Pair(m2.groupValues[1].toInt(), m2.groupValues[2].toInt())

        return null
    }

    private fun parseTrailingEpisodeNumber(qNorm: String, seriesAlias: String): Int? {
        var rest = qNorm
        if (rest.startsWith(seriesAlias)) rest = rest.substring(seriesAlias.length)
        rest = rest.replace(Regex("^_+"), "")

        if (rest.matches(Regex("""^\d{1,4}$"""))) return rest.toInt()

        val m = Regex("""^ep_?(\d{1,4})$""", RegexOption.IGNORE_CASE).find(rest)
        if (m != null) return m.groupValues[1].toInt()

        return null
    }

    private fun globalToSeasonEpisode(counts: List<Int>, globalEp: Int): Pair<Int, Int>? {
        var remaining = globalEp
        for (i in counts.indices) {
            val c = counts[i]
            if (remaining <= c) return Pair(i + 1, remaining)
            remaining -= c
        }
        return null
    }

    fun resolve(query: String): ApiCover? {
        val qNorm = normalizeQuery(query.replace(Regex("""\.(mkv|mp4|avi|mov|wmv|webm)$""", RegexOption.IGNORE_CASE), ""))

        var item: IndexItem? = null
        var matchedAlias = ""
        var currentSearch = qNorm

        while (currentSearch.isNotEmpty()) {
            if (aliasMap.containsKey(currentSearch)) {
                item = aliasMap[currentSearch]
                matchedAlias = currentSearch
                break
            }
            currentSearch = currentSearch.dropLast(1)
        }

        if (item == null) {
            val notFoundPath = config.notFound?.path ?: "no_encontrada/cover.jpg"
            return ApiCover(url = "$r2BaseUrl/$notFoundPath")
        }

        val type = if (item.type == "series") "series" else "movie"
        var season = 1
        var episode = 1

        if (type == "series") {
            val se = parseSeasonEpisodeFromQuery(qNorm)
            if (se != null) {
                season = se.first
                episode = se.second
            } else {
                val globalEp = parseTrailingEpisodeNumber(qNorm, matchedAlias)
                if (globalEp != null) {
                    val mapped = globalToSeasonEpisode(item.seasonEpisodeCounts ?: emptyList(), globalEp)
                    if (mapped != null) {
                        season = mapped.first
                        episode = mapped.second
                    }
                }
            }
        }

        val skip = if (type == "series") {
            item.episodeSkips?.get(season.toString())?.get(episode.toString()) ?: item.episodeSkipDefault ?: 0
        } else item.skipSeconds ?: 0

        val delay = if (type == "series") {
            item.delaySkips?.get(season.toString())?.get(episode.toString()) ?: item.delaySkipDefault ?: 0
        } else item.delaySeconds ?: 0

        val finalUrl = if (type == "series") {
            val hasGlobalCover = globalCoverMap[item.dir] == true

            if (hasGlobalCover) {
                "${config.paths?.seriesBase ?: "series"}/${item.dir}/cover.jpg"
            } else {
                val sFolder = (config.series?.seasonDirTemplate ?: "s{season:02}")
                    .replace("{season:02}", season.toString().padStart(2, '0'))
                    .replace("{season}", season.toString())
                "${config.paths?.seriesBase ?: "series"}/${item.dir}/$sFolder/cover.jpg"
            }
        } else {
            "${config.paths?.moviesBase ?: "peliculas"}/${item.dir}/${item.coverFile ?: "cover.jpg"}"
        }

        return ApiCover(
            type = type,
            id = item.id,
            dir = item.dir,
            season = if (type == "series") season else null,
            episode = if (type == "series") episode else null,
            skipToSecond = skip,
            delaySkip = delay,
            url = "$r2BaseUrl/$finalUrl"
        )
    }
}
