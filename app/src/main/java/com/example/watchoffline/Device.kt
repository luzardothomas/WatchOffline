package com.example.watchoffline

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object Device {
    fun isTv(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode
        val isTelevision =
            (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

        val hasLeanback =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        // cualquiera de las dos señales sirve
        return isTelevision || hasLeanback
    }
}
