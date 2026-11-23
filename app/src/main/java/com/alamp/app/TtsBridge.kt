package com.alamp.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.webkit.JavascriptInterface
import java.util.*

class TtsBridge(
    private val context: Context,
    private val onSpeakingChange: (Boolean) -> Unit
) : TextToSpeech.OnInitListener {

    companion object {
        const val TAG = "TtsBridge"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val queue = mutableListOf<String>()

    init {
        tts = TextToSpeech(context, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onSpeakingChange(true)
            }
            override fun onDone(utteranceId: String?) {
                onSpeakingChange(false)
                speakNext()
            }
            override fun onError(utteranceId: String?) {
                onSpeakingChange(false)
                speakNext()
            }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            val prefs = context.getSharedPreferences("a-lamp", Context.MODE_PRIVATE)
            val lang = prefs.getString("tts_lang", "ru") ?: "ru"
            val locale = if (lang == "ru") Locale("ru") else Locale.US
            tts?.setLanguage(locale)
            val speed = prefs.getFloat("tts_speed", 1.0f)
            tts?.setSpeechRate(speed)
            Log.d(TAG, "TTS ready: $lang @ $speed")
            // Озвучиваем накопленное
            flushQueue()
        } else {
            Log.e(TAG, "TTS init failed: $status")
        }
    }

    @JavascriptInterface
    fun speak(text: String) {
        val clean = cleanTextForTts(text)
        Log.d(TAG, "speak: ${clean.take(50)}...")
        synchronized(queue) {
            queue.add(clean)
        }
        if (isReady) {
            flushQueue()
        }
    }

    @JavascriptInterface
    fun setSpeed(speed: Float) {
        tts?.setSpeechRate(speed)
        context.getSharedPreferences("a-lamp", Context.MODE_PRIVATE)
            .edit().putFloat("tts_speed", speed).apply()
    }

    @JavascriptInterface
    fun setLanguage(lang: String) {
        val locale = if (lang == "ru") Locale("ru") else Locale.US
        tts?.setLanguage(locale)
        context.getSharedPreferences("a-lamp", Context.MODE_PRIVATE)
            .edit().putString("tts_lang", lang).apply()
    }

    @JavascriptInterface
    fun stop() {
        tts?.stop()
        synchronized(queue) {
            queue.clear()
        }
        onSpeakingChange(false)
    }

    @JavascriptInterface
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }

    private fun flushQueue() {
        synchronized(queue) {
            // Читаем актуальную скорость из настроек перед озвучкой
            val prefs = context.getSharedPreferences("a-lamp", Context.MODE_PRIVATE)
            val speed = prefs.getFloat("tts_speed", 1.0f)
            tts?.setSpeechRate(speed)

            while (queue.isNotEmpty()) {
                val text = queue.removeFirst()
                val ssml = toSsmlWithPauses(text)
                speakSsml(ssml)
            }
        }
    }

    private fun speakNext() {
        synchronized(queue) {
            if (queue.isNotEmpty()) {
                // Читаем актуальную скорость из настроек перед озвучкой
                val prefs = context.getSharedPreferences("a-lamp", Context.MODE_PRIVATE)
                val speed = prefs.getFloat("tts_speed", 1.0f)
                tts?.setSpeechRate(speed)

                val text = queue.removeFirst()
                val ssml = toSsmlWithPauses(text)
                speakSsml(ssml)
            }
        }
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun cleanTextForTts(text: String): String {
        var result = text
        // Markdown-ссылки: [text](url) → text
        result = result.replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
        // Markdown-форматирование: **text**, *text*, __text__, _text_, `text` → text
        result = result.replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
        result = result.replace(Regex("""\*([^*]+)\*"""), "$1")
        result = result.replace(Regex("""__([^_]+)__"""), "$1")
        result = result.replace(Regex("""_([^_]+)_"""), "$1")
        result = result.replace(Regex("""`([^`]+)`"""), "$1")
        // Markdown-заголовки: # text → text
        result = result.replace(Regex("""^#+\s+""", RegexOption.MULTILINE), "")
        // Эмодзи (Unicode категория Symbol/Other — большинство эмодзи)
        result = result.replace(Regex("""\p{So}"""), "")
        // Варианты селекторов и Zero-Width Joiner
        result = result.replace(Regex("""[\uFE00-\uFE0F\u200D]"""), "")
        return result.trim()
    }

    /**
     * Разбивает текст на абзацы и оборачивает в SSML с паузами между ними.
     */
    private fun toSsmlWithPauses(text: String): String {
        val paragraphs = text.split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return ""
        if (paragraphs.size == 1) return paragraphs[0]

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\"?>\n<speak>\n")
        for (i in paragraphs.indices) {
            if (i > 0) {
                sb.append("<break time=\"600ms\"/>\n")
            }
            sb.append(escapeXml(paragraphs[i]))
            sb.append("\n")
        }
        sb.append("</speak>")
        return sb.toString()
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun speakSsml(ssml: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            val utteranceId = UUID.randomUUID().toString()
            tts?.speak(ssml, TextToSpeech.QUEUE_ADD, null, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            tts?.speak(ssml, TextToSpeech.QUEUE_ADD, null)
        }
    }
}
