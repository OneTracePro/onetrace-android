package pro.onetrace.sdk.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ProgressBar

/**
 * Pull-to-refresh over a scrolling view without an extra AndroidX dependency: pulling down from the top shows a
 * spinner, releasing it far enough calls [onRefresh]; the spinner stays until [refreshing] is set back to false.
 */
@SuppressLint("ViewConstructor")
internal class PullToRefresh(context: Context, private val content: View, accent: Int, surface: Int, private val onRefresh: () -> Unit) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val size = (40 * density).toInt()
    private val trigger = 72 * density
    private val restingOffset = 16 * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startY = 0f
    private var pulling = false
    private var offset = 0f

    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(accent)
        val padding = (8 * density).toInt()
        setPadding(padding, padding, padding, padding)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(surface)
        }
        elevation = 4 * density
        translationY = -size.toFloat()
        visibility = INVISIBLE
    }

    var refreshing = false
        set(value) {
            field = value

            if (value) {
                spinner.visibility = VISIBLE
                spinner.alpha = 1f
                spinner.animate().translationY(restingOffset).setDuration(150).start()
            } else {
                hide()
            }
        }

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(spinner, LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.y
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> if (!pulling && !refreshing && !content.canScrollVertically(-1) && event.y - startY > slop) {
                pulling = true
                startY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pulling = false
        }

        return pulling
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startY = event.y
            MotionEvent.ACTION_MOVE -> {
                if (!pulling && !refreshing && event.y - startY > slop) {
                    pulling = true
                    startY = event.y
                }

                if (pulling) {
                    offset = ((event.y - startY) * 0.5f).coerceIn(0f, trigger * 1.4f)
                    spinner.animate().cancel()
                    spinner.visibility = VISIBLE
                    spinner.alpha = (offset / trigger).coerceAtMost(1f)
                    spinner.translationY = offset - size
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling && offset >= trigger && event.actionMasked == MotionEvent.ACTION_UP) {
                    refreshing = true
                    onRefresh()
                } else if (pulling) {
                    hide()
                }

                pulling = false
                offset = 0f
            }
        }

        return true
    }

    private fun hide() {
        spinner.animate().translationY(-size.toFloat()).alpha(0f).setDuration(150).withEndAction { spinner.visibility = INVISIBLE }.start()
    }
}
