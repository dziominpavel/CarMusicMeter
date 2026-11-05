package com.example.carmusicmeter.data

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLogEnvelopeTest {

    private val root: File = Files.createTempDirectory("cmm-envelope").toFile()
    private val ts = 1_800_000_000_000L

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `каждое событие содержит bootId и monoMs`() {
        val log = EventLog(root, bootId = "boot42", monoClock = { 1_234L })

        log.append(
            LogEvent(
                ts = ts,
                type = "track_started",
                fields = linkedMapOf("trackKey" to "k", "title" to "T"),
            ),
        )

        val line = log.todayFile(ts).readLines().single()
        val json = JSONObject(line)
        assertEquals(ts, json.getLong("ts"))
        assertEquals("track_started", json.getString("type"))
        assertEquals("boot42", json.getString("bootId"))
        assertEquals(1_234L, json.getLong("monoMs"))
        assertEquals("k", json.getString("trackKey"))
    }

    @Test
    fun `монотонная метка не зависит от настенных часов`() {
        val log = EventLog(root, bootId = "boot1", monoClock = { 5_000L })
        log.append(LogEvent(ts = ts, type = "a", fields = emptyMap()))
        log.append(LogEvent(ts = ts + 60_000L, type = "b", fields = emptyMap()))

        val monos = log.todayFile(ts).readLines().map { JSONObject(it).getLong("monoMs") }

        assertEquals(listOf(5_000L, 5_000L), monos)
    }

    @Test
    fun `bootId по умолчанию различается между процессами`() {
        assertNotEquals(newBootId(), newBootId())
        assertTrue(newBootId().isNotEmpty())
    }

    @Test
    fun `непроверенные часы пишут в отдельный файл без даты`() {
        val log = EventLog(root, bootId = "b", monoClock = { 1L }, clockVerified = { false })

        log.append(LogEvent(ts = ts, type = "service_started", fields = emptyMap()))

        assertEquals("events-unverified.jsonl", log.unverifiedFile.name)
        assertEquals(listOf(log.unverifiedFile), log.logsDir.listFiles()?.toList())
        val json = JSONObject(log.unverifiedFile.readLines().single())
        assertEquals(false, json.getBoolean("clockVerified"))
        assertEquals("b", json.getString("bootId"))
        assertEquals(1L, log.unverifiedEvents)
    }

    @Test
    fun `после подтверждения часов журнал возвращается к файлу по дате`() {
        var verified = false
        val log = EventLog(root, bootId = "b", monoClock = { 1L }, clockVerified = { verified })

        log.append(LogEvent(ts = ts, type = "before", fields = emptyMap()))
        verified = true
        log.append(LogEvent(ts = ts + 1_000L, type = "after", fields = emptyMap()))

        assertEquals("before", JSONObject(log.unverifiedFile.readLines().single()).getString("type"))
        val after = JSONObject(log.todayFile(ts).readLines().single())
        assertEquals("after", after.getString("type"))
        assertEquals(true, after.getBoolean("clockVerified"))
        assertEquals(1L, log.unverifiedEvents)
    }
}
