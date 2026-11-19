package com.example.spyder.robot

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log

/**
 * Joins Twist-auto and keeps the callback registered.
 * Dropping the callback makes Android tear down a local-only robot AP.
 */
class RobotWifi(context: Context) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var heldNetwork: Network? = null
        private set

    @Suppress("DEPRECATION")
    fun wifiNetworks(): List<Network> {
        return connectivity.allNetworks.filter { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    fun ssid(): String? {
        val fromActive = connectivity.activeNetwork?.let { network ->
            val info = connectivity.getNetworkCapabilities(network)?.transportInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is WifiInfo) info.ssid else null
        }
        return clean(fromActive) ?: clean(currentSsid())
    }

    fun requestTwist(
        onReady: (Network) -> Unit,
        onLost: (Network) -> Unit,
        onUnavailable: () -> Unit
    ) {
        release()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(RobotEndpoint.SSID)
            .setWpa2Passphrase(RobotEndpoint.PASSWORD)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                heldNetwork = network
                Log.i(TAG, "Twist-auto available")
                onReady(network)
            }

            override fun onLost(network: Network) {
                if (heldNetwork == network) heldNetwork = null
                Log.i(TAG, "Twist-auto lost")
                onLost(network)
            }

            override fun onUnavailable() {
                Log.i(TAG, "Twist-auto unavailable")
                onUnavailable()
            }
        }
        callback = registered
        try {
            connectivity.requestNetwork(request, registered, 45_000)
        } catch (error: SecurityException) {
            Log.i(TAG, "Wi-Fi request refused: ${error.message}")
            release()
            onUnavailable()
        }
    }

    fun release() {
        callback?.let { registered ->
            runCatching { connectivity.unregisterNetworkCallback(registered) }
        }
        callback = null
        heldNetwork = null
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(): String? = try {
        wifi.connectionInfo?.ssid
    } catch (_: SecurityException) {
        null
    }

    private fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim().trim('"')
        if (value.isBlank() || value == WifiManager.UNKNOWN_SSID || value == "<unknown ssid>") {
            return null
        }
        return value
    }

    private companion object {
        const val TAG = "Spyder"
    }
}
