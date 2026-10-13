package com.spotifyquiet.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotifyquiet.app.R
import com.spotifyquiet.app.analysis.Classification
import com.spotifyquiet.app.data.EventKind
import com.spotifyquiet.app.data.ManualLabel
import com.spotifyquiet.app.data.PlaybackEvent
import com.spotifyquiet.app.monitoring.MonitorUiState
import com.spotifyquiet.app.monitoring.MonitoringCoordinator
import com.spotifyquiet.app.permissions.AccessibilityAccess
import com.spotifyquiet.app.permissions.NotificationAccess
import com.spotifyquiet.app.recovery.RecoveryMode
import com.spotifyquiet.app.recovery.RecoveryOutcome
import com.spotifyquiet.app.ui.theme.InfoColor
import com.spotifyquiet.app.ui.theme.WarningColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val state by MonitoringCoordinator.state.collectAsState()
    val expandedEvidence = remember { mutableStateMapOf<Long, Boolean>() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") { StatusSection(state) }
            item(key = "controls") { ControlsSection(state) }

            val notice = state.notice
            if (notice != null) {
                item(key = "notice") { NoticeCard(notice) }
            }

            item(key = "recovery") { RecoverySection(state) }
            item(key = "privacy") { PrivacySection() }
            item(key = "log_header") { LogHeaderSection(state) }

            if (state.events.isEmpty()) {
                item(key = "empty_log") { EmptyLogSection() }
            } else {
                items(state.events.asReversed(), key = { it.id }) { event ->
                    EventRow(
                        event = event,
                        label = state.diagnosticLabels[event.id],
                        expanded = expandedEvidence[event.id] == true,
                        onToggleEvidence = {
                            expandedEvidence[event.id] = expandedEvidence[event.id] != true
                        },
                        onMark = { MonitoringCoordinator.markEvent(event.id, it) },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ sections

@Composable
private fun StatusSection(state: MonitorUiState) {
    val dotColor = when {
        !state.notificationAccessGranted -> MaterialTheme.colorScheme.error
        state.monitoring && (state.sessionConnected || state.notificationVisible) ->
            MaterialTheme.colorScheme.primary
        state.monitoring -> WarningColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(dotColor, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(14.dp))

            val current = state.current
            Text(
                text = current?.normalizedTitle()
                    ?: stringResource(R.string.current_track_unknown),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val artist = current?.normalizedArtist()
            if (artist != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    label = "session",
                    active = state.sessionConnected,
                )
                StatusPill(
                    label = "notification",
                    active = state.notificationVisible,
                )
                StatusPill(
                    label = "access",
                    active = state.notificationAccessGranted,
                )
            }

            if (current != null && current.phase.name != "UNKNOWN") {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "state: ${current.phase.name.lowercase()} · source: " +
                        current.source.name.lowercase().replace('_', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, active: Boolean) {
    val background = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = background,
        contentColor = foreground,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = if (active) "● $label" else "○ $label",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ControlsSection(state: MonitorUiState) {
    val context = LocalContext.current

    if (!state.notificationAccessGranted) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.grant_notification_access),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.notification_access_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { openNotificationAccessSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.grant_notification_access))
                }
            }
        }
    }

    if (state.monitoring) {
        Button(
            onClick = { MonitoringCoordinator.stopMonitoring() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text(stringResource(R.string.stop_monitoring))
        }
    } else {
        Button(
            onClick = { MonitoringCoordinator.startMonitoring() },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.notificationAccessGranted,
        ) {
            Text(stringResource(R.string.start_monitoring))
        }
    }

    if (!state.spotifyInstalled) {
        Text(
            text = stringResource(R.string.spotify_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoticeCard(notice: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = notice,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { MonitoringCoordinator.dismissNotice() }) {
                Text(stringResource(R.string.clear_notice))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecoverySection(state: MonitorUiState) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.recovery_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.recovery_enable),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = state.autoRecoveryEnabled,
                    onCheckedChange = { MonitoringCoordinator.setAutoRecoveryEnabled(it) },
                )
            }

            if (state.autoRecoveryEnabled) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.recovery_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.recoveryMode == RecoveryMode.LAUNCHER_ONLY,
                        onClick = { MonitoringCoordinator.setRecoveryMode(RecoveryMode.LAUNCHER_ONLY) },
                        label = { Text(stringResource(R.string.mode_launcher)) },
                    )
                    FilterChip(
                        selected = state.recoveryMode == RecoveryMode.ACCESSIBILITY,
                        onClick = { MonitoringCoordinator.setRecoveryMode(RecoveryMode.ACCESSIBILITY) },
                        label = { Text(stringResource(R.string.mode_accessibility)) },
                    )
                }

                Spacer(Modifier.height(10.dp))
                when (state.recoveryMode) {
                    RecoveryMode.ACCESSIBILITY -> {
                        Text(
                            text = stringResource(R.string.accessibility_explanation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (state.accessibilityServiceGranted) {
                            Text(
                                text = stringResource(R.string.accessibility_status_on),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.accessibility_status_off),
                                style = MaterialTheme.typography.labelSmall,
                                color = WarningColor,
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = { openAccessibilitySettings(context) },
                            ) {
                                Text(stringResource(R.string.enable_accessibility))
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.recovery_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.recovery_gesture_limitation),
                            style = MaterialTheme.typography.bodySmall,
                            color = WarningColor,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        R.string.recovery_attempts,
                        state.recoveryAttempts,
                        state.recoveryAttemptsRemaining,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.lastRecoveryOutcome?.let { outcome ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "last outcome: ${outcome.name.lowercase().replace('_', ' ')}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (outcome == RecoveryOutcome.LAUNCHED) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            InfoColor
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { MonitoringCoordinator.manualReopenSpotify() },
                    enabled = state.spotifyInstalled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.reopen_spotify))
                }
                OutlinedButton(
                    onClick = { MonitoringCoordinator.emergencyStopRecovery() },
                    enabled = state.autoRecoveryEnabled || state.recoveryAttempts > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.stop_recovery))
                }
            }
        }
    }
}

