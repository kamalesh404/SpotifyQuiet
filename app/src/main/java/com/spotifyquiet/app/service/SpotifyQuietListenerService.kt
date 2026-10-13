package com.spotifyquiet.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.spotifyquiet.app.monitoring.MonitoringCoordinator

/**
 * System-bound notification listener.
 *
 * This service only exists because the user explicitly granted notification
 * access in Android settings. It does not promote itself to a foreground
 * service, acquires no wake locks, and performs no polling: every callback is
 * driven by Android or by Spotify itself.
 *
 * It is also the anchor that lets [com.spotifyquiet.app.permissions.NotificationAccess]
 * enumerate active media sessions through MediaSessionManager.
 */
class SpotifyQuietListenerService : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        MonitoringCoordinator.attach(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        MonitoringCoordinator.onListenerConnected()
    }

    override fun onListenerDisconnected() {
        MonitoringCoordinator.onListenerDisconnected()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null) {
            MonitoringCoordinator.onNotificationPosted(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null) {
            MonitoringCoordinator.onNotificationRemoved(sbn)
        }
    }

    override fun onDestroy() {
        MonitoringCoordinator.onListenerDisconnected()
        super.onDestroy()
    }
}
