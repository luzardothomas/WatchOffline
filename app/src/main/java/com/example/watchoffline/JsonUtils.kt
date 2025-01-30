package com.example.watchoffline

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns

object JsonUtils {

    // Guardar JSON
    fun saveJsonData(context: Context, fileName: String, jsonContent: String) {
        val sharedPreferences = context.getSharedPreferences("json_data", Context.MODE_PRIVATE)
        sharedPreferences.edit().putString(fileName, jsonContent).apply()
    }

    // Cargar todos los JSON guardados
    fun loadJsonData(context: Context): Map<String, String> {
        val sharedPreferences = context.getSharedPreferences("json_data", Context.MODE_PRIVATE)
        return sharedPreferences.all.mapValues { it.value.toString() }
    }

    // Obtener nombre del archivo desde Uri
    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var fileName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            fileName = cursor.getString(nameIndex)
        }
        return fileName
    }
}