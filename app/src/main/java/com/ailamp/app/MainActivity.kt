package com.ailamp.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
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
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private data class ExportProgress(val label: Int, val percent: Int? = null)

    class ExportStageReporter {
        fun accept(stage: Int) {
            val label = when (stage) {
                1 -> R.string.archive_stop_gateway
                2 -> R.string.archive_snapshot
                3 -> R.string.archive_pack
                5 -> R.string.archive_check_db
                6 -> R.string.archive_replace
                else -> return
            }
            exportProgress.value = ExportProgress(label)
        }
    }

    companion object {
        private const val MANAGEMENT_OPEN = "agent_management_open"
        private const val SETTINGS_OPEN = "shell_settings_open"
        private const val WEBUI_URL_STATE = "webui_url"
        private const val WEBUI_URL = "http://localhost:8766/webui/"
        private val appLanguages = listOf("en" to "English", "ru" to "Русский", "pl" to "Polski")
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
                .setTitle(getString(R.string.agent_import))
                .setMessage(getString(R.string.archive_import_confirm))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.archive_replace_button)) { _, _ -> importAgent(uri) }
                .show()
        }
    }

    private val agentEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            lastChatId = null
            ttsClient?.stop()
            waitForGateway()
        }
    }

    private val speechSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) openVoiceChat()
    }

    private val voicePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (voiceChat.isOpen) {
            if (granted) voiceChat.startListening()
            else voiceChat.showPermissionError()
        }
    }

    private lateinit var webView: ChatWebView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var shellSettings: View
    private lateinit var agentManagement: View
    private lateinit var voiceChat: VoiceChatPanel
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
        shellSettings = findViewById(R.id.shell_settings)
        findViewById<View>(R.id.shell_settings_back).setOnClickListener { showShellSettings(false) }
        findViewById<Button>(R.id.shell_settings_language).apply {
            val current = AppCompatDelegate.getApplicationLocales().get(0)?.language
            val name = appLanguages.firstOrNull { it.first == current }?.second
                ?: getString(R.string.app_language_system)
            text = getString(R.string.app_language_current, name)
            setOnClickListener { chooseAppLanguage() }
        }
        findViewById<View>(R.id.shell_settings_text_size).setOnClickListener {
            startActivity(Intent(this, TextSizeSettingsActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        findViewById<View>(R.id.shell_settings_stt).setOnClickListener {
            speechSettings.launch(Intent(this, SttSettingsActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        findViewById<View>(R.id.shell_settings_tts).setOnClickListener {
            startActivity(Intent(this, TtsSettingsActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        drawerLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDrawerGestureArea()
        }
        tvStatus = findViewById(R.id.tv_status)
        webView = findViewById(R.id.webview)
        agentManagement = findViewById(R.id.agent_management)
        findViewById<View>(R.id.agent_management_back).setOnClickListener { showAgentManagement(false) }
        val actions = listOf(
            R.id.agent_management_export to { chooseExportDestination() },
            R.id.agent_management_import to {
                importSource.launch(arrayOf("application/zip", "application/octet-stream"))
            },
            R.id.agent_management_configs to { openAgentEditor(AgentEditorActivity.CONFIGS) },
            R.id.agent_management_prompts to { openAgentEditor(AgentEditorActivity.PROMPTS) },
        ).map { (id, action) ->
            findViewById<View>(id).apply {
                setOnClickListener { if (!exportInProgress.get()) action() }
            }
        }
        when {
            savedInstanceState?.getBoolean(MANAGEMENT_OPEN) == true -> showAgentManagement(true)
            savedInstanceState?.getBoolean(SETTINGS_OPEN) == true -> showShellSettings(true)
        }
        val exportPanel = findViewById<View>(R.id.export_progress_panel)
        val exportLabel = findViewById<TextView>(R.id.export_progress_label)
        val exportBar = findViewById<ProgressBar>(R.id.export_progress_bar)
        exportProgressJob = CoroutineScope(Dispatchers.Main).launch {
            exportProgress.collect { progress ->
                exportPanel.visibility = if (progress == null) View.GONE else View.VISIBLE
                actions.forEach { it.isEnabled = progress == null }
                if (progress != null) {
                    exportLabel.setText(progress.label)
                    exportBar.isIndeterminate = progress.percent == null
                    progress.percent?.let { exportBar.progress = it }
                }
            }
        }

        setStatus(getString(R.string.app_starting))

        navView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(navView)
            handleMenuItem(item)
            true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        ttsEnabled = VoiceSettings.read(this).enabled
        setupWebView()
        voiceChat = VoiceChatPanel(
            this, findViewById(R.id.voice_chat), ::closeVoiceChat, ::startVoiceInput,
            stopSpeaking = { ttsBridge?.stop() },
            speak = { text, onFinished ->
                val bridge = ttsBridge
                if (bridge == null) onFinished(false) else bridge.speakVoiceReply(text, onFinished)
            },
        )
        requestAudioPermission()
        startNanobotGateway()
        val previousPage = savedInstanceState?.getString(WEBUI_URL_STATE)?.takeIf(::isNanobotPage)
        waitForGateway(previousPage ?: WEBUI_URL)
    }

    private fun handleMenuItem(item: MenuItem) {
        when (item.itemId) {
            R.id.action_voice_chat -> openVoiceChat()
            R.id.action_agent_management -> showAgentManagement(true)
            R.id.action_shell_settings -> showShellSettings(true)
            R.id.action_about -> AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_description, getString(R.string.about_nanobot_version)))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            R.id.action_service_diagnostics -> {
                startActivity(Intent(this, ServiceDiagnosticsActivity::class.java))
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }

    private fun openVoiceChat() {
        if (voiceChat.isOpen) return
        val url = webView.url ?: return
        val uri = Uri.parse(url)
        val route = uri.encodedFragment.orEmpty()
        val isNewChat = route in setOf("", "/", "/new")
        val chatId = if (route.startsWith("/chat/")) extractChatId(url) else null
        if (!isNanobotPage(url) || (chatId == null && !isNewChat)) {
            Toast.makeText(this, getString(R.string.voice_open_chat), Toast.LENGTH_SHORT).show()
            return
        }
        if (shellSettings.visibility == View.VISIBLE) showShellSettings(false)
        ttsClient?.stop()
        lastChatId = null
        ttsBridge?.setVoiceChat(true)
        WindowCompat.getInsetsController(window, webView).hide(WindowInsetsCompat.Type.ime())
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setShellMenuEnabled(false)
        voiceChat.open(chatId)
    }

    private fun closeVoiceChat() {
        val newChat = voiceChat.close()
        ttsBridge?.setVoiceChat(false)
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        setShellMenuEnabled(true)
        if (newChat != null) {
            val route = "/chat/${Uri.encode("websocket:$newChat")}"
            webView.evaluateJavascript("window.location.hash = ${JSONObject.quote(route)};", null)
        } else if (ttsEnabled) {
            checkUrlAndAttach()
        }
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            voiceChat.startListening()
        } else {
            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showAgentManagement(open: Boolean) {
        agentManagement.visibility = if (open) View.VISIBLE else View.GONE
        webView.visibility = if (open) View.INVISIBLE else View.VISIBLE
        setShellMenuEnabled(!open)
    }

    private fun showShellSettings(open: Boolean) {
        shellSettings.visibility = if (open) View.VISIBLE else View.GONE
        webView.visibility = if (open) View.INVISIBLE else View.VISIBLE
        setShellMenuEnabled(!open)
    }

    private fun setShellMenuEnabled(enabled: Boolean) {
        val mode = if (enabled) DrawerLayout.LOCK_MODE_UNLOCKED else DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        drawerLayout.setDrawerLockMode(mode, navView)
        updateDrawerGestureArea()
    }

    private fun updateDrawerGestureArea() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val width = drawerLayout.width
        if (width == 0 || drawerLayout.getDrawerLockMode(navView) != DrawerLayout.LOCK_MODE_UNLOCKED) {
            drawerLayout.systemGestureExclusionRects = emptyList()
            return
        }

        val rightInset = ViewCompat.getRootWindowInsets(drawerLayout)
            ?.getInsets(WindowInsetsCompat.Type.systemGestures())?.right ?: 0
        val edgeWidth = maxOf(rightInset, (24 * resources.displayMetrics.density).toInt())
        val edgeHeight = (160 * resources.displayMetrics.density).toInt()
        // Reserve the upper-right edge for the drawer and leave the rest for Android Back.
        drawerLayout.systemGestureExclusionRects = listOf(
            Rect((width - edgeWidth).coerceAtLeast(0), 0, width, minOf(edgeHeight, drawerLayout.height))
        )
    }

    private fun openAgentEditor(section: String) {
        agentEditor.launch(
            Intent(this, AgentEditorActivity::class.java).putExtra(AgentEditorActivity.SECTION, section)
        )
    }

    private fun importAgent(source: Uri) {
        if (!exportInProgress.compareAndSet(false, true)) return
        val context = applicationContext
        exportProgress.value = ExportProgress(R.string.archive_reading)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    val archive = File.createTempFile("alamp-import-", ".zip", context.cacheDir)
                    try {
                        val input = context.contentResolver.openInputStream(source)
                            ?: throw IOException("Cannot open selected archive")
                        input.use { stream -> archive.outputStream().use { stream.copyTo(it) } }
                        exportProgress.value = ExportProgress(R.string.archive_unpack)
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
                Toast.makeText(context, getString(R.string.archive_imported), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.w(getString(R.string.app_name), "Agent import failed (${e.javaClass.simpleName})")
                Toast.makeText(context, getString(R.string.archive_import_failed), Toast.LENGTH_LONG).show()
            } finally {
                exportProgress.value = null
                exportInProgress.set(false)
            }
        }
    }

    private fun chooseExportDestination() {
        if (exportInProgress.get()) {
            Toast.makeText(this, getString(R.string.archive_export_busy), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_export))
            .setMessage(R.string.archive_export_confirm)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.archive_choose_destination)) { _, _ ->
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                exportDestination.launch("AI-Lamp-agent-$date.zip")
            }
            .show()
    }

    private fun exportAgent(destination: Uri) {
        if (!exportInProgress.compareAndSet(false, true)) return
        val context = applicationContext
        exportProgress.value = ExportProgress(R.string.archive_preparing)
        CoroutineScope(Dispatchers.Main).launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val archive = File.createTempFile("alamp-agent-", ".zip", context.cacheDir)
                    try {
                        Python.getInstance().getModule("start_gateway").callAttr(
                            "export_agent", context.filesDir.absolutePath, archive.absolutePath, ExportStageReporter()
                        )
                        exportProgress.value = ExportProgress(R.string.archive_writing, 0)
                        val output = context.contentResolver.openOutputStream(destination, "wt")
                            ?: throw IOException("Cannot open selected destination")
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
                                        exportProgress.value = ExportProgress(R.string.archive_writing, percent)
                                    }
                                }
                            }
                            exportProgress.value = ExportProgress(R.string.archive_finishing)
                        }
                    } finally {
                        archive.delete()
                    }
                }
                getString(R.string.archive_exported)
            } catch (e: Exception) {
                Log.w(getString(R.string.app_name), "Agent export failed (${e.javaClass.simpleName})")
                getString(R.string.archive_export_failed)
            } finally {
                exportProgress.value = null
                exportInProgress.set(false)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val preferences = VoiceSettings.preferences(this)
        webView.setKeyboardHidden(preferences.getBoolean(VoiceSettings.HIDE_WEB_KEYBOARD, false))
        val chatZoom = TextSizePreference.WEB_CHAT.read(preferences)
        if (webView.settings.textZoom != chatZoom) webView.settings.textZoom = chatZoom
        voiceChat.applyTextSizes()
        val enabled = VoiceSettings.read(this).enabled
        ttsBridge?.reloadSettings()
        if (enabled == ttsEnabled) return
        ttsEnabled = enabled
        if (enabled) {
            checkUrlAndAttach()
        } else {
            lastChatId = null
            ttsClient?.stop()
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
        tvStatus.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setupWebView() {
        ttsBridge = TtsBridge(this) { isSpeaking ->
            runOnUiThread {
                if (::voiceChat.isInitialized) voiceChat.onPlaybackChanged(isSpeaking)
                if (isSpeaking) {
                    setStatus(getString(R.string.app_speaking))
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
                webView.applyKeyboardSetting()
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
                if (ttsEnabled && url != null && !voiceChat.isOpen) {
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
    private fun waitForGateway(page: String = WEBUI_URL) {
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
                            setStatus(getString(R.string.app_loading))
                            webView.loadUrl(page)
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
                setStatus(getString(R.string.gateway_not_started))
                Toast.makeText(this@MainActivity, getString(R.string.gateway_timeout), Toast.LENGTH_LONG).show()
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
        if (voiceChat.isOpen) return
        val url = webView.url
        if (url != null) {
            val chatId = extractChatId(url)
            if (chatId != null && chatId != lastChatId) {
                lastChatId = chatId
                ttsClient?.attachToChat(chatId)
            }
        }
    }

    private fun isNanobotPage(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "http" && uri.host == "localhost" && uri.port == 8766 &&
            uri.path?.trimEnd('/') == "/webui"
    }

    private fun chooseAppLanguage() {
        val current = AppCompatDelegate.getApplicationLocales().get(0)?.language
        val selected = appLanguages.indexOfFirst { it.first == current } + 1
        val names = listOf(getString(R.string.app_language_system)) + appLanguages.map { it.second }
        AlertDialog.Builder(this)
            .setTitle(R.string.app_language)
            .setSingleChoiceItems(names.toTypedArray(), selected) { dialog, index ->
                dialog.dismiss()
                val tag = appLanguages.getOrNull(index - 1)?.first.orEmpty()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            .setNegativeButton(R.string.cancel, null)
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
                    Toast.makeText(this@MainActivity, getString(R.string.gateway_error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerVisible(navView)) {
            drawerLayout.closeDrawer(navView)
        } else if (voiceChat.isOpen) {
            closeVoiceChat()
        } else if (agentManagement.visibility == View.VISIBLE) {
            showAgentManagement(false)
        } else if (shellSettings.visibility == View.VISIBLE) {
            showShellSettings(false)
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        voiceChat.updateAvatarSize()
    }

    override fun onPause() {
        voiceChat.pauseRecording()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(MANAGEMENT_OPEN, agentManagement.visibility == View.VISIBLE)
        outState.putBoolean(SETTINGS_OPEN, shellSettings.visibility == View.VISIBLE)
        webView.url?.takeIf(::isNanobotPage)?.let { outState.putString(WEBUI_URL_STATE, it) }
    }

    override fun onDestroy() {
        gatewayCheckJob?.cancel()
        exportProgressJob?.cancel()
        voiceChat.destroy()
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
