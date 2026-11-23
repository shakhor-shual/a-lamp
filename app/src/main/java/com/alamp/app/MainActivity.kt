package com.alamp.app

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var tvStatus: TextView
    private var ttsBridge: TtsBridge? = null
    private var ttsClient: TtsClient? = null
    private var ttsEnabled = true
    private var lastChatId: String? = null
    private var gatewayCheckJob: Job? = null
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

        setStatus("🔄 Запуск...")

        navView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(GravityCompat.START)
            handleMenuItem(item)
            true
        }

        setupWebView()
        startNanobotGateway()
        waitForGateway()
    }

    private fun handleMenuItem(item: MenuItem) {
        when (item.itemId) {
            R.id.action_tts -> toggleTts()
            R.id.action_tts_settings -> startActivity(Intent(this, TtsSettingsActivity::class.java))
            R.id.action_files -> Toast.makeText(this, "Файлы — скоро", Toast.LENGTH_SHORT).show()
            R.id.action_import -> Toast.makeText(this, "Импорт/Экспорт — скоро", Toast.LENGTH_SHORT).show()
            R.id.action_diagnostics -> Toast.makeText(this, "Диагностика — скоро", Toast.LENGTH_SHORT).show()
            R.id.action_font_size -> showFontSizeDialog()
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
            userAgentString = "Mozilla/5.0 (Linux; Android 10; Lenovo YT-X705L) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 A-lamp/1.0"
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
                        .url("http://127.0.0.1:18790/health")
                        .build()
                    val response = healthClient.newCall(request).execute()
                    val body = response.body?.string() ?: ""

                    if (body.contains("\"ready\": true") || body.contains("\"websocket\": \"running\"")) {
                        withContext(Dispatchers.Main) {
                            setStatus("✅ Загружаю...")
                            webView.loadUrl("http://127.0.0.1:8765/webui/")
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
        try {
            val py = Python.getInstance()
            val module = py.getModule("start_gateway")
            val dataDir = filesDir.absolutePath
            module.callAttr("start_gateway", dataDir)
        } catch (e: Exception) {
            Toast.makeText(this, "Gateway error: ${e.message}", Toast.LENGTH_LONG).show()
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
        ttsClient?.stop()
        ttsBridge?.destroy()
        ttsBridge = null
        super.onDestroy()
    }
}
