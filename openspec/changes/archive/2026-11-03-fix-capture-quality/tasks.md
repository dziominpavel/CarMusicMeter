# Tasks

## 1. Служебные заглушки

- [x] 1.1 Добавить в `StockPlayerParser` общий предикат заглушек (`Неизвестно`, `Unknown`, `unknown`, `Неизвестный`, `Без названия`, `null`, `-`) и применять его к `title`, `artist`, `album`; проверить, что `fromBroadcast` возвращает `null` для `Неизвестно`
- [x] 1.2 Добавить JVM-тесты парсера на `Неизвестно`, `Unknown`, пустой `title` и нормальный трек; проверить `gradlew.bat :app:testDebugUnitTest`

## 2. Переходный intent и границы трека

- [x] 2.1 Добавить в `StockPlayerBridge.update()` склейку переходного intent'а: тот же `title`/`artist`, другой `path`, `playing=false` или `durationMs=null`; сохранять старые `path`, `durationMs`, `album`, `positionMs`, `rawTexts`, `extras`; проверить bridge-тестом
- [x] 2.2 Добавить в `ListeningTracker` страховку: при `sameTrack()` и неполном snapshot (`durationMs=null` или `mediaId=null`) сохранять поля из текущего snapshot; проверить tracker-тестом
- [x] 2.3 Добавить сквозной tracker-тест: старт A с duration/mediaId, переходный snapshot A с путём B и `durationMs=null`, затем старт B; проверить, что `track_ended` A содержит `mediaId`/`durationMs` трека A

## 3. Ложные capture_gap

- [x] 3.1 В `StockPlayerAccessibilityService` не писать `stock_window_unavailable`, если `StockPlayerBridge.activeState()` даёт свежий источник; при ранее открытом gap писать восстановление `source_available`; проверить unit-тестом политики gap
- [x] 3.2 Ограничить `StockPlayerBridge.touch()` состоянием `accessibility`, чтобы `fyt_service` не считался свежим без собственных обновлений; проверить bridge-тестом
- [x] 3.3 Проверить, что при скрытом окне и активном `fyt_service` событие `capture_gap` не пишется; проверить unit-тестом/ручным сценарием

## 4. Разрыв между тиками

- [x] 4.1 Добавить в `ListeningTracker.Active` поле `lastTickWallMs` и guard `MAX_TICK_GAP_MS = 120_000`; при разрыве закрывать трек как `session_lost` на времени последнего тика и не добавлять разрыв в `playedMs`; проверить tracker-тестом
- [x] 4.2 Добавить тесты: сон на паузе, сон во время игры, обычный тик меньше 2 минут; проверить `gradlew.bat :app:testDebugUnitTest`

## 5. Документация и релизный артефакт

- [x] 5.1 Добавить пользовательские пункты в `CHANGELOG.md` и обновить `docs/HEADUNIT_TEST.md`/`docs/LOG_ANALYSIS_2026-11-03.md`; проверить `py scripts/check-version.py`
- [x] 5.2 Прогнать `powershell -ExecutionPolicy Bypass -File scripts\check.ps1` и `openspec validate --all`; проверить, что ошибок нет
- [x] 5.3 Собрать один APK командой `build-apk.bat release`; проверить `dist\CarMusicMeter-release-latest.apk`, пакет `com.syu.widget.music` и наличие `com.fyt.launcher.music`
- [ ] 5.4 Проверить в машине один выезд по `docs/HEADUNIT_TEST.md` раздел 6; проверить ZIP на отсутствие `Неизвестно`, корректные `mediaId`/`durationMs` и отсутствие ложных `capture_gap` (требует магнитолы)

## Workflow follow-up

- Синхронизировать delta-спеки с основными после проверки в машине.
- Заархивировать change после выполнения 5.4 или зафиксировать её как отложенную физическую проверку.
