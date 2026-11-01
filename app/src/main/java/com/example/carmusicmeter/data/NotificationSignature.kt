package com.example.carmusicmeter.data

/**
 * Сигнатура медиа-уведомления без прогресса.
 *
 * `text`/`subText` и сырые `RemoteViews` не входят: обновление времени позиции
 * не должно создавать событие. Для штатного плеера используется metaSignature
 * уже разобранного состояния.
 */
object NotificationSignature {

    fun build(
        packageName: String,
        title: String?,
        bigText: String?,
        template: String?,
        actions: List<String>,
        mediaLike: Boolean,
        stockSignature: String?,
    ): String = listOf(
        packageName,
        title,
        bigText,
        template,
        actions,
        mediaLike,
        stockSignature,
    ).joinToString("|")
}
