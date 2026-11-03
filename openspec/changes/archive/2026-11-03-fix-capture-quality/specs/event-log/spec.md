# Spec Delta

## MODIFIED Requirements

### Requirement: Диагностика потери источника

Приложение MUST писать событие `capture_gap` только при реальной потере
источника штатного плеера, когда ни один источник не даёт свежих метаданных.
Событие MUST содержать `active`, причину, последний известный трек (если он
есть; иначе `null`), `isMusicActive` и список видимых окон. Если accessibility
выключена или не подключена, а музыка активна и источника нет, служба
наблюдения MUST писать `capture_gap` с причиной `no_source`. Если окно плеера
скрыто, но `fyt_service` или другой источник активен, приложение MUST NOT
писать `capture_gap`. Событие MUST писаться один раз на границу разрыва и MUST
NOT заменять `track_ended` или `track_progress`.

#### Scenario: Плеер ушёл в фон

- **WHEN** окно плеера перестало быть видимым, музыка играет, а других источников нет
- **THEN** появляется `capture_gap` с `active = true` и последним треком

#### Scenario: Источник ещё не был известен

- **WHEN** музыка играет, источника нет, и последний трек ещё не записан
- **THEN** появляется `capture_gap` с `active = true`, `lastTitle = null` и
  `lastArtist = null`

#### Scenario: Accessibility выключена

- **WHEN** музыка играет, accessibility выключена или не подключена, а других источников нет
- **THEN** появляется `capture_gap` с `active = true` и `reason = no_source`

#### Scenario: Окно скрыто, но источник активен

- **WHEN** окно плеера скрыто, а `fyt_service` продолжает отдавать свежие метаданные
- **THEN** `capture_gap` не пишется, потому что источник не потерян

#### Scenario: Плеер вернулся

- **WHEN** источник снова стал доступен
- **THEN** появляется `capture_gap` с `active = false` и причиной восстановления

## ADDED Requirements

### Requirement: Согласованность границ трека

`track_ended` одного прослушивания MUST содержать тот же `mediaId` и
`durationMs`, что и его `track_started`, если источник отдал их при старте.
Переходный intent со следующим `play_path` MUST NOT подменять поля текущего
трека. Если длительность неизвестна, `durationMs` MUST оставаться `null`.

#### Scenario: Переходный intent со следующим путём

- **WHEN** приходит intent со старым `title`, новым `play_path` и без `play_total`
- **THEN** `track_ended` текущего трека содержит его прежние `mediaId` и `durationMs`, а не поля следующего трека

#### Scenario: Длительность неизвестна

- **WHEN** источник не отдал `durationMs` ни при старте, ни при окончании
- **THEN** `track_ended` содержит `durationMs = null`, а не длительность другого трека

#### Scenario: Пауза

- **WHEN** приходит `play_state=false` для текущего трека
- **THEN** `track_ended` не пишется, а трек остаётся текущим в состоянии `PAUSED`
