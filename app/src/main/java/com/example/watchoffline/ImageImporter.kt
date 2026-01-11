package com.example.watchoffline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.regex.Pattern

object ImageImporter {
    private const val TAG = "ImageImporter"

    // Ahora acepta "first" para paginar resultados
    suspend fun searchImages(query: String, first: Int = 1): List<String> {
        val results = ArrayList<String>()
        try {
            val client = UnsafeOkHttpClient.getUnsafeOkHttpClient()

            // &first=$first indica a Bing desde qué resultado empezar a mostrar
            val urlStr = "https://www.bing.com/images/search?q=${query.replace(" ", "+")}+poster&qft=+filterui:aspect-tall&first=$first"

            Log.d(TAG, "Buscando Posters en Bing (Inicio: $first): $urlStr")

            val request = Request.Builder()
                .url(urlStr)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                .build()

            // Ejecutamos en un hilo de IO
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val html = response.body?.string() ?: ""
            val p = Pattern.compile("https?://[^\"'\\s,]*bing[^\"'\\s,]*/th\\?id=[^\"'\\s,]*")
            val m = p.matcher(html)

            while (m.find()) {
                val link = m.group()?.replace("\\", "")
                if (!link.isNullOrEmpty() && !results.contains(link)) {
                    if (link.length > 20) results.add(link)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error buscando: ${e.message}")
        }
        // Retornamos hasta 100 por tanda
        return results.distinct().take(100)
    }

    fun downloadAndSave(context: Context, imageUrl: String): String? {
        return try {
            val client = UnsafeOkHttpClient.getUnsafeOkHttpClient()
            val request = Request.Builder().url(imageUrl).build()
            var bitmap: Bitmap? = null
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.byteStream()?.let { stream ->
                    bitmap = BitmapFactory.decodeStream(stream)
                }
            }
            if (bitmap == null) return null
            val widthTarget = 400
            val scaledBitmap = if (bitmap!!.width > widthTarget) {
                val ratio = widthTarget.toDouble() / bitmap!!.width
                val newHeight = (bitmap!!.height * ratio).toInt()
                Bitmap.createScaledBitmap(bitmap!!, widthTarget, newHeight, true)
            } else {
                bitmap!!
            }
            val fileName = "poster_${UUID.randomUUID().toString().take(8)}.jpg"
            val file = File(context.filesDir, fileName)
            FileOutputStream(file).use { out ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            "file://${file.absolutePath}"
        } catch (e: Exception) {
            null
        }
    }
}