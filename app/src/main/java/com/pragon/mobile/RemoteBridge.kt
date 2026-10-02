package com.pragon.mobile

import android.os.Handler
import android.os.Looper
import okhttp3.WebSocket
import org.json.JSONObject

/**
 * Everything the Remote screen (chat / mic / model pills / PC control)
 * needs from PragonService's live socket, without the UI touching OkHttp
 * or JSON directly. PragonService pushes into this; MainActivity reads it.
 */
object RemoteBridge {

    interface Listener {
        fun onChatLine(fromPhone: Boolean, text: String) {}
        fun onModel(current: String, choices: List<String>) {}
        fun onConnection(connected: Boolean) {}
        fun onWebLoginPath(path: String) {}
    }

    @Volatile var socket: WebSocket? = null
    @Volatile var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1

    fun setConnected(connected: Boolean) = main.post { listener?.onConnection(connected) }

    fun deliver(o: JSONObject) {
        when (o.optString("type")) {
            "sys", "toast" -> main.post { listener?.onChatLine(false, o.optString("text")) }
            "web_login" -> main.post { listener?.onWebLoginPath(o.optString("path")) }
            "model_changed" -> {
                val choices = mutableListOf<String>()
                o.optJSONArray("models")?.let { arr -> for (i in 0 until arr.length()) choices.add(arr.getString(i)) }
                main.post { listener?.onModel(o.optString("model"), choices) }
            }
        }
    }

    /** Ask the PC to mint a session for the real PhoneView web app (app.html),
     * so it can be embedded exactly as-is instead of reimplemented natively.
     * The result arrives via Listener.onWebLoginPath(). */
    fun requestWebLogin() {
        socket?.send(JSONObject().put("type", "web_login").toString())
    }

    /** Phone -> PC chat message (mirrors PhoneView's text box). */
    fun sendChat(text: String) {
        socket?.send(JSONObject().put("type", "chat").put("text", text).toString())
        main.post { listener?.onChatLine(true, text) }
    }

    fun requestAiKey() {
        socket?.send(JSONObject().put("type", "get_ai_key").toString())
    }

    fun requestModel() {
        socket?.send(JSONObject().put("type", "get_model").toString())
    }

    fun setModel(model: String) {
        socket?.send(JSONObject().put("type", "set_model").put("model", model).toString())
    }

    fun wakePc() {
        socket?.send(JSONObject().put("type", "wake").toString())
    }

    fun sendMic(pcm16: ByteArray) {
        socket?.send(okio.ByteString.of(*pcm16))
    }

    /** Phone -> PC control (open app / media keys / lock / text_command / mouse). */
    fun pcControl(action: String, value: String? = null, dx: Double? = null, dy: Double? = null) {
        val o = JSONObject().put("type", "pc_cmd").put("id", nextId++).put("action", action)
        if (!value.isNullOrEmpty()) o.put("value", value)
        if (dx != null) o.put("dx", dx)
        if (dy != null) o.put("dy", dy)
        socket?.send(o.toString())
    }
}
