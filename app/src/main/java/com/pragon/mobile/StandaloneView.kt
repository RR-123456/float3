package com.pragon.mobile

import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The phone's own Pragon: a chat screen that works with no PC. It is shown
 * whenever the app is not showing the PC's PhoneView page. Text and voice in,
 * spoken replies out, and Gemini can operate the phone through phone_control.
 *
 * Looks like Pragon Mobile (PhoneView) but keeps only: mic (Google speech dialog),
 * mute, settings and attach. No Remote / Type-on-PC / Files / Devices / model tabs.
 */
class StandaloneView(
    context: Context,
    private val onMic: () -> Unit,
    private val onSettings: () -> Unit,
    private val onSpeak: (String) -> Unit,
    private val onStopSpeaking: () -> Unit,
    private val onAttach: () -> Unit,
    private val onFloat: () -> Unit = {},
) : LinearLayout(context) {

    val statusLine: TextView
    private val spinner: ProgressBar
    private val list: LinearLayout
    private val scroll: ScrollView
    private val input: EditText
    private val chips: TextView
    private val ring: View
    private val floatBtn: ImageView
    private val history = mutableListOf<JSONObject>()
    private val localHistory = mutableListOf<JSONObject>()
    private var lastEngineKey = ""
    private val engineChip: TextView
    private val pending = mutableListOf<GeminiClient.Attachment>()
    private val worker = Executors.newSingleThreadExecutor()
    private var spin: ObjectAnimator? = null
    private var busy = false
    private var muted = false

    private val cyan = 0xFF00D4FF.toInt()
    private val bg = 0xFF0A0A0F.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(radius: Int, fill: Int, stroke: Int, strokeDp: Int = 1) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        setStroke(dp(strokeDp), stroke)
    }

    private fun iconBtn(res: Int, size: Int, radius: Int, fill: Int, stroke: Int, tint: Int, desc: String, click: () -> Unit) =
        ImageView(context).apply {
            setImageResource(res)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = dp(if (size >= 40) 10 else 7)
            setPadding(p, p, p, p)
            background = box(radius, fill, stroke)
            contentDescription = desc
            setOnClickListener { click() }
        }

    init {
        orientation = VERTICAL
        setBackgroundColor(bg)
        isClickable = true

        val stage = FrameLayout(context)
        val wm = ImageView(context).apply {
            setImageResource(R.drawable.pragon_wm)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.22f
        }
        stage.addView(wm, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        stage.addView(col, FrameLayout.LayoutParams(-1, -1))
        addView(stage, LayoutParams(-1, -1))

        // ---- header: spinning-ring logo + P.R.A.G.O.N, then mute + settings ----
        ring = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.SWEEP_GRADIENT
                setColors(intArrayOf(cyan, 0x0000D4FF, 0x0000D4FF, cyan))
            }
        }
        val disc = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF05050A.toInt()) }
        }
        val logoImg = ImageView(context).apply {
            setImageResource(R.drawable.pragon_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
            }
            clipToOutline = true
        }
        val logo = FrameLayout(context).apply {
            addView(ring, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
            addView(disc, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
            addView(logoImg, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }

        val title = TextView(context).apply {
            text = "P.R.A.G.O.N"
            textSize = 17f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            letterSpacing = 0.12f
            setTextColor(cyan)
            setShadowLayer(dp(8).toFloat(), 0f, 0f, 0x6600D4FF)
        }
        statusLine = TextView(context).apply {
            textSize = 10f
            letterSpacing = 0.05f
            maxLines = 2
            setTextColor(0x8000D4FF.toInt())
            text = "Standalone mode"
        }
        val titleCol = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10), 0, 0, 0)
            addView(title)
            addView(statusLine)
        }
        spinner = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(cyan)
            visibility = View.GONE
        }
        val hFill = 0x0D00D4FF
        val hStroke = 0x1F00D4FF
        val hTint = 0x8000D4FF.toInt()
        val speaker = iconBtn(R.drawable.ic_speaker, 32, 8, hFill, hStroke, hTint, "Mute or unmute spoken replies") { }
        speaker.setOnClickListener {
            muted = !muted
            speaker.setImageResource(if (muted) R.drawable.ic_speaker_off else R.drawable.ic_speaker)
            if (muted) onStopSpeaking()
        }
        engineChip = TextView(context).apply {
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = box(8, 0x1A00D4FF, 0x4000D4FF)
            setOnClickListener { cycleEngine() }
        }
        refreshEngine()
        val gear = iconBtn(R.drawable.ic_gear, 32, 8, hFill, hStroke, hTint, "Settings") { onSettings() }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xEB0A0A0F.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(logo, LayoutParams(dp(42), dp(42)))
            addView(titleCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(spinner, LayoutParams(dp(20), dp(20)).also { it.rightMargin = dp(8) })
            addView(engineChip, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).also { it.rightMargin = dp(6) })
            addView(speaker, LayoutParams(dp(32), dp(32)).also { it.rightMargin = dp(6) })
            addView(gear, LayoutParams(dp(32), dp(32)))
        }
        col.addView(header, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
        col.addView(View(context).apply { setBackgroundColor(0x1A00D4FF) }, LayoutParams(-1, dp(1)))

        // ---- messages ----
        list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }
        scroll = ScrollView(context).apply { addView(list) }
        col.addView(scroll, LayoutParams(-1, 0, 1f))

        // ---- footer: [mic .......... attach] then [input][SEND] ----
        val fTint = 0x9900D4FF.toInt()
        val micBtn = iconBtn(R.drawable.ic_mic, 40, 10, 0x0F00D4FF, 0x2600D4FF, fTint, "Talk to Pragon") { onMic() }
        val clipBtn = iconBtn(R.drawable.ic_clip, 40, 10, 0x0F00D4FF, 0x2600D4FF, fTint, "Attach a file") { onAttach() }
        floatBtn = iconBtn(R.drawable.ic_float, 40, 10, 0x0F00D4FF, 0x2600D4FF, fTint, "Float mode: hovering voice bubble") { onFloat() }
        val tools = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(micBtn, LayoutParams(dp(40), dp(40)))
            addView(floatBtn, LayoutParams(dp(40), dp(40)).also { it.leftMargin = dp(8) })
            addView(View(context), LayoutParams(0, 1, 1f))
            addView(clipBtn, LayoutParams(dp(40), dp(40)))
        }
        chips = TextView(context).apply {
            textSize = 11f
            setTextColor(cyan)
            setPadding(dp(4), dp(6), dp(4), 0)
            visibility = View.GONE
            setOnClickListener { pending.clear(); refreshChips() }
        }
        input = EditText(context).apply {
            hint = "Send a command…"
            setHintTextColor(0x4DC8D2E6)
            setTextColor(0xFFE8EAF0.toInt())
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = box(12, 0x08FFFFFF, 0x1A00D4FF, 2)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submit(text.toString(), false)
                    true
                } else false
            }
        }
        val send = TextView(context).apply {
            text = "SEND"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = box(12, 0x1F00D4FF, 0x4000D4FF)
            setOnClickListener { submit(input.text.toString(), false) }
        }
        val inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(send, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also { it.leftMargin = dp(8) })
        }
        val footer = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(0xF20A0A0F.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(tools, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
            addView(chips, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
            addView(inputRow, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
        }
        col.addView(View(context).apply { setBackgroundColor(0x1400D4FF) }, LayoutParams(-1, dp(1)))
        col.addView(footer, LayoutParams(-1, LayoutParams.WRAP_CONTENT))

        addBubble(
            "Hi, I'm Pragon. I work right here on your phone: ask me anything, or tell me to open an app, " +
                "search YouTube or change the volume. Tap the round button next to the mic for float mode: " +
                "a hovering bubble that keeps listening while you use other apps. Pair with your PC in Settings to link up with it.",
            false
        )
    }

    // ---- engine chip: tap to switch Gemini (online) <-> Ollama <-> Custom ----
    private fun engineText(): String {
        return when (Prefs.engine(context)) {
            "ollama" -> "OLLAMA \u00B7 " + Prefs.ollamaModel(context)
            "openai" -> "CUSTOM" + Prefs.openaiModel(context).let { if (it.isBlank()) "" else " \u00B7 $it" }
            else -> "GEMINI \u00B7 ONLINE"
        }
    }

    fun refreshEngine() {
        engineChip.text = engineText()
        engineChip.maxWidth = (resources.displayMetrics.widthPixels * 0.42).toInt()
        engineChip.maxLines = 1
        engineChip.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun cycleEngine() {
        if (busy) return
        val order = listOf("gemini", "ollama", "openai")
        val next = order[(order.indexOf(Prefs.engine(context)) + 1) % order.size]
        Prefs.saveEngine(
            context, next, Prefs.ollamaHost(context), Prefs.ollamaModel(context),
            Prefs.openaiBase(context), null, Prefs.openaiModel(context)
        )
        refreshEngine()
        addNote(
            when (next) {
                "ollama" -> "Engine: Ollama (offline / your network) - model ${Prefs.ollamaModel(context)}"
                "openai" -> "Engine: Custom OpenAI-compatible server"
                else -> "Engine: Gemini (online)"
            }
        )
    }

    // keep the chip right after coming back from Settings
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) { refreshEngine(); refreshFloat() }
    }

    /** Lights the float button up while float mode is running. */
    fun refreshFloat() {
        val on = FloatService.running
        floatBtn.setColorFilter(if (on) 0xFF33FFB0.toInt() else 0x9900D4FF.toInt())
        floatBtn.background = box(10, if (on) 0x2233FFB0 else 0x0F00D4FF, if (on) 0x8033FFB0.toInt() else 0x2600D4FF)
    }

    // ---- logo ring animation only runs while this screen is showing ----
    private fun startRing() {
        if (spin == null) {
            spin = ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f).apply {
                duration = 4000
                interpolator = LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
            }
        }
        if (spin?.isStarted != true) spin?.start()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (visibility == VISIBLE) startRing() }
    override fun onDetachedFromWindow() { spin?.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView !== this || !isAttachedToWindow) return
        if (visibility == VISIBLE) startRing() else spin?.cancel()
    }

    // ---- messages ----
    private fun addBubble(text: String, mine: Boolean): TextView {
        val r = dp(14).toFloat()
        val small = dp(4).toFloat()
        val tv = TextView(context).apply {
            this.text = text
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            setTextColor(if (mine) 0xFFC8D0E0.toInt() else 0xFFA8D8E8.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(if (mine) 0x0AFFFFFF else 0x1400D4FF)
                setStroke(dp(1), if (mine) 0x14FFFFFF else 0x2600D4FF)
                // order: TL, TR, BR, BL (x,y pairs)
                cornerRadii = if (mine) floatArrayOf(r, r, r, r, small, small, r, r)
                else floatArrayOf(r, r, r, r, r, r, small, small)
            }
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
            setTextIsSelectable(true)
        }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = if (mine) Gravity.END else Gravity.START
        lp.setMargins(0, dp(5), 0, dp(5))
        list.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun addNote(text: String) {
        val tv = TextView(context).apply {
            this.text = text
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0x5900D4FF)
        }
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(4), 0, dp(4))
        list.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- attachments ----
    private fun refreshChips() {
        if (pending.isEmpty()) { chips.visibility = View.GONE; return }
        chips.text = "\uD83D\uDCCE " + pending.joinToString(", ") { it.name } + "   (tap to remove)"
        chips.visibility = View.VISIBLE
    }

    /** Called by MainActivity after the person picks a file with the paperclip. */
    fun addAttachment(name: String, mime: String, bytes: ByteArray) {
        val ok = mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/") ||
            mime.startsWith("text/") || mime == "application/pdf"
        if (!ok) { addNote("Pragon can read images, PDFs, audio, video and text files. $name isn't one of those."); return }
        val used = pending.sumOf { it.b64.length }
        if (bytes.size > 12 * 1024 * 1024 || used + bytes.size * 4 / 3 > 16 * 1024 * 1024) {
            addNote("$name is too big to send. Try a smaller file."); return
        }
        pending.add(GeminiClient.Attachment(name, mime, Base64.encodeToString(bytes, Base64.NO_WRAP)))
        refreshChips()
    }

    /** Send a message (typed or spoken). Spoken messages get spoken replies. */
    fun submit(text: String, viaVoice: Boolean) {
        val t = text.trim()
        if ((t.isEmpty() && pending.isEmpty()) || busy) return
        busy = true
        input.setText("")
        val files = pending.toList()
        pending.clear()
        refreshChips()
        val prompt = if (t.isEmpty()) "Please look at the attached file." else t
        val shown = if (files.isEmpty()) t else
            (if (t.isEmpty()) "" else t + "\n") + files.joinToString("\n") { "\uD83D\uDCCE " + it.name }
        addBubble(shown, true)
        val pendingBubble = addBubble("...", false)
        spinner.visibility = View.VISIBLE
        worker.execute {
            val reply = try {
                val key = Prefs.engine(context) + "|" + Prefs.ollamaModel(context) + "|" + Prefs.openaiModel(context) +
                    "|" + Prefs.ollamaHost(context) + "|" + Prefs.openaiBase(context)
                if (key != lastEngineKey) { localHistory.clear(); lastEngineKey = key }
                AiEngine.reply(context, history, localHistory, prompt, files)
            } catch (e: Exception) {
                "Something went wrong: ${e.message}"
            }
            post {
                pendingBubble.text = reply
                spinner.visibility = View.GONE
                busy = false
                scroll.fullScroll(View.FOCUS_DOWN)
                if (viaVoice && !muted) onSpeak(reply)
            }
        }
    }
}
