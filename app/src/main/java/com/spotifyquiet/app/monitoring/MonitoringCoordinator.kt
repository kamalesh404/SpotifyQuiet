package com.spotifyquiet.app.monitoring

import android.content.Context
import android.service.notification.StatusBarNotification
import android.annotation.SuppressLint
import com.spotifyquiet.app.analysis.Classification
import com.spotifyquiet.app.analysis.Confidence
import com.spotifyquiet.app.analysis.HeuristicPlaybackAnalyzer
import com.spotifyquiet.app.analysis.PlaybackSnapshot
import com.spotifyquiet.app.analysis.SnapshotSource
import com.spotifyquiet.app.analysis.mergeSnapshots
import com.spotifyquiet.app.data.DiagnosticStore
import com.spotifyquiet.app.data.EventKind
import com.spotifyquiet.app.data.EventLog
import com.spotifyquiet.app.data.ManualLabel
import com.spotifyquiet.app.data.PlaybackEvent
import com.spotifyquiet.app.permissions.AccessibilityAccess
import com.spotifyquiet.app.permissions.NotificationAccess
import com.spotifyquiet.app.recovery.AccessibilityRecoveryController
import com.spotifyquiet.app.recovery.DisabledRecoveryController
import com.spotifyquiet.app.recovery.ExperimentalRecoveryController
import com.spotifyquiet.app.recovery.RecoveryController
import com.spotifyquiet.app.recovery.RecoveryMode
import com.spotifyquiet.app.recovery.RecoveryOutcome
import com.spotifyquiet.app.recovery.RecoveryThrottle
import com.spotifyquiet.app.recovery.SpotifyLauncher
import com.spotifyquiet.app.service.SpotifyQuietAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single process-wide owner of monitoring state.
 *
 * There is intentionally no foreground service: the system-owned
 * [com.spotifyquiet.app.service.SpotifyQuietListenerService] is the only long
 * lived component, and it exists because the user granted notification access,
 * not to keep this process alive artificially.
 *
 * All entry points are synchronized; UI reads go through [state].
 */
@SuppressLint("StaticFieldLeak")
object MonitoringCoordinator {

    private val log = EventLog()
    private val diagnostics = DiagnosticStore()
    private val engine = PlaybackMonitorEngine(HeuristicPlaybackAnalyzer(), log)
    private val throttle = RecoveryThrottle()

    private var appContext: Context? = null
    private var bridge: MediaSessionBridge? = null
    private var sessionSnapshot: PlaybackSnapshot? = null
    private var notificationSnapshot: PlaybackSnapshot? = null
    private var sessionConnected = false
    private var notificationVisible = false
    private var notificationAccessGranted = false
    private var accessibilityServiceGranted = false
    private var autoRecoveryEnabled = false
    private var recoveryMode = RecoveryMode.LAUNCHER_ONLY
    private var recoveryStopped = false
    private var lastRecoveryOutcome: RecoveryOutcome? = null
    private var notice: String? = null
    private var listenerServiceConnected = false

    private val disabledRecovery = DisabledRecoveryController()
    private val recovery: RecoveryController = ExperimentalRecoveryController(
        throttle,
        recoveryContextGetter = { appContext },
    )
    private val accessibilityRecovery = AccessibilityRecoveryController(
        throttle = throttle,
        performerProvider = { SpotifyQuietAccessibilityService.instance },
    )

    private fun activeController(): RecoveryController = when {
        !autoRecoveryEnabled || recoveryStopped -> disabledRecovery
        recoveryMode == RecoveryMode.ACCESSIBILITY -> accessibilityRecovery
        else -> recovery
    }

    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    // ---------------------------------------------------------------- lifecycle

    @Synchronized
    fun attach(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        refreshPermissionState()
    }

    @Synchronized
    fun onListenerConnected() {
        listenerServiceConnected = true
        notificationAccessGranted = true
        notice = "Notification listener connected"
        if (engine.monitoring) {
            startBridge()
        }
        publish()
    }

