# Proposal

## Why

Выгрузка 02.11.2026 показала: утром записано 13 треков, вечером — 0, хотя вечером
было 94 события громкости и музыка играла. Снимок 18:47:31 содержит
`isMusicActive=true`, `serviceRunning=true`, `accessibilityEnabled=true`, но
`stockPlayer=null`, `stockPlayerUi=null`, `sessions=[]`, `notifications=[]`.
Причина не в парсере: все 187 снимков UI приняты, 13 уникальных названий дали
13 `track_started`. Причина в том, что у приложения не было источника данных:
accessibility видит плеер только когда его окно на экране, а FYT отключает
accessibility при глубоком сне и перезагрузке. Навигатор на весь экран делает
плеер невидимым, и сбор слепнет на всю поездку.

Тестировать можно редко — примерно раз в сутки. Поэтому изменение должно за один
выезд проверить все реальные источники, а не растягиваться на серию догадок.

## What Changes

- Добавить прямой источник метаданных FYT: exported-сервис с action
  `com.fyt.launcher.music`, который принимает `title`, `play_artist`,
  `play_album`, `play_path`, `play_state`, `play_total`, `play_cur` и отдаёт их
  в `StockPlayerBridge`. Штатный плеер отправляет эти данные примерно раз в
  секунду, в том числе когда его окно скрыто навигатором.
- Добавить пассивный приём `ACTION_MEDIA_BUTTON` в `MediaWatchService`, чтобы
  фиксировать нажатия подрулевого переключателя (`NEXT`, `PREVIOUS`,
  `PLAY_PAUSE`) даже при скрытом плеере. События не перехватываются и не мешают
  штатному плееру.
- Добавить диагностику accessibility: `accessibility_state_changed`,
  `accessibility_connected`/`accessibility_disconnected`, поле
  `accessibilityConnected` в снимке. Писать `capture_gap`, когда музыка активна
  и источника нет, даже если последний трек ещё не был известен.
- Добавить диагностику жизненного цикла службы: `service_started` с `pid` и
  признаком нового процесса, `service_stopped`, периодический watchdog через
  JobScheduler для повторного запуска после убийства процесса.
- Исправить раздувание `playedMs` на технических окончаниях: при
  `session_lost`/`service_stopped` ограничивать прослушанное время длительностью
  или последней известной позицией, чтобы `Рассвет` не получал 205 секунд при
  длительности 185.
- Показать в UI фактический источник («окно плеера», «сервис FYT», «нет
  источника») и предупреждать, когда музыка играет, а accessibility выключена.
- Установить приложение под `com.syu.widget.music` и объявить exported-сервис
  `com.fyt.launcher.music` прямо в нём. Второго APK, bridge и
  signature-permission нет. По снимку `com.syu.widget.music` на устройстве
  свободен.
- Обновить `docs/HEADUNIT_TEST.md` сценарием одного выезда: навигатор на весь
  экран, плеер в фоне, Next/Prev с подрулевого, пауза, естественное окончание,
  экспорт ZIP.

## Capabilities

### New Capabilities

Нет. Изменение расширяет существующие возможности, а не вводит новую
пользовательскую способность.

### Modified Capabilities

- `playback-capture`: добавить источник `fyt_service` (action
  `com.fyt.launcher.music`), пассивное наблюдение media buttons, статус
  accessibility и разрыв сбора без последнего известного трека; уточнить
  приоритет источников и поведение при скрытом окне плеера.
- `event-log`: добавить события `media_button`, `accessibility_state_changed`,
  `accessibility_connected`/`accessibility_disconnected`, `service_stopped`,
  поля `pid`/`newProcess` в `service_started`, `accessibilityConnected` в
  снимке и `capture_gap` без последнего трека.
- `listening-metrics`: не раздувать `playedMs` на технических окончаниях и
  показывать покрытие источников, чтобы `session_lost` не выглядел как реально
  прослушанное время.

## Impact

- Код: `data/StockPlayerProtocol.kt`, `data/StockPlayerParser.kt`,
  `data/StockPlayerBridge.kt`, `service/MediaWatchService.kt`,
  `service/StockPlayerAccessibilityService.kt`,
  `service/NotificationWatchService.kt`, `data/ListeningTracker.kt`,
  `diagnostics/SnapshotWriter.kt`, `ui/ProbeScreen.kt`, `MainActivity.kt`,
  новый `service/FytMetadataService.kt`, `app/build.gradle.kts`,
  `build-apk.bat`, `release.ps1`, `scripts/report.py`, `scripts/test_report.py`,
  `docs/HEADUNIT_TEST.md`, `docs/LOG_ANALYSIS_2026-11-02.md`, `CHANGELOG.md`.
- Разрешения: новых не требуется. `INTERNET` не добавляется. Сервис
  `com.fyt.launcher.music` exported, как у штатного плеера; данные валидируются.
  Signature-permission и второй APK не нужны.
- Данные: новые типы событий и поля. Приложение переезжает на пакет
  `com.syu.widget.music`; данных для миграции нет, старую версию
  `com.example.carmusicmeter` можно удалить.
- Риски: штатный плеер может адресовать `com.fyt.launcher.music` только
  конкретным пакетам; `ACTION_MEDIA_BUTTON` может не доставляться на Android 10;
  пакет `com.syu.widget.music` может быть занят другой прошивкой. Fallback —
  сменить `applicationId` на `com.syu.screensaver` или `cn.teyes.online` и
  пересобрать один APK. Поэтому change включает диагностический режим и один
  сценарный выезд.
