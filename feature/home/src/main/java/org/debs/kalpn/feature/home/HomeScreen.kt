package org.debs.kalpn.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.debs.kalpn.core.designsystem.component.KalpnBottomActionBar
import org.debs.kalpn.core.designsystem.component.KalpnCard
import org.debs.kalpn.core.designsystem.component.KalpnGradientHeader
import org.debs.kalpn.core.designsystem.component.KalpnScreenBackground
import org.debs.kalpn.core.designsystem.component.StatusBadge
import org.debs.kalpn.core.model.VpnConnectionStatus

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    onConnectClick: () -> Unit,
    onStopClick: () -> Unit,
) {
    val isRunning = state.status == VpnConnectionStatus.Running || state.status == VpnConnectionStatus.Starting
    val actionText = if (isRunning) "Stop VPN" else "Start VPN"
    val actionHint = if (isRunning) {
        "Connection is active. Stop will tear down the VPN shell and foreground service."
    } else {
        "Start the tunnel from this screen. Android may show a VPN permission dialog first."
    }

    KalpnScreenBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            bottomBar = {
                KalpnBottomActionBar(
                    primaryText = actionText,
                    onPrimaryClick = if (isRunning) onStopClick else onConnectClick,
                    supportingText = actionHint,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                KalpnGradientHeader(
                    title = "KALPN control",
                    subtitle = "Monitor the current tunnel state, runtime diagnostics and the active profile from one place.",
                )

                KalpnCard {
                    StatusBadge(status = state.status)
                    Text(
                        text = state.headline.ifBlank { "VPN ready" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = state.detail,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.engineDiagnostics?.let { diagnostics ->
                        Text(
                            text = diagnostics,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!state.engineAvailable) {
                        Text(
                            text = "vpncore.aar is not available for runtime startup. Check native packaging and bridge initialization.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                KalpnCard {
                    Text(
                        text = "Active profile",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Endpoint: ${state.endpointSummary}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "User ID: ${state.userId.ifBlank { "Not set" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = state.serverSummary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = state.splitTunnelSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
