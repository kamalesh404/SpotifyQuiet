package com.spotifyquiet.app.recovery

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.spotifyquiet.app.analysis.Classification
import com.spotifyquiet.app.analysis.Confidence
import com.spotifyquiet.app.data.PlaybackEvent

/** Why a recovery attempt did or did not happen. */
enum class RecoveryOutcome {
    READY,
    DISABLED,
    NOT_AN_AD,
    LOW_CONFIDENCE,
    COOLDOWN,
    LIMIT_REACHED,
    LAUNCHED,
    BLOCKED_OR_REJECTED,
    NO_SPOTIFY,
}

/**
 * Optional, independently switchable recovery experiment.
 *
 * Detection and recovery are deliberately separate: disabling recovery never
 * stops monitoring, and monitoring never implies recovery ran.
 *
 * Android does NOT give a normal app a supported way to swipe another app out
 * of Recent Apps, and background activity starts are restricted since
 * Android 10. Therefore no implementation here may claim to reproduce the
 * manual "swipe away and reopen" gesture, and none of them may restart
 * Spotify in a loop.
 */
interface RecoveryController {
    val id: String

    /** True when this controller can act without an explicit user tap. */
    val isAutomatic: Boolean

    /** Pure decision step: may this event trigger a recovery attempt now? */
    fun evaluate(event: PlaybackEvent): RecoveryOutcome

    /** Side-effecting step. Only call after [evaluate] returned READY, or for a manual user action. */
    fun attempt(context: Context, reason: String): RecoveryOutcome

    /** Emergency stop: clears state and prevents further automatic attempts. */
    fun reset()
}

/** Default: recovery entirely off. Monitoring is unaffected. */
class DisabledRecoveryController : RecoveryController {
    override val id: String = "disabled"
    override val isAutomatic: Boolean = false

    override fun evaluate(event: PlaybackEvent): RecoveryOutcome = RecoveryOutcome.DISABLED

    override fun attempt(context: Context, reason: String): RecoveryOutcome =
        RecoveryOutcome.DISABLED

    override fun reset() = Unit
}

/**
 * Shared, supported way of bringing Spotify back to the foreground: a normal
 * launcher intent, issued only for a user-initiated action or for the
 * explicitly opted-in experiment.
 */
object SpotifyLauncher {
    const val SPOTIFY_PACKAGE = "com.spotify.music"

    fun reopen(context: Context): RecoveryOutcome {
        val pm = context.packageManager
        val launchIntent: Intent =
            pm.getLaunchIntentForPackage(SPOTIFY_PACKAGE)
                ?: return RecoveryOutcome.NO_SPOTIFY
        return try {
            if (launchIntent.resolveActivity(pm) == null) {
                return RecoveryOutcome.NO_SPOTIFY
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            RecoveryOutcome.LAUNCHED
        } catch (e: ActivityNotFoundException) {
            RecoveryOutcome.NO_SPOTIFY
        } catch (e: SecurityException) {
            RecoveryOutcome.BLOCKED_OR_REJECTED
        } catch (e: IllegalStateException) {
            // Android 10+ may reject background activity starts.
            RecoveryOutcome.BLOCKED_OR_REJECTED
        }
    }
}

/**
 * Opt-in experiment (disabled by default).
 *
 * Attempts to reopen Spotify only when the analyzer reports a HIGH-confidence
 * [Classification.POSSIBLE_AD], and only when the cooldown and per-session
 * attempt limit allow it. Android may silently refuse the launch when
 * SpotifyQuiet is in the background; that is recorded as
 * [RecoveryOutcome.BLOCKED_OR_REJECTED] and never retried in a loop.
 */
class ExperimentalRecoveryController(
    private val throttle: RecoveryThrottle = RecoveryThrottle(),
) : RecoveryController {

    override val id: String = "experimental-relaunch"
    override val isAutomatic: Boolean = true

    val remainingAttempts: Int get() = throttle.remainingAttempts()
    val remainingCooldownMs: Long get() = throttle.remainingCooldownMs()
    val attempts: Int get() = throttle.attempts

    override fun evaluate(event: PlaybackEvent): RecoveryOutcome {
        if (event.classification != Classification.POSSIBLE_AD) {
            return RecoveryOutcome.NOT_AN_AD
        }
        if (event.confidence != Confidence.HIGH) {
            // Uncertain detections must never interrupt playback.
            return RecoveryOutcome.LOW_CONFIDENCE
        }
        return when (throttle.tryAcquire()) {
            ThrottleResult.GRANTED -> RecoveryOutcome.READY
            ThrottleResult.COOLDOWN -> RecoveryOutcome.COOLDOWN
            ThrottleResult.LIMIT_REACHED -> RecoveryOutcome.LIMIT_REACHED
        }
    }

    override fun attempt(context: Context, reason: String): RecoveryOutcome =
        SpotifyLauncher.reopen(context)

    override fun reset() = throttle.reset()
}
