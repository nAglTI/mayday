package org.debs.mayday.feature.onboarding

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
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
import org.debs.mayday.core.designsystem.theme.clipboard
import org.debs.mayday.core.designsystem.theme.clipboardEmpty
import org.debs.mayday.core.designsystem.theme.maydayStrings
import androidx.core.net.toUri

@AndroidEntryPoint
class OnboardingFragment : Fragment() {

    private val viewModel: OnboardingViewModel by viewModels()

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
                                OnboardingUiEffect.ImportFromClipboard -> importConfigFromClipboard()
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

    private fun importConfigFromClipboard() {
        val context = requireContext()
        val strings = maydayStrings(viewModel.uiState.value.uiPreferences.language)
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val rawConfig = runCatching {
            clipboardManager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                ?.trim()
        }.getOrNull()

        if (rawConfig.isNullOrBlank()) {
            viewModel.onEvent(OnboardingUiEvent.ImportSelectionFailed(strings.clipboardEmpty))
            return
        }

        viewModel.onEvent(
            OnboardingUiEvent.ConfigSelected(
                rawConfig = rawConfig,
                sourceName = strings.clipboard,
            ),
        )
    }
}
