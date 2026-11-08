package com.example.carmusicmeter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.carmusicmeter.data.ExportPlan
import com.example.carmusicmeter.data.ExportRange
import com.example.carmusicmeter.data.StoredKind

@Composable
fun ExportDialog(
    range: ExportRange,
    plan: ExportPlan?,
    freshSnapshot: Boolean,
    reportHint: String? = null,
    onRangeChange: (ExportRange) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Экспорт логов") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Период",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = range == ExportRange.ALL,
                        onClick = { onRangeChange(ExportRange.ALL) },
                        label = { Text(ExportRange.ALL.label) },
                    )
                    FilterChip(
                        selected = range == ExportRange.DAYS_90,
                        onClick = { onRangeChange(ExportRange.DAYS_90) },
                        label = { Text(ExportRange.DAYS_90.label) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = range == ExportRange.DAYS_30,
                        onClick = { onRangeChange(ExportRange.DAYS_30) },
                        label = { Text(ExportRange.DAYS_30.label) },
                    )
                    FilterChip(
                        selected = range == ExportRange.DAYS_7,
                        onClick = { onRangeChange(ExportRange.DAYS_7) },
                        label = { Text(ExportRange.DAYS_7.label) },
                    )
                }
                Text(
                    text = when {
                        plan == null -> "Считаю объём…"
                        plan.isEmpty -> "Нет данных за период"
                        else -> "${plan.files.size} ${pluralFiles(plan.files.size)} · ${formatBytes(plan.totalBytes)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (plan?.isEmpty == true) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (plan != null && !plan.isEmpty) {
                    Text(
                        text = breakdown(plan),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                reportHint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (freshSnapshot) {
                    Text(
                        text = "Свежий снимок состояния уже в архиве",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = plan != null && !plan.isEmpty,
            ) {
                Text("Экспортировать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

/** «3 журнала · 12 снимков · 1 crash-файл» — что именно уедет в ZIP. */
private fun breakdown(plan: ExportPlan): String {
    val logs = plan.files.count { it.kind == StoredKind.LOG }
    val snapshots = plan.files.count { it.kind == StoredKind.SNAPSHOT }
    val crashes = plan.files.count { it.kind == StoredKind.CRASH }
    return listOfNotNull(
        logs.takeIf { it > 0 }?.let { "$it ${pluralWord(it, "журнал", "журнала", "журналов")}" },
        snapshots.takeIf { it > 0 }?.let { "$it ${pluralWord(it, "снимок", "снимка", "снимков")}" },
        crashes.takeIf { it > 0 }?.let { "$it ${pluralWord(it, "crash-файл", "crash-файла", "crash-файлов")}" },
    ).joinToString(" · ")
}

@Composable
fun ExportProgressDialog() {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Экспортирую…") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text("Пишу ZIP. Не закрывай приложение.")
            }
        },
        confirmButton = { },
    )
}
