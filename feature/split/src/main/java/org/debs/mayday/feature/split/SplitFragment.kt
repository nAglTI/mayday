package org.debs.mayday.feature.split

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.debs.mayday.core.designsystem.theme.MaydayTheme

@AndroidEntryPoint
class SplitFragment : Fragment() {

    private val viewModel: SplitViewModel by viewModels()

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
                    density = state.uiPreferences.density,
                ) {
                    LaunchedEffect(state.shouldClose) {
                        if (state.shouldClose) {
                            viewModel.onCloseHandled()
                            findNavController().popBackStack()
                        }
                    }
                    SplitScreen(
                        state = state,
                        onBackClick = { findNavController().popBackStack() },
                        onModeChanged = viewModel::onModeChanged,
                        onShowSystemAppsChanged = viewModel::onShowSystemAppsChanged,
                        onAppSearchQueryChanged = viewModel::onAppSearchQueryChanged,
                        onPackageToggled = viewModel::onPackageToggled,
                        onSaveClick = viewModel::save,
                        onMessageConsumed = viewModel::onMessageConsumed,
                    )
                }
            }
        }
    }
}
