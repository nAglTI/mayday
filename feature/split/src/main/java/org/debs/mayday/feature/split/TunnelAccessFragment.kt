package org.debs.mayday.feature.split

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
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
class TunnelAccessFragment : Fragment() {
    private val viewModel: TunnelAccessViewModel by viewModels()
    private val exportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri ->
        if (uri != null) viewModel.export(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val state by viewModel.uiState.collectAsState()
            MaydayTheme(
                themeMode = state.uiPreferences.themeMode,
                language = state.uiPreferences.language,
                density = state.uiPreferences.density
            ) {
                TunnelAccessScreen(
                    state = state,
                    onBack = { findNavController().popBackStack() },
                    onFilter = viewModel::selectFilter,
                    onRecordingChanged = viewModel::setRecordingEnabled,
                    onClear = viewModel::clear,
                    onExport = { exportDocument.launch("mayday-tunnel-access.jsonl") },
                    onMessageShown = viewModel::messageShown
                )
            }
        }
    }
}
