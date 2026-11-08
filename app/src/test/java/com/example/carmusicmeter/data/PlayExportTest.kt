package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayExportTest {

    @Test
    fun `ZIP получает jsonl, csv, словарь и схему`() {
        val plays = listOf(
            playRecord(playId = "p1", endReason = "skipped"),
            playRecord(playId = "o1", endReason = "open", open = true),
        )

        val bundle = PlayExport.build(plays, ExportRange.ALL)
        val paths = bundle.generated.map { it.path }.toSet()

        assertEquals(
            setOf("plays/plays.jsonl", "plays/plays.csv", "DATA_DICTIONARY.csv", "SCHEMA.md"),
            paths,
        )
        assertEquals(1, bundle.stats.total)
        assertEquals(1, bundle.stats.open)
        assertEquals(1, bundle.stats.rated)
        assertEquals(0, bundle.stats.censored)

        val jsonl = bundle.generated.first { it.path == "plays/plays.jsonl" }
            .bytes.toString(Charsets.UTF_8)
        assertEquals(2, jsonl.lines().count { it.isNotBlank() })
        assertEquals("p1", PlayRecord.fromJson(jsonl.lines().first())?.playId)

        val csv = bundle.generated.first { it.path == "plays/plays.csv" }
            .bytes.toString(Charsets.UTF_8)
        assertTrue(csv.startsWith("\uFEFFplaysSchemaVersion"))
        assertTrue(csv.contains("mcuStart"))

        val dictionary = bundle.generated.first { it.path == "DATA_DICTIONARY.csv" }
            .bytes.toString(Charsets.UTF_8)
        assertTrue(dictionary.contains("playId"))
        assertTrue(dictionary.contains("durationMs"))

        val schema = bundle.generated.first { it.path == "SCHEMA.md" }
            .bytes.toString(Charsets.UTF_8)
        assertTrue(schema.contains("playsSchemaVersion"))
        assertTrue(schema.contains("clockVerified"))
    }
}
