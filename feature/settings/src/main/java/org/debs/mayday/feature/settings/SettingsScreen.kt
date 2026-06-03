package org.debs.mayday.feature.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.debs.mayday.core.designsystem.component.MaydayActionButton
import org.debs.mayday.core.designsystem.component.MaydayBottomActionBar
import org.debs.mayday.core.designsystem.component.MaydayIconButton
import org.debs.mayday.core.designsystem.component.MaydayScreenBackground
import org.debs.mayday.core.designsystem.component.MaydaySectionTitle
import org.debs.mayday.core.designsystem.component.MaydaySegmentedControl
import org.debs.mayday.core.designsystem.component.MaydayStatRow
import org.debs.mayday.core.designsystem.component.MaydaySurfaceCard
import org.debs.mayday.core.designsystem.component.MaydayTextField
import org.debs.mayday.core.designsystem.component.MaydayToggle
import org.debs.mayday.core.designsystem.component.MaydayTopBar
import org.debs.mayday.core.designsystem.theme.LocalMaydayDensity
import org.debs.mayday.core.designsystem.theme.MaydayTheme
import org.debs.mayday.core.designsystem.theme.MaydayStrings
import org.debs.mayday.core.designsystem.theme.cancel
import org.debs.mayday.core.designsystem.theme.importClipboard
import org.debs.mayday.core.designsystem.theme.importKey
import org.debs.mayday.core.designsystem.theme.importKeyText
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.designsystem.theme.relayCountLabel
import org.debs.mayday.core.designsystem.theme.serverCountLabel
import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.NetworkRescueProfile
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.UiPreferences
import org.debs.mayday.core.model.VpnTransportMode
import kotlin.math.roundToInt

