package com.example.carmusicmeter.data

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * Запасной путь сохранения ZIP, когда на устройстве нет системного диалога
 * `ACTION_CREATE_DOCUMENT` (например, на магнитоле Teyes).
 *
 * `MediaStore.Downloads` на Android 10 позволяет создать файл в «Загрузках»
 * без разрешения на запись. Файл сначала помечается `IS_PENDING`, чтобы
 * файловый менеджер не показал недописанный архив.
 */
object ExportStorage {

    const val FOLDER = "CarMusicMeter"

    fun createDownloadsZip(
        resolver: ContentResolver,
        displayName: String,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("не удалось создать файл в Downloads")
    }

    fun publish(resolver: ContentResolver, uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        resolver.update(uri, values, null, null)
    }

    fun discard(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    fun displayPath(displayName: String): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$displayName"
}
