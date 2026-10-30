# Changelog

## 1.0.0 — 2026-10-30

- Events, screens, identify, alias and reset with the Segment Spec format of the OneTrace.pro API.
- A persistent queue: events survive restarts and offline periods, are sent in batches and retried on network errors, `429` and `5xx`.
- App lifecycle events: `app_installed`, `app_updated`, `app_opened`, `app_backgrounded`; optional automatic screens for activities.
- Installation registration for mobile push: the push token, the notification permission, the app and device details; a sign-out moves the installation to the next user.
- Push taps: the open and the link click are reported and the link is opened automatically.
- `onetrace-fcm`: Firebase Cloud Messaging service — token updates, delivery receipts, notifications with images in the foreground.
