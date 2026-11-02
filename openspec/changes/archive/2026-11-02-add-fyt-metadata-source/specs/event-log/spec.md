# Spec Delta

## MODIFIED Requirements

### Requirement: Снимок диагностики

Приложение MUST сохранять JSON-снимок по кнопке и MUST сохранять снимок
автоматически при старте каждого трека. Снимок MUST содержать версию приложения,
модель устройства, версию Android, состояние доступа к уведомлениям,
состояние accessibility-доступа (системную настройку и фактическое
подключение сервиса), громкость выбранного источника, все аудиопотоки,
`isVolumeFixed`, `audioMode`, `isMusicActive`, аудиовыходы, все активные
сессии, последние уведомления с признаком `mediaLike`, состояние штатного
плеера с источником и сырыми текстами, состояние сервиса метаданных FYT,
состояние вендорного toolkit и список установленных пакетов Teyes/FYT/лаунчера.

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

#### Scenario: Сервис метаданных FYT

- **WHEN** штатный плеер прислал `com.fyt.launcher.music`
- **THEN** снимок содержит `fytMetadataService.received=true`, пакет, набор
  ключей и время последнего обновления

#### Scenario: Accessibility включена, но сервис не подключён

- **WHEN** системная настройка accessibility включена, а сервис не подключён
- **THEN** снимок содержит `accessibilityEnabled=true` и
  `accessibilityConnected=false`

### Requirement: Диагностика запуска службы

Приложение MUST писать событие `service_started` с источником запуска:
`manual`, `boot`, `job`, `media_mounted`, `app_open`, `teyes_autostart`,
`accessibility`, `notification`, `fyt_service` или `watchdog`. Событие MUST содержать `pid` и
`newProcess`, чтобы отличить новый процесс от повторного `onStartCommand`.
Повторный запуск в том же процессе MUST NOT создавать второе событие.
Неудачная попытка запуска MUST писать `service_start_failed` с источником и
текстом ошибки. Штатная остановка службы MUST писать `service_stopped` с
причиной, `pid` и временем работы.

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

#### Scenario: Запуск watchdog-задачей

- **WHEN** периодическая задача обнаружила, что служба не работает, и запустила её
- **THEN** в журнале есть `service_started` с `source = watchdog`

#### Scenario: Новый процесс

- **WHEN** служба стартовала после убийства процесса
- **THEN** `service_started` содержит новый `pid` и `newProcess=true`

#### Scenario: Штатная остановка

- **WHEN** пользователь остановил службу кнопкой
- **THEN** в журнале есть `service_stopped` с причиной `manual` и временем работы

### Requirement: Диагностика потери источника

Приложение MUST писать событие `capture_gap` при потере и восстановлении
источника штатного плеера. Событие MUST содержать `active`, причину, последний
известный трек (если он есть; иначе `null`), `isMusicActive` и список видимых
окон. Если accessibility выключена или не подключена, а музыка активна и
источника нет, служба наблюдения MUST писать `capture_gap` с причиной
`no_source`. Событие MUST писаться один раз на границу разрыва и MUST NOT
заменять `track_ended` или `track_progress`.

#### Scenario: Плеер ушёл в фон

- **WHEN** окно плеера перестало быть видимым, а музыка играет
- **THEN** появляется `capture_gap` с `active = true` и последним треком

#### Scenario: Источник ещё не был известен

- **WHEN** музыка играет, источника нет, и последний трек ещё не записан
- **THEN** появляется `capture_gap` с `active = true`, `lastTitle = null` и
  `lastArtist = null`

#### Scenario: Accessibility выключена

- **WHEN** музыка играет, accessibility выключена или не подключена, а других источников нет
- **THEN** появляется `capture_gap` с `active = true` и `reason = no_source`

#### Scenario: Плеер вернулся

- **WHEN** источник снова стал доступен
- **THEN** появляется `capture_gap` с `active = false` и причиной восстановления

### Requirement: Диагностика источников штатного плеера

Приложение MUST писать событие `stock_player_text` при изменении текста,
полученного от штатного плеера, и MUST указывать источник
(`fyt_service`, `fyt_broadcast`, `accessibility`, `notification`), пакет,
название, исполнителя и сырые тексты. Приложение MUST NOT писать это событие
на каждом тике, если текст не изменился. На устройстве Teyes/FYT приложение
MUST писать `teyes_toolkit` при первом подключении, смене доступности или
ошибке вендорного сервиса.

#### Scenario: Accessibility впервые увидел трек

- **WHEN** accessibility-сервис получил новый набор текстов штатного плеера
- **THEN** в журнале появляется `stock_player_text` с `source=accessibility`
  и сырыми текстами

#### Scenario: Broadcast обновил тот же трек

- **WHEN** broadcast присылает то же название и исполнителя повторно
- **THEN** повторное событие `stock_player_text` не пишется

#### Scenario: Сервис метаданных FYT обновил трек

- **WHEN** `com.fyt.launcher.music` прислал новый `title` или `play_path`
- **THEN** в журнале появляется `stock_player_text` с `source=fyt_service`

#### Scenario: MCU-сервис недоступен

- **WHEN** приложение не смогло подключиться к `com.syu.ms`
- **THEN** в журнале появляется `teyes_toolkit` с `available=false` и
  причиной, а служба продолжает работать

