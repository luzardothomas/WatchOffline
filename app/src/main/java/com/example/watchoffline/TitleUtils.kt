package com.example.watchoffline

import java.util.Locale

fun prettyTitle(raw: String): String {
    var s = raw.trim()

    if (s.lowercase(Locale.getDefault()).endsWith(".json")) {
        s = s.dropLast(5)
    }

    s = s.replace("_", " ")
    s = s.replace(Regex("\\s+"), " ").trim()

    return s.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault())
        else it.toString()
    }
}
