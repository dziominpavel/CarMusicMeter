package com.example.carmusicmeter.data

import android.content.Context

/**
 * Протокол лаунчера FYT для штатного плеера.
 *
 * Штатный плеер не публикует MediaSession. На Teyes он называется
 * `com.yf.teyesspotify`, на других FYT — `com.syu.music`; лаунчер может
 * пересылать его состояние широковещательным `titlesReceiver`.
 */
object StockPlayerProtocol {

    const val TITLES_RECEIVER = "titlesReceiver"
    const val REMOVE_RECEIVER = "removeReceiver"

    const val EXTRA_TITLE = "title"
    const val EXTRA_ARTIST = "play_artist"
    const val EXTRA_ALBUM = "play_album"
    const val EXTRA_PATH = "play_path"
    const val EXTRA_PLAYING = "play_state"
    const val EXTRA_DURATION = "play_total"
    const val EXTRA_POSITION = "play_cur"
    const val EXTRA_SOURCE = "source"

    val PACKAGES = listOf("com.yf.teyesspotify", "com.syu.music")

    fun isStockPackage(packageName: String?): Boolean =
        packageName != null && packageName in PACKAGES

    /** Пакет, который реально установлен; иначе первый известный. */
    fun resolvePackage(context: Context): String {
        val manager = context.packageManager
        return PACKAGES.firstOrNull { packageName ->
            runCatching { manager.getPackageInfo(packageName, 0) }.isSuccess
        } ?: PACKAGES.first()
    }
}
