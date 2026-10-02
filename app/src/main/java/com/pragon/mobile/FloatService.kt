package com.pragon.mobile

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Float mode: a small circular bubble that hovers over every app and keeps listening by voice.
 *
 *  STANDBY - bubble is amber. Mic is on but ONLY the wake phrase is acted on
 *            ("pragon im home" / "pragon daddy's home").
 *  ACTIVE  - bubble is cyan and pulses. Commands work: "open instagram", "close youtube", ...
 *  MUTED   - bubble is grey. Microphone is fully OFF. Tap the bubble to listen again.
 *
 * "hey pragon mute" -> MUTED.  "hey pragon goodbye" -> closes float mode.
 * Tap = cycle listening on/off.  Long-press = close.  Drag = move.
 */
class FloatService : Service() {

    enum class Mode { STANDBY, ACTIVE, MUTED }

    companion object {
        const val ACTION_START = "com.pragon.mobile.FLOAT_START"
        const val ACTION_STOP = "com.pragon.mobile.FLOAT_STOP"
        const val EXTRA_ACTIVE = "active"
        private const val CHANNEL = "pragon_float"
        private const val NOTIF_ID = 7

        @Volatile var running = false
        /** Why float mode last failed to start/show (empty = no problem). The app shows this. */
        @Volatile var lastError = ""
        @Volatile private var instance: FloatService? = null

        /** The in-app mic (Google dialog) needs the microphone: let it have it for a moment. */
        fun pauseForExternalMic(pause: Boolean) {
            instance?.let { s -> s.main.post { s.externalPause = pause; if (pause) s.stopRec() else s.scheduleRestart(500) } }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private var bubble: FrameLayout? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var ring: View? = null
    private var logo: ImageView? = null
    private var pulse: ObjectAnimator? = null
    private var captionView: TextView? = null

    private var mode = Mode.STANDBY
    private var rec: SpeechRecognizer? = null
    private var recActive = false
    private var externalPause = false
    private var speaking = false
    private var closing = false
    private var busy = false          // a command is running
    private var errorStreak = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val geminiHistory = mutableListOf<JSONObject>()
    private val localHistory = mutableListOf<JSONObject>()

    private val cyan = 0xFF00D4FF.toInt()
    private val amber = 0xFFFFB020.toInt()
    private val grey = 0xFF666B78.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** OnePlus/ColorOS often hides normal overlays. Windows added through the accessibility service are not hidden. */
    private var viaA11y = false
    private val overlayType: Int
        get() = if (viaA11y) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    override fun onBind(intent: Intent?): IBinder? = null

    // ── lifecycle ─────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }

        if (!running) {
            lastError = ""
            if (!Settings.canDrawOverlays(this) && PragonAccessibilityService.instance == null) {
                fail("Float mode needs 'Display over other apps' (or the Pragon accessibility service) turned on.")
                stopSelf(); return START_NOT_STICKY
            }
            try {
                startInForeground()
            } catch (e: Throwable) {
                fail("Android wouldn't start float mode: ${e.javaClass.simpleName} ${e.message}")
                stopSelf(); return START_NOT_STICKY
            }
            val a11y = PragonAccessibilityService.instance
            viaA11y = a11y != null
            wm = (a11y ?: this).getSystemService(Context.WINDOW_SERVICE) as WindowManager
            running = true
            instance = this
            addBubble()
            tts = TextToSpeech(this) { st ->
                if (st == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { main.post { onSpeechFinished() } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { main.post { onSpeechFinished() } }
                    })
                    ttsReady = true
                }
            }
            val active = intent?.getBooleanExtra(EXTRA_ACTIVE, false) ?: false
            setMode(if (active) Mode.ACTIVE else Mode.STANDBY)
            say(
                if (active) "Listening. Say what to do." else "Float mode on. Say: Pragon, I'm home.",
                speak = false
            )
        } else if (intent?.getBooleanExtra(EXTRA_ACTIVE, false) == true && mode != Mode.ACTIVE) {
            setMode(Mode.ACTIVE)
        }
        return START_NOT_STICKY // a system restart in the background couldn't use the mic anyway
    }

