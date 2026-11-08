package com.kmk.turkishtv

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import coil.Coil
import coil.request.ImageRequest

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val input = findViewById<EditText>(R.id.torboxKeyInput)
        val status = findViewById<TextView>(R.id.torboxStatus)
        val testBtn = findViewById<Button>(R.id.testBtn)
        val saveBtn = findViewById<Button>(R.id.saveBtn)

        Torbox.apiKey(this)?.let { input.setText(it) }

        fun refreshStatus() {
            status.text = if (Torbox.apiKey(this) != null)
                "Key saved ✓  (English movies will use TorBox)" else "No key set - English movies need a TorBox key"
        }
        refreshStatus()

        // ---- Pair from phone: TV shows QR + short code, key arrives from the
        // phone page (no TV typing). Polls until the phone submits it.
        val pairPanel = findViewById<LinearLayout>(R.id.pairPanel)
        val pairBtn = findViewById<Button>(R.id.pairBtn)
        val pairCodeText = findViewById<TextView>(R.id.pairCodeText)
        val pairUrlText = findViewById<TextView>(R.id.pairUrlText)
        val pairQr = findViewById<ImageView>(R.id.pairQr)
        var polling = false

        pairBtn.setOnClickListener {
            if (polling) return@setOnClickListener
            polling = true
            pairPanel.visibility = android.view.View.VISIBLE
            pairCodeText.text = "Getting code…"
            Thread {
                try {
                    val code = Api.pairStart()
                    val qrUrl = Config.BASE_URL + "/pair/qr/" + code
                    runOnUiThread {
                        pairCodeText.text = "Code:  $code"
                        pairUrlText.text = "Scan the QR with your phone\nor visit:\n" +
                            Config.BASE_URL.replace("https://", "") + "/pair/" + code
                        Coil.imageLoader(this).enqueue(
                            ImageRequest.Builder(this).data(qrUrl).crossfade(true)
                                .target(pairQr).build())
                    }
                    // poll until ready/expired (max 15 min)
                    val deadline = System.currentTimeMillis() + 15 * 60 * 1000
                    while (polling && System.currentTimeMillis() < deadline) {
                        Thread.sleep(3000)
                        val (st, key) = Api.pairCheck(code)
                        if (st == "ready" && key != null) {
                            Torbox.saveApiKey(this, key)
                            runOnUiThread {
                                input.setText(key)
                                refreshStatus()
                                pairPanel.visibility = android.view.View.GONE
                                Toast.makeText(this, "✓ Paired with TorBox", Toast.LENGTH_LONG).show()
                            }
                            polling = false
                            return@Thread
                        }
                        if (st == "expired") break
                    }
                    if (polling) runOnUiThread {
                        pairCodeText.text = "Code expired - press Pair again"
                    }
                } catch (e: Exception) {
                    runOnUiThread { pairCodeText.text = "Pairing failed: ${e.message}" }
                }
                polling = false
            }.start()
        }

        testBtn.setOnClickListener {
            val key = input.text.toString().trim()
            if (key.isEmpty()) { status.text = "Enter a key first"; return@setOnClickListener }
            status.text = "Testing…"
            Thread {
                val result = Torbox.testKey(key)
                runOnUiThread {
                    status.text = when (result) {
                        "ok" -> "Key is valid ✓"
                        else -> "Key check failed: $result"
                    }
                }
            }.start()
        }

        saveBtn.setOnClickListener {
            val key = input.text.toString().trim()
            if (key.isEmpty()) {
                Torbox.saveApiKey(this, "")
                status.text = "Key cleared"
            } else {
                Torbox.saveApiKey(this, key)
                status.text = "Key saved ✓"
            }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
    }
}
