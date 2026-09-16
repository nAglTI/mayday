package org.debs.mayday.feature.split

import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.TunnelAccessAssessment
import org.debs.mayday.core.model.VpnConnectionStatus

internal class TunnelAccessStrings(private val russian: Boolean) {
    private fun text(ru: String, en: String) = if (russian) ru else en

    val title get() = text("Журнал доступа к туннелю", "Tunnel access journal")
    val recording get() = text("Записывать наблюдения", "Record observations")
    val recordingHint get() = text(
        "Включайте на время диагностики. Запись в фоне; повторы ограничены. Отключение сохраняет историю.",
        "Enable during diagnosis. Recording runs in the background with repeated records limited. Turning it off keeps history."
    )
    val recordingOff get() = text("Запись выключена", "Recording is off")
    val recordingOn get() = text("Запись включена", "Recording is on")
    val waitingForVpn get() = text(
        "Новые наблюдения возможны во время подключения VPN со split-фильтром.",
        "New observations require a VPN connection with a split filter."
    )
    val allAppsMode get() = text(
        "Сохранённый режим всех приложений не использует split-фильтр. Текущая сессия может использовать прежние настройки.",
        "The saved all-app mode does not use a split filter. The current session may use earlier settings."
    )
    val toggleFailed get() = text("Не удалось изменить настройку записи", "Could not change the recording setting")
    val export get() = text("Экспорт JSONL", "Export JSONL")
    val clear get() = text("Очистить", "Clear")
    val cancel get() = text("Отмена", "Cancel")
    val clearTitle get() = text("Очистить журнал?", "Clear the journal?")
    val clearMessage get() = text(
        "Сохранённые наблюдения будут удалены. Новые появятся при работе фильтра, если запись включена.",
        "Stored observations will be deleted. New observations will appear while the filter runs if recording is enabled."
    )
    val cleared get() = text("Журнал очищен", "Journal cleared")
    val clearFailed get() = text("Не удалось очистить журнал", "Could not clear the journal")
    val exported get() = text("Журнал экспортирован", "Journal exported")
    val exportFailed get() = text("Не удалось экспортировать журнал", "Could not export the journal")
    val loading get() = text("Загрузка журнала…", "Loading the journal…")
    val empty get() = text("Наблюдений пока нет", "No observations yet")
    val noMatches get() = text("В этой категории нет наблюдений", "No observations in this category")
    val all get() = text("Все", "All")
    val details get() = text("Подробнее", "Details")
    val hideDetails get() = text("Свернуть", "Hide details")
    val sharedOwner get() = text("Группа приложений с общим UID", "Apps sharing a UID")
    val unknownOwner get() = text("Владелец не определён", "Owner unknown")
    val firstSeen get() = text("Первое", "First")
    val lastSeen get() = text("Последнее", "Last")
    val matchedPackages get() = text("Совпавшие с выбором пакеты", "Packages matching the selection")
    val none get() = text("нет", "none")
    val recentEndpoints get() = text("Последние адреса (до 8)", "Recent endpoints (up to 8)")
    val storageError get() = text(
        "Ошибка файла журнала. Часть наблюдений могла не сохраниться; экспорт может быть неполным.",
        "Journal file error. Some observations may not have been saved; the export may be incomplete."
    )
    val summary get() = text(
        "Здесь — наблюдения владельцев соединений при работе split-фильтра. «Вне правил» означает несоответствие правилам маршрутизации, а не доказанный обход или передачу данных.",
        "These are connection-owner observations from the split filter. “Outside rules” means a routing-policy mismatch, not proof of a bypass or data delivery."
    )
    val technicalDetails get() = text(
        "Запись создаётся при запросе владельца ядром с включённым split-фильтром. В режиме всех приложений таких записей нет. Core 2.1.2 отклоняет неизвестного владельца в обоих режимах; DNS тоже проходит проверку. Для общего UID в белом списке должны быть разрешены все приложения, а в чёрном достаточно одного запрещённого. Журнал не сообщает итог передачи или блокировки. Кэшированные потоки и неподдерживаемые пакеты могут не вызывать resolver. Повторы ограничены интервалом 30 секунд. Это не счётчик пакетов; пустой журнал не доказывает отсутствие доступа. Оценка использует правила на момент наблюдения. На экране — до 1000 записей, экспорт включает сохранённый архив. Старые записи удаляются при ротации. Экспорт содержит имена пакетов, UID, IP-адреса и порты.",
        "A record is created when the core requests an owner with the split filter enabled. All-app mode has no such records. Core 2.1.2 rejects unknown owners in both modes; DNS is checked too. A shared UID requires all its apps to be allowed in a whitelist; any excluded app rejects it in a blacklist. The journal does not report final delivery or blocking. Cached flows and unsupported packets may not call the resolver. Repeats are limited to 30 seconds. This is not a packet counter; an empty journal does not prove no access occurred. Assessments use the rules at observation time. The screen shows up to 1,000 records; exports include the retained archive. Rotation removes old records. Exports include package names, UIDs, IP addresses and ports."
    )
    val sharedDetails get() = text(
        "Android связал соединение с общим UID. Список показывает возможных владельцев; конкретное приложение не определено.",
        "Android associated the connection with a shared UID. These are possible owners; no individual app has been identified."
    )
    val mismatchDetails get() = text(
        "Resolver увидел владельца, который не соответствует правилам этой VPN-сессии. Результат обработки соединения здесь не фиксируется.",
        "The resolver observed an owner outside this VPN session’s routing policy. This journal does not record the connection’s final outcome."
    )
    val matchDetails get() = text(
        "Владелец соответствует правилам этой VPN-сессии. Это не подтверждение передачи данных.",
        "The owner matches this VPN session’s routing policy. This does not confirm data delivery."
    )
    val unknownDetails get() = text(
        "Android не вернул надёжного владельца соединения; сравнить приложение с правилами невозможно.",
        "Android did not return a reliable connection owner; its app cannot be compared with the rules."
    )

