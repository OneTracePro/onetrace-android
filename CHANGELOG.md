# Changelog

## Unreleased

- Rich push drawn by the core SDK, without Firebase: `OneTrace.handlePushData(context, data)` draws carousels (paged with «‹ ›», a tap on a card opens its link and reports the click), countdowns (a chronometer, removed at the end) and up to three buttons — open a link, open the app, close, and «Remind me later» as a local notification in 60 or 180 minutes or tomorrow at 9:00. Images download in parallel, up to 10 seconds and 1 MB each; cards without an image are skipped. `onetrace-fcm` now passes every OneTrace message to it.
- `notificationOpened(context, data, actionId)`: button taps are reported with the button; the memory of the last 50 taps counts a button apart from the notification.
- Android Live Updates: one notification per type and key updated in place — a Live Update on Android 16 (`ProgressStyle`, promoted ongoing), an ongoing notification with progress and a countdown before it; the shown notification and a swipe are reported to the platform, a swiped one comes back only with `start`.
- Silent checks (`cdp_kind=ping`) show and report nothing.
- The installation reports `push.capabilities` (`rich`, `live_updates`, `silent` — with `onetrace-fcm` or `richPush = true` in the config) and `location.permission`.
- Remote Config: `OneTrace.remoteConfig` with `getString`, `getBoolean`, `getLong`, `getDouble`, `getJson`, `fetch` and change listeners; fetched at launch and when the app opens at most once per `remoteConfigMinFetchIntervalSeconds` (an hour), right after `identify` and `reset`, with an ETag; stored values offline.
- New module `onetrace-location`: geofences with Google Play services — `OneTraceLocation.enableGeofences`, `disableGeofences`, `maxMonitored` (90 nearest zones and an update region), `requestLocationPermission()` for the app to call; `geofence_entered`, `geofence_dwelled` and `geofence_exited` with `dwell_seconds`, no coordinates, no re-entry within 30 minutes; registered again after a restart and an app update.
- Live Updates: an update with «Notify» (`alert`) sounds with a banner, others stay silent; from `staleAt` the notification says «out of date» until the next update.
- Geofences: one set of zones per selection, registered one after another (never over the limit of 100); a transition before `OneTrace.init` is kept with its time and sent after it; after a restart the zones are registered without `OneTrace.init`; `enableGeofences` registers them at every call (after a reinstall with restored settings); zones dropped with location turned off are registered again on the next resume.
- A refusal of the platform (402 without the plan's feature, other 4xx but 429) to `/app/config` and `/geofences` is asked again after the interval, not at every launch.
- `compileSdk` 36; `minSdk` stays 21.

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
