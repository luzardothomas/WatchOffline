package com.example.watchoffline


import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.app.DetailsSupportFragmentBackgroundController
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DetailsOverviewRow
import androidx.leanback.widget.FullWidthDetailsOverviewRowPresenter
import androidx.leanback.widget.FullWidthDetailsOverviewSharedElementHelper
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnActionClickedListener
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.example.watchoffline.vid.PlaybackVideoFragment

/**
 * A wrapper fragment for leanback details screens.
 * It shows a detailed view of video and its metadata plus related videos.
 */
class VideoDetailsFragment : DetailsSupportFragment() {

    private var mSelectedMovie: Movie? = null

    private lateinit var mDetailsBackground: DetailsSupportFragmentBackgroundController
    private lateinit var mPresenterSelector: ClassPresenterSelector
    private lateinit var mAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate DetailsFragment")
        super.onCreate(savedInstanceState)

        mDetailsBackground = DetailsSupportFragmentBackgroundController(this)

        mSelectedMovie = activity!!.intent.getSerializableExtra(DetailsActivity.MOVIE) as Movie
        if (mSelectedMovie != null) {
            mPresenterSelector = ClassPresenterSelector()
            mAdapter = ArrayObjectAdapter(mPresenterSelector)
            setupDetailsOverviewRow()
            setupDetailsOverviewRowPresenter()
            adapter = mAdapter
            initializeBackground(mSelectedMovie)
            onItemViewClickedListener = ItemViewClickedListener()
        } else {
            val intent = Intent(activity!!, MainActivity::class.java)
            startActivity(intent)
        }
    }

    private fun initializeBackground(movie: Movie?) {
        mDetailsBackground.enableParallax()
        Glide.with(activity!!)
            .asBitmap()
            .centerCrop()
            .error(R.drawable.default_background)
            .load(movie?.backgroundImageUrl)
            .into<SimpleTarget<Bitmap>>(object : SimpleTarget<Bitmap>() {
                override fun onResourceReady(
                    bitmap: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    mDetailsBackground.coverBitmap = bitmap
                    mAdapter.notifyArrayItemRangeChanged(0, mAdapter.size())
                }
            })
    }

    private fun setupDetailsOverviewRow() {
        Log.d(TAG, "doInBackground: " + mSelectedMovie?.toString())
        val row = DetailsOverviewRow(mSelectedMovie)
        row.imageDrawable = ContextCompat.getDrawable(activity!!, R.drawable.default_background)
        val width = convertDpToPixel(activity!!, DETAIL_THUMB_WIDTH)
        val height = convertDpToPixel(activity!!, DETAIL_THUMB_HEIGHT)
        Glide.with(activity!!)
            .load(mSelectedMovie?.cardImageUrl)
            .centerCrop()
            .error(R.drawable.default_background)
            .into<SimpleTarget<Drawable>>(object : SimpleTarget<Drawable>(width, height) {
                override fun onResourceReady(
                    drawable: Drawable,
                    transition: Transition<in Drawable>?
                ) {
                    Log.d(TAG, "details overview card image url ready: " + drawable)
                    row.imageDrawable = drawable
                    mAdapter.notifyArrayItemRangeChanged(0, mAdapter.size())
                }
            })

        val actionAdapter = ArrayObjectAdapter()

        actionAdapter.add(
            Action(
                ACTION_WATCH_TRAILER,
                resources.getString(R.string.watch_trailer_1)
            )
        )
        row.actionsAdapter = actionAdapter

        mAdapter.add(row)
    }

    private fun setupDetailsOverviewRowPresenter() {
        // Configurar fondo de detalle
        val detailsPresenter = FullWidthDetailsOverviewRowPresenter(DetailsDescriptionPresenter())
        detailsPresenter.backgroundColor = ContextCompat.getColor(activity!!, R.color.selected_background)

        // Configurar elemento de transición compartida
        val sharedElementHelper = FullWidthDetailsOverviewSharedElementHelper()
        sharedElementHelper.setSharedElementEnterTransition(activity, DetailsActivity.SHARED_ELEMENT_NAME)
        detailsPresenter.setListener(sharedElementHelper)
        detailsPresenter.isParticipatingEntranceTransition = true

        detailsPresenter.onActionClickedListener = OnActionClickedListener { action ->
            if (action.id == ACTION_WATCH_TRAILER) {
                // Cargar el PlaybackVideoFragment dentro del FrameLayout
                val playbackFragment = PlaybackVideoFragment()
                val bundle = Bundle()
                bundle.putSerializable("movie", mSelectedMovie) // Usa putSerializable para pasar el objeto
                playbackFragment.arguments = bundle

                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.details_fragment, playbackFragment)
                    .addToBackStack(null)
                    .commit()
            } else {
                Toast.makeText(activity!!, action.toString(), Toast.LENGTH_SHORT).show()
            }
        }

        mPresenterSelector.addClassPresenter(DetailsOverviewRow::class.java, detailsPresenter)
    }

    private fun convertDpToPixel(context: Context, dp: Int): Int {
        val density = context.applicationContext.resources.displayMetrics.density
        return Math.round(dp.toFloat() * density)
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder?,
            item: Any?,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            if (item is Movie) {

                // ✅ playlist real desde la fila (ListRow)
                val listRow = row as? ListRow
                val adapter = listRow?.adapter

                val playlist = ArrayList<Movie>()
                if (adapter != null) {
                    for (i in 0 until adapter.size()) {
                        val obj = adapter.get(i)
                        if (obj is Movie) playlist.add(obj)
                    }
                }

                val index = playlist.indexOfFirst { it.videoUrl == item.videoUrl }
                    .let { if (it >= 0) it else 0 }

                val intent = Intent(requireActivity(), DetailsActivity::class.java).apply {

                    // ✅ MISMA KEY QUE DetailsActivity (CRÍTICO)
                    putExtra(DetailsActivity.MOVIE, item)

                    // ✅ playlist + index (usar mismas keys que DetailsActivity)
                    putExtra(DetailsActivity.EXTRA_PLAYLIST, playlist)
                    putExtra(DetailsActivity.EXTRA_INDEX, index)
                }

                val bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    requireActivity(),
                    (itemViewHolder?.view as ImageCardView).mainImageView,
                    DetailsActivity.SHARED_ELEMENT_NAME
                ).toBundle()

                startActivity(intent, bundle)
            }
        }
    }




    companion object {
        private val TAG = "VideoDetailsFragment"
        private val ACTION_WATCH_TRAILER = 1L
        private val DETAIL_THUMB_WIDTH = 274
        private val DETAIL_THUMB_HEIGHT = 274

    }
}