package com.example.carmusicmeter.data.metrics

import com.example.carmusicmeter.data.PlayRecord
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Оценка симпатии трека, версия 1.
 *
 * Формула — гипотеза: громкость вверх весит больше времени прослушивания,
 * уровень громкости слабый (он наследуется от предыдущего трека), понижение
 * считается только у прерванных прослушиваний. Пороги берутся из привычек
 * владельца (перцентили), а не из констант устройства. При малом числе
 * прослушиваний оценка подтягивается к 50, чтобы одно прослушивание не давало
 * 0 или 100.
 *
 * Правила и ограничения — `docs/METRICS.md`, раздел «Оценка симпатии (v1)».
 */
object TrackAffinity {

    const val VERSION = "v1"

    private const val PRIOR = 0.5
    private const val K = 1.5
    private const val VOLUME_WEIGHT = 0.65
    private const val TIME_WEIGHT = 0.35
    private const val UP_WEIGHT = 0.35
    private const val DOWN_WEIGHT = 0.25
    private const val STATE_WEIGHT = 0.15
    private const val MIN_PLAYS_FOR_CALIBRATION = 30
    private const val TRIP_GAP_MS = 10 * 60 * 1000L

    private const val DEFAULT_CEILING = 30
    private const val DEFAULT_HABIT = 15
    private const val DEFAULT_IQR = 10.0
    private const val DEFAULT_UP = 5
    private const val DEFAULT_DOWN = 3

    private val CENSORED = setOf("session_lost", "stopped", "unknown", "service_stopped")

    /** Пороги, посчитанные по фактам владельца. */
    data class Calibration(
        val ceiling: Int,
        val habit: Int,
        val iqr: Double,
        val typicalUp: Int,
        val typicalDown: Int,
        val trained: Boolean,
    )

    /** Итог по треку; `null` — данных нет, оценка показывается прочерком. */
    data class Result(
        val score: Int,
        val confidence: Double,
        val plays: Int,
        val approximate: Boolean,
        val maxUp: Int,
        val reasons: List<String>,
    )

    private data class Volume(
        val start: Int,
        val low: Int,
        val high: Int,
        val end: Int,
        val up: Int,
        val down: Int,
        val level: Int,
        val raiseMs: Long?,
        val dropMs: Long?,
        val approximate: Boolean,
    )

    private data class Evidence(
        val play: PlayRecord,
        val satisfaction: Double,
        val weight: Double,
        val volume: Volume?,
        val heard: Double?,
    )

    fun calibrate(plays: List<PlayRecord>): Calibration {
        val volumes = plays
            .filter { !it.open && it.clockVerified }
            .mapNotNull { volumeOf(it) }
        if (volumes.size < MIN_PLAYS_FOR_CALIBRATION) {
            return Calibration(
                ceiling = DEFAULT_CEILING,
                habit = DEFAULT_HABIT,
                iqr = DEFAULT_IQR,
                typicalUp = DEFAULT_UP,
                typicalDown = DEFAULT_DOWN,
                trained = false,
            )
        }
        val levels = volumes.map { it.level }.sorted()
        val samples = volumes
            .flatMap { listOf(it.start, it.low, it.high, it.end, it.level) }
            .sorted()
        val ups = volumes.map { it.up }.filter { it > 0 }.sorted()
        val downs = volumes.map { it.down }.filter { it > 0 }.sorted()
        return Calibration(
            ceiling = percentile(samples, 0.95).coerceAtLeast(1),
            habit = median(levels),
            iqr = max(4.0, (percentile(levels, 0.75) - percentile(levels, 0.25)).toDouble()),
            typicalUp = if (ups.isEmpty()) DEFAULT_UP else median(ups).coerceAtLeast(1),
            typicalDown = if (downs.isEmpty()) DEFAULT_DOWN else median(downs).coerceAtLeast(1),
            trained = true,
        )
    }

    /** Медиана уровня громкости по поездке; ключ — `playId`. */
    fun tripMedians(plays: List<PlayRecord>): Map<String, Int> {
        val result = HashMap<String, Int>()
        val sorted = plays.sortedBy { it.startedAt }
        var group = ArrayList<PlayRecord>()

        fun flush() {
            val levels = group.mapNotNull { levelOf(it) }.sorted()
            if (levels.isNotEmpty()) {
                val value = median(levels)
                group.forEach { result[it.playId] = value }
            }
            group = ArrayList()
        }

        var previous: PlayRecord? = null
        sorted.forEach { play ->
            val prev = previous
            if (prev != null && play.startedAt - prev.startedAt > TRIP_GAP_MS) flush()
            group += play
            previous = play
        }
        flush()
        return result
    }

    fun score(
        items: List<PlayRecord>,
        calibration: Calibration,
        tripMedians: Map<String, Int>,
    ): Result? {
        val evidence = items
            .mapNotNull { evidence(it, calibration, tripMedians[it.playId]) }
            .filter { it.weight > 0 }
        if (evidence.isEmpty()) return null
        val weight = evidence.sumOf { it.weight }
        val sum = evidence.sumOf { it.satisfaction * it.weight }
        val score = (100.0 * (sum + PRIOR * K) / (weight + K))
            .roundToInt()
            .coerceIn(0, 100)
        val withVolume = evidence.count { it.volume != null }
        return Result(
            score = score,
            confidence = weight,
            plays = evidence.size,
            approximate = withVolume == 0 || evidence.any { it.volume?.approximate == true },
            maxUp = evidence.maxOfOrNull { it.volume?.up ?: 0 } ?: 0,
            reasons = reasons(evidence),
        )
    }

