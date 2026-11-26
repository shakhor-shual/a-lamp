package com.ailamp.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import android.webkit.WebViewClient
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private data class ExportProgress(val label: String, val percent: Int? = null)

    class ExportStageReporter {
        fun accept(stage: Int) {
            val label = when (stage) {
                1 -> "1 из 4 · Остановка шлюза…"
                2 -> "2 из 4 · Снимок базы и файлов агента…"
                3 -> "3 из 4 · Упаковка ZIP…"
                5 -> "3 из 4 · Проверка базы SQLite…"
                6 -> "4 из 4 · Замена данных и перезапуск шлюза…"
                else -> return
            }
            exportProgress.value = ExportProgress(label)
        }
    }

    companion object {
        private val exportInProgress = AtomicBoolean(false)
        private val exportProgress = MutableStateFlow<ExportProgress?>(null)
    }

    private val exportDestination = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) exportAgent(uri)
    }

    private val importSource = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            AlertDialog.Builder(this)
                .setTitle("Импорт агента")
                .setMessage("Архив полностью заменит текущую историю, настройки, промпты и память агента. Продолжить?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Заменить данные") { _, _ -> importAgent(uri) }
                .show()
        }
    }

    private lateinit var webView: WebView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var tvStatus: TextView
    private var ttsBridge: TtsBridge? = null
    private var ttsClient: TtsClient? = null
    private var ttsEnabled = true
    private var lastChatId: String? = null
    private var gatewayCheckJob: Job? = null
    private var exportProgressJob: Job? = null
    private val AUDIO_PERMISSION_CODE = 1001
    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        drawerLayout = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)
        tvStatus = findViewById(R.id.tv_status)
        webView = findViewById(R.id.webview)
        val exportPanel = findViewById<View>(R.id.export_progress_panel)
        val exportLabel = findViewById<TextView>(R.id.export_progress_label)
        val exportBar = findViewById<ProgressBar>(R.id.export_progress_bar)
        exportProgressJob = CoroutineScope(Dispatchers.Main).launch {
            exportProgress.collect { progress ->
                exportPanel.visibility = if (progress == null) View.GONE else View.VISIBLE
                if (progress != null) {
                    exportLabel.text = progress.label
                    exportBar.isIndeterminate = progress.percent == null
                    progress.percent?.let { exportBar.progress = it }
                }
            }
        }

        setStatus("🔄 Запуск...")

        navView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(GravityCompat.START)
            handleMenuItem(item)
            true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        setupWebView()
        requestAudioPermission()
        startNanobotGateway()
        waitForGateway()
    }

    private fun handleMenuItem(item: MenuItem) {
        when (item.itemId) {
            R.id.action_tts -> toggleTts()
            R.id.action_tts_settings -> startActivity(Intent(this, TtsSettingsActivity::class.java))
            R.id.action_files -> Toast.makeText(this, "Файлы — скоро", Toast.LENGTH_SHORT).show()
            R.id.action_import -> chooseExportDestination()
            R.id.action_restore_agent -> {
                if (exportInProgress.get()) {
                    Toast.makeText(this, "Импорт или экспорт уже выполняется", Toast.LENGTH_SHORT).show()
                } else {
                    importSource.launch(arrayOf("application/zip", "application/octet-stream"))
                }
            }
            R.id.action_diagnostics -> Toast.makeText(this, "Диагностика — скоро", Toast.LENGTH_SHORT).show()
            R.id.action_font_size -> showFontSizeDialog()
        }
    }

    private fun importAgent(source: Uri) {
        if (!exportInProgress.compareAndSet(false, true)) return
        val context = applicationContext
        exportProgress.value = ExportProgress("1 из 4 · Чтение архива…")
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    val archive = File.createTempFile("alamp-import-", ".zip", context.cacheDir)
                    try {
                        val input = context.contentResolver.openInputStream(source)
                            ?: throw IOException("Не удалось открыть выбранный ZIP")
                        input.use { stream -> archive.outputStream().use { stream.copyTo(it) } }
                        exportProgress.value = ExportProgress("2 из 4 · Распаковка и проверка ZIP…")
                        Python.getInstance().getModule("start_gateway").callAttr(
                            "import_agent", context.filesDir.absolutePath, archive.absolutePath, ExportStageReporter()
                        )
                    } finally {
                        archive.delete()
                    }
                }
                if (!isDestroyed) {
                    lastChatId = null
                    ttsClient?.stop()
                    waitForGateway()
                }
                Toast.makeText(context, "Данные агента импортированы", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.w("AI-Lamp", "Agent import failed (${e.javaClass.simpleName})")
                Toast.makeText(context, "Импорт не завершён. Проверь ZIP и свободное место.", Toast.LENGTH_LONG).show()
            } finally {
                exportProgress.value = null
                exportInProgress.set(false)
            }
        }
    }

    private fun chooseExportDestination() {
        if (exportInProgress.get()) {
            Toast.makeText(this, "Экспорт уже выполняется", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Экспорт агента")
            .setMessage(
                "ZIP содержит историю, память, промпты и настройки, включая сохранённые ключи API. " +
                    "Выбери личную папку на устройстве или в облаке. " +
                    "Во время подготовки снимка чат ненадолго отключится."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выбрать место") { _, _ ->
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                exportDestination.launch("AI-Lamp-agent-$date.zip")
            }
            .show()
    }

    private fun exportAgent(destination: Uri) {
        if (!exportInProgress.compareAndSet(false, true)) return
        val context = applicationContext
        exportProgress.value = ExportProgress("Подготовка экспорта…")
        CoroutineScope(Dispatchers.Main).launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val archive = File.createTempFile("alamp-agent-", ".zip", context.cacheDir)
                    try {
                        Python.getInstance().getModule("start_gateway").callAttr(
                            "export_agent", context.filesDir.absolutePath, archive.absolutePath, ExportStageReporter()
                        )
                        exportProgress.value = ExportProgress("4 из 4 · Запись в выбранное место…", 0)
                        val output = context.contentResolver.openOutputStream(destination, "wt")
                            ?: throw IOException("Не удалось открыть выбранный файл")
                        val total = archive.length()
                        var written = 0L
                        output.use { stream ->
                            archive.inputStream().use { input ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    stream.write(buffer, 0, count)
                                    written += count
                                    val percent = (written * 100 / total).toInt()
                                    if (exportProgress.value?.percent != percent) {
                                        exportProgress.value = ExportProgress("4 из 4 · Запись ZIP · $percent%", percent)
                                    }
                                }
                            }
                            exportProgress.value = ExportProgress("4 из 4 · Завершение записи…")
                        }
                    } finally {
                        archive.delete()
                    }
                }
                "ZIP передан в выбранное место"
            } catch (e: Exception) {
                Log.w("AI-Lamp", "Agent export failed (${e.javaClass.simpleName})")
                "Экспорт не завершён. Проверь свободное место и доступ к выбранной папке."
            } finally {
                exportProgress.value = null
                exportInProgress.set(false)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleTts() {
        ttsEnabled = !ttsEnabled
        if (ttsEnabled) {
            checkUrlAndAttach()
            Toast.makeText(this, "🧞 Озвучка включена", Toast.LENGTH_SHORT).show()
            navView.menu.findItem(R.id.action_tts)?.setIcon(android.R.drawable.ic_media_play)
        } else {
            lastChatId = null
            ttsClient?.stop()
            ttsBridge?.stop()
            Toast.makeText(this, "🧞 Озвучка выключена", Toast.LENGTH_SHORT).show()
            navView.menu.findItem(R.id.action_tts)?.setIcon(android.R.drawable.ic_media_pause)
        }
    }

    private fun requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                AUDIO_PERMISSION_CODE
            )
        }
    }

    private fun setStatus(text: String) {
        tvStatus.text = text
        tvStatus.visibility = android.view.View.VISIBLE
        val header = navView.getHeaderView(0)
        header.findViewById<TextView>(R.id.tv_status_header)?.text = text
    }

    private fun setupWebView() {
        ttsBridge = TtsBridge(this) { isSpeaking ->
            runOnUiThread {
                if (isSpeaking) {
                    setStatus("🧞 говорит...")
                } else {
                    setStatus("")
                }
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 10; Lenovo YT-X705L) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 AI-Lamp/1.0"
        }

        ttsBridge?.let { bridge ->
            webView.addJavascriptInterface(bridge, "TtsBridge")
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (ttsEnabled) {
                    checkUrlAndAttach()
                }
                // Инжектируем отладку getUserMedia
                view?.evaluateJavascript("""
                    (function() {
                        if (window.__gmInjected) return;
                        window.__gmInjected = true;
                        const orig = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
                        navigator.mediaDevices.getUserMedia = async function(constraints) {
                            console.log('[AI-Lamp] getUserMedia called with:', JSON.stringify(constraints));
                            try {
                                const stream = await orig(constraints);
                                console.log('[AI-Lamp] getUserMedia SUCCESS');
                                return stream;
                            } catch(e) {
                                console.log('[AI-Lamp] getUserMedia ERROR: name=' + e.name + ' message=' + e.message);
                                throw e;
                            }
                        };
                        console.log('[AI-Lamp] getUserMedia hook installed');
                    })();
                """.trimIndent(), null)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (ttsEnabled && url != null) {
                    val chatId = extractChatId(url)
                    if (chatId != null && chatId != lastChatId) {
                        lastChatId = chatId
                        ttsClient?.attachToChat(chatId)
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
            }

            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                return super.onConsoleMessage(msg)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val origin = request.origin.toString()

                if (!origin.startsWith("http://localhost:8766")) {
                    request.deny()
                    return
                }
                request.grant(request.resources)
            }
        }

        ttsBridge?.let { bridge ->
            ttsClient = TtsClient(bridge)
        }
    }

    /**
     * Ждёт готовности gateway, проверяя health endpoint.
     * Проверяет каждые 2 секунды, максимум 30 попыток (60 секунд).
     * Когда gateway готов — загружает WebUI.
     */
    private fun waitForGateway() {
        gatewayCheckJob?.cancel()
        gatewayCheckJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var attempts = 0
            val maxAttempts = 30

            while (attempts < maxAttempts) {
                try {
                    val request = Request.Builder()
                        .url("http://127.0.0.1:18791/health")
                        .build()
                    val response = healthClient.newCall(request).execute()
                    val body = response.body?.string() ?: ""

                    if (body.contains("\"ready\": true") || body.contains("\"websocket\": \"running\"")) {
                        withContext(Dispatchers.Main) {
                            setStatus("✅ Загружаю...")
                            webView.loadUrl("http://localhost:8766/webui/")
                        }
                        return@launch
                    }
                } catch (e: Exception) {
                    // Gateway ещё не готов — продолжаем ждать
                }

                attempts++
                delay(2000)
            }

            // Таймаут — gateway не запустился
            withContext(Dispatchers.Main) {
                setStatus("❌ Gateway не запустился")
                Toast.makeText(this@MainActivity, "Gateway не отвечает после 60с", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun extractChatId(url: String): String? {
        try {
            val fragment = url.substringAfter("#")
            if (fragment.isBlank()) return null

            val chatMatch = Regex("""/chat/([^/]+)""").find(fragment)
            if (chatMatch != null) {
                val raw = chatMatch.groupValues[1]
                val decoded = URLDecoder.decode(raw, "UTF-8")
                return decoded.removePrefix("websocket:")
            }

            val tempMatch = Regex("""/temporary/([^/]+)""").find(fragment)
            if (tempMatch != null) {
                return tempMatch.groupValues[1]
            }

            return null
        } catch (e: Exception) {
            return null
        }
    }

    private fun checkUrlAndAttach() {
        val url = webView.url
        if (url != null) {
            val chatId = extractChatId(url)
            if (chatId != null && chatId != lastChatId) {
                lastChatId = chatId
                ttsClient?.attachToChat(chatId)
            }
        }
    }

    private fun showFontSizeDialog() {
        val sizes = arrayOf("70%", "80%", "90%", "100%", "110%", "120%", "130%", "150%")
        val values = intArrayOf(70, 80, 90, 100, 110, 120, 130, 150)
        val currentZoom = webView.settings.textZoom
        var checkedItem = values.indexOfFirst { it >= currentZoom }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Размер текста")
            .setSingleChoiceItems(sizes, checkedItem) { dialog, which ->
                webView.settings.textZoom = values[which]
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startNanobotGateway() {
        val dataDir = filesDir.absolutePath
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    Python.getInstance().getModule("start_gateway").callAttr("start_gateway", dataDir)
                }
            } catch (e: Exception) {
                if (!isDestroyed) {
                    Toast.makeText(this@MainActivity, "Gateway error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        gatewayCheckJob?.cancel()
        exportProgressJob?.cancel()
        ttsClient?.stop()
        ttsBridge?.destroy()
        ttsBridge = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_CODE &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

        }
    }
}
