package com.example.watchoffline

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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

    // Cache interno de medidas
    private var cachedDensity = -1f
    private var cachedSizePx = 0
    private var cachedPadH = 0
    private var cachedPadV = 0

    // MODIFICADO: ViewHolder ahora guarda referencia al Contenedor (la cinta)
    private class CardViewHolder(
        root: View,
        val cardView: ImageCardView,
        val textContainer: LinearLayout,
        val metaView: TextView,
        val titleView: TextView
    ) : Presenter.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        if (BuildConfig.DEBUG) Log.d(TAG, "onCreateViewHolder")

        val ctx = parent.context

        if (mDefaultCardImage == null) {
            // Fondo oscuro para los bordes (letterbox)
            sDefaultBackgroundColor = 0xFF212121.toInt()
            sSelectedBackgroundColor = ContextCompat.getColor(ctx, R.color.selected_background)
            mDefaultCardImage = ContextCompat.getDrawable(ctx, R.drawable.movie)
        }

        ensurePxCache(ctx)
        val sizePx = cachedSizePx

        // 1. ROOT (Vuelve a ser FrameLayout)
        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            clipToPadding = true
            clipChildren = true
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        // 2. CARD VIEW (IMAGEN)
        val cardView = object : ImageCardView(ctx) {
            override fun setSelected(selected: Boolean) {
                updateCardBackgroundColor(this, selected)
                super.setSelected(selected)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            titleText = null
            contentText = null
            isFocusable = false
            isFocusableInTouchMode = false

            // Tamaño inicial temporal (se arregla en onBind)
            setMainImageDimensions(sizePx, sizePx)
            setMainImageScaleType(ImageView.ScaleType.FIT_CENTER)

            mainImageView.apply {
                background = null
                setPadding(0, 0, 0, 0)
            }

            updateCardBackgroundColor(this, false)
        }

        // 3. CONTENEDOR DE TEXTO
        val textContainer = LinearLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM // Pegado al fondo
            )
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF212121.toInt())
            setPadding(cachedPadH, cachedPadV, cachedPadH, cachedPadV)
            isFocusable = false
            isFocusableInTouchMode = false
        }

        // 4. METADATA
        val metaView = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = (2 * cachedDensity).toInt()
        }

        // 5. TÍTULO REAL
        val titleView = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        textContainer.addView(metaView)
        textContainer.addView(titleView)

        root.addView(cardView)
        root.addView(textContainer)

        root.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            cardView.isSelected = hasFocus
        }

        return CardViewHolder(root, cardView, textContainer, metaView, titleView)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any) {
        val holder = viewHolder as CardViewHolder
        val movie = item as Movie
        val url = movie.cardImageUrl?.trim().orEmpty()
        val fullTitle = movie.title?.trim().orEmpty()
        val splitIndex = fullTitle.indexOf(" - ")

        if (splitIndex != -1) {
            val metaText = fullTitle.substring(0, splitIndex)
            val titleText = fullTitle.substring(splitIndex + 3)

            holder.metaView.text = metaText
            holder.metaView.visibility = View.VISIBLE

            holder.titleView.text = titleText
        } else {
            holder.metaView.text = ""
            holder.metaView.visibility = View.GONE
            holder.titleView.text = fullTitle
        }

        holder.titleView.visibility = if (fullTitle.isNotEmpty()) View.VISIBLE else View.GONE

        val ctx = holder.cardView.context
        ensurePxCache(ctx)
        val sizePx = cachedSizePx
        val model: Any? = if (url.isNotEmpty()) url else mDefaultCardImage

        holder.textContainer.measure(
            View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val exactTextHeight = holder.textContainer.measuredHeight
        val imageAreaHeight = sizePx - exactTextHeight

        val cardParams = holder.cardView.layoutParams as FrameLayout.LayoutParams
        cardParams.height = imageAreaHeight
        holder.cardView.layoutParams = cardParams
        holder.cardView.setMainImageDimensions(sizePx, imageAreaHeight)

        Glide.with(ctx)
            .load(model)
            .fitCenter()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .dontAnimate()
            .into(holder.cardView.mainImageView)
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val holder = viewHolder as CardViewHolder
        try {
            Glide.with(holder.cardView.mainImageView).clear(holder.cardView.mainImageView)
        } catch (_: Exception) {}

        holder.cardView.mainImageView.setImageDrawable(null)
        holder.metaView.text = ""
        holder.titleView.text = ""
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
    }
}