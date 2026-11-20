package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

/**
 * One place to save a title.
 *
 * There used to be two buttons sitting next to each other doing almost the
 * same job: "My List", which toggled a single built-in list, and
 * "Collection", which opened a checklist of named ones. Two words for one
 * idea, and the named lists were effectively a secret because nobody reads
 * "Collection" as "my own list".
 *
 * Now there is one action. My List is simply the list that always exists
 * and sits at the top; everything below it is a list the viewer made, and
 * the last entry makes another. Ticking and unticking does the work, so
 * saving and unsaving are the same gesture everywhere.
 *
 * Both halves are per profile, so this follows whoever is watching.
 */
object SaveTo {

    private const val MY_LIST = "My List"

    /**
     * @param kind  movie | series | enseries, as MyList and Collections use
     * @param entry what to store in My List, already filled in
     * @param item  the same title in the shape a named list stores
     */
    fun show(activity: Activity, kind: String, id: String,
             entry: MyList.Entry, item: Collections.Item,
             onChanged: () -> Unit = {}) {

        val lists = Collections.names(activity)
        val labels = (listOf(MY_LIST) + lists + listOf("New list…")).toTypedArray()

        val inMyList = MyList.has(activity, kind, id)
        val member = Collections.membership(activity, kind, id)
        val checked = BooleanArray(labels.size)
        checked[0] = inMyList
        lists.forEachIndexed { i, name -> checked[i + 1] = name in member }
        // the trailing "New list…" row is an action, never a tick

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Save \"${entry.name}\" to…")
            .setMultiChoiceItems(labels, checked) { dialog, which, isChecked ->
                when {
                    // the last row is "New list…": it acts, so undo the tick
                    which == labels.size - 1 -> {
                        (dialog as? AlertDialog)?.listView?.setItemChecked(which, false)
                        dialog.dismiss()
                        promptNewList(activity, kind, id, entry, item, onChanged)
                    }
                    which == 0 -> {
                        // read the stored state fresh: the captured value goes
                        // stale the moment this row is ticked once
                        if (isChecked != MyList.has(activity, kind, id)) MyList.toggle(activity, entry)
                    }
                    else -> {
                        // Collections.toggle flips membership, so only call it
                        // when the tick actually disagrees with the stored state
                        val name = lists[which - 1]
                        val isMember = name in Collections.membership(activity, kind, id)
                        if (isChecked != isMember) Collections.toggle(activity, name, item)
                    }
                }
            }
            .setPositiveButton("Done") { _, _ ->
                onChanged()
                Toast.makeText(activity, summary(activity, kind, id), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** what the title is saved in now, said plainly */
    private fun summary(activity: Activity, kind: String, id: String): String {
        val where = ArrayList<String>()
        if (MyList.has(activity, kind, id)) where.add(MY_LIST)
        where.addAll(Collections.membership(activity, kind, id))
        return when {
            where.isEmpty() -> "Not saved anywhere"
            where.size == 1 -> "Saved to ${where[0]}"
            else -> "Saved to " + where.joinToString(", ")
        }
    }

    private fun promptNewList(activity: Activity, kind: String, id: String,
                              entry: MyList.Entry, item: Collections.Item,
                              onChanged: () -> Unit) {
        val input = EditText(activity).apply {
            hint = "Friday nights"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            val pad = Ui.dp(activity, 16)
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Name the new list")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(activity, "A name is needed", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Collections.create(activity, name)
                Collections.toggle(activity, name, item)
                onChanged()
                Toast.makeText(activity, "Saved to $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { _, _ -> onChanged() }
            .show()
        input.requestFocus()
    }

    /** the button's label: it says where the title already is */
    fun label(activity: Activity, kind: String, id: String): String {
        val n = (if (MyList.has(activity, kind, id)) 1 else 0) +
            Collections.membership(activity, kind, id).size
        return when (n) {
            0 -> "＋  Save to…"
            1 -> "✓  Saved"
            else -> "✓  Saved ×$n"
        }
    }
}
