package com.example.carmusicmeter.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import com.example.carmusicmeter.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Доля меньшей стороны экрана, которую занимает медальон. */
private const val MEDALLION_FRACTION = 0.6f

/**
 * Лёгкий зум видео внутри круга: выталкивает угловой вотермарк исходного клипа
 * за границу маски.
 */
private const val MEDALLION_ZOOM = 1.08f

/** Сколько последний кадр держится перед уходом в пульт. */
private const val HOLD_LAST_FRAME_MS = 350L

/** Предел на всю заставку, если декодер не прислал завершение. */
private const val WATCHDOG_MS = 1_800L

private const val FADE_IN_MS = 180
private const val FADE_OUT_MS = 250
private const val SKIP_FADE_MS = 120

/**
 * Экран запуска: круглый медальон с видео-анимацией прибора.
 *
 * Рисуется поверх уже скомпонованного главного экрана, поэтому не задерживает
 * ни службу сбора, ни данные. Квадрат видео скрывается заливкой углов фоном,
 * а не клипом видео-слоя: `TextureView` надёжнее клипать заливкой на любом API.
 * Пока декодер не отдал кадр, под ним виден статичный кадр клипа.
 *
 * `MediaPlayer` создаётся и готовится на отдельном handler-thread: его
 * конструктор обращается к медиасервису и на слабых прошивках может блокировать
 * вызывающий поток на секунды.
 */
@Composable
fun LaunchSplash(onFinished: () -> Unit) {
    val context = LocalContext.current
    val background = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val onFinishedState = rememberUpdatedState(onFinished)

    val fade = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var playbackFinished by remember { mutableStateOf(false) }
    var playbackFailed by remember { mutableStateOf(false) }

    val playerState = remember { mutableStateOf<MediaPlayer?>(null) }
    val playerHandler = remember { mutableStateOf<Handler?>(null) }
    var videoSurface by remember { mutableStateOf<Surface?>(null) }

    fun close(skipped: Boolean) {
        if (closing) return
        closing = true
        scope.launch {
            fade.animateTo(0f, tween(durationMillis = if (skipped) SKIP_FADE_MS else FADE_OUT_MS))
            onFinishedState.value()
        }
    }

    DisposableEffect(Unit) {
        val thread = HandlerThread("splash-player").apply { start() }
        val handler = Handler(thread.looper)
        playerHandler.value = handler
        handler.post {
            runCatching {
                MediaPlayer().apply {
                    setOnPreparedListener { prepared -> prepared.start() }
                    setOnCompletionListener { playbackFinished = true }
                    setOnErrorListener { _, _, _ ->
                        playbackFailed = true
                        true
                    }
                }
            }
                .onSuccess { playerState.value = it }
                .onFailure { playbackFailed = true }
        }
        onDispose {
            handler.post {
                playerState.value?.let { player -> runCatching { player.release() } }
                playerState.value = null
            }
            thread.quitSafely()
            runCatching { videoSurface?.release() }
            videoSurface = null
        }
    }

    LaunchedEffect(Unit) {
        fade.animateTo(1f, tween(FADE_IN_MS))
    }

    LaunchedEffect(Unit) {
        delay(WATCHDOG_MS)
        if (!playbackFinished) {
            close(skipped = true)
        }
    }

    LaunchedEffect(playbackFinished) {
        if (playbackFinished) {
            delay(HOLD_LAST_FRAME_MS)
            close(skipped = false)
        }
    }

    LaunchedEffect(playbackFailed) {
        // Кадр не пришёл — показываем статичную подложку и всё равно уходим в пульт.
        if (playbackFailed) {
            delay(HOLD_LAST_FRAME_MS)
            close(skipped = true)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .alpha(fade.value)
            .pointerInput(Unit) {
                detectTapGestures { close(skipped = true) }
            },
    ) {
        val side = minOf(maxWidth, maxHeight) * MEDALLION_FRACTION

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(side)
                .clipToBounds(),
        ) {
            Image(
                painter = painterResource(R.drawable.splash_still),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(MEDALLION_ZOOM),
                contentScale = ContentScale.Fit,
            )

            playerState.value?.let { player ->
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        TextureView(viewContext).apply {
                            scaleX = MEDALLION_ZOOM
                            scaleY = MEDALLION_ZOOM
                            surfaceTextureListener =
                                object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(
                                        texture: SurfaceTexture,
                                        width: Int,
                                        height: Int,
                                    ) {
                                        val surface = Surface(texture)
                                        videoSurface = surface
                                        playerHandler.value?.post {
                                            runCatching {
                                                player.setSurface(surface)
                                                viewContext.resources
                                                    .openRawResourceFd(R.raw.splash)
                                                    .use { afd ->
                                                        player.setDataSource(
                                                            afd.fileDescriptor,
                                                            afd.startOffset,
                                                            afd.length,
                                                        )
                                                    }
                                                player.prepareAsync()
                                            }.onFailure { playbackFailed = true }
                                        }
                                    }
                                    override fun onSurfaceTextureSizeChanged(
                                        texture: SurfaceTexture,
                                        width: Int,
                                        height: Int,
                                    ) = Unit

                                    override fun onSurfaceTextureDestroyed(
                                        texture: SurfaceTexture,
                                    ): Boolean {
                                        playerHandler.value?.post {
                                            runCatching { player.setSurface(null) }
                                        }
                                        runCatching { videoSurface?.release() }
                                        videoSurface = null
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(
                                        texture: SurfaceTexture,
                                    ) = Unit
                                }
                        }
                    },
                    update = { view -> view.alpha = fade.value },
                )
            }
        }

        // Углы квадратного видео и зелёный ореол вокруг диска.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = side.toPx() / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val disc = Path().apply {
                addOval(
                    Rect(
                        offset = center - Offset(radius, radius),
                        size = Size(radius * 2f, radius * 2f),
                    ),
                )
            }
            val haloRadius = radius * 1.6f
            clipPath(disc, clipOp = ClipOp.Difference) {
                drawRect(color = background)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.Transparent,
                        0.63f to accent.copy(alpha = 0.10f),
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
}
