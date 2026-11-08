package com.example.carmusicmeter.data.metrics

import com.example.carmusicmeter.data.PlayRecord

data class ReportSummary(
    val plays: Int,
    val rated: Int,
    val censored: Int,
    val playedMs: Long,
    val tracks: Int,
    val trips: Int,
    val days: Int,
    val completed: Int,
    val skipped: Int,
    val interrupted: Int,
    val notPlayed: Int,
    val unknown: Int,
    val restored: Int,
)

data class TrackMetrics(
    val trackKey: String,
    val title: String,
    val artist: String?,
    val packageName: String,
    val source: String,
    val plays: Int,
    val ratedPlays: Int,
    val completed: Int,
    val skipped: Int,
    val interrupted: Int,
    val censored: Int,
    val notPlayed: Int,
    val unknown: Int,
    val playedMs: Long,
    val completion: Double?,
    val listened: Double?,
    val skipRate: Double?,
    val earlySkipRate: Double?,
    val avgSkipPct: Double?,
    val durationMs: Long?,
    val volumeDelta: Double?,
    val volumeKnown: Int,
    val volumeUnknown: Int,
    val volumeChanges: Int,
    val seekBack: Int,
    val seekForward: Int,
    val days: Int,
    val lastPlayedAt: Long?,
    val playsList: List<PlayRecord>,
    val affinity: Int?,
    val affinityConfidence: Double,
    val affinityPlays: Int,
    val affinityApprox: Boolean,
    val affinityReasons: List<String>,
    val volumeUpMax: Int,
) {
    val displayTitle: String get() = title.ifBlank { "(без названия)" }
    val enoughData: Boolean get() = ratedPlays >= MIN_RATED

    val antiTopReason: String?
        get() {
            if (affinity == null) return null
            val reasons = buildList {
                add("оценка " + affinity)
                if (skipped > 0) add("" + skipped + " из " + ratedPlays + " — скип")
                completion?.let { add("в среднем " + (it * 100).toInt() + "%") }
                if (completed == 0) add("ни разу не дослушан")
            }
            return reasons.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

    companion object {
        const val MIN_RATED = 3
    }
}

data class QualitySummary(
    val closed: Int,
    val rated: Int,
    val censored: Int,
    val durationKnown: Int,
    val positionKnown: Int,
    val mcuKnown: Int,
    val open: Int,
    val restored: Int,
    val unverified: Int,
    val live: Int,
    val history: Int,
    val captureGaps: Int,
) {
    val durationCoverage: Double? get() = share(durationKnown, closed)
    val positionCoverage: Double? get() = share(positionKnown, closed)
    val mcuCoverage: Double? get() = share(mcuKnown, closed)
    val historyShare: Double? get() = share(history, live + history)

    private fun share(part: Int, total: Int): Double? =
        if (total <= 0) null else part.toDouble() / total
}

data class ReportModel(
    val period: ReportPeriod,
    val fromMs: Long?,
    val toMs: Long,
    val generatedAt: Long,
    val summary: ReportSummary,
    val quality: QualitySummary,
    val tracks: List<TrackMetrics>,
    val rankedTracks: List<TrackMetrics>,
    val top: List<TrackMetrics>,
    val antiTop: List<TrackMetrics>,
    val candidates: List<TrackMetrics>,
    val openPlay: PlayRecord?,
    val unverifiedCount: Int,
    val affinityCalibrated: Boolean,
) {
    fun track(trackKey: String?): TrackMetrics? =
        trackKey?.let { key -> tracks.firstOrNull { it.trackKey == key } }
}
