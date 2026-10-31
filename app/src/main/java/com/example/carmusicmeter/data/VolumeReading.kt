package com.example.carmusicmeter.data

enum class VolumeSource(val value: String) {
    AUDIO("audio"),
    MCU("mcu"),
}

/**
 * Одно показание громкости. `max = null` означает, что источник не сообщает
 * максимум (так ведёт себя MCU Teyes); подставлять чужой максимум нельзя.
 */
data class VolumeReading(
    val value: Int,
    val max: Int?,
    val source: VolumeSource,
    val muted: Boolean = false,
)

/** Выбирает MCU, если он доступен, иначе AudioManager. Чистая логика для тестов. */
object VolumeSelector {

    fun select(
        mcu: McuVolume?,
        audioValue: Int,
        audioMax: Int,
    ): VolumeReading {
        if (mcu != null && mcu.volume != null) {
            val value = if (mcu.muted == true) 0 else mcu.volume
            return VolumeReading(
                value = value,
                max = null,
                source = VolumeSource.MCU,
                muted = mcu.muted == true,
            )
        }
        return VolumeReading(
            value = audioValue,
            max = audioMax,
            source = VolumeSource.AUDIO,
        )
    }
}

/** Значение, полученное от вендорного sound-модуля. */
data class McuVolume(
    val volume: Int?,
    val muted: Boolean?,
    val updatedAtMs: Long,
)
