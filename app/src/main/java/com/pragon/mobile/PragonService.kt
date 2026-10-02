package com.pragon.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps a WebSocket open to Pragon on the PC and executes the commands it sends.
 * First connection uses the one-time key from the QR code; the PC answers with a
 * token that is saved, so later connections are automatic.
 */
class PragonService : Service() {

    companion object {
        const val EXTRA_KEY = "key"
        const val ACTION_STOP = "com.pragon.mobile.STOP"
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var stopped = false
    private var authFailed = false
    private var pendingKey = ""
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForeground()
        } catch (e: Exception) {
            // Android 12+ can refuse a foreground start when the system restarts us in the
            // background; keep running as a normal service instead of crashing.
        }
        if (intent?.action == ACTION_STOP) {
            stopped = true
            socket?.cancel()
            Bridge.set("Disconnected")
            stopSelf()
            return START_NOT_STICKY
        }
        val key = intent?.getStringExtra(EXTRA_KEY)
        if (!key.isNullOrBlank()) {
            pendingKey = key
            authFailed = false
        }
        stopped = false
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        socket?.cancel()
        worker.shutdown()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("pragon", "Pragon connection", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "pragon")
            .setContentTitle("PragonMobile")
            .setContentText("Linked to your Pragon PC")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun connect() {
        socket?.cancel()
        val host = Prefs.host(this)
        val port = Prefs.port(this)
        if (host.isBlank()) {
            Bridge.set("Not paired yet - scan the QR from Pragon")
            return
        }
        if (Prefs.token(this).isBlank() && pendingKey.isBlank()) {
            Bridge.set("Not paired yet - scan the QR from Pragon")
            return
        }
        Bridge.set("Connecting to $host:$port ...")
        val req = Request.Builder().url("ws://$host:$port/ws/mobile").build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                RemoteBridge.socket = webSocket
                val hello = JSONObject().put("type", "hello").put("name", Build.MODEL)
                val token = Prefs.token(this@PragonService)
                if (token.isNotBlank()) hello.put("token", token) else hello.put("key", pendingKey)
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket === socket) handle(webSocket, text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === socket) { RemoteBridge.socket = null; RemoteBridge.setConnected(false); retry("Disconnected from the PC") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === socket) { RemoteBridge.socket = null; RemoteBridge.setConnected(false); retry("Can't reach Pragon (${t.message}). Same Wi-Fi?") }
            }
        })
    }

    private fun retry(msg: String) {
        Bridge.set(msg)
        if (stopped || authFailed) return
        main.postDelayed({ if (!stopped && !authFailed) connect() }, 4000)
    }

    private fun handle(ws: WebSocket, text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        when (o.optString("type")) {
            "paired" -> {
                Prefs.saveToken(this, o.optString("token"))
                pendingKey = ""
                Bridge.set("Connected to Pragon on your PC")
                RemoteBridge.setConnected(true)
                RemoteBridge.requestModel()
                RemoteBridge.requestAiKey()
            }
            "ready" -> {
                Bridge.set("Connected to Pragon on your PC")
                RemoteBridge.setConnected(true)
                RemoteBridge.requestModel()
                RemoteBridge.requestAiKey()
            }
            "error" -> {
                authFailed = true
                if (o.optString("code") == "bad_token") Prefs.saveToken(this, "")
                Bridge.set("Pairing failed: " + o.optString("message"))
                ws.close(1000, "auth")
            }
            "cmd" -> {
                val id = o.optInt("id")
                worker.execute {
                    val r = CommandExecutor.run(this, o)
                    ws.send(
                        JSONObject().put("type", "result").put("id", id)
                            .put("ok", r.ok).put("message", r.msg)
                            .put("unsupported", r.unsupported).toString()
                    )
                }
            }
            "ai_key" -> {
                val k = o.optString("key")
                if (k.isNotBlank()) Prefs.savePcAiKey(this, k)
            }
            "pc_result" -> {
                // Reply to something WE asked the PC to do (Remote screen / pc_cmd).
                if (!o.optBoolean("ok", true)) RemoteBridge.deliver(
                    JSONObject().put("type", "sys").put("text", "PC: " + o.optString("message"))
                )
            }
            "sys", "toast", "model_changed", "web_login" -> RemoteBridge.deliver(o)
        }
    }
}
