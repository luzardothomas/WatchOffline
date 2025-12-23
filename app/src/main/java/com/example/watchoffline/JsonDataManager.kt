package com.example.watchoffline

import android.content.Context
import com.google.common.reflect.TypeToken
import com.google.gson.Gson

data class VideoItem(
    val title: String,
    val skipToSecond: Int,
    val imgBig: String,
    val imgSml: String,
    val videoSrc: String
)

data class ImportedJson(
    val fileName: String,
    val videos: List<VideoItem>
)

class JsonDataManager {
    private val importedJsons = mutableListOf<ImportedJson>()

    fun addJson(context: Context, fileName: String, videos: List<VideoItem>) {
        if (importedJsons.none { it.fileName == fileName }) {
            importedJsons.add(ImportedJson(fileName, videos))
            saveData(context)
        }
    }

    fun removeJson(context: Context, fileName: String) {
        importedJsons.removeAll { it.fileName == fileName }
        saveData(context)
    }

    fun removeAll(context: Context) {
        importedJsons.clear()
        saveData(context)
    }

    fun getImportedJsons(): List<ImportedJson> = importedJsons.toList()

    fun loadData(context: Context) {
        val json = context
            .getSharedPreferences("json_data", Context.MODE_PRIVATE)
            .getString("imported", null) ?: return

        val type = object : TypeToken<List<ImportedJson>>() {}.type
        importedJsons.clear()
        importedJsons.addAll(Gson().fromJson(json, type))
    }

    private fun saveData(context: Context) {
        val json = Gson().toJson(importedJsons)
        context.getSharedPreferences("json_data", Context.MODE_PRIVATE)
            .edit()
            .putString("imported", json)
            .apply()
    }
}
