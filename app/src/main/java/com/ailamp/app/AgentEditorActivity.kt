package com.ailamp.app

import android.os.Bundle
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.widget.doAfterTextChanged
import com.chaquo.python.Python
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AgentEditorActivity : AppCompatActivity() {
    private class SyntaxSpan(color: Int) : ForegroundColorSpan(color)

    companion object {
        const val SECTION = "section"
        const val CONFIGS = "configs"
        const val PROMPTS = "prompts"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var section: String
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var editor: EditText
    private lateinit var choose: ImageButton
    private lateinit var save: ImageButton
    private lateinit var revert: ImageButton
    private lateinit var restart: ImageButton
    private var highlightJob: Job? = null
    private var name: String? = null
    private var savedText = ""
    private var revision = ""
    private var hasBackup = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = intent.getStringExtra(SECTION) ?: CONFIGS
        if (section == CONFIGS) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_agent_editor)
        heading = findViewById(R.id.editor_heading)
        status = findViewById(R.id.editor_status)
        editor = findViewById(R.id.editor_text)
        choose = findViewById(R.id.editor_choose)
        save = findViewById(R.id.editor_save)
        revert = findViewById(R.id.editor_revert)
        restart = findViewById(R.id.editor_restart)
        heading.text = if (section == CONFIGS) "Конфиги" else "Промпты"
        revert.visibility = if (section == CONFIGS) View.VISIBLE else View.GONE
        restart.visibility = revert.visibility
        listOf(choose, save, revert, restart).forEach { button ->
            TooltipCompat.setTooltipText(button, button.contentDescription)
        }

        editor.doAfterTextChanged {
            updateControls()
            scheduleHighlight()
        }
        choose.setOnClickListener { confirmDiscard { chooseFile() } }
        save.setOnClickListener { write("save", editor.text.toString()) }
        revert.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Восстановить предыдущую версию?")
                .setMessage("Файл $name будет заменён сохранённой копией .bak.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Откатить") { _, _ -> write("revert") }
                .show()
        }
        restart.setOnClickListener {
            runOperation("Перезапуск шлюза…") {
                withContext(Dispatchers.IO) {
                    Python.getInstance().getModule("start_gateway")
                        .callAttr("restart_gateway", filesDir.absolutePath)
                }
                setResult(RESULT_OK)
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!busy) confirmDiscard { finish() }
            }
        })

        updateControls()
        chooseFile()
    }

    private fun scheduleHighlight() {
        highlightJob?.cancel()
        highlightJob = scope.launch {
            delay(100)
            val text = editor.text.toString()
            val tokens = withContext(Dispatchers.Default) {
                AgentSyntaxHighlighter.tokens(text, section == CONFIGS)
            }
            val editable = editor.text
            editable.getSpans(0, editable.length, SyntaxSpan::class.java).forEach(editable::removeSpan)
            tokens.forEach { token ->
                editable.setSpan(SyntaxSpan(token.color), token.start, token.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun chooseFile() = runOperation("Чтение списка…") {
        val entries = request("list").getJSONArray("files")
        val files = Array(entries.length()) { entries.getString(it) }
        if (files.isEmpty()) {
            showStatus("Файлы этого раздела пока недоступны.")
        } else {
            showStatus("")
            AlertDialog.Builder(this)
                .setTitle(if (section == CONFIGS) "Конфиги" else "Промпты")
                .setItems(files) { _, index -> open(files[index]) }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun open(file: String) = runOperation("Открытие файла…") {
        val response = request("read", file)
        name = file
        heading.text = file
        display(response)
        showStatus("")
    }

    private fun write(operation: String, text: String = "") {
        val file = name ?: return
        runOperation("Запись файла…") {
            display(request(operation, file, revision, text))
            showStatus(when {
                operation == "revert" -> "Предыдущая версия восстановлена. Перезапусти шлюз, чтобы применить её."
                section == CONFIGS -> "Сохранено. Перезапусти шлюз, чтобы применить все изменения."
                else -> "Промпт сохранён."
            })
        }
    }

    private fun display(response: JSONObject) {
        savedText = response.getString("text")
        revision = response.getString("revision")
        hasBackup = response.getBoolean("backup")
        if (editor.text.toString() != savedText) editor.setText(savedText)
    }

    private suspend fun request(operation: String, file: String = "", revision: String = "", text: String = "") =
        withContext(Dispatchers.IO) {
            val result = Python.getInstance().getModule("start_gateway").callAttr(
                "agent_editor", filesDir.absolutePath, section, operation, file, revision, text
            )
            JSONObject(result.toString()).also {
                if (it.has("error")) throw IllegalArgumentException(it.getString("error"))
            }
        }

    private fun runOperation(label: String, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        showStatus(label)
        updateControls()
        scope.launch {
            try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                val error = e.message ?: "Проверь содержимое файла."
                showStatus(error)
                AlertDialog.Builder(this@AgentEditorActivity)
                    .setTitle("Проверь содержимое файла")
                    .setMessage(error)
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                showStatus("Операция не завершилась. Проверь доступ к файлу и свободное место.")
            } finally {
                busy = false
                if (!isDestroyed) updateControls()
            }
        }
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun hasChanges() = name != null && editor.text.toString() != savedText

    private fun updateControls() {
        val fileIsOpen = name != null && !busy
        choose.isEnabled = !busy
        editor.isEnabled = fileIsOpen
        save.isEnabled = fileIsOpen && hasChanges()
        revert.isEnabled = fileIsOpen && hasBackup
        restart.isEnabled = !busy && !hasChanges()
        listOf(choose, save, revert, restart).forEach { button ->
            button.alpha = if (button.isEnabled) 1f else 0.38f
        }
    }

    private fun confirmDiscard(proceed: () -> Unit) {
        if (!hasChanges()) {
            proceed()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Отбросить несохранённые изменения?")
            .setNegativeButton("Остаться", null)
            .setPositiveButton("Отбросить") { _, _ -> proceed() }
            .show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
