package org.debs.mayday.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.debs.mayday.core.designsystem.component.MaydayActionButton
import org.debs.mayday.core.designsystem.component.MaydayScreenBackground
import org.debs.mayday.core.designsystem.component.MaydaySectionTitle
import org.debs.mayday.core.designsystem.component.MaydaySurfaceCard
import org.debs.mayday.core.designsystem.component.MaydayTextField
import org.debs.mayday.core.designsystem.theme.LocalMaydayDensity
import org.debs.mayday.core.designsystem.theme.MaydayTheme
import org.debs.mayday.core.designsystem.theme.cancel
import org.debs.mayday.core.designsystem.theme.importClipboard
import org.debs.mayday.core.designsystem.theme.importKey
import org.debs.mayday.core.designsystem.theme.importKeyText
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.designsystem.theme.onboardingClipboardHint
import org.debs.mayday.core.designsystem.theme.onboardingTextImportHint
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.UiPreferences

@Composable
internal fun OnboardingScreen(
    state: OnboardingUiState,
    onEvent: (OnboardingUiEvent) -> Unit,
) {
    val strings = maydayStrings(state.uiPreferences.language)
    val density = LocalMaydayDensity.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showTextImportDialog by remember { mutableStateOf(false) }
    var importKeyInput by remember { mutableStateOf("") }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onEvent(OnboardingUiEvent.MessageShown)
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
                            OnboardingUiEvent.ConfigSelected(
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
                    title = strings.importKey,
                    subtitle = strings.onboardingTextImportHint,
                    badge = "01",
                    onClick = { showTextImportDialog = true },
                )
                OnboardingOptionCard(
                    title = strings.importClipboard,
                    subtitle = strings.onboardingClipboardHint,
                    badge = "02",
                    onClick = { onEvent(OnboardingUiEvent.ImportClipboardClicked) },
                )
                OnboardingOptionCard(
                    title = strings.continueLabel,
                    subtitle = strings.onboardingContinueHint,
                    badge = "03",
                    onClick = { onEvent(OnboardingUiEvent.ContinueClicked) },
                )

                Text(
                    text = if (state.isLoading) strings.preparingWorkspace else "v1.0.0",
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

@Preview(name = "Onboarding / Import Options", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun OnboardingImportOptionsPreview() {
    OnboardingScreenPreview(
        state = OnboardingUiState(
            uiPreferences = previewOnboardingPreferences(
                themeMode = AppThemeMode.DARK,
                language = AppLanguage.EN,
            ),
            isLoading = false,
        ),
    )
}

@Preview(name = "Onboarding / Preparing RU", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun OnboardingPreparingRuPreview() {
    OnboardingScreenPreview(
        state = OnboardingUiState(
            uiPreferences = previewOnboardingPreferences(
                themeMode = AppThemeMode.LIGHT,
                language = AppLanguage.RU,
            ),
            isLoading = true,
        ),
    )
}

@Composable
private fun OnboardingScreenPreview(state: OnboardingUiState) {
    MaydayTheme(
        themeMode = state.uiPreferences.themeMode,
        language = state.uiPreferences.language,
        density = state.uiPreferences.density,
    ) {
        OnboardingScreen(
            state = state,
            onEvent = {},
        )
    }
}

private fun previewOnboardingPreferences(
    themeMode: AppThemeMode,
    language: AppLanguage,
): UiPreferences {
    return UiPreferences(
        themeMode = themeMode,
        language = language,
    )
}
