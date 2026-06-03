package org.debs.mayday.core.designsystem.theme

import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppRiskFindingType
import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppRiskScanResult
import org.debs.mayday.core.model.AppRiskSignalStrength
import org.debs.mayday.core.model.VpnConnectionStatus

data class MaydayStrings(
    val locale: AppLanguage,
    val appName: String,
    val tagline: String,
    val connect: String,
    val disconnect: String,
    val connecting: String,
    val connected: String,
    val disconnected: String,
    val reconnecting: String,
    val settings: String,
    val theme: String,
    val language: String,
    val density: String,
    val light: String,
    val dark: String,
    val compact: String,
    val comfortable: String,
    val profile: String,
    val splitRouting: String,
    val advanced: String,
    val diagnostics: String,
    val importConfig: String,
    val continueLabel: String,
    val onboardingTitle: String,
    val onboardingSubtitle: String,
    val allTraffic: String,
    val onlySelected: String,
    val exceptSelected: String,
    val apps: String,
    val relay: String,
    val relays: String,
    val relayId: String,
    val relayAddress: String,
    val relayPorts: String,
    val shortId: String,
    val userId: String,
    val dns: String,
    val transport: String,
    val auto: String,
    val tcp: String,
    val utp: String,
    val serverFailbackDelay: String,
    val autoFailover: String,
    val current: String,
    val routingSummary: String,
    val saveProfile: String,
    val status: String,
    val detail: String,
    val engine: String,
    val ready: String,
    val missing: String,
    val servers: String,
    val priority: String,
    val notSet: String,
    val relayNotConfigured: String,
    val profileField: String,
    val port: String,
    val tun: String,
    val mtu: String,
    val keepSessionAliveHint: String,
    val importedFrom: String,
    val relaysHint: String,
    val serversHint: String,
    val addRelay: String,
    val addServer: String,
    val remove: String,
    val server: String,
    val serverId: String,
    val serverKey: String,
    val loading: String,
    val saving: String,
    val preparingWorkspace: String,
    val showSystemApps: String,
    val showSystemAppsHint: String,
    val search: String,
    val noAppsFound: String,
    val noAppsFoundHint: String,
    val noPerAppSelectionHint: String,
    val onboardingContinueHint: String,
    val readSavedRoutingState: String,
    val readSplitRoutingState: String,
    val failedImportConfig: String,
    val atLeastOneRelayRequired: String,
    val atLeastOneServerRequired: String,
    val userIdMustBeNonNegativeInteger: String,
    val relayShortIdsMustBeUnique: String,
    val relayPortsInvalid: String,
    val serverFailbackDelayInvalid: String,
    val serverKeyMustBe64Hex: String,
    val atLeastOneAppRequired: String = "Select at least one app.",
    val profileSaved: String,
    val failedSaveProfile: String,
    val failedSaveRoutingSettings: String,
    val config: String,
)

