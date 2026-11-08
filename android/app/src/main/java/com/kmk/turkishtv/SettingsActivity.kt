package com.kmk.turkishtv

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val input = findViewById<EditText>(R.id.torboxKeyInput)
        val status = findViewById<TextView>(R.id.torboxStatus)
        val testBtn = findViewById<Button>(R.id.testBtn)
        val saveBtn = findViewById<Button>(R.id.saveBtn)

        Torbox.apiKey(this)?.let { input.setText(it) }

        status.text = if (Torbox.apiKey(this) != null)
            "Key saved ✓  (English movies will use TorBox)" else "No key set - English movies need a TorBox key"

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
