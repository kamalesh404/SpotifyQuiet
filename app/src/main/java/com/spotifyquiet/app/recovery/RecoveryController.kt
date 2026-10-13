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
    PARTIAL_CLOSE_FAILED,
    BLOCKED_ACCESSIBILITY_DISABLED,
    BLOCKED_LOCKED,
    BLOCKED_SCREEN_OFF,
    BLOCKED_RECENTS_FAILED,
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
 * manual "swipe away and reopen" gesture with certainty, and none of them may
 * restart Spotify in a loop. Two modes are offered:
 * - [RecoveryMode.LAUNCHER_ONLY]: reopens Spotify via a normal launcher intent.
 * - [RecoveryMode.ACCESSIBILITY]: also attempts to swipe Spotify out of
 *   Recent Apps using an accessibility gesture. Requires the user to enable
 *   the accessibility service in system settings; the service never reads
 *   screen content.
 */
interface RecoveryController {
    val id: String

    /** True when this controller can act without an explicit user tap. */
    val isAutomatic: Boolean

    /** Pure decision step: may this event trigger a recovery attempt now? */
    fun evaluate(event: PlaybackEvent): RecoveryOutcome

    /**
     * Side-effecting step. Only call after [evaluate] returned READY, or for a
     * manual user action.
     *
     * Implementations must call [onDone] exactly once with the final outcome.
     * Accessibility-based recovery runs on a short delayed sequence, so
     * [onDone] may fire after this method returns.
     */
    fun attempt(event: PlaybackEvent, onDone: (RecoveryOutcome) -> Unit)

    /** Emergency stop: clears state and prevents further automatic attempts. */
    fun reset()
}

/** Default: recovery entirely off. Monitoring is unaffected. */
class DisabledRecoveryController : RecoveryController {
    override val id: String = "disabled"
    override val isAutomatic: Boolean = false

    override fun evaluate(event: PlaybackEvent): RecoveryOutcome = RecoveryOutcome.DISABLED

    override fun attempt(event: PlaybackEvent, onDone: (RecoveryOutcome) -> Unit) {
        onDone(RecoveryOutcome.DISABLED)
    }

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
 * Launcher-only experiment: reopens Spotify via a normal launcher intent.
 *
 * Disabled by default. Only fires on HIGH-confidence POSSIBLE_AD with cooldown
 * and per-session attempt limits. Android may refuse background activity starts.
 */
class ExperimentalRecoveryController(
    private val throttle: RecoveryThrottle = RecoveryThrottle(),
    private val recoveryContextGetter: () -> Context? = { null },
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
            return RecoveryOutcome.LOW_CONFIDENCE
        }
        return when (throttle.tryAcquire()) {
            ThrottleResult.GRANTED -> RecoveryOutcome.READY
            ThrottleResult.COOLDOWN -> RecoveryOutcome.COOLDOWN
            ThrottleResult.LIMIT_REACHED -> RecoveryOutcome.LIMIT_REACHED
        }
    }

    override fun attempt(
        event: PlaybackEvent,
        onDone: (RecoveryOutcome) -> Unit,
    ) {
        val context = recoveryContextGetter() ?: run {
            onDone(RecoveryOutcome.BLOCKED_OR_REJECTED)
            return
        }
        onDone(SpotifyLauncher.reopen(context))
    }

    override fun reset() = throttle.reset()
}

/** Implemented by the accessibility service when it is enabled and running. */
interface AutoClosePerformer {
    /**
     * Perform the swipe-away and reopen sequence. Must call [onDone] exactly
     * once with the final outcome, on the main thread.
     */
    fun perform(onDone: (RecoveryOutcome) -> Unit)
}

/**
 * Accessibility-based recovery: swipe Spotify out of Recent Apps, then reopen
 * it. The only mode that attempts to reproduce the user's manual gesture.
 *
 * Requires the user to enable the accessibility service in system settings.
 * The service never reads screen content and acts only on explicit command.
 * The swipe targets the topmost Recent Apps card, which is assumed to be
 * Spotify (the most recently used app); this assumption is recorded in the
 * log as evidence.
 */
class AccessibilityRecoveryController(
    private val throttle: RecoveryThrottle,
    private val performerProvider: () -> AutoClosePerformer?,
) : RecoveryController {

    override val id: String = "accessibility-close-reopen"
    override val isAutomatic: Boolean = true

    override fun evaluate(event: PlaybackEvent): RecoveryOutcome {
        if (event.classification != Classification.POSSIBLE_AD) {
            return RecoveryOutcome.NOT_AN_AD
        }
        if (event.confidence != Confidence.HIGH) {
            return RecoveryOutcome.LOW_CONFIDENCE
        }
        return when (throttle.tryAcquire()) {
            ThrottleResult.GRANTED -> RecoveryOutcome.READY
            ThrottleResult.COOLDOWN -> RecoveryOutcome.COOLDOWN
            ThrottleResult.LIMIT_REACHED -> RecoveryOutcome.LIMIT_REACHED
        }
    }

    override fun attempt(
        event: PlaybackEvent,
        onDone: (RecoveryOutcome) -> Unit,
    ) {
        val performer = performerProvider()
        if (performer == null) {
            onDone(RecoveryOutcome.BLOCKED_ACCESSIBILITY_DISABLED)
            return
        }
        performer.perform(onDone)
    }

    override fun reset() = throttle.reset()
}