fun maydayStrings(language: AppLanguage): MaydayStrings {
    return when (language) {
        AppLanguage.RU -> MaydayStrings(
            locale = AppLanguage.RU,
            appName = "mayday",
            tagline = "сетевой клиент",
            connect = "подключиться",
            disconnect = "отключиться",
            connecting = "подключение",
            connected = "подключено",
            disconnected = "отключено",
            reconnecting = "переподключение",
            settings = "настройки",
            theme = "тема",
            language = "язык",
            density = "плотность",
            light = "светлая",
            dark = "тёмная",
            compact = "компактная",
            comfortable = "обычная",
            profile = "профиль",
            splitRouting = "маршрутизация",
            advanced = "расширенные",
            diagnostics = "диагностика",
            importConfig = "импорт конфига",
            continueLabel = "пропустить",
            onboardingTitle = "подключение",
            onboardingSubtitle = "импортируйте ключ, вставьте его из буфера обмена или пропустите этот шаг",
            allTraffic = "весь трафик",
            onlySelected = "только выбранные",
            exceptSelected = "все кроме выбранных",
            apps = "приложения",
            relay = "ретранслятор",
            relays = "ретрансляторы",
            relayId = "ID ретранслятора",
            relayAddress = "адрес ретранслятора",
            relayPorts = "порты",
            shortId = "короткий ID",
            userId = "ID пользователя",
            dns = "dns",
            transport = "транспорт",
            auto = "авто",
            tcp = "tcp",
            utp = "utp",
            serverFailbackDelay = "возврат сервера, сек.",
            autoFailover = "автопереключение",
            current = "активный",
            routingSummary = "режим маршрутизации",
            saveProfile = "сохранить профиль",
            status = "статус",
            detail = "детали",
            engine = "движок",
            ready = "готов",
            missing = "недоступен",
            servers = "серверы",
            priority = "приоритет",
            notSet = "не задано",
            relayNotConfigured = "ретранслятор не настроен",
            profileField = "профиль",
            port = "порт",
            tun = "tun",
            mtu = "mtu",
            keepSessionAliveHint = "сохранять сессию при смене активного сетевого пути",
            importedFrom = "Импортировано из",
            relaysHint = "Адреса ретрансляторов сохранены в конфиге и скрыты в приложении.",
            serversHint = "Ключи серверов сохранены в конфиге и скрыты в приложении. Верхний сервер — приоритет 1.",
            addRelay = "добавить ретранслятор",
            addServer = "добавить сервер",
            remove = "удалить",
            server = "сервер",
            serverId = "ID сервера",
            serverKey = "ключ сервера",
            loading = "загрузка...",
            saving = "сохранение...",
            preparingWorkspace = "Подготавливаем рабочее пространство...",
            showSystemApps = "показывать системные приложения",
            showSystemAppsHint = "включить системные пакеты в список",
            search = "поиск",
            noAppsFound = "Приложения не найдены",
            noAppsFoundHint = "Измените поисковый запрос или включите системные приложения, чтобы увидеть больше пакетов.",
            noPerAppSelectionHint = "В этом режиме отдельный выбор приложений не требуется.",
            onboardingContinueHint = "открыть главный экран без импорта",
            readSavedRoutingState = "Читаем сохранённый режим и список приложений...",
            readSplitRoutingState = "Читаем режим split routing и список приложений из хранилища.",
            failedImportConfig = "Не удалось импортировать конфиг.",
            atLeastOneRelayRequired = "Нужно указать хотя бы один ретранслятор.",
            atLeastOneServerRequired = "Нужно указать хотя бы один сервер.",
            userIdMustBeNonNegativeInteger = "ID пользователя должен быть положительным целым числом.",
            relayShortIdsMustBeUnique = "Короткие ID ретрансляторов должны быть уникальными.",
            relayPortsInvalid = "Порты ретранслятора должны быть числами от 1 до 65535.",
            serverFailbackDelayInvalid = "Возврат сервера должен быть -1, 0 или положительным числом.",
            serverKeyMustBe64Hex = "Ключ сервера должен быть 64-символьной hex-строкой.",
            atLeastOneAppRequired = "Выберите хотя бы одно приложение.",
            profileSaved = "Профиль сохранён",
            failedSaveProfile = "Не удалось сохранить профиль.",
            failedSaveRoutingSettings = "Не удалось сохранить настройки маршрутизации.",
            config = "конфиг",
        )
        AppLanguage.EN -> MaydayStrings(
            locale = AppLanguage.EN,
            appName = "mayday",
            tagline = "network client",
            connect = "connect",
            disconnect = "disconnect",
            connecting = "connecting",
            connected = "connected",
            disconnected = "disconnected",
            reconnecting = "reconnecting",
            settings = "settings",
            theme = "theme",
            language = "language",
            density = "density",
            light = "light",
            dark = "dark",
            compact = "compact",
            comfortable = "comfortable",
            profile = "profile",
            splitRouting = "split routing",
            advanced = "advanced",
            diagnostics = "diagnostics",
            importConfig = "import config",
            continueLabel = "skip",
            onboardingTitle = "get connected",
            onboardingSubtitle = "import a key, paste it from clipboard, or skip this step",
            allTraffic = "all traffic",
            onlySelected = "only selected",
            exceptSelected = "all except selected",
            apps = "apps",
            relay = "relay",
            relays = "relays",
            relayId = "relay id",
            relayAddress = "address",
            relayPorts = "ports",
            shortId = "short id",
            userId = "user id",
            dns = "dns",
            transport = "transport",
            auto = "auto",
            tcp = "tcp",
            utp = "utp",
            serverFailbackDelay = "server failback, sec",
            autoFailover = "auto failover",
            current = "active",
            routingSummary = "routing mode",
            saveProfile = "save profile",
            status = "status",
            detail = "detail",
            engine = "engine",
            ready = "ready",
            missing = "missing",
            servers = "servers",
            priority = "priority",
            notSet = "not set",
            relayNotConfigured = "Relay not configured",
            profileField = "profile",
            port = "port",
            tun = "tun",
            mtu = "mtu",
            keepSessionAliveHint = "keep the session alive when the active path changes",
            importedFrom = "Imported from",
            relaysHint = "Relay addresses are stored in the config and hidden in the app.",
            serversHint = "Server keys are stored in the config and hidden in the app. The top server is priority 1.",
            addRelay = "add relay",
            addServer = "add server",
            remove = "remove",
            server = "server",
            serverId = "server id",
            serverKey = "server key",
            loading = "loading...",
            saving = "saving...",
            preparingWorkspace = "Preparing workspace...",
            showSystemApps = "show system apps",
            showSystemAppsHint = "include platform packages in the list",
            search = "search",
            noAppsFound = "No apps found",
            noAppsFoundHint = "Change the search query or include system apps to see more packages.",
            noPerAppSelectionHint = "No per-app selection is needed in this mode.",
            onboardingContinueHint = "open the dashboard without importing now",
            readSavedRoutingState = "Reading saved routing state...",
            readSplitRoutingState = "Reading split routing mode and installed apps from storage.",
            failedImportConfig = "Failed to import config.",
            atLeastOneRelayRequired = "At least one relay is required.",
            atLeastOneServerRequired = "At least one server is required.",
            userIdMustBeNonNegativeInteger = "User ID must be a positive integer.",
            relayShortIdsMustBeUnique = "Relay short IDs must be unique.",
            relayPortsInvalid = "Relay ports must be numbers from 1 to 65535.",
            serverFailbackDelayInvalid = "Server failback must be -1, 0, or a positive number.",
            serverKeyMustBe64Hex = "Server key must be a 64-character hex string.",
            atLeastOneAppRequired = "Select at least one app.",
            profileSaved = "Profile saved",
            failedSaveProfile = "Failed to save profile.",
            failedSaveRoutingSettings = "Failed to save routing settings.",
            config = "config",
        )
    }
}

