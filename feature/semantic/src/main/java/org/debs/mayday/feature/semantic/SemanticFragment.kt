package org.debs.mayday.feature.semantic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
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
    private var pendingScanEvent: SemanticUiEvent? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val event = pendingScanEvent
        pendingScanEvent = null
        if (granted && event != null) {
            viewModel.onEvent(event)
        } else {
            viewModel.onEvent(SemanticUiEvent.NotificationPermissionDenied)
        }
    }

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
                        onEvent = ::onSemanticEvent,
                    )
                }
            }
        }
    }

    private fun onSemanticEvent(event: SemanticUiEvent) {
        if (event.requiresNotificationPermission() && shouldRequestNotificationPermission()) {
            pendingScanEvent = event
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        viewModel.onEvent(event)
    }

    private fun shouldRequestNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
    }

    private fun SemanticUiEvent.requiresNotificationPermission(): Boolean {
        return when (this) {
            SemanticUiEvent.ScanAllClicked,
            SemanticUiEvent.ScanSelectedClicked,
            is SemanticUiEvent.ScanAppClicked,
            -> true
            else -> false
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
