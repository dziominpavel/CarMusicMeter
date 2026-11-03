package com.example.carmusicmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.carmusicmeter.data.LiveState
import com.example.carmusicmeter.data.MediaPhase
import com.example.carmusicmeter.ui.theme.StatusOk
import com.example.carmusicmeter.ui.theme.StatusWarn

@Composable
fun DiagnosticsScreen(
    state: LiveState,
    onBack: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
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
        ScreenHeader(title = "Диагностика", onBack = onBack)

        SectionCard("Сбор") {
            KeyValueRow(
                label = "Служба",
                value = if (state.serviceRunning) "работает" else "остановлена",
                valueColor = if (state.serviceRunning) StatusOk else StatusWarn,
            )
            KeyValueRow("Последний тик", state.lastTickAt?.let(::formatClock) ?: "—")
            KeyValueRow("Последний успех", state.lastSuccessAt?.let(::formatClock) ?: "—")
            KeyValueRow("Ошибок подряд", state.captureFailures.toString())
            KeyValueRow("Потеряно событий", state.droppedEvents.toString())
            KeyValueRow("Смен пакета", state.sessionsSeen.toString())
            state.captureError?.let {
                Text(
                    text = "Ошибка сбора: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.error?.let {
                Text(
                    text = "Ошибка источника: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.teyesError?.let {
                Text(
                    text = "MCU: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.serviceRunning) {
                OutlinedButton(
                    onClick = onStopService,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) {
                    Text("Остановить службу")
                }
            } else {
                Button(
                    onClick = onStartService,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) {
                    Text("Запустить службу")
                }
            }
        }

        SectionCard("Источники") {
            SourceRow(
                name = "MediaSession",
                value = mediaSessionText(state),
                active = state.live?.source == "media_session",
            )
            if (state.teyesRelevant) {
                SourceRow(
                    name = "Сервис FYT",
                    value = fytText(state),
                    active = state.stockSource == "fyt_service" || state.fytMetadataReceived,
                )
                SourceRow(
                    name = "Лаунчер",
                    value = if (state.stockSource == "fyt_broadcast") "данные есть" else "нет данных",
                    active = state.stockSource == "fyt_broadcast",
                )
            }
            SourceRow(
                name = "Уведомления",
                value = notificationText(state),
                active = state.notificationAccess && state.notificationListenerConnected,
                warn = state.notificationAccess && !state.notificationListenerConnected,
                actionLabel = if (!state.notificationAccess) "Разрешить" else null,
                onAction = if (!state.notificationAccess) onOpenNotificationAccess else null,
            )
            if (state.teyesRelevant) {
                SourceRow(
                    name = "Чтение интерфейса",
                    value = accessibilityText(state),
                    active = state.accessibilityEnabled && state.accessibilityConnected,
                    warn = state.accessibilityEnabled && !state.accessibilityConnected,
                    actionLabel = if (state.accessibilityEnabled) "Настроить" else "Включить",
                    onAction = onOpenAccessibilitySettings,
                )
            }
            SourceRow(
                name = "Громкость",
                value = volumeText(state),
                active = state.volumeMax > 0 || state.mcuVolume != null,
            )
        }

        SectionCard("Текущая сессия") {
            val live = state.live
            if (live == null) {
                Text(
                    text = "Нет активной сессии",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KeyValueRow("Пакет", live.packageName)
                KeyValueRow("Название", live.title ?: "Без названия")
                KeyValueRow("Исполнитель", live.artist ?: "—")
                KeyValueRow("Альбом", live.album ?: "—")
                KeyValueRow("Источник", sourceLabel(live.source))
                KeyValueRow("Фаза", phaseLabel(live.phase))
                KeyValueRow("Позиция", "${formatMs(live.positionMs)} / ${formatMs(live.durationMs)}")
                KeyValueRow("В треке", formatMs(live.playedMs))
                KeyValueRow("mediaId", live.mediaId ?: "—")
                KeyValueRow("Ключей метаданных", live.metadataKeys.size.toString())
                if (live.metadataKeys.isNotEmpty()) {
                    Text(
                        text = live.metadataKeys.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard("Последние события") {
            if (state.recent.isEmpty()) {
                Text(
                    text = "Пока пусто. Включи музыку — события появятся здесь.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.recent.take(20).forEachIndexed { index, event ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    }
                    EventRow(event)
                }
            }
        }
    }
    }
}

@Composable
private fun SourceRow(
    name: String,
    value: String,
    active: Boolean,
    warn: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = when {
            warn -> StatusWarn
            active -> StatusOk
            else -> MaterialTheme.colorScheme.outline
        }
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (warn) StatusWarn else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(actionLabel)
            }
        }
    }
}

private fun mediaSessionText(state: LiveState): String {
    val live = state.live ?: return "нет активной сессии"
    return if (live.source == "media_session") {
        "активна · ${live.title ?: "без названия"}"
    } else {
        "не используется"
    }
}

private fun fytText(state: LiveState): String =
    if (state.fytMetadataReceived) {
        "данные есть" + state.fytMetadataAt?.let { " · ${formatClock(it)}" }.orEmpty()
    } else {
        "нет данных"
    }

private fun notificationText(state: LiveState): String = when {
    state.notificationAccess && state.notificationListenerConnected ->
        "доступ есть, listener подключён"
    state.notificationAccess -> "доступ есть, listener не подключён"
    else -> "доступа нет"
}

private fun accessibilityText(state: LiveState): String = when {
    state.accessibilityEnabled && state.accessibilityConnected -> "включено, сервис подключён"
    state.accessibilityEnabled -> "включено, сервис не подключён"
    else -> "выключено (резерв)"
}

private fun volumeText(state: LiveState): String {
    val mcu = state.mcuVolume?.let { value ->
        val max = state.mcuMax?.let { "/$it" }.orEmpty()
        "MCU $value$max"
    }
    return mcu ?: sourceLabel(state.volumeSource)
}

private fun phaseLabel(phase: MediaPhase): String = when (phase) {
    MediaPhase.PLAYING -> "играет"
    MediaPhase.PAUSED -> "пауза"
    MediaPhase.STOPPED -> "остановлен"
    MediaPhase.UNKNOWN -> "неизвестно"
}
