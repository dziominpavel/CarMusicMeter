# Proposal

## Why

Отчёт и выгрузка работают с событиями, а не с прослушиваниями. Чтобы получить
один play, `scripts/report.py` реконструирует его из `track_started`,
`track_progress`, `track_ended`, `seek` и `clock_changed`: сортирует по
`monoMs`, применяет поправку часов, склеивает и дедуплицирует. Это
единственная реализация, и она живёт на ПК. Приложение не может показать отчёт
без неё, а ZIP не содержит готовых фактов для ИИ и сторонних инструментов.

## What Changes

- Приложение пишет `plays/plays-YYYY-MM-DD.jsonl` — одну строку на закрытое
  прослушивание — в одной операции закрытия с `track_ended`, с fsync обоих
  файлов.
- `playId` рождается на старте трека и переживает восстановление процесса;
  восстановленное закрытие использует тот же id.
- Для истории до обновления `PlayBuilder` один раз разбирает
  `events-*.jsonl`, строит факты и кладёт их в `report/cache/`; кэш
  инвалидируется по версии builder'а и источнику.
- `PlaySource` сливает live и history: live приоритетнее, history закрывает
  пробелы до `liveSince`, пересечения отбрасываются.
- Открытое прослушивание выгружается отдельной записью `open=true` и не входит
  в итоги.
- ZIP получает `plays/plays.jsonl`, `plays/plays.csv`,
  `DATA_DICTIONARY.csv`, `SCHEMA.md`; `manifest.json` — блок `plays` и
  `playsSchemaVersion`.
- `scripts/report.py` читает `plays.jsonl` первым, raw — fallback; появляется
  `--plays` для сверки.
- Хранение и очистка фактов и кэша следуют сроку хранения журнала.

## Capabilities

### New Capabilities

- `play-facts`: факты прослушиваний, их запись, разбор истории, слияние,
  хранение и экспорт.

### Modified Capabilities

- `event-log`: ZIP и манифест включают факты прослушиваний; диапазон экспорта
  применяется и к ним.

## Impact

- Данные: `data/ListeningTracker.kt`, `data/OpenPlay.kt`, `data/EventLog.kt`,
  новые `data/PlayRecord.kt`, `data/PlayBuilder.kt`, `data/PlayCache.kt`,
  `data/PlaySource.kt`, а также `data/ExportManifest.kt`,
  `data/ExportRange.kt`, `data/StoragePolicy.kt`.
- Анализ: `scripts/report.py`, `scripts/test_report.py`.
- Документация: `docs/ARCHITECTURE.md`, `docs/METRICS.md`, `AGENTS.md`.
- Совместимость: старые ZIP без `plays` читаются по событиям; новые ZIP
  читаются старым `report.py` — незнакомые файлы игнорируются.
- Версия приложения не меняется: изменение тестовое.
