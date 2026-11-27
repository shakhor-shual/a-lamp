package com.ailamp.app

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TtsClient(
    private val ttsBridge: TtsBridge,
    private val baseUrl: String = "http://127.0.0.1:8766"
) {
    companion object {
        private const val TAG = "TtsClient"
    }

    private var webSocket: WebSocket? = null
    private var chatId: String? = null
    private var attachedChatId: String? = null
    private var textBuffer = StringBuilder()
    private var currentJob: Job? = null
    @Volatile private var connectionGeneration = 0
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun attachToChat(newChatId: String) {
        if (newChatId == attachedChatId) return
        Log.d(TAG, "attachToChat: $newChatId (was: $attachedChatId")

        attachedChatId = newChatId
        textBuffer = StringBuilder()

        if (webSocket != null) {
            sendAttach(newChatId)
        } else {
            connectAndAttach(newChatId)
        }
    }

    fun stop() {
        connectionGeneration++
        currentJob?.cancel()
        currentJob = null
        webSocket?.close(1000, "stopped")
        webSocket = null
        attachedChatId = null
        chatId = null
    }

    private fun connectAndAttach(chatId: String) {
        val generation = ++connectionGeneration
        currentJob?.cancel()
        val jobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        currentJob = jobScope.launch {
            try {
                val token = fetchToken()
                if (token == null) {
                    Log.e(TAG, "Failed to fetch token")
                    return@launch
                }

                val wsUrl = "ws://127.0.0.1:8766/?token=$token"
                Log.d(TAG, "Connecting to $wsUrl")

                val wsClient = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(wsUrl)
                    .build()

                wsClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (generation != connectionGeneration) {
                            webSocket.close(1000, null)
                            return
                        }
                        Log.d(TAG, "WebSocket opened")
                        this@TtsClient.webSocket = webSocket
                        sendAttach(chatId)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (generation == connectionGeneration) handleMessage(text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "WebSocket failure", t)
                        if (generation == connectionGeneration) this@TtsClient.webSocket = null
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "WebSocket closed: $code $reason")
                        if (generation == connectionGeneration) this@TtsClient.webSocket = null
                    }
                })

            } catch (e: Exception) {
                Log.e(TAG, "connectAndAttach error", e)
            }
        }
    }

    private fun sendAttach(chatId: String) {
        val msg = JSONObject().apply {
            put("type", "attach")
            put("chat_id", chatId)
        }.toString()
        Log.d(TAG, "Sending attach: $msg")
        webSocket?.send(msg)
    }

    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            val event = json.optString("event", "")

            when (event) {
                "ready" -> {
                    chatId = json.optString("chat_id", null)
                    Log.d(TAG, "Ready, my chat_id: $chatId")
                }
                "attached" -> {
                    Log.d(TAG, "Attached to chat: ${json.optString("chat_id")}")
                }
                "delta" -> {
                    val text = json.optString("text", "")
                    if (text.isNotEmpty()) {
                        textBuffer.append(text)
                        flushSentences()
                    }
                }
                "stream_end" -> {
                    val remainder = textBuffer.toString().trim()
                    textBuffer = StringBuilder()
                    if (remainder.length >= 5) {
                        Log.d(TAG, "Stream end, speaking remainder: ${remainder.take(50)}...")
                        ttsBridge.speak(remainder)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleMessage error", e)
        }
    }

    /**
     * Ищет в буфере законченные предложения и отправляет их в TTS.
     */
    private fun flushSentences() {
        val content = textBuffer.toString()
        val sentenceEnd = findSentenceBoundary(content)
        if (sentenceEnd > 0) {
            val sentence = content.substring(0, sentenceEnd).trim()
            if (sentence.length >= 5) {
                Log.d(TAG, "Flushing sentence: ${sentence.take(50)}...")
                ttsBridge.speak(sentence)
            }
            // Оставляем остаток в буфере
            textBuffer = StringBuilder(content.substring(sentenceEnd))
        }
    }

    /**
     * Ищет границу предложения: . ! ? за которыми следует пробел или конец строки.
     * Возвращает индекс символа после знака препинания (включая пробел),
     * или -1 если граница не найдена.
     */
    private fun findSentenceBoundary(text: String): Int {
        val match = Regex("""[.!?](?:\s|$)""").find(text)
        return if (match != null) match.range.last + 1 else -1
    }

    private fun fetchToken(): String? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/webui/bootstrap")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            if (body != null) {
                val json = JSONObject(body)
                json.optString("token", null)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "fetchToken error", e)
            null
        }
    }
}
