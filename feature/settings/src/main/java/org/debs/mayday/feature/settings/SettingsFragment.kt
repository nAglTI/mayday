package org.debs.mayday.feature.settings

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import org.debs.mayday.core.designsystem.theme.MaydayTheme
import org.debs.mayday.core.designsystem.theme.clipboard
import org.debs.mayday.core.designsystem.theme.clipboardEmpty
import org.debs.mayday.core.designsystem.theme.maydayStrings
import androidx.core.net.toUri

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.onEvent(SettingsUiEvent.RefreshRequested)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
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
                                SettingsUiEffect.NavigateBack -> findNavController().popBackStack()
                                SettingsUiEffect.NavigateToSplit -> {
                                    findNavController().navigate("mayday://split".toUri())
                                }
                                SettingsUiEffect.NavigateToSemantic -> {
                                    findNavController().navigate("mayday://semantic".toUri())
                                }
                                SettingsUiEffect.ImportFromClipboard -> importConfigFromClipboard()
                            }
                        }
                    }
                    SettingsScreen(
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
            viewModel.onEvent(SettingsUiEvent.ImportSelectionFailed(strings.clipboardEmpty))
            return
        }

        viewModel.onEvent(
            SettingsUiEvent.ConfigSelected(
                rawConfig = rawConfig,
                sourceName = strings.clipboard,
            ),
        )
    }
}
