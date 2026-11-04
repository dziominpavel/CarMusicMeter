package com.example.carmusicmeter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.carmusicmeter.AppVersion
import com.example.carmusicmeter.BuildConfig
import com.example.carmusicmeter.data.LiveState
import com.example.carmusicmeter.data.StorageStats
import com.example.carmusicmeter.ui.theme.StatusOk
import com.example.carmusicmeter.ui.theme.StatusWarn

@Composable
fun SettingsScreen(
    state: LiveState,
    autoStart: Boolean,
    storageStats: StorageStats?,
    retentionDays: Int,
    onBack: () -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onRetentionChange: (Int) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val ready = listOf(
        state.notificationAccess,
        state.batteryUnrestricted,
        autoStart,
    ).count { it }
    var helpExpanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

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
        ScreenHeader(title = "Настройки", onBack = onBack)

        SectionCard("Готовность к машине") {
            Text(
                text = if (ready == 3) "Готово к машине" else "Настроено $ready из 3",
                style = MaterialTheme.typography.titleMedium,
                color = if (ready == 3) StatusOk else StatusWarn,
            )
            ReadinessRow(
                title = "Доступ к уведомлениям",
                detail = if (state.notificationAccess) "включён" else "выключен",
                ok = state.notificationAccess,
                actionLabel = if (state.notificationAccess) "Настроить" else "Открыть",
                onAction = onOpenNotificationAccess,
            )
            ReadinessRow(
                title = "Батарея",
                detail = if (state.batteryUnrestricted) "без ограничений" else "может быть ограничена",
                ok = state.batteryUnrestricted,
                actionLabel = "Настроить",
                onAction = onOpenBatterySettings,
            )
            SettingsRow(
                title = "Автозапуск",
                subtitle = "После загрузки и при появлении музыки",
                trailing = {
                    Switch(checked = autoStart, onCheckedChange = onAutoStartChange)
                },
            )
            if (state.teyesRelevant) {
                ReadinessRow(
                    title = "Чтение интерфейса",
                    detail = accessibilityText(state),
                    ok = state.accessibilityEnabled && state.accessibilityConnected,
                    optional = true,
                    actionLabel = if (state.accessibilityEnabled) "Настроить" else "Включить",
                    onAction = onOpenAccessibilitySettings,
                )
            }
        }

        SectionCard("Хранение") {
            if (storageStats == null) {
                Text(
                    text = "Считаю…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = storageStats.oldestLogDate?.let { "Журнал с $it" } ?: "Журнала пока нет",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Журнал: ${storageStats.logFiles} ${pluralFiles(storageStats.logFiles)} · " +
                        formatBytes(storageStats.logBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Снимки: ${storageStats.snapshotFiles} · ${formatBytes(storageStats.snapshotBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Срок хранения",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RetentionPresets(
                retentionDays = retentionDays,
                onRetentionChange = onRetentionChange,
            )
        }

        SectionCard("Помощь") {
            SettingsRow(
                title = "Проверка в машине",
                subtitle = "Четыре шага перед выездом",
                onClick = { helpExpanded = !helpExpanded },
                trailing = {
                    Text(
                        text = if (helpExpanded) "Свернуть" else "Открыть",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            if (helpExpanded) {
                Text(
                    text = "1. Включи музыку в штатном плеере и подожди 10–15 секунд.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "2. Пропусти трек, сделай паузу, измени громкость.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "3. Нажми «Экспорт логов» и сохрани ZIP на флешку: снимок состояния попадёт в архив сам.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "4. Если трек не появляется — пришли ZIP: в снимке будет видно, что отдаёт плеер.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard("О приложении") {
            KeyValueRow("Версия", AppVersion.name(context))
            KeyValueRow("Сборка", BuildConfig.BUILD_DATE)
        }
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
        modifier = modifier.height(48.dp),
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

private fun accessibilityText(state: LiveState): String = when {
    state.accessibilityEnabled && state.accessibilityConnected -> "включено, сервис подключён"
    state.accessibilityEnabled -> "включено, сервис не подключён"
    else -> "выключено (резерв)"
}
