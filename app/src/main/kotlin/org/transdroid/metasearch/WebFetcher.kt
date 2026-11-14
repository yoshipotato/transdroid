/*
 * Copyright 2010-2026 Eric Kok et al.
 *
 * Transdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Transdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transdroid. If not, see <https://www.gnu.org/licenses/>.
 */
package org.transdroid.metasearch

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** The user dismissed the verification dialog: the search shouldn't ask this site again. */
class VerificationCancelledException : IOException("verification cancelled")

/** The foreground activity, for the rare Cloudflare check that needs the user to tick a box in a dialog. */
object ActivityHolder {
    @Volatile var current: Activity? = null
}

/**
 * Loads a page in a real WebView so Cloudflare's JavaScript / Turnstile checks run in a genuine browser engine.
 * First tries silently; if the check isn't passed after a few seconds, shows the WebView in a dialog so the
 * user can tick the box. Cookies (cf_clearance) persist in the WebView CookieManager, so later loads are instant.
 * The WebView honours the Android system proxy on its own.
 */
class WebFetcher(app: Context) {
    private class Res(val html: String?, val error: String?, val cancelled: Boolean = false)

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** Must be constructed on the main thread. */
    val userAgent: String = WebSettings.getDefaultUserAgent(app.applicationContext).replace("; wv", "")

    @Volatile private var generation = 0
    @Volatile private var aborted = false
    @Volatile private var cancelCurrent: (() -> Unit)? = null

    /** Stops the page being loaded right now (closing its verification dialog) and any loads still waiting their turn. */
    fun abort() {
        aborted = true
        generation++
        main.post { cancelCurrent?.invoke() }
    }

    fun resume() { aborted = false }

    @Throws(IOException::class)
    fun fetch(url: String): String {
        val startedAt = generation
        synchronized(lock) {
            if (aborted || startedAt != generation) throw VerificationCancelledException()
            val q = ArrayBlockingQueue<Res>(1)
            main.post { start(url, q, startedAt) }
            var r: Res? = null
            try {
                r = q.poll(TOTAL_TIMEOUT_MS / 1000 + 15, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (r == null) throw IOException("WebView timed out")
            if (r.cancelled) throw VerificationCancelledException()
            return r.html ?: throw IOException(r.error ?: "WebView failed")
        }
    }

    private fun start(url: String, q: ArrayBlockingQueue<Res>, startedAt: Int) {
        // Stop was pressed after this load was queued: don't open a page (or a dialog) at all
        if (aborted || startedAt != generation) { q.offer(Res(null, "stopped", cancelled = true)); return }
        val activity = ActivityHolder.current
        if (activity == null || activity.isFinishing || activity.isDestroyed) { q.offer(Res(null, "app closed")); return }

        // Fail closed: the WebView must be forced onto the system proxy, or it isn't used at all.
        val sys = ProxyGuard.current()
        if (sys == null) { q.offer(Res(null, "No system HTTP proxy configured - connection blocked")); return }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            q.offer(Res(null, "This WebView can't be forced onto the system proxy - blocked")); return
        }

        val wv = WebView(activity)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = userAgent
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        var done = false
        var loaded = false
        var dialog: AlertDialog? = null
        val t0 = SystemClock.uptimeMillis()

        fun finish(r: Res) {
            if (done) return
            done = true
            try { dialog?.dismiss() } catch (e: Exception) { }
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
            q.offer(r)
        }

        cancelCurrent = { finish(Res(null, "stopped", cancelled = true)) }

        fun showDialog() {
            val frame = FrameLayout(activity)
            val h = (activity.resources.displayMetrics.heightPixels * 0.6).toInt()
            frame.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            if (done || aborted || startedAt != generation) return
            val d = AlertDialog.Builder(activity)
                .setTitle("Verification required")
                .setMessage("Complete the security check below. This closes by itself when it passes.")
                .setView(frame)
                .setNegativeButton("Cancel") { _, _ -> finish(Res(null, "verification cancelled", cancelled = true)) }
                .setOnCancelListener { finish(Res(null, "verification cancelled", cancelled = true)) }
                .create()
            d.setCanceledOnTouchOutside(false)
            dialog = d
            d.show()
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String?) {
                if (u != null && u != "about:blank") loaded = true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) finish(Res(null, "load error: ${error.description}"))
            }
        }

        val poll = object : Runnable {
            override fun run() {
                if (done) return
                if (aborted || startedAt != generation) { finish(Res(null, "stopped", cancelled = true)); return }
                val elapsed = SystemClock.uptimeMillis() - t0
                if (activity.isFinishing || activity.isDestroyed) { finish(Res(null, "app closed")); return }
                if (elapsed > TOTAL_TIMEOUT_MS) { finish(Res(null, "verification timed out")); return }
                if (loaded) {
                    wv.evaluateJavascript("document.documentElement.outerHTML") { v ->
                        if (!done) {
                            val html = decodeJs(v)
                            if (html != null && html.length > 50 && !Cloudflare.looksLikeChallengeBody(html)) {
                                finish(Res(html, null))
                            }
                        }
                    }
                }
                if (!done && dialog == null && elapsed > SHOW_UI_AFTER_MS) showDialog()
                if (!done) main.postDelayed(this, 1000)
            }
        }

        val hostPort = if (sys.host.contains(':')) "[${sys.host}]:${sys.port}" else "${sys.host}:${sys.port}"
        // No direct-fallback rule is added, so if the proxy is unreachable the load fails instead of going direct.
        val cfg = ProxyConfig.Builder().addProxyRule(hostPort).build()
        ProxyController.getInstance().setProxyOverride(cfg, { r -> main.post(r) }, {
            if (!done) {
                wv.loadUrl(url)
                main.postDelayed(poll, 1000)
            }
        })
    }

    private fun decodeJs(v: String?): String? {
        if (v == null || v == "null") return null
        return try { JSONTokener(v).nextValue() as? String } catch (e: Exception) { null }
    }

    companion object {
        private const val SHOW_UI_AFTER_MS = 12_000L
        private const val TOTAL_TIMEOUT_MS = 150_000L
    }
}
