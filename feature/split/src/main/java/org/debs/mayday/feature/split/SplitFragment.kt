package org.debs.mayday.feature.split

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import kotlinx.coroutines.flow.collectLatest
import org.debs.mayday.core.designsystem.theme.MaydayTheme

@AndroidEntryPoint
class SplitFragment : Fragment() {

    private val viewModel: SplitViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.onEvent(SplitUiEvent.RefreshRequested)
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
                                SplitUiEffect.NavigateBack -> findNavController().popBackStack()
                                is SplitUiEffect.OpenAppSettings -> openAppSettings(effect.packageName)
                                is SplitUiEffect.OpenAppPermissions -> openAppSettings(effect.packageName)
                                is SplitUiEffect.RequestAppUninstall -> requestAppUninstall(effect.packageName)
                            }
                        }
                    }
                    SplitScreen(
                        state = state,
                        onEvent = viewModel::onEvent,
                    )
                }
            }
        }
    }

    private fun openAppSettings(packageName: String) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        startActivity(intent)
    }

    private fun requestAppUninstall(packageName: String) {
        val intent = Intent(
            Intent.ACTION_DELETE,
            Uri.fromParts("package", packageName, null),
        )
        startActivity(intent)
    }
}
