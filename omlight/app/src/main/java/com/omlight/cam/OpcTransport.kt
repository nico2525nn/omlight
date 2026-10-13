package com.omlight.cam

import android.net.Network
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * OPC command transport over HTTP.
 *
 * Network isolation: when a camera [Network] is supplied, every socket is
 * created from [Network.getSocketFactory] so camera traffic stays on the
 * camera Wi-Fi while the rest of the system keeps using mobile data /
 * other networks. processDefaultNetwork is never touched.
 *
 * Retries: none here. The camera busies out (503) during captures; callers
 * own backoff since only they know if a command is idempotent.
 */
class OpcTransport(
    val host: String = Opc.HOST,
    val port: Int = Opc.PORT,
    val network: Network? = null,
    readTimeoutMs: Long = 15_000,
) : Closeable {

    @Volatile private var ua: String = Opc.UA_DEFAULT

    private val socketFactory: SocketFactory? =
        network?.socketFactory

    /**
     * Wraps [socketFactory] so every socket is explicitly bound to the
     * camera network. Factory sockets usually inherit the bind, but
     * explicit bindSocket() survives OEMs where they don't — without it
     * HTTP silently leaves via mobile data and times out.
     */
    private val boundFactory: SocketFactory? = socketFactory?.let { base ->
        val net = network ?: return@let base
        object : SocketFactory() {
            private fun bind(s: Socket): Socket {
                runCatching { net.bindSocket(s) }
                return s
            }

            override fun createSocket(): Socket = bind(base.createSocket())
            override fun createSocket(host: String, port: Int): Socket =
                bind(base.createSocket(host, port))

            override fun createSocket(
                host: String, port: Int, localHost: java.net.InetAddress, localPort: Int,
            ): Socket = bind(base.createSocket(host, port, localHost, localPort))

            override fun createSocket(host: java.net.InetAddress, port: Int): Socket =
                bind(base.createSocket(host, port))

            override fun createSocket(
                addr: java.net.InetAddress, port: Int,
                localAddr: java.net.InetAddress, localPort: Int,
            ): Socket = bind(base.createSocket(addr, port, localAddr, localPort))
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .apply {
            // Route sockets via the camera network only. DNS never runs
            // for a literal IP, so no Dns override is needed.
            if (boundFactory != null) socketFactory(boundFactory)
            connectTimeout(6, TimeUnit.SECONDS)
            readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            writeTimeout(10, TimeUnit.SECONDS)
            // No response cache: camera data is live.
            // Retry on stale pooled connections (server closed keep-alive).
            // This only retries pre-request connection failures, never
            // HTTP error statuses like the camera's 503 busy.
            retryOnConnectionFailure(true)
        }
        .build()

    /** Raw GET against `http://host:port/<cgi>?args`, body returned open. Caller closes. */
    @Throws(IOException::class)
    fun get(path: String, vararg args: Pair<String, String>): Response =
        getRange(path, -1L, *args)

    /**
     * GET with optional resume offset. [from] < 0 = whole file.
     * Returns the open response; caller checks for 206 vs 200.
     */
    @Throws(IOException::class)
    fun getRange(path: String, from: Long, vararg args: Pair<String, String>): Response {
        val url = ("http://$host:$port/$path").toHttpUrl().newBuilder()
        for ((k, v) in args) url.addQueryParameter(k, v)
        val b = Request.Builder()
            .url(url.build())
            .header("Host", host)
            .header("User-Agent", ua)
        if (from > 0) b.header("Range", "bytes=$from-")
        return exec(b.get().build())
    }

    /**
     * Bounded range fetch (bytes=from-to). For metadata peeks like EXIF
     * heads. Callers must handle 200 (full body: read + close early).
     */
    @Throws(IOException::class)
    fun getRangeTo(path: String, from: Long, to: Long): Response {
        val url = ("http://$host:$port/$path").toHttpUrl().newBuilder()
        val req = Request.Builder()
            .url(url.build())
            .header("Host", host)
            .header("User-Agent", ua)
            .header("Range", "bytes=$from-$to")
            .get()
            .build()
        return exec(req)
    }

    /** Raw POST with XML body. Caller closes the response. */
    @Throws(IOException::class)
    fun post(path: String, xml: String, vararg args: Pair<String, String>): Response {
        val url = ("http://$host:$port/$path").toHttpUrl().newBuilder()
        for ((k, v) in args) url.addQueryParameter(k, v)
        // Real cameras want text/plain for the XML payload (OSS finding).
        val body = xml.toRequestBody("text/plain;charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(url.build())
            .header("Host", host)
            .header("User-Agent", ua)
            .post(body)
            .build()
        return exec(req)
    }

    private fun exec(req: Request): Response {
        val call: Call = client.newCall(req)
        var resp = call.execute()
        // One UA fallback: OI.Share UA first (shipping bodies), spec UA
        // on handshake-only failures (400/403/404 on the first command).
        if (!resp.isSuccessful && resp.code in 400..404 && ua == Opc.UA_DEFAULT &&
            req.url.encodedPath.endsWith("get_connectmode.cgi")
        ) {
            resp.close()
            ua = Opc.UA_SPEC
            resp = client.newCall(
                req.newBuilder().header("User-Agent", ua).build()
            ).execute()
        }
        return resp
    }

    @Throws(IOException::class)
    fun getString(path: String, vararg args: Pair<String, String>): String {
        get(path, *args).use { r ->
            if (!r.isSuccessful) throw statusError(r, path)
            return r.body!!.string()
        }
    }

    /**
     * Handshake GET with UA failover. Some bodies silently drop unknown
     * UAs (zero bytes, read timeout) instead of answering 4xx — so on a
     * timeout during the FIRST command, flip the UA once and retry.
     * Returns the body string. Only for the handshake path.
     */
    @Throws(IOException::class)
    fun handshakeGet(path: String, vararg args: Pair<String, String>): String {
        try {
            return getString(path, *args)
        } catch (e: java.net.SocketTimeoutException) {
            // Flip UA and retry once.
            ua = if (ua == Opc.UA_DEFAULT) Opc.UA_SPEC else Opc.UA_DEFAULT
            return getString(path, *args)
        }
    }

    /**
     * TCP reachability probe that honors the bound network.
     * Retries: the camera's HTTP server can lag behind the Wi-Fi join
     * by seconds, and the first SYN often loses the race.
     */
    fun probe(timeoutMs: Int = 2_000, attempts: Int = 3): Boolean {
        repeat(attempts) { i ->
            try {
                val s: Socket = socketFactory?.createSocket() ?: Socket()
                // Explicit bind survives OEMs where factory sockets
                // don't stick to the requested network.
                val net = network
                if (net != null) runCatching { net.bindSocket(s) }
                s.use {
                    it.connect(InetSocketAddress(host, port), timeoutMs)
                    return true
                }
            } catch (_: IOException) {
                if (i + 1 < attempts) {
                    try {
                        Thread.sleep(500L * (i + 1))
                    } catch (_: InterruptedException) {
                        return false
                    }
                }
            }
        }
        return false
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        fun statusError(r: Response, what: String): IOException =
            IOException("$what: HTTP ${r.code} ${r.message}")
    }
}
