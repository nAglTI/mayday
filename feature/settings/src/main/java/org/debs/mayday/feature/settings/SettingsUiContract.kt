package org.debs.mayday.feature.settings

import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode

sealed interface SettingsUiEvent {
    data object BackClicked : SettingsUiEvent
    data object RefreshRequested : SettingsUiEvent
    data object OpenSplitClicked : SettingsUiEvent
    data object SaveClicked : SettingsUiEvent
    data object ImportClicked : SettingsUiEvent
    data object AddServerClicked : SettingsUiEvent
    data object MessageShown : SettingsUiEvent
    data class ProfileNameChanged(val value: String) : SettingsUiEvent
    data class RelayHostChanged(val value: String) : SettingsUiEvent
    data class RelayPortChanged(val value: String) : SettingsUiEvent
    data class UserIdChanged(val value: String) : SettingsUiEvent
    data class TunNameChanged(val value: String) : SettingsUiEvent
    data class DnsChanged(val value: String) : SettingsUiEvent
    data class MtuChanged(val value: String) : SettingsUiEvent
    data class AutoReconnectChanged(val value: Boolean) : SettingsUiEvent
    data class ThemeModeChanged(val value: AppThemeMode) : SettingsUiEvent
    data class LanguageChanged(val value: AppLanguage) : SettingsUiEvent
    data class DensityChanged(val value: AppDensity) : SettingsUiEvent
    data class RemoveServerClicked(val index: Int) : SettingsUiEvent
    data class ServerIdChanged(
        val index: Int,
        val value: String,
    ) : SettingsUiEvent
    data class ServerKeyChanged(
        val index: Int,
        val value: String,
    ) : SettingsUiEvent
    data class ServerPriorityChanged(
        val index: Int,
        val value: String,
    ) : SettingsUiEvent
    data class ConfigSelected(
        val rawConfig: String,
        val sourceName: String?,
    ) : SettingsUiEvent
    data class ImportSelectionFailed(
        val message: String,
    ) : SettingsUiEvent
}

sealed interface SettingsUiEffect {
    data object NavigateBack : SettingsUiEffect
    data object NavigateToSplit : SettingsUiEffect
    data object OpenConfigPicker : SettingsUiEffect
}