    private fun shutdown() {
        running = false
        instance = null
        main.removeCallbacksAndMessages(null)
        destroyRec()
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        pulse?.cancel()
        removeCaption()
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bubble = null
        worker.shutdownNow()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        if (running) {
            running = false
            instance = null
            destroyRec()
            bubble?.let { try { wm.removeView(it) } catch (e: Exception) { } }
            removeCaption()
            try { tts?.shutdown() } catch (e: Exception) { }
        }
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pragon float mode", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification("Starting...")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 11, Intent(this, FloatService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 12, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Pragon float mode")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Close", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text)) } catch (e: Exception) { }
    }

    // ── modes ─────────────────────────────────────────────────────────────

    private fun setMode(m: Mode) {
        mode = m
        errorStreak = 0
        refreshBubble()
        updateNotification(
            when (m) {
                Mode.STANDBY -> "Waiting. Say \"Pragon, I'm home\" to start listening."
                Mode.ACTIVE -> "Listening for commands. Say \"hey Pragon mute\" or \"goodbye\"."
                Mode.MUTED -> "Muted - tap the bubble to listen again."
            }
        )
        if (m == Mode.MUTED) { stopRec() } else scheduleRestart(200)
    }

    private fun cycleByTap() {
        when (mode) {
            Mode.MUTED, Mode.STANDBY -> { setMode(Mode.ACTIVE); say("Listening.", speak = false) }
            Mode.ACTIVE -> { setMode(Mode.MUTED); say("Muted. Tap to listen again.", speak = false) }
        }
    }

    // ── bubble UI ─────────────────────────────────────────────────────────

    private fun addBubble() {
        val size = dp(60)
        val ringV = View(this)
        val disc = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF05050A.toInt()) }
        }
        val logoV = ImageView(this).apply {
            setImageResource(R.drawable.pragon_bubble)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) }
            }
            clipToOutline = true
        }
        val root = FrameLayout(this).apply {
            addView(ringV, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            addView(disc, FrameLayout.LayoutParams(size - dp(6), size - dp(6), Gravity.CENTER))
            addView(logoV, FrameLayout.LayoutParams(size - dp(6), size - dp(6), Gravity.CENTER))
            elevation = dp(6).toFloat()
        }
        ring = ringV; logo = logoV; bubble = root

        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val lp = WindowManager.LayoutParams(
            size, size,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.floatX(this@FloatService, sw - size - dp(8))
            y = Prefs.floatY(this@FloatService, (sh * 0.35).toInt())
        }
        bubbleLp = lp
        root.setOnTouchListener(touch)
        try {
            wm.addView(root, lp)
        } catch (e: Throwable) {
            // The accessibility overlay was refused: fall back to the normal overlay window.
            var ok = false
            if (viaA11y && Settings.canDrawOverlays(this)) {
                try {
                    viaA11y = false
                    wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    lp.type = overlayType
                    wm.addView(root, lp)
                    ok = true
                } catch (e2: Throwable) { }
            }
            if (!ok) {
                fail("Couldn't show the bubble: ${e.javaClass.simpleName} ${e.message}")
                shutdown()
                return
            }
        }
        // Some phones accept the window but never draw it. Check, and say so instead of staying silent.
        main.postDelayed({
            val b = bubble
            if (running && b != null && !b.isAttachedToWindow) {
                fail("Android is hiding the Pragon bubble. Turn on 'Display over other apps' / 'Floating windows' for Pragon.")
                shutdown()
            }
        }, 800)
    }

    private fun refreshBubble() {
        val color = when (mode) { Mode.ACTIVE -> cyan; Mode.STANDBY -> amber; Mode.MUTED -> grey }
        ring?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x00000000)
            setStroke(dp(3), color)
        }
        logo?.alpha = if (mode == Mode.MUTED) 0.4f else 1f
        pulse?.cancel()
        ring?.scaleX = 1f; ring?.scaleY = 1f; ring?.alpha = 1f
        if (mode == Mode.ACTIVE) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.55f)
            ).apply {
                duration = 900
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
        }
    }

    /** Brief brightness flash when the mic starts hearing speech. */
    private fun flashHearing() {
        logo?.animate()?.scaleX(1.12f)?.scaleY(1.12f)?.setDuration(120)?.withEndAction {
            logo?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(160)?.start()
        }?.start()
    }

    private val touch = object : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var dragging = false
        private var longFired = false
        private val slop = ViewConfiguration.get(this@FloatService).scaledTouchSlop
        private val longPress = Runnable {
            if (!dragging) { longFired = true; goodbye() }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = bubbleLp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    dragging = false; longFired = false
                    main.postDelayed(longPress, 900)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragging = true; main.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(v, lp) } catch (ex: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (dragging) snapToEdge(v, lp)
                    else if (!longFired) cycleByTap()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }

    private fun snapToEdge(v: View, lp: WindowManager.LayoutParams) {
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val size = lp.width
        lp.x = if (lp.x + size / 2 < sw / 2) dp(8) else sw - size - dp(8)
        lp.y = lp.y.coerceIn(dp(24), sh - size - dp(48))
        try { wm.updateViewLayout(v, lp) } catch (e: Exception) { }
        Prefs.saveFloatPos(this, lp.x, lp.y)
    }

    // ── caption (small text under the bubble) ─────────────────────────────

    private val hideCaption = Runnable { removeCaption() }

    private fun removeCaption() {
        main.removeCallbacks(hideCaption)
        captionView?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        captionView = null
    }

    private fun showCaption(text: String) {
        val lp = bubbleLp ?: return
        removeCaption()
        val tv = TextView(this).apply {
            this.text = text.take(140)
            textSize = 12f
            setTextColor(0xFFE8EAF0.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xE60A0A0F.toInt())
                setStroke(dp(1), 0x6600D4FF)
            }
        }
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val w = dp(230)
        val below = lp.y + lp.height + dp(6)
        val clp = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (lp.x + lp.width / 2 - w / 2).coerceIn(dp(6), sw - w - dp(6))
            y = if (below + dp(70) < sh) below else (lp.y - dp(70)).coerceAtLeast(dp(24))
        }
        try { wm.addView(tv, clp); captionView = tv } catch (e: Exception) { return }
        main.postDelayed(hideCaption, 3500)
    }

    /** Shows [text] under the bubble and optionally says it aloud. */
    private fun say(text: String, speak: Boolean) {
        showCaption(text)
        if (speak && ttsReady) {
            speaking = true
            stopRec() // don't let the mic hear our own voice
            val r = tts?.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, null, "pragon")
            if (r != TextToSpeech.SUCCESS) { speaking = false; scheduleRestart(300) }
        }
    }

    private fun onSpeechFinished() {
        speaking = false
        if (closing) { shutdown(); return }
        scheduleRestart(400)
    }

    // ── speech recognition (re-armed after every phrase) ──────────────────

    private val restart = Runnable { startRec() }

    internal fun scheduleRestart(ms: Long) {
        main.removeCallbacks(restart)
        if (running) main.postDelayed(restart, ms)
    }

    private fun startRec() {
        if (!running || mode == Mode.MUTED || speaking || externalPause || closing) return
        if (recActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("This phone has no speech recognizer.", false); return
        }
        try {
            if (rec == null) {
                rec = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
            }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            recActive = true
            rec?.startListening(i)
        } catch (e: Exception) {
            recActive = false
            destroyRec()
            scheduleRestart(1500)
        }
    }

    internal fun stopRec() {
        main.removeCallbacks(restart)
        recActive = false
        try { rec?.cancel() } catch (e: Exception) { }
    }

    private fun destroyRec() {
        main.removeCallbacks(restart)
        recActive = false
        try { rec?.destroy() } catch (e: Exception) { }
        rec = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { flashHearing() }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            recActive = false
            errorStreak = 0
            val alts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            handleHeard(alts)
            scheduleRestart(350)
        }

        override fun onError(error: Int) {
            recActive = false
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    say("Microphone permission is off for Pragon.", false)
                    main.postDelayed({ shutdown() }, 2500)
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(150)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { destroyRec(); scheduleRestart(1200) }
                SpeechRecognizer.ERROR_CLIENT -> scheduleRestart(500)
                else -> { // network / server / audio problems: back off so we don't spin
                    errorStreak++
                    if (errorStreak == 3) say("Speech recognition isn't responding. Check your internet.", false)
                    if (errorStreak > 2) destroyRec()
                    scheduleRestart((1000L * errorStreak).coerceAtMost(8000L))
                }
            }
        }
    }

    // ── what to do with what was heard ────────────────────────────────────

    private fun handleHeard(alts: List<String>) {
        if (busy) return
        when (val cmd = VoiceParser.parse(alts, active = mode == Mode.ACTIVE)) {
            VoiceParser.Cmd.None -> {
                if (mode == Mode.ACTIVE && alts.isNotEmpty()) showCaption("Heard: \"${alts[0].take(60)}\"")
            }
            VoiceParser.Cmd.Wake -> {
                if (mode == Mode.ACTIVE) say("I'm already listening.", false)
                else { setMode(Mode.ACTIVE); say("Welcome home. I'm listening.", speak = true) }
            }
            VoiceParser.Cmd.Mute -> {
                setMode(Mode.MUTED)
                say("Muted. Tap the bubble to listen again.", speak = false)
            }
            VoiceParser.Cmd.Goodbye -> goodbye()
            is VoiceParser.Cmd.Phone -> runPhone(cmd)
            is VoiceParser.Cmd.Ask -> runAsk(cmd.text)
        }
    }

    private fun goodbye() {
        if (closing) return
        closing = true
        stopRec()
        say("Goodbye.", speak = ttsReady)
        main.postDelayed({ shutdown() }, 2500) // in case speech never reports back
    }

    private fun runPhone(cmd: VoiceParser.Cmd.Phone) {
        busy = true
        val label = when (cmd.action) {
            "open_app" -> "Opening ${cmd.value}..."
            "close_app" -> "Closing ${cmd.value.ifBlank { "this app" }}..."
            else -> "On it..."
        }
        showCaption(label)
        try {
            worker.execute {
                val res = try {
                    CommandExecutor.run(
                        this,
                        JSONObject().put("action", cmd.action).put("value", cmd.value)
                    )
                } catch (e: Exception) {
                    CommandExecutor.Res(false, "Something went wrong: ${e.message}")
                }
                main.post { busy = false; if (running) showCaption(res.msg) }
            }
        } catch (e: Exception) { busy = false }
    }

    private fun runAsk(text: String) {
        busy = true
        showCaption("Thinking...")
        try {
            worker.execute {
                val reply = try {
                    AiEngine.reply(this, geminiHistory, localHistory, text, emptyList())
                } catch (e: Exception) {
                    "Something went wrong: ${e.message}"
                }
                main.post { busy = false; if (running) say(reply, speak = true) }
            }
        } catch (e: Exception) { busy = false }
    }

    private fun fail(m: String) {
        lastError = m
        android.util.Log.e("PragonFloat", m)
        toast(m)
    }

    private fun toast(m: String) = main.post { Toast.makeText(applicationContext, m, Toast.LENGTH_LONG).show() }
}

/** Entry points used by the rest of the app. */
object FloatMode {

    /** null when float mode can start, otherwise a plain-language reason it can't. */
    fun problem(ctx: Context): String? {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "Allow the microphone for Pragon first."
        if (!Settings.canDrawOverlays(ctx) && PragonAccessibilityService.instance == null)
            return "Allow 'Display over other apps' for Pragon first."
        return null
    }

    fun start(ctx: Context, active: Boolean) {
        ctx.startForegroundService(
            Intent(ctx, FloatService::class.java).setAction(FloatService.ACTION_START).putExtra(FloatService.EXTRA_ACTIVE, active)
        )
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, FloatService::class.java).setAction(FloatService.ACTION_STOP))
    }

    /** An app was just opened through Pragon standalone: show the bubble and start listening. */
    fun autoStart(ctx: Context) {
        if (FloatService.running || problem(ctx) != null) return
        try { start(ctx, true) } catch (e: Exception) { }
    }
}
