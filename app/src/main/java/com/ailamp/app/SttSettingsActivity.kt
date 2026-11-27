package com.ailamp.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.text.Collator
import java.util.Locale

class SttSettingsActivity : AppCompatActivity() {
    private val preferences by lazy { VoiceSettings.preferences(this) }
    private lateinit var languageButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stt_settings)
        findViewById<View>(R.id.stt_back).setOnClickListener { finish() }
        findViewById<SwitchCompat>(R.id.voice_hide_keyboard).apply {
            isChecked = preferences.getBoolean(VoiceSettings.HIDE_WEB_KEYBOARD, false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean(VoiceSettings.HIDE_WEB_KEYBOARD, checked).apply()
            }
        }
        languageButton = findViewById(R.id.voice_recognition_language)
        updateRecognitionLanguage()
        languageButton.setOnClickListener { chooseRecognitionLanguage() }
        findViewById<View>(R.id.open_voice_chat).setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun updateRecognitionLanguage() {
        val locale = Locale.forLanguageTag(VoiceSettings.recognitionLanguage(this))
        val name = locale.getDisplayName(resources.configuration.locales[0])
        languageButton.text = getString(R.string.tts_value_label, getString(R.string.stt_language), name)
    }

    private fun chooseRecognitionLanguage() {
        val displayLocale = resources.configuration.locales[0]
        val locales = Locale.getISOLanguages().map(Locale::forLanguageTag)
            .sortedWith(compareBy(Collator.getInstance(displayLocale)) { it.getDisplayLanguage(displayLocale) })
        val selected = preferences.getString(VoiceSettings.RECOGNITION_LANGUAGE, null)
        val choices = listOf(getString(R.string.stt_same_as_tts)) + locales.map { it.getDisplayLanguage(displayLocale) }

        AlertDialog.Builder(this)
            .setTitle(R.string.stt_language)
            .setSingleChoiceItems(choices.toTypedArray(), locales.indexOfFirst { it.toLanguageTag() == selected } + 1) { dialog, index ->
                preferences.edit().putString(
                    VoiceSettings.RECOGNITION_LANGUAGE, locales.getOrNull(index - 1)?.toLanguageTag()
                ).apply()
                updateRecognitionLanguage()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
