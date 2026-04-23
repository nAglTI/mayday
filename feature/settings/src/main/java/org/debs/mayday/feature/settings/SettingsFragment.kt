package org.debs.mayday.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
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
import org.debs.mayday.core.designsystem.theme.maydayStrings

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()

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
                SettingsUiEvent.ImportSelectionFailed(
                    error.message ?: strings.failedReadSelectedFile,
                ),
            )
            return@registerForActivityResult
        }

        viewModel.onEvent(
            SettingsUiEvent.ConfigSelected(
                rawConfig = rawConfig,
                sourceName = sourceName,
            ),
        )
    }

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
                                    findNavController().navigate(Uri.parse("mayday://split"))
                                }
                                SettingsUiEffect.OpenConfigPicker -> {
                                    importConfigLauncher.launch(arrayOf("*/*"))
                                }
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

    private fun resolveDisplayName(uri: Uri): String? {
        val resolver = requireContext().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
            ?: uri.lastPathSegment
    }
}
