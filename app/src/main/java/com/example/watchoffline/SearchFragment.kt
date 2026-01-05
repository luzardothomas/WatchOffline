package com.example.watchoffline

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
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

    private var allImported: List<ImportedJson> = emptyList()

    // ✅ DEDUPE
    private var lastScheduledQuery: String = ""
    private var lastExecutedQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSpeechRecognitionCallback(object : SpeechRecognitionCallback {
            override fun recognizeSpeech() {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Decí qué querés buscar")
                }
                try {
                    startActivityForResult(intent, REQUEST_SPEECH)
                } catch (_: Exception) {}
            }
        })

        jsonDataManager.loadData(requireContext())
        allImported = jsonDataManager.getImportedJsons()

        setSearchResultProvider(this)
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setOnItemViewClickedListener { itemViewHolder, item, _, row ->
            val movie = item as? Movie ?: return@setOnItemViewClickedListener

            // ✅ playlist desde la fila
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

    override fun onResume() {
        super.onResume()

        // ✅ igual que en MainFragment: al volver del video, forzar foco al último reproducido
        handler.post {
            Log.e(TAG, "FOCUSDBG_SEARCH onResume() -> try focusLastPlayedIfAny")
            focusLastPlayedIfAny()
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

    // ✅ DEDUPE + debounce
    private fun scheduleSearch(raw: String) {
        val q = normalize(raw)

        if (q == lastScheduledQuery) return
        lastScheduledQuery = q

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ performSearch(raw) }, 180)
    }

    private fun performSearch(raw: String) {
        val q = normalize(raw)

        if (q == lastExecutedQuery) return
        lastExecutedQuery = q

        rowsAdapter.clear()
        if (q.isBlank()) return

        // ✅ mapa videoUrl -> cover REAL (solo JSONs no RANDOM)
        val coverByUrl = buildNonRandomCoverByUrl()

        val out = LinkedHashMap<String, Movie>()

        for (json in allImported) {
            val jsonTitle = safePrettyTitle(json.fileName)
            val jsonMatch = normalize(jsonTitle).contains(q)

            for (v in json.videos) {
                val titleMatch = normalize(v.title).contains(q)

                if (titleMatch || jsonMatch) {

                    var movie = Movie(
                        title = v.title,
                        videoUrl = v.videoUrl,
                        cardImageUrl = v.cardImageUrl,
                        backgroundImageUrl = v.backgroundImageUrl,
                        skipToSecond = v.skip,
                        delaySkip = v.delaySkip,
                        description = jsonTitle
                    )

                    // ✅ FIX CLAVE:
                    // si viene de RANDOM con cover dados → usar cover real
                    if (isDadosCover(movie.cardImageUrl)) {
                        val realCover = coverByUrl[movie.videoUrl?.trim().orEmpty()]
                        if (!realCover.isNullOrBlank()) {
                            movie = movie.copy(cardImageUrl = realCover)
                        }
                    }

                    val key = movie.videoUrl?.takeIf { it.isNotBlank() }
                        ?: "${jsonTitle}:${movie.title}"

                    out[key] = movie
                }
            }
        }

        val movies = out.values.toList()
        val itemsPerRow = 6

        movies.chunked(itemsPerRow).forEach { chunk ->
            val rowAdapter = ArrayObjectAdapter(cardPresenter)
            chunk.forEach { rowAdapter.add(it) }
            rowsAdapter.add(ListRow(null, rowAdapter))
        }

        // ✅ reintentar foco al último reproducido
        handler.post {
            focusLastPlayedIfAny()
        }
    }


    // =========================
    // ✅ MISMA LÓGICA QUE MAINFRAGMENT (pero usando rowsSupportFragment)
    // =========================

    private fun readLastPlayedUrl(): String? {
        val u = requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PLAYED, null)
            ?.trim()

        Log.e(TAG, "FOCUSDBG_SEARCH readLastPlayedUrl=$u")
        return u?.takeIf { it.isNotEmpty() }
    }

    private fun focusLastPlayedIfAny(): Boolean {
        Log.e(TAG, "FOCUSDBG_SEARCH ENTER focusLastPlayedIfAny()")

        val lastUrl = readLastPlayedUrl() ?: return false

        var targetRowIndex = -1
        var targetColIndex = -1

        for (r in 0 until rowsAdapter.size()) {
            val row = rowsAdapter.get(r) as? ListRow ?: continue
            val rowAdapter = row.adapter ?: continue

            for (c in 0 until rowAdapter.size()) {
                val m = rowAdapter.get(c) as? Movie ?: continue
                if (m.videoUrl == lastUrl) {
                    targetRowIndex = r
                    targetColIndex = c
                    break
                }
            }
            if (targetRowIndex >= 0) break
        }

        if (targetRowIndex < 0 || targetColIndex < 0) {
            Log.e(TAG, "FOCUSDBG_SEARCH NOT FOUND in results url=$lastUrl")
            return false
        }

        Log.e(TAG, "FOCUSDBG_SEARCH FOUND row=$targetRowIndex col=$targetColIndex url=$lastUrl")

        val rowsFrag = rowsSupportFragment
        if (rowsFrag == null) {
            Log.e(TAG, "FOCUSDBG_SEARCH rowsSupportFragment == null (aun no creado)")
            return false
        }

        // ✅ EXACTO como MainFragment: setSelectedPosition con ViewHolderTask
        rowsFrag.setSelectedPosition(targetRowIndex, false, object : Presenter.ViewHolderTask() {
            override fun run(holder: Presenter.ViewHolder?) {
                val rowView = holder?.view
                if (rowView == null) {
                    Log.e(TAG, "FOCUSDBG_SEARCH holder==null en task")
                    return
                }

                val rowContent = rowView.findViewById<HorizontalGridView>(androidx.leanback.R.id.row_content)
                if (rowContent == null) {
                    Log.e(TAG, "FOCUSDBG_SEARCH row_content==null en task")
                    return
                }

                rowContent.scrollToPosition(targetColIndex)

                rowContent.post {
                    rowContent.setSelectedPosition(targetColIndex)
                    rowContent.requestFocus()

                    Log.e(
                        TAG,
                        "FOCUSDBG_SEARCH APPLIED row=$targetRowIndex col=$targetColIndex selected=${rowContent.selectedPosition}"
                    )
                }
            }
        })

        return true
    }

    // =========================

    private fun isRandomJsonName(name: String): Boolean =
        name.trim().uppercase(Locale.ROOT).startsWith("RANDOM")

    private fun isDadosCover(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        return u.contains("/drawable/dados")
    }

    private fun buildNonRandomCoverByUrl(): Map<String, String> {
        val nonRandom = allImported.filterNot { isRandomJsonName(it.fileName) }

        val map = HashMap<String, String>(nonRandom.size * 20)

        nonRandom.forEach { ij ->
            ij.videos.forEach { v ->
                val url = v.videoUrl?.trim().orEmpty()
                val cover = v.cardImageUrl?.trim().orEmpty()
                if (url.isNotEmpty() && cover.isNotEmpty()) {
                    map.putIfAbsent(url, cover)
                }
            }
        }
        return map
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_SPEECH) return
        if (resultCode != Activity.RESULT_OK) return

        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        val spoken = results.firstOrNull().orEmpty().trim()
        if (spoken.isBlank()) return

        setSearchQuery(spoken, true)
        scheduleSearch(spoken)
    }

    companion object {
        private const val REQUEST_SPEECH = 9001

        private const val TAG = "SearchFragment"
        private const val PREFS_NAME = "watchoffline_prefs"
        private const val KEY_LAST_PLAYED = "LAST_PLAYED_VIDEO_URL"
    }
}


