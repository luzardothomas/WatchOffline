package com.example.watchoffline

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*

class MainFragment : BrowseSupportFragment() {

    private val mHandler = Handler(Looper.getMainLooper())
    private lateinit var mBackgroundManager: BackgroundManager
    private var mDefaultBackground: Drawable? = null
    private lateinit var mMetrics: DisplayMetrics
    private var mBackgroundTimer: Timer? = null
    private var mBackgroundUri: String? = null

    private val REQUEST_CODE_IMPORT_JSON = 1001
    private val jsonDataManager = JsonDataManager()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        Log.i(TAG, "onCreate")

        jsonDataManager.loadData(requireContext()) // Los datos persisten aquí

        prepareBackgroundManager()
        setupUIElements()
        loadRows()
        setupEventListeners()

    }

    override fun onDestroy() {
        super.onDestroy()
        mBackgroundTimer?.cancel()
    }

    private fun prepareBackgroundManager() {
        mBackgroundManager = BackgroundManager.getInstance(requireActivity())
        mBackgroundManager.attach(requireActivity().window)
        mDefaultBackground = ContextCompat.getDrawable(requireContext(), R.drawable.default_background)
        mMetrics = DisplayMetrics().apply {
            requireActivity().windowManager.defaultDisplay.getMetrics(this)
        }
    }

    private fun setupUIElements() {
        title = getString(R.string.browse_title)
        headersState = BrowseSupportFragment.HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.fastlane_background)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.search_opaque)
    }

    private fun loadRows() {
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val cardPresenter = CardPresenter()

        // JSON Importados
        jsonDataManager.getImportedJsons().forEach { json ->
            ArrayObjectAdapter(cardPresenter).apply {
                json.videos.forEach { add(it.toMovie()) }
                rowsAdapter.add(ListRow(
                    HeaderItem(json.fileName.hashCode().toLong(), json.fileName), // <- Nombre aquí
                    this
                ))
            }
        }

        // Preferencias
        ArrayObjectAdapter(GridItemPresenter()).apply {
            add(getString(R.string.import_json))
            add(getString(R.string.erase_json))
            rowsAdapter.add(ListRow(HeaderItem(-1, "PREFERENCES"), this))
        }

        adapter = rowsAdapter
    }

    private fun VideoItem.toMovie() = Movie(
        title = title,
        videoUrl = videoSrc,
        cardImageUrl = imgSml,
        backgroundImageUrl = imgBig,
        skipToSecond = skipToSecond,
        description = "Imported from JSON"
    )

    private fun setupEventListeners() {
        setOnSearchClickedListener {
            Toast.makeText(requireContext(), "Implement your own in-app search", Toast.LENGTH_LONG).show()
        }

        onItemViewClickedListener = ItemViewClickedListener()
        onItemViewSelectedListener = ItemViewSelectedListener()
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder,
            item: Any,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            when (item) {
                is Movie -> navigateToDetails(itemViewHolder, item)
                is String -> handleStringAction(item)
            }
        }

        private fun navigateToDetails(itemViewHolder: Presenter.ViewHolder, movie: Movie) {
            Intent(requireContext(), DetailsActivity::class.java).apply {
                putExtra(DetailsActivity.MOVIE, movie)
                val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    requireActivity(),
                    (itemViewHolder.view as ImageCardView).mainImageView,
                    DetailsActivity.SHARED_ELEMENT_NAME
                )
                startActivity(this, options.toBundle())
            }
        }

        private fun handleStringAction(item: String) {
            when (item) {
                getString(R.string.import_json) -> openFilePicker()
                getString(R.string.erase_json) -> showDeleteDialog()
                else -> Toast.makeText(requireContext(), item, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openFilePicker() {
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(this, REQUEST_CODE_IMPORT_JSON)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_IMPORT_JSON && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                    val jsonString = stream.bufferedReader().use { it.readText() }
                    val videos = Gson().fromJson(jsonString, Array<VideoItem>::class.java).toList()

                    // Obtener nombre real del archivo
                    val fileName = JsonUtils.getFileNameFromUri(requireContext(), uri)
                        ?: "imported_${System.currentTimeMillis()}"

                    jsonDataManager.addJson(requireContext(), fileName, videos)
                    refreshUI()
                }
            }
        }
    }

    private fun showDeleteDialog() {
        val items = jsonDataManager.getImportedJsons().map { it.fileName }.toTypedArray()
        AlertDialog.Builder(requireContext()).apply {
            setTitle("Eliminar JSON")
            setItems(items) { _, which ->
                jsonDataManager.removeJson(requireContext(), items[which])
                refreshUI()
            }
            setNegativeButton("Cancelar", null)
            show()
        }
    }

    private fun refreshUI() {
        jsonDataManager.loadData(requireContext()) // Recargar datos
        loadRows()
        startBackgroundTimer()
    }

    private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
        override fun onItemSelected(
            itemViewHolder: Presenter.ViewHolder?,
            item: Any?,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            (item as? Movie)?.let {
                mBackgroundUri = it.backgroundImageUrl
                startBackgroundTimer()
            }
        }
    }

    private fun updateBackground(uri: String?) {
        Glide.with(requireActivity())
            .load(uri)
            .centerCrop()
            .error(mDefaultBackground)
            .into(object : SimpleTarget<Drawable>(mMetrics.widthPixels, mMetrics.heightPixels) {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    mBackgroundManager.drawable = resource
                }
            })
        mBackgroundTimer?.cancel()
    }

    private fun startBackgroundTimer() {
        mBackgroundTimer?.cancel()
        mBackgroundTimer = Timer().apply {
            schedule(UpdateBackgroundTask(), BACKGROUND_UPDATE_DELAY.toLong())
        }
    }

    private inner class UpdateBackgroundTask : TimerTask() {
        override fun run() {
            mHandler.post { updateBackground(mBackgroundUri) }
        }
    }

    private inner class GridItemPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            return TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(GRID_ITEM_WIDTH, GRID_ITEM_HEIGHT)
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(ContextCompat.getColor(context, R.color.default_background))
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }.let { ViewHolder(it) }
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            (viewHolder.view as TextView).text = item as String
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
    }

    companion object {
        private const val TAG = "MainFragment"
        private const val BACKGROUND_UPDATE_DELAY = 300
        private const val GRID_ITEM_WIDTH = 200
        private const val GRID_ITEM_HEIGHT = 200
        private const val NUM_ROWS = 6
        private const val NUM_COLS = 15
    }
}