## ADDED Requirements

### Requirement: Ограничение playedMs на технических окончаниях

При окончаниях `session_lost`, `service_stopped`, `stopped` и `unknown`
приложение MUST писать в `track_ended` `playedMs`, ограниченный `durationMs`,
если длительность известна, и MUST сохранять исходное накопленное значение в
`rawPlayedMs`. Если длительность неизвестна, `playedMs` MUST оставаться
оценочным, а `rawPlayedMs` MUST совпадать с ним. `completed` и `skipped` MUST
NOT использовать `rawPlayedMs`.

#### Scenario: Потеря источника после длинного трека

- **WHEN** трек `Рассвет` закрылся с `session_lost`, `durationMs=185000` и
  накопленным временем 205669 мс
- **THEN** `track_ended` содержит `playedMs=185000` и `rawPlayedMs=205669`

#### Scenario: Длительность неизвестна

- **WHEN** трек закрылся с `session_lost`, а `durationMs=null`
- **THEN** `playedMs` и `rawPlayedMs` равны накопленному времени

#### Scenario: Обычное завершение

- **WHEN** трек закрылся как `completed` и накопленное время меньше длительности
- **THEN** `playedMs` и `rawPlayedMs` равны, ограничение не применяется

### Requirement: Событие media_button

Приложение MUST писать событие `media_button` на каждый полученный
`ACTION_MEDIA_BUTTON` с полями `keyCode`, `action` (`down`/`up`),
`eventTime`, `repeatCount`, `sourcePackage` (если известен), `trackKey`,
`title` и `artist` текущего трека (если он известен). Событие MUST NOT
заменять `track_started`/`track_ended` и MUST NOT влиять на статистику
прослушиваний.

#### Scenario: Следующий трек

- **WHEN** пришёл `KEYCODE_MEDIA_NEXT` с действием `down`
- **THEN** событие содержит `keyCode=87`, `action=down` и текущий `trackKey`

#### Scenario: Нет текущего трека

- **WHEN** нажатие пришло, пока трек неизвестен
- **THEN** событие содержит `trackKey=null`, `title=null`, `artist=null`

#### Scenario: Down и Up

- **WHEN** пришли оба действия одного нажатия
- **THEN** в журнале есть два события `media_button` с разными `action`

### Requirement: Диагностика сервиса метаданных FYT

Приложение MUST писать событие `fyt_metadata_service` при первом получении
метаданных `com.fyt.launcher.music` и при изменении набора extras. Событие MUST
содержать `received=true`, `package`, `keys`, `title`, `artist` и `playing`.
Если intent не получен, приложение MUST NOT писать `received=false` на каждом
тике: отсутствие события означает, что текущий пакет не получает данные.

#### Scenario: Первый intent

- **WHEN** сервис впервые получил `com.fyt.launcher.music`
- **THEN** в журнале появляется `fyt_metadata_service` с `received=true` и
  списком ключей

#### Scenario: Новый набор extras

- **WHEN** в следующем intent появился ключ, которого не было раньше
- **THEN** появляется новое событие `fyt_metadata_service` с обновлённым `keys`

#### Scenario: Пакет не получает данные

- **WHEN** за поездку не пришло ни одного `com.fyt.launcher.music`
- **THEN** событий `fyt_metadata_service` нет, а остальные источники
  продолжают работать

### Requirement: Статус подключения accessibility

Снимок диагностики MUST содержать `accessibilityEnabled` (значение системной
настройки) и `accessibilityConnected` (фактическое подключение сервиса).
Приложение MUST писать `accessibility_state_changed` при изменении настройки,
`accessibility_connected` при подключении сервиса и
`accessibility_disconnected` при отключении. Отключение MUST NOT ронять службу.

#### Scenario: Настройка включена, сервис не подключён

- **WHEN** системная настройка accessibility включена, а сервис не подключён
- **THEN** снимок содержит `accessibilityEnabled=true` и
  `accessibilityConnected=false`

#### Scenario: Сервис подключился

- **WHEN** accessibility-сервис подключился
- **THEN** в журнале появляется `accessibility_connected`

#### Scenario: Система выключила accessibility

- **WHEN** системная настройка сменилась на выключенную
- **THEN** в журнале появляется `accessibility_state_changed` с `enabled=false`

#### Scenario: Сервис отключился

- **WHEN** accessibility-сервис отключился, а служба продолжает работать
- **THEN** в журнале появляется `accessibility_disconnected`, служба не падает

### Requirement: Периодический watchdog службы

Приложение MUST планировать периодическую задачу JobScheduler, которая при
включённом автозапуске проверяет, работает ли `MediaWatchService`, и запускает
её с источником `watchdog`, если служба не работает. Задача MUST NOT запускать
вторую копию службы, MUST NOT мешать работе и MUST логировать только факт
запуска или ошибку.

#### Scenario: Служба убита

- **WHEN** watchdog сработал, а служба не работает
- **THEN** появляется `service_started` с `source=watchdog`

#### Scenario: Служба работает

- **WHEN** watchdog сработал, а служба уже работает
- **THEN** новая служба не запускается и событие не пишется

#### Scenario: Автозапуск выключен

- **WHEN** автозапуск выключен
- **THEN** watchdog ничего не запускает
