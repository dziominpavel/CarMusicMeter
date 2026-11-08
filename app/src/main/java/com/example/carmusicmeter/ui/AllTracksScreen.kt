package com.example.carmusicmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.carmusicmeter.data.metrics.ReportModel

@Composable
fun AllTracksScreen(
    model: ReportModel,
    onBack: () -> Unit,
    onTrackClick: (String) -> Unit,
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
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            ScreenHeader(title = "Все треки", onBack = onBack)
            Text(
                text = "Оценка — v1: громкость и время. Внизу списка — потенциально «не нравится».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
            if (model.rankedTracks.isEmpty()) {
                Text(
                    text = "За этот период нет треков.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(items = model.rankedTracks, key = { it.trackKey }) { track ->
                        ReportTrackRow(
                            track = track,
                            metric = track.affinity?.toString() ?: "—",
                            caption = if (track.affinity == null) null else "v1",
                            onClick = { onTrackClick(track.trackKey) },
                        )
                    }
                }
            }
        }
    }
}
