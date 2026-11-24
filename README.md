# OneTrace.pro for Android

[![CI](https://github.com/OneTracePro/onetrace-android/actions/workflows/ci.yml/badge.svg)](https://github.com/OneTracePro/onetrace-android/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/OneTracePro/onetrace-android.svg)](https://jitpack.io/#OneTracePro/onetrace-android)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

The Android SDK of [OneTrace.pro](https://onetrace.pro), the customer data platform: events and screens of your app users in the same profiles as your website, CRM and stores — for segments, journeys, predictions and analytics — and mobile push notifications.

- Kotlin, Android 5.0+ (API 21). No third-party dependencies in the core besides AndroidX Core and Lifecycle.
- Events are queued on disk and sent in batches: nothing is lost offline or on a restart.
- Mobile push through Firebase Cloud Messaging with the optional `onetrace-fcm` module: tokens, delivery receipts, opens and links of tapped notifications.
- Rich push drawn by the SDK: carousels, countdowns, up to three buttons with «Remind me later»; order and delivery status as Android Live Updates.
- Remote Config: app settings by segment, platform and app version without a new release.
- Geofences with the optional `onetrace-location` module: entries, exits and the time spent in a zone as events, without sending the device position.
- In-app messages and a message feed with a ready-made screen.

## Installation

Add JitPack to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

and the SDK to the app module:

```kotlin
dependencies {
    implementation("com.github.OneTracePro.onetrace-android:onetrace:1.3.0")
    // Mobile push through Firebase Cloud Messaging:
    implementation("com.github.OneTracePro.onetrace-android:onetrace-fcm:1.3.0")
}
```

The Flutter and React Native wrappers take this working copy from the local Maven repository instead of JitPack when the app sets the Gradle property `onetrace.mavenLocal=true` — publish it first:

```bash
./gradlew :onetrace:publishReleasePublicationToMavenLocal :onetrace-fcm:publishReleasePublicationToMavenLocal :onetrace-location:publishReleasePublicationToMavenLocal
```

## Quick start

In `Application.onCreate()`:

```kotlin
OneTrace.init(this, OneTraceConfig(
    writeKey = "cdp_wk_…",                // Settings → API keys: a write key
    host = "https://crm.example.com",    // the address you open OneTrace at
))
```

Then, anywhere:

```kotlin
OneTrace.screen("Product", mapOf("product_id" to "SKU-1"))
OneTrace.track("add_to_cart", mapOf("product_id" to "SKU-1", "price" to 49.9))
OneTrace.identify("u-42", mapOf("email" to "anna@example.com"))  // after sign-in
OneTrace.reset()                                                 // after sign-out
```

Use the same event names and `product_id` values as on your website — then recommendations, abandoned cart journeys and the reports work across the website and the app. See the [e-commerce events](https://onetrace.pro/en/docs/api).

The SDK sends `app_installed`, `app_updated`, `app_opened` and `app_backgrounded` itself, with the app version, device model, OS version, language and time zone in the context of every event. Turn on `trackActivityScreens = true` to send a screen for every activity.

## Signed user ids

The write key ships inside the app, so anyone could call `identify` with someone else's user id and receive that customer's in-app messages and push. Sign the user id on your server to prove it belongs to the signed-in user. When the project requires the signature (**Website → Connection → Server**), a user id without a valid one is ignored: events stay with the anonymous id, and the installation is not registered for that user.

1. Create the signing key on the **Website → Connection → Server** tab and keep it on your server only — never put it into the app.
2. After sign-in, your server returns the signature with the session: HMAC-SHA256 of the user id with the signing key, in hex.

   ```php
   $userHash = hash_hmac('sha256', $userId, getenv('CDP_IDENTITY_KEY'));
   ```

   ```js
   const userHash = crypto.createHmac('sha256', process.env.CDP_IDENTITY_KEY).update(userId).digest('hex');
   ```

3. Pass it to `identify` (and to `alias`):

   ```kotlin
   OneTrace.identify(session.userId, mapOf("email" to session.email), session.userHash)
   ```

The SDK keeps the signature with the user id and sends it as `userHash` with every event and the installation registration. A later `identify` of the same user without a signature keeps the stored one; another user id or `reset()` clears it. After the key is changed, the previous one is accepted for another 24 hours: return signatures with the new key from your server before then.

## Push notifications

1. Add Firebase to the app (`google-services.json` and the `com.google.gms.google-services` plugin) and the `onetrace-fcm` dependency.
2. After `OneTrace.init(…)` call `OneTraceFcm.register()` — the current FCM token goes to OneTrace.
3. On Android 13+ ask for the `POST_NOTIFICATIONS` permission and then call `OneTrace.notificationPermissionChanged()`.
4. In OneTrace add the **Mobile push** integration with your package name and the Firebase service account (Firebase → Project settings → Service accounts → Generate new private key).

That is all: the bundled `OneTraceMessagingService` updates the token, reports notifications as delivered and shows them while the app is in the foreground. A tap opens the app; the SDK reports the open and opens the notification link (`myapp://…` or `https://…`) — set `openPushLinks = false` to handle `cdp_url` from the intent extras yourself.

If your app already has a `FirebaseMessagingService`, remove ours and call the helpers from yours:

```xml
<service android:name="pro.onetrace.sdk.fcm.OneTraceMessagingService" tools:node="remove" />
```

```kotlin
override fun onNewToken(token: String) = OneTraceFcm.onNewToken(token)

override fun onMessageReceived(message: RemoteMessage) {
    if (!OneTraceFcm.onMessageReceived(this, message)) {
        // not from OneTrace
    }
}
```

For a `singleTop` or `singleTask` activity call `OneTrace.handleIntent(this, intent)` from `onNewIntent()`. Notifications use the `onetrace` channel; set your own with the `pro.onetrace.notification_channel` meta-data, and the icon with `com.google.firebase.messaging.default_notification_icon`.

### Rich push

With `onetrace-fcm` the SDK draws OneTrace messages itself, from data-only FCM messages: the installation reports the `rich`, `live_updates` and `silent` capabilities, and the platform sends carousels, buttons, countdowns and Live Updates only to such installations (others get a plain notification with the first image).

- **Carousel** of 2–10 cards: paged with «‹ ›» in the expanded notification; a tap on a card opens its link and is reported as a click with it. Images download in parallel, up to 10 seconds and 1 MB each; a card whose image did not load is skipped, and with fewer than two cards left the notification is a plain one with the first image there is.
- **Countdown** to the end of an offer: a chronometer counting down; with «Remove at the end» the notification disappears at that moment.
- **Buttons** (up to three): «Open link», «Open app», «Close» (removes the notification, reports nothing) and «Remind me later»: the phone shows the same notification again in 60 or 180 minutes or tomorrow at 9:00 by its own clock, without network and without the platform sending it again. A button tap is reported as an open with the button (`action`); a tap reported twice (the SDK and a wrapper) counts once.
- **Silent checks** (`cdp_kind=ping`): the platform checks that the app is still installed; nothing is shown or reported.

Without `onetrace-fcm` (your own push setup or a wrapper), pass the data on and tell the SDK it does:

```kotlin
OneTrace.init(this, OneTraceConfig(writeKey = "cdp_wk_…", host = "https://crm.example.com", richPush = true))

override fun onMessageReceived(message: RemoteMessage) {
    if (!OneTrace.handlePushData(this, message.data)) {
        // not from OneTrace
    }
}
```

If your own code shows the notification and handles its buttons, report the tap with the button id: `OneTrace.notificationOpened(context, data, actionId)`.

### Live Updates

Messages of the «Live Activity» kind — order and delivery status, a countdown — keep one notification per type and key and update it in place. On Android 16 it is a Live Update (`ProgressStyle` with the steps as segments, promoted to the status bar; the SDK declares `POST_PROMOTED_NOTIFICATIONS`), before it an ongoing notification with the progress, «Step 2 of 4» and a countdown. When the countdown is over the notification shows «Completed»; `end` shows the final state and the system removes it after «Dismiss after N minutes». The SDK reports the shown notification (`started`) and a swipe (`dismissed`); after a swipe updates do not bring it back, only a new `start` does. An update sent with «Notify» (`alert`) sounds and shows a banner through the messages channel; the others change the notification silently. From the stale time of the state («Stale after») the notification says «out of date» until the next update. They use the `onetrace_live` channel; set your own with the `pro.onetrace.live_channel` meta-data.

## Remote Config

App settings from **Apps → App settings**: values by segment, platform and app version, without a new release.

```kotlin
val banner = OneTrace.remoteConfig.getString("home_banner", "default")
val newCheckout = OneTrace.remoteConfig.getBoolean("new_checkout", false)
val freeDelivery = OneTrace.remoteConfig.getLong("free_delivery_from", 3000)
val menu = OneTrace.remoteConfig.getJson("menu", null)?.let(::JSONObject)  // objects and lists as JSON text

OneTrace.remoteConfig.addListener { changedKeys -> refreshHome(changedKeys) }  // main thread
OneTrace.remoteConfig.fetch { success -> /* main thread */ }
```

The SDK fetches the values when the app starts and opens, at most once an hour (`remoteConfigMinFetchIntervalSeconds`), and right after `identify` and `reset`; an unchanged set costs a `304`. Until the first answer and offline the getters return the values of the last launch, and without them the defaults you pass. Any app with the write key sees the values: never keep secrets there.

## Geofences

```kotlin
implementation("com.github.OneTracePro.onetrace-android:onetrace-location:1.3.0")
```

The module uses Google Play services (`play-services-location`) and declares the location permissions; the core stays without them, so an app without geofences does not get them. Zones come from **Apps → Geofences**: the SDK fetches the list at launch and every 12 hours, monitors the 90 nearest zones (`OneTraceLocation.maxMonitored`; Android allows 100 per app) and selects them again when the device leaves the area around them or the list changes. A removed or switched off zone stops being monitored with the next list.

The SDK never asks for the location permission itself — ask when it makes sense in your app:

```kotlin
// The precise permission first; call it again after the answer for «Allow all the time» (Android 10+).
OneTraceLocation.requestLocationPermission(activity)

when (OneTraceLocation.enableGeofences(context)) {
    GeofenceStatus.ENABLED -> Unit
    GeofenceStatus.BACKGROUND_PERMISSION_MISSING -> showWhyAllTheTime()
    else -> Unit  // LOCATION_PERMISSION_MISSING, PLAY_SERVICES_UNAVAILABLE, NOT_INITIALIZED
}
```

Call `OneTrace.init` in `Application.onCreate()` (as in the quick start, not later from an activity): Android starts the app in the background for a zone transition, a restart of the phone or an app update, and only `Application.onCreate()` runs then. A transition that comes before `OneTrace.init` is kept with its time and sent after it, and the zones are registered again after a restart of the phone without it; the list is fetched once the SDK is started. Call `enableGeofences` at every start of the app: it registers the zones again (after a reinstall Auto Backup restores the settings of the SDK, but not the zones in the system).

Without the background permission nothing is registered and the reason is returned; once the user grants it, the zones turn on when the app comes back, without a restart. The installation reports `location.permission` (`none`, `foreground`, `always`) for segments. The module declares `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` (Google Play asks why the app needs it) and `RECEIVE_BOOT_COMPLETED`: after a restart of the phone and an app update the zones are registered again. When location is turned off, the system drops the zones; the SDK registers them again when the app comes back with location on.

Events go through the usual queue (they wait offline) and count as events of the plan:

- `geofence_entered`, `geofence_exited` with `geofence_id`, `geofence_external_id`, `geofence_name`; the exit has `dwell_seconds` since the entry — none when the entry was not recorded, and an entry older than 24 hours without an exit is dropped;
- `geofence_dwelled` with `dwell_seconds` after the zone's «dwelled» threshold (the system DWELL transition).

A re-entry into the same zone within 30 minutes after the exit is not reported (GPS jitter at the border). The device position is used on the device only, to select the zones: no coordinates go to the platform.

## Message feed

The feed keeps personal cards — offers, news, reminders, promo codes — in the app until they expire or the user removes them; unlike in-app messages, which are shown once. Cards come from journeys and campaigns through the **Message feed** channel; the same cards are shown in the feed widget of your website. Requires the feed in the plan; without it the feed is empty.

### Ready-made screen

```kotlin
OneTrace.openFeed(context)
```

opens `OneTraceFeedActivity`: the "Updates" header and the cards in their layouts (image on the left, image on top, image only), pinned cards first with the "Pinned" label, a remove button on every card, an empty state and pull-to-refresh. A card is reported shown once at least half of it is visible; a tap on the button, or on a card without a button, reports the click and opens its link (a web page or a deep link of your app, as for push). The texts follow the device language (English, Russian, German, Uzbek, Turkish, Spanish, French, Italian, Portuguese, Chinese), the colors follow the night mode, buttons take the `colorAccent` of your app theme.

To put the feed into a screen of your own, add `OneTraceFeedView` — from code or a layout:

```xml
<pro.onetrace.sdk.OneTraceFeedView
    android:id="@+id/feed"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

```kotlin
binding.feed.accentColor = ContextCompat.getColor(this, R.color.brand)  // optional
binding.feed.refresh()                                                  // it loads itself when attached
```

### Unread badge

```kotlin
private val unread = FeedUnreadListener { count -> badge.isVisible = count > 0; badge.text = "$count" }

override fun onStart() {
    super.onStart()
    OneTrace.addFeedUnreadListener(unread)  // called now and on every change, on the main thread
}

override fun onStop() {
    OneTrace.removeFeedUnreadListener(unread)
    super.onStop()
}
```

While a listener is added, the SDK loads the feed when the app opens and after it sends events, at most once a minute. `OneTrace.feedUnreadCount` reads the current count.

### Your own feed UI

```kotlin
OneTrace.fetchFeed { cards, success ->
    adapter.submitList(cards)  // FeedCard: token, layout, title, body, imageUrl, buttonText, buttonUrl, pinned, read, createdAt, expiresAt
}

OneTrace.feedCardShown(card)              // at least half of the card is visible: reported once, the card becomes read
OneTrace.feedCardClicked(context, card)   // the button, or the card when card.opensOnTap: reports the click, opens the link
OneTrace.feedCardDismissed(card)          // removed: it does not come back
```

Shows reported in this session stay read after a refresh even before the platform has them. To open card links yourself (for example with in-app navigation):

```kotlin
OneTrace.setFeedLinkHandler { _, url -> router.open(url); true }  // true: handled; false: the SDK opens it
```

With `openPushLinks = false` the SDK opens no card links itself; set a link handler.

## How it works

- Every installation has a random id; while push can be sent, it is the `mobile_push` identity of the profile, and the profile is reachable by the **Mobile push** channel in journeys and campaigns.
- `identify` and `reset` re-register the installation: after a sign-out push goes to the next user, the profiles are not merged.
- Events are sent when 20 are queued, every 30 seconds and when the app goes to the background; up to 1,000 are kept on disk.

## Tests

`./gradlew :onetrace:testDebugUnitTest :onetrace-location:testDebugUnitTest` runs the unit tests (Robolectric; they run on Android 15, the Android 16 Live Update path is compiled only). Against a real project:

```bash
./gradlew :onetrace:testDebugUnitTest --tests '*LiveTest*' -Ponetrace.live.url=https://crm.example.com -Ponetrace.live.key=cdp_wk_…
```

## License

MIT
