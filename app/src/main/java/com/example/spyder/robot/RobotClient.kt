package com.example.spyder.robot

import android.net.Network
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * HTTP and WebSocket pinned to the robot access point.
 * That AP has no internet, so an unbound socket would leave the phone over mobile data.
 */
class RobotClient(network: Network?) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .apply {
            if (network != null) {
                socketFactory(network.socketFactory)
                dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        return network.getAllByName(hostname).toList()
                    }
                })
            }
        }
        .build()

    private val sockets: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    fun get(path: String): String {
        val request = base(path).header("Accept", "application/json").build()
        return text(request)
    }

    fun getBytes(path: String): ByteArray {
        http.newCall(base(path).build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            return bytes
        }
    }

    fun post(path: String): String {
        val body = ByteArray(0).toRequestBody("text/plain".toMediaType())
        return text(base(path).post(body).build())
    }

    fun send(text: String): Boolean = socket?.send(text) == true

    /**
     * Opens /ws/live and asks for a frame about 5 times a second.
     * Returns false when the socket never opens, so the caller can poll instead.
     */
    suspend fun streamSocket(onOpen: () -> Unit, onText: (String) -> Unit): Boolean = coroutineScope {
        val incoming = kotlinx.coroutines.channels.Channel<String>(
            kotlinx.coroutines.channels.Channel.CONFLATED
        )
        val opened = CompletableDeferred<Boolean>()
        val created = sockets.newWebSocket(
            Request.Builder().url(RobotEndpoint.LIVE_SOCKET).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!opened.isCompleted) opened.complete(true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    incoming.trySend(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!opened.isCompleted) opened.complete(false)
                    incoming.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.i(TAG, "Live socket failed: ${t.message}")
                    if (!opened.isCompleted) opened.complete(false)
                    incoming.close()
                }
            }
        )
        val didOpen = withTimeoutOrNull(3_000) { opened.await() } == true
        if (!didOpen) {
            created.cancel()
            return@coroutineScope false
        }
        socket = created
        onOpen()
        val ticker = launch {
            while (isActive) {
                if (!created.send("g")) break
                delay(200)
            }
        }
        try {
            val first = try {
                withTimeoutOrNull(4_000) { incoming.receive() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (first == null) return@coroutineScope false
            onText(first)
            for (text in incoming) onText(text)
        } finally {
            ticker.cancel()
            created.close(1000, "bye")
            if (socket === created) socket = null
        }
        true
    }

    suspend fun pollLive(onText: (String) -> Unit) {
        while (coroutineContext.isActive) {
            val started = System.currentTimeMillis()
            onText(get(RobotEndpoint.LIVE))
            val wait = 200L - (System.currentTimeMillis() - started)
            if (wait > 0) delay(wait)
        }
    }

    fun close() {
        socket?.close(1000, "bye")
        socket = null
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
    }

    private fun base(path: String): Request.Builder {
        return Request.Builder()
            .url(RobotEndpoint.BASE + path)
            .header("User-Agent", "Spyder/1.0")
    }

    private fun text(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code} ${body.take(80)}".trim())
            }
            return body
        }
    }

    private companion object {
        const val TAG = "Spyder"
    }
}
