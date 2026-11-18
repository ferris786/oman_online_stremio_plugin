package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The "who is watching" screen.
 *
 * It appears on a cold start when more than one profile exists, and from
 * the sidebar at any time. A profile owns the personal half of the app:
 * history, My List, collections, kept Reel rows and the player skin. The
 * TorBox account and the player engine stay with the box, so switching
 * never means pairing anything again.
 *
 * Two things were wrong with the first version and are fixed here. The
 * round avatars wore the app's rectangular focus ring, which drew a square
 * around a circle. And every action lived behind a long press, so the
 * photo-from-phone flow in particular was effectively invisible; the
 * actions are now stated at the bottom and act on whichever profile is
 * highlighted.
 */
class ProfilesActivity : Activity() {

    companion object {
        /** set once the picker has been offered, so it asks on a cold start only */
        var askedThisLaunch = false

        /** the picker entry that opens the phone upload rather than setting an icon */
        private const val PHONE = "::phone"
    }

    private lateinit var row: LinearLayout
    private lateinit var actions: LinearLayout

    /** the tile the remote is on; the action pills apply to this one */
    private var highlighted: Profiles.Profile? = null

    /** first run: nobody has said who they are on this TV yet */
    private val setup: Boolean get() = intent.getBooleanExtra("setup", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        row = findViewById(R.id.pfRow)
        actions = findViewById(R.id.pfActions)
        askedThisLaunch = true

        findViewById<TextView>(R.id.pfAdd).setOnClickListener { addProfile() }
        findViewById<TextView>(R.id.pfPicture).setOnClickListener {
            highlighted?.let { pickIcon(it) }
        }
        findViewById<TextView>(R.id.pfPhone).setOnClickListener {
            highlighted?.let { uploadFromPhone(it) }
        }
        findViewById<TextView>(R.id.pfEdit).setOnClickListener {
            highlighted?.let { editMenu(it) }
        }

        if (setup) buildSetup() else build()
    }

    // ------------------------------------------------------------ the picker

    private fun build() {
        row.removeAllViews()
        row.orientation = LinearLayout.HORIZONTAL
        actions.visibility = View.VISIBLE
        findViewById<View>(R.id.pfFoot).visibility = View.VISIBLE
        findViewById<TextView>(R.id.pfHint).text = "Press OK to watch"

        val profiles = Profiles.all(this)
        val activeId = Profiles.activeId(this)
        highlighted = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()

        findViewById<TextView>(R.id.pfTitle).text =
            if (profiles.size == 1) "Your profile" else "Who is watching?"

        for (p in profiles) {
            val tile = layoutInflater.inflate(R.layout.item_profile, row, false)
            Avatars.paint(this, tile.findViewById(R.id.pAvatar), tile.findViewById(R.id.pAvatarImg), p)
            tile.findViewById<TextView>(R.id.pName).text = p.name
            tile.findViewById<TextView>(R.id.pBadge).visibility =
                if (p.id == activeId) View.VISIBLE else View.GONE
            // drives the gold ring through duplicateParentState
            tile.isSelected = p.id == activeId

            Ui.focusLift(tile, 1.05f)
            val lift = tile.onFocusChangeListener
            tile.setOnFocusChangeListener { v, f ->
                lift?.onFocusChange(v, f)
                if (f) highlighted = p
            }
            tile.setOnClickListener { choose(p) }
            tile.setOnLongClickListener { editMenu(p); true }
            row.addView(tile)
            if (p.id == activeId) tile.post { tile.requestFocus() }
        }
    }

