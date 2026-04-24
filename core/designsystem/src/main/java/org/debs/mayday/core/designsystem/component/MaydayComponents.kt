package org.debs.mayday.core.designsystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.debs.mayday.core.designsystem.theme.LocalMaydayDensity
import org.debs.mayday.core.designsystem.theme.MaydayStrings
import org.debs.mayday.core.model.VpnConnectionStatus

@Composable
fun MaydayScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        content = content,
    )
}

@Composable
fun MaydayTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null,
    trailingText: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    applyHorizontalPadding: Boolean = true,
) {
    val density = LocalMaydayDensity.current
    val horizontalPadding = if (applyHorizontalPadding) density.screenPadding else 0.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBackClick != null) {
                MaydayIconButton(
                    label = "<",
                    onClick = onBackClick,
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailingText != null && onTrailingClick != null) {
            MaydayIconButton(
                label = trailingText,
                onClick = onTrailingClick,
                monospace = trailingText.length > 1,
            )
        }
    }
}

@Composable
fun MaydayHeroCard(
    statusText: String,
    statusColor: Color,
    title: String,
    subtitle: String,
    actionText: String,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
    filledAction: Boolean = true,
    actionEnabled: Boolean = true,
    showHalo: Boolean = false,
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    val density = LocalMaydayDensity.current
    val colors = MaterialTheme.colorScheme
    val compact = density.screenPadding <= 16.dp
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surface,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outline),
    ) {
        Box {
            if (showHalo) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 36.dp, y = (-52).dp)
                        .size(220.dp)
                        .blur(20.dp)
                        .background(colors.primaryContainer.copy(alpha = 0.72f), CircleShape),
                )
            }
            Column(
                modifier = Modifier.padding(density.heroPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(color = statusColor, shape = CircleShape),
                    )
                    Text(
                        text = statusText.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
                Text(
                    text = title,
                    style = if (compact) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
                    color = colors.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                extraContent()
                MaydayActionButton(
                    text = actionText,
                    onClick = onActionClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(density.actionHeight),
                    filled = filledAction,
                    enabled = actionEnabled,
                )
            }
        }
    }
}

@Composable
fun MaydaySurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalMaydayDensity.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(density.cardPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap),
            content = content,
        )
    }
}

@Composable
fun MaydaySectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun MaydayStatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MaydayBottomActionBar(
    primaryText: String,
    onPrimaryClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    val density = LocalMaydayDensity.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = density.screenPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            supportingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MaydayActionButton(
                text = primaryText,
                onClick = onPrimaryClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(density.actionHeight),
                enabled = enabled,
            )
        }
    }
}

@Composable
fun MaydayActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val containerColor = if (filled) colors.primary else Color.Transparent
    val contentColor = if (filled) {
        if (colors.primary.luminance() > 0.5f) colors.background else Color.White
    } else {
        colors.onSurface
    }
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, if (filled) colors.primary else colors.outline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun <T> MaydaySegmentedControl(
    items: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    equalWidth: Boolean = false,
    minItemHeight: androidx.compose.ui.unit.Dp = 48.dp,
    itemVerticalPadding: androidx.compose.ui.unit.Dp = 10.dp,
    maxLines: Int = 2,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.secondaryContainer,
        border = BorderStroke(1.dp, colors.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, (value, title) ->
                val selectedItem = value == selected
                val interactionSource = remember(value) { MutableInteractionSource() }
                val segmentModifier = if (equalWidth) {
                    Modifier.weight(1f)
                } else {
                    Modifier
                }
                Surface(
                    modifier = segmentModifier
                        .fillMaxHeight()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelect(value) },
                        ),
                    shape = MaterialTheme.shapes.small,
                    color = if (selectedItem) colors.surface else Color.Transparent,
                    contentColor = if (selectedItem) colors.onSurface else colors.onSurfaceVariant,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minItemHeight)
                            .padding(horizontal = 12.dp, vertical = itemVerticalPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selectedItem) colors.onSurface else colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = maxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (index < items.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(colors.outlineVariant),
                    )
                }
            }
        }
    }
}

@Composable
fun MaydayTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
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
                keyboardOptions = keyboardOptions,
                singleLine = singleLine,
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
fun MaydayToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val knobOffset = animateDpAsState(
        targetValue = if (checked) 16.dp else 0.dp,
        label = "maydayToggleOffset",
    )
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .width(44.dp)
            .height(28.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onCheckedChange(!checked) },
            ),
        shape = CircleShape,
        color = if (checked) colors.primary else colors.secondaryContainer,
        border = BorderStroke(1.dp, if (checked) colors.primary else colors.outline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = knobOffset.value)
                    .size(18.dp)
                    .background(
                        color = if (checked) {
                            if (colors.background.luminance() < 0.5f) colors.background else Color.White
                        } else {
                            colors.onSurfaceVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
fun MaydayChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = MaterialTheme.shapes.small,
        color = if (selected) colors.surface else colors.secondaryContainer,
        contentColor = if (selected) colors.onSurface else colors.onSurfaceVariant,
        border = BorderStroke(1.dp, colors.outline),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun MaydayIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .size(36.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = if (monospace) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

fun MaydayStatusText(strings: MaydayStrings, status: VpnConnectionStatus): String {
    return when (status) {
        VpnConnectionStatus.Idle -> strings.disconnected
        VpnConnectionStatus.Starting -> strings.connecting
        VpnConnectionStatus.Running -> strings.connected
        VpnConnectionStatus.CoreMissing -> strings.missing
        VpnConnectionStatus.Stopping -> strings.reconnecting
        VpnConnectionStatus.Error -> strings.disconnected
    }
}

@Composable
fun MaydayStatusColor(status: VpnConnectionStatus): Color {
    return when (status) {
        VpnConnectionStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        VpnConnectionStatus.Starting -> MaterialTheme.colorScheme.tertiary
        VpnConnectionStatus.Running -> MaterialTheme.colorScheme.primary
        VpnConnectionStatus.CoreMissing -> MaterialTheme.colorScheme.error
        VpnConnectionStatus.Stopping -> MaterialTheme.colorScheme.secondary
        VpnConnectionStatus.Error -> MaterialTheme.colorScheme.error
    }
}
