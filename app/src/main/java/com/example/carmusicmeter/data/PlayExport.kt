package com.example.carmusicmeter.data

/**
 * Артефакты фактов для ZIP: `plays.jsonl`, `plays.csv`, словарь и схема.
 *
 * Всё генерируется из одного списка фактов, поэтому числа в манифесте и
 * файлах не могут разойтись.
 */
object PlayExport {

    data class Bundle(
        val generated: List<ExportGenerated>,
        val stats: ExportPlaysStats,
    )

    fun build(plays: List<PlayRecord>, range: ExportRange): Bundle {
        val closed = plays.filterNot { it.open }
        val rated = closed.count { it.endReason in RATED_REASONS }
        val stats = ExportPlaysStats(
            total = closed.size,
            live = closed.count { it.origin == "live" },
            history = closed.count { it.origin == "history" },
            open = plays.count { it.open },
            rated = rated,
            censored = closed.size - rated,
            fromMs = closed.minOfOrNull { it.startedAt },
            toMs = closed.maxOfOrNull { it.startedAt },
        )
        val jsonl = if (plays.isEmpty()) {
            ""
        } else {
            plays.joinToString(separator = "\n", postfix = "\n") { it.toJsonLine() }
        }
        val csv = "\uFEFF" + PlayCsv.render(plays)
        return Bundle(
            generated = listOf(
                ExportGenerated("plays/plays.jsonl", jsonl.toByteArray(Charsets.UTF_8)),
                ExportGenerated("plays/plays.csv", csv.toByteArray(Charsets.UTF_8)),
                ExportGenerated("DATA_DICTIONARY.csv", PlayDictionary.csv().toByteArray(Charsets.UTF_8)),
                ExportGenerated("SCHEMA.md", PlayDictionary.schema().toByteArray(Charsets.UTF_8)),
            ),
            stats = stats,
        )
    }

    private val RATED_REASONS = setOf("completed", "skipped", "interrupted")
}

/** Словарь полей факта: один источник для CSV, документации и ИИ. */
object PlayDictionary {

    data class Field(
        val name: String,
        val type: String,
        val unit: String,
        val nullable: Boolean,
        val description: String,
    )

    private val fields = listOf(
        Field("playsSchemaVersion", "int", "-", false, "Версия схемы факта"),
        Field("playId", "string", "-", false, "Стабильный id прослушивания"),
        Field("origin", "enum", "-", false, "live — запись трекера, history — разбор журнала"),
        Field("bootId", "string", "-", false, "Идентификатор процесса"),
        Field("trackKey", "string", "-", false, "Логический ключ трека"),
        Field("package", "string", "-", false, "Пакет плеера"),
        Field("source", "enum", "-", false, "media_session, fyt_service, fyt_broadcast, accessibility, notification"),
        Field("title", "string", "-", true, "Название"),
        Field("artist", "string", "-", true, "Исполнитель"),
        Field("album", "string", "-", true, "Альбом"),
        Field("mediaId", "string", "-", true, "MediaId или путь, если плеер отдал"),
        Field("durationMs", "long", "ms", true, "Длительность; null — неизвестна"),
        Field("startedAt", "long", "epoch ms", false, "Начало прослушивания"),
        Field("endedAt", "long", "epoch ms", false, "Конец; для open — последняя контрольная точка"),
        Field("playedMs", "long", "ms", false, "Просуммированное время PLAYING"),
        Field("rawPlayedMs", "long", "ms", false, "Накопленное время до ограничения длительностью"),
        Field("endPositionMs", "long", "ms", true, "Позиция на конце; null — неизвестна"),
        Field("endReason", "enum", "-", false, "completed, skipped, interrupted, stopped, session_lost, service_stopped, not_played, unknown, open"),
        Field("completed", "bool", "-", false, "Дослушан ли трек"),
        Field("restored", "bool", "-", false, "Закрытие прослушивания из прошлого процесса"),
        Field("clockVerified", "bool", "-", false, "Можно ли верить дате"),
        Field("volumeStart", "int", "шкала", true, "Громкость в начале"),
        Field("volumeEnd", "int", "шкала", true, "Громкость в конце"),
        Field("volumeLow", "int", "шкала", true, "Минимум громкости"),
        Field("volumeHigh", "int", "шкала", true, "Максимум громкости"),
        Field("volumeMax", "int", "шкала", true, "Верх шкалы; null — неизвестен"),
        Field("volumeSource", "enum", "-", true, "mcu или audio"),
        Field("volumeChanges", "int", "-", false, "Сколько раз менялась громкость"),
        Field("volumeStats", "object", "-", false, "Сегменты громкости по источникам: start, low, high, end, max, changes, up, down, level, raiseMs, dropMs"),
        Field("seekBack", "int", "-", false, "Перемотки назад"),
        Field("seekForward", "int", "-", false, "Перемотки вперёд"),
        Field("deviceId", "string", "-", false, "Идентификатор устройства"),
        Field("appVersion", "string", "-", false, "Версия приложения"),
        Field("open", "bool", "-", false, "true — прослушивание ещё не закрыто"),
        Field("mcuStart…mcuChanges", "int", "шкала", true, "CSV: сегмент MCU (6 колонок)"),
        Field("mcuUp…mcuDropMs", "int", "шкала/ms", true, "CSV: суммы шагов, медиана уровня, места подъёма и понижения MCU (5 колонок)"),
        Field("audioStart…audioChanges", "int", "шкала", true, "CSV: сегмент audio (6 колонок)"),
        Field("audioUp…audioDropMs", "int", "шкала/ms", true, "CSV: те же агрегаты для audio (5 колонок)"),
    )

