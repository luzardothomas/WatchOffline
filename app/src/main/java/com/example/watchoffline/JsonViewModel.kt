package com.example.watchoffline

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.watchoffline.JsonUtils

class JsonViewModel(application: Application) : AndroidViewModel(application) {

    private val _jsonList = MutableLiveData<List<JsonItem>>()
    val jsonList: LiveData<List<JsonItem>> get() = _jsonList

    fun loadSavedJsonData() {
        val data = JsonUtils.loadJsonData(getApplication())
        val items = data.map { (fileName, content) ->
            JsonItem(fileName, content)
        }
        _jsonList.value = items
    }

    data class JsonItem(val fileName: String, val content: String)
}