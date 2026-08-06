package com.redtermapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.redtermapp.distro.DistroInstaller

/**
 * Keeps every installed distro's /etc/resolv.conf in sync with the active
 * Android DNS servers. A long-running proot session (e.g. opencode) reads the
 * rootfs resolv.conf, which is otherwise only refreshed when a new session is
 * created — so switching wifi/cellular mid-session would leave it stale.
 */
object DnsWatcher {
    private const val TAG = "DnsWatcher"
    private var registered = false
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastKey = ""

    fun start(context: Context) {
        if (registered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh(context)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = refresh(context)
        }
        try {
            cm.registerNetworkCallback(req, cb)
            callback = cb
            registered = true
            Log.i(TAG, "Registered network callback")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
        }
    }

    private fun refresh(context: Context) {
        val dns = DnsHelper.getAndroidDnsServers(context).sorted().joinToString(",")
        if (dns.isEmpty()) return
        synchronized(this) {
            if (dns == lastKey) return
            lastKey = dns
        }
        try {
            DistroInstaller(context).refreshDnsForAll()
            Log.i(TAG, "Refreshed DNS for installed distros: $dns")
        } catch (e: Exception) {
            Log.w(TAG, "DNS refresh failed", e)
        }
    }
}
