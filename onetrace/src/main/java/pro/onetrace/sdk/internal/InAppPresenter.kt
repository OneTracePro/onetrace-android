package pro.onetrace.sdk.internal

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Shows in-app messages on the main thread over the resumed activity, one at a time: a centered card (`modal`), a
 * strip at the top or bottom (`banner`) or the whole screen (`fullscreen`). Built in code, no layouts or themes of the
 * app are needed. Shown, button tap and close are reported through [report].
 */
internal class InAppPresenter(
    private val openLinks: Boolean,
    private val report: (token: String, event: String, url: String?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val images = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "onetrace-images").apply { isDaemon = true } }
    private val waiting = ArrayDeque<JSONObject>()
    private var activity: Activity? = null
    private var shown: Shown? = null

    /** The message on screen: a dialog (`modal`, `fullscreen`) or a view over the activity content (`banner`). */
    private class Shown(val dialog: Dialog?, val banner: View?, val host: Activity, val dismiss: () -> Unit)

    /** Any thread: the message is shown as soon as an activity is on screen and no other message is. */
    fun present(message: JSONObject) {
        main.post {
            waiting.addLast(message)
            showNext()
        }
    }

    fun resumed(activity: Activity) {
        this.activity = activity
        showNext()
    }

    /** A banner belongs to the content of its activity: it goes away with the activity, the next message may follow. */
    fun paused(activity: Activity) {
        if (this.activity === activity) {
            this.activity = null
        }

        shown?.takeIf { it.banner != null && it.host === activity }?.let { banner ->
            (banner.banner?.parent as? ViewGroup)?.removeView(banner.banner)
            shown = null
        }
    }

    /** Tests: the dialog on screen. */
    internal val current: Dialog? get() = shown?.dialog

    /** Tests: the banner on screen. */
    internal val currentBanner: View? get() = shown?.banner

    private fun showNext() {
        val host = activity ?: return

        if (shown != null || host.isFinishing) {
            return
        }

        val message = waiting.removeFirstOrNull() ?: return
        val token = message.optString("token")
        val layout = message.optString("layout", "modal")

        if (layout == "banner") {
            showBanner(host, message, token)
        } else {
            showDialog(host, message, layout, token)
        }

        report(token, "shown", null)
    }

    private fun finished() {
        shown = null
        showNext()
    }

    /**
     * A banner is a view over the activity content, not a window: the app keeps the focus and the touches outside it,
     * and the system bars stay as the app draws them.
     */
    private fun showBanner(host: Activity, message: JSONObject, token: String) {
        val decor = host.window?.decorView as? ViewGroup ?: return
        val top = message.optString("position", "bottom") == "top"
        var view: View? = null
        val dismiss = {
            view?.let { banner ->
                view = null
                decor.removeView(banner)
                finished()
            }
            Unit
        }
        val banner = content(host, message, "banner", token, dismiss, if (top) WindowInsetsCompat.Type.statusBars() else WindowInsetsCompat.Type.navigationBars())
        view = banner
        decor.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (top) Gravity.TOP else Gravity.BOTTOM))
        ViewCompat.requestApplyInsets(banner)
        shown = Shown(null, banner, host, dismiss)
        main.postDelayed({ if (shown?.banner === banner) dismiss() }, BANNER_SECONDS * 1000L)
    }

    private fun showDialog(host: Activity, message: JSONObject, layout: String, token: String) {
        val dialog = Dialog(host)
        val dismiss = { dialog.dismiss() }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content(host, message, layout, token, dismiss, WindowInsetsCompat.Type.systemBars()))
        dialog.setCanceledOnTouchOutside(layout == "modal")
        dialog.setOnCancelListener { report(token, "dismissed", null) }
        dialog.setOnDismissListener { finished() }
        window(dialog.window, layout == "fullscreen")

        // A full screen message under the bars: dark bar icons on a light background.
        if (layout == "fullscreen") {
            dialog.window?.let { window ->
                val light = ColorUtils.calculateLuminance(color(message.optString("background_color"), Color.WHITE)) > 0.5
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }

        shown = Shown(dialog, null, host, dismiss)
        dialog.show()
    }

    private fun content(host: Activity, message: JSONObject, layout: String, token: String, dismiss: () -> Unit, insetTypes: Int): ViewGroup {
        val text = color(message.optString("text_color"), Color.parseColor("#111827"))
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(host, 20), dp(host, 20), dp(host, 20), dp(host, 16))
            background = GradientDrawable().apply {
                setColor(color(message.optString("background_color"), Color.WHITE))
                cornerRadius = if (layout == "fullscreen") 0f else dp(host, 16).toFloat()
            }
            gravity = if (layout == "fullscreen") Gravity.CENTER else Gravity.NO_GRAVITY
        }

        message.optString("image_url").takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { link ->
            val image = ImageView(host).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                maxHeight = dp(host, if (layout == "banner") 64 else 220)
            }
            card.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(host, 12) })
            images.execute { download(link)?.let { bitmap -> main.post { image.setImageBitmap(bitmap) } } }
        }

        message.optString("title").takeIf { it.isNotBlank() }?.let {
            card.addView(TextView(host).apply {
                this.text = it
                setTextColor(text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (layout == "banner") 16f else 20f)
                setTypeface(typeface, Typeface.BOLD)
            })
        }

        message.optString("body").takeIf { it.isNotBlank() }?.let {
            card.addView(TextView(host).apply {
                this.text = it
                setTextColor(text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (layout == "banner") 14f else 16f)
                setPadding(0, dp(host, 6), 0, 0)
            })
        }

        val buttons = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(host, 14), 0, 0)
        }

        val close = message.optString("close_text").ifBlank { "✕" }
        buttons.addView(TextView(host).apply {
            this.text = close
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(host, 12), dp(host, 10), dp(host, 12), dp(host, 10))
            contentDescription = "close"
            setOnClickListener {
                report(token, "dismissed", null)
                dismiss()
            }
        })

        message.optString("button_text").takeIf { it.isNotBlank() }?.let { label ->
            val url = message.optString("button_url").takeIf { it.isNotBlank() }
            buttons.addView(Button(host).apply {
                this.text = label
                isAllCaps = false
                setTextColor(color(message.optString("button_text_color"), Color.WHITE))
                background = GradientDrawable().apply {
                    setColor(color(message.optString("button_color"), Color.parseColor("#4F46E5")))
                    cornerRadius = dp(host, 10).toFloat()
                }
                setOnClickListener {
                    report(token, "clicked", url)
                    dismiss()

                    if (openLinks && url != null) {
                        try {
                            host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: ActivityNotFoundException) {
                            // No app can open the link: the click is still counted.
                        }
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(host, 8) })
        }

        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return FrameLayout(host).apply {
            val margin = if (layout == "fullscreen") 0 else dp(host, 16)

            // Drawn under the system bars: the message keeps clear of them, a full screen one paints its color behind.
            if (layout == "fullscreen") {
                setBackgroundColor(color(message.optString("background_color"), Color.WHITE))
            }

            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(insetTypes or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }

            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (layout == "fullscreen") ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(margin, margin, margin, margin)
            })
        }
    }

    private fun window(window: Window?, fullscreen: Boolean) {
        window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        // Dialog windows are laid out between the system bars unless told otherwise.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, if (fullscreen) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun color(value: String, fallback: Int): Int = try {
        if (value.matches(Regex("^#[0-9A-Fa-f]{6}$"))) Color.parseColor(value) else fallback
    } catch (e: IllegalArgumentException) {
        fallback
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun download(url: String): Bitmap? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 10_000
            inputStream.use(BitmapFactory::decodeStream).also { disconnect() }
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val BANNER_SECONDS = 8
    }
}
