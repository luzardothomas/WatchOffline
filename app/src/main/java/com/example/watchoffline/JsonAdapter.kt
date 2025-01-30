package com.example.watchoffline


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.watchoffline.R
import com.example.watchoffline.JsonViewModel

class JsonAdapter(private val items: List<JsonViewModel.JsonItem>) :
    RecyclerView.Adapter<JsonAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvJsonContent: TextView = view.findViewById(R.id.tvJsonContent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_json, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvFileName.text = item.fileName
        holder.tvJsonContent.text = item.content
    }

    override fun getItemCount() = items.size
}