    fun csv(): String = buildString {
        append("field,type,unit,nullable,description\r\n")
        fields.forEach { field ->
            append(
                listOf(
                    field.name,
                    field.type,
                    field.unit,
                    field.nullable.toString(),
                    field.description,
                ).joinToString(",") { escape(it) },
            )
            append("\r\n")
        }
    }

    fun schema(): String = """
        # CarMusicMeter — схема фактов прослушиваний

        `playsSchemaVersion: 1`.

        Одна строка `plays/plays.jsonl` — одно прослушивание. Закрытые факты
        неизменяемы; `open=true` означает текущее прослушивание, оно не входит
        в итоги отчёта. `origin=live` — факт записан трекером,
        `origin=history` — построен из `events-*.jsonl`.

        ## Время

        Все времена — epoch milliseconds. `clockVerified=false` означает, что
        дате нельзя верить; такие факты показываются отдельно и не расширяют
        период отчёта. Поправка часов уже применена к `startedAt`/`endedAt`.

        ## Пустые значения

        `null` означает «неизвестно», а не ноль. `durationMs=null` нельзя
        использовать в `completion`; `endPositionMs=null` — не позиция 0.
        `volumeMax=null` — шкала неизвестна.

        ## Громкость

        `volumeStats` хранит сегменты по источникам (`mcu`, `audio`) и
        агрегаты `up`/`down` (суммы шагов вверх и вниз), `level` (медиана) и
        `raiseMs`/`dropMs` (место подъёма и понижения от начала трека).
        Старые факты этих полей не содержат: читатель берёт `start/low/high` и
        помечает вклад громкости как приблизительный. Сравнивать
        `volumeStart/End` между разными источниками нельзя: шкалы разные.
        `mute_changed` в журнале не подменяет громкость нулём.

        ## Окончания

        Оценённые: `completed`, `skipped`, `interrupted`.
        Технические: `stopped`, `session_lost`, `service_stopped`,
        `not_played`, `unknown`. `open` — незакрытое прослушивание.

        ## Совместимость

        Потребитель MUST игнорировать незнакомые поля. Добавление поля —
        minor, удаление или смена смысла — major. Формулы метрик —
        `docs/METRICS.md`; эталон — `scripts/report.py`.
    """.trimIndent()

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
