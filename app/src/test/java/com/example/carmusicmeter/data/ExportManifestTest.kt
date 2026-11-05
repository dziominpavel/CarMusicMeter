package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class ExportManifestTest {

    private val info = ExportInfo(
        deviceId = "8c1f0b52-0000-0000-0000-000000000000",
        exportSeq = 7,
        appVersion = "0.4.2",
        versionCode = 2_000_100_042,
        manufacturer = "Teyes",
        model = "CC3",
        androidRelease = "10",
        sdkInt = 29,
    )

    @Test
    fun `манифест содержит идентичность устройства и номер выгрузки`() {
        val manifest = ExportManifest.build(info, emptyList(), EXPORTED_AT_MS)

        assertEquals("carmusicmeter-export", manifest["kind"])
        assertEquals(ExportManifest.SCHEMA_VERSION, manifest["schemaVersion"])
        assertEquals(info.deviceId, manifest["deviceId"])
        assertEquals(7L, manifest["exportSeq"])
        assertEquals("0.4.2", manifest["appVersion"])
        assertEquals(2_000_100_042, manifest["versionCode"])

        val device = manifest["device"] as Map<*, *>
        assertEquals("Teyes", device["manufacturer"])
        assertEquals("CC3", device["model"])
        assertEquals("10", device["android"])
        assertEquals(29, device["sdk"])
    }

    @Test
    fun `время выгрузки записано со смещением и в миллисекундах`() {
        val manifest = ExportManifest.build(info, emptyList(), EXPORTED_AT_MS)

        assertEquals(EXPORTED_AT_MS, manifest["exportedAtMs"])
        val parsed = OffsetDateTime.parse(manifest["exportedAt"] as String)
        assertEquals(EXPORTED_AT_MS, parsed.toInstant().toEpochMilli())
    }

    @Test
    fun `файлы перечислены с размером и контрольной суммой`() {
        val files = listOf(
            ExportFile("logs/events-2026-10-31.jsonl", 120, "aa"),
            ExportFile("snapshots/snapshot-1.json", 30, "bb"),
        )
        val manifest = ExportManifest.build(info, files, EXPORTED_AT_MS)

        val listed = manifest["files"] as List<*>
        assertEquals(2, listed.size)
        val first = listed[0] as Map<*, *>
        assertEquals("logs/events-2026-10-31.jsonl", first["path"])
        assertEquals(120L, first["size"])
        assertEquals("aa", first["sha256"])
        assertEquals(150L, manifest["totalBytes"])
    }

    @Test
    fun `манифест содержит диапазон и фактические даты`() {
        val manifest = ExportManifest.build(
            info = info,
            files = emptyList(),
            exportedAtMs = EXPORTED_AT_MS,
            range = ExportRangeInfo("days", 30, 100, 200),
            coverage = ExportCoverage(10, 20),
        )

        val range = manifest["range"] as Map<*, *>
        assertEquals("days", range["mode"])
        assertEquals(30, range["days"])
        assertEquals(100L, range["fromMs"])
        assertEquals(200L, range["toMs"])

        val coverage = manifest["coverage"] as Map<*, *>
        assertEquals(10L, coverage["fromMs"])
        assertEquals(20L, coverage["toMs"])
    }

    @Test
    fun `пустой экспорт остаётся валидным`() {
        val manifest = ExportManifest.build(info, emptyList(), EXPORTED_AT_MS)

        assertTrue((manifest["files"] as List<*>).isEmpty())
        assertEquals(0L, manifest["totalBytes"])
    }

    @Test
    fun `схема манифеста поднята до второй версии`() {
        assertEquals(2, ExportManifest.SCHEMA_VERSION)
    }

    @Test
    fun `манифест содержит статистику журналов`() {
        val logs = listOf(
            ExportLogStats(
                path = "logs/events-unverified.jsonl",
                events = 3,
                fromMs = 1_683_706_439_724L,
                toMs = 1_683_706_500_000L,
                unverified = 3,
                clockChanged = true,
            ),
            ExportLogStats(
                path = "logs/events-2026-11-05.jsonl",
                events = 206,
                fromMs = 1_793_859_523_984L,
                toMs = 1_793_893_253_551L,
            ),
        )

        val manifest = ExportManifest.build(
            info = info,
            files = emptyList(),
            exportedAtMs = EXPORTED_AT_MS,
            logs = logs,
        )

        val listed = manifest["logs"] as List<*>
        assertEquals(2, listed.size)
        val unverified = listed[0] as Map<*, *>
        assertEquals("logs/events-unverified.jsonl", unverified["path"])
        assertEquals(3, unverified["events"])
        assertEquals(3, unverified["unverified"])
        assertEquals(true, unverified["clockChanged"])
        val dated = listed[1] as Map<*, *>
        assertEquals(206, dated["events"])
        assertEquals(0, dated["unverified"])
        assertEquals(false, dated["clockChanged"])
    }

    private companion object {
        const val EXPORTED_AT_MS = 1_793_467_800_123L
    }
}
