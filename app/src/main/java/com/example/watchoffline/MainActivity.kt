package com.example.watchoffline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat


class MainActivity : FragmentActivity() {

    private lateinit var server: BackgroundServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startServer()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, MainFragment())
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

    private fun isTvDevice(): Boolean {
        val uiMode = resources.configuration.uiMode
        val isTelevision =
            (uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
                    android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return isTelevision || hasLeanback
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

                val url = getBestServerUrl(server.listeningPort)
                Log.d("Server", "Servidor iniciado en $url")

                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Servidor activo en:\n$url",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                Log.e("Server", "Error al iniciar servidor: ${e.message}")
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

    // 🔑 LOGICA CORRECTA DE INFORME
    private fun getBestServerUrl(port: Int): String {
        val lanIp = getLanIp()
        return if (!lanIp.isNullOrBlank()) {
            "http://$lanIp:$port"
        } else {
            "http://localhost:$port"
        }
    }

    private fun getLanIp(): String? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null

        val isLan =
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        if (!isLan) return null

        getWifiIp()?.let { return it }
        return getIpFromInterfaces()
    }

    private fun getWifiIp(): String? {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ipInt = wm.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) return null

            val bytes = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(ipInt)
                .array()

            Inet4Address.getByAddress(bytes).hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun getIpFromInterfaces(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { ip ->
                    ip != "127.0.0.1" && !ip.startsWith("169.254.")
                }
        } catch (_: Exception) {
            null
        }
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
        server.stop()
        Log.d("Server", "Servidor detenido")
    }
}
