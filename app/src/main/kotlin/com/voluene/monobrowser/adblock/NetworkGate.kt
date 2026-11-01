package com.voluene.monobrowser.adblock

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

enum class NetStatus { OK, OFFLINE, WIFI_REQUIRED }

/** What the list updater needs from the platform; faked in tests. */
interface NetworkGate {
    /** [wifiOnly]: refuse metered connections (mobile data, tethering). */
    fun status(wifiOnly: Boolean): NetStatus

    /** Opens [url] on the currently active network (not a stale/default-route one). */
    fun open(url: String): HttpURLConnection

    /** Calls [onChange] when connectivity (re)appears or changes; returns a function that stops listening. */
    fun watch(onChange: () -> Unit): () -> Unit
}

class AndroidNetworkGate(context: Context) : NetworkGate {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun status(wifiOnly: Boolean): NetStatus {
        val network = cm.activeNetwork ?: return NetStatus.OFFLINE
        val caps = cm.getNetworkCapabilities(network) ?: return NetStatus.OFFLINE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetStatus.OFFLINE
        if (wifiOnly && cm.isActiveNetworkMetered) return NetStatus.WIFI_REQUIRED
        return NetStatus.OK
    }

    override fun open(url: String): HttpURLConnection {
        val target = URL(url)
        val connection = cm.activeNetwork?.openConnection(target) ?: target.openConnection()
        return connection as HttpURLConnection
    }

    override fun watch(onChange: () -> Unit): () -> Unit {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange()
        }
        val stop: () -> Unit = { runCatching { cm.unregisterNetworkCallback(callback) } }
        return try {
            cm.registerDefaultNetworkCallback(callback)
            stop
        } catch (_: RuntimeException) {
            // e.g. too many registered callbacks: the periodic recheck still works
            return {}
        }
    }
}
