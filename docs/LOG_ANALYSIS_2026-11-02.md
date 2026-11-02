# Разбор логов CarMusicMeter — 2026-11-02

Источник: `G:\CarMusicMeter-804ba220-all-20261102-1847.zip`
Устройство: Teyes CC3, `sprd ums512_1h10_Natv`, Android 10, SDK 29.
Сборка: CarMusicMeter 0.1.0, `versionCode=2000100025` (01.11.2026 22:02).
Период: 2026-11-02 09:01:32 — 18:47:27.

## Коротко

- Парсер больше не теряет треки: 187 снимков `stock_player_ui`, все
  `accepted=true`, 13 уникальных названий дали 13 `track_started`.
- Утром записано 13 треков, вечером — 0, хотя вечером было 94 события
  громкости. Причина — не разбор, а отсутствие источника данных.
- Утром окно плеера было видно только 09:03:55–09:12:45; после 09:12:47
  окон не было. Вечером не было ни окна, ни MediaSession, ни уведомления.
- `accessibilityEnabled` в снимке 18:47:31 равен `true`, но `stockPlayer=null`,
  `stockPlayerUi=null`, `sessions=[]`, `notifications=[]`. Музыка играла,
  служба работала, источника не было.
- «Чтение интерфейса» действительно слетает: FYT отключает accessibility при
  глубоком сне/перезагрузке. Приложение не может включить её обратно без
  системных прав.
- `Рассвет` закрылся с `playedMs=205669` при `durationMs=185000`: трекер
  добавил 90-секундный grace после пропажи источника. Это исправлено
  ограничением `playedMs` и полем `rawPlayedMs`.

## Утро

```text
09:01:32  service_started  source=teyes_autostart
09:02:21  service_started  source=app_open
09:03:55  первый трек, окно плеера видно
09:03:57  окно пропало --> capture_gap active
09:04:45  окно вернулось, но трек уже другой
09:04:45  ......  09:12:45  окно видно: 13 треков, 187 UI-снимков
09:12:47  окно пропало --> capture_gap active
09:14:21  последний трек закрыт session_lost
09:17:50  notification_listener_connected + service_started source=notification
          --> процесс/служба умирала; после этого треков больше нет
```

Что это значит:

1. 09:03:57–09:04:45 — 48 секунд без окна; трек сменился и не записался.
2. После 09:12:47 окно плеера исчезло (навигатор/главный экран). Последний
   трек `Рассвет` тянулся до 09:14:21 и закрылся как `session_lost`.
3. 09:17:50 служба была перезапущена через notification listener. После
   перезапуска `StockPlayerBridge` пуст, поэтому вечерний `capture_gap`
   не записался: старый код писал gap только при `latest != null`.

## Вечер

```text
18:36:12 ...... 18:47:27

volume_changed          94
volume_source_changed   12
teyes_toolkit            5
track_started            0
track_ended              0
track_progress           0
stock_player_ui          0
capture_gap              0
```

Снимок 18:47:31:

```json
"serviceRunning": true,
"accessibilityEnabled": true,
"isMusicActive": true,
"stockPlayer": null,
"stockPlayerUi": {
  "window": null,
  "accepted": null,
  "title": null
},
"sessions": [],
"notifications": []
```

Это главный вывод: приложение было слепым. Навигатор на весь экран убрал окно
плеера, а MediaSession и уведомления у `com.syu.music` нет.

## Что подтвердилось

- Громкость MCU работает: 94 события, `source=mcu`, `max=43`.
- Парсер accessibility работает: все 187 снимков приняты, фейковых
  «Список»/«Внутренняя память» нет.
- Длительность и позиция из UI читаются: `00:03:30` → 210000 мс,
  `00:00:30` → 30000 мс.
- Штатный плеер — `com.syu.music`; `com.yf.teyesspotify` на этом устройстве
  установлен, но это не штатный плеер. Поэтому `com.syu.music` теперь первый
  в `StockPlayerProtocol.PACKAGES`.

## Что изменено в `add-fyt-metadata-source`

- Приложение установлено под `com.syu.widget.music` и само принимает
  `com.fyt.launcher.music` (`source=fyt_service`), без второго APK.
- `ACTION_MEDIA_BUTTON` пишется как `media_button` с `keyCode`/`action`.
- `accessibilityConnected` и heartbeat 10 секунд показывают, что сервис жив,
  а не только что настройка включена.
- `capture_gap reason=no_source` пишется, когда музыка играет и источника нет,
  даже если последний трек ещё не известен.
- Периодический watchdog раз в 15 минут поднимает службу после убийства.
- `playedMs` на `session_lost`/`service_stopped`/`unknown` ограничивается
  длительностью; исходное значение остаётся в `rawPlayedMs`.

## Что проверить в следующем ZIP

- `fyt_metadata_service` с `received=true` и набором ключей.
- `stock_player_text` с `source=fyt_service` при скрытом окне плеера.
- `media_button` с `keyCode=87/88/85` после нажатий подрулевого.
- `capture_gap` с `reason=no_source` и `lastTitle=null`, если источник пропал.
- Отсутствие дублей `track_started` при переходе `fyt_service` ↔ accessibility.
- `accessibility_state_changed`/`accessibility_connected` при включении и
  отключении чтения интерфейса.
- В отчёте: `fyt_service` отдельной строкой, `media_button` в сводке,
  `censored_ms` для технических окончаний.
