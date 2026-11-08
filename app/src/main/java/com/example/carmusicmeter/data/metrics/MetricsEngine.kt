package com.example.carmusicmeter.data.metrics

import com.example.carmusicmeter.data.PlayRecord
import com.example.carmusicmeter.data.PlayVolume
import com.example.carmusicmeter.data.StorageDates
import kotlin.math.max
import kotlin.math.min

/**
 * Считает отчёт по готовым фактам. Чистый Kotlin: тестируется на JVM и
 * используется экраном, HTML и CSV одновременно.
 *
 * Формулы повторяют docs/METRICS.md и scripts/report.py; открытый факт
 * показывается отдельно и в итоги не входит.
 */
object MetricsEngine {

    private val RATED = setOf("completed", "skipped", "interrupted")
    private const val SESSION_GAP_MS = 10L * 60L * 1000L
    private const val EARLY_SKIP_POINT = 0.25
    private const val CANDIDATE_MIN_DURATION_MS = 60_000L

    fun compute(
        plays: List<PlayRecord>,
        period: ReportPeriod,
        captureGaps: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
    ): ReportModel {
        val fromMs = period.fromMs(nowMs)
        val closedAll = plays.filter { !it.open && (fromMs == null || it.startedAt >= fromMs) }
        val dated = closedAll.filter { it.clockVerified }
        val unverified = closedAll.size - dated.size
        val open = plays.filter { it.open }

        val calibration = TrackAffinity.calibrate(dated)
        val tripMedians = TrackAffinity.tripMedians(dated)
        val tracks = dated.groupBy { it.trackKey }.map { (key, items) ->
            track(key, items, calibration, tripMedians)
        }.sortedWith(
            compareByDescending<TrackMetrics> { it.plays }.thenByDescending { it.playedMs },
        )

        val ratedTotal = tracks.sumOf { it.ratedPlays }
        val closedTotal = tracks.sumOf { it.ratedPlays + it.censored }
        val summary = ReportSummary(
            plays = tracks.sumOf { it.plays },
            rated = ratedTotal,
            censored = closedTotal - ratedTotal,
            playedMs = tracks.sumOf { it.playedMs },
            tracks = tracks.size,
            trips = trips(dated.sortedBy { it.startedAt }),
            days = dated.map { StorageDates.dateOf(it.startedAt) }.distinct().size,
            completed = tracks.sumOf { it.completed },
            skipped = tracks.sumOf { it.skipped },
            interrupted = tracks.sumOf { it.interrupted },
            notPlayed = tracks.sumOf { it.notPlayed },
            unknown = tracks.sumOf { it.unknown },
            restored = dated.count { it.restored },
        )

        val quality = QualitySummary(
            closed = dated.size,
            rated = ratedTotal,
            censored = dated.size - ratedTotal,
            durationKnown = dated.count { it.durationMs != null },
            positionKnown = dated.count { it.endPositionMs != null },
            mcuKnown = dated.count { it.volumeStats.mcuRange() != null },
            open = open.size,
            restored = dated.count { it.restored },
            unverified = unverified,
            live = dated.count { it.origin == "live" },
            history = dated.count { it.origin == "history" },
            captureGaps = captureGaps,
        )

        val top = tracks.take(5)
        val antiTop = tracks
            .filter { it.enoughData && it.skipRate != null }
            .sortedWith(
                compareByDescending<TrackMetrics> { it.skipRate ?: 0.0 }
                    .thenByDescending { it.ratedPlays },
            )
            .take(5)
        val candidates = tracks.filter { it.isCandidate() }
            .sortedWith(
                compareBy<TrackMetrics> { it.affinity ?: 100 }
                    .thenByDescending { it.ratedPlays },
            )
        val rankedTracks = tracks.sortedWith(
            compareByDescending<TrackMetrics> { it.affinity ?: -1 }
                .thenByDescending { it.plays }
                .thenByDescending { it.playedMs },
        )

        return ReportModel(
            period = period,
            fromMs = fromMs,
            toMs = nowMs,
            generatedAt = nowMs,
            summary = summary,
            quality = quality,
            tracks = tracks,
            rankedTracks = rankedTracks,
            top = top,
            antiTop = antiTop,
            candidates = candidates,
            openPlay = open.maxByOrNull { it.endedAt },
            unverifiedCount = unverified,
            affinityCalibrated = calibration.trained,
        )
    }

