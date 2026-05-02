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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
import org.debs.mayday.core.designsystem.theme.MaydayStrings
import org.debs.mayday.core.designsystem.theme.cancel
import org.debs.mayday.core.designsystem.theme.configText
import org.debs.mayday.core.designsystem.theme.importClipboard
import org.debs.mayday.core.designsystem.theme.importText
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.designsystem.theme.relayCountLabel
import org.debs.mayday.core.designsystem.theme.serverCountLabel
import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.SplitTunnelMode
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
    var configTextInput by remember { mutableStateOf("") }
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
            title = { Text(text = strings.importText) },
            text = {
                MaydayTextField(
                    label = strings.configText,
                    value = configTextInput,
                    onValueChange = { configTextInput = it },
                    modifier = Modifier.heightIn(min = 160.dp),
                    singleLine = false,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = configTextInput.isNotBlank(),
                    onClick = {
                        val rawConfig = configTextInput
                        configTextInput = ""
                        showTextImportDialog = false
                        onEvent(
                            SettingsUiEvent.ConfigSelected(
                                rawConfig = rawConfig,
                                sourceName = strings.configText,
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
                MaydayBottomActionBar(
                    primaryText = if (state.isLoading) strings.saving else strings.saveProfile,
                    onPrimaryClick = { onEvent(SettingsUiEvent.SaveClicked) },
                    enabled = !state.isLoading,
                    supportingText = "${strings.relayCountLabel(state.relays.size)} | ${strings.serverCountLabel(state.servers.size)} | ${routingSummary(strings, state.splitTunnelMode, state.selectedPackageCount)}",
                )
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
                        MaydaySectionTitle(text = strings.profile)
                        MaydaySurfaceCard {
                            SettingsField(
                                label = strings.profileField,
                                value = state.profileName,
                                onValueChange = { onEvent(SettingsUiEvent.ProfileNameChanged(it)) },
                            )
                            MaydayStatRow(
                                label = strings.userId,
                                value = state.userId.ifBlank { strings.notSet },
                            )
                        }
                    }
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
                        SettingsChoiceRow(
                            label = strings.transport,
                            selected = state.transportMode,
                            items = listOf(
                                VpnTransportMode.AUTO to strings.auto,
                                VpnTransportMode.TCP to strings.tcp,
                                VpnTransportMode.UTP to strings.utp,
                            ),
                            onSelect = {
                                onEvent(SettingsUiEvent.TransportModeChanged(it as VpnTransportMode))
                            },
                        )
                        SettingsField(
                            label = strings.serverFailbackDelay,
                            value = state.serverFailbackDelaySec,
                            onValueChange = { onEvent(SettingsUiEvent.ServerFailbackDelayChanged(it)) },
                        )
                        SettingsNumberField(
                            label = strings.mtu,
                            value = state.mtu,
                            onValueChange = { onEvent(SettingsUiEvent.MtuChanged(it)) },
                        )
                        SettingsField(
                            label = strings.tun,
                            value = state.tunName,
                            onValueChange = { onEvent(SettingsUiEvent.TunNameChanged(it)) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingSwitchRow(
                            title = strings.autoFailover,
                            subtitle = strings.keepSessionAliveHint,
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
                            text = strings.importFile,
                            onClick = { onEvent(SettingsUiEvent.ImportClicked) },
                            modifier = Modifier.fillMaxWidth(),
                            filled = false,
                        )
                        MaydayActionButton(
                            text = strings.importClipboard,
                            onClick = { onEvent(SettingsUiEvent.ImportClipboardClicked) },
                            modifier = Modifier.fillMaxWidth(),
                            filled = false,
                        )
                        MaydayActionButton(
                            text = strings.importText,
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
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MaydayToggle(checked = checked, onCheckedChange = onCheckedChange)
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

private fun semanticAnalysisLabel(strings: MaydayStrings): String {
    return when (strings.locale) {
        AppLanguage.RU -> "Семантический анализ APK"
        AppLanguage.EN -> "Semantic APK analysis"
    }
}
