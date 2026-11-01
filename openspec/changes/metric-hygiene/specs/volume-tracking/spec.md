# Spec Delta

## ADDED Requirements

### Requirement: Mute не меняет абсолютную громкость

Приложение MUST хранить абсолютное значение громкости при `muted = true` и MUST
писать отдельное событие `mute_changed` при изменении mute. Приложение MUST NOT
подменять значение громкости нулём и MUST NOT создавать ложный
`volume_changed` при mute/unmute.

#### Scenario: Mute на громкости 30

- **WHEN** пользователь включает mute при громкости 30
- **THEN** `mute_changed` содержит `muted = true` и `volume = 30`; событие
  `volume_changed` с `value = 0` не пишется

#### Scenario: Возврат из mute

- **WHEN** пользователь выключает mute
- **THEN** появляется `mute_changed` с `muted = false`, а ложного скачка
  громкости на +30 нет
