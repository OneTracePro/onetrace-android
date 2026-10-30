# Changelog

## 1.0.0 — 2026-10-30

- Events, screens, identify, alias and reset with the Segment Spec format of the OneTrace.pro API.
- A persistent queue: events survive restarts and offline periods, are sent in batches and retried on network errors, `429` and `5xx`.
- App lifecycle events: `app_installed`, `app_updated`, `app_opened`, `app_backgrounded`; optional automatic screens for activities.
- Installation registration for mobile push: the push token, the notification permission, the app and device details; a sign-out moves the installation to the next user.
- Push taps: the open and the link click are reported and the link is opened automatically.
- In-app messages: fetched when the app opens and after events are sent, shown over the current activity as a modal, a banner or a full screen; shown, button taps and closes are reported.
- For the Flutter and React Native wrappers: `attachActivity()` for an SDK started after the first activity, `notificationOpened()` for taps on notifications shown by the app's own code; a tap is reported once.
- `onetrace-fcm`: Firebase Cloud Messaging service — token updates, delivery receipts, notifications with images in the foreground.
