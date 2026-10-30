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
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
    private var dialog: Dialog? = null

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

    fun paused(activity: Activity) {
        if (this.activity === activity) {
            this.activity = null
        }
    }

    /** Tests: the message on screen. */
    internal val current: Dialog? get() = dialog

    private fun showNext() {
        val host = activity ?: return

        if (dialog != null || host.isFinishing) {
            return
        }

        val message = waiting.removeFirstOrNull() ?: return
        val token = message.optString("token")
        val layout = message.optString("layout", "modal")
        val shown = Dialog(host)
        shown.requestWindowFeature(Window.FEATURE_NO_TITLE)
        shown.setContentView(content(host, message, layout, shown, token))
        shown.setCanceledOnTouchOutside(layout == "modal")
        shown.setOnCancelListener { report(token, "dismissed", null) }
        shown.setOnDismissListener {
            dialog = null
            showNext()
        }
        window(shown.window, layout, message.optString("position", "bottom"))
        dialog = shown
        shown.show()
        report(token, "shown", null)

        if (layout == "banner") {
            main.postDelayed({ if (dialog === shown) shown.dismiss() }, BANNER_SECONDS * 1000L)
        }
    }

    private fun content(host: Activity, message: JSONObject, layout: String, shown: Dialog, token: String): ViewGroup {
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
                shown.dismiss()
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
                    shown.dismiss()

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
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (layout == "fullscreen") ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(margin, margin, margin, margin)
            })
        }
    }

    private fun window(window: Window?, layout: String, position: String) {
        window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        when (layout) {
            "fullscreen" -> window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            "banner" -> {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                window.setGravity(if (position == "top") Gravity.TOP else Gravity.BOTTOM)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            }
            else -> window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
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
