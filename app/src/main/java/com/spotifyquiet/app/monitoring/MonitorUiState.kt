package com.spotifyquiet.app.monitoring

import com.spotifyquiet.app.analysis.PlaybackSnapshot
import com.spotifyquiet.app.data.ManualLabel
import com.spotifyquiet.app.data.PlaybackEvent
import com.spotifyquiet.app.recovery.RecoveryOutcome

/** Immutable snapshot of everything the UI renders. */
data class MonitorUiState(
    val monitoring: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val accessibilityServiceGranted: Boolean = false,
    val spotifyInstalled: Boolean = true,
    val sessionConnected: Boolean = false,
    val notificationVisible: Boolean = false,
    val current: PlaybackSnapshot? = null,
    val events: List<PlaybackEvent> = emptyList(),
    val diagnosticLabels: Map<Long, ManualLabel> = emptyMap(),
    val diagnosticCount: Int = 0,
    val autoRecoveryEnabled: Boolean = false,
    val recoveryMode: com.spotifyquiet.app.recovery.RecoveryMode =
        com.spotifyquiet.app.recovery.RecoveryMode.DISABLED,
    val recoveryAttempts: Int = 0,
    val recoveryAttemptsRemaining: Int = 0,
    val lastRecoveryOutcome: RecoveryOutcome? = null,
    val notice: String? = null,
) {
    val statusLabel: String
        get() = when {
            !notificationAccessGranted -> "Notification access required"
            !monitoring -> "Monitoring stopped"
            sessionConnected -> "Monitoring · Spotify session connected"
            notificationVisible -> "Monitoring · Spotify notification visible"
            else -> "Monitoring · waiting for Spotify"
        }
}
