package com.spotifyquiet.app.recovery

/** Result of asking the throttle for permission to attempt recovery. */
enum class ThrottleResult {
    GRANTED,
    COOLDOWN,
    LIMIT_REACHED,
}

/**
 * Pure cooldown + attempt-limit gate for the optional recovery experiment.
 *
 * Injected clock keeps the rules unit testable without sleeping.
 */
class RecoveryThrottle(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var attempts: Int = 0
        private set

    var lastAttemptAtMs: Long = 0L
        private set

    private var hasAttempted: Boolean = false

    fun tryAcquire(): ThrottleResult {
        if (attempts >= maxAttempts) return ThrottleResult.LIMIT_REACHED
        if (hasAttempted) {
            val elapsed = clock() - lastAttemptAtMs
            if (elapsed < cooldownMs) {
                return ThrottleResult.COOLDOWN
            }
        }
        attempts += 1
        lastAttemptAtMs = clock()
        hasAttempted = true
        return ThrottleResult.GRANTED
    }

    fun remainingCooldownMs(): Long {
        if (!hasAttempted) return 0L
        val elapsed = clock() - lastAttemptAtMs
        return (cooldownMs - elapsed).coerceAtLeast(0L)
    }

    fun remainingAttempts(): Int = (maxAttempts - attempts).coerceAtLeast(0)

    fun reset() {
        attempts = 0
        lastAttemptAtMs = 0L
        hasAttempted = false
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS: Long = 60_000L
        const val DEFAULT_MAX_ATTEMPTS: Int = 5
    }
}
