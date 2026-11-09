package pro.onetrace.sdk

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import pro.onetrace.sdk.internal.Feed
import pro.onetrace.sdk.internal.ImageLoader
import pro.onetrace.sdk.internal.PullToRefresh

/**
 * The message feed, ready to put into a screen of the app (or open [OneTraceFeedActivity] with [OneTrace.openFeed]):
 * cards in their layouts with the pinned label and the remove button, an empty state and pull-to-refresh. A card is
 * reported shown once at least half of it is visible; a tap on the button, or on a card without one, reports the click
 * and opens the link; the remove button dismisses the card. Light or dark after the app's night mode.
 *
 * The feed is loaded when the view is attached to a window; [refresh] loads it again.
 */
public class OneTraceFeedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    /** The color of unread card borders, buttons, the pinned label and the spinner; the theme's accent by default. */
    public var accentColor: Int = themeAccent(context)
        set(value) {
            field = value
            build()
        }

    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val palette = if (dark) Palette.DARK else Palette.LIGHT
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private var pull: PullToRefresh? = null
    private var cards: List<FeedCard> = emptyList()
    private var loaded = false
    private val rendered = mutableListOf<Pair<FeedCard, View>>()
    private val rect = Rect()

    private val feed: Feed? get() = OneTrace.feed
    private val onCards: (List<FeedCard>) -> Unit = { show(it, loaded = true) }
    private val onScroll = ViewTreeObserver.OnScrollChangedListener { reportVisible() }
    private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { reportVisible() }

    init {
        setBackgroundColor(palette.background)
        build()
    }

    /** Loads the feed again; the spinner shows until it is loaded. */
    public fun refresh() {
        pull?.refreshing = true
        val feed = feed

        if (feed == null) {
            show(emptyList(), loaded = true)
            pull?.refreshing = false
            return
        }

        feed.fetch { cards, _ ->
            show(cards, loaded = true)
            pull?.refreshing = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(onScroll)
        viewTreeObserver.addOnGlobalLayoutListener(onLayout)
        feed?.let { feed ->
            feed.addCardListener(onCards)
            show(feed.current, feed.loadedOnce)
        }
        refresh()
    }

    override fun onDetachedFromWindow() {
        feed?.removeCardListener(onCards)
        viewTreeObserver.removeOnScrollChangedListener(onScroll)
        viewTreeObserver.removeOnGlobalLayoutListener(onLayout)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)

        if (visibility == VISIBLE) {
            post { reportVisible() }
        }
    }

    /** The window of the view is on screen; tests replace it, Robolectric does not report window visibility. */
    internal var onScreen: () -> Boolean = { windowVisibility == VISIBLE }

    /** Tests: the card views on screen with their cards. */
    internal val cardViews: List<Pair<FeedCard, View>> get() = rendered

    private fun build() {
        val refreshing = pull?.refreshing ?: false
        pull?.removeView(scroll)
        removeAllViews()
        pull = PullToRefresh(context, scroll, accentColor, palette.card) { refresh() }.also {
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            it.refreshing = refreshing
        }
        render()
    }

    private fun show(cards: List<FeedCard>, loaded: Boolean) {
        val sameCards = this.loaded == loaded && cards.map { it.token } == this.cards.map { it.token } && rendered.size == cards.size
        this.cards = cards
        this.loaded = loaded

        // Only read marks changed: keep the views (and the scroll position), redraw their borders.
        if (sameCards) {
            rendered.forEachIndexed { index, (old, view) ->
                if (old.read != cards[index].read) {
                    view.background = cardBackground(cards[index])
                }
            }
            rendered.indices.forEach { rendered[it] = cards[it] to rendered[it].second }
        } else {
            render()
        }
    }

    private fun render() {
        list.removeAllViews()
        rendered.clear()
        val gap = dp(10)
        list.setPadding(dp(12), dp(12), dp(12), dp(12))

        if (cards.isEmpty()) {
            list.gravity = Gravity.CENTER
            // Before the first load the spinner says it all.
            if (loaded) {
                list.addView(TextView(context).apply {
                    text = context.getString(R.string.onetrace_feed_empty)
                    gravity = Gravity.CENTER
                    setTextColor(palette.muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(24), dp(24), dp(24), dp(24))
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }

            return
        }

        list.gravity = Gravity.NO_GRAVITY
        cards.forEachIndexed { index, card ->
            val view = cardView(card)
            list.addView(view, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (index > 0) topMargin = gap })
            rendered.add(card to view)
        }
    }

    private fun cardView(card: FeedCard): View {
        val link = card.link
        val frame = FrameLayout(context).apply {
            background = cardBackground(card)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val image = card.imageUrl?.let { url ->
            AspectImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(palette.placeholder)

                if (card.layout == FeedCard.Layout.BANNER) {
                    contentDescription = card.title
                } else {
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }

                ImageLoader.load(this, url, if (card.layout == FeedCard.Layout.CLASSIC) dp(64) else resources.displayMetrics.widthPixels)
            }
        }

        when (card.layout) {
            FeedCard.Layout.CLASSIC -> frame.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(12), dp(36), dp(12))
                image?.let {
                    it.background = GradientDrawable().apply {
                        setColor(palette.placeholder)
                        cornerRadius = dp(8).toFloat()
                    }
                    it.outlineProvider = ViewOutlineProvider.BACKGROUND
                    it.clipToOutline = true
                    addView(it, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(10) })
                }
                addView(body(card, link), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            })
            FeedCard.Layout.CAPTIONED, FeedCard.Layout.BANNER -> frame.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                image?.let {
                    it.fit = true
                    it.maxImageHeight = dp(180)
                    it.emptyHeight = dp(if (card.layout == FeedCard.Layout.BANNER) 96 else 120)
                    addView(it, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                }

                // A banner is only its image; without one its title stands in.
                if (card.layout == FeedCard.Layout.CAPTIONED || image == null) {
                    addView(body(card, link).apply { setPadding(dp(12), dp(10), dp(36), dp(12)) })
                }
            })
        }

        if (card.opensOnTap) {
            frame.setOnClickListener { feed?.clicked(context, card) }
        }

        val dismiss = context.getString(R.string.onetrace_feed_dismiss)
        frame.addView(TextView(context).apply {
            text = "×"
            contentDescription = dismiss
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.dismissBackground)
            }
            asButton(this)
            setOnClickListener { feed?.dismissed(card.token) }
        }, LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })

        return frame
    }

    private fun body(card: FeedCard, link: String?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = card.title
            setTextColor(palette.title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        })

        card.body?.let {
            addView(TextView(context).apply {
                text = it
                setTextColor(palette.body)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }

        if (link != null && card.buttonText != null && card.layout != FeedCard.Layout.BANNER) {
            addView(Button(context).apply {
                text = card.buttonText
                isAllCaps = false
                // The app theme decides the default button size and padding: set them, the same in every app.
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                stateListAnimator = null
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (ColorUtils.calculateLuminance(accentColor) > 0.5) palette.title else Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(accentColor)
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener { feed?.clicked(context, card) }
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }

        if (card.pinned) {
            addView(TextView(context).apply {
                text = context.getString(R.string.onetrace_feed_pinned)
                setTextColor(readableAccent())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
    }

    /** The accent as text on the card: lightened on a dark card until it reads. */
    private fun readableAccent(): Int {
        var color = accentColor
        var step = 0

        while (ColorUtils.calculateContrast(color, palette.card) < 4.5 && step < 10) {
            color = ColorUtils.blendARGB(color, if (dark) Color.WHITE else Color.BLACK, 0.15f)
            step++
        }

        return color
    }

    private fun cardBackground(card: FeedCard) = GradientDrawable().apply {
        setColor(palette.card)
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1).coerceAtLeast(1), if (card.read) palette.border else accentColor)
    }

    /** Cards at least half visible in the feed on screen are reported shown, once each. */
    private fun reportVisible() {
        val feed = feed ?: return

        if (!isAttachedToWindow || !isShown || !onScreen()) {
            return
        }

        rendered.forEach { (card, view) ->
            if (!card.read && view.height > 0 && view.getLocalVisibleRect(rect) && rect.height() * 2 >= view.height) {
                feed.shown(card.token)
            }
        }
    }

    private fun asButton(view: View) {
        ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * An image as wide as the card and as tall as its proportions allow, up to [maxImageHeight] (cropped beyond it);
     * [emptyHeight] until it is downloaded. Without [fit] it keeps the size it is given.
     */
    private class AspectImageView(context: Context) : ImageView(context) {
        var fit = false
        var maxImageHeight = 0
        var emptyHeight = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val drawable = drawable

            if (!fit) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }

            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                (width.toLong() * drawable.intrinsicHeight / drawable.intrinsicWidth).toInt().coerceAtMost(maxImageHeight)
            } else {
                emptyHeight
            }

            setMeasuredDimension(width, height)
        }
    }

    private class Palette(
        val background: Int,
        val card: Int,
        val border: Int,
        val title: Int,
        val body: Int,
        val muted: Int,
        val placeholder: Int,
        val dismissBackground: Int,
    ) {
        companion object {
            val LIGHT = Palette(0xFFF8FAFC.toInt(), Color.WHITE, 0xFFE2E8F0.toInt(), 0xFF0F172A.toInt(), 0xFF475569.toInt(), 0xFF64748B.toInt(), 0xFFF1F5F9.toInt(), 0xD9FFFFFF.toInt())
            val DARK = Palette(0xFF0B1120.toInt(), 0xFF1E293B.toInt(), 0xFF334155.toInt(), 0xFFF1F5F9.toInt(), 0xFFCBD5E1.toInt(), 0xFF94A3B8.toInt(), 0xFF273449.toInt(), 0xD91E293B.toInt())
        }
    }

    internal companion object {
        private const val DEFAULT_ACCENT = 0xFF4F46E5.toInt()

        /**
         * The `colorAccent` of [context]'s theme when it stands out on white (a button, a border, the pinned label),
         * otherwise the indigo of in-app message buttons.
         */
        fun themeAccent(context: Context): Int {
            val value = TypedValue()
            val accent = if (context.theme.resolveAttribute(android.R.attr.colorAccent, value, true) && value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) value.data else null

            return accent?.takeIf { Color.alpha(it) == 255 && ColorUtils.calculateContrast(it, Color.WHITE) >= 3.0 } ?: DEFAULT_ACCENT
        }
    }
}
