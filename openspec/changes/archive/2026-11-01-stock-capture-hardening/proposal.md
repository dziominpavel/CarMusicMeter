# Proposal

## Why

Тест 2026-11-01 показал, что штатный плеер Teyes читается только пока его окно
активно, а в фоне треки не фиксируются. При этом accessibility-парсер принимает
заголовки папок и строки списка за треки, а все окончания штатного плеера
получают `skipped`, потому что у него нет длительности и позиции. Смена источника
громкости MCU → AudioManager молча сбрасывает статистику трека. В результате
журнал содержит ложные прослушивания, а `skip_rate` и `completion` недостоверны.

## What Changes

- Accessibility-парсер штатного плеера принимает только уверенные состояния:
  строка «исполнитель — название» или «название + исполнитель», без номеров
  списка и заголовков папок; переходный UI стабилизируется debounce-ом.
- Названия очищаются от медиа-расширений, чтобы `trackKey` совпадал с будущим
  индексом библиотеки.
- Позиция и длительность извлекаются из времени в UI или `SeekBar.rangeInfo`.
  Если их нет, окончание accessibility-трека получает `endReason = unknown`,
  а не ложный `skipped`.
- Окно штатного плеера ищется не только в `rootInActiveWindow`, но и среди всех
  окон accessibility. Это даёт шанс поймать плеер в split-screen с навигатором.
- Видимое окно плеера поддерживает состояние свежим, даже если текст не менялся.
  При потере окна пишется `capture_gap`, а трек не висит бесконечно.
- Добавляется диагностика `stock_player_ui`: все тексты без фильтра, `className`,
  `viewIdResourceName`, `contentDescription`, `rangeInfo` и список окон.
- Смена источника громкости пишет `volume_source_changed` с причиной; статистика
  трека хранится по каждому источнику отдельно, а не теряется при переключении.
- `teyes_toolkit` пишется только при смене состояния, а не на каждое значение.
- Отчёт считает `unknown` цензурированным окончанием и не включает его в
  `skip_rate`/`completion`; в отчёте видно число неизвестных окончаний.

## Capabilities

### New Capabilities

Нет. Все изменения укладываются в существующие capability.

### Modified Capabilities

- `playback-capture`: уверенный разбор accessibility, извлечение позиции и
  длительности, поиск окна плеера вне фокуса, потеря окна и `unknown`-окончания.
- `event-log`: диагностика UI-дерева и окон, событие `capture_gap`, статус
  подключения notification listener, ограничение шума `teyes_toolkit`.
- `volume-tracking`: событие смены источника громкости и статистика по каждому
  источнику в `track_ended`/`track_progress`.
- `listening-metrics`: `unknown` как цензурированное окончание и отдельный
  показ неполных данных accessibility.

## Impact

- `StockPlayerParser`, `StockPlayerAccessibilityService`, `StockPlayerBridge`,
  `MediaWatchService`, `ListeningTracker`, `VolumeReading`, `SnapshotWriter`,
  `ProbeScreen`, `scripts/report.py`.
- JVM-тесты парсера, трекера и источника громкости; Python-тесты отчёта.
- `docs/METRICS.md`, `docs/HEADUNIT_TEST.md`, `docs/ARCHITECTURE.md`.
- Новых разрешений, сетевых вызовов и изменений штатного плеера нет.
