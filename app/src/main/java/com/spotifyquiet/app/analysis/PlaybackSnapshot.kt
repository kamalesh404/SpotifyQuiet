package com.spotifyquiet.app.analysis

/** Where a snapshot came from. */
enum class SnapshotSource {
    MEDIA_SESSION,
    NOTIFICATION,
    MERGED,
}

/** Playback state abstracted away from Android's MediaController constants. */
enum class PlaybackPhase {
    PLAYING,
    PAUSED,
    BUFFERING,
    STOPPED,
    ERROR,
    UNKNOWN,
}

/**
 * The minimal, platform-independent view of what Spotify is doing right now.
 *
 * Only the fields needed for diagnosis are captured; nothing that could
 * identify the user or their library is stored.
 */
data class PlaybackSnapshot(
    val timestampMs: Long,
    val source: SnapshotSource,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    val phase: PlaybackPhase = PlaybackPhase.UNKNOWN,
    val packageName: String = "",
) {
    fun normalizedTitle(): String? = title?.trim()?.takeIf { it.isNotEmpty() }

    fun normalizedArtist(): String? = artist?.trim()?.takeIf { it.isNotEmpty() }

    fun describe(): String = buildString {
        append(source.name.lowercase())
        append(", phase=").append(phase.name.lowercase())
        append(", title=").append(normalizedTitle() ?: "<none>")
        append(", artist=").append(normalizedArtist() ?: "<none>")
        append(", durationMs=").append(durationMs?.toString() ?: "<none>")
    }
}

/**
 * Combines the best available information from the media session (authoritative
 * for state and metadata) with the Spotify notification (fallback when session
 * metadata is missing).
 */
fun mergeSnapshots(
    session: PlaybackSnapshot?,
    notification: PlaybackSnapshot?,
): PlaybackSnapshot? {
    if (session == null && notification == null) return null
    if (session == null) return notification
    if (notification == null) return session

    val phase = if (session.phase != PlaybackPhase.UNKNOWN) session.phase else notification.phase
    return PlaybackSnapshot(
        timestampMs = maxOf(session.timestampMs, notification.timestampMs),
        source = SnapshotSource.MERGED,
        title = session.normalizedTitle() ?: notification.normalizedTitle(),
        artist = session.normalizedArtist() ?: notification.normalizedArtist(),
        album = session.album?.trim()?.takeIf { it.isNotEmpty() }
            ?: notification.album?.trim()?.takeIf { it.isNotEmpty() },
        durationMs = session.durationMs ?: notification.durationMs,
        positionMs = session.positionMs ?: notification.positionMs,
        phase = phase,
        packageName = session.packageName.ifEmpty { notification.packageName },
    )
}
