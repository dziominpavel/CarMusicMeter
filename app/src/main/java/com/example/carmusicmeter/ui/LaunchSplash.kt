package com.example.carmusicmeter.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.carmusicmeter.R
import com.example.carmusicmeter.data.SplashFrame
import com.example.carmusicmeter.data.SplashTimeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Доля меньшей стороны экрана, которую занимает медальон. */
private const val MEDALLION_FRACTION = 0.88f

/** Радиус обегающей дуги от стороны медальона. */
private const val ARC_RADIUS_FRACTION = 0.417f

/** Толщина дуги от стороны медальона. */
private const val ARC_STROKE_FRACTION = 0.019f

/** Окно прорисовки волны внутри арта — те же доли, что в Html-композиции. */
private const val GLINT_LEFT = 0.14f
private const val GLINT_TOP = 0.375f
private const val GLINT_WIDTH = 0.72f
private const val GLINT_HEIGHT = 0.20f

/**
 * Стопы градиента дуги. Тёплый участок приходится на 3 часа — туда, где
 * оранжевый стоит в самом арте, поэтому дуга не спорит с запечённым ободом.
 */
private val ARC_COLORS = arrayOf(
    0.00f to Color(0xFF6A5BFF),
    0.14f to Color(0xFF8B55FF),
    0.22f to Color(0xFFFFB36B),
    0.34f to Color(0xFFD45AFF),
    0.60f to Color(0xFF8B55FF),
    0.85f to Color(0xFF625AFF),
    1.00f to Color(0xFF6A5BFF),
)

/**
 * Экран запуска: медальон прибора, который просыпается.
 *
 * Сцена целиком нарисована Compose по таймлайну [SplashTimeline] и не
 * воспроизводит медиа: нет ни проигрывателя, ни декодера, поэтому заставка не
 * звучит, не переключает аудиоисточник магнитолы и завершается в одно и то же
 * время на любом устройстве. Длительность и фазы анимации задаются данными, а не
 * скоростью отрисовки: анимация не может оборваться на середине.
 *
 * Рисуется оверлеем поверх уже скомпонованного главного экрана, поэтому не
 * задерживает ни службу сбора, ни данные.
 */
@Composable
fun LaunchSplash(onFinished: () -> Unit) {
    val background = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val onFinishedState = rememberUpdatedState(onFinished)

    val progress = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var closing by remember { mutableStateOf(false) }

    fun close(skipped: Boolean) {
        if (closing) return
        closing = true
        scope.launch {
            fade.animateTo(
                0f,
                tween(
                    durationMillis = if (skipped) {
                        SplashTimeline.SKIP_FADE_MS
                    } else {
                        SplashTimeline.FADE_OUT_MS
                    },
                ),
            )
            onFinishedState.value()
        }
    }

    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            tween(durationMillis = SplashTimeline.ANIMATION_MS, easing = LinearEasing),
        )
        if (!closing) {
            delay(SplashTimeline.HOLD_MS)
            close(skipped = false)
        }
    }

    LaunchedEffect(Unit) {
        // Страховка на случай зависшей корутины: позже конца таймлайна,
        // поэтому оборвать анимацию не может.
        delay(SplashTimeline.WATCHDOG_MS)
        close(skipped = true)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(background)
            .pointerInput(Unit) {
                detectTapGestures { close(skipped = true) }
            },
    ) {
        val side = minOf(maxWidth, maxHeight) * MEDALLION_FRACTION

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(side)
                .graphicsLayer {
                    val frame = SplashTimeline.frame(progress.value)
                    alpha = frame.alpha
                    scaleX = frame.scale
                    scaleY = frame.scale
                },
        ) {
            Image(
                painter = painterResource(R.drawable.splash_art),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val frame = SplashTimeline.frame(progress.value)
                val sidePx = size.minDimension
                drawSweepArc(frame, sidePx)
                drawWaveGlint(frame, sidePx)
            }
        }

        // Круглая маска и ореол: арт приходит с запечённым фоном, поэтому углы
        // закрываются цветом фона так же, как в прежней заставке.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawMedallionMask(
                sidePx = side.toPx(),
                background = background,
                accent = accent,
                glow = SplashTimeline.frame(progress.value).glow,
            )
        }
    }
}

