package com.example.watchoffline

import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import kotlin.properties.Delegates

class CardPresenter : Presenter() {

    private var mDefaultCardImage: Drawable? = null
    private var sSelectedBackgroundColor: Int by Delegates.notNull()
    private var sDefaultBackgroundColor: Int by Delegates.notNull()

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        Log.d(TAG, "onCreateViewHolder")

        sDefaultBackgroundColor = ContextCompat.getColor(parent.context, R.color.default_background)
        sSelectedBackgroundColor = ContextCompat.getColor(parent.context, R.color.selected_background)

        // ✅ drawable de fallback REAL
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

        // título sin "..."
        forceNoEllipsize(findTitleTextView(cardView))

        return Presenter.ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any) {
        val movie = item as Movie
        val cardView = viewHolder.view as ImageCardView

        val url = movie.cardImageUrl?.trim().orEmpty()
        Log.d(TAG, "onBindViewHolder title=${movie.title} url=$url skip=${movie.skipToSecond}")

        cardView.titleText = movie.title
        cardView.contentText = movie.studio
        forceNoEllipsize(findTitleTextView(cardView))

        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)

        if (url.isNotEmpty()) {
            Glide.with(viewHolder.view.context)
                .load(url)
                .centerCrop()
                .placeholder(mDefaultCardImage)
                .error(mDefaultCardImage)
                .into(cardView.mainImageView)
        } else {
            // ✅ fallback correcto a drawable
            Glide.with(viewHolder.view.context)
                .load(mDefaultCardImage)
                .centerCrop()
                .into(cardView.mainImageView)
        }
    }


    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.badgeImage = null
        cardView.mainImage = null
    }

    private fun updateCardBackgroundColor(view: ImageCardView, selected: Boolean) {
        val color = if (selected) sSelectedBackgroundColor else sDefaultBackgroundColor
        view.setBackgroundColor(color)
        view.setInfoAreaBackgroundColor(color)
    }

    // ===== title helpers =====

    private fun findTitleTextView(cardView: View): TextView? {
        val ctx = cardView.context

        var id = ctx.resources.getIdentifier("title_text", "id", "androidx.leanback")
        if (id != 0) return cardView.findViewById(id)

        id = ctx.resources.getIdentifier("title_text", "id", "android.support.v17.leanback")
        if (id != 0) return cardView.findViewById(id)

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
        }
    }

    companion object {
        private const val TAG = "CardPresenter"
        private const val CARD_WIDTH = 313
        private const val CARD_HEIGHT = 176
    }
}
