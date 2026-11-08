# Proposal

## Why

Приложение собирает журнал, но не отвечает на вопросы владельца без ПК и
Python: `scripts/report.py` — единственный потребитель. В машине нельзя
посмотреть, что слушаешь, что пропускаешь и можно ли верить данным. ZIP
содержит сырые события, а не готовый отчёт для ИИ.

## What Changes

- Kotlin-движок метрик по `plays` (из `play-facts`); определения —
  `docs/METRICS.md`, `report.py` остаётся эталоном.
- Экран «Отчёт»: периоды 7/30/90/всё, сводка, топ/антитоп, качество данных,
  детали трека, кандидаты.
- Главный экран: действие «Анализ» рядом с «Экспортом»; отчёт открывается за
  один тап, период выбирается внутри отчёта.
- Экспорт: `tracks.csv`, `report.html` (offline, без CDN), `ai-prompt.md`;
  блок `report` в манифесте.
- Качество: покрытие длительности/позиции/громкости, разрывы, непроверенные
  часы, restored/open, live/history.
- Отчёт не блокирует захват: фон, прогресс, кэш фактов.
- Документы: README, AGENTS, ROADMAP, METRICS, ARCHITECTURE, CHANGELOG;
  политика «лёгкий отчёт на устройстве».
- Никакой сети и AI в приложении: ИИ на ПК через ZIP.

## Capabilities

### New Capabilities

- `listening-report`: отчёт по прослушиваниям на устройстве, его метрики,
  качество, детали и артефакты ZIP.

### Modified Capabilities

- `app-shell`: главный экран получает действие отчёта; экспорт остаётся одним
  действием, период отчёта — на экране отчёта.
- `event-log`: ZIP получает `tracks.csv`, `report.html`, `ai-prompt.md`;
  манифест — блок `report`.

## Impact

- UI: `ui/DashboardScreen.kt`, новые `ui/ReportScreen.kt`,
  `ui/TrackDetailScreen.kt`, `ui/QualityScreen.kt`, `MainActivity.kt`.
- Метрики: новый пакет `data/metrics/`, `data/PlaySource.kt` из
  `play-facts`.
- Экспорт: `data/EventLog.kt`, `data/ExportManifest.kt`,
  `data/ExportRange.kt`, `ui/ExportDialog.kt`.
- Отчёт: новые `report/ReportModel.kt`, `ReportHtmlRenderer.kt`,
  `AiPromptBuilder.kt`.
- Документация: `README.md`, `AGENTS.md`, `docs/ROADMAP.md`,
  `docs/METRICS.md`, `docs/ARCHITECTURE.md`, `CHANGELOG.md`.
- Зависимость: `play-facts` должен быть реализован первым.
- Версия приложения не меняется: изменение тестовое.
