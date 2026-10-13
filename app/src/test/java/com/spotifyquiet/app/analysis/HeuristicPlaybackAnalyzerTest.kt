package com.spotifyquiet.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicPlaybackAnalyzerTest {

    private val analyzer = HeuristicPlaybackAnalyzer()

    private fun snapshot(
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        phase: PlaybackPhase = PlaybackPhase.PLAYING,
        durationMs: Long? = null,
        positionMs: Long? = null,
        timestampMs: Long = 1_000L,
    ) = PlaybackSnapshot(
        timestampMs = timestampMs,
        source = SnapshotSource.MEDIA_SESSION,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        positionMs = positionMs,
        phase = phase,
        packageName = "com.spotify.music",
    )

    @Test
    fun nullSnapshot_isUnknownWithEvidence() {
        val result = analyzer.analyze(null, null)
        assertEquals(Classification.UNKNOWN, result.classification)
        assertEquals(Confidence.LOW, result.confidence)
        assertTrue(result.evidence.isNotEmpty())
    }

    @Test
    fun emptyMetadata_isUnknownNotAd() {
        val result = analyzer.analyze(snapshot(title = null, artist = null), null)
        assertEquals(Classification.UNKNOWN, result.classification)
        assertFalse(
            "missing metadata must never be classified as an ad",
            result.classification == Classification.POSSIBLE_AD,
        )
        assertTrue(result.evidence.isNotEmpty())
    }

    @Test
    fun blankMetadata_isTreatedAsMissing() {
        val result = analyzer.analyze(snapshot(title = "   ", artist = ""), null)
        assertEquals(Classification.UNKNOWN, result.classification)
    }

    @Test
    fun completeMetadataWhilePlaying_isNormal() {
        val result = analyzer.analyze(
            snapshot(title = "Song A", artist = "Artist A", durationMs = 200_000),
            null,
        )
        assertEquals(Classification.NORMAL_PLAYBACK, result.classification)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun normalTrackChange_isTrackTransition() {
        val previous = snapshot(title = "Song A", artist = "Artist A")
        val current = snapshot(title = "Song B", artist = "Artist B", timestampMs = 2_000L)
        val result = analyzer.analyze(current, previous)
        assertEquals(Classification.TRACK_TRANSITION, result.classification)
        assertTrue(result.evidence.any { it.contains("Song A") && it.contains("Song B") })
    }

    @Test
    fun pause_isNotAnAd() {
        val previous = snapshot(title = "Song A", artist = "Artist A")
        val current = snapshot(
            title = "Song A",
            artist = "Artist A",
            phase = PlaybackPhase.PAUSED,
            timestampMs = 2_000L,
        )
        val result = analyzer.analyze(current, previous)
        assertEquals(Classification.NORMAL_PLAYBACK, result.classification)
        assertTrue(result.evidence.isNotEmpty())
    }

    @Test
    fun resumeAfterPause_isNormal() {
        val previous = snapshot(
            title = "Song A",
            artist = "Artist A",
            phase = PlaybackPhase.PAUSED,
        )
        val current = snapshot(
            title = "Song A",
            artist = "Artist A",
            phase = PlaybackPhase.PLAYING,
            timestampMs = 3_000L,
        )
        val result = analyzer.analyze(current, previous)
        assertEquals(Classification.NORMAL_PLAYBACK, result.classification)
    }

    @Test
    fun buffering_isInterruptionNotAd() {
        val result = analyzer.analyze(
            snapshot(title = "Song A", artist = "Artist A", phase = PlaybackPhase.BUFFERING),
            snapshot(title = "Song A", artist = "Artist A"),
        )
        assertEquals(Classification.BUFFERING_OR_INTERRUPTION, result.classification)
    }

    @Test
    fun stopped_isInterruptionNotAd() {
        val result = analyzer.analyze(
            snapshot(title = "Song A", artist = "Artist A", phase = PlaybackPhase.STOPPED),
            null,
        )
        assertEquals(Classification.BUFFERING_OR_INTERRUPTION, result.classification)
    }

    @Test
    fun errorPhase_isInterruptionNotAd() {
        val result = analyzer.analyze(
            snapshot(phase = PlaybackPhase.ERROR),
            null,
        )
        assertEquals(Classification.BUFFERING_OR_INTERRUPTION, result.classification)
    }

    @Test
    fun advertisementMarker_isPossibleAdWithHighConfidence() {
        val result = analyzer.analyze(
            snapshot(title = "Advertisement", artist = null, durationMs = 30_000),
            snapshot(title = "Song A", artist = "Artist A"),
        )
        assertEquals(Classification.POSSIBLE_AD, result.classification)
        assertEquals(Confidence.HIGH, result.confidence)
        assertTrue(result.evidence.any { it.contains("heuristic text match") })
    }

    @Test
    fun advertisementMarkerInArtist_isPossibleAd() {
        val result = analyzer.analyze(
            snapshot(title = "Summer Sale", artist = "Advertisement"),
            null,
        )
        assertEquals(Classification.POSSIBLE_AD, result.classification)
    }

    @Test
    fun metadataClearedWhilePlaying_afterNormalTrack_isPossibleAdButNotCertain() {
        val previous = snapshot(title = "Song A", artist = "Artist A")
        val current = snapshot(title = null, artist = null, timestampMs = 2_000L)
        val result = analyzer.analyze(current, previous)
        assertEquals(Classification.POSSIBLE_AD, result.classification)
        assertEquals(Confidence.MEDIUM, result.confidence)
        assertTrue(result.evidence.any { it.contains("manually") })
    }

    @Test
    fun missingArtistWhilePlaying_isUnknownNotAd() {
        val result = analyzer.analyze(
            snapshot(title = "Song A", artist = null),
            null,
        )
        assertEquals(Classification.UNKNOWN, result.classification)
    }

    @Test
    fun unknownPhase_isUnknown() {
        val result = analyzer.analyze(
            snapshot(title = "Song A", artist = "Artist A", phase = PlaybackPhase.UNKNOWN),
            null,
        )
        assertEquals(Classification.UNKNOWN, result.classification)
        assertEquals(Confidence.LOW, result.confidence)
    }

    @Test
    fun everyResultRecordsEvidence() {
        val inputs = listOf(
            snapshot(),
            snapshot(title = "Song A", artist = "Artist A"),
            snapshot(title = "Advertisement"),
            snapshot(phase = PlaybackPhase.BUFFERING),
            snapshot(phase = PlaybackPhase.PAUSED),
        )
        for (input in inputs) {
            val result = analyzer.analyze(input, null)
            assertNotNull(result)
            assertTrue(result.evidence.isNotEmpty())
        }
    }
}
