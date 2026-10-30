package com.example.carmusicmeter.diagnostics

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.example.carmusicmeter.BuildConfig
import com.example.carmusicmeter.CarMusicMeterApp
import com.example.carmusicmeter.data.Json
import com.example.carmusicmeter.data.ListeningRepository
import com.example.carmusicmeter.data.LiveTrack
import com.example.carmusicmeter.probe.MediaSessionProbe
import com.example.carmusicmeter.probe.NotificationCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Снимок «всё, что видно прямо сейчас»: окружение, громкость, выходы звука,
 * все активные сессии со всеми ключами метаданных и последние уведомления.
 *
 * Это главный диагностический артефакт для проверки в машине: если штатный
 * плеер не отдаёт MediaSession, снимок покажет, что именно он отдаёт.
 */
class SnapshotWriter(
    private val context: Context,
    private val probe: MediaSessionProbe,
) {

    fun capture(
        reason: String,
        sessions: JSONArray? = null,
        live: LiveTrack? = null,
    ): File {
        val json = buildJson(reason, sessions, live)
        return (context.applicationContext as CarMusicMeterApp).eventLog.writeSnapshot(json, reason)
    }

    fun buildJson(
        reason: String,
        sessions: JSONArray?,
        live: LiveTrack?,
    ): JSONObject {
        val audio = context.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

        return JSONObject().apply {
            put("capturedAt", System.currentTimeMillis())
            put("reason", reason)
            put("app", JSONObject().apply {
                put("package", context.packageName)
                put("versionName", packageInfo.versionName)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("buildDate", BuildConfig.BUILD_DATE)
            })
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("brand", Build.BRAND)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("android", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
            })
            put("notificationAccess", probe.hasNotificationAccess())
            put("serviceRunning", ListeningRepository.state.value.serviceRunning)
            put("volume", JSONObject().apply {
                put("music", volume)
                put("max", volumeMax)
            })
            put("audioOutputs", audioOutputs(audio))
            put("live", live?.toJson() ?: JSONObject.NULL)
            put("sessions", sessions ?: probe.sessionsJson())
            put("notifications", NotificationCache.toJson())
            put("logFiles", logFiles())
        }
    }

    private fun audioOutputs(audio: AudioManager): JSONArray = JSONArray().apply {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { device ->
            put(JSONObject().apply {
                put("type", device.type)
                put("typeName", outputTypeName(device.type))
                put("productName", Json.toJsonValue(device.productName?.toString()))
                put("address", Json.toJsonValue(device.address))
                put("isSink", device.isSink)
            })
        }
    }

    private fun logFiles(): JSONArray = JSONArray().apply {
        val log = (context.applicationContext as CarMusicMeterApp).eventLog
        log.logsDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            put(JSONObject().apply {
                put("name", file.name)
                put("size", file.length())
                put("modified", file.lastModified())
            })
        }
    }

    private fun outputTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "builtin_earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb_accessory"
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_DOCK -> "dock"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "line_analog"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line_digital"
        else -> "type_$type"
    }

    private fun LiveTrack.toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("package", packageName)
        put("title", Json.toJsonValue(title))
        put("artist", Json.toJsonValue(artist))
        put("album", Json.toJsonValue(album))
        put("durationMs", Json.toJsonValue(durationMs))
        put("positionMs", Json.toJsonValue(positionMs))
        put("phase", phase.name.lowercase())
        put("volume", volume)
        put("volumeMax", volumeMax)
        put("startedAt", startedAtMs)
        put("playedMs", playedMs)
        put("mediaId", Json.toJsonValue(mediaId))
        put("metadataKeys", JSONArray(metadataKeys))
    }
}