    @Synchronized
    fun onListenerDisconnected() {
        listenerServiceConnected = false
        notice = "Notification listener disconnected by the system"
        stopBridge()
        publish()
    }

    @Synchronized
    fun refreshPermissionState() {
        val context = appContext ?: return
        val granted = NotificationAccess.isGranted(context)
        val wasGranted = notificationAccessGranted
        notificationAccessGranted = granted
        if (wasGranted && !granted && engine.monitoring) {
            stopMonitoringInternal(
                notice = "Notification access was revoked — monitoring stopped",
            )
        }
        refreshAccessibilityStatus()
        publish()
    }

    @Synchronized
    fun onAccessibilityServiceConnected() {
        accessibilityServiceGranted = true
        notice = "Accessibility service connected"
        publish()
    }

    @Synchronized
    fun onAccessibilityServiceDisconnected() {
        accessibilityServiceGranted = false
        if (autoRecoveryEnabled && recoveryMode == RecoveryMode.ACCESSIBILITY) {
            notice = "Accessibility service was disabled"
        }
        publish()
    }

    @Synchronized
    fun refreshAccessibilityStatus() {
        val context = appContext ?: return
        accessibilityServiceGranted = AccessibilityAccess.isGranted(context)
    }

    fun isRecoveryArmed(): Boolean = autoRecoveryEnabled && !recoveryStopped

    fun isAutoRecoveryEnabled(): Boolean = autoRecoveryEnabled

    // ---------------------------------------------------------------- monitoring

    @Synchronized
    fun startMonitoring() {
        val context = appContext ?: run {
            notice = "Coordinator not initialised yet"
            publish()
            return
        }
        if (!NotificationAccess.isGranted(context)) {
            notificationAccessGranted = false
            notice = "Grant notification access to SpotifyQuiet first"
            publish()
            return
        }
        if (engine.monitoring) return
        notificationAccessGranted = true
        sessionSnapshot = null
        notificationSnapshot = null
        sessionConnected = false
        notificationVisible = false
        engine.start()
        startBridge()
        notice = "Monitoring started"
        publish()
    }

    @Synchronized
    fun stopMonitoring() {
        stopMonitoringInternal(notice = "Monitoring stopped")
    }

    private fun stopMonitoringInternal(notice: String) {
        engine.stop()
        stopBridge()
        sessionSnapshot = null
        notificationSnapshot = null
        sessionConnected = false
        notificationVisible = false
        this.notice = notice
        publish()
    }

    private fun startBridge() {
        val context = appContext ?: return
        stopBridge()
        val component = NotificationAccess.listenerComponent(context)
        bridge = MediaSessionBridge(
            context = context,
            listenerComponent = component,
            onSessionListChanged = { connected ->
                onSessionListChangedFromBridge(connected)
            },
            onSnapshot = { kind, snapshot -> handleSessionSnapshot(kind, snapshot) },
            onError = { message ->
                onBridgeError(message)
            },
        ).also { it.start() }
        sessionConnected = bridge?.connectedPackage != null
    }

    @Synchronized
    internal fun onSessionListChangedFromBridge(connected: Boolean) {
        if (!connected) {
            sessionSnapshot = null
        }
        sessionConnected = connected
        publish()
    }

    @Synchronized
    internal fun onBridgeError(message: String) {
        notice = message
        publish()
    }

    private fun stopBridge() {
        bridge?.stop()
        bridge = null
    }

    // ---------------------------------------------------------------- inbound events

    @Synchronized
    fun handleSessionSnapshot(kind: EventKind, snapshot: PlaybackSnapshot) {
        sessionConnected = true
        sessionSnapshot = snapshot
        if (kind == EventKind.SESSION_REMOVED) {
            sessionSnapshot = null
        }
        ingest(kind)
    }

