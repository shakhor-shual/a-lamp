package com.alamp.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.*

class TtsService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val TAG = "TtsService"
        const val CHANNEL_ID = "a-lamp-tts"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.alamp.app.START_TTS"
        const val ACTION_STOP = "com.alamp.app.STOP_TTS"
        const val ACTION_TOGGLE = "com.alamp.app.TOGGLE_TTS"

        var isRunning = false
            private set
        var isSpeaking = false
            private set
    }

    private lateinit var tts: TextToSpeech
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val textBuffer = StringBuilder()
    private var currentChatId: String? = null
    private var isActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }
            override fun onDone(utteranceId: String?) {
                isSpeaking = false
            }
            override fun onError(utteranceId: String?) {
                isSpeaking = false
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
            ACTION_TOGGLE -> toggle()
        }
        return START_STICKY
    }

    private fun start() {
        if (isActive) return
        isActive = true
        isRunning = true

        val notification = createNotification("🧞 A-lamp слушает")
        startForeground(NOTIFICATION_ID, notification)

        connectWebSocket()
    }

    private fun stop() {
        isActive = false
        isRunning = false
        isSpeaking = false
        webSocket?.close(1000, "Stopped")
        webSocket = null
        tts.stop()
        textBuffer.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun toggle() {
        if (isActive) stop() else start()
    }

    private fun connectWebSocket() {
        val request = Request.Builder()
            .url("ws://127.0.0.1:8765/")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                updateNotification("🧞 A-lamp подключена")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWsMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                updateNotification("🧞 Ошибка соединения")
                // Переподключаемся через 5 секунд
                scope.launch {
                    delay(5000)
                    if (isActive) connectWebSocket()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
            }
        })
    }

    private fun handleWsMessage(text: String) {
        try {
            val json = JSONObject(text)
            val event = json.optString("event")

            when (event) {
                "ready" -> {
                    // Получаем chat_id из ready-события
                    currentChatId = json.optString("chat_id")
                    Log.d(TAG, "Connected to chat: $currentChatId")
                }
                "delta" -> {
                    val deltaText = json.optString("text", "")
                    if (deltaText.isNotEmpty()) {
                        textBuffer.append(deltaText)
                        // Пробуем озвучить по предложениям
                        speakSentences()
                    }
                }
                "reasoning_delta" -> {
                    // Игнорируем мысли модели
                }
                "stream_end" -> {
                    // Озвучиваем остаток
                    if (textBuffer.isNotEmpty()) {
                        speak(textBuffer.toString())
                        textBuffer.clear()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }

    private fun speakSentences() {
        val full = textBuffer.toString()
        // Ищем конец предложения
        val sentenceEnd = Regex("[.!?]\\s").find(full)
        if (sentenceEnd != null) {
            val endPos = sentenceEnd.range.last
            val sentence = full.substring(0, endPos + 1)
            speak(sentence)
            textBuffer.delete(0, endPos + 1)
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        val prefs = getSharedPreferences("a-lamp", Context.MODE_PRIVATE)
        val speed = prefs.getFloat("tts_speed", 1.0f)
        tts.setSpeechRate(speed)

        val utteranceId = UUID.randomUUID().toString()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            tts.speak(text, TextToSpeech.QUEUE_ADD, null)
        }
        updateNotification("🧞 A-lamp говорит...")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val prefs = getSharedPreferences("a-lamp", Context.MODE_PRIVATE)
            val lang = prefs.getString("tts_lang", "ru") ?: "ru"
            val locale = if (lang == "ru") Locale("ru") else Locale.US
            tts.setLanguage(locale)
            Log.d(TAG, "TTS initialized: $lang")
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "A-lamp TTS",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления голосового ассистента"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("A-lamp")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        tts.stop()
        tts.shutdown()
        webSocket?.close(1000, "Destroy")
        super.onDestroy()
    }
}
