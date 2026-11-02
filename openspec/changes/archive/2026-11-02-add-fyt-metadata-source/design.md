# Design

## Context

Сейчас путь данных такой:

```text
com.syu.music (нет MediaSession)
   |
   +--> accessibility-окно (только когда видно)
   +--> titlesReceiver (лаунчер, не доходит)
   +--> уведомление (нет)
   |
   v
StockPlayerBridge --> MediaWatchService --> ListeningTracker --> JSONL
```

`StockPlayerBridge` уже умеет источники `fyt_broadcast`, `accessibility` и
`notification`, а `StockPlayerProtocol` уже содержит имена extras штатного
плеера. Проблема не в разборе, а в покрытии: accessibility видит только окно,
а окно скрыто навигатором; FYT отключает accessibility при глубоком сне.

Выгрузка 02.11.2026: утром 13 треков, вечером 0; вечером 94 события громкости
и снимок с `isMusicActive=true`, `serviceRunning=true`,
`accessibilityEnabled=true`, но `stockPlayer=null`, `sessions=[]`,
`notifications=[]`. Подробности — в `proposal.md`.

Ограничения: Android 10, нет adb и root, установка с флешки, тест примерно раз
в сутки. Поэтому решение должно за один выезд проверить несколько источников и
не потерять уже собранные логи.

## Goals / Non-Goals

**Goals:**

- Получать название, исполнителя, альбом, путь, длительность, позицию и
  состояние штатного плеера, когда его окно скрыто навигатором.
- Фиксировать нажатия подрулевого переключателя как отдельные события, не
  мешая штатному плееру.
- Объяснять в журнале, почему данных нет: accessibility выключена/не
  подключена, окно пропало, сервис метаданных не получен.
- Переживать убийство процесса и перезапускать службу.
- Не плодить второй APK, bridge и лишние точки отказа.
- Проверить всё за один выезд и один экспорт ZIP.

**Non-Goals:**

- Не заменять штатный плеер и не делать свой плеер.
- Не использовать root, системные привилегии, adb или модификацию прошивки.
- Не перехватывать media buttons и не запрашивать аудиофокус.
- Не добавлять `INTERNET` и сетевые зависимости.
- Не менять громкость и не вмешиваться в работу навигатора.
- Не делать полноценный матчинг с индексом библиотеки — это следующий этап.

## Decisions

### 1. Один APK под `com.syu.widget.music`

Штатный плеер отправляет `com.fyt.launcher.music` конкретным пакетам. Поэтому
приложение само устанавливается под `com.syu.widget.music` и объявляет
exported-сервис с этим action. Второго APK, bridge и signature-permission нет.

```text
com.syu.music
   | startService(com.fyt.launcher.music, package=com.syu.widget.music)
   v
FytMetadataService (в основном приложении)
   | StockPlayerParser.fromBroadcast(source=fyt_service)
   v
StockPlayerBridge --> MediaWatchService --> JSONL
```

Почему один APK:

- на v0 нет релиза и данных для миграции; сохранять нечего;
- меньше точек отказа: нет второго пакета, permission и порядка установки;
- release и установка проще: один `CarMusicMeter-release-latest.apk`.

Детали:

- `applicationId = "com.syu.widget.music"`, `namespace` остаётся
  `com.example.carmusicmeter` (пакеты классов не переезжают);
- `FytMetadataService` exported, action `com.fyt.launcher.music`,
  `START_NOT_STICKY`;
- принимает extras, валидирует title, обновляет `StockPlayerBridge` и
  `FytMetadataStatus`, пишет `fyt_metadata_service` при первом получении или
  смене набора ключей;
- запускает `MediaWatchService` с `StartSource.FYT_SERVICE`, если автозапуск
  включён и служба не работает;
- запись события — в IO-корутине, не на main thread;
- пакет трека — `com.syu.music`, поэтому он первый в `PACKAGES`.

Если `com.syu.widget.music` занят или не адресуется, `applicationId` меняется
на `com.syu.screensaver` или `cn.teyes.online`, и собирается тот же один APK.

### 2. Media buttons — динамический receiver в foreground-службе

`com.syu.ms` превращает CAN-нажатия в `ACTION_MEDIA_BUTTON`. Приложение
регистрирует динамический `BroadcastReceiver` в `MediaWatchService`:

- filter: `Intent.ACTION_MEDIA_BUTTON`;
- extra: `Intent.EXTRA_KEY_EVENT`;
- пишет `media_button` с `keyCode`, `action`, `eventTime`, `repeatCount`,
  `sourcePackage`, текущим `trackKey`/`title`/`artist`;
