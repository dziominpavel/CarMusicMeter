package com.example.carmusicmeter.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayCacheTest {

    private val root: File = Files.createTempDirectory("cmm-cache").toFile()
    private val cache = PlayCache(root)

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `кэш возвращает факты и инвалидируется по источнику`() {
        val source = File(root, "events-2026-11-06.jsonl").apply { writeText("a") }
        val play = playRecord()

        cache.store("2026-11-06", source, listOf(play))

        assertEquals(listOf(play), cache.load("2026-11-06", source))
        assertTrue(cache.listDays().contains("2026-11-06"))

        source.appendText("b")
        assertNull(cache.load("2026-11-06", source))
    }

    @Test
    fun `кэш инвалидируется при смене версии builder'а`() {
        val source = File(root, "events-2026-11-06.jsonl").apply { writeText("a") }
        cache.store("2026-11-06", source, listOf(playRecord()))

        val nextVersion = PlayCache(root, PlayBuilder.VERSION + 1)

        assertNull(nextVersion.load("2026-11-06", source))
    }

    @Test
    fun `удалённый кэш возвращает null`() {
        val source = File(root, "events-2026-11-06.jsonl").apply { writeText("a") }
        cache.store("2026-11-06", source, listOf(playRecord()))

        cache.delete("2026-11-06")

        assertNull(cache.load("2026-11-06", source))
    }
}
