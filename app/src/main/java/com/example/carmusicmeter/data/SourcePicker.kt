package com.example.carmusicmeter.data

/**
 * Выбор между MediaSession и штатным плеером.
 *
 * При равенстве состояния выигрывает MediaSession: она точнее. Штатный плеер
 * побеждает, только если он играет, а MediaSession молчит или стоит на паузе.
 */
object SourcePicker {

    fun pick(
        mediaSessions: List<MediaSnapshot>,
        stock: MediaSnapshot?,
        currentPackage: String?,
    ): MediaSnapshot? {
        val media = SnapshotPicker.pick(mediaSessions, currentPackage)
        if (stock == null) return media
        if (media == null) return stock

        val mediaPlaying = media.phase == MediaPhase.PLAYING
        val stockPlaying = stock.phase == MediaPhase.PLAYING
        return when {
            mediaPlaying && !stockPlaying -> media
            stockPlaying && !mediaPlaying -> stock
            !mediaPlaying && !stockPlaying && currentPackage == stock.packageName -> stock
            else -> media
        }
    }
}
