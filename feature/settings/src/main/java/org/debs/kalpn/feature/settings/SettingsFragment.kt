package org.debs.kalpn.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.debs.kalpn.core.designsystem.theme.KalpnTheme

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()
    private val importConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult

        val context = requireContext()
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
                ?: error("Unable to open the selected config file.")
        }.getOrElse { error ->
            viewModel.showMessage(error.message ?: "Failed to read the selected file.")
            return@registerForActivityResult
        }

        viewModel.importConfig(rawConfig, sourceName)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                KalpnTheme {
                    val state by viewModel.uiState.collectAsState()
                    SettingsScreen(
                        state = state,
                        onProfileNameChanged = viewModel::onProfileNameChanged,
                        onRelayHostChanged = viewModel::onRelayHostChanged,
                        onRelayPortChanged = viewModel::onRelayPortChanged,
                        onUserIdChanged = viewModel::onUserIdChanged,
                        onTunNameChanged = viewModel::onTunNameChanged,
                        onDnsChanged = viewModel::onDnsChanged,
                        onMtuChanged = viewModel::onMtuChanged,
                        onAutoReconnectChanged = viewModel::onAutoReconnectChanged,
                        onSplitTunnelModeChanged = viewModel::onSplitTunnelModeChanged,
                        onShowSystemAppsChanged = viewModel::onShowSystemAppsChanged,
                        onAppSearchQueryChanged = viewModel::onAppSearchQueryChanged,
                        onPackageToggled = viewModel::onPackageToggled,
                        onSaveClick = viewModel::save,
                        onImportClick = {
                            importConfigLauncher.launch(arrayOf("*/*"))
                        },
                        onAddServerClick = viewModel::addServer,
                        onRemoveServerClick = viewModel::removeServer,
                        onServerIdChanged = viewModel::onServerIdChanged,
                        onServerKeyChanged = viewModel::onServerKeyChanged,
                        onServerPriorityChanged = viewModel::onServerPriorityChanged,
                        onMessageConsumed = viewModel::onMessageConsumed,
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
