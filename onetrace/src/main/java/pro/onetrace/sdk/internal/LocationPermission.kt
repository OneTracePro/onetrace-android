package pro.onetrace.sdk.internal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The location permission of the app as the platform segments by it: `none`, `foreground` (while in use) or `always`.
 * The SDK never asks for it; `onetrace-location` has a helper for the app to.
 */
internal object LocationPermission {
    fun of(context: Context): String {
        val granted = { permission: String -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }

        return when {
            !granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> "none"
            // Before Android 10 a granted location permission works in the background too.
            Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> "always"
            else -> "foreground"
        }
    }
}
