package org.debs.mayday.feature.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import org.debs.mayday.core.designsystem.theme.MaydayTheme
import org.debs.mayday.core.designsystem.theme.maydayStrings
import androidx.core.net.toUri

@AndroidEntryPoint
class OnboardingFragment : Fragment() {

    private val viewModel: OnboardingViewModel by viewModels()

    private val importConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult

        val context = requireContext()
        val strings = maydayStrings(viewModel.uiState.value.uiPreferences.language)
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        val sourceName = resolveDisplayName(uri)
        val rawConfig = runCatching {
            context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error(strings.unableToOpenSelectedConfigFile)
        }.getOrElse { error ->
            viewModel.onEvent(
                OnboardingUiEvent.ImportSelectionFailed(
                    error.message ?: strings.failedReadSelectedFile,
                ),
            )
            return@registerForActivityResult
        }

        viewModel.onEvent(
            OnboardingUiEvent.ConfigSelected(
                rawConfig = rawConfig,
                sourceName = sourceName,
            ),
        )
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by viewModel.uiState.collectAsState()
                MaydayTheme(
                    themeMode = state.uiPreferences.themeMode,
                    language = state.uiPreferences.language,
                    density = state.uiPreferences.density,
                ) {
                    LaunchedEffect(Unit) {
                        viewModel.effect.collectLatest { effect ->
                            when (effect) {
                                OnboardingUiEffect.OpenConfigPicker -> {
                                    importConfigLauncher.launch(arrayOf("*/*"))
                                }
                                OnboardingUiEffect.NavigateHome -> {
                                    findNavController().navigate(
                                        "mayday://home".toUri(),
                                        NavOptions.Builder()
                                            .setPopUpTo(
                                                findNavController().graph.startDestinationId,
                                                true,
                                            )
                                            .build(),
                                    )
                                }
                                OnboardingUiEffect.NavigateToSettings -> {
                                    findNavController().navigate(
                                        "mayday://settings".toUri(),
                                        NavOptions.Builder()
                                            .setPopUpTo(
                                                findNavController().graph.startDestinationId,
                                                true,
                                            )
                                            .build(),
                                    )
                                }
                            }
                        }
                    }
                    OnboardingScreen(
                        state = state,
                        onEvent = viewModel::onEvent,
                    )
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val resolver = requireContext().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ?: uri.lastPathSegment
    }
}
