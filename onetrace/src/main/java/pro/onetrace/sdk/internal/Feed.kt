package pro.onetrace.sdk.internal

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import pro.onetrace.sdk.FeedCallback
import pro.onetrace.sdk.FeedCard
import pro.onetrace.sdk.FeedLinkHandler
import pro.onetrace.sdk.FeedUnreadListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * The message feed of this visitor (`GET /feed` by the anonymous id) and the user's reactions to its cards
 * (`POST /feed/events`): shown once per card, clicked with the link, dismissed.
 *
 * Network calls run on the SDK thread after the calls before them, listeners and callbacks are called on the main
 * thread. Shows, clicks and removals of this session are kept locally: a refresh before the platform has them does not
 * bring a card back as unread or at all.
 */
internal class Feed(
    private val transport: Transport,
    private val executor: Executor,
    private val anonymousId: () -> String,
    private val openLinks: Boolean,
    private val log: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val main: (Runnable) -> Unit = Handler(Looper.getMainLooper())::post,
) {
    @Volatile
    var linkHandler: FeedLinkHandler? = null

    private val lock = Any()
    private var cards: List<FeedCard> = emptyList()
    private var loaded = false
    private var fetchedAt = 0L
    private val reported = HashSet<String>()
    private val removed = HashSet<String>()

    private val unreadListeners = CopyOnWriteArrayList<FeedUnreadListener>()
    private val cardListeners = CopyOnWriteArrayList<(List<FeedCard>) -> Unit>()

    /** The unread count listeners last heard (main thread only). */
    private var notifiedUnread = -1

    val current: List<FeedCard> get() = synchronized(lock) { cards }

    val unread: Int get() = synchronized(lock) { cards.count { !it.read } }

    val loadedOnce: Boolean get() = synchronized(lock) { loaded }

    fun fetch(callback: FeedCallback?) = background {
        val success = fetchNow()
        callback?.let { main { it.onFeed(current, success) } }
    }

    /**
     * SDK thread: the app opened, events were sent or the visitor changed. The feed is loaded only while the app shows
     * it or watches the unread count, and at most once a minute unless [force].
     */
    fun refreshIfWatched(force: Boolean) {
        val watched = unreadListeners.isNotEmpty() || cardListeners.isNotEmpty()

        if (watched && (force || clock() - synchronized(lock) { fetchedAt } >= REFRESH_MILLIS)) {
            fetchNow()
        }
    }

    fun addUnreadListener(listener: FeedUnreadListener) {
        unreadListeners.addIfAbsent(listener)
        val count = unread
        main { listener.onUnreadChanged(count) }

        if (!loadedOnce) {
            fetch(null)
        }
    }

    fun removeUnreadListener(listener: FeedUnreadListener) {
        unreadListeners.remove(listener)
    }

    /** The ready-made feed view follows the cards while it is attached. */
    fun addCardListener(listener: (List<FeedCard>) -> Unit) {
        cardListeners.addIfAbsent(listener)
    }

    fun removeCardListener(listener: (List<FeedCard>) -> Unit) {
        cardListeners.remove(listener)
    }

    /** The card became visible in the open feed: reported once; a card the platform already has as read is not. */
    fun shown(token: String) {
        synchronized(lock) {
            if (cards.firstOrNull { it.token == token }?.read == true || !reported.add(token)) {
                return
            }

            markRead(token)
        }

        changed()
        report(token, "shown", null)
    }

    /**
     * A tap on the button, or on the card without one: the click is reported with the link and the link is opened.
     * Returns false when the card has no link that may be opened.
     */
    fun clicked(launcher: Context, card: FeedCard): Boolean {
        val url = card.link ?: return false

        synchronized(lock) {
            // The platform marks a clicked card read too.
            reported.add(card.token)
            markRead(card.token)
        }

        changed()
        report(card.token, "clicked", url)

        if (linkHandler?.onFeedLink(card, url) != true && openLinks && !Links.open(launcher, url)) {
            log("No app can open $url")
        }

        return true
    }

    fun dismissed(token: String) {
        synchronized(lock) {
            removed.add(token)
            cards = cards.filter { it.token != token }
        }

        changed()
        report(token, "dismissed", null)
    }

    /** SDK thread: a new anonymous id is another visitor with another feed. */
    fun reset() {
        synchronized(lock) {
            cards = emptyList()
            loaded = false
            fetchedAt = 0L
            reported.clear()
            removed.clear()
        }

        changed()
        refreshIfWatched(force = true)
    }

    /** SDK thread. */
    private fun fetchNow(): Boolean {
        val result = transport.get("/feed?anonymousId=${Uri.encode(anonymousId())}")

        if (!result.successful) {
            log("Feed: HTTP ${result.status}")
            return false
        }

        val data = runCatching { JSONObject(result.body).optJSONArray("data") }.getOrNull() ?: return false
        val now = clock()
        val received = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(FeedCard::parse) }

        synchronized(lock) {
            cards = received
                .filter { it.token !in removed && (it.expiresAt?.time ?: Long.MAX_VALUE) > now }
                .map { if (!it.read && it.token in reported) it.copy(read = true) else it }
            loaded = true
            fetchedAt = now
        }

        log("Feed: ${received.size} cards")
        changed()

        return true
    }

    /** Under [lock]. */
    private fun markRead(token: String) {
        cards = cards.map { if (it.token == token && !it.read) it.copy(read = true) else it }
    }

    private fun changed() {
        val snapshot = current
        val count = snapshot.count { !it.read }

        main {
            cardListeners.forEach { it(snapshot) }

            if (count != notifiedUnread) {
                notifiedUnread = count
                unreadListeners.forEach { it.onUnreadChanged(count) }
            }
        }
    }

    private fun report(token: String, event: String, url: String?) = background {
        val body = JSONObject().put("token", token).put("event", event)
        url?.let { body.put("url", it) }
        val result = transport.post("/feed/events", body)
        log("Feed $event reported: HTTP ${result.status}")
    }

    private fun background(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                log("Unexpected error: ${e.message}")
            }
        }
    }

    private companion object {
        const val REFRESH_MILLIS = 60_000L
    }
}