fun MaydayStrings.serverCountLabel(count: Int): String {
    return when (locale) {
        AppLanguage.RU -> "$count ${russianServers(count)}"
        AppLanguage.EN -> "$count ${if (count == 1) "server" else "servers"}"
    }
}

fun MaydayStrings.relayCountLabel(count: Int): String {
    return when (locale) {
        AppLanguage.RU -> "$count ${russianRelays(count)}"
        AppLanguage.EN -> "$count ${if (count == 1) "relay" else "relays"}"
    }
}

fun MaydayStrings.serverTitle(index: Int): String {
    return "$server $index"
}

fun MaydayStrings.importedServersMessage(count: Int): String {
    return when (locale) {
        AppLanguage.RU -> "Импортировано $count ${russianServers(count)}"
        AppLanguage.EN -> "Imported ${serverCountLabel(count)}"
    }
}

fun MaydayStrings.importedConfigMessage(sourceName: String): String {
    return when (locale) {
        AppLanguage.RU -> "Импортирован ${sourceName.ifBlank { config }}"
        AppLanguage.EN -> "Imported ${sourceName.ifBlank { config }}"
    }
}

val MaydayStrings.clipboard: String
    get() = when (locale) {
        AppLanguage.RU -> "буфер обмена"
        AppLanguage.EN -> "clipboard"
    }

