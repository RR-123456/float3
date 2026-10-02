package com.pragon.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.view.Gravity
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Home screen = the REAL PhoneView web app (same app.html the PC serves), signed in through
 * the phone's existing pairing (web_login handoff). PhoneView's own UI is left untouched;
 * assets/pm_inject.* adds the type-on-PC toggle, the Remote sheet and the Settings button.
 */
class MainActivity : AppCompatActivity(), RemoteBridge.Listener {

    private lateinit var web: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var settingsBtn: Button
    private val main = Handler(Looper.getMainLooper())
    private lateinit var standalone: StandaloneView
    private var tts: TextToSpeech? = null

    private var webLoaded = false
    private var awaitingLogin = false
    private var lastLoginReq = 0L
    private var settingsAutoShown = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var lastToast = ""
    private var lastToastAt = 0L

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            FloatService.pauseForExternalMic(false)
            val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (r.resultCode == RESULT_OK && !said.isNullOrBlank()) {
                if (overlay.visibility == View.VISIBLE) standalone.submit(said, true)
                else web.evaluateJavascript("window.__pmVoice&&window.__pmVoice(" + JSONObject.quote(said) + ")", null)
            }
        }

    // Paperclip in Standalone mode: pick files and hand them to the chat.
    private val attachLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            Thread {
                for (u in uris) {
                    try {
                        val mime = contentResolver.getType(u) ?: "application/octet-stream"
                        var name = "file"
                        contentResolver.query(u, null, null, null, null)?.use { c ->
                            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
                        }
                        val bytes = contentResolver.openInputStream(u)?.use { it.readBytes() } ?: continue
                        main.post { standalone.addAttachment(name, mime, bytes) }
                    } catch (e: Exception) {
                        main.post { Toast.makeText(this, "Couldn't read that file.", Toast.LENGTH_SHORT).show() }
                    }
                }
            }.start()
        }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Pragon")
        }
        try {
            FloatService.pauseForExternalMic(true) // float mode lets go of the mic while this dialog listens
            voiceLauncher.launch(i)
        } catch (e: Exception) {
            FloatService.pauseForExternalMic(false)
            Toast.makeText(this, "This phone has no speech recognizer.", Toast.LENGTH_LONG).show()
        }
    }

    /** Float mode button: hovering bubble that listens by voice over every app. */
    private fun toggleFloat() {
        if (FloatService.running) {
            FloatMode.stop(this)
            Toast.makeText(this, "Float mode off.", Toast.LENGTH_SHORT).show()
            main.postDelayed({ standalone.refreshFloat() }, 400)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            Toast.makeText(this, "Allow the microphone, then tap the float button again.", Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this) && PragonAccessibilityService.instance == null) {
            Toast.makeText(this, "Allow 'Display over other apps' for Pragon, then tap the float button again.", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) { openSettings() }
            return
        }
        try {
            FloatMode.start(this, active = false)
        } catch (e: Exception) {
            Toast.makeText(this, "Float mode couldn't start: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Starting float mode...", Toast.LENGTH_SHORT).show()
        // Only claim success (and step aside) once the service really is running.
        main.postDelayed({
            standalone.refreshFloat()
            if (FloatService.running) {
                Toast.makeText(this, "Float mode on. Say: I'm home.", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                val why = FloatService.lastError.ifBlank { "Check microphone, 'Display over other apps' and battery settings for Pragon." }
                Toast.makeText(this, "Float mode did not start. $why", Toast.LENGTH_LONG).show()
            }
        }, 1200)
    }

    private fun speak(text: String) {
        tts?.speak(text.take(1500), TextToSpeech.QUEUE_FLUSH, null, "pragon")
    }

    private val permsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val chooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            val results: Array<Uri>? = when {
                r.resultCode != RESULT_OK -> null
                data?.data == null && data?.clipData == null -> cameraUri?.let { arrayOf(it) }
                else -> WebChromeClient.FileChooserParams.parseResult(r.resultCode, data)
            }
            fileCallback?.onReceiveValue(results)
            fileCallback = null
            cameraUri = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density

        web = WebView(this).apply {
            setBackgroundColor(0xFF0A0A0F.toInt())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true // PhoneView keeps its login in sessionStorage/localStorage
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = false
            }
            addJavascriptInterface(PmBridge(), "PragonNative")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    val u = Uri.parse(url ?: "")
                    if (u.scheme != "http" && u.scheme != "https") return
                    when (u.path ?: "") {
                        "", "/" -> { injectUi(view); hideOverlay() }
                        "/login" -> { webLoaded = false; ensureWeb() } // session rejected: sign in again
                    }
                }
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame) {
                        webLoaded = false
                        showOverlay("Couldn't reach Pragon on your PC. Same Wi-Fi?", spin = false)
                        main.postDelayed({ ensureWeb() }, 4000)
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread { request.grant(request.resources) }
                }
                override fun onShowFileChooser(
                    w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = cb
                    val cam = cameraIntent()
                    val intent = if (params.isCaptureEnabled && cam != null) cam else {
                        Intent.createChooser(params.createIntent(), "Send a file").also {
                            if (cam != null) it.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cam))
                        }
                    }
                    return try { chooserLauncher.launch(intent); true } catch (e: Exception) {
                        fileCallback?.onReceiveValue(null); fileCallback = null; false
                    }
                }
            }
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }
        standalone = StandaloneView(
            this,
            onMic = { startVoice() },
            onSettings = { openSettings() },
            onSpeak = { speak(it) },
            onStopSpeaking = { tts?.stop() },
            onAttach = { attachLauncher.launch("*/*") },
            onFloat = { toggleFloat() },
        )
        overlay = standalone
        statusTv = standalone.statusLine
        spinner = ProgressBar(this)       // not shown: standalone chat has its own busy indicator
        settingsBtn = Button(this)        // not shown: Settings is the gear in the standalone header
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF0A0A0F.toInt())
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(this@MainActivity.overlay, FrameLayout.LayoutParams(-1, -1))
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) } // don't drop the PC link by accident
        })

        val need = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        permsLauncher.launch(need.toTypedArray())
    }

    // ---- flow ---------------------------------------------------------------
    private fun isPaired() = Prefs.token(this).isNotBlank()

    private fun ensureWeb() {
        if (!isPaired()) { showOverlay("Standalone mode - pair a PC in Settings to link up", spin = false); return }
        if (webLoaded) return
        if (RemoteBridge.socket == null) { showOverlay(Bridge.status); return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastLoginReq < 3000) return
        lastLoginReq = now
        awaitingLogin = true
        showOverlay("Opening PhoneView...")
        RemoteBridge.requestWebLogin()
        main.postDelayed({
            if (awaitingLogin) {
                awaitingLogin = false
                showOverlay("The PC didn't answer. Make sure the PC is running the updated Pragon files (pragon_phoneview/server.py).", spin = false)
            }
        }, 8000)
    }

    private fun showOverlay(msg: String, spin: Boolean = true, btn: Boolean = false) {
        statusTv.text = msg
        spinner.visibility = if (spin) View.VISIBLE else View.GONE
        settingsBtn.visibility = if (btn) View.VISIBLE else View.GONE
        overlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() { overlay.visibility = View.GONE }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun injectUi(v: WebView) {
        fun asset(n: String) = assets.open(n).bufferedReader().use { it.readText() }
        val script = "(function(){if(window.__pmDone)return;window.__pmDone=1;" +
            "var s=document.createElement('style');s.textContent=" + JSONObject.quote(asset("pm_inject.css")) + ";document.head.appendChild(s);" +
            "var d=document.createElement('div');d.innerHTML=" + JSONObject.quote(asset("pm_inject.html")) + ";" +
            "while(d.firstChild)document.body.appendChild(d.firstChild);" +
            asset("pm_inject.js") + "\n})();"
        v.evaluateJavascript(script, null)
    }

    private fun cameraIntent(): Intent? {
        if (Build.VERSION.SDK_INT >= 23 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val dir = File(cacheDir, "pics").apply { mkdirs() }
            val f = File.createTempFile("photo_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            cameraUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) { null }
    }

    // ---- bridge exposed to the injected page --------------------------------
    inner class PmBridge {
        @JavascriptInterface fun remote(action: String, value: String?, dx: Double, dy: Double) {
            if (action == "mouse_move") RemoteBridge.pcControl(action, null, dx, dy)
            else RemoteBridge.pcControl(action, value)
        }
        @JavascriptInterface fun typeText(text: String) { RemoteBridge.pcControl("type_text", text) }
        @JavascriptInterface fun openSettings() { runOnUiThread { this@MainActivity.openSettings() } }
        // Same mic as Standalone: opens the Google "Talk to Pragon" speech dialog.
        @JavascriptInterface fun startVoice() { runOnUiThread { this@MainActivity.startVoice() } }
    }

    // ---- RemoteBridge.Listener ---------------------------------------------
    override fun onConnection(connected: Boolean) {
        if (connected) ensureWeb()
    }

    override fun onWebLoginPath(path: String) {
        if (webLoaded) return
        awaitingLogin = false
        if (path.isBlank()) { showOverlay("Couldn't open PhoneView.", spin = false); return }
        webLoaded = true
        web.loadUrl("http://${Prefs.host(this)}:${Prefs.port(this)}$path")
    }

    override fun onChatLine(fromPhone: Boolean, text: String) {
        // PC-side errors for Remote actions (e.g. "only supported on Windows") - shown briefly, de-duplicated.
        val now = SystemClock.elapsedRealtime()
        if (text == lastToast && now - lastToastAt < 3000) return
        lastToast = text; lastToastAt = now
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---- lifecycle ------------------------------------------------------------
    override fun onResume() {
        super.onResume()
        RemoteBridge.listener = this
        Bridge.listener = { s -> if (!webLoaded) { statusTv.text = s; ensureWeb() } }
        if (!isPaired()) {
            if (webLoaded) { // "Disconnect and forget this PC" was used in Settings
                webLoaded = false
                web.loadUrl("about:blank")
                WebStorage.getInstance().deleteAllData()
            }
            ensureWeb()
            return
        }
        settingsAutoShown = false
        ContextCompat.startForegroundService(this, Intent(this, PragonService::class.java))
        ensureWeb()
    }

    override fun onPause() {
        Bridge.listener = null
        RemoteBridge.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }
}
