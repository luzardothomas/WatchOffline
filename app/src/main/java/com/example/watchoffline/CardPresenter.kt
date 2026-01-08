package com.example.watchoffline

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.videolan.BuildConfig
import kotlin.properties.Delegates

class CardPresenter : Presenter() {

    private var mDefaultCardImage: Drawable? = null
    private var sSelectedBackgroundColor: Int by Delegates.notNull()
    private var sDefaultBackgroundColor: Int by Delegates.notNull()

    // cache interno
    private var cachedDensity = -1f
    private var cachedSizePx = 0
    private var cachedPadH = 0
    private var cachedPadV = 0

    private var overlayBg: Drawable? = null

    private class CardViewHolder(
        root: View,
        val cardView: ImageCardView,
        val titleView: TextView
    ) : Presenter.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        if (BuildConfig.DEBUG) Log.d(TAG, "onCreateViewHolder")

        val ctx = parent.context

        if (mDefaultCardImage == null) {
            sDefaultBackgroundColor = ContextCompat.getColor(ctx, R.color.default_background)
            sSelectedBackgroundColor = ContextCompat.getColor(ctx, R.color.selected_background)
            mDefaultCardImage = ContextCompat.getDrawable(ctx, R.drawable.movie)
            overlayBg = ColorDrawable(0x99000000.toInt())
        }

        ensurePxCache(ctx)
        val sizePx = cachedSizePx

        // 1. EL ROOT AHORA ES EL QUE MANDA EL FOCO
        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            clipToPadding = true
            clipChildren = true

            // PROPIEDADES CRÍTICAS PARA TV BOXES GENÉRICOS
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        val cardView = object : ImageCardView(ctx) {
            override fun setSelected(selected: Boolean) {
                updateCardBackgroundColor(this, selected)
                super.setSelected(selected)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            setMainImageDimensions(sizePx, sizePx)
            setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)

            titleText = null
            contentText = null

            // 2. EL HIJO YA NO ES FOCUSABLE (El padre lo controla)
            isFocusable = false
            isFocusableInTouchMode = false

            updateCardBackgroundColor(this, false)
        }

        val titleOverlay = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            setPadding(cachedPadH, cachedPadV, cachedPadH, cachedPadV)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.05f)
            background = overlayBg

            isFocusable = false
            isFocusableInTouchMode = false
        }

        root.addView(cardView)
        root.addView(titleOverlay)

        // 3. PUENTE DE FOCO: Cuando el root recibe foco, activamos la card visualmente
        root.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            // Esto dispara el setSelected del ImageCardView (animación + color)
            cardView.isSelected = hasFocus
        }

        return CardViewHolder(root, cardView, titleOverlay)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any) {
        val holder = viewHolder as CardViewHolder
        val movie = item as Movie

        val url = movie.cardImageUrl?.trim().orEmpty()
        val title = movie.title?.trim().orEmpty()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "bind title=$title url=${if (url.isNotEmpty()) "yes" else "no"} skip=${movie.skipToSecond}")
        }

        holder.titleView.text = title
        holder.titleView.visibility = if (title.isNotEmpty()) View.VISIBLE else View.GONE

        val ctx = holder.cardView.context
        ensurePxCache(ctx)

        val sizePx = cachedSizePx
        val model: Any? = if (url.isNotEmpty()) url else mDefaultCardImage

        Glide.with(ctx)
            .load(model)
            .override(sizePx, sizePx)
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .dontAnimate()
            .thumbnail(0.25f)
            .placeholder(mDefaultCardImage)
            .error(mDefaultCardImage)
            .into(holder.cardView.mainImageView)
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val holder = viewHolder as CardViewHolder
        try {
            Glide.with(holder.cardView.mainImageView).clear(holder.cardView.mainImageView)
        } catch (_: Exception) {}

        holder.cardView.mainImageView.setImageDrawable(null)
        holder.titleView.text = ""
        holder.titleView.visibility = View.GONE
    }

    private fun updateCardBackgroundColor(view: ImageCardView, selected: Boolean) {
        val color = if (selected) sSelectedBackgroundColor else sDefaultBackgroundColor
        view.setBackgroundColor(color)
        view.setInfoAreaBackgroundColor(color)
    }

    private fun ensurePxCache(context: Context) {
        val d = context.resources.displayMetrics.density
        if (d == cachedDensity && cachedSizePx != 0) return

        cachedDensity = d
        cachedSizePx = (SIZE_DP * d).toInt()
        cachedPadH = (PAD_H_DP * d).toInt()
        cachedPadV = (PAD_V_DP * d).toInt()
    }

    companion object {
        private const val TAG = "CardPresenter"
        private const val SIZE_DP = 180
        private const val PAD_H_DP = 10
        private const val PAD_V_DP = 8
        private const val TITLE_SP = 14f
    }
}