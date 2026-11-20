package com.kmk.turkishtv

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * A horizontal row that does not let focus escape off its right-hand end.
 *
 * Without this, pressing RIGHT on the last poster hands focus to the next
 * focusable view, which is the first item of the row BELOW, so running to
 * the end of one row silently drops you into another.
 *
 * The first version read the focused view's adapter position and blocked
 * when it was the last one. That worked for single presses and failed for
 * held ones: under a fast key repeat the focused view is often detached
 * mid-recycle and reports NO_POSITION, and the old code deliberately fell
 * through in that case, which is exactly when the row-jumping happened.
 *
 * Two things fix it. The position is resolved through
 * findContainingItemView, which works when the focused view is a child of
 * the poster rather than the poster itself; and the last position that did
 * resolve is remembered, so a momentary NO_POSITION no longer reads as
 * "unknown, let it go".
 *
 * LEFT at the start is deliberately still allowed: that is how a single
 * press reaches the navigation rail. A HELD left stops at the first item
 * instead, which RowNav handles.
 */
class RowLayoutManager(context: Context) :
    LinearLayoutManager(context, HORIZONTAL, false) {

    private var lastKnownPos = RecyclerView.NO_POSITION

    /** the focused view's adapter position, falling back to the last good one */
    private fun positionOf(focused: View): Int {
        val item = findContainingItemView(focused)
        val pos = if (item != null) getPosition(item) else RecyclerView.NO_POSITION
        if (pos != RecyclerView.NO_POSITION) lastKnownPos = pos
        return if (pos != RecyclerView.NO_POSITION) pos else lastKnownPos
    }

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        val goingRight = direction == View.FOCUS_RIGHT || direction == View.FOCUS_FORWARD
        if (goingRight && itemCount > 0) {
            val pos = positionOf(focused)
            // returning the focused view blocks the search, so focus stays put
            if (pos != RecyclerView.NO_POSITION && pos >= itemCount - 1) return focused
        }
        return super.onInterceptFocusSearch(focused, direction)
    }
}
