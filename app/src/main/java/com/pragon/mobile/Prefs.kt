package com.pragon.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper

/** Saved PC address + the token the PC gave us when we paired. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("pragon", Context.MODE_PRIVATE)
    fun host(c: Context): String = sp(c).getString("host", "") ?: ""
    fun port(c: Context): Int = sp(c).getInt("port", 8000)
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun save(c: Context, host: String, port: Int, token: String) =
        sp(c).edit().putString("host", host).putInt("port", port).putString("token", token).apply()
    fun saveToken(c: Context, token: String) = sp(c).edit().putString("token", token).apply()
    // Standalone AI: the user's own Gemini key wins; otherwise the key cached from the paired PC.
    fun aiKey(c: Context): String = sp(c).getString("ai_key", "") ?: ""
    fun pcAiKey(c: Context): String = sp(c).getString("pc_ai_key", "") ?: ""
    fun aiModel(c: Context): String {
        val m = sp(c).getString("ai_model", "") ?: ""
        return if (m.isBlank()) "gemini-2.5-flash" else m
    }
    fun effectiveAiKey(c: Context): String = aiKey(c).ifBlank { pcAiKey(c) }
    fun saveAi(c: Context, key: String, model: String) =
        sp(c).edit().putString("ai_key", key).putString("ai_model", model).apply()
    fun savePcAiKey(c: Context, key: String) = sp(c).edit().putString("pc_ai_key", key).apply()

    // ---- AI engine for Standalone: "gemini" (online), "ollama" (offline / LAN) or "openai" (any OpenAI-compatible server)
    fun engine(c: Context): String {
        val e = sp(c).getString("engine", "gemini") ?: "gemini"
        return if (e == "ollama" || e == "openai") e else "gemini"
    }
    fun ollamaHost(c: Context): String {
        val h = sp(c).getString("ollama_host", "") ?: ""
        if (h.isNotBlank()) return h
        val pc = host(c)
        return if (pc.isNotBlank()) "http://$pc:11434" else "http://127.0.0.1:11434"
    }
    fun ollamaModel(c: Context): String {
        val m = sp(c).getString("ollama_model", "") ?: ""
        return if (m.isBlank()) "hermes3" else m
    }
    fun openaiBase(c: Context): String {
        val b = sp(c).getString("openai_base", "") ?: ""
        return if (b.isBlank()) "http://127.0.0.1:8080/v1" else b
    }
    fun openaiKey(c: Context): String = sp(c).getString("openai_key", "") ?: ""
    fun openaiModel(c: Context): String = sp(c).getString("openai_model", "") ?: ""
    fun saveEngine(c: Context, engine: String, oHost: String, oModel: String, base: String, key: String?, cModel: String) {
        val e = sp(c).edit()
            .putString("engine", engine)
            .putString("ollama_host", oHost)
            .putString("ollama_model", oModel)
            .putString("openai_base", base)
            .putString("openai_model", cModel)
        if (key != null) e.putString("openai_key", key)   // null = keep the saved key
        e.apply()
    }
    /** Short label for the chip in the Standalone header. */
    fun engineLabel(c: Context): String = when (engine(c)) {
        "ollama" -> "OLLAMA"
        "openai" -> "CUSTOM"
        else -> "GEMINI"
    }

    // ---- Float mode bubble position
    fun floatX(c: Context, def: Int): Int = sp(c).getInt("float_x", def)
    fun floatY(c: Context, def: Int): Int = sp(c).getInt("float_y", def)
    fun saveFloatPos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("float_x", x).putInt("float_y", y).apply()

    /** Forget the PC but keep the user's own AI settings. */
    fun clear(c: Context) {
        val keep = listOf("ai_key", "ai_model", "engine", "ollama_host", "ollama_model",
            "openai_base", "openai_key", "openai_model")
        val keepInts = mapOf("float_x" to sp(c).getInt("float_x", -1), "float_y" to sp(c).getInt("float_y", -1))
        val saved = keep.associateWith { sp(c).getString(it, "") ?: "" }
        val ed = sp(c).edit().clear()
        saved.forEach { (k, v) -> ed.putString(k, v) }
        keepInts.forEach { (k, v) -> if (v >= 0) ed.putInt(k, v) }
        // a PC-derived Ollama host should not outlive the PC
        ed.apply()
    }
}

/** Tiny bridge so the service can show its status in the activity. */
object Bridge {
    @Volatile var status: String = "Not connected"
    @Volatile var listener: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    fun set(s: String) {
        status = s
        main.post { listener?.invoke(s) }
    }
}
