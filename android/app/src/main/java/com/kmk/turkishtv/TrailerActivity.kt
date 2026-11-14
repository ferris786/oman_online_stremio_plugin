package com.kmk.turkishtv

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Trailers, played in-app through YouTube's embedded player.
 *
 * Two things had to be right, and the first attempt got one of them wrong:
 *
 *  - Handing the video to the YouTube TV app does not work: on a cold start
 *    it shows the account picker and loses the deep link, so you choose a
 *    profile and land on YouTube's home screen instead of the trailer.
 *  - The embed needs a REAL https origin. Building the page locally with
 *    loadDataWithBaseURL left the iframe's parent without one and YouTube
 *    refused to play. The page is now served by our own server
 *    (/trailer/{id}), so the embed sees a legitimate origin and referrer.
 *
 * Extraction was ruled out: YouTube answers LOGIN_REQUIRED, "sign in to
 * confirm you're not a bot", to the VPS's datacenter IP.
 *
 * If the TV's WebView still cannot play it, a button hands off to the
 * YouTube app rather than leaving a dead screen.
 */
class TrailerActivity : Activity() {

    private var web: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ytId = ""
    private var title = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ytId = intent.getStringExtra("ytId") ?: run { finish(); return }
        title = intent.getStringExtra("title") ?: ""

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }

        // shown until the page loads, and again as an escape hatch
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val status = TextView(this).apply {
            text = if (title.isEmpty()) "Loading trailer…" else "Loading trailer…\n$title"
            setTextColor(resources.getColor(R.color.text_soft))
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val fallback = TextView(this).apply {
            text = "▶  Open in the YouTube app"
            setTextColor(resources.getColor(R.color.btn_primary_text))
            setBackgroundResource(R.drawable.btn_primary)
            textSize = 15f
            isFocusable = true
            isClickable = true
            val p = Ui.dp(this@TrailerActivity, 18)
            setPadding(p, p / 2, p, p / 2)
            visibility = View.GONE
            setOnClickListener {
                Extras.openInYouTube(this@TrailerActivity, ytId, title)
                finish()
            }
        }
        panel.addView(status)
        panel.addView(fallback, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(this@TrailerActivity, 18); gravity = Gravity.CENTER })
        root.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val w = WebView(this)
        web = w
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // an old TV WebView can get served a layout the player refuses
            userAgentString = userAgentString.replace("; wv", "")
        }
        w.setBackgroundColor(0xFF000000.toInt())
        w.webChromeClient = WebChromeClient()
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                status.visibility = View.GONE
            }
            override fun onReceivedError(view: WebView?, req: android.webkit.WebResourceRequest?,
                                         err: android.webkit.WebResourceError?) {
                if (req?.isForMainFrame == true) showFallback(status, fallback, "Could not load the trailer")
            }
        }
        root.addView(w, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // served from our own https origin - the embed will not play without one
        w.loadUrl(Config.BASE_URL + "/trailer/" + ytId)

        // if nothing is playing after a few seconds, offer the app instead
        handler.postDelayed({
            if (!isFinishing && fallback.visibility != View.VISIBLE) {
                fallback.visibility = View.VISIBLE
                fallback.requestFocus()
            }
        }, 6000)
    }

    private fun showFallback(status: TextView, fallback: TextView, msg: String) {
        if (isFinishing) return
        status.text = msg
        status.visibility = View.VISIBLE
        fallback.visibility = View.VISIBLE
        fallback.requestFocus()
    }

    override fun onPause() { super.onPause(); web?.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web?.apply { loadUrl("about:blank"); destroy() }
        web = null
        super.onDestroy()
    }
}
