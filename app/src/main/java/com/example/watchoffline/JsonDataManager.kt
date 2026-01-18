package com.example.watchoffline

import android.content.Context
import android.util.Log
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
    // Usamos ConcurrentHashMap para que sea ultra rápido buscar (exists) y seguro entre hilos
    private val importedJsons = java.util.concurrent.ConcurrentHashMap<String, ImportedJson>()

    // Carpeta donde se guardarán los archivos individuales
    private fun getImportsFolder(context: Context): java.io.File {
        val folder = java.io.File(context.filesDir, "json_imports")
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    fun getImportedJsons(): List<ImportedJson> = importedJsons.values.toList()

    fun exists(fileName: String): Boolean = importedJsons.containsKey(fileName)

    fun addJson(context: Context, fileName: String, videos: List<VideoItem>) {
        if (!importedJsons.containsKey(fileName)) {
            val newItem = ImportedJson(fileName, videos)
            importedJsons[fileName] = newItem
            saveSingleFile(context, newItem)
        }
    }

    fun upsertJson(context: Context, fileName: String, videos: List<VideoItem>) {
        val newItem = ImportedJson(fileName, videos)
        importedJsons[fileName] = newItem
        saveSingleFile(context, newItem)
    }

    fun removeJson(context: Context, fileName: String) {
        importedJsons.remove(fileName)
        java.io.File(getImportsFolder(context), fileName).delete()
    }

    fun removeAll(context: Context) {
        importedJsons.clear()
        getImportsFolder(context).listFiles()?.forEach { it.delete() }
    }

    private val gson = Gson()

    private fun saveSingleFile(context: Context, item: ImportedJson) {
        try {
            java.io.File(getImportsFolder(context), item.fileName).writeText(gson.toJson(item))
        } catch (e: Exception) {
            Log.e("JsonDataManager", "Error guardando ${item.fileName}", e)
        }
    }

    fun loadData(context: Context) {
        val folder = getImportsFolder(context)
        val files = folder.listFiles() ?: return

        importedJsons.clear()

        for (file in files) {
            try {
                val content = file.readText()
                val item = gson.fromJson(content, ImportedJson::class.java)
                importedJsons[file.name] = item
            } catch (e: Exception) {
                Log.e("JsonDataManager", "Error cargando archivo: ${file.name}")
            }
        }
    }

}