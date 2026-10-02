package com.pragon.mobile

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to Gemini directly from the phone (standalone mode). Gemini can call the
 * phone_control tool, which runs the same CommandExecutor the PC uses. Blocking:
 * call it from a background thread.
 */
object GeminiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM =
        "You are P.R.A.G.O.N, a friendly, concise assistant running on the user's Android phone. " +
        "Reply in short, natural sentences that sound good when spoken aloud: no markdown, no lists, no emojis. " +
        "You can control this phone with the phone_control tool (open and close apps, open links, search YouTube or Google, " +
        "dial a number, volume, media keys, home/back, swipe, type). Use it only when the user asks for a phone action, " +
        "then confirm briefly what you did. If a tool reports a problem, tell the user plainly."

    private fun tools(): JSONArray {
        val actions = JSONArray(
            listOf("open_app", "close_app", "open_url", "youtube_search", "web_search", "call", "key", "swipe", "type_text", "status")
        )
        val props = JSONObject()
            .put("action", JSONObject().put("type", "STRING").put("enum", actions).put(
                "description",
                "open_app: value=app name. close_app: value=app name (force-stops it). open_url: value=link. youtube_search / web_search: value=query. " +
                "call: value=phone number (opens the dialer). key: value one of home, back, recents, notifications, " +
                "quick_settings, lock, wake, volume_up, volume_down, mute, play_pause, next, previous. " +
                "swipe: value=up, down, left or right. type_text: value=text to type. status: phone info."
            ))
            .put("value", JSONObject().put("type", "STRING").put("description", "Argument for the action."))
        val decl = JSONObject()
            .put("name", "phone_control")
            .put("description", "Control the user's Android phone.")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray().put("action")))
        return JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(decl)))
    }

    class Attachment(val name: String, val mime: String, val b64: String)

    private fun content(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    fun reply(ctx: Context, history: MutableList<JSONObject>, userText: String, files: List<Attachment> = emptyList()): String {
        val key = Prefs.effectiveAiKey(ctx)
        if (key.isBlank()) {
            return "I need a Gemini API key first. Add yours in Settings, or pair with your PC once and I'll use its key."
        }
        val model = Prefs.aiModel(ctx)
        if (history.size > 30) history.clear()
        val startSize = history.size
        val userContent = content("user", userText)
        for (f in files) {
            userContent.getJSONArray("parts").put(
                JSONObject().put("inlineData", JSONObject().put("mimeType", f.mime).put("data", f.b64))
            )
        }
        history.add(userContent)
        var lastText = ""
        try {
            for (step in 0 until 5) {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                    .put("contents", JSONArray(history))
                    .put("tools", tools())
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val result = http.newCall(req).execute().use { resp ->
                    Pair(resp.isSuccessful, resp.body?.string() ?: "")
                }
                val ok = result.first
                val raw = result.second
                if (!ok) {
                    val msg = try { JSONObject(raw).getJSONObject("error").optString("message") } catch (e: Exception) { "" }
                    trim(history, startSize)
                    return "Gemini said no: " + msg.ifBlank { "the request failed." }
                }
                val modelContent = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                val parts = modelContent?.optJSONArray("parts")
                if (modelContent == null || parts == null) {
                    trim(history, startSize)
                    return "I couldn't come up with an answer to that."
                }
                modelContent.put("role", "model")
                history.add(modelContent)

                val text = StringBuilder()
                val responses = JSONArray()
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (p.has("functionCall")) {
                        val fc = p.getJSONObject("functionCall")
                        val args = fc.optJSONObject("args") ?: JSONObject()
                        val res = CommandExecutor.run(
                            ctx,
                            JSONObject().put("action", args.optString("action")).put("value", args.optString("value")),
                            fromStandalone = true
                        )
                        responses.put(
                            JSONObject().put(
                                "functionResponse",
                                JSONObject().put("name", fc.optString("name"))
                                    .put("response", JSONObject().put("ok", res.ok).put("message", res.msg))
                            )
                        )
                    } else if (p.has("text") && !p.optBoolean("thought", false)) {
                        text.append(p.optString("text"))
                    }
                }
                if (text.isNotBlank()) lastText = text.toString().trim()
                if (responses.length() == 0) return lastText.ifBlank { "Done." }
                history.add(JSONObject().put("role", "user").put("parts", responses))
            }
        } catch (e: Exception) {
            trim(history, startSize)
            return "I couldn't reach Gemini (${e.message}). Check your internet connection."
        } finally {
            // don't keep big file data in the running conversation; leave a text note instead
            if (files.isNotEmpty() && history.size > startSize) {
                history[startSize] = content("user", userText + " [attached: " + files.joinToString(", ") { it.name } + "]")
            }
        }
        return lastText.ifBlank { "Done." }
    }

    private fun trim(history: MutableList<JSONObject>, size: Int) {
        while (history.size > size) history.removeAt(history.size - 1)
    }
}
