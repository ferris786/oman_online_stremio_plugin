package com.kmk.turkishtv

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * A horizontal row that does not let focus escape off its right-hand end.
 *
 * Without this, pressing RIGHT on the last poster in a row hands focus to
 * the next focusable view, which is the first item of the row BELOW - so
 * running to the end of one row silently drops you into another. Now the
 * row just stops.
 *
 * LEFT at the start is deliberately still allowed: that is how you reach
 * the navigation rail.
 */
class RowLayoutManager(context: Context) :
    LinearLayoutManager(context, HORIZONTAL, false) {

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        val goingRight = direction == View.FOCUS_RIGHT || direction == View.FOCUS_FORWARD
        if (goingRight && itemCount > 0) {
            // During a fast scroll the focused view can be mid-recycle and
            // report NO_POSITION; blocking on that would strand focus.
            val pos = getPosition(focused)
            if (pos != RecyclerView.NO_POSITION && pos >= itemCount - 1) return focused
        }
        return super.onInterceptFocusSearch(focused, direction)
    }
}
