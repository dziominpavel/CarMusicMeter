package com.example.carmusicmeter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.carmusicmeter.AppVersion
import com.example.carmusicmeter.BuildConfig
import com.example.carmusicmeter.data.ExportRange
import com.example.carmusicmeter.data.LiveState
import com.example.carmusicmeter.data.LiveTrack
import com.example.carmusicmeter.data.LogEvent
import com.example.carmusicmeter.data.MediaPhase
import com.example.carmusicmeter.data.StorageStats
import com.example.carmusicmeter.ui.theme.AccentContainer
import com.example.carmusicmeter.ui.theme.StatusOk
import com.example.carmusicmeter.ui.theme.StatusWarn
import java.util.Locale

private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun ProbeScreen(
    state: LiveState,
    autoStart: Boolean,
    storageStats: StorageStats?,
    retentionDays: Int,
    exportRange: ExportRange,
    onAutoStartChange: (Boolean) -> Unit,
    onRetentionChange: (Int) -> Unit,
    onExportRangeChange: (ExportRange) -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onSnapshot: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header()
        StatusCard(
            state = state,
            onStartService = onStartService,
            onStopService = onStopService,
            onOpenNotificationAccess = onOpenNotificationAccess,
            onOpenBatterySettings = onOpenBatterySettings,
        )
        LiveCard(state)
        StockPlayerCard(state = state, onOpenAccessibilitySettings = onOpenAccessibilitySettings)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSnapshot,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
            ) {
                Text("Снять срез")
            }
            OutlinedButton(
                onClick = onExport,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
            ) {
                Text("Экспорт логов")
            }
        }
        StatsRow(state)
        DataCard(
            stats = storageStats,
            retentionDays = retentionDays,
            exportRange = exportRange,
            onRetentionChange = onRetentionChange,
            onExportRangeChange = onExportRangeChange,
        )
        AutoStartCard(autoStart = autoStart, onAutoStartChange = onAutoStartChange)
        RecentEventsCard(state)
        HintCard()
    }
}

