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
        bindSlider(R.id.voice_rate, R.id.voice_rate_label, getString(R.string.tts_speed), "tts_speed", settings.rate, 3, 30)
        bindSlider(R.id.voice_pitch, R.id.voice_pitch_label, getString(R.string.tts_pitch), "tts_pitch", settings.pitch, 5, 20)
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
        showStatus(getString(R.string.tts_loading))
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
                                .sortedWith(compareBy(Collator.getInstance(displayLocale)) { it.getDisplayName(displayLocale) })
                            Catalogue(engines, languages, voices, engine.defaultEngine)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: RuntimeException) {
                        if (current == generation) showStatus(getString(R.string.tts_list_failed))
                        return@launch
                    }
                    if (current != generation) return@launch
                    catalogue = loaded
                    ready = available
                    if (ready) watchPreview(engine, current)
                    showStatus(if (ready) "" else getString(R.string.tts_engine_failed))
                    render()
                }
            }
        }, VoiceSettings.read(this).engine)
    }

    private val displayLocale get() = resources.configuration.locales[0]

    private fun localizedLabel(label: Int, value: String) = localizedLabel(getString(label), value)

    private fun localizedLabel(label: String, value: String) = getString(R.string.tts_value_label, label, value)

    private fun render() {
        val settings = VoiceSettings.read(this)
        val systemName = catalogue.engines.firstOrNull { it.name == catalogue.defaultEngine }?.label
        val engineName = catalogue.engines.firstOrNull { it.name == settings.engine }?.label
        val engineLabel = if (settings.engine == null) systemName ?: getString(R.string.tts_system)
            else engineName ?: settings.engine
        engineButton.text = localizedLabel(R.string.tts_engine, engineLabel)
        engineButton.isEnabled = catalogue.engines.isNotEmpty()

        val locale = Locale.forLanguageTag(settings.language)
        languageButton.text = localizedLabel(R.string.tts_language, locale.getDisplayName(displayLocale))
        languageButton.isEnabled = ready && catalogue.locales.isNotEmpty()
        val availableVoices = voicesFor(settings.language)
        val voice = availableVoices.firstOrNull { it.name == settings.voice }
        voiceButton.text = localizedLabel(R.string.tts_voice, voice?.let(::voiceLabel) ?: getString(R.string.tts_default))
        voiceButton.isEnabled = ready && availableVoices.isNotEmpty()
        previewButton.isEnabled = ready
        dataButton.isEnabled = ready || catalogue.engines.isNotEmpty()
    }

    private fun chooseEngine() {
        val settings = VoiceSettings.read(this)
        val engines = catalogue.engines
        choose(getString(R.string.tts_engine_title), listOf(getString(R.string.tts_system_engine)) + engines.map { it.label },
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
        choose(getString(R.string.tts_language_title), languages.map { it.getDisplayName(displayLocale) },
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
        choose(getString(R.string.tts_voice), listOf(getString(R.string.tts_default)) + voices.map(::voiceLabel),
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
            voice.isNetworkConnectionRequired -> getString(R.string.tts_network)
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features -> getString(R.string.tts_needs_download)
            else -> getString(R.string.tts_on_device)
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
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun bindSlider(slider: Int, label: Int, title: String, key: String, value: Float, min: Int, max: Int) {
        val caption = findViewById<TextView>(label)
        findViewById<SeekBar>(slider).apply {
            this.max = max - min
            progress = (value * 10).roundToInt() - min
            caption.text = localizedLabel(title, String.format(displayLocale, "%.1f×", value))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val updated = (progress + min) / 10f
                    caption.text = localizedLabel(title, String.format(displayLocale, "%.1f×", updated))
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
            showStatus(getString(R.string.tts_unavailable_language))
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
        showStatus(if (result == TextToSpeech.SUCCESS) "" else getString(R.string.tts_preview_failed))
    }

    private fun watchPreview(engine: TextToSpeech, current: Int) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    if (current == generation) showStatus(getString(R.string.tts_preview_data_failed))
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
            showStatus(getString(R.string.tts_download_unavailable))
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
