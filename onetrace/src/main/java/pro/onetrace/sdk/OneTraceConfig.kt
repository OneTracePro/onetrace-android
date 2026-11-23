package pro.onetrace.sdk

/**
 * SDK settings.
 *
 * @property writeKey the project write key (`cdp_wk_…`) from the OneTrace admin panel.
 * @property host the address you open OneTrace at, for example `https://crm.example.com`; `/api/v1` is added automatically.
 * @property flushAt send the queue once it has this many events.
 * @property flushIntervalSeconds send the queue at least this often while the app is running.
 * @property maxQueueSize events kept on disk while offline; the oldest are dropped beyond it.
 * @property trackLifecycle send `app_installed`, `app_updated`, `app_opened` and `app_backgrounded`.
 * @property trackActivityScreens send a `screen` call with the activity name every time an activity starts.
 * @property openPushLinks open the link of a tapped push notification (`cdp_url`) automatically.
 * @property debug log what the SDK does to Logcat under the `OneTrace` tag.
 * @property remoteConfigMinFetchIntervalSeconds fetch the app settings ([OneTrace.remoteConfig]) at most this often;
 * `identify` and `reset` fetch them right away.
 * @property richPush the app passes OneTrace push data to [OneTrace.handlePushData] (the `onetrace-fcm` module does it
 * itself): the platform then sends carousels, buttons, timers, Live Updates and silent checks to this installation.
 * The Flutter and React Native wrappers set it when the app turns the push handling on.
 */
public class OneTraceConfig @JvmOverloads constructor(
    public val writeKey: String,
    public val host: String,
    public val flushAt: Int = 20,
    public val flushIntervalSeconds: Long = 30,
    public val maxQueueSize: Int = 1000,
    public val trackLifecycle: Boolean = true,
    public val trackActivityScreens: Boolean = false,
    public val openPushLinks: Boolean = true,
    public val debug: Boolean = false,
    public val remoteConfigMinFetchIntervalSeconds: Long = 3600,
    public val richPush: Boolean = false,
) {
    internal val apiUrl: String
        get() = host.trim().trimEnd('/').removeSuffix("/api/v1") + "/api/v1"
}
