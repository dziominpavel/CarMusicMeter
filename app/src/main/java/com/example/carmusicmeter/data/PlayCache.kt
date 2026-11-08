package com.example.carmusicmeter.data

import java.io.File
import org.json.JSONObject

/**
 * Кэш фактов, построенных из истории. Файл на день; первая строка — мета,
 * по которой видно, что кэш соответствует источнику и версии builder'а.
 *
 * Кэш производный: его можно удалить, и факты пересоберутся из журнала.
 */
class PlayCache(
    private val baseDir: File,
    private val builderVersion: Int = PlayBuilder.VERSION,
) {

    val dir: File get() = cacheDir(baseDir)

    fun file(day: String): File = File(dir, "plays-$day.jsonl")

    fun load(day: String, source: File): List<PlayRecord>? {
        val file = file(day)
        if (!file.isFile || !source.isFile) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.isEmpty()) return null
        val meta = runCatching { JSONObject(lines.first()) }.getOrNull() ?: return null
        if (!meta.optBoolean("_meta", false)) return null
        if (meta.optInt("builderVersion", -1) != builderVersion) return null
        if (meta.optLong("sourceSize", -1L) != source.length()) return null
        if (meta.optLong("sourceMtime", -1L) != source.lastModified()) return null
        val plays = ArrayList<PlayRecord>(lines.size - 1)
        for (index in 1 until lines.size) {
            val play = PlayRecord.fromJson(lines[index]) ?: return null
            plays += play
        }
        return plays
    }

    fun store(day: String, source: File, plays: List<PlayRecord>) {
        dir.mkdirs()
        val target = file(day)
        val temp = File(dir, "plays-$day.tmp")
        temp.bufferedWriter(Charsets.UTF_8).use { out ->
            out.write(
                JSONObject().apply {
                    put("_meta", true)
                    put("builderVersion", builderVersion)
                    put("sourceSize", source.length())
                    put("sourceMtime", source.lastModified())
                }.toString(),
            )
            out.newLine()
            plays.forEach { play ->
                out.write(play.toJsonLine())
                out.newLine()
            }
        }
        target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    fun delete(day: String) {
        file(day).delete()
    }

    fun listDays(): List<String> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("plays-") && it.name.endsWith(".jsonl") }
            ?.map { it.name.removePrefix("plays-").removeSuffix(".jsonl") }
            ?.sorted()
            .orEmpty()

    companion object {
        fun cacheDir(baseDir: File): File = File(baseDir, "report/cache/v1")
    }
}
