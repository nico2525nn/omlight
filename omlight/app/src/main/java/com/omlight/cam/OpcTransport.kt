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
    network: Network? = null,
    readTimeoutMs: Long = 15_000,
) : Closeable {

    @Volatile private var ua: String = Opc.UA_SPEC

    private val socketFactory: SocketFactory? =
        network?.socketFactory

    private val client: OkHttpClient = OkHttpClient.Builder()
        .apply {
            // Route sockets via the camera network only. DNS never runs
            // for a literal IP, so no Dns override is needed.
            if (socketFactory != null) socketFactory(socketFactory)
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
        // One UA fallback: spec UA first, OI.Share UA on handshake-only
        // failures (400/403/404 on the very first command).
        if (!resp.isSuccessful && resp.code in 400..404 && ua == Opc.UA_SPEC &&
            req.url.encodedPath.endsWith("get_connectmode.cgi")
        ) {
            resp.close()
            ua = Opc.UA_OISHARE
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

    /** TCP reachability probe that honors the bound network. */
    fun probe(timeoutMs: Int = 2_000): Boolean {
        return try {
            val s: Socket = socketFactory?.createSocket() ?: Socket()
            s.use {
                it.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: IOException) {
            false
        }
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
