package com.ailamp.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class VoiceChatTransport(private val context: Context) {
    private val origin = "http://localhost:8766"
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val socketClient = client.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    suspend fun send(chatId: String?, transcript: String, onChatCreated: (String) -> Unit): String {
        val bootstrap = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$origin/webui/bootstrap").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException(context.getString(R.string.voice_gateway_unavailable))
                JSONObject(response.body?.string() ?: throw IOException(context.getString(R.string.voice_gateway_empty)))
            }
        }
        val endpoint = origin.toHttpUrl().newBuilder()
            .encodedPath(bootstrap.optString("ws_path", "/"))
            .addQueryParameter("token", bootstrap.getString("token"))
            .build()
        val request = Request.Builder().url(endpoint).build()
        val turnId = UUID.randomUUID().toString()

        return coroutineScope {
            awaitReply(request, chatId, turnId, transcript, onChatCreated)
        }
    }

    private suspend fun CoroutineScope.awaitReply(
        request: Request,
        chatId: String?,
        turnId: String,
        transcript: String,
        onChatCreated: (String) -> Unit,
    ): String = suspendCancellableCoroutine { result ->
        val finished = AtomicBoolean(false)
        val socket = socketClient.newWebSocket(request, object : WebSocketListener() {
            private var activeChatId = chatId
            private var submitted = false
            private val streams = linkedMapOf<String, StringBuilder>()
            private var finalText = ""

            private fun finish(webSocket: WebSocket, text: String = "", error: String? = null) {
                if (!finished.compareAndSet(false, true)) return
                webSocket.close(1000, null)
                if (error == null) result.resume(text)
                else result.resumeWithException(IOException(error))
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                val command = if (chatId == null) JSONObject().put("type", "new_chat")
                    else JSONObject().put("type", "attach").put("chat_id", chatId)
                if (!webSocket.send(command.toString())) {
                    val error = if (chatId == null) context.getString(R.string.voice_create_failed)
                        else context.getString(R.string.voice_attach_failed)
                    finish(webSocket, error = error)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                val kind = event.optString("event")
                val eventChat = event.optString("chat_id")
                val target = activeChatId
                if (target != null && eventChat.isNotEmpty() && eventChat != target) return
                val eventTurn = event.optString("turn_id")
                if (eventTurn.isNotEmpty() && eventTurn != turnId) return
                when (kind) {
                    "attached" -> if (!submitted && eventChat.isNotEmpty() && !finished.get()) {
                        activeChatId = eventChat
                        submitted = true
                        if (target == null) {
                            launch(Dispatchers.Main.immediate) {
                                if (!finished.get()) {
                                    onChatCreated(eventChat)
                                    submit(webSocket, eventChat)
                                }
                            }
                        } else {
                            submit(webSocket, eventChat)
                        }
                    }
                    "error" -> if (eventTurn == turnId || !submitted) {
                        finish(webSocket, error = context.getString(R.string.voice_message_rejected))
                    }
                    "delta" -> if (eventTurn == turnId) {
                        streams.getOrPut(event.optString("stream_id")) { StringBuilder() }
                            .append(event.optString("text"))
                    }
                    "stream_end" -> if (eventTurn == turnId && event.has("text")) {
                        streams[event.optString("stream_id")] = StringBuilder(event.optString("text"))
                    }
                    "message" -> if (eventTurn == turnId && event.optString("kind").isEmpty()) {
                        finalText = event.optString("text")
                    }
                    "turn_end" -> if (eventTurn == turnId) {
                        if (event.optString("outcome", "completed") != "completed") {
                            finish(webSocket, error = context.getString(R.string.voice_turn_incomplete))
                        } else {
                            finish(webSocket, finalText.ifBlank { streams.values.joinToString("\n\n") }.trim())
                        }
                    }
                }
            }

            private fun submit(webSocket: WebSocket, id: String) {
                val message = JSONObject()
                    .put("type", "message").put("chat_id", id)
                    .put("turn_id", turnId).put("content", transcript)
                    .put("webui", true)
                if (!webSocket.send(message.toString())) {
                    finish(webSocket, error = context.getString(R.string.voice_send_failed))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(webSocket, error = context.getString(R.string.voice_gateway_disconnected))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(webSocket, error = context.getString(R.string.voice_gateway_closed))
            }
        })
        result.invokeOnCancellation {
            finished.set(true)
            socket.cancel()
        }
    }
}
