package com.ailamp.app

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

internal class VoiceChatPanel(
    private val activity: AppCompatActivity,
    val view: VoiceChatGestureLayout,
    private val onClose: () -> Unit,
    private val onMicrophone: () -> Unit,
    private val stopSpeaking: () -> Unit,
    private val speak: (String, (Boolean) -> Unit) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val preferences = VoiceSettings.preferences(activity)
    private val transport = VoiceChatTransport(activity)
    private val heading = view.findViewById<TextView>(R.id.voice_chat_heading)
    private val status = view.findViewById<TextView>(R.id.voice_chat_status)
    private val recording = view.findViewById<View>(R.id.voice_chat_recording)
    private val response = view.findViewById<View>(R.id.voice_chat_reply)
    private val transcriptScroll = view.findViewById<ScrollView>(R.id.voice_chat_transcript_scroll)
    private val prompt = view.findViewById<TextView>(R.id.voice_chat_prompt)
    private val replyScroll = view.findViewById<ScrollView>(R.id.voice_chat_reply_scroll)
    private val answer = view.findViewById<TextView>(R.id.voice_chat_answer)
    private val avatar = view.findViewById<View>(R.id.voice_chat_avatar)
    private val inputWave = view.findViewById<VoiceWaveView>(R.id.voice_chat_input_wave)
    private val outputWave = view.findViewById<VoiceWaveView>(R.id.voice_chat_output_wave)
    private val microphone = view.findViewById<ImageButton>(R.id.voice_chat_mic)
    private val sendButton = view.findViewById<ImageButton>(R.id.voice_chat_send)
    private val autoSend = view.findViewById<SwitchCompat>(R.id.voice_chat_autosend)
    private val replyMode = view.findViewById<ImageButton>(R.id.voice_chat_text_mode)
    private var chatId: String? = null
    private var createdChatId: String? = null
    private var speech: SpeechRecognizer? = null
    private var turn: Job? = null
    private val phrases = mutableListOf<String>()
    private var undoNotice: Snackbar? = null
    private var partial = ""
    private var finishing = false
    private var sendAfterRecognition = false
    private var waiting = false
    private var sendWhenReady = false
    private var reply = ""
    private var showText = false
    private var playing = false
    val isOpen get() = view.isVisible

    init {
        view.findViewById<View>(R.id.voice_chat_close).setOnClickListener { onClose() }
        TooltipCompat.setTooltipText(sendButton, sendButton.contentDescription)
        autoSend.isChecked = preferences.getBoolean("voice_chat_auto_send", false)
        autoSend.setOnCheckedChangeListener { _, enabled ->
            preferences.edit().putBoolean("voice_chat_auto_send", enabled).apply()
        }
        microphone.setOnClickListener {
            if (response.isVisible) showRecording()
            if (speech == null) onMicrophone() else finishRecording()
        }
        sendButton.setOnClickListener { requestSend() }
        view.setSwipeActions(
            canSwipe = { swipe ->
                when (swipe) {
                    VoiceChatSwipe.UP -> sendButton.isEnabled
                    VoiceChatSwipe.LEFT -> recording.isVisible && phrases.isNotEmpty() && !sendAfterRecognition
                }
            },
            onSwipe = { swipe ->
                when (swipe) {
                    VoiceChatSwipe.UP -> requestSend()
                    VoiceChatSwipe.LEFT -> undoLastPhrase()
                }
            },
        )
        replyMode.setOnClickListener { setReplyMode(text = !showText, remember = true) }
    }

    fun open(chat: String?) {
        chatId = chat
        view.isVisible = true
        showRecording()
    }

    fun close(): String? {
        val newChat = createdChatId
        createdChatId = null
        chatId = null
        undoNotice?.dismiss()
        turn?.cancel()
        turn = null
        waiting = false
        sendWhenReady = false
        releaseRecognizer()
        outputWave.animatePlayback(false)
        view.isVisible = false
        return newChat
    }

    fun startListening() {
        if (!isOpen || !recording.isVisible || speech != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            showStatus(activity.getString(R.string.voice_unavailable))
            return
        }
        stopSpeaking()
        showStatus("")
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            speech = recognizer
            renderDraft()
            recognizer.setRecognitionListener(listenerFor(recognizer))
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, VoiceSettings.recognitionLanguage(activity))
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            recognizer.startListening(intent)
        } catch (_: RuntimeException) {
            releaseRecognizer()
            renderDraft()
            showStatus(activity.getString(R.string.voice_start_failed))
        }
    }

    private fun listenerFor(recognizer: SpeechRecognizer) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            if (speech == recognizer && !finishing) inputWave.showLevel((rmsdB + 2f) / 12f)
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            if (speech != recognizer) return
            finishing = true
            inputWave.showLevel(0f)
            renderDraft()
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (speech != recognizer) return
            partial = transcription(partialResults)
            renderDraft()
        }
        override fun onResults(results: Bundle?) {
            if (speech != recognizer) return
            val text = transcription(results)
            val send = sendAfterRecognition || sendWhenReady || (text.isNotEmpty() && autoSend.isChecked)
            if (text.isNotBlank()) phrases.add(text)
            releaseRecognizer()
            renderDraft()
            if (send && phrases.isNotEmpty()) sendDraft()
            else if (text.isEmpty()) showStatus(activity.getString(R.string.voice_no_match))
        }
        override fun onError(error: Int) {
            if (speech != recognizer) return
            val send = (sendAfterRecognition || sendWhenReady) && phrases.isNotEmpty()
            releaseRecognizer()
            renderDraft()
            if (send) {
                sendDraft()
                return
            }
            showStatus(when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    activity.getString(R.string.voice_no_match)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> activity.getString(R.string.voice_mic_denied)
                else -> activity.getString(R.string.voice_recognition_failed)
            })
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun transcription(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()

    private fun requestSend() {
        if (speech == null) {
            sendDraft()
        } else {
            sendAfterRecognition = true
            finishRecording()
            renderDraft()
        }
    }

    private fun undoLastPhrase() {
        if (phrases.isEmpty()) return
        undoNotice?.dismiss()
        val index = phrases.lastIndex
        val removed = phrases.removeAt(index)
        if (sendWhenReady && phrases.isEmpty()) {
            sendWhenReady = false
            showStatus("")
        }
        renderDraft()
        undoNotice = Snackbar.make(view, activity.getString(R.string.voice_phrase_removed), Snackbar.LENGTH_LONG)
            .setAction(activity.getString(R.string.undo)) {
                if (isOpen && recording.isVisible) {
                    phrases.add(index.coerceAtMost(phrases.size), removed)
                    renderDraft()
                }
            }
        undoNotice?.show()
    }

    private fun finishRecording() {
        if (finishing) return
        val recognizer = speech ?: return
        finishing = true
        inputWave.showLevel(0f)
        renderDraft()
        recognizer.stopListening()
    }

    private fun renderDraft() {
        val visibleText = if (partial.isBlank()) phrases.joinToString(" ")
            else (phrases + partial).joinToString(" ")
        if (prompt.text.toString() != visibleText) {
            prompt.text = visibleText
            transcriptScroll.post { transcriptScroll.scrollTo(0, (prompt.height - transcriptScroll.height).coerceAtLeast(0)) }
        }
        val listening = speech != null
        microphone.isEnabled = !finishing
        microphone.setImageResource(if (listening) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now)
        microphone.contentDescription = if (listening) activity.getString(R.string.voice_stop) else activity.getString(R.string.voice_start)
        sendButton.isEnabled = visibleText.isNotBlank() && !sendAfterRecognition
    }

    private fun showRecording() {
        stopSpeaking()
        undoNotice?.dismiss()
        phrases.clear()
        partial = ""
        recording.isVisible = true
        response.isVisible = false
        sendButton.isVisible = true
        replyMode.isVisible = false
        view.setBackgroundColor(ContextCompat.getColor(activity, R.color.shell_surface))
        heading.setText(R.string.voice_user)
        showStatus("")
        renderDraft()
    }

    fun showPermissionError() = showStatus(activity.getString(R.string.voice_mic_permission))

    fun pauseRecording() {
        if (speech == null) return
        releaseRecognizer()
        renderDraft()
        sendWhenReady = false
        showStatus(activity.getString(R.string.voice_stopped))
    }

    private fun sendDraft() {
        if (!isOpen) return
        val target = chatId
        if (phrases.isEmpty() || !recording.isVisible) return
        if (waiting) {
            sendWhenReady = true
            showStatus(activity.getString(R.string.voice_send_queued))
            return
        }
        sendWhenReady = false
        undoNotice?.dismiss()
        val text = phrases.joinToString(" ")
        recording.isVisible = false
        response.isVisible = true
        sendButton.isVisible = false
        replyMode.isVisible = true
        view.setBackgroundColor(ContextCompat.getColor(activity, R.color.voice_answer_surface))
        heading.setText(R.string.voice_assistant)
        waiting = true
        reply = ""
        replyScroll.scrollTo(0, 0)
        showText = preferences.getBoolean("voice_chat_text_replies", false)
        showStatus(if (target == null) activity.getString(R.string.voice_creating_chat) else activity.getString(R.string.voice_waiting))
        renderReply()
        turn = scope.launch {
            val currentTurn = coroutineContext[Job]
            try {
                reply = withTimeout(120_000) {
                    transport.send(target, text) { id ->
                        if (turn == currentTurn && isOpen) {
                            chatId = id
                            createdChatId = id
                            if (response.isVisible) showStatus(activity.getString(R.string.voice_waiting))
                        }
                    }
                }
                if (!response.isVisible) return@launch
                if (reply.isBlank()) showStatus(activity.getString(R.string.voice_empty_reply))
                else {
                    showStatus("")
                    if (!showText) speakReply()
                }
            } catch (_: TimeoutCancellationException) {
                if (response.isVisible) showStatus(activity.getString(R.string.voice_timeout))
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                if (response.isVisible) showStatus(error.message ?: activity.getString(R.string.voice_reply_failed))
            } catch (_: Exception) {
                if (response.isVisible) showStatus(activity.getString(R.string.voice_connection_failed))
            } finally {
                if (turn == coroutineContext[Job]) {
                    turn = null
                    waiting = false
                    if (response.isVisible && target == null && chatId == null) {
                        val failure = status.text.toString()
                        val draft = phrases.toList()
                        showRecording()
                        phrases.addAll(draft)
                        renderDraft()
                        showStatus(failure)
                    } else if (response.isVisible) {
                        renderReply()
                    } else if (sendWhenReady && speech == null) {
                        sendDraft()
                    }
                }
            }
        }
    }

    private fun setReplyMode(text: Boolean, remember: Boolean) {
        if (!response.isVisible || showText == text) return
        showText = text
        if (remember) preferences.edit().putBoolean("voice_chat_text_replies", text).apply()
        if (text) stopSpeaking()
        else if (reply.isNotBlank()) speakReply()
        renderReply()
    }

    private fun speakReply() {
        speak(reply) onFinished@{ success ->
            if (!isOpen || !response.isVisible || showText) return@onFinished
            if (success) showRecording()
            else {
                setReplyMode(text = true, remember = false)
                showStatus(activity.getString(R.string.voice_playback_failed))
            }
        }
    }

    private fun renderReply() {
        val visual = !showText && (waiting || reply.isNotBlank())
        avatar.isVisible = visual
        outputWave.isVisible = visual
        outputWave.animatePlayback(visual && playing)
        answer.isVisible = showText && reply.isNotBlank()
        if (answer.text.toString() != reply) answer.text = reply
        replyMode.setImageResource(
            if (showText) R.drawable.voice_reply_text else android.R.drawable.ic_lock_silent_mode_off
        )
        replyMode.contentDescription = if (showText) activity.getString(R.string.voice_switch_to_audio)
            else activity.getString(R.string.voice_switch_to_text)
        TooltipCompat.setTooltipText(replyMode, replyMode.contentDescription)
    }

    fun applyTextSizes() {
        prompt.textSize = TextSizePreference.DICTATION.read(preferences).toFloat()
        answer.textSize = TextSizePreference.REPLY.read(preferences).toFloat()
    }

    fun updateAvatarSize() {
        val size = activity.resources.getDimensionPixelSize(R.dimen.voice_avatar_size)
        avatar.updateLayoutParams {
            width = size
            height = size
        }
    }

    fun onPlaybackChanged(active: Boolean) {
        playing = active
        if (isOpen && response.isVisible) renderReply()
    }

    private fun showStatus(message: String) {
        status.text = message
        status.isVisible = message.isNotEmpty()
    }

    private fun releaseRecognizer() {
        val current = speech
        speech = null
        finishing = false
        sendAfterRecognition = false
        partial = ""
        inputWave.showLevel(0f)
        current?.destroy()
    }

    fun destroy() {
        close()
        scope.cancel()
    }
}