- не вызывает `abortBroadcast`, не запрашивает аудиофокус, не вызывает
  `dispatchMediaKeyEvent` и не создаёт `MediaSession`.

Почему динамический receiver, а не manifest: на Android 8+ implicit broadcast
не доставляется manifest-приёмникам, а foreground-служба освобождена от
ограничения. Почему не `MediaSession`: активная сессия начала бы конкурировать
со штатным плеером за кнопки и могла бы их перехватить.

Если broadcast не приходит, приложение продолжает работать; событие
`media_button` просто отсутствует, и это видно в отчёте.

### 3. Приоритет источников и свежесть

Порядок внутри штатного плеера:

| Приоритет | Источник | Играет: stale | Пауза: stale |
|---:|---|---:|---:|
| 1 | `fyt_service` (bridge) | 30 с | 10 мин |
| 2 | `fyt_broadcast` (лаунчер) | 30 мин | 10 мин |
| 3 | `accessibility` | 90 с | 10 мин |
| 4 | `notification` | 30 мин | 10 мин |

`StockPlayerBridge.update` игнорирует переход с более приоритетного источника
на менее приоритетный в течение 10 секунд. Это не даёт accessibility перебить
свежий `fyt_service` и не создаёт второй трек при смене источника.

`fyt_service` обновляется примерно раз в секунду. Если он замолчал на 30 секунд
при `play_state=true`, состояние становится stale, и трек закрывается как
`session_lost`. Для паузы 10 минут достаточно, чтобы пережить остановку на
светофоре.

`SourcePicker` не меняется: он по-прежнему выбирает между MediaSession и
штатным плеером, а внутри штатного плеера приоритет решает bridge.

### 4. Accessibility: настройка ≠ подключение

Добавляется объект `AccessibilityConnection`:

- `connected` выставляется в `onServiceConnected`;
- `lastScanAt` обновляется на каждом скане;
- `connected` считается живым, только если последний скан не старше 10 секунд;
- `onUnbind`/`onDestroy` сбрасывают состояние сразу;
- `accessibility_connected` пишет сам accessibility-сервис при подключении;
- `MediaWatchService` раз в 10 секунд сравнивает `AccessibilityAccess.isEnabled`
  с прошлым значением и пишет `accessibility_state_changed`, а при истечении
  heartbeat — `accessibility_disconnected`; так событие не теряется, если сервис
  убили без `onDestroy`.

Снимок содержит и `accessibilityEnabled`, и `accessibilityConnected`. UI
показывает оба: «включено, сервис подключён», «включено, сервис не подключён»,
«выключено». Это закрывает случай, когда настройка включена, а сервис убит.

Приложение не пытается писать `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`:
без `WRITE_SECURE_SETTINGS` это невозможно, а добавлять системные привилегии
нельзя. Вместо этого — предупреждение в UI и запись в журнал.

`StockPlayerAccessibilityService.scan()` перестаёт требовать `latest != null`
для `capture_gap`: если музыка играет и окна нет, событие пишется с
`lastTitle=null`, `lastArtist=null`. Если accessibility выключена или не
подключена, `capture_gap` с причиной `no_source` пишет `MediaWatchService`,
чтобы вечерняя поездка не выглядела как «музыки не было».

### 5. Жизненный цикл службы и watchdog

- `service_started` получает `pid` и `newProcess`; повторный `onStartCommand`
  в том же процессе не пишет второе событие.
- `service_stopped` пишется в `onDestroy` с причиной, `pid` и временем работы.
- `AutostartScheduler` добавляет второй, периодический job (ID 4202) с
  интервалом 15 минут. `AutostartJobService` различает job по ID: 4201 — `job`,
  4202 — `watchdog`.
- Watchdog запускает службу только если `autoStart=true` и
  `serviceRunning=false`; вторую копию не запускает.

Почему 15 минут: это минимальный период `JobScheduler`; для страховки от
убийства процесса этого достаточно, а батарею не расходует. `AlarmManager`
каждую минуту — избыточно и агрессивно. `START_STICKY` уже есть, но он не
помогает, если процесс убит вместе с сервисом.

### 6. `playedMs` на технических окончаниях

В `ListeningTracker.endEvent`:

- `rawPlayedMs` — накопленное значение до ограничения;
- `playedMs` — `min(rawPlayedMs, durationMs)`, если длительность известна и
  окончание техническое (`session_lost`, `service_stopped`, `stopped`,
  `unknown`);
