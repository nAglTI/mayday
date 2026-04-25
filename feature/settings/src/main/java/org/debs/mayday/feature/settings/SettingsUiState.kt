package org.debs.mayday.feature.settings

import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.UiPreferences

data class SettingsUiState(
    val uiPreferences: UiPreferences = UiPreferences(),
    val profileName: String = "",
    val relays: List<RelayDraft> = listOf(RelayDraft()),
    val userId: String = "",
    val servers: List<ServerDraft> = listOf(ServerDraft()),
    val tunName: String = "",
    val dnsServers: String = "1.1.1.1",
    val mtu: String = "1420",
    val autoReconnect: Boolean = true,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val selectedPackageCount: Int = 0,
    val isLoading: Boolean = true,
    val importedConfigName: String? = null,
    val message: String? = null,
) {
    val themeMode: AppThemeMode get() = uiPreferences.themeMode
    val language: AppLanguage get() = uiPreferences.language
    val density: AppDensity get() = uiPreferences.density
}