val MaydayStrings.importClipboard: String
    get() = when (locale) {
        AppLanguage.RU -> "из буфера обмена"
        AppLanguage.EN -> "from clipboard"
    }

val MaydayStrings.importKey: String
    get() = when (locale) {
        AppLanguage.RU -> "по ключу"
        AppLanguage.EN -> "from key"
    }

val MaydayStrings.importKeyText: String
    get() = when (locale) {
        AppLanguage.RU -> "ключ импорта"
        AppLanguage.EN -> "import key"
    }

fun MaydayStrings.updateAvailableTitle(version: String): String {
    return when (locale) {
        AppLanguage.RU -> "Доступна версия $version"
        AppLanguage.EN -> "Version $version is available"
    }
}

val MaydayStrings.updateAvailableBody: String
    get() = when (locale) {
        AppLanguage.RU -> "Вышла новая версия приложения. Можно обновиться с GitHub."
        AppLanguage.EN -> "A new app version is out. You can update from GitHub."
    }

val MaydayStrings.updateNow: String
    get() = when (locale) {
        AppLanguage.RU -> "обновиться"
        AppLanguage.EN -> "update"
    }

val MaydayStrings.dismiss: String
    get() = when (locale) {
        AppLanguage.RU -> "скрыть"
        AppLanguage.EN -> "dismiss"
    }

val MaydayStrings.saveAppSelection: String
    get() = when (locale) {
        AppLanguage.RU -> "сохранить выбор приложений"
        AppLanguage.EN -> "save app selection"
    }

val MaydayStrings.configNeedsNewKeyTitle: String
    get() = when (locale) {
        AppLanguage.RU -> "нужен новый ключ"
        AppLanguage.EN -> "new key required"
    }

val MaydayStrings.configNeedsNewKeyBody: String
    get() = when (locale) {
        AppLanguage.RU -> "Сохранённый конфиг не подходит для текущей версии ядра VPN. Получите новый ключ и импортируйте его."
        AppLanguage.EN -> "The saved config is not compatible with the current VPN core. Get a new key and import it."
    }

val MaydayStrings.advancedDiagnostics: String
    get() = when (locale) {
        AppLanguage.RU -> "расширенная диагностика"
        AppLanguage.EN -> "advanced diagnostics"
    }

val MaydayStrings.showAdvanced: String
    get() = when (locale) {
        AppLanguage.RU -> "показать"
        AppLanguage.EN -> "show"
    }

val MaydayStrings.hideAdvanced: String
    get() = when (locale) {
        AppLanguage.RU -> "скрыть"
        AppLanguage.EN -> "hide"
    }

val MaydayStrings.coreStateLabel: String
    get() = when (locale) {
        AppLanguage.RU -> "состояние ядра"
        AppLanguage.EN -> "core state"
    }

val MaydayStrings.vpnStateLabel: String
    get() = when (locale) {
        AppLanguage.RU -> "состояние туннеля"
        AppLanguage.EN -> "VPN tunnel"
    }

val MaydayStrings.exitServerLabel: String
    get() = when (locale) {
        AppLanguage.RU -> "выходной сервер"
        AppLanguage.EN -> "exit server"
    }

val MaydayStrings.uploadLabel: String
    get() = when (locale) {
        AppLanguage.RU -> "исходящая скорость"
        AppLanguage.EN -> "upload"
    }

val MaydayStrings.downloadLabel: String
    get() = when (locale) {
        AppLanguage.RU -> "входящая скорость"
        AppLanguage.EN -> "download"
    }

val MaydayStrings.totalRateLabel: String
    get() = when (locale) {
        AppLanguage.RU -> "общая скорость"
        AppLanguage.EN -> "total"
    }

val MaydayStrings.protocolsLabel: String
    get() = when (locale) {
        AppLanguage.RU -> "протоколы"
        AppLanguage.EN -> "protocols"
    }

val MaydayStrings.endpointsLabel: String
    get() = when (locale) {
        AppLanguage.RU -> "точки подключения"
        AppLanguage.EN -> "endpoints"
    }

