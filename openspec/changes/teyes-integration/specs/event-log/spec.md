# Spec Delta

## MODIFIED Requirements

### Requirement: Снимок диагностики

Приложение MUST сохранять JSON-снимок по кнопке и MUST сохранять снимок
автоматически при старте каждого трека. Снимок MUST содержать версию приложения,
модель устройства, версию Android, состояние доступа к уведомлениям,
состояние accessibility-доступа, громкость выбранного источника, все
аудиопотоки, `isVolumeFixed`, `audioMode`, `isMusicActive`, аудиовыходы,
все активные сессии, последние уведомления с признаком `mediaLike`, состояние
штатного плеера с источником и сырыми текстами, состояние вендорного
toolkit и список установленных пакетов Teyes/FYT/лаунчера.

#### Scenario: Первый трек в машине

- **WHEN** штатный плеер начал играть первый трек
- **THEN** рядом с журналом появляется снимок `snapshot-*-track_started.json`

#### Scenario: Громкость на Teyes

- **WHEN** пользователь делает снимок при громкости 4 на панели
- **THEN** снимок содержит `teyes.volume=4`, `teyes.max=43`, `teyes.source=mcu`,
  значения и максимумы всех аудиопотоков и `isVolumeFixed`, даже если
  `STREAM_MUSIC` показывает 25/25

#### Scenario: Штатный плеер без MediaSession

- **WHEN** штатный плеер не отдаёт MediaSession
- **THEN** снимок содержит `stockPlayer` с последним известным названием,
  источником, сырыми текстами accessibility и/или `RemoteViews`, а также
  список пакетов, по которым можно проверить имя плеера

#### Scenario: Неизвестное уведомление

- **WHEN** локальный плеер публикует уведомление без медиашаблона
- **THEN** оно попадает в снимок с `mediaLike = false`, но не создаёт событие
  `notification` в журнале

### Requirement: Диагностика запуска службы

Приложение MUST писать событие `service_started` с источником запуска:
`manual`, `boot`, `job`, `media_mounted`, `app_open`, `teyes_autostart`,
`accessibility` или `notification`. Событие MUST позволять отличить запуск
после загрузки от ручного и от запуска по уведомлению. Неудачная попытка
запуска MUST писать `service_start_failed` с источником и текстом ошибки.

#### Scenario: Запуск после загрузки

- **WHEN** система прислала `BOOT_COMPLETED` и служба стартовала
- **THEN** в журнале есть `boot_received` и `service_started` с
  `source = boot`

#### Scenario: Запуск по отложенной задаче

- **WHEN** persisted JobScheduler сработал после загрузки
- **THEN** в журнале есть `service_started` с `source = job`

#### Scenario: Запуск при монтировании флешки

- **WHEN** система смонтировала USB-накопитель и служба стартовала
- **THEN** в журнале есть `service_started` с `source = media_mounted`

#### Scenario: Ручной запуск

- **WHEN** пользователь нажал «Запустить службу»
- **THEN** в журнале есть `service_started` с `source = manual`

#### Scenario: Запуск по уведомлению

- **WHEN** служба стартовала из обработчика медиа-уведомления
- **THEN** в журнале есть `service_started` с `source = notification`

## ADDED Requirements

### Requirement: Диагностика источников штатного плеера

Приложение MUST писать событие `stock_player_text` при изменении текста,
полученного от штатного плеера, и MUST указывать источник
(`fyt_broadcast`, `accessibility`, `notification`), пакет, название,
исполнителя и сырые тексты. Приложение MUST NOT писать это событие на каждом
тике, если текст не изменился. На устройстве Teyes/FYT приложение MUST писать
`teyes_toolkit` при первом подключении, смене доступности или ошибке
вендорного сервиса.

#### Scenario: Accessibility впервые увидел трек

- **WHEN** accessibility-сервис получил новый набор текстов штатного плеера
- **THEN** в журнале появляется `stock_player_text` с `source=accessibility`
  и сырыми текстами

#### Scenario: Broadcast обновил тот же трек

- **WHEN** broadcast присылает то же название и исполнителя повторно
- **THEN** повторное событие `stock_player_text` не пишется

#### Scenario: MCU-сервис недоступен

- **WHEN** приложение не смогло подключиться к `com.syu.ms`
- **THEN** в журнале появляется `teyes_toolkit` с `available=false` и
  причиной, а служба продолжает работать