@Composable
private fun PrivacySection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.privacy_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.unofficial_notice),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogHeaderSection(state: MonitorUiState) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.events_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = pluralStringResource(R.plurals.events_count, state.events.size, state.events.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = pluralStringResource(R.plurals.labels_count, state.diagnosticCount, state.diagnosticCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { MonitoringCoordinator.clearLog() }) {
                Text(stringResource(R.string.clear_log))
            }
            TextButton(
                onClick = { MonitoringCoordinator.clearDiagnostics() },
                enabled = state.diagnosticCount > 0,
            ) {
                Text(stringResource(R.string.clear_labels))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun EmptyLogSection() {
    Text(
        text = stringResource(R.string.empty_log),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
}

// ------------------------------------------------------------------ event row

@Composable
private fun EventRow(
    event: PlaybackEvent,
    label: ManualLabel?,
    expanded: Boolean,
    onToggleEvidence: () -> Unit,
    onMark: (ManualLabel) -> Unit,
) {
    val time = remember(event.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timestampMs))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                ClassificationChip(event.classification)
                Spacer(Modifier.weight(1f))
                Text(
                    text = event.kind.name.lowercase().replace('_', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = event.summary(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            label?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (it) {
                        ManualLabel.AD -> stringResource(R.string.labelled_ad)
                        ManualLabel.NOT_AD -> stringResource(R.string.labelled_not_ad)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onToggleEvidence) {
                    Text(
                        stringResource(
                            if (expanded) R.string.hide_evidence else R.string.show_evidence,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { onMark(ManualLabel.AD) },
                    enabled = label != ManualLabel.AD,
                ) {
                    Text(stringResource(R.string.mark_ad))
                }
                TextButton(
                    onClick = { onMark(ManualLabel.NOT_AD) },
                    enabled = label != ManualLabel.NOT_AD,
                ) {
                    Text(stringResource(R.string.mark_not_ad))
                }
            }

            if (expanded) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(6.dp))
                if (event.durationMs != null || event.positionMs != null) {
                    Text(
                        text = "position=${event.positionMs ?: "?"}ms · " +
                            "duration=${event.durationMs ?: "?"}ms",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = "confidence: ${event.confidence.name.lowercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                event.evidence.forEach { line ->
                    Text(
                        text = "• $line",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (event.kind == EventKind.RECOVERY) {
                    Text(
                        text = "recovery events are recorded attempts, not ad detections",
                        style = MaterialTheme.typography.labelSmall,
                        color = InfoColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassificationChip(classification: Classification) {
    val colors = when (classification) {
        Classification.NORMAL_PLAYBACK ->
            Color(0xFF1E4D3B) to Color(0xFF9DE8C8)
        Classification.TRACK_TRANSITION ->
            Color(0xFF1D3350) to Color(0xFFAFCBF7)
        Classification.POSSIBLE_AD ->
            Color(0xFF4A3A12) to Color(0xFFF4D488)
        Classification.BUFFERING_OR_INTERRUPTION ->
            Color(0xFF33373F) to Color(0xFFB9C0CC)
        Classification.UNKNOWN ->
            Color(0xFF2A2E35) to Color(0xFF99A2AE)
    }
    Surface(
        color = colors.first,
        contentColor = colors.second,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = classification.name.lowercase().replace('_', ' '),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ------------------------------------------------------------------ helpers

private fun openNotificationAccessSettings(context: Context) {
    val candidates = listOf(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // try the next candidate
        } catch (e: SecurityException) {
            // try the next candidate
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(AccessibilityAccess.settingsIntent())
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (ignored: ActivityNotFoundException) {
            // No settings screen available; nothing further to do.
        }
    }
}