@Composable
private fun Header() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "CarMusicMeter",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "v${AppVersion.name(context)} • сборка ${BuildConfig.BUILD_DATE}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(
    state: LiveState,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusRow(
                label = "Доступ к уведомлениям",
                value = if (state.notificationAccess) "включён" else "выключен",
                ok = state.notificationAccess,
                actionLabel = if (state.notificationAccess) "Настройки" else "Открыть",
                onAction = onOpenNotificationAccess,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            StatusRow(
                label = "Служба наблюдения",
                value = if (state.serviceRunning) "работает" else "остановлена",
                ok = state.serviceRunning,
                actionLabel = if (state.serviceRunning) "Стоп" else "Запустить",
                onAction = if (state.serviceRunning) onStopService else onStartService,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            StatusRow(
                label = "Батарея",
                value = if (state.batteryUnrestricted) "без ограничений" else "может быть ограничена",
                ok = state.batteryUnrestricted,
                actionLabel = "Настроить",
                onAction = onOpenBatterySettings,
            )
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    ok: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(if (ok) StatusOk else StatusWarn, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TextButton(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun LiveCard(state: LiveState) {
    val live = state.live
    Card(
        shape = CardShape,
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LiveTrackBody(live)
            }
            if (state.error != null) {
                Text(
                    text = "Диагностика: ${state.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LiveTrackBody(live: LiveTrack) {
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
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "в треке ${formatMs(live.playedMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Text(
            text = "позиция ${formatMs(position)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoPill(volumeLabel(live))
        InfoPill("источник: ${sourceLabel(live.source)}")
    }
    Text(
        text = "${live.metadataKeys.size} ключей · ${live.packageName}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StockPlayerCard(
    state: LiveState,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val stockSource = state.stockSource
        ?: state.live?.source?.takeIf { it != "media_session" }
    val hasStock = stockSource != null
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Штатный плеер",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = (if (hasStock) StatusOk else StatusWarn).copy(alpha = 0.16f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = if (hasStock) "данные есть" else "нет данных",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasStock) StatusOk else StatusWarn,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                text = if (hasStock) {
                    "Источник: ${sourceLabel(stockSource!!)}. Название и исполнитель попадают в журнал."
                } else {
                    "MediaSession не публикуется. Включи чтение интерфейса — приложение будет читать название в открытом окне плеера."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Чтение интерфейса", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (state.accessibilityEnabled) "включено" else "выключено",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.accessibilityEnabled) StatusOk else StatusWarn,
                    )
                }
                TextButton(onClick = onOpenAccessibilitySettings) {
                    Text(if (state.accessibilityEnabled) "Настройки" else "Включить")
                }
            }
            Text(
                text = "Громкость: ${sourceLabel(state.volumeSource)}" +
                    state.mcuVolume?.let { value ->
                        val max = state.mcuMax?.let { "/$it" }.orEmpty()
                        " · MCU $value$max"
                    }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.teyesAvailable) {
                Text(
                    text = "MCU недоступен — используется громкость Android",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PhaseBadge(phase: MediaPhase) {
    val (label, color) = when (phase) {
        MediaPhase.PLAYING -> "играет" to StatusOk
        MediaPhase.PAUSED -> "пауза" to StatusWarn
        MediaPhase.STOPPED -> "остановлен" to MaterialTheme.colorScheme.onSurfaceVariant
        MediaPhase.UNKNOWN -> "неизвестно" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun InfoPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun StatsRow(state: LiveState) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            label = "событий сегодня",
            value = state.eventsToday.toString(),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "треков",
            value = state.tracksToday.toString(),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "сессий",
            value = state.sessionsSeen.toString(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DataCard(
    stats: StorageStats?,
    retentionDays: Int,
    exportRange: ExportRange,
    onRetentionChange: (Int) -> Unit,
    onExportRangeChange: (ExportRange) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Данные",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stats == null) {
                Text("Считаю…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    text = stats.oldestLogDate?.let { "Журнал с $it" } ?: "Журнала пока нет",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Журнал: ${stats.logFiles} файлов · ${formatBytes(stats.logBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Снимки: ${stats.snapshotFiles} · ${formatBytes(stats.snapshotBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Срок хранения",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RetentionPresets(
                retentionDays = retentionDays,
                onRetentionChange = onRetentionChange,
            )
            Text(
                text = "Период экспорта",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExportPresets(
                exportRange = exportRange,
                onExportRangeChange = onExportRangeChange,
            )
        }
    }
}

@Composable
private fun RetentionPresets(
    retentionDays: Int,
    onRetentionChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        CompactChip("Бессрочно", retentionDays == 0, Modifier.weight(1.2f)) { onRetentionChange(0) }
        CompactChip("1 год", retentionDays == 365, Modifier.weight(1f)) { onRetentionChange(365) }
        CompactChip("180 дней", retentionDays == 180, Modifier.weight(1.1f)) { onRetentionChange(180) }
        CompactChip("90 дней", retentionDays == 90, Modifier.weight(1f)) { onRetentionChange(90) }
    }
}

@Composable
private fun ExportPresets(
    exportRange: ExportRange,
    onExportRangeChange: (ExportRange) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        CompactChip(ExportRange.ALL.label, exportRange == ExportRange.ALL, Modifier.weight(1.3f)) {
            onExportRangeChange(ExportRange.ALL)
        }
        CompactChip(ExportRange.DAYS_90.label, exportRange == ExportRange.DAYS_90, Modifier.weight(1f)) {
            onExportRangeChange(ExportRange.DAYS_90)
        }
        CompactChip(ExportRange.DAYS_30.label, exportRange == ExportRange.DAYS_30, Modifier.weight(1f)) {
            onExportRangeChange(ExportRange.DAYS_30)
        }
        CompactChip(ExportRange.DAYS_7.label, exportRange == ExportRange.DAYS_7, Modifier.weight(1f)) {
            onExportRangeChange(ExportRange.DAYS_7)
        }
    }
}

@Composable
private fun CompactChip(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    val foreground = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(10.dp),
        color = background,
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            },
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = foreground,
            )
        }
    }
}

@Composable
private fun AutoStartCard(autoStart: Boolean, onAutoStartChange: (Boolean) -> Unit) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Автозапуск",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "После загрузки, при появлении музыки и при открытии приложения. " +
                        "В Teyes дополнительно: Настройки → Пользователь → Настройки приложений → CarMusicMeter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoStart, onCheckedChange = onAutoStartChange)
        }
    }
}

@Composable
private fun RecentEventsCard(state: LiveState) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Последние события",
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.recent.isEmpty()) {
                Text(
                    text = "Пока пусто. Включи музыку — события появятся здесь.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.recent.take(12).forEachIndexed { index, event ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    }
                    EventRow(event)
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: LogEvent) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = formatClock(event.ts),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(62.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = event.type,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = event.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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

private fun sourceLabel(source: String?): String = when (source) {
    "media_session" -> "MediaSession"
    "fyt_broadcast" -> "лаунчер"
    "accessibility" -> "интерфейс"
    "notification" -> "уведомление"
    "mcu" -> "MCU"
    "audio" -> "Android"
    else -> source ?: "—"
}

@Composable
private fun HintCard() {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = AccentContainer.copy(alpha = 0.55f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Проверка в машине",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "1. Включи музыку в штатном плеере и подожди 10–15 секунд.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "2. Пропусти трек, сделай паузу, измени громкость.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "3. Нажми «Снять срез», затем «Экспорт логов» и сохрани ZIP на флешку.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "4. Если карточка пуста — пришли ZIP: в снимке будет видно, что отдаёт плеер.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