fun MaydayStrings.vpnTunnelStatus(status: VpnConnectionStatus): String {
    return when (locale) {
        AppLanguage.RU -> when (status) {
            VpnConnectionStatus.Idle -> "VPN отключен"
            VpnConnectionStatus.Starting -> "VPN подключается"
            VpnConnectionStatus.Running -> "VPN-туннель активен"
            VpnConnectionStatus.CoreMissing -> "движок VPN недоступен"
            VpnConnectionStatus.Stopping -> "VPN отключается"
            VpnConnectionStatus.Error -> "ошибка подключения"
        }
        AppLanguage.EN -> when (status) {
            VpnConnectionStatus.Idle -> "VPN disconnected"
            VpnConnectionStatus.Starting -> "VPN connecting"
            VpnConnectionStatus.Running -> "VPN tunnel active"
            VpnConnectionStatus.CoreMissing -> "VPN engine missing"
            VpnConnectionStatus.Stopping -> "VPN disconnecting"
            VpnConnectionStatus.Error -> "connection error"
        }
    }
}

fun MaydayStrings.vpnTunnelHeadline(status: VpnConnectionStatus): String {
    return when (locale) {
        AppLanguage.RU -> when (status) {
            VpnConnectionStatus.Idle -> "Готов к подключению"
            VpnConnectionStatus.Starting -> "Проверяем узлы и готовим туннель"
            VpnConnectionStatus.Running -> "VPN-туннель работает"
            VpnConnectionStatus.CoreMissing -> "Движок VPN недоступен"
            VpnConnectionStatus.Stopping -> "Отключаем туннель"
            VpnConnectionStatus.Error -> "Не удалось подключиться"
        }
        AppLanguage.EN -> when (status) {
            VpnConnectionStatus.Idle -> "Ready to connect"
            VpnConnectionStatus.Starting -> "Checking relays and preparing the tunnel"
            VpnConnectionStatus.Running -> "VPN tunnel is active"
            VpnConnectionStatus.CoreMissing -> "VPN engine is unavailable"
            VpnConnectionStatus.Stopping -> "Disconnecting the tunnel"
            VpnConnectionStatus.Error -> "Unable to connect"
        }
    }
}

val MaydayStrings.clipboardEmpty: String
    get() = when (locale) {
        AppLanguage.RU -> "Буфер обмена пуст."
        AppLanguage.EN -> "Clipboard is empty."
    }

val MaydayStrings.cancel: String
    get() = when (locale) {
        AppLanguage.RU -> "отмена"
        AppLanguage.EN -> "cancel"
    }

val MaydayStrings.onboardingClipboardHint: String
    get() = when (locale) {
        AppLanguage.RU -> "ключ импорта из буфера обмена"
        AppLanguage.EN -> "import key from clipboard"
    }

val MaydayStrings.onboardingTextImportHint: String
    get() = when (locale) {
        AppLanguage.RU -> "mayday://import/... или сам ключ"
        AppLanguage.EN -> "mayday://import/... or the key"
    }

val MaydayStrings.vpnRiskScan: String
    get() = when (locale) {
        AppLanguage.RU -> "проверка VPN-слежки"
        AppLanguage.EN -> "VPN tracking check"
    }

val MaydayStrings.vpnRiskDetails: String
    get() = when (locale) {
        AppLanguage.RU -> "признаки риска"
        AppLanguage.EN -> "risk signals"
    }

val MaydayStrings.noRiskSignals: String
    get() = when (locale) {
        AppLanguage.RU -> "признаков VPN-слежки не найдено"
        AppLanguage.EN -> "no VPN tracking signals found"
    }

val MaydayStrings.knownAppGroup: String
    get() = when (locale) {
        AppLanguage.RU -> "группа"
        AppLanguage.EN -> "group"
    }

val MaydayStrings.openAppSettings: String
    get() = when (locale) {
        AppLanguage.RU -> "открыть настройки"
        AppLanguage.EN -> "open settings"
    }

val MaydayStrings.openAppPermissions: String
    get() = when (locale) {
        AppLanguage.RU -> "разрешения"
        AppLanguage.EN -> "permissions"
    }

