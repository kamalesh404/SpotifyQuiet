package com.spotifyquiet.app.data

import com.spotifyquiet.app.analysis.Classification
import com.spotifyquiet.app.analysis.Confidence

enum class EventKind {
    SESSION_APPEARED,
    SESSION_REMOVED,
    MEDIA_STATE,
    MEDIA_METADATA,
    NOTIFICATION_POSTED,
    NOTIFICATION_REMOVED,
    RECOVERY,
}

data class PlaybackEvent(
    val id: Long,
    val timestampMs: Long,
    val kind: EventKind,
    val classification: Classification,
    val confidence: Confidence,
    val title: String?,
    val artist: String?,
    val album: String?,
    val phase: String?,
    val positionMs: Long?,
    val durationMs: Long?,
    val evidence: List<String>,
) {
    fun summary(): String = buildString {
        append(title?.takeIf { it.isNotBlank() } ?: "—")
        if (!artist.isNullOrBlank()) append(" · ").append(artist)
    }
}
