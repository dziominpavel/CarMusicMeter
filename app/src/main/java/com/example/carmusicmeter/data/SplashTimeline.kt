package com.example.carmusicmeter.data

/**
 * Состояние сцены запуска в один момент времени: все фазы как доли 0..1.
 *
 * @property alpha прозрачность медальона (появление);
 * @property scale масштаб медальона относительно номинала;
 * @property glow яркость свечения вокруг диска;
 * @property arc доля пройденной дуги по ободу;
 * @property glint доля прорисованной волны.
 */
data class SplashFrame(
    val alpha: Float,
    val scale: Float,
    val glow: Float,
    val arc: Float,
    val glint: Float,
)

/**
 * Таймлайн экрана запуска: единственный прогресс 0..1 задаёт все фазы.
 *
 * Раскладка повторяет композицию «Instrument Wake» (1.42 с) из Html-варианта и
 * живёт в чистом коде, чтобы заставка шла одинаково на любом устройстве и
 * проверялась JVM-тестом без Android.
 */
object SplashTimeline {

    /** Длительность анимации от первого кадра до последнего. */
    const val ANIMATION_MS = 1400

    /** Сколько последний кадр держится перед гашением. */
    const val HOLD_MS = 150L

    /** Гашение в главный экран. */
    const val FADE_OUT_MS = 200

    /** Гашение после касания: короче обычного, потому что действие явное. */
    const val SKIP_FADE_MS = 120

    /**
     * Страховка от зависшей корутины. Заведомо длиннее таймлайна, чтобы не
     * мочь оборвать анимацию на середине.
     */
    const val WATCHDOG_MS = 2500L

    /** Появление по прозрачности заканчивается к 22 % анимации. */
    private const val ALPHA_END = 0.22f

    /** Пик масштаба — к 72 % анимации, дальше возврат к номиналу. */
    private const val SCALE_PEAK_AT = 0.72f

    /** Пик свечения — на 48 %. */
    private const val GLOW_PEAK_AT = 0.48f

    /** Дуга идёт с 0.12 с до 1.14 с исходных 1.42 с. */
    private const val ARC_START = 0.085f
    private const val ARC_END = 0.80f

    /** Волна прорисовывается с 0.52 с до 1.17 с. */
    private const val GLINT_START = 0.366f
    private const val GLINT_END = 0.824f

    private const val SCALE_FROM = 0.965f
    private const val SCALE_PEAK = 1.012f

    /** Кадр сцены для прогресса [progress]; значения вне 0..1 прижимаются. */
    fun frame(progress: Float): SplashFrame {
        val p = progress.coerceIn(0f, 1f)
        return SplashFrame(
            alpha = ramp(p, 0f, ALPHA_END),
            scale = scaleAt(p),
            glow = glowAt(p),
            arc = ramp(p, ARC_START, ARC_END),
            glint = ramp(p, GLINT_START, GLINT_END),
        )
    }

    private fun scaleAt(p: Float): Float = if (p <= SCALE_PEAK_AT) {
        lerp(SCALE_FROM, SCALE_PEAK, easeOut(p / SCALE_PEAK_AT))
    } else {
        lerp(SCALE_PEAK, 1f, easeOut((p - SCALE_PEAK_AT) / (1f - SCALE_PEAK_AT)))
    }

    private fun glowAt(p: Float): Float = if (p <= GLOW_PEAK_AT) {
        easeOut(p / GLOW_PEAK_AT)
    } else {
        1f - 0.5f * easeOut((p - GLOW_PEAK_AT) / (1f - GLOW_PEAK_AT))
    }

    private fun ramp(p: Float, start: Float, end: Float): Float =
        if (p <= start) 0f else ((p - start) / (end - start)).coerceIn(0f, 1f)

    private fun easeOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val inverse = 1f - x
        return 1f - inverse * inverse * inverse
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
}
