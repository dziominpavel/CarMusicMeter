# Proposal

## Why

На магнитоле Teyes нет системного диалога сохранения (`ACTION_CREATE_DOCUMENT`):
нажатие «Экспорт логов» сразу роняет приложение, не показывая выбор файла. На
телефоне экспорт работает. Без выгрузки журнала выезд в машину даёт только
скриншоты, поэтому нужен путь сохранения, не зависящий от SAF, и одна
диагностическая сборка, которая за один выезд ответит на вопросы по громкости,
локальному плееру и автозапуску.

## What Changes

- Экспорт сначала пробует SAF, но MUST NOT падать, если обработчика нет:
  ZIP сохраняется в `Downloads/CarMusicMeter/` через `MediaStore` (Android 10,
  без разрешений на запись), с сообщением о пути и кнопкой «Поделиться».
- На телефоне, где SAF есть, поведение остаётся прежним.
- Снимок диагностики дополняется всеми аудиопотоками (`STREAM_MUSIC`,
  `STREAM_VOICE_CALL`, `STREAM_SYSTEM`, `STREAM_NOTIFICATION`, `STREAM_ALARM`,
  `STREAM_RING`, `STREAM_ACCESSIBILITY`), `isVolumeFixed`, `audioMode`,
  `isMusicActive`.
- `NotificationCache` хранит в памяти все последние уведомления с признаком
  `mediaLike`; в журнал по-прежнему пишутся только медиаподобные. Это нужно,
  чтобы снимок показал, постит ли локальный плеер обычное уведомление.
- Событие `service_started` фиксирует источник запуска службы: `manual`,
  `boot`, `notification`. Вместе с `boot_received` это позволяет отличить
  «прошивка не дала стартовать» от «служба стартовала и была убита».
- `docs/HEADUNIT_TEST.md` дополняется установкой через Play Protect,
  whitelist Teyes и проверкой `boot_received`.

Не входит: LAN-экспорт по хотспоту (отдельный change), настройка хранения и
диапазона экспорта (change `configurable-retention-and-export`), изменения
источника громкости — сначала нужны данные с магнитолы.

## Capabilities

### New Capabilities

Нет.

### Modified Capabilities

- `event-log`: экспорт больше не зависит от наличия SAF; снимок получает
  диагностику аудиопотоков и всех уведомлений; добавляется диагностика
  запуска службы.

## Impact

- `MainActivity.kt` / `ui/ProbeScreen.kt`: безопасный запуск SAF, fallback,
  сообщение о пути и «Поделиться».
- `data/EventLog.kt` / новый помощник записи в `MediaStore`: открытие потока
  для Downloads и запись ZIP.
- `diagnostics/SnapshotWriter.kt`: аудиопотоки и флаги громкости.
- `probe/NotificationCache.kt`, `service/NotificationWatchService.kt`: кэш всех
  уведомлений с `mediaLike`, журнал без изменений.
- `service/MediaWatchService.kt`, `WatchServiceController.kt`,
  `receiver/BootReceiver.kt`: источник запуска и событие `service_started`.
- `docs/HEADUNIT_TEST.md`, `README.md`: установка, Play Protect, whitelist,
  диагностика.
- Новых разрешений нет; `INTERNET` не добавляется.
