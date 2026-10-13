package com.spotifyquiet.app.monitoring

import com.spotifyquiet.app.analysis.AnalysisResult
import com.spotifyquiet.app.analysis.PlaybackEventAnalyzer
import com.spotifyquiet.app.analysis.PlaybackSnapshot
import com.spotifyquiet.app.data.EventLog
import com.spotifyquiet.app.data.EventKind
import com.spotifyquiet.app.data.PlaybackEvent

/**
 * Turns incoming snapshots into classified, deduplicated log entries.
 *
 * Pure Kotlin on purpose: monitoring on/off behaviour, deduplication and event
 * creation are unit tested without touching Android.
 */
class PlaybackMonitorEngine(
    private val analyzer: PlaybackEventAnalyzer,
    private val log: EventLog,
) {
    var monitoring: Boolean = false
        private set

    private var previous: PlaybackSnapshot? = null
    private var nextId: Long = 1L

    /** Signature of the last logged event, used to avoid flooding the log. */
    private var lastSignature: String? = null

    fun start() {
        monitoring = true
        previous = null
        lastSignature = null
    }

    fun stop() {
        monitoring = false
        previous = null
        lastSignature = null
    }

    fun nextEventId(): Long = nextId++

    /**
     * @return the recorded event, or null when monitoring is off, there is no
     * snapshot, or the change is a duplicate of the last logged event.
     */
    fun ingest(kind: EventKind, snapshot: PlaybackSnapshot?): PlaybackEvent? {
        if (!monitoring || snapshot == null) return null

        val analysis = analyzer.analyze(snapshot, previous)
        previous = snapshot

        val signature = signatureOf(kind, snapshot, analysis)
        if (signature == lastSignature) return null
        lastSignature = signature

        val event = PlaybackEvent(
            id = nextEventId(),
            timestampMs = snapshot.timestampMs,
            kind = kind,
            classification = analysis.classification,
            confidence = analysis.confidence,
            title = snapshot.normalizedTitle(),
            artist = snapshot.normalizedArtist(),
            album = snapshot.album,
            phase = snapshot.phase.name,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            evidence = analysis.evidence,
        )
        log.add(event)
        return event
    }

    /** Records a synthetic event (for example a recovery attempt). */
    fun recordSynthetic(event: PlaybackEvent): PlaybackEvent {
        log.add(event)
        return event
    }

    private fun signatureOf(
        kind: EventKind,
        snapshot: PlaybackSnapshot,
        analysis: AnalysisResult,
    ): String = listOf(
        kind.name,
        analysis.classification.name,
        snapshot.normalizedTitle().orEmpty(),
        snapshot.normalizedArtist().orEmpty(),
        snapshot.phase.name,
        snapshot.durationMs?.toString().orEmpty(),
    ).joinToString("|")
}