val MaydayStrings.suggestUninstall: String
    get() = when (locale) {
        AppLanguage.RU -> "предложить удалить"
        AppLanguage.EN -> "suggest uninstall"
    }

val MaydayStrings.hideRiskWarning: String
    get() = when (locale) {
        AppLanguage.RU -> "скрыть предупреждение"
        AppLanguage.EN -> "hide warning"
    }

val MaydayStrings.warningHidden: String
    get() = when (locale) {
        AppLanguage.RU -> "предупреждение скрыто"
        AppLanguage.EN -> "warning hidden"
    }

val MaydayStrings.systemAppNotChecked: String
    get() = when (locale) {
        AppLanguage.RU -> "системное, не проверяется"
        AppLanguage.EN -> "system app, not checked"
    }

val MaydayStrings.pendingRiskScan: String
    get() = when (locale) {
        AppLanguage.RU -> "ожидает проверки"
        AppLanguage.EN -> "waiting for scan"
    }

val MaydayStrings.checkingRiskScan: String
    get() = when (locale) {
        AppLanguage.RU -> "проверяется..."
        AppLanguage.EN -> "checking..."
    }

val MaydayStrings.riskScanComplete: String
    get() = when (locale) {
        AppLanguage.RU -> "проверка завершена"
        AppLanguage.EN -> "scan complete"
    }

val MaydayStrings.restartRiskScan: String
    get() = when (locale) {
        AppLanguage.RU -> "перезапустить проверку"
        AppLanguage.EN -> "restart scan"
    }

val MaydayStrings.blacklistedAppNotChecked: String
    get() = when (locale) {
        AppLanguage.RU -> "в blacklist, не проверяется"
        AppLanguage.EN -> "blacklisted, not checked"
    }

fun MaydayStrings.appRiskLabel(result: AppRiskScanResult): String {
    return when (locale) {
        AppLanguage.RU -> when (result.riskLevel) {
            AppRiskLevel.CRITICAL -> "критично"
            AppRiskLevel.HIGH -> "высокий риск"
            AppRiskLevel.MEDIUM -> "подозрительно"
            AppRiskLevel.LOW -> "без предупреждения"
            AppRiskLevel.CLEAN -> "без признаков"
        }
        AppLanguage.EN -> when (result.riskLevel) {
            AppRiskLevel.CRITICAL -> "critical"
            AppRiskLevel.HIGH -> "high risk"
            AppRiskLevel.MEDIUM -> "suspicious"
            AppRiskLevel.LOW -> "no warning"
            AppRiskLevel.CLEAN -> "clean"
        }
    }
}

fun MaydayStrings.appRiskSummary(result: AppRiskScanResult): String {
    return when (locale) {
        AppLanguage.RU -> when (result.riskLevel) {
            AppRiskLevel.CRITICAL -> "Найден прямой VPN-детект вместе с признаками отправки или блокировки VPN-статуса."
            AppRiskLevel.HIGH -> "Найдена проверяемая связка прямого детекта VPN, Tor, proxy или VPN-приложений."
            AppRiskLevel.MEDIUM -> "Есть ограниченные признаки риска без достаточной связки для высокого уровня."
            AppRiskLevel.LOW -> "Есть только слабые диагностические признаки без предупреждения."
            AppRiskLevel.CLEAN -> noRiskSignals
        }
        AppLanguage.EN -> when (result.riskLevel) {
            AppRiskLevel.CRITICAL -> "Direct VPN detection was found together with VPN-status export or blocking signals."
            AppRiskLevel.HIGH -> "A verifiable direct VPN, Tor, proxy, or VPN-app detection pattern was found."
            AppRiskLevel.MEDIUM -> "Limited risk signals were found without enough correlation for high risk."
            AppRiskLevel.LOW -> "Only weak diagnostic signals were found, with no warning."
            AppRiskLevel.CLEAN -> noRiskSignals
        }
    }
}