- для `completed`/`skipped` ограничение не применяется;
- если длительность неизвестна, `playedMs` остаётся оценкой, а окончание
  цензурируется.

Это исправляет `Рассвет`: 205 669 мс при длительности 185 000 превращаются в
`playedMs=185000`, `rawPlayedMs=205669`.

### 7. UI и отчёт

- `ProbeScreen`: источник штатного плеера (`fyt_service`, `fyt_broadcast`,
  `accessibility`, `notification`), статус accessibility (настройка и
  подключение), время последнего `fyt_metadata_service`.
- Предупреждение, если `isMusicActive=true`, а источника нет: «музыка играет,
  но приложение не видит источник».
- `SnapshotWriter`: `accessibilityConnected`, `fytMetadataService`
  (`received`, `package`, `keys`, `updatedAt`), `stockPlayer.source`.
- `scripts/report.py`: `fyt_service` уже попадёт в разрез по источникам
  автоматически; добавить счётчики `media_button` (next/previous/play_pause),
  причины `capture_gap` (включая `no_source`) и показ `rawPlayedMs` для
  цензурированных окончаний.
- `scripts/test_report.py`: тесты на `fyt_service`, `media_button`,
  `capture_gap/no_source`, ограничение `playedMs`.

### 8. Сборка и поставка

- `app/build.gradle.kts`: `applicationId = "com.syu.widget.music"`,
  `namespace` остаётся `com.example.carmusicmeter`.
- `build-apk.bat` и `release.ps1`: один APK, без второго ассета.
- `docs/HEADUNIT_TEST.md`: установка одного APK и сценарий одного выезда.
- Если пакет занят или не адресуется, меняется только `applicationId`
  в `app/build.gradle.kts`; структура проекта не меняется.

## Risks / Trade-offs

| Риск | Mitigation |
|---|---|
| Штатный плеер не адресует `com.fyt.launcher.music` пакету `com.syu.widget.music` | Событие `fyt_metadata_service` покажет это за один выезд; fallback — `com.syu.screensaver`/`cn.teyes.online`, затем toolkit-модуль или лаунчер-мод (вне этого change) |
| Лаунчер Teyes реагирует на `com.syu.widget.music` как на музыкальный виджет | Приложение не предоставляет виджет; проверить в поездке; при проблеме сменить `applicationId` на `com.syu.screensaver` или `cn.teyes.online` |
| `ACTION_MEDIA_BUTTON` не доставляется на Android 10 | Событие просто отсутствует; треки собираются из `fyt_service`/accessibility; это диагностируется, а не ломает сбор |
| FYT снова выключит accessibility | Основной источник — `fyt_service`; UI предупреждает; автозапуск Teyes помогает процессу, но не включает accessibility — это известное ограничение |
| Дублирование трека при переходе `fyt_service` → accessibility | `StockPlayerBridge` игнорирует менее приоритетный источник 10 секунд; идентичность трека не зависит от source |
| Сервис exported, поэтому другое приложение может отправить fake metadata | Приложение валидирует title/extras; на магнитоле нет сторонних недоверенных приложений; риск принят для v0 |
| Пакет `com.syu.widget.music` занят или не адресуется | Сменить `applicationId` на `com.syu.screensaver` или `cn.teyes.online` и пересобрать один APK |

## Migration Plan

1. Старые логи уже выгружены; данных для миграции нет. Старую версию
   `com.example.carmusicmeter` можно удалить после установки новой.
2. Собрать `release` (или `debug`) и установить с флешки один APK
   `CarMusicMeter-release-latest.apk`.
3. Выдать приложению доступ к уведомлениям и accessibility, разрешить работу
   без ограничений батареи, добавить в автозапуск Teyes.
4. Один выезд по сценарию `docs/HEADUNIT_TEST.md`: навигатор на весь экран,
   плеер в фоне, Next/Prev с подрулевого, пауза/продолжение, естественное
   окончание трека.
5. Выгрузить ZIP и проверить:
   - есть ли `fyt_metadata_service` и `stock_player_text` с `source=fyt_service`;
   - есть ли `media_button` с `keyCode=87/88/85`;
   - есть ли `capture_gap` с `reason=no_source`, если источник пропал;
   - нет ли дублей `track_started` при смене источника.
6. Если `fyt_metadata_service` нет — сменить `applicationId` в
   `app/build.gradle.kts` на `com.syu.screensaver` или `cn.teyes.online`,
   пересобрать один APK и повторить выезд.

Rollback: удалить APK. Данные остаются в его каталоге до удаления; второго
пакета больше нет.
