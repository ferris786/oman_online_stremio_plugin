package com.kmk.turkishtv

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Moving along a row with the remote.
 *
 * A single press is left to the framework: it is precise and already
 * correct. A HELD press is not. The remote auto-repeats twenty or thirty
 * times a second, each repeat moves focus one poster and starts a fresh
 * smooth scroll that interrupts the last one, and the result lurches and
 * overshoots.
 *
 * So a held key is paced here instead, on a ramp borrowed from the
 * player's seek: slow enough at first that a short hold is still
 * controllable, fast enough after a second or two to cross a long row.
 *
 * At the end of a row it simply stops. The key is swallowed rather than
 * passed on, so focus cannot leave the row upward or downward, which is
 * the other half of the row-jumping problem.
 *
 * State lives here rather than per-activity because only one screen is in
 * front at a time, and it is reset whenever the key is released.
 */
object RowNav {

    private var holdStartedAt = 0L
    private var lastStepAt = 0L

    /**
     * How far to move and how long to wait, by how long the key has been
     * held. Roughly 7 a second at first, then 11, then 24, then 37, so a
     * sixty-item row takes a couple of seconds end to end.
     */
    private fun rampFor(heldMs: Long): Pair<Int, Long> = when {
        heldMs < 700 -> 1 to 140L
        heldMs < 1600 -> 1 to 90L
        heldMs < 3000 -> 2 to 85L
        else -> 3 to 80L
    }

    /** true when this press was handled here and must not go any further */
    fun handle(activity: Activity, event: KeyEvent, onNearEnd: ((RecyclerView, Int) -> Unit)? = null): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false

        if (event.action == KeyEvent.ACTION_UP) {
            holdStartedAt = 0L
            lastStepAt = 0L
            return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // a tap is the framework's job
        if (event.repeatCount == 0) {
            holdStartedAt = SystemClock.uptimeMillis()
            lastStepAt = 0L
            return false
        }

        val focused = activity.currentFocus ?: return false
        val rv = recyclerOf(focused) ?: return false
        if (rv.layoutManager !is LinearLayoutManager) return false

        val now = SystemClock.uptimeMillis()
        if (holdStartedAt == 0L) holdStartedAt = now
        val (step, gap) = rampFor(now - holdStartedAt)
        // swallow the repeats that arrive between our own steps, so the
        // remote's rate stops driving the scroll
        if (now - lastStepAt < gap) return true
        lastStepAt = now

        move(rv, focused, if (code == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step, onNearEnd)
        return true
    }

    /** the row this view sits in, if any */
    fun recyclerOf(view: View?): RecyclerView? {
        var cur: View? = view
        while (cur != null) {
            val p = cur.parent
            if (p is RecyclerView) return p
            cur = p as? View ?: return null
        }
        return null
    }

    private fun move(rv: RecyclerView, focused: View, delta: Int,
                     onNearEnd: ((RecyclerView, Int) -> Unit)?) {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val item = lm.findContainingItemView(focused) ?: return
        val pos = rv.getChildAdapterPosition(item)
        if (pos == RecyclerView.NO_POSITION) return
        val count = rv.adapter?.itemCount ?: return
        if (count == 0) return

        val target = (pos + delta).coerceIn(0, count - 1)

        // Ask for more before arriving, so an endless row keeps flowing
        // instead of stopping dead while the next page is fetched.
        if (delta > 0) onNearEnd?.invoke(rv, target)

        // already at the end: stay exactly here. The key was consumed by the
        // caller, so focus does not escape to the row above or below.
        if (target == pos) return

        val holder = rv.findViewHolderForAdapterPosition(target)
        if (holder != null) {
            holder.itemView.requestFocus()
            return
        }
        // not laid out yet: bring it in, then take focus once it exists
        rv.scrollToPosition(target)
        rv.post {
            val v = rv.findViewHolderForAdapterPosition(target)?.itemView
            if (v != null) v.requestFocus()
            else rv.post { rv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        }
    }
}
