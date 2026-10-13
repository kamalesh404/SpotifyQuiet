package com.spotifyquiet.app.monitoring

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Extracts only the diagnosis-relevant fields from Spotify's media
 * notification. Used as a fallback when the media session does not publish
 * metadata, and as corroboration when it does.
 */
object NotificationParser {

    /** Extra key for the media notification chronometer (public API surface varies by level). */
    private const val EXTRA_SHOW_CHRONOMETER = "android.showChronometer"

    data class ParsedNotification(
        val notificationId: Int,
        val title: String?,
        val text: String?,
        val subText: String?,
        val phaseHint: com.spotifyquiet.app.analysis.PlaybackPhase,
        val evidence: List<String>,
    )

    fun parse(sbn: StatusBarNotification): ParsedNotification? {
        if (sbn.packageName != SpotifyLauncherPackage.NAME) return null
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        val evidence = mutableListOf<String>()
        var phase = com.spotifyquiet.app.analysis.PlaybackPhase.UNKNOWN

        val inferred = inferPhaseFromActions(notification)
        if (inferred != com.spotifyquiet.app.analysis.PlaybackPhase.UNKNOWN) {
            phase = inferred
            evidence += "phase inferred from notification actions (${inferred.name.lowercase()})"
        } else if (extras.getBoolean(EXTRA_SHOW_CHRONOMETER, false)) {
            phase = com.spotifyquiet.app.analysis.PlaybackPhase.PLAYING
            evidence += "phase inferred from active chronometer (playing)"
        }

        if (title == null && text == null) return null

        return ParsedNotification(
            notificationId = sbn.id,
            title = title,
            text = text,
            subText = subText,
            phaseHint = phase,
            evidence = evidence,
        )
    }

    /**
     * Media notifications advertise their primary transport action. A visible
     * "Pause" means playback is running; a visible "Play" means it is not.
     */
    private fun inferPhaseFromActions(
        notification: Notification,
    ): com.spotifyquiet.app.analysis.PlaybackPhase {
        val actions = notification.actions
            ?: return com.spotifyquiet.app.analysis.PlaybackPhase.UNKNOWN
        for (action in actions) {
            val label = action?.title?.toString()?.trim()?.lowercase() ?: continue
            if (label == "pause") {
                return com.spotifyquiet.app.analysis.PlaybackPhase.PLAYING
            }
            if (label == "play") {
                return com.spotifyquiet.app.analysis.PlaybackPhase.PAUSED
            }
        }
        return com.spotifyquiet.app.analysis.PlaybackPhase.UNKNOWN
    }

    object SpotifyLauncherPackage {
        const val NAME = "com.spotify.music"
    }
}
