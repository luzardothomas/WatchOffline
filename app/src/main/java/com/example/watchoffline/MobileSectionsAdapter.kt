package com.example.watchoffline

import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

class MobileSectionsAdapter(
    private val sections: List<MobileSection>,
    private val onMovieClick: (Movie) -> Unit
) : RecyclerView.Adapter<MobileSectionsAdapter.SectionVH>() {

    // =========================
    // ✅ STATE (arriba)
    // =========================
    private var selectedVideoUrl: String? = null

    // =========================
    // ✅ PUBLIC API
    // =========================
    fun setSelectedVideoUrl(url: String?) {
        selectedVideoUrl = url
        notifyDataSetChanged()
    }

    // =========================
    // Recycler
    // =========================
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mobile_section, parent, false)
        return SectionVH(v)
    }

    override fun getItemCount(): Int = sections.size

    override fun onBindViewHolder(holder: SectionVH, position: Int) {
        holder.bind(sections[position])
    }

    // =========================
    // ViewHolder sección
    // =========================
    inner class SectionVH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val title = itemView.findViewById<TextView>(R.id.sectionTitle)
        private val rowRecycler = itemView.findViewById<RecyclerView>(R.id.sectionRowRecycler)

        fun bind(section: MobileSection) {
            title.text = section.title

            rowRecycler.layoutManager =
                LinearLayoutManager(itemView.context, RecyclerView.HORIZONTAL, false)

            // ✅ El adapter interno usa selectedVideoUrl del adapter externo (closure)
            rowRecycler.adapter = MoviesAdapter(section.items)
        }
    }

    // =========================
    // Adapter horizontal (cards)
    // =========================
    inner class MoviesAdapter(
        private val items: List<Movie>
    ) : RecyclerView.Adapter<MoviesAdapter.MovieVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MovieVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_mobile_card, parent, false)
            return MovieVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: MovieVH, position: Int) {
            holder.bind(items[position])
        }

        inner class MovieVH(itemView: View) : RecyclerView.ViewHolder(itemView) {

            private val cardRoot = itemView.findViewById<View>(R.id.cardRoot)
            private val title = itemView.findViewById<TextView>(R.id.cardTitle)
            private val cover = itemView.findViewById<ImageView>(R.id.cardCover)

            private fun isAction(movie: Movie): Boolean =
                movie.videoUrl?.startsWith("__action_") == true

            fun bind(movie: Movie) {
                title.text = movie.title

                val action = isAction(movie)
                val isSelected = (movie.videoUrl != null && movie.videoUrl == selectedVideoUrl)

                if (action) {
                    // ✅ ACCIONES: sin cover + centrado
                    Glide.with(cover).clear(cover)
                    cover.setImageDrawable(null)
                    cover.visibility = View.GONE

                    // ✅ Solo tocamos lo mínimo para acciones
                    title.apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        textAlignment = View.TEXT_ALIGNMENT_CENTER
                    }

                    // ✅ Para centrar vertical, hacemos que el TextView ocupe el resto de la card
                    title.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                } else {
                    // ✅ VIDEOS: NO TOCAR estilos del XML (gravity, alignment, height, maxLines, etc.)
                    cover.visibility = View.VISIBLE

                    // ✅ Importantísimo: revertir cualquier cosa que haya quedado “pegada” si recicló una action
                    title.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    // NO seteamos gravity ni textAlignment acá -> manda el XML

                    val url = movie.cardImageUrl?.trim().orEmpty()
                    if (url.isNotEmpty()) {
                        // ✅ Sin RoundedCorners: el recorte lo hace el XML con clipToOutline/background
                        Glide.with(cover)
                            .load(url)
                            .centerCrop()
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .into(cover)
                    } else {
                        Glide.with(cover).clear(cover)
                        cover.setImageDrawable(null)
                    }
                }

                // ✅ highlight del último reproducido
                cardRoot.alpha = if (isSelected) 1f else 0.90f
                cardRoot.scaleX = if (isSelected) 1.04f else 1f
                cardRoot.scaleY = if (isSelected) 1.04f else 1f
                cardRoot.isSelected = isSelected

                itemView.setOnClickListener { onMovieClick(movie) }
            }
        }
    }
}