@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsUiEvent) -> Unit,
) {
    val strings = maydayStrings(state.language)
    val density = LocalMaydayDensity.current
    val snackbarHostState = remember { SnackbarHostState() }
    val serverRowStepPx = with(LocalDensity.current) { (64.dp + density.sectionGap).toPx() }
    var showTextImportDialog by remember { mutableStateOf(false) }
    var importKeyInput by remember { mutableStateOf("") }
    var draggingServerId by remember { mutableStateOf<String?>(null) }
    var draggingServerIndex by remember { mutableStateOf<Int?>(null) }
    var draggingServerTargetIndex by remember { mutableStateOf<Int?>(null) }
    var draggingServerOffset by remember { mutableStateOf(0f) }
    val serverDragStateKey = state.servers.joinToString(separator = "|") { it.clientId }

    fun serverDragTargetIndex(fromIndex: Int, offset: Float = draggingServerOffset): Int {
        return (fromIndex + (offset / serverRowStepPx).roundToInt())
            .coerceIn(state.servers.indices)
    }

    fun resetServerDrag() {
        draggingServerId = null
        draggingServerIndex = null
        draggingServerTargetIndex = null
        draggingServerOffset = 0f
    }

    fun finishServerDrag(commitMove: Boolean) {
        val fromIndex = draggingServerIndex
        val targetIndex = if (commitMove && fromIndex != null && fromIndex in state.servers.indices) {
            draggingServerTargetIndex ?: serverDragTargetIndex(fromIndex)
        } else {
            null
        }
        resetServerDrag()
        if (commitMove && fromIndex != null && fromIndex in state.servers.indices) {
            if (targetIndex != null && targetIndex != fromIndex) {
                onEvent(
                    SettingsUiEvent.ServerMoved(
                        fromIndex = fromIndex,
                        toIndex = targetIndex,
                    ),
                )
            }
        }
    }

    LaunchedEffect(serverDragStateKey) {
        if (draggingServerId != null) {
            resetServerDrag()
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onEvent(SettingsUiEvent.MessageShown)
    }

    if (showTextImportDialog) {
        AlertDialog(
            onDismissRequest = { showTextImportDialog = false },
            title = { Text(text = strings.importKey) },
            text = {
                MaydayTextField(
                    label = strings.importKeyText,
                    value = importKeyInput,
                    onValueChange = { importKeyInput = it },
                    modifier = Modifier.heightIn(min = 160.dp),
                    singleLine = false,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = importKeyInput.isNotBlank(),
                    onClick = {
                        val rawConfig = importKeyInput
                        importKeyInput = ""
                        showTextImportDialog = false
                        onEvent(
                            SettingsUiEvent.ConfigSelected(
                                rawConfig = rawConfig,
                                sourceName = strings.importKey,
                            ),
                        )
                    },
                ) {
                    Text(text = strings.importConfig)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextImportDialog = false }) {
                    Text(text = strings.cancel)
                }
            },
        )
    }

    MaydayScreenBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (state.hasUnsavedChanges) {
                    MaydayBottomActionBar(
                        primaryText = if (state.isLoading) strings.saving else strings.saveProfile,
                        onPrimaryClick = { onEvent(SettingsUiEvent.SaveClicked) },
                        enabled = !state.isLoading,
                        supportingText = "${strings.relayCountLabel(state.relays.size)} | ${strings.serverCountLabel(state.servers.size)} | ${routingSummary(strings, state.splitTunnelMode, state.selectedPackageCount)}",
                    )
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = innerPadding.calculateTopPadding() + 6.dp,
                    bottom = innerPadding.calculateBottomPadding() + density.sectionGap,
                ),
                verticalArrangement = Arrangement.spacedBy(density.sectionGap),
            ) {
                item {
                    MaydayTopBar(
                        title = strings.settings,
                        onBackClick = { onEvent(SettingsUiEvent.BackClicked) },
                        applyHorizontalPadding = false,
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MaydaySectionTitle(text = strings.splitRouting)
                        MaydaySurfaceCard {
                            MaydayStatRow(
                                label = strings.routingSummary,
                                value = routingSummary(
                                    strings,
                                    state.splitTunnelMode,
                                    state.selectedPackageCount,
                                ),
                            )
                            MaydayActionButton(
                                text = strings.splitRouting,
                                onClick = { onEvent(SettingsUiEvent.OpenSplitClicked) },
                                modifier = Modifier.fillMaxWidth(),
                                filled = false,
                            )
                            MaydayActionButton(
                                text = semanticAnalysisLabel(strings),
                                onClick = { onEvent(SettingsUiEvent.OpenSemanticClicked) },
                                modifier = Modifier.fillMaxWidth(),
                                filled = false,
                            )
                        }
                    }
                }

                item {
                    SettingsCollapsibleSection(
                        title = strings.advanced,
                        initiallyExpanded = false,
                    ) {
                        SettingsField(
                            label = strings.dns,
                            value = state.dnsServers,
                            onValueChange = { onEvent(SettingsUiEvent.DnsChanged(it)) },
                        )
                        SettingsPopupChoiceRow(
                            label = strings.transport,
                            selected = state.transportMode,
                            items = transportChoices(strings, state.transportOptions),
                            onSelect = {
                                onEvent(SettingsUiEvent.TransportModeChanged(it as VpnTransportMode))
                            },
                        )
                        SettingsRescueModeRow(
                            copy = advancedSettingCopy(strings, AdvancedSettingCopyKey.NetworkRescue),
                            icon = Icons.Outlined.HealthAndSafety,
                            strings = strings,
                            selected = state.networkRescueProfile,
                            onSelect = {
                                onEvent(SettingsUiEvent.NetworkRescueProfileChanged(it as NetworkRescueProfile))
                            },
                        )
                        SettingsField(
                            label = strings.serverFailbackDelay,
                            value = state.serverFailbackDelaySec,
                            onValueChange = { onEvent(SettingsUiEvent.ServerFailbackDelayChanged(it)) },
                        )
                        SettingsNumberSettingRow(
                            copy = advancedSettingCopy(strings, AdvancedSettingCopyKey.TunnelMtu),
                            icon = Icons.Outlined.Tune,
                            value = state.mtu,
                            onValueChange = { onEvent(SettingsUiEvent.MtuChanged(it)) },
                        )
                        SettingSwitchRow(
                            copy = advancedSettingCopy(strings, AdvancedSettingCopyKey.DisableIpv6),
                            icon = Icons.Outlined.Public,
                            checked = state.disableIpv6,
                            onCheckedChange = { onEvent(SettingsUiEvent.DisableIpv6Changed(it)) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsNumberSettingRow(
                            copy = advancedSettingCopy(strings, AdvancedSettingCopyKey.PacketFragment),
                            icon = Icons.AutoMirrored.Outlined.CallSplit,
                            value = state.packetFragmentPayloadBytes,
                            onValueChange = { onEvent(SettingsUiEvent.PacketFragmentPayloadChanged(it)) },
                        )
                        SettingSwitchRow(
                            copy = advancedSettingCopy(strings, AdvancedSettingCopyKey.DisablePacketBatching),
                            icon = Icons.Outlined.AllInclusive,
                            checked = state.disablePacketBatching,
                            onCheckedChange = { onEvent(SettingsUiEvent.DisablePacketBatchingChanged(it)) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingSwitchRow(
                            copy = advancedSettingCopy(strings, AdvancedSettingCopyKey.PrestartFullProbe),
                            icon = Icons.AutoMirrored.Outlined.FactCheck,
                            checked = state.prestartFullProbe,
                            onCheckedChange = { onEvent(SettingsUiEvent.PrestartFullProbeChanged(it)) },
                        )
                        SettingSwitchRow(
                            copy = advancedSettingCopy(strings, AdvancedSettingCopyKey.SteadyStateQuickProbe),
                            icon = Icons.Outlined.Speed,
                            checked = state.steadyStateQuickProbeEnabled,
                            onCheckedChange = { onEvent(SettingsUiEvent.SteadyStateQuickProbeChanged(it)) },
                        )
                        SettingSwitchRow(
                            copy = advancedSettingCopy(strings, AdvancedSettingCopyKey.SteadyStateBenchmark),
                            icon = Icons.Outlined.Sync,
                            checked = state.steadyStateBenchmarkEnabled,
                            onCheckedChange = { onEvent(SettingsUiEvent.SteadyStateBenchmarkChanged(it)) },
                        )
                        SettingsField(
                            label = strings.tun,
                            value = state.tunName,
                            onValueChange = { onEvent(SettingsUiEvent.TunNameChanged(it)) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingSwitchRow(
                            copy = AdvancedSettingCopy(
                                title = strings.autoFailover,
                                subtitle = strings.keepSessionAliveHint,
                            ),
                            icon = Icons.Outlined.Sync,
                            checked = state.autoReconnect,
                            onCheckedChange = { onEvent(SettingsUiEvent.AutoReconnectChanged(it)) },
                        )
                    }
                }

                item {
                    SettingsCollapsibleSection(
                        title = "${strings.theme} / ${strings.language}",
                        initiallyExpanded = false,
                    ) {
                        SettingsChoiceRow(
                            label = strings.language,
                            selected = state.language,
                            items = listOf(
                                AppLanguage.EN to "en",
                                AppLanguage.RU to "ru",
                            ),
                            onSelect = { onEvent(SettingsUiEvent.LanguageChanged(it as AppLanguage)) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsChoiceRow(
                            label = strings.theme,
                            selected = state.themeMode,
                            items = listOf(
                                AppThemeMode.LIGHT to strings.light,
                                AppThemeMode.DARK to strings.dark,
                            ),
                            onSelect = { onEvent(SettingsUiEvent.ThemeModeChanged(it as AppThemeMode)) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsChoiceRow(
                            label = strings.density,
                            selected = state.density,
                            items = listOf(
                                AppDensity.COMPACT to strings.compact,
                                AppDensity.COMFORTABLE to strings.comfortable,
                            ),
                            onSelect = { onEvent(SettingsUiEvent.DensityChanged(it as AppDensity)) },
                        )
                    }
                }

                item {
                    SettingsCollapsibleSection(
                        title = strings.importConfig,
                        initiallyExpanded = false,
                    ) {
                        state.importedConfigName?.let { imported ->
                            Text(
                                text = "${strings.importedFrom} $imported",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        MaydayActionButton(
                            text = strings.importClipboard,
                            onClick = { onEvent(SettingsUiEvent.ImportClipboardClicked) },
                            modifier = Modifier.fillMaxWidth(),
                            filled = false,
                        )
                        MaydayActionButton(
                            text = strings.importKey,
                            onClick = { showTextImportDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            filled = false,
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MaydaySectionTitle(text = strings.relays)
                        MaydaySurfaceCard {
                            Text(
                                text = strings.relaysHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                itemsIndexed(state.relays, key = { index, _ -> "relay-$index" }) { index, relay ->
                    ReadOnlyListField(name = relay.id.ifBlank { "${strings.relay} ${index + 1}" })
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MaydaySectionTitle(text = strings.servers)
                        MaydaySurfaceCard {
                            Text(
                                text = strings.serversHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                val previewFromIndex = draggingServerIndex
                    ?.takeIf { draggingServerId != null && it in state.servers.indices }
                val previewTargetIndex = draggingServerTargetIndex
                    ?.takeIf { previewFromIndex != null && it in state.servers.indices }

                itemsIndexed(state.servers, key = { _, server -> server.clientId }) { index, server ->
                    val isDragging = draggingServerId == server.clientId
                    val previewOffset = serverPreviewOffset(
                        index = index,
                        fromIndex = previewFromIndex,
                        targetIndex = previewTargetIndex,
                        rowStepPx = serverRowStepPx,
                    )
                    val animatedPreviewOffset by animateFloatAsState(
                        targetValue = previewOffset,
                        animationSpec = if (draggingServerId == null) {
                            snap()
                        } else {
                            spring()
                        },
                        label = "serverPreviewOffset",
                    )
                    val rowOffset = if (isDragging) {
                        draggingServerOffset
                    } else {
                        animatedPreviewOffset
                    }
                    ServerPriorityField(
                        name = server.id.ifBlank { strings.notSet },
                        dragKey = "${server.clientId}-$index",
                        canDrag = state.servers.size > 1 && !state.isLoading,
                        onDragStart = {
                            draggingServerId = server.clientId
                            draggingServerIndex = index
                            draggingServerTargetIndex = index
                            draggingServerOffset = 0f
                        },
                        onDrag = { deltaY ->
                            val nextOffset = draggingServerOffset + deltaY
                            draggingServerOffset = nextOffset
                            draggingServerTargetIndex = serverDragTargetIndex(index, nextOffset)
                        },
                        onDragEnd = {
                            finishServerDrag(commitMove = true)
                        },
                        onDragCancel = {
                            finishServerDrag(commitMove = false)
                        },
                        modifier = Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = rowOffset
                                shadowElevation = if (isDragging) 12.dp.toPx() else 0f
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerPriorityField(
    name: String,
    dragKey: String,
    canDrag: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalMaydayDensity.current
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = density.cardPadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServerDragHandle(
                enabled = canDrag,
                dragKey = dragKey,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                onDragCancel = onDragCancel,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReadOnlyListField(
    name: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalMaydayDensity.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text = name,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = density.cardPadding, vertical = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun serverPreviewOffset(
    index: Int,
    fromIndex: Int?,
    targetIndex: Int?,
    rowStepPx: Float,
): Float {
    if (fromIndex == null || targetIndex == null || fromIndex == targetIndex) {
        return 0f
    }
    return when {
        targetIndex > fromIndex && index in (fromIndex + 1)..targetIndex -> -rowStepPx
        targetIndex < fromIndex && index in targetIndex until fromIndex -> rowStepPx
        else -> 0f
    }
}

@Composable
private fun SettingsCollapsibleSection(
    title: String,
    initiallyExpanded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaydaySectionTitle(text = title)
            MaydayIconButton(
                label = if (expanded) "-" else "+",
                onClick = { expanded = !expanded },
            )
        }
        if (expanded) {
            MaydaySurfaceCard(content = content)
        }
    }
}

@Composable
private fun ServerDragHandle(
    enabled: Boolean,
    dragKey: String,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .size(56.dp)
            .serverImmediateVerticalDragGesture(
                enabled = enabled,
                gestureKey = dragKey,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                onDragCancel = onDragCancel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = colors.secondaryContainer,
            contentColor = colors.onSurfaceVariant,
            border = BorderStroke(1.dp, colors.outline),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "::",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Modifier.serverImmediateVerticalDragGesture(
    enabled: Boolean,
    gestureKey: String,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
): Modifier {
    if (!enabled) {
        return this
    }
    return pointerInput(gestureKey, enabled) {
        detectVerticalDragGestures(
            onDragStart = { onDragStart() },
            onDragEnd = { onDragEnd() },
            onDragCancel = { onDragCancel() },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                onDrag(dragAmount)
            },
        )
    }
}

@Composable
private fun SettingsChoiceRow(
    label: String,
    selected: Any,
    items: List<Pair<Any, String>>,
    onSelect: (Any) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MaydaySegmentedControl(
            items = items,
            selected = selected,
            onSelect = onSelect,
            equalWidth = true,
            minItemHeight = 40.dp,
            itemVerticalPadding = 8.dp,
            maxLines = 1,
        )
    }
}

@Composable
private fun SettingsPopupChoiceRow(
    label: String,
    selected: Any,
    items: List<Pair<Any, String>>,
    onSelect: (Any) -> Unit,
) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    var menuWidth by remember { mutableStateOf(0.dp) }
    val localDensity = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val selectedLabel = items.firstOrNull { it.first == selected }?.second
        ?: selected.toString()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        menuWidth = with(localDensity) { coordinates.size.width.toDp() }
                    },
                onClick = { expanded = true },
                shape = MaterialTheme.shapes.large,
                color = colors.surface,
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.outline),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selectedLabel,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = if (menuWidth > 0.dp) {
                    Modifier.width(menuWidth)
                } else {
                    Modifier.fillMaxWidth()
                },
            ) {
                items.forEach { item ->
                    val isSelected = item.first == selected
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = item.second,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 3,
                            )
                        },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            expanded = false
                            onSelect(item.first)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    copy: AdvancedSettingCopy,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingIcon(icon = icon)
        SettingCopy(copy = copy, modifier = Modifier.weight(1f))
        MaydayToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsRescueModeRow(
    copy: AdvancedSettingCopy,
    icon: ImageVector,
    strings: MaydayStrings,
    selected: NetworkRescueProfile,
    onSelect: (Any) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingIcon(icon = icon)
            SettingCopy(copy = copy, modifier = Modifier.weight(1f))
        }
        MaydaySegmentedControl(
            items = networkRescueChoices(strings),
            selected = selected,
            onSelect = onSelect,
            equalWidth = true,
            minItemHeight = 40.dp,
            itemVerticalPadding = 8.dp,
            maxLines = 1,
        )
    }
}

@Composable
private fun SettingsNumberSettingRow(
    copy: AdvancedSettingCopy,
    icon: ImageVector,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingIcon(icon = icon)
            SettingCopy(copy = copy, modifier = Modifier.weight(1f))
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun SettingIcon(
    icon: ImageVector,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        color = colors.secondaryContainer,
        contentColor = colors.primary,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SettingCopy(
    copy: AdvancedSettingCopy,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = copy.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = copy.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    MaydayTextField(
        label = label,
        value = value,
        onValueChange = onValueChange,
    )
}

@Composable
private fun SettingsNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    MaydayTextField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private enum class AdvancedSettingCopyKey {
    NetworkRescue,
    TunnelMtu,
    DisableIpv6,
    PacketFragment,
    DisablePacketBatching,
    PrestartFullProbe,
    SteadyStateQuickProbe,
    SteadyStateBenchmark,
}

private data class AdvancedSettingCopy(
    val title: String,
    val subtitle: String,
)

private fun advancedSettingCopy(
    strings: MaydayStrings,
    key: AdvancedSettingCopyKey,
): AdvancedSettingCopy {
    return when (strings.locale) {
        AppLanguage.RU -> when (key) {
            AdvancedSettingCopyKey.NetworkRescue -> AdvancedSettingCopy(
                title = "Режим спасения сети",
                subtitle = "Обычный режим без fallback, стабильный для плохих каналов, экстренный включает Raw UDP.",
            )
            AdvancedSettingCopyKey.TunnelMtu -> AdvancedSettingCopy(
                title = "MTU туннеля",
                subtitle = "Auto: 1280 для auto/UTP/Raw UDP, 1420 для TCP/WS/HTTPS. Максимум 1500.",
            )
            AdvancedSettingCopyKey.DisableIpv6 -> AdvancedSettingCopy(
                title = "Отключить IPv6",
                subtitle = "Использовать только IPv4, если сеть работает нестабильно.",
            )
            AdvancedSettingCopyKey.PacketFragment -> AdvancedSettingCopy(
                title = "Размер фрагмента пакета",
                subtitle = "0 отключает дробление. Защитный диапазон: 64-65536 байт.",
            )
            AdvancedSettingCopyKey.DisablePacketBatching -> AdvancedSettingCopy(
                title = "Отключить группировку пакетов",
                subtitle = "Помогает вместе с малым фрагментом на нестабильных каналах.",
            )
            AdvancedSettingCopyKey.PrestartFullProbe -> AdvancedSettingCopy(
                title = "Полная проверка перед подключением",
                subtitle = "VPN дождется полного теста маршрутов перед стартом туннеля.",
            )
            AdvancedSettingCopyKey.SteadyStateQuickProbe -> AdvancedSettingCopy(
                title = "Быстрая проверка в фоне",
                subtitle = "Легко проверяет доступность серверов во время работы.",
            )
            AdvancedSettingCopyKey.SteadyStateBenchmark -> AdvancedSettingCopy(
                title = "Фоновый замер скорости",
                subtitle = "Разрешает более тяжелую проверку качества канала.",
            )
        }
        AppLanguage.EN -> when (key) {
            AdvancedSettingCopyKey.NetworkRescue -> AdvancedSettingCopy(
                title = "Network rescue",
                subtitle = "Off for normal networks, Stable for poor links, Extreme only for emergency UDP fallback.",
            )
            AdvancedSettingCopyKey.TunnelMtu -> AdvancedSettingCopy(
                title = "Tunnel MTU",
                subtitle = "Auto: 1280 for auto/UTP/Raw UDP, 1420 for TCP/WS/HTTPS. Maximum 1500.",
            )
            AdvancedSettingCopyKey.DisableIpv6 -> AdvancedSettingCopy(
                title = "Disable IPv6",
                subtitle = "Use IPv4 only when the network is unstable.",
            )
            AdvancedSettingCopyKey.PacketFragment -> AdvancedSettingCopy(
                title = "Packet fragment size",
                subtitle = "0 disables fragmentation. Protected range: 64-65536 bytes.",
            )
            AdvancedSettingCopyKey.DisablePacketBatching -> AdvancedSettingCopy(
                title = "Disable packet batching",
                subtitle = "Pairs with small fragments on unstable links.",
            )
            AdvancedSettingCopyKey.PrestartFullProbe -> AdvancedSettingCopy(
                title = "Full check before connecting",
                subtitle = "Wait for a full route test before starting the tunnel.",
            )
            AdvancedSettingCopyKey.SteadyStateQuickProbe -> AdvancedSettingCopy(
                title = "Quick background check",
                subtitle = "Lightly checks server availability while connected.",
            )
            AdvancedSettingCopyKey.SteadyStateBenchmark -> AdvancedSettingCopy(
                title = "Background speed benchmark",
                subtitle = "Allows heavier channel quality checks in the background.",
            )
        }
    }
}

private fun routingSummary(
    strings: MaydayStrings,
    mode: SplitTunnelMode,
    count: Int,
): String {
    return when (mode) {
        SplitTunnelMode.DISABLED -> strings.allTraffic
        SplitTunnelMode.ONLY_SELECTED -> "${strings.onlySelected} ($count)"
        SplitTunnelMode.EXCLUDE_SELECTED -> "${strings.exceptSelected} ($count)"
    }
}

private fun transportChoices(
    strings: MaydayStrings,
    options: List<TransportModeOption>,
): List<Pair<Any, String>> {
    return options.ifEmpty { defaultTransportModeOptions() }
        .map { option ->
            option.mode to transportLabel(strings, option)
        }
}

private fun networkRescueChoices(strings: MaydayStrings): List<Pair<Any, String>> {
    return NetworkRescueProfile.entries.map { profile ->
        profile to networkRescueLabel(strings, profile)
    }
}

private fun networkRescueLabel(
    strings: MaydayStrings,
    profile: NetworkRescueProfile,
): String {
    return when (strings.locale) {
        AppLanguage.RU -> when (profile) {
            NetworkRescueProfile.OFF -> "Выкл."
            NetworkRescueProfile.STABLE -> "Стабильный"
            NetworkRescueProfile.EXTREME -> "Экстренный"
        }
        AppLanguage.EN -> when (profile) {
            NetworkRescueProfile.OFF -> "Off"
            NetworkRescueProfile.STABLE -> "Stable"
            NetworkRescueProfile.EXTREME -> "Extreme"
        }
    }
}

private fun transportLabel(
    strings: MaydayStrings,
    option: TransportModeOption,
): String {
    val catalogLabel = option.label.trim()
    return when (option.mode) {
        VpnTransportMode.AUTO -> strings.auto
        VpnTransportMode.TCP -> catalogLabel.ifBlank { strings.tcp }
        VpnTransportMode.UTP -> catalogLabel.ifBlank { strings.utp }
        VpnTransportMode.WS -> catalogLabel.ifBlank { "WebSocket" }
        VpnTransportMode.HTTPS -> catalogLabel.ifBlank { "HTTPS REST" }
        VpnTransportMode.RAW_UDP -> catalogLabel.ifBlank { "Raw UDP" }
    }
}

private fun semanticAnalysisLabel(strings: MaydayStrings): String {
    return when (strings.locale) {
        AppLanguage.RU -> "Семантический анализ APK"
        AppLanguage.EN -> "Semantic APK analysis"
    }
}

@Preview(name = "Settings / Profile", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SettingsProfilePreview() {
    SettingsScreenPreview(
        state = previewSettingsState(
            uiPreferences = previewSettingsPreferences(
                themeMode = AppThemeMode.DARK,
                language = AppLanguage.EN,
                density = AppDensity.COMFORTABLE,
            ),
        ),
    )
}

@Preview(name = "Settings / Saving RU", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SettingsSavingRuPreview() {
    SettingsScreenPreview(
        state = previewSettingsState(
            uiPreferences = previewSettingsPreferences(
                themeMode = AppThemeMode.LIGHT,
                language = AppLanguage.RU,
                density = AppDensity.COMPACT,
            ),
            isLoading = true,
        ),
    )
}

@Composable
private fun SettingsScreenPreview(state: SettingsUiState) {
    MaydayTheme(
        themeMode = state.uiPreferences.themeMode,
        language = state.uiPreferences.language,
        density = state.uiPreferences.density,
    ) {
        SettingsScreen(
            state = state,
            onEvent = {},
        )
    }
}

private fun previewSettingsState(
    uiPreferences: UiPreferences,
    isLoading: Boolean = false,
): SettingsUiState {
    return SettingsUiState(
        uiPreferences = uiPreferences,
        relays = listOf(
            RelayDraft(
                id = "relay-eu-1",
                addr = "eu.relay.mayday.dev",
                shortId = "1",
                relayKey = PREVIEW_HEX_KEY,
            ),
            RelayDraft(
                id = "relay-us-1",
                addr = "us.relay.mayday.dev",
                shortId = "2",
                relayKey = PREVIEW_HEX_KEY,
            ),
        ),
        userId = "4815162342",
        servers = listOf(
            ServerDraft(
                id = "server-main",
                key = PREVIEW_HEX_KEY,
                priority = "1",
                clientId = "preview-server-main",
            ),
            ServerDraft(
                id = "server-backup",
                key = PREVIEW_HEX_KEY,
                priority = "2",
                clientId = "preview-server-backup",
            ),
        ),
        tunName = "mayday0",
        dnsServers = "1.1.1.1, 8.8.8.8",
        mtu = "1280",
        serverFailbackDelaySec = "60",
        transportMode = VpnTransportMode.AUTO,
        packetFragmentPayloadBytes = "100",
        disablePacketBatching = true,
        autoReconnect = true,
        splitTunnelMode = SplitTunnelMode.ONLY_SELECTED,
        selectedPackageCount = 6,
        isLoading = isLoading,
        importedConfigName = "mayday import key",
    )
}

private fun previewSettingsPreferences(
    themeMode: AppThemeMode,
    language: AppLanguage,
    density: AppDensity,
): UiPreferences {
    return UiPreferences(
        themeMode = themeMode,
        language = language,
        density = density,
        onboardingCompleted = true,
    )
}

private const val PREVIEW_HEX_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
