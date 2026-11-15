package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * The "who is watching" screen.
 *
 * It appears on a cold start when more than one profile exists, and from
 * the sidebar at any time. A profile owns the personal half of the app -
 * history, My List, collections, kept Reel rows, player skin - while the
 * TorBox account and the player engine stay with the box, so switching
 * never means pairing anything again.
 */
class ProfilesActivity : Activity() {

    companion object {
        /** set once the picker has been offered, so it asks on a cold start only */
        var askedThisLaunch = false
    }

    private lateinit var row: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        row = findViewById(R.id.pfRow)
        askedThisLaunch = true
        build()
    }

    private fun build() {
        row.removeAllViews()
        val profiles = Profiles.all(this)
        val activeId = Profiles.activeId(this)

        for (p in profiles) {
            val tile = layoutInflater.inflate(R.layout.item_profile, row, false)
            val avatar = tile.findViewById<TextView>(R.id.pAvatar)
            val name = tile.findViewById<TextView>(R.id.pName)
            avatar.text = p.initial
            avatar.backgroundTintList = android.content.res.ColorStateList.valueOf(p.colour)
            name.text = if (p.id == activeId) "${p.name}  ·  watching" else p.name
            Ui.focusLift(tile, 1.06f)
            tile.setOnClickListener { choose(p) }
            tile.setOnLongClickListener { editMenu(p); true }
            row.addView(tile)
            if (p.id == activeId) tile.post { tile.requestFocus() }
        }

        // room for a handful, not a crowd - this is a television
        if (profiles.size < 6) {
            val tile = layoutInflater.inflate(R.layout.item_profile, row, false)
            val avatar = tile.findViewById<TextView>(R.id.pAvatar)
            avatar.text = "＋"
            avatar.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2A33.toInt())
            avatar.setTextColor(0xFFD8D3C8.toInt())
            tile.findViewById<TextView>(R.id.pName).text = "Add profile"
            Ui.focusLift(tile, 1.06f)
            tile.setOnClickListener { addProfile() }
            row.addView(tile)
        }

        if (profiles.size == 1) {
            findViewById<TextView>(R.id.pfTitle).text = "Profiles"
        }
    }

    /** switch and go back; the home screen notices the change and rebuilds */
    private fun choose(p: Profiles.Profile) {
        if (p.id != Profiles.activeId(this)) {
            Profiles.switchTo(this, p.id)
            Toast.makeText(this, "Watching as ${p.name}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun addProfile() {
        nameDialog("New profile", "") { name ->
            val p = Profiles.add(this, name)
            Profiles.switchTo(this, p.id)
            Toast.makeText(this, "${p.name} added - this profile starts fresh", Toast.LENGTH_LONG).show()
            build()
        }
    }

    private fun editMenu(p: Profiles.Profile) {
        val options = ArrayList<String>()
        options.add("Rename")
        options.add("Change colour")
        // the first profile holds the history the app has always had, so it stays
        if (!p.isPrimary) options.add("Remove this profile")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(p.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Rename" -> nameDialog("Rename profile", p.name) {
                        Profiles.rename(this, p.id, it); build()
                    }
                    "Change colour" -> { Profiles.recolour(this, p.id); build() }
                    "Remove this profile" -> confirmRemove(p)
                }
            }
            .show()
    }

    private fun confirmRemove(p: Profiles.Profile) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Remove ${p.name}?")
            .setMessage("Their watch history, list, collections and kept rows are deleted. " +
                "This cannot be undone. Nothing changes for the other profiles.")
            .setPositiveButton("Remove") { _, _ ->
                Profiles.remove(this, p.id)
                Toast.makeText(this, "${p.name} removed", Toast.LENGTH_SHORT).show()
                build()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun nameDialog(title: String, initial: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
            hint = "Name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            val pad = Ui.dp(this@ProfilesActivity, 16)
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isEmpty()) Toast.makeText(this, "A name is needed", Toast.LENGTH_SHORT).show()
                else onDone(v)
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }
}