    /**
     * The one-time question.
     *
     * There is no sign-in and typing on a remote is miserable, so this is a
     * choice from the people who actually use the app. The name picked here
     * is what the other TV in the household sees on its "watching now" and
     * "sent by" rows, so it is worth asking properly rather than defaulting
     * to a name nobody chose.
     */
    private fun buildSetup() {
        findViewById<TextView>(R.id.pfTitle).text = "Who is watching on this TV?"
        findViewById<TextView>(R.id.pfHint).text = "You can change it later in Settings"
        actions.visibility = View.GONE
        findViewById<View>(R.id.pfFoot).visibility = View.GONE

        row.removeAllViews()
        row.orientation = LinearLayout.VERTICAL

        val entries: List<Pair<String, () -> Unit>> =
            Profiles.KNOWN.map { name ->
                name to { nameThisTv(name) }
            } + listOf("Someone else" to {
                nameDialog("Your name", "") { nameThisTv(it) }
            })

        // five across, two rows, so nine tiles fit a television comfortably
        var line: LinearLayout? = null
        entries.forEachIndexed { i, (name, onPick) ->
            if (i % 5 == 0) {
                line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                }
                row.addView(line)
            }
            val plus = name == "Someone else"
            val tile = layoutInflater.inflate(R.layout.item_profile_small, line, false)
            val letter = tile.findViewById<TextView>(R.id.pAvatar)
            val image = tile.findViewById<ImageView>(R.id.pAvatarImg)
            if (plus) {
                letter.text = "+"
                letter.setTextColor(0xFFD8D3C8.toInt())
                letter.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF2A2A33.toInt())
                image.visibility = View.GONE
            } else {
                // show the mark they would get, not just a letter
                Avatars.paint(this, letter, image, Profiles.Profile(
                    "", name, Profiles.colourFor(name), Avatars.defaultFor(name)))
            }
            tile.findViewById<TextView>(R.id.pName).text = name
            Ui.focusLift(tile, 1.08f)
            tile.setOnClickListener { onPick() }
            line?.addView(tile)
        }
        (row.getChildAt(0) as? LinearLayout)?.getChildAt(0)
            ?.let { first -> first.post { first.requestFocus() } }
    }

    private fun nameThisTv(name: String) {
        Profiles.nameThisTv(this, name)
        Toast.makeText(this, "Welcome, $name", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** switch and go back; the home screen notices the change and rebuilds */
    private fun choose(p: Profiles.Profile) {
        if (p.id != Profiles.activeId(this)) {
            Profiles.switchTo(this, p.id)
            Toast.makeText(this, "Watching as ${p.name}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    // ------------------------------------------------------------ managing

    private fun addProfile() {
        if (Profiles.all(this).size >= 6) {
            Toast.makeText(this, "Six profiles is the limit on this TV", Toast.LENGTH_LONG).show()
            return
        }
        pickKnownName("Who is this for?") { name ->
            val p = Profiles.add(this, name)
            Profiles.switchTo(this, p.id)
            Toast.makeText(this, "${p.name} added - this profile starts fresh", Toast.LENGTH_LONG).show()
            build()
        }
    }

    /**
     * The name list, with an escape hatch. Names already in use are left
     * out, so two profiles cannot both end up called Wahab.
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
        options.add("Choose a picture")
        options.add("Photo from your phone")
        options.add("Rename")
        options.add("Change colour")
        // the first profile holds the history the app has always had, so it stays
        if (!p.isPrimary) options.add("Remove this profile")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(p.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Choose a picture" -> pickIcon(p)
                    "Photo from your phone" -> uploadFromPhone(p)
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

    // ------------------------------------------------------------ pictures

    /**
     * The picture picker: the built-in marks first, then anything dropped
     * into the server's avatar folder. A grid in a dialog rather than a new
     * screen, so it stays one press from the profile it belongs to.
     */
    private fun pickIcon(p: Profiles.Profile) {
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@ProfilesActivity, 6)
            val pad = Ui.dp(this@ProfilesActivity, 10)
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("A picture for ${p.name}")
            .setView(grid)
            .setNegativeButton("Cancel", null)
            .show()

        // built-ins are instant; the server's folder arrives when it arrives
        var options: List<Pair<String, String>> =
            listOf(Pair("From phone", PHONE)) +
            Avatars.BUILT_IN.map { (key, _) -> Pair(key, "av:$key") }
        grid.adapter = IconAdapter(p, options, dialog)
        Thread {
            val pictures = Household.avatarPictures(this)
            if (pictures.isEmpty()) return@Thread
            runOnUiThread {
                if (isFinishing || !dialog.isShowing) return@runOnUiThread
                options = options + pictures
                grid.adapter = IconAdapter(p, options, dialog)
            }
        }.start()
    }

    private inner class IconAdapter(
        private val profile: Profiles.Profile,
        private val options: List<Pair<String, String>>,
        private val dialog: AlertDialog
    ) : RecyclerView.Adapter<IconAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val letter: TextView = v.findViewById(R.id.pAvatar)
            val image: ImageView = v.findViewById(R.id.pAvatarImg)
            val label: TextView = v.findViewById(R.id.pName)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_profile_small, parent, false))
        override fun getItemCount() = options.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val (name, value) = options[position]
            if (value == PHONE) {
                holder.image.visibility = View.GONE
                holder.letter.text = "▣"
                holder.letter.setTextColor(0xFFD8D3C8.toInt())
                holder.letter.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF2A2A33.toInt())
            } else {
                // paint it as if the profile already had this icon
                Avatars.paint(this@ProfilesActivity, holder.letter, holder.image,
                    profile.copy(icon = value))
            }
            holder.label.text = name
            holder.itemView.isSelected = value == profile.icon
            Ui.focusLift(holder.itemView, 1.1f)
            holder.itemView.setOnClickListener {
                if (value == PHONE) {
                    dialog.dismiss()
                    uploadFromPhone(profile)
                } else {
                    Profiles.setIcon(this@ProfilesActivity, profile.id, value)
                    dialog.dismiss()
                    build()
                }
            }
            if (value == profile.icon) holder.itemView.post { holder.itemView.requestFocus() }
        }
    }

    /**
     * Put your own picture on the TV, from a phone.
     *
     * The same handshake as the TorBox pairing: a QR and a short code on
     * the television, everything else on the phone. The TV polls until the
     * picture lands and then wears it straight away, so there is nothing to
     * confirm with a remote.
     */
    private fun uploadFromPhone(p: Profiles.Profile) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val pad = Ui.dp(this@ProfilesActivity, 18)
            setPadding(pad, pad, pad, pad)
        }
        val qr = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                Ui.dp(this@ProfilesActivity, 220), Ui.dp(this@ProfilesActivity, 220))
        }
        val codeView = TextView(this).apply {
            textSize = 24f
            letterSpacing = 0.2f
            setTextColor(resources.getColor(R.color.accent))
            gravity = android.view.Gravity.CENTER
            setPadding(0, Ui.dp(this@ProfilesActivity, 12), 0, 0)
        }
        val hint = TextView(this).apply {
            text = "Scan with your phone, pick a photo, and it appears here."
            textSize = 13f
            setTextColor(resources.getColor(R.color.text_dim))
            gravity = android.view.Gravity.CENTER
            setPadding(0, Ui.dp(this@ProfilesActivity, 8), 0, 0)
        }
        box.addView(qr); box.addView(codeView); box.addView(hint)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("A photo for ${p.name}")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .show()

        codeView.text = "…"
        Thread {
            val code = Household.avatarUploadStart()
            if (code == null) {
                runOnUiThread {
                    if (!isFinishing) { hint.text = "Could not reach the server"; codeView.text = "" }
                }
                return@Thread
            }
            runOnUiThread {
                if (isFinishing || !dialog.isShowing) return@runOnUiThread
                codeView.text = code
                coil.Coil.imageLoader(this).enqueue(
                    coil.request.ImageRequest.Builder(this)
                        .data(Household.avatarQrUrl(code)).target(qr).build())
            }
            // poll until the phone sends something, or the code dies
            val deadline = System.currentTimeMillis() + 15 * 60 * 1000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2500)
                if (isFinishing || !dialog.isShowing) return@Thread
                val (status, url) = Household.avatarUploadCheck(code)
                if (status == "ready" && url != null) {
                    Profiles.setIcon(this, p.id, url)
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        dialog.dismiss()
                        Toast.makeText(this, "Picture set for ${p.name}", Toast.LENGTH_SHORT).show()
                        build()
                    }
                    return@Thread
                }
                if (status == "expired") {
                    runOnUiThread { if (!isFinishing) hint.text = "That code expired. Try again." }
                    return@Thread
                }
            }
        }.start()
    }
}
