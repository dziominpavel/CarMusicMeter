package com.example.carmusicmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.carmusicmeter.data.ReportUiState
import com.example.carmusicmeter.data.metrics.ReportModel
import com.example.carmusicmeter.data.metrics.ReportPeriod

@Composable
fun ReportScreen(
    state: ReportUiState,
    period: ReportPeriod,
    onPeriodChange: (ReportPeriod) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onTrackClick: (String) -> Unit,
    onQualityClick: () -> Unit,
    onAllTracks: () -> Unit,
    onExport: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 960.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenHeader(title = "Отчёт", onBack = onBack)
            ReportPeriodChips(period = period, onPeriodChange = onPeriodChange)
            when (state) {
                is ReportUiState.Loading -> LoadingCard(state)
                ReportUiState.Empty -> EmptyCard()
                is ReportUiState.Error -> ErrorCard(state.message, onRetry)
                is ReportUiState.Ready -> ReportContent(
                    model = state.model,
                    onTrackClick = onTrackClick,
                    onQualityClick = onQualityClick,
                    onAllTracks = onAllTracks,
                    onExport = onExport,
                )
            }
        }
    }
}

@Composable
private fun LoadingCard(state: ReportUiState.Loading) {
    SectionCard("Разбираю историю") {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = if (state.total > 0) {
                "День " + state.done + " из " + state.total
            } else {
                "Готовлю отчёт…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyCard() {
    SectionCard("Отчёт") {
        Text("За этот период нет прослушиваний.", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Выберите другой период или прокатитесь.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    SectionCard("Ошибка отчёта") {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text("Повторить")
        }
    }
}

@Composable
private fun ReportContent(
    model: ReportModel,
    onTrackClick: (String) -> Unit,
    onQualityClick: () -> Unit,
    onAllTracks: () -> Unit,
    onExport: () -> Unit,
) {
    ReportSummaryCard(model)
    ReportQualityCard(model, onQualityClick)
    if (model.quality.unverified > 0) {
        Text(
            text = "Непроверенных дат: " + model.quality.unverified + " — они не входят в итоги.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    model.openPlay?.let { open ->
        Text(
            text = "Сейчас играет: " + open.title.orEmpty() + " — " + open.artist.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    BoxWithConstraints {
        if (maxWidth >= 840.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ReportListCard(
                    title = "Слушал чаще",
                    tracks = model.top,
                    metric = { it.plays.toString() + " play" },
                    onTrackClick = onTrackClick,
                    modifier = Modifier.weight(1f),
                )
                ReportListCard(
                    title = "Пропускал чаще",
                    tracks = model.antiTop,
                    metric = { formatPercent(it.skipRate) },
                    onTrackClick = onTrackClick,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            ReportListCard(
                title = "Слушал чаще",
                tracks = model.top,
                metric = { it.plays.toString() + " play" },
                onTrackClick = onTrackClick,
            )
            ReportListCard(
                title = "Пропускал чаще",
                tracks = model.antiTop,
                metric = { formatPercent(it.skipRate) },
                onTrackClick = onTrackClick,
            )
        }
    }

    SectionCard("Кандидаты «послушать ещё»") {
        if (model.candidates.isEmpty()) {
            Text(
                text = "Пока нет — данных мало или всё в порядке.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            model.candidates.take(3).forEach { track ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { onTrackClick(track.trackKey) }
                        .padding(vertical = 6.dp),
                ) {
                    Text(track.displayTitle, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = track.antiTopReason.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = "Локальность неизвестна: это «послушать ещё», не удаление.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = onAllTracks,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        ) {
            Text("Все треки", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(
            onClick = onExport,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        ) {
            Text("Сохранить отчёт в ZIP", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }

    Text(
        text = "Подробный отчёт смотреть на стоянке.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
