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

@Composable
fun ExportDialog(
    range: ExportRange,
    plan: ExportPlan?,
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
