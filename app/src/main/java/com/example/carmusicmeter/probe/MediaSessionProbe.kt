package com.example.carmusicmeter.probe

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import com.example.carmusicmeter.data.MediaPhase
import com.example.carmusicmeter.data.MediaSnapshot
import com.example.carmusicmeter.service.NotificationWatchService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Чтение активных MediaSession. Работает только когда пользователь выдал
 * приложению доступ к уведомлениям — это единственный публичный способ
 * увидеть, что играет в чужом плеере.
 */
class MediaSessionProbe(private val context: Context) {

    data class CaptureResult(
        val snapshots: List<MediaSnapshot>,
        val error: String?,
    )

    private val manager: MediaSessionManager? =
        context.getSystemService(MediaSessionManager::class.java)

    private val listenerComponent = ComponentName(context, NotificationWatchService::class.java)

    @Volatile
    private var accessCheckedAt = 0L

    @Volatile
    private var accessGranted = false

    fun hasNotificationAccess(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (accessCheckedAt > 0 && now - accessCheckedAt < ACCESS_CACHE_MS) {
            return accessGranted
        }
        val granted = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        accessGranted = granted
        accessCheckedAt = now
        return granted
    }

    fun capture(): CaptureResult {
        if (!hasNotificationAccess()) {
            return CaptureResult(emptyList(), "notification_access_missing")
        }
        val sessionManager = manager
            ?: return CaptureResult(emptyList(), "media_session_manager_missing")
        return try {
            val snapshots = sessionManager.getActiveSessions(listenerComponent).map { it.toSnapshot() }
            CaptureResult(snapshots, null)
        } catch (e: SecurityException) {
            CaptureResult(emptyList(), "security: ${e.message}")
        } catch (e: Exception) {
            CaptureResult(emptyList(), "error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Полный дамп всех сессий: все ключи метаданных, состояние, actions.
     * Это главный артефакт пробника — по нему видно, что отдаёт штатный плеер.
     */
    fun sessionsJson(): JSONArray {
        val sessionManager = manager
            ?: return JSONArray().put(JSONObject().put("error", "media_session_manager_missing"))
        if (!hasNotificationAccess()) {
            return JSONArray().put(JSONObject().put("error", "notification_access_missing"))
        }
        return try {
            JSONArray().apply {
                sessionManager.getActiveSessions(listenerComponent).forEach { controller ->
                    put(sessionJson(controller))
                }
            }
        } catch (e: Exception) {
            JSONArray().put(
                JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}"),
            )
        }
    }

    private fun MediaController.toSnapshot(): MediaSnapshot {
        val metadata = metadata
        val playback = playbackState
        val title = metadata.string(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.description?.title?.toString()
        val artist = metadata.string(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.string(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.description?.subtitle?.toString()

        return MediaSnapshot(
            packageName = packageName,
            sessionTag = tagOrNull(),
            title = title?.takeIf { it.isNotBlank() },
            artist = artist?.takeIf { it.isNotBlank() },
            album = metadata.string(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata.long(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = playback?.position?.takeIf { it >= 0 },
            phase = playback.phase(),
            speed = playback?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            actions = playback?.actions ?: 0L,
            mediaId = metadata.string(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadataKeys = metadata?.keySet()?.sorted().orEmpty(),
            hasArtwork = metadata?.containsKey(MediaMetadata.METADATA_KEY_ART) == true ||
                metadata?.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ART) == true ||
                metadata?.containsKey(MediaMetadata.METADATA_KEY_DISPLAY_ICON) == true,
        )
    }

    private fun PlaybackState?.phase(): MediaPhase = when (this?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        -> MediaPhase.PLAYING

        PlaybackState.STATE_PAUSED -> MediaPhase.PAUSED
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_NONE,
        PlaybackState.STATE_ERROR,
        -> MediaPhase.STOPPED

        else -> MediaPhase.UNKNOWN
    }

    private fun sessionJson(controller: MediaController): JSONObject {
        val metadata = controller.metadata
        val playback = controller.playbackState
        val metadataJson = JSONObject()
        metadata?.keySet()?.sorted()?.forEach { key ->
            metadataJson.put(key, metadataValue(metadata, key))
        }
        return JSONObject().apply {
            put("package", controller.packageName)
            put("tag", controller.tagOrNull())
            put("metadata", metadataJson)
            put("playback", JSONObject().apply {
                put("state", PlaybackNames.state(playback?.state))
                put("stateCode", playback?.state ?: -1)
                put("positionMs", playback?.position ?: -1L)
                put("speed", playback?.playbackSpeed ?: 0f)
                put("actions", JSONArray(PlaybackNames.actions(playback?.actions ?: 0L)))
                put("error", playback?.errorMessage?.toString())
                put("lastPositionUpdateTime", playback?.lastPositionUpdateTime ?: 0L)
            })
        }
    }

    private fun metadataValue(metadata: MediaMetadata, key: String): Any = when {
        key in BITMAP_KEYS -> if (metadata.containsKey(key)) "present" else JSONObject.NULL
        key in LONG_KEYS -> runCatching { metadata.getLong(key) }.getOrDefault(0L)
        else -> runCatching { metadata.getString(key) }.getOrNull() ?: "present"
    }

    // getTag() появился только в Android 11; на магнитоле Android 10 тега нет.
    private fun MediaController.tagOrNull(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tag else null

    private fun MediaMetadata?.string(key: String): String? =
        this?.let { runCatching { it.getString(key) }.getOrNull() }

    private fun MediaMetadata?.long(key: String): Long? =
        this?.let { runCatching { it.getLong(key) }.getOrNull() }?.takeIf { it > 0L }

    private companion object {
        const val ACCESS_CACHE_MS = 10_000L

        val BITMAP_KEYS = setOf(
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )
        val LONG_KEYS = setOf(
            MediaMetadata.METADATA_KEY_DURATION,
            MediaMetadata.METADATA_KEY_YEAR,
            MediaMetadata.METADATA_KEY_TRACK_NUMBER,
            MediaMetadata.METADATA_KEY_NUM_TRACKS,
            MediaMetadata.METADATA_KEY_DISC_NUMBER,
        )
    }
}
