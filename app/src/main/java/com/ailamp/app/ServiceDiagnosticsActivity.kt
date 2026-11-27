package com.ailamp.app

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.TooltipCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

class ServiceDiagnosticsActivity : AppCompatActivity() {
    private enum class Probe { HEAD, MODELS, OPENROUTER_KEY, TRANSCRIPTION, EMBEDDING }
    private enum class State(val color: Int) {
        OK(R.color.diagnostic_success),
        PARTIAL(R.color.diagnostic_partial),
        FAILED(R.color.diagnostic_failure),
    }
    private data class Verdict(val state: State, val text: String)
    private class Service(
        val name: String,
        val url: HttpUrl?,
        val probe: Probe = Probe.HEAD,
        val key: String? = null,
        val authHeader: String = "Authorization",
        val authPrefix: String = "Bearer ",
        val model: String? = null,
    )
    private data class Configuration(val services: List<Service>, val errors: List<String>)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private lateinit var testButton: ImageButton
    private lateinit var status: TextView
    private lateinit var results: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_service_diagnostics)
        findViewById<View>(R.id.diagnostics_back).setOnClickListener { finish() }
        testButton = findViewById(R.id.diagnostics_test)
        status = findViewById(R.id.diagnostics_status)
        results = findViewById(R.id.diagnostics_results)
        TooltipCompat.setTooltipText(testButton, testButton.contentDescription)
        testButton.setOnClickListener { testServices() }
        testServices()
    }

    private fun testServices() {
        testButton.isEnabled = false
        testButton.alpha = 0.55f
        results.removeAllViews()
        status.text = getString(R.string.diag_reading)
        scope.launch {
            val configuration = withContext(Dispatchers.IO) { readServices() }
            val services = configuration.services
            val errors = configuration.errors
            status.text = (errors + when {
                services.isEmpty() -> getString(R.string.diag_no_addresses)
                else -> getString(R.string.diag_checking)
            }).joinToString("\n")

            val permits = Semaphore(4)
            coroutineScope {
                for (service in services) {
                    val row = layoutInflater.inflate(R.layout.diagnostic_service, results, false)
                    row.findViewById<TextView>(R.id.diagnostic_service_name).text = service.name
                    val url = service.url
                    row.findViewById<TextView>(R.id.diagnostic_service_url).text =
                        url?.newBuilder()?.query(null)?.build()?.toString() ?: getString(R.string.diag_invalid_url)
                    val result = row.findViewById<TextView>(R.id.diagnostic_service_result)
                    results.addView(row)
                    launch {
                        val verdict = if (url == null) Verdict(State.FAILED, getString(R.string.diag_check_url))
                            else permits.withPermit { probe(service, url) }
                        result.text = verdict.text
                        result.setTextColor(ContextCompat.getColor(this@ServiceDiagnosticsActivity, verdict.state.color))
                    }
                }
            }
            if (services.isNotEmpty()) {
                status.text = (errors + getString(R.string.diag_done)).joinToString("\n")
            }
            testButton.isEnabled = true
            testButton.alpha = 1f
        }
    }

    private fun readServices(): Configuration {
        val configDir = File(filesDir, "nanobot")
        val errors = mutableListOf<String>()
        fun read(name: String): JSONObject? = try {
            JSONObject(File(configDir, name).readText())
        } catch (_: IOException) {
            errors.add(getString(R.string.diag_read_failed, name))
            null
        } catch (_: JSONException) {
            errors.add(getString(R.string.diag_invalid_json, name))
            null
        }

        val nanobot = read("config.json")
        val local = read("alamp_config.json")
        val services = buildList {
            val providers = nanobot?.optJSONObject("providers")
            val channels = nanobot?.optJSONObject("channels")
            val transcription = nanobot?.optJSONObject("transcription")
            val voiceProvider = (
                transcription?.value("provider")
                    ?: channels?.value("transcription_provider", "transcriptionProvider")
                )?.lowercase(Locale.ROOT)?.replace('-', '_')
            val defaults = nanobot?.optJSONObject("agents")?.optJSONObject("defaults")
            val presets = nanobot?.optJSONObject("model_presets") ?: nanobot?.optJSONObject("modelPresets")
            val modelProviders = buildSet {
                defaults?.chatProvider()?.let { add(it) }
                presets?.keys()?.forEach { presets.optJSONObject(it)?.chatProvider()?.let { add(it) } }
            }

            providers?.keys()?.forEach { name ->
                val provider = providers.optJSONObject(name) ?: return@forEach
                val id = name.lowercase(Locale.ROOT).replace('-', '_')
                val address = provider.value("api_base", "apiBase")
                    ?: when (id) {
                        "openrouter" -> "https://openrouter.ai/api/v1"
                        "openai" -> "https://api.openai.com/v1"
                        "groq" -> "https://api.groq.com/openai/v1"
                        else -> null
                    }?.takeIf { provider.value("api_key", "apiKey") != null || id == voiceProvider }
                    ?: return@forEach
                val base = address.httpUrl()
                val key = provider.value("api_key", "apiKey")
                if (id == voiceProvider && transcription?.optBoolean("enabled", true) != false) {
                    val path = when (id) {
                        "xiaomi_mimo" -> "chat/completions"
                        "stepfun" -> "audio/asr/sse"
                        "assemblyai" -> "transcript"
                        else -> "audio/transcriptions"
                    }
                    add(Service(
                        getString(R.string.diag_stt, name), base?.endpoint(path), Probe.TRANSCRIPTION, key,
                        authPrefix = if (id == "assemblyai") "" else "Bearer ",
                        model = transcription?.value("model") ?: when (id) {
                            "groq" -> "whisper-large-v3"
                            "openrouter" -> "openai/whisper-1"
                            "siliconflow", "silicon" -> "FunAudioLLM/SenseVoiceSmall"
                            else -> "whisper-1"
                        },
                    ))
                }
                if (id != voiceProvider || id in modelProviders) {
                    val url = base?.modelsEndpoint(id)
                    add(Service(
                        getString(R.string.diag_models, name), url, Probe.MODELS, key,
                        authHeader = if (id == "minimax_anthropic") "X-Api-Key" else "Authorization",
                        authPrefix = if (id == "minimax_anthropic") "" else "Bearer ",
                    ))
                    if (id == "openrouter" && base?.host == "openrouter.ai") {
                        add(Service(getString(R.string.diag_key, name), url?.replaceEndpoint("key"), Probe.OPENROUTER_KEY, key))
                    }
                }
            }

            val tools = nanobot?.optJSONObject("tools")
            val mcp = tools?.optJSONObject("mcp_servers") ?: tools?.optJSONObject("mcpServers")
            mcp?.keys()?.forEach { name ->
                mcp.optJSONObject(name)?.service("MCP · $name", "url")?.let { add(it) }
            }

            tools?.optJSONObject("web")?.optJSONObject("search")
                ?.service(getString(R.string.diag_search), "base_url", "baseUrl")?.let { add(it) }

            val embedding = local?.optJSONArray("embedding_services")
            if (embedding != null) {
                for (index in 0 until embedding.length()) {
                    val entry = embedding.optJSONObject(index) ?: continue
                    val address = entry.value("url") ?: continue
                    val base = address.httpUrl()
                    val path = base?.encodedPath.orEmpty().trimEnd('/')
                    val endpoint = when {
                        path.endsWith("/embeddings") -> base
                        path.endsWith("/v1") -> base?.endpoint("embeddings")
                        base?.host == "openrouter.ai" -> base.endpoint("api/v1/embeddings")
                        else -> base?.endpoint("v1/embeddings")
                    }
                    add(Service(
                        getString(R.string.diag_embeddings, index + 1), endpoint, Probe.EMBEDDING,
                        entry.value("api_key"), model = entry.value("model"),
                    ))
                }
            }
        }
        return Configuration(services, errors)
    }

    private fun JSONObject.value(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { (opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty) }

    private fun JSONObject.chatProvider(): String? {
        val provider = value("provider")?.takeUnless { it.equals("auto", ignoreCase = true) }
        return (provider ?: value("model")?.substringBefore('/', "")?.takeIf { it.isNotEmpty() })
            ?.lowercase(Locale.ROOT)?.replace('-', '_')
    }

    private fun JSONObject.service(name: String, vararg keys: String): Service? =
        value(*keys)?.let { Service(name, it.httpUrl()) }

    private fun String.httpUrl(): HttpUrl? =
        toHttpUrlOrNull()?.newBuilder()?.username("")?.password("")?.fragment(null)?.build()

    private fun HttpUrl.endpoint(suffix: String): HttpUrl {
        val path = encodedPath.trimEnd('/')
        if (path.endsWith("/$suffix")) return newBuilder().encodedPath(path).build()
        return newBuilder().encodedPath("$path/$suffix").build()
    }

    private fun HttpUrl.replaceEndpoint(suffix: String): HttpUrl =
        newBuilder().encodedPath("${encodedPath.substringBeforeLast('/')}/$suffix").build()

    private fun HttpUrl.modelsEndpoint(provider: String): HttpUrl {
        val path = encodedPath.trimEnd('/')
        val basePath = listOf("/chat/completions", "/responses", "/messages", "/models")
            .firstOrNull(path::endsWith)?.let(path::removeSuffix) ?: path
        val base = newBuilder().encodedPath(basePath.ifEmpty { "/" }).build()
        return when {
            provider == "minimax_anthropic" && !basePath.endsWith("/v1") -> base.endpoint("v1/models")
            basePath.isEmpty() && host == "openrouter.ai" -> base.endpoint("api/v1/models")
            basePath.isEmpty() && host == "api.openai.com" -> base.endpoint("v1/models")
            else -> base.endpoint("models")
        }
    }

    private val speechSample by lazy {
        resources.openRawResource(R.raw.diagnostic_speech).use { it.readBytes() }
            .toRequestBody("audio/wav".toMediaType())
    }

    private fun Service.payload(): RequestBody = when (probe) {
        Probe.TRANSCRIPTION -> MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model.orEmpty())
            .addFormDataPart("file", "diagnostic.wav", speechSample)
            .build()
        else -> JSONObject()
            .put("model", model ?: "BAAI/bge-m3")
            .put("input", "AI-Lamp test")
            .toString()
            .toRequestBody("application/json".toMediaType())
    }

    private suspend fun probe(service: Service, url: HttpUrl): Verdict {
        if (service.probe == Probe.OPENROUTER_KEY && service.key == null) {
            return Verdict(State.FAILED, getString(R.string.diag_missing_key))
        }
        if (service.probe == Probe.TRANSCRIPTION && !url.encodedPath.endsWith("/audio/transcriptions")) {
            return Verdict(State.PARTIAL, getString(R.string.diag_stt_unsupported))
        }
        val request = try {
            Request.Builder().url(url).apply {
                when (service.probe) {
                    Probe.HEAD -> head()
                    Probe.MODELS, Probe.OPENROUTER_KEY -> get()
                    Probe.TRANSCRIPTION, Probe.EMBEDDING -> post(service.payload())
                }
                service.key?.let { header(service.authHeader, service.authPrefix + it) }
            }.build()
        } catch (_: IllegalArgumentException) {
            return Verdict(State.FAILED, getString(R.string.diag_invalid_request))
        }

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val detail = when (e) {
                        is UnknownHostException -> getString(R.string.diag_dns)
                        is InterruptedIOException -> getString(R.string.diag_timeout)
                        is SSLException -> getString(R.string.diag_tls)
                        else -> getString(R.string.diag_connection_error)
                    }
                    continuation.resume(Verdict(State.FAILED, getString(R.string.diag_unavailable_detail, detail)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val code = response.use { it.code }
                    continuation.resume(service.verdict(code))
                }
            })
        }
    }

    private fun Service.verdict(code: Int): Verdict {
        val detail = when (code) {
            in 200..299 -> when (probe) {
                Probe.OPENROUTER_KEY -> State.OK to getString(R.string.diag_key_valid)
                Probe.MODELS -> State.OK to getString(R.string.diag_catalog_ok)
                Probe.TRANSCRIPTION -> State.OK to getString(R.string.diag_audio_ok)
                Probe.EMBEDDING -> State.OK to getString(R.string.diag_embedding_ok)
                else -> State.OK to getString(R.string.diag_endpoint_ok)
            }
            in 300..399 -> State.PARTIAL to getString(R.string.diag_redirect)
            400, 422 -> State.FAILED to getString(R.string.diag_test_rejected)
            401 -> State.FAILED to if (key == null) getString(R.string.diag_auth_required) else getString(R.string.diag_key_rejected)
            402 -> State.FAILED to getString(R.string.diag_payment)
            403 -> State.FAILED to getString(R.string.diag_forbidden)
            404 -> State.FAILED to getString(R.string.diag_not_found)
            405, 501 -> State.PARTIAL to getString(R.string.diag_method_unsupported)
            429 -> State.FAILED to getString(R.string.diag_rate_limit)
            in 500..599 -> State.FAILED to getString(R.string.diag_server_error)
            else -> State.FAILED to getString(R.string.diag_request_rejected)
        }
        return Verdict(detail.first, getString(R.string.diag_http_result, detail.second, code))
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
