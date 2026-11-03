# Proposal

## Why

Логи поездки 2026-11-03 показали, что захват `fyt_service` работает, но качество
записей искажено: 10 из 39 стартов — фантомные треки `Неизвестно`; 11 из 38
окончаний получили `mediaId` следующего трека и `durationMs=null`; 4 активных
`capture_gap` были ложными, потому что `fyt_service` в этот момент продолжал
работать. Плюс трек, стоявший на паузе, остался открытым на 9 часов до
следующего пробуждения службы. Это напрямую искажает `plays`, `skip_rate`,
`completion` и будущие рекомендации «оставить/удалить».

## What Changes

- Игнорировать служебные заголовки (`Неизвестно`, `Unknown`, `unknown` и
  аналогичные), не создавать из них `StockPlayerState` и не писать
  `track_started`/`track_ended`.
- При переходном intent'е с тем же `title`/`artist`, но новым `play_path`,
  `play_state=false` и отсутствующим `play_total` сохранять у текущего трека
  прежние `path`, `mediaId`, `durationMs`, `album` и `positionMs`; менять только
  состояние на `PAUSED`.
- Гарантировать, что `track_ended` одного прослушивания содержит тот же
  `mediaId` и `durationMs`, что и его `track_started`, если источник их отдал.
- Писать `capture_gap` только когда действительно нет свежего источника, а не
  при скрытом окне плеера, если `fyt_service` или другой источник активен.
- Закрывать текущий трек как `session_lost` на времени последнего тика, если
  между тиками прошло больше 1–2 минут; не добавлять этот разрыв в `playedMs`.
- Покрыть сценарии JVM-тестами и обновить документацию/CHANGELOG.

## Capabilities

### New Capabilities

Нет.

### Modified Capabilities

- `playback-capture`: обработка заглушек, переходных intent'ов, потеря окна при
  активном источнике, разрыв между тиками.
- `event-log`: `capture_gap` только при реальной потере источника, согласованность
  `mediaId`/`durationMs` между `track_started` и `track_ended`.

## Impact

- `app/src/main/java/com/example/carmusicmeter/data/StockPlayerParser.kt`
- `app/src/main/java/com/example/carmusicmeter/data/StockPlayerBridge.kt`
- `app/src/main/java/com/example/carmusicmeter/data/ListeningTracker.kt`
- `app/src/main/java/com/example/carmusicmeter/service/StockPlayerAccessibilityService.kt`
- `app/src/main/java/com/example/carmusicmeter/service/MediaWatchService.kt`
- JVM-тесты в `app/src/test/java/com/example/carmusicmeter/data/`
- `openspec/specs/playback-capture/spec.md`, `openspec/specs/event-log/spec.md`
- `docs/HEADUNIT_TEST.md`, `CHANGELOG.md`

Новых разрешений, второго APK, bridge, аудиофокуса и управления плеером нет.
