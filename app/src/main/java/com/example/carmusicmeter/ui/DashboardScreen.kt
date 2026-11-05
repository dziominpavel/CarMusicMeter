package com.example.carmusicmeter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.carmusicmeter.data.CaptureAction
import com.example.carmusicmeter.data.CaptureStatus
import com.example.carmusicmeter.data.CaptureStatusView
import com.example.carmusicmeter.data.LiveState
import com.example.carmusicmeter.data.LiveTrack
import com.example.carmusicmeter.data.deriveCaptureStatus
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    state: LiveState,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartService: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onExport: () -> Unit,
) {
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val status = deriveCaptureStatus(state, nowMs)
    val onStatusAction: (CaptureAction) -> Unit = { action ->
        when (action) {
            CaptureAction.START_SERVICE -> onStartService()
            CaptureAction.OPEN_NOTIFICATION_SETTINGS -> onOpenNotificationAccess()
            CaptureAction.OPEN_DIAGNOSTICS -> onOpenDiagnostics()
            CaptureAction.NONE -> Unit
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val fontScale = LocalDensity.current.fontScale
        val wide = maxWidth >= 840.dp && maxHeight >= (480.dp * fontScale)
        if (wide) {
            WideDashboard(
                state = state,
                status = status,
                onStatusAction = onStatusAction,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenSettings = onOpenSettings,
                onExport = onExport,
            )
        } else {
            CompactDashboard(
                state = state,
                status = status,
                onStatusAction = onStatusAction,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenSettings = onOpenSettings,
                onExport = onExport,
            )
        }
    }
}

@Composable
private fun WideDashboard(
    state: LiveState,
    status: CaptureStatusView,
    onStatusAction: (CaptureAction) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DashboardHeader(status, wide = true, onOpenDiagnostics, onOpenSettings)
        StatusBanner(status, onStatusAction)
        ClockWarningCard(state)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(
                modifier = Modifier.weight(1.4f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                NowPlayingCard(state)
                DashboardActions(onExport = onExport)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TodaySummary(state)
                DiagnosticsEntry(onOpenDiagnostics)
            }
        }
    }
}

@Composable
private fun CompactDashboard(
    state: LiveState,
    status: CaptureStatusView,
    onStatusAction: (CaptureAction) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DashboardHeader(status, wide = false, onOpenDiagnostics, onOpenSettings)
        StatusBanner(status, onStatusAction)
        ClockWarningCard(state)
        NowPlayingCard(state)
        DashboardActions(onExport = onExport)
        TodaySummary(state)
        DiagnosticsEntry(onOpenDiagnostics)
    }
}

@Composable
private fun DashboardHeader(
    status: CaptureStatusView,
    wide: Boolean,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (wide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "CarMusicMeter",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            StatusChip(status, onClick = onOpenDiagnostics)
            StatusUpdatedAt(status)
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Настройки")
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "CarMusicMeter",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Настройки")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusChip(status, onClick = onOpenDiagnostics)
                StatusUpdatedAt(status, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusUpdatedAt(status: CaptureStatusView, modifier: Modifier = Modifier) {
    val lastSuccess = status.lastSuccessAt ?: return
    val prefix = if (status.status == CaptureStatus.RECORDING) "обновлено" else "последний успех"
    Text(
        text = "$prefix ${formatClock(lastSuccess)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun NowPlayingCard(state: LiveState) {
    val live = state.live
    Card(
        shape = AppCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Сейчас играет",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (live != null) PhaseBadge(live.phase)
            }
            if (live == null) {
                Text(
                    text = "Активных медиасессий нет",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Включи музыку в штатном плеере — карточка обновится сама.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                NowPlayingBody(live)
            }
        }
    }
}

@Composable
private fun NowPlayingBody(live: LiveTrack) {
    Text(
        text = live.title ?: "Без названия",
        style = MaterialTheme.typography.headlineSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = listOfNotNull(live.artist, live.album)
            .joinToString(" • ")
            .ifBlank { "Неизвестный исполнитель" },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val duration = live.durationMs
    val position = live.positionMs ?: 0L
    if (duration != null && duration > 0) {
        val progress = (position.toFloat() / duration).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )
        Row {
            Text(
                text = "${formatMs(position)} / ${formatMs(duration)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "в треке ${formatMs(live.playedMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Text(
            text = "позиция ${formatMs(position)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoPill(volumeLabel(live), Modifier.weight(1f))
        InfoPill("источник: ${sourceLabel(live.source)}", Modifier.weight(1f))
    }
}

@Composable
private fun DashboardActions(onExport: () -> Unit) {
    Button(
        onClick = onExport,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        Text("Экспорт логов", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Предупреждение о сбитых часах магнитолы.
 *
 * Пока часы не подтверждены, события идут в непроверенный журнал: их дату
 * восстановит отчёт, но пользователю стоит об этом знать на главном экране.
 */
@Composable
private fun ClockWarningCard(state: LiveState) {
    if (state.clockVerified) return
    Surface(
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Часы магнитолы не синхронизированы",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "События пишутся в непроверенный журнал и получат дату " +
                    "после синхронизации времени. Непроверенных событий: " +
                    "${state.clockUnverifiedEvents}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun TodaySummary(state: LiveState) {
    Surface(
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Сегодня",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${state.eventsToday} ${pluralEvents(state.eventsToday)} · " +
                    "${state.tracksToday} ${pluralTracks(state.tracksToday)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagnosticsEntry(onOpenDiagnostics: () -> Unit) {
    Surface(
        onClick = onOpenDiagnostics,
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Диагностика",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Источники, ошибки, последние события",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun volumeLabel(live: LiveTrack): String {
    val source = sourceLabel(live.volumeSource)
    return if (live.volumeMax > 0) {
        "громкость ${live.volume}/${live.volumeMax} · $source"
    } else {
        "громкость ${live.volume} · $source"
    }
}

private fun pluralEvents(n: Int): String = plural(n, "событие", "события", "событий")

private fun pluralTracks(n: Int): String = plural(n, "трек", "трека", "треков")

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
