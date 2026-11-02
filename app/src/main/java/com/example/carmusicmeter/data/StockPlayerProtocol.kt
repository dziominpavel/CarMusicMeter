package com.example.carmusicmeter.data

import android.content.Context

/**
 * Протокол лаунчера FYT для штатного плеера.
 *
 * Штатный плеер не публикует MediaSession. На проверенном Teyes CC3 это
 * `com.syu.music`; `com.yf.teyesspotify` остаётся резервным именем для других
 * прошивок. Лаунчер может пересылать состояние широковещательным
 * `titlesReceiver`, а сам плеер — сервисом `com.fyt.launcher.music`.
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

    const val STOCK_PACKAGE = "com.syu.music"

    val PACKAGES = listOf(STOCK_PACKAGE, "com.yf.teyesspotify")

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
