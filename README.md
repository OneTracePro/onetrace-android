# OneTrace.pro for Android

[![CI](https://github.com/OneTracePro/onetrace-android/actions/workflows/ci.yml/badge.svg)](https://github.com/OneTracePro/onetrace-android/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/OneTracePro/onetrace-android.svg)](https://jitpack.io/#OneTracePro/onetrace-android)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

The Android SDK of [OneTrace.pro](https://onetrace.pro), the customer data platform: events and screens of your app users in the same profiles as your website, CRM and stores — for segments, journeys, predictions and analytics — and mobile push notifications.

- Kotlin, Android 5.0+ (API 21). No third-party dependencies in the core besides AndroidX Core and Lifecycle.
- Events are queued on disk and sent in batches: nothing is lost offline or on a restart.
- Mobile push through Firebase Cloud Messaging with the optional `onetrace-fcm` module: tokens, delivery receipts, opens and links of tapped notifications.

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
    implementation("com.github.OneTracePro.onetrace-android:onetrace:1.0.0")
    // Mobile push through Firebase Cloud Messaging:
    implementation("com.github.OneTracePro.onetrace-android:onetrace-fcm:1.0.0")
}
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

## How it works

- Every installation has a random id; while push can be sent, it is the `mobile_push` identity of the profile, and the profile is reachable by the **Mobile push** channel in journeys and campaigns.
- `identify` and `reset` re-register the installation: after a sign-out push goes to the next user, the profiles are not merged.
- Events are sent when 20 are queued, every 30 seconds and when the app goes to the background; up to 1,000 are kept on disk.

## Tests

`./gradlew :onetrace:testDebugUnitTest` runs the unit tests (Robolectric). Against a real project:

```bash
./gradlew :onetrace:testDebugUnitTest --tests '*LiveTest*' -Ponetrace.live.url=https://crm.example.com -Ponetrace.live.key=cdp_wk_…
```

## License

MIT
