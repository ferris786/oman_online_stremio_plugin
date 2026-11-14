package com.kmk.turkishtv

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/**
 * "Add to collection" from any title page: tick the collections it belongs
 * to, or make a new one. Collections live on this TV (see Collections.kt).
 */
object CollectionPicker {

    fun show(activity: Activity, item: Collections.Item, onChanged: () -> Unit = {}) {
        val names = Collections.names(activity)
        if (names.isEmpty()) {
            promptNew(activity, item, onChanged)
            return
        }
        val member = Collections.membership(activity, item.kind, item.id)
        val checked = names.map { it in member }.toBooleanArray()
        val labels = names.toTypedArray()

        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setTitle("Add \"${item.name}\" to…")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val now = Collections.toggle(activity, labels[which], item)
                // keep the box honest if the toggle disagreed
                if (now != isChecked) Collections.toggle(activity, labels[which], item)
            }
            .setPositiveButton("Done") { _, _ -> onChanged() }
            .setNeutralButton("New collection…") { _, _ -> promptNew(activity, item, onChanged) }
            .show()
    }

    private fun promptNew(activity: Activity, item: Collections.Item, onChanged: () -> Unit) {
        val input = EditText(activity).apply {
            hint = "Collection name"
            setSingleLine()
            setTextColor(activity.resources.getColor(R.color.text))
            setHintTextColor(activity.resources.getColor(R.color.text_dim))
        }
        val pad = (activity.resources.displayMetrics.density * 20).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setTitle("New collection")
            .setMessage("\"${item.name}\" will be the first title in it.")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(activity, "Give the collection a name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Collections.create(activity, name)
                Collections.toggle(activity, name, item)
                Toast.makeText(activity, "Added to \"$name\"", Toast.LENGTH_LONG).show()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }
}
