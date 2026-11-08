package com.example.carmusicmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.carmusicmeter.data.metrics.ReportModel

@Composable
fun QualityScreen(model: ReportModel, onBack: () -> Unit) {
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
            ScreenHeader(title = "Качество данных", onBack = onBack)

            SectionCard("Период") {
                KeyValueRow("Отчёт", model.period.label)
                KeyValueRow("Прослушиваний", model.summary.plays.toString())
                KeyValueRow("Оценённых", model.summary.rated.toString())
                KeyValueRow("Технических", model.summary.censored.toString())
                KeyValueRow("Треков", model.summary.tracks.toString())
            }

            SectionCard("Покрытие") {
                KeyValueRow("Длительность", formatPercent(model.quality.durationCoverage))
                KeyValueRow("Позиция", formatPercent(model.quality.positionCoverage))
                KeyValueRow("Громкость MCU", formatPercent(model.quality.mcuCoverage))
                Text(
                    text = "Прочерк — данных нет; это не ноль.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard("Надёжность") {
                KeyValueRow("Разрывов сбора", model.quality.captureGaps.toString())
                KeyValueRow("Открытых", model.quality.open.toString())
                KeyValueRow("Восстановленных", model.quality.restored.toString())
                KeyValueRow("Непроверенных дат", model.quality.unverified.toString())
                KeyValueRow(
                    "Live / History",
                    model.quality.live.toString() + " / " + model.quality.history,
                )
                KeyValueRow("Доля истории", formatPercent(model.quality.historyShare))
            }

            if (model.quality.unverified > 0) {
                SectionCard("Дата не подтверждена") {
                    Text(
                        text = "Часть прослушиваний записана при сбитых часах. " +
                            "Они показаны отдельно и не расширяют период в прошлое.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
