# Spec Delta

## MODIFIED Requirements

### Requirement: Разрез по источникам и разрывам

Отчёт MUST показывать число прослушиваний, оценённых и цензурированных
окончаний по источникам `media_session`, `fyt_service`, `fyt_broadcast`,
`accessibility` и `notification`, а также число событий `capture_gap` и
`media_button`. Этот разрез MUST позволять отличить полные данные от неполных,
не смешивая их в одной метрике.

#### Scenario: Штатный плеер без длительности

- **WHEN** в выгрузке есть accessibility-треки с окончанием `unknown`
- **THEN** отчёт показывает их отдельно от `skipped` и не включает в `skip_rate`

#### Scenario: Разрывы фонового сбора

- **WHEN** в журнале есть `capture_gap` за поездку
- **THEN** отчёт показывает число разрывов и время последнего разрыва

#### Scenario: Сервис метаданных FYT

- **WHEN** треки пришли с `source=fyt_service`
- **THEN** отчёт показывает их отдельной строкой и не смешивает с accessibility

#### Scenario: Нажатия подрулевого

- **WHEN** в журнале есть `media_button`
- **THEN** отчёт показывает число нажатий `next`, `previous` и `play_pause`

## ADDED Requirements

### Requirement: Технические окончания не искажают метрики

При окончаниях `session_lost`, `service_stopped`, `stopped` и `unknown`
отчёт MUST использовать `playedMs`, ограниченный длительностью трека, если она
известна. Если длительность неизвестна, `playedMs` MUST считаться оценочным и
MUST NOT использоваться для `completion` или `skip_rate`. Разница между
исходным и ограниченным значением MUST быть видна в отчёте как цензурированная.

#### Scenario: Трек пережил потерю источника

- **WHEN** `track_ended` содержит `session_lost`, `durationMs=185000` и
  исходное `playedMs=205669`
- **THEN** отчёт использует 185000 мс и показывает прослушивание как цензурированное

#### Scenario: Длительность неизвестна

- **WHEN** трек закрылся с `session_lost`, а `durationMs=null`
- **THEN** `playedMs` показывается как оценка и не входит в `completion` или `skip_rate`

#### Scenario: Обычное завершение

- **WHEN** трек закрылся как `completed`, а `playedMs` не превышает `durationMs`
- **THEN** значение не ограничивается и попадает в метрику как есть
