package dev.menggu.androiddeck.data

import android.annotation.SuppressLint
import okhttp3.Call
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * HTTP clients for PC servers. Each server presents a self-signed certificate; a client only
 * accepts the exact certificate whose SHA-256 fingerprint was pinned at pairing, and adds that
 * server's pairing token to every request.
 */
object Net {
    private val tokens = ConcurrentHashMap<String, String>()
    private val pins = ConcurrentHashMap<String, String>()
    private val pinned = ConcurrentHashMap<String, OkHttpClient>()
    private val pinnedSlow = ConcurrentHashMap<String, OkHttpClient>()

    fun hostKey(host: String, port: Int) = "${host.lowercase()}:$port"

    fun register(server: SavedServer) {
        val key = hostKey(server.host, server.port)
        tokens[key] = server.token
        server.fp?.let { pins[key] = it.lowercase() }
    }

    fun unregister(host: String, port: Int) {
        val key = hostKey(host, port)
        tokens.remove(key)
        pins.remove(key)
    }

    fun baseUrl(host: String, port: Int): String {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "https://$h:$port"
    }

    /** Default trust; used for internet URLs such as album art from a streaming service. */
    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val token = tokens[hostKey(req.url.host, req.url.port)]
                if (token != null && req.header("Authorization") == null) {
                    chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
                } else {
                    chain.proceed(req)
                }
            }
            .build()
    }

    private fun OkHttpClient.Builder.trust(tm: X509TrustManager): OkHttpClient.Builder {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        // The certificate itself is pinned, so the host name is irrelevant (it is an IP anyway).
        return sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }
    }

    private fun pinnedClient(fp: String): OkHttpClient =
        pinned.getOrPut(fp) { base.newBuilder().trust(PinTrustManager(fp, null)).build() }

    /** Client for a paired PC (falls back to default trust, which rejects self-signed certs). */
    fun clientFor(host: String, port: Int): OkHttpClient {
        val fp = pins[hostKey(host, port)] ?: return base
        return pinnedClient(fp)
    }

    /** App scans on Windows can take a while on the first run. */
    fun slowClientFor(host: String, port: Int): OkHttpClient {
        val fp = pins[hostKey(host, port)] ?: return base
        return pinnedSlow.getOrPut(fp) { pinnedClient(fp).newBuilder().readTimeout(150, TimeUnit.SECONDS).build() }
    }

    /** Client that trusts only [fp], used while pairing before the server is saved. */
    fun clientPinnedTo(fp: String): OkHttpClient = pinnedClient(fp.lowercase())

    /** First contact with a manually added PC: accepts any certificate and reports its fingerprint. */
    fun probeClient(onFingerprint: (String) -> Unit): OkHttpClient =
        base.newBuilder().trust(PinTrustManager(null, onFingerprint)).build()

    /** Image loading routes each request to the right pinned client by host. */
    val callFactory: Call.Factory = Call.Factory { request ->
        clientFor(request.url.host, request.url.port).newCall(request)
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // Not a trust-all manager: it accepts exactly one certificate (pinned SHA-256), or, for the
    // first-contact probe only, records the fingerprint so the user can compare it with the PC.
    @SuppressLint("CustomX509TrustManager")
    private class PinTrustManager(
        private val expected: String?,
        private val onSeen: ((String) -> Unit)?,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are not supported")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("Empty certificate chain")
            val fp = sha256Hex(leaf.encoded)
            onSeen?.invoke(fp)
            if (expected != null && !fp.equals(expected, ignoreCase = true)) {
                throw CertificateException("Certificate does not match the paired PC")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