    @Synchronized
    fun onNotificationPosted(sbn: StatusBarNotification) {
        val parsed = NotificationParser.parse(sbn) ?: return
        notificationVisible = true
        val artist = parsed.text?.takeIf {
            !it.equals("Spotify", ignoreCase = true) &&
                !it.equals(parsed.title, ignoreCase = true)
        }
        notificationSnapshot = PlaybackSnapshot(
            timestampMs = System.currentTimeMillis(),
            source = SnapshotSource.NOTIFICATION,
            title = parsed.title,
            artist = artist,
            album = parsed.subText,
            phase = parsed.phaseHint,
            packageName = NotificationParser.SpotifyLauncherPackage.NAME,
        )
        ingest(EventKind.NOTIFICATION_POSTED)
    }

    @Synchronized
    fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != NotificationParser.SpotifyLauncherPackage.NAME) return
        notificationVisible = false
        notificationSnapshot = null
        ingest(EventKind.NOTIFICATION_REMOVED)
    }

    private fun ingest(kind: EventKind) {
        if (!engine.monitoring) return
        val merged = mergeSnapshots(sessionSnapshot, notificationSnapshot)
        val event = engine.ingest(kind, merged)
        if (event != null) {
            handlePossibleRecovery(event)
        }
        publish()
    }

    // ---------------------------------------------------------------- recovery

    private fun handlePossibleRecovery(event: PlaybackEvent) {
        if (!isRecoveryArmed()) return
        val controller = activeController()
        when (val decision = controller.evaluate(event)) {
            RecoveryOutcome.READY -> {
                controller.attempt(event) { outcome ->
                    onAttemptFinished(event, outcome, automatic = true)
                }
            }
            RecoveryOutcome.COOLDOWN,
            RecoveryOutcome.LIMIT_REACHED,
            -> {
                recordRecovery(
                    evidence = listOf(
                        "automatic recovery suppressed",
                        "outcome=$decision",
                        "cooldownRemainingMs=${throttle.remainingCooldownMs()}",
                    ),
                    classification = Classification.POSSIBLE_AD,
                    confidence = event.confidence,
                )
                lastRecoveryOutcome = decision
                publish()
            }
            else -> Unit
        }
    }

    private fun onAttemptFinished(
        event: PlaybackEvent,
        outcome: RecoveryOutcome,
        automatic: Boolean,
    ) {
        recordRecovery(
            evidence = listOf(
                if (automatic) {
                    "automatic recovery attempt after HIGH-confidence possible ad"
                } else {
                    "manual recovery requested by the user"
                },
                "outcome=$outcome",
                "Android may refuse background activity starts; verify on device",
            ),
            classification = event.classification,
            confidence = event.confidence,
        )
        lastRecoveryOutcome = outcome
        publish()
    }

    @Synchronized
    fun manualReopenSpotify(): RecoveryOutcome {
        val context = appContext ?: return RecoveryOutcome.BLOCKED_OR_REJECTED
        val probe = PlaybackEvent(
            id = engine.nextEventId(),
            timestampMs = System.currentTimeMillis(),
            kind = EventKind.RECOVERY,
            classification = Classification.UNKNOWN,
            confidence = Confidence.LOW,
            title = null,
            artist = null,
            album = null,
            phase = null,
            positionMs = null,
            durationMs = null,
            evidence = emptyList(),
        )
        val outcome = SpotifyLauncher.reopen(context)
        onAttemptFinished(probe, outcome, automatic = false)
        notice = when (outcome) {
            RecoveryOutcome.LAUNCHED -> "Reopening Spotify…"
            RecoveryOutcome.NO_SPOTIFY -> "Spotify is not installed"
            else -> "Android refused to start Spotify from the background"
        }
        publish()
        return outcome
    }

    @Synchronized
    fun setRecoveryMode(mode: RecoveryMode) {
        recoveryMode = mode
        if (mode == RecoveryMode.ACCESSIBILITY && !accessibilityServiceGranted) {
            val context = appContext
            notice = if (context != null && !AccessibilityAccess.isGranted(context)) {
                "Enable the accessibility service in Android settings first"
            } else {
                "Accessibility mode selected"
            }
        } else {
            notice = "Recovery mode: ${mode.name.lowercase().replace('_', ' ')}"
        }
        publish()
    }

    @Synchronized
    fun setAutoRecoveryEnabled(enabled: Boolean) {
        autoRecoveryEnabled = enabled
        if (enabled) {
            recoveryStopped = false
            throttle.reset()
            recovery.reset()
            accessibilityRecovery.reset()
            notice = "Automatic recovery armed (${recoveryMode.name.lowercase().replace('_', ' ')}): " +
                "${RecoveryThrottle.DEFAULT_COOLDOWN_MS / 1000}s cooldown, max " +
                "${RecoveryThrottle.DEFAULT_MAX_ATTEMPTS} attempts per session"
        } else {
            recoveryStopped = true
            notice = "Recovery disabled"
        }
        publish()
    }

    @Synchronized
    fun emergencyStopRecovery() {
        recoveryStopped = true
        throttle.reset()
        lastRecoveryOutcome = null
        notice = "Recovery stopped and counters reset"
        publish()
    }

    private fun recordRecovery(
        evidence: List<String>,
        classification: Classification,
        confidence: Confidence,
    ) {
        val event = PlaybackEvent(
            id = engine.nextEventId(),
            timestampMs = System.currentTimeMillis(),
            kind = EventKind.RECOVERY,
            classification = classification,
            confidence = confidence,
            title = null,
            artist = null,
            album = null,
            phase = null,
            positionMs = null,
            durationMs = null,
            evidence = evidence,
        )
        engine.recordSynthetic(event)
    }

    // ---------------------------------------------------------------- log actions

    @Synchronized
    fun clearLog() {
        log.clear()
        notice = "Event log cleared"
        publish()
    }

    @Synchronized
    fun clearDiagnostics() {
        diagnostics.clear()
        notice = "Diagnostic labels cleared"
        publish()
    }

    @Synchronized
    fun markEvent(eventId: Long, label: ManualLabel) {
        val event = log.snapshot().firstOrNull { it.id == eventId } ?: return
        diagnostics.mark(event, label)
        notice = when (label) {
            ManualLabel.AD -> "Event #$eventId labelled as a real advertisement"
            ManualLabel.NOT_AD -> "Event #$eventId labelled as not an advertisement"
        }
        publish()
    }

    @Synchronized
    fun dismissNotice() {
        notice = null
        publish()
    }

    // ---------------------------------------------------------------- state

    private fun publish() {
        val context = appContext
        _state.value = MonitorUiState(
            monitoring = engine.monitoring,
            notificationAccessGranted = notificationAccessGranted,
            accessibilityServiceGranted = accessibilityServiceGranted,
            spotifyInstalled = context == null || SpotifyLauncher.SPOTIFY_PACKAGE.let { pkg ->
                context.packageManager.getLaunchIntentForPackage(pkg) != null
            },
            sessionConnected = sessionConnected,
            notificationVisible = notificationVisible,
            current = mergeSnapshots(sessionSnapshot, notificationSnapshot),
            events = log.snapshot(),
            diagnosticLabels = diagnostics.all().associate { it.eventId to it.label },
            diagnosticCount = diagnostics.size,
            autoRecoveryEnabled = autoRecoveryEnabled,
            recoveryMode = recoveryMode,
            recoveryAttempts = throttle.attempts,
            recoveryAttemptsRemaining = throttle.remainingAttempts(),
            lastRecoveryOutcome = lastRecoveryOutcome,
            notice = notice,
        )
    }

    @Synchronized
    fun isListenerServiceConnected(): Boolean = listenerServiceConnected

    /** Test hook: resets all process state. Not used by the app. */
    @Synchronized
    internal fun resetForTests() {
        stopBridge()
        engine.stop()
        log.clear()
        diagnostics.clear()
        throttle.reset()
        sessionSnapshot = null
        notificationSnapshot = null
        autoRecoveryEnabled = false
        recoveryStopped = false
        lastRecoveryOutcome = null
        notice = null
        publish()
    }
}
