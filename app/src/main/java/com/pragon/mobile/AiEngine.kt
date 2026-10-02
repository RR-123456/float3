package com.pragon.mobile

import android.content.Context
import org.json.JSONObject

/** Picks the engine chosen in Settings: Gemini (online), Ollama or a custom OpenAI-compatible server (offline / self-hosted). */
object AiEngine {
    fun reply(
        ctx: Context,
        geminiHistory: MutableList<JSONObject>,
        localHistory: MutableList<JSONObject>,
        userText: String,
        files: List<GeminiClient.Attachment>,
    ): String = when (Prefs.engine(ctx)) {
        "ollama", "openai" -> LocalLlmClient.reply(ctx, localHistory, userText, files)
        else -> GeminiClient.reply(ctx, geminiHistory, userText, files)
    }
}
