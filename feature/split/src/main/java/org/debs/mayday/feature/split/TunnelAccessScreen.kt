package org.debs.mayday.feature.split

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.debs.mayday.core.designsystem.component.MaydayScreenBackground
import org.debs.mayday.core.designsystem.component.MaydaySurfaceCard
import org.debs.mayday.core.designsystem.component.MaydayTopBar
import org.debs.mayday.core.designsystem.theme.LocalMaydayDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.TunnelAccessAssessment
import org.debs.mayday.core.model.VpnConnectionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TunnelAccessScreen(
    state: TunnelAccessUiState,
    onBack: () -> Unit,
    onFilter: (TunnelAccessAssessment?) -> Unit,
    onRecordingChanged: (Boolean) -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onMessageShown: () -> Unit
) {
    val strings = tunnelAccessStrings(state.uiPreferences.language)
    val density = LocalMaydayDensity.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var technicalExpanded by rememberSaveable { mutableStateOf(false) }
    val filteredGroups = remember(state.groups, state.filter) {
        state.groups.filter { state.filter == null || it.key.assessment == state.filter }
    }
    val timeFormatter = remember(state.uiPreferences.language) {
        DateTimeFormatter.ofPattern(
            "dd MMM yyyy HH:mm:ss",
            if (state.uiPreferences.language == AppLanguage.RU) Locale.forLanguageTag("ru") else Locale.ENGLISH
        ).withZone(ZoneId.systemDefault())
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            onMessageShown()
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(strings.clearTitle) },
            text = { Text(strings.clearMessage) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text(strings.clear) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(strings.cancel) }
            }
        )
    }

    MaydayScreenBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = innerPadding.calculateTopPadding() + 6.dp,
                    bottom = innerPadding.calculateBottomPadding() + density.sectionGap
                ),
                verticalArrangement = Arrangement.spacedBy(density.sectionGap)
            ) {
                item {
                    MaydayTopBar(
                        title = strings.title,
                        onBackClick = onBack,
                        applyHorizontalPadding = false
                    )
                }
                item {
                    MaydaySurfaceCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(strings.recording, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    strings.recordingHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                modifier = Modifier.semantics { contentDescription = strings.recording },
                                checked = state.recordingEnabled,
                                enabled = !state.isWorking,
                                onCheckedChange = onRecordingChanged
                            )
                        }
                        Text(
                            if (state.recordingEnabled) strings.recordingOn else strings.recordingOff,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (state.recordingEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(strings.vpnStatus(state.status), style = MaterialTheme.typography.bodyMedium)
                        state.savedMode?.let { Text(strings.savedMode(it), style = MaterialTheme.typography.bodyMedium) }
                        if (state.recordingEnabled) {
                            if (state.status != VpnConnectionStatus.Running) {
                                Text(strings.waitingForVpn, style = MaterialTheme.typography.bodySmall)
                            } else if (state.savedMode == SplitTunnelMode.DISABLED) {
                                Text(strings.allAppsMode, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    MaydaySurfaceCard {
                        Text(strings.summary, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { technicalExpanded = !technicalExpanded }) {
                            Text(if (technicalExpanded) strings.hideDetails else strings.details)
                        }
                        if (technicalExpanded) {
                            Text(strings.technicalDetails, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !state.log.isLoading && !state.isWorking, onClick = onExport) {
                            Text(strings.export)
                        }
                        TextButton(
                            enabled = !state.log.isLoading && !state.isWorking,
                            onClick = { confirmClear = true }
                        ) { Text(strings.clear) }
                        if (state.isWorking) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                if (state.log.writeError || state.log.droppedEvents > 0) {
                    item {
                        MaydaySurfaceCard {
                            if (state.log.writeError) {
                                Text(strings.storageError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (state.log.droppedEvents > 0) {
                                Text(strings.dropped(state.log.droppedEvents), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings.retained(state.log.events.size), style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                TunnelAccessAssessment.POLICY_MISMATCH,
                                null,
                                TunnelAccessAssessment.UNKNOWN_OWNER,
                                TunnelAccessAssessment.SHARED_UID,
                                TunnelAccessAssessment.POLICY_MATCH
                            ).forEach { filter ->
                                FilterChip(
                                    selected = state.filter == filter,
                                    onClick = { onFilter(filter) },
                                    label = { Text(filter?.let(strings::assessment) ?: strings.all) }
                                )
                            }
                        }
                    }
                }
                if (state.log.isLoading) {
                    item {
                        MaydaySurfaceCard {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text(strings.loading)
                        }
                    }
                } else if (filteredGroups.isEmpty()) {
                    item {
                        MaydaySurfaceCard {
                            Text(
                                if (state.log.events.isEmpty()) strings.empty else strings.noMatches,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                } else {
                    items(filteredGroups, key = { it.key.toString() }) { group ->
                        TunnelAccessGroupCard(group, state.appLabels, strings, timeFormatter)
                    }
                }
            }
        }
    }
}

@Composable
private fun TunnelAccessGroupCard(
    group: TunnelAccessGroup,
    labels: Map<String, String>,
    strings: TunnelAccessStrings,
    timeFormatter: DateTimeFormatter
) {
    var expanded by rememberSaveable(group.key.toString()) { mutableStateOf(false) }
    val latest = group.latest
    val name = when {
        group.key.assessment == TunnelAccessAssessment.SHARED_UID -> strings.sharedOwner
        group.key.packages.isEmpty() -> strings.unknownOwner
        else -> labels[group.key.packages.first()] ?: group.key.packages.first()
    }
    val assessmentColor = when (group.key.assessment) {
        TunnelAccessAssessment.POLICY_MISMATCH -> MaterialTheme.colorScheme.error
        TunnelAccessAssessment.POLICY_MATCH -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    MaydaySurfaceCard {
        Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        group.key.packages.forEach { packageName ->
            val label = labels[packageName]?.takeIf { group.key.packages.size > 1 && it != packageName }
            Text(
                text = if (label != null) "$label · $packageName" else packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(strings.assessment(group.key.assessment), style = MaterialTheme.typography.labelLarge, color = assessmentColor)
        Text(
            "UID: ${group.key.ownerUid ?: "—"} · ${strings.observations(group.observations.size)}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "${strings.lastSeen}: ${timeFormatter.format(Instant.ofEpochMilli(latest.timestampEpochMillis))}",
            style = MaterialTheme.typography.bodySmall
        )
        Text("${latest.protocol} · ${latest.remoteEndpoint}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) strings.hideDetails else strings.details) }
        if (expanded) {
            Text(
                when (group.key.assessment) {
                    TunnelAccessAssessment.POLICY_MISMATCH -> strings.mismatchDetails
                    TunnelAccessAssessment.POLICY_MATCH -> strings.matchDetails
                    TunnelAccessAssessment.UNKNOWN_OWNER -> strings.unknownDetails
                    TunnelAccessAssessment.SHARED_UID -> strings.sharedDetails
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(strings.eventMode(group.key.mode), style = MaterialTheme.typography.bodySmall)
            Text(
                "${strings.firstSeen}: ${timeFormatter.format(Instant.ofEpochMilli(group.firstTimestamp))}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "${strings.matchedPackages}: ${latest.matchedPackages.joinToString().ifEmpty { strings.none }}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(strings.recentEndpoints, style = MaterialTheme.typography.labelMedium)
            group.observations.asSequence()
                .map { "${it.protocol} · ${it.localEndpoint} → ${it.remoteEndpoint}" }
                .distinct()
                .take(8)
                .forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
