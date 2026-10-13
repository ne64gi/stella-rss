package net.fuyumori.stellarss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.abs

/** Lock the initial axis: diagonal row swipes must not become pull-to-refresh. */
class ArticleRefreshLayout(context: Context) : SwipeRefreshLayout(context) {
    private var x = 0f
    private var y = 0f
    private var axis = 0
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x = event.x; y = event.y; axis = 0 }
            MotionEvent.ACTION_MOVE -> if (axis == 0) {
                val dx = abs(event.x - x); val dy = abs(event.y - y)
                if (maxOf(dx, dy) > slop) axis = if (dx > dy) 1 else 2
            }
        }
        return axis != 1 && super.onInterceptTouchEvent(event)
    }
}

/** Direction is physical left/right even in RTL; the action itself never toggles state. */
class ArticleSwipeCallback(context: Context, private val action: (RecyclerView.ViewHolder, Int) -> Unit) :
    ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
    private val padding = context.dp(20).toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * context.resources.displayMetrics.scaledDensity }
    override fun isLongPressDragEnabled() = false
    override fun onMove(list: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
    override fun getSwipeThreshold(holder: RecyclerView.ViewHolder) = .35f
    override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = action(holder, direction)
    override fun onChildDraw(canvas: Canvas, list: RecyclerView, holder: RecyclerView.ViewHolder,
                             dx: Float, dy: Float, state: Int, active: Boolean) {
        if (state == ItemTouchHelper.ACTION_STATE_SWIPE && dx != 0f) {
            val v = holder.itemView
            canvas.save(); canvas.clipRect(v.left, v.top, v.right, v.bottom)
            paint.color = if (dx < 0) 0xFF224639.toInt() else 0xFF203F55.toInt()
            canvas.drawRect(v.left.toFloat(), v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), paint)
            paint.color = INK; paint.textAlign = if (dx < 0) Paint.Align.RIGHT else Paint.Align.LEFT
            canvas.drawText(if (dx < 0) "✓ 既読にする" else "☆ あとで読む", if (dx < 0) v.right - padding else v.left + padding,
                (v.top + v.bottom) / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
            canvas.restore()
        }
        super.onChildDraw(canvas, list, holder, dx, dy, state, active)
    }
}
