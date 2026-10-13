package com.spotifyquiet.app.data

import com.spotifyquiet.app.analysis.Classification

/** What the user asserted about an observed event. */
enum class ManualLabel {
    AD,
    NOT_AD,
}

/**
 * A user-supplied ground-truth label attached to a recorded event.
 *
 * These labels exist so that captured evidence can later be compared against
 * what really happened on the device; they are never used to claim the
 * analyzer was correct.
 */
data class DiagnosticMark(
    val eventId: Long,
    val timestampMs: Long,
    val label: ManualLabel,
    val title: String?,
    val artist: String?,
    val analyzerClassification: Classification,
    val note: String? = null,
)

class DiagnosticStore(val maxSize: Int = DEFAULT_MAX_SIZE) {

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    private val items = LinkedHashMap<Long, DiagnosticMark>()

    val size: Int
        get() = items.size

    fun mark(event: PlaybackEvent, label: ManualLabel, note: String? = null): DiagnosticMark {
        val existing = items.remove(event.id)
        val mark = DiagnosticMark(
            eventId = event.id,
            timestampMs = if (existing != null) existing.timestampMs else event.timestampMs,
            label = label,
            title = event.title,
            artist = event.artist,
            analyzerClassification = event.classification,
            note = note,
        )
        items[event.id] = mark
        while (items.size > maxSize) {
            val oldest = items.keys.firstOrNull() ?: break
            items.remove(oldest)
        }
        return mark
    }

    fun labelFor(eventId: Long): ManualLabel? = items[eventId]?.label

    fun all(): List<DiagnosticMark> = items.values.toList()

    fun clear() = items.clear()

    companion object {
        const val DEFAULT_MAX_SIZE: Int = 100
    }
}
