package com.ailamp.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.*

class TtsSettingsActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var speedSlider: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var langSwitch: Switch
    private lateinit var testButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Настройки озвучки"

        tts = TextToSpeech(this, this)

        speedSlider = findViewById(R.id.speedSlider)
        speedLabel = findViewById(R.id.speedLabel)
        langSwitch = findViewById(R.id.langSwitch)
        testButton = findViewById(R.id.testButton)

        val prefs = getSharedPreferences("ai-lamp", Context.MODE_PRIVATE)

        // Скорость
        val currentSpeed = prefs.getFloat("tts_speed", 1.0f)
        speedSlider.progress = (currentSpeed * 10).toInt()
        speedLabel.text = "Скорость: %.1f".format(currentSpeed)

        speedSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                val speed = progress / 10f
                speedLabel.text = "Скорость: %.1f".format(speed)
                prefs.edit().putFloat("tts_speed", speed).apply()
                tts.setSpeechRate(speed)
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        // Язык
        val currentLang = prefs.getString("tts_lang", "ru") ?: "ru"
        langSwitch.isChecked = currentLang == "en"
        langSwitch.text = if (currentLang == "ru") "Русский" else "English"

        langSwitch.setOnCheckedChangeListener { _, isChecked ->
            val lang = if (isChecked) "en" else "ru"
            prefs.edit().putString("tts_lang", lang).apply()
            langSwitch.text = if (lang == "ru") "Русский" else "English"
            val locale = if (lang == "ru") Locale("ru") else Locale.US
            tts.setLanguage(locale)
        }

        // Тест
        testButton.setOnClickListener {
            val lang = prefs.getString("tts_lang", "ru") ?: "ru"
            val text = if (lang == "ru") "Привет, я AI-Lamp. Я готова говорить."
                       else "Hello, I am AI-Lamp. I am ready to speak."
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "test")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val prefs = getSharedPreferences("ai-lamp", Context.MODE_PRIVATE)
            val lang = prefs.getString("tts_lang", "ru") ?: "ru"
            val locale = if (lang == "ru") Locale("ru") else Locale.US
            tts.setLanguage(locale)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }
}
