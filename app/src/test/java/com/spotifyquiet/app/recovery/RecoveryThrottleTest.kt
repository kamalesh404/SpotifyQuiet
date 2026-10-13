package com.spotifyquiet.app.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryThrottleTest {

    private var now = 0L

    private fun throttle(
        cooldownMs: Long = 1_000L,
        maxAttempts: Int = 3,
    ) = RecoveryThrottle(cooldownMs = cooldownMs, maxAttempts = maxAttempts, clock = { now })

    @Test
    fun firstAttemptIsGranted() {
        val throttle = throttle()
        assertEquals(ThrottleResult.GRANTED, throttle.tryAcquire())
        assertEquals(1, throttle.attempts)
    }

    @Test
    fun attemptDuringCooldownIsRejected() {
        val throttle = throttle()
        assertEquals(ThrottleResult.GRANTED, throttle.tryAcquire())
        assertEquals(ThrottleResult.COOLDOWN, throttle.tryAcquire())
        now = 999L
        assertEquals(ThrottleResult.COOLDOWN, throttle.tryAcquire())
        assertEquals("a rejected attempt must not consume budget", 1, throttle.attempts)
    }

    @Test
    fun attemptAfterCooldownIsGranted() {
        val throttle = throttle()
        throttle.tryAcquire()
        now = 1_000L
        assertEquals(ThrottleResult.GRANTED, throttle.tryAcquire())
        assertEquals(2, throttle.attempts)
    }

    @Test
    fun remainingCooldownCountsDown() {
        val throttle = throttle()
        throttle.tryAcquire()
        now = 400L
        assertEquals(600L, throttle.remainingCooldownMs())
        now = 5_000L
        assertEquals(0L, throttle.remainingCooldownMs())
    }

    @Test
    fun maximumAttemptsIsEnforced() {
        val throttle = throttle(maxAttempts = 2)
        assertEquals(ThrottleResult.GRANTED, throttle.tryAcquire())
        now = 5_000L
        assertEquals(ThrottleResult.GRANTED, throttle.tryAcquire())
        now = 10_000L
        assertEquals(ThrottleResult.LIMIT_REACHED, throttle.tryAcquire())
        now = 100_000L
        assertEquals(
            "the limit must survive any amount of time passing",
            ThrottleResult.LIMIT_REACHED,
            throttle.tryAcquire(),
        )
        assertEquals(0, throttle.remainingAttempts())
    }

    @Test
    fun resetRestoresTheBudget() {
        val throttle = throttle(maxAttempts = 1)
        throttle.tryAcquire()
        assertEquals(ThrottleResult.LIMIT_REACHED, throttle.tryAcquire())
        throttle.reset()
        assertEquals(0, throttle.attempts)
        assertEquals(ThrottleResult.GRANTED, throttle.tryAcquire())
    }

    @Test
    fun cooldownDefaultsAreSane() {
        assertTrue(RecoveryThrottle.DEFAULT_COOLDOWN_MS >= 30_000L)
        assertTrue(RecoveryThrottle.DEFAULT_MAX_ATTEMPTS in 1..10)
    }
}
