package org.debs.mayday.feature.home

sealed interface HomeUiEvent {
    data object ConnectClicked : HomeUiEvent
    data object DisconnectClicked : HomeUiEvent
    data object SettingsClicked : HomeUiEvent
    data object StartConfirmed : HomeUiEvent
}

sealed interface HomeUiEffect {
    data object RequestStartFlow : HomeUiEffect
    data object NavigateToSettings : HomeUiEffect
}
