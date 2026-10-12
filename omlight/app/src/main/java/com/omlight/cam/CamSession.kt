package com.omlight.cam

import android.content.Context
import com.omlight.BuildConfig
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide camera session. The UI ([CamViewModel]) and the background
 * [DownloadService] used to build separate [OpcClient]s, which fought over
 * the camera's REC/PLAY mode and doubled handshakes. Worse, the
 * app-requested Wi-Fi (WifiNetworkSpecifier) was owned by the ViewModel,
 * so background transfers could lose the AP when the UI died.
 *
 * This holder owns exactly one transport+client+network triple, created
 * on first [acquire] and shared by everyone until [release] drops the
 * last refcount. Mode switches inside OpcClient are already serialized;
 * callers just must not cache the client across [release].
 */
object CamSession {

    private val mutex = Mutex()
    private var refs = 0
    private var net: CameraNetwork? = null
    private var transport: OpcTransport? = null
    private var client: OpcClient? = null

    /** Borrow the shared client, connecting on first use. Caller must [release]. */
    suspend fun acquire(app: Context): OpcClient {
        mutex.withLock {
            refs++
            client?.let { return it }
            val n = CameraNetwork(app.applicationContext)
            net = n
            val t = try {
                n.findCamera(BuildConfig.CAM_HOST, BuildConfig.CAM_PORT)
            } catch (e: Exception) {
                refs--
                n.close()
                net = null
                throw IOException("ネットワーク探索に失敗: ${e.message}")
            } ?: run {
                refs--
                // Keep the network handle for diagnosis; caller reads it.
                throw IOException(
                    "カメラが見つかりません (${BuildConfig.CAM_HOST})。" +
                        "カメラのWi-Fiに接続してからもう一度。"
                )
            }
            transport = t
            val c = OpcClient(t)
            try {
                c.connect()
            } catch (e: Exception) {
                refs--
                runCatching { t.close() }
                runCatching { n.close() }
                net = null
                transport = null
                throw IOException("カメラ応答なし: ${e.message}")
            }
            try {
                c.syncClock()
            } catch (_: Exception) { /* non-fatal */ }
            // Keep the link alive for long transfers (stock: 1800s).
            try {
                c.extendTimeout()
            } catch (_: Exception) { /* non-fatal */ }
            client = c
            return c
        }
    }

    /** Borrow for an app-requested AP join (Android 10+). Caller must [release]. */
    suspend fun acquireAp(app: Context, ssid: String, pass: String): OpcClient {
        mutex.withLock {
            refs++
            client?.let { return it }
            val n = CameraNetwork(app.applicationContext)
            net = n
            try {
                val t = n.requestCameraAp(ssid, pass)
                    ?: throw IOException("APへの接続をキャンセルしたか、カメラが応答しません。")
                transport = t
                val c = OpcClient(t)
                c.connect()
                c.syncClock()
                client = c
                return c
            } catch (e: Exception) {
                // connect() (or the AP join) failed: unwind everything so
                // the next acquire starts clean (no leaked ref/network).
                refs--
                runCatching { transport?.close() }
                runCatching { n.close() }
                net = null
                transport = null
                throw e
            }
        }
    }

    /** Current client without connecting (null when nobody holds the session). */
    suspend fun peek(): OpcClient? = mutex.withLock { client }

    suspend fun network(): CameraNetwork? = mutex.withLock { net }

    /**
     * Drop one refcount; tears everything down at zero. Safe to call
     * more often than [acquire] (extra releases are ignored).
     */
    suspend fun release() {
        val doClose: Triple<CameraNetwork?, OpcTransport?, OpcClient?>? = mutex.withLock {
            if (refs > 0) refs--
            if (refs == 0 && (net != null || transport != null || client != null)) {
                val t = Triple(net, transport, client)
                net = null
                transport = null
                client = null
                t
            } else null
        }
        doClose?.let { (n, t, c) ->
            runCatching { c?.idle() }
            runCatching { t?.close() }
            runCatching { n?.close() }
        }
    }

    /** Force-close regardless of refcount (disconnect button). */
    suspend fun closeNow() {
        val doClose = mutex.withLock {
            refs = 0
            val t = Triple(net, transport, client)
            net = null
            transport = null
            client = null
            t
        }
        runCatching { doClose.third?.idle() }
        runCatching { doClose.second?.close() }
        runCatching { doClose.first?.close() }
    }
}
