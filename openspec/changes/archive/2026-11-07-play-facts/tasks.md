# Tasks

## 1. Модель и live-запись

- [x] 1.1 Добавить `PlayRecord` (поля из design.md), JSON-сериализацию и JVM-тест
  на полный набор полей, `null` и `volumeStats`
- [x] 1.2 Добавить `playId` в `Active` и `OpenPlay`: генерировать в `start()`,
  сохранять в checkpoint, использовать в `closeRestored`; тесты на восстановление
- [x] 1.3 Считать `seekBack`/`seekForward` в `Active` на событии перемотки;
  тест на перемотку вперёд/назад и на границы
- [x] 1.4 Добавить `EventLog.appendClose(event, play)`: запись события, fsync,
  запись факта, fsync, очистка checkpoint; тест на повторную финализацию
- [x] 1.5 Отображать `track_ended` в `PlayRecord` (`origin=live`, `bootId`,
  `deviceId`, `appVersion`), не пересчитывая `playedMs` и `volumeStats`

## 2. История и кэш

- [x] 2.1 Реализовать `PlayBuilder`: порядок по `ts`/`monoMs`, поправка
  `clock_changed`, склейка started/progress/ended, `endReason`, `playedMs`;
  JVM-тесты на completed, skipped, session_lost, restored, seek
- [x] 2.2 Реализовать `PlayCache`: файл на день, мета `builderVersion`/размер/
  mtime источника, атомарная запись, инвалидация; тесты
- [x] 2.3 Запускать разбор лениво для выбранного периода и полностью для экспорта,
  с прогрессом и пропуском битых строк; тест на повторный запуск без пересборки

## 3. Слияние и открытый факт

- [x] 3.1 Реализовать `PlaySource`: live + history, `liveSince`, отбрасывание
  пересечений, дедуп по `playId`, сортировка; тесты на середину дня и дубль
- [x] 3.2 Писать/читать `open.json` (или брать из `LiveState`) и включать
  открытый факт в экспорт с `open=true`; тест на отсутствие активного трека
- [x] 3.3 Отделять факты с `clockVerified=false` от основного периода; тест на
  журнал 2023 года

## 4. Экспорт и манифест

- [x] 4.1 Научить `EventLog.exportTo` принимать сгенерированные записи и писать
  `plays/plays.jsonl` и `plays/plays.csv` за диапазон; тест на хеши и размер
- [x] 4.2 Генерировать `DATA_DICTIONARY.csv` и `SCHEMA.md`; тест на поля,
  экранирование и версии
- [x] 4.3 Добавить в `manifest.json` `playsSchemaVersion` и блок `plays`
  (live/history/open/rated/censored, границы); тест `ExportManifest`
- [x] 4.4 Применять диапазон к фактам по `startedAt`, всегда включать открытый
  факт, не создавать ZIP без файлов и фактов; тесты на 7/30/90/all

## 5. Хранение и диагностика

- [x] 5.1 Удалять `plays-<day>.jsonl` и кэш вместе с днём в `trimOld`,
  оставлять непроверенный журнал и его факты; тесты `StoragePolicy`
- [x] 5.2 Учитывать факты и кэш в `storageStats`; тест на объём
- [x] 5.3 Показать в диагностике число live/history, дни кэша, версию builder'а,
  открытый факт, пересечения и ошибки; тест модели

## 6. Совместимость и parity

- [x] 6.1 Научить `report.py` читать `plays.jsonl` первым, raw — fallback;
  тест на ZIP с фактами и без
- [x] 6.2 Добавить `report.py --plays FILE` и golden-тест на мини-архиве:
  число фактов, `endReason`, `playedMs` совпадают
- [ ] 6.3 Прогнать parity на `data/exports` и зафиксировать допуски
  (`startedAt` ±1 с, остальное точно)

## 7. Документация и проверка

- [x] 7.1 Обновить `docs/ARCHITECTURE.md` (поток фактов, кэш, watermark) и
  `docs/METRICS.md` (факт как единица, `origin`)
- [x] 7.2 Обновить `AGENTS.md`: `plays` — единственная копия фактов, кэш
  производный; changelog — в `in-app-report`
- [ ] 7.3 Запустить `openspec validate --all`, `:app:testDebugUnitTest` и
  проверить ZIP на устройстве
