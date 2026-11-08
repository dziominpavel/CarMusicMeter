package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportManifestReportTest {

    @Test
    fun `манифест содержит блок отчёта`() {
        val info = ExportInfo(
            deviceId = "dev",
            exportSeq = 1,
            appVersion = "1.0",
            versionCode = 1,
            manufacturer = "Teyes",
            model = "CC3",
            androidRelease = "10",
            sdkInt = 29,
        )
        val report = ExportReportStats(
            period = "7d",
            days = 7,
            tracks = 3,
            plays = 9,
            generatedAtMs = 1_000L,
        )

        val manifest = ExportManifest.build(
            info = info,
            files = emptyList(),
            exportedAtMs = 1_000L,
            report = report,
        )

        val block = manifest["report"] as Map<*, *>
        assertEquals("7d", block["period"])
        assertEquals(7, block["days"])
        assertEquals(3, block["tracks"])
        assertEquals(9, block["plays"])
        assertEquals(1_000L, block["generatedAtMs"])
    }
}
