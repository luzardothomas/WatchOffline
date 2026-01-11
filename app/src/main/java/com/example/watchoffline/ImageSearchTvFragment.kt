package com.example.watchoffline

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class ImageSearchTvFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private lateinit var mRowsAdapter: ArrayObjectAdapter
    private var currentQuery = ""
    private var searchJob: Job? = null
    private var currentPage = 1

    // ✅ IMPLEMENTACIÓN OBLIGATORIA: Vincula los resultados con la UI de Leanback
    override fun getResultsAdapter(): ObjectAdapter = mRowsAdapter

    // Se dispara mientras el usuario escribe (opcional)
    override fun onQueryTextChange(newQuery: String?): Boolean = true

    // Se dispara cuando el usuario confirma la búsqueda o habla por el micro
    override fun onQueryTextSubmit(query: String?): Boolean {
        val q = query.orEmpty().trim()
        if (q.isNotBlank()) {
            currentQuery = q
            performSearch(q)
        }
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configuración del botón de voz nativo
        setSpeechRecognitionCallback {
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Busca una imagen...")
                }
                activity?.startActivityForResult(intent, 1000)
            } catch (e: Exception) {
                Log.e("TV_DEBUG", "Error iniciando micrófono")
            }
        }

        // Definimos cómo se verán las filas
        val rowPresenter = ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply {
            shadowEnabled = false
            selectEffectEnabled = false
        }
        mRowsAdapter = ArrayObjectAdapter(rowPresenter)

        // IMPORTANTE: Le decimos al fragmento que nosotros proveemos los datos
        setSearchResultProvider(this)

        // Click en las imágenes o en el botón cargar más
        setOnItemViewClickedListener { _, item, _, _ ->
            if (item == "ACTION_LOAD_MORE") {
                removeLoadMoreButton()
                loadNextPage()
            } else if (item is String && item.startsWith("http")) {
                // Llamamos a la función de la Activity principal
                (activity as? ImageSearchActivity)?.processImageSelection(item)
            }
        }
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        (activity as? ImageSearchActivity)?.setLoading(true, "Buscando '$currentQuery'...")


        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val allUrls = ImageImporter.searchImages(query, first = 1)

                if (!isActive || !isAdded || allUrls.isEmpty()) {
                    withContext(Dispatchers.Main) { (activity as? ImageSearchActivity)?.setLoading(false) }
                    return@launch
                }

                val numFilas = allUrls.size / 8
                if (numFilas < 1) {
                    withContext(Dispatchers.Main) { (activity as? ImageSearchActivity)?.setLoading(false) }
                    return@launch
                }

                val tempRows = allUrls.take(numFilas * 8).chunked(8).mapIndexed { index, group ->
                    val listRowAdapter = ArrayObjectAdapter(ImageCardPresenter())
                    group.forEach { listRowAdapter.add(it) }
                    ListRow(HeaderItem(index.toLong(), ""), listRowAdapter)
                }

                withContext(Dispatchers.Main) {
                    mRowsAdapter.clear()
                    mRowsAdapter.addAll(0, tempRows)
                    addLoadMoreButton()
                    (activity as? ImageSearchActivity)?.setLoading(false)

                    // Forzamos el foco inicial a la lista
                    val gridView = view?.findViewById<VerticalGridView>(androidx.leanback.R.id.container_list)
                    gridView?.requestFocus()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { (activity as? ImageSearchActivity)?.setLoading(false) }
            }
        }
    }

    private fun addLoadMoreButton() {
        val actionAdapter = ArrayObjectAdapter(ImageCardPresenter())
        actionAdapter.add("ACTION_LOAD_MORE")
        mRowsAdapter.add(ListRow(HeaderItem(999, ""), actionAdapter))
    }

    private fun removeLoadMoreButton() {
        if (mRowsAdapter.size() > 0) {
            val lastRow = mRowsAdapter.get(mRowsAdapter.size() - 1) as? ListRow
            if (lastRow?.headerItem?.id == 999L) {
                mRowsAdapter.remove(lastRow)
            }
        }
    }

    // Recibe el texto de la Activity (cuando el micro termina) y dispara la búsqueda
    fun setVoiceResults(data: Intent?, text: String?) {
        if (text != null) {
            setSearchQuery(text, true)
        }
    }

    private fun loadNextPage() {
        currentPage++
        val startOffset = (currentPage - 1) * 24 + 1
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val newUrls = ImageImporter.searchImages(currentQuery, first = startOffset)
                if (!isActive || !isAdded || newUrls.isEmpty()) return@launch

                val numFilas = newUrls.size / 8
                if (numFilas < 1) return@launch

                val nuevasFilas = newUrls.take(numFilas * 8).chunked(8).map { group ->
                    val listRowAdapter = ArrayObjectAdapter(ImageCardPresenter())
                    group.forEach { url -> listRowAdapter.add(url) }
                    ListRow(null, listRowAdapter)
                }

                withContext(Dispatchers.Main) {
                    mRowsAdapter.addAll(mRowsAdapter.size(), nuevasFilas)
                    addLoadMoreButton()
                }
            } catch (e: Exception) {}
        }
    }
}