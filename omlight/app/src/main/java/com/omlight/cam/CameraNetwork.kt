package com.omlight.cam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Camera network ownership.
 *
 * The camera is an internet-less Wi-Fi AP. Two connection paths:
 *
 * 1. User already joined the camera AP in system settings (typical OI.Share
 *    flow): we just find that Wi-Fi [Network] and bind our sockets to it
 *    via [OpcTransport]. Other apps keep using mobile data — nothing
 *    system-wide is touched, so no other communication is blocked.
 *
 * 2. App-requested AP (Android 10+): [requestCameraAp] asks the system to
 *    join the camera SSID for this app only. The system may keep mobile
 *    data as the default route; our sockets still use the camera network
 *    explicitly.
 *
 * No `bindProcessToNetwork` — binding is per-socket inside OpcTransport,
 * so only camera traffic goes to the AP.
 */
class CameraNetwork(private val ctx: Context) : Closeable {

    private val cm = ctx.getSystemService(ConnectivityManager::class.java)

    /** Currently usable camera network, or null. */
    @Volatile var network: Network? = null
        private set

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** True when some Wi-Fi transport is up (camera AP or otherwise). */
    fun wifiUp(): Boolean {
        val nets = cm.allNetworks ?: return false
        return nets.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /**
     * Locate the Wi-Fi network whose gateway answers like a camera.
     * Probes each Wi-Fi network's socket factory against the camera host:port.
     * Returns the bound [OpcTransport], or null when no camera is reachable.
     *
     * Debug/emulator: pass the mockcam host/port; when no Wi-Fi transport
     * exists at all (plain emulator), fall back to an unbound transport so
     * 10.0.2.2 still routes via the emulator NAT.
     */
    suspend fun findCamera(host: String = Opc.HOST, port: Int = Opc.PORT): OpcTransport? =
        withContext(Dispatchers.IO) {
            val nets = cm.allNetworks?.filter {
                cm.getNetworkCapabilities(it)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }.orEmpty()
            for (n in nets) {
                val t = OpcTransport(host = host, port = port, network = n)
                if (t.probe()) {
                    network = n
                    return@withContext t
                }
                t.close()
            }
            // Fallback: unbound socket on the default route. Real devices
            // need this when the camera AP is the default network already;
            // emulators need it because their Wi-Fi is fake and traffic
            // actually leaves via the mobile-data interface.
            val t = OpcTransport(host = host, port = port)
            if (t.probe()) return@withContext t
            t.close()
            null
        }

    /**
     * Android 10+: request the app join the camera AP (shows a system
     * dialog naming this app; does not change system Wi-Fi settings).
     * Resolves with a bound transport, or null if the user declines /
     * the camera never answers.
     */
    suspend fun requestCameraAp(ssid: String, passphrase: String): OpcTransport? =
        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT < 29) return@withContext null
            if (!hasNearbyWifiPermission()) return@withContext null
            val done = CompletableDeferred<OpcTransport?>()
            val spec = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .build()
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(spec)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    network = n
                    val t = OpcTransport(network = n)
                    done.complete(if (t.probe()) t else null)
                }

                override fun onUnavailable() {
                    done.complete(null)
                }
            }
            callback = cb
            cm.requestNetwork(req, cb)
            try {
                withContext(Dispatchers.IO) { done.await() }
            } finally {
                // Keep the callback alive while connected: the system drops
                // the app network when the request is released. Callers
                // release via close().
            }
        }

    private fun hasNearbyWifiPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            // NEARBY_WIFI_DEVICES exists only on 33+; on 31/32 the AP
            // request path needs location instead.
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * Watch the bound camera network; [onLost] fires once when it drops.
     * No-op when unbound (emulator fallback) — nothing to watch.
     */
    fun watchLoss(onLost: () -> Unit) {
        val n = network ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(net: Network) {
                if (net.networkHandle == n.networkHandle) {
                    runCatching { cm.unregisterNetworkCallback(this) }
                    onLost()
                }
            }
        }
        lossCallback = cb
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm.registerNetworkCallback(req, cb) }
    }

    /** True when a VPN is active — it may hijack camera routes. Warn the user. */
    fun vpnActive(): Boolean {
        val nets = try { cm.allNetworks.orEmpty() } catch (_: Exception) { return false }
        return nets.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private var lossCallback: ConnectivityManager.NetworkCallback? = null

    override fun close() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        lossCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        lossCallback = null
        network = null
    }
}
