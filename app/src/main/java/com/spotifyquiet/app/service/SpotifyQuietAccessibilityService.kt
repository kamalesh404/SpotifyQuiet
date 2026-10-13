package com.spotifyquiet.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import com.spotifyquiet.app.monitoring.MonitoringCoordinator
import com.spotifyquiet.app.recovery.AutoClosePerformer
import com.spotifyquiet.app.recovery.RecoveryOutcome
import com.spotifyquiet.app.recovery.SpotifyLauncher

/**
 * Accessibility service that reproduces the manual "swipe Spotify away from
 * Recent Apps and reopen it" gesture.
 *
 * Safety and privacy:
 * - Enabled only when the user explicitly toggles it in Android's accessibility
 *   settings. SpotifyQuiet never enables it silently.
 * - [canRetrieveWindowContent] is false: the service never reads or records
 *   the content of other apps' windows.
 * - Window-state-change events are ignored; the service has no use for them.
 * - It acts only when (a) automatic recovery is armed by the user and (b) a
 *   high-confidence possible-ad event passed the cooldown and attempt gates.
 * - It checks again, immediately before swiping, that recovery is still armed,
 *   so an emergency stop is honoured even mid-sequence.
 * - It never loops: at most one swipe + one reopen per attempt.
 *
 * Performance: the service is event-driven and performs a short one-shot
 * sequence only when triggered. There is no polling, no continuous listening
 * and no screen content retrieval.
 */
class SpotifyQuietAccessibilityService : AccessibilityService(), AutoClosePerformer {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        MonitoringCoordinator.onAccessibilityServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally unused: window state events carry no data we need.
    }

    override fun onInterrupt() {
        // No long-running work to interrupt.
    }

    override fun onDestroy() {
        instance = null
        MonitoringCoordinator.onAccessibilityServiceDisconnected()
        super.onDestroy()
    }

    override fun perform(onDone: (RecoveryOutcome) -> Unit) {
        if (isKeyguardLocked(this)) {
            onDone(RecoveryOutcome.BLOCKED_LOCKED)
            return
        }
        if (!isScreenOn(this)) {
            onDone(RecoveryOutcome.BLOCKED_SCREEN_OFF)
            return
        }
        val opened = try {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        } catch (e: RuntimeException) {
            false
        }
        if (!opened) {
            onDone(RecoveryOutcome.BLOCKED_RECENTS_FAILED)
            return
        }
        // Give Recents time to animate in before swiping the topmost card.
        mainHandler.postDelayed({ swipeThenReopen(onDone) }, 1200L)
    }

    private fun swipeThenReopen(onDone: (RecoveryOutcome) -> Unit) {
        if (!MonitoringCoordinator.isRecoveryArmed()) {
            onDone(RecoveryOutcome.BLOCKED_OR_REJECTED)
            return
        }
        val swiped = dispatchSwipeUp()
        // Give the dismissal animation time to finish before relaunching.
        mainHandler.postDelayed({
            val reopened = SpotifyLauncher.reopen(this)
            onDone(if (swiped) reopened else RecoveryOutcome.PARTIAL_CLOSE_FAILED)
        }, 900L)
    }

    /**
     * Blind upward fling over the centre of the screen, intended to dismiss the
     * topmost Recent Apps card. We cannot identify the card without reading
     * window content (which we deliberately do not do), so the assumption that
     * the most recent app is Spotify is recorded as evidence in the log.
     */
    private fun dispatchSwipeUp(): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val path = Path().apply {
            moveTo(width * 0.5f, height * 0.45f)
            lineTo(width * 0.5f, height * 0.08f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 200L))
            .build()
        return try {
            dispatchGesture(gesture, null, null)
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun isKeyguardLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguard?.isDeviceLocked == true
    }

    private fun isScreenOn(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return power?.isInteractive == true
    }

    companion object {
        var instance: SpotifyQuietAccessibilityService? = null
    }
}
