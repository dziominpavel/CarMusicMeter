package com.example.carmusicmeter.data

import java.util.Locale

/**
 * Логическая идентичность трека.
 *
 * Ключ не включает длительность, `mediaId`, путь и источник: они могут
 * появиться позже или смениться у того же трека. Для различения на ПК эти поля
 * остаются в событиях.
 */
object TrackIdentity {

    private val whitespace = Regex("""\s+""")

    fun normalize(value: String?): String =
        value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(whitespace, " ")
            .orEmpty()

    fun key(
        packageName: String,
        title: String?,
        artist: String?,
        sessionTag: String?,
    ): String {
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isBlank()) {
            return "$packageName::session::${sessionTag ?: "default"}"
        }
        return "$packageName::$normalizedTitle::${normalize(artist)}"
    }

    fun sameTrack(first: MediaSnapshot, second: MediaSnapshot): Boolean {
        if (first.packageName != second.packageName) return false
        val firstTitle = normalize(first.title)
        val secondTitle = normalize(second.title)
        if (firstTitle.isBlank() || secondTitle.isBlank()) return firstTitle == secondTitle
        if (firstTitle != secondTitle) return false
        return artistsCompatible(first.artist, second.artist)
    }

    /** Пустой исполнитель считается ещё не полученным, а не другим треком. */
    fun artistsCompatible(first: String?, second: String?): Boolean {
        val a = normalize(first)
        val b = normalize(second)
        return a.isBlank() || b.isBlank() || a == b
    }
}
