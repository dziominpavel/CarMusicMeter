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
    val reason: String? = null,
)

/** Выбирает MCU, если он доступен, иначе AudioManager. Чистая логика для тестов. */
object VolumeSelector {

    /** Teyes CC3 показывает шкалу 0…43; на другой прошивке значение можно переопределить. */
    const val DEFAULT_MCU_MAX = 43

    fun select(
        mcu: McuVolume?,
        audioValue: Int,
        audioMax: Int,
        mcuMax: Int = DEFAULT_MCU_MAX,
        fallbackReason: String? = null,
    ): VolumeReading {
        if (mcu != null && mcu.volume != null) {
            return VolumeReading(
                value = mcu.volume,
                max = mcuMax.takeIf { it > 0 },
                source = VolumeSource.MCU,
                muted = mcu.muted == true,
                reason = mcu.reason ?: "mcu_available",
            )
        }
        return VolumeReading(
            value = audioValue,
            max = audioMax,
            source = VolumeSource.AUDIO,
            reason = mcu?.reason ?: fallbackReason ?: "audio_fallback",
        )
    }
}

/** Значение, полученное от вендорного sound-модуля. */
data class McuVolume(
    val volume: Int?,
    val muted: Boolean?,
    val updatedAtMs: Long,
    val reason: String? = null,
)