fun MaydayStrings.appRiskFindingType(type: AppRiskFindingType): String {
    return when (locale) {
        AppLanguage.RU -> when (type) {
            AppRiskFindingType.ANDROID_API -> "Android API"
            AppRiskFindingType.NETWORK_INTERFACE -> "сетевые интерфейсы"
            AppRiskFindingType.PROXY -> "proxy"
            AppRiskFindingType.LINUX_PROC -> "сетевые таблицы"
            AppRiskFindingType.VPN_APP_DISCOVERY -> "поиск VPN-приложений"
            AppRiskFindingType.TOR -> "Tor"
            AppRiskFindingType.TELEMETRY -> "телеметрия"
            AppRiskFindingType.PACKAGE_VISIBILITY -> "список приложений"
            AppRiskFindingType.NETWORK_PERMISSION -> "сетевое разрешение"
            AppRiskFindingType.ROUTING -> "маршрутизация"
            AppRiskFindingType.DNS -> "DNS"
            AppRiskFindingType.LOCAL_PROXY -> "локальный proxy"
            AppRiskFindingType.XRAY_API -> "Xray API"
            AppRiskFindingType.CLASH_API -> "Clash API"
            AppRiskFindingType.PUBLIC_IP -> "публичный IP"
            AppRiskFindingType.BYPASS -> "обход маршрутизации"
            AppRiskFindingType.ACTIVE_VPN -> "активный VPN"
            AppRiskFindingType.COMBINED -> "связка признаков"
            AppRiskFindingType.NETWORK_LIBRARY -> "сетевая библиотека"
        }
        AppLanguage.EN -> when (type) {
            AppRiskFindingType.ANDROID_API -> "Android API"
            AppRiskFindingType.NETWORK_INTERFACE -> "network interfaces"
            AppRiskFindingType.PROXY -> "proxy"
            AppRiskFindingType.LINUX_PROC -> "network tables"
            AppRiskFindingType.VPN_APP_DISCOVERY -> "VPN app discovery"
            AppRiskFindingType.TOR -> "Tor"
            AppRiskFindingType.TELEMETRY -> "telemetry"
            AppRiskFindingType.PACKAGE_VISIBILITY -> "app visibility"
            AppRiskFindingType.NETWORK_PERMISSION -> "network permission"
            AppRiskFindingType.ROUTING -> "routing"
            AppRiskFindingType.DNS -> "DNS"
            AppRiskFindingType.LOCAL_PROXY -> "local proxy"
            AppRiskFindingType.XRAY_API -> "Xray API"
            AppRiskFindingType.CLASH_API -> "Clash API"
            AppRiskFindingType.PUBLIC_IP -> "public IP"
            AppRiskFindingType.BYPASS -> "routing bypass"
            AppRiskFindingType.ACTIVE_VPN -> "active VPN"
            AppRiskFindingType.COMBINED -> "combined signals"
            AppRiskFindingType.NETWORK_LIBRARY -> "network library"
        }
    }
}

fun MaydayStrings.appRiskSignalStrength(strength: AppRiskSignalStrength): String {
    return when (locale) {
        AppLanguage.RU -> when (strength) {
            AppRiskSignalStrength.LOW -> "слабый"
            AppRiskSignalStrength.MEDIUM -> "средний"
            AppRiskSignalStrength.HIGH -> "сильный"
        }
        AppLanguage.EN -> when (strength) {
            AppRiskSignalStrength.LOW -> "low"
            AppRiskSignalStrength.MEDIUM -> "medium"
            AppRiskSignalStrength.HIGH -> "high"
        }
    }
}

private fun russianServers(count: Int): String {
    val mod10 = count % 10
    val mod100 = count % 100
    return when (mod10) {
        1 if mod100 != 11 -> "сервер"
        in 2..4 if mod100 !in 12..14 -> "сервера"
        else -> "серверов"
    }
}

private fun russianRelays(count: Int): String {
    val mod10 = count % 10
    val mod100 = count % 100
    return when (mod10) {
        1 if mod100 != 11 -> "ретранслятор"
        in 2..4 if mod100 !in 12..14 -> "ретранслятора"
        else -> "ретрансляторов"
    }
}
