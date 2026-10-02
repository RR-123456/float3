package com.pragon.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Settings screen (assets/settings.html): pairing (QR / manual), permissions, forget this PC. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var pairing = false

    private val scan = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val uri = Uri.parse(text.trim())
        val host = uri.host
        val key = uri.getQueryParameter("key")
        if (host.isNullOrBlank() || key.isNullOrBlank()) {
            toast("That QR code isn't from Pragon."); return@registerForActivityResult
        }
        startPairing(host, if (uri.port > 0) uri.port else 8000, key)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(0xFF0A0A0F.toInt())
            settings.javaScriptEnabled = true
            addJavascriptInterface(Bridge2(), "PragonNative")
            loadUrl("file:///android_asset/settings.html")
        }
        setContentView(web)
    }

    inner class Bridge2 {
        @JavascriptInterface fun getState(): String {
            val pm = getSystemService(PowerManager::class.java)
            val host = Prefs.host(this@SettingsActivity)
            return JSONObject()
                .put("paired", Bridge.status.startsWith("Connected"))
                .put("host", if (host.isBlank()) "" else "$host:${Prefs.port(this@SettingsActivity)}")
                .put("status", Bridge.status)
                .put("a11y", PragonAccessibilityService.instance != null)
                .put("overlay", Settings.canDrawOverlays(this@SettingsActivity))
                .put("battery", pm.isIgnoringBatteryOptimizations(packageName))
                .toString()
        }
        @JavascriptInterface fun scanQr() {
            runOnUiThread {
                scan.launch(
                    ScanOptions().setPrompt("Scan the QR shown in Pragon > Remote - PhoneView")
                        .setBeepEnabled(false).setOrientationLocked(false)
                )
            }
        }
        @JavascriptInterface fun connect(hostPort: String, key: String) {
            val parts = hostPort.trim().split(":")
            val host = parts.getOrNull(0).orEmpty()
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 8000
            if (host.isBlank() || key.isBlank()) toast("Enter the PC address and the key.")
            else runOnUiThread { startPairing(host, port, key) }
        }
        @JavascriptInterface fun getAi(): String {
            val own = Prefs.aiKey(this@SettingsActivity)
            return JSONObject()
                .put("own", own.isNotBlank())
                .put("pc", Prefs.pcAiKey(this@SettingsActivity).isNotBlank())
                .put("model", Prefs.aiModel(this@SettingsActivity))
                .put("hint", if (own.length > 8) own.take(4) + "..." + own.takeLast(3) else "")
                .toString()
        }
        @JavascriptInterface fun saveAi(key: String, model: String) {
            val cur = Prefs.aiKey(this@SettingsActivity)
            Prefs.saveAi(this@SettingsActivity, if (key.isBlank()) cur else key.trim(), model.trim())
            toast("Saved.")
        }
        // ---- AI engine (Gemini online / Ollama / custom OpenAI-compatible) ----
        @JavascriptInterface fun getEngine(): String {
            val c = this@SettingsActivity
            return JSONObject()
                .put("engine", Prefs.engine(c))
                .put("ollamaHost", Prefs.ollamaHost(c))
                .put("ollamaModel", Prefs.ollamaModel(c))
                .put("openaiBase", Prefs.openaiBase(c))
                .put("openaiModel", Prefs.openaiModel(c))
                .put("openaiKeySet", Prefs.openaiKey(c).isNotBlank())
                .toString()
        }
        @JavascriptInterface fun saveEngine(engine: String, oHost: String, oModel: String, base: String, key: String, cModel: String) {
            val c = this@SettingsActivity
            Prefs.saveEngine(
                c, engine, oHost.trim(), oModel.trim(), base.trim(),
                if (key.isBlank()) null else key.trim(), cModel.trim()
            )
            toast("Saved. Engine: " + Prefs.engineLabel(c))
        }
        @JavascriptInterface fun clearEngineKey() {
            val c = this@SettingsActivity
            Prefs.saveEngine(c, Prefs.engine(c), Prefs.ollamaHost(c), Prefs.ollamaModel(c), Prefs.openaiBase(c), "", Prefs.openaiModel(c))
            toast("Custom server key removed.")
        }
        /** Lists the models the server has (Ollama /api/tags or OpenAI /models). Answers via pmModels(json). */
        @JavascriptInterface fun listModels(engine: String, oHost: String, base: String, key: String) {
            Thread {
                val names = JSONArray()
                var err = ""
                try {
                    if (engine == "openai") {
                        val rb = Request.Builder().url(LocalLlmClient.openaiBase(base) + "/models")
                        val k = if (key.isBlank()) Prefs.openaiKey(this@SettingsActivity) else key
                        if (k.isNotBlank()) rb.header("Authorization", "Bearer $k")
                        LocalLlmClient.http.newCall(rb.build()).execute().use { r ->
                            val raw = r.body?.string() ?: ""
                            if (!r.isSuccessful) err = "HTTP ${r.code}"
                            else {
                                val arr = JSONObject(raw).optJSONArray("data")
                                if (arr != null) for (i in 0 until arr.length()) names.put(arr.getJSONObject(i).optString("id"))
                            }
                        }
                    } else {
                        val req = Request.Builder().url(LocalLlmClient.ollamaBase(oHost) + "/api/tags").build()
                        LocalLlmClient.http.newCall(req).execute().use { r ->
                            val raw = r.body?.string() ?: ""
                            if (!r.isSuccessful) err = "HTTP ${r.code}"
                            else {
                                val arr = JSONObject(raw).optJSONArray("models")
                                if (arr != null) for (i in 0 until arr.length()) names.put(arr.getJSONObject(i).optString("name"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    err = e.message ?: "can't connect"
                }
                js("pmModels(" + JSONObject().put("models", names).put("error", err).toString() + ")")
            }.start()
        }
        /** Tells the Ollama server to download a model (hermes3, llama3.2, hf.co/... anything Ollama can pull). Progress via pmPull(). */
        @JavascriptInterface fun pullModel(oHost: String, model: String) {
            val name = model.trim()
            if (name.isEmpty()) { toast("Type a model name to pull."); return }
            Thread {
                try {
                    val body = JSONObject().put("model", name).put("stream", true).toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                    val req = Request.Builder().url(LocalLlmClient.ollamaBase(oHost) + "/api/pull").post(body).build()
                    LocalLlmClient.http.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) {
                            js("pmPull(" + JSONObject.quote("Failed: HTTP ${r.code}") + ",true)")
                            return@use
                        }
                        val src = r.body?.source()
                        var last = 0L
                        while (src != null && !src.exhausted()) {
                            val line = src.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            val o = try { JSONObject(line) } catch (e: Exception) { continue }
                            if (o.has("error")) {
                                js("pmPull(" + JSONObject.quote("Failed: " + o.optString("error")) + ",true)"); return@use
                            }
                            val st = o.optString("status")
                            val total = o.optLong("total", 0)
                            val done = o.optLong("completed", 0)
                            val msg = if (total > 0) "$st ${done * 100 / total}%" else st
                            val now = System.currentTimeMillis()
                            if (st == "success" || now - last > 400) {
                                last = now
                                js("pmPull(" + JSONObject.quote(if (st == "success") "Done - $name is ready." else msg) + "," + (st == "success") + ")")
                            }
                        }
                    }
                } catch (e: Exception) {
                    js("pmPull(" + JSONObject.quote("Failed: " + (e.message ?: "can't connect")) + ",true)")
                }
            }.start()
        }
        @JavascriptInterface fun clearAiKey() {
            Prefs.saveAi(this@SettingsActivity, "", Prefs.aiModel(this@SettingsActivity))
            toast("Your key was removed.")
        }
        @JavascriptInterface fun openA11y() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Find PragonMobile in the list and turn it on.")
            }
        }
        @JavascriptInterface fun openOverlay() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        @JavascriptInterface fun openBattery() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        @JavascriptInterface fun forget() {
            runOnUiThread {
                ContextCompat.startForegroundService(
                    this@SettingsActivity,
                    Intent(this@SettingsActivity, PragonService::class.java).setAction(PragonService.ACTION_STOP)
                )
                Prefs.clear(this@SettingsActivity)
                RemoteBridge.socket = null
                Bridge.set("Not paired yet - scan the QR from Pragon")
            }
        }
        @JavascriptInterface fun close() { runOnUiThread { finish() } }
    }

    private fun startPairing(host: String, port: Int, key: String) {
        pairing = true
        Prefs.save(this, host, port, "")
        ContextCompat.startForegroundService(
            this, Intent(this, PragonService::class.java).putExtra(PragonService.EXTRA_KEY, key.trim().uppercase())
        )
        Bridge.set("Pairing with $host:$port ...")
    }

    override fun onResume() {
        super.onResume()
        // After a successful pairing, go straight back to PhoneView.
        Bridge.listener = { s -> if (pairing && s.startsWith("Connected")) finish() }
    }

    override fun onPause() { Bridge.listener = null; super.onPause() }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
}
