package com.spotifyquiet.app.recovery

import com.spotifyquiet.app.analysis.Classification
import com.spotifyquiet.app.analysis.Confidence
import com.spotifyquiet.app.data.EventKind
import com.spotifyquiet.app.data.PlaybackEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryControllerTest {

    private fun event(
        classification: Classification,
        confidence: Confidence,
        id: Long = 1L,
    ) = PlaybackEvent(
        id = id,
        timestampMs = 1_000L,
        kind = EventKind.MEDIA_METADATA,
        classification = classification,
        confidence = confidence,
        title = "Advertisement",
        artist = null,
        album = null,
        phase = "PLAYING",
        positionMs = null,
        durationMs = 30_000L,
        evidence = listOf(
            "metadata title contains the advertisement marker",
            "heuristic text match only; Spotify exposes no authoritative ad flag",
        ),
    )

    @Test
    fun disabledControllerNeverActsWithoutConsent() {
        val controller = DisabledRecoveryController()
        val result = controller.evaluate(
            event(Classification.POSSIBLE_AD, Confidence.HIGH),
        )
        assertEquals(RecoveryOutcome.DISABLED, result)
        assertTrue("recovery must be off by default", !controller.isAutomatic)
    }

    @Test
    fun normalPlaybackNeverTriggersRecovery() {
        val controller = ExperimentalRecoveryController(
            RecoveryThrottle(cooldownMs = 0L, maxAttempts = 5),
        )
        assertEquals(
            RecoveryOutcome.NOT_AN_AD,
            controller.evaluate(event(Classification.NORMAL_PLAYBACK, Confidence.HIGH)),
        )
        assertEquals(
            RecoveryOutcome.NOT_AN_AD,
            controller.evaluate(event(Classification.TRACK_TRANSITION, Confidence.HIGH)),
        )
        assertEquals(
            RecoveryOutcome.NOT_AN_AD,
            controller.evaluate(
                event(Classification.BUFFERING_OR_INTERRUPTION, Confidence.HIGH),
            ),
        )
    }

    @Test
    fun uncertainDetectionNeverInterruptsPlayback() {
        val controller = ExperimentalRecoveryController(
            RecoveryThrottle(cooldownMs = 0L, maxAttempts = 5),
        )
        assertEquals(
            RecoveryOutcome.LOW_CONFIDENCE,
            controller.evaluate(event(Classification.POSSIBLE_AD, Confidence.MEDIUM)),
        )
        assertEquals(
            RecoveryOutcome.LOW_CONFIDENCE,
            controller.evaluate(event(Classification.POSSIBLE_AD, Confidence.LOW)),
        )
    }

    @Test
    fun highConfidenceAdIsReadyOnlyOncePerCooldown() {
        val controller = ExperimentalRecoveryController(
            RecoveryThrottle(cooldownMs = 60_000L, maxAttempts = 5),
        )
        assertEquals(
            RecoveryOutcome.READY,
            controller.evaluate(event(Classification.POSSIBLE_AD, Confidence.HIGH)),
        )
        assertEquals(
            RecoveryOutcome.COOLDOWN,
            controller.evaluate(event(Classification.POSSIBLE_AD, Confidence.HIGH, id = 2L)),
        )
    }

    @Test
    fun attemptLimitStopsTheExperiment() {
        val controller = ExperimentalRecoveryController(
            RecoveryThrottle(cooldownMs = 0L, maxAttempts = 2),
        )
        assertEquals(
            RecoveryOutcome.READY,
            controller.evaluate(event(Classification.POSSIBLE_AD, Confidence.HIGH, id = 1L)),
        )
        assertEquals(
            RecoveryOutcome.READY,
            controller.evaluate(event(Classification.POSSIBLE_AD, Confidence.HIGH, id = 2L)),
        )
        assertEquals(
            RecoveryOutcome.LIMIT_REACHED,
            controller.evaluate(event(Classification.POSSIBLE_AD, Confidence.HIGH, id = 3L)),
        )
        assertEquals(0, controller.remainingAttempts)
    }

    @Test
    fun emergencyStopResetsTheExperiment() {
        val controller = ExperimentalRecoveryController(
            RecoveryThrottle(cooldownMs = 0L, maxAttempts = 1),
        )
        assertEquals(
            RecoveryOutcome.READY,
            controller.evaluate(event(Classification.POSSIBLE_AD, Confidence.HIGH)),
        )
        assertEquals(
            RecoveryOutcome.LIMIT_REACHED,
            controller.evaluate(event(Classification.POSSIBLE_AD, Confidence.HIGH, id = 2L)),
        )
        controller.reset()
        assertEquals(0, controller.attempts)
        assertEquals(
            RecoveryOutcome.READY,
            controller.evaluate(event(Classification.POSSIBLE_AD, Confidence.HIGH, id = 3L)),
        )
    }

    @Test
    fun spotifyPackageIsTheRealOne() {
        assertEquals("com.spotify.music", SpotifyLauncher.SPOTIFY_PACKAGE)
    }
}