    private fun evidence(play: PlayRecord, calibration: Calibration, tripMedian: Int?): Evidence? {
        if (play.open) return null
        if (play.endReason == "not_played") return null
        if (play.restored && play.playedMs <= 0L) return null
        val volume = volumeOf(play)
        val heard = heard(play)
        val time = timeScore(play, heard)
        if (volume == null && time == null) return null
        val value = if (volume == null) {
            0.5
        } else {
            volumeScore(play, volume, calibration, tripMedian, heard)
        }
        val satisfaction = if (time == null) value else VOLUME_WEIGHT * value + TIME_WEIGHT * time
        val weight = when {
            time == null -> 0.6
            volume == null -> 0.6
            play.endReason in CENSORED -> 0.6
            else -> 1.0
        }
        return Evidence(play, satisfaction, weight, volume, heard)
    }

    private fun volumeScore(
        play: PlayRecord,
        volume: Volume,
        calibration: Calibration,
        tripMedian: Int?,
        heard: Double?,
    ): Double {
        val headroom = max(4, calibration.ceiling - volume.start)
        val up = 0.5 * min(volume.up / calibration.typicalUp.toDouble(), 1.0) +
            0.5 * min(volume.up / headroom.toDouble(), 1.0)
        val countsDown = play.endReason == "skipped" || play.endReason == "interrupted"
        val down = if (!countsDown) {
            0.0
        } else {
            0.5 * min(volume.down / calibration.typicalDown.toDouble(), 1.0) +
                0.5 * min(volume.down / max(4, volume.start).toDouble(), 1.0)
        }
        val habitDelta = (volume.level - calibration.habit) / calibration.iqr
        val tripDelta = (volume.level - (tripMedian ?: volume.level)) / calibration.iqr
        val state = 0.5 * clampSigned(habitDelta) + 0.5 * clampSigned(tripDelta)
        val heardFactor = 0.4 + 0.6 * (heard ?: 0.3)
        return clamp01(0.5 + UP_WEIGHT * up - DOWN_WEIGHT * down + STATE_WEIGHT * state * heardFactor)
    }

    private fun reasons(evidence: List<Evidence>): List<String> {
        val out = ArrayList<String>()
        val bestUp = evidence
            .filter { (it.volume?.up ?: 0) >= 1 }
            .maxByOrNull { it.volume?.up ?: 0 }
        if (bestUp != null) {
            val volume = bestUp.volume!!
            val offset = formatOffset(volume.raiseMs)
            out += if (offset != null) "подъём +${volume.up} на $offset" else "подъём +${volume.up}"
        }
        val bestDown = evidence
            .filter {
                (it.play.endReason == "skipped" || it.play.endReason == "interrupted") &&
                    (it.volume?.down ?: 0) >= 3
            }
            .maxByOrNull { it.volume?.down ?: 0 }
        if (bestDown != null) {
            val volume = bestDown.volume!!
            val offset = formatOffset(volume.dropMs)
            out += if (offset != null) "понижение −${volume.down} на $offset" else "понижение −${volume.down}"
        }
        val heard = evidence.mapNotNull { it.heard }
        if (heard.isNotEmpty()) {
            out += "прослушано в среднем ${(heard.average() * 100).roundToInt()}%"
        }
        val completed = evidence.count { it.play.endReason == "completed" }
        val skipped = evidence.count { it.play.endReason == "skipped" }
        out += when {
            completed == 0 && skipped > 0 ->
                "$skipped ${plural(skipped, "скип", "скипа", "скипов")}, ни разу не дослушан"
            completed == 0 -> "ни разу не дослушан"
            else -> "дослушан $completed ${plural(completed, "раз", "раза", "раз")}"
        }
        if (evidence.count { it.volume == null } > 0) {
            out += "громкость известна не везде"
        }
        if (evidence.size < 2) out += "мало данных"
        return out
    }

    private fun volumeOf(play: PlayRecord): Volume? {
        val mcu = play.volumeStats["mcu"] ?: return null
        val up = mcu.up
        val down = mcu.down
        val level = mcu.level
        val approximate = up == null || down == null || level == null
        return Volume(
            start = mcu.start,
            low = mcu.low,
            high = mcu.high,
            end = mcu.end,
            up = up ?: max(0, mcu.high - mcu.start),
            down = down ?: max(0, mcu.start - mcu.low),
            level = level ?: ((mcu.start + mcu.end) / 2),
            raiseMs = mcu.raiseMs,
            dropMs = mcu.dropMs,
            approximate = approximate,
        )
    }

    private fun levelOf(play: PlayRecord): Int? {
        val mcu = play.volumeStats["mcu"] ?: return null
        return mcu.level ?: ((mcu.start + mcu.end) / 2)
    }

    private fun heard(play: PlayRecord): Double? {
        val duration = play.durationMs ?: return null
        if (duration <= 0L) return null
        return (play.playedMs.toDouble() / duration).coerceIn(0.0, 1.0)
    }

    private fun timeScore(play: PlayRecord, heard: Double?): Double? = when (play.endReason) {
        "completed" -> 1.0
        "interrupted" -> 0.4
        "skipped" -> heard?.let { 0.05 + 0.75 * it }
        in CENSORED -> heard?.let { 0.25 + 0.5 * it }
        else -> null
    }

    private fun median(sorted: List<Int>): Int {
        if (sorted.isEmpty()) return 0
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2
        } else {
            sorted[middle]
        }
    }

    private fun percentile(sorted: List<Int>, fraction: Double): Int {
        if (sorted.isEmpty()) return 0
        val index = ((sorted.size - 1) * fraction).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun clamp01(value: Double): Double = max(0.0, min(1.0, value))

    private fun clampSigned(value: Double): Double = max(-1.0, min(1.0, value))

    private fun formatOffset(ms: Long?): String? {
        if (ms == null) return null
        val total = (ms / 1000).toInt().coerceAtLeast(0)
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}
