package com.kmk.turkishtv

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A ScrollView that never auto-scrolls to the focused child. The home
 * screen snaps whole rows into place itself (so the first row sits under
 * the billboard instead of at the top edge), and the stock
 * "scroll minimally to reveal the focused rect" behaviour would fight it.
 */
class SnapScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : ScrollView(context, attrs, defStyle) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0
}
