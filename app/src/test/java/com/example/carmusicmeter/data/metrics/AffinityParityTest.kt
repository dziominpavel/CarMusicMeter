package com.example.carmusicmeter.data.metrics

import com.example.carmusicmeter.data.PlayRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Паритет с scripts/report.py на общей фикстуре. */
class AffinityParityTest {

    @Test
    fun `Kotlin и Python дают одинаковые оценки на общей фикстуре`() {
        val plays = resource("/affinity/plays.jsonl")
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { PlayRecord.fromJson(it) }
        val expected = JSONObject(resource("/affinity/expected.json"))
        val model = MetricsEngine.compute(plays, ReportPeriod.ALL, 0, 1_800_000_100_000L)

        expected.keys().forEach { key ->
            val track = model.track(key)
            requireNotNull(track) { "нет трека $key" }
            val want = expected.getJSONObject(key)
            assertEquals("оценка $key", want.getInt("affinity"), track.affinity)
            assertEquals("прослушиваний $key", want.getInt("plays"), track.affinityPlays)
        }
    }

    private fun resource(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("нет ресурса $path")
}
