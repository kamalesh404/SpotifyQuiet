package com.spotifyquiet.app.data

/**
 * Bounded in-memory ring of recorded playback events.
 *
 * Deliberately not a database: the first version only needs to show a recent
 * diagnostic history while the process lives, and it must never grow without
 * a limit.
 */
class EventLog(val maxSize: Int = DEFAULT_MAX_SIZE) {

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    private val items = ArrayDeque<PlaybackEvent>(maxSize.coerceAtMost(64))

    val size: Int
        get() = items.size

    val isEmpty: Boolean
        get() = items.isEmpty()

    fun add(event: PlaybackEvent) {
        items.addLast(event)
        while (items.size > maxSize) {
            items.removeFirst()
        }
    }

    fun last(): PlaybackEvent? = items.lastOrNull()

    fun snapshot(): List<PlaybackEvent> = items.toList()

    fun exportAsText(): String = buildString {
        if (items.isEmpty()) {
            append("No events recorded.")
            return@buildString
        }
        appendLine("=== SpotifyQuiet Event Log (${items.size} events) ===")
        for (event in items) {
            appendLine("[#${event.id} @ ${event.timestampMs}ms] ${event.kind} -> ${event.classification} (${event.confidence})")
            appendLine("  Track: ${event.summary()} | Phase: ${event.phase ?: "—"}")
            if (event.evidence.isNotEmpty()) {
                appendLine("  Evidence: ${event.evidence.joinToString("; ")}")
            }
        }
    }

    fun clear() = items.clear()

    companion object {
        const val DEFAULT_MAX_SIZE: Int = 200
    }
}
