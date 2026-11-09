package pro.onetrace.sdk

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The message feed as a screen of its own: a header with the title and the back button over [OneTraceFeedView].
 * Open it with [OneTrace.openFeed]; it is declared in the SDK manifest. Cards and buttons take the accent color of the
 * app theme.
 */
public class OneTraceFeedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val surface = if (dark) 0xFF1E293B.toInt() else Color.WHITE
        val divider = if (dark) 0xFF334155.toInt() else 0xFFE2E8F0.toInt()
        val text = if (dark) 0xFFF1F5F9.toInt() else 0xFF0F172A.toInt()
        val title = getString(R.string.onetrace_feed_title)
        setTitle(title)
        edgeToEdge(dark)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(16), 0)
            addView(TextView(this@OneTraceFeedActivity).apply {
                this.text = if (layoutDirection == ViewGroup.LAYOUT_DIRECTION_RTL) "→" else "←"
                contentDescription = getString(R.string.onetrace_feed_close)
                gravity = Gravity.CENTER
                setTextColor(text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                val outValue = TypedValue()
                if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
                    setBackgroundResource(outValue.resourceId)
                }
                isClickable = true
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(this@OneTraceFeedActivity).apply {
                this.text = title
                setTextColor(text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, Typeface.BOLD)
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        }

        val feed = OneTraceFeedView(this).apply {
            // The activity has the SDK theme: the accent comes from the app theme.
            accentColor = OneTraceFeedView.themeAccent(ContextThemeWrapper(applicationContext, applicationInfo.theme))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
            addView(android.view.View(this@OneTraceFeedActivity).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)))
            addView(feed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        // Drawn under the system bars: the header keeps clear of the status bar, the cards of the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            feed.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        setContentView(root)
    }

    private fun edgeToEdge(dark: Boolean) {
        val window = window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
