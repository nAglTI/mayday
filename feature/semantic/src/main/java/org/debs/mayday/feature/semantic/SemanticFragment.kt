package org.debs.mayday.feature.semantic

import android.content.Intent
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
import androidx.core.content.FileProvider
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import org.debs.mayday.core.designsystem.theme.MaydayTheme
import java.io.File

@AndroidEntryPoint
class SemanticFragment : Fragment() {

    private val viewModel: SemanticViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.onEvent(SemanticUiEvent.RefreshRequested)
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
                                SemanticUiEffect.NavigateBack -> findNavController().popBackStack()
                                is SemanticUiEffect.ShareSemanticReport -> shareSemanticReport(effect)
                            }
                        }
                    }
                    SemanticScreen(
                        state = state,
                        onEvent = viewModel::onEvent,
                    )
                }
            }
        }
    }

    private fun shareSemanticReport(effect: SemanticUiEffect.ShareSemanticReport) {
        val context = requireContext()
        val file = File(effect.absolutePath)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.semantic_file_provider",
            file,
        )
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType(effect.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, effect.fileName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(sendIntent, effect.fileName))
    }
}
