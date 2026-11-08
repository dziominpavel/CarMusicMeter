package com.example.carmusicmeter.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportManifestPlaysTest {

    @Test
    fun `манифест содержит блок фактов`() {
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
        val plays = ExportPlaysStats(
            total = 3,
            live = 2,
            history = 1,
            open = 1,
            rated = 2,
            censored = 1,
            fromMs = 10L,
            toMs = 20L,
        )

        val manifest = ExportManifest.build(
            info = info,
            files = emptyList(),
            exportedAtMs = 1_000L,
            plays = plays,
        )

        val block = manifest["plays"] as Map<*, *>
        assertEquals(PlayRecord.SCHEMA_VERSION, block["playsSchemaVersion"])
        assertEquals(3, block["total"])
        assertEquals(2, block["live"])
        assertEquals(1, block["history"])
        assertEquals(1, block["open"])
        assertEquals(2, block["rated"])
        assertEquals(1, block["censored"])
        assertEquals(10L, block["fromMs"])
        assertEquals(20L, block["toMs"])
    }
}
