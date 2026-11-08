package com.example.carmusicmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.carmusicmeter.data.PlayRecord
import com.example.carmusicmeter.data.metrics.ReportModel

@Composable
fun TrackDetailScreen(
    model: ReportModel,
    trackKey: String?,
    onBack: () -> Unit,
) {
    val track = model.track(trackKey)
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
            ScreenHeader(title = track?.displayTitle ?: "Трек", onBack = onBack)
            if (track == null) {
                SectionCard("Трек") {
                    Text("Трек не найден в выбранном периоде.")
                }
                return@Column
            }

            Text(
                text = listOfNotNull(
                    track.artist?.takeIf { it.isNotBlank() },
                    sourceLabel(track.source),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = AppCardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SummaryValue("Play", track.plays.toString(), Modifier.weight(1f))
                    SummaryValue("Completion", formatPercent(track.completion), Modifier.weight(1f))
                    SummaryValue("Скипов", formatPercent(track.skipRate), Modifier.weight(1f))
                    SummaryValue("Прослушано", formatHumanDuration(track.playedMs), Modifier.weight(1f))
                }
            }

            SectionCard("Оценка симпатии (v1)") {
                val score = track.affinity
                if (score == null) {
                    Text(
                        text = "Пока нет данных для оценки: нужны закрытые прослушивания.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = score.toString(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Column {
                            Text(
                                text = "из 100 · версия 1",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (track.affinityPlays < 3) {
                                    "Мало данных: " + track.affinityPlays + " пр."
                                } else {
                                    "По " + track.affinityPlays + " прослушиваниям"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (track.affinityApprox) {
                        Text(
                            text = "Громкость учтена приблизительно: часть фактов без новых полей.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    track.affinityReasons.forEach { reason ->
                        Text(
                            text = "• " + reason,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = "Оценка — гипотеза (v1), а не приговор: удаление остаётся ручным.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard("Громкость и перемотки") {
                KeyValueRow("Средняя дельта MCU", formatSigned(track.volumeDelta))
                KeyValueRow(
                    "Громкость известна",
                    "у " + track.volumeKnown + ", неизвестна у " + track.volumeUnknown,
                )
                KeyValueRow("Изменений громкости", track.volumeChanges.toString())
                KeyValueRow("Перемотки", track.seekBack.toString() + " назад / " + track.seekForward + " вперёд")
                KeyValueRow("Последний раз", formatDateShort(track.lastPlayedAt))
                KeyValueRow("Дней с прослушиванием", track.days.toString())
            }

            SectionCard("Почему здесь") {
                val reason = when {
                    !track.enoughData -> "Мало данных: меньше трёх оценённых окончаний."
                    track.antiTopReason != null -> track.antiTopReason
                    else -> "Трек не попал в антитоп."
                }
                Text(reason ?: "—", style = MaterialTheme.typography.bodyMedium)
                if (track.completion != null) {
                    Text(
                        text = "Дослушано в среднем " + formatPercent(track.completion) +
                            ", точка скипа " + formatPercent(track.avgSkipPct) + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard("Последние прослушивания") {
                if (track.playsList.isEmpty()) {
                    Text(
                        text = "Нет закрытых прослушиваний.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    track.playsList.take(20).forEach { play -> PlayRow(play) }
                }
            }

            if (model.openPlay?.trackKey == track.trackKey) {
                SectionCard("Сейчас играет") {
                    Text(
                        text = formatHumanDuration(model.openPlay.playedMs) + " · " +
                            (model.openPlay.endPositionMs ?: 0L) + " / " +
                            (model.openPlay.durationMs ?: 0L) + " мс",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "В итоги не входит.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayRow(play: PlayRecord) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = formatDateShort(play.startedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(reasonLabel(play.endReason), style = MaterialTheme.typography.bodyMedium)
            val volume = play.volumeEnd?.let {
                it.toString() + "/" + (play.volumeMax ?: 0)
            } ?: "—"
            Text(
                text = formatMs(play.playedMs) + " · громкость " + volume,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
