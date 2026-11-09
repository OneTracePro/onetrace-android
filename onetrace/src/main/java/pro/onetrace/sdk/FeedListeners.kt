package pro.onetrace.sdk

/** The result of [OneTrace.fetchFeed], on the main thread. */
public fun interface FeedCallback {
    /**
     * @param cards the feed: pinned cards first, then the newest; the last known feed when [success] is false.
     * @param success false when the feed could not be loaded (no network, the SDK is not initialized yet).
     */
    public fun onFeed(cards: List<FeedCard>, success: Boolean)
}

/** The number of feed cards the user has not seen yet, for a badge; called on the main thread. */
public fun interface FeedUnreadListener {
    public fun onUnreadChanged(unread: Int)
}

/** Opens the links of feed cards yourself, for example with in-app navigation. */
public fun interface FeedLinkHandler {
    /** @return true when the app opened [url]; false lets the SDK open it as it opens push links. */
    public fun onFeedLink(card: FeedCard, url: String): Boolean
}
