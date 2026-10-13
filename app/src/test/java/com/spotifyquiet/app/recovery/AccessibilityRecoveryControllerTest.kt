package com.spotifyquiet.app.recovery

import com.spotifyquiet.app.analysis.Classification
import com.spotifyquiet.app.analysis.Confidence
import com.spotifyquiet.app.data.EventKind
import com.spotifyquiet.app.data.PlaybackEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityRecoveryControllerTest {

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

    private val noPerformer: AutoClosePerformer? = null

    @Test
    fun evaluate_blocksNonAdEvents() {
        val controller = AccessibilityRecoveryController(
            RecoveryThrottle(cooldownMs = 0L, maxAttempts = 5),
            { noPerformer },
        )
        assertEquals(
            RecoveryOutcome.NOT_AN_AD,
            controller.evaluate(event(Classification.NORMAL_PLAYBACK, Confidence.HIGH)),
        )
        assertEquals(
            RecoveryOutcome.NOT_AN_AD,
            controller.evaluate(event(Classification.BUFFERING_OR_INTERRUPTION, Confidence.HIGH)),
        )
    }

    @Test
    fun evaluate_blocksUncertainDetections() {
        val controller = AccessibilityRecoveryController(
            RecoveryThrottle(cooldownMs = 0L, maxAttempts = 5),
            { noPerformer },
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
    fun evaluate_allowsHighConfidenceAdOncePerCooldown() {
        val controller = AccessibilityRecoveryController(
            RecoveryThrottle(cooldownMs = 60_000L, maxAttempts = 5),
            { noPerformer },
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
    fun evaluate_enforcesAttemptLimit() {
        val controller = AccessibilityRecoveryController(
            RecoveryThrottle(cooldownMs = 0L, maxAttempts = 2),
            { noPerformer },
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
    }

    @Test
    fun attempt_withoutService_reportsBlocked() {
        var captured: RecoveryOutcome? = null
        val controller = AccessibilityRecoveryController(
            RecoveryThrottle(cooldownMs = 0L, maxAttempts = 5),
            { noPerformer },
        )
        controller.attempt(event(Classification.POSSIBLE_AD, Confidence.HIGH)) { captured = it }
        assertEquals(RecoveryOutcome.BLOCKED_ACCESSIBILITY_DISABLED, captured)
    }

    @Test
    fun attempt_withService_delegatesToPerformer() {
        val performer = object : AutoClosePerformer {
            override fun perform(onDone: (RecoveryOutcome) -> Unit) {
                onDone(RecoveryOutcome.LAUNCHED)
            }
        }
        val controller = AccessibilityRecoveryController(
            RecoveryThrottle(cooldownMs = 0L, maxAttempts = 5),
            { performer },
        )
        var captured: RecoveryOutcome? = null
        controller.attempt(event(Classification.POSSIBLE_AD, Confidence.HIGH)) { captured = it }
        assertEquals(RecoveryOutcome.LAUNCHED, captured)
    }

    @Test
    fun reset_restoresBudget() {
        val controller = AccessibilityRecoveryController(
            RecoveryThrottle(cooldownMs = 0L, maxAttempts = 1),
            { noPerformer },
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
        assertEquals(
            RecoveryOutcome.READY,
            controller.evaluate(event(Classification.POSSIBLE_AD, Confidence.HIGH, id = 3L)),
        )
    }
}
