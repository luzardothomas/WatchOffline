package com.example.watchoffline

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
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

    // ✅ DEDUPE: evita ejecutar dos veces la misma query
    private var lastScheduledQuery: String = ""
    private var lastExecutedQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ HABILITAR VOZ: callback para que el mic no quede “muerto”
        setSpeechRecognitionCallback(object : SpeechRecognitionCallback {
            override fun recognizeSpeech() {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Decí qué querés buscar")
                }

                try {
                    // Leanback maneja el result y llama onActivityResult del fragment
                    startActivityForResult(intent, REQUEST_SPEECH)
                } catch (_: Exception) {
                    // Si el dispositivo no tiene recognizer/assistant, simplemente no hará nada
                }
            }
        })

        // ❌ Sacá esto: en tu leanback no existe el id y además view acá es null
        // view?.findViewById<View?>(androidx.leanback.R.id.lb_search_bar_speech_orb) ...

        jsonDataManager.loadData(requireContext())
        allImported = jsonDataManager.getImportedJsons()

        setSearchResultProvider(this)

        setOnItemViewClickedListener { itemViewHolder, item, _, _ ->
            val movie = item as? Movie ?: return@setOnItemViewClickedListener

            val intent = Intent(requireContext(), DetailsActivity::class.java).apply {
                putExtra(DetailsActivity.MOVIE, movie)
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
        // ✅ Dejalo activo por voz/enter; el dedupe evita repetir
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

        val out = LinkedHashMap<String, Movie>() // dedupe por key (videoUrl)

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

                    val key = movie.videoUrl
                        ?.takeIf { it.isNotBlank() }
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

        val results = data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            .orEmpty()

        val spoken = results.firstOrNull().orEmpty().trim()
        if (spoken.isBlank()) return

        // ✅ Pone el texto reconocido en la barra y busca
        setSearchQuery(spoken, true)
        scheduleSearch(spoken)
    }

    companion object {
        private const val REQUEST_SPEECH = 9001
    }
}

