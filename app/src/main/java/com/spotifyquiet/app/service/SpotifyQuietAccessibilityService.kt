package com.spotifyquiet.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
    private var recoveryWakeLock: PowerManager.WakeLock? = null

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

    /**
     * Briefly wakes the display so the swipe can land even when the screen is
     * off. The lock is held for at most 10 seconds and released as soon as the
     * recovery sequence finishes.
     */
    private fun wakeScreenForRecovery() {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        recoveryWakeLock = power.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "SpotifyQuiet::RecoveryWake",
        ).also { it.acquire(10_000L) }
    }

    private fun releaseWakeLock() {
        recoveryWakeLock?.let { if (it.isHeld) it.release() }
        recoveryWakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        instance = null
        MonitoringCoordinator.onAccessibilityServiceDisconnected()
        super.onDestroy()
    }

    override fun perform(onDone: (RecoveryOutcome) -> Unit) {
        wakeScreenForRecovery()
        val opened = try {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        } catch (e: RuntimeException) {
            false
        }
        if (!opened) {
            onDone(RecoveryOutcome.BLOCKED_RECENTS_FAILED)
            return
        }
        // Give Recents time to fully load before swiping the topmost card.
        mainHandler.postDelayed({ swipeThenReopen(onDone) }, 1000L)
    }

    private fun swipeThenReopen(onDone: (RecoveryOutcome) -> Unit) {
        if (!MonitoringCoordinator.isRecoveryArmed()) {
            releaseWakeLock()
            onDone(RecoveryOutcome.BLOCKED_OR_REJECTED)
            return
        }
        val swiped = dispatchSwipeUp()
        if (swiped) {
            mainHandler.postDelayed({
                val reopened = SpotifyLauncher.reopen(this)
                releaseWakeLock()
                onDone(reopened)
            }, 700L)
        } else {
            // Retry once if the first swipe didn't dismiss the card.
            mainHandler.postDelayed({
                val retry = dispatchSwipeUp()
                mainHandler.postDelayed({
                    val reopened = SpotifyLauncher.reopen(this)
                    releaseWakeLock()
                    onDone(if (retry) reopened else RecoveryOutcome.PARTIAL_CLOSE_FAILED)
                }, 500L)
            }, 400L)
        }
    }

    /**
     * Fast upward fling from the lower-middle of the screen, intended to dismiss
     * the topmost Recent Apps card. We cannot identify the card without reading
     * window content (which we deliberately do not do), so the assumption that
     * the most recent app is Spotify is recorded as evidence in the log.
     */
    private fun dispatchSwipeUp(): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val path = Path().apply {
            moveTo(width * 0.5f, height * 0.65f)
            lineTo(width * 0.5f, height * 0.05f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 120L))
            .build()
        return try {
            dispatchGesture(gesture, null, null)
        } catch (e: RuntimeException) {
            false
        }
    }

    companion object {
        var instance: SpotifyQuietAccessibilityService? = null
    }
}
