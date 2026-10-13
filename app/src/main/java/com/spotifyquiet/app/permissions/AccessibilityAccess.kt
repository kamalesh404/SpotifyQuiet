package com.spotifyquiet.app.permissions

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.spotifyquiet.app.service.SpotifyQuietAccessibilityService

/**
 * Accessibility access is a special access setting, not an ordinary runtime
 * permission: the user must enable SpotifyQuiet in Android's accessibility
 * settings. SpotifyQuiet never enables the service silently and never acts
 * through it unless the user explicitly arms the recovery experiment.
 */
object AccessibilityAccess {

    fun isGranted(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java)
            ?: return false
        val services = manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ) ?: return false
        return services.any { info ->
            info.resolveInfo?.serviceInfo?.packageName == context.packageName
        }
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
