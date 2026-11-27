package com.ailamp.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

internal data class VoiceSettings(
    val enabled: Boolean,
    val engine: String?,
    val language: String,
    val voice: String?,
    val rate: Float,
    val pitch: Float,
) {
    fun applyTo(tts: TextToSpeech): Boolean {
        val locale = Locale.forLanguageTag(language)
        val chosenVoice = voice?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        val supported = (chosenVoice != null && tts.setVoice(chosenVoice) == TextToSpeech.SUCCESS) ||
            tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        return supported
    }

    companion object {
        const val HIDE_WEB_KEYBOARD = "hide_web_keyboard"
        const val RECOGNITION_LANGUAGE = "stt_language"

        fun preferences(context: Context) = context.getSharedPreferences("ai-lamp", Context.MODE_PRIVATE)

        fun recognitionLanguage(context: Context): String =
            preferences(context).getString(RECOGNITION_LANGUAGE, null) ?: read(context).language

        fun read(context: Context): VoiceSettings = preferences(context).run {
            VoiceSettings(
                getBoolean("tts_enabled", true),
                getString("tts_engine", null),
                getString("tts_lang", "ru") ?: "ru",
                getString("tts_voice", null),
                getFloat("tts_speed", 1f).coerceIn(0.3f, 3f),
                getFloat("tts_pitch", 1f).coerceIn(0.5f, 2f),
            )
        }
    }
}
