package com.voluene.monobrowser.ui.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.voluene.monobrowser.browser.MonoWebView
import com.voluene.monobrowser.browser.PullRefresh

/**
 * Pull-to-refresh for a single child (the page): while the page is at its top, dragging down grows a thin
 * rectangular line along the edge next to the toolbar ([lineAtBottom]); releasing it full calls [onRefresh].
 * No spinner, no rounded shapes; the address bar shows the load progress afterwards.
 */
class PullRefreshLayout(context: Context) : FrameLayout(context) {
    var onRefresh: () -> Unit = {}
    var lineAtBottom: Boolean = true
    var lineColor: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val threshold = 160f * density
    private val lineHeight = 3f * density
    private val paint = Paint().apply { style = Paint.Style.FILL }
    private var startX = 0f
    private var startY = 0f
    private var canStart = false
    private var pulling = false
    private var progress = 0f

    private fun content(): View? = getChildAt(0)

    /**
     * The page (not a scrollable box inside it) is being pulled down past its top. Other views: at the top is enough.
     */
    private fun pastTop(): Boolean = (content() as? MonoWebView)?.pulledPastTop ?: true

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                canStart = content()?.canScrollVertically(-1) == false
                (content() as? MonoWebView)?.pulledPastTop = false
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> if (!pulling && PullRefresh.shouldStartPull(ev.y - startY, ev.x - startX, slop, canStart && pastTop())) {
                pulling = true
                startY = ev.y // the line starts growing from here
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        return pulling
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (pulling) {
                progress = PullRefresh.progress(ev.y - startY, threshold)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val refresh = pulling && PullRefresh.shouldRefresh(progress)
                reset()
                if (refresh) onRefresh()
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return pulling
    }

    private fun reset() {
        pulling = false
        canStart = false
        progress = 0f
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (progress > 0f) {
            val width = this.width * progress
            val left = (this.width - width) / 2f // grows from the centre outwards
            val top = if (lineAtBottom) height - lineHeight else 0f
            canvas.drawRect(left, top, left + width, top + lineHeight, paint)
        }
    }
}
