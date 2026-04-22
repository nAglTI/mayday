package org.debs.mayday.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.debs.mayday.core.designsystem.component.MaydayActionButton
import org.debs.mayday.core.designsystem.component.MaydayScreenBackground
import org.debs.mayday.core.designsystem.component.MaydaySectionTitle
import org.debs.mayday.core.designsystem.component.MaydaySurfaceCard
import org.debs.mayday.core.designsystem.theme.LocalMaydayDensity
import org.debs.mayday.core.designsystem.theme.maydayStrings

@Composable
internal fun OnboardingScreen(
    state: OnboardingUiState,
    onImportClick: () -> Unit,
    onManualClick: () -> Unit,
    onContinueClick: () -> Unit,
    onMessageConsumed: () -> Unit,
) {
    val strings = maydayStrings(state.uiPreferences.language)
    val density = LocalMaydayDensity.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onMessageConsumed()
    }

    MaydayScreenBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(innerPadding)
                    .padding(
                        start = density.screenPadding,
                        top = 6.dp,
                        end = density.screenPadding,
                        bottom = density.sectionGap,
                    ),
                verticalArrangement = Arrangement.spacedBy(density.sectionGap),
            ) {
                Text(
                    text = strings.appName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = strings.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = strings.onboardingTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = density.sectionGap),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = strings.onboardingSubtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                MaydaySectionTitle(text = strings.importConfig)

                OnboardingOptionCard(
                    title = strings.importFile,
                    subtitle = "client.yaml / client.json",
                    badge = "01",
                    onClick = onImportClick,
                )
                OnboardingOptionCard(
                    title = strings.manual,
                    subtitle = "relay, user id, servers[]",
                    badge = "02",
                    onClick = onManualClick,
                )
                OnboardingOptionCard(
                    title = strings.continueLabel,
                    subtitle = "open the dashboard without importing now",
                    badge = "03",
                    onClick = onContinueClick,
                )

                Text(
                    text = if (state.isLoading) "Preparing workspace..." else "v1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = density.sectionGap),
                )
            }
        }
    }
}

@Composable
private fun OnboardingOptionCard(
    title: String,
    subtitle: String,
    badge: String,
    onClick: () -> Unit,
) {
    MaydaySurfaceCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MaydayActionButton(
            text = title,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            filled = false,
        )
    }
}
