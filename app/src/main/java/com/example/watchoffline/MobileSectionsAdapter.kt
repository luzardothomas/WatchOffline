package com.example.watchoffline

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MobileSectionsAdapter(
    private val sections: List<MobileSection>,
    private val onMovieClick: (Movie) -> Unit
) : RecyclerView.Adapter<MobileSectionsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.sectionTitle)
        val row: RecyclerView = v.findViewById(R.id.sectionRowRecycler)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mobile_section, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val section = sections[position]
        holder.title.text = section.title

        holder.row.layoutManager =
            LinearLayoutManager(holder.itemView.context, RecyclerView.HORIZONTAL, false)

        holder.row.setHasFixedSize(true)
        holder.row.isNestedScrollingEnabled = false

        holder.row.adapter = MobileMovieRowAdapter(section.items, onMovieClick)
    }

    override fun getItemCount(): Int = sections.size
}
