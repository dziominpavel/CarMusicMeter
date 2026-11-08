package com.example.carmusicmeter.data

import com.example.carmusicmeter.data.metrics.MetricsEngine
import com.example.carmusicmeter.data.metrics.ReportPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportExporterTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `HTML, CSV и промпт собираются из одной модели`() {
        val play = playRecord(
            trackKey = "k",
            title = "<Track>, \"A\"",
            artist = "A & B",
            startedAt = now - 60_000L,
            endedAt = now,
            playedMs = 60_000L,
            endReason = "skipped",
        )
        val model = MetricsEngine.compute(
            plays = listOf(play),
            period = ReportPeriod.DAYS_7,
            captureGaps = 1,
            nowMs = now,
        )

        val bundle = ReportExporter.build(model)
        val paths = bundle.generated.map { it.path }.toSet()
        assertEquals(setOf("tracks.csv", "report.html", "ai-prompt.md"), paths)
        assertEquals(1, bundle.stats.tracks)
        assertEquals(1, bundle.stats.plays)
        assertEquals(1, bundle.stats.scoredTracks)
        assertEquals("v1", bundle.stats.affinityVersion)

        val html = bundle.generated.first { it.path == "report.html" }.bytes.toString(Charsets.UTF_8)
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("Оценка v1"))
        assertTrue(html.contains("Оценка — v1"))
        assertTrue(html.contains("&lt;Track&gt;"))
        assertTrue(html.contains("A &amp; B"))
        assertFalse(html.contains("http://"))
        assertFalse(html.contains("https://"))

        val csv = bundle.generated.first { it.path == "tracks.csv" }.bytes.toString(Charsets.UTF_8)
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"<Track>, \"\"A\"\"\""))
        assertTrue(csv.contains("affinity"))
        assertTrue(csv.contains("affinity_reasons"))

        val prompt = bundle.generated.first { it.path == "ai-prompt.md" }
            .bytes.toString(Charsets.UTF_8)
        assertTrue(prompt.contains("не выдумывай"))
        assertTrue(prompt.contains("## Задание"))
        assertTrue(prompt.contains("Прослушиваний: 1"))
    }
}
