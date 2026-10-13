package com.spotifyquiet.app.monitoring

import com.spotifyquiet.app.analysis.Classification
import com.spotifyquiet.app.analysis.HeuristicPlaybackAnalyzer
import com.spotifyquiet.app.analysis.PlaybackPhase
import com.spotifyquiet.app.analysis.PlaybackSnapshot
import com.spotifyquiet.app.analysis.SnapshotSource
import com.spotifyquiet.app.data.EventKind
import com.spotifyquiet.app.data.EventLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMonitorEngineTest {

    private val log = EventLog(maxSize = 10)
    private val engine = PlaybackMonitorEngine(HeuristicPlaybackAnalyzer(), log)

    private fun snapshot(
        title: String? = "Song A",
        artist: String? = "Artist A",
        phase: PlaybackPhase = PlaybackPhase.PLAYING,
        timestampMs: Long = 1_000L,
    ) = PlaybackSnapshot(
        timestampMs = timestampMs,
        source = SnapshotSource.MEDIA_SESSION,
        title = title,
        artist = artist,
        phase = phase,
        packageName = "com.spotify.music",
    )

    @Test
    fun permissionDisabled_monitoringOff_producesNoEvents() {
        assertFalse(engine.monitoring)
        val event = engine.ingest(EventKind.MEDIA_METADATA, snapshot())
        assertNull("monitoring disabled must not record anything", event)
        assertTrue(log.isEmpty)
    }

    @Test
    fun nullSnapshot_producesNoEventEvenWhenMonitoring() {
        engine.start()
        assertNull(engine.ingest(EventKind.MEDIA_METADATA, null))
        assertTrue(log.isEmpty)
    }

    @Test
    fun monitoringStart_recordsFirstSnapshot() {
        engine.start()
        val event = engine.ingest(EventKind.MEDIA_METADATA, snapshot())
        assertNotNull(event)
        assertEquals(1, log.size)
        assertEquals(Classification.NORMAL_PLAYBACK, event?.classification)
        assertEquals("Song A", event?.title)
        assertEquals("Artist A", event?.artist)
    }

    @Test
    fun duplicateSnapshot_isDeduplicated() {
        engine.start()
        assertNotNull(engine.ingest(EventKind.MEDIA_METADATA, snapshot()))
        val duplicate = engine.ingest(
            EventKind.MEDIA_METADATA,
            snapshot(timestampMs = 2_000L),
        )
        assertNull("identical playback state must not flood the log", duplicate)
        assertEquals(1, log.size)
    }

    @Test
    fun changedSnapshot_isRecorded() {
        engine.start()
        engine.ingest(EventKind.MEDIA_METADATA, snapshot())
        val second = engine.ingest(
            EventKind.MEDIA_METADATA,
            snapshot(title = "Song B", timestampMs = 2_000L),
        )
        assertNotNull(second)
        assertEquals(2, log.size)
        assertEquals(Classification.TRACK_TRANSITION, second?.classification)
    }

    @Test
    fun stop_clearsStateAndIgnoresFurtherEvents() {
        engine.start()
        engine.ingest(EventKind.MEDIA_METADATA, snapshot())
        engine.stop()
        assertFalse(engine.monitoring)
        assertNull(engine.ingest(EventKind.MEDIA_METADATA, snapshot(title = "Song B")))
        assertEquals(1, log.size)
    }

    @Test
    fun restart_forgetsPreviousSnapshotAndDedup() {
        engine.start()
        engine.ingest(EventKind.MEDIA_METADATA, snapshot())
        engine.stop()
        engine.start()
        val event = engine.ingest(EventKind.MEDIA_METADATA, snapshot())
        assertNotNull("a fresh monitoring session must log again", event)
        assertEquals(2, log.size)
    }

    @Test
    fun syntheticEvents_areRecorded() {
        engine.start()
        val id = engine.nextEventId()
        val event = com.spotifyquiet.app.data.PlaybackEvent(
            id = id,
            timestampMs = 5_000L,
            kind = EventKind.RECOVERY,
            classification = Classification.UNKNOWN,
            confidence = com.spotifyquiet.app.analysis.Confidence.LOW,
            title = null,
            artist = null,
            album = null,
            phase = null,
            positionMs = null,
            durationMs = null,
            evidence = listOf("manual recovery"),
        )
        engine.recordSynthetic(event)
        assertEquals(1, log.size)
        assertEquals(EventKind.RECOVERY, log.last()?.kind)
    }
}
