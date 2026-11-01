## ADDED Requirements

### Requirement: Разрез по источникам и разрывам

Отчёт MUST показывать число прослушиваний, оценённых и цензурированных
окончаний по источникам `media_session`, `accessibility`, `fyt_broadcast` и
`notification`, а также число событий `capture_gap`. Этот разрез MUST позволять
отличить полные данные от неполных, не смешивая их в одной метрике.

#### Scenario: Штатный плеер без длительности

- **WHEN** в выгрузке есть accessibility-треки с окончанием `unknown`
- **THEN** отчёт показывает их отдельно от `skipped` и не включает в `skip_rate`

#### Scenario: Разрывы фонового сбора

- **WHEN** в журнале есть `capture_gap` за поездку
- **THEN** отчёт показывает число разрывов и время последнего разрыва

## MODIFIED Requirements

### Requirement: Оценённые окончания

`skip_rate` и `completion` MUST считаться только по оценённым окончаниям:
`completed`, `skipped`, `interrupted`. Окончания `service_stopped`,
`session_lost`, `not_played`, `stopped` и `unknown` MUST NOT входить в
знаменатель и MUST показываться отдельно как цензурированные.

#### Scenario: Трек часто играет при выключении зажигания

- **WHEN** у трека один `skipped` и три `service_stopped`
- **THEN** `skip_rate` считается по одному оценённому окончанию, а не по четырём

#### Scenario: Техническая потеря сессии

- **WHEN** прослушивание закончилось `session_lost`
- **THEN** оно не ухудшает `completion` и не считается скипом

#### Scenario: Штатный плеер без длительности

- **WHEN** accessibility-трек закончился с `endReason = unknown`
- **THEN** он не попадает в `skip_rate` и `completion`, а показывается как неизвестное окончание
