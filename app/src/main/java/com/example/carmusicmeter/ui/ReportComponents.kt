package com.example.carmusicmeter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.carmusicmeter.data.metrics.ReportModel
import com.example.carmusicmeter.data.metrics.ReportPeriod
import com.example.carmusicmeter.data.metrics.TrackMetrics

@Composable
fun ReportPeriodChips(
    period: ReportPeriod,
    onPeriodChange: (ReportPeriod) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PeriodChip(ReportPeriod.DAYS_7, period, onPeriodChange)
            PeriodChip(ReportPeriod.DAYS_30, period, onPeriodChange)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PeriodChip(ReportPeriod.DAYS_90, period, onPeriodChange)
            PeriodChip(ReportPeriod.ALL, period, onPeriodChange)
        }
    }
}

@Composable
private fun PeriodChip(
    value: ReportPeriod,
    selected: ReportPeriod,
    onChange: (ReportPeriod) -> Unit,
) {
    FilterChip(
        selected = value == selected,
        onClick = { onChange(value) },
        label = { Text(value.label) },
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

@Composable
fun ReportSummaryCard(model: ReportModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryValue("Прослушано", formatHumanDuration(model.summary.playedMs), Modifier.weight(1f))
            SummaryValue("Прослушиваний", model.summary.plays.toString(), Modifier.weight(1f))
            SummaryValue("Поездок", model.summary.trips.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
fun SummaryValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ReportQualityCard(model: ReportModel, onClick: () -> Unit) {
    val quality = model.quality
    val parts = listOfNotNull(
        quality.durationCoverage?.let { "длительность " + formatPercent(it) },
        quality.positionCoverage?.let { "позиция " + formatPercent(it) },
        quality.mcuCoverage?.let { "громкость " + formatPercent(it) },
        "разрывов " + quality.captureGaps,
    )
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = AppCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Данные",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Подробнее",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun ReportTrackRow(
    track: TrackMetrics,
    metric: String,
    onClick: () -> Unit,
    caption: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = track.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                track.artist?.takeIf { it.isNotBlank() },
                track.plays.toString() + " play",
            ).joinToString(" · ")
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = metric,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ReportListCard(
    title: String,
    tracks: List<TrackMetrics>,
    metric: (TrackMetrics) -> String,
    onTrackClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title, modifier = modifier) {
        if (tracks.isEmpty()) {
            Text(
                text = "Пока нет данных",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            tracks.forEach { track ->
                ReportTrackRow(
                    track = track,
                    metric = metric(track),
                    onClick = { onTrackClick(track.trackKey) },
                )
            }
        }
    }
}