// Clases externas
data class VideoItem(
    val title: String,
    val skipToSecond: Int,
    val imgBig: String,
    val imgSml: String,
    val videoSrc: String
)

data class ImportedJson(
    val fileName: String,
    val videos: List<VideoItem>
)

class JsonDataManager {
    private val importedJsons = mutableListOf<ImportedJson>()

    // Añade un JSON usando el nombre real del archivo
    fun addJson(context: Context, fileName: String, videos: List<VideoItem>) {
        if (importedJsons.none { it.fileName == fileName }) {
            importedJsons.add(ImportedJson(fileName, videos))
            saveData(context)
        }
    }

    fun removeJson(context: Context, fileName: String) {
        importedJsons.removeAll { it.fileName == fileName }
        saveData(context)
    }

    fun getImportedJsons(): List<ImportedJson> = importedJsons.toList()


    fun loadData(context: Context) {
        try {
            val json = context.getSharedPreferences("json_data", Context.MODE_PRIVATE)
                .getString("imported", null) ?: return

            val type = object : TypeToken<List<ImportedJson>>() {}.type
            importedJsons.clear()
            importedJsons.addAll(Gson().fromJson(json, type))
        } catch (e: Exception) {
            Log.e("JsonDataManager", "Error loading JSON data", e)
        }
    }

    fun saveData(context: Context) {
        try {
            val json = Gson().toJson(importedJsons)
            context.getSharedPreferences("json_data", Context.MODE_PRIVATE).edit()
                .putString("imported", json)
                .apply()
        } catch (e: Exception) {
            Log.e("JsonDataManager", "Error saving JSON data", e)
        }
    }
}