/** Дуга, обегающая обод медальона от 12 часов по часовой стрелке. */
private fun DrawScope.drawSweepArc(frame: SplashFrame, sidePx: Float) {
    if (frame.arc <= 0f) return
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = sidePx * ARC_RADIUS_FRACTION
    val stroke = sidePx * ARC_STROKE_FRACTION
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2f, radius * 2f)
    val brush = Brush.sweepGradient(*ARC_COLORS)
    val sweepAngle = 360f * frame.arc

    // Блюра на API 29 нет: свечение — три прохода одной геометрии.
    rotate(degrees = -90f, pivot = center) {
        drawArc(
            brush = brush,
            startAngle = 0f,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            alpha = 0.10f,
            style = Stroke(width = stroke * 2.6f, cap = StrokeCap.Round),
        )
        drawArc(
            brush = brush,
            startAngle = 0f,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            alpha = 0.26f,
            style = Stroke(width = stroke * 1.7f, cap = StrokeCap.Round),
        )
        drawArc(
            brush = brush,
            startAngle = 0f,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** Волна, которая прорисовывается слева направо поверх запечённой в арте. */
private fun DrawScope.drawWaveGlint(frame: SplashFrame, sidePx: Float) {
    if (frame.glint <= 0f) return
    val left = size.width * GLINT_LEFT
    val top = size.height * GLINT_TOP
    val width = size.width * GLINT_WIDTH
    val height = size.height * GLINT_HEIGHT
    val path = wavePath(left, top, width, height)
    val reveal = width * frame.glint
    val alpha = glintAlpha(frame.glint)

    clipRect(
        left = left - 2f,
        top = top - 2f,
        right = left + reveal + 2f,
        bottom = top + height + 2f,
    ) {
        drawPath(
            path = path,
            color = Color(0xFFFF8CF0),
            alpha = alpha * 0.30f,
            style = Stroke(width = sidePx * 0.007f, cap = StrokeCap.Round),
        )
        drawPath(
            path = path,
            color = Color(0xFFFFB8F8),
            alpha = alpha * 0.70f,
            style = Stroke(
                width = sidePx * 0.0035f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        drawPath(
            path = path,
            color = Color(0xFFFFE1FF),
            alpha = alpha,
            style = Stroke(
                width = sidePx * 0.0016f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/**
 * Яркость глинта: вспыхивает быстро и гаснет медленнее, как в исходной
 * композиции (пик на четверти прорисовки). На конце волны яркость нулевая.
 */
private fun glintAlpha(reveal: Float): Float = if (reveal < 0.25f) {
    0.85f * (reveal / 0.25f)
} else {
    0.85f * (1f - (reveal - 0.25f) / 0.75f)
}

/** Тот же контур волны, что в Html-композиции, растянутый на окно глинта. */
private fun wavePath(left: Float, top: Float, width: Float, height: Float): Path {
    fun x(value: Float): Float = left + width * (value / 360f)
    fun y(value: Float): Float = top + height * (value / 90f)

    return Path().apply {
        moveTo(x(2f), y(45f))
        cubicTo(x(35f), y(45f), x(37f), y(22f), x(65f), y(24f))
        cubicTo(x(93f), y(26f), x(97f), y(71f), x(126f), y(44f))
        cubicTo(x(155f), y(17f), x(155f), y(4f), x(180f), y(44f))
        cubicTo(x(205f), y(84f), x(214f), y(78f), x(240f), y(48f))
        cubicTo(x(266f), y(18f), x(276f), y(24f), x(300f), y(44f))
        cubicTo(x(324f), y(64f), x(333f), y(48f), x(358f), y(45f))
    }
}

/** Заливка углов вне диска и мягкий ореол вокруг него. */
private fun DrawScope.drawMedallionMask(
    sidePx: Float,
    background: Color,
    accent: Color,
    glow: Float,
) {
    val radius = sidePx / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val disc = Path().apply { addOval(Rect(center = center, radius = radius)) }
    val haloRadius = radius * 1.6f

    clipPath(disc, clipOp = ClipOp.Difference) {
        drawRect(color = background)
        if (glow > 0f) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.63f to accent.copy(alpha = 0.12f * glow),
                    1f to Color.Transparent,
                    center = center,
                    radius = haloRadius,
                ),
                radius = haloRadius,
                center = center,
            )
        }
    }
}
