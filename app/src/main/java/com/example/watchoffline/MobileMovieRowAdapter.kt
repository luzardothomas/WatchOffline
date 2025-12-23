package com.example.watchoffline

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
            // ✅ ACCIÓN: caja sólida, sin imagen
            holder.cover.setImageDrawable(null)
            holder.cover.setBackgroundColor(
                holder.itemView.context.getColor(R.color.fastlane_background)
            )

            holder.title.setTextColor(Color.WHITE)
            holder.title.textAlignment = View.TEXT_ALIGNMENT_CENTER

        } else {
            // ✅ CONTENIDO NORMAL
            holder.cover.setBackgroundColor(Color.TRANSPARENT)
            holder.title.textAlignment = View.TEXT_ALIGNMENT_TEXT_START

            Glide.with(holder.itemView)
                .load(item.cardImageUrl)
                .into(holder.cover)
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = movies.size
}
