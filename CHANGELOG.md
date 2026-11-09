# Changelog

## 1.2.0 — 2026-11-09

- Message feed: cards that stay in the app until they expire or the user removes them. `OneTrace.openFeed(context)` opens the ready-made screen (`OneTraceFeedActivity`), `OneTraceFeedView` puts the feed into your own one: the three card layouts, pinned cards, the remove button, an empty state, pull-to-refresh, light and dark colors, texts in 10 languages. Cards at least half visible are reported shown, taps report the click and open the link.
- Feed data for your own UI: `fetchFeed`, `feedCards`, `feedUnreadCount` and `addFeedUnreadListener` for a badge, `feedCardShown`, `feedCardClicked`, `feedCardDismissed`, `setFeedLinkHandler`. Shows of this session stay read after a refresh before the platform has them.
- Images of in-app messages and feed cards are scaled down to the size they are shown at and kept in memory.

## 1.1.0 — 2026-11-02

- Signed user ids (`userHash`): `identify(userId, traits, userHash)` and `alias(userId, userHash)` keep the signature from your server with the user id and send it with every event and the installation registration; `reset()` clears it. Required when the project turns on the user id signature.

## 1.0.0 — 2026-10-31

- Events, screens, identify, alias and reset with the Segment Spec format of the OneTrace.pro API.
- A persistent queue: events survive restarts and offline periods, are sent in batches and retried on network errors, `429` and `5xx`.
- App lifecycle events: `app_installed`, `app_updated`, `app_opened`, `app_backgrounded`; optional automatic screens for activities.
- Installation registration for mobile push: the push token, the notification permission, the app and device details; a sign-out moves the installation to the next user.
- Push taps: the open and the link click are reported and the link is opened automatically.
- In-app messages: fetched when the app opens and after events are sent, shown over the current activity as a modal, a banner or a full screen; shown, button taps and closes are reported.
- For the Flutter and React Native wrappers: `attachActivity()` for an SDK started after the first activity, `notificationOpened()` for taps on notifications shown by the app's own code; a tap is reported once.
- `onetrace-fcm`: Firebase Cloud Messaging service — token updates, delivery receipts, notifications with images in the foreground.
