package com.pragon.mobile

import android.content.Context
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Offline / self-hosted engines for Standalone mode:
 *  - "ollama": Ollama's native /api/chat (hermes3, llama3.2, qwen, gemma, anything you `ollama pull`)
 *  - "openai": any OpenAI-compatible server (LM Studio, llama.cpp server, vLLM, OpenRouter, an OpenClaw gateway ...)
 * Both can call the phone_control tool, same as Gemini. Blocking: call from a background thread.
 */
object LocalLlmClient {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)   // a local model can take a while to load the first time
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val noTools = HashSet<String>()      // models that said "does not support tools"

    private const val SYSTEM =
        "You are P.R.A.G.O.N, a friendly, concise assistant running on the user's Android phone. " +
        "Reply in short, natural sentences that sound good when spoken aloud: no markdown, no lists, no emojis. " +
        "You can control this phone with the phone_control tool (open and close apps, open links, search YouTube or Google, " +
        "dial a number, volume, media keys, home/back, swipe, type). Use it only when the user asks for a phone action, " +
        "then confirm briefly what you did. If a tool reports a problem, tell the user plainly."

    /** Tool schema in the OpenAI / Ollama function format. */
    private fun tools(): JSONArray {
        val actions = JSONArray(
            listOf("open_app", "close_app", "open_url", "youtube_search", "web_search", "call", "key", "swipe", "type_text", "status")
        )
        val props = JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", actions).put(
                "description",
                "open_app: value=app name. close_app: value=app name (force-stops it). open_url: value=link. youtube_search / web_search: value=query. " +
                "call: value=phone number (opens the dialer). key: value one of home, back, recents, notifications, " +
                "quick_settings, lock, wake, volume_up, volume_down, mute, play_pause, next, previous. " +
                "swipe: value=up, down, left or right. type_text: value=text to type. status: phone info."
            ))
            .put("value", JSONObject().put("type", "string").put("description", "Argument for the action."))
        val fn = JSONObject()
            .put("name", "phone_control")
            .put("description", "Control the user's Android phone.")
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray().put("action")))
        return JSONArray().put(JSONObject().put("type", "function").put("function", fn))
    }

    // ---------- address helpers ----------
    fun ollamaBase(raw: String): String {
        var h = raw.trim().trimEnd('/')
        if (h.isEmpty()) h = "127.0.0.1"
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://$h"
        val afterScheme = h.substringAfter("://")
        if (!afterScheme.contains(":") && h.startsWith("http://")) h += ":11434"
        return h
    }

    fun openaiBase(raw: String): String {
        var b = raw.trim().trimEnd('/')
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://$b"
        return b
    }

    private class Call(val id: String, val name: String, val args: JSONObject)
    private class Parsed(val text: String, val calls: List<Call>, val assistant: JSONObject)

    fun reply(ctx: Context, history: MutableList<JSONObject>, userText: String, files: List<GeminiClient.Attachment>): String {
        val engine = Prefs.engine(ctx)
        val isOllama = engine == "ollama"
        val model = if (isOllama) Prefs.ollamaModel(ctx) else Prefs.openaiModel(ctx)
        if (model.isBlank()) {
            return "Pick a model first. Open Settings, choose the Custom engine and type the model name your server uses."
        }
        if (history.size > 30) history.clear()
        val startSize = history.size

        // ---- build the user message (images + text files supported) ----
        var text = userText
        val images = mutableListOf<GeminiClient.Attachment>()
        val skipped = mutableListOf<String>()
        for (f in files) {
            when {
                f.mime.startsWith("image/") -> images.add(f)
                f.mime.startsWith("text/") -> {
                    val body = try { String(Base64.decode(f.b64, Base64.DEFAULT), Charsets.UTF_8).take(20000) } catch (e: Exception) { "" }
                    text += "\n\n[File ${f.name}]\n$body"
                }
                else -> skipped.add(f.name)
            }
        }
        val user = JSONObject().put("role", "user")
        if (isOllama) {
            user.put("content", text)
            if (images.isNotEmpty()) {
                val arr = JSONArray()
                for (a in images) arr.put(a.b64)
                user.put("images", arr)
            }
        } else if (images.isNotEmpty()) {
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text))
            for (a in images) {
                parts.put(JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:${a.mime};base64,${a.b64}")))
            }
            user.put("content", parts)
        } else {
            user.put("content", text)
        }
        history.add(user)

        var lastText = ""
        try {
            for (step in 0 until 5) {
                val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM))
                for (m in history) msgs.put(m)
                val useTools = model !in noTools
                val parsed = try {
                    if (isOllama) chatOllama(ctx, model, msgs, useTools) else chatOpenAi(ctx, model, msgs, useTools)
                } catch (e: ToolsUnsupported) {
                    noTools.add(model)
                    if (isOllama) chatOllama(ctx, model, msgs, false) else chatOpenAi(ctx, model, msgs, false)
                }
                history.add(parsed.assistant)
                if (parsed.text.isNotBlank()) lastText = parsed.text
                if (parsed.calls.isEmpty()) {
                    val note = if (skipped.isNotEmpty())
                        " (I can't read ${skipped.joinToString(", ")} with this engine.)" else ""
                    return lastText.ifBlank { "Done." } + note
                }
                for (c in parsed.calls) {
                    val res = CommandExecutor.run(
                        ctx,
                        JSONObject().put("action", c.args.optString("action")).put("value", c.args.optString("value")),
                        fromStandalone = true
                    )
                    val out = JSONObject().put("ok", res.ok).put("message", res.msg).toString()
                    val tm = JSONObject().put("role", "tool").put("content", out)
                    if (isOllama) tm.put("tool_name", c.name) else tm.put("tool_call_id", c.id)
                    history.add(tm)
                }
            }
        } catch (e: ToolsUnsupported) {
            trim(history, startSize)
            return "This model can't be used right now."
        } catch (e: Exception) {
            trim(history, startSize)
            val where = if (isOllama) Prefs.ollamaHost(ctx) else Prefs.openaiBase(ctx)
            return "I couldn't reach the model at $where (${e.message}). Is the server running and on the same network?"
        } finally {
            // keep big image data out of the running conversation
            if (history.size > startSize && history[startSize].has("images")) {
                history[startSize] = JSONObject().put("role", "user").put("content", text)
            }
        }
        return lastText.ifBlank { "Done." }
    }

    private class ToolsUnsupported : Exception()

    private fun chatOllama(ctx: Context, model: String, msgs: JSONArray, useTools: Boolean): Parsed {
        val body = JSONObject().put("model", model).put("messages", msgs).put("stream", false)
        if (useTools) body.put("tools", tools())
        val req = Request.Builder()
            .url(ollamaBase(Prefs.ollamaHost(ctx)) + "/api/chat")
            .post(body.toString().toRequestBody(JSON)).build()
        val (ok, code, raw) = http.newCall(req).execute().use { Triple(it.isSuccessful, it.code, it.body?.string() ?: "") }
        if (!ok) {
            val err = try { JSONObject(raw).optString("error") } catch (e: Exception) { raw.take(200) }
            if (useTools && code == 400 && err.contains("tool", true)) throw ToolsUnsupported()
            if (code == 404) throw RuntimeException("model \"$model\" isn't installed - pull it in Settings")
            throw RuntimeException(err.ifBlank { "HTTP $code" })
        }
        val msg = JSONObject(raw).optJSONObject("message") ?: throw RuntimeException("empty answer")
        val calls = mutableListOf<Call>()
        val tc = msg.optJSONArray("tool_calls")
        if (tc != null) for (i in 0 until tc.length()) {
            val fn = tc.getJSONObject(i).optJSONObject("function") ?: continue
            val a = fn.opt("arguments")
            val args = when (a) {
                is JSONObject -> a
                is String -> try { JSONObject(a) } catch (e: Exception) { JSONObject() }
                else -> JSONObject()
            }
            calls.add(Call("call_$i", fn.optString("name"), args))
        }
        return Parsed(clean(msg.optString("content")), calls, msg)
    }

    private fun chatOpenAi(ctx: Context, model: String, msgs: JSONArray, useTools: Boolean): Parsed {
        val body = JSONObject().put("model", model).put("messages", msgs)
        if (useTools) body.put("tools", tools())
        val rb = Request.Builder()
            .url(openaiBase(Prefs.openaiBase(ctx)) + "/chat/completions")
            .post(body.toString().toRequestBody(JSON))
        val key = Prefs.openaiKey(ctx)
        if (key.isNotBlank()) rb.header("Authorization", "Bearer $key")
        val (ok, code, raw) = http.newCall(rb.build()).execute().use { Triple(it.isSuccessful, it.code, it.body?.string() ?: "") }
        if (!ok) {
            val err = try { JSONObject(raw).optJSONObject("error")?.optString("message") ?: raw.take(200) } catch (e: Exception) { raw.take(200) }
            if (useTools && code == 400 && err.contains("tool", true)) throw ToolsUnsupported()
            throw RuntimeException(err.ifBlank { "HTTP $code" })
        }
        val msg = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw RuntimeException("empty answer")
        val calls = mutableListOf<Call>()
        val tc = msg.optJSONArray("tool_calls")
        if (tc != null) for (i in 0 until tc.length()) {
            val t = tc.getJSONObject(i)
            val fn = t.optJSONObject("function") ?: continue
            val args = try { JSONObject(fn.optString("arguments", "{}")) } catch (e: Exception) { JSONObject() }
            calls.add(Call(t.optString("id", "call_$i"), fn.optString("name"), args))
        }
        val assistant = JSONObject().put("role", "assistant")
            .put("content", if (msg.isNull("content")) "" else msg.optString("content"))
        if (tc != null && tc.length() > 0) assistant.put("tool_calls", tc)
        return Parsed(clean(msg.optString("content")), calls, assistant)
    }

    /** Remove <think>...</think> blocks that reasoning models print. */
    private fun clean(s: String): String =
        s.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    private fun trim(history: MutableList<JSONObject>, size: Int) {
        while (history.size > size) history.removeAt(history.size - 1)
    }
}
