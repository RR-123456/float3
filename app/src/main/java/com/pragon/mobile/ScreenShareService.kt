package com.pragon.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Streams this phone's screen to Pragon on the PC as small JPEG frames
 * (about 5 per second) over /ws/phone-screen. Runs only while the user
 * has accepted Android's screen-capture prompt; stopping the notification
 * or the PC's Stop button ends it.
 */
class ScreenShareService : Service() {

    companion object {
        const val ACTION_STOP = "com.pragon.mobile.SCREEN_STOP"
        @Volatile var running = false
    }

    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var socket: WebSocket? = null
    private var thread: HandlerThread? = null
    private var stopping = false
    private var lastSent = 0L
    private var width = 0
    private var height = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        try {
            startInForeground()
        } catch (e: Exception) {
        }
        if (data == null || running) {
            if (!running) stopAll()
            return START_NOT_STICKY
        }
        try {
            begin(code, data)
        } catch (e: Exception) {
            stopAll()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("pragon_screen", "Screen sharing", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = android.app.PendingIntent.getService(
            this, 1, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "pragon_screen")
            .setContentTitle("Sharing your screen with Pragon")
            .setContentText("Tap to stop")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(stopIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, n)
        }
    }

    private fun begin(code: Int, data: Intent) {
        val host = Prefs.host(this)
        val port = Prefs.port(this)
        val token = Prefs.token(this)
        if (host.isBlank() || token.isBlank()) {
            stopAll()
            return
        }

        val ht = HandlerThread("pragon-screen").also { it.start() }
        thread = ht
        val handler = Handler(ht.looper)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        // Android 14+ requires a callback to be registered before capturing.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopAll()
            }
        }, handler)

        val dm = resources.displayMetrics
        val scale = if (dm.widthPixels > 720) 720.0 / dm.widthPixels else 1.0
        width = (dm.widthPixels * scale).toInt().coerceAtLeast(2)
        height = (dm.heightPixels * scale).toInt().coerceAtLeast(2)

        val req = Request.Builder()
            .url("ws://$host:$port/ws/phone-screen?token=" + java.net.URLEncoder.encode(token, "UTF-8"))
            .build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === socket) stopAll()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === socket) stopAll()
            }
        })

        val ir = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = ir
        display = proj.createVirtualDisplay(
            "pragon-screen", width, height, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, handler
        )
        ir.setOnImageAvailableListener({ r -> onFrame(r) }, handler)
        running = true
    }

    private fun onFrame(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
        if (img == null) return
        try {
            val now = SystemClock.elapsedRealtime()
            val ws = socket
            if (ws == null || now - lastSent < 200 || ws.queueSize() > 600_000L) return
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * width
            val full = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val frame = Bitmap.createBitmap(full, 0, 0, width, height)
            val out = ByteArrayOutputStream()
            frame.compress(Bitmap.CompressFormat.JPEG, 55, out)
            full.recycle()
            frame.recycle()
            ws.send(okio.ByteString.of(*out.toByteArray()))
            lastSent = now
        } catch (e: Exception) {
            // skip this frame
        } finally {
            img.close()
        }
    }

    private fun stopAll() {
        if (stopping) return
        stopping = true
        running = false
        try { reader?.setOnImageAvailableListener(null, null) } catch (e: Exception) {}
        try { display?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        try { socket?.close(1000, "done") } catch (e: Exception) {}
        try { thread?.quitSafely() } catch (e: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }
}
