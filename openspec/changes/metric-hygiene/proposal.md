# Proposal

## Why

Первый реальный ZIP с магнитолы должен быть пригоден для выводов, а не только
для диагностики. Сейчас в него попадают фантомные прослушивания, если служба
стартовала при плеере на паузе; пауза дольше ~20 секунд может разрезать трек;
mute превращается в громкость 0; обновления прогресса в медиа-уведомлении могут
писать событие каждую секунду; а `skip_rate` считает технические окончания
(`service_stopped`, `session_lost`) наравне с настоящими скипами. На редком
выезде это даст систематически смещённые данные.

## What Changes

- Трек стартует только когда плеер действительно `PLAYING`; короткие
  «прослушивания» без прогресса получают `endReason = not_played` и не входят
  в метрики скипов/завершения.
- Штатный плеер: отсутствующий `play_state` больше не означает «пауза»;
  уведомление не считается всегда играющим; пауза и редкий broadcast не теряют
  трек из-за 15/30-секундных таймаутов.
- Mute сохраняет абсолютную громкость и пишет отдельное `mute_changed`, а не
  подменяет значение на 0.
- NotificationListener пишет вне main-потока, не логирует прогресс в
  `text/subText` и кэширует только медиа-уведомления.
- `report.py` считает `skip_rate` и `completion` по «оценённым» окончаниям
  (`completed + skipped + interrupted`), а технические окончания показывает
  отдельно; кандидаты на удаление используют ту же базу.
- Мелкие меры стоимости: кэш доступа к уведомлениям, стартовый I/O вне main,
  ограничение accessibility двумя пакетами, защита от повторного bind к Teyes,
  подтверждение удаления логов и крупные чипы на экране.

## Capabilities

### New Capabilities

- `listening-metrics`: правила расчёта скипов, завершения и открытых
  прослушиваний по журналу, чтобы отчёт и будущая встроенная статистика
  считали одинаково.

### Modified Capabilities

- `playback-capture`: старт только с `PLAYING`, корректное определение состояния
  штатного плеера, таймауты паузы и уведомления, отсутствие прогресса в
  сигнатуре события.
- `volume-tracking`: mute не меняет абсолютную громкость и фиксируется отдельным
  событием.

## Impact

- `data/ListeningTracker.kt`, `data/MediaSnapshot.kt` — старт только с
  `PLAYING`, `not_played`.
- `data/StockPlayerBridge.kt`, `data/StockPlayerParser.kt` — `playingKnown`,
  `isMusicActive`, новые таймауты.
- `data/VolumeReading.kt`, `data/ListeningTracker.kt` — raw volume и
  `mute_changed`.
- `service/NotificationWatchService.kt`, `probe/NotificationCache.kt` — запись
  вне main, сигнатура без прогресса, фильтр кэша.
- `scripts/report.py`, `scripts/test_report.py` — rated plays, censored
  окончания, кандидаты на удаление.
- `CarMusicMeterApp.kt`, `ui/ProbeScreen.kt`, `service/MediaWatchService.kt`,
  `res/xml/accessibility_service_config.xml`, `TeyesToolkitClient.kt` — стоимость
  и UX.

Новых разрешений и зависимостей нет.