    fun observations(count: Int) = text("Наблюдений: $count", "Observations: $count")
    fun retained(count: Int) = text("На экране наблюдений: $count", "Observations on screen: $count")
    fun dropped(count: Long) = text("Пропущено при записи: $count", "Observations dropped while recording: $count")
    fun savedMode(value: SplitTunnelMode) = text("Сохранённый режим: ${mode(value)}", "Saved mode: ${mode(value)}")
    fun eventMode(value: SplitTunnelMode) = text("Режим наблюдения: ${mode(value)}", "Observation mode: ${mode(value)}")
    fun vpnStatus(value: VpnConnectionStatus): String {
        val status = when (value) {
            VpnConnectionStatus.Idle -> text("отключён", "off")
            VpnConnectionStatus.Starting -> text("подключается", "starting")
            VpnConnectionStatus.Running -> text("подключён", "connected")
            VpnConnectionStatus.CoreMissing -> text("ядро недоступно", "core unavailable")
            VpnConnectionStatus.Stopping -> text("отключается", "stopping")
            VpnConnectionStatus.Error -> text("ошибка", "error")
        }
        return "VPN: $status"
    }

    fun assessment(value: TunnelAccessAssessment) = when (value) {
        TunnelAccessAssessment.POLICY_MATCH -> text("По правилам", "Matches rules")
        TunnelAccessAssessment.POLICY_MISMATCH -> text("Вне правил", "Outside rules")
        TunnelAccessAssessment.UNKNOWN_OWNER -> text("Неизвестные", "Unknown")
        TunnelAccessAssessment.SHARED_UID -> text("Общий UID", "Shared UID")
    }

    private fun mode(value: SplitTunnelMode) = when (value) {
        SplitTunnelMode.DISABLED -> text("все приложения", "all apps")
        SplitTunnelMode.ONLY_SELECTED -> text("только выбранные", "selected apps only")
        SplitTunnelMode.EXCLUDE_SELECTED -> text("кроме выбранных", "except selected apps")
    }
}

internal fun tunnelAccessStrings(language: AppLanguage) = TunnelAccessStrings(language == AppLanguage.RU)
