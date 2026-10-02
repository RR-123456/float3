package com.pragon.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "hands" of the app: global buttons (Home/Back/Recents...), taps, swipes
 * and typing. Android only allows this through an Accessibility Service that the
 * user switches on once in Settings.
 */
class PragonAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: PragonAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
        Bridge.set(Bridge.status) // refresh UI
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Performs a single-stroke gesture and waits for it to finish. */
    fun gesture(path: Path, durationMs: Long): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val done = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            val started = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    done.set(true); latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            if (!started) latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return done.get()
    }

    /** Types into the currently focused text box (appends to what's there). */
    fun typeText(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val existing = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ── close-app support ────────────────────────────────────────────────

    enum class StopResult { DONE, NOT_RUNNING, NO_BUTTON, NO_CONFIRM }

    /** Package of the app currently on screen (the Pragon bubble is not focusable, so it never counts). */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    private fun matches(n: AccessibilityNodeInfo, ids: List<String>, texts: List<String>): Boolean {
        val id = n.viewIdResourceName
        if (id != null && ids.contains(id)) return true
        val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim().lowercase()
        return t.isNotEmpty() && texts.any { t == it }
    }

    private fun find(root: AccessibilityNodeInfo?, ids: List<String>, texts: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        if (matches(root, ids, texts)) return root
        for (i in 0 until root.childCount) {
            val c = root.getChild(i) ?: continue
            find(c, ids, texts)?.let { return it }
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 5) {
            if (cur.isClickable && cur.isEnabled) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
            hops++
        }
        return false
    }

    private fun waitFor(ms: Long, ids: List<String>, texts: List<String>): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            find(rootInActiveWindow, ids, texts)?.let { return it }
            try { Thread.sleep(150) } catch (e: InterruptedException) { return null }
        }
        return null
    }

    /**
     * Assumes the system "App info" screen for the target app is opening. Presses Force stop,
     * confirms the dialog, then goes Back. Blocking: call from a background thread.
     */
    fun forceStopCurrentAppInfo(): StopResult {
        val btn = waitFor(
            5000,
            listOf("com.android.settings:id/force_stop_button", "com.android.settings:id/right_button"),
            listOf("force stop", "force close", "force-stop")
        ) ?: return StopResult.NO_BUTTON
        // Greyed out = the app isn't running.
        if (!btn.isEnabled) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return StopResult.NOT_RUNNING
        }
        if (!clickNode(btn)) return StopResult.NO_BUTTON
        val ok = waitFor(
            3000,
            listOf("android:id/button1"),
            listOf("ok", "yes", "force stop")
        ) ?: return StopResult.NO_CONFIRM
        clickNode(ok)
        try { Thread.sleep(500) } catch (e: InterruptedException) { }
        performGlobalAction(GLOBAL_ACTION_BACK) // leave App info, back to what was underneath
        return StopResult.DONE
    }
}
