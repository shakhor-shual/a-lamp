package com.ailamp.app

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TextSizeSettingsActivity : AppCompatActivity() {
    private val preferences by lazy { VoiceSettings.preferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_size_settings)
        findViewById<View>(R.id.text_size_back).setOnClickListener { finish() }
        bindSize(R.id.text_size_web, R.string.text_size_web, TextSizePreference.WEB_CHAT)
        bindSize(R.id.text_size_dictation, R.string.text_size_dictation, TextSizePreference.DICTATION)
        bindSize(R.id.text_size_reply, R.string.text_size_reply, TextSizePreference.REPLY)
    }

    private fun bindSize(rowId: Int, titleId: Int, setting: TextSizePreference) {
        val row = findViewById<View>(rowId)
        val label = row.findViewById<TextView>(R.id.text_size_label)
        val preview = row.findViewById<TextView>(R.id.text_size_preview)
        val slider = row.findViewById<SeekBar>(R.id.text_size_slider)
        val title = getString(titleId)
        val webZoom = setting == TextSizePreference.WEB_CHAT

        fun render(value: Int) {
            label.text = getString(
                if (webZoom) R.string.text_size_percent else R.string.text_size_sp, title, value
            )
            preview.textSize = if (webZoom) 16f * value / 100f else value.toFloat()
        }

        val size = setting.read(preferences)
        slider.contentDescription = title
        slider.max = (setting.max - setting.min) / setting.step
        slider.progress = (size - setting.min) / setting.step
        render(size)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = setting.min + progress * setting.step
                render(value)
                if (fromUser) setting.write(preferences, value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
