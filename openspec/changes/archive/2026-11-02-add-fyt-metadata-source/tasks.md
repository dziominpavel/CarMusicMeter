# Tasks

## 1. Данные, парсер и мост

- [x] 1.1 Добавить `StockPlayerSources.FYT_SERVICE`, параметр `source` в `StockPlayerParser.fromBroadcast` и поставить `com.syu.music` первым в `StockPlayerProtocol.PACKAGES`; проверить `gradlew.bat :app:testDebugUnitTest`.
- [x] 1.2 Добавить в `StockPlayerBridge` приоритет `fyt_service` выше `fyt_broadcast`/`accessibility`/`notification`, grace 10 секунд и stale 30 секунд для playing / 10 минут для paused; покрыть тестами смену источника без второго `track_started`.
- [x] 1.3 Ограничить `playedMs` на технических окончаниях в `ListeningTracker.endEvent`, добавить `rawPlayedMs`; проверить тестами `session_lost`, `unknown` и обычный `completed`.

## 2. Один APK и прямой сервис metadata

- [x] 2.1 Поставить `applicationId=com.syu.widget.music`, добавить exported `FytMetadataService` на action `com.fyt.launcher.music`, обновление `StockPlayerBridge`/`FytMetadataStatus`, событие `fyt_metadata_service` и `StartSource.FYT_SERVICE`.
- [x] 2.2 Убедиться, что в проекте нет второго APK, bridge, signature-permission и лишнего receiver; остался только основной app.
- [x] 2.3 Собрать `gradlew.bat :app:assembleDebug` и проверить, что один APK содержит exported-сервис `com.fyt.launcher.music`.

## 3. Media buttons

- [x] 3.1 Вынести разбор `KeyEvent` в чистую модель и писать `media_button` с `keyCode`, `action`, `eventTime`, `repeatCount`, `sourcePackage`; проверить unit-тестами `NEXT`, `PREVIOUS`, `PLAY_PAUSE`, `down`/`up`.
- [x] 3.2 Зарегистрировать динамический receiver `ACTION_MEDIA_BUTTON` в `MediaWatchService` и снимать его в `onDestroy`; проверить сборку и отсутствие краша при старте/остановке службы.

## 4. Диагностика accessibility и разрывов

- [x] 4.1 Добавить `AccessibilityConnection` (`connected`, `lastScanAt` с heartbeat 10 секунд), события `accessibility_connected`/`accessibility_disconnected` и `accessibility_state_changed`; проверить unit-тестом переходов и полями снимка.
- [x] 4.2 Убрать условие `latest != null` из `capture_gap` в `StockPlayerAccessibilityService`, добавить `capture_gap reason=no_source` из `MediaWatchService`, когда accessibility выключена/не подключена; проверить тестом `lastTitle=null` и отсутствие дублей на границе.
- [x] 4.3 Добавить в `SnapshotWriter` поля `accessibilityConnected` и `fytMetadataService` (`received`, `package`, `keys`, `updatedAt`); проверить `SnapshotDiagnosticsTest`.

## 5. Жизненный цикл службы

- [x] 5.1 Добавить `pid`/`newProcess` в `service_started` и событие `service_stopped`; проверить unit-тестом, что повторный `onStartCommand` не пишет второе событие.
- [x] 5.2 Добавить периодический watchdog-job (ID 4202, 15 минут), `StartSource.WATCHDOG` и различение job по ID; проверить сборку и тестом, что при работающей службе второй запуск не происходит.

## 6. UI и отчёт

- [x] 6.1 Показать в `ProbeScreen` источник штатного плеера, `accessibilityEnabled`/`accessibilityConnected` и предупреждение «музыка играет, источника нет»; проверить сборкой `gradlew.bat :app:assembleDebug`.
- [x] 6.2 Добавить в `scripts/report.py` счётчики `media_button` (next/previous/play_pause), причины `capture_gap` (включая `no_source`) и показ `rawPlayedMs`; проверить `py scripts/test_report.py`.
- [x] 6.3 Обновить `scripts/test_report.py` тестами на `fyt_service`, `media_button`, `capture_gap/no_source` и ограничение `playedMs`; проверить `py scripts/test_report.py`.

## 7. Сборка, документация, changelog

- [x] 7.1 Собрать один release APK, проверить `dist\CarMusicMeter-release-latest.apk` и отсутствие второго APK; проверить, что `release.ps1` публикует один ассет.
- [x] 7.2 Обновить `docs/HEADUNIT_TEST.md` установкой одного APK и сценарием одного выезда: навигатор на весь экран, плеер в фоне, Next/Prev, пауза, естественное окончание, экспорт ZIP.
- [x] 7.3 Добавить `docs/LOG_ANALYSIS_2026-11-02.md` с разбором утренней/вечерней поездки, `fyt_service`/`media_button`/`capture_gap` и ограничением `playedMs`.
- [x] 7.4 Добавить запись в `CHANGELOG.md` в `[Unreleased]` (пользовательский блок **Добавлено**/**Исправлено**, без бэктиков) и проверить формат по `docs/versioning.md`.

## 8. Интеграция и один выезд

- [ ] 8.1 Собрать release, установить один APK, удалить старую версию `com.example.carmusicmeter`, выдать разрешения и добавить приложение в автозапуск Teyes.
- [ ] 8.2 Провести один выезд по сценарию: навигатор на весь экран, плеер в фоне, несколько Next/Prev с подрулевого, пауза/продолжение, естественное окончание; выгрузить ZIP.
- [ ] 8.3 Проверить в ZIP: `fyt_metadata_service`, `stock_player_text` с `source=fyt_service`, `media_button` с `keyCode=87/88/85`, `capture_gap` с `reason=no_source` при пропаже источника и отсутствие дублей `track_started`.
- [ ] 8.4 Если `fyt_metadata_service` нет, сменить `applicationId` в `app/build.gradle.kts` на `com.syu.screensaver` или `cn.teyes.online`, пересобрать один APK и повторить выезд.
- [ ] 8.5 Проверить отчёт: `py scripts/report.py <zip>` показывает `fyt_service`, `media_button`, `capture_gap` и не раздувает `playedMs` у `session_lost`.
- [ ] 8.6 Проверить rollback: удалить APK, убедиться, что данные остаются до удаления и второго пакета больше нет.

## Workflow follow-up

- После проверки в машине и review — архивировать change и синхронизировать specs.
- Перед архивом выполнить `openspec validate --all` и `py scripts/check-version.py`.
