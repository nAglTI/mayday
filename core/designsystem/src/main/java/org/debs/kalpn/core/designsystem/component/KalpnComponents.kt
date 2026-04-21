package org.debs.kalpn.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.debs.kalpn.core.model.VpnConnectionStatus

@Composable
fun KalpnGradientHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val headerContentColor = MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = headerContentColor,
        ),
        shape = RoundedCornerShape(30.dp),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ),
                )
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = headerContentColor,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = headerContentColor.copy(alpha = 0.76f),
            )
        }
    }
}

@Composable
fun KalpnCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun KalpnPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        contentPadding = PaddingValues(vertical = 14.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(text = text)
    }
}

@Composable
fun KalpnScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val isDark = background.luminance() < 0.5f
    val primaryGlow = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.16f else 0.12f)
    val tertiaryGlow = MaterialTheme.colorScheme.tertiary.copy(alpha = if (isDark) 0.14f else 0.08f)

    Box(
        modifier = modifier.drawBehind {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        background,
                        surfaceVariant.copy(alpha = if (isDark) 0.42f else 0.68f),
                    ),
                ),
            )
            drawCircle(
                color = primaryGlow,
                radius = size.maxDimension * 0.34f,
                center = Offset(size.width * 0.12f, size.height * 0.08f),
            )
            drawCircle(
                color = tertiaryGlow,
                radius = size.maxDimension * 0.30f,
                center = Offset(size.width * 0.90f, size.height * 0.88f),
            )
        },
        content = content,
    )
}

@Composable
fun KalpnBottomActionBar(
    primaryText: String,
    onPrimaryClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
            supportingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            KalpnPrimaryButton(
                text = primaryText,
                onClick = onPrimaryClick,
                enabled = enabled,
            )
        }
    }
}

@Composable
fun StatusBadge(
    status: VpnConnectionStatus,
    modifier: Modifier = Modifier,
) {
    val color = when (status) {
        VpnConnectionStatus.Idle -> MaterialTheme.colorScheme.outline
        VpnConnectionStatus.Starting -> MaterialTheme.colorScheme.primary
        VpnConnectionStatus.Running -> MaterialTheme.colorScheme.tertiary
        VpnConnectionStatus.CoreMissing -> MaterialTheme.colorScheme.error
        VpnConnectionStatus.Stopping -> MaterialTheme.colorScheme.secondary
        VpnConnectionStatus.Error -> MaterialTheme.colorScheme.error
    }

    val label = when (status) {
        VpnConnectionStatus.Idle -> "Idle"
        VpnConnectionStatus.Starting -> "Starting"
        VpnConnectionStatus.Running -> "Running"
        VpnConnectionStatus.CoreMissing -> "Core missing"
        VpnConnectionStatus.Stopping -> "Stopping"
        VpnConnectionStatus.Error -> "Error"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color = color, shape = CircleShape),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