    private fun track(
        key: String,
        items: List<PlayRecord>,
        calibration: TrackAffinity.Calibration,
        tripMedians: Map<String, Int>,
    ): TrackMetrics {
        val restoredEmpty = items.count { it.restored && it.playedMs <= 0L }
        val rated = items.count { it.endReason in RATED }
        val durations = items.mapNotNull { it.durationMs?.takeIf { value -> value > 0 } }
        val completions = ArrayList<Double>()
        val listened = ArrayList<Double>()
        val skipPoints = ArrayList<Double>()
        val volumeDeltas = ArrayList<Double>()
        var volumeKnown = 0
        var volumeUnknown = 0

        items.forEach { play ->
            val duration = play.durationMs?.takeIf { it > 0L }
            if (duration != null) {
                if (!(play.restored && play.playedMs <= 0L)) {
                    listened += min(play.playedMs.toDouble() / duration, 1.0)
                }
                if (play.endReason in RATED) {
                    val progress = (play.endPositionMs ?: play.playedMs).toDouble()
                    completions += min(max(progress / duration, 0.0), 1.0)
                }
                if (play.endReason == "skipped") {
                    val point = (play.endPositionMs ?: play.playedMs).toDouble()
                    skipPoints += min(max(point / duration, 0.0), 1.0)
                }
            }
            val mcu = play.volumeStats.mcuRange()
            if (mcu != null) {
                volumeDeltas += (mcu.second - mcu.first).toDouble()
                volumeKnown += 1
            } else {
                volumeUnknown += 1
            }
        }

        val skipped = items.count { it.endReason == "skipped" }
        val completed = items.count { it.endReason == "completed" }
        val interrupted = items.count { it.endReason == "interrupted" }
        val latest = items.maxByOrNull { it.startedAt }
        val affinity = TrackAffinity.score(items, calibration, tripMedians)

        return TrackMetrics(
            trackKey = key,
            title = latest?.title.orEmpty(),
            artist = latest?.artist,
            packageName = latest?.packageName.orEmpty(),
            source = latest?.source.orEmpty(),
            plays = (items.size - restoredEmpty).coerceAtLeast(0),
            ratedPlays = rated,
            completed = completed,
            skipped = skipped,
            interrupted = interrupted,
            censored = items.size - rated,
            notPlayed = items.count { it.endReason == "not_played" },
            unknown = items.count { it.endReason == "unknown" },
            playedMs = items.sumOf { it.playedMs },
            completion = mean(completions),
            listened = mean(listened),
            skipRate = if (rated > 0) skipped.toDouble() / rated else null,
            earlySkipRate = if (skipped > 0) {
                skipPoints.count { it < EARLY_SKIP_POINT }.toDouble() / skipped
            } else {
                null
            },
            avgSkipPct = mean(skipPoints),
            durationMs = median(durations),
            volumeDelta = mean(volumeDeltas),
            volumeKnown = volumeKnown,
            volumeUnknown = volumeUnknown,
            volumeChanges = items.sumOf { it.volumeChanges },
            seekBack = items.sumOf { it.seekBack },
            seekForward = items.sumOf { it.seekForward },
            days = items.map { StorageDates.dateOf(it.startedAt) }.distinct().size,
            lastPlayedAt = items.maxOfOrNull { it.startedAt },
            playsList = items.sortedByDescending { it.startedAt },
            affinity = affinity?.score,
            affinityConfidence = affinity?.confidence ?: 0.0,
            affinityPlays = affinity?.plays ?: 0,
            affinityApprox = affinity?.approximate ?: false,
            affinityReasons = affinity?.reasons.orEmpty(),
            volumeUpMax = affinity?.maxUp ?: 0,
        )
    }

    private fun TrackMetrics.isCandidate(): Boolean {
        val score = affinity ?: return false
        return score <= 30 &&
            ratedPlays >= TrackMetrics.MIN_RATED &&
            (durationMs ?: 0L) >= CANDIDATE_MIN_DURATION_MS &&
            completed == 0 &&
            volumeUpMax < 3 &&
            days >= 2
    }

    private fun trips(sorted: List<PlayRecord>): Int {
        if (sorted.isEmpty()) return 0
        var count = 1
        var previous = sorted.first().startedAt
        sorted.drop(1).forEach { play ->
            if (play.startedAt - previous > SESSION_GAP_MS) count += 1
            previous = play.startedAt
        }
        return count
    }

    private fun mean(values: List<Double>): Double? =
        if (values.isEmpty()) null else values.average()

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2
        } else {
            sorted[middle]
        }
    }

    private fun Map<String, PlayVolume>.mcuRange(): Pair<Int, Int>? {
        val mcu = this["mcu"] ?: return null
        return mcu.start to mcu.end
    }
}
