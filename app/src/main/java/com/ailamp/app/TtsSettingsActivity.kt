package com.ailamp.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.*
import java.text.Collator
import java.util.Locale
import kotlin.math.roundToInt

class TtsSettingsActivity : AppCompatActivity() {
    private data class Catalogue(
        val engines: List<TextToSpeech.EngineInfo> = emptyList(),
        val locales: List<Locale> = emptyList(),
        val voices: List<Voice> = emptyList(),
        val defaultEngine: String? = null,
    )

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val preferences by lazy { VoiceSettings.preferences(this) }
    private val voiceData = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        connectEngine()
    }
    private var tts: TextToSpeech? = null
    private var catalogueJob: Job? = null
    private var generation = 0
    private var ready = false
    private var catalogue = Catalogue()
    private lateinit var engineButton: Button
    private lateinit var languageButton: Button
    private lateinit var voiceButton: Button
    private lateinit var previewButton: Button
    private lateinit var dataButton: Button
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_settings)
        engineButton = findViewById(R.id.voice_engine)
        languageButton = findViewById(R.id.voice_language)
        voiceButton = findViewById(R.id.voice_selection)
        previewButton = findViewById(R.id.voice_preview)
        dataButton = findViewById(R.id.voice_data)
        status = findViewById(R.id.voice_status)
        findViewById<View>(R.id.voice_back).setOnClickListener { finish() }
        val settings = VoiceSettings.read(this)
        findViewById<SwitchCompat>(R.id.voice_enabled).apply {
            isChecked = settings.enabled
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean("tts_enabled", checked).apply()
            }
        }
        findViewById<SwitchCompat>(R.id.voice_hide_keyboard).apply {
            isChecked = preferences.getBoolean(VoiceSettings.HIDE_WEB_KEYBOARD, false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean(VoiceSettings.HIDE_WEB_KEYBOARD, checked).apply()
            }
        }
        bindSlider(R.id.voice_rate, R.id.voice_rate_label, "Скорость", "tts_speed", settings.rate, 3, 30)
        bindSlider(R.id.voice_pitch, R.id.voice_pitch_label, "Высота тона", "tts_pitch", settings.pitch, 5, 20)
        engineButton.setOnClickListener { chooseEngine() }
        languageButton.setOnClickListener { chooseLanguage() }
        voiceButton.setOnClickListener { chooseVoice() }
        previewButton.setOnClickListener { preview() }
        dataButton.setOnClickListener { openVoiceData() }
        connectEngine()
    }

    private fun connectEngine() {
        val current = ++generation
        ready = false
        catalogueJob?.cancel()
        tts?.shutdown()
        catalogue = catalogue.copy(locales = emptyList(), voices = emptyList())
        render()
        showStatus("Загружаю доступные голоса…")
        tts = TextToSpeech(this, { result ->
            window.decorView.post {
                val engine = tts
                if (current != generation || isDestroyed || engine == null) return@post
                catalogueJob = scope.launch {
                    val available = result == TextToSpeech.SUCCESS
                    val loaded = try {
                        withContext(Dispatchers.IO) {
                            val engines = engine.engines.orEmpty().sortedBy { it.label }
                            val voices = if (available) engine.voices.orEmpty().toList() else emptyList()
                            val locales = (if (available) engine.availableLanguages.orEmpty() else emptySet()) +
                                voices.map { it.locale }
                            val languages = locales.filter { it.language.isNotEmpty() }
                                .distinctBy { it.toLanguageTag() }
                                .sortedWith(compareBy(Collator.getInstance()) { it.displayName })
                            Catalogue(engines, languages, voices, engine.defaultEngine)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: RuntimeException) {
                        if (current == generation) showStatus("Не удалось получить список голосов.")
                        return@launch
                    }
                    if (current != generation) return@launch
                    catalogue = loaded
                    ready = available
                    if (ready) watchPreview(engine, current)
                    showStatus(if (ready) "" else "Движок озвучки не запустился. Выбери другой движок.")
                    render()
                }
            }
        }, VoiceSettings.read(this).engine)
    }

    private fun render() {
        val settings = VoiceSettings.read(this)
        val systemName = catalogue.engines.firstOrNull { it.name == catalogue.defaultEngine }?.label
        val engineName = catalogue.engines.firstOrNull { it.name == settings.engine }?.label
        val engineLabel = if (settings.engine == null) systemName ?: "Системный"
            else engineName ?: settings.engine
        engineButton.text = "Движок · $engineLabel"
        engineButton.isEnabled = catalogue.engines.isNotEmpty()

        val locale = Locale.forLanguageTag(settings.language)
        languageButton.text = "Язык · ${locale.displayName}"
        languageButton.isEnabled = ready && catalogue.locales.isNotEmpty()
        val availableVoices = voicesFor(settings.language)
        val voice = availableVoices.firstOrNull { it.name == settings.voice }
        voiceButton.text = "Голос · ${voice?.let(::voiceLabel) ?: "По умолчанию"}"
        voiceButton.isEnabled = ready && availableVoices.isNotEmpty()
        previewButton.isEnabled = ready
        dataButton.isEnabled = ready || catalogue.engines.isNotEmpty()
    }

    private fun chooseEngine() {
        val settings = VoiceSettings.read(this)
        val engines = catalogue.engines
        choose("Движок озвучки", listOf("Системный движок") + engines.map { it.label },
            engines.indexOfFirst { it.name == settings.engine } + 1) { index ->
            val selected = engines.getOrNull(index - 1)?.name
            if (selected != settings.engine) {
                preferences.edit().putString("tts_engine", selected).remove("tts_voice").apply()
                connectEngine()
            }
        }
    }

    private fun chooseLanguage() {
        val languages = catalogue.locales
        val settings = VoiceSettings.read(this)
        choose("Язык озвучки", languages.map { it.displayName },
            languages.indexOfFirst { it.toLanguageTag() == settings.language }) { index ->
            val selected = languages[index].toLanguageTag()
            if (selected != settings.language) {
                preferences.edit().putString("tts_lang", selected).remove("tts_voice").apply()
                render()
            }
        }
    }

    private fun chooseVoice() {
        val settings = VoiceSettings.read(this)
        val voices = voicesFor(settings.language)
        choose("Голос", listOf("По умолчанию") + voices.map(::voiceLabel),
            voices.indexOfFirst { it.name == settings.voice } + 1) { index ->
            preferences.edit().putString("tts_voice", voices.getOrNull(index - 1)?.name).apply()
            render()
        }
    }

    private fun voicesFor(language: String): List<Voice> {
        val locale = Locale.forLanguageTag(language)
        val exact = catalogue.voices.filter { it.locale == locale }
        return exact.ifEmpty { catalogue.voices.filter { it.locale.language == locale.language } }
            .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.name })
    }

    private fun voiceLabel(voice: Voice): String {
        val type = when {
            voice.isNetworkConnectionRequired -> "через интернет"
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features -> "нужно загрузить"
            else -> "на устройстве"
        }
        return "${voice.name} · $type"
    }

    private fun choose(title: String, entries: List<String>, checked: Int, select: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(entries.toTypedArray(), checked) { dialog, index ->
                dialog.dismiss()
                select(index)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun bindSlider(slider: Int, label: Int, title: String, key: String, value: Float, min: Int, max: Int) {
        val caption = findViewById<TextView>(label)
        findViewById<SeekBar>(slider).apply {
            this.max = max - min
            progress = (value * 10).roundToInt() - min
            caption.text = "$title · %.1f×".format(value)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val updated = (progress + min) / 10f
                    caption.text = "$title · %.1f×".format(updated)
                    if (fromUser) preferences.edit().putFloat(key, updated).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
    }

    private fun preview() {
        val engine = tts ?: return
        val settings = VoiceSettings.read(this)
        if (!settings.applyTo(engine)) {
            showStatus("Этот язык пока недоступен в выбранном движке.")
            return
        }
        val locale = Locale.forLanguageTag(settings.language)
        val text = when (locale.language) {
            "ru" -> "Привет! Я AI-Lamp. Так звучит этот голос."
            "en" -> "Hello! I'm AI-Lamp. This is how this voice sounds."
            "pl" -> "Cześć! Jestem AI-Lamp. Tak brzmi ten głos."
            "uk" -> "Привіт! Я AI-Lamp. Так звучить цей голос."
            "de" -> "Hallo! Ich bin AI-Lamp. So klingt diese Stimme."
            "fr" -> "Bonjour ! Je suis AI-Lamp. Voici ma voix."
            "es" -> "¡Hola! Soy AI-Lamp. Así suena esta voz."
            else -> "AI-Lamp. ${locale.getDisplayLanguage(locale)}."
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice-preview")
        showStatus(if (result == TextToSpeech.SUCCESS) "" else "Не удалось воспроизвести пример.")
    }

    private fun watchPreview(engine: TextToSpeech, current: Int) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    if (current == generation) showStatus("Голос не воспроизвёл пример. Проверь доступ к голосовым данным.")
                }
            }
        })
    }

    private fun openVoiceData() {
        val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        (VoiceSettings.read(this).engine ?: catalogue.defaultEngine)?.let(intent::setPackage)
        try {
            voiceData.launch(intent)
        } catch (_: ActivityNotFoundException) {
            showStatus("Этот движок не открывает загрузку голосов из приложения.")
        }
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        tts?.stop()
        super.onStop()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        generation++
        scope.cancel()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
