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

    /** first run: nobody has said who they are on this TV yet */
    private val setup: Boolean get() = intent.getBooleanExtra("setup", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        row = findViewById(R.id.pfRow)
        askedThisLaunch = true
        if (setup) buildSetup() else build()
    }

    /**
     * The one-time question. There is no sign-in and typing on a remote is
     * miserable, so this is a choice from the people who actually use the
     * app. The name picked here is what the other TV in the household sees
     * on its "watching now" and "sent by" rows, so it matters that it is a
     * real name rather than a default.
     */
    private fun buildSetup() {
        findViewById<TextView>(R.id.pfTitle).text = "Who is watching on this TV?"
        findViewById<TextView>(R.id.pfHint).text =
            "Pick your name. You can change it later in Settings."
        row.removeAllViews()

        // Nine full-size tiles do not fit in a 540dp-tall screen, so the
        // setup grid uses small ones, five across, in two rows.
        val grid = findViewById<LinearLayout>(R.id.pfRow)
        grid.orientation = LinearLayout.VERTICAL
        val entries = Profiles.KNOWN.map { name ->
            Triple(name, false, { ->
                Profiles.nameThisTv(this, name)
                Toast.makeText(this, "Welcome, $name", Toast.LENGTH_SHORT).show()
                finish()
            })
        } + listOf(Triple("Someone else", true, { ->
            nameDialog("Your name", "") {
                Profiles.nameThisTv(this, it)
                finish()
            }
        }))

        var line: LinearLayout? = null
        entries.forEachIndexed { i, (name, plus, onPick) ->
            if (i % 5 == 0) {
                line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                }
                grid.addView(line)
            }
            line?.addView(nameTile(name, plus, small = true, onPick = onPick))
        }
        (grid.getChildAt(0) as? LinearLayout)?.getChildAt(0)
            ?.let { first -> first.post { first.requestFocus() } }
    }

    /** one avatar tile, used by the setup grid */
    private fun nameTile(name: String, plus: Boolean = false, small: Boolean = false,
                         onPick: () -> Unit): android.view.View {
        val tile = layoutInflater.inflate(R.layout.item_profile, row, false)
        val avatar = tile.findViewById<TextView>(R.id.pAvatar)
        val label = tile.findViewById<TextView>(R.id.pName)
        avatar.text = if (plus) "+" else name.take(1).uppercase()
        avatar.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (plus) 0xFF2A2A33.toInt() else Profiles.colourFor(name))
        if (plus) avatar.setTextColor(0xFFD8D3C8.toInt())
        label.text = name
        if (small) {
            val d = Ui.dp(this, 76)
            avatar.layoutParams = avatar.layoutParams.apply { width = d; height = d }
            avatar.textSize = 28f
            label.layoutParams = label.layoutParams.apply { width = Ui.dp(this@ProfilesActivity, 92) }
            label.textSize = 13f
            val m = Ui.dp(this, 6)
            (tile.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.apply {
                marginStart = m; marginEnd = m
            }
            tile.setPadding(m, m, m, m)
        }
        Ui.focusLift(tile, 1.06f)
        tile.setOnClickListener { onPick() }
        return tile
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
        pickKnownName("Who is this for?") { name ->
            val p = Profiles.add(this, name)
            Profiles.switchTo(this, p.id)
            Toast.makeText(this, "${p.name} added - this profile starts fresh", Toast.LENGTH_LONG).show()
            build()
        }
    }

    /**
     * The name list, with an escape hatch. Names already in use are left
     * out, so two profiles cannot end up both called Wahab.
     */
    private fun pickKnownName(title: String, onPick: (String) -> Unit) {
        val taken = Profiles.all(this).map { it.name.lowercase() }.toSet()
        val free = Profiles.KNOWN.filter { it.lowercase() !in taken }
        val options = free + listOf("Someone else…")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, which ->
                if (which < free.size) onPick(free[which])
                else nameDialog("Their name", "") { onPick(it) }
            }
            .show()
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
                    "Rename" -> pickKnownName("Rename ${p.name} to") {
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
