package com.ailamp.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.webkit.JavascriptInterface
import java.util.*

class TtsBridge(
    private val context: Context,
    private val onSpeakingChange: (Boolean) -> Unit
) {
    private data class Speech(val text: String, val onFinished: ((Boolean) -> Unit)? = null)
    private data class Playback(val pending: MutableSet<String>, val onFinished: (Boolean) -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val preferences = VoiceSettings.preferences(context)
    private var settings = VoiceSettings.read(context)
    private var tts: TextToSpeech? = null
    private var generation = 0
    private var ready = false
    private var engineFailed = false
    private var destroyed = false
    private var voiceChat = false
    @Volatile private var speaking = false
    private val queue = ArrayDeque<Speech>()
    private var playback: Playback? = null

    init {
        startEngine()
    }

    private fun startEngine() {
        val current = ++generation
        ready = false
        engineFailed = false
        stopSpeaking(clearPending = false)
        tts?.shutdown()
        val engine = TextToSpeech(context, { result ->
            main.post {
                if (current != generation || destroyed) return@post
                ready = result == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.let(settings::applyTo)
                    flushQueue()
                } else {
                    engineFailed = true
                    val pending = queue.toList()
                    queue.clear()
                    Log.w("TtsBridge", "TTS initialization failed ($result)")
                    pending.forEach { it.onFinished?.invoke(false) }
                }
            }
        }, settings.engine)
        tts = engine
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = updateSpeaking(true)
            override fun onDone(utteranceId: String?) = finishUtterance(utteranceId, true)
            override fun onError(utteranceId: String?) = finishUtterance(utteranceId, false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finishUtterance(utteranceId, false)

            private fun finishUtterance(id: String?, success: Boolean) {
                main.post {
                    if (current != generation || destroyed) return@post
                    val currentPlayback = playback
                    if (id != null && currentPlayback?.pending?.remove(id) == true &&
                        (!success || currentPlayback.pending.isEmpty())
                    ) {
                        playback = null
                        currentPlayback.onFinished(success)
                    }
                    setSpeaking((settings.enabled || voiceChat) && tts?.isSpeaking == true)
                }
            }

            private fun updateSpeaking(active: Boolean) {
                main.post {
                    if (current == generation && !destroyed) {
                        setSpeaking((settings.enabled || voiceChat) && (active || tts?.isSpeaking == true))
                    }
                }
            }
        })
    }

    fun reloadSettings() {
        val updated = VoiceSettings.read(context)
        if (settings == updated || destroyed) return
        val engineChanged = settings.engine != updated.engine
        settings = updated
        if (!settings.enabled && !voiceChat) stopSpeaking()
        if (engineChanged) {
            startEngine()
        } else if (ready) {
            tts?.let(settings::applyTo)
        }
    }

    fun setVoiceChat(active: Boolean) {
        voiceChat = active
        stopSpeaking()
    }

    fun speakVoiceReply(text: String, onFinished: (Boolean) -> Unit) {
        val clean = cleanTextForTts(text)
        if (!voiceChat || destroyed || engineFailed || clean.isBlank()) {
            onFinished(false)
            return
        }
        queue.addLast(Speech(clean, onFinished))
        flushQueue()
    }

    @JavascriptInterface
    fun speak(text: String) {
        val clean = cleanTextForTts(text)
        if (clean.isBlank()) return
        main.post {
            if (!destroyed && (settings.enabled || voiceChat)) {
                if (engineFailed) return@post
                queue.addLast(Speech(clean))
                flushQueue()
            }
        }
    }

    @JavascriptInterface
    fun setSpeed(speed: Float) {
        if (!speed.isFinite()) return
        main.post {
            preferences.edit().putFloat("tts_speed", speed.coerceIn(0.3f, 3f)).apply()
            reloadSettings()
        }
    }

    @JavascriptInterface
    fun setLanguage(lang: String) {
        val language = Locale.forLanguageTag(lang).toLanguageTag()
        if (language == "und") return
        main.post {
            if (settings.language != language) {
                preferences.edit().putString("tts_lang", language).remove("tts_voice").apply()
                reloadSettings()
            }
        }
    }

    @JavascriptInterface
    fun stop() {
        if (Looper.myLooper() == Looper.getMainLooper()) stopSpeaking()
        else main.post { stopSpeaking() }
    }

    @JavascriptInterface
    fun isSpeaking(): Boolean = speaking

    private fun setSpeaking(active: Boolean) {
        if (speaking != active) {
            speaking = active
            onSpeakingChange(active)
        }
    }

    private fun stopSpeaking(clearPending: Boolean = true) {
        playback = null
        tts?.stop()
        if (clearPending) queue.clear()
        setSpeaking(false)
    }

    private fun flushQueue() {
        val engine = tts ?: return
        if (!ready || !(settings.enabled || voiceChat)) return
        while (queue.isNotEmpty()) {
            val speech = queue.removeFirst()
            val pending = mutableSetOf<String>()
            val paragraphs = speech.text.split(Regex("""\n\s*\n"""))
            var failed = false
            paragraphs.filter { it.isNotBlank() }.forEachIndexed { index, paragraph ->
                if (index > 0) {
                    engine.playSilentUtterance(600, TextToSpeech.QUEUE_ADD, UUID.randomUUID().toString())
                }
                for (chunk in paragraph.chunked(TextToSpeech.getMaxSpeechInputLength())) {
                    val id = UUID.randomUUID().toString()
                    if (engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS) {
                        if (speech.onFinished != null) pending.add(id)
                    } else {
                        failed = true
                    }
                }
            }
            speech.onFinished?.let { completion ->
                if (failed || pending.isEmpty()) completion(false)
                else playback = Playback(pending, completion)
            }
        }
    }

    fun destroy() {
        destroyed = true
        stopSpeaking()
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
}
