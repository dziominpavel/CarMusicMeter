package com.example.tunemileage.probe

import android.app.Notification
import android.media.MediaMetadata
import android.media.session.PlaybackState

/** Человекочитаемые имена состояний и actions для снимка. */
object PlaybackNames {

    fun state(state: Int?): String = when (state) {
        PlaybackState.STATE_NONE, null -> "none"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_FAST_FORWARDING -> "fast_forwarding"
        PlaybackState.STATE_REWINDING -> "rewinding"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skipping_to_previous"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "skipping_to_next"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "skipping_to_queue_item"
        else -> "unknown($state)"
    }

    fun actions(mask: Long): List<String> {
        val names = mutableListOf<String>()
        ACTIONS.forEach { (action, name) ->
            if (mask and action != 0L) names += name
        }
        return names
    }

    fun notificationCategory(category: String?): String = category ?: "none"

    fun metadataKeyName(key: String): String = when (key) {
        MediaMetadata.METADATA_KEY_TITLE -> "TITLE"
        MediaMetadata.METADATA_KEY_ARTIST -> "ARTIST"
        MediaMetadata.METADATA_KEY_ALBUM -> "ALBUM"
        MediaMetadata.METADATA_KEY_ALBUM_ARTIST -> "ALBUM_ARTIST"
        MediaMetadata.METADATA_KEY_DURATION -> "DURATION"
        MediaMetadata.METADATA_KEY_MEDIA_ID -> "MEDIA_ID"
        MediaMetadata.METADATA_KEY_TRACK_NUMBER -> "TRACK_NUMBER"
        MediaMetadata.METADATA_KEY_NUM_TRACKS -> "NUM_TRACKS"
        else -> key
    }

    val notificationTemplateKey: String = Notification.EXTRA_TEMPLATE

    private val ACTIONS = linkedMapOf(
        PlaybackState.ACTION_PLAY to "PLAY",
        PlaybackState.ACTION_PAUSE to "PAUSE",
        PlaybackState.ACTION_PLAY_PAUSE to "PLAY_PAUSE",
        PlaybackState.ACTION_STOP to "STOP",
        PlaybackState.ACTION_SKIP_TO_NEXT to "SKIP_TO_NEXT",
        PlaybackState.ACTION_SKIP_TO_PREVIOUS to "SKIP_TO_PREVIOUS",
        PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to "SKIP_TO_QUEUE_ITEM",
        PlaybackState.ACTION_SEEK_TO to "SEEK_TO",
        PlaybackState.ACTION_FAST_FORWARD to "FAST_FORWARD",
        PlaybackState.ACTION_REWIND to "REWIND",
        PlaybackState.ACTION_SET_RATING to "SET_RATING",
        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "PLAY_FROM_MEDIA_ID",
        PlaybackState.ACTION_PLAY_FROM_SEARCH to "PLAY_FROM_SEARCH",
        PlaybackState.ACTION_PLAY_FROM_URI to "PLAY_FROM_URI",
        PlaybackState.ACTION_PREPARE to "PREPARE",
        PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID to "PREPARE_FROM_MEDIA_ID",
        PlaybackState.ACTION_PREPARE_FROM_SEARCH to "PREPARE_FROM_SEARCH",
        PlaybackState.ACTION_PREPARE_FROM_URI to "PREPARE_FROM_URI",
    )
}
