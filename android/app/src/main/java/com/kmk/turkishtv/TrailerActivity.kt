package com.kmk.turkishtv

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Trailers, played in-app through YouTube's own embedded player.
 *
 * Handing the video to the YouTube TV app does not work reliably: on a cold
 * start it shows the account picker and loses the deep link, so you choose
 * a profile and end up on the home screen instead of the trailer. The
 * embedded player has no such gate, needs no sign-in, and keeps the viewer
 * inside Wahab TV. Checked that the trailers we serve are embeddable.
 *
 * Settings can still route trailers to the YouTube app instead.
 */
class TrailerActivity : Activity() {

    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ytId = intent.getStringExtra("ytId") ?: run { finish(); return }
        val title = intent.getStringExtra("title") ?: ""

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val status = TextView(this).apply {
            text = if (title.isEmpty()) "Loading trailer…" else "Loading trailer…\n$title"
            setTextColor(resources.getColor(R.color.text_soft))
            textSize = 15f
            gravity = android.view.Gravity.CENTER
        }
        root.addView(status, FrameLayout.LayoutParams(
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
        }
        w.setBackgroundColor(0xFF000000.toInt())
        w.webChromeClient = WebChromeClient()
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                status.visibility = View.GONE
            }
            override fun onReceivedError(view: WebView?, req: android.webkit.WebResourceRequest?,
                                         err: android.webkit.WebResourceError?) {
                if (req?.isForMainFrame == true) {
                    Toast.makeText(this@TrailerActivity,
                        "Could not load the trailer", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
        root.addView(w, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // autoplay, no related videos at the end, no YouTube chrome we cannot drive
        w.loadDataWithBaseURL(
            "https://www.youtube.com",
            """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
            iframe{border:0;width:100%;height:100%}</style></head>
            <body><iframe src="https://www.youtube.com/embed/$ytId?autoplay=1&playsinline=1&rel=0&modestbranding=1&fs=1"
              allow="autoplay; encrypted-media; fullscreen" allowfullscreen></iframe></body></html>
            """.trimIndent(),
            "text/html", "utf-8", null)
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        web?.apply {
            loadUrl("about:blank")
            destroy()
        }
        web = null
        super.onDestroy()
    }
}
