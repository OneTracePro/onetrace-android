package pro.onetrace.sdk.internal

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import pro.onetrace.sdk.BuildConfig
import java.util.Locale
import java.util.TimeZone

/**
 * The app and device in Segment Spec terms: `context.app`, `context.device`, `context.os`, locale, time zone, screen
 * and the library that sent the event.
 */
internal class DeviceContext(private val context: Context, private val installationId: String) {
    /** Set by the Flutter and React Native wrappers: they report themselves as the library. */
    @Volatile
    var wrapper: Pair<String, String>? = null

    val packageName: String get() = context.packageName

    val appVersion: String get() = packageInfo()?.versionName ?: ""

    @Suppress("DEPRECATION")
    val appBuild: String
        get() = packageInfo()?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else it.versionCode.toString() } ?: ""

    val locale: String get() = Locale.getDefault().toLanguageTag()

    val timezone: String get() = TimeZone.getDefault().id

    val libraryName: String get() = wrapper?.let { "onetrace-${it.first}" } ?: "onetrace-android"

    val libraryVersion: String get() = wrapper?.second ?: BuildConfig.SDK_VERSION

    /** «android 1.0.0» or «flutter 1.0.0» for the device registration. */
    val sdk: Pair<String, String> get() = wrapper ?: ("android" to BuildConfig.SDK_VERSION)

    fun build(): JSONObject {
        val metrics = context.resources.displayMetrics

        return JSONObject()
            .put("app", JSONObject()
                .put("name", context.applicationInfo.loadLabel(context.packageManager).toString())
                .put("version", appVersion)
                .put("build", appBuild)
                .put("namespace", packageName))
            .put("device", JSONObject()
                .put("id", installationId)
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("type", "android"))
            .put("os", JSONObject().put("name", "android").put("version", Build.VERSION.RELEASE ?: ""))
            .put("screen", JSONObject().put("width", metrics.widthPixels).put("height", metrics.heightPixels).put("density", metrics.density.toDouble()))
            .put("locale", locale)
            .put("timezone", timezone)
            .put("library", JSONObject().put("name", libraryName).put("version", libraryVersion))
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(): PackageInfo? = try {
        context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
