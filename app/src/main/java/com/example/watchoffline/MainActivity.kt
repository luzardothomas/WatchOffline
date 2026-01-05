package com.example.watchoffline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var server: BackgroundServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startServer()

        if (savedInstanceState == null) {
            val fragment = if (isTvDevice()) {
                MainFragment()          // TV / TV Box
            } else {
                MobileMainFragment()    // Mobile (touch)
            }

            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, fragment)
                .commitNow()
        }

        if (!isTvDevice()) {
            enterImmersiveMode()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isTvDevice()) {
            enterImmersiveMode()
        }
    }

    /**
     * Detección robusta de TV:
     * - Google TV / Android TV
     * - TV Boxes no Google (DPAD + sin touch)
     */
    private fun isTvDevice(): Boolean {
        val cfg = resources.configuration
        val pm = packageManager

        val uiModeType =
            cfg.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
        val isTelevision =
            uiModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        val hasLeanback =
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                    pm.hasSystemFeature("android.software.leanback")

        val hasTelevisionFeature =
            pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)

        val noTouch =
            cfg.touchscreen == android.content.res.Configuration.TOUCHSCREEN_NOTOUCH
        val dpad =
            cfg.navigation == android.content.res.Configuration.NAVIGATION_DPAD

        return isTelevision || hasLeanback || hasTelevisionFeature || (noTouch && dpad)
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun startServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                server = BackgroundServer()
                server.start()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Error al iniciar servidor: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        checkPermissions()
    }

    private fun getBestServerUrl(port: Int): String {
        return "http://localhost:$port"
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .apply { data = Uri.parse("package:$packageName") }
                startActivity(intent)
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    101
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { server.stop() } catch (_: Exception) {}
    }
}
