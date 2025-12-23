package com.example.watchoffline

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityOptionsCompat
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.*
import java.util.Locale
import java.util.LinkedHashMap

class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val handler = Handler(Looper.getMainLooper())

    private val jsonDataManager = JsonDataManager()
    private val cardPresenter = CardPresenter()

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val resultsAdapter = ArrayObjectAdapter(cardPresenter)

    private var allImported: List<ImportedJson> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        jsonDataManager.loadData(requireContext())
        allImported = jsonDataManager.getImportedJsons()

        rowsAdapter.add(ListRow(HeaderItem(0L, "Resultados"), resultsAdapter))
        setSearchResultProvider(this)

        setOnItemViewClickedListener { itemViewHolder, item, _, _ ->
            if (item is Movie) {
                val intent = Intent(requireContext(), DetailsActivity::class.java).apply {
                    putExtra(DetailsActivity.MOVIE, item)
                }
                val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    requireActivity(),
                    (itemViewHolder.view as ImageCardView).mainImageView,
                    DetailsActivity.SHARED_ELEMENT_NAME
                )
                startActivity(intent, options.toBundle())
            }
        }
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String?): Boolean {
        scheduleSearch(newQuery.orEmpty())
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        scheduleSearch(query.orEmpty())
        return true
    }

    private fun scheduleSearch(raw: String) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ performSearch(raw) }, 180)
    }

    private fun performSearch(raw: String) {
        val q = normalize(raw)
        resultsAdapter.clear()

        if (q.isBlank()) return

        val out = LinkedHashMap<String, Movie>() // dedupe por key (videoUrl)

        for (json in allImported) {
            // ✅ siempre no-null (evita el error String?)
            val jsonTitle = safePrettyTitle(json.fileName)
            val jsonMatch = normalize(jsonTitle).contains(q)

            for (v in json.videos) {
                val titleMatch = normalize(v.title).contains(q)

                if (titleMatch || jsonMatch) {
                    val movie = Movie(
                        title = v.title,
                        videoUrl = v.videoSrc,
                        cardImageUrl = v.imgSml,
                        backgroundImageUrl = v.imgBig,
                        skipToSecond = v.skipToSecond,
                        description = jsonTitle // de qué JSON viene
                    )

                    // ✅ key nunca null ni vacía
                    val key = movie.videoUrl
                        ?.takeIf { it.isNotBlank() }
                        ?: "${jsonTitle}:${movie.title}"

                    out[key] = movie
                }
            }
        }

        out.values.take(200).forEach { resultsAdapter.add(it) }
    }

    private fun normalize(s: String): String {
        return s.trim()
            .lowercase(Locale.getDefault())
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
    }

    /**
     * ✅ Usa prettyTitle() global si existe (TitleUtils.kt),
     * y si por alguna razón falla o devuelve raro, siempre devuelve String NO-null.
     */
    private fun safePrettyTitle(fileName: String): String {
        return try {
            // si tenés TitleUtils.kt con prettyTitle(...) top-level, esto compila y se usa
            prettyTitle(fileName)
        } catch (_: Throwable) {
            // fallback simple (por si no existe o hay conflicto raro)
            fileName.removeSuffix(".json")
                .replace("_", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
