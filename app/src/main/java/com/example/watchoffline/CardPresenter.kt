package com.example.watchoffline

import android.graphics.drawable.Drawable
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.ViewGroup
import android.widget.TextView
import android.text.TextUtils
import android.view.View

import com.bumptech.glide.Glide
import kotlin.properties.Delegates

/**
 * A CardPresenter is used to generate Views and bind Objects to them on demand.
 * It contains an ImageCardView.
 */
class CardPresenter : Presenter() {
    private var mDefaultCardImage: Drawable? = null
    private var sSelectedBackgroundColor: Int by Delegates.notNull()
    private var sDefaultBackgroundColor: Int by Delegates.notNull()


    private fun findTitleTextView(cardView: View): TextView? {
        val ctx = cardView.context

        // androidx
        var id = ctx.resources.getIdentifier("title_text", "id", "androidx.leanback")
        if (id != 0) return cardView.findViewById(id)

        // support old
        id = ctx.resources.getIdentifier("title_text", "id", "android.support.v17.leanback")
        if (id != 0) return cardView.findViewById(id)

        // fallback: recorrer hijos y agarrar el primer TextView que parezca ser el título
        fun dfs(v: View): TextView? {
            if (v is TextView) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val r = dfs(v.getChildAt(i))
                    if (r != null) return r
                }
            }
            return null
        }
        return dfs(cardView)
    }

    private fun forceNoEllipsize(titleTv: TextView?) {
        titleTv ?: return
        titleTv.apply {
            isSingleLine = false
            setHorizontallyScrolling(false)
            maxLines = 5
            ellipsize = null
            // por si quedó algo pegado:
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        Log.d(TAG, "onCreateViewHolder")

        sDefaultBackgroundColor = ContextCompat.getColor(parent.context, R.color.default_background)
        sSelectedBackgroundColor =
            ContextCompat.getColor(parent.context, R.color.selected_background)
        mDefaultCardImage = ContextCompat.getDrawable(parent.context, R.drawable.movie)

        val cardView = object : ImageCardView(parent.context) {
            override fun setSelected(selected: Boolean) {
                updateCardBackgroundColor(this, selected)
                super.setSelected(selected)
            }
        }

        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        updateCardBackgroundColor(cardView, false)

        // ✅ Forzar título multilínea y sin "..."
        val titleId = parent.context.resources.getIdentifier("title_text", "id", "androidx.leanback")
        val titleTv = cardView.findViewById<TextView>(titleId)

        titleTv?.apply {
            isSingleLine = false
            maxLines = 5                      // subilo a 3 si querés
            ellipsize = null                  // <- esto saca el "..."
            setHorizontallyScrolling(false)
        }



        return Presenter.ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any) {
        val movie = item as Movie
        val cardView = viewHolder.view as ImageCardView

        Log.d(TAG, "onBindViewHolder")
        if (movie.cardImageUrl != null) {
            cardView.titleText = movie.title
            cardView.contentText = movie.studio

            forceNoEllipsize(findTitleTextView(cardView))

            cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
            Glide.with(viewHolder.view.context)
                .load(movie.cardImageUrl)
                .centerCrop()
                .error(mDefaultCardImage)
                .into(cardView.mainImageView)
        }
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        Log.d(TAG, "onUnbindViewHolder")
        val cardView = viewHolder.view as ImageCardView
        // Remove references to images so that the garbage collector can free up memory
        cardView.badgeImage = null
        cardView.mainImage = null
    }

    private fun updateCardBackgroundColor(view: ImageCardView, selected: Boolean) {
        val color = if (selected) sSelectedBackgroundColor else sDefaultBackgroundColor
        // Both background colors should be set because the view"s background is temporarily visible
        // during animations.
        view.setBackgroundColor(color)
        view.setInfoAreaBackgroundColor(color)
    }

    companion object {
        private val TAG = "CardPresenter"

        private val CARD_WIDTH = 313
        private val CARD_HEIGHT = 176
    }
}