## ADDED Requirements

### Requirement: Смена источника громкости

При смене источника громкости приложение MUST писать событие
`volume_source_changed` с полями `from`, `to`, `fromValue`, `toValue`,
`fromMax`, `toMax`, `sourceReason`, `trackKey`, `title`. Событие MUST NOT
содержать ложный `delta` и MUST NOT заменяться обычным `volume_changed`.
Причина MUST различать штатную смену, недоступность MCU, устаревание и ошибку
чтения.

#### Scenario: MCU перестал отвечать

- **WHEN** в середине трека MCU перестал отдавать громкость и приложение перешло на `AudioManager`
- **THEN** появляется `volume_source_changed` с `from = mcu`, `to = audio` и причиной `mcu_unavailable`

#### Scenario: MCU вернулся

- **WHEN** вендорный сервис снова отдал громкость
- **THEN** появляется `volume_source_changed` с `from = audio`, `to = mcu` и причиной `mcu_available`

#### Scenario: Ложный скачок

- **WHEN** источник сменился со значения 3/43 на 25/25
- **THEN** событие `volume_changed` с `delta = 22` не пишется

## MODIFIED Requirements

### Requirement: Статистика по треку

При закрытии трека приложение MUST сохранять начальную, минимальную,
максимальную и конечную громкость, максимум источника и число изменений
отдельно для каждого источника. Переключение источника MUST NOT стирать
статистику предыдущего источника. Событие `track_ended` MUST содержать
`volumeStats` с записью по каждому источнику; верхнеуровневые
`volumeStart`/`volumeEnd`/`volumeLow`/`volumeHigh` MAY отражать последний
сегмент. Событие `track_progress` MUST содержать `volumeSource` и `volumeMax`.

#### Scenario: Трек с несколькими изменениями

- **WHEN** за трек громкость менялась три раза
- **THEN** событие `track_ended` содержит `volumeChanges = 3` и корректные `volumeLow`/`volumeHigh`

#### Scenario: MCU и audio в одном треке

- **WHEN** трек начался на MCU, затем MCU пропал и вернулся
- **THEN** `volumeStats.mcu` и `volumeStats.audio` содержат отдельные диапазоны, а статистика MCU не обнулена
