package com.example.watchoffline

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityOptionsCompat
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.*
import java.util.LinkedHashMap
import java.util.Locale

class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val handler = Handler(Looper.getMainLooper())

    private val jsonDataManager = JsonDataManager()
    private val cardPresenter = CardPresenter()
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    private var allImported: List<ImportedJson> = emptyList()

    private var lastScheduledQuery: String = ""
    private var lastExecutedQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ CLAVE: NO disparar VOZ (evita Katniss / VoiceInputActivity)
        setSpeechRecognitionCallback(null)

        jsonDataManager.loadData(requireContext())
        allImported = jsonDataManager.getImportedJsons()
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ✅ SIN ESTO, no hay results provider => no busca
        setSearchResultProvider(this)

        setOnItemViewClickedListener { itemViewHolder, item, _, row ->
            val movie = item as? Movie ?: return@setOnItemViewClickedListener

            // ✅ playlist desde la fila (ListRow)
            val listRow = row as? ListRow
            val adapter = listRow?.adapter

            val playlist = ArrayList<Movie>()
            if (adapter != null) {
                for (i in 0 until adapter.size()) {
                    val obj = adapter.get(i)
                    if (obj is Movie) playlist.add(obj)
                }
            }

            val index = playlist.indexOfFirst { it.videoUrl == movie.videoUrl }
                .let { if (it >= 0) it else 0 }

            val intent = Intent(requireContext(), DetailsActivity::class.java).apply {
                putExtra(DetailsActivity.MOVIE, movie)

                if (playlist.size > 1) {
                    putExtra(DetailsActivity.EXTRA_PLAYLIST, playlist)
                    putExtra(DetailsActivity.EXTRA_INDEX, index)
                }
            }

            val cardView = itemViewHolder.view as? ImageCardView
            val shared = cardView?.mainImageView

            if (shared != null) {
                val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    requireActivity(),
                    shared,
                    DetailsActivity.SHARED_ELEMENT_NAME
                )
                startActivity(intent, options.toBundle())
            } else {
                startActivity(intent)
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
        val q = normalize(raw)
        if (q == lastScheduledQuery) return
        lastScheduledQuery = q

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ performSearch(raw) }, 120)
    }

    private fun performSearch(raw: String) {
        val q = normalize(raw)
        if (q == lastExecutedQuery) return
        lastExecutedQuery = q

        Log.d("SearchFragment", "SEARCH q='$q'")

        rowsAdapter.clear()
        if (q.isBlank()) return

        val out = LinkedHashMap<String, Movie>()

        for (json in allImported) {
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
                        description = jsonTitle
                    )

                    val key = movie.videoUrl?.takeIf { it.isNotBlank() }
                        ?: "${jsonTitle}:${movie.title}"

                    out[key] = movie
                }
            }
        }

        val movies = out.values.toList()
        Log.d("SearchFragment", "RESULTS size=${movies.size}")

        val itemsPerRow = 6
        movies.chunked(itemsPerRow).forEach { chunk ->
            val rowAdapter = ArrayObjectAdapter(cardPresenter)
            chunk.forEach { rowAdapter.add(it) }
            rowsAdapter.add(ListRow(null, rowAdapter))
        }
    }

    private fun normalize(s: String): String =
        s.trim()
            .lowercase(Locale.getDefault())
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")

    private fun safePrettyTitle(fileName: String): String {
        return try {
            prettyTitle(fileName)
        } catch (_: Throwable) {
            fileName.removeSuffix(".json")
                .replace("_", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
