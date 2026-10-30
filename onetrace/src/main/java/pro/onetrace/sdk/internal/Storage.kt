package pro.onetrace.sdk.internal

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Identifiers and SDK state kept between launches.
 */
internal class Storage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pro.onetrace.sdk", Context.MODE_PRIVATE)

    /** Random id of this installation; also the `mobile_push` identity of the profile. Never changes. */
    val installationId: String = prefs.getString(INSTALLATION_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(INSTALLATION_ID, it).apply() }

    var anonymousId: String
        get() = prefs.getString(ANONYMOUS_ID, null) ?: newAnonymousId()
        set(value) = prefs.edit().putString(ANONYMOUS_ID, value).apply()

    var userId: String?
        get() = prefs.getString(USER_ID, null)
        set(value) = prefs.edit().putString(USER_ID, value).apply()

    var appVersion: String?
        get() = prefs.getString(APP_VERSION, null)
        set(value) = prefs.edit().putString(APP_VERSION, value).apply()

    var appBuild: String?
        get() = prefs.getString(APP_BUILD, null)
        set(value) = prefs.edit().putString(APP_BUILD, value).apply()

    var pushToken: String?
        get() = prefs.getString(PUSH_TOKEN, null)
        set(value) = prefs.edit().putString(PUSH_TOKEN, value).apply()

    var pushProvider: String?
        get() = prefs.getString(PUSH_PROVIDER, null)
        set(value) = prefs.edit().putString(PUSH_PROVIDER, value).apply()

    /** Fingerprint of the last device registration the platform accepted. */
    var registration: String?
        get() = prefs.getString(REGISTRATION, null)
        set(value) = prefs.edit().putString(REGISTRATION, value).apply()

    fun newAnonymousId(): String = UUID.randomUUID().toString().also { anonymousId = it }

    private companion object {
        const val INSTALLATION_ID = "installation_id"
        const val ANONYMOUS_ID = "anonymous_id"
        const val USER_ID = "user_id"
        const val APP_VERSION = "app_version"
        const val APP_BUILD = "app_build"
        const val PUSH_TOKEN = "push_token"
        const val PUSH_PROVIDER = "push_provider"
        const val REGISTRATION = "registration"
    }
}
