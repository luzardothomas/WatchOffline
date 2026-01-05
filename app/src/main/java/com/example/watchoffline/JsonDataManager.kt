package com.example.watchoffline

import android.content.Context
import com.google.common.reflect.TypeToken
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName


data class VideoItem(
    @SerializedName("title")
    val title: String = "",

    @SerializedName(value = "skip", alternate = ["skipToSecond"])
    val skip: Int = 0,

    @SerializedName(value = "delaySkip", alternate = ["delaySeconds"])
    val delaySkip: Int = 0,

    // ✅ compat viejo: imgSml -> cardImageUrl
    @SerializedName(value = "cardImageUrl", alternate = ["imgSml"])
    val cardImageUrl: String = "",

    // ✅ compat viejo: imgBig -> backgroundImageUrl
    @SerializedName(value = "backgroundImageUrl", alternate = ["imgBig"])
    val backgroundImageUrl: String = "",

    // ✅ compat viejo: videoSrc -> videoUrl
    @SerializedName(value = "videoUrl", alternate = ["videoSrc"])
    val videoUrl: String = ""
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

    fun upsertJson(context: Context, fileName: String, videos: List<VideoItem>) {
        importedJsons.removeAll { it.fileName == fileName }
        importedJsons.add(ImportedJson(fileName, videos))
        saveData(context)
    }

    fun exists(fileName: String): Boolean {
        return importedJsons.any { it.fileName == fileName }
    }


    private fun saveData(context: Context) {
        val json = Gson().toJson(importedJsons)
        context.getSharedPreferences("json_data", Context.MODE_PRIVATE)
            .edit()
            .putString("imported", json)
            .apply()
    }
}
