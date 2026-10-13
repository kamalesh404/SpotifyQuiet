package com.spotifyquiet.app.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.spotifyquiet.app.service.SpotifyQuietListenerService

/**
 * Notification access is a special access setting, not an ordinary runtime
 * permission: the user must enable SpotifyQuiet in Android's special-access
 * screen. This helper only reports status and opens that screen.
 */
object NotificationAccess {

    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, SpotifyQuietListenerService::class.java)

    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun settingsIntentFallback(): Intent = Intent(Settings.ACTION_SETTINGS)
}
