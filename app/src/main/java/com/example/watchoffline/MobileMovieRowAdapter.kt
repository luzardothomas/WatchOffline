package com.example.watchoffline

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide


class MobileMovieRowAdapter(
    private val movies: List<Movie>,
    private val onClick: (Movie) -> Unit
) : RecyclerView.Adapter<MobileMovieRowAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cardCover)
        val title: TextView = v.findViewById(R.id.cardTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mobile_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = movies[position]
        val isAction = item.videoUrl?.startsWith("__action_") == true

        holder.title.text = item.title

        if (isAction) {
            // ✅ ACCIÓN: caja tipo botón (solo texto)
            holder.cover.visibility = View.GONE

            holder.title.apply {
                text = item.title
                setTextColor(Color.WHITE)
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                gravity = Gravity.CENTER
                maxLines = 3
            }

            // centrar vertical + horizontal el contenido
            (holder.itemView as LinearLayout).gravity = Gravity.CENTER

        } else {
            // ✅ CONTENIDO NORMAL
            holder.cover.visibility = View.VISIBLE

            // ✅ NO uses setBackgroundColor(TRANSPARENT) porque rompe el outline redondeado
            holder.cover.setBackgroundResource(R.drawable.bg_action_card)

            // recorte + borde redondeado usando el BACKGROUND (shape con corners)
            holder.cover.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.cover.clipToOutline = true
            holder.cover.outlineProvider = ViewOutlineProvider.BACKGROUND
            holder.cover.invalidateOutline()

            // alinear título
            holder.title.apply {
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                gravity = Gravity.CENTER_HORIZONTAL
            }

            Glide.with(holder.itemView)
                .load(item.cardImageUrl)
                .centerCrop()
                .dontAnimate()
                .into(holder.cover)
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }


    override fun getItemCount(): Int = movies.size
}
