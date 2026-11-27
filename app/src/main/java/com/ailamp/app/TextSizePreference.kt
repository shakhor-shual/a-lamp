package com.ailamp.app

import android.content.SharedPreferences

internal enum class TextSizePreference(
    private val key: String,
    private val defaultSize: Int,
    val min: Int,
    val max: Int,
    val step: Int,
) {
    WEB_CHAT("web_chat_text_zoom", 100, 70, 150, 5),
    DICTATION("voice_chat_dictation_text_size", 26, 14, 44, 1),
    REPLY("voice_chat_reply_text_size", 20, 14, 44, 1);

    fun read(preferences: SharedPreferences): Int =
        preferences.getInt(key, defaultSize).coerceIn(min, max)

    fun write(preferences: SharedPreferences, size: Int) {
        preferences.edit().putInt(key, size).apply()
    }
}
